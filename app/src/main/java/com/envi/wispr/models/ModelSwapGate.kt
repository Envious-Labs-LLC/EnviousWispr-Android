package com.envi.wispr.models

/**
 * #108 plan §17.4, §18.2, §19.2: the main process's one gate between engine use and model-folder writes.
 *
 * Engine use is a take (admitted atomically with the session's IDLE to STARTING step, and released at its end) or an
 * engine binding (warm-up, qualification, the speech keep-alive, a take's own binds). A swap closes the gate only when
 * no take holds a permit, and while it is closed every new take is refused and every new engine bind is refused. The
 * gate starts closed and opens once start-up recovery (#492) has settled the folders, so no engine reads a model
 * folder that recovery is still moving.
 *
 * Process-local by design: one process (main) owns every take, every engine binding and every folder write; after
 * that process dies the gate is rebuilt closed and start-up recovery runs again.
 */
internal class ModelSwapGate {
    enum class Admission { ADMITTED, UPDATING, NOT_IDLE }

    private var recovered = false
    private var swapping = false
    private var takes = 0

    val open: Boolean @Synchronized get() = recovered && !swapping

    /** Whether a swap could close the gate now: recovered, no swap, no take. A hint for waiting; [beginSwap] decides. */
    val idle: Boolean @Synchronized get() = recovered && !swapping && takes == 0

    @Synchronized fun recovered() { recovered = true }

    /**
     * [transition] is the owner's IDLE to STARTING step, run under the gate's lock: a swap can never close between the
     * check and the start, and a refused start never touches the owner's state.
     */
    @Synchronized fun admitTake(transition: () -> Boolean): Admission {
        if (!recovered || swapping) return Admission.UPDATING
        if (!transition()) return Admission.NOT_IDLE
        takes++
        return Admission.ADMITTED
    }

    /** At the take's terminal step, after its engine work and bindings ended. The owner calls it once per admission. */
    @Synchronized fun releaseTake() { if (takes > 0) takes-- }

    /** Runs [bind] only while the gate is open, under its lock, so no bind slips in after a swap closed it. */
    @Synchronized fun <T> ifOpen(bind: () -> T): T? = if (recovered && !swapping) bind() else null

    /** Closes for a swap only when recovered, idle and not already swapping. */
    @Synchronized fun beginSwap(): Boolean {
        if (!recovered || swapping || takes > 0) return false
        swapping = true
        return true
    }

    @Synchronized fun endSwap() { swapping = false }

    companion object {
        /** The main process's gate. */
        val main = ModelSwapGate()
    }
}
