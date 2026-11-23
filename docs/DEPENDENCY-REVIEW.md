# Dependency and compatibility review — Couchy Launcher 1.1.0

Reviewed 23 November 2026. This review covers the resolved app, test and build-tool Maven graph, the packaged TDLib native library, download integrity, CI privileges and optional network services. Advisory matching is a dated check, not a guarantee that dependencies contain no defects.

## Feature contributions and compatibility

Based on `dikodahan/couchy-launcher` at `f36b14c9c29b8adfdbbfa2a7a067a7cef133643b`, which includes Telegram/ADB backups and hidden-API close-app support. Adapted upstream contributions, without merging their branches:

| Contribution | Author | Reviewed head |
|---|---|---|
| [Weather and seven-day forecast, PR #19](https://github.com/conreo/couchy-launcher/pull/19) | Xaero252 | `14f4485bcf19ecbfdb029a0b4b42ba0d17a1a59e` |
| [App customization, PR #20](https://github.com/conreo/couchy-launcher/pull/20) | Xaero252 | `7e672353fb704f29b172672f790d3b0108ec6232` |
| [Performance and aerial playback, PR #27](https://github.com/conreo/couchy-launcher/pull/27) | 9Chrk | `3ee704c941bbe17ded2f7a9fed153fee52768377` |

Weather defaults to disabled, changes refresh immediately, responses and timeouts are bounded, cached forecasts cannot cross locations/units, and polling stops when Home is hidden. Photo imports use bounds-first downsampling rather than loading entire input files, image writes are atomic, and pack-wide art fills only banner-less apps. Aerial cache initialization stays off the UI thread, storage/memory tiers cap its size, and cache errors allow ordinary streaming. The automatic disappearing-dock behavior from PR #27 was not included; Up-to-collapse navigation is retained. Existing JSON backups receive defaults for the new settings. Export, document import, Telegram import and ADB import share one size-limited codec. No profile or undo features from PR #29/#30 are included.

## Risks addressed

- **TLS bypass:** removed the previous trust-all certificate manager and hostname verifier from aerial playback. Only ordinary validated HTTPS is used; cleartext is disabled where Android supports that policy. Older TVs with stale CA stores may use the gradient fallback or local media.
- **Guava runtime dependency:** Media3 inherited Guava 33.0.0-android; constrained it to 33.8.0-android, beyond the fix for [GHSA-xxph-c9ww-hj94](https://github.com/advisories/GHSA-xxph-c9ww-hj94).
- **Build-tool dependencies:** the initial scan identified affected Kotlin Gradle plugin, Netty, protobuf-java, Commons IO/Compress, Bouncy Castle, jose4j and JDOM versions. AGP is updated to 8.13.2, UTP to 0.0.9-alpha04 and protobuf Java/Kotlin runtimes to 4.36.2. Kotlin tooling is updated to 2.4.21, with published R8 9.1.57 (above the documented 9.1.29 minimum) for Kotlin 2.4 class-file support. AGP's affected transitive build dependencies are pinned to patched compatible releases rather than shipping them inside the APK. The overrides also cover AGP’s isolated host-side Android test (UTP) configurations. The release build, shrinker and connected tests are validated with these overrides.
- **Integrity and reproducibility:** the wrapper validates its distribution URL and official SHA-256. Resolved dependency lockfiles and SHA-256 verification metadata pin future downloads, including the native-only AAR. The metadata establishes a reviewed baseline; it is not proof that upstream binaries were independently reproduced. CI actions use immutable commits, PR builds have read-only repository access and do not receive Telegram credentials, and release write privileges are scoped to the tagged-release job.
- **Credential portability:** Android cloud backup and device transfer exclude the `files/tdlib/` session directory. Home switching uses a fixed-loopback ADB client with an AndroidKeyStore private key and a device-local recovery journal excluded from OS/portable backup; see [the REA review](LAUNCHER-MANAGER-REVIEW.md). The exported backup Activity requires the system DUMP permission, permitting ADB shell use while blocking ordinary third-party apps from silently restoring launcher settings.

## Telegram native dependency

The old `io.github.xephosbot:tdlib-kmp-android:1.8.62` native binaries contain OpenSSL 1.1.1w, which [left public support in 2023](https://openssl-corporation.org/blog/2023-09-11-eol-111.html). They are replaced by the native files from `1.8.69-1.0.0-alpha03`, containing TDLib 1.8.69 and OpenSSL 3.5.9. This is the same publisher's maintained [td-kmp project](https://github.com/td-kmp/tdlib-kmp). Its Kotlin wrapper is an alpha release; only its pinned native `.so` files are used, and none of its Kotlin 2.3/API-36 wrapper classes or transitive dependencies are packaged.

Release provenance: [td-kmp/td-pack v1.8.69](https://github.com/td-kmp/td-pack/releases/tag/v1.8.69), whose TDLib submodule is [tdlib/td at `4c6377aa7386c39217878a9a56d581922ad37ca4`](https://github.com/tdlib/td/tree/4c6377aa7386c39217878a9a56d581922ad37ca4). The JNI bridge includes the callback signature required by the new native registration. An instrumented test loads the native library and executes a version query without credentials or network login.

The updated native build requires API 24; this fork now uniformly requires **Android 9/API 28**. Obsolete pre-28 branches and legacy JNI extraction packaging are removed. All four ABIs remain packaged; native libraries load directly from the APK with modern alignment. Native ELF load alignment is checked separately from ZIP alignment; this is not a substitute for testing a physical 16 KiB-page device.

**Remaining native advisory limitation:** OpenSSL 3.5.9 is not 3.5.10. The [official OpenSSL 3.5 advisory list](https://openssl-library.org/news/vulnerabilities-3.5/) includes later fixes for QUIC, CMS encryption, AES-GCM-SIV, Suite B server configuration and caller-supplied PEM stacks. A search of the exact TDLib source above found no direct calls or references to `CMS_encrypt`, QUIC APIs, AES-GCM-SIV, `SSL_CTX_set_cert_flags`/Suite B, or `PEM_X509_INFO_read*`. These specific paths therefore have no identified caller in this use, but this source-level assessment is not a proof of unreachability in every native binary. A future native release using OpenSSL 3.5.10+ should replace this pin when its JNI/API compatibility has been tested. No claim is made that all embedded native vulnerabilities are eliminated.

## Generated-code findings beyond package advisory matching

Connected tests with AGP 8.5.2 exposed unsafe pre-22 protobuf-generated UTP TestCase/Result classes even after the runtime had been patched. AGP 8.13.2 and the UTP 0.0.9-alpha04 protocol artifacts replace those generated classes; Java/Kotlin protobuf runtimes are aligned to 4.36.2. This is why a version-only advisory scan is not the entire review. UTP still declares OpenCensus proto 0.2.0, which contains older generated-code markers in a host-side tracing dependency; it is not packaged in Couchy's APK. Its tracing reachability is not established by the package scan. The final connected-test log is checked separately for emitted unsafe-gencode warnings.

## External services and feature limits

Weather, aerial streaming and Telegram are optional external services, subject to outages and endpoint/format changes. Forecast coordinates go to Open-Meteo. With explicit opt-in, IP location uses GeoJS once per launch. City search uses Open-Meteo Geocoding, and postal lookup uses Zippopotam.us. GeoJS currently publishes no fixed rate limit but reserves the right to throttle usage. Forecast attribution remains in the README. No device location permission, trackers, Google Play Services or proprietary runtime SDKs are added.

JSON backups include preferences and selected pack names, not custom media, installed packs, document grants or Telegram session credentials. Missing packs/media on a destination TV fall back to original app art or the preset background. Close & clear cache uses an approved local TV debugging connection and Android’s cache-only command. This removed the HiddenApiBypass dependency.

The supplied local build does not receive repository secrets or a production signing key. A locally signed release must match an installed app's certificate to update it in place. Full Telegram login/save/restore requires configured app credentials and a user account, and is outside the account-free emulator checks.

## Recorded validation

- Every resolvable app/auxiliary-test/compiler configuration plus buildscript classpaths: **346 distinct Maven modules, zero active OSV advisories** on 23 November 2026. Native cryptography and generated-code limitations are assessed separately above.
- **26 JVM tests passed**: weather/backup/artwork compatibility, ADB framing/authentication, output limits, timeouts (including a blocked writer), cancellation and command identifier checks.
- **8 Android TV API-31 device tests passed, none skipped**: the native Telegram bridge, backup/import and artwork behavior, non-exportable Keystore key signing, Home mutation rejection/recovery, exact component commands and cross-user rejection.
- Debug and optimized release builds succeeded; release lint reported **zero errors**. The current connected-test log emitted no unsafe protobuf generated-code warnings.
- APK identity: `com.conreo.couchytv`, version `1.1.0`, version code `8`, min SDK `28`, target SDK `34`, four native ABIs. Signing, ZIP alignment and native ELF alignment are checked separately.
- The normal Home picker opens in the emulator. The embedded ADB client reached the real Android RSA authorization prompt; the user explicitly approved granting its emulator key for the interactive switch/restore test. The optional pause-stock switch to Couchy and in-app restoration/switch back to Android TV Home both succeeded. This image could not persist ADB keys; see the Home analysis report for that limitation.
- Physical TV/OEM restrictions, authenticated Telegram round trips, real third-party icon packs and a physical 16 KiB-page device remain outside these checks. A package advisory scan does not prove all embedded code safe.

## Re-running the checks

```sh
./gradlew testDebugUnitTest assembleDebug assembleRelease lintRelease --no-daemon
./gradlew dependencyInventory -I scripts/dependency-inventory.init.gradle
python3 scripts/audit-dependencies.py
./gradlew connectedDebugAndroidTest
```

Intentional dependency changes require a reviewed `--write-locks --write-verification-metadata sha256` run, followed by a normal strict-verification build. The OSV script scans exact Maven coordinates and retains raw advisory data in `build/reports/dependencies/osv-audit.json`. Embedded OpenSSL is reviewed separately because Maven advisory matching does not inspect its contents.
