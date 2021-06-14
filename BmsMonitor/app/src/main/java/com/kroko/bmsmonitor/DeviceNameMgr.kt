package com.kroko.bmsmonitor

import android.content.Context
import android.content.Context.MODE_PRIVATE

class DeviceNameMgr(ctx: Context) {
    private val namePrefs by lazy {ctx.getSharedPreferences("BMS_NAMES", MODE_PRIVATE)}

    fun getDisplayName(address: String): String? {
        return namePrefs.getString(address, null)
    }

    fun setDisplayName(address: String, newName: String) {
        namePrefs.edit().putString(address, if (newName.isEmpty()) null else newName).commit()
    }

}