package com.conreo.couchytv.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerFallbackTest {
    @Test
    fun myKoNamespacedIdMatchesOnlyMyKo() {
        val id = "myko:watch:tv:123:s2e4"
        assertEquals(123, trailerLookup("com.myko.tv", false, true, id, null, null)?.tmdbId)
        assertNull(trailerLookup("com.example.other", false, true, id, null, null))
        assertNull(trailerLookup("com.myko.tv", true, false, id, null, null))
        assertNull(trailerLookup("com.netflix.ninja", true, false, "123", "Example", null)?.tmdbId)
    }

    @Test
    fun trailersSkipAmbiguousMovieMatches() = runBlocking {
        val lookup = requireNotNull(trailerLookup("com.example.app", true, false, null, "Example", "2001-10-01"))
        val video = matchYouTubeTrailers(lookup) { path, params ->
            assertEquals("search/movie", path)
            assertEquals(mapOf("query" to "Example", "primary_release_year" to "2001"), params.toMap())
            json("""{"results":[
                {"id":3,"title":"Example","release_date":"2001-01-01"},
                {"id":4,"title":"Example","release_date":"2001-03-01"}
            ]}""")
        }
        assertTrue(video.isEmpty())
    }

    @Test
    fun imdbLookupUsesMatchingMediaTypeAndOfficialTrailer() = runBlocking {
        val lookup = requireNotNull(trailerLookup("com.example.app", false, false, "imdb:tt1234567", null, null))
        val video = matchYouTubeTrailers(lookup) { path, params ->
            when (path) {
                "find/tt1234567" -> {
                    assertEquals(mapOf("external_source" to "imdb_id"), params.toMap())
                    json("""{"tv_results":[{"id":77}],"movie_results":[{"id":88}]}""")
                }
                "tv/77/videos" -> json("""{"results":[
                    {"site":"YouTube","type":"Trailer","official":false,"key":"12345678901"},
                    {"site":"YouTube","type":"Teaser","official":true,"key":"12345678902"},
                    {"site":"YouTube","type":"Trailer","official":true,"key":"12345678903"}
                ]}""")
                else -> error("Unexpected route")
            }
        }
        assertEquals(listOf("12345678903"), video)
    }

    @Test
    fun uniqueExactSeriesTitleWorksWithoutTheOptionalReleaseDate() = runBlocking {
        val lookup = requireNotNull(trailerLookup("com.example.app", false, true, "opaque-id", "Example", "2025-01-01"))
        val videos = matchYouTubeTrailers(lookup) { path, params ->
            when (path) {
                "search/tv" -> {
                    assertEquals(mapOf("query" to "Example"), params.toMap())
                    json("""{"results":[{"id":12,"name":"Example Season 2"},{"id":13,"name":"Example"}]}""")
                }
                "tv/13/videos" -> json("""{"results":[
                    {"site":"YouTube","type":"Trailer","official":true,"key":"12345678901"},
                    {"site":"YouTube","type":"Trailer","official":true,"key":"12345678902"}
                ]}""")
                else -> error("Unexpected route")
            }
        }
        assertEquals(listOf("12345678901", "12345678902"), videos)
        assertNull(lookup.year)
    }

    @Test
    fun publisherYouTubeLinksUseTheOfficialPlayer() {
        assertEquals("12345678901", publisherYouTubeId("https://www.youtube.com/watch?feature=share&v=12345678901"))
        assertEquals("12345678901", publisherYouTubeId("https://youtu.be/12345678901"))
        assertEquals("12345678901", publisherYouTubeId("https://www.youtube-nocookie.com/embed/12345678901"))
        assertNull(publisherYouTubeId("https://www.youtube.com.example.org/embed/12345678901"))
        assertNull(publisherYouTubeId("https://example.org/trailer.mp4"))
    }

    @Test
    fun credentialAndToggleArePortable() {
        val key = "a".repeat(32)
        assertTrue(validTmdbCredential(key))
        assertTrue(validTmdbCredential("demo.demo.demo"))
        assertFalse(validTmdbCredential("invalid credential"))
        val original = LauncherConfig(
            appContentPreviewFallback = true,
            appContentPreviewTmdbCredential = key,
            appContentPreviewSubtitles = true,
            appContentPreviewSubtitleLanguage = "he",
        )
        assertEquals(original, ConfigBackup.decode(ConfigBackup.encode(original)))
        val legacy = ConfigBackup.decode("""{"categories":[{"id":"apps","name":"Apps"}]}""")
        assertFalse(legacy.appContentPreviewSubtitles)
        assertTrue(legacy.appContentPreviewSubtitleLanguage.isEmpty())
        assertFalse(legacy.appContentPreviewFallback)
        assertTrue(legacy.appContentPreviewTmdbCredential.isEmpty())
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
