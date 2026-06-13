#!/usr/bin/env bash
#
# SessionStart hook for Claude Code on the web.
#
# A fresh web container ships with a JDK but NO Clojure toolchain, so out of
# the box Claude cannot run the test suite or lint. This hook bootstraps the
# toolchain and prefetches deps so `clojure -M:test` and `clj-kondo` work the
# moment the session starts.
#
# It installs into ~/.local (no sudo) and is idempotent: anything already
# present is skipped, so re-runs (resume/clear/compact) are cheap.
#
# NOTE on egress: nippy + tufte are Clojars-only (not mirrored on Maven
# Central). The container's egress is allowlist-based, so `repo.clojars.org`
# MUST be added to this environment's network egress settings, alongside the
# already-allowed repo1.maven.org and github.com. Without it the prefetch
# below cannot resolve a classpath and tests will fail. See
# docs/dev-environment.md.

set -euo pipefail

log() { printf '[session-start] %s\n' "$*" >&2; }

# Only meaningful in the remote (web) container. Local dev machines already
# have the toolchain (and pin JDK 24 via mise); don't touch them.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  log "not a remote session; skipping toolchain bootstrap"
  exit 0
fi

BIN="$HOME/.local/bin"
mkdir -p "$BIN"
export PATH="$BIN:$PATH"

# Persist PATH for the rest of the session so later tool calls find the
# binaries even though this hook runs in its own shell.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export PATH=\"$BIN:\$PATH\"" >> "$CLAUDE_ENV_FILE"
fi

# --- Clojure CLI (tools.deps) ------------------------------------------------
if command -v clojure >/dev/null 2>&1; then
  log "clojure already installed: $(clojure --version 2>/dev/null || true)"
else
  log "installing Clojure CLI into $HOME/.local ..."
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/linux-install.sh" \
    https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
  bash "$tmp/linux-install.sh" -p "$HOME/.local" >/dev/null
  rm -rf "$tmp"
  log "clojure installed: $(clojure --version 2>/dev/null || true)"
fi

# --- clj-kondo (linter) ------------------------------------------------------
if command -v clj-kondo >/dev/null 2>&1; then
  log "clj-kondo already installed: $(clj-kondo --version 2>/dev/null || true)"
else
  log "installing clj-kondo into $BIN ..."
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/install-clj-kondo" \
    https://raw.githubusercontent.com/clj-kondo/clj-kondo/master/script/install-clj-kondo
  bash "$tmp/install-clj-kondo" --dir "$BIN" >/dev/null
  rm -rf "$tmp"
  log "clj-kondo installed: $(clj-kondo --version 2>/dev/null || true)"
fi

# --- Prefetch deps -----------------------------------------------------------
# `-P` downloads the classpath without running anything. Resolves from Maven
# Central + GitHub (test-runner git dep) + Clojars. The container filesystem
# is cached after this hook, so subsequent sessions start warm.
log "prefetching deps (clojure -P -M:test) ..."
if clojure -P -M:test >/dev/null 2>&1; then
  log "deps prefetched OK"
else
  log "WARNING: dep prefetch failed."
  log "         Most likely repo.clojars.org is not on the egress allowlist."
  log "         nippy/tufte are Clojars-only; tests cannot resolve without it."
  log "         The session will still start. See docs/dev-environment.md."
fi

log "bootstrap complete"
