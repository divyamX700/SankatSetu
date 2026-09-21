package com.sankatsetu.app.assistant

/**
 * Parses one plain-text knowledge-base document into [KnowledgeChunk]s. Pure
 * Kotlin, no Android dependency, so this is unit tested directly — see
 * [KnowledgeBaseLoader] for the asset-reading wrapper around it.
 *
 * Expected format:
 * ```
 * # Document Title
 * Source: <citation-style description>
 *
 * ## Section Heading
 * Body text…
 *
 * ## Another Section
 * Body text…
 * ```
 * `# Title` and `Source:` are optional — if missing, [fallbackTitle] (the
 * file name) is used as the source instead, so a malformed or minimal
 * document still degrades to something usable rather than being dropped.
 */
object KnowledgeDocumentParser {
    private val TITLE_LINE = Regex("^#\\s+(.+)$")
    private val SOURCE_LINE = Regex("^Source:\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val SECTION_HEADING = Regex("^##\\s+(.+)$")

    fun parse(fallbackTitle: String, rawText: String): List<KnowledgeChunk> {
        val lines = rawText.lines()
        var title = fallbackTitle
        var bodyStart = 0

        for ((i, line) in lines.withIndex()) {
            val titleMatch = TITLE_LINE.find(line)
            if (titleMatch != null && i == 0) {
                title = titleMatch.groupValues[1].trim()
                bodyStart = i + 1
                continue
            }
            if (i <= bodyStart) {
                val sourceMatch = SOURCE_LINE.find(line)
                if (sourceMatch != null) {
                    bodyStart = i + 1
                }
            }
            if (SECTION_HEADING.matches(line)) break // first section heading — stop scanning the preamble
        }

        val body = lines.drop(bodyStart)
        val chunks = mutableListOf<KnowledgeChunk>()
        var currentSection: String? = null
        var currentText = StringBuilder()
        var index = 0

        fun flush() {
            val section = currentSection ?: return
            val text = currentText.toString().trim()
            if (text.isNotEmpty()) {
                chunks.add(KnowledgeChunk(id = "$fallbackTitle#$index", source = title, section = section, text = text))
                index++
            }
            currentText = StringBuilder()
        }

        for (line in body) {
            val heading = SECTION_HEADING.find(line)
            if (heading != null) {
                flush()
                currentSection = heading.groupValues[1].trim()
            } else if (currentSection != null) {
                currentText.appendLine(line)
            }
        }
        flush()

        return chunks
    }
}
