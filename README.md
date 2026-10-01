# crystalball — Hearth

**Hearth** is a family spend tracker. It reads bank/UPI SMS on each phone (no manual logging),
auto-categorizes spending (asking only when unsure, and remembering the answer for the whole
family), and syncs a shared ledger between family phones **over the home Wi-Fi via a small
home hub — no cloud**. On top of the shared ledger: personal and family views by
day/week/month/year, category reports, an analytics dashboard (trends, budget overruns, unusual
spend) and a combined investment portfolio / net-worth view.

Design prototype: [`design/Family_Spend_Tracker.dc.html`](design/Family_Spend_Tracker.dc.html) ·
Architecture: [`docs/architecture.md`](docs/architecture.md)

## Repository layout

| Path | What | Stack |
|------|------|-------|
| [`harness/`](harness/README.md) | Self-correcting development harness: checks, mistake catalog, guards, ledger, autofix, generate→verify→fix loop, Claude Code Stop hook | Python 3.11 (stdlib) |
| [`contracts/`](contracts/) | Shared JSON fixtures — the spec both stacks are tested against | JSON |
| [`backend/`](backend/) | `hearth-core` (pure domain) + `hearth-server` (home hub: HTTP API, SQLite, mDNS) | Rust, axum, rusqlite (SQLite) |
| [`android/core`](android/core) | Pure Kotlin domain: SMS parser, categorizer, HLC/LWW sync engine, hub client, reports, analytics, portfolio, screen presenter | Kotlin/JVM |
| [`android/app`](android/app) | Android app: Compose UI per the design, Room, SMS receiver, NSD discovery, WorkManager sync | Kotlin, Jetpack Compose |
| [`CLAUDE.md`](CLAUDE.md) / [`self_improvement.md`](self_improvement.md) | Rules for agents (incl. rules the harness promotes automatically) and the improvement log | Markdown |

All tooling and storage is open source: SQLite (hub + Room on the phone), axum/tokio,
rusqlite, mdns-sd, kotlinx.serialization, Jetpack Compose/Room/WorkManager, JUnit, ktlint.

## Quick start

```bash
# Verify everything (records results into the mistake ledger and docs)
python3 harness/hearth.py check --autofix

# Run the home hub on any always-on machine on your Wi-Fi
cd backend && cargo run --release -p hearth-server -- --db hearth.db --bind 0.0.0.0:8787

# Build the Android app (needs the Android SDK: ANDROID_HOME or android/local.properties)
cd android && ./gradlew :app:assembleDebug
```

## How it fits together

1. A bank SMS arrives → `SmsReceiver` → `SmsParser` extracts amount/merchant/account/reference
   (the raw text is never stored) → `Categorizer` picks a category or leaves it for review.
2. The transaction becomes a `SyncRecord` stamped with a hybrid logical clock and is stored
   locally (Room). Its id is derived from the bank reference, so a joint-account debit seen on
   two phones is one record.
3. When the phone is on Wi-Fi it discovers the hub (`_hearth._tcp` mDNS) and runs one
   push/pull round (`SyncEngine`); conflicts resolve last-writer-wins per record.
4. Every screen is computed on-device from the local ledger by the `Dashboard` presenter, so
   the app works offline; the hub serves the same reports (and an E2E test proves both stacks
   compute identical numbers).

## Test-driven, contract-first

* `contracts/*.json` define HLC, merge, periods, reports, analytics, portfolio, SMS parsing and
  categorization. Rust (`backend/crates/hearth-core/tests/contracts.rs`) and Kotlin
  (`android/core/src/test/.../ContractTest.kt`) run the same files.
* `harness/e2e.py` starts the real Rust hub and drives two Kotlin "phones" against it.

## Current status

| Check | State |
|-------|-------|
| Harness self-tests, static guards | ✅ |
| Rust fmt / clippy `-D warnings` / tests (core + hub API) | ✅ |
| Kotlin core tests (contracts, presenter, sync engine, ingest) | ✅ |
| ktlint over core **and** app sources (proves the app parses) | ✅ |
| Cross-stack E2E (Kotlin phones ↔ Rust hub) | ✅ |
| `:app` compile + unit tests | ⏭️ not run here — the build environment blocks `dl.google.com` (Android SDK / Google Maven). Run `python3 harness/hearth.py check` with `ANDROID_HOME` set. |

Notes and next steps:

* Google Play restricts `RECEIVE_SMS`; distribution needs Play's SMS/Call Log permission
  declaration (personal-finance use) or sideloading.
* The hub uses plain HTTP on the LAN with bearer tokens; add TLS (self-signed + pinning) before
  using it on untrusted networks.
* Not built yet: PDF export (reports are shared as text), manual transaction entry, editing
  /deleting holdings, a "Manage categories" screen beyond budgets.
