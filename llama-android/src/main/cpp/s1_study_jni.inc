namespace {

using SamplerPointer = std::unique_ptr<common_sampler, decltype(&common_sampler_free)>;

std::vector<jint> study_contracts() {
    if (g_model == nullptr || g_context == nullptr) return {};
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    int32_t suppressed_count = 0;
    const llama_token * suppressed = llama_vocab_get_suppress_tokens(vocab, &suppressed_count);
    std::vector<llama_token> ids;
    for (const char * word : {" a", " b", " c"}) {
        const auto token = common_tokenize(g_context, word, false, true);
        if (token.size() != 1) return {};
        if (suppressed_count > 0 && std::find(suppressed, suppressed + suppressed_count, token[0]) != suppressed + suppressed_count) return {};
        ids.push_back(token[0]);
    }
    std::sort(ids.begin(), ids.end());
    if (std::unique(ids.begin(), ids.end()) != ids.end()) return {};
    const llama_token eos = llama_vocab_eos(vocab);
    if (eos < 0 || !llama_vocab_is_eog(vocab, eos)) return {};
    const float inf = std::numeric_limits<float>::infinity();
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const std::vector<std::vector<float>> cases = {
        {3, 1, 2}, {1, 3, 2}, {2, 2, 1}, {-0.0f, 0.0f, -1}, {-inf, 1, 2},
        {nan, nan, nan}, {-inf, -inf, -inf}, {1, inf, 0}, {}, {-1, 100, 2},
    };
    std::vector<jint> result = {1, suppressed_count};
    const auto row = [&](int arm, int scenario, int actual, int auxiliary) {
        result.insert(result.end(), {arm, scenario, actual, auxiliary});
    };
    for (int arm = 0; arm < 2; ++arm) {
        const auto mode = arm == 0 ? SamplerMode::Baseline : SamplerMode::LibraryGreedy;
        for (size_t scenario = 0; scenario < cases.size(); ++scenario) {
            if (arm == 0 && scenario >= 5 && scenario <= 7) continue;
            auto parameters = s1_sampling_parameters();
            parameters.seed = 7;
            if (scenario == 9) parameters.logit_bias.push_back({ids[1], -inf});
            SamplerPointer sampler(make_sampler(parameters, mode), common_sampler_free);
            if (!sampler) return {};
            std::vector<llama_token_data> data;
            for (size_t i = 0; i < cases[scenario].size(); ++i) data.push_back({ids[i], cases[scenario][i], 0});
            llama_token_data_array candidates{data.data(), data.size(), -1, false};
            if (!data.empty()) llama_sampler_apply(common_sampler_get(sampler.get()), &candidates);
            const bool valid = sampler_study::valid_winner(candidates);
            row(arm, static_cast<int>(scenario), valid ? static_cast<int>(candidates.selected) : -1, valid ? 1 : 0);
        }
        auto parameters = s1_sampling_parameters();
        parameters.seed = 7;
        SamplerPointer sampler(make_sampler(parameters, mode), common_sampler_free);
        if (!sampler) return {};
        if (suppressed_count == 0) {
            row(arm, 10, -2, 0);
        } else {
            const int count = llama_vocab_n_tokens(vocab);
            std::vector<llama_token_data> data;
            data.reserve(count);
            for (int i = 0; i < count; ++i) data.push_back({i, -10, 0});
            data[suppressed[0]].logit = 100;
            data[ids[0]].logit = 10;
            llama_token_data_array candidates{data.data(), data.size(), -1, false};
            llama_sampler_apply(common_sampler_get(sampler.get()), &candidates);
            row(arm, 10, candidates.data[candidates.selected].id == ids[0] ? 1 : 0, suppressed_count);
        }
        common_sampler_accept(sampler.get(), ids[0], true);
        common_sampler_accept(sampler.get(), ids[1], true);
        common_sampler_accept(sampler.get(), eos, true);
        row(arm, 11, llama_perf_sampler(common_sampler_get(sampler.get())).n_sample, 1);
        row(arm, 12, common_sampler_last(sampler.get()) == eos ? 1 : 0, 1);
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_envi_wispr_llama_S1SamplingExperiment_prepareOrdinary(JNIEnv *, jobject, jint phase) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (phase < 0 || phase > 4 || sampler_study::pending_ordinary_phase >= 0) return JNI_FALSE;
    sampler_study::pending_ordinary_phase = phase;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_envi_wispr_llama_S1SamplingExperiment_generate(
    JNIEnv * env, jobject, jstring system, jstring user, jint cap, jint arm, jint abort_phase) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (arm < 0 || arm > 1 || abort_phase < 0 || abort_phase > 4) {
        sampler_study::observation = sampler_study::Observation{};
        sampler_study::observed_abort.store(sampler_study::None);
        g_last_end[0] = END_NONE; g_last_end[1] = 0; g_last_end[2] = 0;
        return to_jstring(env, "ERROR: Invalid sampler study request");
    }
    sampler_study::Session session(arm, abort_phase);
    sampler_study::observation.entry = 1;
    return to_jstring(env, generate_text(from_jstring(env, system), from_jstring(env, user), cap,
        arm == 0 ? SamplerMode::Baseline : SamplerMode::LibraryGreedy));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_envi_wispr_llama_S1SamplingExperiment_metrics(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const auto & value = sampler_study::observation;
    const jlong values[] = {value.mode, value.elapsed_ns, value.sampling_ns, value.accepted, value.effective_cap,
        sampler_study::observed_abort.load(), value.serial, value.seed, value.last_accepted, value.entry};
    jlongArray result = env->NewLongArray(10);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 10, values);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_envi_wispr_llama_S1SamplingExperiment_tokens(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const auto & tokens = sampler_study::observation.tokens;
    jintArray result = env->NewIntArray(static_cast<jsize>(tokens.size()));
    if (result != nullptr && !tokens.empty()) env->SetIntArrayRegion(result, 0, static_cast<jsize>(tokens.size()), tokens.data());
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_envi_wispr_llama_S1SamplingExperiment_chain(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return to_jstring(env, sampler_study::observation.chain);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_envi_wispr_llama_S1SamplingExperiment_contracts(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    const auto values = study_contracts();
    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr && !values.empty()) env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_envi_wispr_llama_S1SamplingExperiment_serial(JNIEnv *, jobject) {
    return sampler_study::serial.load();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_envi_wispr_llama_S1SamplingExperiment_awaitNativePhase(
    JNIEnv * env, jobject, jlong after, jint phase, jlong timeout_ms) {
    const auto values = sampler_study::await_native(after, phase, timeout_ms);
    const jlong converted[] = {values[0], values[1], values[2], values[3]};
    jlongArray result = env->NewLongArray(4);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 4, converted);
    return result;
}
