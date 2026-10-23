#!/usr/bin/env bash
# Launch the InterLink desktop dev app detached, logging to /tmp/opencode/desktop-dev.log
export PATH="$HOME/.cargo/bin:$HOME/.local/node/bin:$PATH"
export RUST_LOG="${RUST_LOG:-info,desktop=debug}"
cd "$HOME/interlink/desktop" || exit 1
exec npm run tauri dev
