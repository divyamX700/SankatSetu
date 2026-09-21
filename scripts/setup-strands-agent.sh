#!/usr/bin/env bash
# Installs Ollama, pulls the model matching the on-device assistant, and
# installs the Strands Agents SDK — see gateway/README.md for what this
# actually builds and why. Run from the repo root.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="/c/Tools"
INSTALLER="$TOOLS_DIR/OllamaSetup.exe"
URL="https://ollama.com/download/OllamaSetup.exe"
MODEL="qwen2.5:0.5b-instruct" # closest Ollama tag to the on-device Qwen2.5-0.5B-Instruct — see docs/adr/0011

mkdir -p "$TOOLS_DIR"

if ! command -v ollama >/dev/null 2>&1; then
  echo "Downloading Ollama installer (resumable)..."
  curl -L --retry 20 --retry-delay 5 --retry-all-errors -C - -o "$INSTALLER" "$URL"
  echo "Running installer (Windows GUI installer — may need a click to confirm)..."
  "$INSTALLER"
  echo "Waiting for Ollama service to come up..."
  sleep 5
else
  echo "Ollama already installed: $(ollama --version)"
fi

echo "Pulling $MODEL..."
ollama pull "$MODEL"

echo "Installing Strands Agents SDK + Ollama extra..."
python3 -m pip install -r "$REPO_ROOT/gateway/requirements.txt"

echo
echo "Setup complete. Try it:"
echo "  cd \"$REPO_ROOT\""
echo "  python -m gateway.agent.main \"how do I treat a snake bite\""
