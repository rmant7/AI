// Minimal JNI bridge over qwen3-tts.cpp's C API (qwen3_tts_c.h) for
// ai.localstudio.qwen3tts.Qwen3TtsNative. One handle = one model context; the
// Kotlin side (QwenTtsRuntimeManager) guarantees there is only ever one and
// that native calls on it never overlap, except cancel().
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "qwen3_tts_c.h"

#define TAG "Qwen3TtsBridge"

namespace {

struct Handle {
    qwen3_tts_context_t* ctx = nullptr;
    int32_t threads = 4;
    std::atomic<bool> cancel{false};
    std::atomic<int64_t> first_chunk_ms{-1};
    std::string error;
};

constexpr jint kOk = 0;
constexpr jint kCancelled = 1;
constexpr jint kError = 2;

Handle* handle_of(jlong h) { return reinterpret_cast<Handle*>(h); }

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::string to_std(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

struct Stream {
    Handle* h;
    int64_t started_ms;
};

// Non-zero keeps generating, zero asks the runtime to stop (see
// pipeline_synthesize.cpp: "Streaming audio callback requested cancellation").
int32_t on_chunk(const qwen3_tts_audio_chunk_t* chunk, void* user) {
    auto* s = static_cast<Stream*>(user);
    if (chunk != nullptr && chunk->n_samples > 0 && s->h->first_chunk_ms.load() < 0) {
        s->h->first_chunk_ms.store(now_ms() - s->started_ms);
    }
    return s->h->cancel.load() ? 0 : 1;
}

void put_u16(std::vector<uint8_t>& v, uint16_t x) { v.push_back(x & 0xFF); v.push_back(x >> 8); }
void put_u32(std::vector<uint8_t>& v, uint32_t x) { put_u16(v, x & 0xFFFF); put_u16(v, x >> 16); }

// mono 16-bit PCM WAV, little-endian (every Android ABI is).
bool write_wav16(const std::string& path, const float* samples, int32_t n, int32_t rate) {
    std::vector<uint8_t> out;
    out.reserve(44 + static_cast<size_t>(n) * 2);
    const uint32_t data_bytes = static_cast<uint32_t>(n) * 2;
    out.insert(out.end(), {'R', 'I', 'F', 'F'});
    put_u32(out, 36 + data_bytes);
    out.insert(out.end(), {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
    put_u32(out, 16);
    put_u16(out, 1);
    put_u16(out, 1);
    put_u32(out, static_cast<uint32_t>(rate));
    put_u32(out, static_cast<uint32_t>(rate) * 2);
    put_u16(out, 2);
    put_u16(out, 16);
    out.insert(out.end(), {'d', 'a', 't', 'a'});
    put_u32(out, data_bytes);
    for (int32_t i = 0; i < n; ++i) {
        float v = samples[i];
        if (!(v == v)) v = 0.0f;  // NaN
        if (v > 1.0f) v = 1.0f;
        if (v < -1.0f) v = -1.0f;
        put_u16(out, static_cast<uint16_t>(static_cast<int16_t>(std::lrintf(v * 32767.0f))));
    }
    FILE* f = fopen(path.c_str(), "wb");
    if (f == nullptr) return false;
    const bool ok = fwrite(out.data(), 1, out.size(), f) == out.size();
    fclose(f);
    return ok;
}

std::string context_error(Handle* h) {
    char* e = qwen3_tts_get_last_error(h->ctx);
    std::string s = e ? e : "";
    if (e) qwen3_tts_free_string(e);
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_create(JNIEnv*, jobject, jint threads) {
    auto* h = new Handle();
    h->threads = threads > 0 ? threads : 4;
    h->ctx = qwen3_tts_init();
    if (h->ctx == nullptr) {
        delete h;
        return 0;
    }
    qwen3_tts_set_backend_preference(QWEN3_TTS_BACKEND_CPU);
    qwen3_tts_set_cpu_threads(h->threads);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_destroy(JNIEnv*, jobject, jlong handle) {
    Handle* h = handle_of(handle);
    if (h == nullptr) return;
    if (h->ctx != nullptr) qwen3_tts_free(h->ctx);
    delete h;
}

JNIEXPORT jboolean JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_loadModels(
    JNIEnv* env, jobject, jlong handle, jstring model_dir, jstring talker_file) {
    Handle* h = handle_of(handle);
    if (h == nullptr) return JNI_FALSE;
    const std::string dir = to_std(env, model_dir);
    const std::string talker = to_std(env, talker_file);
    const int32_t ok = qwen3_tts_load_models_with_name(h->ctx, dir.c_str(), talker.c_str());
    if (!ok) {
        h->error = context_error(h);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "load failed: %s", h->error.c_str());
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Speaker embedding + tokenized reference transcript + reference speech codes,
// saved to prompt_path so any number of phrases can reuse it.
JNIEXPORT jboolean JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_prepareVoice(
    JNIEnv* env, jobject, jlong handle, jstring reference_wav, jstring reference_text, jstring prompt_path) {
    Handle* h = handle_of(handle);
    if (h == nullptr) return JNI_FALSE;
    const std::string wav = to_std(env, reference_wav);
    const std::string text = to_std(env, reference_text);
    const std::string out = to_std(env, prompt_path);
    const int32_t ok = qwen3_tts_extract_icl_prompt(h->ctx, wav.c_str(), text.c_str(), out.c_str());
    if (!ok) {
        h->error = context_error(h);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "prepareVoice failed: %s", h->error.c_str());
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Returns kOk, kCancelled or kError; on kOk output_wav is a 24 kHz mono
// 16-bit WAV. Runs the streaming entry point with collect_audio so that
// cancel() can stop generation between chunks.
JNIEXPORT jint JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_synthesize(
    JNIEnv* env, jobject, jlong handle, jstring prompt_path, jstring text, jint language_id,
    jint max_audio_tokens, jstring output_wav) {
    Handle* h = handle_of(handle);
    if (h == nullptr) return kError;
    const std::string prompt = to_std(env, prompt_path);
    const std::string utterance = to_std(env, text);
    const std::string out_path = to_std(env, output_wav);

    h->cancel.store(false);
    h->first_chunk_ms.store(-1);
    h->error.clear();

    qwen3_tts_streaming_params_t params{};
    params.generation = {max_audio_tokens, 0.9f, 1.0f, 50, h->threads, 0, 1, 1.05f, language_id, nullptr, nullptr, 2.0f};
    params.chunk_sec = 1.0f;
    params.left_context_sec = 2.0f;
    params.collect_audio = 1;

    Stream stream{h, now_ms()};
    qwen3_tts_result_t r = qwen3_tts_synthesize_with_icl_prompt_streaming(
        h->ctx, utterance.c_str(), prompt.c_str(), params, on_chunk, &stream);

    jint status = kOk;
    if (!r.success) {
        status = h->cancel.load() ? kCancelled : kError;
        if (status == kError) h->error = r.error_msg ? r.error_msg : context_error(h);
    } else if (r.audio == nullptr || r.audio_len <= 0) {
        status = kError;
        h->error = "synthesis produced no audio";
    } else if (!write_wav16(out_path, r.audio, r.audio_len, r.sample_rate)) {
        status = kError;
        h->error = "could not write " + out_path;
    }
    qwen3_tts_free_result(r);
    if (status == kError) __android_log_print(ANDROID_LOG_ERROR, TAG, "synthesize failed: %s", h->error.c_str());
    return status;
}

// The only call allowed to overlap another native call on the same handle.
JNIEXPORT void JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_cancel(JNIEnv*, jobject, jlong handle) {
    Handle* h = handle_of(handle);
    if (h != nullptr) h->cancel.store(true);
}

JNIEXPORT jlong JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_firstChunkMs(JNIEnv*, jobject, jlong handle) {
    Handle* h = handle_of(handle);
    return h == nullptr ? -1 : static_cast<jlong>(h->first_chunk_ms.load());
}

JNIEXPORT jstring JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_lastError(JNIEnv* env, jobject, jlong handle) {
    Handle* h = handle_of(handle);
    return env->NewStringUTF(h == nullptr ? "" : h->error.c_str());
}

}  // extern "C"
