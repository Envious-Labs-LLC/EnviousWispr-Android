package com.envi.wispr.polish

import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product Outcome: when this fails, a wedged local model keeps a dictation in Processing forever, or a
 * finished polish is thrown away because a late timer fired, or the engine ends its process under a healthy
 * request because a late worker could not tell the timer had already won.
 */
class EngineDeadlineTest {

    /**
     * A scheduler the test advances by hand, so both orders of the race can be played. A cancelled timer is
     * NOT removed: a real scheduler can already be running the command when `cancel(false)` arrives, so the
     * handle's own state, not the future's cancellation, must be what stops a late timer.
     */
    private class ManualScheduler : ScheduledExecutorService by unsupported() {
        val queue = mutableListOf<Pair<Long, Runnable>>()
        var cancelled = 0

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            val entry = delay to command
            queue += entry
            return object : ScheduledFuture<Any?> {
                override fun cancel(mayInterruptIfRunning: Boolean): Boolean { cancelled++; return true }
                override fun isCancelled() = false
                override fun isDone() = false
                override fun get(): Any? = null
                override fun get(timeout: Long, unit: TimeUnit): Any? = null
                override fun getDelay(unit: TimeUnit) = delay
                override fun compareTo(other: Delayed) = 0
            }
        }

        fun fireAll() { val due = queue.toList(); queue.clear(); due.forEach { it.second.run() } }
    }

    private fun deadline(): Pair<EngineDeadline, ManualScheduler> { val s = ManualScheduler(); return EngineDeadline(s) to s }

    @Test fun expiryRunsTheActionWhenTheTimerWins() {
        val (deadline, scheduler) = deadline()
        val fired = AtomicInteger()
        val handle = deadline.arm(12_000) { fired.incrementAndGet() }
        scheduler.fireAll()
        assertEquals(1, fired.get())
        assertEquals(EngineDeadline.State.EXPIRED, handle.current)
        assertFalse("a worker finishing late cannot cancel an expiry", handle.cancel())
    }

    @Test fun aWorkerFinishingFirstCancelsTheTimerAndTheActionNeverRuns() {
        val (deadline, scheduler) = deadline()
        val fired = AtomicInteger()
        val handle = deadline.arm(12_000) { fired.incrementAndGet() }
        assertTrue(handle.cancel())
        assertEquals(EngineDeadline.State.CANCELLED, handle.current)
        scheduler.fireAll()
        assertEquals("a cancelled timer that still fires must do nothing", 0, fired.get())
        assertFalse("cancel is not repeatable", handle.cancel())
    }

    @Test fun aWinningExpiryPoisonsThenDeliversThenSchedulesTheExit() {
        val entry = PolishRequestRegistry().register(1L)!!
        val order = mutableListOf<String>()
        assertTrue(expireOnce(entry, poison = { order += "poison" }, deliver = { order += "deliver" }, scheduleExit = { order += "exit" }))
        assertEquals(listOf("poison", "deliver", "exit"), order)
        assertFalse("the worker's later delivery is refused", entry.deliverOnce { })
    }

    @Test fun aLosingExpiryDoesNothing() {
        // The worker delivered first; the timer fires late and must not poison, deliver, or exit.
        val (deadline, scheduler) = deadline()
        val entry = PolishRequestRegistry().register(2L)!!
        val order = mutableListOf<String>()
        deadline.arm(12_000) { expireOnce(entry, { order += "poison" }, { order += "deliver" }, { order += "exit" }) }
        assertTrue("the worker delivered first", entry.deliverOnce { })
        scheduler.fireAll()
        assertEquals(emptyList<String>(), order)
    }

    @Test fun aThrowingDeliveryStillCountsAsWonAndStillExits() {
        val entry = PolishRequestRegistry().register(3L)!!
        val exits = AtomicInteger()
        assertTrue(expireOnce(entry, poison = { }, deliver = { throw IllegalStateException("client gone") }, scheduleExit = { exits.incrementAndGet() }))
        assertEquals(1, exits.get())
    }

    @Test fun activeLocalWorkKeepsOnDestroyFromCancellingItsOnlyHardDeadline() {
        assertTrue(mustKillEngineOnDestroy(poisoned = false, activeLocalRequests = 1))
        assertTrue(mustKillEngineOnDestroy(poisoned = true, activeLocalRequests = 0))
        assertFalse(mustKillEngineOnDestroy(poisoned = false, activeLocalRequests = 0))
    }

    @Test fun overrideBoundsAreExact() {
        assertEquals(LocalPolishBudget.SHIPPED, LocalPolishBudget.fromOverride(null))
        assertEquals(LocalPolishBudget.SHIPPED, LocalPolishBudget.fromOverride(""))
        assertEquals(LocalPolishBudget.SHIPPED, LocalPolishBudget.fromOverride("abc"))
        assertEquals(LocalPolishBudget.SHIPPED, LocalPolishBudget.fromOverride("0"))
        assertEquals(LocalPolishBudget.SHIPPED, LocalPolishBudget.fromOverride("-5"))
        assertEquals(LocalPolishBudget.SHIPPED, LocalPolishBudget.fromOverride("60001"))
        assertEquals(LocalPolishBudget(1, 2_001), LocalPolishBudget.fromOverride("1"))
        assertEquals(LocalPolishBudget(20_000, 22_000), LocalPolishBudget.fromOverride(" 20000\n"))
        assertEquals(LocalPolishBudget(60_000, 62_000), LocalPolishBudget.fromOverride("60000"))
        assertNull("no shipped value is inside the override range by accident", listOf(LocalPolishBudget.COOPERATIVE_MS).firstOrNull { it > LocalPolishBudget.MAX_OVERRIDE_MS })
        assertTrue(LocalPolishBudget.COOPERATIVE_MS < LocalPolishBudget.HARD_MS)
    }
}

private inline fun <reified T> unsupported(): T = java.lang.reflect.Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> throw UnsupportedOperationException(method.name) } as T
