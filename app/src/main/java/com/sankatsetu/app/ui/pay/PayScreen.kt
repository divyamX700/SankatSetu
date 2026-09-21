package com.sankatsetu.app.ui.pay

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sankatsetu.app.data.IouEntity
import com.sankatsetu.app.payments.UpiQrParser
import com.sankatsetu.app.payments.UpiUssdScanToPayBuilder
import com.sankatsetu.app.payments.UssdDialer
import com.sankatsetu.app.ui.components.StampMark
import com.sankatsetu.app.ui.components.StatusPill
import com.sankatsetu.app.ui.components.counterfoilEdge
import com.sankatsetu.app.ui.theme.SankatSetuColors
import com.sankatsetu.app.ui.theme.pressScale

/**
 * The Pay tab (docs/PRD.md §F3/F4): Scan QR to Pay (parses a real UPI QR
 * code and places the *99# call directly — see `UssdDialer.kt` for why
 * that's `Intent.ACTION_CALL`, not `ACTION_DIAL`), two plain USSD/IVR
 * buttons for manual entry, a composer for a mesh IOU voucher, and the IOU
 * list grouped by direction/status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayScreen(viewModel: PayViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showComposer by remember { mutableStateOf(false) }

    // Scan-to-pay state — everything from the scan to building the full
    // *99*1*3*vpa*amount*remarks# string happens in this screen.
    // UssdDialer.openDialer (Intent.ACTION_CALL — see its own doc for why
    // ACTION_DIAL doesn't work for a VPA) is the only hand-off out of the
    // app, and it places the call immediately once tapped, matching
    // Flowpay's own real CallManager.kt mechanism exactly. No other app is
    // ever launched, unlike a typical `upi://` deep link that hands off to
    // whichever UPI app the person has installed. See
    // docs/adr/0025-qr-scan-to-pay.md.
    var pendingScan by remember { mutableStateOf<UpiQrParser.ParseResult.Valid?>(null) }
    var scanErrorReason by remember { mutableStateOf<UpiQrParser.Reason?>(null) }
    var pendingUssdCode by remember { mutableStateOf<String?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@rememberLauncherForActivityResult // user backed out of the scanner, not an error
        when (val parsed = UpiQrParser.parse(raw)) {
            is UpiQrParser.ParseResult.Valid -> pendingScan = parsed
            is UpiQrParser.ParseResult.Invalid -> scanErrorReason = parsed.reason
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(true))
    }
    val launchScan = launch@{
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(true))
            return@launch
        }
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Intent.ACTION_CALL (see UssdDialer's doc) needs CALL_PHONE, unlike the
    // ACTION_DIAL this app used before — placing the call is what the
    // person's own tap on "Open *99#"/"Pay via *99#"/"UPI 123Pay" already
    // authorized; this permission check just gates whether Android lets
    // that tap actually place the call.
    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val code = pendingUssdCode
        pendingUssdCode = null
        if (granted && code != null) UssdDialer.openDialer(context, code)
    }
    fun dialUssd(ussdCode: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            UssdDialer.openDialer(context, ussdCode)
        } else {
            pendingUssdCode = ussdCode
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    pendingScan?.let { scan ->
        ScanToPayConfirmDialog(
            payload = scan.data,
            onDismiss = { pendingScan = null },
            onConfirm = { ussdCode ->
                dialUssd(ussdCode)
                pendingScan = null
            }
        )
    }
    scanErrorReason?.let { reason ->
        AlertDialog(
            onDismissRequest = { scanErrorReason = null },
            title = { Text("Couldn't read that QR code") },
            text = { Text(qrRejectionMessage(reason)) },
            confirmButton = { TextButton(onClick = { scanErrorReason = null }) { Text("OK") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pay", style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
    LazyColumn(
        Modifier.fillMaxWidth().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "This works with no internet. USSD and IVR use your SIM's voice channel, and mesh IOUs travel over Bluetooth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The core, primary ledger entries: real UPI money movement. These
        // lead the page. The mesh IOU below is real but secondary, one
        // entry type in the register, not a competing headline action.
        item { LedgerSectionHeader("Pay now") }
        item {
            PayActionCard(
                title = "Scan QR to Pay",
                steps = listOf(
                    "Scan the shop's UPI QR code.",
                    "Confirm the amount in this app.",
                    "The payee ID is copied and *99# opens automatically.",
                    "Paste the ID and type the amount when asked.",
                    "Enter your UPI PIN directly in that screen. This app never sees it."
                ),
                onClick = launchScan
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CompactActionButton(
                    title = "USSD *99#",
                    modifier = Modifier.weight(1f),
                    onClick = { dialUssd("*99#") }
                )
                CompactActionButton(
                    title = "UPI 123Pay",
                    modifier = Modifier.weight(1f),
                    onClick = { dialUssd("*99#") }
                )
            }
        }

        item { LedgerSectionHeader("Mesh IOU") }
        item {
            PayActionCard(
                title = "Send Mesh IOU",
                subtitle = "A signed promise to pay, not an actual transfer. It goes to a nearby phone over Bluetooth, with no bank and no internet needed, and gets settled later.",
                onClick = { showComposer = !showComposer }
            )
        }

        if (showComposer) {
            item {
                IouComposer(
                    peers = state.peers,
                    onSend = { peerId, nickname, amountPaise, memo ->
                        viewModel.sendIou(peerId, nickname, amountPaise, memo)
                        showComposer = false
                    }
                )
            }
        }

        item { IouSectionHeader("Owed to you") }
        if (state.owedToMe.isEmpty()) {
            item { EmptySectionText("Nothing owed to you yet.") }
        } else {
            items(state.owedToMe, key = { it.iouId }) { iou -> IouCard(iou) }
        }

        item { IouSectionHeader("You owe") }
        if (state.iOwe.isEmpty()) {
            item { EmptySectionText("You don't owe anything right now.") }
        } else {
            items(state.iOwe, key = { it.iouId }) { iou ->
                IouCard(iou, onMarkSettled = { viewModel.markSettled(iou.iouId) })
            }
        }

        item { IouSectionHeader("Settled") }
        if (state.settled.isEmpty()) {
            item { EmptySectionText("No settled IOUs yet.") }
        } else {
            items(state.settled, key = { it.iouId }) { iou -> IouCard(iou) }
        }
    }
    }
}

@Composable
private fun LedgerSectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = com.sankatsetu.app.ui.theme.ConsoleReadoutStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun CompactActionButton(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp).pressScale(interaction),
        interactionSource = interaction,
        shape = MaterialTheme.shapes.small
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * A torn-counterfoil dashed edge, not a solid border — this card is the
 * IOU composer trigger, and the IOU is a pending promise by definition, so
 * even the entry point into it carries the "not yet settled" mark. See
 * docs/adr/0019-ledger-register-redesign.md.
 */
@Composable
private fun PayActionCard(title: String, subtitle: String? = null, steps: List<String> = emptyList(), onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .counterfoilEdge(SankatSetuColors.StatusCaution.copy(alpha = 0.6f), cornerRadius = 6.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = null
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    steps.forEachIndexed { index, step -> NumberedStep(index + 1, step) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IouComposer(
    peers: List<PeerPickerEntry>,
    onSend: (peerIdBase64: String, nickname: String, amountPaise: Long, memo: String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PeerPickerEntry?>(null) }
    var amountRupees by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("New mesh IOU", style = MaterialTheme.typography.titleLarge)

            if (peers.isEmpty()) {
                Text(
                    "No nearby phones yet. Open the Chat tab first so a peer shows up here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = selected?.nickname ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Pay to") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        peers.forEach { peer ->
                            DropdownMenuItem(
                                text = { Text(peer.nickname) },
                                onClick = { selected = peer; expanded = false }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = amountRupees,
                onValueChange = { amountRupees = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("Amount (₹)") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = { Text("What for?") },
                modifier = Modifier.fillMaxWidth()
            )

            val amountPaise = amountRupees.toDoubleOrNull()?.let { (it * 100).toLong() }
            val sendInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    val peer = selected ?: return@Button
                    val paise = amountPaise ?: return@Button
                    onSend(peer.peerIdBase64, peer.nickname, paise, memo.trim())
                },
                enabled = selected != null && amountPaise != null && amountPaise > 0,
                interactionSource = sendInteraction,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().height(48.dp).pressScale(sendInteraction)
            ) {
                Text("Send IOU", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

/**
 * Shown right after a successful scan, before anything is dialed.
 *
 * Matches Flowpay's own real, shipped mechanism exactly (traced directly
 * from `QRScannerActivity.kt`, Apache 2.0 — this project's earlier belief
 * that Flowpay embeds the VPA into a full dial string was wrong; their
 * actual code does not). No dial mechanism on Android can carry a VPA
 * (letters + `@`) through intact — confirmed by two separate live-device
 * failures (`ACTION_DIAL`'s keypad UI mangles it; `ACTION_CALL` rejects it
 * silently). So neither this dialog nor Flowpay's own tries: tapping "Pay"
 * copies the VPA to the clipboard (flagged sensitive on Android 13+, same
 * as Flowpay does) and dials the **bare** `*99*1*3#` menu shortcut — Send
 * Money → To VPA, skipping those two menu taps, nothing more. The person
 * pastes the VPA and types the amount into the carrier's own live
 * interactive prompt afterward. See
 * docs/adr/0025-qr-scan-to-pay.md's fourth update for the full story.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanToPayConfirmDialog(
    payload: com.sankatsetu.app.payments.UpiQrPayload,
    onDismiss: () -> Unit,
    onConfirm: (ussdCode: String) -> Unit
) {
    var amount by remember { mutableStateOf(payload.amount) }
    val context = LocalContext.current
    val validation = remember(amount) { UpiUssdScanToPayBuilder.validate(payload.vpa, amount) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (payload.payeeName.isNotBlank()) payload.payeeName else "Pay via UPI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(payload.vpa, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Amount (₹)") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (validation is UpiUssdScanToPayBuilder.Result.Invalid && amount.isNotBlank()) {
                    Text(
                        ussdRejectionMessage(validation.reason),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text("What happens when you tap Pay:", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NumberedStep(1, "The payee ID is copied to your clipboard.")
                    NumberedStep(2, "The phone opens the *99# menu automatically.")
                    NumberedStep(3, "Choose Send Money, then To VPA.")
                    NumberedStep(4, "Paste the payee ID when it's asked for.")
                    NumberedStep(5, "Type the amount you entered above.")
                    NumberedStep(6, "Enter your UPI PIN directly in that screen. This app never sees it.")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (validation is UpiUssdScanToPayBuilder.Result.Valid) {
                        copyVpaToClipboard(context, payload.vpa)
                        onConfirm("*99*1*3#")
                    }
                },
                enabled = validation is UpiUssdScanToPayBuilder.Result.Valid
            ) { Text("Pay via *99#") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NumberedStep(number: Int, text: String) {
    Row {
        Text("$number.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Copies [vpa] to the system clipboard so it can be pasted into the live
 * `*99#` prompt — matches Flowpay's own `copyVpaToClipboard` exactly,
 * including flagging the clip sensitive on Android 13+ (`ClipDescription
 * .EXTRA_IS_SENSITIVE`) so it's excluded from clipboard-history previews
 * and the system's own clipboard-access toast, since a VPA is a payee
 * identifier worth treating as sensitive even though it isn't a secret
 * the way a PIN is.
 */
private fun copyVpaToClipboard(context: android.content.Context, vpa: String) {
    val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("VPA", vpa)
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    clipboardManager.setPrimaryClip(clip)
}

private fun qrRejectionMessage(reason: UpiQrParser.Reason): String = when (reason) {
    UpiQrParser.Reason.EMPTY -> "That didn't look like a QR code at all. Try scanning again."
    UpiQrParser.Reason.NOT_A_UPI_QR -> "That's not a UPI payment QR code."
    UpiQrParser.Reason.MALFORMED -> "That QR code's payment details couldn't be read."
    UpiQrParser.Reason.NO_PAYEE_ADDRESS -> "That QR code doesn't have a payee to pay."
    UpiQrParser.Reason.INVALID_PAYEE_ADDRESS -> "That QR code's payee address doesn't look valid."
    UpiQrParser.Reason.INVALID_AMOUNT -> "That QR code's amount doesn't look valid."
}

private fun ussdRejectionMessage(reason: UpiUssdScanToPayBuilder.Reason): String = when (reason) {
    UpiUssdScanToPayBuilder.Reason.MISSING_VPA -> "No payee address."
    UpiUssdScanToPayBuilder.Reason.INVALID_VPA -> "Payee address doesn't look valid."
    UpiUssdScanToPayBuilder.Reason.MISSING_AMOUNT -> "Enter an amount."
    UpiUssdScanToPayBuilder.Reason.AMOUNT_NOT_A_NUMBER -> "Enter a valid amount."
    UpiUssdScanToPayBuilder.Reason.AMOUNT_BELOW_MINIMUM -> "Amount must be at least ₹1."
    UpiUssdScanToPayBuilder.Reason.AMOUNT_ABOVE_CAP -> "Amount is too large for this flow."
}

@Composable
private fun IouSectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun EmptySectionText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * One vocabulary shared with the Chat tab's peer rows — settled shares the
 * same stamp-ink green as a ready peer (both mean "confirmed, no action
 * needed"), rejected shares the one reserved red. A settled or rejected
 * entry gets the plain hairline border every ledger row has; only a still-
 * pending IOU keeps the dashed counterfoil edge, so the mark disappears
 * the moment it's actually resolved — the visual proof that it stopped
 * being a promise and became a closed entry. See
 * docs/adr/0019-ledger-register-redesign.md.
 */
@Composable
private fun IouCard(iou: IouEntity, onMarkSettled: (() -> Unit)? = null) {
    val (tint, statusWord) = when (iou.status) {
        "settled" -> SankatSetuColors.StatusSafe to "SETTLED"
        "rejected" -> SankatSetuColors.StatusCritical to "REJECTED"
        else -> SankatSetuColors.StatusCaution to "PENDING"
    }
    val pending = iou.status == "pending"
    Card(
        Modifier
            .fillMaxWidth()
            .let { if (pending) it.counterfoilEdge(tint.copy(alpha = 0.6f)) else it },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = if (pending) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${iou.counterpartyNickname} · ₹${"%.2f".format(iou.amountPaise / 100.0)}", style = MaterialTheme.typography.titleMedium)
                if (iou.memo.isNotBlank()) {
                    Text(iou.memo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                if (pending) StatusPill(statusWord, tint) else StampMark(statusWord, tint, seed = iou.iouId.hashCode())
            }
            if (onMarkSettled != null && pending) {
                OutlinedButton(onClick = onMarkSettled, shape = MaterialTheme.shapes.small) { Text("Mark paid") }
            }
        }
    }
}
