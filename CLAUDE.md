# CLAUDE.md — Hearth

Hearth is a family spend tracker: Android app (Kotlin, native) reads bank/UPI SMS on-device,
auto-categorizes spending, and syncs a shared family ledger with a Rust **home hub** over the
home Wi-Fi (mDNS discovery, no cloud). Design prototype: `design/Family_Spend_Tracker.dc.html`.
Architecture and data model: `docs/architecture.md`.

## Layout

| Path | What |
|------|------|
| `harness/` | Self-correcting harness (Python 3.11 stdlib): checks, mistake catalog, guards, ledger, generate loop |
| `contracts/` | JSON fixtures shared by Rust and Kotlin — **single source of truth** for shared behaviour |
| `backend/crates/hearth-core` | Pure Rust domain: money, HLC, LWW sync merge, reports, analytics, portfolio |
| `backend/crates/hearth-server` | Rust hub: axum HTTP API + SQLite (rusqlite) + mDNS advertisement |
| `android/core` | Pure Kotlin/JVM: SMS parser, categorizer, HLC/merge, reports, analytics, INR formatting |
| `android/app` | Android app (Jetpack Compose, Room, WorkManager, NSD). Needs the Android SDK |

## The workflow (always)

1. **Test first.** Write/extend a failing test (and a `contracts/` fixture when behaviour is shared)
   before the implementation. Run it and see it fail for the right reason — run the red phase
   directly (`cargo test`, `./gradlew :core:test`) or with `hearth.py check --no-record` so an
   intentional red is not logged as a mistake.
2. Implement the smallest change that makes it pass.
3. Verify with the harness: `python3 harness/hearth.py check --changed` (or `--only backend`, etc.).
   The Claude Code **Stop hook** runs the fast checks on your changes and blocks finishing while red.
4. When a failure was not recognised (`UNCLASSIFIED` in `self_improvement.md`), triage it and teach
   the harness: `python3 harness/hearth.py learn --id NEW-ID --title ... --rule ... --pattern '<regex>'`
   (add `--guard-glob/--guard-regex` if the mistake can be caught statically).
5. Larger features can be driven end-to-end: `python3 harness/hearth.py loop --task "..."`.

Commands:

```bash
python3 harness/hearth.py check            # everything (records into ledger + docs)
python3 harness/hearth.py check --fast --changed
cd backend && cargo test                   # Rust
cd android && ./gradlew :core:test         # Kotlin core (JVM, no SDK needed)
cd android && ./gradlew :app:assembleDebug # needs ANDROID_HOME or android/local.properties
```

## Core rules (hand-written)

- Money is **integer paise** (`i64`/`Long`, fields named `*_minor`/`*Minor`). Never floats.
  Percentages are integer **basis points** (`*_bps`) or rounded integer percent.
- Pure cores (`hearth-core`, `android/core`) never read the clock, network, disk or randomness —
  time comes in as a parameter (`now_ms`, `anchor` date, `tz_offset_minutes`).
- Behaviour shared by both stacks (sync merge, period/report math, analytics) is pinned by
  `contracts/*.json`; both test suites load the same files. Change the fixture first, then both sides.
- Sync is per-record last-writer-wins on Hybrid Logical Clock strings; deletes are tombstones.
  Never mutate a record without bumping its HLC.
- Raw SMS bodies never leave `SmsParser`: not logged, not stored, not synced.
- SQL always uses bound parameters. Server handlers return `ApiError`, never `unwrap()`.
- Don't weaken, skip or delete a failing test to get green; fix the cause.
- Keep `self_improvement.md`'s generated sections untouched by hand — the harness owns them.

## Learned rules (promoted automatically by the harness)

Mistakes that recurred at least `promote_after` times (see `harness/checks.toml`) are promoted here.

<!-- harness:rules:start -->
- **RUST-FMT** (seen 3x): Run `cargo fmt --all` (in backend/) after editing Rust. Fix: `cd backend && cargo fmt --all`.
<!-- harness:rules:end -->
