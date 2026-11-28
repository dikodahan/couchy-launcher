package com.conreo.couchytv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.conreo.couchytv.R
import com.conreo.couchytv.data.AppEntry
import com.conreo.couchytv.data.AppPlacement
import com.conreo.couchytv.data.CategoryCfg
import com.conreo.couchytv.data.LauncherConfig
import com.conreo.couchytv.data.ReorderDirection
import com.conreo.couchytv.data.moveApp

// Feed the flat Dock list through the same move logic as a single group.
private val DockReorderSection = CategoryCfg("dock", "")

@Composable
internal fun AppReorderScreen(
    initialRows: List<Pair<CategoryCfg, List<AppEntry>>>,
    dockApps: List<AppEntry>?,
    start: AppPlacement,
    config: LauncherConfig,
    accent: Color,
    cardWidth: Dp,
    gap: Dp,
    saving: Boolean,
    onDone: (List<Pair<CategoryCfg, List<AppEntry>>>, List<Pair<CategoryCfg, List<AppEntry>>>) -> Unit,
) {
    val dockMode = dockApps != null
    val original = remember { if (dockApps == null) initialRows else listOf(DockReorderSection to dockApps) }
    var rows by remember { mutableStateOf(original) }
    var held by remember { mutableStateOf<AppPlacement?>(null) }
    var existingCopyIndex by remember { mutableStateOf<Int?>(null) }
    var target by remember {
        mutableStateOf(if (dockMode) start.copy(sectionId = DockReorderSection.id) else start)
    }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val focus = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    fun finish() {
        if (!saving) onDone(original, rows)
    }
    BackHandler { finish() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (columns, columnGap) = if (dockMode) fitDockRow(maxWidth, cardWidth, gap)
        else fitRow(maxWidth - GroupedAppsSideInset * 2, cardWidth, gap)
        val gridWidth = cardWidth * columns + columnGap * (columns - 1)

        LaunchedEffect(rows, target, columns) {
            val index = if (dockMode) {
                val flatIndex = rows.single().second.indexOfFirst { it.pkg == target.packageName }
                if (flatIndex < 0) return@LaunchedEffect
                // The dock is pinned above the scrollable grid.
                (flatIndex - columns).takeIf { it >= 0 }
            } else {
                val sectionIndex = rows.indexOfFirst { it.first.id == target.sectionId }
                if (sectionIndex < 0) return@LaunchedEffect
                val position = rows[sectionIndex].second.indexOfFirst { it.pkg == target.packageName }
                if (position < 0) return@LaunchedEffect
                rows.take(sectionIndex).sumOf { it.second.size + 1 } + 1 + position
            }
            if (index != null && grid.layoutInfo.visibleItemsInfo.none { it.index == index }) grid.scrollToItem(index)
            repeat(3) {
                withFrameNanos {}
                if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }

        val card: @Composable (CategoryCfg, AppEntry) -> Unit = { category, app ->
            val placement = AppPlacement(category.id, app.pkg)
            val selected = placement == held
            AppCard(
                app = app,
                isMoving = selected,
                isHidden = app.pkg in config.hidden,
                accent = accent,
                cardWidth = cardWidth,
                showLabel = config.showAppLabels,
                modifier = (if (placement == target) Modifier.focusRequester(focus) else Modifier)
                    .semantics { this.selected = selected },
                onLaunch = {
                    if (!saving) {
                        target = held ?: placement
                        held = if (held == null) placement else null
                        existingCopyIndex = null
                    }
                },
                onLongPress = null,
            )
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = if (dockMode) DockAppsSideInset else GroupedAppsSideInset).onPreviewKeyEvent { event ->
                if (event.key == Key.Menu) return@onPreviewKeyEvent true
                if (event.key == Key.Escape) {
                    if (event.type == KeyEventType.KeyUp) finish()
                    return@onPreviewKeyEvent true
                }
                val selected = held ?: return@onPreviewKeyEvent saving
                val direction = when (event.key) {
                    Key.DirectionLeft -> if (isRtl) ReorderDirection.End else ReorderDirection.Start
                    Key.DirectionRight -> if (isRtl) ReorderDirection.Start else ReorderDirection.End
                    Key.DirectionUp -> ReorderDirection.Up
                    Key.DirectionDown -> ReorderDirection.Down
                    else -> return@onPreviewKeyEvent saving
                }
                if (event.type == KeyEventType.KeyDown && !saving) {
                    moveApp(rows, selected, direction, columns, existingCopyIndex)?.let {
                        rows = it.rows
                        held = it.placement
                        target = it.placement
                        existingCopyIndex = it.existingCopyIndex
                    }
                }
                true
            },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Move,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Column {
                    Text(
                        stringResource(R.string.apps_reorder_mode),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.apps_reorder_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (dockMode) {
                val apps = rows.single().second
                val dock = apps.take(columns)
                if (dock.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = gap), contentAlignment = Alignment.Center) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.matchParentSize()
                                .launcherGlass(LocalCornerRadius.current + 14.dp, GlassPanel.Dock))
                            Row(
                                Modifier.padding(DockPanelPadding),
                                horizontalArrangement = Arrangement.spacedBy(columnGap),
                            ) {
                                dock.forEach { app -> key(app.pkg) { card(DockReorderSection, app) } }
                            }
                        }
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.width(gridWidth)
                    .align(if (dockMode) Alignment.CenterHorizontally else Alignment.Start)
                    .weight(1f),
                state = grid,
                contentPadding = PaddingValues(top = if (dockMode) 0.dp else 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(gap),
                horizontalArrangement = Arrangement.spacedBy(columnGap),
            ) {
                if (dockMode) {
                    items(rows.single().second.drop(columns), key = { it.pkg }) { app ->
                        card(DockReorderSection, app)
                    }
                } else {
                    for ((category, apps) in rows) {
                        item(key = "header:${category.id}", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeading(category, showName = true, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(apps, key = { "app:${category.id}:${it.pkg}" }) { app ->
                            card(category, app)
                        }
                    }
                }
            }
        }
    }
}
