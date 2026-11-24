# Licensing

## The app

**Couchy Launcher is licensed under the GNU General Public License, version 3 (GPLv3).**
The full text is in [`LICENSE`](LICENSE).

In short:

- You are free to **use, study, share and modify** Couchy.
- It comes with **no warranty**.
- **Copyleft:** if you distribute a modified version (a fork, a rebuild, a different-name build), you must release your source under the **GPLv3** too, and grant the same freedoms. There are no proprietary forks — improvements flow back to everyone.

```
Copyright (C) 2026 the Couchy Launcher contributors

This program is free software: you can redistribute it and/or modify it
under the terms of the GNU General Public License as published by the Free
Software Foundation, version 3.

This program is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see <https://www.gnu.org/licenses/>.
```

## Bundled third-party components

These keep their own licenses, all compatible with GPLv3:

| Component | Where | License |
|---|---|---|
| Couch artwork — Twemoji couch emoji ([source](https://github.com/twitter/twemoji)) | `art/couchy-launcher.svg` (original logo retained in the source tree) | **MIT** |
| Material Design icon path data — Google | `ui/Icons.kt` (vector path strings) | Apache-2.0 |
| Google Material Symbols ([source](https://github.com/google/material-design-icons)), via [Iconify Material Symbols 1.2.95](https://www.npmjs.com/package/@iconify-json/material-symbols/v/1.2.95) | `app/src/main/assets/material-symbols.tsv` and accompanying license | Apache-2.0 |
| AndroidX / Jetpack Compose, `androidx.tv`, DataStore | framework / UI | Apache-2.0 |
| AndroidX Media3 (ExoPlayer) + OkHttp data source | video wallpaper | Apache-2.0 |
| ZXing core | QR code for Telegram device-link | Apache-2.0 |
| TDLib (via `tdlib-kmp`) | optional Telegram Saved Messages backup | Boost 1.0 |
| kotlinx.serialization | config persistence | Apache-2.0 |

## Content that is *not* distributed

The built-in **aerial wallpapers** stream video owned by **Apple, Amazon** and
community creators. Couchy ships only a manifest of public URLs, never the
footage itself, and streams it only when you enable that wallpaper. The
decorative video is fetched over a TLS client scoped to that purpose; no
credentials or personal data are ever sent.

**Telegram backup** is opt-in. Choosing Save/Load in the Cloud links this TV
as a Telegram device (QR on the TV, scan with the phone app; optional two-step
password) and stores a `.couchybak` file in **Saved Messages**. The session
stays on the device; it is not included in the local JSON backup.

## Fork and community attribution

This is the updated open-source fork at [dikodahan/couchy-launcher](https://github.com/dikodahan/couchy-launcher), based on [conreo/couchy-launcher](https://github.com/conreo/couchy-launcher). Original copyright and GPLv3 terms remain in place. Thanks to conreo and the original contributors, Xaero252 (upstream PRs #19/#20), and 9Chrk (upstream PR #27). Their contributions are adapted in version 1.1.0.

The native TDLib package includes OpenSSL 3.5 (Apache-2.0) and zlib (zlib license), in addition to TDLib (Boost 1.0). Source/build provenance and advisory limitations are recorded in `docs/DEPENDENCY-REVIEW.md`. Weather data comes from Open-Meteo; location lookup is optional and uses the selected provider.

## Contributions

By submitting a contribution you agree to license it under the GPLv3, the same
terms as the rest of the project.
