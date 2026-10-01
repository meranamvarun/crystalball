# Hearth self-correcting harness

A small, dependency-free (Python 3.11 stdlib) harness that verifies every change, learns from
the mistakes it sees, and feeds that learning back to whoever writes the code — a person, a
Claude Code session, or the automated generate→verify→fix loop.

```
python3 harness/hearth.py check [--fast] [--changed] [--only a,b] [--autofix] [--no-record]
python3 harness/hearth.py loop --task "Add a CSV export to reports" [--max-iter 4] [--dry-run]
python3 harness/hearth.py learn --id NEW-ID --title "…" --rule "…" --pattern '<regex>' [--check rust-test]
python3 harness/hearth.py docs        # re-render CLAUDE.md / self_improvement.md from the ledger
python3 harness/hearth.py hook-stop   # Claude Code Stop hook (configured in .claude/settings.json)
```

## Pieces

| File | Role |
|------|------|
| `checks.toml` | The pipeline: guards, harness tests, rustfmt, clippy, cargo test, Kotlin core tests, ktlint, Android app build, cross-stack E2E. Each check declares the paths it covers (for `--changed`), whether it is `fast` (Stop hook), requirements (`env:`, `file:`, `tool:` → otherwise SKIPPED) and an optional mechanical `fix`. |
| `mistakes.json` | Mistake catalog. Each entry has a rule, a fix hint, regex `patterns` that recognise it in tool output (optionally scoped to `checks`), optional `guards` (forbidden source patterns) and a `transient` flag for environment flakes. |
| `ledger.json` | Observed mistakes: counts, first/last seen, per-check tallies, run history, generation loops, unclassified failures. A failure that persists across runs counts once; a recurrence after a fix counts again. |
| `hearth_harness/` | `catalog` (classify), `guards` (static scan), `checks` (select/run/retry/autofix), `ledger`, `docs` (render), `loop` (agent loop), `cli`. |
| `e2e.py` | Starts the Rust hub and runs the Kotlin E2E test against it; fails if the test was skipped. |
| `tests/` | The harness's own unit tests (it is code too). |

## The self-correction loop

1. **Verify** — run the selected checks (retrying catalogued transient failures like HTTP 429).
2. **Autofix** — mechanical failures (rustfmt, ktlint) are fixed and re-checked; the original
   failure is still recorded.
3. **Classify** — failing output is matched against the catalog; static guards catch mistakes
   that tests can't (float money, wall clock in pure core, SQL interpolation, `unwrap()` in
   handlers, SMS bodies in logs).
4. **Feed back** — a fix prompt is built from the failing output plus the matched rules/fixes.
   The Stop hook returns it to Claude Code (exit 2, max 3 rounds); `loop` sends it to the agent.
5. **Learn** — the ledger is updated; mistakes seen `promote_after` times are promoted into
   `CLAUDE.md` ("Learned rules"), and `self_improvement.md` is re-rendered. Unclassified failures
   land in *Needs triage* until someone runs `hearth.py learn`.

`loop` uses `agent_cmd` from `checks.toml` (default `claude -p --permission-mode acceptEdits`).
