package com.sankatsetu.app.assistant

/**
 * One retrievable passage of the offline knowledge base. Deliberately plain
 * data — no Android/JSON dependency — so [KnowledgeRetriever] can be unit
 * tested without touching an asset file or a device.
 *
 * There is no curated `keywords` field (Day 2 had one): [KnowledgeRetriever]
 * is now a real BM25 + TF-IDF ranker over [text] itself, so passages don't
 * need hand-picked keyword lists to be findable — which also means new
 * knowledge-base documents (see docs/knowledge-base/) can be authored as
 * plain text with no extra bookkeeping. See docs/adr/0011.
 *
 * [image] is never set by this class or by [KnowledgeDocumentParser] — it
 * stays null until [KnowledgeBaseLoader] joins a parsed chunk against
 * `assets/kb/images/manifest.json` by (document, section). That keeps this
 * type, and the parser that produces it, free of any Android/image concern
 * while still letting an image ride along with retrieval deterministically.
 * See docs/adr/0023-image-grounded-answers.md.
 */
data class KnowledgeChunk(
    val id: String,
    val source: String,
    val section: String,
    val text: String,
    val image: KnowledgeImage? = null
)
