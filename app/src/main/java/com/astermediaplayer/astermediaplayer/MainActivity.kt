package com.astermediaplayer.astermediaplayer

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
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark whatever the system theme is, so the bars always get light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val repo = (application as AsterApp).repository
        if (isTelevision(this)) {
            // One Apple TV point is this many dp (text: sp) here, on this screen's own pixels.
            val metrics = resources.displayMetrics
            val unit = metrics.widthPixels / 1920f / metrics.density
            Lockup.useTv(unit)
            Type.useTv(unit / resources.configuration.fontScale)
        }
        setContent {
            val signedIn by produceState<Boolean?>(null) { value = repo.restore() }
            val tv = isTelevision(this)
            CompositionLocalProvider(LocalIsTv provides tv, LocalBringIntoViewSpec provides if (tv) MinimalBringIntoView else LocalBringIntoViewSpec.current) {
                AsterTheme {
                    Surface(Modifier.fillMaxSize()) {
                        signedIn?.let { AsterNavHost(signedIn = it) }
                    }
                }
            }
        }
    }
}
