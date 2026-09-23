package com.envi.wispr.ui

import com.envi.wispr.polish.PolishOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The binder stubs are transport only (#253): a speech or polish callback's values are posted to main, and the
 * owner's listener runs only when main runs the post, never on the binder thread that received them. Driven
 * through the same `PostingSpeechListener` and `PostingPolishListener` the production proxies wrap each Stub
 * around, with a recording post in place of the main `Handler`.
 */
class PipelineProxyTest {
    private val posted = CopyOnWriteArrayList<Runnable>()
    private val heard = CopyOnWriteArrayList<String>()

    private fun onForeignThread(block: () -> Unit) = Thread(block, "binder-1").apply { start(); join(10_000) }

    /** MUTATION: call the speech listener directly in `PostingSpeechListener`. */
    @Test fun speechCallbacksReachTheListenerOnlyThroughThePost() {
        val listener = object : SpeechListener {
            override fun onResult(text: String?) { heard += "result:$text on ${Thread.currentThread().name}" }
            override fun onError(message: String?) { heard += "error on ${Thread.currentThread().name}" }
            override fun onFailure(reason: Int, detail: String?) { heard += "failure:$reason on ${Thread.currentThread().name}" }
        }
        val posting = PostingSpeechListener({ posted += it }, listener)
        onForeignThread { posting.onResult("hello"); posting.onError("x"); posting.onFailure(3, "d") }
        assertTrue("nothing reached the listener on the binder thread: $heard", heard.isEmpty())
        assertEquals(3, posted.size)
        posted.forEach { it.run() }
        assertEquals(listOf("result:hello on Test worker", "error on Test worker", "failure:3 on Test worker").map { it.substringBefore(" on ") }, heard.map { it.substringBefore(" on ") })
        assertTrue("the listener ran where the post ran", heard.none { it.endsWith("binder-1") })
    }

    /** MUTATION: call the polish listener directly in `PostingPolishListener`. */
    @Test fun polishCallbacksReachTheListenerOnlyThroughThePost() {
        val listener = object : PolishListener {
            override fun onOutcome(outcome: PolishOutcome?) { heard += "outcome on ${Thread.currentThread().name}" }
            override fun onResult(text: String?, engine: String?, latencyMs: Long) { heard += "result on ${Thread.currentThread().name}" }
            override fun onError(message: String?) { heard += "error on ${Thread.currentThread().name}" }
        }
        val posting = PostingPolishListener({ posted += it }, listener)
        onForeignThread { posting.onOutcome(null); posting.onResult("t", "e", 1L); posting.onError("x") }
        assertTrue("nothing reached the listener on the binder thread: $heard", heard.isEmpty())
        assertEquals(3, posted.size)
        posted.forEach { it.run() }
        assertEquals(3, heard.size)
        assertTrue("the listener ran where the post ran", heard.none { it.endsWith("binder-1") })
    }
}
