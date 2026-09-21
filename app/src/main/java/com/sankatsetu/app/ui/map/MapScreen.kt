package com.sankatsetu.app.ui.map

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sankatsetu.app.maps.GeoMath
import com.sankatsetu.app.ui.theme.ConsoleReadoutStyle
import com.sankatsetu.app.ui.theme.SankatSetuColors
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqlTileWriter
import com.sankatsetu.app.maps.MapTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * The Map tab — a real, downloaded-once, genuinely-offline-afterward area
 * around the person, not a live-tiles-over-the-internet map (which would
 * be useless the moment it's actually needed). See
 * `docs/adr/0020-offline-maps.md` (once written) for the full reasoning:
 * why a 2km radius, why raster tiles via `osmdroid` rather than a native
 * vector-tile SDK, and why this has to be downloaded before a crisis, not
 * during one.
 *
 * `osmdroid`'s `MapView`/`CacheManager` are real Android `View`/`AsyncTask`
 * objects, not Compose-native — this screen owns constructing and driving
 * them directly (via [AndroidView]) rather than pushing that down into
 * [MapViewModel], which only owns the download *decision* and its result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MapViewModel) {
    val state by viewModel.uiState.collectAsState()
    val sosMarkers by viewModel.sosMarkers.collectAsState()
    val peerMarkers by viewModel.peerMarkers.collectAsState()
    val highlightedSosId by viewModel.highlightedSosId.collectAsState()
    val context = LocalContext.current
    // osmdroid requires a real user agent before any tile request — the
    // default empty value gets some tile servers to silently reject
    // requests. Must run before the first MapView is constructed below.
    // Configuration is a process-wide singleton; `remember(Unit)` just
    // keeps this from re-running on every recomposition, not a
    // correctness requirement.
    remember(Unit) { org.osmdroid.config.Configuration.getInstance().userAgentValue = context.packageName }
    var mapView by remember { mutableStateOf<MapView?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not, startDownload()'s own LocationProvider check reports
        // PermissionDenied cleanly either way — no need to branch here.
        viewModel.startDownload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(MapTileSource.streets)
                        setMultiTouchControls(true)
                        controller.setZoom(15.0)
                        // Real bug found by the user actually testing this online:
                        // osmdroid's default tile provider falls back to network
                        // for any tile not already cached, so with internet on
                        // the live map silently streams the whole world, not
                        // just the downloaded 2km — making the "offline map"
                        // claim false the moment you have signal. This is the
                        // ONLY place tiles should ever come from outside an
                        // explicit "Download"/"Re-download" tap, which goes
                        // through CacheManager below, not through this MapView.
                        tileProvider.setUseDataConnection(false)
                        mapView = this
                    }
                }
            )

            when (val current = state) {
                is MapUiState.Idle -> DownloadPrompt(
                    onDownloadClick = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                )

                is MapUiState.RequestingLocation -> StatusOverlay {
                    CircularProgressIndicator(color = SankatSetuColors.SignalBlue)
                    Text("Finding your location…", style = MaterialTheme.typography.bodyMedium)
                }

                is MapUiState.PreparingDownload -> {
                    LaunchedEffect(current) {
                        val view = mapView
                        // Center the LIVE map on the person right away so it
                        // doesn't sit blank/wherever it last was — but the
                        // actual bulk download below deliberately does NOT
                        // reuse this MapView. Real on-device finding: a
                        // CacheManager built from a live MapView visibly
                        // drags that MapView's own camera across zoom levels
                        // while it works internally, which looks exactly
                        // like a runaway multi-hundred-km download even
                        // though the actual downloaded area (verified by
                        // logging the real BoundingBox) was correctly a 2km
                        // radius the whole time. The MapTileProviderBase/
                        // ITileSource-based constructor below has no MapView
                        // at all, so it can't touch anyone's camera.
                        view?.controller?.setCenter(GeoPoint(current.lat, current.lon))
                        val box = GeoMath.boundingBoxForRadius(current.lat, current.lon, current.radiusMeters)
                        val boundingBox = BoundingBox(box.maxLat, box.maxLon, box.minLat, box.minLon)
                        val cacheManager = CacheManager(MapTileSource.streets, SqlTileWriter(), MapViewModel.ZOOM_MIN, MapViewModel.ZOOM_MAX)
                        cacheManager.downloadAreaAsyncNoUI(
                            context,
                            boundingBox,
                            MapViewModel.ZOOM_MIN,
                            MapViewModel.ZOOM_MAX,
                            object : CacheManager.CacheManagerCallback {
                                override fun onTaskComplete() {
                                    viewModel.onDownloadComplete(current.lat, current.lon)
                                }
                                override fun updateProgress(progress: Int, currentZoomLevel: Int, zoomMin: Int, zoomMax: Int) {
                                    viewModel.onDownloadProgress(progress, currentZoomLevel, zoomMin, zoomMax)
                                }
                                override fun downloadStarted() = Unit
                                override fun setPossibleTilesInArea(total: Int) = Unit
                                override fun onTaskFailed(errors: Int) {
                                    viewModel.onDownloadFailed()
                                }
                            }
                        )
                    }
                    StatusOverlay {
                        CircularProgressIndicator(color = SankatSetuColors.SignalBlue)
                        Text("Starting download…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is MapUiState.Downloading -> StatusOverlay {
                    // Real on-device finding: osmdroid's own upfront tile-count
                    // estimate (used as the percentage's denominator) can run
                    // low against the actual count at the finest zoom level,
                    // so the raw value legitimately exceeds 100 (observed up
                    // to 290%+) before the download actually finishes.
                    // Clamping the *displayed number* to 100 is still the
                    // honest choice -- but a second real bug the user found
                    // by actually waiting: a flat, unmoving "100%" for the
                    // 20-30s the raw value keeps climbing past that reads as
                    // frozen/hung, not "still working." Switching to an
                    // indeterminate spinner once clamped tells the truth
                    // instead: progress can't be shown precisely anymore,
                    // but it hasn't stopped.
                    val rawPercent = current.progressPercent
                    val displayPercent = rawPercent.coerceIn(0, 100)
                    val stillFinishing = rawPercent >= 100
                    if (stillFinishing) {
                        CircularProgressIndicator(color = SankatSetuColors.SignalBlue)
                    } else {
                        LinearProgressIndicator(
                            progress = { displayPercent / 100f },
                            modifier = Modifier.fillMaxWidth(0.7f),
                            color = SankatSetuColors.SignalBlue
                        )
                    }
                    Text(
                        if (stillFinishing) "Finishing up… (zoom ${current.currentZoom} of ${current.zoomMax})"
                        else "Downloading… $displayPercent% (zoom ${current.currentZoom} of ${current.zoomMax})",
                        style = ConsoleReadoutStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is MapUiState.Ready -> {
                    LaunchedEffect(current.area) {
                        // Real osmdroid quirk found by the user actually
                        // waiting after a download hit 100%: the live
                        // MapView's in-memory tile cache remembers "no tile
                        // available" for every tile it tried to fetch while
                        // useDataConnection was false and nothing existed on
                        // disk yet (true for every state before this one).
                        // Those negative entries don't clear themselves
                        // quickly, so the map sits visibly blank for a while
                        // after the download actually finishes. Clearing the
                        // in-memory cache forces it to re-check disk, where
                        // the real tiles now are.
                        mapView?.tileProvider?.clearTileCache()
                    }
                    ReadyBadge(
                        radiusMeters = current.area.radiusMeters,
                        onRedownload = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                    )
                }

                is MapUiState.Error -> StatusOverlay {
                    Text(current.message, style = MaterialTheme.typography.bodyMedium, color = SankatSetuColors.StatusCritical)
                    TextButton(onClick = { viewModel.retry() }) { Text("Try again") }
                }
            }

            // All overlays -- the "your location" download-center pin, every
            // direct (1-hop) peer with a location (docs/adr/0022-peer-location.md),
            // and every SOS with a real fix attached
            // (docs/adr/0021-sos-location.md) -- are rebuilt together in one
            // place, not split across per-state effects: two effects both
            // clearing/rebuilding view.overlays independently would race and
            // clobber each other. Peer/SOS markers show regardless of
            // download state (state isn't part of this effect's real
            // dependency, only used to read the current Ready area if any)
            // since a card's "View on map" button can open this screen
            // before any area has ever been downloaded.
            LaunchedEffect(sosMarkers, peerMarkers, highlightedSosId, state) {
                val view = mapView ?: return@LaunchedEffect
                view.overlays.clear()

                val readyArea = (state as? MapUiState.Ready)?.area
                if (readyArea != null) {
                    view.overlays.add(
                        Marker(view).apply {
                            position = GeoPoint(readyArea.centerLat, readyArea.centerLon)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "Your location"
                            icon = pinDrawable(context, SankatSetuColors.SignalBlue.toArgb(), MarkerSize.NORMAL, "You")
                        }
                    )
                }

                peerMarkers.forEach { peer ->
                    view.overlays.add(
                        Marker(view).apply {
                            position = GeoPoint(peer.latitude, peer.longitude)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "${peer.nickname} (1 hop)"
                            icon = pinDrawable(context, SankatSetuColors.StatusSafe.toArgb(), MarkerSize.NORMAL, peer.nickname)
                        }
                    )
                }

                var highlightedPosition: GeoPoint? = null
                sosMarkers.forEach { sos ->
                    val isHighlighted = sos.sosId == highlightedSosId
                    val position = GeoPoint(sos.latitude, sos.longitude)
                    val label = if (sos.isOutgoing) "You" else sos.senderNickname
                    view.overlays.add(
                        Marker(view).apply {
                            this.position = position
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = sos.category.label + if (sos.isOutgoing) " (you)" else " — ${sos.senderNickname}"
                            icon = pinDrawable(
                                context,
                                colorInt = if (isHighlighted) HIGHLIGHT_COLOR else SankatSetuColors.StatusCritical.toArgb(),
                                sizeDp = if (isHighlighted) MarkerSize.HIGHLIGHTED else MarkerSize.SOS,
                                label = label
                            )
                        }
                    )
                    if (isHighlighted) highlightedPosition = position
                }

                // zoomToBoundingBox/setZoom need the view already laid out
                // (non-zero size) to compute correctly -- posting runs
                // after that layout pass instead of racing it.
                view.post {
                    val target = highlightedPosition
                    if (target != null) {
                        view.controller.setZoom(17.0)
                        view.controller.setCenter(target)
                    } else if (readyArea != null) {
                        val box = GeoMath.boundingBoxForRadius(readyArea.centerLat, readyArea.centerLon, readyArea.radiusMeters)
                        view.zoomToBoundingBox(BoundingBox(box.maxLat, box.maxLon, box.minLat, box.minLon), false)
                    }
                    view.invalidate()
                }
            }
        }
    }
}

/** dp sizes for [pinDrawable] — one shared scale so every marker on this screen reads as the same visual system, not a grab-bag of ad-hoc sizes. SOS markers are deliberately larger than "your location"/peer pins (more urgent), and the one just tapped from an emergency-log card larger still. */
private object MarkerSize {
    const val NORMAL = 28
    const val SOS = 34
    const val HIGHLIGHTED = 46
}

/**
 * A real drawn map-pin (teardrop: circular head, pointed tail touching the
 * actual coordinate) rendered into a [android.graphics.Bitmap], not
 * osmdroid's built-in default marker (a generic "hand cursor" glyph that
 * doesn't distinguish what kind of thing is being pointed at) or a tinted
 * copy of some other library/system drawable. A `BitmapDrawable`'s
 * intrinsic size always matches the bitmap exactly, so there's no risk of
 * osmdroid rendering an invisible, zero-size marker the way an un-sized
 * `GradientDrawable`/`ShapeDrawable` genuinely can. One shape, three colors
 * (blue = your location, green = a direct peer, red/amber = SOS) is the
 * whole visual vocabulary for this screen -- see [MarkerSize] for the one
 * shared scale.
 *
 * [label], if given, is baked directly into the same bitmap above the pin
 * -- not left to osmdroid's `Marker.title`, which only shows in a tap-to-open
 * info bubble. A name that only appears after tapping every single pin
 * defeats the point of a "who's around me" map. The pin's own tip is always
 * flush with the bitmap's bottom edge regardless of label width, so the
 * anchor stays a fixed (center, bottom) no matter what text is drawn above.
 */
private fun pinDrawable(context: android.content.Context, colorInt: Int, sizeDp: Int, label: String? = null): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val headDiameter = (sizeDp * density).toInt().coerceAtLeast(1)
    val pinWidth = headDiameter
    val pinHeight = (headDiameter * 1.35f).toInt().coerceAtLeast(1)

    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = density * 12f
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    val labelPaddingH = (density * 8f)
    val labelHeight = if (label != null) (textPaint.textSize * 1.8f).toInt() else 0
    val labelWidth = if (label != null) (textPaint.measureText(label) + labelPaddingH * 2).toInt() else 0

    val totalWidth = maxOf(pinWidth, labelWidth).coerceAtLeast(1)
    val totalHeight = pinHeight + labelHeight
    val bitmap = android.graphics.Bitmap.createBitmap(totalWidth, totalHeight, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    if (label != null) {
        val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(215, 20, 20, 20)
        }
        val bgRect = android.graphics.RectF(
            (totalWidth - labelWidth) / 2f, 0f,
            (totalWidth + labelWidth) / 2f, labelHeight.toFloat()
        )
        canvas.drawRoundRect(bgRect, labelHeight / 3f, labelHeight / 3f, bgPaint)
        canvas.drawText(label, totalWidth / 2f, labelHeight * 0.7f, textPaint)
    }

    val strokeWidth = density * 1.5f
    val headRadius = pinWidth / 2f - strokeWidth
    val cx = totalWidth / 2f
    val cy = labelHeight + headRadius + strokeWidth

    val path = android.graphics.Path().apply {
        addCircle(cx, cy, headRadius, android.graphics.Path.Direction.CW)
        moveTo(cx - headRadius * 0.72f, cy + headRadius * 0.62f)
        lineTo(cx, totalHeight - strokeWidth)
        lineTo(cx + headRadius * 0.72f, cy + headRadius * 0.62f)
        close()
    }

    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; style = android.graphics.Paint.Style.FILL }
    canvas.drawPath(path, fillPaint)

    val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    canvas.drawPath(path, strokePaint)

    val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.FILL }
    canvas.drawCircle(cx, cy, headRadius * 0.32f, dotPaint)

    return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
}

/** Amber, not part of [SankatSetuColors] -- deliberately distinct from both the default SOS red and the app's signal-blue, so "this is the one you tapped" reads as a color nothing else on this screen uses. */
private const val HIGHLIGHT_COLOR: Int = 0xFFFFC107.toInt()

/** A centered card over the (empty, online-tile-only) map, offering the one real action this screen has before anything is downloaded. */
@Composable
private fun DownloadPrompt(onDownloadClick: () -> Unit) {
    StatusOverlay {
        Text("No offline map for this area yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Downloads roughly a 2km radius around you, once, while you have a real internet connection. Fully usable with no signal afterward.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onDownloadClick) { Text("Download offline map") }
    }
}

/** A small corner badge once an area is downloaded — the map itself is the main content at this point, this is just the honest "yes, this is really offline now" confirmation plus a way to refresh it. */
@Composable
private fun ReadyBadge(radiusMeters: Double, onRedownload: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp))
                .padding(12.dp)
        ) {
            Text(
                "Downloaded — ${(radiusMeters / 1000).toInt()}km radius, viewable offline",
                style = ConsoleReadoutStyle,
                color = SankatSetuColors.StatusSafe
            )
            TextButton(onClick = onRedownload) { Text("Re-download for here") }
        }
    }
}

@Composable
private fun StatusOverlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(24.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(3.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            content()
        }
    }
}
