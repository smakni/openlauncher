package com.openlauncher.app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * A request/response channel to an ELM327 adapter over Bluetooth SPP.
 *
 * The ELM327 speaks a line protocol: each command is terminated with CR, and the
 * adapter answers with data followed by a '>' prompt once it is ready for the
 * next one. Reads therefore run until that prompt rather than until a newline,
 * because a single answer can span several lines on multi-frame PIDs.
 *
 * All calls block and must be made off the main thread.
 */
class Elm327Connection(context: Context) {

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var socket: BluetoothSocket? = null
    private var input:  InputStream?     = null
    private var output: OutputStream?    = null

    val isConnected: Boolean get() = socket?.isConnected == true

    /** Devices already paired from the system Bluetooth settings. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> =
        runCatching { adapter?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())

    /**
     * Opens the socket and runs the ELM327 init sequence.
     * Returns false on any failure — the caller retries rather than throwing,
     * since an unreachable dongle is the normal state with the ignition off.
     */
    @SuppressLint("MissingPermission")
    fun connect(macAddress: String): Boolean {
        close()
        val device = runCatching { adapter?.getRemoteDevice(macAddress) }.getOrNull() ?: return false

        // Discovery and an open RFCOMM socket contend for the same radio; leaving
        // it running turns connect() into a coin flip.
        runCatching { adapter?.cancelDiscovery() }

        val sock = openSocket(device) ?: return false
        socket = sock
        input  = runCatching { sock.inputStream }.getOrNull()
        output = runCatching { sock.outputStream }.getOrNull()
        if (input == null || output == null) {
            close()
            return false
        }
        return initialise()
    }

    /**
     * Standard service-record connect, falling back to the reflective channel-1
     * call. The fallback is not optional in practice: a large share of cheap
     * ELM327 clones advertise no usable SPP service record and only ever connect
     * this way.
     */
    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket? {
        tryOpen { device.createRfcommSocketToServiceRecord(SPP_UUID) }?.let { return it }

        return tryOpen {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            method.invoke(device, 1) as BluetoothSocket
        }
    }

    /**
     * Creates a socket and connects it, closing it again if the connect fails.
     * Without that cleanup a failed attempt leaks the socket, and since the
     * reconnect loop retries indefinitely the leak compounds over a drive.
     */
    private fun tryOpen(create: () -> BluetoothSocket): BluetoothSocket? {
        val candidate = runCatching(create).getOrNull() ?: return null
        return runCatching { candidate.connect() }
            .fold(onSuccess = { candidate }, onFailure = { runCatching { candidate.close() }; null })
    }

    /**
     * Echo, linefeeds, spaces and headers are all switched off so that a reply is
     * a single bare hex run — the response parser depends on that shape. ATSP0
     * leaves protocol selection to the adapter, which is what makes this work
     * across manufacturers without a per-car setting.
     */
    private fun initialise(): Boolean {
        // ATZ is a full reset and is markedly slower than the rest.
        if (send("ATZ", timeoutMs = 5000) == null) return false
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
            if (send(cmd) == null) return false
        }
        // A PID the ECU answers only once the bus is actually up, which
        // distinguishes "adapter responding" from "car reachable".
        return send(ObdPid.RPM.command) != null
    }

    /** Sends one command and returns the raw reply, or null if the link failed. */
    fun send(command: String, timeoutMs: Long = 2000): String? {
        val out = output ?: return null
        val ins = input ?: return null
        return try {
            // Drain anything left by a timed-out previous command, otherwise its
            // late reply would be parsed as the answer to this one.
            while (ins.available() > 0) ins.read(ByteArray(ins.available()))

            out.write((command + "\r").toByteArray(Charsets.US_ASCII))
            out.flush()
            readUntilPrompt(ins, timeoutMs)
        } catch (_: Exception) {
            close()
            null
        }
    }

    private fun readUntilPrompt(ins: InputStream, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val reply = StringBuilder()
        val buffer = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            if (ins.available() > 0) {
                val read = ins.read(buffer)
                if (read < 0) return null
                reply.append(String(buffer, 0, read, Charsets.US_ASCII))
                if (reply.contains(PROMPT)) return reply.toString()
            } else {
                // available() rather than a blocking read, so a dongle that goes
                // silent mid-answer times out instead of wedging the poll loop.
                Thread.sleep(4)
            }
        }
        return null
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
        input  = null
        output = null
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val PROMPT = '>'
    }
}
