package com.envi.wispr.polish

import com.envi.wispr.processing.ProcessingBackend
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Which local S1 runtime this phone can run (#449). Decided once per process, before any native polish code loads.
 *
 * - [GENIEX]: Qualcomm GenieX 0.4.0, graphics processor then processor. Its libraries are compiled for newer Arm
 *   processors and its plugin links the vendor OpenCL library, so it is chosen only when the processor reports
 *   every feature in [GENIEX_REQUIRED_HWCAP] / [GENIEX_REQUIRED_HWCAP2] AND `libOpenCL.so` opens for this app.
 * - [PORTABLE_CPU]: the repository's own llama.cpp build (`:llama-android`), which picks a processor variant at
 *   runtime. Same model file, prompts and sampling; processor only. Anything unknown lands here: it cannot run
 *   an instruction the phone lacks, so failing toward it is the safe direction.
 */
internal enum class S1RuntimeKind(val identity: String, val backends: Set<ProcessingBackend>) {
    GENIEX("geniex-$GENIEX_VERSION-llama_cpp", setOf(ProcessingBackend.GPU, ProcessingBackend.CPU)),
    PORTABLE_CPU("llama_cpp-portable-cpu", setOf(ProcessingBackend.CPU));

    companion object {
        /** This process's answer. A `val`: processor features cannot change while the process lives. */
        val current: S1RuntimeKind by lazy {
            decide(CpuFeatures.read(), { OpenClProbe.openError().also { openClError = it } == null })
        }

        /**
         * Why [current] is what it is, for the one local log line `PolishService` writes (#449: on one S26 the choice
         * was the portable runtime although its processor passes, and nothing said why). Library and bit names only.
         */
        val reason: String by lazy {
            val features = CpuFeatures.read()
            when {
                features == null -> "processor features unreadable"
                features.hwcap and GENIEX_REQUIRED_HWCAP != GENIEX_REQUIRED_HWCAP ->
                    "missing hwcap 0x" + java.lang.Long.toHexString(GENIEX_REQUIRED_HWCAP and features.hwcap.inv())
                features.hwcap2 and GENIEX_REQUIRED_HWCAP2 != GENIEX_REQUIRED_HWCAP2 -> "missing i8mm"
                current == PORTABLE_CPU -> "OpenCL: " + (openClError ?: "not checked")
                else -> "all checks passed"
            }
        }

        @Volatile private var openClError: String? = null

        internal fun decide(features: CpuFeatures?, openClAvailable: () -> Boolean): S1RuntimeKind = when {
            features == null -> PORTABLE_CPU
            features.hwcap and GENIEX_REQUIRED_HWCAP != GENIEX_REQUIRED_HWCAP -> PORTABLE_CPU
            features.hwcap2 and GENIEX_REQUIRED_HWCAP2 != GENIEX_REQUIRED_HWCAP2 -> PORTABLE_CPU
            !openClAvailable() -> PORTABLE_CPU
            else -> GENIEX
        }
    }
}

/**
 * The GenieX build the masks below were measured against. `S1RuntimeKindTest` fails when the Gradle dependency
 * moves without this changing, because a new GenieX build can use instructions these masks do not cover.
 * Regenerate the masks with the instruction census (`docs/feature-requests/issue-449-2026-10-19-portable-local-polish.md` §1.2, §3).
 */
internal const val GENIEX_VERSION = "0.4.0"

/**
 * Linux arm64 `AT_HWCAP` bits for every instruction family the GenieX 0.4.0 libraries on the processor and
 * graphics paths contain (#449 census): LSE atomics, half precision (scalar and vector), dot product, complex
 * multiply, RCpc and RCpc2. Its crypto code checks the processor at runtime and is not required.
 */
internal const val HWCAP_ATOMICS = 1L shl 8
internal const val HWCAP_FPHP = 1L shl 9
internal const val HWCAP_ASIMDHP = 1L shl 10
internal const val HWCAP_FCMA = 1L shl 14
internal const val HWCAP_LRCPC = 1L shl 15
internal const val HWCAP_ASIMDDP = 1L shl 20
internal const val HWCAP_ILRCPC = 1L shl 26
internal const val GENIEX_REQUIRED_HWCAP =
    HWCAP_ATOMICS or HWCAP_FPHP or HWCAP_ASIMDHP or HWCAP_FCMA or HWCAP_LRCPC or HWCAP_ASIMDDP or HWCAP_ILRCPC

/** `AT_HWCAP2` bit 13: 8-bit integer matrix multiply (I8MM), used by GenieX's processor kernels on every generation. */
internal const val HWCAP2_I8MM = 1L shl 13
internal const val GENIEX_REQUIRED_HWCAP2 = HWCAP2_I8MM

/** The processor's feature words, as the kernel hands them to this process. */
internal data class CpuFeatures(val hwcap: Long, val hwcap2: Long) {
    companion object {
        private const val AT_NULL = 0L
        private const val AT_HWCAP = 16L
        private const val AT_HWCAP2 = 26L

        /**
         * The kernel's words through `getauxval` (`cpp/capability_probe.cpp`); null when they cannot be had, which the
         * caller treats as unknown. Not `/proc/self/auxv`: a release build is not dumpable, so that file belongs to
         * root and the read fails (measured on the founder's S26, build 282, 2026-10-20, while every debug build
         * passed). The file is kept only as the fallback when the native library itself cannot load.
         */
        fun read(): CpuFeatures? {
            val native = try {
                System.loadLibrary("enviouswispr-capability")
                nativeHwcaps()
            } catch (error: Throwable) {
                null
            }
            if (native != null && native.size == 2 && native[0] != 0L) return CpuFeatures(native[0], native[1])
            return try { parse(File("/proc/self/auxv").readBytes()) } catch (error: Exception) { null }
        }

        private external fun nativeHwcaps(): LongArray?

        /** Parses the 64-bit little-endian (type, value) pairs of an arm64 auxiliary vector. */
        internal fun parse(bytes: ByteArray): CpuFeatures? {
            if (bytes.size % 16 != 0) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var hwcap: Long? = null
            var hwcap2 = 0L
            while (buffer.remaining() >= 16) {
                val type = buffer.long
                val value = buffer.long
                when (type) {
                    AT_NULL -> break
                    AT_HWCAP -> hwcap = value
                    AT_HWCAP2 -> hwcap2 = value
                }
            }
            return hwcap?.let { CpuFeatures(it, hwcap2) }
        }
    }
}

/** Whether the vendor OpenCL library opens in this app's linker namespace (see `cpp/capability_probe.cpp`). */
internal object OpenClProbe {
    /** Null when `libOpenCL.so` opens; otherwise why not (the linker's text, or the failure's class name). */
    fun openError(): String? = try {
        System.loadLibrary("enviouswispr-capability")
        nativeOpenError()
    } catch (error: Throwable) {
        error.javaClass.simpleName
    }

    private external fun nativeOpenError(): String?
}
