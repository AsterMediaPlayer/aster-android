package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astermediaplayer.astermediaplayer.data.Session

@Composable
fun SettingsScreen(session: Session?, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Account", style = MaterialTheme.typography.titleMedium)
        Text(session?.userName.orEmpty(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        Text(session?.serverUrl.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onSignOut, modifier = Modifier.padding(top = 16.dp)) { Text("Sign out") }
    }
}
