// Patched build-only transitive dependencies inherited from AGP 8.13.2.
// R8 9.1.57 exceeds the documented 9.1.29 minimum for Kotlin 2.4 class files.
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("com.android.tools:r8:9.1.57") }
    configurations.named("classpath") {
        resolutionStrategy.activateDependencyLocking()
        resolutionStrategy.eachDependency {
            val patched = when (requested.group) {
                "io.netty" -> "4.1.137.Final"
                "com.google.testing.platform" -> "0.0.9-alpha04"
                "org.bouncycastle" -> "1.85"
                else -> when ("${requested.group}:${requested.name}") {
                    "com.google.guava:guava" -> "33.8.0-jre"
                    "com.google.protobuf:protobuf-java", "com.google.protobuf:protobuf-java-util",
                    "com.google.protobuf:protobuf-kotlin", "com.google.protobuf:protobuf-kotlin-lite" -> "4.36.2"
                    "commons-io:commons-io" -> "2.20.0"
                    "org.apache.commons:commons-compress" -> "1.28.0"
                    "org.bitbucket.b_c:jose4j" -> "0.9.6"
                    "org.jdom:jdom2" -> "2.0.6.1"
                    else -> null
                }
            }
            if (patched != null) useVersion(patched)
        }
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
