package com.envi.wispr.asr

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Process
import java.util.concurrent.Executors

/** Disposable same-UID test client. No model, recording, provider, exported control or production use. */
class AsrCancellationClientProbe : Service() {
    private data class Request(val path: String, val take: String)
    private sealed interface Command {
        data object Empty : Command
        data class Pending(val request: Request) : Command
        data object Issued : Command
    }
    private enum class Binding { NOT_ATTEMPTED, ATTEMPTED, CONFIRMED }
    private val lock = Any()
    private var command: Command = Command.Empty
    private var peer: IAsrService? = null
    private var binding = Binding.NOT_ATTEMPTED
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "AsrProbeClient").apply { isDaemon = true } }
    private val callback = object : IAsrCallback.Stub() {
        override fun onResult(text: String?) { }
        override fun onError(message: String?) { }
        override fun onFailure(reason: Int, detail: String?) { }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(lock) { peer = IAsrService.Stub.asInterface(binder) }
            issueIfReady()
        }
        override fun onServiceDisconnected(name: ComponentName?) { synchronized(lock) { peer = null } }
    }
    private val control = object : IAsrCancellationProbe.Stub() {
        private fun sameUid() { check(Binder.getCallingUid() == Process.myUid()) { "Same UID required" } }
        override fun pid(): Int { sameUid(); return Process.myPid() }
        override fun start(audioFilePath: String, takeId: String) {
            sameUid()
            synchronized(lock) {
                check(command == Command.Empty) { "Probe already owns a request" }
                command = Command.Pending(Request(audioFilePath, takeId))
            }
            issueIfReady()
        }
    }
    private fun issueIfReady() {
        val issued = synchronized(lock) {
            val service = peer ?: return
            val pending = command as? Command.Pending ?: return
            command = Command.Issued
            service to pending.request
        }
        worker.execute { issued.first.transcribeFileForTake(issued.second.path, issued.second.take, callback) }
    }
    override fun onCreate() {
        super.onCreate()
        binding = Binding.ATTEMPTED
        try {
            if (bindService(Intent(this, AsrService::class.java), connection, Context.BIND_AUTO_CREATE)) binding = Binding.CONFIRMED
            else stopSelf()
        } catch (failure: Throwable) {
            runCatching { unbindService(connection) }
            worker.shutdown()
            throw failure
        }
    }
    override fun onBind(intent: Intent?): IBinder = control
    override fun onDestroy() {
        try {
            when (binding) {
                Binding.NOT_ATTEMPTED -> Unit
                Binding.ATTEMPTED -> try { unbindService(connection) } catch (_: IllegalArgumentException) { }
                Binding.CONFIRMED -> unbindService(connection)
            }
        } finally { worker.shutdown(); super.onDestroy() }
    }
}
