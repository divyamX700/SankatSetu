package com.sankatsetu.app.mesh.crypto

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat

/**
 * The nickname broadcast in every announce packet — see
 * docs/adr/0015-editable-nickname.md. Defaults to the anonymous
 * `builder-xxxx` form (unchanged behavior for anyone who never opens the
 * rename dialog); the OS Bluetooth device name is offered inside that
 * dialog as a one-tap suggestion, never applied silently, because a phone's
 * Bluetooth name is very often someone's real name ("Priya's Galaxy S23")
 * and this app should not broadcast that to nearby strangers in a disaster
 * zone without the person explicitly choosing to.
 */
class NicknameStore(private val context: Context, private val fallbackSeed: ByteArray) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): String = prefs.getString(KEY_NICKNAME, null)?.takeIf { it.isNotBlank() } ?: defaultNickname()

    fun set(nickname: String) {
        val trimmed = nickname.trim().take(MAX_LENGTH)
        if (trimmed.isNotEmpty()) prefs.edit().putString(KEY_NICKNAME, trimmed).apply()
    }

    /** The OS Bluetooth device name, only if permission is already granted — never requested here, only read. */
    fun deviceBluetoothName(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.name?.takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun defaultNickname(): String = "builder-${fallbackSeed.take(2).joinToString("") { "%02x".format(it) }}"

    private companion object {
        const val PREFS_NAME = "sankatsetu_nickname"
        const val KEY_NICKNAME = "nickname"
        const val MAX_LENGTH = 24
    }
}
