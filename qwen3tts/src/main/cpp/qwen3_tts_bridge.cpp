// Minimal JNI bridge over qwen3-tts.cpp's C API (qwen3_tts_c.h) for
// ai.localstudio.qwen3tts.Qwen3TtsNative. One handle = one model context; the
// Kotlin side (QwenTtsRuntimeManager) guarantees there is only ever one and
// that native calls on it never overlap, except cancel().
#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#include "qwen3_tts_c.h"
#include "ggml_matvec_bench.h"

#define TAG "Qwen3TtsBridge"

// Read by the patched pipeline_synthesize.cpp (see CMakeLists.txt): threads for the vocoder decode only.
namespace {
std::atomic<int32_t> g_vocoder_threads_hook{0};
}
namespace qwen3_tts {
int32_t phase_vocoder_threads() { return g_vocoder_threads_hook.load(); }
}


namespace {

// Streaming parameters, set from Kotlin (diagnostics): audio chunk length and vocoder left context.
std::atomic<int32_t> g_chunk_ms{1000};
std::atomic<int32_t> g_left_ms{2000};
// Threads for the streaming vocoder, whose backend is created on the first synthesis after a load
// (0 = same as the talker). The runtime reads the global thread count only when it creates a backend.
std::atomic<int32_t> g_vocoder_threads{0};

struct Handle {
    qwen3_tts_context_t* ctx = nullptr;
    int32_t threads = 4;
    std::atomic<bool> cancel{false};
    std::atomic<int64_t> first_chunk_ms{-1};
    // Live view of the generation in progress, for a progress display (see progress()).
    std::atomic<int64_t> chunk_count{0};
    std::atomic<int64_t> audio_ms{0};
    std::atomic<int64_t> gen_start_ms{0};
    std::atomic<int64_t> gen_end_ms{0};
    std::string error;
    // What the native runtime (and this bridge) reported since the last takeLog().
    std::string log;
    // The same text as it arrives, while a native call is still running (see peekLive()).
    std::mutex live_mu;
    std::string live;
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
    int64_t audio_samples = 0;
    int32_t sample_rate = 24000;
    // (elapsed ms when the chunk arrived, total audio ms delivered so far)
    std::vector<std::pair<int64_t, int64_t>> chunks;
};

// Non-zero keeps generating, zero asks the runtime to stop (see
// pipeline_synthesize.cpp: "Streaming audio callback requested cancellation").
int32_t on_chunk(const qwen3_tts_audio_chunk_t* chunk, void* user) {
    auto* s = static_cast<Stream*>(user);
    if (chunk != nullptr && chunk->n_samples > 0) {
        const int64_t elapsed = now_ms() - s->started_ms;
        if (s->h->first_chunk_ms.load() < 0) s->h->first_chunk_ms.store(elapsed);
        s->audio_samples += chunk->n_samples;
        if (chunk->sample_rate > 0) s->sample_rate = chunk->sample_rate;
        s->chunks.emplace_back(elapsed, s->audio_samples * 1000 / s->sample_rate);
        s->h->chunk_count.store(static_cast<int64_t>(s->chunks.size()));
        s->h->audio_ms.store(s->audio_samples * 1000 / s->sample_rate);
    }
    return s->h->cancel.load() ? 0 : 1;
}

// qwen3-tts.cpp reports its own per-phase timings (tokenization, speaker
// encode, code generation, vocoder, memory ...) with fprintf(stderr), which on
// Android goes nowhere. While one of its calls runs, fd 2 is redirected into a
// pipe, so the text can be handed to Kotlin (and the app log) instead.
// Only one capture at a time: fd 2 is process-wide, so with two contexts generating at once the second
// one simply runs without a capture (its report is empty) instead of tangling the redirection.
std::atomic<bool> g_capture_busy{false};

class StderrCapture {
public:
    explicit StderrCapture(Handle* h) : h_(h) {
        std::lock_guard<std::mutex> g(h_->live_mu);
        h_->live.clear();
    }

    void start() {
        if (g_capture_busy.exchange(true)) return;
        if (pipe(fds_) != 0) {
            g_capture_busy.store(false);
            return;
        }
        saved_ = dup(2);
        if (saved_ < 0) {
            close(fds_[0]);
            close(fds_[1]);
            g_capture_busy.store(false);
            return;
        }
        fflush(stderr);
        dup2(fds_[1], 2);
        reader_ = std::thread([this] {
            char buf[4096];
            ssize_t n;
            while ((n = read(fds_[0], buf, sizeof(buf))) > 0) {
                text_.append(buf, static_cast<size_t>(n));
                std::lock_guard<std::mutex> g(h_->live_mu);
                h_->live.append(buf, static_cast<size_t>(n));
            }
        });
        active_ = true;
    }
    StderrCapture(const StderrCapture&) = delete;
    StderrCapture& operator=(const StderrCapture&) = delete;
    ~StderrCapture() { finish(); }

    std::string finish() {
        if (!active_) return std::move(text_);
        fflush(stderr);
        dup2(saved_, 2);
        close(saved_);
        close(fds_[1]);  // last write end: the reader now sees EOF
        reader_.join();
        close(fds_[0]);
        active_ = false;
        g_capture_busy.store(false);
        return std::move(text_);
    }

private:
    Handle* h_;
    int fds_[2] = {-1, -1};
    int saved_ = -1;
    bool active_ = false;
    std::thread reader_;
    std::string text_;
};

// Also into logcat, one line at a time, so `adb logcat -s Qwen3TtsNative` shows it.
void mirror_to_logcat(const std::string& text) {
    size_t start = 0;
    while (start < text.size()) {
        size_t end = text.find('\n', start);
        if (end == std::string::npos) end = text.size();
        if (end > start) {
            __android_log_print(ANDROID_LOG_INFO, "Qwen3TtsNative", "%.*s", static_cast<int>(end - start), text.c_str() + start);
        }
        start = end + 1;
    }
}

void keep_log(Handle* h, std::string text) {
    mirror_to_logcat(text);
    h->log += text;
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
    StderrCapture capture(h);
    capture.start();
    const int32_t ok = qwen3_tts_load_models_with_name(h->ctx, dir.c_str(), talker.c_str());
    keep_log(h, capture.finish());
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
    StderrCapture capture(h);
    capture.start();
    const int32_t ok = qwen3_tts_extract_icl_prompt(h->ctx, wav.c_str(), text.c_str(), out.c_str());
    keep_log(h, capture.finish());
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
    h->chunk_count.store(0);
    h->audio_ms.store(0);
    h->gen_end_ms.store(0);
    h->gen_start_ms.store(now_ms());
    h->error.clear();

    qwen3_tts_streaming_params_t params{};
    params.generation = {max_audio_tokens, 0.9f, 1.0f, 50, h->threads, 0, 1, 1.05f, language_id, nullptr, nullptr, 2.0f};
    params.chunk_sec = static_cast<float>(g_chunk_ms.load()) / 1000.0f;
    params.left_context_sec = static_cast<float>(g_left_ms.load()) / 1000.0f;
    params.collect_audio = 1;

    Stream stream{h, now_ms()};
    StderrCapture capture(h);
    capture.start();
    qwen3_tts_result_t r = qwen3_tts_synthesize_with_icl_prompt_streaming(
        h->ctx, utterance.c_str(), prompt.c_str(), params, on_chunk, &stream);
    const int64_t native_ms = now_ms() - stream.started_ms;
    h->gen_end_ms.store(now_ms());
    std::string runtime_log = capture.finish();

    jint status = kOk;
    int64_t wav_ms = 0;
    if (!r.success) {
        status = h->cancel.load() ? kCancelled : kError;
        if (status == kError) h->error = r.error_msg ? r.error_msg : context_error(h);
    } else if (r.audio == nullptr || r.audio_len <= 0) {
        status = kError;
        h->error = "synthesis produced no audio";
    } else {
        const int64_t wav_start = now_ms();
        if (!write_wav16(out_path, r.audio, r.audio_len, r.sample_rate)) {
            status = kError;
            h->error = "could not write " + out_path;
        }
        wav_ms = now_ms() - wav_start;
    }
    qwen3_tts_free_result(r);

    // Bridge-side facts the runtime's own report cannot know: the wall time of
    // the whole native call, WAV writing, and when each audio chunk arrived —
    // chunks arriving at a steady pace mean generation is linear, chunks
    // getting further apart mean it slows down as the sequence grows.
    char line[160];
    std::string extra = "\nBridge:\n";
    snprintf(line, sizeof(line), "  Threads:         %d\n  Native call:     %lld ms\n  WAV writing:     %lld ms\n",
             static_cast<int>(h->threads), static_cast<long long>(native_ms), static_cast<long long>(wav_ms));
    extra += line;
    snprintf(line, sizeof(line), "  Audio chunks:    %d (chunk %.1f s, vocoder left context %.1f s)\n",
             static_cast<int>(stream.chunks.size()), g_chunk_ms.load() / 1000.0, g_left_ms.load() / 1000.0);
    extra += line;
    const size_t shown = stream.chunks.size() < 40 ? stream.chunks.size() : 40;
    for (size_t i = 0; i < shown; ++i) {
        snprintf(line, sizeof(line), "    chunk %zu: arrived at %lld ms, audio so far %lld ms\n", i + 1,
                 static_cast<long long>(stream.chunks[i].first), static_cast<long long>(stream.chunks[i].second));
        extra += line;
    }
    if (stream.chunks.size() > shown) extra += "    ...\n";
    keep_log(h, runtime_log + extra);
    if (status == kError) __android_log_print(ANDROID_LOG_ERROR, TAG, "synthesize failed: %s", h->error.c_str());
    return status;
}

// The only call allowed to overlap another native call on the same handle.
JNIEXPORT void JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_setStreaming(JNIEnv*, jobject, jint chunkMs, jint leftMs) {
    g_chunk_ms.store(chunkMs > 0 ? chunkMs : 1000);
    g_left_ms.store(leftMs >= 0 ? leftMs : 2000);
}

JNIEXPORT void JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_setVocoderThreads(JNIEnv*, jobject, jint threads) {
    g_vocoder_threads.store(threads > 0 ? threads : 0);
    g_vocoder_threads_hook.store(threads > 0 ? threads : 0);
}

// Not tied to a model handle: a self-contained ggml micro-benchmark. Flipped by cancelBenchmark().
std::atomic<bool> g_bench_cancel{false};

JNIEXPORT jstring JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_benchmarkMatvec(JNIEnv* env, jobject) {
    g_bench_cancel.store(false);
    const std::string report = run_matvec_benchmark([]() { return g_bench_cancel.load(); });
    return env->NewStringUTF(report.c_str());
}

JNIEXPORT void JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_cancelBenchmark(JNIEnv*, jobject) {
    g_bench_cancel.store(true);
}

JNIEXPORT void JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_cancel(JNIEnv*, jobject, jlong handle) {
    Handle* h = handle_of(handle);
    if (h != nullptr) h->cancel.store(true);
}

JNIEXPORT jlong JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_firstChunkMs(JNIEnv*, jobject, jlong handle) {
    Handle* h = handle_of(handle);
    return h == nullptr ? -1 : static_cast<jlong>(h->first_chunk_ms.load());
}

// [chunks delivered, audio ms delivered, ms since generation started (frozen when it ended)]
// of the current or last generation — safe to call while synthesize() runs.
JNIEXPORT jlongArray JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_progress(JNIEnv* env, jobject, jlong handle) {
    jlong values[3] = {0, 0, 0};
    Handle* h = handle_of(handle);
    if (h != nullptr && h->gen_start_ms.load() > 0) {
        const int64_t end = h->gen_end_ms.load();
        values[0] = h->chunk_count.load();
        values[1] = h->audio_ms.load();
        values[2] = (end > 0 ? end : now_ms()) - h->gen_start_ms.load();
    }
    jlongArray out = env->NewLongArray(3);
    env->SetLongArrayRegion(out, 0, 3, values);
    return out;
}

// What the running (or last) native call has printed so far — safe to call while it runs.
JNIEXPORT jstring JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_peekLive(JNIEnv* env, jobject, jlong handle) {
    Handle* h = handle_of(handle);
    if (h == nullptr) return env->NewStringUTF("");
    std::string copy;
    {
        std::lock_guard<std::mutex> g(h->live_mu);
        copy = h->live;
    }
    return env->NewStringUTF(copy.c_str());
}

// Everything logged since the previous call; clears it.
JNIEXPORT jstring JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_takeLog(JNIEnv* env, jobject, jlong handle) {
    Handle* h = handle_of(handle);
    if (h == nullptr) return env->NewStringUTF("");
    std::string out;
    out.swap(h->log);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL Java_ai_localstudio_qwen3tts_Qwen3TtsNative_lastError(JNIEnv* env, jobject, jlong handle) {
    Handle* h = handle_of(handle);
    return env->NewStringUTF(h == nullptr ? "" : h->error.c_str());
}

}  // extern "C"
