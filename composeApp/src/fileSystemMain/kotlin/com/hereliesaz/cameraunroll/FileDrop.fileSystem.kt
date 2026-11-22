package com.hereliesaz.cameraunroll

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile

@Composable
actual fun FileDropEffect(onImages: (List<PlatformFile>) -> Unit, onVideo: (PlatformFile) -> Unit) = Unit
