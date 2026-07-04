package com.niandui.idectl.core.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Make external, on-disk edits visible to the IDE before we compile/hot-swap.
 *
 * When an AI agent edits source files with its own file tools, the IDE's VFS doesn't know they
 * changed, so JPS treats them as up-to-date and build/reload_classes silently do nothing
 * (compile "ok", outcome "nothing_to_reload"). A synchronous VFS refresh fixes that — the same
 * thing the IDE does on window focus. Runs off-EDT; whole-project refresh (targeted refresh is a
 * follow-up optimization for very large projects).
 */
suspend fun refreshProjectVfs(project: Project) {
    val basePath = project.basePath ?: return
    withContext(Dispatchers.Default) {
        val root = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return@withContext
        VfsUtil.markDirtyAndRefresh(false, true, true, root)
    }
}

/**
 * Refresh specific paths (absolute, or relative to [base]) — used after external git/file changes so
 * the IDE picks them up before build/reload. Returns how many resolved. Runs off-EDT.
 */
suspend fun refreshPaths(base: String?, paths: List<String>): Int = withContext(Dispatchers.Default) {
    val lfs = LocalFileSystem.getInstance()
    val files = paths.mapNotNull { p ->
        val abs = if (p.startsWith("/") || base == null) p else "$base/$p"
        lfs.refreshAndFindFileByPath(abs)
    }
    if (files.isNotEmpty()) VfsUtil.markDirtyAndRefresh(false, true, true, *files.toTypedArray())
    files.size
}
