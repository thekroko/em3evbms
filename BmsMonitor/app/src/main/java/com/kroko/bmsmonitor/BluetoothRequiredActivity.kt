package com.kroko.bmsmonitor

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

private val requiredPermissions: Array<String> = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.BLUETOOTH_ADMIN,
    Manifest.permission.BLUETOOTH)

/** Ensure bluetooth is accessible */
abstract class BluetoothRequiredActivity : AppCompatActivity() {
    class EnableBluetoothContract : ActivityResultContract<Void, Boolean>() {
        override fun createIntent(context: Context, input: Void?): Intent {
            return Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
            return resultCode == Activity.RESULT_OK
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(EnableBluetoothContract()) {
        when (it && getSystemService(BluetoothManager::class.java).adapter.isEnabled) {
            true -> onBluetoothAccessible()
            else -> showError("Could not access bluetooth")
        }
    }
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        when (results.isNotEmpty() && results.all { it.value } ) {
            true -> onBluetoothAccessible()
            else -> showError("Could not get required permissions")
        }
    }

    /** Returns an accessible bluetooth device (or null if not yet accessible) */
    fun getBluetooth() : BluetoothAdapter? {
        // Make sure we have all required permissions
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this,  it)  != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            Log.d("BmsMonitor", "Missing bluetooth permissions: $missingPermissions")
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
            return null
        }

        // Get the bluetooth adapter
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter = bluetoothManager?.adapter ?: return null
        if (!bluetoothAdapter.isEnabled) {
            Log.d("BmsMonitor", "Bluetooth is disabled; trying to enable")
            enableBluetoothLauncher.launch(null);
            return null;
        }
        return bluetoothAdapter
    }

    /** Called when the bluetooth device becomes accessible */
    abstract fun onBluetoothAccessible()

    private fun showError(msg: String) {
        Toast.makeText(this, "ERROR: $msg", Toast.LENGTH_LONG).show()
    }
}