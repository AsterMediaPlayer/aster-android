package com.astermediaplayer.astermediaplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Comes up when playback keeps stalling: carry on, or play at a lower quality, which the server converts down to fit the
 * connection. The Apple apps' `StallQualityCard`; Back is "Keep Trying".
 */
@Composable
fun StallQualityCard(offer: List<Quality>, onKeepTrying: () -> Unit, onChoose: (Quality) -> Unit, modifier: Modifier = Modifier) {
    androidx.activity.compose.BackHandler(onBack = onKeepTrying)
    // Takes the touches, so a tap on the card doesn't also show or hide the controls behind it.
    Box(
        modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(24.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xF22C2C2E)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Playback Keeps Stopping", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "The connection can't keep up with the original file. A lower quality has the server convert it to fit.",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, textAlign = TextAlign.Center,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onKeepTrying, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Keep Trying") }
                offer.forEach { quality -> Button(onClick = { onChoose(quality) }) { Text(quality.label) } }
            }
        }
    }
}
