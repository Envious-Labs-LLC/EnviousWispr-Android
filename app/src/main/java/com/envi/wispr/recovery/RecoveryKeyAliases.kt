package com.envi.wispr.recovery

import java.util.Collections
import java.util.Enumeration

/** Names and bounded positive evidence only. An ended enumeration is never a healthy negative census. */
internal object RecoveryKeyAliases {
    private const val NAMESPACE = "enviouswispr.recovery."
    private const val OBSERVATIONS = 256
    private val maximumNameUnits = NAMESPACE.length + "v1.".length + 36 + 1 + Long.MAX_VALUE.toString().length + 1 + 36

    internal enum class Scan { ENDED, CAPPED, FAILED }

    internal class Observation(names: Set<String>, val oversizedRecoveryAlias: Boolean, val scan: Scan) {
        val names: Set<String> = Collections.unmodifiableSet(HashSet(names))
    }

    fun of(ref: RecoveryRecordRef): String = "$NAMESPACE" + "v1.${ref.storeId}.${ref.identity.generation}.${ref.identity.takeId}"

    fun failed(): Observation = Observation(emptySet(), false, Scan.FAILED)

    fun observe(aliases: Enumeration<String>): Observation {
        val names = HashSet<String>()
        var oversized = false
        var count = 0
        var scan = Scan.ENDED
        try {
            while (aliases.hasMoreElements()) {
                if (count++ == OBSERVATIONS) {
                    scan = Scan.CAPPED
                    break
                }
                val name = aliases.nextElement()
                if (name.startsWith(NAMESPACE)) {
                    // A name longer than any current opaque identity is already unexpected.
                    // Preserve that positive fact without retaining an arbitrarily large body.
                    if (name.length > maximumNameUnits) oversized = true else names.add(name)
                }
            }
        } catch (_: Throwable) {
            scan = Scan.FAILED
        }
        return Observation(names, oversized, scan)
    }
}
