package com.sankatsetu.app.maps

import android.content.Context

/** The last area downloaded for offline use — enough to re-render the map immediately on a later app launch without asking for location or re-downloading. */
data class DownloadedArea(
    val centerLat: Double,
    val centerLon: Double,
    val radiusMeters: Double,
    val downloadedAt: Long
)

/** SharedPreferences-backed, same pattern as [com.sankatsetu.app.mesh.crypto.NicknameStore] — this is small, non-sensitive metadata, not a fit for the encrypted Room database. */
class MapAreaStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): DownloadedArea? {
        if (!prefs.contains(KEY_LAT)) return null
        return DownloadedArea(
            centerLat = prefs.getFloat(KEY_LAT, 0f).toDouble(),
            centerLon = prefs.getFloat(KEY_LON, 0f).toDouble(),
            radiusMeters = prefs.getFloat(KEY_RADIUS, 0f).toDouble(),
            downloadedAt = prefs.getLong(KEY_DOWNLOADED_AT, 0L)
        )
    }

    fun save(area: DownloadedArea) {
        prefs.edit()
            .putFloat(KEY_LAT, area.centerLat.toFloat())
            .putFloat(KEY_LON, area.centerLon.toFloat())
            .putFloat(KEY_RADIUS, area.radiusMeters.toFloat())
            .putLong(KEY_DOWNLOADED_AT, area.downloadedAt)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "sankatsetu_map_area"
        const val KEY_LAT = "center_lat"
        const val KEY_LON = "center_lon"
        const val KEY_RADIUS = "radius_m"
        const val KEY_DOWNLOADED_AT = "downloaded_at"
    }
}
