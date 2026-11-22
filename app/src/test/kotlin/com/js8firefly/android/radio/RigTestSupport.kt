package com.js8firefly.android.radio

import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** Records everything the controller writes; the test plays the radio's side. */
class FakeTransport : ByteTransport {
    override var onData: ((ByteArray) -> Unit)? = null
    override var onState: ((TransportState) -> Unit)? = null
    val writes = mutableListOf<ByteArray>()
    val rts = mutableListOf<Boolean>()
    val dtr = mutableListOf<Boolean>()
    var closed = false

    override fun open() { onState?.invoke(TransportState.connected) }
    override fun close() { closed = true }
    override fun write(data: ByteArray) { writes += data }
    override fun setRTS(on: Boolean) { rts += on }
    override fun setDTR(on: Boolean) { dtr += on }

    /** The radio answers. */
    fun reply(bytes: ByteArray) { onData?.invoke(bytes) }
    fun reply(text: String) = reply(text.toByteArray(Charsets.UTF_8))

    /** Writes as text (Yaesu/Kenwood/rigctld). */
    fun text(): List<String> = writes.map { String(it, Charsets.UTF_8) }
    /** Writes as hex strings, e.g. "FE FE 94 E0 03 FD". */
    fun hex(): List<String> = writes.map { it.toHex() }
    fun clear() = writes.clear()
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

fun hex(s: String): ByteArray = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

/**
 * A RigController wired to a [FakeTransport] on a virtual clock. The controller
 * only reads Context for real transports, which the factory replaces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RigHarness {
    val scheduler = TestCoroutineScheduler()
    val fake = FakeTransport()
    val rig: RigController

    init {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        rig = RigController(ContextWrapper(null))
        rig.transportFactory = { fake }
    }

    fun connect(s: RigSettings) {
        rig.configure(s)
        scheduler.runCurrent()
    }

    /** Moves the virtual clock forward, running timers (polls, queued writes). */
    fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    fun close() {
        rig.disconnect()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }
}

fun serial(preset: RigPreset): RigSettings =
    RigSettings(connection = RigConnection.serial, serialPath = "TEST").apply(preset)
