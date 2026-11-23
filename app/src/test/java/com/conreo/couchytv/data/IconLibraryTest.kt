package com.conreo.couchytv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconLibraryTest {
    private val catalog = IconLibrary.read(
        (
            "movie-filter\tflick\t24\t24\tM0 0Z\n" +
                "movie\tcinema film\t24\t24\tM0 0Z\n" +
                "video-library\t\t24\t24\tM0 0Z\n"
        ).reader().buffered()
    )

    @Test
    fun browseKeepsCatalogOrderAndExactMatchesRankFirst() {
        assertEquals(listOf("movie-filter", "movie", "video-library"), catalog.search("").map { it.name })
        assertEquals(listOf("movie", "movie-filter"), catalog.search(" MOVIE ").map { it.name })
    }

    @Test
    fun searchMatchesAliasesAndTreatsPunctuationAsWordSeparators() {
        assertEquals(listOf("movie"), catalog.search("film CINEMA").map { it.name })
        assertEquals(listOf("video-library"), catalog.search("video-library").map { it.name })
        assertTrue(catalog.search("moon").isEmpty())
    }
}
