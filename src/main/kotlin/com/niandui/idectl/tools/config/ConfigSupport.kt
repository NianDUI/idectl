package com.niandui.idectl.tools.config

import com.google.gson.JsonObject
import com.intellij.execution.CommonJavaRunConfigurationParameters
import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.configurations.RunConfiguration

/** Shared helpers for the run-configuration editing tools. */
object ConfigSupport {

    /** Flatten an `env` JSON object into a String→String map (non-string values are stringified). */
    fun parseEnv(env: JsonObject?): Map<String, String> {
        if (env == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in env.entrySet()) {
            if (!v.isJsonNull) out[k] = if (v.isJsonPrimitive) v.asString else v.toString()
        }
        return out
    }

    /**
     * Apply the provided common parameters to a run configuration. VM options require a Java config
     * ([CommonJavaRunConfigurationParameters]); the rest work on any [CommonProgramRunConfigurationParameters].
     * Returns the list of fields that were actually applied. Only non-null args are touched.
     */
    fun applyCommon(
        config: RunConfiguration,
        programArgs: String?,
        vmOptions: String?,
        env: Map<String, String>?,
        workingDir: String?,
    ): List<String> {
        val applied = mutableListOf<String>()
        val common = config as? CommonProgramRunConfigurationParameters
        if (common != null) {
            programArgs?.let { common.programParameters = it; applied += "program_args" }
            env?.let { common.envs = HashMap(it); applied += "env" }
            workingDir?.let { common.workingDirectory = it; applied += "working_dir" }
        }
        if (vmOptions != null) {
            (config as? CommonJavaRunConfigurationParameters)?.let {
                it.vmParameters = vmOptions
                applied += "vm_options"
            }
        }
        return applied
    }

    fun supportsCommonParams(config: RunConfiguration): Boolean =
        config is CommonProgramRunConfigurationParameters
}
