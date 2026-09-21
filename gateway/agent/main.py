"""
Reference implementation of Sankat Setu's on-device 3-stage assistant
pipeline (see app/src/main/java/com/sankatsetu/app/assistant/AssistantEngine.kt
and docs/adr/0016-on-device-agent-architecture.md), built with AWS's real
Strands Agents SDK instead of hand-rolled Kotlin — this satisfies the
hackathon's "use a real AWS open-source tool" requirement honestly: it is
NOT claimed to run on the phone (Strands is Python-only, the phone runs the
Kotlin port), but it is a genuine, runnable Strands agent, using the same
knowledge base, the same hybrid BM25+TF-IDF retrieval (see knowledge.py),
and the same prompt structure/Action-line contract as the on-device engine,
so the two are directly comparable.

Model: Ollama running `qwen2.5:0.5b-instruct` — the closest publicly
available Ollama tag to the exact on-device model
(Qwen2.5-0.5B-Instruct, int8, via MediaPipe — see docs/adr/0011). Fully
local and offline: no AWS account, no billing, matching the Build It
track's "no AWS account, no card, no bill" rule.

Usage:
    pip install -r gateway/requirements.txt
    ollama pull qwen2.5:0.5b-instruct
    python -m gateway.agent.main "how do I treat a snake bite"
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from enum import Enum

from pathlib import Path

from strands import Agent
from strands.models.ollama import OllamaModel

from .knowledge import KnowledgeChunk, load_knowledge_base, search
from .tools import search_kb

OLLAMA_HOST = "http://localhost:11434"
MODEL_ID = "qwen2.5:0.5b-instruct"
KB_DIR = Path(__file__).resolve().parent.parent.parent / "docs" / "knowledge-base"
TOP_K = 3

ACTION_LINE = re.compile(r"(?m)^Action:\s*(NONE|BROADCAST_SAFE|OPEN_PAY)\s*$")
STRAY_ACTION_RULE_LINE = re.compile(r"(?m)^Action rule:.*$\n?")

MAX_HISTORY_TURNS = 2  # mirrors AssistantEngine.kt's MAX_HISTORY_TURNS


class SuggestedAction(str, Enum):
    NONE = "NONE"
    BROADCAST_SAFE = "BROADCAST_SAFE"
    OPEN_PAY = "OPEN_PAY"


@dataclass
class Exchange:
    question: str
    answer: str


@dataclass
class AssistantAnswer:
    text: str
    was_generated: bool
    suggested_action: SuggestedAction = SuggestedAction.NONE


FALLBACK_NO_MATCH = "I don't have specific guidance for this — call 112 immediately."


def build_prompt(query: str, chunks: list[KnowledgeChunk], history: list[Exchange]) -> str:
    """
    Direct Python port of AssistantEngine.kt's buildPrompt() — see that
    file's doc for why retrieval happens as a guaranteed step BEFORE the
    single generation call, not as something the model decides whether to
    do. A real run against qwen2.5:0.5b-instruct confirmed why this
    matters: the earlier version of this file gave the model a `search_kb`
    *tool* and told it to always call that tool first — the model
    frequently didn't, and instead emitted the prompt's own template
    placeholders verbatim ("1. First action step.") with a wrong Action
    value, because a 0.5B model can't reliably be trusted to make that
    judgment call. The on-device Kotlin engine never gives the model that
    choice either (see docs/adr/0016) — this port now matches that
    exactly. `search_kb` (tools.py) still exists as a real Strands `@tool`
    for optional *secondary* lookups mid-conversation (see docs/PRD.md
    §F2.4), just not as the mechanism the primary answer's grounding
    depends on.
    """
    context = "\n\n".join(
        f"[{i + 1}] {chunk.text}\n    Source: {chunk.source}, section {chunk.section}"
        for i, chunk in enumerate(chunks)
    )
    history_block = ""
    if history:
        recent = history[-MAX_HISTORY_TURNS:]
        lines = "\n".join(f"User: {h.question}\nGuide: {h.answer}" for h in recent)
        history_block = (
            "Conversation so far (for resolving pronouns/follow-ups ONLY, not a source of facts):\n"
            f"{lines}\n\n"
        )

    return f"""Instruction: You are a calm, direct crisis-response guide for rural India, working
completely offline. Answer the new question as a short practical guide, in your own
words — do not copy text verbatim. Format your answer EXACTLY like this, and nothing
more:
Action: NONE, BROADCAST_SAFE, or OPEN_PAY — write this line FIRST, see rule below.
Situation: one short sentence on what this is.
1. First action step.
2. Next action step.
3. Last action step.
Avoid: one short line on what not to do, only if the context mentions one.
Always call 112 immediately for a life-threatening emergency.

When to write each Action value: BROADCAST_SAFE only if the question says the danger
already passed (e.g. "the bleeding stopped"). OPEN_PAY only if the question is about
paying for something. Otherwise NONE — correct for almost every question. Do not
write the words "Action rule" anywhere in your answer — "Action:" is only the format
line at the very top.

Hard rules: base every fact ONLY on the Context passages below. The Conversation so
far, if present, is ONLY for resolving a pronoun or follow-up like "for a child" —
never a source of facts; if the new question is a different situation, ignore the
prior answer entirely. AT MOST 3 numbered steps, never more. Never invent a medicine,
dose, or fact not in the context. If the context does not answer the question, say so
in one line instead of guessing.

{history_block}Context passages (the only source of facts for the new question):
{context}

New question: {query}

Answer:"""

DRAFT_PROMPT = """Instruction: Write ONE short message (max 2 sentences, plain language) this
person could send to a family member over a text or chat app, summarizing
their situation and what they're doing about it. Base it only on the
question and guidance below — do not invent facts, names, or locations not
present in them. Output only the message itself, nothing else.

Question: {question}
Guidance given: {answer}

Message:"""


def _normalize_literal_newlines(text: str) -> str:
    return text.replace("\\n", "\n")


def load_kb() -> list[KnowledgeChunk]:
    return load_knowledge_base(KB_DIR)


def build_answer_agent() -> Agent:
    # search_kb stays available as a real Strands @tool for optional
    # secondary lookups (PRD §F2.4) — but see build_prompt's doc for why
    # the primary answer's grounding does NOT depend on the model choosing
    # to call it.
    model = OllamaModel(host=OLLAMA_HOST, model_id=MODEL_ID)
    # callback_handler=None: Strands' default handler streams tokens to
    # stdout as they generate, which duplicates this CLI's own print of the
    # final answer. Silencing it here is purely cosmetic for this
    # command-line reference tool.
    return Agent(model=model, tools=[search_kb], callback_handler=None)


def build_plain_model() -> OllamaModel:
    return OllamaModel(host=OLLAMA_HOST, model_id=MODEL_ID)


def answer(agent: Agent, query: str, knowledge_base: list[KnowledgeChunk], history: list[Exchange] | None = None) -> AssistantAnswer:
    """Stage 1+2: retrieval (guaranteed, deterministic) + triage/action-suggestion + grounded structured answer, one generation — mirrors AssistantEngine.answer()."""
    history = history or []
    matches = search(query, knowledge_base, top_k=TOP_K)
    if not matches:
        return AssistantAnswer(text=FALLBACK_NO_MATCH, was_generated=False)

    chunks = [m.chunk for m in matches]
    prompt = build_prompt(query, chunks, history)
    result = agent(prompt)
    raw = str(result).strip()
    raw = _normalize_literal_newlines(raw)

    if not raw:
        return AssistantAnswer(text=FALLBACK_NO_MATCH, was_generated=False)

    action_match = ACTION_LINE.search(raw)
    try:
        action = SuggestedAction(action_match.group(1)) if action_match else SuggestedAction.NONE
    except ValueError:
        action = SuggestedAction.NONE

    text = ACTION_LINE.sub("", raw)
    text = STRAY_ACTION_RULE_LINE.sub("", text)
    text = text.strip()

    return AssistantAnswer(text=text, was_generated=True, suggested_action=action)


def draft_shareable_message(question: str, answer_text: str) -> str | None:
    """Stage 3: a genuinely separate, on-request second call — mirrors AssistantEngine.draftShareableMessage()."""
    model = build_plain_model()
    agent = Agent(model=model, callback_handler=None)
    prompt = DRAFT_PROMPT.format(question=question, answer=answer_text)
    result = str(agent(prompt)).strip()
    return result or None


def _main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python -m gateway.agent.main \"<question>\" [--draft]", file=sys.stderr)
        sys.exit(1)

    query = sys.argv[1]
    want_draft = "--draft" in sys.argv[2:]

    knowledge_base = load_kb()
    agent = build_answer_agent()
    result = answer(agent, query, knowledge_base)

    print(f"[wasGenerated={result.was_generated} action={result.suggested_action.value}]")
    print(result.text)

    if want_draft and result.was_generated:
        draft = draft_shareable_message(query, result.text)
        print("\n--- Draft message to share ---")
        print(draft or "(draft generation returned empty)")


if __name__ == "__main__":
    _main()
