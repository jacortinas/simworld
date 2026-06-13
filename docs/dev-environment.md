# Dev environment (incl. Claude Code on the web)

How to get `sim` building, testing, and linting — on a local machine and inside
an ephemeral remote container (Claude Code on the web). The remote case has
sharp edges the local case doesn't, so they're called out explicitly.

## TL;DR

| Task            | Command                          | Needs a display? |
| --------------- | -------------------------------- | ---------------- |
| Run tests       | `clojure -M:test`                | No (headless)    |
| Lint            | `clj-kondo --lint src test dev`  | No               |
| Run the game    | `clojure -M:run` (`:mac:run` on macOS) | **Yes** (libGDX window) |
| REPL            | `clojure -M:repl`                | **Yes** (opens the window) |

The test suite is **fully headless**: no test loads libGDX, a GL context, or
native code (the GL `draw` fns are deliberately untested; only their pure cores
are). So tests run anywhere a JVM + the Clojure CLI exist. The *game* and the
*REPL* both open a real window and cannot run in a headless container.

## Toolchain

- **JDK.** Local dev pins **Temurin 24** via `.mise.toml` (the `:run`/`:repl`
  JVM opts use JDK-24-only flags: generational ZGC, `--sun-misc-unsafe-memory-access=allow`).
  The `:test` alias carries **no** JVM opts, so tests run fine on the JDK 21 that
  the web container ships. Don't add JDK-24 flags to `:test`.
- **Clojure CLI** (tools.deps) — required for everything. Not preinstalled in
  the web container; the SessionStart hook installs it (below).
- **clj-kondo** — the linter. Config lives in `.clj-kondo/config.edn` (tracked);
  its `.cache/` is gitignored. Installed by the SessionStart hook.

## Network egress (the one thing you must configure for the web)

The web container's egress is **allowlist-based**. Out of the box:

- `repo1.maven.org` (Maven Central) — **allowed** ✅
- `github.com` / `raw.githubusercontent.com` — **allowed** ✅ (the test-runner
  is a git dep; the toolchain installers are GitHub downloads)
- `repo.clojars.org` (Clojars) — **NOT allowed** ❌ (returns 403)

`nippy` and `tufte` are **Clojars-only** — they are not mirrored on Maven
Central — so without Clojars the Clojure CLI **cannot build a classpath**, and
neither tests nor the REPL can start.

**Fix (one-time, per environment):** add `repo.clojars.org` to this
environment's network egress allowlist in the Claude Code on the web settings.
See https://code.claude.com/docs/en/claude-code-on-the-web. After that, the
SessionStart hook's dep prefetch succeeds and `clojure -M:test` works.

## SessionStart hook

`.claude/hooks/session-start.sh` (registered in `.claude/settings.json`) runs
when a web session starts. It:

1. installs the **Clojure CLI** into `~/.local` (no sudo) if missing,
2. installs **clj-kondo** into `~/.local/bin` if missing,
3. prefetches deps (`clojure -P -M:test`) so the first test run is warm — the
   container filesystem is cached after the hook, so later sessions start warm.

It is idempotent (skips anything already present) and only acts in the remote
container (guarded by `CLAUDE_CODE_REMOTE`), so it never touches a local
machine. If Clojars is still blocked, step 3 prints a warning and the session
**still starts** — it just can't resolve deps until egress is fixed.

The hook runs **synchronously**: the session waits for it, which guarantees the
toolchain is ready before Claude does anything (no race where a test runs before
deps exist). The tradeoff is slower session startup. To trade that for speed,
switch it to async mode (emit `{"async": true, "asyncTimeout": 300000}` as the
first stdout line) — at the cost of reintroducing that race.

Changes to the hook only take effect for **future** sessions once merged into
the repo's default branch.

## Linting

```bash
clj-kondo --lint src test dev
```

The config suppresses the deliberate "unused" requires in the `user` REPL helper
namespace (it loads the whole system on purpose so everything is reachable at
the prompt). Everything else is real signal. The current baseline is a handful
of minor warnings (redundant `double` coercions, a stray unused require, a
type-hint placement) and **zero errors**.
