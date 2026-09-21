package com.sankatsetu.app.assistant

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps MediaPipe's LLM Inference API around a side-loaded Gemma model file.
 * The model itself is never bundled in the APK — see
 * docs/adr/0005-model-assets-not-committed.md — so [isAvailable] genuinely
 * reflects whether this specific device has one at [modelPath] right now,
 * and every failure mode (missing file, OOM, unsupported device, corrupt
 * model) degrades to "unavailable" rather than crashing the Assistant tab.
 *
 * Model file expected at (see README.md's "Running it" for the adb push
 * command): `/sdcard/Android/data/com.sankatsetu.app/files/models/qwen2.5-0.5b-instruct-q8.task`
 * — Qwen2.5-0.5B-Instruct, int8, from `litert-community` on Hugging Face.
 * See docs/adr/0011-on-device-llm-model-choice.md for why this model and
 * not the originally-considered Qwen3-0.6B (short version: Qwen3-0.6B is
 * only published in the newer `.litertlm` container, which this API version
 * cannot load — see that ADR for the full comparison).
 */
class MediaPipeLlmAssistant(
    private val context: Context,
    private val modelPath: String
) : LlmAssistant {

    @Volatile
    private var inference: LlmInference? = null
    @Volatile
    private var initFailed = false

    override val isAvailable: Boolean
        get() = !initFailed && File(modelPath).exists()

    /**
     * Loads the model file and builds the [LlmInference] engine now, off
     * the calling thread, instead of waiting for the first real question.
     * Call once at app startup (fire-and-forget is fine — [generate] will
     * still call [createEngine] itself if this hasn't finished yet). Model
     * load is disk I/O + weight parsing for a 521MB file; doing it while
     * the person is still looking at the Chat tab, instead of after they've
     * already typed a question, is pure latency reduction with zero
     * quality impact — the engine and its sampling behavior are identical
     * either way.
     */
    suspend fun warmUp() {
        if (!isAvailable) return
        withContext(Dispatchers.Default) { createEngine() }
    }

    override suspend fun generate(prompt: String): String? {
        if (!isAvailable) return null
        return withContext(Dispatchers.Default) {
            // Retries with a different random seed cover *both* bad-sample
            // failure modes actually observed on real hardware (see
            // docs/adr/0011's update notes): a 0.5B model occasionally
            // samples straight into an early stop token (empty/very short
            // output), and on a different seed can instead ignore the "at
            // most 3 steps" instruction entirely and ramble into a long,
            // partly-hallucinated answer (one real capture: 6 numbered
            // items, ~1300 characters, including a nonsensical invented
            // step) — that's not just slower, it's wrong, so length-gating
            // the retry is a correctness fix, not just a latency one. A
            // sample inside the expected length band is trusted and used
            // immediately; if every attempt falls outside it, the shortest
            // candidate seen is used, since a long derailment compounds
            // more invented content than a merely-terse answer does.
            var bestResult: String? = null
            for (attempt in 0 until MAX_ATTEMPTS) {
                val result = runCatching { generateOnce(prompt, seed = BASE_SEED + attempt) }
                    .onFailure { e ->
                        Log.w(TAG, "LLM generation attempt $attempt failed", e)
                        initFailed = true
                    }
                    .getOrNull()
                    ?.trim()

                if (!result.isNullOrEmpty()) {
                    if (result.length in MIN_USEFUL_LENGTH..MAX_USEFUL_LENGTH) return@withContext result
                    if (bestResult == null || result.length < bestResult.length) bestResult = result
                }
                if (initFailed) break // real error, not just a bad sample — no point retrying
            }
            bestResult
        }
    }

    private fun generateOnce(prompt: String, seed: Int): String {
        val t0 = System.currentTimeMillis()
        val engine = inference ?: createEngine() ?: throw IllegalStateException("LlmInference failed to initialize")
        val t1 = System.currentTimeMillis()
        val sessionOptions = LlmInferenceSessionOptions.builder()
            // Qwen2.5-0.5B-Instruct at this temperature/topK reliably fills
            // out a full multi-step answer instead of sampling an early stop
            // token — tuned against this exact model+quantization on real
            // hardware, not a generic default. See docs/adr/0011.
            .setTopK(40)
            .setTemperature(0.6f)
            .setRandomSeed(seed)
            .build()
        LlmInferenceSession.createFromOptions(engine, sessionOptions).use { session ->
            val t2 = System.currentTimeMillis()
            session.addQueryChunk(prompt)
            val t3 = System.currentTimeMillis()
            val result = session.generateResponse()
            val t4 = System.currentTimeMillis()
            Log.i(
                TAG,
                "generateOnce timing: engineInit=${t1 - t0}ms sessionCreate=${t2 - t1}ms " +
                    "addQueryChunk(prefill)=${t3 - t2}ms generateResponse(decode)=${t4 - t3}ms " +
                    "promptChars=${prompt.length} resultChars=${result.length}"
            )
            return result
        }
    }

    @Synchronized
    private fun createEngine(): LlmInference? {
        inference?.let { return it }
        if (initFailed) return null
        return try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                // This .task bundle was converted with a 1280-token KV cache
                // (`ekv1280` in the filename — see docs/adr/0011); maxTokens
                // covers prompt + generated output combined. Real-device
                // timing found generateResponse() taking 44 SECONDS on one
                // query because the model ignored a soft "add a 4th or 5th
                // step only if needed" instruction and rambled to a 15-step,
                // partly-hallucinated answer (~1900 characters). The prompt
                // now hard-instructs at most 3 steps, and *that* instruction
                // is what actually keeps normal answers short (verified:
                // consistent ~300-character 3-step answers afterward) — this
                // ceiling only needs to be a backstop against the worst
                // case, not a tight budget. A first attempt at a tight
                // ceiling here (896) backfired: a real 2-turn-history prompt
                // measured ~870 tokens, leaving only ~25 tokens for the
                // answer and truncating it mid-sentence. 1200 stays safely
                // under the 1280 cache ceiling while giving a full-history
                // prompt real room to finish.
                .setMaxTokens(1200)
                .setMaxTopK(40)
                .build()
            LlmInference.createFromOptions(context, options).also { inference = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize MediaPipe LlmInference", e)
            initFailed = true
            null
        }
    }

    fun close() {
        inference?.close()
        inference = null
    }

    companion object {
        private const val TAG = "MediaPipeLlmAssistant"
        private const val MAX_ATTEMPTS = 2
        private const val BASE_SEED = 42
        /** Below this many characters, treat a generation as a bad sample rather than a real (if terse) answer. */
        private const val MIN_USEFUL_LENGTH = 25
        /** Above this many characters, treat a generation as a runaway/rambling sample — a compliant 3-step answer measured 280-500 chars on real hardware. */
        private const val MAX_USEFUL_LENGTH = 700

        fun defaultModelPath(context: Context): String =
            File(context.getExternalFilesDir("models"), "qwen2.5-0.5b-instruct-q8.task").absolutePath
    }
}
