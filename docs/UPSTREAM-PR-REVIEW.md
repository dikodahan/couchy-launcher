# Upstream pull request review

Reviewed 23 November 2026 against `conreo/couchy-launcher`. The user's fork at `f36b14c9c29b8adfdbbfa2a7a067a7cef133643b` was two commits ahead of, and zero commits behind, upstream. Six pull requests were open at the review snapshot.

| PR | Short description | Handling in 1.1.0 |
|---|---|---|
| [#17 — Telegram backups](https://github.com/conreo/couchy-launcher/pull/17) | dikodahan's Telegram Saved Messages backup, local/ADB save and restore, and close-app support. | Already in the fork; retained and reviewed with shared backup validation and native dependency updates. |
| [#19 — Weather](https://github.com/conreo/couchy-launcher/pull/19) | Xaero252's opt-in weather chip and seven-day forecast, with manual, postal or IP-based location. | Adapted; bounded network work, lifecycle polling, request-scoped caches and validated coordinates. |
| [#20 — App customization](https://github.com/conreo/couchy-launcher/pull/20) | Xaero252's per-app names, custom icons/banners and installed icon packs. | Adapted; bounded image decoding, atomic saves and precedence for explicit artwork. |
| [#27 — Performance and aerial playback](https://github.com/conreo/couchy-launcher/pull/27) | 9Chrk's lower-memory app artwork and smoother aerial transitions/caching. | Adapted; decoder lifecycle, cache/storage bounds, retry limits and normal TLS validation. Automatic dock disappearance omitted. |
| [#29 — Restore preview and undo](https://github.com/conreo/couchy-launcher/pull/29) | DVOpenLabs' backup review before applying and persistent settings undo. | Not included in the requested feature scope. |
| [#30 — Household profiles](https://github.com/conreo/couchy-launcher/pull/30) | DVOpenLabs' local profiles with separate settings and undo. | Not included in the requested feature scope. |

No PR branch was merged unchanged. Source changes were reviewed and adapted to the fork's existing backup and launcher behavior. See [dependency/compatibility review](DEPENDENCY-REVIEW.md) and [Home switching analysis](LAUNCHER-MANAGER-REVIEW.md) for validation and remaining limits. The original project and contribution authors are credited in the README.
