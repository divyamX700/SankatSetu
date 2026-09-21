package com.sankatsetu.app.assistant

/**
 * Abstraction over whatever on-device generation backend is available, so
 * [AssistantEngine] doesn't need to know or care whether MediaPipe's model
 * file has actually been side-loaded onto this device (see
 * docs/adr/0005-model-assets-not-committed.md — it's gitignored, not
 * bundled, and side-loaded manually per README.md).
 */
interface LlmAssistant {
    /** True once a usable model is loaded (or confirmed loadable). Checked before every [generate] call. */
    val isAvailable: Boolean

    /** Returns the generated text, or null if generation failed for any reason (never throws). */
    suspend fun generate(prompt: String): String?
}

/** Used when no model file is present — makes the fallback explicit rather than implicit-by-null-checking everywhere. */
object UnavailableLlmAssistant : LlmAssistant {
    override val isAvailable: Boolean = false
    override suspend fun generate(prompt: String): String? = null
}
