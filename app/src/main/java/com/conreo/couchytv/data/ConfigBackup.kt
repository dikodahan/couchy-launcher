package com.conreo.couchytv.data

import java.io.InputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Shared by document, Downloads, ADB and Telegram paths; old flat JSON remains readable. */
object ConfigBackup {
    const val MAX_BYTES = 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun encode(config: LauncherConfig): String = json.encodeToString(config).also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup too large" }
    }

    fun read(input: InputStream): LauncherConfig {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= MAX_BYTES) { "Backup too large" }
            output.write(buffer, 0, count)
        }
        return decode(output.toString("UTF-8"))
    }

    fun decode(text: String): LauncherConfig {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup too large" }
        // Reject deeply nested/unrelated data before it reaches the serializer or launcher UI.
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "Backup too deeply nested" } }
                '}', ']' -> depth--
            }
        }
        require("categories" in json.parseToJsonElement(text).jsonObject) { "Not a Couchy backup" }
        return json.decodeFromString<LauncherConfig>(text).also { config ->
            require(config.categories.isNotEmpty() && config.categories.size <= 128)
            require(config.categories.map { it.id }.distinct().size == config.categories.size)
            require(config.categories.all { it.id.isNotBlank() && it.name.isNotBlank() })
            require(config.labelOverrides.all { (pkg, label) -> pkg.isNotBlank() && label.isNotBlank() && label.length <= 256 })
            require(config.recommendedAppsUrl.isEmpty() || isRecommendedAppsUrl(config.recommendedAppsUrl))
            require(!config.recommendedAppsEnabled || config.recommendedAppsUrl.isNotEmpty())
            require(config.weatherLat.isFinite() && config.weatherLon.isFinite())
        }
    }
}
