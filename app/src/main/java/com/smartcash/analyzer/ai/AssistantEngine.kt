package com.smartcash.analyzer.ai

import com.smartcash.analyzer.data.TransactionRepository
import com.smartcash.analyzer.data.db.AppDatabase
import com.smartcash.analyzer.data.db.ChatMessageEntity
import com.smartcash.analyzer.data.db.TransactionEntity
import com.smartcash.analyzer.data.settings.SettingsRepository
import com.smartcash.analyzer.domain.AmountParser
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.DateParser
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.Period
import com.smartcash.analyzer.domain.SummaryCalculator
import com.smartcash.analyzer.domain.TxFormat
import com.smartcash.analyzer.domain.TxType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId

/**
 * The AI assistant used by both the in-app chat and the Telegram bot.
 * Grok answers questions by calling read-only tools over the local database; any change
 * (delete, pin, add, rename, balance) is only *proposed* and waits for the user's confirmation.
 */
class AssistantEngine(
    private val ai: AiService,
    private val repo: TransactionRepository,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    private val actions: ActionExecutor,
) {
    companion object {
        const val CHANNEL_APP = "app"
        const val CHANNEL_TELEGRAM = "telegram"
        private const val MAX_TOOL_ROUNDS = 6
        private const val HISTORY = 16
    }

    data class Reply(val text: String, val actionIds: List<Long>)

    private val chatDao = db.chat()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------------ tool definitions

    private fun fn(name: String, description: String, props: JsonObject = JsonObject(emptyMap()), required: List<String> = emptyList()) =
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                putJsonObject("parameters") {
                    put("type", "object")
                    put("properties", props)
                    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
                }
            }
        }

    private fun prop(type: String, description: String, enum: List<String>? = null) = buildJsonObject {
        put("type", type)
        put("description", description)
        if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
    }

    private val tools: JsonArray = buildJsonArray {
        add(fn("get_overview", "Current balances per currency and totals received/sent for today, this week, this month and all time, plus counts and top people."))
        add(fn(
            "search_transactions",
            "Search the user's transactions. All filters optional. Returns matching transactions (newest first) and their totals.",
            buildJsonObject {
                put("query", prop("string", "Free text matched against names, note and operation number"))
                put("type", prop("string", "sent or received", listOf("sent", "received")))
                put("person", prop("string", "Name of the other person"))
                put("from_date", prop("string", "YYYY-MM-DD inclusive"))
                put("to_date", prop("string", "YYYY-MM-DD inclusive"))
                put("min_amount", prop("number", "Minimum amount"))
                put("max_amount", prop("number", "Maximum amount"))
                put("currency", prop("string", "USD, SYP, EUR or TRY"))
                put("needs_review", prop("boolean", "Only transactions flagged for review"))
                put("limit", prop("integer", "Max results (default 20, max 50)"))
            },
        ))
        add(fn(
            "list_people",
            "List people (counterparties) with totals sent to and received from each.",
            buildJsonObject {
                put("query", prop("string", "Filter by name"))
                put("sort_by", prop("string", "Sort order", listOf("sent", "received", "count", "recent", "name")))
                put("limit", prop("integer", "Max results (default 20)"))
            },
        ))
        add(fn("get_person", "Details and recent transactions of one person.", buildJsonObject { put("name", prop("string", "Person name")) }, listOf("name")))
        add(fn("get_transaction", "Full details of one transaction by id.", buildJsonObject { put("id", prop("integer", "Transaction id")) }, listOf("id")))
        add(fn(
            "propose_delete_transaction", "Ask the user to confirm deleting one transaction. Does NOT delete until the user confirms.",
            buildJsonObject { put("id", prop("integer", "Transaction id")) }, listOf("id"),
        ))
        add(fn(
            "propose_delete_person", "Ask the user to confirm deleting a person and ALL their transactions. Does NOT delete until confirmed.",
            buildJsonObject { put("name", prop("string", "Person name")) }, listOf("name"),
        ))
        add(fn(
            "propose_pin_person", "Ask the user to confirm pinning (or unpinning) a person at the top of the people list.",
            buildJsonObject { put("name", prop("string", "Person name")); put("pinned", prop("boolean", "true to pin, false to unpin")) },
            listOf("name", "pinned"),
        ))
        add(fn(
            "propose_add_transaction", "Ask the user to confirm adding a transaction.",
            buildJsonObject {
                put("type", prop("string", "sent or received", listOf("sent", "received")))
                put("amount", prop("number", "Amount"))
                put("currency", prop("string", "USD, SYP, EUR or TRY"))
                put("counterparty_name", prop("string", "The other person's name exactly as the user wrote it"))
                put("date", prop("string", "YYYY-MM-DD (default today)"))
                put("time", prop("string", "HH:MM"))
                put("note", prop("string", "Optional note"))
                put("reference", prop("string", "Optional operation number"))
            },
            listOf("type", "amount", "counterparty_name"),
        ))
        add(fn(
            "propose_add_person", "Ask the user to confirm adding a new person.",
            buildJsonObject { put("name", prop("string", "Name")); put("account", prop("string", "Optional account")); put("note", prop("string", "Optional note")) },
            listOf("name"),
        ))
        add(fn(
            "propose_rename_person", "Ask the user to confirm renaming a person (merges with an existing person of the new name).",
            buildJsonObject { put("name", prop("string", "Current name")); put("new_name", prop("string", "New name")) },
            listOf("name", "new_name"),
        ))
        add(fn(
            "propose_set_balance", "Ask the user to confirm setting the current wallet balance of a currency.",
            buildJsonObject { put("currency", prop("string", "USD, SYP, EUR or TRY")); put("amount", prop("number", "Balance now")) },
            listOf("amount"),
        ))
    }

    // ------------------------------------------------------------------ prompt

    private suspend fun systemPrompt(channel: String): String {
        val s = settings.current()
        val txs = repo.all()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val currencies = (txs.map { it.currency } + s.balances.keys + s.primaryCurrency).distinct()
        val balances = currencies.joinToString("، ") { c ->
            val b = SummaryCalculator.balance(txs.map { it.toLite() }, c, s.balances[c])
            if (b == null) "$c: غير محدد" else "$c: ${Money.withCurrency(b, c)}"
        }
        val style = if (channel == CHANNEL_TELEGRAM) {
            "You are replying inside Telegram: plain text only (no markdown, no tables), short lines, emojis allowed."
        } else {
            "You are replying inside the app chat: plain text, short paragraphs or simple lists, no markdown tables."
        }
        return """
أنت "مساعد شام كاش"، مساعد مالي ذكي داخل تطبيق يحلل معاملات محفظة شام كاش (Sham Cash) السورية لصاحب الهاتف.
Now: ${Instant.ofEpochMilli(now).atZone(zone).toLocalDateTime().withNano(0)} ($zone). Weeks start on Saturday.
Owner's Sham Cash name(s): ${s.ownerNames.ifEmpty { listOf("غير محدد") }.joinToString("، ")}.
Primary currency: ${s.primaryCurrency}. Current balances: $balances. Stored transactions: ${txs.size}.

Rules:
- Answer in the user's language (Arabic by default), concise and friendly. $style
- ALWAYS use the tools to get numbers; never guess or invent amounts, names or dates.
- Write amounts like "193 $" or "50,000 ل.س". "sent" = أرسلت (outgoing), "received" = استلمت (incoming).
- Quote people's names exactly as stored (they may be Latin usernames with dots/digits); never translate them.
- To delete, pin/unpin, add, rename or change the balance you MUST call the matching propose_* tool. That only creates a
  confirmation request; tell the user to press "تأكيد" on the card/button. Never claim it is done before confirmation.
- One propose_* call per item (e.g. deleting 3 people = 3 calls). If a name is ambiguous, ask which person they mean.
- If something is not in the data, say so honestly.
""".trim()
    }

    // ------------------------------------------------------------------ public API

    fun observe(channel: String) = chatDao.observe(channel)

    suspend fun clear(channel: String) = chatDao.clear(channel)

    suspend fun addEvent(channel: String, text: String, actionId: Long? = null) {
        chatDao.insert(ChatMessageEntity(channel = channel, role = "event", content = text, actionId = actionId))
    }

    suspend fun ask(channel: String, userText: String): Reply {
        chatDao.insert(ChatMessageEntity(channel = channel, role = "user", content = userText))
        val history = chatDao.recent(channel, HISTORY)
            .filter { it.role == "user" || it.role == "assistant" || (it.role == "event" && it.actionId == null) }
            .map { m -> ChatMsg(if (m.role == "assistant") "assistant" else "user", if (m.role == "event") "[ملاحظة من التطبيق] ${m.content}" else m.content) }

        val messages = ArrayList<ChatMsg>()
        messages += ChatMsg("system", systemPrompt(channel))
        messages += history
        val proposed = ArrayList<Long>()
        var finalText: String? = null

        for (round in 0 until MAX_TOOL_ROUNDS) {
            val reply = ai.chat(messages, tools = tools)
            if (reply.toolCalls.isEmpty()) {
                finalText = reply.content
                break
            }
            messages += ChatMsg("assistant", reply.content, toolCalls = reply.toolCalls)
            for (call in reply.toolCalls) {
                val result = runCatching { runTool(channel, call, proposed) }
                    .getOrElse { e -> buildJsonObject { put("error", e.message ?: "tool failed") } }
                messages += ChatMsg("tool", result.toString(), toolCallId = call.id)
            }
        }
        val text = finalText?.trim()?.ifEmpty { null }
            ?: if (proposed.isNotEmpty()) "يرجى تأكيد الإجراء المطلوب بالضغط على زر التأكيد." else "عذراً، لم أتمكن من إكمال الطلب. حاول مرة أخرى."
        chatDao.insert(ChatMessageEntity(channel = channel, role = "assistant", content = text))
        for (id in proposed) {
            val a = actions.get(id) ?: continue
            chatDao.insert(ChatMessageEntity(channel = channel, role = "action", content = a.description, actionId = id))
        }
        return Reply(text, proposed)
    }

    // ------------------------------------------------------------------ tools

    private fun args(call: ToolCall): JsonObject =
        runCatching { json.parseToJsonElement(call.arguments).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    private fun JsonObject.i(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    private fun txJson(t: TransactionEntity, full: Boolean = false) = buildJsonObject {
        put("id", t.id)
        put("type", if (t.type == TxType.SENT) "sent" else "received")
        put("amount", t.amount)
        put("currency", t.currency)
        put("person", t.displayName)
        put("date", Instant.ofEpochMilli(t.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString())
        t.reference?.let { put("operation_number", it) }
        t.note?.let { put("note", it) }
        if (t.needsReview) put("needs_review", true)
        if (full) {
            t.senderName?.let { put("sender_name", it) }
            t.receiverName?.let { put("receiver_name", it) }
            t.senderAccount?.let { put("sender_account", it) }
            t.receiverAccount?.let { put("receiver_account", it) }
            t.fee?.let { put("fee", it) }
            put("source", t.source.label)
            t.reviewReason?.let { put("review_reason", it) }
        }
    }

    private suspend fun runTool(channel: String, call: ToolCall, proposed: MutableList<Long>): JsonObject {
        val a = args(call)
        return when (call.name) {
            "get_overview" -> overview()
            "search_transactions" -> search(a)
            "list_people" -> listPeople(a)
            "get_person" -> getPerson(a.s("name") ?: return err("name is required"))
            "get_transaction" -> {
                val t = repo.get(a.l("id") ?: return err("id is required")) ?: return err("transaction not found")
                txJson(t, full = true)
            }
            "propose_delete_transaction" -> {
                val t = repo.get(a.l("id") ?: return err("id is required")) ?: return err("transaction not found")
                val desc = "حذف المعاملة: ${TxFormat.typeLabel(t.type)} ${Money.withCurrency(t.amount, t.currency)} ${if (t.type == TxType.SENT) "إلى" else "من"} ${t.displayName} (${TxFormat.date(t.timestamp)})"
                pending(channel, ActionExecutor.Kind.DELETE_TRANSACTION, buildJsonObject { put("id", t.id) }, desc, proposed)
            }
            "propose_delete_person" -> {
                val p = repo.findPerson(a.s("name") ?: return err("name is required")) ?: return notFound(a.s("name"))
                val n = repo.transactionCount(p.key)
                pending(
                    channel, ActionExecutor.Kind.DELETE_PERSON,
                    buildJsonObject { put("key", p.key); put("name", p.displayName) },
                    "حذف «${p.displayName}» مع جميع معاملاته ($n معاملة)", proposed,
                )
            }
            "propose_pin_person" -> {
                val p = repo.findPerson(a.s("name") ?: return err("name is required")) ?: return notFound(a.s("name"))
                val pin = a.b("pinned") ?: true
                pending(
                    channel, ActionExecutor.Kind.PIN_PERSON,
                    buildJsonObject { put("key", p.key); put("name", p.displayName); put("pinned", pin) },
                    if (pin) "تثبيت «${p.displayName}» في أعلى قائمة الأشخاص" else "إلغاء تثبيت «${p.displayName}»", proposed,
                )
            }
            "propose_add_transaction" -> {
                val type = a.s("type") ?: return err("type is required")
                val amount = a.d("amount") ?: return err("amount is required")
                val name = a.s("counterparty_name") ?: return err("counterparty_name is required")
                val currency = AmountParser.normalizeCurrency(a.s("currency")) ?: settings.current().primaryCurrency
                val when_ = listOfNotNull(a.s("date"), a.s("time")).joinToString(" ").ifEmpty { "الآن" }
                val payload = buildJsonObject {
                    put("type", if (type == "received") "received" else "sent")
                    put("amount", amount)
                    put("currency", currency)
                    put("counterparty_name", name)
                    a.s("date")?.let { put("date", it) }
                    a.s("time")?.let { put("time", it) }
                    a.s("note")?.let { put("note", it) }
                    a.s("reference")?.let { put("reference", it) }
                }
                val verb = if (type == "received") "استلام" else "إرسال"
                val dir = if (type == "received") "من" else "إلى"
                pending(channel, ActionExecutor.Kind.ADD_TRANSACTION, payload, "إضافة معاملة: $verb ${Money.withCurrency(amount, currency)} $dir «$name» ($when_)", proposed)
            }
            "propose_add_person" -> {
                val name = a.s("name") ?: return err("name is required")
                val payload = buildJsonObject { put("name", name); a.s("account")?.let { put("account", it) }; a.s("note")?.let { put("note", it) } }
                pending(channel, ActionExecutor.Kind.ADD_PERSON, payload, "إضافة الشخص «$name»", proposed)
            }
            "propose_rename_person" -> {
                val p = repo.findPerson(a.s("name") ?: return err("name is required")) ?: return notFound(a.s("name"))
                val newName = a.s("new_name") ?: return err("new_name is required")
                val target = repo.person(ArabicText.nameKey(newName))
                val extra = if (target != null && target.key != p.key) " (سيتم دمجه مع «${target.displayName}» الموجود)" else ""
                pending(
                    channel, ActionExecutor.Kind.RENAME_PERSON,
                    buildJsonObject { put("key", p.key); put("new_name", newName) },
                    "تغيير اسم «${p.displayName}» إلى «$newName»$extra", proposed,
                )
            }
            "propose_set_balance" -> {
                val amount = a.d("amount") ?: return err("amount is required")
                val currency = AmountParser.normalizeCurrency(a.s("currency")) ?: settings.current().primaryCurrency
                pending(
                    channel, ActionExecutor.Kind.SET_BALANCE,
                    buildJsonObject { put("currency", currency); put("amount", amount) },
                    "ضبط الرصيد الحالي إلى ${Money.withCurrency(amount, currency)}", proposed,
                )
            }
            else -> err("unknown tool ${call.name}")
        }
    }

    private fun err(msg: String) = buildJsonObject { put("error", msg) }

    private suspend fun notFound(name: String?): JsonObject {
        val people = repo.allPeople()
        val key = ArabicText.looseKey(name)
        val suggestions = people.sortedByDescending { ArabicText.similarity(ArabicText.looseKey(it.displayName), key) }.take(5).map { it.displayName }
        return buildJsonObject {
            put("error", "person not found: $name")
            putJsonArray("closest_names") { suggestions.forEach { add(JsonPrimitive(it)) } }
        }
    }

    private suspend fun pending(channel: String, kind: String, payload: JsonObject, description: String, proposed: MutableList<Long>): JsonObject {
        val a = actions.propose(channel, kind, payload, description)
        proposed += a.id
        return buildJsonObject {
            put("status", "awaiting_user_confirmation")
            put("action_id", a.id)
            put("description", description)
            put("instruction", "Tell the user to confirm with the button. Nothing has been changed yet.")
        }
    }

    private suspend fun overview(): JsonObject {
        val s = settings.current()
        val txs = repo.all()
        val lites = txs.map { it.toLite() }
        val currencies = (txs.map { it.currency } + s.balances.keys + s.primaryCurrency).distinct()
        val people = repo.summarizePeople(repo.allPeople(), txs)
        return buildJsonObject {
            put("transactions_count", txs.size)
            put("people_count", people.size)
            put("needs_review_count", txs.count { it.needsReview })
            putJsonObject("by_currency") {
                for (c in currencies) {
                    putJsonObject(c) {
                        SummaryCalculator.balance(lites, c, s.balances[c])?.let { put("balance_now", it) }
                        for (p in Period.entries) {
                            val sm = SummaryCalculator.summarize(lites, c, p, s.balances[c])
                            putJsonObject(p.name.lowercase()) {
                                put("received", sm.received); put("sent", sm.sent)
                                put("received_count", sm.receivedCount); put("sent_count", sm.sentCount)
                            }
                        }
                    }
                }
            }
            putJsonArray("top_sent_to") {
                people.sortedByDescending { it.sent(s.primaryCurrency) }.take(5).filter { it.sent(s.primaryCurrency) > 0 }.forEach {
                    add(buildJsonObject { put("name", it.person.displayName); put("sent", it.sent(s.primaryCurrency)); put("currency", s.primaryCurrency) })
                }
            }
            putJsonArray("top_received_from") {
                people.sortedByDescending { it.received(s.primaryCurrency) }.take(5).filter { it.received(s.primaryCurrency) > 0 }.forEach {
                    add(buildJsonObject { put("name", it.person.displayName); put("received", it.received(s.primaryCurrency)); put("currency", s.primaryCurrency) })
                }
            }
        }
    }

    private suspend fun search(a: JsonObject): JsonObject {
        val zone = ZoneId.systemDefault()
        var list = repo.all()
        a.s("type")?.let { t -> list = list.filter { (t == "sent") == (it.type == TxType.SENT) } }
        a.s("person")?.let { name ->
            val key = ArabicText.looseKey(name)
            list = list.filter { ArabicText.sameName(it.counterpartyName, name) || (key.length >= 3 && ArabicText.looseKey(it.counterpartyName).contains(key)) }
        }
        a.s("query")?.let { q ->
            val k = ArabicText.nameKey(q)
            list = list.filter { t ->
                listOfNotNull(t.counterpartyName, t.note, t.reference, t.senderName, t.receiverName, t.operationLabel)
                    .any { ArabicText.nameKey(it).contains(k) }
            }
        }
        a.s("from_date")?.let { DateParser.parseDate(it) }?.let { d ->
            val from = d.atStartOfDay(zone).toInstant().toEpochMilli(); list = list.filter { it.timestamp >= from }
        }
        a.s("to_date")?.let { DateParser.parseDate(it) }?.let { d ->
            val to = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(); list = list.filter { it.timestamp < to }
        }
        a.d("min_amount")?.let { m -> list = list.filter { it.amount >= m } }
        a.d("max_amount")?.let { m -> list = list.filter { it.amount <= m } }
        AmountParser.normalizeCurrency(a.s("currency"))?.let { c -> list = list.filter { it.currency == c } }
        if (a.b("needs_review") == true) list = list.filter { it.needsReview }
        val limit = (a.i("limit") ?: 20).coerceIn(1, 50)
        return buildJsonObject {
            put("total_matches", list.size)
            putJsonObject("totals") {
                for ((c, l) in list.groupBy { it.currency }) {
                    putJsonObject(c) {
                        put("sent", Money.round2(l.filter { it.type == TxType.SENT }.sumOf { it.amount }))
                        put("received", Money.round2(l.filter { it.type == TxType.RECEIVED }.sumOf { it.amount }))
                    }
                }
            }
            putJsonArray("transactions") { list.take(limit).forEach { add(txJson(it)) } }
        }
    }

    private suspend fun listPeople(a: JsonObject): JsonObject {
        val s = settings.current()
        var people = repo.summarizePeople(repo.allPeople(), repo.all())
        a.s("query")?.let { q -> val k = ArabicText.looseKey(q); people = people.filter { ArabicText.looseKey(it.person.displayName).contains(k) || ArabicText.sameName(it.person.displayName, q) } }
        val c = s.primaryCurrency
        people = when (a.s("sort_by")) {
            "sent" -> people.sortedByDescending { it.sent(c) }
            "received" -> people.sortedByDescending { it.received(c) }
            "count" -> people.sortedByDescending { it.count }
            "name" -> people.sortedBy { it.person.displayName.lowercase() }
            else -> people.sortedByDescending { it.lastTimestamp ?: 0L }
        }
        val limit = (a.i("limit") ?: 20).coerceIn(1, 100)
        return buildJsonObject {
            put("total_people", people.size)
            putJsonArray("people") {
                people.take(limit).forEach { p ->
                    add(buildJsonObject {
                        put("name", p.person.displayName)
                        put("pinned", p.person.pinned)
                        put("transactions", p.count)
                        putJsonObject("totals") {
                            p.totals.forEach { (cur, v) -> putJsonObject(cur) { put("sent_to_them", Money.round2(v.first)); put("received_from_them", Money.round2(v.second)) } }
                        }
                        p.lastTimestamp?.let { put("last_date", Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString()) }
                    })
                }
            }
        }
    }

    private suspend fun getPerson(name: String): JsonObject {
        val p = repo.findPerson(name) ?: return notFound(name)
        val txs = repo.all().filter { it.counterpartyKey == p.key }
        val summary = repo.summarizePeople(listOf(p), txs).first()
        return buildJsonObject {
            put("name", p.displayName)
            put("pinned", p.pinned)
            p.account?.let { put("account", it) }
            p.note?.let { put("note", it) }
            put("transactions_count", txs.size)
            putJsonObject("totals") {
                summary.totals.forEach { (cur, v) -> putJsonObject(cur) { put("sent_to_them", Money.round2(v.first)); put("received_from_them", Money.round2(v.second)) } }
            }
            putJsonArray("recent_transactions") { txs.take(15).forEach { add(txJson(it)) } }
        }
    }
}
