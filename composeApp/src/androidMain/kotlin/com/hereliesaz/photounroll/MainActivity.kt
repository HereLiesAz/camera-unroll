package com.hereliesaz.photounroll

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hereliesaz.photounroll.video.AppContext
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.init

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppContext.app = applicationContext
        ActivityHolder.attach(this) // Permission requests and the camera's lifecycle.
        FileKit.init(this) // Save dialog needs an activity result registry.
        setContent { App() }
    }

    override fun onDestroy() {
        ActivityHolder.detach(this)
        super.onDestroy()
    }
}
