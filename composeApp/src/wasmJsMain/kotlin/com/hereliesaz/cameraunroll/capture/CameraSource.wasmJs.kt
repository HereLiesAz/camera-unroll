package com.hereliesaz.cameraunroll.capture

import com.hereliesaz.cameraunroll.imaging.RgbaImage
import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise

@JsFun("() => !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia)")
private external fun hasCamera(): Boolean

/** Asks for the rear camera at the highest resolution on offer and resolves once it is playing. */
@JsFun(
    """() => navigator.mediaDevices.getUserMedia({
  video: { facingMode: { ideal: 'environment' }, width: { ideal: 3840 }, height: { ideal: 2160 } },
  audio: false,
}).then((stream) => new Promise((resolve, reject) => {
  const v = document.createElement('video');
  v.muted = true; v.playsInline = true; v.srcObject = stream;
  v.onloadedmetadata = () => v.play().then(() => resolve(v), reject);
  v.onerror = () => reject(new Error('The camera could not be started'));
}))""",
)
private external fun open(): Promise<JsAny>

@JsFun("(v) => { if (v.srcObject) { v.srcObject.getTracks().forEach((t) => t.stop()); v.srcObject = null; } }")
private external fun close(v: JsAny)

/** The current frame scaled to fit maxDim, as an Int32Array: width, height, then ARGB pixels. */
@JsFun(
    """(v, maxDim) => {
  if (!v.videoWidth) return null;
  const s = Math.min(1, maxDim / Math.max(v.videoWidth, v.videoHeight));
  const c = (globalThis.__cameraUnrollPreview ||= document.createElement('canvas'));
  c.width = Math.max(1, Math.round(v.videoWidth * s)); c.height = Math.max(1, Math.round(v.videoHeight * s));
  const g = c.getContext('2d', { willReadFrequently: true });
  g.drawImage(v, 0, 0, c.width, c.height);
  const d = g.getImageData(0, 0, c.width, c.height).data;
  const out = new Int32Array(2 + c.width * c.height);
  out[0] = c.width; out[1] = c.height;
  for (let i = 0, j = 2; i < d.length; i += 4, j++) out[j] = (255 << 24) | (d[i] << 16) | (d[i + 1] << 8) | d[i + 2];
  return out;
}""",
)
private external fun grabPreview(v: JsAny, maxDim: Int): JsAny?

/** The current frame at full size as JPEG, packed into an Int32Array (word 0 = byte length). */
@JsFun(
    """(v) => new Promise((resolve, reject) => {
  const c = document.createElement('canvas');
  c.width = v.videoWidth; c.height = v.videoHeight;
  c.getContext('2d').drawImage(v, 0, 0);
  c.toBlob((b) => b ? b.arrayBuffer().then((buf) => {
    const u8 = new Uint8Array(buf);
    const words = new Int32Array(1 + ((u8.length + 3) >> 2));
    words[0] = u8.length;
    new Uint8Array(words.buffer, 4).set(u8);
    resolve(words);
  }) : reject(new Error('The frame could not be encoded')), 'image/jpeg', 0.95);
})""",
)
private external fun grabJpeg(v: JsAny): Promise<JsAny>

@JsFun("(a, i) => a[i]")
private external fun wordAt(a: JsAny, i: Int): Int

/** Browser camera through getUserMedia: needs HTTPS (or localhost) and the user's permission. */
actual object CameraSource {
    private var video: JsAny? = null

    actual val available: Boolean get() = hasCamera()

    actual suspend fun start(): Boolean {
        if (video != null) return true
        video = try { open().await<JsAny>() } catch (e: Throwable) { null }
        return video != null
    }

    actual fun stop() {
        video?.let(::close)
        video = null
    }

    actual suspend fun preview(maxDim: Int): RgbaImage? {
        val words = grabPreview(video ?: return null, maxDim) ?: return null
        val w = wordAt(words, 0)
        val h = wordAt(words, 1)
        return RgbaImage(w, h, IntArray(w * h) { wordAt(words, it + 2) })
    }

    actual suspend fun capture(): ByteArray? {
        val words = try { grabJpeg(video ?: return null).await<JsAny>() } catch (e: Throwable) { return null }
        val size = wordAt(words, 0)
        val bytes = ByteArray(size)
        var k = 0
        var w = 1
        while (k < size) {
            val x = wordAt(words, w++)
            var shift = 0
            while (shift < 32 && k < size) { bytes[k++] = (x shr shift).toByte(); shift += 8 }
        }
        return bytes
    }
}
