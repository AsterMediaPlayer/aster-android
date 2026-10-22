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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark whatever the system theme is, so the bars always get light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val repo = (application as AsterApp).repository
        setContent {
            val signedIn by produceState<Boolean?>(null) { value = repo.restore() }
            AsterTheme {
                Surface(Modifier.fillMaxSize()) {
                    signedIn?.let { AsterNavHost(signedIn = it) }
                }
            }
        }
    }
}
