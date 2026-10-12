package com.smartcash.analyzer.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.Period
import com.smartcash.analyzer.domain.Periods
import com.smartcash.analyzer.domain.TxFormat
import com.smartcash.analyzer.domain.TxType
import com.smartcash.analyzer.ui.LocalContainer
import com.smartcash.analyzer.ui.Routes
import com.smartcash.analyzer.ui.components.EmptyState
import com.smartcash.analyzer.ui.components.TransactionRow
import com.smartcash.analyzer.ui.theme.LocalMoneyColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun TransactionsScreen(nav: NavHostController, initialFilter: String) {
    val c = LocalContainer.current
    val all by remember { c.repo.observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(initialFilter) } // all, sent, received, review
    var period by rememberSaveable { mutableStateOf(Period.ALL) }
    val money = LocalMoneyColors.current

    val from = Periods.startOf(period, System.currentTimeMillis())
    val key = ArabicText.nameKey(query)
    val list = all.filter { t ->
        t.timestamp >= from &&
            when (filter) {
                "sent" -> t.type == TxType.SENT
                "received" -> t.type == TxType.RECEIVED
                "review" -> t.needsReview
                else -> true
            } &&
            (key.isEmpty() || listOfNotNull(t.counterpartyName, t.note, t.reference, t.senderName, t.receiverName).any { ArabicText.nameKey(it).contains(key) } ||
                ArabicText.digits(query).let { d -> d.isNotEmpty() && Money.format(t.amount).replace(",", "").contains(d) })
    }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val grouped = list.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("المعاملات") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.edit()) }, containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Rounded.Add, "إضافة معاملة")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("ابحث بالاسم، الملاحظة، رقم العملية أو المبلغ") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "مسح") } },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                val filters = listOf("all" to "الكل", "sent" to "المرسل ↗", "received" to "المستلم ↙", "review" to "تحتاج مراجعة")
                items(filters) { (k, label) -> FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label) }) }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Period.entries) { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) }
            }
            // totals of what is shown
            val byCur = list.groupBy { it.currency }
            if (byCur.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    byCur.forEach { (cur, l) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("${l.size} عملية", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("+" + Money.withCurrency(l.filter { it.type == TxType.RECEIVED }.sumOf { it.amount }, cur), color = money.received, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                            Text("-" + Money.withCurrency(l.filter { it.type == TxType.SENT }.sumOf { it.amount }, cur), color = money.sent, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                        }
                    }
                }
            }
            if (list.isEmpty()) {
                EmptyState(Icons.Rounded.SearchOff, "لا توجد نتائج", "جرّب تغيير البحث أو الفلتر.")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    grouped.forEach { (date, txs) ->
                        item(key = "h$date") {
                            Text(
                                TxFormat.dayHeader(date, today),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
                            )
                        }
                        items(txs, key = { it.id }) { tx ->
                            TransactionRow(tx, onClick = { nav.navigate(Routes.tx(tx.id)) }, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
            }
        }
    }
}
