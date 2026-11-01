package com.kamal.transcriber

/** Called from native code (same thread that called [WhisperLib.transcribe]). */
interface WhisperProgress {
    fun onProgress(percent: Int)
}

/** Thin JNI wrapper around whisper.cpp. Native side: app/src/main/cpp/whisper_jni.cpp */
object WhisperLib {
    init {
        System.loadLibrary("mufarrigh_whisper")
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ctx: Long)
    external fun setCancel(cancel: Boolean)

    /** Returns 0 on success; non-zero on failure or when cancelled via [setCancel]. */
    external fun transcribe(
        ctx: Long,
        samples: FloatArray,
        language: String,
        promptUtf8: ByteArray?,
        threads: Int,
        listener: WhisperProgress?
    ): Int

    external fun segmentCount(ctx: Long): Int
    external fun segmentTextBytes(ctx: Long, index: Int): ByteArray

    /** Times are in units of 10 ms. */
    external fun segmentStart(ctx: Long, index: Int): Long
    external fun segmentEnd(ctx: Long, index: Int): Long
    external fun systemInfo(): String
}
