package com.niandui.idectl.session

import com.niandui.idectl.settings.IdectlSettings
import com.niandui.idectl.settings.TokenEntry
import java.security.SecureRandom

/**
 * Token store (M3). The primary token in [IdectlSettings.token] is always an admin over all projects
 * (unchanged from M0). On top of it, any number of additional scoped tokens can be issued, each
 * carrying its own role + allowed-projects set, so different agents get different privileges.
 */
class TokenStore(private val settings: IdectlSettings) {

    /** Ensure the primary admin token exists; generate and persist one on first run. */
    fun ensureToken(): String {
        // Test/dev override: -Didea.bridge.token=<value> forces a known token (used by smoke tests).
        val override = System.getProperty("idea.bridge.token")
        if (!override.isNullOrBlank()) {
            settings.token = override
            return override
        }
        val existing = settings.token
        if (!existing.isNullOrBlank()) return existing
        val generated = generate()
        settings.token = generated
        return generated
    }

    fun currentToken(): String? = settings.token

    /** Force a brand-new primary token. The old primary stops validating immediately. */
    fun regenerate(): String {
        val generated = generate()
        settings.token = generated
        return generated
    }

    /** Validate a presented Bearer token → Principal, or null if it matches nothing. */
    fun validate(bearer: String?): Principal? {
        if (bearer.isNullOrEmpty()) return null
        val primary = settings.token
        if (!primary.isNullOrEmpty() && constantTimeEquals(bearer, primary)) {
            return Principal(subject = "admin", role = Role.ADMIN, allowedProjects = null)
        }
        for (entry in settings.tokens) {
            val secret = entry.token ?: continue
            if (constantTimeEquals(bearer, secret)) {
                val projects = entry.projects.filter { it.isNotBlank() }.toSet()
                return Principal(
                    subject = entry.subject?.ifBlank { null } ?: "token",
                    role = Role.from(entry.role),
                    allowedProjects = projects.ifEmpty { null },
                )
            }
        }
        return null
    }

    // ---- scoped-token management (M3) ----

    /** Issue a new scoped token. Returns the persisted entry (its `token` is the secret to hand out). */
    fun createToken(subject: String, role: Role, projects: List<String>): TokenEntry {
        val entry = TokenEntry()
        entry.token = generate()
        entry.subject = subject
        entry.role = role.name.lowercase()
        entry.projects = projects.filter { it.isNotBlank() }.toMutableList()
        settings.tokens.add(entry)
        return entry
    }

    fun listTokens(): List<TokenEntry> = settings.tokens.toList()

    /** Revoke every scoped token matching [match] by exact secret, secret prefix, or subject. Returns the count removed. */
    fun revokeToken(match: String): Int {
        val before = settings.tokens.size
        settings.tokens.removeAll { e ->
            e.subject == match || e.token == match || (match.length >= 6 && e.token?.startsWith(match) == true)
        }
        return before - settings.tokens.size
    }

    private fun generate(): String = Companion.generate()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        if (ab.size != bb.size) return false
        var result = 0
        for (i in ab.indices) result = result or (ab[i].toInt() xor bb[i].toInt())
        return result == 0
    }

    companion object {
        /** Generate a fresh `ib_`-prefixed Bearer secret (192 bits, URL-safe). Shared with the settings UI. */
        fun generate(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return "ib_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
