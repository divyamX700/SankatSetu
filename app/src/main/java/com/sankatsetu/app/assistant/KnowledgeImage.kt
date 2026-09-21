package com.sankatsetu.app.assistant

/**
 * One image deterministically attached to a [KnowledgeChunk] — never chosen
 * or described by the LLM. [assetPath] is relative to `assets/` (e.g.
 * `kb/images/tourniquet_application.jpg`). The attachment itself happens in
 * [KnowledgeBaseLoader] by matching a manifest entry's (document, section)
 * pair against a parsed chunk — see `assets/kb/images/manifest.json`. Once
 * attached, the image travels with the chunk through retrieval automatically:
 * whenever [KnowledgeRetriever] surfaces this chunk, the image comes with it,
 * with no model call involved.
 */
data class KnowledgeImage(
    val assetPath: String,
    val caption: String,
    val attribution: String
)
