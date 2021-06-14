package com.kroko.bmsmonitor

import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.os.postDelayed
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kroko.bmsmonitor.databinding.ActivitySearchForBmsBinding
import com.kroko.bmsmonitor.databinding.BluetoothListItemBinding

/** Lists bluetooth devices & connects to a specific BMS. */
class SearchForBms : BluetoothRequiredActivity() {
    private lateinit var binding: ActivitySearchForBmsBinding
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val bluetoothViewAdapter = BluetoothViewAdapter(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySearchForBmsBinding.inflate(layoutInflater)
        binding.buttonStartScanning.setOnClickListener { startScanning() }
        binding.buttonTest.setOnClickListener { bluetoothViewAdapter.addResult(BluetoothViewAdapter.Device("TestDevice", "F8:33:31:D4:E0:61", null)) }
        binding.bluetoothDevices.adapter = bluetoothViewAdapter
        binding.bluetoothDevices.layoutManager = LinearLayoutManager(this)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar);
        title = "Connect to EM3EV BMS"
    }

    override fun onStart() {
        super.onStart()
        bluetoothViewAdapter.maybeUpdateNames()
    }

    override fun onStop() {
        super.onStop()
        stopScanning()
    }

    private fun startScanning() {
        val bluetooth = getBluetooth()
        if (bluetooth == null) {
            binding.state.text = "Cannot access bluetooth"
            return
        }

        Handler(Looper.getMainLooper()).postDelayed( 10000) { stopScanning() }

        bluetoothViewAdapter.clear()
        bluetoothLeScanner = bluetooth.bluetoothLeScanner;
        bluetoothLeScanner!!.startScan(scanCallback)
        binding.state.text = "Bluetooth scanning..."
        binding.buttonStartScanning.isEnabled = false;
    }

    private fun stopScanning() {
        bluetoothLeScanner?.stopScan(scanCallback)
        bluetoothLeScanner = null
        binding.state.text = ""
        binding.buttonStartScanning.isEnabled = true
    }


    override fun onBluetoothAccessible() { startScanning() }

    class BluetoothViewAdapter(private val ctx : Context) : RecyclerView.Adapter<BluetoothViewAdapter.ViewHolder>() {
        class Device(val name: String, val address: String, var displayName: String?)
        class ViewHolder(private val binding: BluetoothListItemBinding) : RecyclerView.ViewHolder(binding.root) {
            fun set(result: Device) {
                binding.deviceName.text = result.displayName ?: result.name
                binding.address.text = result.address
            }
        }

        private val deviceNameMgr = DeviceNameMgr(ctx)
        private var deviceList: MutableList<BluetoothViewAdapter.Device> = mutableListOf()

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): BluetoothViewAdapter.ViewHolder {
            val binding = BluetoothListItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false)
            binding.buttonConnect.setOnClickListener { connectToDevice(binding.deviceName.text, binding.address.text) }
            return ViewHolder(binding)
        }

        private fun connectToDevice(name: CharSequence, device: CharSequence) {
            val intent = Intent(ctx, BmsInfoActivity::class.java)
            intent.putExtra("name", name.toString())
            intent.putExtra("address", device.toString())
            ctx.startActivity(intent)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.set(deviceList[position])
        }

        override fun getItemCount(): Int {
            return deviceList.size
        }

        fun addResult(result: Device) {
            if (deviceList.any { it.address == result.address }) {
                return
            }
            deviceList.add(result)
            notifyDataSetChanged()
        }
        fun addResult(result: ScanResult) {
            addResult(Device(result.scanRecord!!.deviceName!!, result.device.address, deviceNameMgr.getDisplayName(result.device.address)))
        }

        fun maybeUpdateNames() {
            var didChange = false
            for (d in deviceList) {
                val currentName = deviceNameMgr.getDisplayName(d.address)
                if (currentName != d.displayName) {
                    d.displayName = currentName
                    didChange = true
                }
            }
            if (didChange) {
                notifyDataSetChanged()
            }
        }

        fun clear() {
            deviceList.clear()
            notifyDataSetChanged()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result?.scanRecord?.deviceName?.startsWith("DXB-") != true) return
            binding.state.text = "Found device!";
            bluetoothViewAdapter.addResult(result)
        }
    }
}