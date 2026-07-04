package com.niandui.idectl.session

/** Three-tier RBAC (D8). Ordinal order encodes the subset relation viewer ⊂ operator ⊂ admin. */
enum class Role {
    VIEWER, OPERATOR, ADMIN;

    /** true if this role satisfies (is at least) the required role. */
    fun satisfies(required: Role): Boolean = ordinal >= required.ordinal

    companion object {
        fun from(value: String?): Role = when (value?.trim()?.lowercase()) {
            "viewer" -> VIEWER
            "operator" -> OPERATOR
            "admin" -> ADMIN
            else -> VIEWER
        }
    }
}
