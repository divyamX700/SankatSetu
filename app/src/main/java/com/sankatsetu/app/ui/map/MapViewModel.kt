package com.sankatsetu.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sankatsetu.app.data.PeerDao
import com.sankatsetu.app.maps.DownloadedArea
import com.sankatsetu.app.maps.LocationProvider
import com.sankatsetu.app.maps.LocationResult
import com.sankatsetu.app.maps.MapAreaStore
import com.sankatsetu.app.mesh.emergency.SosManager
import com.sankatsetu.app.mesh.protocol.SosCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class MapUiState {
    data object Idle : MapUiState()
    data object RequestingLocation : MapUiState()
    data class PreparingDownload(val lat: Double, val lon: Double, val radiusMeters: Double) : MapUiState()
    data class Downloading(val progressPercent: Int, val currentZoom: Int, val zoomMin: Int, val zoomMax: Int) : MapUiState()
    data class Ready(val area: DownloadedArea) : MapUiState()
    data class Error(val message: String) : MapUiState()
}

/** One SOS alert with a real location fix attached — see docs/adr/0021-sos-location.md. Alerts with no fix (SosEntity.latitude/longitude null) never appear here; there's nowhere honest to put them on a map. */
data class SosMapMarker(
    val sosId: String,
    val latitude: Double,
    val longitude: Double,
    val category: SosCategory,
    val senderNickname: String,
    val isOutgoing: Boolean
)

/** A direct (1-hop) peer with a location their last announce carried — see docs/adr/0022-peer-location.md. A peer more than 1 hop away, or one with no cached fix on their end, never appears here. */
data class PeerMapMarker(
    val peerIdBase64: String,
    val latitude: Double,
    val longitude: Double,
    val nickname: String
)

/**
 * Backs the Map tab — see `docs/adr/0020-offline-maps.md` (once written).
 * This ViewModel owns the decision ("what area, at what radius, should be
 * downloaded") and the persisted result; the actual `osmdroid` `MapView`/
 * `CacheManager` mechanics live in `MapScreen.kt` since they're real
 * Android `View` objects with no meaningful ViewModel-layer equivalent —
 * the screen reports progress back here via [onDownloadProgress] etc.
 * rather than this class reaching into UI-layer view objects itself.
 */
class MapViewModel(
    private val locationProvider: LocationProvider,
    private val mapAreaStore: MapAreaStore,
    sosManager: SosManager,
    peerDao: PeerDao
) : ViewModel() {
    private val _uiState = MutableStateFlow<MapUiState>(MapUiState.Idle)
    val uiState: StateFlow<MapUiState> = _uiState

    /** Every SOS alert that carries a real location fix, for the "all SOS marked" view opened from an emergency-log card's map button. */
    val sosMarkers: StateFlow<List<SosMapMarker>> = sosManager.observeAll()
        .map { alerts ->
            alerts.mapNotNull { alert ->
                val lat = alert.latitude
                val lon = alert.longitude
                if (lat == null || lon == null) return@mapNotNull null
                val category = SosCategory.entries.find { it.name == alert.category } ?: return@mapNotNull null
                SosMapMarker(alert.sosId, lat, lon, category, alert.senderNickname, alert.isOutgoing)
            }
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /**
     * Direct (1-hop) peers with a location on file — deliberately excludes
     * anyone further away: a relayed hop count is real (see PRODUCT.md's
     * Product Principle 4) and a multi-hop peer's *last known* position
     * could be stale by an unknown, unbounded amount by the time it's
     * relayed to us, unlike a 1-hop peer's own fresh announce.
     */
    val peerMarkers: StateFlow<List<PeerMapMarker>> = peerDao.observeAll()
        .map { peers ->
            peers.mapNotNull { peer ->
                val lat = peer.latitude
                val lon = peer.longitude
                if (lat == null || lon == null || peer.lastKnownHopCount > 1) return@mapNotNull null
                PeerMapMarker(peer.peerIdBase64, lat, lon, peer.nickname)
            }
        }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    /** Set when the Map tab is opened from a specific emergency-log card's "View on map" button — see MainActivity's onOpenMapForSos. */
    private val _highlightedSosId = MutableStateFlow<String?>(null)
    val highlightedSosId: StateFlow<String?> = _highlightedSosId

    fun highlightSos(sosId: String) {
        _highlightedSosId.value = sosId
    }

    init {
        mapAreaStore.get()?.let { _uiState.value = MapUiState.Ready(it) }
    }

    /** Call after the location permission has been requested (granted or not — [LocationProvider] itself reports PermissionDenied cleanly either way). */
    fun startDownload() {
        viewModelScope.launch {
            _uiState.value = MapUiState.RequestingLocation
            when (val result = locationProvider.getCurrentFix()) {
                is LocationResult.Fix -> _uiState.value = MapUiState.PreparingDownload(result.latitude, result.longitude, RADIUS_METERS)
                LocationResult.PermissionDenied -> _uiState.value = MapUiState.Error("Location access is needed to download a map for your area.")
                LocationResult.NoProviderAvailable -> _uiState.value = MapUiState.Error("No location provider is available on this device.")
                LocationResult.TimedOut -> _uiState.value = MapUiState.Error("Could not get a location fix in time. A clearer view of the sky helps GPS lock on faster.")
            }
        }
    }

    fun onDownloadProgress(progressPercent: Int, currentZoom: Int, zoomMin: Int, zoomMax: Int) {
        _uiState.value = MapUiState.Downloading(progressPercent, currentZoom, zoomMin, zoomMax)
    }

    fun onDownloadComplete(centerLat: Double, centerLon: Double) {
        val area = DownloadedArea(centerLat, centerLon, RADIUS_METERS, System.currentTimeMillis())
        mapAreaStore.save(area)
        _uiState.value = MapUiState.Ready(area)
    }

    fun onDownloadFailed() {
        _uiState.value = MapUiState.Error("The download failed partway through. Check your connection and try again.")
    }

    fun retry() {
        _uiState.value = MapUiState.Idle
    }

    companion object {
        const val RADIUS_METERS = 2000.0
        // Vector-tile services generate real detail only up to z14 and derive
        // higher zooms client-side (see the size-estimate research this
        // feature was planned against); osmdroid's raster tiles have no such
        // shortcut, so z12-z17 is a deliberate, real trade: z12 for enough
        // context to reorient after panning, z17 for street-level, without
        // paying for the far denser z18+ tile count a 2km radius doesn't need.
        const val ZOOM_MIN = 12
        const val ZOOM_MAX = 17
    }
}
