package com.conreo.couchytv.data

// Separates built-in pack downloads from any user-configured catalog.
internal const val BUILTIN_ICON_PACK_CATALOG = "couchy:builtin-icon-packs"

internal val BUILTIN_ICON_PACKS = listOf(
    RecommendedApp(
        category = "",
        name = "Minimalist",
        downloadUrl = "https://github.com/hqn-scl/android-tv-minimalist-icon-pack/releases/download/v1.0.0/Minimalist-Icons.1.0.0.apk",
        iconUrl = "https://raw.githubusercontent.com/hqn-scl/android-tv-minimalist-icon-pack/main/assets/ic_launcher.png",
    ),
    RecommendedApp(
        category = "",
        name = "Projectivy",
        downloadUrl = "https://github.com/SicMundus86/ProjectivyIconPack/releases/download/1.1.9/Projectivy_Icon_Pack_1.1.9.apk",
        iconUrl = "https://avatars.githubusercontent.com/u/226375324",
    ),
    RecommendedApp(
        category = "",
        name = "Articons",
        downloadUrl = "https://github.com/Arcticons-Team/Arcticons/releases/download/15.4.7/Arcticons-15.4.7-normal-release.apk",
        iconUrl = "https://raw.githubusercontent.com/Arcticons-Team/Arcticons/main/github/arcticons.png",
    ),
    RecommendedApp(
        category = "",
        name = "CoreBuildsApp",
        downloadUrl = "https://github.com/brevityA/CoreBuildsApps/releases/download/iconpack/iconpack-release.apk",
        iconUrl = "https://raw.githubusercontent.com/brevityA/CoreBuildsApps/main/launcher/app/src/main/res/drawable-nodpi/core_builds_mark.webp",
    ),
)
