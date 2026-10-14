package com.lushu.q07battery.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.lushu.q07battery.core.Gear
import com.lushu.q07battery.core.VehicleSignals

/**
 * 副驾屏 / 其他 Display 上的仪表（壳）。
 */
class DashboardPresentation(
    context: Context,
    display: Display,
) : Presentation(context, display) {

    private lateinit var tvPower: TextView
    private lateinit var tvGearSpeed: TextView
    private lateinit var tvSoc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF101418"))
            setPadding(48, 48, 48, 48)
        }
        tvPower = label(72f, "#FF8AE234")
        tvGearSpeed = label(44f, "#FFFCE94F")
        tvSoc = label(40f, "#FF729FCF")
        root.addView(tvPower)
        root.addView(tvGearSpeed)
        root.addView(tvSoc)
        setContentView(root)
    }

    fun render(s: VehicleSignals) {
        tvPower.text = "%.1f kW".format(s.batteryPowerKw)
        tvGearSpeed.text = "%s  %.0f km/h".format(Gear.label(s.gear), s.speedKph)
        tvSoc.text = "电量 %.0f%%   续航 %.0f km".format(s.socPercent, s.rangeKm)
    }

    private fun label(sizeSp: Float, color: String): TextView =
        TextView(context).apply {
            textSize = sizeSp
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
        }
}
