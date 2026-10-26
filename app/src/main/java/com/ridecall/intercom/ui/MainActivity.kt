package com.ridecall.intercom.ui

import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.ridecall.intercom.BuildConfig
import com.ridecall.intercom.R
import com.ridecall.intercom.databinding.ActivityMainBinding
import com.ridecall.intercom.service.AudioRoute
import com.ridecall.intercom.service.CallUiState
import com.ridecall.intercom.service.VoiceCallService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: DeviceListAdapter
    private lateinit var routeAdapter: ArrayAdapter<String>
    private var service: VoiceCallService? = null
    private var bound = false
    private var routeOptions: List<AudioRoute> = emptyList()
    private var suppressRouteCallback = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[android.Manifest.permission.RECORD_AUDIO] != true) {
            binding.statusText.text = getString(R.string.status_error, "microphone permission is required")
            return@registerForActivityResult
        }
        if (!bound) startAndBindService()
        if (!hasBluetoothConnectPermission()) {
            binding.statusText.text = getString(R.string.status_error, "Bluetooth permission is required to connect devices")
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as VoiceCallService.LocalBinder).getService()
            bound = true
            refreshDeviceList()
            observeState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = DeviceListAdapter { device -> onConnectClicked(device) }
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = adapter

        binding.muteButton.setOnClickListener { service?.toggleMute() }
        binding.endCallButton.setOnClickListener { service?.endCall() }
        binding.versionText.text = getString(R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        setupAudioRouteSpinner()

        permissionLauncher.launch(PermissionHelper.requiredPermissions())
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun startAndBindService() {
        val intent = Intent(this, VoiceCallService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun onConnectClicked(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            binding.statusText.text = getString(R.string.status_error, "Bluetooth permission is required to connect devices")
            permissionLauncher.launch(PermissionHelper.requiredPermissions())
            return
        }
        service?.connectToDevice(device)
    }

    private fun refreshDeviceList() {
        val list = service?.bondedDevices() ?: emptyList()
        adapter.submitList(list)
        if (list.isEmpty()) {
            binding.statusText.text = getString(R.string.no_paired_devices)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val svc = service ?: return@repeatOnLifecycle
                launch { svc.uiState.collect { state -> render(state) } }
                launch {
                    combine(svc.availableAudioRoutes, svc.audioRoute) { routes, route -> routes to route }
                        .collect { (routes, route) -> renderAudioRoutes(routes, route) }
                }
            }
        }
    }

    private fun render(state: CallUiState) {
        val connected = state is CallUiState.Connected
        val inCallScreen = state is CallUiState.Connecting || state is CallUiState.Connected || state is CallUiState.Reconnecting
        binding.deviceListGroup.visibility = if (inCallScreen) View.GONE else View.VISIBLE
        binding.callGroup.visibility = if (inCallScreen) View.VISIBLE else View.GONE
        binding.muteButton.isEnabled = connected
        binding.endCallButton.isEnabled = connected || state is CallUiState.Connecting || state is CallUiState.Reconnecting
        binding.muteButton.text = if (connected && (state as CallUiState.Connected).muted) {
            getString(R.string.unmute)
        } else {
            getString(R.string.mute)
        }
        binding.statusText.text = when (state) {
            is CallUiState.Idle -> getString(R.string.status_idle)
            is CallUiState.Listening -> getString(R.string.status_listening)
            is CallUiState.Connecting -> getString(R.string.status_connecting, state.deviceName)
            is CallUiState.Connected -> getString(R.string.status_connected, state.deviceName)
            is CallUiState.Reconnecting -> getString(R.string.status_reconnecting, state.deviceName, state.attempt)
            is CallUiState.Error -> getString(R.string.status_error, state.message)
        }
    }

    private fun setupAudioRouteSpinner() {
        routeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf())
        routeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.audioRouteSpinner.adapter = routeAdapter
        binding.audioRouteSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressRouteCallback) return
                routeOptions.getOrNull(position)?.let { service?.selectAudioRoute(it) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun renderAudioRoutes(available: Set<AudioRoute>, current: AudioRoute) {
        val ordered = listOf(AudioRoute.BLUETOOTH, AudioRoute.EARPIECE, AudioRoute.SPEAKER).filter { it in available }
        routeOptions = ordered
        routeAdapter.clear()
        routeAdapter.addAll(ordered.map { routeLabel(it) })
        val index = ordered.indexOf(current).takeIf { it >= 0 } ?: 0
        suppressRouteCallback = true
        if (ordered.isNotEmpty()) binding.audioRouteSpinner.setSelection(index)
        suppressRouteCallback = false
    }

    private fun routeLabel(route: AudioRoute): String = when (route) {
        AudioRoute.BLUETOOTH -> getString(R.string.audio_route_bluetooth)
        AudioRoute.EARPIECE -> getString(R.string.audio_route_earpiece)
        AudioRoute.SPEAKER -> getString(R.string.audio_route_speaker)
    }
}
