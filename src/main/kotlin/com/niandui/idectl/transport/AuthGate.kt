package com.niandui.idectl.transport

import com.niandui.idectl.session.Principal
import com.niandui.idectl.session.TokenStore

/** L2 front door: Host/Origin anti-DNS-rebinding (403) → Bearer auth (401) → Principal. */
class AuthGate(private val tokenStore: TokenStore) {

    sealed interface Result {
        data class Ok(val principal: Principal) : Result
        data class Rejected(val status: Int, val reason: String) : Result
    }

    fun check(host: String?, origin: String?, authorization: String?): Result {
        if (!hostAllowed(host)) return Result.Rejected(403, "host not allowed: $host")
        if (!originAllowed(origin)) return Result.Rejected(403, "origin not allowed: $origin")
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

    private fun hostAllowed(host: String?): Boolean {
        if (host == null) return true // Ktor localhost binding already restricts reachability
        return isLocalHostName(host.substringBefore(':').trim().removeSurrounding("[", "]"))
    }

    private fun originAllowed(origin: String?): Boolean {
        if (origin.isNullOrBlank() || origin == "null") return true
        val hostPart = origin
            .substringAfter("://", origin)
            .substringBefore('/')
            .substringBeforeLast(':')
            .removeSurrounding("[", "]")
        return isLocalHostName(hostPart)
    }

    private fun isLocalHostName(h: String): Boolean =
        h == "127.0.0.1" || h.equals("localhost", ignoreCase = true) || h == "::1" || h == "0:0:0:0:0:0:0:1"
}
