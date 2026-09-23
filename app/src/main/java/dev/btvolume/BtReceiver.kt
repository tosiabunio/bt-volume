package dev.btvolume

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

class BtReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val device = intent.bluetoothDevice() ?: return
        val prefs = Prefs(context)
        if (prefs.level(device.address) == null && prefs.saved(device.address) == null) return
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> onConnected(context, prefs, device)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> onDisconnected(context, prefs, device)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun onConnected(context: Context, prefs: Prefs, device: BluetoothDevice) {
        val address = device.address
        val percent = prefs.level(address) ?: return
        if (prefs.saved(address) != null) return // already applied (duplicate broadcast)

        val am = context.getSystemService(AudioManager::class.java)
        // Capture the volume before audio is routed to the headset.
        val previous = am.getStreamVolume(STREAM)
        prefs.setSaved(address, previous)
        Log.i(TAG, "connected $address, previous=$previous, fixed=${am.isVolumeFixed}")

        // ACL comes up before the A2DP route, and STREAM_MUSIC volume is kept per output
        // device: wait until media actually plays on the headset, or the change lands on
        // the speaker and is replaced by the headset's remembered volume.
        val routed = waitUntil { am.isMediaRoutedTo(address) }
        Log.i(TAG, "routed=$routed ${am.describeRoute()}")
        val target = toIndex(am, percent)
        val actual = applyVolume(am, target)

        notify(
            context, address,
            "${device.label()} connected",
            if (close(am, actual, target)) "Volume set to ${toPercent(am, actual)}% (was ${toPercent(am, previous)}%)"
            else "Tried $percent%, but volume is ${toPercent(am, actual)}%",
        )
    }

    private fun onDisconnected(context: Context, prefs: Prefs, device: BluetoothDevice) {
        val address = device.address
        val previous = prefs.saved(address) ?: return

        val am = context.getSystemService(AudioManager::class.java)
        // Wait for audio to fall back to the phone before restoring. The BT output can
        // linger for a moment after ACL_DISCONNECTED.
        val routed = waitUntil { !am.isMediaRoutedTo(address) }
        Log.i(TAG, "disconnected $address, unrouted=$routed ${am.describeRoute()}")
        val actual = applyVolume(am, previous)
        prefs.setSaved(address, null)

        notify(
            context, address,
            "${device.label()} disconnected",
            if (close(am, actual, previous)) "Volume restored to ${toPercent(am, previous)}%"
            else "Tried ${toPercent(am, previous)}%, but volume is ${toPercent(am, actual)}%",
        )
    }

    /**
     * Sets the volume, then keeps re-applying it for a short while: the headset may push
     * its own absolute volume, or the route may settle, shortly after connecting.
     * Returns the volume read back at the end.
     */
    private fun applyVolume(am: AudioManager, index: Int): Int {
        var reached = setVolume(am, index)
        val deadline = SystemClock.uptimeMillis() + HOLD_MS
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(250)
            val now = am.getStreamVolume(STREAM)
            if (now != reached) {
                Log.w(TAG, "volume drifted to $now, re-applying $index ${am.describeRoute()}")
                reached = setVolume(am, index)
            }
        }
        return am.getStreamVolume(STREAM).also { Log.i(TAG, "final volume $it (wanted $index)") }
    }

    companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
        private const val TAG = "BtVolume"
        private const val CHANNEL = "volume"
        // Headsets can take several seconds to bring up A2DP after ACL. WAIT_MS + HOLD_MS
        // stays well within the 60 s goAsync() budget of a background broadcast.
        private const val WAIT_MS = 25_000L
        private const val HOLD_MS = 3_000L
        private const val MAX_STEPS = 200
        private const val STEP_MS = 500L

        private val MEDIA = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

        /** Sets the volume as close to [index] as the platform allows; returns what it reached. */
        fun setVolume(am: AudioManager, index: Int): Int {
            am.setStreamVolume(STREAM, index, 0)
            var now = am.getStreamVolume(STREAM)
            Log.i(TAG, "setStreamVolume $index -> read back $now")
            if (now == index) return now

            // ColorOS silently drops setStreamVolume/adjustStreamVolume on STREAM_MUSIC unless
            // the caller is the foreground app. adjustSuggestedStreamVolume is not gated, so
            // step towards the target instead (steps can be coarser than one index).
            repeat(MAX_STEPS) {
                val direction = if (now < index) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                val after = step(am, direction, now)
                if (after == now) return now // no effect: blocked, or at the limit
                if ((after - index).sign != (now - index).sign && after != index) {
                    // Stepped past the target: keep whichever side is closer.
                    if (abs(after - index) > abs(now - index)) {
                        return step(am, -direction, after).also { Log.i(TAG, "stepped to $it") }
                    }
                    return after.also { Log.i(TAG, "stepped to $it") }
                }
                now = after
                if (now == index) return now.also { Log.i(TAG, "stepped to $it") }
            }
            return now
        }

        /** Adjusts by one step and waits for it to apply: it is dispatched asynchronously. */
        private fun step(am: AudioManager, direction: Int, from: Int): Int {
            am.adjustSuggestedStreamVolume(direction, STREAM, 0)
            val deadline = SystemClock.uptimeMillis() + STEP_MS
            var now = am.getStreamVolume(STREAM)
            while (now == from && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(20)
                now = am.getStreamVolume(STREAM)
            }
            return now
        }

        fun toIndex(am: AudioManager, percent: Int): Int {
            val min = am.getStreamMinVolume(STREAM)
            val max = am.getStreamMaxVolume(STREAM)
            return (min + (max - min) * percent / 100f).roundToInt()
        }

        /** Stepping may only get near the target; within 5% counts as done. */
        private fun close(am: AudioManager, a: Int, b: Int): Boolean =
            abs(a - b) * 100 <= am.getStreamMaxVolume(STREAM) * 5

        fun toPercent(am: AudioManager, index: Int): Int =
            (index * 100f / am.getStreamMaxVolume(STREAM)).roundToInt()

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Volume changes", NotificationManager.IMPORTANCE_LOW)
            )
        }

        private fun notify(context: Context, address: String, title: String, text: String) {
            createChannel(context)
            val n = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_volume)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            // Silently dropped by the system if POST_NOTIFICATIONS was denied.
            context.getSystemService(NotificationManager::class.java).notify(address.hashCode(), n)
        }

        /** Returns whether the condition was met before the timeout. */
        private fun waitUntil(condition: () -> Boolean): Boolean {
            val deadline = SystemClock.uptimeMillis() + WAIT_MS
            while (!condition()) {
                if (SystemClock.uptimeMillis() >= deadline) return false
                SystemClock.sleep(250)
            }
            return true
        }

        private val BT_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            26, // TYPE_BLE_HEADSET (API 31)
            27, // TYPE_BLE_SPEAKER (API 31)
        )

        /**
         * The outputs media currently plays on. Being connected (listed in getDevices) is not
         * enough: the volume APIs act on the device media is actually routed to.
         */
        private fun AudioManager.mediaOutputs(): List<AudioDeviceInfo> =
            if (Build.VERSION.SDK_INT >= 33) getAudioDevicesForAttributes(MEDIA)
            else getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

        private fun AudioManager.isMediaRoutedTo(address: String): Boolean =
            mediaOutputs().any {
                // Compare the tail only: addresses may be partially anonymized.
                it.type in BT_OUTPUTS && it.address.takeLast(5).equals(address.takeLast(5), true)
            }

        private fun AudioManager.describeRoute(): String =
            mediaOutputs().joinToString(prefix = "[", postfix = "]") { "${it.type}/${it.address}" } +
                " vol=${getStreamVolume(STREAM)}/${getStreamMaxVolume(STREAM)}"

        @SuppressLint("MissingPermission")
        private fun BluetoothDevice.label(): String = runCatching { name }.getOrNull() ?: address

        private fun Intent.bluetoothDevice(): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= 33)
                getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else
                @Suppress("DEPRECATION") getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
}
