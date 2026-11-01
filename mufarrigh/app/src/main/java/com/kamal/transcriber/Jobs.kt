package com.kamal.transcriber

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class JobStatus { COPYING, QUEUED, DECODING, TRANSCRIBING, DONE, STOPPED }

data class JobInfo(
    val id: String,
    val name: String,
    val createdAt: Long,
    val status: JobStatus,
    val durationMs: Long = 0L,
    val totalSamples: Long = 0L,
    val pcmReady: Boolean = false,
    val nextSample: Long = 0L,
    val message: String = ""
) {
    val isRunning: Boolean
        get() = status == JobStatus.COPYING || status == JobStatus.QUEUED ||
            status == JobStatus.DECODING || status == JobStatus.TRANSCRIBING
}

/** One transcribed piece of text with its position in the recording. */
data class Seg(val startMs: Long, val endMs: Long, val text: String)

data class LiveJob(val jobId: String, val phase: String, val fraction: Float, val etaSec: Long)

data class LiveDownload(
    val file: String,
    val done: Long,
    val total: Long,
    val error: String? = null,
    val finished: Boolean = false
)

/** Live progress published by the service for the UI. */
object LiveBus {
    val job = MutableStateFlow<LiveJob?>(null)
    val download = MutableStateFlow<LiveDownload?>(null)
}

/**
 * File-based storage. Every job lives in filesDir/jobs/<id>/ :
 *   meta.json        job state
 *   source.bin       the copied input file (deleted once decoded)
 *   audio.pcm        16 kHz mono 16-bit PCM (deleted when the job is done)
 *   seg_<start>.json transcript of one chunk (keyed by its first sample, so a re-run overwrites)
 */
object JobStore {
    /** Bumped on every change so observers reload. */
    val tick = MutableStateFlow(0L)

    private val lock = Any()

    fun root(ctx: Context): File {
        val d = File(ctx.filesDir, "jobs")
        d.mkdirs()
        return d
    }

    fun dir(ctx: Context, id: String): File {
        val d = File(root(ctx), id)
        d.mkdirs()
        return d
    }

    private fun toJson(j: JobInfo): JSONObject = JSONObject().apply {
        put("id", j.id)
        put("name", j.name)
        put("createdAt", j.createdAt)
        put("status", j.status.name)
        put("durationMs", j.durationMs)
        put("totalSamples", j.totalSamples)
        put("pcmReady", j.pcmReady)
        put("nextSample", j.nextSample)
        put("message", j.message)
    }

    private fun fromJson(o: JSONObject): JobInfo = JobInfo(
        id = o.getString("id"),
        name = o.optString("name", ""),
        createdAt = o.optLong("createdAt", 0L),
        status = try {
            JobStatus.valueOf(o.optString("status", "STOPPED"))
        } catch (e: IllegalArgumentException) {
            JobStatus.STOPPED
        },
        durationMs = o.optLong("durationMs", 0L),
        totalSamples = o.optLong("totalSamples", 0L),
        pcmReady = o.optBoolean("pcmReady", false),
        nextSample = o.optLong("nextSample", 0L),
        message = o.optString("message", "")
    )

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun load(ctx: Context, id: String): JobInfo? = synchronized(lock) {
        val f = File(File(root(ctx), id), "meta.json")
        if (!f.isFile) return null
        try {
            fromJson(JSONObject(f.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: Context, job: JobInfo) {
        synchronized(lock) {
            writeAtomic(File(dir(ctx, job.id), "meta.json"), toJson(job).toString())
        }
        tick.value = tick.value + 1
    }

    /** Reads the latest state from disk, applies [change] and saves it. */
    fun update(ctx: Context, id: String, change: (JobInfo) -> JobInfo): JobInfo? {
        var result: JobInfo? = null
        synchronized(lock) {
            val f = File(File(root(ctx), id), "meta.json")
            if (f.isFile) {
                val cur = try {
                    fromJson(JSONObject(f.readText(Charsets.UTF_8)))
                } catch (e: Exception) {
                    null
                }
                if (cur != null) {
                    val next = change(cur)
                    writeAtomic(f, toJson(next).toString())
                    result = next
                }
            }
        }
        if (result != null) tick.value = tick.value + 1
        return result
    }

    fun list(ctx: Context): List<JobInfo> {
        val dirs = root(ctx).listFiles() ?: return emptyList()
        val out = ArrayList<JobInfo>()
        for (d in dirs) {
            if (!d.isDirectory) continue
            val j = load(ctx, d.name)
            if (j != null) out.add(j)
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun delete(ctx: Context, id: String) {
        synchronized(lock) {
            File(root(ctx), id).deleteRecursively()
        }
        tick.value = tick.value + 1
    }

    // ---- transcript chunks ----

    fun saveChunk(ctx: Context, jobId: String, startSample: Long, segs: List<Seg>) {
        val arr = JSONArray()
        for (s in segs) {
            arr.put(JSONObject().apply {
                put("s", s.startMs)
                put("e", s.endMs)
                put("t", s.text)
            })
        }
        synchronized(lock) {
            writeAtomic(File(dir(ctx, jobId), "seg_$startSample.json"), arr.toString())
        }
    }

    fun readSegments(ctx: Context, jobId: String): List<Seg> {
        val files = File(root(ctx), jobId).listFiles() ?: return emptyList()
        val chunks = files
            .filter { it.name.startsWith("seg_") && it.name.endsWith(".json") }
            .sortedBy { it.name.removePrefix("seg_").removeSuffix(".json").toLongOrNull() ?: 0L }
        val out = ArrayList<Seg>()
        for (f in chunks) {
            try {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(Seg(o.getLong("s"), o.getLong("e"), o.getString("t")))
                }
            } catch (e: Exception) {
                // skip a damaged chunk file
            }
        }
        return out
    }
}

/** Turns segments into the texts the user copies, shares or saves. */
object TextFmt {
    fun clock(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun srtClock(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val milli = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    /** Groups segments into readable paragraphs (new paragraph after a pause or a long run). */
    fun paragraphs(segs: List<Seg>): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var lastEnd = -1L
        for (s in segs) {
            val gap = if (lastEnd < 0) 0L else s.startMs - lastEnd
            if (sb.isNotEmpty() && (gap >= 2500 || sb.length > 700)) {
                out.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(s.text)
            lastEnd = s.endMs
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    fun plain(segs: List<Seg>): String = paragraphs(segs).joinToString("\n\n")

    fun timed(segs: List<Seg>): String =
        segs.joinToString("\n") { "[" + clock(it.startMs) + "] " + it.text }

    fun srt(segs: List<Seg>): String {
        val sb = StringBuilder()
        var n = 1
        for (s in segs) {
            sb.append(n++).append('\n')
            sb.append(srtClock(s.startMs)).append(" --> ").append(srtClock(maxOf(s.endMs, s.startMs + 500))).append('\n')
            sb.append(s.text).append("\n\n")
        }
        return sb.toString()
    }

    /** Whisper sometimes loops on silence/music; keep at most 2 identical segments in a row. */
    fun collapseRepeats(segs: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        var run = 0
        var last = ""
        for (s in segs) {
            val key = s.text.trim()
            if (key.isEmpty()) continue
            if (key == last) run++ else {
                run = 1
                last = key
            }
            if (run <= 2) out.add(s)
        }
        return out
    }
}
