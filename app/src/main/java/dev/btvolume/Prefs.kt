package dev.btvolume

import android.content.Context

/** Per-device settings, keyed by Bluetooth MAC address. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("bt_volume", Context.MODE_PRIVATE)

    /** Target volume in percent, or null if the device is not managed. */
    fun level(address: String): Int? =
        sp.getInt("level_$address", -1).takeIf { it >= 0 }

    fun setLevel(address: String, percent: Int?) =
        sp.edit().apply {
            if (percent == null) remove("level_$address") else putInt("level_$address", percent)
        }.apply()

    /** Raw stream volume captured on connect, to be restored on disconnect. */
    fun saved(address: String): Int? =
        sp.getInt("saved_$address", -1).takeIf { it >= 0 }

    fun setSaved(address: String, index: Int?) =
        sp.edit().apply {
            if (index == null) remove("saved_$address") else putInt("saved_$address", index)
        }.commit()
}
