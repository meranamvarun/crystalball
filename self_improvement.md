# Self improvement log

This file is how Hearth's development process gets better over time. The harness
(`harness/hearth.py`) classifies every failing check against the mistake catalog
(`harness/mistakes.json`), counts occurrences in `harness/ledger.json` and re-renders the
generated sections below. A mistake seen `promote_after` times has its rule promoted into
`CLAUDE.md` → *Learned rules*.

## How the loop works

```
 task ──► agent writes tests ──► agent implements ──► harness check
              ▲                                            │
              │        fix prompt = failing output         │ red
              └──── + matched catalog rules + fixes ◄──────┘
                                                           │ green
                                   ledger + docs updated ◄─┘
```

- **Classify:** regex patterns in the catalog map raw tool output to a mistake id.
- **Guard:** catalog guards scan the source for patterns that *are* the mistake
  (float money, wall-clock in pure core, SQL interpolation, unwrap in handlers, SMS logging).
- **Count:** a failure that persists across runs counts once; a recurrence after a fix counts again.
- **Promote:** recurring mistakes become rules in `CLAUDE.md`, which every agent session reads.
- **Triage:** unmatched failures land in *Needs triage*; teach the harness with `hearth.py learn`.

## Lessons (hand-written)

Write one entry per non-obvious lesson: what went wrong, why, and the rule/guard that now prevents it.

<!-- Add newest lessons at the top. -->

### 2026-10-01 · Mechanical mistakes should be self-corrected, not just reported
- **What happened:** `RUST-FMT` recurred and was promoted into CLAUDE.md.
- **Change:** checks can declare a `fix` command; `check --autofix`, the Stop hook and the
  generation loop apply it and re-check. The original failure is still recorded in the ledger
  (label `… (before autofix)`) so recurrence statistics stay honest.

### 2026-10-01 · Smoke-testing the real binary found a validation gap the unit tests missed
- **What happened:** pushing a transaction with `member_id: "x"` was accepted.
- **Fix:** the hub rejects live records whose `member_id` is not in the family
  (`records_must_reference_members_of_the_family`). Lesson: after the tests go green, run the
  binary once with realistic input and look at what it accepted.

### 2026-10-01 · Hand-written test constants were wrong (HLC width, epoch millis)
- **What happened:** server tests used 16-digit HLC millis (`0001790865000000-…`) and an epoch
  constant that was not 2026-09-29T14:30Z. Both were caught before the run, by re-deriving them.
- **Prevention:** compute timestamps (`python3 -c 'import datetime…'`) instead of typing them;
  HLC millis are exactly 13 digits.

### 2026-10-01 · clap `#[arg(env = …)]` needs the `env` cargo feature
- **What happened:** `error[E0599]: no method named env found for struct Arg`; classified only as
  the generic `RUST-TYPE`.
- **Teach:** new catalog entry `CARGO-FEATURE-MISSING` (via `hearth.py learn`) with patterns for
  clap/tokio/chrono/serde/tower feature gaps, scoped to Rust checks.

### 2026-10-01 · Harness misclassified a rustfmt diff as CONTRACT-DRIFT
- **What happened:** the first `rust-fmt` failure was tagged `RUST-FMT` *and* `CONTRACT-DRIFT`.
  The contract pattern `contract case '.*' failed` matched the assertion-message *string literal*
  shown inside the rustfmt diff, not a failing test. Output also carried ANSI colour codes.
- **Why it matters:** false positives inflate the ledger and would promote wrong rules into CLAUDE.md.
- **Fix / prevention:** catalog entries now declare `checks` (patterns only apply to those checks'
  output), ANSI codes are stripped before classification, and the contract pattern uses a
  negative look-behind for quotes. Regression tests: `test_mistake_scoped_to_checks_*`,
  `test_repo_catalog_contract_drift_ignores_source_literals`. The bogus ledger entry was removed.

### 2026-10-01 · Long one-line `assert_eq!` calls fail rustfmt
- **What happened:** contract tests were written with long single-line `assert_eq!(…, "msg {}", x)`
  calls; rustfmt (max_width 100) rewrapped them → `RUST-FMT`.
- **Prevention:** run `cargo fmt --all` after writing Rust (rule `RUST-FMT`); the Stop hook catches it.

## Mistake ledger (generated)

<!-- harness:ledger:start -->
| Id | Mistake | Count | First seen | Last seen | Checks |
|----|---------|-------|------------|-----------|--------|
| RUST-FMT | Rust code not formatted with rustfmt | 3 | 2026-10-01T05:02:40Z | 2026-10-01T05:08:19Z | rust-fmt×3 |
| RUST-TYPE | Rust type mismatch / missing trait / unresolved item | 1 | 2026-10-01T05:06:24Z | 2026-10-01T05:06:24Z | rust-test×1 |
<!-- harness:ledger:end -->

## Recent harness runs (generated)

<!-- harness:history:start -->
| When | Label | Result | Mistakes |
|------|-------|--------|----------|
| 2026-10-01T05:08:19Z | server: member reference validation | ✅ green | - |
| 2026-10-01T05:08:19Z | server: member reference validation (before autofix) | ❌ red | RUST-FMT |
| 2026-10-01T05:07:49Z | server: lint with autofix | ✅ green | - |
| 2026-10-01T05:07:49Z | server: lint with autofix (before autofix) | ❌ red | RUST-FMT |
| 2026-10-01T05:07:00Z | server: lint | ❌ red | RUST-FMT |
| 2026-10-01T05:06:52Z | server: clap env feature | ✅ green | - |
| 2026-10-01T05:06:24Z | server: first implementation | ❌ red | RUST-TYPE |
| 2026-10-01T05:03:26Z | core: lint after fmt | ✅ green | - |
| 2026-10-01T05:02:40Z | core: lint | ❌ red | RUST-FMT |
| 2026-10-01T05:02:12Z | core: first implementation | ✅ green | - |
| 2026-10-01T04:53:04Z | harness bootstrap | ✅ green | - |
<!-- harness:history:end -->

## Generation loops (generated)

<!-- harness:loops:start -->
_No generation loops recorded yet._
<!-- harness:loops:end -->

## Needs triage (generated)

<!-- harness:triage:start -->
_Nothing to triage._
<!-- harness:triage:end -->
