package com.sankatsetu.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sankatsetu.app.SankatSetuApplication
import com.sankatsetu.app.mesh.transport.MeshForegroundService
import com.sankatsetu.app.ui.assistant.AssistantScreen
import com.sankatsetu.app.ui.assistant.AssistantViewModel
import com.sankatsetu.app.ui.chat.ChatListScreen
import com.sankatsetu.app.ui.chat.ChatThreadScreen
import com.sankatsetu.app.ui.chat.ChatViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.sankatsetu.app.ui.chat.PeerUiModel
import com.sankatsetu.app.ui.emergency.SosViewModel
import com.sankatsetu.app.ui.map.MapScreen
import com.sankatsetu.app.ui.map.MapViewModel
import com.sankatsetu.app.ui.pay.PayScreen
import com.sankatsetu.app.ui.pay.PayViewModel
import com.sankatsetu.app.ui.theme.ConsoleReadoutStyle
import com.sankatsetu.app.ui.theme.SankatSetuColors
import com.sankatsetu.app.ui.theme.SankatSetuTheme

// Real drawn icons, not emoji — see docs/adr/0013-operate-mode-color-and-icons.md.
// Assistant used a "robot head" glyph (SmartToy) — the generic cartoon-bot
// icon that reads as a placeholder "AI feature" sticker in a lot of apps,
// not a considered design choice. A sparkle (AutoAwesome) is what Apple's
// own AI-feature glyph actually looks like, and it's already the icon this
// app uses everywhere else it marks on-device-generated content (see
// AssistantScreen.kt's empty state and "Generated on-device" label) — this
// makes the tab icon consistent with that, not a new symbol. See
// docs/adr/0018-ui-revamp.md.
private enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat),
    PAY("Pay", Icons.Filled.Payments),
    MAP("Map", Icons.Filled.Map),
    ASSISTANT("Assistant", Icons.Filled.AutoAwesome)
}

/**
 * Day 2 scope: a bottom nav bar switches between Chat and the offline
 * Assistant, both still using plain Compose state rather than
 * Navigation-Compose — Pay/SOS tabs (PRD §11.1) land Day 3 as those
 * features exist. The Chat tab's own list/thread state predates this and is
 * unchanged from Day 1.
 */
class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Day 1's assumption here was wrong, caught by actually running this
        // on a device: Android 14 validates a connectedDevice foreground
        // service's permissions at Service.onCreate()/startForeground() time
        // and throws — a hard crash, not a graceful no-op — if none of the
        // "relevant" permissions (BLUETOOTH_ADVERTISE/CONNECT/SCAN) are
        // granted yet. Starting the service unconditionally after merely
        // *requesting* permissions (not checking whether they were granted)
        // crashed the app on first launch. A per-permission rationale UI
        // that lets the user retry from Settings (PRD §5.1 Setup 3/4) is
        // still Day 3 polish; this is the minimum fix to not crash.
        val hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            grants[Manifest.permission.BLUETOOTH_ADVERTISE] == true ||
                grants[Manifest.permission.BLUETOOTH_CONNECT] == true ||
                grants[Manifest.permission.BLUETOOTH_SCAN] == true
        } else {
            true // pre-API-31 BLE doesn't need a runtime-requested "relevant" FGS permission the same way
        }
        if (hasBluetoothPermission) {
            startMeshService()
        }
    }

    private lateinit var chatViewModel: ChatViewModel
    private lateinit var assistantViewModel: AssistantViewModel
    private lateinit var payViewModel: PayViewModel
    private lateinit var sosViewModel: SosViewModel
    private lateinit var mapViewModel: MapViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as SankatSetuApplication
        chatViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChatViewModel(
                        identity = app.container.identity,
                        router = app.container.messageRouter,
                        peerDao = app.container.database.peerDao(),
                        messageDao = app.container.database.messageDao(),
                        nicknameStore = app.container.nicknameStore,
                        bluetoothState = app.container.bluetoothOn,
                        locationProvider = app.container.locationProvider
                    ) as T
                }
            }
        )[ChatViewModel::class.java]

        assistantViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AssistantViewModel(engine = app.container.assistantEngine) as T
                }
            }
        )[AssistantViewModel::class.java]

        payViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PayViewModel(
                        iouManager = app.container.iouManager,
                        peerDao = app.container.database.peerDao()
                    ) as T
                }
            }
        )[PayViewModel::class.java]

        sosViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SosViewModel(sosManager = app.container.sosManager) as T
                }
            }
        )[SosViewModel::class.java]

        mapViewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MapViewModel(
                        locationProvider = app.container.locationProvider,
                        mapAreaStore = app.container.mapAreaStore,
                        sosManager = app.container.sosManager,
                        peerDao = app.container.database.peerDao()
                    ) as T
                }
            }
        )[MapViewModel::class.java]

        requestPermissions.launch(requiredPermissions())

        setContent {
            SankatSetuTheme {
                Surface(modifier = Modifier) {
                    var currentTab by remember { mutableStateOf(Tab.CHAT) }
                    // Holds only the peer's *id*, not a PeerUiModel snapshot — a
                    // held snapshot would freeze connectivity/handshake state at
                    // whatever it was the moment the thread was opened, which is
                    // exactly the bug real-device testing found: the thread kept
                    // showing "online" long after the peer actually disconnected,
                    // because the stale snapshot never got replaced. Re-deriving
                    // it from the live uiState below keeps it current.
                    var openThreadPeerId by remember { mutableStateOf<String?>(null) }
                    val chatState by chatViewModel.uiState.collectAsState()
                    val sosState by sosViewModel.uiState.collectAsState()

                    // System Back / edge-swipe must never exit the app out
                    // from under an open thread or a non-home tab — it
                    // should step back one level at a time, same as every
                    // other Android app. Order matters: an open thread
                    // closes first, then a non-Chat tab returns to Chat,
                    // then (nothing left to intercept) the system's own
                    // default finishes the Activity.
                    BackHandler(enabled = openThreadPeerId != null) { openThreadPeerId = null }
                    BackHandler(enabled = openThreadPeerId == null && currentTab != Tab.CHAT) { currentTab = Tab.CHAT }

                    Scaffold(
                        bottomBar = {
                            androidx.compose.foundation.layout.Column {
                                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    tonalElevation = 0.dp
                                ) {
                                    Tab.entries.forEach { tab ->
                                        // A small tag, not a blocking dialog, for "something arrived
                                        // while you weren't on Chat" — see the SosLogRow doc in
                                        // ChatScreen.kt for why the old full-screen interrupt was
                                        // replaced. A plain Material dot read as too subtle for
                                        // something genuinely emergency-relevant (direct feedback),
                                        // so this spells out "SOS" instead of just coloring a dot.
                                        // Only shown for a tab you're NOT currently on: once you're
                                        // on Chat the log itself is the notification.
                                        val showSosTag = tab == Tab.CHAT && currentTab != Tab.CHAT && sosState.hasUnreadIncoming
                                        NavigationBarItem(
                                            selected = currentTab == tab,
                                            onClick = { currentTab = tab },
                                            icon = {
                                                if (showSosTag) {
                                                    Box {
                                                        Icon(tab.icon, contentDescription = null)
                                                        Box(
                                                            Modifier
                                                                .align(Alignment.TopEnd)
                                                                .offset(x = 14.dp, y = (-8).dp)
                                                                .background(SankatSetuColors.StatusCritical, RoundedCornerShape(3.dp))
                                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                                        ) {
                                                            Text(
                                                                "SOS",
                                                                color = Color.White,
                                                                style = ConsoleReadoutStyle.copy(fontSize = 9.sp)
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    Icon(tab.icon, contentDescription = null)
                                                }
                                            },
                                            label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                            )
                                        )
                                    }
                                }
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.background
                    ) { padding ->
                        Surface(Modifier.padding(padding).fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            androidx.compose.animation.Crossfade(targetState = currentTab, label = "tab") { tab ->
                                when (tab) {
                                    Tab.CHAT -> {
                                        val thread = openThreadPeerId?.let { id -> chatState.peers.find { it.peerIdBase64 == id } }
                                        if (thread == null) {
                                            ChatListScreen(
                                                viewModel = chatViewModel,
                                                sosViewModel = sosViewModel,
                                                onOpenThread = { openThreadPeerId = it.peerIdBase64 },
                                                onViewSosOnMap = { sosId ->
                                                    mapViewModel.highlightSos(sosId)
                                                    currentTab = Tab.MAP
                                                }
                                            )
                                        } else {
                                            ChatThreadScreen(viewModel = chatViewModel, peer = thread, onBack = { openThreadPeerId = null })
                                        }
                                    }
                                    Tab.PAY -> PayScreen(viewModel = payViewModel)
                                    Tab.MAP -> MapScreen(viewModel = mapViewModel)
                                    Tab.ASSISTANT -> AssistantScreen(
                                        viewModel = assistantViewModel,
                                        knowledgeBase = app.container.knowledgeBase,
                                        onBroadcastSafe = { chatViewModel.broadcastImSafe() },
                                        onOpenPay = { currentTab = Tab.PAY }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startMeshService() {
        val intent = Intent(this, MeshForegroundService::class.java)
        startForegroundService(intent)
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return permissions.toTypedArray()
    }
}
