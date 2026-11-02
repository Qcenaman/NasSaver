package com.qcenaman.nassaver.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.qcenaman.nassaver.BuildConfig
import com.qcenaman.nassaver.service.DeviceState
import com.qcenaman.nassaver.service.KeepAliveService
import com.qcenaman.nassaver.service.Logins
import com.qcenaman.nassaver.service.Logs

/** TV screen: starts the server and shows its address, login state, warnings and latest log lines. */
class MainActivity : Activity() {
    private lateinit var address: TextView
    private lateinit var login: TextView
    private lateinit var warnings: TextView
    private lateinit var summary: TextView
    private lateinit var logView: TextView
    private lateinit var reset: Button
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
        column.addView(text(30f, bold = true).apply { text = "NasSaver ${BuildConfig.VERSION_NAME}" })
        address = text(24f, bold = true).apply { setTextColor(0xFF4ADE80.toInt()) }
        column.addView(address)
        login = text(17f)
        column.addView(login)
        warnings = text(17f, bold = true).apply { setTextColor(0xFFF87171.toInt()) }
        column.addView(warnings)
        reset = Button(this).apply {
            text = "Reset login"
            setOnClickListener { confirmReset() }
        }
        column.addView(LinearLayout(this).apply {
            setPadding(0, dp(12), 0, dp(4))
            addView(reset)
        })
        summary = text(15f, mono = true).apply { setPadding(0, dp(12), 0, dp(12)) }
        column.addView(summary)
        logView = text(13f, mono = true)
        column.addView(logView)

        setContentView(ScrollView(this).apply { addView(column) })
        reset.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset login?")
            .setMessage("Every device will lose access until a new login is created from a browser.")
            .setPositiveButton("Reset") { _, _ ->
                KeepAliveService.current?.resetLogin() ?: run {
                    Logins.get(this).reset()
                    Logs.get(this).add("login reset on the TV")
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun render() {
        val device = DeviceState(this)
        val ip = device.ipv4().firstOrNull()
        val url = if (ip != null) "http://$ip:${KeepAliveService.PORT}" else null
        address.text = url ?: "Waiting for a network address…"
        val user = Logins.get(this).user
        login.text = when {
            user != null -> "Login: \"$user\". Windows, phones and laptops connect to ${url ?: "this address"}/dav"
            url != null -> "No login yet: open $url in a browser on your phone or PC to create it."
            else -> "No login yet."
        }
        reset.isEnabled = user != null
        warnings.text = buildList {
            if (!device.allFilesAccess()) add("⚠ All files access is missing, so the drives can't be shared yet.")
            if (device.lowPowerStandbyBlocks()) add("⚠ Low Power Standby is on: the drives go offline while the TV is in standby.")
        }.joinToString("\n")
        summary.text = KeepAliveService.current?.summary()?.joinToString("\n") { "${it.first}: ${it.second}" }
            ?: "Server starting…"
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
