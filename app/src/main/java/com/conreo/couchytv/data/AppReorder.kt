package com.conreo.couchytv.data

internal data class AppPlacement(val sectionId: String, val packageName: String)

internal enum class ReorderDirection { Start, End, Up, Down }

internal data class ReorderEdit(
    val rows: List<Pair<CategoryCfg, List<AppEntry>>>,
    val placement: AppPlacement,
    val existingCopyIndex: Int?,
)

internal fun moveApp(
    rows: List<Pair<CategoryCfg, List<AppEntry>>>,
    placement: AppPlacement,
    direction: ReorderDirection,
    columns: Int,
    existingCopyIndex: Int? = null,
): ReorderEdit? {
    val sourceIndex = rows.indexOfFirst { it.first.id == placement.sectionId }
    if (sourceIndex < 0) return null
    val source = rows[sourceIndex].second
    val index = source.indexOfFirst { it.pkg == placement.packageName }
    if (index < 0) return null
    val horizontal = direction == ReorderDirection.Start || direction == ReorderDirection.End
    val delta = if (direction == ReorderDirection.Start || direction == ReorderDirection.Up) -1 else 1
    val lastRow = (source.lastIndex / columns) * columns
    val crossesSection = when (direction) {
        ReorderDirection.Up -> index < columns
        ReorderDirection.Down -> index >= lastRow
        else -> false
    }
    if (!crossesSection) {
        val next = index + if (horizontal) delta else delta * columns
        if (horizontal && next !in source.indices) return null
        val updated = source.toMutableList()
        updated.add(next.coerceIn(source.indices), updated.removeAt(index))
        return ReorderEdit(rows.toMutableList().also {
            it[sourceIndex] = rows[sourceIndex].first to updated
        }, placement, existingCopyIndex)
    }
    val targetIndex = sourceIndex + delta
    val (targetCategory, target) = rows.getOrNull(targetIndex) ?: return null
    val existingTargetIndex = target.indexOfFirst { it.pkg == placement.packageName }.takeIf { it >= 0 }
    val updatedTarget = target.filterNot { it.pkg == placement.packageName }.toMutableList()
    val column = index % columns
    val offset = if (delta < 0 && target.isNotEmpty()) (target.lastIndex / columns) * columns else 0
    updatedTarget.add((offset + column).coerceAtMost(updatedTarget.size), source[index])
    val updatedRows = rows.toMutableList()
    // All Apps is an automatic index. Moving from it creates a placement
    // without removing the package from that index.
    if (placement.sectionId != AppRepository.ALL_APPS_ID) {
        val updatedSource = source.filterNot { it.pkg == placement.packageName }.toMutableList()
        // Passing through a section that already contained this app preserves its other placement.
        if (existingCopyIndex != null) {
            updatedSource.add(existingCopyIndex.coerceAtMost(updatedSource.size), source[index])
        }
        updatedRows[sourceIndex] = rows[sourceIndex].first to updatedSource
    }
    updatedRows[targetIndex] = targetCategory to updatedTarget
    return ReorderEdit(updatedRows, AppPlacement(targetCategory.id, placement.packageName), existingTargetIndex)
}

internal fun LauncherConfig.applyReorder(
    original: List<Pair<CategoryCfg, List<AppEntry>>>,
    reordered: List<Pair<CategoryCfg, List<AppEntry>>>,
): LauncherConfig {
    val oldAppsBySection = original.associate { it.first.id to it.second }
    val validSections = categories.mapTo(hashSetOf()) { it.id }
    val updatedSections = sections.toMutableMap()
    val updatedOrder = order.toMutableMap()
    for ((category, apps) in reordered) {
        val section = category.id
        if (section !in validSections) continue
        val previousApps = oldAppsBySection[section].orEmpty()
        val prior = previousApps.map { it.pkg }
        val next = apps.map { it.pkg }
        if (prior == next) continue
        val priorSet = prior.toSet()
        val nextSet = next.toSet()
        if (section != AppRepository.ALL_APPS_ID) {
            for (app in previousApps.filter { it.pkg !in nextSet } + apps.filter { it.pkg !in priorSet }) {
                val memberships = updatedSections[app.pkg] ?: AppRepository.sectionsOf(app, this)
                updatedSections[app.pkg] =
                    if (app.pkg in nextSet) memberships + section else memberships - section
            }
        }
        updatedOrder[section] = mergeVisibleOrder(order[section].orEmpty(), priorSet, next)
    }
    return copy(sections = updatedSections, order = updatedOrder)
}

/** The same flat order is used by the Home dock and its reorder preview. */
internal fun appsInDockOrder(
    rows: List<Pair<CategoryCfg, List<AppEntry>>>,
    order: List<String>,
): List<AppEntry> {
    val apps = rows.flatMap { it.second }.distinctBy { it.pkg }
    if (order.isEmpty()) return apps
    val positions = order.withIndex().associate { (index, pkg) -> pkg to index }
    return apps.sortedBy { positions[it.pkg] ?: Int.MAX_VALUE }
}

internal fun LauncherConfig.applyDockReorder(original: List<AppEntry>, reordered: List<AppEntry>): LauncherConfig =
    copy(dockOrder = mergeVisibleOrder(dockOrder, original.mapTo(hashSetOf()) { it.pkg }, reordered.map { it.pkg }))

private fun mergeVisibleOrder(order: List<String>, prior: Set<String>, next: List<String>): List<String> {
    val visible = prior + next
    val pending = next.iterator()
    return buildList {
        for (pkg in order) {
            if (pkg !in visible) add(pkg)
            else if (pending.hasNext()) add(pending.next())
        }
        while (pending.hasNext()) add(pending.next())
    }
}
