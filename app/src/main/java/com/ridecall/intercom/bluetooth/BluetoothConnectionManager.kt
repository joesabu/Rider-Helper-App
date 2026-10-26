package com.ridecall.intercom.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.UUID

/**
 * Owns the RFCOMM accept-loop (server role) and client connect attempts for a single
 * active call at a time. Every running instance of the app passively listens; whoever
 * taps Connect on a bonded device becomes the client for that session.
 */
class BluetoothConnectionManager(
    private val context: Context,
    private val onEvent: (ConnectionEvent) -> Unit
) {

    companion object {
        private const val TAG = "BtConnManager"
        private const val APP_NAME = "RideCallIntercom"
        private val APP_UUID: UUID = UUID.fromString("8ab8f7d0-28f8-4a3b-9f61-6f6a3a5a2b1e")
    }

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    @Volatile private var acceptThread: AcceptThread? = null
    @Volatile private var connectThread: ConnectThread? = null
    @Volatile private var activeSocket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        return try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (acceptThread?.isAlive == true) return
        val a = adapter ?: run {
            onEvent(ConnectionEvent.Error("Bluetooth not available"))
            return
        }
        try {
            if (!a.isEnabled) {
                onEvent(ConnectionEvent.Error("Bluetooth is off"))
                return
            }
            acceptThread = AcceptThread(a).also { it.start() }
        } catch (e: SecurityException) {
            onEvent(ConnectionEvent.Error("Missing Bluetooth permission"))
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        connectThread?.cancel()
        onEvent(ConnectionEvent.Connecting(device.safeName()))
        connectThread = ConnectThread(device).also { it.start() }
    }

    fun stop() {
        acceptThread?.cancel()
        acceptThread = null
        connectThread?.cancel()
        connectThread = null
        activeSocket?.let { try { it.close() } catch (_: IOException) {} }
        activeSocket = null
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeName(): String = try { name ?: address } catch (_: SecurityException) { address }

    private inner class AcceptThread(adapter: BluetoothAdapter) : Thread("BT-Accept") {

        private val serverSocket: BluetoothServerSocket? = try {
            adapter.listenUsingRfcommWithServiceRecord(APP_NAME, APP_UUID)
        } catch (e: Exception) {
            Log.e(TAG, "listen failed", e)
            null
        }

        @SuppressLint("MissingPermission")
        override fun run() {
            if (serverSocket == null) {
                onEvent(ConnectionEvent.Error("Could not start listening"))
                return
            }
            onEvent(ConnectionEvent.Listening)
            val socket: BluetoothSocket? = try {
                serverSocket.accept()
            } catch (e: IOException) {
                null
            }
            if (socket != null) {
                activeSocket = socket
                onEvent(ConnectionEvent.Connected(socket, ConnectionRole.SERVER, socket.remoteDevice.safeName()))
                try { serverSocket.close() } catch (_: IOException) {}
            }
        }

        fun cancel() {
            try { serverSocket?.close() } catch (_: IOException) {}
            interrupt()
        }
    }

    private inner class ConnectThread(private val device: BluetoothDevice) : Thread("BT-Connect") {

        @SuppressLint("MissingPermission")
        override fun run() {
            try {
                val socket = try {
                    device.createRfcommSocketToServiceRecord(APP_UUID)
                } catch (e: IOException) {
                    onEvent(ConnectionEvent.Error("Could not create connection: ${e.message}"))
                    return
                }
                try {
                    socket.connect()
                } catch (e: IOException) {
                    try { socket.close() } catch (_: IOException) {}
                    onEvent(ConnectionEvent.Error("Connection failed: ${e.message}"))
                    return
                }
                activeSocket = socket
                onEvent(ConnectionEvent.Connected(socket, ConnectionRole.CLIENT, device.safeName()))
            } catch (e: SecurityException) {
                onEvent(ConnectionEvent.Error("Missing Bluetooth permission"))
            }
        }

        fun cancel() {
            interrupt()
        }
    }
}
