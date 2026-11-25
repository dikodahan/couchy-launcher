package com.conreo.couchytv.data

import java.util.Locale
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
        val entry = parseRecommendedCatalog("[$smartTube]").apps.single()
        assertEquals("SmartTube", entry.name)
        assertEquals("Entertainment", entry.category)
        assertEquals("smarttube", entry.key)
        assertEquals(emptyList<RecommendedApp>(), parseRecommendedCatalog("[]").apps)
    }

    @Test
    fun acceptsAnEntryWithoutAnIcon() {
        val withoutIcon = smartTube.substringBeforeLast(',') + "}"
        assertNull(parseRecommendedCatalog("[$withoutIcon]").apps.single().iconUrl)
        assertEquals(true, isRecommendedAppsUrl("https://catalog.example.org:8443/apps.json"))
        assertEquals(false, isRecommendedAppsUrl("http://catalog.example.org/apps.json"))
    }

    @Test
    fun rejectsDuplicateNamesAndUrlsThatCannotBeUsed() {
        val sameName = smartTube.replace("SmartTube\"", "SMARTTUBE\"")
        assertThrows(IllegalArgumentException::class.java) {
            parseRecommendedCatalog("[$smartTube, $sameName]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRecommendedCatalog("[${smartTube.replace("https://github.com", "http://github.com")}]")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseRecommendedCatalog("[${smartTube.replace("https://github.com", "https://user:password@github.com")}]")
        }
    }

    @Test
    fun readsMixedAndCommandOnlyCatalogs() {
        val command = """{"name":{"en":"Show Android version"},"command":"getprop ro.build.version.release"}"""
        val mixed = parseRecommendedCatalog("""{"apps":[$smartTube],"adbCommands":[$command]}""")
        assertEquals("SmartTube", mixed.apps.single().name)
        assertEquals("getprop ro.build.version.release", mixed.adbCommands.single().commands.single())
        val onlyCommands = parseRecommendedCatalog("""{"adbCommands":[$command]}""")
        assertEquals(emptyList<RecommendedApp>(), onlyCommands.apps)
        assertEquals("Show Android version", onlyCommands.adbCommands.single().displayName(Locale.ENGLISH))
        assertEquals(emptyList<RecommendedCommand>(), parseRecommendedCatalog("[$smartTube]").adbCommands)
    }

    @Test
    fun usesExactLocaleThenParentThenEnglishAndRecognizesHebrewAlias() {
        val json = """{"adbCommands":[{"name":{"en":"English","es":"Spanish","es-MX":"Mexican Spanish","iw":"Hebrew","zh-Hant":"Traditional Chinese"},"command":"date"}]}"""
        val entry = parseRecommendedCatalog(json).adbCommands.single()
        assertEquals("Mexican Spanish", entry.displayName(Locale.forLanguageTag("es-MX")))
        assertEquals("Spanish", entry.displayName(Locale.forLanguageTag("es-AR")))
        assertEquals("English", entry.displayName(Locale.forLanguageTag("fr-CA")))
        assertEquals("Hebrew", entry.displayName(Locale.forLanguageTag("he-IL")))
        assertEquals("Traditional Chinese", entry.displayName(Locale.forLanguageTag("zh-Hant-TW")))
    }

    @Test
    fun rejectsMalformedCommands() {
        val invalid = listOf(
            """{"name":{"es":"Fecha"},"command":"date"}""",
            """{"name":"Date","command":"date"}""",
            """{"name":{"en":"Date"},"command":"   "}""",
            """{"name":{"en":"Date"},"command":" adb shell date"}""",
            """{"name":{"en":"Date"},"command":"date\u0000"}""",
            """{"name":{"en":"Date"},"command":"date\u202e"}""",
            """{"name":{"en":"Date"},"command":"${"a".repeat(8193)}"}""",
        )
        for (entry in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                parseRecommendedCatalog("""{"adbCommands":[$entry]}""")
            }
        }
    }

    @Test
    fun ordersNumberedCommandsNumericallyRegardlessOfJsonKeyOrder() {
        val steps = (12 downTo 1).joinToString(",") { """"command$it":"echo $it"""" }
        val action = parseRecommendedCatalog("""{"adbCommands":[{"name":{"en":"Sequence"},$steps}]}""")
            .adbCommands.single()
        assertEquals((1..12).map { "echo $it" }, action.commands)
    }

    @Test
    fun requiresConsecutiveNonemptyStringStepsAndRejectsMixedFormats() {
        val invalid = listOf(
            """"command2":"date"""",
            """"command1":"date","command3":"true"""",
            """"command0":"true","command1":"date"""",
            """"command01":"date"""",
            """"command":"date","command1":"true"""",
            """"command1":"date","command2":42""",
            """"command1":"date","command2":"adb shell date"""",
            """"command1":"date","command2":"" """,
        )
        for (steps in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                parseRecommendedCatalog("""{"adbCommands":[{"name":{"en":"Sequence"},$steps}]}""")
            }
        }
    }
}
