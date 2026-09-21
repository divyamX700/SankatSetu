package com.sankatsetu.app.assistant

import android.content.Context
import org.json.JSONArray

/**
 * Loads every `.txt` file in the `assets/kb/docs` directory into
 * [KnowledgeChunk]s. This is the only Android-specific piece of the
 * retrieval feature — kept
 * deliberately thin so [KnowledgeRetriever]'s actual scoring logic stays
 * unit-testable without an asset manager or a device (see
 * [KnowledgeDocumentParser] for the pure-Kotlin parsing logic itself, which
 * *is* unit tested).
 *
 * Day 2 shipped one hand-authored JSON file with curated keywords per
 * chunk. Day 3 replaces that with plain-text documents (see
 * docs/knowledge-base/ for the source-of-truth copies, mirrored here) — see
 * docs/adr/0011 for why: BM25 doesn't need curated keywords, and plain text
 * is far easier to author, review, and extend than hand-picked keyword
 * lists per passage.
 *
 * Document format (see any file under docs/knowledge-base/ for real
 * examples):
 * ```
 * # Document Title
 * Source: <where this guidance is drawn from>
 *
 * ## Section Heading
 * Body text for this section, one or more paragraphs.
 *
 * ## Another Section
 * ...
 * ```
 * Each `##` section becomes one [KnowledgeChunk], sized for grounding a
 * single retrieved answer (a paragraph or two) rather than a whole document.
 *
 * After parsing, [load] joins every chunk against `assets/kb/images/manifest.json`
 * by (file name without `.txt`, section heading) — see [loadImageManifest].
 * This is the *entire* mechanism behind image-grounded answers: there is no
 * LLM step here, no image classifier, just an exact-match lookup on the same
 * key the retriever already scored. An image rides along with its chunk
 * automatically whenever that chunk is retrieved. See
 * docs/adr/0023-image-grounded-answers.md.
 */
object KnowledgeBaseLoader {
    private const val ASSET_DIR = "kb/docs"
    private const val IMAGE_MANIFEST_PATH = "kb/images/manifest.json"

    fun load(context: Context): List<KnowledgeChunk> {
        val images = loadImageManifest(context)
        val fileNames = context.assets.list(ASSET_DIR)?.filter { it.endsWith(".txt") } ?: emptyList()
        return fileNames.flatMap { fileName ->
            val text = context.assets.open("$ASSET_DIR/$fileName").bufferedReader().use { it.readText() }
            val docId = fileName.removeSuffix(".txt")
            KnowledgeDocumentParser.parse(docId, text).map { chunk ->
                val image = images[docId to chunk.section]
                if (image != null) chunk.copy(image = image) else chunk
            }
        }
    }

    /**
     * Parses the flat JSON array at [IMAGE_MANIFEST_PATH] into a lookup keyed
     * by (document id, exact section heading text). A missing or malformed
     * manifest degrades to an empty map — a knowledge base with no images
     * attached is still a fully working knowledge base, same tolerance
     * [KnowledgeDocumentParser] already applies to a malformed document.
     */
    private fun loadImageManifest(context: Context): Map<Pair<String, String>, KnowledgeImage> {
        val raw = runCatching {
            context.assets.open(IMAGE_MANIFEST_PATH).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyMap()

        val entries = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<Pair<String, String>, KnowledgeImage>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val doc = entry.optString("doc").takeIf { it.isNotBlank() } ?: continue
            val section = entry.optString("section").takeIf { it.isNotBlank() } ?: continue
            val assetPath = entry.optString("image").takeIf { it.isNotBlank() } ?: continue
            val caption = entry.optString("caption")
            val attribution = entry.optString("attribution")
            result[doc to section] = KnowledgeImage(assetPath, caption, attribution)
        }
        return result
    }
}
