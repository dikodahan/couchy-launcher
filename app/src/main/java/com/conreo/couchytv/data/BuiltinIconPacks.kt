package com.conreo.couchytv.data

import com.conreo.couchytv.R

// Separates built-in pack downloads from any user-configured catalog.
internal const val BUILTIN_ICON_PACK_CATALOG = "couchy:builtin-icon-packs"

internal val BUILTIN_ICON_PACKS = mapOf(
    RecommendedApp(
        category = "",
        name = "Minimalist",
        downloadUrl = "https://github.com/hqn-scl/android-tv-minimalist-icon-pack/releases/download/v1.0.0/Minimalist-Icons.1.0.0.apk",
    ) to R.drawable.icon_pack_minimalist,
    RecommendedApp(
        category = "",
        name = "Projectivy",
        downloadUrl = "https://github.com/SicMundus86/ProjectivyIconPack/releases/download/1.1.9/Projectivy_Icon_Pack_1.1.9.apk",
    ) to R.drawable.icon_pack_projectivy,
    RecommendedApp(
        category = "",
        name = "Articons",
        downloadUrl = "https://github.com/Arcticons-Team/Arcticons/releases/download/15.4.7/Arcticons-15.4.7-normal-release.apk",
    ) to R.drawable.icon_pack_arcticons,
    RecommendedApp(
        category = "",
        name = "CoreBuildsApp",
        downloadUrl = "https://github.com/brevityA/CoreBuildsApps/releases/download/iconpack/iconpack-release.apk",
    ) to R.drawable.icon_pack_corebuilds,
)
