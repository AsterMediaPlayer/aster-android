package com.astermediaplayer.astermediaplayer.tv

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** What the TV's "add channels" step opens: puts Aster's channels in the guide, then closes. */
class AsterTvSetupActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Adding Aster's channels…"; textSize = 28f; setPadding(120, 120, 120, 120) })
        scope.launch {
            val count = runCatching { TvGuide.sync(this@AsterTvSetupActivity) }.getOrDefault(0)
            if (count == 0) {
                Toast.makeText(this@AsterTvSetupActivity, "Sign in to Aster and set up a channel first.", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
            } else setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
