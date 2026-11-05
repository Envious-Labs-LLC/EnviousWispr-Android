#pragma once

#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <limits>
#include <mutex>
#include <string>
#include <vector>
#include <jni.h>
#include "llama.h"
#include "sampling.h"

namespace {
namespace sampler_study {

enum Phase { None = 0, Before = 1, PromptNative = 2, BetweenTokens = 3, TokenNative = 4 };

struct Observation {
    int mode = -1;
    int entry = -1;
    int effective_cap = 0;
    int accepted = 0;
    int last_accepted = -1;
    int64_t serial = 0;
    int64_t elapsed_ns = 0;
    int64_t sampling_ns = 0;
    uint32_t seed = 0;
    std::string chain;
    std::vector<jint> tokens;
};

inline Observation observation;
inline std::atomic<bool> enabled{false};
inline std::atomic<int> current_phase{None};
inline std::atomic<int> target_abort{None};
inline std::atomic<int> observed_abort{None};
inline int pending_ordinary_phase = -1;  // Accessed only under the existing model mutex.
inline std::atomic<int64_t> serial{0};
inline std::atomic<int64_t> active_serial{0};
inline std::array<std::atomic<int64_t>, 5> events{};
inline std::mutex event_mutex;
inline std::condition_variable event_changed;

inline int64_t now_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

inline bool valid_winner(const llama_token_data_array & data) {
    return data.selected >= 0 && static_cast<size_t>(data.selected) < data.size &&
           std::isfinite(data.data[data.selected].logit);
}

inline void publish(int phase) {
    const int64_t call = active_serial.load();
    if (call == 0 || phase < Before || phase > TokenNative || events[phase].load() == call) return;
    {
        std::lock_guard<std::mutex> lock(event_mutex);
        events[phase].store(call);
    }
    event_changed.notify_all();
}

inline void request_target_abort(int phase, std::atomic<bool> & abort) {
    if (enabled.load() && target_abort.load() == phase) {
        observed_abort.store(phase);
        abort.store(true);
    }
}

inline void phase(int phase, std::atomic<bool> & abort) {
    current_phase.store(phase);
    publish(phase);
    request_target_abort(phase, abort);
}

inline void set_native_phase(int phase) { current_phase.store(phase); }

inline void native_callback(std::atomic<bool> & abort) {
    const int phase = current_phase.load();
    if (phase == PromptNative || phase == TokenNative) {
        publish(phase);
        request_target_abort(phase, abort);
    }
}

class CallLifetime {
    const int64_t started = now_ns();
public:
    CallLifetime() {
        const int64_t call = serial.fetch_add(1) + 1;
        active_serial.store(call);
        if (enabled.load()) observation.serial = call;
    }
    ~CallLifetime() {
        if (enabled.load()) observation.elapsed_ns = now_ns() - started;
        {
            std::lock_guard<std::mutex> lock(event_mutex);
            active_serial.store(0);
            current_phase.store(None);
        }
        event_changed.notify_all();
    }
};

class Session {
public:
    Session(int mode, int abort_phase) {
        observation = Observation{};
        observation.mode = mode;
        observed_abort.store(None);
        target_abort.store(abort_phase);
        enabled.store(true);
    }
    ~Session() {
        enabled.store(false);
        target_abort.store(None);
    }
};

class SamplerObservation {
    common_sampler * sampler;
public:
    explicit SamplerObservation(common_sampler * sampler) : sampler(sampler) {
        if (enabled.load()) {
            observation.chain = common_sampler_print(sampler);
            observation.seed = common_sampler_get_seed(sampler);
        }
    }
    ~SamplerObservation() {
        if (!enabled.load()) return;
        const auto perf = llama_perf_sampler(common_sampler_get(sampler));
        observation.sampling_ns = static_cast<int64_t>(std::llround(perf.t_sample_ms * 1000000.0));
        observation.accepted = perf.n_sample;
        if (perf.n_sample > 0) observation.last_accepted = common_sampler_last(sampler);
    }
};

inline std::array<int64_t, 4> await_native(int64_t after, int phase, int64_t timeout_ms) {
    if ((phase != PromptNative && phase != TokenNative) || timeout_ms < 1 || timeout_ms > 10000) {
        return {0, phase, active_serial.load(), 1};
    }
    std::unique_lock<std::mutex> lock(event_mutex);
    const bool reached = event_changed.wait_for(lock, std::chrono::milliseconds(timeout_ms), [&] {
        return events[phase].load() > after;
    });
    return {reached ? events[phase].load() : 0, phase, active_serial.load(), reached ? 0 : 1};
}

}  // namespace sampler_study
}  // namespace
