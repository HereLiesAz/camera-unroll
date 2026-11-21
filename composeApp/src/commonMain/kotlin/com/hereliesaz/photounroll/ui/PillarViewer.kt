package com.hereliesaz.photounroll.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The unrolled strip wrapped back onto a pillar you can spin by dragging (it keeps turning
 * when flung). Only the part that was photographed is painted: [arc] radians of surface,
 * centered on the side first shown; the rest of the way around is bare.
 *
 * Drawn without a 3D engine, as the unroll run backwards: the pillar is cut into thin vertical
 * slices, and the slice at screen offset x from the axis shows the surface at angle asin(x/r).
 * Each slice is sunk by the rim's ellipse and darkened toward the silhouette, which is all a
 * cylinder's look amounts to.
 *
 * @param arc radians of surface the strip covers (2π when it closes on itself)
 * @param heightRadii the strip's height in pillar radii, which fixes the pillar's proportions
 * @param startYaw how far the pillar is turned when first shown (radians; 0 faces the strip's middle)
 */
@Composable
fun PillarViewer(bitmap: ImageBitmap, arc: Float, heightRadii: Float, modifier: Modifier = Modifier, startYaw: Float = 0f) {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val yaw = remember(bitmap) { Animatable(startYaw) }

    Canvas(
        modifier.clipToBounds().pointerInput(bitmap) {
            val tracker = VelocityTracker()
            var radius = 1f
            detectHorizontalDragGestures(
                onDragStart = {
                    tracker.resetTracking()
                    radius = pillarRadius(Size(size.width.toFloat(), size.height.toFloat()), heightRadii)
                    scope.launch { yaw.stop() }
                },
                onDragEnd = {
                    val v = tracker.calculateVelocity().x / radius
                    scope.launch { yaw.animateDecay(v, decay) }
                },
            ) { change, dx ->
                tracker.addPosition(change.uptimeMillis, change.position)
                change.consume()
                // A point on the near surface follows the finger.
                scope.launch { yaw.snapTo(yaw.value + dx / radius) }
            }
        },
    ) {
        val r = pillarRadius(size, heightRadii)
        val body = r * heightRadii * cos(TILT)
        val sink = r * kotlin.math.sin(TILT) // how far the near edge of a rim sits below its far edge
        val cx = size.width / 2f
        val top = (size.height - body) / 2f

        // The top face, seen from slightly above.
        drawOval(CAP, Offset(cx - r, top - sink), Size(2f * r, 2f * sink))

        // Slices sit on whole pixels, edge to edge: no gaps to hide, no overlaps to darken twice.
        val slice = max(1, (r / 220f).roundToInt())
        val left = (cx - r).roundToInt()
        val right = (cx + r).roundToInt()
        val bodyPx = body.roundToInt()
        var px = left
        while (px < right) {
            val next = min(right, px + slice)
            val t0 = asin(((px - cx) / r).coerceIn(-1f, 1f))
            val t1 = asin(((next - cx) / r).coerceIn(-1f, 1f))
            val mid = (t0 + t1) / 2f
            val at = IntOffset(px, (top + sink * cos(mid)).roundToInt())
            val box = IntSize(next - px, bodyPx)
            // Where on the strip this slice looks: surface angle, measured from the strip's middle.
            val u0 = wrap(t0 - yaw.value) / arc + 0.5f
            val u1 = u0 + (t1 - t0) / arc
            if (u1 <= 0f || u0 >= 1f) {
                drawRect(BARE, Offset(at.x.toFloat(), at.y.toFloat()), Size(box.width.toFloat(), box.height.toFloat()))
            } else {
                val sx0 = (u0.coerceIn(0f, 1f) * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
                val sx1 = (u1.coerceIn(0f, 1f) * bitmap.width).roundToInt().coerceIn(sx0 + 1, bitmap.width)
                drawImage(
                    bitmap,
                    srcOffset = IntOffset(sx0, 0),
                    srcSize = IntSize(sx1 - sx0, bitmap.height),
                    dstOffset = at,
                    dstSize = box,
                    filterQuality = FilterQuality.Medium,
                )
            }
            // The surface turns away from the viewer toward the silhouette, and darkens.
            drawRect(
                Color.Black.copy(alpha = SHADE * (1f - cos(mid))),
                Offset(at.x.toFloat(), at.y.toFloat()), Size(box.width.toFloat(), box.height.toFloat()),
            )
            px = next
        }
    }
}

/** Largest pillar that fits: most of the height, and never wider than most of the width. */
private fun pillarRadius(canvas: Size, heightRadii: Float): Float =
    min(canvas.height * 0.86f / (heightRadii * cos(TILT)), canvas.width * 0.4f)

/** Angle folded into (-π, π]. */
private fun wrap(a: Float): Float {
    val turn = (2 * PI).toFloat()
    var w = a % turn
    if (w > PI) w -= turn
    if (w <= -PI) w += turn
    return w
}

/** The viewer looks down on the pillar by this much (radians). */
private const val TILT = 0.2f
private const val SHADE = 0.75f
private val BARE = Color(0xFF2E2E2E)
private val CAP = Color(0xFF161616)
