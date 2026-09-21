# Sankat Setu — crisis bridge, no internet needed

Built for **First Commit** (Bharat Builds Tour), competing in the **Build
It** track.

An Android app that keeps a village or city block functioning during a
crisis when the internet and cell networks are down. Phones talk to each
other over Bluetooth mesh, relaying messages through nearby phones so a
message can reach someone outside direct radio range — no towers, no
central server, no coordinator laptop, nothing. On top of that: real UPI
payments over USSD/IVR or QR scan when there's still a bank rail
reachable, a signed offline IOU voucher for when there isn't, an SOS
broadcast that floods your location and emergency category to every
phone in range, an offline map you download in advance and use with zero
signal, and an on-device LLM that answers first-aid and survival
questions from a bundled knowledge base grounded in real WHO/ICRC/NDMA
content, with photos and diagrams attached where relevant.

## What's actually built

- **Mesh chat** — BLE transport, binary wire protocol, TTL/dedup/jitter/
  fanout routing, Noise `XX`-encrypted 1:1 chat, packet fragmentation, a
  sender outbox, delivery ticks, a stale-handshake retry so a peer
  doesn't get stuck stuck "connecting" forever. Every packet that hits
  the router passes through a Cedar authorization check before it's
  allowed to relay or flood.
- **On-device assistant** — a real local LLM (Qwen2.5-0.5B-Instruct, via
  MediaPipe) answering from a hybrid BM25 + TF-IDF retriever over a
  first-aid/disaster-response knowledge base, with real photos and
  diagrams attached to specific answers (CPR compressions, tourniquet
  steps, splinting) instead of just text. Falls back to the retrieved
  passage verbatim if no model is loaded — never invents medical facts.
- **Payments** — USSD (`*99#`) and UPI 123Pay dial cards that open the
  system dialer pre-filled (Android won't let an app auto-dial, so you
  tap the call button yourself), QR scan-to-pay that reads a UPI QR code
  and dials the same rail, and a mesh-signed IOU voucher for when even
  that's unreachable — an explicit promise-to-pay, not a real money
  transfer, settled manually later.
- **SOS broadcast** — a one-handed, category-only emergency report,
  flooded unencrypted to every phone in range (not just people you've
  already connected with), carrying your GPS location when a fix is
  available quickly. Shows up as a pin on the map, with the sender's
  name.
- **"I'm Safe"** — the calmer counterpart to SOS, a one-tap encrypted
  broadcast to everyone you're already connected to.
- **Offline maps** — download roughly a 2km radius around you once,
  while online, then pan and zoom it with zero connectivity afterward.
  The same map shows nearby SOS reports and directly-connected peers as
  pins.

## AWS open-source stack used

- **Amazon Corretto** — the JDK this whole build compiles and runs on.
- **Cedar** — real, on-device policy authorization. Every message that
  hits the mesh router (chat, SOS, IOU, announce) gets a permit/forbid
  decision from a native Cedar engine running on the phone, enforcing
  per-message-kind rate caps so a flooding or malicious peer can't spam
  the network. See `app/src/main/assets/cedar/policies.cedar`.
- **Strands Agents SDK** — a standalone reference agent (`gateway/`)
  that mirrors the on-device assistant's retrieval-and-answer pipeline
  using a local Ollama model instead of a cloud LLM, kept fully offline
  to match the app's own no-internet constraint. This is a laptop-side
  Python script for demonstrating the SDK, not something the phone app
  calls — the app never has network access to reach it or anything
  else.

## Running it

```bash
git clone https://github.com/divyamX700/SankatSetu.git
cd SankatSetu
# Open in Android Studio, let Gradle sync.
# Or from the command line, once local.properties points at your SDK:
./gradlew assembleDebug
```

Requires two Android 10+ (API 29+) devices with Bluetooth LE for the
mesh demo — an emulator has no real Bluetooth radio. No AWS account, no
server, no internet connection needed for the mesh, payments, SOS, or
assistant features. Offline maps is the one exception: it needs internet
once, in advance, to download an area, and needs a free MapTiler API
key — sign up at maptiler.com (no card needed), then add it to your own
`local.properties` as `maptiler.api.key=YOUR_KEY`.

The assistant tab works out of the box with no setup (retrieval over the
bundled knowledge base, extractive answers). For real on-device
LLM-generated answers, side-load the model file (not bundled in the
APK):

```bash
curl -L -o qwen2.5-0.5b-instruct-q8.task \
  "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
adb push qwen2.5-0.5b-instruct-q8.task \
  /sdcard/Android/data/com.sankatsetu.app/files/models/qwen2.5-0.5b-instruct-q8.task
```

To try the Strands reference agent separately:

```bash
cd gateway
pip install -r requirements.txt
python -m agent.main
```

## Repo layout

```
app/src/main/java/com/sankatsetu/app/
  mesh/
    protocol/     binary wire format, TLV packets, fragmentation
    crypto/       identity keys (ECDSA + Curve25519), Noise XX sessions
    router/       TTL/dedup/jitter/fanout/fragment/outbox dispatch
    transport/    BLE advertising/scanning/GATT, foreground service
    authz/        Cedar-based flood-control gate
  assistant/      offline knowledge retrieval + MediaPipe LLM wrapper
  payments/       USSD dialer, QR scan-to-pay, mesh IOU
  maps/           offline map download and storage
  data/           Room entities/DAOs, SQLCipher wiring
  di/             hand-rolled composition root (AppContainer, no DI framework)
  ui/             Compose screens + ViewModels
app/src/main/assets/
  kb/             first-aid/disaster knowledge base (text + images)
  cedar/          Cedar policy set + schema for the mesh flood-control gate
gateway/          Strands Agents SDK + Ollama reference agent
scripts/          setup scripts for Corretto/Android SDK/Cedar cross-compile/Strands+Ollama
```

## Attribution

This project builds on a few open-source projects — see
[`NOTICE.md`](NOTICE.md) for the full list and what was ported vs.
referenced vs. adapted.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
