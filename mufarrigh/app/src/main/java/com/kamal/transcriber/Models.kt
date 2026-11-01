package com.kamal.transcriber

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class CatalogModel(
    val file: String,
    val title: String,
    val note: String,
    val sizeMb: Int
) {
    val url: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"
}

object Models {
    val catalog: List<CatalogModel> = listOf(
        CatalogModel(
            "ggml-large-v3-turbo-q5_0.bin",
            "Large-v3 Turbo (مضغوط q5)",
            "الأدق للعربية بحجم صغير — موصى به",
            574
        ),
        CatalogModel(
            "ggml-large-v3-turbo-q8_0.bin",
            "Large-v3 Turbo (q8)",
            "جودة أعلى قليلًا، أكبر حجمًا وأبطأ",
            874
        ),
        CatalogModel(
            "ggml-small-q5_1.bin",
            "Small (مضغوط q5)",
            "أسرع بكثير، لكن دقته في العربية أقل",
            190
        )
    )

    fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "models")
        d.mkdirs()
        return d
    }

    fun installed(ctx: Context): List<File> {
        val all = dir(ctx).listFiles() ?: return emptyList()
        return all.filter { it.isFile && it.name.endsWith(".bin") }.sortedBy { it.name }
    }

    fun file(ctx: Context, name: String): File? {
        if (name.isBlank()) return null
        val f = File(dir(ctx), name)
        return if (f.isFile) f else null
    }

    fun title(name: String): String {
        val c = catalog.firstOrNull { it.file == name }
        return c?.title ?: name
    }

    /** A whisper.cpp ggml file starts with the magic 0x67676d6c ("lmgg" on disk, little-endian). */
    fun isValid(f: File): Boolean {
        if (!f.isFile || f.length() < 10L * 1024 * 1024) return false
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val b = ByteArray(4)
                raf.readFully(b)
                b[0] == 0x6c.toByte() && b[1] == 0x6d.toByte() && b[2] == 0x67.toByte() && b[3] == 0x67.toByte()
            }
        } catch (e: IOException) {
            false
        }
    }

    /** Keeps only characters that are safe in a file name. */
    fun safeName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringBefore('?').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.endsWith(".bin")) cleaned else "$cleaned.bin"
    }

    private fun connect(url: String, from: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mufarrigh/1.0")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    /**
     * Downloads [url] into the models folder as [fileName]. Resumes a previous partial download
     * when the server supports it. Throws on failure or when [cancelled] returns true
     * (the partial file is kept so the download can be resumed).
     */
    fun download(
        ctx: Context,
        url: String,
        fileName: String,
        onProgress: (done: Long, total: Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        val dest = File(dir(ctx), fileName)
        val part = File(dir(ctx), "$fileName.part")
        var existing = if (part.exists()) part.length() else 0L

        var conn = connect(url, existing)
        var code = conn.responseCode
        if (code == 416) {
            // Stale or complete partial file: start over.
            conn.disconnect()
            part.delete()
            existing = 0L
            conn = connect(url, 0L)
            code = conn.responseCode
        }
        if (code != 200 && code != 206) {
            conn.disconnect()
            throw IOException("فشل الاتصال بالخادم (رمز $code)")
        }
        val append = code == 206 && existing > 0
        if (!append) existing = 0L
        val len = conn.contentLengthLong
        val total = if (len > 0) existing + len else 0L

        var done = existing
        var lastReport = 0L
        try {
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("أُلغي التنزيل")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 400) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        onProgress(done, if (total > 0) total else done)

        if (!isValid(part)) {
            part.delete()
            throw IOException("الملف الذي نزل ليس مودل whisper صالحًا")
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("تعذّر حفظ ملف المودل")
    }

    /** Copies a model picked from the device storage into the models folder. */
    fun importFrom(ctx: Context, input: InputStream, fileName: String) {
        val dest = File(dir(ctx), fileName)
        val part = File(dir(ctx), "$fileName.part")
        input.use { src ->
            FileOutputStream(part).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        if (!isValid(part)) {
            part.delete()
            throw IOException("هذا الملف ليس مودل whisper بصيغة ggml")
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("تعذّر حفظ ملف المودل")
    }
}
