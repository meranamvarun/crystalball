package com.hearth.app.data

import android.content.Context
import com.hearth.core.Credentials
import com.hearth.core.FamilyInfo
import com.hearth.core.HearthJson
import com.hearth.core.Hlc
import com.hearth.core.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

/** Small key-value state: credentials, hub address, sync cursor/clock and settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("hearth", Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)

    /** Bumps on every write so the UI can recompute. */
    val version: StateFlow<Int> = _version

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        sp.edit().apply(block).apply()
        _version.value += 1
    }

    val nodeId: String
        get() = sp.getString("node_id", null) ?: UUID.randomUUID().toString().take(8).also { id ->
            edit { putString("node_id", id) }
        }

    val token: String? get() = sp.getString("token", null)
    val memberId: String? get() = sp.getString("member_id", null)
    val familyName: String get() = sp.getString("family_name", null) ?: "Your family"
    val inviteCode: String? get() = sp.getString("invite_code", null)
    val tzOffsetMinutes: Int get() = sp.getInt("tz_offset", 330)

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = edit { putBoolean("onboarded", v) }

    var smsEnabled: Boolean
        get() = sp.getBoolean("sms_enabled", true)
        set(v) = edit { putBoolean("sms_enabled", v) }

    var hubUrl: String?
        get() = sp.getString("hub_url", null)
        set(v) = edit { putString("hub_url", v) }

    var cursor: Long
        get() = sp.getLong("cursor", 0)
        set(v) = edit { putLong("cursor", v) }

    var clock: Hlc
        get() = sp.getString("hlc", null)?.let { Hlc.parseOrNull(it) } ?: Hlc(0, 0, nodeId)
        set(v) = edit { putString("hlc", v.toString()) }

    var lastStatus: SyncStatus?
        get() = sp.getString("last_status", null)?.let { runCatching { HearthJson.decodeFromString(SyncStatus.serializer(), it) }.getOrNull() }
        set(v) = edit { putString("last_status", v?.let { HearthJson.encodeToString(SyncStatus.serializer(), it) }) }

    private val namesSerializer = MapSerializer(String.serializer(), String.serializer())

    val memberNames: Map<String, String>
        get() = sp.getString("members", null)?.let { HearthJson.decodeFromString(namesSerializer, it) } ?: emptyMap()

    fun saveCredentials(c: Credentials) = edit {
        putString("token", c.token)
        putString("member_id", c.memberId)
        putString("family_id", c.familyId)
        putString("invite_code", c.inviteCode)
    }

    fun saveFamily(info: FamilyInfo) = edit {
        putString("family_name", info.name)
        putString("invite_code", info.inviteCode)
        putInt("tz_offset", info.tzOffsetMinutes)
        putString("members", HearthJson.encodeToString(namesSerializer, info.memberNames()))
    }

    fun clear() = edit { clear() }
}
