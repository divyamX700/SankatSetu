package com.sankatsetu.app.mesh.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.sankatsetu.app.mesh.router.MeshLink
import com.sankatsetu.app.mesh.router.MessageRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the actual radio: BLE advertising + scanning (peer discovery) and a
 * dual GATT server/client role (every device is simultaneously both, per
 * docs/concepts/ble-mesh-protocol.md#dual-role). Feeds decoded bytes to
 * [MessageRouter] and never makes routing decisions itself.
 *
 * Permission preconditions (all checked by the caller before construction —
 * see `ui/setup/PermissionsScreen.kt`, Day 1 UI scaffold):
 * `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+), or
 * `BLUETOOTH`/`BLUETOOTH_ADMIN` + `ACCESS_FINE_LOCATION` below API 31.
 *
 * Day 1 scope: one GATT connection per discovered peer, full-duplex over a
 * single characteristic (write from central, notify from peripheral — see
 * [GattProfile]). No fragmentation, no source routing, no courier envelopes
 * yet — those land Day 2 per docs/PLAN.md.
 */
@SuppressLint("MissingPermission") // permission preconditions documented above; runtime checks happen in the UI layer
class MeshTransport(
    private val context: Context,
    private val router: MessageRouter,
    private val scope: CoroutineScope
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var meshCharacteristic: BluetoothGattCharacteristic? = null

    // Centrals currently subscribed to our peripheral (i.e. they connected to us).
    private val subscribedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    // Our outgoing connections where we act as central (i.e. we connected to them),
    // keyed by the peer's raw device address (one physical connection per address).
    private val clientConnections = ConcurrentHashMap<String, BluetoothGatt>()
    // The CentralLink wrapper for each outgoing connection — looked up from
    // clientGattCallback to complete pending writes/RSSI reads and to drive
    // the heartbeat loop that detects a dead link faster than Android's own
    // supervision timeout (which can take 20s+ to fire, or longer in the
    // field — see docs/adr/0011-link-reliability.md).
    private val centralLinks = ConcurrentHashMap<String, CentralLink>()
    private val heartbeatJobs = ConcurrentHashMap<String, Job>()

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    fun start() {
        val bt = adapter
        if (bt == null) {
            Log.w(TAG, "start(): no BluetoothAdapter on this device")
            return
        }
        if (!bt.isEnabled) {
            Log.w(TAG, "start(): BluetoothAdapter reports isEnabled=false, not starting")
            return
        }
        Log.i(TAG, "start(): adapter enabled, address=${bt.address}, starting GATT server + advertising + scanning")
        startGattServer()
        startAdvertising(bt)
        startScanning(bt)
    }

    fun stop() {
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        gattServer?.close()
        gattServer = null
        heartbeatJobs.values.forEach { it.cancel() }
        heartbeatJobs.clear()
        centralLinks.clear()
        clientConnections.values.forEach { it.close() }
        clientConnections.clear()
        subscribedCentrals.clear()
    }

    // ---------------------------------------------------------------------
    // Peripheral role: advertise + GATT server
    // ---------------------------------------------------------------------

    private fun startGattServer() {
        val server = bluetoothManager.openGattServer(context, gattServerCallback)
        if (server == null) {
            Log.e(TAG, "startGattServer(): openGattServer() returned null")
            return
        }
        val service = BluetoothGattService(GattProfile.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val characteristic = BluetoothGattCharacteristic(
            GattProfile.MESH_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val cccd = BluetoothGattDescriptor(
            GattProfile.CLIENT_CONFIG_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccd)
        service.addCharacteristic(characteristic)
        server.addService(service)

        gattServer = server
        meshCharacteristic = characteristic
        Log.i(TAG, "startGattServer(): GATT server + service registered OK (service=${GattProfile.SERVICE_UUID})")
    }

    private fun startAdvertising(bt: BluetoothAdapter) {
        val advertiser = bt.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "startAdvertising(): bluetoothLeAdvertiser is null — device may not support peripheral/advertiser role")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(android.os.ParcelUuid(GattProfile.SERVICE_UUID))
            .setIncludeDeviceName(false) // no device-name leak beyond the service UUID itself
            .build()
        Log.i(TAG, "startAdvertising(): calling startAdvertising()")
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "advertiseCallback.onStartSuccess(): now advertising")
        }

        override fun onStartFailure(errorCode: Int) {
            // Common cause on some OEMs: too many concurrent advertisers. Non-fatal —
            // we still function as central-only until the next start() retry.
            // errorCode maps to AdvertiseCallback.ADVERTISE_FAILED_* constants:
            // 1=DATA_TOO_LARGE 2=TOO_MANY_ADVERTISERS 3=ALREADY_STARTED
            // 4=INTERNAL_ERROR 5=FEATURE_UNSUPPORTED
            Log.e(TAG, "advertiseCallback.onStartFailure(): errorCode=$errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "gattServerCallback.onConnectionStateChange(): device=${device.address} status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                registerLink(PeripheralLink(device))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedCentrals.remove(device.address)
                router.onLinkDisconnected(linkId(device, Role.PERIPHERAL))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == GattProfile.MESH_CHARACTERISTIC_UUID) {
                Log.i(TAG, "onCharacteristicWriteRequest(): ${value.size} bytes from ${device.address}")
                scope.launch { router.handleInboundBytes(linkId(device, Role.PERIPHERAL), value) }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == GattProfile.CLIENT_CONFIG_DESCRIPTOR_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    subscribedCentrals[device.address] = device
                } else {
                    subscribedCentrals.remove(device.address)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Central role: scan + connect
    // ---------------------------------------------------------------------

    private fun startScanning(bt: BluetoothAdapter) {
        val scanner = bt.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "startScanning(): bluetoothLeScanner is null")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(GattProfile.SERVICE_UUID))
            .build()
        // Balanced duty cycle per docs/concepts/ble-mesh-protocol.md: not the
        // most battery-efficient possible setting, but SCAN_MODE_LOW_LATENCY
        // burns too much battery for a multi-hour demo and LOW_POWER can miss
        // fast-moving handshakes. Adaptive duty cycling (idle vs "active
        // conversation" mode) is Day 2 scope.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        Log.i(TAG, "startScanning(): calling startScan() filtered on ${GattProfile.SERVICE_UUID}")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            Log.i(TAG, "scanCallback.onScanResult(): saw ${device.address} rssi=${result.rssi}")
            if (clientConnections.containsKey(device.address) || subscribedCentrals.containsKey(device.address)) return
            // No MAC-based tie-break: BluetoothAdapter.getAddress() cannot
            // return this device's real address on any Android app (a
            // long-standing platform privacy restriction — it always
            // returns the placeholder "02:00:00:00:00:00", identical on
            // every device), so the previous "lexicographically-lesser MAC
            // initiates" logic was comparing the *peer's* real address
            // against a constant that has nothing to do with either side's
            // actual identity. That silently made almost every real device
            // decide "the other one should connect" — meaning BOTH sides
            // deferred forever and no connection was ever made. Confirmed by
            // running this on two real phones: found in
            // docs/adr/0010-real-device-ble-diagnostics.md.
            //
            // Fix: just connect whenever we see a not-yet-linked peer. The
            // guard above already prevents re-connecting to an already-
            // linked peer; the only remaining race is both sides connecting
            // to each other near-simultaneously before either registers the
            // other's link, producing two redundant links for one pair
            // instead of one. That's a real simplification (Bitchat's own
            // link-collapsing logic, which handles exactly this, was
            // explicitly not ported — see FanoutSelector.kt) but not
            // fatal: duplicate delivery is absorbed by SeenMessageCache.
            Log.i(TAG, "scanCallback: initiating central connection to ${device.address}")
            connectAsCentral(device)
        }

        override fun onScanFailed(errorCode: Int) {
            // Non-fatal: we still function as peripheral-only until the platform recovers.
            // errorCode maps to ScanCallback.SCAN_FAILED_* constants:
            // 1=ALREADY_STARTED 2=APPLICATION_REGISTRATION_FAILED 3=INTERNAL_ERROR
            // 4=FEATURE_UNSUPPORTED 5=OUT_OF_HARDWARE_RESOURCES 6=SCANNING_TOO_FREQUENTLY
            Log.e(TAG, "scanCallback.onScanFailed(): errorCode=$errorCode")
        }
    }

    private fun connectAsCentral(device: BluetoothDevice) {
        Log.i(TAG, "connectAsCentral(): connectGatt to ${device.address}")
        val gatt = device.connectGatt(context, false, clientGattCallback, BluetoothDevice.TRANSPORT_LE)
        clientConnections[device.address] = gatt
    }

    private val clientGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "clientGattCallback.onConnectionStateChange(): device=${gatt.device.address} status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(GattProfile.REQUESTED_MTU)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clientConnections.remove(gatt.device.address)
                centralLinks.remove(gatt.device.address)
                heartbeatJobs.remove(gatt.device.address)?.cancel()
                router.onLinkDisconnected(linkId(gatt.device, Role.CENTRAL))
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        @Suppress("DEPRECATION") // descriptor.value setter form; minSdk 29 predates the API-33 writeDescriptor(desc, value) overload
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(GattProfile.SERVICE_UUID)
                ?.getCharacteristic(GattProfile.MESH_CHARACTERISTIC_UUID) ?: return

            gatt.setCharacteristicNotification(characteristic, true)
            characteristic.getDescriptor(GattProfile.CLIENT_CONFIG_DESCRIPTOR_UUID)?.let { cccd ->
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }

            val link = CentralLink(gatt, characteristic)
            centralLinks[gatt.device.address] = link
            registerLink(link)
            startHeartbeat(gatt.device.address, link)
        }

        // Two distinct deprecations here: the 2-arg onCharacteristicChanged
        // callback itself (superseded by a 3-arg overload with an explicit
        // value: ByteArray in API 33 — KT-47902 wants @Deprecated re-added on
        // any override, which we don't want since this is still the only
        // signature that fires on minSdk 29-32), and characteristic.value's
        // getter in the body, same as the other two suppressions in this class.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != GattProfile.MESH_CHARACTERISTIC_UUID) return
            val value = characteristic.value ?: return
            Log.i(TAG, "onCharacteristicChanged(): ${value.size} bytes from ${gatt.device.address}")
            scope.launch { router.handleInboundBytes(linkId(gatt.device, Role.CENTRAL), value) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            centralLinks[gatt.device.address]?.completeWrite(status == android.bluetooth.BluetoothGatt.GATT_SUCCESS)
        }
    }

    /**
     * Detects a dead central-role link far faster than Android's own GATT
     * supervision timeout, which can take 20s+ (sometimes much longer in the
     * field) to notice a peer walked out of range — see
     * docs/adr/0011-link-reliability.md.
     *
     * This used to probe with [BluetoothGatt.readRemoteRssi] on the theory
     * that a cheap built-in round trip would fail once the peer was gone.
     * Real-device testing disproved that: some BLE controllers answer an
     * RSSI read from a cached/last-known value without a genuine over-the-air
     * round trip, so it kept reporting success for a link whose peer had its
     * radio switched off entirely. A GATT write with
     * [BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT] doesn't have that
     * escape hatch — the local stack only reports success once the peer's
     * ATT layer actually acknowledges it — so the heartbeat now sends one
     * tiny probe frame through the same acknowledged-write path real
     * traffic uses. The peer's [MessageRouter] silently drops it as an
     * undecodable packet; that's fine; it never needs its own protocol type.
     */
    private fun startHeartbeat(deviceAddress: String, link: CentralLink) {
        heartbeatJobs[deviceAddress]?.cancel()
        heartbeatJobs[deviceAddress] = scope.launch {
            var misses = 0
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val alive = link.send(HEARTBEAT_PROBE)
                if (!alive) {
                    misses += 1
                    Log.w(TAG, "heartbeat: missed RSSI read for $deviceAddress ($misses/$HEARTBEAT_MAX_MISSES)")
                    if (misses >= HEARTBEAT_MAX_MISSES) {
                        Log.w(TAG, "heartbeat: giving up on $deviceAddress, forcing disconnect")
                        clientConnections[deviceAddress]?.disconnect()
                        break
                    }
                } else {
                    misses = 0
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // MeshLink implementations
    // ---------------------------------------------------------------------

    private fun registerLink(link: MeshLink) = router.onLinkConnected(link)

    /**
     * The same peer can hold both a peripheral-role link (they connected to
     * us) and a central-role link (we connected to them) at once — see the
     * scanCallback comment on why we no longer tie-break who initiates.
     * Without a role suffix here, the second role to register would silently
     * overwrite the first in MessageRouter's link map, and only one
     * direction of the pair would ever be used to send — the exact cause of
     * the asymmetric-range bug found in real two-phone field testing
     * (central-role writes and peripheral-role notifies don't necessarily
     * have the same effective range on a given phone's BLE chipset, so
     * whichever one happened to "win" the map slot capped that phone's
     * outbound range to its own). Keeping both means a send tries every
     * physical path to that peer, not just whichever one was left standing.
     */
    private enum class Role { CENTRAL, PERIPHERAL }

    private fun linkId(device: BluetoothDevice, role: Role): String = "${device.address}#${role.name}"

    /** Represents a central that connected to *our* peripheral; we send by notifying it. */
    private inner class PeripheralLink(private val device: BluetoothDevice) : MeshLink {
        override val linkId: String = linkId(device, Role.PERIPHERAL)

        @Suppress("DEPRECATION") // characteristic.value setter form; API-33's overload with an explicit value param is the alternative once minSdk allows dropping this
        override suspend fun send(bytes: ByteArray): Boolean {
            val server = gattServer ?: return false
            val characteristic = meshCharacteristic ?: return false
            if (!subscribedCentrals.containsKey(device.address)) {
                Log.w(TAG, "PeripheralLink.send(): ${device.address} not subscribed, dropping ${bytes.size} bytes")
                return false
            }
            characteristic.value = bytes
            val ok = server.notifyCharacteristicChanged(device, characteristic, false)
            Log.i(TAG, "PeripheralLink.send(): notify to ${device.address} (${bytes.size} bytes) -> $ok")
            return ok
        }
    }

    /** Represents a peripheral we connected to as central; we send by writing to it. */
    private inner class CentralLink(
        private val gatt: BluetoothGatt,
        private val characteristic: BluetoothGattCharacteristic
    ) : MeshLink {
        override val linkId: String = linkId(gatt.device, Role.CENTRAL)

        // Android's GATT stack only tolerates one outstanding operation per
        // connection at a time; this mutex serializes writes and heartbeat
        // RSSI reads so a message send and a heartbeat tick can never race
        // on the same BluetoothGatt.
        private val opMutex = Mutex()
        private var pendingWrite: CompletableDeferred<Boolean>? = null

        fun completeWrite(success: Boolean) {
            pendingWrite?.complete(success)
        }

        /**
         * Waits for real GATT-level acknowledgment instead of firing and
         * forgetting (the previous WRITE_TYPE_NO_RESPONSE behaviour): a
         * write silently vanishing into a dead-but-not-yet-detected link was
         * indistinguishable from success, which is exactly what let messages
         * disappear with no failure signal during real-world range testing.
         * WRITE_TYPE_DEFAULT gets us a genuine onCharacteristicWrite status,
         * and the Android BLE stack itself retries at the link layer within
         * the connection interval — more reliable at the edge of range than
         * an unacknowledged write, at the cost of one round trip per send.
         */
        @Suppress("DEPRECATION")
        override suspend fun send(bytes: ByteArray): Boolean = opMutex.withLock {
            val result = CompletableDeferred<Boolean>()
            pendingWrite = result
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = bytes
            val queued = gatt.writeCharacteristic(characteristic)
            val outcome = if (!queued) false else withTimeoutOrNull(WRITE_TIMEOUT_MS) { result.await() } ?: false
            pendingWrite = null
            Log.i(TAG, "CentralLink.send(): write to ${gatt.device.address} (${bytes.size} bytes, queued=$queued) -> $outcome")
            outcome
        }
    }

    companion object {
        // Verbose on purpose (see docs/adr/0010-real-device-ble-diagnostics.md):
        // this is the layer where "does it actually work on real hardware"
        // lives or dies, and system logcat alone wasn't enough to tell why
        // one real device's BLE stack went silent — these lines are what
        // made the actual cause visible.
        private const val TAG = "MeshTransport"

        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_MAX_MISSES = 2
        private val HEARTBEAT_PROBE = byteArrayOf(0)
    }
}
