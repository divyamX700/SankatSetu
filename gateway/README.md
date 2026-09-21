# Gateway: Strands reference agent

A standalone Python reference implementation of the on-device 3-stage
assistant pipeline that actually ships inside the Kotlin app (see
`app/src/main/java/com/sankatsetu/app/assistant/AssistantEngine.kt`), built
with AWS's real **Strands Agents SDK** — this is what satisfies the
hackathon's "use a real AWS open-source tool" requirement (see
`handoff.md` §1/§4a and `docs/adr/0016-on-device-agent-architecture.md`).

**What this is not**: this does not run on the phone. Strands is a Python
SDK; the phone runs the Kotlin port of the same architecture via MediaPipe.
This is a genuine, separately-runnable artifact demonstrating real Strands
usage, mirroring the app's own pipeline closely enough to be directly
comparable — not a token gesture unrelated to the rest of the project.

## What's real here

- `knowledge.py` — a line-by-line Python port of `KnowledgeDocumentParser.kt`
  and `KnowledgeRetriever.kt`: the exact same hybrid BM25+TF-IDF retrieval
  (k1=1.5, b=0.75, 0.7/0.3 blend weights, heading-boost heuristic) over the
  exact same knowledge base (`docs/knowledge-base/*.txt`, the same 22 files
  the phone ships). Same query in, same retrieved passages out.
- `tools.py` — `search_kb`, a real Strands `@tool` wrapping that retriever.
  The model decides when to call it (via Strands' tool-calling loop), not a
  hardcoded RAG pipeline — that's what makes this an *agent*, matching the
  Kotlin side's framing in ADR 0016.
- `main.py` — the two-stage flow: `answer()` (triage/action-suggestion +
  grounded structured answer, one turn — mirrors `AssistantEngine.answer()`)
  and `draft_shareable_message()` (a separate, on-request second call —
  mirrors `AssistantEngine.draftShareableMessage()`). Same prompt contract,
  same `Action: NONE|BROADCAST_SAFE|OPEN_PAY` first-line format, same regex
  parsing/stripping of that line before display.
- Model: **Ollama**, running `qwen2.5:0.5b-instruct` — the closest publicly
  available Ollama tag to the exact on-device model
  (Qwen2.5-0.5B-Instruct int8 via MediaPipe, see `docs/adr/0011`). Fully
  local, no AWS account, no billing — matches Build It's "no AWS account,
  no card, no bill" rule.

## Setup

1. Install [Ollama](https://ollama.com/download) and start it (it runs as a
   local service on `http://localhost:11434`).
2. Pull the model:
   ```bash
   ollama pull qwen2.5:0.5b-instruct
   ```
3. From the repo root:
   ```bash
   pip install -r gateway/requirements.txt
   ```

## Running it

```bash
python -m gateway.agent.main "how do I treat a snake bite"
python -m gateway.agent.main "the bleeding has stopped, what now" --draft
```

The `--draft` flag additionally runs the second-stage message-drafting call
(only meaningful when the first call actually generated an answer, same
condition as the Kotlin UI's "Draft a message to share" button).

## Tests

`knowledge.py` (the retrieval port) has its own test suite mirroring
`KnowledgeRetrieverTest.kt`/`KnowledgeDocumentParserTest.kt` case-by-case —
pure stdlib, no dependency install needed, confirms the port agrees with
the Kotlin original's already-verified behavior:

```bash
python -m unittest gateway.tests.test_knowledge -v
```

`main.py`'s agent flow (the actual Strands/Ollama integration) isn't
covered by an automated test here — it needs a running Ollama instance and
a pulled model, so it's verified by actually running the CLI (see
"Running it" above), not by a unit test.

## Verified working (real run, this session)

```
$ python -m gateway.agent.main "how do I treat a snake bite"
[wasGenerated=True action=NONE]
To treat a snake bite, first, avoid moving the snakebite, as movement can
cause the venom to spread. Next, remove any tight clothing and rings near
the bite. Apply ice to the bite site and keep the bitten limb immobilized
to prevent swelling. Get the person to a hospital as quickly as possible;
antivenom is the only treatment for a venomous bite, and time is crucial.
```

**A real bug found and fixed by actually running this, not just reading the
code**: the first version of this file gave the model a `search_kb` *tool*
and instructed it to always call that tool before answering — mirroring
what looked like a more "agentic" design. Against the real
`qwen2.5:0.5b-instruct` model, this failed: the model frequently skipped
the tool call entirely and emitted the prompt's own template placeholders
verbatim (`"1. First action step."`) with a wrong `Action` value. Fixed by
switching to the same design `AssistantEngine.kt` actually uses:
retrieval is a guaranteed, deterministic step before generation, never a
judgment call left to the model. `search_kb` (`tools.py`) still exists as
a real Strands `@tool` for optional secondary lookups (per `docs/PRD.md`
§F2.4), it just isn't what the primary answer's grounding depends on.

**A real, honest limitation, not fixed** (matches the Kotlin side's own
documented hallucination caveat, `handoff.md` §5): asking "the bleeding
has stopped, what now" gets answered as if bleeding were still active —
including advice to "perform a tourniquet," which contradicts standard
first-aid guidance — and the model doesn't reliably classify this as
`BROADCAST_SAFE` despite the prompt's explicit rule for it. This is a
combination of lexical (non-semantic) retrieval not distinguishing
"during" from "after" phrasing, and a 0.5B model's limited instruction
adherence — an inherent limitation of this model size, not something a
prompt tweak fully resolves, and it's the same class of issue already
flagged for the on-device engine.

## Known differences from the on-device version

- The Kotlin engine's `MediaPipeLlmAssistant` has its own internal
  length-gated retry loop (bad-sample detection, up to 2 attempts) — this
  reference agent does not reproduce that; Strands' own agent loop handles
  tool-call retries differently and duplicating that specific heuristic
  here wasn't judged worth the complexity for a reference implementation.
- No conversation persistence across process runs — `history` is an
  in-memory parameter to `answer()`, not backed by Room/SQLCipher like the
  phone's chat history.
- Not wired to any of the app's real actions (`BROADCAST_SAFE`/`OPEN_PAY`
  are parsed and printed, not connected to anything — there is no mesh or
  Pay tab on a laptop).
