package com.sankatsetu.app.data

import android.util.Base64
import androidx.room.TypeConverter

/** Room can't index/store raw ByteArray columns directly on all backends — base64 round-trip. */
class Converters {
    @TypeConverter
    fun fromBase64(value: String?): ByteArray? = value?.let { Base64.decode(it, Base64.NO_WRAP) }

    @TypeConverter
    fun toBase64(bytes: ByteArray?): String? = bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
}
