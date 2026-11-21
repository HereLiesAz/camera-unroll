package com.hereliesaz.photounroll

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import io.github.vinceglb.filekit.PlatformFile
import org.w3c.files.File
import kotlin.js.JsAny

/** Installs page-wide dragover/drop listeners; returns a function that removes them. */
@JsFun(
    """(cb) => {
  const over = (e) => { if (e.dataTransfer && [...e.dataTransfer.types].includes('Files')) { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; } };
  const drop = (e) => {
    if (!e.dataTransfer || !e.dataTransfer.files.length) return;
    e.preventDefault();
    const all = [...e.dataTransfer.files];
    const video = all.find((f) => f.type.startsWith('video/'));
    const picked = video ? [video] : all.filter((f) => f.type.startsWith('image/'));
    if (picked.length) cb(picked, !!video);
  };
  window.addEventListener('dragover', over);
  window.addEventListener('drop', drop);
  return () => { window.removeEventListener('dragover', over); window.removeEventListener('drop', drop); };
}""",
)
private external fun installDrop(cb: (files: JsAny, isVideo: Boolean) -> Unit): JsAny

@JsFun("(f) => f()")
private external fun invoke(f: JsAny)

@JsFun("(a) => a.length")
private external fun length(a: JsAny): Int

@JsFun("(a, i) => a[i]")
private external fun fileAt(a: JsAny, i: Int): File

@Composable
actual fun FileDropEffect(onImages: (List<PlatformFile>) -> Unit, onVideo: (PlatformFile) -> Unit) {
    val images = rememberUpdatedState(onImages)
    val video = rememberUpdatedState(onVideo)
    DisposableEffect(Unit) {
        val remove = installDrop { arr, isVideo ->
            val files = List(length(arr)) { PlatformFile(fileAt(arr, it)) }
            if (isVideo) video.value(files.first()) else images.value(files)
        }
        onDispose { invoke(remove) }
    }
}
