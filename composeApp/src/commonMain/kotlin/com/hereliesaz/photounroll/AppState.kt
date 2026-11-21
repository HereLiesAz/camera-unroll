package com.hereliesaz.photounroll

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.hereliesaz.photounroll.imaging.CylinderGuides
import com.hereliesaz.photounroll.imaging.DepthModel
import com.hereliesaz.photounroll.imaging.EdgeSuggester
import com.hereliesaz.photounroll.imaging.Exif
import com.hereliesaz.photounroll.imaging.Leveler
import com.hereliesaz.photounroll.imaging.Perspective
import com.hereliesaz.photounroll.imaging.ImageCodec
import com.hereliesaz.photounroll.imaging.Pipelines
import com.hereliesaz.photounroll.imaging.PhotoAdjust
import com.hereliesaz.photounroll.video.VIDEO_FRAME_MAX_DIM
import com.hereliesaz.photounroll.video.VideoFrames
import com.hereliesaz.photounroll.imaging.Rendered
import com.hereliesaz.photounroll.imaging.RgbaImage
import com.hereliesaz.photounroll.imaging.loadPhoto
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private var nextId = 0L

enum class Mode(val label: String, val action: String) {
    Unroll("UNROLL", "Unroll"),
    Panorama("PANORAMA", "Stitch"),
}

/**
 * One imported photo. Guides are per photo because the object drifts in frame as you walk.
 * [original] is never modified; [image] is it after [adjust] (rotation + perspective), and is
 * what guides, unrolling and stitching see. [fromVideo] frames are already in capture order. [rotatedBitmap] is the rotation alone, the canvas
 * the warp corners are picked on.
 */
class Photo(
    val name: String,
    val original: RgbaImage,
    guides: CylinderGuides,
    val fromVideo: Boolean = false,
    /** EXIF capture time (sortable, see [Exif.captureTime]); an ordering clue. */
    val taken: Long? = null,
    /** EXIF 35 mm-equivalent focal length; sets how strongly the guessed rims curve. */
    val focal35: Float? = null,
    /** The file as imported. Editing works on a reduced copy; the final render decodes this again, larger. */
    val source: ByteArray? = null,
) {
    /** Horizon row (fraction of height) on [image], from [Leveler]; sets how the rims curve. */
    var horizon = 0.5f
        internal set
    /** The rims were detected in the photo rather than placed at the frame's ends. */
    var rimsSeen = fromVideo
        internal set
    val focalPx: Float get() = Perspective.focalPx(focal35, original.width, original.height)
    /** Stable list key (thumbnails keep their gesture state while being reordered). */
    val id: Long = nextId++
    var guides by mutableStateOf(guides)
    var adjust by mutableStateOf(PhotoAdjust())
        internal set
    var image by mutableStateOf(original)
        internal set
    var bitmap by mutableStateOf(ImageCodec.toImageBitmap(original))
        internal set
    var rotatedBitmap by mutableStateOf(bitmap)
        internal set
    val originalBitmap: ImageBitmap = bitmap
    internal var adjustJob: Job? = null
}

/** What the editor canvas is for: guides (or plain view in panorama), rotation, or perspective. */
enum class Tool(val label: String) { Main("GUIDES"), Rotate("ROTATE"), Warp("WARP") }

class Result(val rendered: Rendered, val bitmap: ImageBitmap, val mode: Mode)

/** All UI state. Heavy work runs on [Dispatchers.Default]; results land back in snapshot state. */
class AppState(private val scope: CoroutineScope) {
    var mode by mutableStateOf(Mode.Unroll)
    val photos = mutableStateListOf<Photo>()
    var selected by mutableStateOf(0)
    var tool by mutableStateOf(Tool.Main)

    /** Half-arc kept from each walk-around photo. */
    var unrollAngle by mutableStateOf(60f)

    /** Lens horizontal field of view for panorama reprojection. */
    var hfov by mutableStateOf(68f)

    /** Close the last photo onto the first so the output tiles seamlessly. */
    var fullTurn by mutableStateOf(true)

    var progress by mutableStateOf<Float?>(null)
        private set
    var result by mutableStateOf<Result?>(null)
    var error by mutableStateOf<String?>(null)

    private var job: Job? = null
    val busy get() = progress != null
    val current: Photo? get() = photos.getOrNull(selected)

    /** Ordering is only meaningful for loose photos; video frames arrive in sequence. */
    val canArrange get() = photos.size >= 3 && photos.none { it.fromVideo }

    /**
     * Imports photos: each is stood upright ([Leveler]) and given guides, then the set is put
     * in capture order (see [arrange]) and made to cover the same stretch of the pillar
     * ([harmonize]), so distance and tilt need not be consistent between shots.
     */
    fun import(files: List<PlatformFile>) = task {
        files.forEachIndexed { i, file ->
            progress = 0.5f * i / files.size
            val bytes = file.readBytes()
            val photo = withContext(Dispatchers.Default) {
                Photo(file.name, loadPhoto(bytes), CylinderGuides(), taken = Exif.captureTime(bytes), focal35 = Exif.focalLength35(bytes), source = bytes)
            }
            detect(photo)
            photos += photo
        }
        harmonize()
        arrangeNow(from = 0.5f)
    }

    /** Levels [photo] from scratch and guesses its guides on the leveled image. */
    private suspend fun detect(photo: Photo) {
        val depth = DepthModel.estimate(photo.original)
        val level = withContext(Dispatchers.Default) { Leveler.level(photo.original, photo.focal35, depth) }
        setAdjust(photo, level.adjust)
        photo.adjustJob?.join()
        photo.horizon = level.horizon
        val found = withContext(Dispatchers.Default) {
            // The depth image is bent the same way the photo was, so it still lies over it.
            EdgeSuggester.detect(photo.image, photo.focal35, level.horizon, depth?.let { level.adjust.apply(it) })
        }
        photo.guides = found.guides
        photo.rimsSeen = found.rimsSeen
    }

    /**
     * Gives every photo whose rims are out of frame the same vertical extent of pillar
     * (see [Perspective.harmonize]). Photos with visible rims already agree: rim to rim.
     */
    private fun harmonize() {
        val loose = photos.filter { !it.rimsSeen }
        if (loose.isEmpty() || loose.size != photos.count { !it.fromVideo }) return
        val out = Perspective.harmonize(
            loose.map { it.guides }, loose.map { it.image.width to it.image.height }, loose.map { it.focalPx }, loose.map { it.horizon },
        )
        loose.forEachIndexed { i, p -> p.guides = out[i] }
    }

    /**
     * Imports a video of a slow lap around the subject as a run of ordered frames. One set of
     * guides (the median of every frame's own guess) is shared by all frames: walking in a
     * circle keeps the subject roughly where it was, and one steady guess beats forty noisy ones.
     */
    fun importVideo(file: PlatformFile) = task {
        val frames = VideoFrames.extract(file, VIDEO_FRAME_MAX_DIM) { progress = 0.6f * it }
        val guesses = withContext(Dispatchers.Default) { frames.map { EdgeSuggester.suggest(it) } }
        val shared = CylinderGuides.median(guesses)
        val base = file.name.substringBeforeLast('.')
        frames.forEachIndexed { i, img ->
            progress = 0.6f + 0.4f * i / frames.size
            photos += Photo("$base #${i + 1}", img, shared, fromVideo = true)
        }
    }

    fun remove(index: Int) {
        photos.removeAt(index)
        selected = selected.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    }

    /**
     * Reorders the photos by how well their flattened strips overlap, so it no longer matters
     * what order the picker returned them in, nudged by metadata (see [metadataRank]).
     * Leaves video frames alone (already in order).
     */
    fun arrange() = task { arrangeNow(from = 0f) }

    /** Manual reorder (thumbnail drag); the selection follows its photo. */
    fun move(from: Int, to: Int) {
        if (from == to || from !in photos.indices || to !in photos.indices) return
        val keep = current
        photos.add(to, photos.removeAt(from))
        selected = photos.indexOf(keep).coerceAtLeast(0)
    }

    private suspend fun arrangeNow(from: Float) {
        if (photos.size < 3 || photos.any { it.fromVideo }) return
        photos.forEach { it.adjustJob?.join() }
        val snapshot = photos.toList()
        val m = mode
        val rank = metadataRank(snapshot)
        val report: (Float) -> Unit = { progress = from + (1f - from) * it }
        val order = withContext(Dispatchers.Default) {
            when (m) {
                Mode.Unroll -> Pipelines.arrangeCylinder(
                    snapshot.map { it.image }, snapshot.map { it.guides }, unrollAngle, report, rank, snapshot.map { it.focalPx },
                )
                Mode.Panorama -> Pipelines.arrangePanorama(snapshot.map { it.image }, hfov, report, rank)
            }
        }
        val keep = current
        photos.clear()
        photos.addAll(order.map { snapshot[it] })
        selected = photos.indexOf(keep).coerceAtLeast(0)
    }

    fun autoGuides() {
        val p = current ?: return
        task {
            if (p.fromVideo) p.guides = withContext(Dispatchers.Default) { EdgeSuggester.suggest(p.image, p.focal35) }
            else { detect(p); harmonize() }
        }
    }

    /**
     * Re-renders [photo] with [adjust] off the main thread. Superseded renders are cancelled,
     * so dragging a slider only pays for the last value.
     */
    fun setAdjust(photo: Photo, adjust: PhotoAdjust) {
        if (adjust == photo.adjust) return
        photo.adjust = adjust
        photo.adjustJob?.cancel()
        photo.adjustJob = scope.launch {
            val rotationOnly = adjust.copy(quad = PhotoAdjust.FULL_FRAME)
            val (img, bmp, rot) = withContext(Dispatchers.Default) {
                val img = adjust.apply(photo.original)
                val rot = if (rotationOnly == adjust) img else rotationOnly.apply(photo.original)
                val bmp = ImageCodec.toImageBitmap(img)
                Triple(img, bmp, if (rot === img) bmp else ImageCodec.toImageBitmap(rot))
            }
            photo.image = img
            photo.bitmap = bmp
            photo.rotatedBitmap = rot
        }
    }

    fun applyAdjustToAll() {
        val a = current?.adjust ?: return
        photos.forEach { setAdjust(it, a) }
    }

    fun applyGuidesToAll() {
        val g = current?.guides ?: return
        photos.forEach { it.guides = g }
    }

    fun process() {
        if (photos.isEmpty()) return
        val m = mode
        val trim = photos.any { it.fromVideo }
        task {
            // A rotation or warp may still be rendering; unroll what the user last set.
            photos.forEach { it.adjustJob?.join() }
            val snapshot = photos.toList()
            val rendered = withContext(Dispatchers.Default) {
                val report: (Float) -> Unit = { progress = it }
                when (m) {
                    Mode.Unroll -> {
                        // The result is made from larger, sharper copies than the ones edited on
                        // screen; guides and adjustments are fractions of the frame, so they carry over.
                        val full = snapshot.mapIndexed { i, p ->
                            report(0.2f * i / snapshot.size)
                            p.source?.let { p.adjust.apply(loadPhoto(it, FINAL_MAX_DIM), sharp = true) } ?: p.image
                        }
                        Pipelines.unrollCylinder(
                            full, snapshot.map { it.guides }, unrollAngle, fullTurn, trim, { report(0.2f + 0.8f * it) },
                            full.indices.map { Perspective.focalPx(snapshot[it].focal35, full[it].width, full[it].height) },
                        )
                    }
                    Mode.Panorama -> Pipelines.panorama(snapshot.map { it.image }, hfov, fullTurn, trim, report)
                }
            }
            result = Result(rendered, withContext(Dispatchers.Default) { ImageCodec.toImageBitmap(rendered.image) }, m)
        }
    }

    suspend fun encode(r: Result): ByteArray = withContext(Dispatchers.Default) { r.rendered.toJpeg() }

    /** Runs one task at a time with progress and error reporting. */
    private fun task(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        error = null
        progress = 0f
        job = scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                progress = null
            }
        }
    }

    /**
     * Metadata order clue: EXIF capture time when every photo has one, else the number in the
     * file name (IMG_0042) when every name has one, else nothing. Ties keep import order.
     */
    private fun metadataRank(ps: List<Photo>): IntArray? {
        val keys: List<Long> = when {
            ps.all { it.taken != null } -> ps.map { it.taken!! }
            else -> ps.map { p -> NUMBER.findAll(p.name).lastOrNull()?.value?.takeLast(18)?.toLong() ?: return null }
        }
        if (keys.distinct().size < 2) return null
        val rank = IntArray(ps.size)
        ps.indices.sortedBy { keys[it] }.forEachIndexed { r, i -> rank[i] = r }
        return rank
    }

    private companion object {
        /** Longest side of the photos the final unroll is rendered from. */
        const val FINAL_MAX_DIM = 2400
        val NUMBER = Regex("\\d+")
    }
}
