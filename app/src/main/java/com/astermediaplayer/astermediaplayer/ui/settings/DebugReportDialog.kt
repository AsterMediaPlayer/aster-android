package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astermediaplayer.astermediaplayer.data.diagnostics.DebugLog
import com.astermediaplayer.astermediaplayer.data.diagnostics.DebugReport
import kotlinx.coroutines.launch

/** Where a report's code is quoted. */
private const val ISSUES = "github.com/AsterMediaPlayer/aster-android/issues"

/**
 * Send Debug Report: asks what happened, says what a report holds, sends it, and shows the id the service gave it. [afterCrash] opens it as
 * the app's offer after the last run ended in a crash, which goes with the report; choosing Not Now puts that crash aside.
 */
@Composable
fun DebugReportDialog(afterCrash: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    // A TV's remote makes typing slow: there the words are optional and no reply address is asked for.
    val tv = com.astermediaplayer.astermediaplayer.ui.theme.LocalIsTv.current

    fun send() {
        sending = true
        failure = null
        scope.launch {
            runCatching {
                val crash = DebugLog.lastCrash()
                DebugReport.send(DebugReport.build(context, description, crash, email.takeIf { !tv })).also { if (crash != null) DebugLog.clearCrash() }
            }.onSuccess { code = it }.onFailure { failure = it.message ?: "The report couldn't be sent." }
            sending = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text(if (code != null) "Report Sent" else if (afterCrash) "Aster Closed Unexpectedly" else "Send Debug Report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    code != null -> {
                        Text("Thanks. Your report is")
                        Text(code!!, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = androidx.compose.ui.Modifier.align(Alignment.CenterHorizontally))
                        Text("If you open a bug report at $ISSUES, quote it there.")
                    }
                    sending -> {
                        CircularProgressIndicator(androidx.compose.ui.Modifier.align(Alignment.CenterHorizontally))
                        Text("Sending…")
                    }
                    else -> {
                        if (afterCrash) Text("Sending a debug report helps find out why.")
                        androidx.compose.material3.OutlinedTextField(
                            description, { description = it.take(10_000) }, label = { Text("What happened?") },
                            placeholder = { Text(if (tv) "Optional" else "What you were doing, and what went wrong") },
                            minLines = if (tv) 1 else 3, modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        )
                        if (!tv) androidx.compose.material3.OutlinedTextField(
                            email, { email = it.take(254) }, label = { Text("Email for a reply (optional)") }, singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        )
                        Text(DebugReport.CONTENTS, style = MaterialTheme.typography.bodySmall)
                        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            when {
                code != null -> TextButton(onDismiss) { Text("Done") }
                !sending -> TextButton(::send) { Text(if (failure != null) "Try Again" else "Send") }
            }
        },
        dismissButton = {
            if (code == null && !sending) TextButton({ if (afterCrash) DebugLog.clearCrash(); onDismiss() }) { Text(if (afterCrash) "Not Now" else "Cancel") }
        },
    )
}
