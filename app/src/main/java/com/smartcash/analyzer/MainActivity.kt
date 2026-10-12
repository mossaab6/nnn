package com.smartcash.analyzer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.telegram.BotService
import com.smartcash.analyzer.telegram.TelegramSyncWorker
import com.smartcash.analyzer.ui.AppRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TX_ID = "tx_id"
    }

    /** Transaction to open (from a notification tap). */
    private val openTx = MutableStateFlow<Long?>(null)

    private val container get() = (application as App).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // on re-creation (rotation) the system re-delivers the original intent: import only once
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppRoot(container = container, openTx = openTx)
        }
        lifecycleScope.launch {
            val s = container.settings.current()
            if (s.botServiceEnabled && s.telegramConfigured) runCatching { BotService.start(this@MainActivity) }
            if (s.telegramConfigured) TelegramSyncWorker.runNow(this@MainActivity)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getLongExtra(EXTRA_TX_ID, -1L).takeIf { it > 0 }?.let { openTx.value = it }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            container.importController.import(uris, TxSource.PDF)
        }
    }
}
