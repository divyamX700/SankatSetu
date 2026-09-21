package com.sankatsetu.app.ui.emergency

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sankatsetu.app.data.SosEntity
import com.sankatsetu.app.mesh.emergency.SosManager
import com.sankatsetu.app.mesh.protocol.SosCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the SOS surface on the Chat tab — see docs/TODO.md's ideation and
 * [SosManager]'s doc for the reasoning. [uiState] backs both the
 * reviewable log (each row pulses its own attention animation when fresh
 * and unacknowledged, see `ChatScreen.kt`'s `SosLogRow`) and
 * [SosUiState.hasUnreadIncoming], which only drives a small badge on the
 * Chat tab's nav icon — deliberately not a blocking interrupt, see
 * `MainActivity`'s own note on why that was replaced.
 */
data class SosUiState(val alerts: List<SosEntity> = emptyList()) {
    /** True if there's at least one incoming alert nobody has tapped yet. Never true for our own outgoing reports. */
    val hasUnreadIncoming: Boolean
        get() = alerts.any { !it.isOutgoing && !it.acknowledged }
}

class SosViewModel(private val sosManager: SosManager) : ViewModel() {
    private val _uiState = MutableStateFlow(SosUiState())
    val uiState: StateFlow<SosUiState> = _uiState

    // Which alerts have already run their attention pulse. Plain mutable
    // set on the ViewModel, not per-row Compose `remember` state: the
    // emergency log is a LazyColumn, and a row scrolled out of the visible
    // window and back gets recomposed fresh — a `remember` living only on
    // that row replayed the pulse every time, a real bug found by testing.
    // The ViewModel outlives any single row's composition, so this is the
    // one place "have I already shown this" can actually stick.
    private val pulsedSosIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            sosManager.observeAll().collect { alerts -> _uiState.value = SosUiState(alerts) }
        }
    }

    fun sendSos(category: SosCategory) {
        viewModelScope.launch { sosManager.broadcastSos(category) }
    }

    fun acknowledge(sosId: String) {
        viewModelScope.launch { sosManager.acknowledge(sosId) }
    }

    /** True the first time this is called for a given [sosId], false every time after — see [pulsedSosIds]'s doc. */
    fun consumeAttentionPulse(sosId: String): Boolean = pulsedSosIds.add(sosId)
}
