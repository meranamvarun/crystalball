package com.hearth.core

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Kotlin DTOs must decode exactly what hearth-server sends (see backend tests/api.rs). */
class ProtocolTest {
    @Test
    fun decodesFamilyInfo() {
        val json = """{"id":"f_1","name":"The Sharma Family","invite_code":"SGVQ-DHBB","tz_offset_minutes":330,"me":"m_1",
            "members":[{"id":"m_1","name":"You","role":"admin"},{"id":"m_2","name":"Priya","role":"member"}]}"""
        val info = HearthJson.decodeFromString<FamilyInfo>(json)
        assertEquals("SGVQ-DHBB", info.inviteCode)
        assertEquals(330, info.tzOffsetMinutes)
        assertEquals(mapOf("m_1" to "You", "m_2" to "Priya"), info.memberNames())
    }

    @Test
    fun decodesCredentialsAndSyncResponse() {
        val creds = HearthJson.decodeFromString<Credentials>(
            """{"family_id":"f","member_id":"m","device_id":"d","token":"t","invite_code":"ABCD-EFGH","role":"admin"}""",
        )
        assertEquals("m", creds.memberId)
        val resp = HearthJson.decodeFromString<SyncResponse>(
            """{"applied":1,"rejected":[{"entity":"transaction","id":"t2","reason":"bad"}],"cursor":7,
               "records":[{"entity":"budget","id":"b1","hlc":"0000000000001-0000-a","deleted":false,"author":"m","payload":{"category":"Dining","cap_minor":5}}]}""",
        )
        assertEquals(7L, resp.cursor)
        assertEquals("Dining", fromRecord<Budget>(resp.records.single()).category)
    }

    @Test
    fun encodesRequestsWithSnakeCase() {
        assertEquals(
            """{"family_name":"F","member_name":"You","device_name":"Pixel","tz_offset_minutes":330}""",
            HearthJson.encodeToString(CreateFamilyRequest("F", "You", "Pixel", 330)),
        )
        assertEquals(
            """{"invite_code":"ABCD-EFGH","member_name":"Priya","device_name":"Pixel"}""",
            HearthJson.encodeToString(JoinFamilyRequest("ABCD-EFGH", "Priya", "Pixel")),
        )
    }
}
