package com.sankatsetu.app.mesh.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.sankatsetu.app.SankatSetuApplication
import com.sankatsetu.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keeps [MeshTransport] alive against OEM background-execution kills
 * (Xiaomi/Realme/OnePlus are the worst offenders — see docs/adr/0001
 * "known limitation, not fixed for the hackathon"). A persistent, low-priority
 * notification is Android's contract for "please don't kill this process."
 *
 * Started from [MainActivity] on first launch and kept running for the life
 * of the app; there's no user-facing "stop" action deliberately — the whole
 * point of this app is to keep the radio on. Panic wipe stops it as part of
 * clearing everything else.
 */
class MeshForegroundService : Service() {

    companion object {
        private const val TAG = "MeshForegroundService"
        private const val CHANNEL_ID = "mesh_status"
        private const val NOTIFICATION_ID = 1001

        private val _connectedPeerCount = MutableStateFlow(0)
        val connectedPeerCount: StateFlow<Int> = _connectedPeerCount

        fun updatePeerCount(count: Int) {
            _connectedPeerCount.value = count
        }
    }

    private var transport: MeshTransport? = null

    /**
     * Real-device testing found that a full adapter cycle — the user
     * toggling Bluetooth off and back on, or the OS resetting the BLE stack
     * for its own reasons — leaves every [MeshTransport] reference
     * ([android.bluetooth.le.BluetoothLeAdvertiser],
     * [android.bluetooth.le.BluetoothLeScanner], the GATT server) silently
     * dead. [MeshTransport] itself only reacts to individual link drops, not
     * the adapter disappearing out from under it, so without this receiver
     * the mesh never comes back until the app is force-restarted — the exact
     * "disconnected and stayed disconnected" failure mode this service
     * exists to prevent. See docs/adr/0011-link-reliability.md.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "bluetoothStateReceiver: adapter back ON, restarting MeshTransport")
                    transport?.stop()
                    transport?.start()
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "bluetoothStateReceiver: adapter OFF, stopping MeshTransport")
                    transport?.stop()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0), foregroundServiceType())

        val app = application as SankatSetuApplication
        transport = MeshTransport(applicationContext, app.container.messageRouter, app.container.appScope)
        transport?.start()
        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        transport?.stop()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else 0

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mesh status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when Sankat Setu's offline mesh is active"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(peerCount: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val peerText = if (peerCount == 1) "1 peer nearby" else "$peerCount peers nearby"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sankat Setu is on")
            .setContentText(peerText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }
}
