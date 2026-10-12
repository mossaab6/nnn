package com.smartcash.analyzer

import android.content.Context
import com.smartcash.analyzer.ai.ActionExecutor
import com.smartcash.analyzer.ai.AiExtractor
import com.smartcash.analyzer.ai.AiService
import com.smartcash.analyzer.ai.AssistantEngine
import com.smartcash.analyzer.ai.GrokClient
import com.smartcash.analyzer.analyze.ImportController
import com.smartcash.analyzer.analyze.ReceiptAnalyzer
import com.smartcash.analyzer.analyze.ReceiptImporter
import com.smartcash.analyzer.data.BackupManager
import com.smartcash.analyzer.data.TransactionRepository
import com.smartcash.analyzer.data.db.AppDatabase
import com.smartcash.analyzer.data.settings.SettingsRepository
import com.smartcash.analyzer.notifications.NotificationProcessor
import com.smartcash.analyzer.ocr.ReceiptOcrReader
import com.smartcash.analyzer.ocr.TesseractOcr
import com.smartcash.analyzer.telegram.BotEngine
import com.smartcash.analyzer.telegram.RelayClient
import com.smartcash.analyzer.telegram.TelegramApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient

/** Manual dependency container (one per process). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient by lazy { OkHttpClient.Builder().retryOnConnectionFailure(true).build() }
    val db: AppDatabase by lazy { AppDatabase.build(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val repo: TransactionRepository by lazy { TransactionRepository(db) }

    val grok: GrokClient by lazy { GrokClient(http) }
    val ai: AiService by lazy { AiService(grok, settings) }
    val extractor: AiExtractor by lazy { AiExtractor(ai) }

    val ocr: TesseractOcr by lazy { TesseractOcr(appContext) }
    val ocrReader: ReceiptOcrReader by lazy { ReceiptOcrReader(ocr) }
    val analyzer: ReceiptAnalyzer by lazy { ReceiptAnalyzer(ocrReader, extractor, settings) }
    val importer: ReceiptImporter by lazy { ReceiptImporter(appContext, analyzer, repo, settings) }
    val importController: ImportController by lazy { ImportController(importer, settings, scope) }

    val actions: ActionExecutor by lazy { ActionExecutor(db, repo, settings) }
    val assistant: AssistantEngine by lazy { AssistantEngine(ai, repo, settings, db, actions) }

    val telegram: TelegramApi by lazy { TelegramApi(http) }
    val relay: RelayClient by lazy { RelayClient(http) }
    val botEngine: BotEngine by lazy { BotEngine(settings, telegram, relay, importer, assistant, actions, repo) }

    val notificationProcessor: NotificationProcessor by lazy {
        NotificationProcessor(appContext, settings, repo, db, extractor) { botEngine }
    }
    val backup: BackupManager by lazy { BackupManager(appContext, repo, db, settings) }
}
