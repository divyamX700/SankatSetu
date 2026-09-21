# Setup scripts

Captures the exact commands needed to set up a new machine so you don't
have to re-derive them.

**Steps 1-2 are required on any new machine.** Step 3 (the Cedar
cross-compile) is **not required for normal development** — the resulting
`.so` files are already committed at `app/src/main/jniLibs/`. Only run
step 3 if you're deliberately rebuilding Cedar from scratch (a version
bump, or if those files go missing from the working tree). Step 4 is only
needed if you're working on the `gateway/` Strands reference agent.

1. `setup-corretto.sh` — Amazon Corretto 11 JDK, prints the
   `gradle.properties` line to pin it.
2. `setup-android-sdk.sh` — Android SDK command-line tools, platform 34,
   build-tools 34.0.0, platform-tools, and the NDK; writes `local.properties`.
   Note: the *latest* cmdline-tools release needs JDK 17 to run
   `sdkmanager` itself (separate from the JDK 11 the actual Gradle build
   needs) — this script uses an older cmdline-tools release to avoid that.
3. **(Optional, not needed for normal builds)** `build-cedar-ffi.sh` —
   cross-compiles Cedar's native FFI for Android ARM ABIs and drops the
   `.so` files into `app/src/main/jniLibs/`. Needs steps 1-2 done first
   (uses the NDK from step 2) plus a working Rust toolchain with a real
   host linker — on Windows without Visual Studio, this means installing
   a standalone MinGW-w64 GCC and switching rustup's default host
   toolchain to `stable-x86_64-pc-windows-gnu` first; the script doesn't
   currently automate that part.
4. `setup-strands-agent.sh` — Ollama + the matching model + the Strands
   Agents SDK (see `gateway/README.md`). Installs into the system Python
   by default; consider using a venv (`python -m venv gateway/.venv`,
   already gitignored) instead to avoid dependency conflicts with
   unrelated Python projects on the same machine.

After steps 1-2 (and 4 if needed), verify with:

```bash
./gradlew assembleDebug       # full APK build, using the already-committed Cedar .so
python -m gateway.agent.main "how do I treat a snake bite"
```

If a device is connected, also `adb install -r
app/build/outputs/apk/debug/app-debug.apk` and confirm the app launches
without crashing.
