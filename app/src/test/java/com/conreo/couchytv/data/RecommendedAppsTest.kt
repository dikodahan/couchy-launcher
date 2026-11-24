package com.conreo.couchytv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test

class RecommendedAppsTest {
    private val smartTube = """
        {
          "category": "Entertainment",
          "name": "SmartTube",
          "downloadUrl": "https://github.com/yuliskov/SmartTube/releases/download/latest/smarttube_stable.apk",
          "iconUrl": "https://raw.githubusercontent.com/yuliskov/SmartTube/master/app_icon.png"
        }
    """.trimIndent()

    @Test
    fun keepsEnglishNameAndCategoryFromCatalog() {
        val entry = parseRecommendedApps("[$smartTube]").single()
        assertEquals("SmartTube", entry.name)
        assertEquals("Entertainment", entry.category)
        assertEquals("smarttube", entry.key)
        assertEquals(emptyList<RecommendedApp>(), parseRecommendedApps("[]"))
    }

    @Test
    fun acceptsAnEntryWithoutAnIcon() {
        val withoutIcon = smartTube.substringBeforeLast(',') + "}"
        assertNull(parseRecommendedApps("[$withoutIcon]").single().iconUrl)
        assertEquals(true, isRecommendedAppsUrl("https://catalog.example.org:8443/apps.json"))
        assertEquals(false, isRecommendedAppsUrl("http://catalog.example.org/apps.json"))
    }

    @Test
    fun rejectsDuplicateNamesAndUrlsThatCannotBeUsed() {
        val sameName = smartTube.replace("SmartTube\"", "SMARTTUBE\"")
        assertThrows(IllegalArgumentException::class.java) {
            parseRecommendedApps("[$smartTube, $sameName]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRecommendedApps("[${smartTube.replace("https://github.com", "http://github.com")}]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRecommendedApps("[${smartTube.replace("https://github.com", "https://user:password@github.com")}]")
        }
    }
}
