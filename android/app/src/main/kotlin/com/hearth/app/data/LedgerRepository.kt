package com.hearth.app.data

import com.hearth.core.Categorizer
import com.hearth.core.CategoryConfig
import com.hearth.core.Entities
import com.hearth.core.Hlc
import com.hearth.core.Hub
import com.hearth.core.Ledger
import com.hearth.core.LocalStore
import com.hearth.core.SyncEngine
import com.hearth.core.SyncRecord
import com.hearth.core.SyncResult
import com.hearth.core.wins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Room-backed [LocalStore] with the same LWW semantics as core's InMemoryLocalStore. */
class RoomLocalStore(private val db: HearthDatabase, private val prefs: Prefs) : LocalStore {
    private val dao = db.recordDao()

    override var cursor: Long
        get() = prefs.cursor
        set(v) {
            prefs.cursor = v
        }

    override var clock: Hlc
        get() = prefs.clock
        set(v) {
            prefs.clock = v
        }

    fun writeLocal(record: SyncRecord) = db.runInTransaction(
        Runnable {
            if (wins(dao.get(record.entity, record.id)?.hlc, record.hlc)) dao.upsert(record.toEntity(dirty = true))
        },
    )

    override fun dirtyRecords(): List<SyncRecord> = dao.dirty().map { it.toRecord() }

    override fun markClean(pushed: List<SyncRecord>) = db.runInTransaction(
        Runnable { pushed.forEach { dao.markClean(it.entity, it.id, it.hlc) } },
    )

    override fun applyRemote(records: List<SyncRecord>) = db.runInTransaction(
        Runnable {
            for (r in records) {
                // A newer remote version also replaces (and so drops) a losing pending local edit.
                if (wins(dao.get(r.entity, r.id)?.hlc, r.hlc)) dao.upsert(r.toEntity(dirty = false))
            }
        },
    )
}

/** All ledger reads/writes for the app. Writes are serialized so HLC stamps stay monotonic. */
class LedgerRepository(private val db: HearthDatabase, private val prefs: Prefs) {
    private val dao = db.recordDao()
    private val lock = Mutex()
    val store = RoomLocalStore(db, prefs)

    val ledger: Flow<Ledger> = dao.observeAll().map { rows -> Ledger.fromRecords(rows.map { it.toRecord() }) }

    private fun currentLedger(): Ledger = Ledger.fromRecords(dao.all().map { it.toRecord() })

    private fun categorizer(ledger: Ledger): Categorizer =
        Categorizer(categoryConfig, emptyMap()).withLearned(ledger.categoryRules)

    private val categoryConfig by lazy { CategoryConfig.default() }

    fun categories(): List<String> = categoryConfig.categories

    /** Returns true when the SMS was a transaction and got recorded. */
    suspend fun ingestSms(sender: String, body: String, receivedAtMs: Long): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val memberId = prefs.memberId ?: return@withLock false
            val result = com.hearth.core.ingestSms(sender, body, receivedAtMs, memberId, categorizer(currentLedger()), store.clock)
                ?: return@withLock false
            val (record, clock) = result
            store.clock = clock
            store.writeLocal(record)
            true
        }
    }

    suspend fun recategorize(transactionId: String, category: String, nowMs: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            val record = dao.get(Entities.TRANSACTION, transactionId)?.toRecord() ?: return@withLock
            val (updated, rule, clock) = com.hearth.core.recategorize(record, category, nowMs, store.clock)
            store.clock = clock
            store.writeLocal(updated)
            store.writeLocal(rule)
        }
    }

    /** Create or replace a user-entered entity (holding, budget, bill, …) as a local edit. */
    suspend fun put(entity: String, id: String, payload: JsonObject, nowMs: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            val stamp = store.clock.tick(nowMs)
            store.clock = stamp
            store.writeLocal(SyncRecord(entity, id, stamp.toString(), false, prefs.memberId.orEmpty(), payload))
        }
    }

    suspend fun sync(hub: Hub, token: String, nowMs: Long): SyncResult = withContext(Dispatchers.IO) {
        lock.withLock { SyncEngine.syncOnce(store, hub, token, nowMs) }
    }

    suspend fun wipe() = withContext(Dispatchers.IO) { lock.withLock { dao.clear() } }
}
