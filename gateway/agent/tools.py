"""
Strands @tool definitions for the reference agent. Only one real tool today
— search_kb — because that's the only retrieval capability that actually
exists (see knowledge.py). Mirrors the on-device AssistantEngine's use of
KnowledgeRetriever.search(): retrieval is a plain function call, not an
agent decision, in the Kotlin app; exposing it as a Strands @tool here is
what makes this a genuine agent (the model decides when to call it) rather
than a hardcoded RAG pipeline, per docs/adr/0016.
"""
from __future__ import annotations

from pathlib import Path

from strands import tool

from .knowledge import KnowledgeChunk, load_knowledge_base, search

KB_DIR = Path(__file__).resolve().parent.parent.parent / "docs" / "knowledge-base"
_KNOWLEDGE_BASE: list[KnowledgeChunk] = load_knowledge_base(KB_DIR)


@tool
def search_kb(query: str) -> str:
    """
    Search the offline crisis-response knowledge base for passages relevant to a query.

    Args:
        query: The question or situation to find guidance for, e.g. "snake bite" or "flood evacuation".

    Returns:
        Up to 3 matched passages, each with its source document and section, or a
        message saying nothing matched.
    """
    matches = search(query, _KNOWLEDGE_BASE, top_k=3)
    if not matches:
        return "No matching passage found in the knowledge base."

    parts = []
    for i, scored in enumerate(matches, start=1):
        chunk = scored.chunk
        parts.append(f"[{i}] {chunk.text}\n    Source: {chunk.source}, section {chunk.section}")
    return "\n\n".join(parts)
