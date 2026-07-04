package com.niandui.idectl

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

/** Global constants and small helpers shared across layers. */
object Idectl {
    const val PLUGIN_ID = "com.niandui.idectl"

    /** L1 transport: first port tried; increments until free (avoids built-in MCP 64342±240). D3. */
    const val PORT_BASE = 48620
    const val PORT_SCAN_LIMIT = 64

    /** MCP protocol we advertise, plus versions we accept on down-negotiation. D2. */
    const val PROTOCOL_VERSION = "2025-11-25"
    val SUPPORTED_PROTOCOLS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

    const val SERVER_NAME = "idectl"

    fun pluginVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "dev"
}
