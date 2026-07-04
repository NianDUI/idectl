package com.niandui.idectl

import com.intellij.ide.AppLifecycleListener

/** Starts the MCP server at IDE startup, so it is reachable even on the welcome screen (no project). */
class IdectlAppListener : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        IdectlService.getInstance().ensureStarted()
    }
}
