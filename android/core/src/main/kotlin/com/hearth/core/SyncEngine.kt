package com.hearth.core

/** Phone-side storage the sync engine works against (Room on Android, in-memory in tests). */
interface LocalStore {
    var cursor: Long
    var clock: Hlc

    /** Local edits not yet acknowledged by the hub. */
    fun dirtyRecords(): List<SyncRecord>

    /** Mark pushed records clean, unless they were edited again (newer HLC) meanwhile. */
    fun markClean(pushed: List<SyncRecord>)

    /** LWW-merge records from the hub; they are clean by definition. */
    fun applyRemote(records: List<SyncRecord>)
}

data class SyncResult(val pushed: Int, val applied: Int, val pulled: Int, val rejected: List<RejectedRecord>)

object SyncEngine {
    /** One push/pull round with the hub. Safe to retry: the protocol is idempotent. */
    fun syncOnce(store: LocalStore, hub: Hub, token: String, nowMs: Long): SyncResult {
        val dirty = store.dirtyRecords()
        val response = hub.sync(token, SyncRequest(store.cursor, dirty))
        store.markClean(dirty)
        store.applyRemote(response.records)
        var clock = store.clock
        for (r in response.records) {
            Hlc.parseOrNull(r.hlc)?.let { clock = clock.recv(it, nowMs) }
        }
        store.clock = clock
        store.cursor = response.cursor
        return SyncResult(dirty.size, response.applied, response.records.size, response.rejected)
    }
}

/** Reference [LocalStore]; the Room implementation in the app mirrors these semantics. */
class InMemoryLocalStore(node: String) : LocalStore {
    private val store = MemoryStore()
    private val dirty = linkedMapOf<String, SyncRecord>()
    override var cursor: Long = 0
    override var clock: Hlc = Hlc(0, 0, node)

    /** A local edit: LWW against what we have, then queued for push. */
    fun writeLocal(record: SyncRecord) {
        if (store.merge(listOf(record)).isNotEmpty()) dirty[record.key] = record
    }

    fun records(): List<SyncRecord> = store.records()

    override fun dirtyRecords(): List<SyncRecord> = dirty.values.toList()

    override fun markClean(pushed: List<SyncRecord>) {
        for (p in pushed) {
            if (dirty[p.key]?.hlc == p.hlc) dirty.remove(p.key)
        }
    }

    override fun applyRemote(records: List<SyncRecord>) {
        for (applied in store.merge(records)) {
            // A newer edit from another phone beat our pending one: never re-push the loser.
            if (dirty[applied.key]?.let { it.hlc < applied.hlc } == true) dirty.remove(applied.key)
        }
    }
}
