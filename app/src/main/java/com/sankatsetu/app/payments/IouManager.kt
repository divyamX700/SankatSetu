package com.sankatsetu.app.payments

import android.util.Base64
import com.sankatsetu.app.data.IouDao
import com.sankatsetu.app.data.IouEntity
import com.sankatsetu.app.data.PeerDao
import com.sankatsetu.app.mesh.crypto.Identity
import com.sankatsetu.app.mesh.protocol.IouPacket
import com.sankatsetu.app.mesh.protocol.IouSettlementAck
import com.sankatsetu.app.mesh.protocol.MeshPacket
import com.sankatsetu.app.mesh.protocol.MessageType
import com.sankatsetu.app.mesh.router.MessageRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Mesh-signed IOU vouchers — see docs/PRD.md §F4 and
 * docs/adr/0012-mesh-iou-voucher.md.
 *
 * **This never moves real money.** [sendIou] records a cryptographically
 * signed promise-to-pay and delivers it over the mesh, exactly like a chat
 * message — no bank, UPI, or telecom API is touched. [markSettled] is a
 * manual, user-initiated action standing in for "I actually paid them back
 * through some real channel (UPI, cash, anything)" — this app deliberately
 * does not attempt to execute a real payment automatically for the same
 * reason it never auto-dials USSD codes (see `ui/pay/PayScreen.kt`): a
 * financial transfer needs an explicit, physical action from the person
 * whose money it is, every time.
 *
 * Signatures are verified against the sender's signing public key learned
 * from their [com.sankatsetu.app.mesh.protocol.AnnouncementPacket] (stored
 * in [com.sankatsetu.app.data.PeerEntity.signingPublicKeyBase64]) — an IOU
 * from an unknown peer, or one whose signature doesn't check out, is
 * dropped before it ever reaches the UI as money someone owes.
 */
class IouManager(
    private val identity: Identity,
    private val router: MessageRouter,
    private val peerDao: PeerDao,
    private val iouDao: IouDao,
    scope: CoroutineScope
) {
    fun observeAll(): Flow<List<IouEntity>> = iouDao.observeAll()

    init {
        scope.launch { observeInbound() }
    }

    /** Signs and sends a new IOU to [peerIdBase64], recording it locally as money *we* owe. */
    suspend fun sendIou(peerIdBase64: String, peerNickname: String, amountPaise: Long, memo: String): String? {
        val unsigned = IouPacket(amountPaise = amountPaise, memo = memo)
        val signature = identity.sign(unsigned.signingBytes())
        val signed = unsigned.copy(signature = signature)
        val encoded = signed.encode() ?: return null // memo/signature too long for the TLV format

        val remotePeerId = Base64.decode(peerIdBase64, Base64.NO_WRAP)
        router.sendDirected(MessageType.IOU_ENVELOPE, remotePeerId, encoded)

        iouDao.insert(
            IouEntity(
                iouId = signed.iouId,
                isOwedToMe = false,
                counterpartyPeerIdBase64 = peerIdBase64,
                counterpartyNickname = peerNickname,
                amountPaise = amountPaise,
                memo = memo,
                createdAt = signed.createdAt,
                status = "pending",
                signatureBase64 = Base64.encodeToString(signature, Base64.NO_WRAP)
            )
        )
        return signed.iouId
    }

    /**
     * Marks an IOU we owe as settled and tells the counterparty. Call this
     * only after the money has actually changed hands through a real
     * channel — see the class doc.
     */
    suspend fun markSettled(iouId: String) {
        val iou = iouDao.getById(iouId) ?: return
        iouDao.updateStatus(iouId, "settled")
        val remotePeerId = Base64.decode(iou.counterpartyPeerIdBase64, Base64.NO_WRAP)
        router.sendDirected(MessageType.IOU_SETTLEMENT_ACK, remotePeerId, IouSettlementAck(iouId).encode())
    }

    /** Marks an IOU owed *to us* as rejected (e.g. we don't recognize the debt) — local-only, no mesh message. */
    suspend fun rejectIou(iouId: String) {
        iouDao.updateStatus(iouId, "rejected")
    }

    private suspend fun observeInbound() {
        router.inboundApplicationPackets.collect { packet ->
            when (packet.type) {
                MessageType.IOU_ENVELOPE -> handleIncomingIou(packet)
                MessageType.IOU_SETTLEMENT_ACK -> handleSettlementAck(packet)
                else -> Unit
            }
        }
    }

    private suspend fun handleIncomingIou(packet: MeshPacket) {
        val iouPkt = IouPacket.decode(packet.payload) ?: return
        val senderPeerIdB64 = Base64.encodeToString(packet.senderId, Base64.NO_WRAP)
        val senderPeer = peerDao.getByPeerId(senderPeerIdB64) ?: return // no announce seen from them yet — no key to verify against
        if (senderPeer.signingPublicKeyBase64.isEmpty()) return

        val signingKey = Base64.decode(senderPeer.signingPublicKeyBase64, Base64.NO_WRAP)
        val validSignature = Identity.verifyWithPublicKey(signingKey, iouPkt.signingBytes(), iouPkt.signature)
        if (!validSignature) return // forged or corrupted — silently dropped, never shown as money owed

        iouDao.insert(
            IouEntity(
                iouId = iouPkt.iouId,
                isOwedToMe = true,
                counterpartyPeerIdBase64 = senderPeerIdB64,
                counterpartyNickname = senderPeer.nickname,
                amountPaise = iouPkt.amountPaise,
                memo = iouPkt.memo,
                createdAt = iouPkt.createdAt,
                status = "pending",
                signatureBase64 = Base64.encodeToString(iouPkt.signature, Base64.NO_WRAP)
            )
        )
    }

    private suspend fun handleSettlementAck(packet: MeshPacket) {
        val ack = IouSettlementAck.decode(packet.payload) ?: return
        iouDao.updateStatus(ack.iouId, "settled")
    }
}
