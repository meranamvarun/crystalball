//! SQLite persistence for the hub. All SQL uses bound parameters.

use std::path::Path;

use hearth_core::sync::SyncRecord;
use rand::Rng;
use rusqlite::{params, Connection, OptionalExtension};
use sha2::{Digest, Sha256};

use crate::error::{ApiError, ApiResult};

const SCHEMA: &str = r#"
PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS families (
    id                TEXT PRIMARY KEY,
    name              TEXT NOT NULL,
    invite_code       TEXT NOT NULL UNIQUE,
    tz_offset_minutes INTEGER NOT NULL,
    seq               INTEGER NOT NULL DEFAULT 0,
    created_at_ms     INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS members (
    id            TEXT PRIMARY KEY,
    family_id     TEXT NOT NULL REFERENCES families(id),
    name          TEXT NOT NULL,
    role          TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
    id           TEXT PRIMARY KEY,
    member_id    TEXT NOT NULL REFERENCES members(id),
    name         TEXT NOT NULL,
    token_hash   TEXT NOT NULL UNIQUE,
    last_sync_ms INTEGER
);
CREATE TABLE IF NOT EXISTS records (
    family_id TEXT NOT NULL REFERENCES families(id),
    entity    TEXT NOT NULL,
    id        TEXT NOT NULL,
    hlc       TEXT NOT NULL,
    deleted   INTEGER NOT NULL,
    author    TEXT NOT NULL,
    payload   TEXT NOT NULL,
    seq       INTEGER NOT NULL,
    PRIMARY KEY (family_id, entity, id)
);
CREATE INDEX IF NOT EXISTS records_by_seq ON records(family_id, seq);
"#;

/// Invite alphabet without look-alikes (no I, O, 0, 1).
const INVITE_ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Device {
    pub device_id: String,
    pub member_id: String,
    pub family_id: String,
}

#[derive(Debug, Clone)]
pub struct Credentials {
    pub family_id: String,
    pub member_id: String,
    pub device_id: String,
    pub token: String,
    pub invite_code: String,
    pub role: String,
}

#[derive(Debug, Clone)]
pub struct Member {
    pub id: String,
    pub name: String,
    pub role: String,
    pub last_sync_ms: Option<i64>,
}

#[derive(Debug, Clone)]
pub struct Family {
    pub id: String,
    pub name: String,
    pub invite_code: String,
    pub tz_offset_minutes: i32,
}

pub struct Db {
    conn: Connection,
}

fn random_hex(bytes: usize) -> String {
    let mut buf = vec![0u8; bytes];
    rand::thread_rng().fill(&mut buf[..]);
    hex::encode(buf)
}

fn invite_code() -> String {
    let mut rng = rand::thread_rng();
    let mut pick = || INVITE_ALPHABET[rng.gen_range(0..INVITE_ALPHABET.len())] as char;
    let a: String = (0..4).map(|_| pick()).collect();
    let b: String = (0..4).map(|_| pick()).collect();
    format!("{a}-{b}")
}

pub fn hash_token(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

impl Db {
    pub fn open(path: &Path) -> rusqlite::Result<Db> {
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        Self::init(conn)
    }

    pub fn open_in_memory() -> rusqlite::Result<Db> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(conn: Connection) -> rusqlite::Result<Db> {
        conn.execute_batch(SCHEMA)?;
        Ok(Db { conn })
    }

    fn add_member_and_device(
        &self,
        family_id: &str,
        member_name: &str,
        role: &str,
        device_name: &str,
        now_ms: i64,
    ) -> ApiResult<(String, String, String)> {
        let member_id = format!("m_{}", random_hex(8));
        let device_id = format!("d_{}", random_hex(8));
        let token = random_hex(32);
        self.conn.execute(
            "INSERT INTO members (id, family_id, name, role, created_at_ms) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![member_id, family_id, member_name, role, now_ms],
        )?;
        self.conn.execute(
            "INSERT INTO devices (id, member_id, name, token_hash) VALUES (?1, ?2, ?3, ?4)",
            params![device_id, member_id, device_name, hash_token(&token)],
        )?;
        Ok((member_id, device_id, token))
    }

    pub fn create_family(
        &mut self,
        family_name: &str,
        member_name: &str,
        device_name: &str,
        tz_offset_minutes: i32,
        now_ms: i64,
    ) -> ApiResult<Credentials> {
        let family_id = format!("f_{}", random_hex(8));
        let code = invite_code();
        self.conn.execute(
            "INSERT INTO families (id, name, invite_code, tz_offset_minutes, created_at_ms) VALUES (?1, ?2, ?3, ?4, ?5)",
            params![family_id, family_name, code, tz_offset_minutes, now_ms],
        )?;
        let (member_id, device_id, token) =
            self.add_member_and_device(&family_id, member_name, "admin", device_name, now_ms)?;
        Ok(Credentials {
            family_id,
            member_id,
            device_id,
            token,
            invite_code: code,
            role: "admin".into(),
        })
    }

    pub fn join(
        &mut self,
        invite_code: &str,
        member_name: &str,
        device_name: &str,
        now_ms: i64,
    ) -> ApiResult<Credentials> {
        let normalized = invite_code.trim().to_uppercase();
        let family_id: Option<String> = self
            .conn
            .query_row(
                "SELECT id FROM families WHERE invite_code = ?1",
                params![normalized],
                |r| r.get(0),
            )
            .optional()?;
        let family_id =
            family_id.ok_or_else(|| ApiError::NotFound("unknown invite code".into()))?;
        let (member_id, device_id, token) =
            self.add_member_and_device(&family_id, member_name, "member", device_name, now_ms)?;
        Ok(Credentials {
            family_id,
            member_id,
            device_id,
            token,
            invite_code: normalized,
            role: "member".into(),
        })
    }

    pub fn authenticate(&self, token: &str) -> ApiResult<Device> {
        self.conn
            .query_row(
                "SELECT d.id, d.member_id, m.family_id FROM devices d JOIN members m ON m.id = d.member_id
                 WHERE d.token_hash = ?1",
                params![hash_token(token)],
                |r| {
                    Ok(Device {
                        device_id: r.get(0)?,
                        member_id: r.get(1)?,
                        family_id: r.get(2)?,
                    })
                },
            )
            .optional()?
            .ok_or(ApiError::Unauthorized)
    }

    pub fn family(&self, family_id: &str) -> ApiResult<Family> {
        self.conn
            .query_row(
                "SELECT id, name, invite_code, tz_offset_minutes FROM families WHERE id = ?1",
                params![family_id],
                |r| {
                    Ok(Family {
                        id: r.get(0)?,
                        name: r.get(1)?,
                        invite_code: r.get(2)?,
                        tz_offset_minutes: r.get(3)?,
                    })
                },
            )
            .optional()?
            .ok_or_else(|| ApiError::NotFound("family".into()))
    }

    /// Members ordered by name, with the latest sync time over their devices.
    pub fn members(&self, family_id: &str) -> ApiResult<Vec<Member>> {
        let mut stmt = self.conn.prepare(
            "SELECT m.id, m.name, m.role, MAX(d.last_sync_ms) FROM members m
             LEFT JOIN devices d ON d.member_id = m.id
             WHERE m.family_id = ?1 GROUP BY m.id ORDER BY m.name, m.id",
        )?;
        let rows = stmt.query_map(params![family_id], |r| {
            Ok(Member {
                id: r.get(0)?,
                name: r.get(1)?,
                role: r.get(2)?,
                last_sync_ms: r.get(3)?,
            })
        })?;
        Ok(rows.collect::<Result<_, _>>()?)
    }

    pub fn touch_device(&self, device_id: &str, now_ms: i64) -> ApiResult<()> {
        self.conn.execute(
            "UPDATE devices SET last_sync_ms = ?1 WHERE id = ?2",
            params![now_ms, device_id],
        )?;
        Ok(())
    }

    /// LWW upsert of already-validated records. Returns how many changed state.
    pub fn apply(&mut self, family_id: &str, records: &[SyncRecord]) -> ApiResult<usize> {
        let tx = self.conn.transaction()?;
        let mut seq: i64 = tx.query_row(
            "SELECT seq FROM families WHERE id = ?1",
            params![family_id],
            |r| r.get(0),
        )?;
        let mut applied = 0;
        for rec in records {
            let changed = tx.execute(
                "INSERT INTO records (family_id, entity, id, hlc, deleted, author, payload, seq)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
                 ON CONFLICT (family_id, entity, id) DO UPDATE SET
                    hlc = excluded.hlc, deleted = excluded.deleted, author = excluded.author,
                    payload = excluded.payload, seq = excluded.seq
                 WHERE excluded.hlc > records.hlc",
                params![
                    family_id,
                    rec.entity,
                    rec.id,
                    rec.hlc,
                    rec.deleted,
                    rec.author,
                    serde_json::to_string(&rec.payload)?,
                    seq + 1
                ],
            )?;
            if changed > 0 {
                seq += 1;
                applied += 1;
            }
        }
        tx.execute(
            "UPDATE families SET seq = ?1 WHERE id = ?2",
            params![seq, family_id],
        )?;
        tx.commit()?;
        Ok(applied)
    }

    /// Records changed after `since`, oldest change first, and the cursor to use next time.
    pub fn changes_since(&self, family_id: &str, since: i64) -> ApiResult<(Vec<SyncRecord>, i64)> {
        let cursor: i64 = self.conn.query_row(
            "SELECT seq FROM families WHERE id = ?1",
            params![family_id],
            |r| r.get(0),
        )?;
        let records = self.query_records(
            "SELECT entity, id, hlc, deleted, author, payload FROM records
             WHERE family_id = ?1 AND seq > ?2 ORDER BY seq",
            params![family_id, since],
        )?;
        Ok((records, cursor))
    }

    pub fn all_records(&self, family_id: &str) -> ApiResult<Vec<SyncRecord>> {
        self.query_records(
            "SELECT entity, id, hlc, deleted, author, payload FROM records WHERE family_id = ?1 ORDER BY seq",
            params![family_id],
        )
    }

    fn query_records(&self, sql: &str, args: impl rusqlite::Params) -> ApiResult<Vec<SyncRecord>> {
        let mut stmt = self.conn.prepare(sql)?;
        let rows = stmt.query_map(args, |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, bool>(3)?,
                r.get::<_, String>(4)?,
                r.get::<_, String>(5)?,
            ))
        })?;
        let mut out = Vec::new();
        for row in rows {
            let (entity, id, hlc, deleted, author, payload) = row?;
            out.push(SyncRecord {
                entity,
                id,
                hlc,
                deleted,
                author,
                payload: serde_json::from_str(&payload)?,
            });
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn rec(id: &str, hlc: &str) -> SyncRecord {
        SyncRecord {
            entity: "budget".into(),
            id: id.into(),
            hlc: hlc.into(),
            deleted: false,
            author: "m".into(),
            payload: json!({"category": "Dining", "cap_minor": 1}),
        }
    }

    #[test]
    fn apply_is_last_writer_wins_and_advances_cursor_only_on_change() {
        let mut db = Db::open_in_memory().unwrap();
        let fam = db.create_family("F", "A", "d", 330, 0).unwrap().family_id;
        assert_eq!(
            db.apply(&fam, &[rec("b1", "0000000000002-0000-a")])
                .unwrap(),
            1
        );
        assert_eq!(
            db.apply(&fam, &[rec("b1", "0000000000001-0000-a")])
                .unwrap(),
            0
        );
        assert_eq!(
            db.apply(&fam, &[rec("b1", "0000000000002-0000-a")])
                .unwrap(),
            0
        );
        let (recs, cursor) = db.changes_since(&fam, 0).unwrap();
        assert_eq!((recs.len(), cursor), (1, 1));
        assert_eq!(
            db.apply(&fam, &[rec("b1", "0000000000003-0000-a")])
                .unwrap(),
            1
        );
        let (recs, cursor) = db.changes_since(&fam, 1).unwrap();
        assert_eq!((recs.len(), cursor), (1, 2));
        assert_eq!(recs[0].hlc, "0000000000003-0000-a");
    }

    #[test]
    fn tokens_are_stored_hashed() {
        let mut db = Db::open_in_memory().unwrap();
        let creds = db.create_family("F", "A", "d", 330, 0).unwrap();
        let stored: String = db
            .conn
            .query_row("SELECT token_hash FROM devices", [], |r| r.get(0))
            .unwrap();
        assert_ne!(stored, creds.token);
        assert_eq!(stored, hash_token(&creds.token));
        assert_eq!(
            db.authenticate(&creds.token).unwrap().member_id,
            creds.member_id
        );
    }

    #[test]
    fn invite_codes_avoid_ambiguous_characters() {
        for _ in 0..200 {
            let code = invite_code();
            assert_eq!(code.len(), 9);
            assert!(!code.contains(['I', 'O', '0', '1']));
        }
    }
}
