package com.conreo.couchytv.ui

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

@Composable
internal fun FillDialogWindow() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
}

@Composable
internal fun PositionDialogAboveKeyboard() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val topPadding = with(LocalDensity.current) { 24.dp.roundToPx() }
    DisposableEffect(window, topPadding) {
        if (window != null) {
            val originalGravity = window.attributes.gravity
            val originalY = window.attributes.y
            val originalSoftInputMode = window.attributes.softInputMode
            window.attributes = window.attributes.apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = topPadding
            }
            window.setSoftInputMode(
                (originalSoftInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            onDispose {
                window.attributes = window.attributes.apply {
                    gravity = originalGravity
                    y = originalY
                }
                window.setSoftInputMode(originalSoftInputMode)
            }
        } else {
            onDispose {}
        }
    }
}
