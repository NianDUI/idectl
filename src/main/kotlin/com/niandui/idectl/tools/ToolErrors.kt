package com.niandui.idectl.tools

/** Structured tool-execution error codes (03 §1.7). Surfaced as isError results, not JSON-RPC errors. */
object ErrorCodes {
    const val PERMISSION_DENIED = "PERMISSION_DENIED"
    const val NOT_FOUND = "NOT_FOUND"
    const val PROJECT_NOT_BOUND = "PROJECT_NOT_BOUND"
    const val CONFLICT = "CONFLICT"
    const val BUSY = "BUSY"
    const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
    const val TIMEOUT = "TIMEOUT"
    const val NOT_SUSPENDED = "NOT_SUSPENDED"
    const val NOT_DEBUG_SESSION = "NOT_DEBUG_SESSION"
    const val UNAVAILABLE = "UNAVAILABLE"
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val INTERNAL = "INTERNAL"
}

/**
 * A recoverable tool-level failure. The gate turns this into a structured isError result
 * `{code, message, remediation}` so the Agent can decide its next step (never a transport error).
 */
class ToolException(
    val code: String,
    override val message: String,
    val remediation: String? = null,
) : RuntimeException(message)
