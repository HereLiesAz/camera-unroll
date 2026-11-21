# Photo Unroll

Compose Multiplatform app (Android · iOS · Desktop · Web) that flattens what photographs curve.

## Modes

**Unroll** — walk around a cylinder (can, column, bottle, tree trunk, tattooed forearm), one photo every 30–45°, same height and distance. Each photo gets guides marking the silhouette and both rims; auto-detect proposes them, you correct them. The visible face of each photo is unwrapped uniformly in angle and the strips are stitched into the surface laid flat. With **Full 360°** the last photo is aligned back onto the first, so the output is exactly one circumference wide and tiles seamlessly.

**Panorama** — stand still and turn, overlapping frames by about a third. Frames are reprojected onto a cylinder (set the lens FOV; phone main cameras are ~65–75°), stitched, then mapped to an equirectangular band and saved as JPEG with Photo Sphere (GPano) XMP. Google Photos, Facebook and most 360° viewers wrap it; the in-app viewer scrolls it forever.

Both results open in a viewer that drags/flings horizontally and, when closed into a full turn, never ends. An unrolled pillar also has a **PILLAR** tab: the strip wrapped back onto a cylinder of the pillar's own proportions that you spin by dragging. Only what was photographed is painted (the result line says how far around that is, e.g. `190° of 360°`); the rest is left bare.

### Video

**+ VIDEO** takes a slow lap around the subject (or a slow turn on the spot) instead of photos. The app pulls 16–40 evenly spaced frames (about 1.5 per second), shares one set of guides across them (the median of every frame's own guess), and, if you walked past a full turn, drops the frames after the view comes back to the start. Frames arrive already in order.

| Target | Decoder |
| ------ | ------- |
| Android | `MediaMetadataRetriever` |
| Web | `<video>` + canvas (formats the browser can play: MP4/H.264, WebM) |
| Desktop | `ffmpeg` and `ffprobe` on the PATH |
| iOS | `AVAssetImageGenerator` |

### Guided capture (web, Android)

**CAMERA** opens the rear camera with the pillar's place drawn on the view: a ghost column to fit the real one into. While the pillar is centered, upright enough and the right size, the column turns solid and a ring fills; when it closes, the photo is taken. No shutter. After each photo a bar shows the walk to the next spot (about 30° around; the app compares the live view with the last photo to tell), a plan view of the pillar fills in with what has been covered, and one short line says what to fix (`step back`, `keep walking around`). **DONE** hands the photos to the editor. Tolerances are loose: the unroll corrects lean, tilt and distance by itself.

- **Web:** needs HTTPS and camera permission; photos are frames of the video stream.
- **Android:** CameraX; asks for the camera when first used; photos are real full-resolution stills with EXIF, so the lens's focal length is known.
- Desktop and iOS use the picker for now.

### Android

Depth Anything runs on-device through ONNX Runtime (the model ships in the APK's assets). Working sizes are smaller than on desktop (1280 px to edit, 2000 px for the result), photos are decoded at reduced size straight from the file, and a render that runs out of memory is redone from the smaller copies instead of failing. Back steps out of the camera or the result; the screen stays on while capturing or rendering. On touch, hold a thumbnail to pick it up and reorder (a plain drag scrolls the strip); with a mouse, drag it straight away.

~~~
./gradlew :composeApp:assembleDebug     # installable, debug-signed
./gradlew :composeApp:assembleRelease   # shrunk with R8; unsigned until a signing config is added
~~~

### Automatic pillar, tilt and distance

On import each photo is handled without being asked:

- **Pillar:** on the web and desktop, a bundled depth network (Depth Anything V2 Small, Apache-2.0, 27 MB, run locally with ONNX Runtime; nothing leaves the device) turns the photo into a depth image, where the pillar is simply the nearest column: its sides and lean are read from that, whatever its paint, the light or the clutter behind it. Elsewhere (Android, iOS for now), or if the model fails to load, the two longest near-vertical edges with nothing as strong between them are taken as its sides. A whole object against a plain background is still found by its silhouette, rims included.
- **Tilt:** the sides' common lean is the phone's roll and their convergence its pitch; the photo is rotated and warped (visible in **Rotate** / **Warp**) until the pillar stands upright with parallel sides.
- **Distance:** the pillar's width in frame and the lens (EXIF 35 mm focal length, 26 mm assumed) give the camera's distance. The unroll is a true perspective one, every strip comes out at the same scale, and all photos are given the same stretch of pillar above and below the horizon, so shots taken nearer, farther or tipped differently still line up.

**AUTO-DETECT** redoes this for the current photo. Rims you can't see in frame are placed by geometry, not detected.

### Automatic order

Photos are put left to right along the surface on import (and by **ORDER**), whatever order they were taken or picked in. Each is flattened, and every pair is matched (on texture, which lighting cannot fool, backed by plain brightness, which a repeating pattern cannot). The most trustworthy matches that connect everything give each photo a position; sorting by position is the order. Walking back and forth, or shooting the same spot twice, is fine. Capture order breaks ties, and metadata (EXIF time, else the number in the file name) is the whole answer when nothing overlaps. Video frames are never reordered.

### Stitching

- **Placement:** the stitcher finds positions the same way, so it does not depend on the thumbnail order, and it leaves out a photo taken almost where another was.
- **Second pass:** what still separates neighbors after geometry (a degree of lean, a few percent of size) is measured band by band on the strips and unrolled away.
- **Registration:** across each overlap the leftover disagreement is measured block by block, and both photos are bent to meet exactly at the cut.
- **One photo per place:** nothing is averaged. A cut runs through each overlap where the two photos differ least; detail switches photo at the cut, while lighting and color (exposure-matched first) fade across the whole overlap.
- **Resolution:** the result is rendered from 2400 px copies of the originals with bicubic resampling, at the median photo's scale; editing uses 1600 px copies.
- **FULL 360°** closes the loop only if the last photo really continues into the first; otherwise the strip is left open.

Drag a thumbnail sideways to reorder by hand (on touch, hold it first). On the web, drop photos or a video anywhere on the page to import them.

### Rotate and warp

Each photo has **Rotate** (any angle, level grid, live preview) and **Warp** (drag four corners onto something that should be rectangular) tabs. Both are applied in one resample from the untouched original, before guides, unrolling and stitching.

### Touch

Built for fingers first; a mouse gets the same gestures.

| Gesture | Where | Does |
| ------- | ----- | ---- |
| Drag a handle | guides, warp | Moves it from the first pixel (no slop delay), keeping the grab offset. A **loupe** (2.5×) floats above the fingertip showing what it covers. |
| Pinch / mouse wheel | editors, result | Zoom about the fingers (editors to 8×). |
| Two-finger drag | editors | Pan. One finger pans too once zoomed in and not on a handle. |
| Two-finger twist | rotate | Turns the photo. Snaps to square (0/±90/180°) within 1.5°. |
| Drag / fling, pinch, double-tap | result | Scroll the strip (wraps forever when seamless); zoom; toggle 1× / 2.5×. |

Warp corners snap to the photo's own edges; snaps (and picking up a handle) give a haptic tick where the platform has one (Android, iOS). Every control is at least 48 dp; thumbnails are 80 dp with a separate corner target to remove, **+ PHOTOS** / **+ VIDEO** are tiles at the end of the strip, and the action row scrolls sideways instead of wrapping on a narrow screen. On the web, `touch-action: none` and `overscroll-behavior: none` keep the browser from scrolling, pull-to-refresh or zooming the page underneath.

## Layout

~~~
composeApp/src/
  commonMain/  imaging/  all math — pure Kotlin, shared by every target
                 RgbaImage            ARGB raster, bilinear sampling, EXIF rotation
                 PhotoAdjust          rotation + four-corner perspective (homography)
                 CylinderGuides       silhouette + rim ellipses, normalized
                 CylinderUnroller     photo face -> flat strip (uniform in θ)
                 CylindricalProjector frame -> cylinder; cylinder -> equirect band
                 Stitcher             coarse-to-fine NCC alignment (sub-pixel), loop closure, feather blend
                 Arranger             capture-order recovery; one-turn detection for video
                 EdgeSuggester        background segmentation + rim ellipse fit
                 PanoramaXmp          GPano XMP, APP1 injection
                 Exif                 orientation tag reader
                 Pipelines            the end-to-end operations
               video/  VideoFrames (expect), frame count policy
               ui/  Touch (zoom/pan state, gesture loop, loupe), GuideEditor, AdjustEditors, StripViewer, Theme
               App.kt, AppState.kt
  skiaMain/        codec for desktop, iOS, web (Skia)
  androidMain/     codec (Bitmap), video (MediaMetadataRetriever), MainActivity
  desktopMain/     video (ffmpeg)
  iosMain/         video (AVFoundation)
  wasmJsMain/      video (<video> + canvas), browser download, entry point
  fileSystemMain/  save dialog (Android, desktop, iOS)
  commonTest/      synthetic-scene tests for unroll, stitch, panorama, arrange, adjust, auto-detect, EXIF/XMP
iosApp/iosApp/   SwiftUI host for the shared UI
~~~

## Build

~~~
./gradlew :composeApp:desktopTest                 # imaging tests
./gradlew :composeApp:run                         # desktop
./gradlew :composeApp:assembleDebug               # Android APK
./gradlew :composeApp:wasmJsBrowserDevelopmentRun # web (installable PWA, offline after first load)
~~~

iOS: open an Xcode project at `iosApp/` whose target embeds the `ComposeApp` framework (build phase `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`) and uses the sources in `iosApp/iosApp/`. The `.xcodeproj` itself is not checked in yet; generate it from `HereLiesAz/compose-multiplatform-template` or Xcode's App template.

## Limits

- Unroll assumes a near-orthographic view with the axis vertical in frame. Shoot from a few diameters away; telephoto beats wide.
- Stitching aligns by translation only. Keep height and distance constant; vertical drift up to ~6% is absorbed.
- Order is recovered from content, so photos with no overlap, a plain featureless surface, or a surface with exact repeats (evenly spaced identical stripes) can be misordered; check the thumbnails and fix the guides, then press ORDER, or drag the thumbnails into place.
- Inputs are capped at 1600 px on the long side to stay inside mobile and browser memory.
