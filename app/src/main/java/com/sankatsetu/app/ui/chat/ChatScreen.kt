package com.sankatsetu.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import com.sankatsetu.app.data.SosEntity
import com.sankatsetu.app.mesh.protocol.SosCategory
import com.sankatsetu.app.ui.components.SignalBars
import com.sankatsetu.app.ui.components.StampMark
import com.sankatsetu.app.ui.components.StatusPill
import com.sankatsetu.app.ui.components.hazardEdge
import com.sankatsetu.app.ui.emergency.SosViewModel
import com.sankatsetu.app.ui.theme.ConsoleReadoutStyle
import com.sankatsetu.app.ui.theme.PillShape
import com.sankatsetu.app.ui.theme.SankatSetuColors
import com.sankatsetu.app.ui.theme.SankatSetuMotion
import com.sankatsetu.app.ui.theme.pressScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Peer list — the mesh's front door. Tap a peer to open [ChatThreadScreen], long-press to forget a stale one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(viewModel: ChatViewModel, sosViewModel: SosViewModel, onOpenThread: (PeerUiModel) -> Unit, onViewSosOnMap: (String) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val sosState by sosViewModel.uiState.collectAsState()
    var peerToForget by remember { mutableStateOf<PeerUiModel?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    // Re-read on recomposition after a rename, not cached in a StateFlow —
    // renames are rare and this avoids threading self-identity through the
    // combine() pipeline that drives peer list updates.
    var selfNickname by remember { mutableStateOf(viewModel.selfNickname()) }

    var imSafeSent by remember { mutableStateOf(false) }
    val readyPeerCount = state.peers.count { it.handshakeEstablished }

    Scaffold(
        topBar = {
            TopAppBar(
                // A two-line title (name + nickname) here, against Pay/
                // Assistant's plain one-line title, meant "Chat" itself sat
                // higher than "Pay"/"Assistant" in their app bars — Material
                // vertically centers the WHOLE title block, so a taller
                // block pushes its first line above where a one-line title
                // centers. The nickname moved out to its own row below the
                // bar instead of stretching the title to match everywhere
                // else, so every tab's title text lands on the same
                // baseline. See docs/adr/0019-ledger-register-redesign.md.
                title = { Text("Chat", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "You: $selfNickname",
                    style = ConsoleReadoutStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                IconButton(onClick = { showRenameDialog = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Change your name",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            AnimatedVisibility(visible = !state.bluetoothOn, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp, 8.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp)
                ) {
                    Text(
                        "Bluetooth is off. Turn it on to reach nearby phones.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            SosReportSection(
                onSend = { category -> sosViewModel.sendSos(category) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // The single most load-bearing pattern in the crisis-UX research
            // behind this screen (docs/PRODUCT.md, Evidence on Hand): one tap,
            // no typing, tells everyone in range you're okay. Full-width pill,
            // the app's one accent color, spring press + haptic feedback so
            // the single most important action in the app feels the most
            // deliberate one to press.
            val imSafeInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = { viewModel.broadcastImSafe(); imSafeSent = true },
                enabled = readyPeerCount > 0,
                interactionSource = imSafeInteraction,
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = SankatSetuColors.StatusSafe, contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(52.dp)
                    .pressScale(imSafeInteraction)
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (readyPeerCount > 0) "I'm safe — notify $readyPeerCount ${if (readyPeerCount == 1) "peer" else "peers"}" else "I'm safe — no peers in range yet",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            AnimatedVisibility(visible = imSafeSent, enter = fadeIn(), exit = fadeOut()) {
                Text(
                    "Sent to everyone currently reachable.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }

            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sosState.alerts.isNotEmpty()) {
                    item {
                        Text(
                            "Emergency log",
                            style = ConsoleReadoutStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                        )
                    }
                    // A long-running crisis session could log many alerts —
                    // this must never grow to push the peer list (and,
                    // further down, an actual chat thread) off screen. Past
                    // SOS_LOG_INLINE_LIMIT rows, the log becomes its own
                    // small scrollable region instead of the whole screen
                    // scrolling through an ever-growing list to reach chat.
                    if (sosState.alerts.size <= SOS_LOG_INLINE_LIMIT) {
                        itemsIndexed(sosState.alerts, key = { _, it -> it.sosId }) { index, alert ->
                            SosLogRow(alert, showDivider = index != sosState.alerts.lastIndex, onAcknowledge = { sosViewModel.acknowledge(alert.sosId) }, consumeAttentionPulse = { sosViewModel.consumeAttentionPulse(alert.sosId) }, onViewOnMap = { onViewSosOnMap(alert.sosId) })
                        }
                    } else {
                        item {
                            Box(Modifier.heightIn(max = SOS_LOG_ROW_HEIGHT * SOS_LOG_INLINE_LIMIT)) {
                                LazyColumn {
                                    itemsIndexed(sosState.alerts, key = { _, it -> it.sosId }) { index, alert ->
                                        SosLogRow(alert, showDivider = index != sosState.alerts.lastIndex, onAcknowledge = { sosViewModel.acknowledge(alert.sosId) }, consumeAttentionPulse = { sosViewModel.consumeAttentionPulse(alert.sosId) }, onViewOnMap = { onViewSosOnMap(alert.sosId) })
                                    }
                                }
                            }
                        }
                    }
                    item {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                        )
                    }
                }
                if (state.peers.isEmpty()) {
                    item { MeshSearchingCard(modifier = Modifier.padding(top = if (sosState.alerts.isEmpty()) 0.dp else 4.dp)) }
                } else {
                    item {
                        PeerSummaryRow(
                            peerCount = state.peers.size,
                            readyCount = readyPeerCount,
                            modifier = Modifier.padding(top = if (sosState.alerts.isEmpty()) 0.dp else 4.dp, bottom = 6.dp)
                        )
                    }
                    items(state.peers, key = { it.peerIdBase64 }) { peer ->
                        PeerRow(peer, onClick = { onOpenThread(peer) }, onLongPress = { peerToForget = peer })
                    }
                }
            }
        }
    }

    peerToForget?.let { peer ->
        AlertDialog(
            onDismissRequest = { peerToForget = null },
            title = { Text("Forget ${peer.nickname}?") },
            text = { Text("Removes this peer and its chat history from this phone only. If their phone is still nearby, it will reappear as a new entry.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forgetPeer(peer.peerIdBase64)
                    peerToForget = null
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { peerToForget = null }) { Text("Cancel") }
            }
        )
    }

    if (showRenameDialog) {
        RenameDialog(
            currentNickname = selfNickname,
            deviceBluetoothName = viewModel.deviceBluetoothName(),
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                viewModel.renameSelf(newName)
                selfNickname = newName
                showRenameDialog = false
            }
        )
    }
}

/**
 * The SOS send surface — the counterpart to "I'm Safe," see docs/TODO.md's
 * ideation. Placed above "I'm Safe" (danger surfaced before calm
 * confirmation) but deliberately not styled as a bigger version of the
 * same button: [hazardEdge]'s stripe corner and correction-ink red instead
 * of the stamp's rounded green, collapsed to a single low-profile row by
 * default so it doesn't dominate a normal, non-emergency visit to this
 * screen. Expands into a category picker on tap; each category then needs
 * a deliberate press-and-hold (see [HoldToSendRow]), not a single tap —
 * more friction than "I'm Safe," not less, since a false SOS wastes
 * attention and could cause real panic where a false "I'm safe" is
 * harmless.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SosReportSection(onSend: (SosCategory) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var justSent by remember { mutableStateOf<SosCategory?>(null) }

    LaunchedEffect(justSent) {
        if (justSent != null) {
            delay(4000)
            justSent = null
        }
    }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .hazardEdge(SankatSetuColors.StatusCritical)
                .combinedClickable(onClick = { expanded = !expanded })
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = SankatSetuColors.StatusCritical, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Report emergency",
                style = MaterialTheme.typography.titleMedium,
                color = SankatSetuColors.StatusCritical,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = SankatSetuColors.StatusCritical
            )
        }

        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.padding(top = 8.dp)) {
                Text(
                    "Reaches every phone in range, not just known peers. Sends your GPS location if a quick fix is available (never waits more than a few seconds for one). Hold a category to send.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
                )
                SosCategory.entries.forEach { category ->
                    HoldToSendRow(
                        category = category,
                        onHoldComplete = {
                            onSend(category)
                            justSent = category
                            expanded = false
                        },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }

        AnimatedVisibility(visible = justSent != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                "Emergency broadcast sent: ${justSent?.label}.",
                style = MaterialTheme.typography.labelSmall,
                color = SankatSetuColors.StatusCritical,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp)
            )
        }
    }
}

/**
 * A press-and-hold control, filling with correction ink as it's held — the
 * same convention iOS/Android use for their own emergency SOS. Releasing
 * early resets to empty; only a completed hold fires [onHoldComplete].
 * Chosen over a circular countdown ring for a simpler, more reliable
 * implementation under real testing time — a growing fill bar is just as
 * legible a "still holding, not yet done" signal.
 */
@Composable
private fun HoldToSendRow(category: SosCategory, onHoldComplete: () -> Unit, modifier: Modifier = Modifier) {
    val holdDurationMs = 1100
    var progress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .pointerInput(category) {
                detectTapGestures(
                    onPress = {
                        holdJob = scope.launch {
                            val start = System.currentTimeMillis()
                            while (isActive) {
                                val elapsed = System.currentTimeMillis() - start
                                progress = (elapsed / holdDurationMs.toFloat()).coerceIn(0f, 1f)
                                if (progress >= 1f) {
                                    onHoldComplete()
                                    break
                                }
                                delay(16)
                            }
                        }
                        tryAwaitRelease()
                        holdJob?.cancel()
                        progress = 0f
                    }
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(SankatSetuColors.StatusCritical.copy(alpha = 0.55f))
        )
        Text(
            category.label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp)
        )
    }
}

private val SOS_LOG_ROW_HEIGHT = 62.dp
private const val SOS_LOG_INLINE_LIMIT = 2

/**
 * A plain row, not a card. Every entry here already sits inside a section
 * headed "Emergency log" and separated from the rest of the screen by its
 * own divider (see [ChatListScreen]) — giving every single row its own
 * colored border on top of that (an earlier version of this row did
 * exactly that) repeats the same alarm signal once per line instead of
 * once for the section, the same "a colored stripe decorates every card"
 * pattern flagged at https://impeccable.style/slop/. The icon tint and
 * text color already carry the meaning; a thin hairline divider between
 * rows is enough structure.
 */
/**
 * Non-screen-blocking on purpose — an earlier version of this used a
 * full-screen `AlertDialog` that interrupted whatever the person was
 * doing, deliberately made non-dismissible. Direct user feedback
 * replaced that with this: the row itself pulses a correction-ink tint
 * for a few seconds when it's a fresh, unacknowledged incoming alert,
 * enough to catch a glancing eye without ever blocking the screen a
 * received SOS happened to arrive on top of. Tapping an unacknowledged
 * row clears its own "NEW" pill — the row itself is now what the old
 * dialog's confirm button was.
 *
 * The pulse is a plain coroutine-driven alpha toggle, not
 * `rememberInfiniteTransition`, specifically so it has a real end (2.5s,
 * ~3-4 pulses) rather than running forever — an alert that's still
 * unacknowledged an hour later shouldn't still be flashing.
 */
@Composable
private fun SosLogRow(alert: SosEntity, showDivider: Boolean, onAcknowledge: () -> Unit, consumeAttentionPulse: () -> Boolean, onViewOnMap: () -> Unit) {
    val category = SosCategory.entries.find { it.name == alert.category }?.label ?: alert.category
    val isNewIncoming = !alert.isOutgoing && !alert.acknowledged
    val lat = alert.latitude
    val lon = alert.longitude

    var pulseOn by remember { mutableStateOf(false) }
    LaunchedEffect(alert.sosId, isNewIncoming) {
        // consumeAttentionPulse is backed by a set on SosViewModel, not
        // remember — it survives this row scrolling out of the lazy list's
        // window and back, so re-entering composition (which restarts this
        // effect) doesn't replay the pulse a second time. See that
        // function's own doc for the bug this fixes.
        if (isNewIncoming && consumeAttentionPulse()) {
            val until = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < until) {
                pulseOn = true
                delay(350)
                pulseOn = false
                delay(350)
            }
        }
    }
    val pulseAlpha by animateFloatAsState(if (pulseOn) 0.28f else 0f, animationSpec = tween(300), label = "sosPulse")

    Column(
        Modifier
            .fillMaxWidth()
            .height(SOS_LOG_ROW_HEIGHT)
            .background(SankatSetuColors.StatusCritical.copy(alpha = pulseAlpha))
            .then(if (isNewIncoming) Modifier.clickable(onClick = onAcknowledge) else Modifier)
    ) {
        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = SankatSetuColors.StatusCritical, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (alert.isOutgoing) "You reported: $category" else "${alert.senderNickname}: $category",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // A real fix isn't guaranteed (SosManager only waits
                        // a few seconds before sending regardless — see its
                        // own doc) -- falling back to the old hop-count line
                        // when there's no location is more honest than
                        // showing nothing.
                        if (lat != null && lon != null) formatCoordinates(lat, lon)
                        else if (alert.isOutgoing) "Sent to everyone in range" else if (alert.hopCount <= 1) "1 hop away" else "${alert.hopCount} hops away, relayed",
                        style = ConsoleReadoutStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (lat != null && lon != null) {
                        IconButton(onClick = onViewOnMap, modifier = Modifier.size(22.dp).padding(start = 2.dp)) {
                            Icon(
                                Icons.Filled.Map,
                                contentDescription = "View on map",
                                tint = SankatSetuColors.SignalBlue,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatMessageTime(alert.receivedAt),
                    style = ConsoleReadoutStyle.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isNewIncoming) {
                    Spacer(Modifier.height(4.dp))
                    StatusPill("NEW", SankatSetuColors.StatusCritical)
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 26.dp))
        }
    }
}

/** 4 decimal places is ~11m precision at the equator -- enough to place someone on a street, not their exact doorstep, and matches the precision GeoMath's own bounding-box math already works at. */
private fun formatCoordinates(lat: Double, lon: Double): String {
    val latHemisphere = if (lat >= 0) "N" else "S"
    val lonHemisphere = if (lon >= 0) "E" else "W"
    return "%.4f°%s, %.4f°%s".format(kotlin.math.abs(lat), latHemisphere, kotlin.math.abs(lon), lonHemisphere)
}

/**
 * The mesh's "nothing to show yet" moment, reframed for the ledger world —
 * an unstamped blank ruled line with a blinking write-cursor, like a
 * register waiting for its next entry, replacing the prior pass's radar
 * sweep (that metaphor belonged to the discarded "field radio" world; a
 * ledger doesn't have a radar, it has a page waiting to be written on).
 * Still motion, not a frozen icon — a blank screen reads as unfinished
 * regardless of typography — but motion drawn from this world's own
 * vocabulary. See docs/adr/0019-ledger-register-redesign.md.
 */
@Composable
private fun MeshSearchingCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scanning for nearby phones", style = ConsoleReadoutStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BlinkingCursor(color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Spacer(Modifier.height(18.dp))
            Text("No entries yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "Keep Bluetooth on and stay within about 30 metres of another phone running Sankat Setu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

/** A steady on/off blink, like a cursor waiting at the end of an unfinished ledger line. */
@Composable
private fun BlinkingCursor(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cursorBlink")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(600, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "cursorAlpha"
    )
    Box(
        modifier
            .padding(start = 4.dp)
            .width(8.dp)
            .height(14.dp)
            .background(color.copy(alpha = alpha))
    )
}

/**
 * The register's own summary line once at least one peer exists, sitting
 * above the peer rows as the list's own header. Previously a full-width
 * `Card` with a display-size digit — a "hero metric" treatment for a
 * number that isn't actually the important thing on this screen, giving
 * it the same visual weight as a real headline stat. This count is
 * reference information, not a result to celebrate, so it now reads at
 * the same quiet weight as the "You: nickname" row above it: a single
 * plain line, no card, no oversized digit.
 *
 * [peerCount] is every phone ever recorded in this register, connected or
 * not — a real ledger doesn't forget an entry just because the other party
 * isn't in the room. It is deliberately NOT labelled "in range": that was
 * a real, misleading claim in a prior version of this screen (a peer seen
 * once, days ago, and currently unreachable still counted toward it). The
 * [readyCount] stamp is the one number that actually means "reachable
 * right now" — see the per-row connected/handshake state this reads from.
 */
@Composable
private fun PeerSummaryRow(peerCount: Int, readyCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$peerCount ${if (peerCount == 1) "known device" else "known devices"}",
            style = ConsoleReadoutStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        StampMark(
            "$readyCount READY",
            if (readyCount > 0) SankatSetuColors.StatusSafe else SankatSetuColors.OfflineGray
        )
    }
}

/**
 * The OS Bluetooth device name is offered as a one-tap suggestion, never
 * applied by default — a phone's Bluetooth name is very often someone's
 * real name, and this app shouldn't broadcast that to nearby strangers in
 * a disaster zone unless the person chooses to. See
 * docs/adr/0015-editable-nickname.md.
 */
@Composable
private fun RenameDialog(
    currentNickname: String,
    deviceBluetoothName: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by remember { mutableStateOf(currentNickname) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your name on the mesh") },
        text = {
            Column {
                Text(
                    "This is what nearby phones see instead of a random ID.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                if (deviceBluetoothName != null && deviceBluetoothName != draft) {
                    TextButton(onClick = { draft = deviceBluetoothName }) {
                        Text("Use this phone's Bluetooth name: \"$deviceBluetoothName\"")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (draft.isNotBlank()) onConfirm(draft.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * A row reads like a channel roster entry, not a chat-app contact card:
 * signal bars carry connection quality at a glance (radio-relay language,
 * matching what hop count already means physically), the status pill runs
 * in the console monospace register. Hairline border + surfaceContainer
 * fill (not a Material default elevated Card) reads as the "precision
 * instrument" density this pass's research recommends over heavier
 * drop-shadow elevation. See docs/adr/0018-ui-revamp.md.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PeerRow(peer: PeerUiModel, onClick: () -> Unit, onLongPress: () -> Unit) {
    val (bars, tint, statusWord) = when {
        !peer.connected -> Triple(0, SankatSetuColors.OfflineGray, "OFFLINE")
        peer.handshakeEstablished -> Triple((5 - peer.hopCount.coerceIn(1, 4)), SankatSetuColors.StatusSafe, if (peer.hopCount <= 1) "1 HOP · READY" else "${peer.hopCount} HOPS · RELAYED")
        else -> Triple(1, SankatSetuColors.StatusCaution, "CONNECTING")
    }
    val interactionSource = remember { MutableInteractionSource() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .combinedClickable(interactionSource = interactionSource, indication = null, onClick = onClick, onLongClick = onLongPress),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SignalBars(filledBars = bars, tint = tint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.nickname, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (peer.connected && peer.handshakeEstablished) {
                    StampMark(statusWord, tint, seed = peer.peerIdBase64.hashCode())
                } else {
                    StatusPill(statusWord, tint)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScreen(viewModel: ChatViewModel, peer: PeerUiModel, onBack: () -> Unit) {
    val messages by viewModel.threadMessages(peer.peerIdBase64).collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(peer.peerIdBase64) { viewModel.onThreadOpened(peer.peerIdBase64) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(peer.nickname, style = MaterialTheme.typography.titleLarge)
                        StatusPill(
                            if (peer.connected) "ONLINE" else "DISCONNECTED",
                            if (peer.connected) SankatSetuColors.StatusSafe else SankatSetuColors.OfflineGray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear this chat")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message ->
                    MessageBubble(
                        text = message.body,
                        isOutgoing = message.isOutgoing,
                        hopCount = message.hopCount,
                        status = message.status,
                        sentAt = message.sentAt
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.extraLarge,
                    placeholder = { Text(if (peer.handshakeEstablished) "Message…" else "Waiting for secure link…") },
                    enabled = peer.handshakeEstablished
                )
                Spacer(Modifier.width(8.dp))
                val sendInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = {
                        if (draft.isNotBlank()) {
                            viewModel.sendMessage(peer.peerIdBase64, draft.trim())
                            draft = ""
                            keyboardController?.hide()
                        }
                    },
                    interactionSource = sendInteraction,
                    enabled = peer.handshakeEstablished && draft.isNotBlank(),
                    modifier = Modifier
                        .pressScale(sendInteraction)
                        .clip(PillShape)
                        .background(if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear this chat?") },
            text = { Text("This deletes every message in this thread from your phone only. ${peer.nickname} keeps their own copy. Your peer list isn't affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearThread(peer.peerIdBase64)
                    showClearConfirm = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MessageBubble(text: String, isOutgoing: Boolean, hopCount: Int, status: String, sentAt: Long) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
                if (!isOutgoing && hopCount > 1) {
                    Text(
                        "via $hopCount hops",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Text(
                        formatMessageTime(sentAt),
                        style = ConsoleReadoutStyle.copy(fontSize = 10.sp),
                        color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isOutgoing) {
                        Spacer(Modifier.width(4.dp))
                        MessageStatusGlyph(status)
                    }
                }
            }
        }
    }
}

private val messageTimeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
private fun formatMessageTime(epochMs: Long): String = messageTimeFormat.format(java.util.Date(epochMs))

/**
 * WhatsApp-style delivery ticks: single grey = left this phone but not yet
 * confirmed, double grey = the peer's app decrypted it, double stamp-green =
 * the peer opened the thread and saw it — reusing the ledger's own
 * "confirmed" ink instead of a second blue, since the outgoing bubble itself
 * is now filled with the pen-ink accent color. A same-hue tick on a
 * same-hue bubble is invisible, a real bug this pass found by actually
 * being asked where the read tick went — every other build in this repo's
 * history used a bubble color distinct from the read-tick color, so this
 * never surfaced until the ledger world made both indigo. "queued" gets a
 * clock instead of a tick since it never actually reached a radio yet —
 * see ChatViewModel's EnvelopeKind/onThreadOpened for how each transition
 * fires.
 */
@Composable
private fun MessageStatusGlyph(status: String) {
    val onPrimaryMuted = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
    val (glyph, color) = when (status) {
        "queued" -> "waiting…" to onPrimaryMuted
        "sending" -> "sending…" to onPrimaryMuted
        "sent" -> "✓" to onPrimaryMuted
        "delivered" -> "✓✓" to onPrimaryMuted
        "read" -> "✓✓" to SankatSetuColors.StatusSafe
        else -> "" to onPrimaryMuted
    }
    if (glyph.isNotEmpty()) {
        Text(glyph, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
