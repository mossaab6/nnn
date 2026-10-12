package com.smartcash.analyzer.ui.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.smartcash.analyzer.ocr.PageImages
import com.smartcash.analyzer.ui.LocalContainer
import com.smartcash.analyzer.ui.components.BackButton
import com.smartcash.analyzer.ui.components.EmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Scanned text → text": converts a scanned PDF or a photo into plain text, either fully offline
 * (Tesseract, Arabic + English) or with Grok vision, then optionally saves it as a transaction.
 */
@Composable
fun OcrScreen(nav: NavHostController) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u == null) return@rememberLauncherForActivityResult
        uri = u
        text = ""
        error = null
        scope.launch {
            busy = "تجهيز الصفحات…"
            pages = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: ByteArray(0)
                    val tmp = File(context.cacheDir, "ocr_input").apply { writeBytes(bytes) }
                    PageImages.load(tmp)
                }
            }.getOrElse { error = "تعذر فتح الملف: ${it.message}"; emptyList() }
            busy = null
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("تحويل الممسوح ضوئياً إلى نص") }, navigationIcon = { BackButton { nav.popBackStack() } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (uri == null) "اختر ملف PDF أو صورة" else "اختيار ملف آخر")
            }
            if (pages.isEmpty() && busy == null) {
                EmptyState(Icons.Rounded.DocumentScanner, "حوّل الإيصالات الممسوحة إلى نص", "يقرأ العربية والإنجليزية بدون إنترنت، أو بدقة أعلى عبر Grok.")
            }
            pages.firstOrNull()?.let { bmp ->
                Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                    Image(bmp.asImageBitmap(), "معاينة", modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = busy == null, modifier = Modifier.weight(1f), onClick = {
                        scope.launch {
                            busy = "قراءة النص على الجهاز…"
                            text = runCatching { c.ocrReader.read(pages).let { r -> r.fullText + if (r.rows.isNotEmpty()) "\n\n— الحقول —\n" + r.rows.joinToString("\n") else "" } }
                                .getOrElse { error = it.message; "" }
                            busy = null
                        }
                    }) { Text("بدون إنترنت (OCR)") }
                    Button(enabled = busy == null, modifier = Modifier.weight(1f), onClick = {
                        scope.launch {
                            busy = "Grok يقرأ الصورة…"
                            text = runCatching {
                                val r = c.extractor.readReceipt(pages.take(2).map { PageImages.toJpeg(it) })
                                r?.fullText ?: r?.parsed?.rawText ?: "لم يتم العثور على نص"
                            }.getOrElse { error = it.message; "" }
                            busy = null
                        }
                    }) { Text("بالذكاء الاصطناعي") }
                }
            }
            busy?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  $it")
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (text.isNotBlank()) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text("النص المستخرج", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ocr", text))
                                Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Rounded.ContentCopy, "نسخ") }
                            IconButton(onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "مشاركة"))
                            }) { Icon(Icons.Rounded.Share, "مشاركة") }
                        }
                        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                uri?.let { u ->
                    Button(onClick = { c.importController.import(listOf(u)) }, modifier = Modifier.fillMaxWidth()) { Text("تحليل وحفظ كمعاملة") }
                }
            }
        }
    }
}
