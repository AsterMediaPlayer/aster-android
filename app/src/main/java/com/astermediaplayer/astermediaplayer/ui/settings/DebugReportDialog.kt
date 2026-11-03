package com.astermediaplayer.astermediaplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * Send Debug Report: says what a report holds, sends it, and shows the code to quote in a bug report. [afterCrash] opens it as
 * the app's offer after the last run ended in a crash, which goes with the report; choosing Not Now puts that crash aside.
 */
@Composable
fun DebugReportDialog(afterCrash: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun send() {
        sending = true
        failure = null
        scope.launch {
            runCatching {
                val crash = DebugLog.lastCrash()
                DebugReport.send(DebugReport.build(context, crash)).also { if (crash != null) DebugLog.clearCrash() }
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
                        Text("Your report's code is")
                        Text(code!!, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = androidx.compose.ui.Modifier.align(Alignment.CenterHorizontally))
                        Text("Quote it in a bug report at $ISSUES, with what you were doing when it went wrong.")
                    }
                    sending -> {
                        CircularProgressIndicator(androidx.compose.ui.Modifier.align(Alignment.CenterHorizontally))
                        Text("Sending…")
                    }
                    else -> {
                        if (afterCrash) Text("Sending a debug report helps find out why. You'll get a code to quote in a bug report.")
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
