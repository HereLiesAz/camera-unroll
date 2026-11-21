package com.hereliesaz.photounroll

import androidx.compose.runtime.Composable

/** Runs [onBack] on the system back gesture or button while [enabled]; elsewhere, nothing. */
@Composable
expect fun BackAction(enabled: Boolean, onBack: () -> Unit)

/** Stops the display from sleeping while [on] (a capture in progress, a long render). */
@Composable
expect fun KeepScreenOn(on: Boolean)

/** Working sizes, set by how much memory the platform can spare. */
expect object Limits {
    /** Longest side of the copy of each photo that is edited and analyzed on screen. */
    val editMaxDim: Int

    /** Longest side of the copy of each photo the final result is rendered from. */
    val finalMaxDim: Int
}
