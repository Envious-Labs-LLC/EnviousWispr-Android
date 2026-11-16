#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <unistd.h>
#include <utility>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml-backend.h"
#include "llama.h"
#include "sampling.h"

namespace {

constexpr const char * LOG_TAG = "S1Native";
constexpr int BATCH_SIZE = 512;
// The CPU variant modules this library builds (CMakeLists.txt keeps their names; only the shared libraries are
// renamed). The app APK also carries GenieX's libggml-cpu.so, libggml-opencl.so and libggml-hexagon.so, which are
// compiled for newer processors, so the loader below never looks at anything but these names (#449).
constexpr const char * CPU_VARIANT_PREFIX = "libggml-cpu-android_";

// How the last generation ended (#449), read by S1Native.lastEnd. Codes match S1PortableRuntime; END_NONE means
// the call failed before it could end (an ERROR: string), never a timeout.
constexpr int END_NONE = -1;
constexpr int END_EOS = 0;
constexpr int END_LENGTH = 1;
constexpr int END_ABORTED = 2;
constexpr size_t MAX_PREFIX_STATE_BYTES = 64u * 1024u * 1024u;

std::mutex g_mutex;
bool g_backend_initialized = false;
std::string g_cpu_variant;
int g_context_size = 2048;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_batch g_batch{};
common_chat_templates_ptr g_templates;
llama_tokens g_cached_prefix_tokens;
std::vector<uint8_t> g_cached_prefix_state;
// Set by S1Native.abort from the deadline thread WITHOUT g_mutex (generation holds it); cleared by resetAbort
// before the deadline is armed. Checked between steps and, through the llama abort callback, inside a decode.
std::atomic<bool> g_abort{false};
int g_last_end[4] = {END_NONE, 0, 0, 0};

void log_info(const std::string & message) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", message.c_str());
}

// llama.cpp's log text is vendor text this app does not control (model paths and tensor names today,
// whatever a later version adds); none of it reaches logcat (#194, the same silence the GenieX runtime keeps). A failed load still reports
// through the fixed JNI error strings below and a healthy one through log_info's summary line.
void llama_log_callback(ggml_log_level, const char *, void *) {
}

bool abort_requested(void *) {
    return g_abort.load(std::memory_order_relaxed);
}

// Picks the best CPU variant this processor can run by each module's own ggml_backend_score (which reads the
// processor's feature report), then registers only that one. Never ggml_backend_load_all_from_path: with no
// variant of a name it falls back to a base libggml-<name>.so, which in this APK is GenieX's (#449).
bool load_cpu_backend(const std::string & directory) {
    DIR * dir = opendir(directory.c_str());
    if (dir == nullptr) return false;
    int best_score = 0;
    std::string best_path;
    std::string best_name;
    const size_t prefix_length = std::strlen(CPU_VARIANT_PREFIX);
    while (dirent * entry = readdir(dir)) {
        const std::string name = entry->d_name;
        if (name.compare(0, prefix_length, CPU_VARIANT_PREFIX) != 0) continue;
        if (name.size() < 3 || name.compare(name.size() - 3, 3, ".so") != 0) continue;
        const std::string path = directory + "/" + name;
        void * handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) continue;
        auto score = reinterpret_cast<int (*)()>(dlsym(handle, "ggml_backend_score"));
        const int value = score == nullptr ? 0 : score();
        if (value > best_score) {
            best_score = value;
            best_path = path;
            best_name = name;
        }
        dlclose(handle);
    }
    closedir(dir);
    if (best_score == 0 || ggml_backend_load(best_path.c_str()) == nullptr) return false;
    g_cpu_variant = best_name;
    return true;
}

std::string from_jstring(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring to_jstring(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

void clear_prefix_cache() {
    llama_tokens().swap(g_cached_prefix_tokens);
    std::vector<uint8_t>().swap(g_cached_prefix_state);
}

struct SamplerGuard {
    common_sampler * sampler = nullptr;
    ~SamplerGuard() {
        if (sampler != nullptr) {
            common_sampler_free(sampler);
        }
    }
};

void free_model() {
    clear_prefix_cache();
    g_templates.reset();
    if (g_batch.token != nullptr) {
        llama_batch_free(g_batch);
        g_batch = {};
    }
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

// 0 decoded, 1 failed, 2 aborted.
int decode_tokens(const llama_tokens & tokens, size_t start_index = 0) {
    int position = static_cast<int>(start_index);
    for (size_t offset = start_index; offset < tokens.size(); offset += BATCH_SIZE) {
        if (g_abort.load()) return 2;
        const size_t count = std::min(static_cast<size_t>(BATCH_SIZE), tokens.size() - offset);
        common_batch_clear(g_batch);
        for (size_t index = 0; index < count; ++index) {
            const bool needs_logits = (offset + index) == tokens.size() - 1;
            common_batch_add(g_batch, tokens[offset + index], position++, {0}, needs_logits);
        }
        const int result = llama_decode(g_context, g_batch);
        if (g_abort.load()) return 2;
        if (result != 0) return 1;
    }
    return 0;
}

bool warm_prefix_state(const std::string & system_prompt) {
    clear_prefix_cache();
    if (g_model == nullptr || g_context == nullptr || !g_templates || system_prompt.empty()) {
        return false;
    }
    if (g_abort.load()) return false;

    try {
        common_chat_templates_inputs inputs;
        inputs.messages = {
            {"system", system_prompt},
        };
        inputs.add_generation_prompt = false;
        inputs.use_jinja = true;
        inputs.enable_thinking = false;

        const std::string prompt = common_chat_templates_apply(g_templates.get(), inputs).prompt;
        llama_tokens prefix_tokens = common_tokenize(g_context, prompt, true, true);
        if (prefix_tokens.empty() || static_cast<int>(prefix_tokens.size()) >= g_context_size - 16) {
            return false;
        }

        llama_memory_clear(llama_get_memory(g_context), true);
        const int decoded = decode_tokens(prefix_tokens);
        if (decoded != 0) {
            llama_memory_clear(llama_get_memory(g_context), true);
            return false;
        }

        const size_t state_size = llama_state_seq_get_size(g_context, 0);
        if (state_size == 0 || state_size > MAX_PREFIX_STATE_BYTES) {
            llama_memory_clear(llama_get_memory(g_context), true);
            return false;
        }

        std::vector<uint8_t> state(state_size);
        const size_t written = llama_state_seq_get_data(g_context, state.data(), state.size(), 0);
        if (written != state_size) {
            llama_memory_clear(llama_get_memory(g_context), true);
            return false;
        }
        // Move only after capture succeeds so an allocation failure earlier cannot leave a partial snapshot.
        g_cached_prefix_tokens = std::move(prefix_tokens);
        g_cached_prefix_state = std::move(state);
        return true;
    } catch (...) {
        clear_prefix_cache();
        if (g_context != nullptr) {
            llama_memory_clear(llama_get_memory(g_context), true);
        }
        return false;
    }
}

std::string aborted(int prompt_tokens, int generated) {
    g_last_end[0] = END_ABORTED;
    g_last_end[1] = generated;
    g_last_end[2] = prompt_tokens;
    g_last_end[3] = 0;
    return "ERROR: S1 generation aborted";
}

std::string generate_text(const std::string & system_prompt,
                          const std::string & user_prompt,
                          int requested_tokens) {
    g_last_end[0] = END_NONE; g_last_end[1] = 0; g_last_end[2] = 0; g_last_end[3] = 0;
    if (g_model == nullptr || g_context == nullptr || !g_templates) {
        return "ERROR: S1-mini is not loaded";
    }
    if (g_abort.load()) return aborted(0, 0);

    common_chat_templates_inputs inputs;
    inputs.messages = {
        {"system", system_prompt},
        {"user", user_prompt},
    };
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;
    inputs.enable_thinking = false;

    std::string prompt;
    try {
        prompt = common_chat_templates_apply(g_templates.get(), inputs).prompt;
    } catch (const std::exception &) {
        // Never the exception's text: a template error can quote the prompt (#449).
        return "ERROR: Chat template failed";
    }

    try {
        const llama_tokens prompt_tokens = common_tokenize(g_context, prompt, true, true);
        if (prompt_tokens.empty()) return "ERROR: S1 prompt tokenization failed";
        const int prompt_count = static_cast<int>(prompt_tokens.size());

        const int available_tokens = g_context_size - prompt_count - 8;
        if (available_tokens <= 0) return "ERROR: S1 prompt exceeds the mobile context limit";
        const int max_tokens = std::max(1, std::min(requested_tokens, available_tokens));

        bool used_prefix_cache = false;
        if (!g_cached_prefix_tokens.empty() &&
            !g_cached_prefix_state.empty() &&
            prompt_tokens.size() > g_cached_prefix_tokens.size() &&
            std::equal(g_cached_prefix_tokens.begin(), g_cached_prefix_tokens.end(), prompt_tokens.begin())) {
            try {
                llama_memory_clear(llama_get_memory(g_context), true);
                const size_t restored = llama_state_seq_set_data(
                    g_context, g_cached_prefix_state.data(), g_cached_prefix_state.size(), 0);
                if (restored == g_cached_prefix_state.size()) {
                    const int suffix_decoded = decode_tokens(prompt_tokens, g_cached_prefix_tokens.size());
                    if (suffix_decoded == 2) return aborted(prompt_count, 0);
                    if (suffix_decoded == 0) {
                        used_prefix_cache = true;
                    } else {
                        clear_prefix_cache();
                        llama_memory_clear(llama_get_memory(g_context), true);
                    }
                } else {
                    clear_prefix_cache();
                    llama_memory_clear(llama_get_memory(g_context), true);
                }
            } catch (...) {
                clear_prefix_cache();
                llama_memory_clear(llama_get_memory(g_context), true);
                used_prefix_cache = false;
            }
        }
        if (!used_prefix_cache) {
            llama_memory_clear(llama_get_memory(g_context), true);
            const int decoded = decode_tokens(prompt_tokens);
            if (decoded == 2) return aborted(prompt_count, 0);
            if (decoded != 0) return "ERROR: Prompt decode failed";
        }

        common_params_sampling sampling;
        sampling.temp = 0.0f;
        sampling.top_k = 0;
        sampling.top_p = 1.0f;
        sampling.min_p = 0.0f;
        sampling.penalty_repeat = 1.0f;
        SamplerGuard sampler{common_sampler_init(g_model, sampling)};
        if (sampler.sampler == nullptr) return "ERROR: S1 sampler initialization failed";

        std::string output;
        int position = prompt_count;
        const llama_vocab * vocab = llama_model_get_vocab(g_model);
        int end = END_LENGTH;
        int generated = 0;

        for (; generated < max_tokens; ++generated) {
            if (g_abort.load()) {
                return aborted(prompt_count, generated);
            }
            const llama_token token = common_sampler_sample(sampler.sampler, g_context, -1);
            common_sampler_accept(sampler.sampler, token, true);
            if (llama_vocab_is_eog(vocab, token)) {
                end = END_EOS;
                break;
            }

            output += common_token_to_piece(g_context, token);

            common_batch_clear(g_batch);
            common_batch_add(g_batch, token, position++, {0}, true);
            const int result = llama_decode(g_context, g_batch);
            if (g_abort.load()) {
                return aborted(prompt_count, generated + 1);
            }
            if (result != 0) {
                return "ERROR: S1 generation decode failed";
            }
        }

        g_last_end[0] = end;
        g_last_end[1] = end == END_EOS ? generated : max_tokens;
        g_last_end[2] = prompt_count;
        g_last_end[3] = used_prefix_cache ? 1 : 0;
        return output;
    } catch (...) {
        clear_prefix_cache();
        if (g_context != nullptr) {
            llama_memory_clear(llama_get_memory(g_context), true);
        }
        return "ERROR: Out of memory during local polish";
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_envi_wispr_llama_S1Native_initialize(
    JNIEnv * env, jobject, jstring native_library_dir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_initialized) {
        llama_log_set(llama_log_callback, nullptr);
        if (!load_cpu_backend(from_jstring(env, native_library_dir))) {
            return to_jstring(env, "ERROR: No CPU backend could load");
        }
        llama_backend_init();
        g_backend_initialized = true;
    }
    return to_jstring(env, g_cpu_variant + " | " + llama_print_system_info());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_envi_wispr_llama_S1Native_loadModel(
    JNIEnv * env, jobject, jstring model_path, jint context_size, jint thread_count) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_initialized) return to_jstring(env, "ERROR: Runtime is not initialized");

    free_model();
    g_context_size = std::clamp(static_cast<int>(context_size), 512, 4096);
    const int available_threads = std::max(2L, sysconf(_SC_NPROCESSORS_ONLN) - 2);
    const int threads = std::clamp(static_cast<int>(thread_count), 2, available_threads);

    llama_model_params model_params = llama_model_default_params();
    const std::string path = from_jstring(env, model_path);
    g_model = llama_model_load_from_file(path.c_str(), model_params);
    if (g_model == nullptr) return to_jstring(env, "ERROR: Unable to load S1-mini model");

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = g_context_size;
    context_params.n_batch = BATCH_SIZE;
    context_params.n_ubatch = BATCH_SIZE;
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.abort_callback = abort_requested;
    context_params.abort_callback_data = nullptr;
    g_context = llama_init_from_model(g_model, context_params);
    if (g_context == nullptr) {
        free_model();
        return to_jstring(env, "ERROR: Unable to allocate S1-mini context");
    }

    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
    g_templates = common_chat_templates_init(g_model, "");
    if (!g_templates) {
        free_model();
        return to_jstring(env, "ERROR: S1-mini chat template is unavailable");
    }

    char description[256];
    llama_model_desc(g_model, description, sizeof(description));
    std::ostringstream status;
    status << description << " | context " << g_context_size << " | threads " << threads;
    log_info(status.str());
    return to_jstring(env, status.str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_envi_wispr_llama_S1Native_warmPrefix(
    JNIEnv * env, jobject, jstring system_prompt) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        return warm_prefix_state(from_jstring(env, system_prompt)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        clear_prefix_cache();
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_envi_wispr_llama_S1Native_generate(
    JNIEnv * env, jobject, jstring system_prompt, jstring user_prompt, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        return to_jstring(env, generate_text(
            from_jstring(env, system_prompt),
            from_jstring(env, user_prompt),
            static_cast<int>(max_tokens)));
    } catch (...) {
        clear_prefix_cache();
        return to_jstring(env, "ERROR: Out of memory during local polish");
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_envi_wispr_llama_S1Native_lastEnd(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    jintArray result = env->NewIntArray(4);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 4, g_last_end);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_envi_wispr_llama_S1Native_resetAbort(JNIEnv *, jobject) {
    g_abort.store(false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_envi_wispr_llama_S1Native_abort(JNIEnv *, jobject) {
    g_abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_envi_wispr_llama_S1Native_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model();
}
