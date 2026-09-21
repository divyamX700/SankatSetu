package com.sankatsetu.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sankatsetu.app.assistant.AssistantEngine
import com.sankatsetu.app.assistant.AssistantSource
import com.sankatsetu.app.assistant.SuggestedAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class AssistantTurn(
    val id: String = UUID.randomUUID().toString(),
    val question: String,
    val answer: String? = null, // null while the answer is still being computed
    val sources: List<AssistantSource> = emptyList(),
    val wasGenerated: Boolean = false,
    val suggestedAction: SuggestedAction = SuggestedAction.NONE
)

data class AssistantUiState(
    val turns: List<AssistantTurn> = emptyList(),
    val isThinking: Boolean = false
)

/**
 * Entirely offline Q&A — [AssistantEngine] never touches the network. See
 * docs/adr/0009-on-device-assistant-scope.md for what's a genuine retrieval
 * algorithm today vs. what Day 3 upgrades (neural embeddings, a larger
 * corpus, on-device generation once a model is actually side-loaded).
 */
class AssistantViewModel(private val engine: AssistantEngine) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState

    fun ask(question: String) {
        if (question.isBlank()) return
        val turn = AssistantTurn(question = question)

        // Every question is answered independently — no prior exchange is
        // passed as history, ever. This used to carry the last couple of
        // turns forward so a follow-up like "what about for a child?"
        // could resolve against the preceding answer. A real-device test
        // found this ~0.5B model does NOT reliably obey the prompt's own
        // "ignore the prior answer for a different situation" instruction:
        // asking "how do I stop massive bleeding" (correctly answered with
        // tourniquet guidance) and then, in the same conversation, "how do
        // I apply a tourniquet" produced an answer contaminated with facts
        // from the FIRST question's context rather than a clean, correctly-
        // grounded tourniquet answer on its own — confirmed by asking the
        // exact same second question fresh (conversation cleared first),
        // which came back correct every time. A wrong answer to a genuinely
        // new question is worse than losing pronoun-resolution on a rare
        // follow-up, so history is off entirely rather than half-fixed.
        // See docs/adr/0024-llm-grounding-regression.md's update.
        _uiState.value = _uiState.value.copy(
            turns = _uiState.value.turns + turn,
            isThinking = true
        )

        viewModelScope.launch {
            val result = engine.answer(question, history = emptyList())
            val updatedTurns = _uiState.value.turns.map {
                if (it.id == turn.id) {
                    it.copy(
                        answer = result.text,
                        sources = result.sources,
                        wasGenerated = result.wasGenerated,
                        suggestedAction = result.suggestedAction
                    )
                } else it
            }
            _uiState.value = _uiState.value.copy(turns = updatedTurns, isThinking = false)
        }
    }

    /**
     * Clears the visible conversation. [ask] no longer carries any prior
     * turn into the model's own prompt (see its doc), so this only clears
     * what's shown on screen — there's no separate model-side context to
     * reset alongside it. Turns were never persisted to a database in the
     * first place (a restart already loses them); this just lets the
     * person do it deliberately, mid-session, without restarting the app.
     */
    fun clearConversation() {
        _uiState.value = AssistantUiState()
    }
}
