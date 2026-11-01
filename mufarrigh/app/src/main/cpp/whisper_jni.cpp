// JNI bridge between the Kotlin app and whisper.cpp.
// Kotlin side: com.kamal.transcriber.WhisperLib

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "MufarrighJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_cancel{false};

struct ProgressCtx {
    JNIEnv *env;
    jobject listener;
    jmethodID method;
    int last;
};

void on_progress(struct whisper_context *, struct whisper_state *, int progress, void *user_data) {
    auto *p = static_cast<ProgressCtx *>(user_data);
    if (p == nullptr || p->listener == nullptr || progress == p->last) return;
    p->last = progress;
    p->env->CallVoidMethod(p->listener, p->method, static_cast<jint>(progress));
}

bool on_abort(void *) {
    return g_cancel.load();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kamal_transcriber_WhisperLib_initContext(JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    cparams.flash_attn = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    if (ctx == nullptr) {
        LOGE("failed to load model");
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_kamal_transcriber_WhisperLib_freeContext(JNIEnv *, jobject, jlong ctx_ptr) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctx_ptr);
    if (ctx != nullptr) whisper_free(ctx);
}

JNIEXPORT void JNICALL
Java_com_kamal_transcriber_WhisperLib_setCancel(JNIEnv *, jobject, jboolean cancel) {
    g_cancel.store(cancel == JNI_TRUE);
}

// Returns 0 on success, non-zero on failure or when cancelled.
JNIEXPORT jint JNICALL
Java_com_kamal_transcriber_WhisperLib_transcribe(
        JNIEnv *env, jobject, jlong ctx_ptr, jfloatArray samples, jstring language,
        jbyteArray prompt_utf8, jint threads, jobject listener) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctx_ptr);
    if (ctx == nullptr) return -100;

    const jsize n_samples = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);

    const char *lang_c = env->GetStringUTFChars(language, nullptr);
    std::string lang(lang_c);
    env->ReleaseStringUTFChars(language, lang_c);

    std::string prompt;
    if (prompt_utf8 != nullptr) {
        const jsize plen = env->GetArrayLength(prompt_utf8);
        if (plen > 0) {
            std::vector<char> buf(static_cast<size_t>(plen));
            env->GetByteArrayRegion(prompt_utf8, 0, plen, reinterpret_cast<jbyte *>(buf.data()));
            prompt.assign(buf.begin(), buf.end());
        }
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.token_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.language = lang.c_str();      // "ar", "en", ... or "auto"
    params.detect_language = false;
    params.n_threads = threads;
    params.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();

    ProgressCtx pctx{env, listener, nullptr, -1};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        pctx.method = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
        if (pctx.method != nullptr) {
            params.progress_callback = on_progress;
            params.progress_callback_user_data = &pctx;
        }
    }
    params.abort_callback = on_abort;
    params.abort_callback_user_data = nullptr;

    const int rc = whisper_full(ctx, params, pcm, static_cast<int>(n_samples));

    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_kamal_transcriber_WhisperLib_segmentCount(JNIEnv *, jobject, jlong ctx_ptr) {
    return whisper_full_n_segments(reinterpret_cast<struct whisper_context *>(ctx_ptr));
}

// Raw UTF-8 bytes: a multi-byte character can be split between tokens, and
// NewStringUTF() would choke on that, so decoding is done on the Kotlin side.
JNIEXPORT jbyteArray JNICALL
Java_com_kamal_transcriber_WhisperLib_segmentTextBytes(JNIEnv *env, jobject, jlong ctx_ptr, jint index) {
    const char *text = whisper_full_get_segment_text(reinterpret_cast<struct whisper_context *>(ctx_ptr), index);
    if (text == nullptr) text = "";
    const jsize len = static_cast<jsize>(std::char_traits<char>::length(text));
    jbyteArray out = env->NewByteArray(len);
    env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte *>(text));
    return out;
}

// Times are in units of 10 ms (whisper.cpp convention).
JNIEXPORT jlong JNICALL
Java_com_kamal_transcriber_WhisperLib_segmentStart(JNIEnv *, jobject, jlong ctx_ptr, jint index) {
    return whisper_full_get_segment_t0(reinterpret_cast<struct whisper_context *>(ctx_ptr), index);
}

JNIEXPORT jlong JNICALL
Java_com_kamal_transcriber_WhisperLib_segmentEnd(JNIEnv *, jobject, jlong ctx_ptr, jint index) {
    return whisper_full_get_segment_t1(reinterpret_cast<struct whisper_context *>(ctx_ptr), index);
}

JNIEXPORT jstring JNICALL
Java_com_kamal_transcriber_WhisperLib_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
