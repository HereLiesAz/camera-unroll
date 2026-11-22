package com.hereliesaz.cameraunroll.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.cameraunroll.capture.CameraSource
import com.hereliesaz.cameraunroll.capture.CaptureGuide
import com.hereliesaz.cameraunroll.imaging.ImageCodec
import com.hereliesaz.cameraunroll.imaging.RgbaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Guided capture of a pillar: the camera's view with the pillar's place drawn on it, and the
 * photos taken by themselves.
 *
 * The photographer's whole job is to walk a circle keeping the pillar centered, upright and
 * the same size, so the guide is the pillar: a ghost column to fit the real one into. While
 * the fit is good the column turns solid and a ring around its middle fills; when the ring
 * closes the photo is taken. No shutter. After each photo a bar shows the walk to the next
 * spot, and a small plan view of the pillar fills in with what has been covered.
 *
 * @param onDone the photos taken (JPEG bytes, in capture order); empty when cancelled
 */
@Composable
fun CaptureScreen(onDone: (List<ByteArray>) -> Unit, modifier: Modifier = Modifier) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var reading by remember { mutableStateOf<CaptureGuide.Reading?>(null) }
    var fault by remember { mutableStateOf<CaptureGuide.Fault?>(CaptureGuide.Fault.NoPillar) }
    var hold by remember { mutableFloatStateOf(0f) }
    var stepDeg by remember { mutableStateOf<Float?>(null) }
    var count by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    // Coverage, in degrees around the pillar relative to the first photo.
    var here by remember { mutableFloatStateOf(0f) }
    var from by remember { mutableFloatStateOf(0f) }
    var to by remember { mutableFloatStateOf(0f) }
    val shots = remember { ArrayList<ByteArray>() }
    var reference by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(Unit) { onDispose { CameraSource.stop() } }

    LaunchedEffect(Unit) {
        if (!CameraSource.start()) { failed = true; return@LaunchedEffect }
        var lastFace: RgbaImage? = null
        var lastAngle = 0f
        var goodSince: TimeSource.Monotonic.ValueTimeMark? = null
        while (isActive) {
            val img = CameraSource.preview(PREVIEW_DIM)
            if (img == null) { delay(100); continue }
            frame = ImageCodec.toImageBitmap(img)
            val (r, face, step) = withContext(Dispatchers.Default) {
                val r = CaptureGuide.read(img)
                val face = r?.let { CaptureGuide.face(img, it) }
                val step = if (face != null && lastFace != null) CaptureGuide.step(lastFace!!, face) else null
                Triple(r, face, step)
            }
            reading = r
            // With no pillar in view the walk is unknown, not lost: the "aim" hint comes first.
            stepDeg = if (lastFace == null) null else step ?: Float.NaN
            if (step != null && !step.isNaN()) here = lastAngle + step
            val f = CaptureGuide.assess(r, reference, stepDeg)
            fault = f
            if (f != null || face == null || r == null) {
                goodSince = null
                hold = 0f
            } else {
                val since = goodSince ?: TimeSource.Monotonic.markNow().also { goodSince = it }
                hold = min(1f, since.elapsedNow().inWholeMilliseconds / HOLD_MS)
                if (hold >= 1f) {
                    CameraSource.capture()?.let { bytes ->
                        shots += bytes
                        count = shots.size
                        if (reference == null) reference = r.width
                        lastFace = face
                        lastAngle = here
                        from = min(from, here)
                        to = max(to, here)
                    }
                    goodSince = null
                    hold = 0f
                }
            }
            delay(30)
        }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val bmp = frame
            if (failed) {
                Text("The camera is not available.\nAllow camera access, or add photos instead.", color = Ink.Smoke, lineHeight = 22.sp)
            } else if (bmp != null) {
                Canvas(Modifier.fillMaxSize()) {
                    // The live view, fitted whole.
                    val s = min(size.width / bmp.width, size.height / bmp.height)
                    val w = bmp.width * s
                    val h = bmp.height * s
                    val x0 = (size.width - w) / 2f
                    val y0 = (size.height - h) / 2f
                    drawImage(
                        bmp, dstOffset = IntOffset(x0.roundToInt(), y0.roundToInt()),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    )
                    val ready = fault == null
                    val line = 2.dp.toPx()

                    // Where the pillar is seen now: thin, so the ghost stays the thing to match.
                    reading?.let { r ->
                        for (x in listOf(r.left, r.right)) {
                            drawLine(Ink.Smoke, Offset(x0 + x * w, y0), Offset(x0 + x * w, y0 + h), strokeWidth = 1.dp.toPx())
                        }
                    }
                    // The ghost column: where the pillar belongs. Dashed while wrong, solid when right.
                    val ghost = (reference ?: CaptureGuide.FIRST_WIDTH) * w
                    val gx = x0 + (w - ghost) / 2f
                    val dash = if (ready) null else PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx()))
                    for (x in listOf(gx, gx + ghost)) {
                        drawLine(
                            Ink.Bone.copy(alpha = if (ready) 1f else 0.6f), Offset(x, y0), Offset(x, y0 + h),
                            strokeWidth = if (ready) 2f * line else line, pathEffect = dash,
                        )
                    }
                    // The hold ring: fills while everything is right; closing it takes the photo.
                    val ring = min(ghost, h) * 0.22f
                    val center = Offset(x0 + w / 2f, y0 + h / 2f)
                    drawCircle(Ink.Bone.copy(alpha = 0.25f), ring, center, style = Stroke(line))
                    if (hold > 0f) drawArc(
                        Ink.Bone, -90f, 360f * hold, useCenter = false,
                        topLeft = center - Offset(ring, ring), size = Size(2f * ring, 2f * ring),
                        style = Stroke(3f * line, cap = StrokeCap.Round),
                    )
                    // The walk to the next spot: a bar under the ring that fills as the surface slides by.
                    stepDeg?.takeIf { !it.isNaN() }?.let { step ->
                        val bar = ghost * 0.8f
                        val by = center.y + ring + 18.dp.toPx()
                        val done = (abs(step) / CaptureGuide.TARGET_STEP_DEG).coerceIn(0f, 1f)
                        drawLine(Ink.Bone.copy(alpha = 0.25f), Offset(center.x - bar / 2f, by), Offset(center.x + bar / 2f, by), 3f * line, StrokeCap.Round)
                        if (done > 0f) drawLine(
                            Ink.Bone, Offset(center.x - bar / 2f, by), Offset(center.x - bar / 2f + bar * done, by), 3f * line, StrokeCap.Round,
                        )
                    }
                    // Plan view: the pillar from above, with what has been photographed so far.
                    if (count > 0) {
                        val pr = 26.dp.toPx()
                        val pc = Offset(x0 + w - pr - 14.dp.toPx(), y0 + pr + 14.dp.toPx())
                        drawCircle(Color.Black.copy(alpha = 0.55f), pr + 6.dp.toPx(), pc)
                        drawCircle(Ink.Bone.copy(alpha = 0.25f), pr, pc, style = Stroke(3f * line))
                        val covered = min(360f, to - from + CaptureGuide.PHOTO_ARC_DEG)
                        // Up is the side first photographed; the camera's walk turns the arc.
                        drawArc(
                            Ink.Bone, -90f + from - CaptureGuide.PHOTO_ARC_DEG / 2f, covered, useCenter = false,
                            topLeft = pc - Offset(pr, pr), size = Size(2f * pr, 2f * pr), style = Stroke(3f * line),
                        )
                        val a = (-90f + here) * kotlin.math.PI / 180.0
                        drawCircle(Ink.Bone, 4.dp.toPx(), pc + Offset((pr * kotlin.math.cos(a)).toFloat(), (pr * kotlin.math.sin(a)).toFloat()))
                    }
                }
                Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        fault?.hint ?: "hold still",
                        color = Ink.Bone, fontSize = 18.sp, letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onDone(emptyList()) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("CANCEL", fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            Text(
                if (count == 0) "fit the pillar between the lines"
                else "$count taken  ·  ${min(360f, to - from + CaptureGuide.PHOTO_ARC_DEG).roundToInt()}° of 360°",
                color = Ink.Smoke, fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onDone(shots.toList()) }, enabled = count > 0, modifier = Modifier.heightIn(min = 56.dp)) {
                Text("DONE", fontSize = 15.sp)
            }
        }
    }
}

private const val PREVIEW_DIM = 480
/** How long everything must stay right before the photo is taken. */
private const val HOLD_MS = 900f
