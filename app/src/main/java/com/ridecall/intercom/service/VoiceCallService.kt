package com.ridecall.intercom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.ridecall.intercom.R
import com.ridecall.intercom.audio.AudioCapture
import com.ridecall.intercom.audio.AudioPlayback
import com.ridecall.intercom.audio.JitterBuffer
import com.ridecall.intercom.audio.SimpleAdpcmCodec
import com.ridecall.intercom.bluetooth.BluetoothConnectionManager
import com.ridecall.intercom.bluetooth.ConnectionEvent
import com.ridecall.intercom.bluetooth.ConnectionRole
import com.ridecall.intercom.protocol.FrameCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the Bluetooth connection, the full-duplex audio pipeline, and the persistent
 * call notification. Everything here is self-contained behind [BluetoothConnectionManager]
 * and the audio/ classes (which depend only on Context), so a future ride-tracking
 * foreground service can run as an independent sibling without touching this class.
 *
 * All state transitions are funneled onto the main thread (BluetoothConnectionManager
 * events are re-posted here, and socket I/O exceptions post back via [postToMain]) so
 * concurrent events from different background threads can't race on shared state.
 */
class VoiceCallService : LifecycleService() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "voice_call"
        private const val NOTIFICATION_ID = 1
        const val ACTION_MUTE_TOGGLE = "com.ridecall.intercom.action.MUTE_TOGGLE"
        const val ACTION_END_CALL = "com.ridecall.intercom.action.END_CALL"
        private const val MAX_BACKOFF_MS = 30_000L
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val SCO_CONNECT_TIMEOUT_MS = 2_500L
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }

    private val binder = LocalBinder()

    private val _uiState = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private lateinit var connectionManager: BluetoothConnectionManager
    private var lastRole: ConnectionRole? = null
    private var lastDeviceAddress: String? = null
    private var reconnectAttempt = 0
    private var reconnectThread: Thread? = null

    private val muted = AtomicBoolean(false)
    private var connectedSinceMs = 0L

    private var capture: AudioCapture? = null
    private var playback: AudioPlayback? = null
    private var captureThread: Thread? = null
    private var networkWriterThread: Thread? = null
    private var networkReaderThread: Thread? = null
    private var playbackThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val outgoingQueue = ArrayBlockingQueue<ByteArray>(4)
    private var currentSocket: BluetoothSocket? = null
    private val socketWriteLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundActive = false

    private lateinit var audioManager: AudioManager
    private var scoReceiverRegistered = false
    private var scoRequested = false
    private var scoConfirmedConnected = false
    private var scoReadyCallback: (() -> Unit)? = null
    private val scoTimeoutRunnable = Runnable { completeScoRouting() }
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> { scoConfirmedConnected = true; completeScoRouting() }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> { scoConfirmedConnected = false; completeScoRouting() }
            }
        }
    }

    private val _audioRoute = MutableStateFlow(AudioRoute.EARPIECE)
    val audioRoute: StateFlow<AudioRoute> = _audioRoute.asStateFlow()
    private val _availableAudioRoutes = MutableStateFlow<Set<AudioRoute>>(emptySet())
    val availableAudioRoutes: StateFlow<Set<AudioRoute>> = _availableAudioRoutes.asStateFlow()

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    _uiState.value = CallUiState.Error("Bluetooth is off")
                    teardownCall(resumeListening = false)
                }
                BluetoothAdapter.STATE_ON -> {
                    if (_uiState.value is CallUiState.Error) {
                        startListeningIfIdle()
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager = getSystemService(AudioManager::class.java)
        connectionManager = BluetoothConnectionManager(applicationContext) { event ->
            postToMain { handleConnectionEvent(event) }
        }
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        startListeningIfIdle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_MUTE_TOGGLE -> toggleMute()
            ACTION_END_CALL -> endCall()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        teardownCall(resumeListening = false)
        connectionManager.stop()
        try { unregisterReceiver(btStateReceiver) } catch (_: IllegalArgumentException) {}
        super.onDestroy()
    }

    // ---- Public API for the bound Activity ----

    fun bondedDevices() = connectionManager.bondedDevices()

    fun connectToDevice(device: BluetoothDevice) {
        reconnectAttempt = 0
        lastDeviceAddress = device.address
        connectionManager.connect(device)
    }

    fun toggleMute() {
        muted.set(!muted.get())
        refreshConnectedState()
        updateNotification()
    }

    fun isMuted() = muted.get()

    fun endCall() {
        sendHangupSignal()
        performLocalHangup()
    }

    /** Called when the peer sends an explicit hangup frame, as opposed to the link just dropping. */
    private fun onPeerHangup() {
        if (currentSocket == null) return
        performLocalHangup()
    }

    private fun performLocalHangup() {
        reconnectThread?.interrupt()
        lastRole = null
        lastDeviceAddress = null
        teardownCall(resumeListening = true)
    }

    // ---- Connection events (always on the main thread) ----

    private fun handleConnectionEvent(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.Listening -> {
                if (currentSocket == null) _uiState.value = CallUiState.Listening
            }
            is ConnectionEvent.Connecting -> {
                _uiState.value = CallUiState.Connecting(event.deviceName)
            }
            is ConnectionEvent.Connected -> {
                reconnectAttempt = 0
                lastRole = event.role
                lastDeviceAddress = event.socket.remoteDevice.address
                startCall(event.socket, event.deviceName)
            }
            is ConnectionEvent.Disconnected -> {
                onLinkLost()
            }
            is ConnectionEvent.Error -> {
                _uiState.value = CallUiState.Error(event.message)
            }
        }
        updateNotification()
    }

    private fun onLinkLost() {
        if (currentSocket == null) return
        teardownAudioPipeline()
        stopBluetoothScoRouting()
        currentSocket = null
        connectedSinceMs = 0L
        _uiState.value = CallUiState.Idle

        val address = lastDeviceAddress
        val role = lastRole
        if (role == ConnectionRole.CLIENT && address != null) {
            startReconnectLoop(address)
        } else {
            startListeningIfIdle()
        }
        updateNotification()
    }

    private fun startReconnectLoop(address: String) {
        reconnectThread?.interrupt()
        reconnectThread = Thread({
            var backoff = INITIAL_BACKOFF_MS
            val adapter = (applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            while (!Thread.currentThread().isInterrupted) {
                reconnectAttempt++
                postToMain {
                    _uiState.value = CallUiState.Reconnecting(address, reconnectAttempt)
                    updateNotification()
                }
                try {
                    Thread.sleep(backoff)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val device = try {
                    adapter?.getRemoteDevice(address)
                } catch (e: IllegalArgumentException) {
                    null
                }
                if (device != null) {
                    postToMain { connectionManager.connect(device) }
                    return@Thread
                }
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }, "BT-Reconnect").also { it.start() }
    }

    private fun startListeningIfIdle() {
        if (currentSocket == null) {
            connectionManager.startListening()
        }
    }

    // ---- Audio pipeline ----

    private fun startCall(socket: BluetoothSocket, deviceName: String) {
        teardownAudioPipeline()
        currentSocket = socket
        connectedSinceMs = System.currentTimeMillis()
        _uiState.value = CallUiState.Connected(deviceName, muted.get(), connectedSinceMs)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Probe for a connected Bluetooth headset before picking the initial route, so
        // capture/playback don't briefly open on the earpiece and then jump to the headset.
        scoReadyCallback = { finishRouteSetupAndStartAudio(socket) }
        ensureScoActive()
        mainHandler.postDelayed(scoTimeoutRunnable, SCO_CONNECT_TIMEOUT_MS)
    }

    private fun completeScoRouting() {
        mainHandler.removeCallbacks(scoTimeoutRunnable)
        val callback = scoReadyCallback
        scoReadyCallback = null
        callback?.invoke()
    }

    private fun finishRouteSetupAndStartAudio(socket: BluetoothSocket) {
        val routes = computeAvailableRoutes()
        _availableAudioRoutes.value = routes
        val initial = when {
            scoConfirmedConnected && AudioRoute.BLUETOOTH in routes -> AudioRoute.BLUETOOTH
            AudioRoute.EARPIECE in routes -> AudioRoute.EARPIECE
            else -> AudioRoute.SPEAKER
        }
        applyAudioRoute(initial)
        startAudioPipeline(socket)
    }

    private fun computeAvailableRoutes(): Set<AudioRoute> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val routes = mutableSetOf(AudioRoute.SPEAKER)
        if (devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }) routes.add(AudioRoute.EARPIECE)
        if (devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) routes.add(AudioRoute.BLUETOOTH)
        return routes
    }

    /** Called from the bound Activity when the rider picks a route from the dropdown. */
    fun selectAudioRoute(route: AudioRoute) {
        if (currentSocket == null || route !in _availableAudioRoutes.value) return
        applyAudioRoute(route)
    }

    private fun applyAudioRoute(route: AudioRoute) {
        when (route) {
            AudioRoute.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                ensureScoActive()
            }
            AudioRoute.EARPIECE -> {
                ensureScoInactive()
                audioManager.isSpeakerphoneOn = false
            }
            AudioRoute.SPEAKER -> {
                ensureScoInactive()
                audioManager.isSpeakerphoneOn = true
            }
        }
        _audioRoute.value = route
    }

    private fun ensureScoActive() {
        if (scoRequested) return
        try {
            registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            scoReceiverRegistered = true
        } catch (_: IllegalStateException) {
        }
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true
        scoRequested = true
    }

    private fun ensureScoInactive() {
        if (!scoRequested) return
        if (scoReceiverRegistered) {
            try { unregisterReceiver(scoReceiver) } catch (_: IllegalArgumentException) {}
            scoReceiverRegistered = false
        }
        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = false
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        scoRequested = false
        scoConfirmedConnected = false
    }

    private fun stopBluetoothScoRouting() {
        mainHandler.removeCallbacks(scoTimeoutRunnable)
        scoReadyCallback = null
        ensureScoInactive()
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        _availableAudioRoutes.value = emptySet()
    }

    private fun startAudioPipeline(socket: BluetoothSocket) {
        if (currentSocket !== socket) return
        val input = socket.inputStream
        val output = socket.outputStream
        val cap = AudioCapture()
        val play = AudioPlayback()
        try {
            cap.start()
            play.start()
        } catch (e: Exception) {
            _uiState.value = CallUiState.Error("Audio init failed: ${e.message}")
            stopBluetoothScoRouting()
            try { socket.close() } catch (_: IOException) {}
            return
        }
        capture = cap
        playback = play
        running.set(true)
        outgoingQueue.clear()

        val jitterBuffer = JitterBuffer()

        networkWriterThread = Thread({ networkWriterLoop(output) }, "Net-Writer").also { it.start() }
        captureThread = Thread({ captureLoop(cap) }, "Audio-Capture").also { it.start() }
        networkReaderThread = Thread({ networkReaderLoop(input, jitterBuffer) }, "Net-Reader").also { it.start() }
        playbackThread = Thread({ playbackLoop(play, jitterBuffer) }, "Audio-Playback").also { it.start() }
    }

    /** Tells the peer this side is hanging up on purpose, so it doesn't auto-reconnect. */
    private fun sendHangupSignal() {
        val socket = currentSocket ?: return
        try {
            val payload = ByteArray(FrameCodec.PAYLOAD_SIZE)
            payload[0] = FrameCodec.CONTROL_HANGUP
            val frame = FrameCodec.wrap(FrameCodec.TYPE_CONTROL, 0, payload)
            synchronized(socketWriteLock) {
                socket.outputStream.write(frame)
                socket.outputStream.flush()
            }
        } catch (_: IOException) {
        }
    }

    private fun captureLoop(cap: AudioCapture) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val encoder = SimpleAdpcmCodec()
        var seq = 0
        while (running.get()) {
            val pcm = cap.readFrame() ?: break
            val payload = if (muted.get()) {
                encoder.encode(ShortArray(pcm.size))
            } else {
                encoder.encode(pcm)
            }
            val frame = FrameCodec.wrap(FrameCodec.TYPE_AUDIO, seq, payload)
            seq = (seq + 1) and 0xFFFF
            if (outgoingQueue.remainingCapacity() == 0) outgoingQueue.poll()
            outgoingQueue.offer(frame)
        }
    }

    private fun networkWriterLoop(output: OutputStream) {
        try {
            while (running.get()) {
                val frame = outgoingQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                synchronized(socketWriteLock) {
                    output.write(frame)
                    output.flush()
                }
            }
        } catch (e: IOException) {
            if (running.get()) postToMain { onLinkLost() }
        }
    }

    private fun networkReaderLoop(input: InputStream, jitterBuffer: JitterBuffer) {
        val decoder = SimpleAdpcmCodec()
        val frameBuf = ByteArray(FrameCodec.FRAME_SIZE)
        try {
            while (running.get()) {
                readFully(input, frameBuf)
                val parsed = FrameCodec.parse(frameBuf)
                when {
                    parsed.type == FrameCodec.TYPE_AUDIO -> jitterBuffer.offer(decoder.decode(parsed.payload))
                    parsed.type == FrameCodec.TYPE_CONTROL && parsed.payload.firstOrNull() == FrameCodec.CONTROL_HANGUP -> {
                        postToMain { onPeerHangup() }
                        return
                    }
                }
            }
        } catch (e: IOException) {
            if (running.get()) postToMain { onLinkLost() }
        }
    }

    private fun playbackLoop(play: AudioPlayback, jitterBuffer: JitterBuffer) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val silence = ShortArray(AudioCapture.SAMPLES_PER_FRAME)
        while (running.get()) {
            val frame = jitterBuffer.poll(100) ?: silence
            play.writeFrame(frame)
        }
    }

    @Throws(IOException::class)
    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw IOException("stream closed")
            offset += read
        }
    }

    private fun teardownAudioPipeline() {
        running.set(false)
        captureThread?.interrupt()
        networkWriterThread?.interrupt()
        networkReaderThread?.interrupt()
        playbackThread?.interrupt()
        currentSocket?.let { try { it.close() } catch (_: IOException) {} }
        listOf(captureThread, networkWriterThread, networkReaderThread, playbackThread).forEach {
            try { it?.join(500) } catch (_: InterruptedException) {}
        }
        captureThread = null
        networkWriterThread = null
        networkReaderThread = null
        playbackThread = null
        capture?.stop()
        playback?.stop()
        capture = null
        playback = null
    }

    private fun teardownCall(resumeListening: Boolean) {
        teardownAudioPipeline()
        stopBluetoothScoRouting()
        currentSocket = null
        connectedSinceMs = 0L
        connectionManager.stop()
        _uiState.value = CallUiState.Idle
        if (resumeListening) startListeningIfIdle()
        updateNotification()
    }

    private fun refreshConnectedState() {
        val state = _uiState.value
        if (state is CallUiState.Connected) {
            _uiState.value = state.copy(muted = muted.get())
        }
    }

    private fun postToMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val state = _uiState.value
        val statusText = when (state) {
            is CallUiState.Idle -> getString(R.string.status_idle)
            is CallUiState.Listening -> getString(R.string.status_listening)
            is CallUiState.Connecting -> getString(R.string.status_connecting, state.deviceName)
            is CallUiState.Connected -> getString(R.string.status_connected, state.deviceName)
            is CallUiState.Reconnecting -> getString(R.string.status_reconnecting, state.deviceName, state.attempt)
            is CallUiState.Error -> getString(R.string.status_error, state.message)
        }

        val muteIntent = Intent(this, VoiceCallService::class.java).setAction(ACTION_MUTE_TOGGLE)
        val mutePending = PendingIntent.getService(
            this, 1, muteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endIntent = Intent(this, VoiceCallService::class.java).setAction(ACTION_END_CALL)
        val endPending = PendingIntent.getService(
            this, 2, endIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteLabel = if (muted.get()) getString(R.string.unmute) else getString(R.string.mute)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, muteLabel, mutePending)
            .addAction(0, getString(R.string.end_call), endPending)
            .build()
    }

    private fun updateNotification() {
        val callActive = when (_uiState.value) {
            is CallUiState.Connecting, is CallUiState.Connected, is CallUiState.Reconnecting -> true
            else -> false
        }
        if (callActive) {
            if (!foregroundActive) {
                startForeground(NOTIFICATION_ID, buildNotification())
                foregroundActive = true
            } else {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
            }
        } else if (foregroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundActive = false
        }
    }
}
