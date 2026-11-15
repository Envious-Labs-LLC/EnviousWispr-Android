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

// Copy-ahead (prompt-lookup) decoding (#510). Polish mostly copies the transcript, so after each token the next few
// are guessed from where the recent output last appeared in prompt+output, decoded in ONE batch with logits for
// each, and checked one by one by the same greedy sampler. The matching prefix is kept, the first mismatch is the
// sampler's own token, and the rejected positions are removed from the cache. Greedy output is the one-token loop's
// output, except where batched arithmetic flips a near-tie (the fidelity check in #510 measures that).
constexpr int COPY_AHEAD_GUESSES = 4;
// Stats of the last generation, counts only: [decode calls, guessed tokens, accepted guesses, recoveries].
int g_copy_ahead_stats[4] = {0, 0, 0, 0};

#ifdef EW_COPY_AHEAD_TEST_SWITCHES
// Debug builds only (llama-android/build.gradle.kts): device tests drive every branch through these. A release
// library is compiled without them, so nothing outside a test can change decoding.
int test_switch(const char * name, int fallback) {
    const char * value = getenv(name);
    return value == nullptr ? fallback : atoi(value);
}
// EW_COPY_AHEAD_GUESSES=<n>: guesses per step (0 runs the one-token loop, the removal control).
int copy_ahead_guesses() { return std::clamp(test_switch("EW_COPY_AHEAD_GUESSES", COPY_AHEAD_GUESSES), 0, 16); }
// EW_COPY_AHEAD_FAULT=1 fails the first guess batch's decode; =2 makes the first cache removal report failure.
int copy_ahead_fault() { return test_switch("EW_COPY_AHEAD_FAULT", 0); }
// EW_COPY_AHEAD_ACCEPT_ALL=1 accepts every guess unchecked: the mutation the fidelity oracle must catch.
bool copy_ahead_accept_all() { return test_switch("EW_COPY_AHEAD_ACCEPT_ALL", 0) == 1; }
#else
int copy_ahead_guesses() { return COPY_AHEAD_GUESSES; }
int copy_ahead_fault() { return 0; }
bool copy_ahead_accept_all() { return false; }
#endif

// Recurrent-state snapshots a context needs so a recurrent or hybrid model can drop `guesses` rejected positions.
// Classified by the model's own properties, never a model list; llama.cpp clamps the request to zero for an
// architecture that cannot roll back, and copy_ahead_limit then reads that zero.
uint32_t copy_ahead_snapshots(int guesses) {
    if (guesses <= 0) return 0;
    if (!llama_model_is_recurrent(g_model) && !llama_model_is_hybrid(g_model)) return 0;
    return static_cast<uint32_t>(guesses + 1);
}

// Guesses this loaded context can verify and drop: a transformer drops positions from its cache directly; a
// recurrent or hybrid model only within the snapshots its context actually kept.
int copy_ahead_limit() {
    const int guesses = copy_ahead_guesses();
    if (guesses <= 0) return 0;
    if (llama_model_is_recurrent(g_model) || llama_model_is_hybrid(g_model)) {
        const int snapshots = static_cast<int>(llama_n_rs_seq(g_context));
        return snapshots > 1 ? std::min(guesses, snapshots - 1) : 0;
    }
    return guesses;
}

// Longest recent n-gram (3, then 2, then 1 token) found earlier in history; `guess` gets the tokens that followed it.
void lookup_guess(const llama_tokens & history, int max_guess, llama_tokens & guess) {
    guess.clear();
    if (max_guess <= 0) return;
    const int size = static_cast<int>(history.size());
    for (int n = 3; n >= 1 && guess.empty(); --n) {
        if (size <= n) continue;
        for (int start = size - n - 1; start >= 0; --start) {
            if (!std::equal(history.end() - n, history.end(), history.begin() + start)) continue;
            for (int i = start + n; i < size - n && static_cast<int>(guess.size()) < max_guess; ++i) {
                guess.push_back(history[i]);
            }
            if (!guess.empty()) break;
        }
    }
}

// Rebuilds the cache from the committed tokens alone after a speculative failure: a refused removal leaves rejected
// recurrent state in place and a failed decode leaves the cache undefined, so neither can be patched in place.
// Restores the seq-0 prefix when it still matches, else decodes from the start. The sampler is not touched, so
// nothing is accepted twice; the last committed token's logits are valid afterwards. 0 rebuilt, 1 failed, 2 aborted.
int rebuild_committed(const llama_tokens & committed) {
    llama_memory_clear(llama_get_memory(g_context), true);
    if (!g_cached_prefix_tokens.empty() && !g_cached_prefix_state.empty() &&
        committed.size() > g_cached_prefix_tokens.size() &&
        std::equal(g_cached_prefix_tokens.begin(), g_cached_prefix_tokens.end(), committed.begin())) {
        const size_t restored = llama_state_seq_set_data(
            g_context, g_cached_prefix_state.data(), g_cached_prefix_state.size(), 0);
        if (restored == g_cached_prefix_state.size()) return decode_tokens(committed, g_cached_prefix_tokens.size());
        llama_memory_clear(llama_get_memory(g_context), true);
    }
    return decode_tokens(committed);
}

// The generation loop with copy-ahead. `position` is the next cache position after the prompt. Returns END_EOS,
// END_LENGTH, END_ABORTED, or END_NONE for a decode failure the one-token loop would also have reported. After a
// recoverable speculative failure it rebuilds the cache and continues with guessing off, under the same token
// allowance and the caller's same deadline.
int generate_copy_ahead(common_sampler * sampler, const llama_vocab * vocab, const llama_tokens & prompt_tokens,
                        int max_tokens, int limit, int position, std::string & output, int & generated) {
    llama_tokens committed = prompt_tokens;  // prompt plus every emitted token, in cache order
    llama_tokens guess;
    llama_memory_t memory = llama_get_memory(g_context);
    int fault = copy_ahead_fault();
    const bool accept_all = copy_ahead_accept_all();
    // `next` is sampled and accepted by the sampler but not yet emitted.
    llama_token next = common_sampler_sample(sampler, g_context, -1);
    common_sampler_accept(sampler, next, true);
    while (true) {
        if (g_abort.load()) return END_ABORTED;
        if (llama_vocab_is_eog(vocab, next)) return END_EOS;
        output += common_token_to_piece(g_context, next);
        committed.push_back(next);
        if (++generated >= max_tokens) return END_LENGTH;

        const int room = std::min({limit, max_tokens - generated, g_context_size - position - 2, BATCH_SIZE - 1});
        lookup_guess(committed, room, guess);

        const int base = position;
        common_batch_clear(g_batch);
        common_batch_add(g_batch, next, base, {0}, true);
        for (size_t i = 0; i < guess.size(); ++i) {
            common_batch_add(g_batch, guess[i], base + 1 + static_cast<int>(i), {0}, true);
        }
        int result = llama_decode(g_context, g_batch);
        if (fault == 1 && !guess.empty()) { result = -1; fault = 0; }
        if (g_abort.load()) return END_ABORTED;
        if (result != 0) {
            if (guess.empty()) return END_NONE;  // the one-token step failed: exactly today's error
            limit = 0;
            g_copy_ahead_stats[3] += 1;
            const int rebuilt = rebuild_committed(committed);
            if (rebuilt == 2) return END_ABORTED;
            if (rebuilt != 0) return END_NONE;
            position = static_cast<int>(committed.size());
            next = common_sampler_sample(sampler, g_context, -1);
            common_sampler_accept(sampler, next, true);
            continue;
        }
        g_copy_ahead_stats[0] += 1;
        g_copy_ahead_stats[1] += static_cast<int>(guess.size());

        size_t accepted = 0;
        while (true) {
            llama_token token = common_sampler_sample(sampler, g_context, static_cast<int>(accepted));
            if (accept_all && accepted < guess.size()) token = guess[accepted];
            common_sampler_accept(sampler, token, true);
            if (accepted < guess.size() && token == guess[accepted] && !llama_vocab_is_eog(vocab, token)) {
                output += common_token_to_piece(g_context, token);
                committed.push_back(token);
                ++accepted;
                g_copy_ahead_stats[2] += 1;
                if (++generated >= max_tokens) return END_LENGTH;
                continue;
            }
            next = token;
            break;
        }
        position = base + 1 + static_cast<int>(accepted);
        if (accepted == guess.size()) continue;
        if (llama_vocab_is_eog(vocab, next)) return END_EOS;  // nothing more is decoded, so no removal is needed
        bool removed = llama_memory_seq_rm(memory, 0, position, -1);
        if (fault == 2) { removed = false; fault = 0; }
        if (!removed) {
            // `next` stays pending (accepted, not emitted): the rebuilt cache ends at the last committed token.
            limit = 0;
            g_copy_ahead_stats[3] += 1;
            const int rebuilt = rebuild_committed(committed);
            if (rebuilt == 2) return END_ABORTED;
            if (rebuilt != 0) return END_NONE;
            position = static_cast<int>(committed.size());
        }
    }
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

        std::fill(std::begin(g_copy_ahead_stats), std::end(g_copy_ahead_stats), 0);
        if (const int limit = copy_ahead_limit(); limit > 0) {
            const int copy_end = generate_copy_ahead(
                sampler.sampler, vocab, prompt_tokens, max_tokens, limit, position, output, generated);
            std::ostringstream stats;
            stats << "copy-ahead decodes " << g_copy_ahead_stats[0] << " | guessed " << g_copy_ahead_stats[1]
                  << " | accepted " << g_copy_ahead_stats[2] << " | recoveries " << g_copy_ahead_stats[3];
            log_info(stats.str());
            if (copy_end == END_ABORTED) return aborted(prompt_count, generated);
            if (copy_end == END_NONE) return "ERROR: S1 generation decode failed";
            g_last_end[0] = copy_end;
            g_last_end[1] = copy_end == END_EOS ? generated : max_tokens;
            g_last_end[2] = prompt_count;
            g_last_end[3] = used_prefix_cache ? 1 : 0;
            return output;
        }

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
    // Zero for a transformer; a recurrent or hybrid model keeps snapshots so copy-ahead can drop rejected guesses.
    if (const uint32_t snapshots = copy_ahead_snapshots(copy_ahead_guesses()); snapshots > 0) {
        context_params.n_rs_seq = snapshots;
    }
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
    status << description << " | context " << g_context_size << " | threads " << threads
           << " | copy-ahead " << copy_ahead_limit();
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

extern "C" JNIEXPORT jintArray JNICALL
Java_com_envi_wispr_llama_S1Native_lastCopyAheadStats(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    jintArray result = env->NewIntArray(4);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 4, g_copy_ahead_stats);
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
