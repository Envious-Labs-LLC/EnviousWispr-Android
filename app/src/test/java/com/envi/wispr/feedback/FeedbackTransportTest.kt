package com.envi.wispr.feedback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Product Outcome: cancelling a send after headers cannot let the next report ignore the server's hold or lose the first report. */
class FeedbackTransportTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun cancellationAfterHeadersKeepsMutexUntilResponseSettlementFinishes() = runBlocking {
        withTimeout(10_000) {
            val store = FeedbackStore(File(temp.root, "state.json"))
            val report = FeedbackRecord("a".repeat(32), 1000, "report", null, null, null,
                FeedbackContext("0.1.0", "1", "development", "16", "build", "phone"))
            store.admit(FeedbackDraft(1, "report", ""), report)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .header("X-Sentry-Rate-Limits", "120:feedback:organization").body("".toResponseBody()).build()
            }.build()
            val destination = FeedbackDestination.parse("https://abc123@${FeedbackDestination.HOST}/${FeedbackDestination.PROJECT}")!!
            val received = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val secondWaiting = CompletableDeferred<Unit>()
            val secondFinished = CompletableDeferred<Unit>()
            val first = launch {
                FeedbackDelivery.mutex.withLock {
                    FeedbackSender(destination, client).send(report) { reply ->
                        received.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS)) { "Response settlement gate was never released" }
                        FeedbackDelivery.responseReceived(store, report.id, reply)
                    }
                }
            }
            try {
                received.await()
                first.cancel()
                val second = launch {
                    secondWaiting.complete(Unit)
                    FeedbackDelivery.mutex.withLock {
                        assertTrue("Second worker observed no response hold", store.read().holdUntilMs > System.currentTimeMillis())
                        assertTrue(store.read().records.isEmpty())
                        secondFinished.complete(Unit)
                    }
                }
                secondWaiting.await()
                yield() // Let the cancellation continuation reach the production terminal-callback waiter.
                assertFalse("Cancelled sender released the gate before callback settlement", first.isCompleted)
                assertFalse("Second send passed the still-active response settlement", secondFinished.isCompleted)
                release.countDown()
                first.join()
                secondFinished.await()
                second.join()
            } finally {
                release.countDown()
                first.cancelAndJoin()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
    @Test fun fixtureDeadlineRefusesAnUnsentSignal() {
        assertFalse("Missing signal was reported as arrival", CountDownLatch(1).await(1, TimeUnit.MILLISECONDS))
    }
}
