package com.smartcash.analyzer.ui.people

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartcash.analyzer.data.PersonSummary
import com.smartcash.analyzer.data.db.PersonEntity
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.TxFormat
import com.smartcash.analyzer.ui.LocalContainer
import com.smartcash.analyzer.ui.Routes
import com.smartcash.analyzer.ui.components.BackButton
import com.smartcash.analyzer.ui.components.ConfirmDialog
import com.smartcash.analyzer.ui.components.EmptyState
import com.smartcash.analyzer.ui.components.PersonAvatar
import com.smartcash.analyzer.ui.components.TransactionRow
import com.smartcash.analyzer.ui.theme.LocalMoneyColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeopleScreen(nav: NavHostController) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val people by remember { c.repo.observePeople() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("recent") }
    var sortMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<PersonSummary?>(null) }
    var deleting by remember { mutableStateOf<PersonSummary?>(null) }
    var renaming by remember { mutableStateOf<PersonEntity?>(null) }

    val key = ArabicText.looseKey(query)
    val list = people
        .filter { key.isEmpty() || ArabicText.looseKey(it.person.displayName).contains(key) || ArabicText.sameName(it.person.displayName, query) }
        .sortedWith(
            compareByDescending<PersonSummary> { it.person.pinned }.then(
                when (sort) {
                    "sent" -> compareByDescending<PersonSummary> { it.totals.values.sumOf { v -> v.first } }
                    "received" -> compareByDescending<PersonSummary> { it.totals.values.sumOf { v -> v.second } }
                    "count" -> compareByDescending<PersonSummary> { it.count }
                    "name" -> compareBy<PersonSummary> { it.person.displayName.lowercase() }
                    else -> compareByDescending<PersonSummary> { it.lastTimestamp ?: 0L }
                }
            )
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الأشخاص (${people.size})") },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "ترتيب") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("recent" to "الأحدث", "sent" to "الأكثر إرسالاً له", "received" to "الأكثر استلاماً منه", "count" to "عدد المعاملات", "name" to "الاسم")
                                .forEach { (k, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { sort = k; sortMenu = false }) }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Rounded.PersonAdd, null) }, text = { Text("إضافة شخص") }, containerColor = MaterialTheme.colorScheme.primary)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("ابحث عن شخص") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "مسح") } },
                shape = RoundedCornerShape(16.dp),
            )
            if (list.isEmpty()) {
                EmptyState(Icons.Rounded.Groups, if (people.isEmpty()) "لا يوجد أشخاص بعد" else "لا توجد نتائج", "يُضاف الأشخاص تلقائياً من المعاملات، أو أضفهم يدوياً.")
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.person.id }) { p ->
                        Box {
                            PersonCard(p, Modifier.combinedClickable(onClick = { nav.navigate(Routes.person(p.person.key)) }, onLongClick = { menuFor = p }))
                            DropdownMenu(expanded = menuFor?.person?.id == p.person.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(
                                    text = { Text(if (p.person.pinned) "إلغاء التثبيت" else "تثبيت في الأعلى") },
                                    leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                                    onClick = { scope.launch { c.repo.setPinned(p.person.key, !p.person.pinned) }; menuFor = null },
                                )
                                DropdownMenuItem(text = { Text("تعديل الاسم") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { renaming = p.person; menuFor = null })
                                DropdownMenuItem(text = { Text("حذف") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { deleting = p; menuFor = null })
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) AddPersonDialog(onDismiss = { adding = false }) { name, account, phone, note ->
        scope.launch { c.repo.addPerson(name, account, phone, note) }
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "حذف «${p.person.displayName}»؟",
            text = "سيتم حذف هذا الشخص مع جميع معاملاته (${p.count} معاملة)، وسيتغير الرصيد المحسوب. لا يمكن التراجع.",
            confirmText = "حذف", destructive = true,
            onConfirm = { scope.launch { c.repo.deletePerson(p.person.key) } },
            onDismiss = { deleting = null },
        )
    }
    renaming?.let { p -> RenameDialog(p, onDismiss = { renaming = null }) { newName -> scope.launch { c.repo.renamePerson(p.key, newName) } } }
}

@Composable
private fun PersonCard(p: PersonSummary, modifier: Modifier = Modifier) {
    val money = LocalMoneyColors.current
    Card(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(p.person.displayName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.person.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (p.person.pinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.PushPin, "مثبت", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                val totals = p.totals.ifEmpty { mapOf("USD" to (0.0 to 0.0)) }
                totals.forEach { (cur, v) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("أرسلت: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Money.withCurrency(v.first, cur), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = money.sent)
                        Text("استلمت: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Money.withCurrency(v.second, cur), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = money.received)
                    }
                }
                Text(
                    "${p.count} معاملة" + (p.lastTimestamp?.let { " · آخرها ${TxFormat.relative(it, false)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AddPersonDialog(onDismiss: () -> Unit, onSave: (String, String?, String?, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة شخص") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم كما في شام كاش *") }, singleLine = true)
                OutlinedTextField(account, { account = it }, label = { Text("رقم الحساب (اختياري)") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("الهاتف (اختياري)") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("ملاحظة (اختياري)") })
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, account, phone, note); onDismiss() }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun RenameDialog(p: PersonEntity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(p.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل الاسم") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                Text("إذا كتبت اسم شخص موجود سيتم دمج الاثنين في شخص واحد.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name); onDismiss() }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
fun PersonDetailScreen(nav: NavHostController, key: String) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val person by remember(key) { c.repo.observePerson(key) }.collectAsStateWithLifecycle(initialValue = null)
    val txs by remember(key) { c.repo.observeByPerson(key) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var deleting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val money = LocalMoneyColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(person?.displayName ?: "") },
                navigationIcon = { BackButton { nav.popBackStack() } },
                actions = {
                    person?.let { p ->
                        IconButton(onClick = { scope.launch { c.repo.setPinned(p.key, !p.pinned) } }) {
                            Icon(if (p.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (p.pinned) "إلغاء التثبيت" else "تثبيت")
                        }
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "تعديل") }
                        IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.edit(person = person?.displayName)) },
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("معاملة جديدة") }, containerColor = MaterialTheme.colorScheme.primary,
            )
        },
    ) { padding ->
        val p = person ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PersonAvatar(p.displayName, 72.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(p.displayName, style = MaterialTheme.typography.headlineSmall)
                    p.account?.let { Text("الحساب: $it", style = MaterialTheme.typography.bodySmall) }
                    p.phone?.let { Text("الهاتف: $it", style = MaterialTheme.typography.bodySmall) }
                    p.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            val byCur = txs.groupBy { it.currency }
            byCur.forEach { (cur, l) ->
                item(key = "sum$cur") {
                    val sent = l.filter { it.type == com.smartcash.analyzer.domain.TxType.SENT }.sumOf { it.amount }
                    val received = l.filter { it.type == com.smartcash.analyzer.domain.TxType.RECEIVED }.sumOf { it.amount }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SumTile(Modifier.weight(1f), "أرسلت له", Money.withCurrency(sent, cur), money.sent)
                        SumTile(Modifier.weight(1f), "استلمت منه", Money.withCurrency(received, cur), money.received)
                        SumTile(Modifier.weight(1f), "الصافي", Money.withCurrency(received - sent, cur), MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            item { Text("المعاملات (${txs.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp)) }
            items(txs, key = { it.id }) { tx -> TransactionRow(tx, onClick = { nav.navigate(Routes.tx(tx.id)) }, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
    }

    if (deleting) {
        person?.let { p ->
            ConfirmDialog(
                title = "حذف «${p.displayName}»؟",
                text = "سيتم حذف هذا الشخص مع جميع معاملاته (${txs.size} معاملة). لا يمكن التراجع.",
                confirmText = "حذف", destructive = true,
                onConfirm = { scope.launch { c.repo.deletePerson(p.key); nav.popBackStack() } },
                onDismiss = { deleting = false },
            )
        }
    }
    if (renaming) {
        person?.let { p ->
            RenameDialog(p, onDismiss = { renaming = false }) { newName ->
                scope.launch {
                    c.repo.renamePerson(p.key, newName)
                    nav.popBackStack()
                    nav.navigate(Routes.person(ArabicText.nameKey(newName)))
                }
            }
        }
    }
}

@Composable
private fun SumTile(modifier: Modifier, label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr), color = color, maxLines = 1)
        }
    }
}
