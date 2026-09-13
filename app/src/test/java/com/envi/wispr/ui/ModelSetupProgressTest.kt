package com.envi.wispr.ui

import androidx.work.WorkInfo
import com.envi.wispr.models.ModelHealth
import com.envi.wispr.models.ModelUiState
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Product Outcome: live download progress is visible even when an older verification is blocked. */
class ModelSetupProgressTest {
    @Test fun aNewTransferPublishesProgressBeforeAnOlderVerificationReturns() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sawProgress = CountDownLatch(1)
        val old = WorkInfo(UUID.randomUUID(), WorkInfo.State.SUCCEEDED, emptySet())
        val current = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet())
        val work = MutableStateFlow<WorkInfo?>(old)
        try {
            val observation = observeSetupModelProgress(work, MutableStateFlow(0), scope,
                verify = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); true },
                project = { info, ready -> ModelUiState(info?.state?.name.orEmpty(), if (ready) ModelHealth.READY else ModelHealth.NOT_READY) })
            scope.launch { observation.collect { if (it.label == "RUNNING" && it.health != ModelHealth.READY) sawProgress.countDown() } }
            assertTrue("The older verification must be blocked", entered.await(5, TimeUnit.SECONDS))
            work.value = current
            assertTrue("Progress must arrive while verification is still blocked", sawProgress.await(2, TimeUnit.SECONDS))
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }
    @Test fun explicitRefreshInvalidatesReadyBeforeItsNewVerificationFinishes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstReady = CountDownLatch(1)
        val rechecking = CountDownLatch(1)
        val notReady = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val refresh = MutableStateFlow(0)
        val work = MutableStateFlow<WorkInfo?>(WorkInfo(UUID.randomUUID(), WorkInfo.State.SUCCEEDED, emptySet()))
        try {
            val observation = observeSetupModelProgress(work, refresh, scope,
                verify = {
                    if (calls.incrementAndGet() == 1) true else {
                        rechecking.countDown(); check(release.await(10, TimeUnit.SECONDS)); false
                    }
                },
                project = { _, ready -> ModelUiState("Model", if (ready) ModelHealth.READY else ModelHealth.NOT_READY) })
            scope.launch { observation.collect {
                if (it.health == ModelHealth.READY) firstReady.countDown()
                else if (refresh.value == 1) notReady.countDown()
            } }
            assertTrue(firstReady.await(5, TimeUnit.SECONDS))
            refresh.value = 1
            assertTrue("Refresh must trigger another verification", rechecking.await(5, TimeUnit.SECONDS))
            assertTrue("Old Ready must be invalidated while rechecking", notReady.await(2, TimeUnit.SECONDS))
            assertEquals(1L, release.count)
        } finally { release.countDown(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

}
