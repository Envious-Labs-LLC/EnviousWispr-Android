package com.envi.wispr.debug

import java.io.File

/**
 * Every path the local log owns (#378), in one place so the writer, the switch owner, the export worker and
 * the adb door can never disagree about a name. All of it is app-private storage under `filesDir` and
 * `cacheDir`; nothing here is ever shared storage.
 *
 * - `flags/`: one empty file per switch, present exactly when that switch is on. Written only by main
 *   (`DeveloperSwitches`); every process reads it.
 * - `logs/<process>.log` and `<process>.1.log` to `.3.log`: one rotating set per process, written only by
 *   that process's [LogWriter].
 * - `logs/<process>.lock`: the per-process file lock (`ProcessLogLock`).
 * - `logs/fence`, `logs/fence-ack/<process>`, `logs/snapshots/<fenceId>/<process>/`: the Share fence.
 */
internal class LogFiles(filesDir: File, cacheDir: File) {
    val flagsDir = File(filesDir, "flags")
    val logsDir = File(filesDir, "logs")
    val detailedLogFlag = File(flagsDir, DETAILED_LOG)
    val keepRecordingsFlag = File(flagsDir, KEEP_RECORDINGS)
    val fence = File(logsDir, FENCE)
    val fenceAcks = File(logsDir, "fence-ack")
    val snapshots = File(logsDir, "snapshots")
    val shareDir = File(cacheDir, "share")
    val doorLogDir = File(cacheDir, "devlog-pulls/log")

    fun current(process: String) = File(logsDir, "$process.log")
    fun rotated(process: String, index: Int) = File(logsDir, "$process.$index.log")
    fun lock(process: String) = File(logsDir, "$process.lock")
    fun ack(process: String) = File(fenceAcks, process)
    fun snapshot(fenceId: String, process: String) = File(File(snapshots, fenceId), process)

    /** The current file first, then `.1` to `.[ROTATIONS]`: newest to oldest. */
    fun setOf(process: String): List<File> = listOf(current(process)) + (1..ROTATIONS).map { rotated(process, it) }

    companion object {
        const val DETAILED_LOG = "detailed-log"
        const val KEEP_RECORDINGS = "keep-recordings"
        const val FENCE = "fence"

        /** A cancellation id writers never acknowledge or pin for (startup cleanup). */
        const val CANCELLED_FENCE = "cancelled"

        /** Three rotated files beside the current one: four files of [MAX_FILE_BYTES], 10 MB per process. */
        const val ROTATIONS = 3
        const val MAX_FILE_BYTES = 2_500_000L

        /** The five processes the manifest declares, as `TelemetryConfig.processTag` names them. */
        val PROCESSES = listOf("main", "audio", "vad", "asr", "polish")
    }
}
