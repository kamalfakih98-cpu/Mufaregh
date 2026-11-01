package com.kamal.transcriber

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Foreground service that does the heavy work (model download, audio decoding, transcription)
 * so it keeps running while the app is in the background or the screen is off.
 */
class TranscriptionService : Service() {

    companion object {
        const val ACTION_PROCESS = "com.kamal.transcriber.PROCESS"
        const val ACTION_DOWNLOAD = "com.kamal.transcriber.DOWNLOAD"
        const val ACTION_STOP = "com.kamal.transcriber.STOP"
        const val ACTION_CANCEL_DOWNLOAD = "com.kamal.transcriber.CANCEL_DOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_FILE = "file"

        private const val CH_WORK = "work"
        private const val CH_DONE = "done"
        private const val NOTIF_ID = 1

        @Volatile
        var running: Boolean = false

        fun process(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, TranscriptionService::class.java).setAction(ACTION_PROCESS)
            )
        }

        fun download(ctx: Context, url: String, file: String) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, TranscriptionService::class.java)
                    .setAction(ACTION_DOWNLOAD)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_FILE, file)
            )
        }

        fun stop(ctx: Context) {
            if (!running) return
            ctx.startService(Intent(ctx, TranscriptionService::class.java).setAction(ACTION_STOP))
        }

        fun cancelDownload(ctx: Context) {
            if (!running) return
            ctx.startService(Intent(ctx, TranscriptionService::class.java).setAction(ACTION_CANCEL_DOWNLOAD))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var processing = false
    private var downloading = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var downloadCancel = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    // The loaded whisper model, kept while the queue is being processed.
    private var ctxPtr = 0L
    private var ctxModel = ""

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannels()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mufarrigh:work").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        running = false
        WhisperLib.setCancel(true)
        scope.cancel()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(buildNotification("جارٍ التحضير…", -1))
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                downloadCancel = true
                WhisperLib.setCancel(true)
                finishIfIdle()
            }
            ACTION_CANCEL_DOWNLOAD -> {
                downloadCancel = true
                finishIfIdle()
            }
            ACTION_DOWNLOAD -> {
                val url = intent?.getStringExtra(EXTRA_URL)
                val file = intent?.getStringExtra(EXTRA_FILE)
                if (url != null && file != null) startDownload(url, file) else finishIfIdle()
            }
            else -> ensureWorker()
        }
        return START_NOT_STICKY
    }

    private fun finishIfIdle() {
        synchronized(lock) {
            if (!processing && !downloading) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_WORK, "التفريغ والتنزيل", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "اكتمال التفريغ", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildNotification(text: String, percent: Int): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CH_WORK)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("مُفرِّغ")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "إيقاف", stop)
        if (percent >= 0) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun notifyProgress(text: String, percent: Int, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, percent))
    }

    private fun notifyFinished(job: JobInfo, ok: Boolean) {
        val title = if (ok) "اكتمل التفريغ" else "توقّف التفريغ"
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(job.name)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1000 + (job.id.hashCode() and 0xFFFF), n)
    }

    // ------------------------------------------------------------------ model download

    private fun startDownload(url: String, file: String) {
        synchronized(lock) {
            if (downloading) return
            downloading = true
        }
        downloadCancel = false
        scope.launch {
            try {
                LiveBus.download.value = LiveDownload(file, 0, 0)
                Models.download(
                    this@TranscriptionService, url, file,
                    onProgress = { done, total ->
                        LiveBus.download.value = LiveDownload(file, done, total)
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        notifyProgress("تنزيل المودل ${mb(done)} / ${if (total > 0) mb(total) else "؟"} ميغا", pct)
                    },
                    cancelled = { downloadCancel }
                )
                val s = AppSettings(this@TranscriptionService)
                if (Models.file(this@TranscriptionService, s.modelFile) == null) s.modelFile = file
                LiveBus.download.value = LiveDownload(file, 1, 1, finished = true)
            } catch (e: Throwable) {
                LiveBus.download.value = LiveDownload(file, 0, 0, error = e.message ?: "فشل التنزيل")
            } finally {
                synchronized(lock) { downloading = false }
                finishIfIdle()
            }
        }
    }

    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)

    // ------------------------------------------------------------------ transcription queue

    private fun ensureWorker() {
        synchronized(lock) {
            if (processing) return
            processing = true
        }
        stopRequested = false
        WhisperLib.setCancel(false)
        scope.launch {
            try {
                runQueue()
            } catch (e: Throwable) {
                // runQueue handles per-job errors; this is only a last resort
            } finally {
                finishIfIdle()
            }
        }
    }

    private fun pickNext(): JobInfo? =
        JobStore.list(this).filter { it.status == JobStatus.QUEUED }.minByOrNull { it.createdAt }

    /**
     * Processes queued jobs one after another. The decision "nothing left, release the worker slot"
     * is taken under the same lock that ensureWorker() uses, so a job queued at the last moment
     * is never left unattended.
     */
    private fun runQueue() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    val n = if (stopRequested) null else pickNext()
                    if (n == null) {
                        if (stopRequested) {
                            // Stop everything that was still waiting.
                            for (j in JobStore.list(this)) {
                                if (j.status == JobStatus.QUEUED) {
                                    JobStore.update(this, j.id) { it.copy(status = JobStatus.STOPPED, message = "أُوقف") }
                                }
                            }
                        }
                        freeModel()
                        processing = false
                    }
                    n
                }
                if (next == null) return
                processJob(next)
            }
        } catch (e: Throwable) {
            synchronized(lock) {
                freeModel()
                processing = false
            }
            throw e
        }
    }

    private fun processJob(first: JobInfo) {
        val id = first.id
        val dir = JobStore.dir(this, id)
        val source = File(dir, "source.bin")
        val pcmFile = File(dir, "audio.pcm")
        try {
            var job = first

            // 1) decode to 16 kHz mono PCM
            if (!job.pcmReady) {
                JobStore.update(this, id) { it.copy(status = JobStatus.DECODING, message = "") }
                publish(id, "تحويل الصوت", 0f, -1)
                val total = AudioDecoder.decode(
                    source, pcmFile,
                    onProgress = { f -> publish(id, "تحويل الصوت", f, -1) },
                    stopped = { stopRequested }
                )
                job = JobStore.update(this, id) {
                    it.copy(
                        pcmReady = true,
                        totalSamples = total,
                        durationMs = total * 1000L / AudioDecoder.TARGET_RATE,
                        nextSample = 0L
                    )
                } ?: throw IOException("اختفى سجل المهمة")
                source.delete()
            }

            // 2) load the model
            JobStore.update(this, id) { it.copy(status = JobStatus.TRANSCRIBING, message = "") }
            val settings = AppSettings(this)
            val modelFile = Models.file(this, settings.modelFile)
                ?: throw IOException("لم يتم اختيار مودل — حمّل مودلًا من الإعدادات")
            publish(id, "تحميل المودل", 0f, -1)
            ensureModel(modelFile.absolutePath)

            // 3) transcribe chunk by chunk, saving after every chunk so it can resume
            val total = job.totalSamples
            var pos = job.nextSample
            val startPos = pos
            val startTime = SystemClock.elapsedRealtime()
            val language = settings.language
            val threads = settings.threads
            var tail = ""
            if (pos > 0 && settings.useContext) {
                val prev = JobStore.readSegments(this, id)
                tail = prev.joinToString(" ") { it.text }.takeLast(200)
            }

            while (pos < total) {
                if (stopRequested) throw StopRequested()
                waitForCooling(id, pos.toFloat() / total)

                val chunkStart = pos
                val chunkEnd = Pcm.planEnd(pcmFile, chunkStart, total)
                val samples = Pcm.read(pcmFile, chunkStart, (chunkEnd - chunkStart).toInt())
                val prompt = buildPrompt(settings, tail)

                val listener = object : WhisperProgress {
                    override fun onProgress(percent: Int) {
                        val done = chunkStart + (chunkEnd - chunkStart) * percent / 100
                        val frac = done.toFloat() / total
                        val elapsed = (SystemClock.elapsedRealtime() - startTime) / 1000.0
                        val processed = (done - startPos).toDouble()
                        val eta = if (elapsed > 8 && processed > 0) {
                            ((total - done) / (processed / elapsed)).toLong()
                        } else {
                            -1L
                        }
                        publish(id, "جارٍ التفريغ", frac, eta)
                    }
                }

                val rc = WhisperLib.transcribe(ctxPtr, samples, language, prompt, threads, listener)
                if (stopRequested) throw StopRequested()
                if (rc != 0) throw IOException("فشل التفريغ (رمز $rc)")

                val offsetMs = chunkStart * 1000L / AudioDecoder.TARGET_RATE
                val n = WhisperLib.segmentCount(ctxPtr)
                val raw = ArrayList<Seg>()
                for (i in 0 until n) {
                    val text = String(WhisperLib.segmentTextBytes(ctxPtr, i), Charsets.UTF_8).trim()
                    if (text.isEmpty()) continue
                    raw.add(
                        Seg(
                            offsetMs + WhisperLib.segmentStart(ctxPtr, i) * 10L,
                            offsetMs + WhisperLib.segmentEnd(ctxPtr, i) * 10L,
                            text
                        )
                    )
                }
                val segs = TextFmt.collapseRepeats(raw)
                JobStore.saveChunk(this, id, chunkStart, segs)

                pos = chunkEnd
                JobStore.update(this, id) { it.copy(nextSample = chunkEnd) }
                tail = if (settings.useContext) segs.joinToString(" ") { it.text }.takeLast(200) else ""
                publish(id, "جارٍ التفريغ", pos.toFloat() / total, -1)
            }

            // 4) done
            val finished = JobStore.update(this, id) {
                it.copy(status = JobStatus.DONE, message = "", nextSample = total)
            }
            pcmFile.delete()
            source.delete()
            if (finished != null) notifyFinished(finished, true)
        } catch (e: StopRequested) {
            JobStore.update(this, id) { it.copy(status = JobStatus.STOPPED, message = "أُوقف — يمكنك الاستئناف من حيث وصل") }
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            val j = JobStore.update(this, id) { it.copy(status = JobStatus.STOPPED, message = "تعذّر الإكمال: $msg") }
            if (j != null) notifyFinished(j, false)
        } finally {
            LiveBus.job.value = null
        }
    }

    private fun buildPrompt(s: AppSettings, tail: String): ByteArray? {
        val parts = ArrayList<String>()
        if (s.prompt.isNotBlank()) parts.add(s.prompt.trim())
        if (s.useContext && tail.isNotBlank()) parts.add(tail.trim())
        if (parts.isEmpty()) return null
        return parts.joinToString(" ").takeLast(400).toByteArray(Charsets.UTF_8)
    }

    private fun ensureModel(path: String) {
        if (ctxPtr != 0L && ctxModel == path) return
        freeModel()
        val p = WhisperLib.initContext(path)
        if (p == 0L) throw IOException("تعذّر تحميل المودل — قد يكون الملف تالفًا أو الذاكرة غير كافية")
        ctxPtr = p
        ctxModel = path
    }

    private fun freeModel() {
        if (ctxPtr != 0L) {
            WhisperLib.freeContext(ctxPtr)
            ctxPtr = 0L
            ctxModel = ""
        }
    }

    /** Pauses between chunks while the phone is dangerously hot. */
    private fun waitForCooling(jobId: String, fraction: Float) {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        while (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            if (stopRequested) throw StopRequested()
            publish(jobId, "الجهاز ساخن — استراحة للتبريد", fraction, -1)
            Thread.sleep(10_000)
        }
    }

    private fun publish(jobId: String, phase: String, fraction: Float, etaSec: Long) {
        LiveBus.job.value = LiveJob(jobId, phase, fraction, etaSec)
        val pct = (fraction * 100).toInt().coerceIn(0, 100)
        val eta = if (etaSec > 0) " — متبقي نحو ${formatEta(etaSec)}" else ""
        notifyProgress("$phase $pct%$eta", pct)
    }
}

fun formatEta(sec: Long): String {
    val m = (sec + 59) / 60
    return if (m >= 60) {
        val h = m / 60
        val r = m % 60
        if (r == 0L) "$h ساعة" else "$h ساعة و$r دقيقة"
    } else {
        "$m دقيقة"
    }
}
