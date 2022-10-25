package com.kroko.bmsmonitor

import android.content.DialogInterface
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.core.os.postDelayed
import com.kroko.bmsmonitor.databinding.ActivityBmsInfoBinding
import java.lang.Integer.max
import kotlin.math.min


class BmsInfoActivity : BluetoothRequiredActivity(), Em3evBms.DataUpdateHandler {
    private fun deviceName() : String { return intent.getStringExtra("name") ?: ""}
    private fun address() : String { return intent.getStringExtra("address") ?: ""}
    private lateinit var deviceNameMgr: DeviceNameMgr
    lateinit var binder: ActivityBmsInfoBinding
    lateinit var bms : Em3evBms
    private var lastResponseMillis = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceNameMgr = DeviceNameMgr(this)
        binder = ActivityBmsInfoBinding.inflate(layoutInflater)
        setContentView(binder.root)
        setSupportActionBar(binder.toolbar);
        updateTitle()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.bmsinfomenu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.renameMenuItem -> {
            showRenameDialog()
            true
        }
        android.R.id.home -> {
            onBackPressed()
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onStart() {
        super.onStart()
        setupDevice()
        Handler(Looper.getMainLooper()).postDelayed(4000) { checkPingState() }
    }

    private fun checkPingState() {
        val connected = (SystemClock.elapsedRealtime() - lastResponseMillis <= 3000)
        binder.status.text = if (connected) "Connected" else "Not responding"
        binder.status.setTextColor(if (connected) Color.GREEN else Color.RED)
        Handler(Looper.getMainLooper()).postDelayed(2000) { checkPingState() }
    }

    override fun onStop() {
        super.onStop()
        bms?.stop()
        Handler(Looper.getMainLooper()).removeCallbacksAndMessages(null)
    }

    override fun onBluetoothAccessible() {
        setupDevice()
    }

    private fun setupDevice() {
        val bluetooth = getBluetooth() ?: return
        binder.status.text = "Connecting..."
        bms = Em3evBms(this, bluetooth.getRemoteDevice(address()), this)
        bms.init()
    }

    private fun showRenameDialog() {
        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setTitle("Rename BMS")

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.text = Editable.Factory.getInstance().newEditable(title)
        builder.setView(input)
        builder.setPositiveButton("OK", DialogInterface.OnClickListener { _, _ -> renameDevice(input.text.toString()) })
        builder.setNegativeButton("Cancel", DialogInterface.OnClickListener { dialog, _ -> dialog.cancel() })
        builder.show()
    }

    private fun renameDevice(newName: String) {
        deviceNameMgr.setDisplayName(address(), newName)
        updateTitle()
    }

    private fun updateTitle() {
        title = deviceNameMgr.getDisplayName(address()) ?: deviceName()
    }

    override fun onDataChanged(data: Em3evBms.BmsData) {
        runOnUiThread {
            lastResponseMillis = SystemClock.elapsedRealtime()
            binder.batteryLevel.text = "${data.remainingChargePercent}%"
            binder.chargeCycles.text = "${data.cycleCount} cycle(s)"
            binder.voltage.text = "${data.voltage} V"
            binder.current.text = "%.2f A".format(data.current)
            binder.power.text = "%.2f W".format(data.current * data.voltage)
            binder.temperature.text = "%.1f°C".format(data.temperature)
            binder.numberOfCells.text = "${data.cellVoltages.size} cell(s)"
            val remainingCapacityPercent = data.fullChargeCapacity * 100 / (data.factoryCapacity+1)
            binder.remainingCapacity.text = "${data.fullChargeCapacity} mA ($remainingCapacityPercent%)"
            binder.factoryCapacity.text = "${data.factoryCapacity} mA"
            val cells = arrayOf(binder.cell1, binder.cell2, binder.cell3, binder.cell4, binder.cell5, binder.cell6, binder.cell7, binder.cell8, binder.cell9, binder.cell10, binder.cell11, binder.cell12, binder.cell13, binder.cell14, binder.cell14, binder.cell15, binder.cell16)
            var vMin = Integer.MAX_VALUE
            var vMax = 0
            var vSum = 0
            for (i in 0 until data.numCells) {
                val cellVolt = data.cellVoltages[i]
                vMin = if (cellVolt < vMin) cellVolt else vMin;
                vMax = if (cellVolt > vMax) cellVolt else vMax;
                vSum += cellVolt
                cells[i].setMilliVolts(cellVolt)
            }
            if (data.numCells == 0 || vMax == 0) {
                binder.vavg.text =  "-"
                binder.vmin.text =  "-"
                binder.vmax.text =  "-"
                binder.vdelta.text =  "-"
            } else {
                binder.vavg.text = "${vSum / data.numCells} mV"
                binder.vmin.text = "${vMin} mV"
                binder.vmax.text = "${vMax} mV"
                binder.vdelta.text = "${vMax - vMin} mV"
            }
        }
    }

    override fun debug(str: String) {
        //Log.d("BmsInfo", str)
    }
}