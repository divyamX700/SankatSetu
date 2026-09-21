package com.sankatsetu.app.assistant

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Retrieval over the on-device knowledge base using a real hybrid **BM25 +
 * TF-IDF** ranker — no curated keyword lists, no neural embeddings, just
 * classic lexical IR run entirely on-device over plain-text passages. This
 * replaces Day 2's keyword/term-overlap scorer (see docs/adr/0011).
 *
 * - **BM25** (Okapi BM25, k1=1.5, b=0.75) is the primary ranking signal: it
 *   rewards a query term appearing in a passage, weighted by how rare that
 *   term is across the whole corpus (IDF), with diminishing returns for
 *   repeated occurrences and a length-normalization term so long passages
 *   don't win purely by being long.
 * - **TF-IDF cosine similarity** is blended in as a secondary signal. It's
 *   restricted to the query's own vocabulary (a standard, tractable
 *   simplification for small on-device corpora — the full corpus vocabulary
 *   cosine would cost more to compute for no real ranking benefit at this
 *   corpus size) and rewards passages whose term-weight *profile* looks like
 *   the query's, which BM25's saturation curve can under-weight for very
 *   short passages.
 *
 * The index (document frequencies, average passage length) is rebuilt per
 * query from the [chunks] list passed in. That's deliberately simple rather
 * than cached: this runs over a few hundred short passages on a phone,
 * which is microseconds of work, and it keeps this object stateless and
 * trivially testable — no invalidation logic needed if the knowledge base
 * changes.
 */
object KnowledgeRetriever {
    private const val BM25_K1 = 1.5
    private const val BM25_B = 0.75
    private const val BM25_WEIGHT = 0.7
    private const val TFIDF_WEIGHT = 0.3
    private const val HEADING_BOOST_REPEATS = 3

    private val STOP_WORDS = setOf(
        "a", "an", "the", "is", "are", "was", "were", "be", "been", "am",
        "i", "you", "he", "she", "it", "we", "they", "my", "your", "his", "her", "its", "our", "their",
        "and", "or", "but", "if", "then", "so", "to", "of", "in", "on", "at", "for", "with", "by",
        "do", "does", "did", "can", "could", "will", "would", "should", "what", "how", "when", "where", "why",
        "this", "that", "these", "those", "have", "has", "had", "not", "no"
    )

    data class ScoredChunk(val chunk: KnowledgeChunk, val score: Double)

    /** Tokenizes [text] into lowercase alphanumeric words, dropping stop words. */
    fun tokenize(text: String): List<String> =
        Regex("[a-zA-Z0-9]+")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it !in STOP_WORDS && it.length > 1 }
            .toList()

    /** Returns the top [topK] chunks with score > 0, highest first. Empty if nothing matches. */
    fun search(query: String, chunks: List<KnowledgeChunk>, topK: Int = 3): List<ScoredChunk> {
        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty() || chunks.isEmpty()) return emptyList()

        // Section headings are scored too, weighted up via repetition — a
        // real-device bug found the opposite (heading text ignored
        // entirely): a "how do I treat a snake bite" query ranked the
        // "Dog Bites and Rabies Risk" section above "Immediate Steps After
        // a Snake Bite" itself, because both sections' *body* prose shares
        // generic words like "bite" and "wound," while the word "snake" sat
        // unscored in the section heading the whole time. Repeating the
        // heading is a standard IR technique (title boosting) and needs no
        // separate weighting mechanism — it just raises those terms' body
        // term frequency naturally.
        val docTokens = chunks.map { tokenize("${it.section} ".repeat(HEADING_BOOST_REPEATS) + it.text) }
        val docLengths = docTokens.map { it.size }
        val avgDocLength = docLengths.average().takeIf { it > 0.0 } ?: 1.0
        val n = chunks.size

        // Inverse document frequency per query term, BM25's smoothed variant
        // (always positive even when a term appears in every passage, unlike
        // classic IDF which can go negative in that case).
        val idf = queryTerms.associateWith { term ->
            val df = docTokens.count { term in it }
            ln(((n - df + 0.5) / (df + 0.5)) + 1.0)
        }
        val queryVecNorm = sqrt(queryTerms.sumOf { t -> (idf[t] ?: 0.0).let { it * it } })

        val scored = chunks.indices.map { i ->
            val termCounts = docTokens[i].groupingBy { it }.eachCount()
            val docLength = docLengths[i]

            var bm25 = 0.0
            var tfidfDot = 0.0
            var docVecNormSq = 0.0
            for (term in queryTerms) {
                val tf = termCounts[term] ?: 0
                val termIdf = idf[term] ?: 0.0
                if (tf > 0) {
                    val denom = tf + BM25_K1 * (1 - BM25_B + BM25_B * docLength / avgDocLength)
                    bm25 += termIdf * (tf * (BM25_K1 + 1)) / denom

                    val tfWeight = (1.0 + ln(tf.toDouble())) * termIdf
                    tfidfDot += termIdf * tfWeight
                    docVecNormSq += tfWeight * tfWeight
                }
            }
            val docVecNorm = sqrt(docVecNormSq)
            val tfidfCosine = if (docVecNorm > 0.0 && queryVecNorm > 0.0) tfidfDot / (docVecNorm * queryVecNorm) else 0.0

            ScoredChunk(chunks[i], BM25_WEIGHT * bm25 + TFIDF_WEIGHT * tfidfCosine)
        }

        return scored.filter { it.score > 0.0 }.sortedByDescending { it.score }.take(topK)
    }
}
