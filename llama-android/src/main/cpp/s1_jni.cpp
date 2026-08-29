#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <mutex>
#include <sstream>
#include <string>
#include <unistd.h>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

namespace {

constexpr const char * LOG_TAG = "S1Native";
constexpr int BATCH_SIZE = 512;

std::mutex g_mutex;
bool g_backend_initialized = false;
int g_context_size = 2048;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
llama_batch g_batch{};
common_chat_templates_ptr g_templates;

void log_info(const std::string & message) {
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", message.c_str());
}

void llama_log_callback(ggml_log_level level, const char * text, void *) {
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    __android_log_print(priority, "llama.cpp", "%s", text);
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

void free_model() {
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

bool decode_tokens(const llama_tokens & tokens, std::string & error) {
    int position = 0;
    for (int offset = 0; offset < static_cast<int>(tokens.size()); offset += BATCH_SIZE) {
        const int count = std::min(BATCH_SIZE, static_cast<int>(tokens.size()) - offset);
        common_batch_clear(g_batch);
        for (int index = 0; index < count; ++index) {
            const bool needs_logits = offset + index == static_cast<int>(tokens.size()) - 1;
            common_batch_add(g_batch, tokens[offset + index], position++, {0}, needs_logits);
        }
        const int result = llama_decode(g_context, g_batch);
        if (result != 0) {
            error = "Prompt decode failed: " + std::to_string(result);
            return false;
        }
    }
    return true;
}

std::string generate_text(const std::string & system_prompt,
                          const std::string & user_prompt,
                          int requested_tokens) {
    if (g_model == nullptr || g_context == nullptr || !g_templates) {
        return "ERROR: S1-mini is not loaded";
    }

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
    } catch (const std::exception & exception) {
        return std::string("ERROR: Chat template failed: ") + exception.what();
    }

    llama_memory_clear(llama_get_memory(g_context), true);
    const llama_tokens prompt_tokens = common_tokenize(g_context, prompt, true, true);
    if (prompt_tokens.empty()) return "ERROR: S1 prompt tokenization failed";

    const int available_tokens =
        g_context_size - static_cast<int>(prompt_tokens.size()) - 8;
    if (available_tokens <= 0) return "ERROR: S1 prompt exceeds the mobile context limit";
    const int max_tokens = std::max(1, std::min(requested_tokens, available_tokens));

    std::string error;
    if (!decode_tokens(prompt_tokens, error)) return "ERROR: " + error;

    common_params_sampling sampling;
    sampling.temp = 0.0f;
    sampling.top_k = 0;
    sampling.top_p = 1.0f;
    sampling.min_p = 0.0f;
    sampling.penalty_repeat = 1.0f;
    common_sampler * sampler = common_sampler_init(g_model, sampling);
    if (sampler == nullptr) return "ERROR: S1 sampler initialization failed";

    std::string output;
    int position = static_cast<int>(prompt_tokens.size());
    const llama_vocab * vocab = llama_model_get_vocab(g_model);

    for (int generated = 0; generated < max_tokens; ++generated) {
        const llama_token token = common_sampler_sample(sampler, g_context, -1);
        common_sampler_accept(sampler, token, true);
        if (llama_vocab_is_eog(vocab, token)) break;

        output += common_token_to_piece(g_context, token);

        common_batch_clear(g_batch);
        common_batch_add(g_batch, token, position++, {0}, true);
        if (llama_decode(g_context, g_batch) != 0) {
            common_sampler_free(sampler);
            return "ERROR: S1 generation decode failed";
        }
    }

    common_sampler_free(sampler);
    return output;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_envi_wispr_llama_S1Native_initialize(
    JNIEnv * env, jobject, jstring native_library_dir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_backend_initialized) {
        llama_log_set(llama_log_callback, nullptr);
        const std::string directory = from_jstring(env, native_library_dir);
        ggml_backend_load_all_from_path(directory.c_str());
        llama_backend_init();
        g_backend_initialized = true;
    }
    return to_jstring(env, llama_print_system_info());
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_envi_wispr_llama_S1Native_generate(
    JNIEnv * env, jobject, jstring system_prompt, jstring user_prompt, jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_jstring(env, generate_text(
        from_jstring(env, system_prompt),
        from_jstring(env, user_prompt),
        static_cast<int>(max_tokens)));
}

extern "C" JNIEXPORT void JNICALL
Java_com_envi_wispr_llama_S1Native_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model();
}
