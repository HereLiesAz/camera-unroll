package com.hereliesaz.cameraunroll

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import java.lang.ref.WeakReference

/**
 * The running activity, for the things only an activity can do: ask for a permission, own the
 * camera's lifecycle. Held weakly and re-set on every create, so a destroyed activity is
 * never kept alive or used.
 */
internal object ActivityHolder {
    private var ref = WeakReference<ComponentActivity>(null)
    private var cameraLauncher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Boolean>? = null

    val activity: ComponentActivity? get() = ref.get()?.takeIf { !it.isDestroyed }

    /** Call from onCreate, before the activity is started (launchers must be registered by then). */
    fun attach(activity: ComponentActivity) {
        ref = WeakReference(activity)
        cameraLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.complete(granted)
            pending = null
        }
    }

    fun detach(activity: ComponentActivity) {
        if (ref.get() === activity) {
            ref = WeakReference(null)
            cameraLauncher = null
            pending?.complete(false)
            pending = null
        }
    }

    /** True once the camera may be used; asks the user if they have not answered yet. */
    suspend fun cameraPermission(): Boolean {
        val a = activity ?: return false
        if (ContextCompat.checkSelfPermission(a, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
        val launcher = cameraLauncher ?: return false
        // One question at a time: a second caller waits on the first one's answer.
        pending?.let { return it.await() }
        val answer = CompletableDeferred<Boolean>().also { pending = it }
        launcher.launch(Manifest.permission.CAMERA)
        return answer.await()
    }
}

@Composable
actual fun BackAction(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

@Composable
actual fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        val before = view.keepScreenOn
        if (on) view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}

/** Phones have a fraction of a desktop's memory for the same eight photos. */
actual object Limits {
    actual val editMaxDim = 1280
    actual val finalMaxDim = 2000
}
