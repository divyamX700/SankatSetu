package com.sankatsetu.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local-only, encrypted-at-rest persistence. Follows Flowpay's pattern
 * exactly (see docs/adr/0002): a random SQLCipher passphrase, generated once,
 * wrapped by a non-exportable Android Keystore AES key so the raw passphrase
 * never leaves hardware and is never itself stored in plaintext anywhere.
 *
 * Schema is intentionally small for Day 1 (peers + messages only). It grows
 * to the full docs/PRD.md §9.1 shape (IouVoucher, CourierEnvelope,
 * PublicHistory, KnowledgeChunk) across Day 2-3 — each addition is a real
 * Room migration, never `fallbackToDestructiveMigration`, matching Flowpay's
 * own rule about never silently wiping a user's data.
 */
@Database(
    entities = [PeerEntity::class, MessageEntity::class, IouEntity::class, SosEntity::class],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun iouDao(): IouDao
    abstract fun sosDao(): SosDao

    companion object {
        private const val DB_NAME = "sankatsetu.db"
        private const val KEYSTORE_ALIAS = "sankatsetu.db.wrapping_key"
        private const val PREFS_NAME = "sankatsetu.db_key"
        private const val PREF_WRAPPED_PASSPHRASE = "wrapped_passphrase"
        private const val PREF_IV = "wrap_iv"

        /** Adds read-receipt bookkeeping (see docs/adr/0011-link-reliability.md) — a real migration, never destructive. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN readReceiptSent INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the mesh IOU voucher table and peers.signingPublicKeyBase64 (see docs/adr/0012-mesh-iou-voucher.md) — a real migration, never destructive. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN signingPublicKeyBase64 TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `ious` (
                        `iouId` TEXT NOT NULL PRIMARY KEY,
                        `isOwedToMe` INTEGER NOT NULL,
                        `counterpartyPeerIdBase64` TEXT NOT NULL,
                        `counterpartyNickname` TEXT NOT NULL,
                        `amountPaise` INTEGER NOT NULL,
                        `memo` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `signatureBase64` TEXT NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ious_createdAt` ON `ious` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ious_status` ON `ious` (`status`)")
            }
        }

        /** Adds the SOS broadcast log table (see docs/TODO.md's SOS ideation and mesh/emergency/SosManager.kt) — a real migration, never destructive. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `sos_alerts` (
                        `sosId` TEXT NOT NULL PRIMARY KEY,
                        `senderPeerIdBase64` TEXT NOT NULL,
                        `senderNickname` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `hopCount` INTEGER NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `acknowledged` INTEGER NOT NULL,
                        `isOutgoing` INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sos_alerts_receivedAt` ON `sos_alerts` (`receivedAt`)")
            }
        }

        /** Adds real GPS coordinates to an SOS broadcast (see mesh/emergency/SosManager.kt and docs/adr/0021-sos-location.md) — a real migration, never destructive. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sos_alerts ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE sos_alerts ADD COLUMN longitude REAL")
            }
        }

        /** Adds real GPS coordinates to a peer's announce, for showing direct (1-hop) peers on the map (see mesh/protocol/AnnouncementPacket.kt and docs/adr/0022-peer-location.md) — a real migration, never destructive. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE peers ADD COLUMN longitude REAL")
            }
        }

        fun build(context: Context): AppDatabase {
            val passphrase = loadOrCreatePassphrase(context)
            val factory = SupportFactory(SQLiteDatabase.getBytes(passphrase))
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
        }

        /**
         * Returns the SQLCipher passphrase as a char array, generating and
         * Keystore-wrapping a new random one on first run. If the wrapping
         * key is ever lost (OS update, Keystore corruption) while the
         * wrapped blob survives, this throws rather than silently returning
         * garbage — the caller (AppContainer init) should catch that and
         * treat it exactly like Flowpay's DatabaseKeyManager does: discard
         * the unreadable database and start fresh rather than crash-loop.
         * Not yet wired for Day 1 (see docs/adr/0002 open item).
         */
        private fun loadOrCreatePassphrase(context: Context): CharArray {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingWrapped = prefs.getString(PREF_WRAPPED_PASSPHRASE, null)
            val existingIv = prefs.getString(PREF_IV, null)

            val wrappingKey = wrappingKey()

            if (existingWrapped != null && existingIv != null) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val iv = Base64.decode(existingIv, Base64.NO_WRAP)
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
                val raw = cipher.doFinal(Base64.decode(existingWrapped, Base64.NO_WRAP))
                return String(raw, Charsets.UTF_8).toCharArray()
            }

            val passphraseBytes = ByteArray(32)
            SecureRandom().nextBytes(passphraseBytes)
            val passphrase = Base64.encodeToString(passphraseBytes, Base64.NO_WRAP) // printable, simplest to round-trip as SQLCipher key material

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val wrapped = cipher.doFinal(passphrase.toByteArray(Charsets.UTF_8))

            prefs.edit()
                .putString(PREF_WRAPPED_PASSPHRASE, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()

            return passphrase.toCharArray()
        }

        private fun wrappingKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }

        /** Panic wipe: deletes the database file and the wrapping-key material. */
        fun wipe(context: Context) {
            context.deleteDatabase(DB_NAME)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEYSTORE_ALIAS)
            }
        }
    }
}
