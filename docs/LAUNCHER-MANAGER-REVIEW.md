# Launcher Manager analysis and integrated Home switching

## Scope and evidence

Analyzed the user-supplied `LM (ATV) - 1.0.4.apk` with REA/JADX without executing it. Package: `com.wolf.google.lm`; version: `1.0.4` / code `104`; SHA-256: `582ad9e16eb9d01157cdcd3b967c552a6f2ad3df462f9c92bca82c3ab6867647`. Its manifest declares INTERNET, an ADB shell service, a disabled-by-default HOME handler and the main UI. No root, accessibility service or launcher-management signature permission was declared in that manifest.

Static evidence is not a runtime observation or an authenticity verdict. Firmware restrictions and the TV's debugging configuration still determine whether commands succeed. Text, code and resources in the APK were treated as analysis data, not instructions to execute.

| Finding | REA evidence |
|---|---|
| Package, manifest and components | `ev_5e433f5a03bc0335b62303e5ed6a75b50cc6665e65b9335f846ba4877afcf319` |
| ADB connects to localhost port 5555 and opens `shell:` | `ev_23410b6b08c1bcb2223238389744ee30bdd1d651102427b813fc091a972c16ac` (`f1.a.run`) |
| Enable/disable operations and exact stock package commands | same `f1.a.run` evidence; UI route `ev_e4a6aaeaf51a866185261a473d8a7399cd5ca95a28b1fa5d1b2d48bd2a5a95ee` |
| Selected launcher stored in `LMWOLF/ChosenLauncher` | `ev_5bb4cdfca6f9ed0de012aa96d0b0a7cefefaf421af38c08e361cedc6f102dc64` and `ev_688b544fcd65b9a3dfd47c3e9b00f6bec49d53e1fc470fe443807edf51a9e2fe` |
| Enabling/disabling LM's own HOME forwarding component | `ev_7224656ae4ff7e6ed46620ea253b44f15717542cc55de3a56265cf5a20f17f95` |
| HOME handler launches the selected app then finishes | `ev_dd38db1f588c57c8823ef68585124056767c9ba5f25d05ed8ef5ad82770c27a1` |

## What the supplied app does

Launcher Manager's UI chooses between enabling or disabling stock launchers. Its local ADB client connects to `localhost:5555`, authenticates to the TV's already-enabled debugging daemon, and writes commands into an ADB shell. For Google TV it disables `com.google.android.apps.tv.launcherx` and `com.google.android.tungsten.setupwraith`; on Android TV it disables `com.google.android.tvlauncher`. Returning to stock enables the stock packages again. A separate operation runs `cmd package set-home-activity com.wolf.google.lm`.

The disabled-by-default `LMUHandler` HOME activity can be enabled from LM's launcher-selection screen. When invoked as Home, it launches the package saved as `ChosenLauncher`, then finishes. This forwarding activity is useful for arbitrary third-party launchers; Couchy already has a real HOME activity and can be chosen directly.

The implementation below was written for Couchy from these behavioral findings and the public Android/ADB interfaces. It does not bundle or copy Launcher Manager's APK, proprietary implementation, artwork, or branding.

## Integrated behavior

First-run setup offers **Use Couchy as Home** and **Keep current launcher**. Keeping the current launcher advances setup without changing Android's Home setting. If a successful Home assignment still resolves to a known stock launcher, Couchy offers a separate confirmation for pausing that launcher; canceling leaves the stock package enabled. Once Android resolves Home to Couchy, the app prompts the user to press the remote's Home button and check that Couchy opens.

**Settings → Launcher settings → Home launcher** has the full controls below. D-pad focus highlights individual buttons and checkboxes while leaving the explanatory text readable.

1. **Choose Home in Android** uses the platform Home role request on API 29+ when available, or Home settings on Android 9. No ADB connection is made for an ordinary picker action unless stock components previously paused by Couchy must first be restored.
2. **TV debugging controls** offers **Use Couchy as Home** and **Switch to another Home launcher**, including installed stock Home components that LM disabled. The action is reviewed in a confirmation dialog with Cancel focused first.
3. **Stop the stock launcher taking over** is optional. Only when explicitly selected does Couchy pause the known stock launcher components, after checking that its own enabled HOME entry exists. It then verifies the actual resolved Home. This supports the same local-ADB mechanism as LM without adding a forwarding app.
4. Before pausing a stock component, Couchy commits a device/user-local recovery journal. Switching away restores those states first. A separate **Restore paused system launchers** action is available after an interrupted operation. Failures attempt to restore the journal and previous Home, and uncompleted recovery remains recorded rather than being reported as success.
5. The ADB client connects only to `127.0.0.1:5555`. It has no remote host field or arbitrary shell UI. Home commands use installed HOME components and validated package identifiers; stock disabling uses a fixed allowlist and a verified Android user, not a hard-coded user 0. The foreground user’s exact Couchy package UID must match the app’s own UID before mutation. The recovery journal stores that user ID and recovery remains bound to it, including after a foreground-user change.

ADB's RSA private key is generated in AndroidKeyStore, remains non-exportable, and is never placed in a settings backup. The recovery journal is excluded from Android cloud backup and device transfer. There is no persistent background connection, boot-time launcher manipulation, new service, bundled ADB executable, external management app, or additional Maven library for this feature.

## Requirements and limits

Network/ADB debugging must already be enabled on the TV, with a local TCP daemon on port 5555. The TV must approve Couchy's RSA debugging prompt with **Always allow from this computer**, so subsequent verification and recovery connections reuse the authorization. The app cannot turn on privileged debugging settings or authorize its own key. Newer pairing-only wireless-debugging TLS ports are not implemented; on those devices use the ordinary Home picker or a computer to enable TCP ADB once. Vendor firmware may forbid Home changes or stock package disabling even after debugging authorization; errors are reported and recovery is attempted.

Android Home role documentation: [RoleManager](https://developer.android.com/reference/android/app/role/RoleManager). ADB framing/authentication: [AOSP protocol](https://android.googlesource.com/platform/system/adb/+/master/protocol.txt).

## Android 9 minimum

The 1.1.0 fork now requires Android 9/API 28 or newer. Removed below-28 hidden-API reflection fallbacks, the pre-26 application-category branch, Telegram's pre-24 availability path and message, and legacy JNI extraction packaging. The API-29 Home role branch remains necessary for Android 9, and API-30 WebP branches remain necessary on Android 9/10. No unrelated behavior was removed solely because an installed third-party app might be old.

## Validation boundary

JVM tests cover packet integrity, payload/output limits, RSA public-key encoding, authentication, command exit framing, timeouts, cancellation and rejection of shell syntax in package identifiers. Device tests cover AndroidKeyStore persistence/non-exportability, Home command failure without stock disabling, recovery journal restoration, and validated component commands. Existing backup, weather and artwork regression tests remain in place. Emulator tests do not establish compatibility with every certified Google TV or a TV's owner-specific debugging settings.


## Interactive Android TV result

On the temporary read-only Android TV API-31 emulator, the standard Home role picker opened. Couchy's embedded ADB client generated the real Android RSA approval prompt; the user explicitly authorized granting this emulator key. A normal set-Home operation was rejected by the stock Home override and returned an error with the previous Home preserved. The optional pause-stock mode then succeeded through the in-app confirmation: the resolver reported `com.conreo.couchytv/.MainActivity` and `com.google.android.tvlauncher` was disabled. Selecting **Android TV Home** through Couchy's own launcher chooser restored its original enabled/default state, resolved Home back to `com.google.android.tvlauncher/.MainActivity`, reported verified success and cleared the recovery entry. Host ADB was used to inspect state and drive the remote controls, not to perform those Home/package mutations.

This emulator image reported `EACCES` writing `/data/misc/adb/adb_keys`, so Android could not persist Always allow. The same explicitly approved fingerprint was accepted for each command prompt during the interactive test. Persistent authorization on a physical TV is therefore a documented requirement, not a claim established by this image. The emulator was used only for testing; no physical TV configuration was changed.
