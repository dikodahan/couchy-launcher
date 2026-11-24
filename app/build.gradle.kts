plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun oauthCredential(name: String, env: String): String {
    System.getenv(env)?.takeIf { it.isNotBlank() }?.let { return it }
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }?.let { return it }
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        local.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || !trimmed.contains("=")) return@forEach
            val idx = trimmed.indexOf('=')
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key == name && value.isNotBlank()) return value
        }
    }
    return ""
}

fun quotedBuildConfig(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val githubUpdates = providers.gradleProperty("githubUpdates").isPresent
val telegramApiId = oauthCredential("telegram.api.id", "TELEGRAM_API_ID").toIntOrNull() ?: 0
val telegramApiHash = oauthCredential("telegram.api.hash", "TELEGRAM_API_HASH")
if (providers.gradleProperty("requireTelegramCloud").isPresent) {
    check(telegramApiId > 0 && telegramApiHash.matches(Regex("[0-9a-fA-F]{32}"))) {
        "Telegram Cloud requires Couchy's API ID and hash. Configure the build credentials before publishing."
    }
}


android {
    namespace = "com.conreo.couchytv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.conreo.couchytv"
        minSdk = 28
        targetSdk = 34
        versionCode = 11
        versionName = "1.1.3"
        buildConfigField("boolean", "GITHUB_UPDATES", githubUpdates.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Telegram client (api_id + api_hash from https://my.telegram.org) for
        // optional Saved-Messages backup. Empty = Cloud options toast "not configured".
        // Set via local.properties, gradle.properties, or env (never commit secrets).
        buildConfigField(
            "int",
            "TELEGRAM_API_ID",
            telegramApiId.toString(),
        )
        buildConfigField(
            "String",
            "TELEGRAM_API_HASH",
            quotedBuildConfig(telegramApiHash),
        )
    }

    // Release signing key, supplied by CI via environment variables (from GitHub
    // secrets). Absent locally and on F-Droid, so this config stays inert there.
    val ciKeystore = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (ciKeystore != null) {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    if (githubUpdates) {
        sourceSets.getByName("release").apply {
            manifest.srcFile("src/githubUpdates/AndroidManifest.xml")
            res.srcDir("src/githubUpdates/res")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signing priority:
            //  • CI (KEYSTORE_FILE env set)  → your real release key, from secrets.
            //  • -PlocalSign                 → the auto-generated debug key (local test).
            //  • otherwise (incl. F-Droid)   → UNSIGNED; F-Droid signs with its own key.
            signingConfig = when {
                ciKeystore != null -> signingConfigs.getByName("ci")
                project.hasProperty("localSign") -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }

    // Reproducibility for F-Droid: don't embed the Google-signed dependency
    // metadata block in the artifact.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    bundle { language { enableSplit = false } }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/tdlibJni"))
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Media3's transitive Guava version predates the native-deserialization allocation fix.
    constraints { implementation("com.google.guava:guava:33.8.0-android") }
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")
    // iOS-style continuous (squircle) corners — perceptually smoother than the
    // circular-arc RoundedCornerShape.
    implementation("androidx.graphics:graphics-shapes:1.0.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Applies the bundled baseline profile (src/main/baseline-prof.txt) so the
    // startup + scroll paths are AOT-compiled on first run instead of JIT'd —
    // the big cold-start "screen shows but not smooth yet" win.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Video wallpaper playback — plays Apple .mov aerials that MediaPlayer can't
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    // Public aerial streaming uses normal certificate and hostname validation.
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    // QR encoding for Telegram device-link on the TV (no camera on the box).
    implementation("com.google.zxing:core:3.5.4")
    // Reach ActivityManager.forceStopPackage / PackageManager.deleteApplicationCacheFiles
    // (hidden on API 28+). Apache-2.0; F-Droid-safe. Permissions still required.
}

// Native-only TDLib 1.8.69 / OpenSSL 3.5.9. The wrapper is alpha; its Kotlin/API-36 classes are not packaged.
// Android 9/API 28 is the launcher minimum and also covers the native API-24 requirement.
// We vendor a matching JNI shim in io.xbot.tdlib and unpack the .so files here.
val tdlibAar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    tdlibAar("io.github.xephosbot:tdlib-kmp-android:1.8.69-1.0.0-alpha03")
}

val extractTdlibJni by tasks.registering {
    val dest = layout.buildDirectory.dir("generated/tdlibJni")
    inputs.files(tdlibAar)
    outputs.dir(dest)
    doLast {
        copy {
            from(zipTree(tdlibAar.singleFile))
            include("jni/**/*.so")
            eachFile {
                relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
            }
            includeEmptyDirs = false
            into(dest.get().asFile)
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(extractTdlibJni)
}

// Keep transitive versions reproducible; update deliberately with --write-locks.
dependencyLocking { lockAllConfigurations() }

// AGP creates isolated host-side UTP configurations; root buildscript patches do not reach them.
configurations.configureEach {
    if (name.startsWith("_internal-unified-test-platform-")) {
        resolutionStrategy.eachDependency {
            val patched = when (requested.group) {
                "io.netty" -> "4.1.137.Final"
                "com.google.testing.platform" -> "0.0.9-alpha04"
                else -> when ("${requested.group}:${requested.name}") {
                    "com.google.guava:guava" -> "33.8.0-jre"
                    "com.google.protobuf:protobuf-java", "com.google.protobuf:protobuf-java-util",
                    "com.google.protobuf:protobuf-kotlin", "com.google.protobuf:protobuf-kotlin-lite" -> "4.36.2"
                    "commons-io:commons-io" -> "2.20.0"
                    else -> null
                }
            }
            if (patched != null) useVersion(patched)
        }
    }
}
