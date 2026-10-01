package com.hearth.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** The part of the hub API the sync engine needs (faked in tests). */
interface Hub {
    fun sync(token: String, request: SyncRequest): SyncResponse
}

class HubException(val status: Int, message: String) : IOException(message)

/**
 * Blocking JSON client for hearth-server over the LAN (plain java.net, so it works on Android
 * and the JVM). Call it off the main thread.
 */
class HubClient(baseUrl: String, private val timeoutMs: Int = 10_000) : Hub {
    private val base = baseUrl.trimEnd('/')

    fun healthy(): Boolean = runCatching { request("GET", "/healthz", null, null) }.isSuccess

    fun createFamily(req: CreateFamilyRequest): Credentials =
        HearthJson.decodeFromString(request("POST", "/v1/families", null, HearthJson.encodeToString(req)))

    fun join(req: JoinFamilyRequest): Credentials =
        HearthJson.decodeFromString(request("POST", "/v1/join", null, HearthJson.encodeToString(req)))

    fun family(token: String): FamilyInfo = HearthJson.decodeFromString(request("GET", "/v1/family", token, null))

    fun status(token: String): SyncStatus = HearthJson.decodeFromString(request("GET", "/v1/sync/status", token, null))

    override fun sync(token: String, request: SyncRequest): SyncResponse =
        HearthJson.decodeFromString(request("POST", "/v1/sync", token, HearthJson.encodeToString(request)))

    /** Hub-computed report (used to cross-check the on-device computation). */
    fun report(token: String, scope: String, range: String, anchor: String): JsonObject {
        val q = listOf("scope" to scope, "range" to range, "anchor" to anchor)
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        return HearthJson.parseToJsonElement(request("GET", "/v1/reports?$q", token, null)).jsonObject
    }

    private fun request(method: String, path: String, token: String?, body: String?): String {
        val conn = URI.create(base + path).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw HubException(status, "$method $path -> $status $text")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
