package com.envi.wispr.audio

import com.envi.wispr.R

/** The original macOS pairs. Waveform bars are RMS bins from each actual start/stop WAV. */
internal enum class RecordingSoundPairing(
    val storageKey: String,
    val title: String,
    val description: String,
    val startResource: Int,
    val stopResource: Int,
    val waveform: FloatArray,
) {
    DUST_MOTE("dustMote", "Dust Mote", "Soft filtered air, no tone.", R.raw.chime_dust_mote_start, R.raw.chime_dust_mote_stop, floatArrayOf(0.238f, 0.722f, 0.869f, 0.756f, 0.599f, 0.787f, 0.571f, 0.623f, 0.609f, 0.514f, 0.421f, 0.257f, 0.348f, 0.151f, 0.060f, 0.060f, 0.231f, 0.560f, 0.754f, 1.000f, 0.977f, 0.974f, 0.693f, 0.969f, 0.395f, 0.559f, 0.415f, 0.403f, 0.205f, 0.146f, 0.060f, 0.060f)),
    VELVET_HUSH("velvetHush", "Velvet Hush", "Two close tones, gentle warmth.", R.raw.chime_velvet_hush_start, R.raw.chime_velvet_hush_stop, floatArrayOf(0.090f, 0.372f, 0.747f, 1.000f, 0.924f, 0.830f, 0.733f, 0.569f, 0.417f, 0.269f, 0.096f, 0.060f, 0.105f, 0.105f, 0.060f, 0.060f, 0.086f, 0.392f, 0.767f, 0.983f, 0.942f, 0.832f, 0.707f, 0.569f, 0.421f, 0.261f, 0.100f, 0.060f, 0.103f, 0.105f, 0.063f, 0.060f)),
    MUTED_CONFIRM("mutedConfirm", "Muted Confirm", "Same pitch both ways, plain.", R.raw.chime_muted_confirm_start, R.raw.chime_muted_confirm_stop, floatArrayOf(0.060f, 0.121f, 0.285f, 0.485f, 0.690f, 0.866f, 0.979f, 1.000f, 0.943f, 0.831f, 0.682f, 0.514f, 0.346f, 0.195f, 0.080f, 0.060f, 0.060f, 0.090f, 0.196f, 0.362f, 0.534f, 0.656f, 0.854f, 0.870f, 0.832f, 0.756f, 0.562f, 0.450f, 0.297f, 0.160f, 0.073f, 0.060f)),
    WHISPER_TICK("whisperTick", "Whisper Tick", "Barely-there tick.", R.raw.chime_whisper_tick_start, R.raw.chime_whisper_tick_stop, floatArrayOf(0.348f, 0.966f, 0.972f, 0.934f, 0.996f, 0.992f, 0.934f, 0.971f, 0.949f, 0.792f, 0.662f, 0.532f, 0.343f, 0.192f, 0.088f, 0.060f, 0.422f, 0.922f, 0.942f, 0.999f, 1.000f, 0.943f, 0.944f, 0.993f, 0.944f, 0.788f, 0.657f, 0.531f, 0.353f, 0.188f, 0.083f, 0.060f)),
    ROUND_PEBBLE("roundPebble", "Round Pebble", "Rounded, no edge.", R.raw.chime_round_pebble_start, R.raw.chime_round_pebble_stop, floatArrayOf(0.060f, 0.271f, 0.573f, 0.838f, 0.958f, 0.965f, 0.970f, 0.976f, 0.983f, 0.973f, 0.881f, 0.710f, 0.500f, 0.294f, 0.126f, 0.060f, 0.060f, 0.256f, 0.577f, 0.847f, 0.950f, 0.957f, 0.982f, 1.000f, 0.981f, 0.945f, 0.860f, 0.719f, 0.520f, 0.297f, 0.124f, 0.060f)),
    PAPER_TAP("paperTap", "Paper Tap", "Soft paper-like tap.", R.raw.chime_paper_tap_start, R.raw.chime_paper_tap_stop, floatArrayOf(0.102f, 0.363f, 0.801f, 0.910f, 0.917f, 0.989f, 0.871f, 0.951f, 0.878f, 0.696f, 0.647f, 0.445f, 0.307f, 0.186f, 0.066f, 0.060f, 0.070f, 0.417f, 0.784f, 0.877f, 0.917f, 1.000f, 0.911f, 0.868f, 0.877f, 0.778f, 0.573f, 0.447f, 0.332f, 0.171f, 0.067f, 0.060f)),
    SOFT_HUSH("softHush", "Soft Hush", "Slow fade, like a breath.", R.raw.chime_soft_hush_start, R.raw.chime_soft_hush_stop, floatArrayOf(0.060f, 0.116f, 0.280f, 0.464f, 0.686f, 0.831f, 0.995f, 0.984f, 0.993f, 0.836f, 0.734f, 0.528f, 0.381f, 0.206f, 0.092f, 0.060f, 0.060f, 0.114f, 0.284f, 0.456f, 0.696f, 0.820f, 1.000f, 0.986f, 0.982f, 0.855f, 0.710f, 0.551f, 0.362f, 0.219f, 0.085f, 0.060f)),
    LOW_NOD("lowNod", "Low Nod", "Low, warm, unhurried.", R.raw.chime_low_nod_start, R.raw.chime_low_nod_stop, floatArrayOf(0.060f, 0.242f, 0.528f, 0.801f, 0.960f, 0.979f, 0.980f, 0.979f, 0.979f, 0.978f, 0.923f, 0.772f, 0.561f, 0.340f, 0.147f, 0.060f, 0.060f, 0.242f, 0.508f, 0.813f, 0.981f, 0.958f, 0.959f, 1.000f, 1.000f, 0.957f, 0.906f, 0.792f, 0.569f, 0.326f, 0.147f, 0.060f)),
    CLOUD_POP("cloudPop", "Cloud Pop", "Tiny filtered-air pop.", R.raw.chime_cloud_pop_start, R.raw.chime_cloud_pop_stop, floatArrayOf(0.905f, 1.000f, 0.834f, 0.674f, 0.558f, 0.490f, 0.414f, 0.344f, 0.279f, 0.231f, 0.201f, 0.173f, 0.129f, 0.073f, 0.060f, 0.060f, 0.963f, 0.943f, 0.790f, 0.661f, 0.531f, 0.447f, 0.360f, 0.292f, 0.240f, 0.199f, 0.162f, 0.133f, 0.105f, 0.064f, 0.060f, 0.060f)),
    VELVET_TAP("velvetTap", "Velvet Tap", "Muted, compact tap.", R.raw.chime_velvet_tap_start, R.raw.chime_velvet_tap_stop, floatArrayOf(0.984f, 0.953f, 0.817f, 0.743f, 0.565f, 0.451f, 0.411f, 0.356f, 0.268f, 0.226f, 0.200f, 0.170f, 0.125f, 0.077f, 0.060f, 0.060f, 1.000f, 0.959f, 0.832f, 0.720f, 0.608f, 0.467f, 0.400f, 0.362f, 0.302f, 0.226f, 0.197f, 0.182f, 0.145f, 0.094f, 0.060f, 0.060f)),
    SATIN_SHIFT("satinShift", "Satin Shift", "Smooth two-tone shift.", R.raw.chime_satin_shift_start, R.raw.chime_satin_shift_stop, floatArrayOf(0.394f, 1.000f, 0.980f, 0.894f, 0.904f, 0.863f, 0.727f, 0.726f, 0.756f, 0.660f, 0.645f, 0.712f, 0.605f, 0.401f, 0.209f, 0.060f, 0.434f, 0.925f, 0.931f, 0.921f, 0.889f, 0.849f, 0.818f, 0.800f, 0.807f, 0.842f, 0.886f, 0.919f, 0.885f, 0.642f, 0.313f, 0.068f)),
    AIR_GLINT("airGlint", "Air Glint", "Clean, airy glint.", R.raw.chime_air_glint_start, R.raw.chime_air_glint_stop, floatArrayOf(0.669f, 0.995f, 0.970f, 0.913f, 0.864f, 0.819f, 0.771f, 0.752f, 0.697f, 0.670f, 0.637f, 0.606f, 0.507f, 0.322f, 0.149f, 0.060f, 0.681f, 1.000f, 0.942f, 0.887f, 0.841f, 0.796f, 0.761f, 0.714f, 0.678f, 0.647f, 0.610f, 0.575f, 0.503f, 0.332f, 0.153f, 0.060f));

    companion object {
        val DEFAULT = WHISPER_TICK
        fun fromStorage(value: String?): RecordingSoundPairing = entries.firstOrNull { it.storageKey == value } ?: DEFAULT
    }
}

internal enum class RecordingSoundMoment { START, STOP }
