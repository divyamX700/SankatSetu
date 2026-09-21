package com.sankatsetu.app.ui.assistant

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.sankatsetu.app.assistant.KnowledgeChunk
import com.sankatsetu.app.assistant.KnowledgeImage
import com.sankatsetu.app.ui.theme.ConsoleReadoutStyle
import com.sankatsetu.app.ui.theme.pressScale
import com.sankatsetu.app.ui.theme.rememberShimmerProgress

/** Which layer of the Docs browser is showing, if any — see [DocsBrowser]. Back (system or app bar) steps down one level instead of leaving the tab. */
private sealed class DocsView {
    data object Closed : DocsView()
    data object List : DocsView()
    data class Reading(val source: String) : DocsView()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel,
    knowledgeBase: List<KnowledgeChunk>,
    onBroadcastSafe: () -> Unit,
    onOpenPay: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var draft by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    var docsView by remember { mutableStateOf<DocsView>(DocsView.Closed) }
    var showClearConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = docsView is DocsView.Reading) { docsView = DocsView.List }
    BackHandler(enabled = docsView is DocsView.List) { docsView = DocsView.Closed }

    if (docsView != DocsView.Closed) {
        DocsBrowser(
            knowledgeBase = knowledgeBase,
            view = docsView,
            onOpen = { source -> docsView = DocsView.Reading(source) },
            onBack = { docsView = if (docsView is DocsView.Reading) DocsView.List else DocsView.Closed }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistant", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    if (state.turns.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Clear conversation")
                        }
                    }
                    IconButton(onClick = { docsView = DocsView.List }) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Browse offline guides")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
        if (state.turns.isEmpty()) {
            // weight(1f), not fillMaxSize(): a fillMaxSize() sibling claims
            // the whole Column's height, leaving nothing for the input Row
            // below it — real-device screenshot found this pushing the
            // question field and send button off the bottom of the screen
            // entirely, hidden under the app's own nav bar.
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Ask about first aid, evacuation, or what to do in an emergency. Works completely offline.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(state.turns, key = { it.id }) { turn ->
                    TurnCard(
                        turn = turn,
                        onBroadcastSafe = onBroadcastSafe,
                        onOpenPay = onOpenPay
                    )
                }
                if (state.isThinking) {
                    item { ThinkingIndicator() }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.extraLarge,
                placeholder = { Text("Ask something…") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        if (draft.isNotBlank()) {
                            viewModel.ask(draft.trim())
                            draft = ""
                        }
                        keyboardController?.hide()
                    }
                )
            )
            Spacer(Modifier.width(8.dp))
            val sendInteraction = remember { MutableInteractionSource() }
            IconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        viewModel.ask(draft.trim())
                        draft = ""
                    }
                    keyboardController?.hide()
                },
                interactionSource = sendInteraction,
                enabled = draft.isNotBlank(),
                modifier = Modifier
                    .pressScale(sendInteraction)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(if (draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Ask",
                    tint = if (draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear this conversation?") },
            text = { Text("This removes every question and answer shown here. The assistant won't remember any of it for follow-up questions.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearConversation()
                    showClearConfirm = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * [turn.suggestedAction] is the agent's further stage beyond a plain
 * answer — see docs/adr/0016-on-device-agent-architecture.md. It never
 * fires on its own: an action chip only calls [onBroadcastSafe]/
 * [onOpenPay] on an explicit tap.
 */
@Composable
private fun TurnCard(
    turn: AssistantTurn,
    onBroadcastSafe: () -> Unit,
    onOpenPay: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        // The question, right-aligned like an outgoing chat bubble.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    turn.question,
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        turn.answer?.let { answer ->
            Card(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(answer, style = MaterialTheme.typography.bodyLarge)
                    // Retrieved source passages (turn.sources) are what the
                    // answer is grounded in, but not shown here anymore —
                    // the per-source chip list read as clutter at the end of
                    // every answer. The data is still there on AssistantTurn
                    // if a future UI (e.g. a "why this answer" expandable)
                    // wants it.
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (turn.wasGenerated) Icons.Filled.AutoAwesome else Icons.Filled.Description,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp).size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (turn.wasGenerated) "Generated on-device" else "Direct excerpt. On-device AI isn't available right now.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Deterministic — never chosen by the model. Only the
                    // SINGLE top-ranked retrieved source's image is shown,
                    // not any image among the top 3 matches: a real-device
                    // test asking "how do I put on a bandage for a wound"
                    // showed the tourniquet photo instead of a wound-care
                    // one, because "bandage"/"wound" also scored against
                    // the tourniquet section (ranked #2 or #3) even though
                    // the generated text was actually grounded in "Cleaning
                    // Minor Wounds" (ranked #1, no image attached). Using
                    // only sources.firstOrNull() — the strongest single
                    // lexical match, same passage AssistantEngine's
                    // extractive fallback would use verbatim — means a
                    // shown image is always about the same passage the
                    // answer is actually grounded in most strongly, never
                    // a plausible-looking but wrong runner-up.
                    val turnImage = remember(turn.sources) { turn.sources.firstOrNull()?.image }
                    if (turnImage != null) {
                        Spacer(Modifier.height(10.dp))
                        KnowledgeImageCard(turnImage)
                    }

                    // The suggested-action chip (Broadcast "I'm safe" /
                    // Open Pay tab) is intentionally never rendered, even
                    // though AssistantEngine still parses an Action line
                    // out of the model's own output — a real-device test
                    // found this ~0.5B model does not reliably follow the
                    // prompt's "BROADCAST_SAFE only if the danger already
                    // passed" instruction, the same class of unreliable
                    // self-classification documented for conversation
                    // history in AssistantViewModel.ask()'s doc. It showed
                    // up on unrelated first-aid questions ("how to stop
                    // massive bleeding") where it was actively wrong, not
                    // just unhelpful. turn.suggestedAction is left in the
                    // data model rather than removed, since AssistantEngine
                    // parsing it out of the visible answer text is still
                    // correct and necessary regardless of whether the UI
                    // acts on it.
                }
            }
        }
    }
}

/**
 * Renders one deterministically-attached [KnowledgeImage] straight from
 * `assets/` — no network, no LLM involvement. Decoded once per [image]
 * (keyed by its asset path) rather than on every recomposition; a missing or
 * corrupt asset degrades to rendering nothing rather than crashing the turn.
 */
@Composable
private fun KnowledgeImageCard(image: KnowledgeImage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(image.assetPath) {
        runCatching { context.assets.open(image.assetPath).use { BitmapFactory.decodeStream(it) } }.getOrNull()
    } ?: return

    Column(modifier.fillMaxWidth()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = image.caption,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.FillWidth
        )
        Spacer(Modifier.height(6.dp))
        Text(image.caption, style = MaterialTheme.typography.bodySmall)
        if (image.attribution.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(image.attribution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A moving shimmer bar — replaces the plain spinner+"Thinking…" row with something that reads as active work happening, not a stalled app. */
@Composable
private fun ThinkingIndicator() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShimmerLine(modifier = Modifier.fillMaxWidth().height(16.dp))
        ShimmerLine(modifier = Modifier.fillMaxWidth(0.7f).height(16.dp))
    }
}

@Composable
private fun ShimmerLine(modifier: Modifier = Modifier) {
    val progress = rememberShimmerProgress()
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(progress * 600f - 300f, 0f),
                    end = Offset(progress * 600f, 0f)
                )
            )
    ) {}
}

/**
 * Lets the person read the offline knowledge base directly, not just
 * through a generated answer — the same guides the Assistant already
 * grounds its answers in, browsable on their own. Two levels: a list of
 * documents ([DocsView.List]), then one document's sections stacked and
 * scrollable ([DocsView.Reading]). Back (system, gesture, or the app bar's
 * arrow) steps down one level via the [BackHandler]s in [AssistantScreen],
 * never straight out of the tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocsBrowser(
    knowledgeBase: List<KnowledgeChunk>,
    view: DocsView,
    onOpen: (String) -> Unit,
    onBack: () -> Unit
) {
    // Preserves each document's on-disk order (chunk id embeds a
    // sequential index — see KnowledgeDocumentParser) and the order
    // documents were first seen, rather than re-sorting alphabetically.
    val bySource = remember(knowledgeBase) { knowledgeBase.groupBy { it.source } }
    val titles = remember(bySource) { bySource.keys.toList() }
    val title = when (view) {
        is DocsView.Reading -> view.source
        else -> "Offline Guides"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when (view) {
            is DocsView.Reading -> {
                val sections = bySource[view.source].orEmpty()
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(sections, key = { it.id }) { chunk ->
                        Column {
                            Text(chunk.section, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(6.dp))
                            Text(chunk.text, style = MaterialTheme.typography.bodyLarge)
                            chunk.image?.let { image ->
                                Spacer(Modifier.height(10.dp))
                                KnowledgeImageCard(image)
                            }
                        }
                    }
                }
            }
            else -> {
                // Read as a reference-index register — the printed rules
                // page bound into the back of a real passbook — rather than
                // a generic file list: each entry numbered like an index
                // card, section count set in the ledger's own tabular
                // monospace. See docs/adr/0019-ledger-register-redesign.md.
                LazyColumn(
                    Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(titles) { index, source ->
                        val sectionCount = bySource[source]?.size ?: 0
                        val interaction = remember { MutableInteractionSource() }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressScale(interaction)
                                .clickable(interactionSource = interaction, indication = null, onClick = { onOpen(source) }),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (index + 1).toString().padStart(2, '0'),
                                    style = ConsoleReadoutStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(source, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "$sectionCount ${if (sectionCount == 1) "entry" else "entries"}",
                                        style = ConsoleReadoutStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
