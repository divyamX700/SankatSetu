package com.sankatsetu.app.ui.chat

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sankatsetu.app.data.MessageDao
import com.sankatsetu.app.data.MessageEntity
import com.sankatsetu.app.data.PeerDao
import com.sankatsetu.app.data.PeerEntity
import com.sankatsetu.app.mesh.crypto.Identity
import com.sankatsetu.app.mesh.crypto.NicknameStore
import com.sankatsetu.app.mesh.crypto.NoiseSession
import com.sankatsetu.app.maps.LocationProvider
import com.sankatsetu.app.mesh.protocol.AnnouncementPacket
import com.sankatsetu.app.mesh.protocol.MeshPacket
import com.sankatsetu.app.mesh.protocol.MessageType
import com.sankatsetu.app.mesh.protocol.PrivateMessagePacket
import com.sankatsetu.app.mesh.router.MessageRouter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * How long a Noise handshake gets to complete before [ChatViewModel]
 * retries it on the peer's next announce. Real two-phone field testing
 * found a peer stuck in "CONNECTING" indefinitely because the single
 * handshake attempt's reply was silently dropped at the BLE transport
 * layer (a real `PeripheralLink` central-subscription race, not a Kotlin
 * bug — see `MeshTransport.kt`'s own `subscribedCentrals` tracking).
 * Peers re-announce periodically already, so 15s is generous slack past
 * a normal handshake's round-trip time before assuming this one attempt
 * was lost and trying again.
 */
private const val HANDSHAKE_RETRY_TIMEOUT_MS = 15_000L

data class PeerUiModel(
    val peerIdBase64: String,
    val nickname: String,
    val hopCount: Int,
    val handshakeEstablished: Boolean,
    /** Is there currently a live mesh link to this peer? False doesn't mean unreachable forever — see [MessageRouter.peerLinkEvents]. */
    val connected: Boolean
)

/**
 * Discriminates what's inside a [MessageType.NOISE_ENCRYPTED] plaintext once
 * decrypted, so delivery/read receipts can travel through the same
 * end-to-end-encrypted session as the chat text itself rather than as a
 * separate unencrypted packet type. See docs/adr/0011-link-reliability.md.
 */
private object EnvelopeKind {
    const val TEXT: Byte = 0x00
    const val DELIVERED: Byte = 0x01
    const val READ: Byte = 0x02
}

data class ChatUiState(
    val peers: List<PeerUiModel> = emptyList(),
    val bluetoothOn: Boolean = true
)

/**
 * Day 1 scope: peer discovery via announces, one-hop-aware peer list, Noise
 * XX handshake lifecycle per peer, and encrypted 1:1 text messaging. Public
 * `#public` broadcast, courier store-and-forward, and gossip sync are Day 2.
 *
 * This is the piece that turns [MessageRouter]'s raw [MeshPacket] stream
 * into something a screen can render — see docs/concepts/ble-mesh-protocol.md
 * for how announce/handshake/encrypted flows fit together end to end.
 */
class ChatViewModel(
    private val identity: Identity,
    private val router: MessageRouter,
    private val peerDao: PeerDao,
    private val messageDao: MessageDao,
    private val nicknameStore: NicknameStore,
    /** The real adapter state (AppContainer.bluetoothOn) — see that property's doc for why this can't be a value ChatViewModel invents itself. */
    private val bluetoothState: StateFlow<Boolean>,
    private val locationProvider: LocationProvider
) : ViewModel() {

    // One Noise session per peer we've ever started a handshake with.
    private val sessions = ConcurrentHashMap<String, NoiseSession>()
    // When each session in [sessions] was created — see [handleAnnounce]'s
    // stale-session retry for why this exists.
    private val sessionStartedAt = ConcurrentHashMap<String, Long>()
    private val knownNicknames = ConcurrentHashMap<String, String>()
    private val knownHopCounts = ConcurrentHashMap<String, Int>()

    // Populated from router.peerLinkEvents — a live view of which peer
    // identities currently have at least one real link, independent of
    // NoiseSession.isEstablished (which, once true, never resets on its own
    // and was exactly why "Ready to chat" kept showing after a real
    // disconnect during range testing).
    private val _connectedPeerIds = MutableStateFlow<Set<String>>(emptySet())
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState

    fun threadMessages(peerIdBase64: String) = messageDao.observeThread(peerIdBase64)

    init {
        viewModelScope.launch {
            combine(peerDao.observeAll(), bluetoothState, _connectedPeerIds) { peers, btOn, connectedIds ->
                ChatUiState(
                    peers = peers.map { p ->
                        PeerUiModel(
                            peerIdBase64 = p.peerIdBase64,
                            nickname = knownNicknames[p.peerIdBase64] ?: p.nickname,
                            hopCount = knownHopCounts[p.peerIdBase64] ?: p.lastKnownHopCount,
                            handshakeEstablished = sessions[p.peerIdBase64]?.isEstablished == true,
                            connected = connectedIds.contains(p.peerIdBase64)
                        )
                    },
                    bluetoothOn = btOn
                )
            }.collect { _uiState.value = it }
        }
        viewModelScope.launch { observeInbound() }
        viewModelScope.launch { observePeerLinkEvents() }
        viewModelScope.launch { announceLoop() }
    }

    /**
     * A peer identity becoming reachable again is exactly when queued
     * outbox messages for them should retry — this is a faster, more direct
     * path than waiting for their next announce (which can be up to 30s
     * away once both sides think the link is stable), and it's what makes a
     * message typed while out of range actually leave once back in range.
     */
    private suspend fun observePeerLinkEvents() {
        router.peerLinkEvents.collect { event ->
            val peerIdB64 = Base64.encodeToString(event.peerId, Base64.NO_WRAP)
            android.util.Log.i("ChatViewModel", "observePeerLinkEvents(): $peerIdB64 connected=${event.connected}")
            _connectedPeerIds.update { current -> if (event.connected) current + peerIdB64 else current - peerIdB64 }
            if (event.connected) router.retryOutbox(event.peerId)
        }
    }

    /**
     * Adaptive announce cadence per whitepaper §4.5: 4s while we have zero
     * connected peers ("isolated"), backing off to a jittered 15-30s once
     * we have at least one — no need to shout every 4s once discovery has
     * already worked.
     */
    private suspend fun announceLoop() {
        while (true) {
            sendAnnounce()
            val isolated = router.connectedLinkCount() == 0
            val delayMs = if (isolated) {
                4_000L
            } else {
                (15_000..30_000).random().toLong()
            }
            delay(delayMs)
        }
    }

    private suspend fun sendAnnounce() {
        // Cached-only, never a live GPS request -- see AnnouncementPacket's
        // own doc and LocationProvider.cachedFixOrNull's doc for why: this
        // fires every 4-30s, far too often to block on real location.
        // A peer only shows up on the map once this device has a fix
        // cached from something else real (opening the Map tab, sending an
        // SOS). See docs/adr/0022-peer-location.md.
        val fix = locationProvider.cachedFixOrNull()
        val packet = AnnouncementPacket(
            nickname = nicknameStore.get(),
            noisePublicKey = identity.noisePublicKey,
            signingPublicKey = identity.signingPublicKeyBytes(),
            latitude = fix?.latitude,
            longitude = fix?.longitude
        )
        val encoded = packet.encode() ?: return
        router.broadcast(MessageType.ANNOUNCE, encoded, sign = true)
    }

    fun selfNickname(): String = nicknameStore.get()

    /** The OS Bluetooth device name, offered as a rename suggestion only — see [NicknameStore]'s doc for why it's never applied automatically. */
    fun deviceBluetoothName(): String? = nicknameStore.deviceBluetoothName()

    /** Renames this device's own broadcast identity and re-announces immediately, instead of waiting for the next scheduled cycle. */
    fun renameSelf(newNickname: String) {
        nicknameStore.set(newNickname)
        viewModelScope.launch { sendAnnounce() }
    }

    private suspend fun observeInbound() {
        router.inboundApplicationPackets.collect { packet ->
            when (packet.type) {
                MessageType.ANNOUNCE -> handleAnnounce(packet)
                MessageType.NOISE_HANDSHAKE -> handleHandshake(packet)
                MessageType.NOISE_ENCRYPTED -> handleEncrypted(packet)
                else -> Unit // SOS/IOU/courier/etc. handled by their own ViewModels, Day 2-3
            }
        }
    }

    private suspend fun handleAnnounce(packet: MeshPacket) {
        val announce = AnnouncementPacket.decode(packet.payload) ?: return
        val peerIdB64 = Base64.encodeToString(packet.senderId, Base64.NO_WRAP)
        val hopCount = MeshPacket.DEFAULT_TTL - packet.ttl

        knownNicknames[peerIdB64] = announce.nickname
        knownHopCounts[peerIdB64] = hopCount.toInt()

        val now = System.currentTimeMillis()
        val existing = peerDao.getByPeerId(peerIdB64)
        if (existing == null) {
            peerDao.upsert(
                PeerEntity(
                    peerIdBase64 = peerIdB64,
                    noiseStaticKeyBase64 = Base64.encodeToString(announce.noisePublicKey, Base64.NO_WRAP),
                    nickname = announce.nickname,
                    firstSeen = now,
                    lastSeen = now,
                    lastKnownHopCount = hopCount.toInt(),
                    signingPublicKeyBase64 = Base64.encodeToString(announce.signingPublicKey, Base64.NO_WRAP),
                    latitude = announce.latitude,
                    longitude = announce.longitude
                )
            )
        } else {
            peerDao.touch(peerIdB64, now, hopCount.toInt(), announce.nickname, announce.latitude, announce.longitude)
        }

        // Any announce from a peer means the mesh currently has a path to
        // them (even if we don't yet have a live link to *them specifically*
        // — this announce reached us somehow), so retry anything queued for
        // them in the sender outbox (see MessageRouter.retryOutbox's doc).
        router.retryOutbox(packet.senderId)

        // Auto-initiate a handshake with any newly-discovered peer, not just
        // one-hop ones (the original Day 1 scope — see
        // docs/adr/0001-hackathon-scope-and-day1-slice.md, which explicitly
        // named "the two-phone one-hop encrypted chat gate" as the Day 1 bar
        // and multi-hop as deliberately deferred, not architecturally
        // excluded). A NOISE_HANDSHAKE packet is directed traffic, and
        // MessageRouter.relayDirected already floods directed traffic across
        // every hop up to the mesh's TTL budget exactly like ANNOUNCE and
        // MESSAGE do (see that function's own "no source-routing table yet"
        // doc) — the transport already supports this, this call site was the
        // only place still gating on hop count.
        //
        // Retries a STALE session, not just a missing one — a real two-phone
        // field test found a peer stuck showing "CONNECTING" indefinitely:
        // MeshTransport's own logs showed the handshake reply being dropped
        // ("PeripheralLink.send(): ... not subscribed, dropping N bytes"),
        // a real BLE central/peripheral subscription race, not a Kotlin bug.
        // Before this fix, `sessions[peerIdB64] == null` bounded the app to
        // exactly one handshake attempt for the entire process's lifetime —
        // if that single attempt's packets were lost at the transport layer
        // (proven above to actually happen), the peer was stuck forever with
        // no recovery short of forgetting the peer or restarting the app.
        // Peers already re-announce periodically (that's how liveness is
        // maintained at all), so re-checking staleness here needs no new
        // timer — it rides the existing announce cadence for free.
        val existingSession = sessions[peerIdB64]
        val isStale = existingSession != null && !existingSession.isEstablished &&
            (now - (sessionStartedAt[peerIdB64] ?: now)) > HANDSHAKE_RETRY_TIMEOUT_MS
        if (existingSession == null || isStale) {
            startHandshake(peerIdB64, packet.senderId, isInitiator = isLexicographicInitiator(packet.senderId))
        }
    }

    /** Both sides seeing each other's announce would otherwise race to both initiate; break the tie deterministically. */
    private fun isLexicographicInitiator(remotePeerId: ByteArray): Boolean {
        for (i in identity.peerId.indices) {
            val a = identity.peerId[i].toInt() and 0xFF
            val b = remotePeerId[i].toInt() and 0xFF
            if (a != b) return a < b
        }
        return false
    }

    private suspend fun startHandshake(peerIdB64: String, remotePeerId: ByteArray, isInitiator: Boolean) {
        val session = NoiseSession(identity.noisePrivateKey, identity.noisePublicKey, isInitiator)
        sessions[peerIdB64] = session
        sessionStartedAt[peerIdB64] = System.currentTimeMillis()
        if (isInitiator) {
            session.nextHandshakeMessage()?.let { msg ->
                router.sendDirected(MessageType.NOISE_HANDSHAKE, remotePeerId, msg)
            }
        }
    }

    private suspend fun handleHandshake(packet: MeshPacket) {
        val peerIdB64 = Base64.encodeToString(packet.senderId, Base64.NO_WRAP)
        val session = sessions[peerIdB64]
            ?: NoiseSession(identity.noisePrivateKey, identity.noisePublicKey, isInitiator = false)
                .also {
                    sessions[peerIdB64] = it
                    sessionStartedAt[peerIdB64] = System.currentTimeMillis()
                }

        session.consumeHandshakeMessage(packet.payload)
        session.nextHandshakeMessage()?.let { reply ->
            router.sendDirected(MessageType.NOISE_HANDSHAKE, packet.senderId, reply)
        }
    }

    private suspend fun handleEncrypted(packet: MeshPacket) {
        val peerIdB64 = Base64.encodeToString(packet.senderId, Base64.NO_WRAP)
        val session = sessions[peerIdB64] ?: return // no session: can't decrypt, drop (see NoiseSession.decrypt doc)
        val plaintext = session.decrypt(packet.payload) ?: return
        if (plaintext.isEmpty()) return
        val kind = plaintext[0]
        val body = plaintext.copyOfRange(1, plaintext.size)

        when (kind) {
            EnvelopeKind.TEXT -> {
                val privateMessage = PrivateMessagePacket.decode(body) ?: return
                messageDao.insert(
                    MessageEntity(
                        messageId = privateMessage.messageId,
                        threadPeerIdBase64 = peerIdB64,
                        senderPeerIdBase64 = peerIdB64,
                        body = privateMessage.content,
                        sentAt = packet.timestamp,
                        receivedAt = System.currentTimeMillis(),
                        hopCount = MeshPacket.DEFAULT_TTL - packet.ttl,
                        status = "delivered",
                        isOutgoing = false
                    )
                )
                // Tell the sender their message actually reached and decrypted
                // here — this is what turns a single grey tick into a double
                // one, instead of the sender never knowing either way.
                sendReceipt(packet.senderId, EnvelopeKind.DELIVERED, privateMessage.messageId)
            }
            EnvelopeKind.DELIVERED -> messageDao.advanceStatus(String(body, Charsets.UTF_8), "delivered")
            EnvelopeKind.READ -> messageDao.advanceStatus(String(body, Charsets.UTF_8), "read")
        }
    }

    private suspend fun sendReceipt(remotePeerId: ByteArray, kind: Byte, messageId: String) {
        val peerIdB64 = Base64.encodeToString(remotePeerId, Base64.NO_WRAP)
        val session = sessions[peerIdB64] ?: return
        if (!session.isEstablished) return
        val plaintext = byteArrayOf(kind) + messageId.toByteArray(Charsets.UTF_8)
        val ciphertext = session.encrypt(plaintext) ?: return
        router.sendDirected(MessageType.NOISE_ENCRYPTED, remotePeerId, ciphertext)
    }

    /**
     * Call when the user opens a thread: sends a read receipt for every
     * incoming message in it we haven't acknowledged yet, and marks them so
     * we don't re-send on every recomposition. This is the "blue tick"
     * half — [handleEncrypted]'s DELIVERED receipt is the "grey tick" half.
     */
    fun onThreadOpened(peerIdBase64: String) {
        viewModelScope.launch {
            val remotePeerId = Base64.decode(peerIdBase64, Base64.NO_WRAP)
            for (message in messageDao.getUnacknowledgedIncoming(peerIdBase64)) {
                sendReceipt(remotePeerId, EnvelopeKind.READ, message.messageId)
                messageDao.markReadReceiptSent(message.messageId)
            }
        }
    }

    fun sendMessage(peerIdBase64: String, text: String) {
        viewModelScope.launch { sendToPeer(peerIdBase64, text) }
    }

    /**
     * One-tap broadcast to every peer with an established session — the
     * American Red Cross "I'm Safe" pattern (docs/PRODUCT.md, Evidence on
     * Hand): the single most load-bearing feature in that research for
     * panic-state cognitive load, so it goes out as an ordinary message to
     * every ready thread rather than a new wire message type — one tap,
     * no typing required. See docs/adr/0014-field-radio-design-language.md.
     */
    fun broadcastImSafe() {
        viewModelScope.launch {
            sessions.filterValues { it.isEstablished }.keys.forEach { peerIdBase64 ->
                sendToPeer(peerIdBase64, "I'm safe.")
            }
        }
    }

    private suspend fun sendToPeer(peerIdBase64: String, text: String) {
        val session = sessions[peerIdBase64]
        if (session == null || !session.isEstablished) return // UI should disable send until handshake completes

        val privateMessage = PrivateMessagePacket(content = text)
        val encoded = privateMessage.encode() ?: return
        val plaintext = byteArrayOf(EnvelopeKind.TEXT) + encoded
        val ciphertext = session.encrypt(plaintext) ?: return
        val remotePeerId = Base64.decode(peerIdBase64, Base64.NO_WRAP)

        val now = System.currentTimeMillis()
        messageDao.insert(
            MessageEntity(
                messageId = privateMessage.messageId,
                threadPeerIdBase64 = peerIdBase64,
                senderPeerIdBase64 = Base64.encodeToString(identity.peerId, Base64.NO_WRAP),
                body = text,
                sentAt = now,
                receivedAt = now,
                hopCount = 0,
                status = "sending",
                isOutgoing = true
            )
        )

        val outcome = router.sendDirected(MessageType.NOISE_ENCRYPTED, remotePeerId, ciphertext)
        // Honest status: "sent" only if it actually left over a live
        // link right now. If there was no link at all, it's sitting in
        // the outbox — say so instead of falsely claiming "sent" (the
        // bug that made a queued-while-disconnected message look
        // identical to a delivered one).
        messageDao.updateStatus(privateMessage.messageId, if (outcome.queued) "queued" else "sent")
    }

    /** Clears one thread's local message history only — the peer keeps their own copy, and the session/peer entry itself is untouched, unlike [forgetPeer]. */
    fun clearThread(peerIdBase64: String) {
        viewModelScope.launch { messageDao.deleteThread(peerIdBase64) }
    }

    /** Removes a peer from local history — see [PeerDao.delete]'s doc for why this exists. Does not affect the peer's own device. */
    fun forgetPeer(peerIdBase64: String) {
        viewModelScope.launch {
            peerDao.delete(peerIdBase64)
            sessions.remove(peerIdBase64)
            sessionStartedAt.remove(peerIdBase64)
            knownNicknames.remove(peerIdBase64)
            knownHopCounts.remove(peerIdBase64)
        }
    }
}
