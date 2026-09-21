package com.sankatsetu.app.mesh.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.southernstorm.noise.crypto.Curve25519
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Per-device identity: one Curve25519 keypair for Noise key agreement, one
 * signing keypair for announces/broadcasts. Generated once on first launch
 * and never transmitted or backed up — this is what "no accounts, no phone
 * numbers" means in practice. See docs/concepts/ble-mesh-protocol.md#identity.
 *
 * The signing key lives in the Android Keystore (hardware-backed where
 * available) so the private key material never exists in JVM memory as raw
 * bytes. **Uses ECDSA on the P-256 curve, not Ed25519** — see
 * docs/adr/0007-ecdsa-not-ed25519.md: Ed25519 turned out to be unavailable
 * both from AndroidKeyStore (confirmed on a real API 34 emulator: the app
 * crashed on first launch with `NoSuchAlgorithmException: no such algorithm:
 * Ed25519 for provider AndroidKeyStore`) and from noise-java (which only
 * implements Curve25519/Curve448 for Diffie-Hellman, no Ed25519 signing
 * primitive at all). ECDSA/P-256 has been in AndroidKeyStore since API 18.
 *
 * The Curve25519 static key for Noise has to be usable by the pure-JVM
 * noise-java library, which cannot call into Keystore-held keys, so it is
 * generated in-process and persisted in SharedPreferences — see
 * docs/adr/0002's note on Keystore-agreement-capable curves as future work.
 */
class Identity private constructor(
    val noisePrivateKey: ByteArray,
    val noisePublicKey: ByteArray,
    private val keyStoreAlias: String
) {
    /** First 8 bytes of SHA-256(noisePublicKey) — the mesh peer ID. Stable until [wipe]. */
    val peerId: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest(noisePublicKey).copyOfRange(0, 8)
    }

    fun sign(data: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(keyStoreAlias, null) as java.security.PrivateKey
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey)
            update(data)
        }.sign()
    }

    fun verify(data: ByteArray, signature: ByteArray): Boolean {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val cert = keyStore.getCertificate(keyStoreAlias)
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initVerify(cert.publicKey)
            update(data)
        }.verify(signature)
    }

    fun signingPublicKeyBytes(): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val cert = keyStore.getCertificate(keyStoreAlias)
        return cert.publicKey.encoded // X.509 SubjectPublicKeyInfo; peers compare by fingerprint, not raw bytes
    }

    companion object {
        /**
         * Verifies a signature against an arbitrary *peer's* signing public
         * key (as broadcast in their [com.sankatsetu.app.mesh.protocol.AnnouncementPacket]),
         * not our own Keystore-held one — [verify] above only ever checks
         * against our own cert, which is useless for checking whether an
         * inbound IOU voucher was really signed by the peer it claims to be
         * from. Needed for [com.sankatsetu.app.payments.IouManager] to reject
         * a forged or tampered IOU before it ever shows in the UI as money
         * someone owes.
         */
        fun verifyWithPublicKey(publicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
            val publicKey = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyBytes))
            Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initVerify(publicKey)
                update(data)
            }.verify(signature)
        } catch (e: Exception) {
            false // malformed key or signature — never a valid IOU, never a crash
        }

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "sankatsetu.signing.ecdsa"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val PREFS_NAME = "sankatsetu.identity"
        private const val PREF_NOISE_PRIVATE = "noise_sk"
        private const val PREF_NOISE_PUBLIC = "noise_pk"

        /** Loads the existing identity, or generates and persists a new one. */
        fun loadOrCreate(context: Context): Identity {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ensureSigningKey()

            val existingSk = prefs.getString(PREF_NOISE_PRIVATE, null)
            val existingPk = prefs.getString(PREF_NOISE_PUBLIC, null)
            if (existingSk != null && existingPk != null) {
                return Identity(
                    Base64.decode(existingSk, Base64.NO_WRAP),
                    Base64.decode(existingPk, Base64.NO_WRAP),
                    KEYSTORE_ALIAS
                )
            }

            val sk = ByteArray(32)
            SecureRandom().nextBytes(sk)
            val pk = ByteArray(32)
            Curve25519.eval(pk, 0, sk, null)

            prefs.edit()
                .putString(PREF_NOISE_PRIVATE, Base64.encodeToString(sk, Base64.NO_WRAP))
                .putString(PREF_NOISE_PUBLIC, Base64.encodeToString(pk, Base64.NO_WRAP))
                .apply()

            return Identity(sk, pk, KEYSTORE_ALIAS)
        }

        /** Panic wipe (PRD §5.6, F1.6): destroys both key materials. Peer ID changes on next launch. */
        fun wipe(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEYSTORE_ALIAS)
            }
        }

        private fun ensureSigningKey() {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) return

            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }
    }
}
