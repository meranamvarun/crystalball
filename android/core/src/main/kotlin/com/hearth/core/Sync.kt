package com.hearth.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** JSON settings shared by sync payloads, the hub protocol and contract tests. */
val HearthJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

/** One replicated row; [payload] is entity-specific JSON (see Model.kt). */
@Serializable
data class SyncRecord(
    val entity: String,
    val id: String,
    val hlc: String,
    val deleted: Boolean,
    val author: String,
    val payload: JsonElement,
) {
    val key: String get() = "$entity/$id"
}

/** Last-writer-wins: [incoming] replaces the current version iff its HLC sorts strictly later. */
fun wins(currentHlc: String?, incomingHlc: String): Boolean = currentHlc == null || incomingHlc > currentHlc

/** In-memory LWW store keyed by (entity, id); the reference merge (contracts/merge.json). */
class MemoryStore {
    private val records = sortedMapOf<Pair<String, String>, SyncRecord>(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })

    /** Applies [incoming] in order and returns the records that changed local state. */
    fun merge(incoming: List<SyncRecord>): List<SyncRecord> {
        val applied = LinkedHashMap<String, SyncRecord>()
        for (rec in incoming) {
            val key = rec.entity to rec.id
            if (wins(records[key]?.hlc, rec.hlc)) {
                records[key] = rec
                applied.remove(rec.key)
                applied[rec.key] = rec
            }
        }
        return applied.values.toList()
    }

    fun records(): List<SyncRecord> = records.values.toList()
}

// ---- Hub protocol (POST /v1/sync) ----

@Serializable
data class SyncRequest(val since: Long, val records: List<SyncRecord>)

@Serializable
data class RejectedRecord(val entity: String, val id: String, val reason: String)

@Serializable
data class SyncResponse(
    val applied: Int,
    val rejected: List<RejectedRecord>,
    val cursor: Long,
    val records: List<SyncRecord>,
)

@Serializable
data class Credentials(
    @SerialName("family_id") val familyId: String,
    @SerialName("member_id") val memberId: String,
    @SerialName("device_id") val deviceId: String,
    val token: String,
    @SerialName("invite_code") val inviteCode: String,
    val role: String,
)

@Serializable
data class MemberSync(
    @SerialName("member_id") val memberId: String,
    val name: String,
    @SerialName("last_sync_ms") val lastSyncMs: Long?,
)

@Serializable
data class SyncStatus(@SerialName("now_ms") val nowMs: Long, val members: List<MemberSync>)
