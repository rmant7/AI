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
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "llama_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** How many recent tokens the repetition penalty looks back over. */
constexpr int32_t PENALTY_LAST_N = 64;

/** Matches contextParams.n_batch in nativeLoad — how much prompt gets decoded per llama_decode call. */
constexpr int32_t BATCH_SIZE = 512;

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
    // Tokens actually resident in the KV cache after the last successful
    // call — the prompt tokens that were decoded plus whatever generated
    // tokens were themselves decoded back in. Compared against the next
    // call's prompt to reuse the shared prefix instead of redecoding it.
    std::vector<llama_token> cachedTokens;

    // Last turn's timings, reported through nativeLastTurnStats. Prompt
    // processing and token generation are separate costs with separate
    // causes, and on-device they have differed by two orders of magnitude
    // within one session — a single "how long did the answer take" number
    // cannot tell a large prompt at a normal rate from a small one at a
    // collapsed rate, nor show whether prefix reuse is doing anything.
    int32_t promptTokens = 0;
    int32_t reusedTokens = 0;
    int32_t decodedTokens = 0;
    int64_t prefillMs = 0;
    int64_t decodeMs = 0;
};

int64_t nowMs() {
    struct timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

std::string toStdString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

int utf8SequenceLength(unsigned char leadByte) {
    if ((leadByte & 0x80) == 0x00) return 1;
    if ((leadByte & 0xE0) == 0xC0) return 2;
    if ((leadByte & 0xF0) == 0xE0) return 3;
    if ((leadByte & 0xF8) == 0xF0) return 4;
    return 1; // not a valid lead byte; treat as complete so we don't buffer forever
}

/**
 * True once the trailing bytes of `s` are not sitting mid-way through a
 * multi-byte UTF-8 codepoint.
 *
 * llama_token_to_piece returns a token's raw bytes, and a BPE token boundary
 * routinely lands inside a multi-byte codepoint for non-Latin scripts —
 * Cyrillic is 2 bytes per character in UTF-8, so a single token's piece can
 * be exactly the first byte of a letter with the continuation byte arriving
 * only in the next token. Handing that half-codepoint straight to
 * NewStringUTF produces invalid modified UTF-8, and ART treats that as a
 * fatal abort rather than a catchable exception — the whole process dies,
 * which is what "typed a Russian prompt and it crashed" looks like from the
 * Kotlin side, since a reply in Cyrillic hits this on nearly every token.
 */
bool endsOnCompleteUtf8(const std::string &s) {
    if (s.empty()) return true;
    int back = 1;
    while (back <= 4 && back <= (int) s.size()) {
        const auto byte = (unsigned char) s[s.size() - back];
        if ((byte & 0xC0) != 0x80) return utf8SequenceLength(byte) == back;
        back++;
    }
    return true; // four continuation bytes with no lead byte: give up buffering, emit as-is
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
 * The last-resort layout, for a GGUF that carries no usable chat template.
 *
 * Deliberately not a plain `system + "\n\n" + user` concatenation, which is
 * what this used to be. With no turn markers of any kind, an
 * instruction-tuned model is simply a text completer over whatever it was
 * handed — and what it was handed is this app's labelled context document.
 * Observed on a real device, twice: asked "Конкретные рецепты" it replied
 * " для одного из этих вариантов..." — finishing the user's own sentence —
 * and then reproduced `[SYSTEM]` and `[CONVERSATION]` sections of its own,
 * inventing an `[ASSISTANT_MESSAGE]` label to write under. It was
 * continuing the document, exactly as a completion model should, because
 * nothing in the text said a turn had ended and its own had begun.
 *
 * The Alpaca-style headers below are heavily represented in instruction
 * tuning data across model families, and the trailing "### Response:" is
 * the part that matters: an explicit, unambiguous "your turn starts here"
 * that a completion model has something to continue *from*.
 */
std::string genericInstructScaffold(const std::string &system, const std::string &user) {
    std::string out;
    if (!system.empty()) out += system + "\n\n";
    out += "### Instruction:\n" + user + "\n\n### Response:\n";
    return out;
}

/** What [applyChatTemplate] actually did last, surfaced to Kotlin so it reaches the in-app log. */
std::string g_lastTemplateInfo = "not attempted yet";

/**
 * Formats the turn with the template baked into the GGUF. Gemma, Qwen and
 * Llama each want a different layout, and feeding a raw prompt to an
 * instruction-tuned model produces confident nonsense — the model answers a
 * question it was never asked to answer in that form.
 */
std::string applyChatTemplate(llama_model *model, const std::string &system, const std::string &user) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr) {
        // Not a warning to shrug at: without the model's own turn markers
        // the answer quality drop is severe and looks like the model being
        // bad rather than the prompt being malformed. Recorded so it shows
        // up in the app's own log next to the load line, not just logcat.
        g_lastTemplateInfo = "MISSING in GGUF — falling back to a generic instruct scaffold";
        LOGE("no chat template in this GGUF; using the generic instruct scaffold");
        return genericInstructScaffold(system, user);
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
        // Some templates reject a system message. Retry the user turn alone
        // — but against the template still, not by giving up on it: the old
        // code recursed into a path that returned the raw string when that
        // second attempt also failed, silently losing the turn markers.
        if (!system.empty()) {
            const std::string merged = system + "\n\n" + user;
            llama_chat_message userOnly[] = {{"user", merged.c_str()}};
            buffer.assign(merged.size() + 2048, '\0');
            written = llama_chat_apply_template(
                tmpl, userOnly, 1, true, buffer.data(), (int32_t) buffer.size());
            if (written > 0) {
                g_lastTemplateInfo = "applied (system folded into the user turn)";
                return std::string(buffer.data(), written);
            }
        }
        g_lastTemplateInfo = "present but FAILED to apply — falling back to a generic instruct scaffold";
        LOGE("chat template present but llama_chat_apply_template failed; using the generic scaffold");
        return genericInstructScaffold(system, user);
    }
    g_lastTemplateInfo = "applied";
    return std::string(buffer.data(), written);
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_ai_localstudio_app_llama_LlamaBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

/**
 * Where the last turn's time actually went.
 *
 * Prompt processing and token generation are separate costs. On a real
 * device a 339-character prompt reached its first token in 4.6 seconds
 * while a 3334-character one produced nothing in 462 — from the outside
 * both are just "slow", and a rate collapse, a large prompt, and prefix
 * reuse quietly not working are indistinguishable without these numbers.
 * `reused` in particular is the direct answer to whether the KV-cache
 * prefix match in nativeGenerate is doing anything across turns.
 */
JNIEXPORT jstring JNICALL
Java_ai_localstudio_app_llama_LlamaBridge_nativeLastTurnStats(JNIEnv *env, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr) return env->NewStringUTF("no session");

    const int32_t prefilled = session->promptTokens - session->reusedTokens;
    char buffer[320];
    snprintf(
        buffer, sizeof(buffer),
        "prompt %d tok (%d reused from the last turn), prefill %d tok in %lldms (%.1f tok/s); "
        "generated %d tok in %lldms (%.1f tok/s)",
        session->promptTokens, session->reusedTokens,
        prefilled, (long long) session->prefillMs,
        session->prefillMs > 0 ? prefilled * 1000.0 / (double) session->prefillMs : 0.0,
        session->decodedTokens, (long long) session->decodeMs,
        session->decodeMs > 0 ? session->decodedTokens * 1000.0 / (double) session->decodeMs : 0.0);
    return env->NewStringUTF(buffer);
}

/**
 * Whether this model's own chat template was found and used for the last
 * turn — the difference between the model answering a question and merely
 * continuing this app's prompt as prose, which is invisible from Kotlin and
 * was previously invisible in the log too.
 */
JNIEXPORT jstring JNICALL
Java_ai_localstudio_app_llama_LlamaBridge_nativeChatTemplateInfo(JNIEnv *env, jobject, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session == nullptr || session->model == nullptr) return env->NewStringUTF("no model");
    const char *tmpl = llama_model_chat_template(session->model, nullptr);
    const std::string state = tmpl == nullptr
        ? "absent from this GGUF"
        : "present (" + std::to_string(strlen(tmpl)) + " chars)";
    return env->NewStringUTF((state + "; last turn: " + g_lastTemplateInfo).c_str());
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
    contextParams.n_batch = BATCH_SIZE;
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

    // The Kotlin side resends the whole conversation as prompt text every
    // turn rather than an incremental continuation, but for a plain
    // back-and-forth chat that resent text is just the previous prompt with
    // new content appended — the shared prefix's KV state is still exactly
    // what it was. Trimming the cache to the longest common prefix (instead
    // of clearing it outright) and decoding only the diverged suffix reuses
    // that state; llama_batch_get_one's automatic position tracking then
    // continues correctly from the trim point on its own. When nothing
    // matches (commonPrefixLen == 0) this is equivalent to the old
    // unconditional clear; when the whole prompt already matches, it is a
    // no-op. A prompt that reorders earlier content (attachments, recalled
    // memory) simply gets a short or zero common prefix and falls back to
    // redecoding it — never wrong, just not sped up.
    size_t commonPrefixLen = 0;
    const size_t maxCommon = std::min(session->cachedTokens.size(), (size_t) count);
    while (commonPrefixLen < maxCommon && session->cachedTokens[commonPrefixLen] == tokens[commonPrefixLen]) {
        commonPrefixLen++;
    }
    llama_memory_seq_rm(llama_get_memory(session->ctx), 0, (llama_pos) commonPrefixLen, -1);

    // Processed in BATCH_SIZE-token pieces, checking cancellation between
    // them — a single llama_decode() call over the whole prompt cannot be
    // interrupted mid-call, so a long prompt (a full conversation resent
    // every turn, easily thousands of tokens) previously meant a cancel
    // request from Kotlin did nothing until that entire call returned,
    // however long that took. That left this thread still inside
    // llama_decode() well after the Kotlin side had given up and moved on;
    // if the model was then evicted to make room for a different one, its
    // context was freed while this call was still using it — a
    // use-after-free, and a very plausible cause of a crash that only shows
    // up after a timeout or a model switch, not on a plain single turn.
    // Measured, not guessed at. "First token after 141 seconds" says nothing
    // about whether that was a large prompt processed at a normal rate, a
    // small one processed at a collapsed rate, or prefix reuse silently not
    // working — and those want completely different fixes. Recorded per turn
    // and reported through nativeLastTurnStats.
    const int64_t prefillStart = nowMs();
    session->promptTokens = count;
    session->reusedTokens = (int32_t) commonPrefixLen;
    for (int32_t offset = (int32_t) commonPrefixLen; offset < count; offset += BATCH_SIZE) {
        if (session->cancelled.load()) {
            // Only the prefix through `offset` actually made it into the KV
            // cache — trusting the full intended prompt here would make the
            // next call's common-prefix comparison believe tokens are cached
            // that never got decoded.
            session->cachedTokens.assign(tokens.begin(), tokens.begin() + offset);
            session->prefillMs = nowMs() - prefillStart;
            return 0;
        }
        const int32_t batchCount = std::min(BATCH_SIZE, count - offset);
        if (llama_decode(session->ctx, llama_batch_get_one(tokens.data() + offset, batchCount)) != 0) {
            // Cache state after a failed decode is unknown; force a full
            // redecode on the next call rather than risk trusting it.
            session->cachedTokens.clear();
            session->prefillMs = nowMs() - prefillStart;
            return -5;
        }
    }
    session->prefillMs = nowMs() - prefillStart;

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

    const int64_t decodeStart = nowMs();
    int32_t produced = 0;
    uint32_t used = (uint32_t) count;
    // Only tokens that were themselves successfully decoded are actually
    // resident in the KV cache — recorded separately from `produced` so a
    // token that was sampled/emitted but then failed to decode (the break
    // below) is correctly excluded from what the next call can trust as cached.
    std::vector<llama_token> generatedTokens;
    // Bytes held back because they end mid-codepoint — see endsOnCompleteUtf8.
    std::string pendingUtf8;
    while (produced < maxTokens && used + 1 < contextSize) {
        if (session->cancelled.load()) break;

        llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) break;

        pendingUtf8 += pieceOf(session->vocab, token);
        if (!pendingUtf8.empty() && endsOnCompleteUtf8(pendingUtf8)) {
            jstring value = env->NewStringUTF(pendingUtf8.c_str());
            env->CallVoidMethod(callback, onToken, value);
            env->DeleteLocalRef(value);
            pendingUtf8.clear();
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
        }

        if (llama_decode(session->ctx, llama_batch_get_one(&token, 1)) != 0) break;
        generatedTokens.push_back(token);
        produced++;
        used++;
    }

    llama_sampler_free(sampler);
    session->decodeMs = nowMs() - decodeStart;
    session->decodedTokens = produced;
    // Whatever prompt tokens were decoded plus whichever generated tokens
    // were themselves decoded back in are what the KV cache actually holds
    // now, regardless of which of the above paths (EOG, maxTokens, cancelled)
    // stopped the loop.
    session->cachedTokens.assign(tokens.begin(), tokens.begin() + count);
    session->cachedTokens.insert(session->cachedTokens.end(), generatedTokens.begin(), generatedTokens.end());
    return produced;
}

} // extern "C"
