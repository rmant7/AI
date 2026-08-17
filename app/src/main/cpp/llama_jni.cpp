// JNI bridge to llama.cpp.
//
// Deliberately small: load a GGUF, format a chat turn with the model's own
// template, stream tokens back to Kotlin, and stop when asked. Everything else
// — which model, what context, what the answer is used for — lives above this
// layer, where it can be tested without a device.

#include <jni.h>
#include <android/log.h>
#include <sys/resource.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** How many recent tokens the repetition penalty looks back over. */
constexpr int32_t PENALTY_LAST_N = 64;

/**
 * Best-effort: ask the scheduler to favour this thread.
 *
 * ggml's worker threads inherit the nice value of whichever thread creates
 * them, so raising it here before decoding propagates to the pool. A phone
 * will still throttle and the OS may refuse the request outright — hence
 * "best-effort" rather than a guarantee, and hence no failure path: inference
 * at normal priority is slower, not broken.
 */
void raiseThreadPriority() {
    if (setpriority(PRIO_PROCESS, 0, -8) != 0) {
        LOGI("could not raise thread priority; continuing at default");
    }
}

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> cancelled{false};
};

std::string toStdString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string pieceOf(const llama_vocab *vocab, llama_token token) {
    char buffer[256];
    const int32_t written = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, true);
    if (written < 0) {
        std::vector<char> larger(-written);
        const int32_t retried =
            llama_token_to_piece(vocab, token, larger.data(), (int32_t) larger.size(), 0, true);
        return retried > 0 ? std::string(larger.data(), retried) : std::string();
    }
    return std::string(buffer, written);
}

/**
 * Formats the turn with the template baked into the GGUF. Gemma, Qwen and
 * Llama each want a different layout, and feeding a raw prompt to an
 * instruction-tuned model produces confident nonsense — the model answers a
 * question it was never asked to answer in that form.
 */
std::string applyChatTemplate(llama_model *model, const std::string &system, const std::string &user) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr) {
        return system.empty() ? user : system + "\n\n" + user;
    }

    std::vector<llama_chat_message> messages;
    if (!system.empty()) messages.push_back({"system", system.c_str()});
    messages.push_back({"user", user.c_str()});

    std::vector<char> buffer(user.size() + system.size() + 2048);
    int32_t written = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), true, buffer.data(), (int32_t) buffer.size());
    if (written > (int32_t) buffer.size()) {
        buffer.resize(written);
        written = llama_chat_apply_template(
            tmpl, messages.data(), messages.size(), true, buffer.data(), (int32_t) buffer.size());
    }
    if (written <= 0) {
        // Some templates reject a system message; retry with the user turn only.
        if (!system.empty()) return applyChatTemplate(model, "", system + "\n\n" + user);
        return user;
    }
    return std::string(buffer.data(), written);
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_ai_localstudio_app_llama_LlamaBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_ai_localstudio_app_llama_LlamaBridge_nativeLoad(
    JNIEnv *env, jobject, jstring modelPath, jint contextTokens, jint threads) {

    static std::atomic<bool> backendReady{false};
    if (!backendReady.exchange(true)) {
        llama_backend_init();
        llama_log_set([](ggml_log_level level, const char *text, void *) {
            if (level >= GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
        }, nullptr);
    }

    const std::string path = toStdString(env, modelPath);

    llama_model_params modelParams = llama_model_default_params();
    // No GPU offload: Android GPU backends are per-vendor and this build has to
    // run everywhere. CPU with the right ARM flags is what makes it usable.
    modelParams.n_gpu_layers = 0;
    // mmap rather than reading the weights into the heap: a 3 GB model is then
    // paged in on demand and, more importantly, evictable under pressure —
    // which is what keeps Android from killing the app while it loads.
    modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model *model = llama_model_load_from_file(path.c_str(), modelParams);
    if (model == nullptr) {
        LOGE("failed to load model from %s", path.c_str());
        return 0;
    }

    llama_context_params contextParams = llama_context_default_params();
    // Defensive: a zero, negative, or unreasonable value here should not be
    // trusted at this boundary, whatever the Kotlin side currently clamps to
    // — a negative jint cast straight to uint32_t wraps to billions, which
    // llama_init_from_model then tries to allocate and crashes on.
    const int32_t clampedContextTokens = std::min(std::max(contextTokens, 512), 32768);
    contextParams.n_ctx = (uint32_t) clampedContextTokens;
    // Larger batches process the prompt in fewer passes at the cost of memory
    // during that phase — a good trade on a device with RAM to spare.
    contextParams.n_batch = 512;
    contextParams.n_threads = threads;
    contextParams.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, contextParams);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *session = new Session();
    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    LOGI("loaded %s, n_ctx=%u, threads=%d", path.c_str(), llama_n_ctx(ctx), threads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_ai_localstudio_app_llama_LlamaBridge_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return;
    if (session->ctx != nullptr) llama_free(session->ctx);
    if (session->model != nullptr) llama_model_free(session->model);
    delete session;
}

JNIEXPORT void JNICALL
Java_ai_localstudio_app_llama_LlamaBridge_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session != nullptr) session->cancelled.store(true);
}

JNIEXPORT jint JNICALL
Java_ai_localstudio_app_llama_LlamaBridge_nativeGenerate(
    JNIEnv *env, jobject, jlong handle, jstring systemPrompt, jstring userPrompt,
    jint maxTokens, jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty,
    jobject callback) {

    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return -1;
    session->cancelled.store(false);
    raiseThreadPriority();

    // Every call sends the *whole* conversation as prompt text — the Kotlin
    // side rebuilds full context each turn, it does not send an incremental
    // continuation. Without this, llama_batch_get_one's automatic position
    // tracking keeps appending onto the KV cache left over from the previous
    // turn: positions drift out of sync with the token stream being decoded,
    // which produced exactly what a real device showed — coherent-length but
    // wrong-language, degenerating replies from turn two onward, and by the
    // third or fourth turn the accumulated (never-freed) cache exceeded n_ctx
    // and crashed. Clearing before every call makes each generate() the fresh
    // single-shot decode it was written to be.
    llama_memory_clear(llama_get_memory(session->ctx), true);

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    if (onToken == nullptr) return -2;

    const std::string prompt = applyChatTemplate(
        session->model, toStdString(env, systemPrompt), toStdString(env, userPrompt));

    std::vector<llama_token> tokens(prompt.size() + 64);
    int32_t count = llama_tokenize(
        session->vocab, prompt.c_str(), (int32_t) prompt.size(),
        tokens.data(), (int32_t) tokens.size(), true, true);
    if (count < 0) {
        tokens.resize(-count);
        count = llama_tokenize(
            session->vocab, prompt.c_str(), (int32_t) prompt.size(),
            tokens.data(), (int32_t) tokens.size(), true, true);
    }
    if (count <= 0) return -3;
    tokens.resize(count);

    const uint32_t contextSize = llama_n_ctx(session->ctx);
    // The context engine above this layer estimates tokens with a heuristic,
    // not this model's real tokenizer — for Cyrillic text especially, that
    // estimate can undercount enough that an assembled prompt looks like it
    // fits and then does not. Failing outright here turned that estimation
    // gap into "the model doesn't respond, no explanation" for exactly the
    // conversations most likely to be long: many turns, attached documents,
    // recalled memory. Truncating to the most recent tokens — the ones most
    // likely to matter for the answer — keeps the turn alive instead.
    const uint32_t reserved = std::min<uint32_t>((uint32_t) std::max(maxTokens, 0), contextSize / 4);
    if ((uint32_t) count + reserved >= contextSize) {
        const int32_t keep = (int32_t) contextSize - (int32_t) reserved - 1;
        if (keep <= 0) return -4; // context too small to hold any prompt at all
        const int32_t drop = count - keep;
        if (drop > 0) {
            tokens.erase(tokens.begin(), tokens.begin() + drop);
            LOGI("prompt of %d tokens truncated to %d to fit context of %u",
                 count, keep, contextSize);
            count = keep;
        }
    }

    if (llama_decode(session->ctx, llama_batch_get_one(tokens.data(), count)) != 0) {
        return -5;
    }

    // Without a repetition penalty, a small quantized model that starts
    // echoing a phrase has nothing pushing it out of the loop — top-k/top-p
    // still rate the repeated token highly, so it keeps winning. That
    // matches degenerating/repeating output seen on-device far better than
    // any single-turn decoding bug does, and left unchecked it runs the
    // decode loop out to maxTokens instead of stopping, which is what a long
    // hang before "no response" looks like from the Kotlin side.
    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        llama_vocab_n_tokens(session->vocab), PENALTY_LAST_N, repeatPenalty, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int32_t produced = 0;
    uint32_t used = (uint32_t) count;
    while (produced < maxTokens && used + 1 < contextSize) {
        if (session->cancelled.load()) break;

        llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) break;

        const std::string piece = pieceOf(session->vocab, token);
        if (!piece.empty()) {
            jstring value = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(callback, onToken, value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
        }

        if (llama_decode(session->ctx, llama_batch_get_one(&token, 1)) != 0) break;
        produced++;
        used++;
    }

    llama_sampler_free(sampler);
    return produced;
}

} // extern "C"
