package com.sankatsetu.app.di

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.sankatsetu.app.assistant.AssistantEngine
import com.sankatsetu.app.assistant.KnowledgeBaseLoader
import com.sankatsetu.app.assistant.MediaPipeLlmAssistant
import com.sankatsetu.app.data.AppDatabase
import com.sankatsetu.app.mesh.authz.CedarAuthorizer
import com.sankatsetu.app.mesh.crypto.Identity
import com.sankatsetu.app.mesh.crypto.NicknameStore
import com.sankatsetu.app.mesh.emergency.SosManager
import com.sankatsetu.app.mesh.router.MessageRouter
import com.sankatsetu.app.maps.LocationProvider
import com.sankatsetu.app.maps.MapAreaStore
import com.sankatsetu.app.payments.IouManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Hand-rolled composition root — no DI framework, on purpose. Follows
 * Flowpay's own `AppContainer` pattern (see docs/adr/0002): for an app this
 * size, a graph a reader can follow by eye beats annotation-generated
 * indirection, and it's one less thing to debug at 2am on Day 3.
 *
 * Construction order matters: [identity] must exist before [messageRouter]
 * (the router signs/derives peer ID from it), and [database] has no
 * dependency on the mesh stack at all yet (Day 1) — that changes once
 * IouVoucher/CourierEnvelope persistence lands.
 */
class AppContainer(context: Context) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val identity: Identity = Identity.loadOrCreate(context)

    val nicknameStore: NicknameStore = NicknameStore(context, identity.peerId)

    val database: AppDatabase = AppDatabase.build(context)

    // Must run before any com.cedarpolicy.* class is touched (its static
    // initializer is what triggers cedar-java's native-library load) — see
    // CedarAuthorizer.prepareNativeLibraryPath's doc and
    // docs/adr/0017-cedar-cross-compile.md.
    val cedarAuthorizer: CedarAuthorizer = run {
        CedarAuthorizer.prepareNativeLibraryPath(context)
        val policyText = context.assets.open("cedar/policies.cedar").bufferedReader().use { it.readText() }
        CedarAuthorizer(policyText)
    }

    val messageRouter: MessageRouter = MessageRouter(
        localPeerId = identity.peerId,
        scope = appScope,
        signer = { data -> identity.sign(data) },
        cedarAuthorizer = cedarAuthorizer
    )

    // The model file is side-loaded, not bundled (docs/adr/0005) — on a
    // fresh device MediaPipeLlmAssistant.isAvailable is false and
    // AssistantEngine transparently falls back to extractive answers from
    // the knowledge base, per docs/adr/0009.
    private val llmAssistant = MediaPipeLlmAssistant(context, MediaPipeLlmAssistant.defaultModelPath(context))

    // Held separately from AssistantEngine (not just passed through) so the
    // Docs browser can list/read the raw knowledge base without the engine
    // exposing its retrieval-internal field.
    val knowledgeBase = KnowledgeBaseLoader.load(context)

    val assistantEngine: AssistantEngine = AssistantEngine(
        knowledgeBase = knowledgeBase,
        llm = llmAssistant
    )

    init {
        // Load the model off the UI thread as soon as the app starts,
        // instead of on the person's first question — pure latency
        // reduction, see MediaPipeLlmAssistant.warmUp's doc.
        appScope.launch { llmAssistant.warmUp() }
    }

    val iouManager: IouManager = IouManager(
        identity = identity,
        router = messageRouter,
        peerDao = database.peerDao(),
        iouDao = database.iouDao(),
        scope = appScope
    )

    // Offline maps (Day 4) — see docs/adr/0020-offline-maps.md. Neither of
    // these touches Room/SQLCipher: a GPS fix isn't persisted data, and the
    // downloaded-area record is small, non-sensitive metadata, same tier as
    // NicknameStore's own SharedPreferences use. Declared before
    // [sosManager] since SOS location tagging (docs/adr/0021) reuses this
    // same provider rather than each feature keeping its own.
    val locationProvider: LocationProvider = LocationProvider(context)
    val mapAreaStore: MapAreaStore = MapAreaStore(context)

    val sosManager: SosManager = SosManager(
        identity = identity,
        router = messageRouter,
        peerDao = database.peerDao(),
        sosDao = database.sosDao(),
        locationProvider = locationProvider,
        scope = appScope
    )

    // The real, live Bluetooth radio state — not to be confused with
    // MessageRouter's connected-link count, which only exists once a BLE
    // link has already formed. This is the one honest source for "is the
    // radio even on," registered once at app scope rather than per-screen,
    // so ChatUiState.bluetoothOn (see ChatViewModel) reflects the actual
    // adapter instead of a value nothing ever updates.
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val _bluetoothOn = MutableStateFlow(bluetoothManager?.adapter?.isEnabled == true)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                _bluetoothOn.value = state == BluetoothAdapter.STATE_ON
            }
        }
        context.applicationContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }
}
