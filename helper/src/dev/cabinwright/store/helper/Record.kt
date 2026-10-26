package dev.cabinwright.store.helper

import dev.cabinwright.store.common.Json
import java.io.IOException

/**
 * state.json in the helper's private storage: the ids it installed (the only thing removal
 * trusts), the index sequences it accepted, the request ids it ran, and the import from
 * kia.store. Helpers before 1.1 kept no record; [Helper] seeds it once.
 */
internal object Record {
    private const val MAX = 64 shl 10

    fun state(): MutableMap<String, Any?> = try {
        Json.parseObject(readText(Helper.STATE, MAX))
    } catch (ignored: IOException) {
        Json.of() // none yet
    } catch (ignored: IllegalArgumentException) {
        Json.of()
    }

    fun save(state: Map<String, Any?>) {
        writeFile(Helper.STATE, Json.write(state).toByteArray(Charsets.UTF_8))
    }

    /** Ids this helper installed. */
    fun recorded(): List<String> = Json.strings(state(), "installed")

    /** Notes that this helper installed [id], or that it's gone. */
    fun mark(id: String, installed: Boolean) {
        val state = state()
        val ids = Json.strings(state, "installed")
        ids.remove(id)
        if (installed) ids.add(id)
        ids.sort()
        state["installed"] = ArrayList<Any?>(ids)
        try {
            save(state)
        } catch (e: IOException) {
            log("state: " + e.message)
        }
    }
}
