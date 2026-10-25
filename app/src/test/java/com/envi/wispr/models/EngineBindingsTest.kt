package com.envi.wispr.models

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product outcome (#108 review rounds 2 and 3). When this fails, a swap renames model folders while an engine service
 * that was still connecting starts and loads them, or one dead connection blocks every later model update forever.
 */
class EngineBindingsTest {
    private class FakeContext : ContextWrapper(null) {
        val bound = mutableListOf<ServiceConnection>()
        val unbound = mutableListOf<ServiceConnection>()
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean { bound += conn; return true }
        override fun unbindService(conn: ServiceConnection) { unbound += conn }
    }

    private class Owner : ServiceConnection {
        var events = mutableListOf<String>()
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { events += "connected" }
        override fun onServiceDisconnected(name: ComponentName?) { events += "disconnected" }
        override fun onNullBinding(name: ComponentName?) { events += "null" }
    }

    private val context = FakeContext()
    private val owner = Owner()

    init { ModelSwapGate.main.recovered() }

    @After fun tearDown() { EngineBindings.revokeAll { it.run() } }

    /** REVERT: unbind a still-connecting record at once; the swap then proceeds while the service may be created. */
    @Test fun aBindReleasedBeforeItConnectsHoldsOffTheSwapUntilItArrives() {
        assertTrue(EngineBindings.bind(context, EngineBindings.Engine.SPEECH, owner, 0))
        EngineBindings.unbind(owner)
        assertTrue("not unbound before it connects", context.unbound.isEmpty())
        assertNull("the swap waits for it", EngineBindings.revokeAll { it.run() })
        context.bound.single().onServiceConnected(null, Binder())
        assertEquals("released on arrival", context.bound, context.unbound)
        assertEquals("the owner never hears a connection it released", emptyList<String>(), owner.events)
        assertNotNull("then the swap may run", EngineBindings.revokeAll { it.run() })
    }

    /** REVERT: forward a terminal callback without forgetting the record; every later swap is deferred forever. */
    @Test fun aConnectionThatEndsBeforeConnectingNeverBlocksASwap() {
        assertTrue(EngineBindings.bind(context, EngineBindings.Engine.POLISH, owner, 0))
        context.bound.single().onNullBinding(null)
        assertEquals(listOf("null"), owner.events)
        EngineBindings.unbind(owner)
        assertNotNull(EngineBindings.revokeAll { it.run() })
    }
}
