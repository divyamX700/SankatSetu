package com.sankatsetu.app.mesh.crypto

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState

/**
 * Wraps a single Noise `XX` handshake + transport session with one mesh
 * peer, using rweather/noise-java (`com.southernstorm.noise.protocol`) as
 * the underlying implementation — see NOTICE.md and docs/adr/0002 for why we
 * depend on this library rather than hand-rolling X25519/ChaCha20-Poly1305.
 *
 * Noise `XX` (mutual authentication + forward secrecy) is used for **live**
 * sessions only. Sealed store-and-forward mail (Day 2, courier envelopes)
 * uses the one-way `X` pattern instead and does NOT get forward secrecy —
 * that gap is inherited from Bitchat's design and documented, not hidden.
 * See docs/concepts/noise-encryption.md.
 *
 * Handshake flow (3 messages, XX pattern):
 * ```
 * Initiator -> Responder : e
 * Responder -> Initiator : e, ee, s, es
 * Initiator -> Responder : s, se
 * ```
 * After the third message, both sides call [split] and get a [CipherStatePair]
 * — one [com.southernstorm.noise.protocol.CipherState] for sending, one for
 * receiving. Every encrypted payload after that point carries forward secrecy:
 * compromising a static key later cannot decrypt traffic recorded during
 * this session.
 */
class NoiseSession(
    private val localStaticPrivateKey: ByteArray,
    private val localStaticPublicKey: ByteArray,
    isInitiator: Boolean
) {
    private val protocolName = "Noise_XX_25519_ChaChaPoly_SHA256"

    private val handshakeState: HandshakeState = HandshakeState(
        protocolName,
        if (isInitiator) HandshakeState.INITIATOR else HandshakeState.RESPONDER
    ).also { hs ->
        hs.localKeyPair.setPrivateKey(localStaticPrivateKey, 0)
        hs.start()
    }

    private var transport: CipherStatePair? = null

    val isEstablished: Boolean get() = transport != null

    /** The verified remote static public key, available only after the handshake completes. */
    val remoteStaticPublicKey: ByteArray?
        get() = if (isEstablished) {
            ByteArray(handshakeState.remotePublicKey.publicKeyLength).also {
                handshakeState.remotePublicKey.getPublicKey(it, 0)
            }
        } else null

    /**
     * Produces the next outbound handshake message, or null if the handshake
     * needs to *read* next (i.e. it's the other side's turn).
     */
    fun nextHandshakeMessage(): ByteArray? {
        if (handshakeState.action != HandshakeState.WRITE_MESSAGE) return null
        val buffer = ByteArray(HANDSHAKE_MESSAGE_MAX)
        val len = handshakeState.writeMessage(buffer, 0, null, 0, 0)
        maybeSplit()
        return buffer.copyOf(len)
    }

    /** Feeds an inbound handshake message. Call [nextHandshakeMessage] afterward to see if a reply is due. */
    fun consumeHandshakeMessage(message: ByteArray) {
        if (handshakeState.action != HandshakeState.READ_MESSAGE) return
        // Unlike writeMessage(), readMessage() cannot take a null payload
        // buffer even for an empty handshake payload — it calls .length on
        // it unconditionally internally (confirmed via real-device crash:
        // NullPointerException at HandshakeState.readMessage). Plaintext
        // output is never longer than the ciphertext it came from, so
        // sizing the scratch buffer to message.size is always sufficient.
        val payloadBuffer = ByteArray(message.size)
        handshakeState.readMessage(message, 0, message.size, payloadBuffer, 0)
        maybeSplit()
    }

    private fun maybeSplit() {
        if (handshakeState.action == HandshakeState.SPLIT) {
            transport = handshakeState.split()
        }
    }

    fun encrypt(plaintext: ByteArray): ByteArray? {
        val pair = transport ?: return null
        val sender = pair.sender
        val ciphertext = ByteArray(plaintext.size + sender.macLength)
        val len = sender.encryptWithAd(null, plaintext, 0, ciphertext, 0, plaintext.size)
        return ciphertext.copyOf(len)
    }

    fun decrypt(ciphertext: ByteArray): ByteArray? {
        val pair = transport ?: return null
        val receiver = pair.receiver
        val plaintext = ByteArray(ciphertext.size)
        return try {
            val len = receiver.decryptWithAd(null, ciphertext, 0, plaintext, 0, ciphertext.size)
            plaintext.copyOf(len)
        } catch (e: javax.crypto.ShortBufferException) {
            null
        } catch (e: javax.crypto.BadPaddingException) {
            null // authentication failure — tampered or wrong session, never crash on this
        }
    }

    companion object {
        // Generous upper bound for a single XX handshake message (static key +
        // ephemeral key + auth tags never exceed ~200 bytes for this cipher
        // suite; padded well above that for safety).
        private const val HANDSHAKE_MESSAGE_MAX = 512
    }
}
