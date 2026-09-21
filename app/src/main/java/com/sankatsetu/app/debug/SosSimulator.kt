package com.sankatsetu.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Base64
import com.sankatsetu.app.data.PeerDao
import com.sankatsetu.app.data.PeerEntity
import com.sankatsetu.app.data.SosDao
import com.sankatsetu.app.mesh.protocol.AnnouncementPacket
import com.sankatsetu.app.mesh.protocol.BinaryProtocol
import com.sankatsetu.app.mesh.protocol.MeshPacket
import com.sankatsetu.app.mesh.protocol.MessageType
import com.sankatsetu.app.mesh.protocol.SosCategory
import com.sankatsetu.app.mesh.protocol.SosPacket
import com.sankatsetu.app.mesh.router.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Debug-only tool for testing "SOS arriving from someone else" without a
 * second physical phone -- genuine two-phone BLE mesh delivery has never
 * been verified in this project (see handoff.md, docs/adr/0020's Known
 * Gaps, and every other mesh feature's own honest "not yet field-tested"
 * note) and this doesn't change that. What it DOES prove: given a real
 * wire-format packet arriving on a link, the real decode -> Cedar gate ->
 * [MessageRouter.inboundApplicationPackets] -> SosManager.handleIncoming
 * -> Room -> Compose pipeline renders the sender's name, category, and
 * location correctly. It is real code exercising a real packet, just
 * injected at the BLE-transport boundary instead of over an actual radio.
 *
 * Never present in a release build -- registered only when
 * `BuildConfig.DEBUG` is true (see SankatSetuApplication.onCreate). Fire it
 * with:
 * ```
 * adb shell am broadcast -a com.sankatsetu.app.debug.SIMULATE_INCOMING_SOS \
 *   --es nickname "Priya" --es category FIRE --ef lat 26.1445 --ef lon 91.7362 \
 *   --ei hops 2 -p com.sankatsetu.app
 * ```
 */
class SosSimulator(
    private val messageRouter: MessageRouter,
    private val peerDao: PeerDao,
    private val sosDao: SosDao,
    private val scope: CoroutineScope
) {
    fun register(context: Context) {
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val nickname = intent.getStringExtra("nickname") ?: "Simulated peer"
                    val categoryName = intent.getStringExtra("category") ?: SosCategory.OTHER.name
                    val category = SosCategory.entries.find { it.name == categoryName } ?: SosCategory.OTHER
                    val lat = intent.getFloatExtra("lat", Float.NaN).takeIf { !it.isNaN() }?.toDouble()
                    val lon = intent.getFloatExtra("lon", Float.NaN).takeIf { !it.isNaN() }?.toDouble()
                    val hops = intent.getIntExtra("hops", 2)
                    android.util.Log.d("SosSimulator", "onReceive: nickname=$nickname category=$categoryName lat=$lat lon=$lon hops=$hops")
                    scope.launch {
                        try {
                            simulate(nickname, category, lat, lon, hops)
                        } catch (t: Throwable) {
                            android.util.Log.e("SosSimulator", "simulate() failed", t)
                        }
                    }
                }
            },
            IntentFilter("com.sankatsetu.app.debug.SIMULATE_INCOMING_SOS"),
            // Must be exported: `adb shell am broadcast` sends as the shell
            // UID, a different app from this one, so RECEIVER_NOT_EXPORTED
            // silently drops it -- the real bug found running this the
            // first time (no crash, no log, just nothing happened).
            // Acceptable only because the whole class is compiled out of
            // release builds (BuildConfig.DEBUG gate in
            // SankatSetuApplication) and the action does nothing beyond
            // inserting local test data.
            Context.RECEIVER_EXPORTED
        )
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val nickname = intent.getStringExtra("nickname") ?: "Simulated peer"
                    val lat = intent.getFloatExtra("lat", Float.NaN).takeIf { !it.isNaN() }?.toDouble()
                    val lon = intent.getFloatExtra("lon", Float.NaN).takeIf { !it.isNaN() }?.toDouble()
                    val hops = intent.getIntExtra("hops", 1)
                    scope.launch {
                        try {
                            simulateAnnounce(nickname, lat, lon, hops)
                        } catch (t: Throwable) {
                            android.util.Log.e("SosSimulator", "simulateAnnounce() failed", t)
                        }
                    }
                }
            },
            IntentFilter("com.sankatsetu.app.debug.SIMULATE_INCOMING_PEER"),
            Context.RECEIVER_EXPORTED
        )
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    scope.launch { sosDao.deleteAll() }
                }
            },
            IntentFilter("com.sankatsetu.app.debug.CLEAR_SOS_LOG"),
            Context.RECEIVER_EXPORTED
        )
    }

    private suspend fun simulateAnnounce(nickname: String, lat: Double?, lon: Double?, hops: Int) {
        val fakePeerId = ByteArray(MeshPacket.SENDER_ID_SIZE).also { SecureRandom().nextBytes(it) }
        val fakeNoiseKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val fakeSigningKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val announce = AnnouncementPacket(
            nickname = nickname,
            noisePublicKey = fakeNoiseKey,
            signingPublicKey = fakeSigningKey,
            latitude = lat,
            longitude = lon
        )
        val encodedPayload = announce.encode() ?: return
        val meshPacket = MeshPacket(
            type = MessageType.ANNOUNCE,
            ttl = (MeshPacket.DEFAULT_TTL - hops).coerceAtLeast(0).toByte(),
            timestamp = System.currentTimeMillis(),
            senderId = fakePeerId,
            payload = encodedPayload
        )
        val raw = BinaryProtocol.encode(meshPacket, padding = false)
        messageRouter.handleInboundBytes("debug-simulated-link", raw)
    }

    private suspend fun simulate(nickname: String, category: SosCategory, lat: Double?, lon: Double?, hops: Int) {
        val fakePeerId = ByteArray(MeshPacket.SENDER_ID_SIZE).also { SecureRandom().nextBytes(it) }
        val fakePeerIdB64 = Base64.encodeToString(fakePeerId, Base64.NO_WRAP)

        // A real incoming SOS shows whatever nickname (if any) this device
        // already has on file for that sender -- see SosManager's own doc
        // on why a never-announced stranger still displays, just as
        // "Unknown device". Upserting a fake peer record first is what lets
        // this simulate the common case (a peer you've already seen) rather
        // than only the stranger fallback.
        peerDao.upsert(
            PeerEntity(
                peerIdBase64 = fakePeerIdB64,
                noiseStaticKeyBase64 = "",
                nickname = nickname,
                firstSeen = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
                lastKnownHopCount = hops
            )
        )

        val sosPacket = SosPacket(category = category, latitude = lat, longitude = lon)
        val encodedPayload = sosPacket.encode() ?: return
        val meshPacket = MeshPacket(
            type = MessageType.SOS_BROADCAST,
            ttl = (MeshPacket.DEFAULT_TTL - hops).coerceAtLeast(0).toByte(),
            timestamp = System.currentTimeMillis(),
            senderId = fakePeerId,
            payload = encodedPayload
        )
        val raw = BinaryProtocol.encode(meshPacket, padding = false)
        android.util.Log.d("SosSimulator", "encoded meshPacket ttl=${meshPacket.ttl} rawSize=${raw.size} senderId=$fakePeerIdB64")
        // The same call MeshTransport makes for a byte string that actually
        // arrived over BLE (see MessageRouter.handleInboundBytes's own doc)
        // -- "debug-simulated-link" never matches a real link ID, which is
        // fine, it's only used for liveness bookkeeping on that link.
        messageRouter.handleInboundBytes("debug-simulated-link", raw)
        android.util.Log.d("SosSimulator", "handleInboundBytes returned")
    }
}
