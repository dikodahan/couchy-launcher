package com.conreo.couchytv.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Built-in aerial-videos wallpaper source — the video manifests ship WITH the
 * launcher, so no companion app or plugin needs to be installed or configured.
 * 289 streamed videos from Apple, Amazon Fire TV and community collections.
 * Amazon Fire TV supplies the lower-resolution 720p variants.
 */
object BuiltinAerials {
    private val format = Json { ignoreUnknownKeys = true }

    /** Source filter values (config.builtinSource). */
    val SOURCES = listOf("", "A", "Z", "C1", "C2") // "" = all

    const val QUALITY_1080P = 0
    const val QUALITY_720P = 1

    @Serializable
    private data class Entry(val u: String, val s: String = "A", val u720: String? = null)

    @Volatile
    private var cache: List<Entry>? = null

    private fun loadAll(context: Context): List<Entry> {
        cache?.let { return it }
        val list = runCatching {
            val json = context.assets.open("aerials.json")
                .bufferedReader().use { it.readText() }
            format.decodeFromString<List<Entry>>(json)
        }.getOrDefault(emptyList())
        cache = list
        return list
    }

    /** 720p uses only genuine lower-resolution variants. The 1080p collection choice is preserved. */
    fun load(context: Context, sourceIndex: Int, quality: Int): List<String> {
        val all = loadAll(context)
        if (quality == QUALITY_720P) return all.mapNotNull { it.u720 }
        val key = SOURCES.getOrElse(sourceIndex) { "" }
        return all.filter { key.isEmpty() || it.s == key }.map { it.u }
    }
}
