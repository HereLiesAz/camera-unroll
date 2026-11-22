package com.hereliesaz.cameraunroll

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile

/**
 * Accepts files dropped anywhere on the page while composed. [onImages] gets the dropped
 * images, or the first dropped video (videos win; one lap per import). No-op where the
 * platform has no page to drop onto.
 */
@Composable
expect fun FileDropEffect(onImages: (List<PlatformFile>) -> Unit, onVideo: (PlatformFile) -> Unit)
