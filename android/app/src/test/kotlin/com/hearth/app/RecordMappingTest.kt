package com.hearth.app

import com.hearth.app.data.toEntity
import com.hearth.app.data.toRecord
import com.hearth.core.SyncRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordMappingTest {
    @Test
    fun recordRoundTripsThroughRoomEntity() {
        val record = SyncRecord(
            entity = "transaction",
            id = "sms-abc",
            hlc = "1790692200000-0000-phone",
            deleted = false,
            author = "m_you",
            payload = JsonObject(mapOf("amount_minor" to JsonPrimitive(64_900), "category" to JsonPrimitive("Entertainment"))),
        )
        val entity = record.toEntity(dirty = true)
        assertTrue(entity.dirty)
        assertEquals(record, entity.toRecord())
    }
}
