package com.conreo.couchytv.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private val apiKeyPattern = Regex("[0-9a-fA-F]{32}")
private val bearerPattern = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
internal val youtubeIdPattern = Regex("[A-Za-z0-9_-]{11}")

internal fun validTmdbCredential(credential: String): Boolean =
    credential.length <= 1024 && (apiKeyPattern.matches(credential) || bearerPattern.matches(credential))

internal data class TrailerLookup(
    val film: Boolean,
    val tmdbId: Int?,
    val imdbId: String?,
    val title: String?,
    val year: Int?,
) {
    val mediaType get() = if (film) "movie" else "tv"
    val dateField get() = if (film) "release_date" else "first_air_date"
    val titleField get() = if (film) "title" else "name"
    val originalTitleField get() = if (film) "original_title" else "original_name"
}

private val imdbPattern = Regex("(?i)(?:imdb:)?(tt[0-9]{7,})")
private val tmdbPattern = Regex("(?i)tmdb:(?:(movie|tv):)?([0-9]+)")
private val myKoPattern = Regex("myko:(?:watch:)?(movie|tv):([0-9]+)(?::s[0-9]+e[0-9]+)?")

internal fun trailerLookup(
    packageName: String,
    film: Boolean,
    episodic: Boolean,
    contentId: String?,
    title: String?,
    releaseDate: String?,
): TrailerLookup? {
    val kind = if (film) "movie" else "tv"
    val explicit = contentId?.let(tmdbPattern::matchEntire)
    val myKo = contentId?.takeIf { packageName == "com.myko.tv" }?.let(myKoPattern::matchEntire)
    val id = (explicit ?: myKo)?.takeIf { it.groupValues[1].let { type -> type.isEmpty() || type.equals(kind, true) } }
        ?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 }
    val imdbId = contentId?.let(imdbPattern::matchEntire)?.groupValues?.get(1)?.lowercase()
    // Android TV uses Title for the series name on episodic records. The
    // record's release date can describe a season or episode instead.
    val safeTitle = title?.trim()?.takeIf { it.isNotEmpty() && it.length <= 160 }
    val year = releaseDate?.takeUnless { episodic }?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2100 }
    return TrailerLookup(film, id, imdbId, safeTitle, year).takeIf {
        it.tmdbId != null || it.imdbId != null || it.title != null
    }
}

/** IDs from publisher-provided YouTube links are played through the official player. */
internal fun publisherYouTubeId(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
    val path = uri.path.orEmpty()
    val id = when (uri.host?.lowercase()) {
        "youtu.be" -> path.removePrefix("/")
        "youtube.com", "www.youtube.com", "m.youtube.com", "www.youtube-nocookie.com" -> when {
            path == "/watch" -> uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("v=") }?.substring(2)
            path.startsWith("/embed/") -> path.removePrefix("/embed/")
            path.startsWith("/shorts/") -> path.removePrefix("/shorts/")
            else -> null
        }
        else -> null
    }
    return id?.takeIf(youtubeIdPattern::matches)
}

internal object TrailerFallback {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
    private val base = "https://api.themoviedb.org/3/".toHttpUrl()
    private data class CachedTrailer(val videoIds: List<String>, val expiresAt: Long)
    private val results = android.util.LruCache<TrailerLookup, CachedTrailer>(48)

    suspend fun validate(credential: String): Boolean = request("configuration", credential) != null

    suspend fun youtubeTrailers(lookup: TrailerLookup, credential: String): List<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        results.get(lookup)?.takeIf { now < it.expiresAt }?.let { return it.videoIds }
        val ids = matchYouTubeTrailers(lookup) { path, parameters ->
            request(path, credential, *parameters.toTypedArray())
        }
        results.put(lookup, CachedTrailer(ids, now + if (ids.isEmpty()) 60_000 else 600_000))
        return ids
    }

    private suspend fun request(
        route: String,
        credential: String,
        vararg parameters: Pair<String, String>,
    ): JsonObject? {
        if (!validTmdbCredential(credential)) return null
        val url = base.newBuilder().addPathSegments(route)
        parameters.forEach { (name, value) -> url.addQueryParameter(name, value) }
        val builder = Request.Builder().header("Accept", "application/json")
        if (apiKeyPattern.matches(credential)) url.addQueryParameter("api_key", credential)
        else builder.header("Authorization", "Bearer $credential")
        val call = client.newCall(builder.url(url.build()).build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resume(null)

                override fun onResponse(call: Call, response: Response) {
                    val data = response.use {
                        runCatching {
                            if (!response.isSuccessful) return@runCatching null
                            val source = response.body?.source() ?: return@runCatching null
                            if (source.request(512 * 1024L + 1)) return@runCatching null
                            Json.parseToJsonElement(source.readUtf8()).jsonObject
                        }.getOrNull()
                    }
                    continuation.resume(data)
                }
            })
        }
    }
}

internal typealias TrailerApiRequest = suspend (String, List<Pair<String, String>>) -> JsonObject?

internal suspend fun matchYouTubeTrailers(lookup: TrailerLookup, request: TrailerApiRequest): List<String> {
    val id = lookup.tmdbId ?: resolve(lookup, request) ?: return emptyList()
    val response = request("${lookup.mediaType}/$id/videos", emptyList()) ?: return emptyList()
    return response.items("results").mapNotNull { item ->
        item.string("key")?.takeIf {
            youtubeIdPattern.matches(it) && item.string("site") == "YouTube" &&
                item.string("type") == "Trailer" && (item["official"] as? JsonPrimitive)?.booleanOrNull == true
        }
    }.distinct().take(2)
}

private suspend fun resolve(lookup: TrailerLookup, request: TrailerApiRequest): Int? {
    val imdb = lookup.imdbId
    if (imdb != null) {
        val response = request("find/$imdb", listOf("external_source" to "imdb_id")) ?: return null
        val ids = response.items(if (lookup.film) "movie_results" else "tv_results").mapNotNull(JsonObject::tmdbId)
        val parentIds = if (lookup.film) emptyList() else {
            (response.items("tv_episode_results") + response.items("tv_season_results"))
                .mapNotNull { (it["show_id"] as? JsonPrimitive)?.intOrNull?.takeIf { id -> id > 0 } }
        }
        return (ids + parentIds).distinct().singleOrNull()
    }
    val title = lookup.title ?: return null
    val year = lookup.year
    val yearField = if (lookup.film) "primary_release_year" else "first_air_date_year"
    val parameters = listOf("query" to title) + listOfNotNull(year?.let { yearField to it.toString() })
    val response = request("search/${lookup.mediaType}", parameters) ?: return null
    if (year == null && ((response["total_pages"] as? JsonPrimitive)?.intOrNull ?: 1) > 1) return null
    return response.items("results")
        .filter {
            (year == null || it.string(lookup.dateField)?.take(4)?.toIntOrNull() == year) &&
                (title.equals(it.string(lookup.titleField), true) || title.equals(it.string(lookup.originalTitleField), true))
        }
        .mapNotNull(JsonObject::tmdbId).distinct().singleOrNull()
}

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.tmdbId(): Int? = (get("id") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
private fun JsonObject.items(name: String) = (get(name) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
