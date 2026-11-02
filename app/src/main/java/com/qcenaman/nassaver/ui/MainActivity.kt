package com.qcenaman.nassaver.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.qcenaman.nassaver.BuildConfig
import com.qcenaman.nassaver.service.DeviceState
import com.qcenaman.nassaver.service.KeepAliveService
import com.qcenaman.nassaver.service.Logs

/** TV screen: starts the service and shows its status, the status page address and the latest log lines. */
class MainActivity : Activity() {
    private lateinit var address: TextView
    private lateinit var summary: TextView
    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeepAliveService.start(this, "app opened")

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(27), dp(48), dp(27))
        }
        column.addView(text(30f, bold = true).apply { text = "NasSaver ${BuildConfig.VERSION_NAME} · standby test" })
        address = text(22f, bold = true).apply { setTextColor(0xFF4ADE80.toInt()) }
        column.addView(address)
        column.addView(text(16f).apply {
            text = "Put the TV in standby for about an hour, then open the address above from your phone."
            alpha = 0.7f
        })
        summary = text(16f, mono = true).apply { setPadding(0, dp(16), 0, dp(16)) }
        column.addView(summary)
        logView = text(13f, mono = true)
        column.addView(logView)

        setContentView(ScrollView(this).apply {
            isFocusable = true
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        val ip = DeviceState(this).ipv4().firstOrNull()
        address.text = if (ip != null) "http://$ip:${KeepAliveService.PORT}" else "Waiting for a network address…"
        summary.text = KeepAliveService.current?.summary()?.joinToString("\n") { "${it.first}: ${it.second}" }
            ?: "Service starting…"
        logView.text = Logs.get(this).tail(25).asReversed().joinToString("\n")
    }

    private fun text(sp: Float, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
