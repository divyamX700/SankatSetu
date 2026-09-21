package com.sankatsetu.app.assistant

/**
 * One source passage the answer is grounded in, for the UI's source card
 * (PRD §11.2's Assistant screen). [image] is carried straight from the
 * matched [KnowledgeChunk] — never chosen here, never touched by the LLM.
 * See [KnowledgeBaseLoader] for how a chunk gets an image in the first place.
 */
data class AssistantSource(val source: String, val section: String, val text: String, val image: KnowledgeImage? = null)

/** One prior question+answer, for multi-turn context — see [AssistantEngine.answer]. */
data class AssistantExchange(val question: String, val answer: String)

/**
 * A real, already-built app action the agent decided is worth offering —
 * never auto-fired; the UI always asks the person to confirm. See
 * docs/adr/0016-on-device-agent-architecture.md for why only these two
 * exist: they're the only two non-chat actions the app actually has today.
 */
enum class SuggestedAction { NONE, BROADCAST_SAFE, OPEN_PAY }

data class AssistantAnswer(
    val text: String,
    val sources: List<AssistantSource>,
    /** True if [text] came from the LLM; false if it's the extractive fallback (top retrieved passage verbatim). */
    val wasGenerated: Boolean,
    val suggestedAction: SuggestedAction = SuggestedAction.NONE
)

private const val FALLBACK_NO_MATCH =
    "I don't have specific guidance for this. Call 112 immediately."

private const val EMERGENCY_NUMBER_REMINDER = "\n\nCall 112 immediately if this is a medical or life-threatening emergency."

/** How many prior exchanges to carry into the prompt — kept small on purpose, see [AssistantEngine.buildPrompt]. */
private const val MAX_HISTORY_TURNS = 2

private val ACTION_LINE = Regex("(?m)^Action:\\s*(NONE|BROADCAST_SAFE|OPEN_PAY)\\s*$")

// A real-device generation echoed the prompt's own "Action rule:" heading
// as a literal line in the visible answer (the format's "Action:" line and
// the explanatory "Action rule:" heading share a first word, and this
// small model occasionally confuses the two). The prompt wording now tells
// it not to, which should make this rare — but stripping the line
// defensively costs nothing and means a slip never reaches the screen.
private val STRAY_ACTION_RULE_LINE = Regex("(?m)^Action rule:.*$\\n?")

/**
 * A real-device generation was observed starting with a literal two-char
 * `\n` (backslash, then n) instead of an actual line break — this small
 * model occasionally emits the escape-sequence text rather than a real
 * newline byte. Compose's Text() correctly does not interpret escape
 * sequences in a runtime string, so left alone this renders as a visible
 * stray "\n" glyph. Converting it back to a real newline is a cheap,
 * harmless normalization regardless of why the model did it.
 */
private fun String.normalizeLiteralNewlines(): String = replace("\\n", "\n")

/**
 * Caps how much of a single retrieved chunk's text enters the LLM prompt's
 * context block. A real-device test asking three unrelated questions (a
 * bandage, a tourniquet, and CPR) got the SAME generic wound-cleaning answer
 * for all three, despite [KnowledgeRetriever] correctly ranking a different,
 * on-topic passage first each time (verified directly against the retriever,
 * not assumed) — the model was ignoring correct context, not being given
 * wrong context. Root cause: knowledge-base sections roughly doubled in
 * length in a later content pass (public-health-sourced detail per section,
 * each with an inline citation), so a 3-passage context block plus the
 * instruction preamble routinely built prompts past 5000 characters
 * (~1300+ tokens) — over the `.task` bundle's 1280-token KV cache
 * (see [MediaPipeLlmAssistant]'s `setMaxTokens(1200)` comment) BEFORE any
 * output token is generated. [MediaPipeLlmAssistant] has no way to safely
 * raise that ceiling (it's fixed by how the model file itself was
 * converted), so the fix has to shrink what goes in, not raise what fits.
 * Cutting at the last sentence boundary before the cap (falling back to a
 * hard cut only if no sentence boundary exists) keeps the truncated text
 * grammatical instead of trailing off mid-word. This only affects what the
 * model reads to draft its own answer — [AssistantSource.text] (the
 * "why this answer" data and the Docs browser's full-text reading view)
 * still carries the complete, untruncated section. See
 * docs/adr/0023-image-grounded-answers.md's "Consequences" for how this was
 * found during the same testing pass that verified the image feature.
 */
private const val MAX_CONTEXT_CHARS_PER_CHUNK = 500

private fun String.truncatedForPrompt(): String {
    if (length <= MAX_CONTEXT_CHARS_PER_CHUNK) return this
    val window = substring(0, MAX_CONTEXT_CHARS_PER_CHUNK)
    val cut = window.lastIndexOfAny(charArrayOf('.', '!', '?'))
    return if (cut >= MAX_CONTEXT_CHARS_PER_CHUNK / 2) window.substring(0, cut + 1) else "$window…"
}

/**
 * Retrieval-augmented, single-call agent: one on-device generation does
 * triage (is a real app action warranted), grounding (the existing
 * [KnowledgeRetriever] search), and drafting (the structured guide itself)
 * together — see docs/adr/0016-on-device-agent-architecture.md for why this
 * is one call, not three round-trips, and what "the agentic architecture"
 * means for a 1B on-device model where every extra call costs 12-20s.
 * [MediaPipeLlmAssistant] already retries a bad/empty sample once before
 * giving up (see its doc), so the extractive fallback here is for the case
 * that's actually undegradable: no model side-loaded at all, or the
 * device's second attempt still failing. That fallback is never silent —
 * [AssistantAnswer.wasGenerated] tells the UI exactly which happened, and
 * `AssistantScreen.kt` labels it visibly.
 */
class AssistantEngine(
    private val knowledgeBase: List<KnowledgeChunk>,
    private val llm: LlmAssistant = UnavailableLlmAssistant
) {
    /** [history] is prior turns in *this* conversation, oldest first — see [buildPrompt] for how much of it is actually used. */
    suspend fun answer(query: String, history: List<AssistantExchange> = emptyList()): AssistantAnswer {
        val matches = KnowledgeRetriever.search(query, knowledgeBase, topK = 3)
        if (matches.isEmpty()) {
            // No knowledge-base match doesn't mean no answer is possible — a
            // real bug found by actually asking the app "hello": this used
            // to return FALLBACK_NO_MATCH unconditionally, meaning the LLM
            // never even ran for anything outside the 22-file crisis KB, so
            // plain conversation was impossible. The KB is what GROUNDS a
            // first-aid answer in a real source (never skipped when it has
            // something relevant — see the branch below this one), not a
            // gate on whether the model gets to respond at all. When there's
            // nothing to ground an answer in, the model still runs, just on
            // a plain conversational prompt instead of the structured
            // guide format, and the UI is told sources is empty so it never
            // implies this came from the knowledge base.
            if (llm.isAvailable) {
                val raw = llm.generate(buildGeneralPrompt(query, history))?.trim()?.normalizeLiteralNewlines()
                if (!raw.isNullOrEmpty()) {
                    return AssistantAnswer(raw, emptyList(), wasGenerated = true)
                }
            }
            return AssistantAnswer(FALLBACK_NO_MATCH, emptyList(), wasGenerated = false)
        }

        val sources = matches.map { AssistantSource(it.chunk.source, it.chunk.section, it.chunk.text, it.chunk.image) }

        if (llm.isAvailable) {
            // Only the single best-ranked chunk is fed to the model — NOT
            // all of `matches` (topK=3), even though `sources` above
            // intentionally keeps all 3 for the UI's citations/image. A
            // real-device regression this session: three unrelated
            // questions (a bandage, a tourniquet, CPR) kept getting the
            // same generic wound-cleaning answer even after fixing a real
            // prompt-overflow bug (see MAX_CONTEXT_CHARS_PER_CHUNK's doc)
            // that made the prompt fit comfortably in-budget again. That
            // means a 3-passage context block gives this ~0.5B model room
            // to drift toward a memorized-sounding generic answer instead
            // of the specific top-ranked passage, even when that passage is
            // both correct (verified directly against KnowledgeRetriever)
            // and well within the token budget on its own. Restricting to
            // one passage is a reasonable next lever given the evidence,
            // NOT a confirmed fix — there was no device left to re-test
            // this change live. See docs/adr/0024-llm-grounding-regression.md.
            val prompt = buildPrompt(query, matches.take(1).map { it.chunk }, history)
            // A real-device test tried an outer retry here for a missing
            // Action line, and found a worse bug: MediaPipeLlmAssistant's
            // own generate() is ALREADY a length-gated retry loop (see its
            // doc), so one outer retry here authorized up to 4 total
            // on-device generations for a single question — one real query
            // measured 86 seconds end to end. Reordering the Action line to
            // the FRONT of the format (see buildPrompt) is what actually
            // fixes the truncation this was trying to work around, so a
            // second, separate retry layer here was solving an
            // already-solved problem at real latency cost. A missing or
            // malformed Action line now just reads as NONE — see the
            // "defaults to NONE" test — same as any other format slip.
            val raw = llm.generate(prompt)?.trim()?.normalizeLiteralNewlines()
            if (!raw.isNullOrEmpty()) {
                val action = ACTION_LINE.find(raw)?.groupValues?.get(1)
                    ?.let { runCatching { SuggestedAction.valueOf(it) }.getOrDefault(SuggestedAction.NONE) }
                    ?: SuggestedAction.NONE
                // .trim(), not .trimEnd(): the Action line is now the
                // FIRST line (see buildPrompt — moving it there is what
                // fixed a real truncation bug, see docs/adr/0016's update),
                // so stripping it leaves a leading blank line to clean up
                // too, not just a trailing one.
                val text = raw.replace(ACTION_LINE, "").replace(STRAY_ACTION_RULE_LINE, "").trim()
                return AssistantAnswer(text, sources, wasGenerated = true, suggestedAction = action)
            }
        }

        // Extractive fallback: the single best-matched passage, verbatim.
        // Not as useful as a synthesized guide, but never fabricates
        // anything beyond what's actually in the knowledge base — and the
        // UI marks this state visibly rather than presenting it as if it
        // were a real generated answer. No action suggestion here: that
        // judgment call needs the model, not a raw passage.
        val best = matches.first().chunk
        return AssistantAnswer(best.text + EMERGENCY_NUMBER_REMINDER, sources, wasGenerated = false)
    }

    /**
     * Asks for a short structured guide — situation + numbered steps + what
     * to avoid + the 112 reminder — synthesized from the context in the
     * model's own words, not the raw retrieved passages pasted back, PLUS
     * one triage/action line the app parses out before showing the text
     * (see [ACTION_LINE]). Early testing (see docs/adr/0011's update)
     * showed this small model reliably *completes* this format rather than
     * trailing off, likely because a numbered list gives it a concrete
     * continuation pattern to follow — whereas an open-ended "write a
     * paragraph" instruction is exactly what produced short, cut-off
     * answers before.
     *
     * History is capped at [MAX_HISTORY_TURNS] prior exchanges, kept short:
     * this model's `.task` bundle has a 1280-token KV cache shared across
     * prompt *and* generated output (see `MediaPipeLlmAssistant`), so
     * unbounded history would eventually crowd out the retrieved context or
     * the answer itself. Two turns is enough for "and what about children"
     * follow-up-style questions without risking that.
     */
    private fun buildPrompt(query: String, chunks: List<KnowledgeChunk>, history: List<AssistantExchange>): String {
        val context = chunks.withIndex().joinToString("\n\n") { (i, chunk) ->
            "[${i + 1}] ${chunk.text.truncatedForPrompt()}\n    Source: ${chunk.source}, section ${chunk.section}"
        }
        val historyBlock = if (history.isEmpty()) {
            ""
        } else {
            val recent = history.takeLast(MAX_HISTORY_TURNS)
            "Conversation so far (for resolving pronouns/follow-ups ONLY, not a source of facts):\n" +
                recent.joinToString("\n") { "User: ${it.question}\nGuide: ${it.answer}" } +
                "\n\n"
        }

        return buildGuidePrompt(query, context, historyBlock)
    }

    /**
     * Plain conversation, used only when [KnowledgeRetriever] found nothing
     * to ground an answer in — see [answer]'s doc for why this exists at
     * all. Deliberately not the structured Action/Situation/numbered-step
     * format: that format is a promise the answer is grounded in the crisis
     * KB, and this path by definition isn't. A short honest instruction
     * (say so instead of inventing medical/disaster facts) is the only
     * safety rule that survives from [buildPrompt] here.
     */
    private fun buildGeneralPrompt(query: String, history: List<AssistantExchange>): String {
        val historyBlock = if (history.isEmpty()) {
            ""
        } else {
            val recent = history.takeLast(MAX_HISTORY_TURNS)
            "Conversation so far:\n" + recent.joinToString("\n") { "User: ${it.question}\nGuide: ${it.answer}" } + "\n\n"
        }
        return """
            |Instruction: You are a calm, direct crisis-response guide for rural India, working
            |completely offline. The person just said something general, not a specific first-aid
            |or disaster question — reply naturally and briefly, in plain language, the way a real
            |person would. Do not invent a medical fact, dose, or disaster-response step; if they
            |later ask something you don't actually have grounded guidance for, say so plainly
            |instead of guessing.
            |
            |$historyBlock|New message: $query
            |
            |Answer:
        """.trimMargin()
    }

    private fun buildGuidePrompt(query: String, context: String, historyBlock: String): String {
        return """
            |Instruction: You are a calm, direct crisis-response guide for rural India, working
            |completely offline. Answer the new question as a short practical guide, in your own
            |words — do not copy text verbatim. Format your answer EXACTLY like this, and nothing
            |more:
            |Action: NONE, BROADCAST_SAFE, or OPEN_PAY — write this line FIRST, see rule below.
            |Situation: one short sentence on what this is.
            |1. First action step.
            |2. Next action step.
            |3. Last action step.
            |Avoid: one short line on what not to do, only if the context mentions one.
            |Always call 112 immediately for a life-threatening emergency.
            |
            |When to write each Action value: BROADCAST_SAFE only if the question says the danger
            |already passed (e.g. "the bleeding stopped"). OPEN_PAY only if the question is about
            |paying for something. Otherwise NONE — correct for almost every question. Do not
            |write the words "Action rule" anywhere in your answer — "Action:" is only the format
            |line at the very top.
            |
            |Hard rules: base every fact ONLY on the Context passages below. The Conversation so
            |far, if present, is ONLY for resolving a pronoun or follow-up like "for a child" —
            |never a source of facts; if the new question is a different situation, ignore the
            |prior answer entirely. AT MOST 3 numbered steps, never more. Never invent a medicine,
            |dose, or fact not in the context. If the context does not answer the question, say so
            |in one line instead of guessing.
            |
            |$historyBlock|Context passages (the only source of facts for the new question):
            |$context
            |
            |New question: $query
            |
            |Answer:
        """.trimMargin()
    }
}
