package com.astermediaplayer.astermediaplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
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
