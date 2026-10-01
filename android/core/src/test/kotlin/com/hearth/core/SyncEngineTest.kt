package com.hearth.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A hub that behaves like hearth-server's /v1/sync (LWW + per-family change cursor). */
private class FakeHub : Hub {
    private val store = MemoryStore()
    private val seqOf = mutableMapOf<String, Long>()
    private var seq = 0L
    val received = mutableListOf<List<SyncRecord>>()

    override fun sync(token: String, request: SyncRequest): SyncResponse {
        received += request.records
        val applied = store.merge(request.records)
        applied.forEach { seqOf[it.key] = ++seq }
        val changes = store.records().filter { (seqOf[it.key] ?: 0) > request.since }.sortedBy { seqOf[it.key] }
        return SyncResponse(applied.size, emptyList(), seq, changes)
    }
}

class SyncEngineTest {
    private fun txn(id: String, hlc: String, amount: Long) = SyncRecord(
        Entities.TRANSACTION, id, hlc, false, "m1",
        JsonObject(mapOf("amount_minor" to JsonPrimitive(amount))),
    )

    @Test
    fun pushesDirtyRecordsAndPullsOthers() {
        val hub = FakeHub()
        val a = InMemoryLocalStore("a")
        val b = InMemoryLocalStore("b")
        a.writeLocal(txn("t1", "0000000001000-0000-a", 100))
        val ra = SyncEngine.syncOnce(a, hub, "tok", nowMs = 2_000)
        assertEquals(1, ra.pushed)
        assertTrue(a.dirtyRecords().isEmpty(), "pushed records are clean")
        val rb = SyncEngine.syncOnce(b, hub, "tok", nowMs = 2_000)
        assertEquals(1, rb.pulled)
        assertEquals(listOf("t1"), b.records().map { it.id })
        assertEquals(a.cursor, b.cursor)
    }

    @Test
    fun pullAdvancesLocalClockPastRemoteStamps() {
        val hub = FakeHub()
        val a = InMemoryLocalStore("a")
        a.writeLocal(txn("t1", "0000000009000-0003-a", 100))
        SyncEngine.syncOnce(a, hub, "tok", nowMs = 1_000)
        val b = InMemoryLocalStore("b")
        SyncEngine.syncOnce(b, hub, "tok", nowMs = 1_000) // b's wall clock is behind a's stamp
        val next = b.clock.tick(1_000)
        assertTrue(next.toString() > "0000000009000-0003-a", "b's next edit must win over what it has seen")
    }

    @Test
    fun localEditDuringSyncStaysDirty() {
        val hub = object : Hub {
            lateinit var store: InMemoryLocalStore
            override fun sync(token: String, request: SyncRequest): SyncResponse {
                store.writeLocal(txn("t1", "0000000005000-0000-a", 999)) // user edits while the request is in flight
                return SyncResponse(request.records.size, emptyList(), 1, emptyList())
            }
        }
        val a = InMemoryLocalStore("a")
        hub.store = a
        a.writeLocal(txn("t1", "0000000001000-0000-a", 100))
        SyncEngine.syncOnce(a, hub, "tok", nowMs = 1_000)
        assertEquals(listOf("0000000005000-0000-a"), a.dirtyRecords().map { it.hlc })
    }

    @Test
    fun olderLocalWriteNeverOverwritesNewerState() {
        val a = InMemoryLocalStore("a")
        a.writeLocal(txn("t1", "0000000002000-0000-a", 2))
        a.writeLocal(txn("t1", "0000000001000-0000-a", 1))
        assertEquals(2L, fromRecordAmount(a.records().single()))
    }

    @Test
    fun pendingEditThatLostToANewerRemoteEditIsDropped() {
        val a = InMemoryLocalStore("a")
        a.writeLocal(txn("t1", "0000000001000-0000-a", 1))
        a.applyRemote(listOf(txn("t1", "0000000002000-0000-b", 2)))
        assertTrue(a.dirtyRecords().isEmpty())
        assertEquals(2L, fromRecordAmount(a.records().single()))
    }

    private fun fromRecordAmount(r: SyncRecord) = (r.payload as JsonObject).getValue("amount_minor").toString().toLong()
}
