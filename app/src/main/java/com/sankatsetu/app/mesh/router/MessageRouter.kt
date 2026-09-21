package com.sankatsetu.app.mesh.router

import com.sankatsetu.app.mesh.authz.MessageKind
import com.sankatsetu.app.mesh.authz.MeshAuthorizer
import com.sankatsetu.app.mesh.protocol.BinaryProtocol
import com.sankatsetu.app.mesh.protocol.FragmentPacket
import com.sankatsetu.app.mesh.protocol.MeshPacket
import com.sankatsetu.app.mesh.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** A directed send either went out over a live link now, or was queued because no link exists at all. */
data class SendOutcome(val messageId: String, val queued: Boolean)

/** Fired whenever a peer identity transitions between reachable and unreachable — see [MessageRouter.peerLinkEvents]. */
data class PeerLinkEvent(val peerId: ByteArray, val connected: Boolean)

/**
 * Transport-agnostic packet dispatcher: the piece that turns "a bunch of BLE
 * links to nearby phones" into "a mesh". Owns TTL clamping, dedup, relay
 * jitter, and fanout subset selection. Ported behaviourally from Bitchat's
 * `MessageRouter.swift` + `BLEFanoutSelector.swift` (public domain); see
 * docs/concepts/ble-mesh-protocol.md for the full parameter table this file
 * implements against.
 *
 * [MeshTransport] feeds this class raw bytes off the wire and asks it to
 * relay/broadcast; this class never touches `BluetoothGatt` directly.
 *
 * Takes [localPeerId] and an optional [signer] rather than the whole
 * [com.sankatsetu.app.mesh.crypto.Identity] — the router only ever needs
 * "what's my ID" and "sign these bytes," and decoupling from Identity means
 * this class (and the mesh behaviour it implements) can be exercised in a
 * plain JVM unit test without an Android Keystore, see
 * `MessageRouterFragmentationTest`.
 */
class MessageRouter(
    private val localPeerId: ByteArray,
    private val scope: CoroutineScope,
    private val signer: ((ByteArray) -> ByteArray)? = null,
    private val seenCache: SeenMessageCache = SeenMessageCache(),
    private val fragmentAssembler: FragmentAssembler = FragmentAssembler(),
    private val outbox: SenderOutbox = SenderOutbox(),
    // Optional on purpose: absent in every existing JVM unit test (a plain
    // MessageRouter still relays everything, unchanged behaviour) and
    // absent whenever CedarAuthorizer itself is unavailable (no native lib
    // for this ABI) — see CedarAuthorizer's own fail-open doc. Real
    // wiring (a real CedarAuthorizer) lives in AppContainer; a router test
    // can pass a plain lambda instead, see MessageRouterAuthorizationTest.
    private val cedarAuthorizer: MeshAuthorizer? = null
) {
    private val links = ConcurrentHashMap<String, MeshLink>()
    private val linksLock = Mutex()

    // Which peer identity was last seen arriving over which raw link — lets us
    // tell the UI "this specific peer just became (un)reachable" rather than
    // just "some link changed." Keyed by a hex encoding of the peer ID (plain
    // Kotlin, no Android dependency, so this class still runs in a JVM test).
    private val linkPeerIds = ConcurrentHashMap<String, String>() // linkId -> hex(peerId)
    private val peerIdBytesByHex = ConcurrentHashMap<String, ByteArray>()

    private val _inboundApplicationPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
    /** Emits packets addressed to us (recipientId == our peer ID) or public broadcasts, post-dedup. */
    val inboundApplicationPackets: SharedFlow<MeshPacket> = _inboundApplicationPackets

    private val _peerLinkEvents = MutableSharedFlow<PeerLinkEvent>(extraBufferCapacity = 64)
    /** Emits whenever a peer identity (not just a raw link) becomes reachable or unreachable. */
    val peerLinkEvents: SharedFlow<PeerLinkEvent> = _peerLinkEvents

    // --- Link lifecycle, called by MeshTransport ---

    fun onLinkConnected(link: MeshLink) {
        links[link.linkId] = link
    }

    /**
     * A link dying doesn't necessarily mean the peer behind it is gone — with
     * role-split link IDs (see MeshTransport) the same peer can have both a
     * central-role and peripheral-role link simultaneously, and losing one
     * doesn't mean losing the other. Only fire a disconnect event once no
     * link at all still maps to that peer.
     */
    fun onLinkDisconnected(linkId: String) {
        links.remove(linkId)
        val peerHex = linkPeerIds.remove(linkId) ?: return
        val stillReachable = linkPeerIds.values.contains(peerHex)
        if (!stillReachable) {
            peerIdBytesByHex.remove(peerHex)?.let { peerId ->
                _peerLinkEvents.tryEmit(PeerLinkEvent(peerId, connected = false))
            }
        }
    }

    fun connectedLinkCount(): Int = links.size

    private fun recordLinkPeer(linkId: String, peerId: ByteArray) {
        val hex = peerId.toHexKey()
        val wasReachable = linkPeerIds.values.contains(hex)
        linkPeerIds[linkId] = hex
        peerIdBytesByHex[hex] = peerId
        if (!wasReachable) _peerLinkEvents.tryEmit(PeerLinkEvent(peerId, connected = true))
    }

    // --- Outbound ---

    /** Origin a new broadcast (public chat, announce, SOS, etc.) at the default TTL. */
    suspend fun broadcast(type: MessageType, payload: ByteArray, sign: Boolean = false, padded: Boolean = false) {
        val unsigned = MeshPacket(
            type = type,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = System.currentTimeMillis(),
            senderId = localPeerId,
            recipientId = null,
            payload = payload
        )
        val packet = if (sign) unsigned.copy(signature = signer!!(unsigned.signingBytes())) else unsigned
        seenCache.markIfNew(packet) // never relay our own origin back to ourselves
        relayToFanout(packet, ingressLinkId = null, padded = padded)
    }

    /**
     * Origin directed traffic (handshake, private message, IOU) toward a
     * known peer ID. If there is currently no mesh link to *anyone* — not
     * just no link to this specific recipient — the message can't possibly
     * get anywhere yet, so it's queued in [outbox] instead of being silently
     * dropped. [retryOutbox] resends it once a link exists again. This is
     * deliberately conservative: with at least one live link, we still
     * attempt the send (it may reach the recipient in one hop or relay
     * through whoever *is* connected) rather than queuing pre-emptively.
     */
    suspend fun sendDirected(type: MessageType, recipientId: ByteArray, payload: ByteArray, sign: Boolean = false, padded: Boolean = true): SendOutcome {
        val messageId = java.util.UUID.randomUUID().toString()
        if (links.isEmpty()) {
            outbox.enqueue(
                SenderOutbox.QueuedMessage(
                    messageId = messageId,
                    recipientId = recipientId,
                    type = type,
                    payload = payload,
                    sign = sign,
                    queuedAt = System.currentTimeMillis()
                )
            )
            return SendOutcome(messageId, queued = true)
        }

        val unsigned = MeshPacket(
            type = type,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = System.currentTimeMillis(),
            senderId = localPeerId,
            recipientId = recipientId,
            payload = payload
        )
        val packet = if (sign) unsigned.copy(signature = signer!!(unsigned.signingBytes())) else unsigned
        seenCache.markIfNew(packet)
        relayDirected(packet, ingressLinkId = null, padded = padded)
        return SendOutcome(messageId, queued = false)
    }

    /**
     * Re-attempts every outbox message queued for [recipientId]. Callers
     * (e.g. [com.sankatsetu.app.ui.chat.ChatViewModel] on receiving that
     * peer's announce) are responsible for knowing *when* a peer becomes
     * reachable again — the router itself only knows about raw links, not
     * which peer identity sits behind one, until an announce or handshake
     * resolves that mapping.
     */
    suspend fun retryOutbox(recipientId: ByteArray) {
        for (queued in outbox.pending(recipientId)) {
            outbox.recordAttempt(recipientId, queued.messageId)
            sendDirected(queued.type, queued.recipientId, queued.payload, queued.sign)
        }
    }

    // --- Inbound ---

    /** Called by [MeshTransport] whenever bytes arrive on any link. */
    suspend fun handleInboundBytes(fromLinkId: String, raw: ByteArray) {
        val packet = BinaryProtocol.decode(raw) ?: return // malformed — drop silently, don't crash the mesh
        if (packet.senderId.contentEquals(localPeerId)) return // our own packet came back around; ignore

        // Refresh liveness even for a duplicate/retransmitted packet — this is
        // what lets the UI show "disconnected" quickly instead of trusting a
        // stale Noise-session flag that never resets on its own.
        recordLinkPeer(fromLinkId, packet.senderId)

        if (!seenCache.markIfNew(packet)) return // duplicate: dedup absorbs it, no re-relay

        if (packet.type == MessageType.FRAGMENT) {
            handleFragment(packet, fromLinkId)
            return
        }

        // Cedar flood/blocked-peer gate — the choke point every real (non-
        // fragment, non-plumbing) packet passes through exactly once, after
        // dedup so retransmits of an already-seen packet don't count twice,
        // but before it's delivered to us or relayed any further. A denial
        // drops the packet silently, same as a malformed one above — see
        // docs/adr/0017 and CedarAuthorizer's fail-open doc for why this
        // never blocks traffic when Cedar itself is unavailable.
        cedarMessageKind(packet.type)?.let { kind ->
            if (cedarAuthorizer?.isAllowed(packet.senderId.toHexKey(), kind) == false) return
        }

        val isForUs = packet.recipientId == null || packet.recipientId.contentEquals(localPeerId)
        if (isForUs) _inboundApplicationPackets.tryEmit(packet)

        if (packet.ttl <= 0) return // hop budget exhausted, don't relay further

        val decremented = packet.decremented()
        if (packet.recipientId != null && !isForUs) {
            // Directed traffic not addressed to us: relay on toward the recipient.
            relayDirected(decremented, ingressLinkId = fromLinkId, padded = isNoiseType(packet.type))
        } else if (packet.recipientId == null) {
            // Broadcast: continue flooding to our fanout subset (minus the link it
            // came in on). Dense neighbourhoods clamp TTL down to bound total
            // flood volume — a message doesn't need 7 hops of budget when every
            // hop already reaches 6+ peers (whitepaper §4.2).
            val clamped = if (links.size >= DENSE_LINK_THRESHOLD && decremented.ttl > DENSE_BROADCAST_TTL_CAP) {
                decremented.copy(ttl = DENSE_BROADCAST_TTL_CAP)
            } else {
                decremented
            }
            relayToFanout(clamped, ingressLinkId = fromLinkId, padded = false)
        }
        // Directed traffic addressed to us: consumed above, nothing further to relay.
    }

    /**
     * A [MessageType.FRAGMENT] packet is itself relayed exactly like any
     * other packet (its own TTL/dedup/fanout already ran in the caller) —
     * what's special is that once every fragment for its ID has arrived,
     * the reassembled bytes are the *original* packet's full wire encoding,
     * fed straight back into [handleInboundBytes] as if freshly received.
     * That single re-entry point is what gives the reassembled packet its
     * own correct dedup/relay/delivery handling, whatever type it turns
     * out to be, without duplicating that logic here.
     */
    private suspend fun handleFragment(packet: MeshPacket, fromLinkId: String) {
        val fragment = FragmentPacket.decode(packet.payload) ?: return
        val reassembled = fragmentAssembler.addFragment(fragment)

        if (packet.ttl > 0) {
            val decremented = packet.decremented()
            if (packet.recipientId != null) {
                relayDirected(decremented, ingressLinkId = fromLinkId, padded = false)
            } else {
                relayToFanout(decremented, ingressLinkId = fromLinkId, padded = false)
            }
        }

        if (reassembled != null) {
            handleInboundBytes(fromLinkId, reassembled)
        }
    }

    // --- Relay mechanics ---

    private suspend fun relayToFanout(packet: MeshPacket, ingressLinkId: String?, padded: Boolean) {
        val candidateIds = linksLock.withLock { links.keys.filter { it != ingressLinkId } }
        if (candidateIds.isEmpty()) return

        val targetIds = if (FanoutSelector.shouldSubset(packet.type)) {
            val k = FanoutSelector.subsetSize(candidateIds.size)
            FanoutSelector.deterministicSubset(candidateIds, k, seed = fanoutSeed(packet))
        } else {
            candidateIds.toSet()
        }

        scheduleRelay(packet, targetIds, padded, directed = false)
    }

    private suspend fun relayDirected(packet: MeshPacket, ingressLinkId: String?, padded: Boolean) {
        // Day 1: no source-routing table yet (that's Day-2 gossip/topology work), so
        // directed traffic still floods — but with tight jitter and full fanout,
        // matching Bitchat's own fallback-to-flooding behaviour when no confirmed
        // route exists yet (whitepaper §4.3).
        val candidateIds = linksLock.withLock { links.keys.filter { it != ingressLinkId } }
        if (candidateIds.isEmpty()) return
        scheduleRelay(packet, candidateIds.toSet(), padded, directed = true)
    }

    private fun scheduleRelay(packet: MeshPacket, targetLinkIds: Set<String>, padded: Boolean, directed: Boolean) {
        val bytes = BinaryProtocol.encode(packet, padding = padded)
        val jitterRangeMs = if (directed) DIRECTED_JITTER_MS else broadcastJitterRange(targetLinkIds.size)

        // Payloads over one BLE write get fragmented — see FragmentPacket.kt.
        // Each fragment travels as its own [MessageType.FRAGMENT] packet,
        // carrying the same sender/recipient/ttl as the packet it came from,
        // so it relays and dedupes independently of the other fragments.
        val outboundFrames: List<ByteArray> = if (bytes.size <= FragmentPacket.DEFAULT_CHUNK_SIZE) {
            listOf(bytes)
        } else {
            FragmentPacket.split(bytes).map { fragment ->
                val fragmentPacket = MeshPacket(
                    type = MessageType.FRAGMENT,
                    ttl = packet.ttl,
                    timestamp = packet.timestamp,
                    senderId = packet.senderId,
                    recipientId = packet.recipientId,
                    payload = fragment.encode()
                )
                BinaryProtocol.encode(fragmentPacket, padding = false)
            }
        }

        for (linkId in targetLinkIds) {
            if (!links.containsKey(linkId)) continue // skip already-disconnected targets before even scheduling the jitter delay
            scope.launch {
                val jitter = Random.nextLong(jitterRangeMs.first, jitterRangeMs.last + 1)
                delay(jitter)
                // Re-check the link is still live post-jitter; a duplicate relay
                // arriving from elsewhere during the delay is already absorbed by
                // SeenMessageCache on the receiving end, so we don't need to
                // re-check seenCache here — we're the origin of this specific send.
                val link = links[linkId] ?: return@launch
                for (frame in outboundFrames) {
                    if (!link.send(frame)) break // link died mid-burst — stop sending remaining fragments to it
                }
            }
        }
    }

    private fun broadcastJitterRange(linkCount: Int): LongRange =
        if (linkCount >= DENSE_LINK_THRESHOLD) DENSE_JITTER_MS else SPARSE_JITTER_MS

    private fun fanoutSeed(packet: MeshPacket): String =
        packet.senderId.joinToString("") { "%02x".format(it) } + ":" + packet.timestamp

    private fun isNoiseType(type: MessageType): Boolean =
        type == MessageType.NOISE_HANDSHAKE || type == MessageType.NOISE_ENCRYPTED

    /**
     * Maps a wire [MessageType] to the Cedar resource vocabulary in
     * `assets/cedar/policies.cedar` — see that file's header for why this
     * is a 5-kind mapping and not the PRD's original per-channel model.
     * Returns null for mesh plumbing (LEAVE, COURIER_ENVELOPE,
     * REQUEST_SYNC, PING, PONG) that isn't user-facing content and isn't
     * flood-gated at all.
     */
    private fun cedarMessageKind(type: MessageType): MessageKind? = when (type) {
        MessageType.MESSAGE -> MessageKind.PUBLIC
        MessageType.SOS_BROADCAST -> MessageKind.SOS
        MessageType.IOU_ENVELOPE, MessageType.IOU_SETTLEMENT_ACK -> MessageKind.IOU
        MessageType.ANNOUNCE -> MessageKind.ANNOUNCE
        MessageType.NOISE_HANDSHAKE, MessageType.NOISE_ENCRYPTED -> MessageKind.DIRECTED
        else -> null
    }

    private fun ByteArray.toHexKey(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val DENSE_LINK_THRESHOLD = 6
        private const val DENSE_BROADCAST_TTL_CAP: Byte = 5
        private val SPARSE_JITTER_MS = 10L..220L
        private val DENSE_JITTER_MS = 40L..220L // widened, not just capped — see docs/concepts/ble-mesh-protocol.md
        private val DIRECTED_JITTER_MS = 5L..40L
    }
}
