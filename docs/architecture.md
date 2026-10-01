# Hearth architecture

## Shape of the system

```
 ┌──────────── phone (Android, Kotlin) ────────────┐        home Wi-Fi only
 │ SMS BroadcastReceiver ─► SmsParser ─► Categorizer│
 │            │ (raw SMS never leaves this box)     │      ┌──────────────────────────┐
 │            ▼                                     │ mDNS │  Hearth hub (Rust)       │
 │   Room DB (local ledger, HLC-stamped records)    │◄────►│  axum + SQLite           │
 │            ▲                ▲                    │ HTTP │  stores records, merges  │
 │   Compose UI (reports,      │ SyncWorker (NSD    │      │  LWW, serves change feed │
 │   analytics, portfolio)     │ discovery, 15 min  │      │  + reports/analytics API │
 │   computed by android/core  │ + on Wi-Fi connect)│      └──────────────────────────┘
 └──────────────────────────────────────────────────┘          ▲      runs on any always-on
                     other family phones ──────────────────────┘      box at home (Pi, NAS, PC)
```

* **Local-first.** Every phone keeps the full family ledger in Room and computes all screens
  offline with `android/core`. The hub is only a rendezvous + durable copy on the LAN; nothing
  goes to a cloud.
* **Discovery.** The hub advertises `_hearth._tcp.local.` via mDNS; the app finds it with Android
  NSD whenever it joins Wi-Fi (plus a periodic WorkManager job) and syncs silently.
* **Why a hub and not pure peer-to-peer?** Phones are rarely online at the same time; a hub gives
  every phone a consistent change feed to catch up from, while keeping data in the home.

## Sync protocol

Every syncable thing is a `SyncRecord`:

```json
{"entity": "transaction", "id": "sms-1f…", "hlc": "0001759100000000-0003-dev-a1",
 "deleted": false, "author": "m_you", "payload": { … entity-specific … }}
```

* **HLC** (hybrid logical clock) string `{millis:013}-{counter:04}-{node}`; fixed width so plain
  string comparison orders them. Rules in `contracts/hlc.json`.
* **Merge** is last-writer-wins per `(entity, id)`: incoming replaces local iff `incoming.hlc >
  local.hlc`. Deletes are tombstones (`deleted: true`). Rules in `contracts/merge.json`.
* **Exchange:** `POST /v1/sync {since, records}` → hub merges the pushed records, then returns
  every record whose `seq` > `since` plus the new cursor. Idempotent and safe to retry.
* **SMS dedupe across the family:** transaction ids for SMS are derived from (account last4,
  bank reference / amount+minute), so a joint-account debit seen on two phones becomes one record.

## Entities (payloads)

| entity | payload |
|--------|---------|
| `transaction` | `member_id, amount_minor, direction (debit/credit), merchant, category?, occurred_at_ms, source (sms/manual), account_last4?, note?` |
| `budget` | `category, cap_minor, member_id?` (null = family budget; monthly) |
| `bill` | `name, amount_minor, due_day, kind (recurring/variable/autopay)` |
| `holding` | `member_id, name, asset_class (equity/mutual_fund/fd/gold/other), invested_minor, value_minor` |
| `liability` | `member_id, name, outstanding_minor` |
| `goal` | `name, target_minor, saved_minor, target_month (YYYY-MM)` |
| `networth_snapshot` | `month (YYYY-MM), value_minor` |
| `category_rule` | `merchant_key, category` — a correction made by one member teaches every phone |

## Reporting math (shared, see `contracts/`)

* Only non-deleted **debits** are spend. Periods are local-time `[start, end)` in the family's
  `tz_offset_minutes`; weeks start Monday (`contracts/periods.json`).
* `round_div(a, b)` rounds half away from zero; percent = `round_div(part*100, whole)`,
  `delta_bps = round_div((cur-prev)*10000, prev)` (null when prev = 0).
* Categories sort by amount desc then name; `null` category is "Uncategorized" (needs review).
* Anomaly: a debit in the anchor month whose amount ≥ 3× the mean of the same scope+category
  debits in the 90 days before it (needs ≥ 3 baseline transactions).

## Hub HTTP API (v1)

| Method & path | Auth | Purpose |
|---------------|------|---------|
| `GET /healthz` | – | liveness |
| `POST /v1/families` | – | create family → admin member, device token, invite code |
| `POST /v1/join` | – | join with invite code → member, device token |
| `GET /v1/family` | bearer | family, members, invite code |
| `POST /v1/sync` | bearer | push records + pull changes since cursor |
| `GET /v1/sync/status` | bearer | per-member last sync time (sync sheet) |
| `GET /v1/reports?scope=me\|family&range=day\|week\|month\|year&anchor=YYYY-MM-DD` | bearer | report |
| `GET /v1/analytics?scope=…&anchor=…&months=6` | bearer | trend, budgets, anomalies, top merchants |
| `GET /v1/portfolio` | bearer | holdings, allocation, net worth, goals |

Device tokens are random 32-byte values; only their SHA-256 is stored.

## Storage

* Hub: SQLite (rusqlite, bundled, WAL). Tables `families, members, devices, records`.
  `records` keeps the JSON payload plus `seq` (per-family monotonically increasing change cursor).
* Phone: Room (SQLite) mirror of `records` + `sync_state(cursor, hlc)`.
