// EG-1 Micro on Alibaba MNN (#531). One Llm per process, driven only from PolishService's worker thread; abort() and
// resetAbort() are the exceptions and are safe from any thread. Errors come back as "ERROR: ..." strings, matching the
// S1Native protocol PolishService already reads. Nothing here logs: MNN is built with MNN_USE_LOGCAT=OFF, and this file
// never writes the prompt, the output or any user text anywhere.

#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "llm/llm.hpp"

using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;

namespace {

std::unique_ptr<Llm> g_llm;
std::atomic<bool> g_abort{false};

// [end code, generated tokens, prompt tokens, prefill ms, decode ms]. End codes: 0 stop token, 1 token cap,
// 2 aborted, 3 engine error, 4 refused before running (context or input).
constexpr int END_STOP = 0;
constexpr int END_CAP = 1;
constexpr int END_ABORTED = 2;
constexpr int END_ENGINE_ERROR = 3;
constexpr int END_REFUSED = 4;
int32_t g_last[5] = {END_REFUSED, 0, 0, 0, 0};

std::string fromJava(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring toJava(JNIEnv* env, const std::string& value) { return env->NewStringUTF(value.c_str()); }

// JSON string escaping for the paths and words this file puts into set_config. Every byte below 0x20 is escaped.
std::string jsonString(const std::string& value) {
    std::string out = "\"";
    for (unsigned char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            default:
                if (c < 0x20) {
                    char buffer[8];
                    snprintf(buffer, sizeof buffer, "\\u%04x", c);
                    out += buffer;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out + "\"";
}

bool endsWith(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() && value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

int64_t usToMs(int64_t us) { return us / 1000; }

}  // namespace

extern "C" {

// configPath: the model folder's config.json (anything else is refused, so a GGUF path can never reach MNN).
// backend: "cpu" or "opencl". cacheDir: an existing, writable folder outside the model folder for the weight cache.
JNIEXPORT jstring JNICALL Java_com_envi_wispr_mnn_MnnNative_loadModel(JNIEnv* env, jobject, jstring jConfigPath,
                                                                     jstring jBackend, jint threads, jstring jCacheDir) {
    const std::string configPath = fromJava(env, jConfigPath);
    const std::string backend = fromJava(env, jBackend);
    const std::string cacheDir = fromJava(env, jCacheDir);
    if (!endsWith(configPath, "/config.json")) return toJava(env, "ERROR: not an MNN config.json");
    if (backend != "cpu" && backend != "opencl") return toJava(env, "ERROR: unknown backend");
    if (cacheDir.empty()) return toJava(env, "ERROR: no cache folder");
    g_llm.reset();
    std::unique_ptr<Llm> llm(Llm::createLLM(configPath));
    if (!llm) return toJava(env, "ERROR: createLLM failed");
    std::ostringstream config;
    config << "{\"backend_type\":" << jsonString(backend) << ",\"thread_num\":" << threads
           << ",\"precision\":\"low\",\"memory\":\"low\",\"sampler_type\":\"greedy\",\"reuse_kv\":false"
           << ",\"use_mmap\":true,\"tmp_path\":" << jsonString(cacheDir)
           << ",\"jinja\":{\"context\":{\"enable_thinking\":false}}}";
    if (!llm->set_config(config.str())) return toJava(env, "ERROR: set_config failed");
    if (!llm->load()) return toJava(env, "ERROR: load failed");
    g_llm = std::move(llm);
    return toJava(env, "");
}

// Prefill once, then decode one token per step so an abort lands between tokens (#531 plan §3.1). contextLimit bounds
// prompt plus output tokens; a prompt that cannot fit is refused rather than truncated.
JNIEXPORT jstring JNICALL Java_com_envi_wispr_mnn_MnnNative_generate(JNIEnv* env, jobject, jstring jSystem,
                                                                    jstring jUser, jint maxTokens, jint contextLimit) {
    g_last[0] = END_REFUSED;
    g_last[1] = g_last[2] = g_last[3] = g_last[4] = 0;
    if (!g_llm) return toJava(env, "ERROR: model not loaded");
    if (maxTokens <= 0) return toJava(env, "ERROR: no output budget");
    const ChatMessages messages = {{"system", fromJava(env, jSystem)}, {"user", fromJava(env, jUser)}};
    const std::vector<int> ids = g_llm->tokenizer_encode(g_llm->apply_chat_template(messages));
    g_last[2] = static_cast<int32_t>(ids.size());
    if (ids.empty()) return toJava(env, "ERROR: empty prompt");
    if (static_cast<int64_t>(ids.size()) + maxTokens > contextLimit) return toJava(env, "ERROR: prompt exceeds context");
    if (g_abort.load()) {
        g_last[0] = END_ABORTED;
        return toJava(env, "ERROR: aborted");
    }

    std::ostringstream text;
    g_llm->generate_init(&text, nullptr);
    g_llm->generate(ids, 0);  // prefill only
    const auto* context = g_llm->getContext();
    int end = END_CAP;
    while (true) {
        if (context->status == LlmStatus::INTERNAL_ERROR) { end = END_ENGINE_ERROR; break; }
        if (g_llm->stoped()) { end = END_STOP; break; }
        if (context->gen_seq_len >= maxTokens) { end = END_CAP; break; }
        if (g_abort.load()) { end = END_ABORTED; break; }
        g_llm->generate(1);
    }
    g_last[0] = end;
    g_last[1] = context->gen_seq_len;
    g_last[3] = static_cast<int32_t>(usToMs(context->prefill_us));
    g_last[4] = static_cast<int32_t>(usToMs(context->decode_us));
    if (end == END_ENGINE_ERROR) return toJava(env, "ERROR: engine error");
    if (end == END_ABORTED) return toJava(env, "ERROR: aborted");
    return toJava(env, text.str());
}

JNIEXPORT jintArray JNICALL Java_com_envi_wispr_mnn_MnnNative_lastEnd(JNIEnv* env, jobject) {
    jintArray out = env->NewIntArray(5);
    if (out != nullptr) env->SetIntArrayRegion(out, 0, 5, g_last);
    return out;
}

JNIEXPORT void JNICALL Java_com_envi_wispr_mnn_MnnNative_resetAbort(JNIEnv*, jobject) { g_abort.store(false); }

JNIEXPORT void JNICALL Java_com_envi_wispr_mnn_MnnNative_abort(JNIEnv*, jobject) { g_abort.store(true); }

JNIEXPORT void JNICALL Java_com_envi_wispr_mnn_MnnNative_unload(JNIEnv*, jobject) { g_llm.reset(); }

#ifdef EW_MNN_TEST_SWITCHES
// Debug libraries only: MNN's own one-call generation, the oracle the step loop must match token for token.
JNIEXPORT jstring JNICALL Java_com_envi_wispr_mnn_MnnNative_referenceResponse(JNIEnv* env, jobject, jstring jSystem,
                                                                             jstring jUser, jint maxTokens) {
    if (!g_llm) return toJava(env, "ERROR: model not loaded");
    const ChatMessages messages = {{"system", fromJava(env, jSystem)}, {"user", fromJava(env, jUser)}};
    std::ostringstream text;
    g_llm->response(messages, &text, nullptr, maxTokens);
    return toJava(env, text.str());
}
#endif

}  // extern "C"
