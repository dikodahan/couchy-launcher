package com.conreo.couchytv.data

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppReorderTest {
    private val alpha = CategoryCfg("alpha", "Alpha", iconName = "apps", iconEnabled = true)
    private val beta = CategoryCfg("beta", "Beta")
    private val gamma = CategoryCfg("gamma", "Gamma")
    private val all = CategoryCfg(AppRepository.ALL_APPS_ID, "All apps")

    @Test fun reordersAcrossGridRowsThenMovesIntoAnEmptySection() {
        val a = app("a")
        val b = app("b")
        val c = app("c")
        val initial = listOf(alpha to listOf(a, b, c), beta to emptyList())
        val selection = AppPlacement(alpha.id, a.pkg)
        val inside = requireNotNull(moveApp(initial, selection, ReorderDirection.Down, 2))
        assertEquals(listOf(b, c, a), inside.rows[0].second)
        val across = requireNotNull(moveApp(inside.rows, inside.placement, ReorderDirection.Down, 2))
        assertEquals(AppPlacement(beta.id, a.pkg), across.placement)
        assertEquals(listOf(a), across.rows[1].second)

        val config = LauncherConfig(categories = listOf(alpha, beta),
            order = mapOf(alpha.id to listOf("a", "hidden", "b", "c")))
        val saved = config.applyReorder(initial, across.rows)
        assertEquals(setOf(beta.id), saved.sections[a.pkg])
        assertEquals(listOf("b", "hidden", "c"), saved.order[alpha.id])
        assertEquals(listOf("a"), saved.order[beta.id])
        assertEquals(saved, ConfigBackup.decode(ConfigBackup.encode(saved)))
        assertEquals("apps", saved.categories.first().visibleIcon)
    }

    @Test fun passingThroughAnotherPlacementPreservesTheExistingCopy() {
        val a = app("a")
        val b = app("b")
        val rows = listOf(alpha to listOf(a), beta to listOf(b, a), gamma to emptyList())
        val start = AppPlacement(alpha.id, "a")
        val through = requireNotNull(moveApp(rows, start, ReorderDirection.Down, 2))
        val across = requireNotNull(moveApp(through.rows, through.placement, ReorderDirection.Down, 2,
            through.existingCopyIndex))
        assertEquals(listOf(b, a), across.rows[1].second)
        val config = LauncherConfig(categories = listOf(alpha, beta, gamma),
            sections = mapOf("a" to setOf(alpha.id, beta.id)))
        assertEquals(setOf(beta.id, gamma.id), config.applyReorder(rows, across.rows).sections["a"])
    }

    @Test fun allAppsRemainsAnAutomaticIndexWhileItsOrderIsSaved() {
        val a = app("a")
        val b = app("b")
        val rows = listOf(alpha to listOf(a), all to listOf(a, b))
        val changed = requireNotNull(moveApp(rows, AppPlacement(all.id, "a"), ReorderDirection.End, 2))
        val config = LauncherConfig(categories = listOf(alpha, all))
        val saved = config.applyReorder(rows, changed.rows)
        assertNull(saved.sections["a"])
        assertEquals(listOf(b, a), AppRepository.categorize(listOf(a, b), saved)[1].second)
        val moved = requireNotNull(moveApp(rows, AppPlacement(all.id, "b"), ReorderDirection.Up, 2))
        assertEquals(listOf(a, b), moved.rows[1].second)
        assertEquals(setOf(alpha.id), config.applyReorder(rows, moved.rows).sections["b"])
    }

    @Test fun dockOrderChangesTheFirstRowAndPreservesSectionMembershipAndSavedPositions() {
        val a = app("a")
        val b = app("b")
        val c = app("c")
        val grouped = listOf(alpha to listOf(a, b), beta to listOf(c, a), all to listOf(a, b, c))
        val config = LauncherConfig(
            categories = listOf(alpha, beta, all),
            sections = mapOf("a" to setOf(alpha.id, beta.id)),
            order = mapOf(alpha.id to listOf("a", "b")),
            dockOrder = listOf("a", "hidden", "b", "c"),
        )
        assertEquals(listOf(a, b, c), appsInDockOrder(grouped, emptyList()))
        val flat = appsInDockOrder(grouped, config.dockOrder)
        val dock = listOf(alpha to flat)
        val edited = requireNotNull(moveApp(dock, AppPlacement(alpha.id, "c"), ReorderDirection.Up, 2))
        val saved = config.applyDockReorder(flat, edited.rows.single().second)
        assertEquals(listOf("c", "hidden", "a", "b"), saved.dockOrder)
        assertEquals(listOf(c, a), appsInDockOrder(grouped, saved.dockOrder).take(2))
        assertEquals(config.sections, saved.sections)
        assertEquals(config.order, saved.order)
        assertEquals(saved, ConfigBackup.decode(ConfigBackup.encode(saved)))
    }

    private fun app(pkg: String) = AppEntry(pkg, pkg, null, null, alpha.id, Color.Black, 0, 0)
}
