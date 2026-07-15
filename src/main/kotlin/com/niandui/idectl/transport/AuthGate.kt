package com.niandui.idectl.transport

import com.niandui.idectl.session.Principal
import com.niandui.idectl.session.TokenStore

/**
 * L2 front door: Host/Origin anti-DNS-rebinding (403) → Bearer auth (401) → Principal.
 *
 * By default only loopback Host/Origin pass. When [allowLan] is on (the "allow LAN" setting), the
 * fence is widened to private-range LAN peers as well — the Bearer token remains the real gate.
 */
class AuthGate(private val tokenStore: TokenStore, private val allowLan: () -> Boolean = { false }) {

    sealed interface Result {
        data class Ok(val principal: Principal) : Result
        data class Rejected(val status: Int, val reason: String) : Result
    }

    fun check(host: String?, origin: String?, authorization: String?): Result {
        val lan = allowLan()
        if (!hostAllowed(host, lan)) return Result.Rejected(403, "host not allowed: $host")
        if (!originAllowed(origin, lan)) return Result.Rejected(403, "origin not allowed: $origin")
        val bearer = extractBearer(authorization)
        val principal = tokenStore.validate(bearer)
            ?: return Result.Rejected(401, "missing or invalid Bearer token")
        return Result.Ok(principal)
    }

    private fun extractBearer(authorization: String?): String? {
        if (authorization == null) return null
        val prefix = "Bearer "
        return if (authorization.startsWith(prefix, ignoreCase = true)) authorization.substring(prefix.length).trim()
        else null
    }

    private fun hostAllowed(host: String?, allowLan: Boolean): Boolean {
        if (host == null) return true // Ktor localhost binding already restricts reachability
        val h = host.substringBefore(':').trim().removeSurrounding("[", "]")
        return isLocalHostName(h) || (allowLan && isLanHost(h))
    }

    private fun originAllowed(origin: String?, allowLan: Boolean): Boolean {
        if (origin.isNullOrBlank() || origin == "null") return true
        val hostPart = origin
            .substringAfter("://", origin)
            .substringBefore('/')
            .substringBeforeLast(':')
            .removeSurrounding("[", "]")
        return isLocalHostName(hostPart) || (allowLan && isLanHost(hostPart))
    }

    private fun isLocalHostName(h: String): Boolean =
        h == "127.0.0.1" || h.equals("localhost", ignoreCase = true) || h == "::1" || h == "0:0:0:0:0:0:0:1"

    /**
     * Private-range LAN addresses accepted when the "allow LAN" setting is on:
     * IPv4 10/8, 172.16/12, 192.168/16, link-local 169.254/16; IPv6 ULA fc00::/7 and link-local fe80::/10.
     * Non-IP hostnames are NOT matched here — a peer connects by its LAN IP, and refusing arbitrary
     * hostnames keeps the anti-DNS-rebinding property intact even with LAN enabled.
     */
    private fun isLanHost(h: String): Boolean {
        val octets = h.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
            val (a, b) = octets[0].toInt() to octets[1].toInt()
            return when (a) {
                10 -> true
                172 -> b in 16..31
                192 -> b == 168
                169 -> b == 254
                else -> false
            }
        }
        val lower = h.lowercase()
        return lower.startsWith("fe8") || lower.startsWith("fe9") || lower.startsWith("fea") ||
            lower.startsWith("feb") || lower.startsWith("fc") || lower.startsWith("fd")
    }
}
