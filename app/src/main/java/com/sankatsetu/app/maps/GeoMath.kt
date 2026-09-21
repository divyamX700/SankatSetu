package com.sankatsetu.app.maps

import kotlin.math.cos

/** A plain lat/lon bounding box — deliberately not osmdroid's `BoundingBox` so this file has zero Android dependency and can be unit-tested on the plain JVM, matching this project's own convention for protocol/router math (see mesh/protocol, mesh/router). */
data class LatLonBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

/**
 * The "2km radius around me" math backing the offline map download — see
 * `docs/adr/0020-offline-maps.md` (once written) for why a rectangle
 * circumscribing the circle, not the circle itself: every offline map tile
 * source (osmdroid's `CacheManager` included) downloads rectangular tile
 * grids, not arbitrary shapes, so the honest on-screen claim is "at least
 * this radius in every direction," not "exactly a circle."
 */
object GeoMath {
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

    /**
     * A bounding box guaranteed to contain every point within [radiusMeters]
     * of ([centerLat], [centerLon]). Longitude degrees shrink toward the
     * poles (a real fact, not an approximation error) — that's what the
     * `cos(latitude)` term corrects for; without it, a box built for a
     * high-latitude city would be far narrower east-west than the radius
     * actually requires.
     */
    fun boundingBoxForRadius(centerLat: Double, centerLon: Double, radiusMeters: Double): LatLonBox {
        val deltaLat = radiusMeters / METERS_PER_DEGREE_LATITUDE
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.01) // guard near the poles, never divide by ~0
        val deltaLon = radiusMeters / (METERS_PER_DEGREE_LATITUDE * cosLat)
        return LatLonBox(
            minLat = (centerLat - deltaLat).coerceIn(-90.0, 90.0),
            maxLat = (centerLat + deltaLat).coerceIn(-90.0, 90.0),
            minLon = centerLon - deltaLon,
            maxLon = centerLon + deltaLon
        )
    }
}
