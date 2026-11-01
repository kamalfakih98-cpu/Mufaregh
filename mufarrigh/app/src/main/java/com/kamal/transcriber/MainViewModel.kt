package com.kamal.transcriber

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

data class SettingsState(
    val modelFile: String,
    val language: String,
    val threads: Int,
    val useContext: Boolean,
    val prompt: String
)

data class ModelRow(val file: String, val sizeMb: Long)

data class TranscriptUi(val job: JobInfo, val segs: List<Seg>)

/** Work that must outlive the screen (copying a big file into the app). */
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx: Application = app
    private val prefs = AppSettings(app)

    /** One-off messages for the UI (shown as toasts). */
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 8)

    // ---------------------------------------------------------------- jobs

    val jobs: StateFlow<List<JobInfo>> = JobStore.tick
        .map { JobStore.list(appCtx) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val openId = MutableStateFlow<String?>(null)

    val transcript: StateFlow<TranscriptUi?> = combine(openId, JobStore.tick) { id, _ -> id }
        .map { id ->
            if (id == null) {
                null
            } else {
                val j = JobStore.load(appCtx, id)
                if (j == null) null else TranscriptUi(j, JobStore.readSegments(appCtx, id))
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun openTranscript(id: String) {
        openId.value = id
    }

    fun closeTranscript() {
        openId.value = null
    }

    // ---------------------------------------------------------------- settings & models

    private fun readSettings(): SettingsState {
        val installed = Models.installed(appCtx).map { it.name }
        var selected = prefs.modelFile
        if (selected.isNotBlank() && selected !in installed) selected = ""
        if (selected.isBlank() && installed.isNotEmpty()) {
            selected = installed.first()
            prefs.modelFile = selected
        }
        return SettingsState(selected, prefs.language, prefs.threads, prefs.useContext, prefs.prompt)
    }

    private val _settings = MutableStateFlow(readSettings())
    val settingsState: StateFlow<SettingsState> = _settings

    private fun readModels(): List<ModelRow> =
        Models.installed(appCtx).map { ModelRow(it.name, it.length() / (1024 * 1024)) }

    private val _models = MutableStateFlow(readModels())
    val models: StateFlow<List<ModelRow>> = _models

    val importing = MutableStateFlow(false)

    fun refreshModels() {
        _models.value = readModels()
        _settings.value = readSettings()
    }

    fun setLanguage(v: String) {
        prefs.language = v
        _settings.value = readSettings()
    }

    fun setThreads(v: Int) {
        prefs.threads = v
        _settings.value = readSettings()
    }

    fun setUseContext(v: Boolean) {
        prefs.useContext = v
        _settings.value = readSettings()
    }

    fun setPrompt(v: String) {
        prefs.prompt = v
        _settings.value = readSettings()
    }

    fun selectModel(file: String) {
        prefs.modelFile = file
        _settings.value = readSettings()
    }

    fun deleteModel(file: String) {
        viewModelScope.launch(Dispatchers.IO) {
            File(Models.dir(appCtx), file).delete()
            File(Models.dir(appCtx), "$file.part").delete()
            refreshModels()
        }
    }

    fun downloadModel(m: CatalogModel) {
        TranscriptionService.download(appCtx, m.url, m.file)
    }

    fun downloadCustom(url: String) {
        val u = url.trim()
        if (!u.startsWith("https://") && !u.startsWith("http://")) {
            toast.tryEmit("الرابط غير صالح")
            return
        }
        val name = Models.safeName(u)
        TranscriptionService.download(appCtx, u, name)
    }

    fun cancelDownload() {
        TranscriptionService.cancelDownload(appCtx)
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            importing.value = true
            try {
                val name = Models.safeName(displayName(uri))
                val input = appCtx.contentResolver.openInputStream(uri) ?: throw IOException("تعذّر فتح الملف")
                Models.importFrom(appCtx, input, name)
                val s = AppSettings(appCtx)
                if (Models.file(appCtx, s.modelFile) == null) s.modelFile = name
                refreshModels()
                toast.tryEmit("تم استيراد المودل")
            } catch (e: Exception) {
                toast.tryEmit(e.message ?: "فشل الاستيراد")
            } finally {
                importing.value = false
            }
        }
    }

    // ---------------------------------------------------------------- creating / controlling jobs

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            appCtx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (e: Exception) {
            // ignore
        }
        return name ?: uri.lastPathSegment ?: "تسجيل"
    }

    fun addJob(uri: Uri) {
        if (Models.file(appCtx, readSettings().modelFile) == null) {
            toast.tryEmit("حمّل مودلًا أولًا من الإعدادات")
            return
        }
        appScope.launch {
            val id = UUID.randomUUID().toString()
            val name = displayName(uri)
            val job = JobInfo(id, name, System.currentTimeMillis(), JobStatus.COPYING)
            JobStore.save(appCtx, job)
            try {
                val dest = File(JobStore.dir(appCtx, id), "source.bin")
                val input = appCtx.contentResolver.openInputStream(uri) ?: throw IOException("تعذّر فتح الملف")
                input.use { src ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                JobStore.update(appCtx, id) { it.copy(status = JobStatus.QUEUED) }
                TranscriptionService.process(appCtx)
            } catch (e: Exception) {
                JobStore.delete(appCtx, id)
                toast.tryEmit("تعذّر نسخ الملف: " + (e.message ?: ""))
            }
        }
    }

    fun resume(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (Models.file(appCtx, readSettings().modelFile) == null) {
                toast.tryEmit("حمّل مودلًا أولًا من الإعدادات")
                return@launch
            }
            JobStore.update(appCtx, id) { it.copy(status = JobStatus.QUEUED, message = "") }
            TranscriptionService.process(appCtx)
        }
    }

    fun stop() {
        TranscriptionService.stop(appCtx)
    }

    fun deleteJob(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            JobStore.delete(appCtx, id)
        }
    }

    fun writeText(uri: Uri, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                appCtx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                toast.tryEmit("تم الحفظ")
            } catch (e: Exception) {
                toast.tryEmit("تعذّر الحفظ: " + (e.message ?: ""))
            }
        }
    }

    init {
        // Jobs left "running" by a process that died cannot really be running: mark them stopped.
        viewModelScope.launch(Dispatchers.IO) {
            if (!TranscriptionService.running) {
                for (j in JobStore.list(appCtx)) {
                    if (j.status == JobStatus.COPYING) {
                        JobStore.delete(appCtx, j.id)
                    } else if (j.isRunning) {
                        JobStore.update(appCtx, j.id) {
                            it.copy(status = JobStatus.STOPPED, message = "توقّفت المعالجة — اضغط «استئناف» للمتابعة من حيث وصلت")
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            LiveBus.download.collect { d ->
                if (d != null && d.finished) refreshModels()
            }
        }
    }
}
