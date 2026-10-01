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

## Mistake ledger (generated)

<!-- harness:ledger:start -->
_No mistakes recorded yet._
<!-- harness:ledger:end -->

## Recent harness runs (generated)

<!-- harness:history:start -->
| When | Label | Result | Mistakes |
|------|-------|--------|----------|
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
