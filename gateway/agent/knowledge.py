"""
Loads and retrieves from the same plain-text knowledge base the on-device
Kotlin assistant uses (docs/knowledge-base/*.txt) — this is a line-by-line
Python port of KnowledgeDocumentParser.kt and KnowledgeRetriever.kt, not a
reimplementation from scratch, so the reference agent's retrieval behaves
identically to the phone's for the same query. See
docs/adr/0016-on-device-agent-architecture.md: this file exists specifically
so the Strands reference agent isn't just "an LLM with no grounding" — it
demonstrates the exact same hybrid BM25+TF-IDF retrieval, just running on a
laptop instead of a phone.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass
from pathlib import Path

TITLE_LINE = re.compile(r"^#\s+(.+)$")
SOURCE_LINE = re.compile(r"^Source:\s*(.+)$", re.IGNORECASE)
SECTION_HEADING = re.compile(r"^##\s+(.+)$")
TOKEN = re.compile(r"[a-zA-Z0-9]+")

STOP_WORDS = {
    "a", "an", "the", "is", "are", "was", "were", "be", "been", "am",
    "i", "you", "he", "she", "it", "we", "they", "my", "your", "his", "her", "its", "our", "their",
    "and", "or", "but", "if", "then", "so", "to", "of", "in", "on", "at", "for", "with", "by",
    "do", "does", "did", "can", "could", "will", "would", "should", "what", "how", "when", "where", "why",
    "this", "that", "these", "those", "have", "has", "had", "not", "no",
}

BM25_K1 = 1.5
BM25_B = 0.75
BM25_WEIGHT = 0.7
TFIDF_WEIGHT = 0.3
HEADING_BOOST_REPEATS = 3


@dataclass(frozen=True)
class KnowledgeChunk:
    chunk_id: str
    source: str
    section: str
    text: str


@dataclass(frozen=True)
class ScoredChunk:
    chunk: KnowledgeChunk
    score: float


def parse_document(fallback_title: str, raw_text: str) -> list[KnowledgeChunk]:
    """Port of KnowledgeDocumentParser.kt's parse()."""
    lines = raw_text.splitlines()
    title = fallback_title
    body_start = 0

    for i, line in enumerate(lines):
        title_match = TITLE_LINE.match(line) if i == 0 else None
        if title_match:
            title = title_match.group(1).strip()
            body_start = i + 1
            continue
        if i <= body_start:
            source_match = SOURCE_LINE.match(line)
            if source_match:
                body_start = i + 1
        if SECTION_HEADING.match(line):
            break

    body = lines[body_start:]
    chunks: list[KnowledgeChunk] = []
    current_section: str | None = None
    current_text: list[str] = []
    index = 0

    def flush():
        nonlocal index, current_text
        if current_section is None:
            return
        text = "\n".join(current_text).strip()
        if text:
            chunks.append(KnowledgeChunk(f"{fallback_title}#{index}", title, current_section, text))
            index += 1
        current_text = []

    for line in body:
        heading = SECTION_HEADING.match(line)
        if heading:
            flush()
            current_section = heading.group(1).strip()
        elif current_section is not None:
            current_text.append(line)
    flush()

    return chunks


def load_knowledge_base(directory: Path) -> list[KnowledgeChunk]:
    chunks: list[KnowledgeChunk] = []
    for path in sorted(directory.glob("*.txt")):
        raw = path.read_text(encoding="utf-8")
        chunks.extend(parse_document(path.stem, raw))
    return chunks


def tokenize(text: str) -> list[str]:
    return [t for t in TOKEN.findall(text.lower()) if t not in STOP_WORDS and len(t) > 1]


def search(query: str, chunks: list[KnowledgeChunk], top_k: int = 3) -> list[ScoredChunk]:
    """Port of KnowledgeRetriever.kt's search() — hybrid BM25 + TF-IDF cosine, no caching, rebuilt per query."""
    query_terms = list(dict.fromkeys(tokenize(query)))  # distinct, order-preserving
    if not query_terms or not chunks:
        return []

    doc_tokens = [tokenize((chunk.section + " ") * HEADING_BOOST_REPEATS + chunk.text) for chunk in chunks]
    doc_lengths = [len(t) for t in doc_tokens]
    avg_doc_length = (sum(doc_lengths) / len(doc_lengths)) if doc_lengths else 1.0
    avg_doc_length = avg_doc_length or 1.0
    n = len(chunks)

    idf = {}
    for term in query_terms:
        df = sum(1 for toks in doc_tokens if term in toks)
        idf[term] = math.log(((n - df + 0.5) / (df + 0.5)) + 1.0)
    query_vec_norm = math.sqrt(sum(idf[t] ** 2 for t in query_terms))

    scored: list[ScoredChunk] = []
    for i, chunk in enumerate(chunks):
        term_counts: dict[str, int] = {}
        for t in doc_tokens[i]:
            term_counts[t] = term_counts.get(t, 0) + 1
        doc_length = doc_lengths[i]

        bm25 = 0.0
        tfidf_dot = 0.0
        doc_vec_norm_sq = 0.0
        for term in query_terms:
            tf = term_counts.get(term, 0)
            term_idf = idf.get(term, 0.0)
            if tf > 0:
                denom = tf + BM25_K1 * (1 - BM25_B + BM25_B * doc_length / avg_doc_length)
                bm25 += term_idf * (tf * (BM25_K1 + 1)) / denom

                tf_weight = (1.0 + math.log(tf)) * term_idf
                tfidf_dot += term_idf * tf_weight
                doc_vec_norm_sq += tf_weight * tf_weight

        doc_vec_norm = math.sqrt(doc_vec_norm_sq)
        tfidf_cosine = (tfidf_dot / (doc_vec_norm * query_vec_norm)) if doc_vec_norm > 0.0 and query_vec_norm > 0.0 else 0.0

        score = BM25_WEIGHT * bm25 + TFIDF_WEIGHT * tfidf_cosine
        if score > 0.0:
            scored.append(ScoredChunk(chunk, score))

    scored.sort(key=lambda s: s.score, reverse=True)
    return scored[:top_k]
