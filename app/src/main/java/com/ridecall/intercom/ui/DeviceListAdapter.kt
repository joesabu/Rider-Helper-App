package com.ridecall.intercom.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ridecall.intercom.databinding.ItemDeviceBinding

class DeviceListAdapter(
    private val onConnectClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

    private val devices = mutableListOf<BluetoothDevice>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newDevices: List<BluetoothDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount() = devices.size

    inner class ViewHolder(private val binding: ItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("MissingPermission")
        fun bind(device: BluetoothDevice) {
            binding.deviceName.text = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
            binding.connectButton.setOnClickListener { onConnectClick(device) }
        }
    }
}
