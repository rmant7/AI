// JNI bridge to whisper.cpp.
//
// Deliberately small, mirroring :app's llama_jni.cpp: load a ggml model,
// run one transcription pass over a caller-supplied PCM buffer, free it.
// whisper.cpp owns feature extraction (log-mel spectrogram), tokenization,
// and the decode loop itself — there is no Kotlin-side equivalent to keep in
// sync with the reference implementation the way there was with the old
// TFLite path's hand-rolled mel computation.

#include <jni.h>
#include <android/log.h>

#include <string>

#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    whisper_context *ctx = nullptr;
};

std::string toStdString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

/** Trims the whitespace whisper.cpp's segment text is routinely padded with. */
std::string trim(const std::string &s) {
    size_t start = s.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) return "";
    size_t end = s.find_last_not_of(" \t\r\n");
    return s.substr(start, end - start + 1);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_ai_localstudio_whisper_WhisperBridge_nativeLoad(JNIEnv *env, jobject, jstring modelPath) {
  try {
    const std::string path = toStdString(env, modelPath);

    whisper_context_params params = whisper_context_default_params();
    // No GPU offload: same reasoning as llama_jni's nativeLoad — Android GPU
    // backends are per-vendor, and this build has to run everywhere.
    params.use_gpu = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), params);
    if (ctx == nullptr) {
        LOGE("failed to load whisper model from %s", path.c_str());
        return 0;
    }

    auto *session = new Session();
    session->ctx = ctx;
    return reinterpret_cast<jlong>(session);
  } catch (const std::exception &e) {
    // See llama_jni.cpp's nativeLoad for why this matters: an uncaught C++
    // exception (std::bad_alloc, most plausibly, for one of the larger
    // Whisper models under memory pressure) crossing back into JNI aborts
    // the whole process via std::terminate() instead of surfacing as an
    // ordinary "failed to load" Kotlin can show an error for.
    LOGE("nativeLoad: exception: %s", e.what());
    return 0;
  } catch (...) {
    LOGE("nativeLoad: unknown exception");
    return 0;
  }
}

JNIEXPORT void JNICALL
Java_ai_localstudio_whisper_WhisperBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    if (session->ctx != nullptr) whisper_free(session->ctx);
    delete session;
}

/**
 * [samples] is mono 16kHz PCM already converted to float32 in [-1, 1] — the
 * format whisper.cpp's own examples all feed it, so nothing in this bridge
 * needs to know the original recording was 16-bit integer PCM at all.
 *
 * Language left as "auto": this app has no language-selection UI for speech
 * input, and whisper's own language detection runs on the same encoder pass
 * transcription needs anyway, so there is no separate cost to skip.
 */
JNIEXPORT jstring JNICALL
Java_ai_localstudio_whisper_WhisperBridge_nativeTranscribe(
    JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint threads) {

    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->ctx == nullptr) return env->NewStringUTF("");

    const jsize sampleCount = env->GetArrayLength(samples);
    jfloat *sampleData = env->GetFloatArrayElements(samples, nullptr);

    int result = -1;
  try {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = "auto";
    params.translate = false;
    params.n_threads = threads;
    // Each call here is an independent utterance (either the live preview's
    // growing-but-restarted snapshot, or the one final pass) — never a
    // continuation of a previous call's decode state, so nothing should be
    // carried over between them.
    params.no_context = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;

    result = whisper_full(session->ctx, params, sampleData, (int) sampleCount);
  } catch (const std::exception &e) {
    // whisper_full's internal buffers (mel spectrogram, decoder state) are
    // exactly the kind of allocation that can throw std::bad_alloc under
    // memory pressure — letting that cross back into JNI uncaught would
    // abort the whole process instead of just failing this one
    // transcription. See llama_jni.cpp's nativeLoad for the same reasoning.
    LOGE("nativeTranscribe: exception: %s", e.what());
  } catch (...) {
    LOGE("nativeTranscribe: unknown exception");
  }
    env->ReleaseFloatArrayElements(samples, sampleData, JNI_ABORT); // read-only access, nothing to write back
    if (result != 0) {
        LOGE("whisper_full failed with code %d", result);
        return env->NewStringUTF("");
    }

    std::string text;
    const int segments = whisper_full_n_segments(session->ctx);
    for (int i = 0; i < segments; i++) {
        text += whisper_full_get_segment_text(session->ctx, i);
    }
    return env->NewStringUTF(trim(text).c_str());
}

} // extern "C"
