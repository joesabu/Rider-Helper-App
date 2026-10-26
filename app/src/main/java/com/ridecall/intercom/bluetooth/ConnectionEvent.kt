package com.ridecall.intercom.bluetooth

import android.bluetooth.BluetoothSocket

sealed class ConnectionEvent {
    object Listening : ConnectionEvent()
    data class Connecting(val deviceName: String) : ConnectionEvent()
    data class Connected(val socket: BluetoothSocket, val role: ConnectionRole, val deviceName: String) : ConnectionEvent()
    data class Disconnected(val reason: String?) : ConnectionEvent()
    data class Error(val message: String) : ConnectionEvent()
}
