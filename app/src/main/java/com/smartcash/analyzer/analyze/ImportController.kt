package com.smartcash.analyzer.analyze

import android.net.Uri
import com.smartcash.analyzer.data.settings.SettingsRepository
import com.smartcash.analyzer.domain.TxSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-wide state of receipt imports started from the UI or from the share sheet. */
class ImportController(
    private val importer: ReceiptImporter,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    sealed class State {
        data object Idle : State()
        data class Running(val index: Int, val total: Int, val step: String) : State()
        data class Done(val outcomes: List<ReceiptImporter.Outcome>) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val mutex = Mutex()

    fun import(uris: List<Uri>, source: TxSource = TxSource.PDF) {
        if (uris.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val outcomes = ArrayList<ReceiptImporter.Outcome>()
                uris.forEachIndexed { i, uri ->
                    _state.value = State.Running(i + 1, uris.size, "قراءة الملف…")
                    val outcome = runCatching {
                        importer.importUri(uri, source) { step -> _state.value = State.Running(i + 1, uris.size, step) }
                    }.getOrElse { e -> ReceiptImporter.Outcome(false, null, "❌ ${e.message ?: "خطأ غير متوقع"}") }
                    outcomes += outcome
                }
                _state.value = State.Done(outcomes)
            }
        }
    }

    fun confirmOwner(name: String) {
        scope.launch { settings.addOwnerName(name) }
    }

    fun dismiss() {
        _state.value = State.Idle
    }
}
