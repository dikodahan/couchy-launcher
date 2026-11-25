package com.conreo.couchytv.data

import androidx.compose.ui.unit.dp
import com.conreo.couchytv.ui.GAP_SIZES
import com.conreo.couchytv.ui.GroupedAppsSideInset
import com.conreo.couchytv.ui.fitDockRow
import com.conreo.couchytv.ui.fitRow
import com.conreo.couchytv.ui.iconCardWidth
import org.junit.Assert.assertEquals
import org.junit.Test

class IconSizeTest {
    @Test fun extraSmallFitsOneMoreAppThanCompactAtDifferentWidthsAndSpacings() {
        for (width in listOf(720.dp, 984.6.dp, 1280.dp, 1920.dp)) {
            for (gap in GAP_SIZES) {
                val dockCompact = fitDockRow(width, iconCardWidth(0, LAYOUT_DOCK, width, gap), gap).first
                val dockExtraSmall = fitDockRow(width, iconCardWidth(ICON_SCALE_EXTRA_SMALL, LAYOUT_DOCK, width, gap), gap).first
                assertEquals(dockCompact + 1, dockExtraSmall)

                val available = width - GroupedAppsSideInset * 2
                val gridCompact = fitRow(available, iconCardWidth(0, LAYOUT_GRID, width, gap), gap).first
                val gridExtraSmall = fitRow(available, iconCardWidth(ICON_SCALE_EXTRA_SMALL, LAYOUT_GRID, width, gap), gap).first
                assertEquals(gridCompact + 1, gridExtraSmall)
            }
        }
    }

    @Test fun savedSizeStepsRetainTheirOriginalWidths() {
        listOf(120.dp, 150.dp, 190.dp, 230.dp, 270.dp).forEachIndexed { step, width ->
            assertEquals(width, iconCardWidth(step, LAYOUT_DOCK, 1280.dp, 10.dp))
        }
        val extraSmall = LauncherConfig(iconScale = ICON_SCALE_EXTRA_SMALL)
        assertEquals(extraSmall, ConfigBackup.decode(ConfigBackup.encode(extraSmall)))
    }
}
