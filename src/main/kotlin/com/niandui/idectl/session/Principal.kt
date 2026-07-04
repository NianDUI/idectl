package com.niandui.idectl.session

/**
 * The authenticated caller behind a Bearer token (D8).
 *
 * @param allowedProjects set of project paths this token may touch; `null` means "all projects".
 *        Projects outside this set are invisible in every listing.
 */
data class Principal(
    val subject: String,
    val role: Role,
    val allowedProjects: Set<String>?,
) {
    fun mayAccessProject(path: String): Boolean =
        allowedProjects == null || path in allowedProjects
}
