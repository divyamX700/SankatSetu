package com.sankatsetu.app.ui.pay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sankatsetu.app.data.IouEntity
import com.sankatsetu.app.data.PeerDao
import com.sankatsetu.app.payments.IouManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PeerPickerEntry(val peerIdBase64: String, val nickname: String)

data class PayUiState(
    val peers: List<PeerPickerEntry> = emptyList(),
    val ious: List<IouEntity> = emptyList()
) {
    val owedToMe: List<IouEntity> get() = ious.filter { it.isOwedToMe && it.status == "pending" }
    val iOwe: List<IouEntity> get() = ious.filter { !it.isOwedToMe && it.status == "pending" }
    val settled: List<IouEntity> get() = ious.filter { it.status != "pending" }
}

/**
 * Backs the Pay tab (docs/PRD.md §F3/F4): USSD/123Pay dialing (see
 * `PayScreen.kt` — this ViewModel never touches telephony itself, only the
 * IOU voucher half) and the mesh IOU voucher list/composer.
 */
class PayViewModel(
    private val iouManager: IouManager,
    private val peerDao: PeerDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(PayUiState())
    val uiState: StateFlow<PayUiState> = _uiState

    init {
        viewModelScope.launch {
            combine(peerDao.observeAll(), iouManager.observeAll()) { peers, ious ->
                PayUiState(
                    peers = peers.map { PeerPickerEntry(it.peerIdBase64, it.nickname) },
                    ious = ious
                )
            }.collect { _uiState.value = it }
        }
    }

    /** [amountPaise] — always paise (1 rupee = 100 paise), matching Flowpay's own amount convention (docs/adr/0002). */
    fun sendIou(peerIdBase64: String, peerNickname: String, amountPaise: Long, memo: String) {
        if (amountPaise <= 0) return
        viewModelScope.launch { iouManager.sendIou(peerIdBase64, peerNickname, amountPaise, memo) }
    }

    fun markSettled(iouId: String) {
        viewModelScope.launch { iouManager.markSettled(iouId) }
    }

    fun rejectIou(iouId: String) {
        viewModelScope.launch { iouManager.rejectIou(iouId) }
    }
}
