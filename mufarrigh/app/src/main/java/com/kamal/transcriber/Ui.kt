package com.kamal.transcriber

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------ theme

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFF0F766E),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCDEEE9),
        onPrimaryContainer = Color(0xFF00201D),
        background = Color(0xFFF6F8F7),
        surface = Color.White,
        surfaceVariant = Color(0xFFE3EAE8),
        onSurfaceVariant = Color(0xFF3F4947)
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }
}

// ------------------------------------------------------------------ root

@Composable
fun App(vm: MainViewModel) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf("home") }
    var detailId by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        vm.toast.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }

    BackHandler(enabled = screen != "home") {
        if (screen == "detail") vm.closeTranscript()
        screen = "home"
    }

    when (screen) {
        "settings" -> SettingsScreen(vm, onBack = { screen = "home" })
        "detail" -> DetailScreen(vm, detailId, onBack = {
            vm.closeTranscript()
            screen = "home"
        })
        else -> HomeScreen(
            vm,
            onOpen = { id ->
                detailId = id
                screen = "detail"
            },
            onSettings = { screen = "settings" }
        )
    }
}

// ------------------------------------------------------------------ helpers

private fun liveFor(job: JobInfo, live: LiveJob?): LiveJob? =
    if (live != null && live.jobId == job.id) live else null

private fun jobFraction(job: JobInfo, live: LiveJob?): Float? {
    val l = liveFor(job, live)
    if (l != null) return l.fraction.coerceIn(0f, 1f)
    return when (job.status) {
        JobStatus.DONE -> 1f
        JobStatus.TRANSCRIBING, JobStatus.STOPPED ->
            if (job.totalSamples > 0) (job.nextSample.toFloat() / job.totalSamples).coerceIn(0f, 1f) else null
        else -> null
    }
}

private fun jobStatusText(job: JobInfo, live: LiveJob?): String {
    val l = liveFor(job, live)
    return when (job.status) {
        JobStatus.COPYING -> "جارٍ تجهيز الملف…"
        JobStatus.QUEUED -> "في الانتظار"
        JobStatus.DECODING, JobStatus.TRANSCRIBING -> {
            if (l != null) {
                val pct = (l.fraction * 100).toInt().coerceIn(0, 100)
                val eta = if (l.etaSec > 0) " — متبقي نحو " + formatEta(l.etaSec) else ""
                l.phase + " " + pct + "%" + eta
            } else if (job.status == JobStatus.DECODING) {
                "تحويل الصوت…"
            } else {
                "جارٍ التفريغ…"
            }
        }
        JobStatus.DONE -> if (job.durationMs > 0) "اكتمل — مدة التسجيل " + TextFmt.clock(job.durationMs) else "اكتمل"
        JobStatus.STOPPED -> if (job.message.isBlank()) "متوقف" else job.message
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

// ------------------------------------------------------------------ home

@Composable
fun HomeScreen(vm: MainViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val jobs by vm.jobs.collectAsState()
    val live by LiveBus.job.collectAsState()
    val st by vm.settingsState.collectAsState()
    val dl by LiveBus.download.collectAsState()
    var confirmDelete by remember { mutableStateOf<JobInfo?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addJob(uri)
    }
    val hasModel = st.modelFile.isNotBlank()
    val d = dl

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("مُفرِّغ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "تفريغ الصوت إلى نص على هاتفك دون إنترنت",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onSettings) { Text("الإعدادات") }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (hasModel) MaterialTheme.colorScheme.primaryContainer else Color(0xFFFFE9C7)
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasModel) {
                        Text("المودل الحالي", style = MaterialTheme.typography.labelLarge)
                        Text(Models.title(st.modelFile), fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("لا يوجد مودل مثبّت", fontWeight = FontWeight.Bold)
                        Text(
                            "افتح «الإعدادات» وحمّل المودل الموصى به (نحو 574 ميغا) مرة واحدة فقط، وبعدها يعمل التطبيق دون إنترنت.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(onClick = onSettings) { Text("تحميل مودل") }
                    }
                    if (d != null && !d.finished && d.error == null) {
                        Text(
                            "جارٍ تنزيل المودل: " + (d.done / (1024 * 1024)) + " ميغا",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (d.total > 0) {
                            LinearProgressIndicator(
                                progress = (d.done.toFloat() / d.total).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = { pick.launch(arrayOf("audio/*", "video/*", "application/ogg")) },
                enabled = hasModel,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text("اختيار ملف صوتي أو فيديو للتفريغ", fontSize = 16.sp)
            }
        }

        if (jobs.isEmpty()) {
            item {
                Text(
                    "لا توجد تفريغات بعد. اختر ملفًا، أو شارك رسالة صوتية (مثل واتساب) مباشرة إلى «مُفرِّغ».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(jobs, key = { it.id }) { job ->
            JobCard(
                job = job,
                live = live,
                onOpen = { onOpen(job.id) },
                onStop = { vm.stop() },
                onResume = { vm.resume(job.id) },
                onDelete = { confirmDelete = job }
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("لضمان العمل في الخلفية")
                    Text(
                        "التفريغ الطويل يحتاج أن لا يوقفه نظام توفير البطارية. اسمح للتطبيق بالعمل دون قيود، وفي قائمة التطبيقات الأخيرة ثبّته (قفل) حتى لا يُغلق.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (e: Exception) {
                            Toast.makeText(context, "افتح إعدادات البطارية يدويًا", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("إعدادات تحسين البطارية") }
                }
            }
        }
    }

    val toDelete = confirmDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("حذف هذا التفريغ؟") },
            text = { Text(toDelete.name) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteJob(toDelete.id)
                    confirmDelete = null
                }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun JobCard(
    job: JobInfo,
    live: LiveJob?,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit
) {
    val frac = jobFraction(job, live)
    val hasText = job.status == JobStatus.DONE || job.nextSample > 0
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                job.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                jobStatusText(job, live),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (frac != null && job.status != JobStatus.DONE) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = frac, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasText) {
                    TextButton(onClick = onOpen) {
                        Text(if (job.status == JobStatus.DONE) "فتح النص" else "عرض ما تم تفريغه")
                    }
                }
                if (job.isRunning) {
                    TextButton(onClick = onStop) { Text("إيقاف") }
                }
                if (job.status == JobStatus.STOPPED) {
                    TextButton(onClick = onResume) { Text("استئناف") }
                }
                if (!job.isRunning) {
                    TextButton(onClick = onDelete) {
                        Text("حذف", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val st by vm.settingsState.collectAsState()
    val models by vm.models.collectAsState()
    val dl by LiveBus.download.collectAsState()
    val importing by vm.importing.collectAsState()
    var promptText by rememberSaveable { mutableStateOf(st.prompt) }
    var customUrl by rememberSaveable { mutableStateOf("") }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    val d = dl
    val downloadingNow = d != null && !d.finished && d.error == null
    val installedNames = models.map { it.file }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(12.dp))
            Text("الإعدادات", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        SectionTitle("المودل")
        if (models.isEmpty()) {
            Text("لا توجد مودلات مثبّتة.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (m in models) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.selectModel(m.file) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = st.modelFile == m.file, onClick = { vm.selectModel(m.file) })
                    Column(Modifier.weight(1f)) {
                        Text(Models.title(m.file), fontWeight = FontWeight.SemiBold)
                        Text(
                            m.sizeMb.toString() + " ميغا",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { vm.deleteModel(m.file) }) {
                        Text("حذف", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        val toDownload = Models.catalog.filter { it.file !in installedNames }
        if (toDownload.isNotEmpty()) {
            Text("مودلات متاحة للتنزيل (مرة واحدة، يلزم إنترنت)", fontWeight = FontWeight.SemiBold)
        }
        for (c in toDownload) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(c.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        c.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { vm.downloadModel(c) }, enabled = !downloadingNow) {
                        Text("تنزيل (نحو " + c.sizeMb + " ميغا)")
                    }
                }
            }
        }

        if (d != null && downloadingNow) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("جارٍ التنزيل: " + d.file, fontWeight = FontWeight.SemiBold)
                    if (d.total > 0) {
                        LinearProgressIndicator(
                            progress = (d.done.toFloat() / d.total).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text((d.done / (1024 * 1024)).toString() + " / " + (d.total / (1024 * 1024)) + " ميغا")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text((d.done / (1024 * 1024)).toString() + " ميغا")
                    }
                    OutlinedButton(onClick = { vm.cancelDownload() }) { Text("إلغاء التنزيل") }
                    Text(
                        "إن انقطع التنزيل اضغط تنزيل مرة أخرى ليكمل من حيث توقف.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (d != null && d.error != null) {
            Text("تعذّر التنزيل: " + d.error, color = MaterialTheme.colorScheme.error)
        }

        OutlinedButton(
            onClick = { importPicker.launch(arrayOf("*/*")) },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (importing) "جارٍ الاستيراد…" else "استيراد ملف مودل (.bin) من الجهاز")
        }
        Text(
            "يمكنك استيراد أي مودل whisper بصيغة ggml، مثل نسخ whisper المعدّلة (fine-tuned) للعربية بعد تحويلها.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it },
            label = { Text("أو رابط تنزيل مباشر لملف .bin") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = { vm.downloadCustom(customUrl) },
            enabled = customUrl.isNotBlank() && !downloadingNow,
            modifier = Modifier.fillMaxWidth()
        ) { Text("تنزيل من الرابط") }

        Divider()

        SectionTitle("لغة التسجيل")
        val langs = listOf(
            "ar" to "العربية (فصحى ولهجات)",
            "en" to "English",
            "fr" to "Français",
            "auto" to "تلقائي (يكتشف اللغة)"
        )
        for (l in langs) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { vm.setLanguage(l.first) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = st.language == l.first, onClick = { vm.setLanguage(l.first) })
                Text(l.second)
            }
        }

        Divider()

        SectionTitle("عدد الخيوط (المعالجات)")
        Text(
            "4 هو الأنسب لمعالج هاتفك (4 أنوية قوية). الرفع قد يبطئ ويسخّن الجهاز.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in listOf(3, 4, 6, 8)) {
                if (st.threads == n) {
                    Button(onClick = { vm.setThreads(n) }, modifier = Modifier.weight(1f)) { Text(n.toString()) }
                } else {
                    OutlinedButton(onClick = { vm.setThreads(n) }, modifier = Modifier.weight(1f)) { Text(n.toString()) }
                }
            }
        }

        Divider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ربط السياق بين المقاطع", fontWeight = FontWeight.SemiBold)
                Text(
                    "يمرّر نهاية المقطع السابق للمقطع التالي ليحافظ على الأسلوب والإملاء. أوقفه إن لاحظت تكرارًا غريبًا.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = st.useContext, onCheckedChange = { vm.setUseContext(it) })
        }

        OutlinedTextField(
            value = promptText,
            onValueChange = {
                promptText = it
                vm.setPrompt(it)
            },
            label = { Text("نص تمهيدي اختياري (أسماء، مصطلحات، نموذج لهجة)") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "مثال: كلمات أو أسماء تتكرر في تسجيلاتك، أو جملة قصيرة بالأسلوب الذي تريده. يساعد المودل على كتابتها بشكل صحيح.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ transcript

@Composable
fun DetailScreen(vm: MainViewModel, id: String, onBack: () -> Unit) {
    LaunchedEffect(id) { vm.openTranscript(id) }
    val t by vm.transcript.collectAsState()
    val ui = t
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var withTimes by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf("") }

    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) vm.writeText(uri, pending)
    }
    val saveSrt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) vm.writeText(uri, pending)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(12.dp))
            Text(
                ui?.job?.name ?: "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        if (ui == null || ui.job.id != id) {
            Text("جارٍ التحميل…")
        } else {
            val job = ui.job
            val segs = ui.segs
            val lines = remember(segs, withTimes) {
                if (withTimes) segs.map { "[" + TextFmt.clock(it.startMs) + "] " + it.text } else TextFmt.paragraphs(segs)
            }
            val fullText = remember(segs, withTimes) {
                if (withTimes) TextFmt.timed(segs) else TextFmt.plain(segs)
            }
            val baseName = job.name.substringBeforeLast('.').replace('/', '_').ifBlank { "تفريغ" }

            if (job.status != JobStatus.DONE) {
                Text(
                    "هذا نص جزئي — التفريغ لم يكتمل بعد. " + (if (job.status == JobStatus.STOPPED) job.message else ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(fullText))
                    Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
                }, enabled = segs.isNotEmpty()) { Text("نسخ") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, fullText)
                    }
                    context.startActivity(Intent.createChooser(send, "مشاركة النص"))
                }, enabled = segs.isNotEmpty()) { Text("مشاركة") }
                OutlinedButton(onClick = {
                    pending = fullText
                    saveTxt.launch("$baseName.txt")
                }, enabled = segs.isNotEmpty()) { Text("حفظ TXT") }
                OutlinedButton(onClick = {
                    pending = TextFmt.srt(segs)
                    saveSrt.launch("$baseName.srt")
                }, enabled = segs.isNotEmpty()) { Text("حفظ SRT") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = withTimes, onCheckedChange = { withTimes = it })
                Spacer(Modifier.width(8.dp))
                Text("إظهار الأزمنة")
            }

            Divider()

            if (segs.isEmpty()) {
                Text("لا يوجد نص بعد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(lines) { line ->
                        SelectionContainer {
                            Text(line, style = MaterialTheme.typography.bodyLarge, lineHeight = 30.sp)
                        }
                    }
                }
            }
        }
    }
}
