package com.niandui.idectl.discovery

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * `~/.idectl/instances.json` — the offline instance-discovery registry (D3).
 * Each running IDE keeps one entry keyed by PID; readers (stdio-bridge, humans) map an
 * open project path to the port serving it. Cross-process writes are guarded by a lock file;
 * dead PIDs are pruned on every write.
 */
class InstancesRegistry {
    private val dir: Path = Path.of(System.getProperty("user.home"), ".idectl")
    private val file: Path = dir.resolve("instances.json")
    private val lockFile: Path = dir.resolve("instances.lock")
    private val pid: Long = ProcessHandle.current().pid()

    fun update(port: Int, ide: String, projects: List<String>, tokenHint: String?) {
        mutate { entries ->
            val self = JsonObject().apply {
                addProperty("pid", pid)
                addProperty("port", port)
                addProperty("ide", ide)
                add("projects", JsonArray().apply { projects.forEach { add(it) } })
                addProperty("tokenHint", tokenHint ?: "")
                addProperty("updatedAt", System.currentTimeMillis())
            }
            entries.add(self)
        }
    }

    fun unregister() = mutate { /* self already dropped by prune below */ }

    /** Read → drop self + dead PIDs → let [add] append fresh self → atomic write, all under a file lock. */
    private fun mutate(add: (MutableList<JsonObject>) -> Unit) {
        try {
            Files.createDirectories(dir)
            RandomAccessFile(lockFile.toFile(), "rw").use { raf ->
                val channel: FileChannel = raf.channel
                channel.lock().use {
                    val kept = readEntries().filter { entry ->
                        val p = entry.get("pid")?.asLong ?: return@filter false
                        p != pid && isAlive(p)
                    }.toMutableList()
                    add(kept)
                    writeAtomic(kept)
                }
            }
        } catch (t: Throwable) {
            thisLogger().warn("instances.json update failed", t)
        }
    }

    private fun readEntries(): List<JsonObject> {
        if (!Files.exists(file)) return emptyList()
        return try {
            val root = JsonParser.parseString(Files.readString(file))
            if (!root.isJsonArray) emptyList()
            else root.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
        } catch (t: Throwable) {
            thisLogger().warn("instances.json unreadable, resetting", t)
            emptyList()
        }
    }

    private fun writeAtomic(entries: List<JsonObject>) {
        val arr = JsonArray().apply { entries.forEach { add(it) } }
        val tmp = Files.createTempFile(dir, "instances", ".tmp")
        Files.writeString(tmp, arr.toString())
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Throwable) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}
