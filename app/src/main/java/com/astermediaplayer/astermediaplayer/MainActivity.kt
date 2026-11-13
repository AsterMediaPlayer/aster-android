package com.astermediaplayer.astermediaplayer

import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.astermediaplayer.astermediaplayer.ui.AsterNavHost
import com.astermediaplayer.astermediaplayer.ui.theme.AsterTheme
import com.astermediaplayer.astermediaplayer.ui.theme.Lockup
import com.astermediaplayer.astermediaplayer.ui.theme.Type
import com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv
import com.astermediaplayer.astermediaplayer.ui.theme.isTelevision
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import com.astermediaplayer.astermediaplayer.ui.theme.MinimalBringIntoView

@OptIn(ExperimentalFoundationApi::class)
// A FragmentActivity (a ComponentActivity with fragments) because the Cast button opens its device list as a dialog fragment.
class MainActivity : androidx.fragment.app.FragmentActivity() {
    // Leaving the app with a video playing takes it into picture in picture (Android 12 does this by itself).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        com.astermediaplayer.astermediaplayer.ui.player.PictureInPicture.onUserLeaveHint(this)
    }

    // What input reaches the app, for a debug report (a TV with a touchscreen whose taps did nothing): told, then handled as ever.
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.astermediaplayer.astermediaplayer.data.diagnostics.InputLog.touch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        com.astermediaplayer.astermediaplayer.data.diagnostics.InputLog.generic(ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        com.astermediaplayer.astermediaplayer.data.diagnostics.InputLog.key(event)
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark whatever the system theme is, so the bars always get light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val repo = (application as AsterApp).repository
        // What the device decodes and which input devices it has: asked in the background, not before the first frame (a
        // second and more on a TV box), and the decoder lists are ready by the first video.
        val touchLayer = com.astermediaplayer.astermediaplayer.ui.theme.usesTouchLayer(this)
        val appContext = applicationContext
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            com.astermediaplayer.astermediaplayer.ui.player.AudioCapabilities.log(appContext)
            com.astermediaplayer.astermediaplayer.data.DeviceCapabilities.log()
            com.astermediaplayer.astermediaplayer.data.diagnostics.InputLog.noteDevices(touchLayer)
        }
        if (isTelevision(this)) {
            // One Apple TV point is this many dp (text: sp) here, on this screen's own pixels.
            val metrics = resources.displayMetrics
            val unit = metrics.widthPixels / 1920f / metrics.density
            Lockup.useTv(unit)
            Type.useTv(unit / resources.configuration.fontScale)
        }
        // The clock for each server's lock-after: it starts when the app leaves the screen and is read when it's back.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { lifecycleScope.launch { repo.noteLeftApp() } }
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                lifecycleScope.launch { repo.checkLock() }
                // A Plex server that stopped answering where it was saved (this device on another network, a new address): found again,
                // and the app starts afresh on it.
                lifecycleScope.launch { if (repo.session != null && repo.refindPlex()) recreate() }
                // The synced settings come from the server whenever the app opens or returns (and again when Settings opens).
                lifecycleScope.launch { if (repo.session != null) (application as AsterApp).settingsSync.pull() }
            }
        })
        setContent {
            // Who's watching is decided before the main screen composes, from what's kept here, so nobody's rows load under the picker.
            val signedIn by produceState<Boolean?>(null) { value = repo.restore().also { if (it) (application as AsterApp).households.decideAtLaunch() } }
            val tv = isTelevision(this)
            CompositionLocalProvider(
                LocalIsTv provides tv, com.astermediaplayer.astermediaplayer.ui.theme.LocalTouchTv provides com.astermediaplayer.astermediaplayer.ui.theme.usesTouchLayer(this),
                LocalBringIntoViewSpec provides if (tv) MinimalBringIntoView else LocalBringIntoViewSpec.current,
            ) {
                AsterTheme {
                    Surface(Modifier.fillMaxSize()) {
                        signedIn?.let { AsterNavHost(signedIn = it) }
                    }
                }
            }
        }
    }
}
