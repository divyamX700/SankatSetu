package com.sankatsetu.app.maps

import com.sankatsetu.app.BuildConfig
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * MapTiler's raster tile endpoint, not `osmdroid`'s built-in
 * `TileSourceFactory.MAPNIK` (the public OSM tile server) — real, on-device
 * finding: `MAPNIK`'s own `TileSourcePolicy` sets `FLAG_NO_BULK`, and
 * `CacheManager.downloadAreaAsyncNoUI` throws `TileSourcePolicyException`
 * immediately on any source carrying that flag. That's not a client-side
 * bug to route around — the public server's real usage policy genuinely
 * forbids bulk/app-embedded downloading, and `osmdroid` enforces it in
 * code rather than letting an app find out by getting rate-limited or
 * banned in production. MapTiler's free tier (no card, ~100k loads/month)
 * permits exactly this use, so this is a different tile source entirely,
 * not the same one with the guardrail silenced.
 *
 * [BuildConfig.MAPTILER_API_KEY] comes from the machine-local, gitignored
 * `local.properties` (see `app/build.gradle.kts`) — never committed. A
 * fresh clone with no key configured gets an empty string here, and the
 * download will fail with a real, honest network/auth error rather than a
 * silently wrong one.
 */
object MapTileSource {
    val streets: XYTileSource = XYTileSource(
        "MapTiler-Streets",
        0,
        19,
        256,
        ".png?key=${BuildConfig.MAPTILER_API_KEY}",
        arrayOf("https://api.maptiler.com/maps/streets-v2/"),
        "© MapTiler © OpenStreetMap contributors",
        TileSourcePolicy(2, TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL or TileSourcePolicy.FLAG_USER_AGENT_NORMALIZED)
    )
}
