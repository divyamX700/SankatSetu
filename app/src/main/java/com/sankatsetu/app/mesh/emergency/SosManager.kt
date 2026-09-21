package com.sankatsetu.app.mesh.emergency

import android.util.Base64
import com.sankatsetu.app.data.PeerDao
import com.sankatsetu.app.data.SosDao
import com.sankatsetu.app.data.SosEntity
import com.sankatsetu.app.maps.LocationProvider
import com.sankatsetu.app.maps.LocationResult
import com.sankatsetu.app.mesh.crypto.Identity
import com.sankatsetu.app.mesh.protocol.MeshPacket
import com.sankatsetu.app.mesh.protocol.MessageType
import com.sankatsetu.app.mesh.protocol.SosCategory
import com.sankatsetu.app.mesh.protocol.SosPacket
import com.sankatsetu.app.mesh.router.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * SOS broadcast — the counterpart to "I'm Safe," see docs/TODO.md's
 * ideation for the full reasoning behind every choice here.
 *
 * Unlike [com.sankatsetu.app.payments.IouManager], this never signs or
 * Noise-encrypts anything: an SOS is meant to reach and be readable by
 * every phone in range, including a stranger with no completed handshake,
 * the same way [MessageType.ANNOUNCE] already floods. Flood volume is
 * bounded by Cedar's own `sos` resource cap (5/minute per sender, see
 * `assets/cedar/policies.cedar`), enforced inside [MessageRouter] itself —
 * nothing extra to wire here.
 *
 * Follows [com.sankatsetu.app.payments.IouManager]'s composition-root
 * pattern: an independent collector on [MessageRouter.inboundApplicationPackets]
 * (a multicast `SharedFlow`, so this and `ChatViewModel`/`IouManager` each
 * get their own copy of every packet) rather than routing SOS handling
 * through `ChatViewModel`.
 */
class SosManager(
    private val identity: Identity,
    private val router: MessageRouter,
    private val peerDao: PeerDao,
    private val sosDao: SosDao,
    private val locationProvider: LocationProvider,
    scope: CoroutineScope
) {
    fun observeAll(): Flow<List<SosEntity>> = sosDao.observeAll()

    init {
        scope.launch { observeInbound() }
    }

    /**
     * Broadcasts a new SOS and records it locally as our own outgoing
     * report — the mesh never echoes our own origin packet back to us (see
     * [MessageRouter.broadcast]'s `seenCache.markIfNew` call), so without
     * this the sender's own log would never show what they just sent.
     *
     * Waits up to [LOCATION_TIMEOUT_MS] for a location fix before sending —
     * deliberately short, not [LocationProvider]'s own 30s default: this is
     * a one-handed emergency action (see docs/PRODUCT.md's Product
     * Principle 2), and blocking it on a slow or unavailable GPS fix would
     * be worse than sending without coordinates. A cached recent fix (e.g.
     * from already having opened the Map tab) still resolves near-instantly
     * either way. See docs/adr/0021-sos-location.md.
     */
    suspend fun broadcastSos(category: SosCategory): String? {
        val fix = locationProvider.getCurrentFix(timeoutMs = LOCATION_TIMEOUT_MS) as? LocationResult.Fix
        val packet = SosPacket(category = category, latitude = fix?.latitude, longitude = fix?.longitude)
        val encoded = packet.encode() ?: return null
        router.broadcast(MessageType.SOS_BROADCAST, encoded, sign = false, padded = false)

        sosDao.insert(
            SosEntity(
                sosId = packet.sosId,
                senderPeerIdBase64 = Base64.encodeToString(identity.peerId, Base64.NO_WRAP),
                senderNickname = "You",
                category = category.name,
                hopCount = 0,
                receivedAt = packet.createdAt,
                acknowledged = true, // nothing to acknowledge about your own report
                isOutgoing = true,
                latitude = packet.latitude,
                longitude = packet.longitude
            )
        )
        return packet.sosId
    }

    suspend fun acknowledge(sosId: String) = sosDao.acknowledge(sosId)

    private suspend fun observeInbound() {
        router.inboundApplicationPackets.collect { packet ->
            if (packet.type == MessageType.SOS_BROADCAST) handleIncoming(packet)
        }
    }

    private suspend fun handleIncoming(packet: MeshPacket) {
        val sos = SosPacket.decode(packet.payload) ?: return
        val senderPeerIdB64 = Base64.encodeToString(packet.senderId, Base64.NO_WRAP)
        // Unlike IouManager, a sender we've never seen an announce from is
        // still shown, not dropped: the whole point of an unsigned flooded
        // broadcast is that even a stranger's SOS reaches and displays —
        // requiring a known peer record first would defeat that.
        val senderNickname = peerDao.getByPeerId(senderPeerIdB64)?.nickname ?: "Unknown device"

        sosDao.insert(
            SosEntity(
                sosId = sos.sosId,
                senderPeerIdBase64 = senderPeerIdB64,
                senderNickname = senderNickname,
                category = sos.category.name,
                hopCount = MeshPacket.DEFAULT_TTL - packet.ttl,
                receivedAt = System.currentTimeMillis(),
                acknowledged = false,
                isOutgoing = false,
                latitude = sos.latitude,
                longitude = sos.longitude
            )
        )
    }

    companion object {
        private const val LOCATION_TIMEOUT_MS = 5_000L
    }
}
