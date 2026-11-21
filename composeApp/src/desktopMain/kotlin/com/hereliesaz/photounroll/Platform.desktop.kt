package com.hereliesaz.photounroll

import androidx.compose.runtime.Composable

@Composable
actual fun BackAction(enabled: Boolean, onBack: () -> Unit) {}

@Composable
actual fun KeepScreenOn(on: Boolean) {}

actual object Limits {
    actual val editMaxDim = 1600
    actual val finalMaxDim = 2400
}
