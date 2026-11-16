// EG-1 Micro on Alibaba MNN (#531). One Llm per process, driven only from PolishService's worker thread; abort() and
// resetAbort() are the exceptions and are safe from any thread. Errors come back as "ERROR: ..." strings, matching the
// S1Native protocol PolishService already reads. Nothing here logs: MNN is built with MNN_USE_LOGCAT=OFF, and this file
// never writes the prompt, the output or any user text anywhere.

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <map>
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
// Prefix reuse (#531 plan §3.6): on when the model was loaded with a prefix folder.
std::string g_prefixDir;
constexpr size_t MAX_PREFIX_ENTRIES = 4;
const char* const SIDECAR = ".ok";

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

namespace fs = std::filesystem;

std::vector<int> render(const std::string& system, const std::string& user) {
    return g_llm->tokenizer_encode(g_llm->apply_chat_template(ChatMessages{{"system", system}, {"user", user}}));
}

// The shared prefix of a request: the common tokens of two renders that differ only in the user turn, cut further to
// what the request's own tokens share with them. Zero means no usable prefix.
size_t prefixLength(const std::string& system, const std::vector<int>& ids) {
    const auto a = render(system, "a");
    const auto b = render(system, "b c");
    size_t n = 0;
    while (n < a.size() && n < b.size() && n < ids.size() && a[n] == b[n] && a[n] == ids[n]) n++;
    return n < ids.size() ? n : 0;
}

// Names an entry by its exact prefix tokens (FNV-1a 64), so an entry is only ever reused for those tokens.
std::string entryKey(const std::vector<int>& ids, size_t n) {
    uint64_t h = 1469598103934665603ULL;
    for (size_t i = 0; i < n; i++) {
        h ^= static_cast<uint32_t>(ids[i]);
        h *= 1099511628211ULL;
    }
    char key[24];
    snprintf(key, sizeof key, "p%016llx", static_cast<unsigned long long>(h));
    return key;
}

bool ownedBy(const fs::path& file, const std::string& key) {
    const std::string name = file.filename().string();
    return name.rfind(key + "_", 0) == 0 || name == key + SIDECAR;
}

// Files only, one by one (tools-and-apps.md RULE: never-unattended-recursive-delete).
void removeEntry(const std::string& key) {
    std::error_code ec;
    for (const auto& item : fs::directory_iterator(g_prefixDir, ec)) {
        if (item.is_regular_file(ec) && ownedBy(item.path(), key)) fs::remove(item.path(), ec);
    }
}

// True when the sidecar exists and every file it lists still has its recorded size.
bool entryTrusted(const std::string& key) {
    std::ifstream in(fs::path(g_prefixDir) / (key + SIDECAR));
    if (!in) return false;
    std::string name;
    uintmax_t size = 0;
    int listed = 0;
    std::error_code ec;
    while (in >> name >> size) {
        listed++;
        const fs::path file = fs::path(g_prefixDir) / name;
        if (!fs::is_regular_file(file, ec) || fs::file_size(file, ec) != size) return false;
    }
    return listed > 0;
}

// Records every file of a freshly written entry with its size; written only after the write call returned cleanly.
void publishEntry(const std::string& key) {
    std::error_code ec;
    std::vector<std::pair<std::string, uintmax_t>> files;
    for (const auto& item : fs::directory_iterator(g_prefixDir, ec)) {
        const std::string name = item.path().filename().string();
        if (item.is_regular_file(ec) && name.rfind(key + "_", 0) == 0) files.emplace_back(name, item.file_size(ec));
    }
    if (files.empty()) return;
    const fs::path tmp = fs::path(g_prefixDir) / (key + SIDECAR + ".tmp");
    {
        std::ofstream out(tmp);
        for (const auto& [name, size] : files) out << name << " " << size << "\n";
    }
    fs::rename(tmp, fs::path(g_prefixDir) / (key + SIDECAR), ec);
}

// Keeps at most MAX_PREFIX_ENTRIES complete entries counting the one about to be built; drops incomplete leftovers.
void makeRoomFor(const std::string& key) {
    std::error_code ec;
    std::map<std::string, fs::file_time_type> complete;
    std::vector<std::string> incomplete;
    for (const auto& item : fs::directory_iterator(g_prefixDir, ec)) {
        const std::string name = item.path().filename().string();
        if (name.size() < 18 || name[0] != 'p') continue;
        const std::string owner = name.substr(0, 17);
        if (owner == key) continue;
        if (name == owner + SIDECAR) complete[owner] = item.last_write_time(ec);
        else if (std::find(incomplete.begin(), incomplete.end(), owner) == incomplete.end()) incomplete.push_back(owner);
    }
    for (const auto& owner : incomplete) {
        if (!complete.count(owner)) removeEntry(owner);
    }
    std::vector<std::pair<fs::file_time_type, std::string>> byAge;
    for (const auto& [owner, time] : complete) byAge.emplace_back(time, owner);
    std::sort(byAge.begin(), byAge.end());
    while (byAge.size() + 1 > MAX_PREFIX_ENTRIES) {
        removeEntry(byAge.front().second);
        byAge.erase(byAge.begin());
    }
}

// One prefix session: entry check, MNN's call 1 (prefix) and call 2 (rest), both prefill only. Every request runs one,
// because a plain generate after prefix mode is on would be read as part of a stale session (plan §3.6).
// Returns false when aborted or failed before the step loop.
bool prefixSession(const std::vector<int>& ids, size_t n, std::ostringstream& text) {
    const std::string key = entryKey(ids, n);
    const bool trusted = entryTrusted(key);
    if (!trusted) {
        removeEntry(key);
        makeRoomFor(key);
    }
    g_llm->reset();
    g_llm->setPrefixCacheFile(key);
    g_llm->generate_init(&text, nullptr);
    if (g_abort.load()) return false;
    g_llm->generate(std::vector<int>(ids.begin(), ids.begin() + n), 0);
    if (g_llm->getContext()->status == LlmStatus::INTERNAL_ERROR) return false;
    if (!trusted) publishEntry(key);
    if (g_abort.load()) return false;
    g_llm->generate(std::vector<int>(ids.begin() + n, ids.end()), 0);
    return g_llm->getContext()->status != LlmStatus::INTERNAL_ERROR;
}

}  // namespace

extern "C" {

// configPath: the model folder's config.json (anything else is refused, so a GGUF path can never reach MNN).
// backend: "cpu" or "opencl". cacheDir: an existing, writable folder outside the model folder for the weight cache.
JNIEXPORT jstring JNICALL Java_com_envi_wispr_mnn_MnnNative_loadModel(JNIEnv* env, jobject, jstring jConfigPath,
                                                                     jstring jBackend, jint threads, jstring jCacheDir,
                                                                     jstring jPrefixDir) {
    const std::string configPath = fromJava(env, jConfigPath);
    const std::string backend = fromJava(env, jBackend);
    const std::string cacheDir = fromJava(env, jCacheDir);
    const std::string prefixDir = fromJava(env, jPrefixDir);
    if (!endsWith(configPath, "/config.json")) return toJava(env, "ERROR: not an MNN config.json");
    if (backend != "cpu" && backend != "opencl") return toJava(env, "ERROR: unknown backend");
    if (cacheDir.empty()) return toJava(env, "ERROR: no cache folder");
    g_llm.reset();
    g_prefixDir.clear();
    std::unique_ptr<Llm> llm(Llm::createLLM(configPath));
    if (!llm) return toJava(env, "ERROR: createLLM failed");
    std::ostringstream config;
    config << "{\"backend_type\":" << jsonString(backend) << ",\"thread_num\":" << threads
           << ",\"precision\":\"low\",\"memory\":\"low\",\"sampler_type\":\"greedy\",\"reuse_kv\":false"
           << ",\"use_mmap\":true,\"tmp_path\":" << jsonString(cacheDir);
    if (!prefixDir.empty()) config << ",\"kvcache_mmap\":true,\"prefix_cache_path\":" << jsonString(prefixDir);
    config
           << ",\"jinja\":{\"context\":{\"enable_thinking\":false}}}";
    if (!llm->set_config(config.str())) return toJava(env, "ERROR: set_config failed");
    if (!llm->load()) return toJava(env, "ERROR: load failed");
    g_llm = std::move(llm);
    g_prefixDir = prefixDir;
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
    if (g_prefixDir.empty()) {
        g_llm->generate_init(&text, nullptr);
        g_llm->generate(ids, 0);  // prefill only
    } else {
        const size_t n = prefixLength(messages[0].second, ids);
        if (n == 0) return toJava(env, "ERROR: no shared prefix");
        if (!prefixSession(ids, n, text)) {
            g_last[0] = g_abort.load() ? END_ABORTED : END_ENGINE_ERROR;
            return toJava(env, g_abort.load() ? "ERROR: aborted" : "ERROR: engine error");
        }
    }
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

// Builds (or confirms) the prefix entry for [system] before the first request, while the user is still dictating.
JNIEXPORT jboolean JNICALL Java_com_envi_wispr_mnn_MnnNative_warmPrefix(JNIEnv* env, jobject, jstring jSystem) {
    if (!g_llm || g_prefixDir.empty()) return JNI_FALSE;
    const std::string system = fromJava(env, jSystem);
    const std::vector<int> ids = render(system, "a");
    const size_t n = prefixLength(system, ids);
    if (n == 0) return JNI_FALSE;
    const std::string key = entryKey(ids, n);
    if (entryTrusted(key)) return JNI_TRUE;
    removeEntry(key);
    makeRoomFor(key);
    std::ostringstream ignored;
    g_llm->reset();
    g_llm->setPrefixCacheFile(key);
    g_llm->generate_init(&ignored, nullptr);
    if (g_abort.load()) return JNI_FALSE;
    g_llm->generate(std::vector<int>(ids.begin(), ids.begin() + n), 0);
    if (g_llm->getContext()->status == LlmStatus::INTERNAL_ERROR) return JNI_FALSE;
    publishEntry(key);
    return entryTrusted(key) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL Java_com_envi_wispr_mnn_MnnNative_lastEnd(JNIEnv* env, jobject) {
    jintArray out = env->NewIntArray(5);
    if (out != nullptr) env->SetIntArrayRegion(out, 0, 5, g_last);
    return out;
}

JNIEXPORT void JNICALL Java_com_envi_wispr_mnn_MnnNative_resetAbort(JNIEnv*, jobject) { g_abort.store(false); }

JNIEXPORT void JNICALL Java_com_envi_wispr_mnn_MnnNative_abort(JNIEnv*, jobject) { g_abort.store(true); }

JNIEXPORT void JNICALL Java_com_envi_wispr_mnn_MnnNative_unload(JNIEnv*, jobject) {
    g_llm.reset();
    g_prefixDir.clear();
}

#ifdef EW_MNN_TEST_SWITCHES
// Debug libraries only: MNN's own one-call generation, the oracle the step loop must match token for token. Valid only
// on a model loaded WITHOUT a prefix folder: with prefix mode on, a plain call could join a stale session.
JNIEXPORT jstring JNICALL Java_com_envi_wispr_mnn_MnnNative_referenceResponse(JNIEnv* env, jobject, jstring jSystem,
                                                                             jstring jUser, jint maxTokens) {
    if (!g_llm) return toJava(env, "ERROR: model not loaded");
    if (!g_prefixDir.empty()) return toJava(env, "ERROR: reference needs a load without prefix reuse");
    const ChatMessages messages = {{"system", fromJava(env, jSystem)}, {"user", fromJava(env, jUser)}};
    std::ostringstream text;
    g_llm->response(messages, &text, nullptr, maxTokens);
    return toJava(env, text.str());
}
#endif

}  // extern "C"
