package dev.btvolume

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var list: LinearLayout
    private val prefs by lazy { Prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BtReceiver.createChannel(this)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        // targetSdk 35 is edge-to-edge: keep content clear of the system bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val pad = dp(16)
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            } else {
                v.setPadding(pad, pad, pad, pad)
            }
            insets
        }
        setContentView(scroll)

        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        render()
    }

    @SuppressLint("MissingPermission")
    private fun render() {
        list.removeAllViews()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            list.addView(text("Grant the Nearby devices permission to list paired Bluetooth devices."))
            return
        }
        val devices = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices
            .orEmpty().sortedBy { it.name?.lowercase() ?: it.address }
        if (devices.isEmpty()) {
            list.addView(text("No paired Bluetooth devices."))
            return
        }
        list.addView(text("Enable a device to set media volume when it connects. " +
            "The previous volume is restored when it disconnects.", 14f))
        devices.forEach { list.addView(row(it)) }
    }

    @SuppressLint("MissingPermission")
    private fun row(device: BluetoothDevice): View {
        val address = device.address
        val level = prefs.level(address)

        val label = text("", 14f)
        val seek = SeekBar(this).apply {
            max = 100
            progress = level ?: 50
            visibility = if (level != null) View.VISIBLE else View.GONE
        }
        fun updateLabel() {
            label.text = if (seek.visibility == View.VISIBLE) "Volume: ${seek.progress}%" else "Not managed"
        }

        val toggle = Switch(this).apply {
            text = device.name ?: address
            textSize = 18f
            isChecked = level != null
            setOnCheckedChangeListener { _, on ->
                seek.visibility = if (on) View.VISIBLE else View.GONE
                prefs.setLevel(address, if (on) seek.progress else null)
                updateLabel()
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) prefs.setLevel(address, p)
                updateLabel()
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        updateLabel()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, 0)
            addView(toggle)
            addView(label)
            addView(seek)
        }
    }

    private fun text(s: String, size: Float = 16f) = TextView(this).apply {
        text = s
        textSize = size
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
