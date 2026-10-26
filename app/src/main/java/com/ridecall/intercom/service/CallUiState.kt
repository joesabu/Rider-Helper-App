package com.ridecall.intercom.service

sealed class CallUiState {
    object Idle : CallUiState()
    object Listening : CallUiState()
    data class Connecting(val deviceName: String) : CallUiState()
    data class Connected(val deviceName: String, val muted: Boolean, val connectedSinceMs: Long) : CallUiState()
    data class Reconnecting(val deviceName: String, val attempt: Int) : CallUiState()
    data class Error(val message: String) : CallUiState()
}
