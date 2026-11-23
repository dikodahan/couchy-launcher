package com.conreo.couchytv.data

import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test

class HomeCommandsTest {
    @Test fun packageNamesCannotContainShellSyntax() {
        for (bad in listOf("com.example;reboot", "com.example'", "--user", "com.example\nreboot", "com.example/Activity", "com.example app"))
            assertFalse(bad, HomeCommands.validPackage(bad))
        assertTrue(HomeCommands.validPackage("com.google.android.apps.tv.launcherx"))
    }
    @Test fun invalidUsersCannotChangePackageState() {
        assertTrue(runCatching { HomeCommands.disable("com.example.tv", -1) }.isFailure)
        assertTrue(runCatching { HomeCommands.enable("com.example.tv", 0, 3) }.isFailure)
    }
}
