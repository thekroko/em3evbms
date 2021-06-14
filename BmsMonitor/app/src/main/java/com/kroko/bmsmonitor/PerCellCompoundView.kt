package com.kroko.bmsmonitor

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.kroko.bmsmonitor.databinding.ViewPercellBinding
import java.lang.Math.max

const val MIN_VOLTS = 3300
const val MAX_VOLTS = 4200

class PerCellCompoundView @JvmOverloads
    constructor(private val ctx: Context, private val attributeSet: AttributeSet? = null)
    : LinearLayout(ctx, attributeSet) {

    private var binder: ViewPercellBinding

    private var cellId = 1
    private var cellMilliVolts = 0

    init {
        val attributes = ctx.obtainStyledAttributes(attributeSet, R.styleable.PerCellCompoundView)
        cellId = attributes.getInt(R.styleable.PerCellCompoundView_cellId, 0)
        cellMilliVolts = attributes.getInt(R.styleable.PerCellCompoundView_cellMilliVolts, 0)
        attributes.recycle()

        val inflater = ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        binder = ViewPercellBinding.inflate(inflater, this)
        update()
    }

    private fun update() {
        binder.cellId.text = "Cell ${cellId.toString().padStart(2, '0')}"
        binder.cellVoltage.text = "$cellMilliVolts mV"
        binder.progressBar.max = MAX_VOLTS - MIN_VOLTS
        binder.progressBar.progress = (cellMilliVolts - MIN_VOLTS).coerceAtLeast(0)
        visibility = if (cellMilliVolts > 0) VISIBLE else GONE
    }

    fun setMilliVolts(mv : Int) {
        cellMilliVolts = mv
        update()
    }
}