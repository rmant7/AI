// JNI bridge to whisper.cpp.
//
// Mirrors :app's llama_jni.cpp for the same reasons: load a ggml model, run a
// transcription pass over a caller-supplied PCM buffer, free it. whisper.cpp
// owns feature extraction (log-mel spectrogram), tokenization and the decode
// loop itself — there is no Kotlin-side equivalent to keep in sync with the
// reference implementation.
//
// The abort/segment-callback plumbing below is ported from the proven
// implementation in rmant7/claude-code's claude/android-whisper-transcription
// branch (WhisperTranscriber/app/src/main/cpp/whisper_jni.cpp), adapted to
// this bridge's Session/handle shape and extended with segment timestamps —
// see docs/13-asr-pipeline-migration.md for the mapping.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <string>

#include "whisper.h"

#define LOG_TAG "whisper_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// The jlong handle Kotlin holds actually points at this, not a bare
// whisper_context*, so [nativeCancel] can flip cancelRequested from the
// calling thread while nativeTranscribe's whisper_full() is still running on
// a worker thread — whisper.cpp polls it cheaply (no JNI upcall) via
// abort_callback below.
struct Session {
    whisper_context *ctx = nullptr;
    std::atomic<bool> cancelRequested{false};
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

/**
 * listener/onSegmentMethod are resolved once, outside the callback, because
 * whisper_full_parallel() (not currently called here, but new_segment_callback
 * has the same signature either way) can invoke callbacks from worker threads
 * it spawns internally — a JNIEnv captured on the calling thread is only ever
 * valid on that thread, so each invocation resolves its own via the cached
 * JavaVM instead.
 */
struct SegmentCallbackContext {
    JavaVM *vm = nullptr;
    jobject listener = nullptr;
    jmethodID onSegmentMethod = nullptr;
};

JNIEnv *resolveEnvForCurrentThread(JavaVM *vm) {
    if (vm == nullptr) return nullptr;
    JNIEnv *env = nullptr;
    jint status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
    } else if (status != JNI_OK) {
        return nullptr;
    }
    return env;
}

/** whisper.cpp's own new_segment_callback signature: told how many segments were just finalized. */
void onWhisperNewSegment(whisper_context *ctx, whisper_state *state, int n_new, void *userData) {
    auto *context = static_cast<SegmentCallbackContext *>(userData);
    if (context == nullptr || context->listener == nullptr || n_new <= 0) return;
    JNIEnv *env = resolveEnvForCurrentThread(context->vm);
    if (env == nullptr) return;

    // state is only non-null under whisper_full_parallel(), which this bridge
    // does not call (see nativeTranscribe) — the plain whisper_full() path
    // always reports through ctx. Handled defensively anyway, matching the
    // reference implementation, so calling this from a parallel path later
    // needs no change here.
    const int totalSegments = state != nullptr
        ? whisper_full_n_segments_from_state(state)
        : whisper_full_n_segments(ctx);

    for (int i = totalSegments - n_new; i < totalSegments; ++i) {
        const char *text = state != nullptr
            ? whisper_full_get_segment_text_from_state(state, i)
            : whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;

        const int64_t t0 = state != nullptr
            ? whisper_full_get_segment_t0_from_state(state, i)
            : whisper_full_get_segment_t0(ctx, i);
        const int64_t t1 = state != nullptr
            ? whisper_full_get_segment_t1_from_state(state, i)
            : whisper_full_get_segment_t1(ctx, i);

        jstring jtext = env->NewStringUTF(trim(text).c_str());
        // whisper.cpp reports t0/t1 in 10ms units; the Kotlin side (and every
        // TranscriptSegment consumer) works in milliseconds.
        env->CallVoidMethod(context->listener, context->onSegmentMethod, jtext,
                             static_cast<jlong>(t0 * 10), static_cast<jlong>(t1 * 10));
        env->DeleteLocalRef(jtext);
    }
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
    // Fused attention kernel, exact (not approximate) result: less memory
    // traffic per decode step, which is the binding constraint on a phone
    // CPU. Whisper.cpp ignores this if a given build/model combination does
    // not support it, so this never turns into a hard requirement.
    params.flash_attn = true;

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

JNIEXPORT void JNICALL
Java_ai_localstudio_whisper_WhisperBridge_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    session->cancelRequested.store(true);
}

/**
 * [samples] is mono 16kHz PCM already converted to float32 in [-1, 1] — the
 * format whisper.cpp's own examples all feed it, so nothing in this bridge
 * needs to know the original recording was 16-bit integer PCM at all.
 *
 * [language] is an ISO-639-1 code ("ru", "en", ...) or "auto". Passing the
 * actual language whenever it's known beats "auto" on more than just
 * pedantic grounds: "auto" makes whisper.cpp re-run language ID from
 * scratch on whatever audio this one call was given, no memory of any
 * earlier call — and a live/partial caller calls this repeatedly on a
 * still-growing, still-short buffer, which is exactly the regime language
 * ID is least reliable in.
 */
JNIEXPORT jstring JNICALL
Java_ai_localstudio_whisper_WhisperBridge_nativeTranscribe(
    JNIEnv *env, jobject, jlong handle, jfloatArray samples, jint threads, jstring language, jobject sink) {

    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->ctx == nullptr) return env->NewStringUTF("");

    // A handle is reused across many calls (one loaded model, many chunks or
    // many files — see WhisperCppSpeechModel); a Stop request belonging to a
    // previous call must never carry over and instantly abort the next one.
    session->cancelRequested.store(false);

    const jsize sampleCount = env->GetArrayLength(samples);
    jfloat *sampleData = env->GetFloatArrayElements(samples, nullptr);
    const std::string lang = toStdString(env, language);

    jobject sinkGlobal = nullptr;
    SegmentCallbackContext segmentContext{};

    int result = -1;
  try {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = lang.empty() ? "auto" : lang.c_str();
    params.translate = false;
    params.n_threads = threads;
    // Each call here is an independent unit of audio (one whisper-window
    // chunk of a file, or one restarted live-preview snapshot) — never a
    // continuation of a previous call's decode state.
    params.no_context = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    // Left at whisper.cpp's own default rather than disabled: the retries
    // this triggers on a window that fails its own quality heuristics are
    // what keeps greedy decoding out of repetition loops. Cheap when audio
    // is clean (it never fires), expensive-but-correct when it isn't.

    params.abort_callback = [](void *userData) {
        return static_cast<std::atomic<bool> *>(userData)->load();
    };
    params.abort_callback_user_data = &session->cancelRequested;

    if (sink != nullptr) {
        sinkGlobal = env->NewGlobalRef(sink);
        jclass sinkClass = env->GetObjectClass(sink);
        JavaVM *vm = nullptr;
        env->GetJavaVM(&vm);
        segmentContext.vm = vm;
        segmentContext.listener = sinkGlobal;
        segmentContext.onSegmentMethod = env->GetMethodID(sinkClass, "onSegment", "(Ljava/lang/String;JJ)V");
        params.new_segment_callback = onWhisperNewSegment;
        params.new_segment_callback_user_data = &segmentContext;
    }

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
    if (sinkGlobal != nullptr) env->DeleteGlobalRef(sinkGlobal);
    env->ReleaseFloatArrayElements(samples, sampleData, JNI_ABORT); // read-only access, nothing to write back
    if (result != 0) {
        // whisper_full also returns non-zero when abort_callback returned
        // true (cancellation), not only on genuine failure — either way there
        // is nothing complete to return, and the caller (WhisperCppSpeechModel)
        // already knows a cancel was requested.
        if (!session->cancelRequested.load()) LOGE("whisper_full failed with code %d", result);
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
