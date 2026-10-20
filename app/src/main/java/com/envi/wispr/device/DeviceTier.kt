package com.envi.wispr.device

import com.envi.wispr.polish.CpuFeatures
import com.envi.wispr.polish.HWCAP2_I8MM
import java.io.File

/**
 * Whether EnviousWispr supports this phone, and how well (#449 chunk 2, founder 2026-10-19/20). Decided once per
 * process from the processor's own report; there is no override and no setting.
 *
 * - [BLOCKED]: every core is an old design ([OLD_CORES]). Measured on Test Lab 2026-10-19/20: a 2.6 s sentence took
 *   7.4 to 8 s to become text on a Galaxy A05s and a Moto g04. The app shows one screen, starts no dictation and
 *   downloads no model.
 * - [MID]: not blocked, and the processor lacks I8MM (no Armv8.6+/Armv9 cores). Everything works; the user is told
 *   once that dictation and on-device polish may be slow.
 * - [STANDARD]: everything else, including any phone whose report cannot be read or is incomplete. Unknown never
 *   blocks: a wrong block is worse than a slow phone.
 *
 * Plan: `docs/feature-requests/issue-449-2026-10-20-low-end-block-and-notice.md`. Lifting the block for a tier is
 * #453's job, with evidence, never an edit here alone.
 */
internal enum class DeviceTier {
    BLOCKED, MID, STANDARD;

    companion object {
        /** This process's answer. A `val`: the cores and features cannot change while the process lives. */
        val current: DeviceTier by lazy {
            classify(
                possibleCpus = readOrNull("/sys/devices/system/cpu/possible"),
                cpuinfo = readOrNull("/proc/cpuinfo"),
                hasI8mm = CpuFeatures.read()?.let { it.hwcap2 and HWCAP2_I8MM != 0L },
            )
        }

        /**
         * Pure, so every rule is tested on the measured phones' own text. [hasI8mm] null means the features could
         * not be read: no notice.
         */
        internal fun classify(possibleCpus: String?, cpuinfo: String?, hasI8mm: Boolean?): DeviceTier {
            val cores = completeCores(possibleCpus, cpuinfo)
            if (cores != null && cores.all { it in OLD_CORES }) return BLOCKED
            return if (hasI8mm == false) MID else STANDARD
        }

        /**
         * One (implementer, part) per possible CPU, or null unless the identification is complete: a nonempty set of
         * possible CPU ids, exactly one `processor` record for each, no duplicate or extra ids, and a valid implementer
         * and part in every record. Linux lists online processors only, so a phone with a core offline reads as
         * incomplete here and is never blocked on a partial view.
         */
        internal fun completeCores(possibleCpus: String?, cpuinfo: String?): List<Core>? {
            val possible = parseCpuList(possibleCpus ?: return null) ?: return null
            if (possible.isEmpty()) return null
            val records = mutableMapOf<Int, MutableMap<String, String>>()
            var current: MutableMap<String, String>? = null
            for (line in (cpuinfo ?: return null).lineSequence()) {
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (key == "processor") {
                    val id = value.toIntOrNull() ?: return null
                    if (id in records) return null
                    current = mutableMapOf<String, String>().also { records[id] = it }
                } else {
                    current?.put(key, value)
                }
            }
            if (records.keys != possible) return null
            return records.values.map { record ->
                val implementer = record["CPU implementer"]?.let(::parseHex) ?: return null
                val part = record["CPU part"]?.let(::parseHex) ?: return null
                Core(implementer, part)
            }
        }

        /** `0-3,4-7` or `0,2` style lists, as the kernel writes `cpu/possible`. Null on anything malformed. */
        internal fun parseCpuList(text: String): Set<Int>? {
            val ids = mutableSetOf<Int>()
            for (range in text.trim().split(',')) {
                if (range.isEmpty()) return null
                val bounds = range.split('-')
                val start = bounds[0].toIntOrNull() ?: return null
                val end = if (bounds.size == 2) bounds[1].toIntOrNull() ?: return null else if (bounds.size == 1) start else return null
                if (end < start) return null
                (start..end).forEach { ids += it }
            }
            return ids
        }

        private fun parseHex(text: String): Int? = text.removePrefix("0x").removePrefix("0X").toIntOrNull(16)

        private fun readOrNull(path: String): String? = try { File(path).readText() } catch (error: Exception) { null }

        /**
         * Old core designs, by (implementer, part), a conservative support-policy list, not every design older than
         * Cortex-A76 (A57 and A72 stay allowed pending evidence). Arm: A35, A53, A55, A73, A75. Qualcomm Kryo built on
         * them: 0x800 (A73), 0x801 (A53), 0x802 (A75), 0x803 (A55), 0x805 (A55); 0x804 is A76-class and is not here.
         * Mapping per pytorch/cpuinfo `src/arm/uarch.c`.
         */
        internal val OLD_CORES: Set<Core> = setOf(
            Core(ARM, 0xd04), Core(ARM, 0xd03), Core(ARM, 0xd05), Core(ARM, 0xd09), Core(ARM, 0xd0a),
            Core(QUALCOMM, 0x800), Core(QUALCOMM, 0x801), Core(QUALCOMM, 0x802), Core(QUALCOMM, 0x803), Core(QUALCOMM, 0x805),
        )

        private const val ARM = 0x41
        private const val QUALCOMM = 0x51
    }

    internal data class Core(val implementer: Int, val part: Int)
}
