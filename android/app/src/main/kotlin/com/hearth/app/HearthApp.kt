package com.hearth.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.Room
import com.hearth.app.data.HearthDatabase
import com.hearth.app.data.LedgerRepository
import com.hearth.app.data.Prefs
import com.hearth.app.sync.HubDiscovery
import com.hearth.app.sync.SyncScheduler
import com.hearth.core.CreateFamilyRequest
import com.hearth.core.Credentials
import com.hearth.core.HubClient
import com.hearth.core.JoinFamilyRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class HearthApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        SyncScheduler.schedulePeriodic(this)
        SyncScheduler.syncWhenOnWifi(this)
    }
}

/** Manual dependency graph: one instance of each collaborator per process. */
class AppGraph(context: Context) {
    val prefs = Prefs(context)
    private val db = Room.databaseBuilder(context, HearthDatabase::class.java, "hearth.db").build()
    val repo = LedgerRepository(db, prefs)
    val discovery = HubDiscovery(context)

    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Finds the hub on the home Wi-Fi (mDNS), falling back to the last known address. */
    suspend fun locateHub(): String? = discovery.find() ?: prefs.hubUrl

    suspend fun createFamily(hubUrl: String, familyName: String, memberName: String): Credentials =
        withContext(Dispatchers.IO) {
            val client = HubClient(hubUrl)
            val creds = client.createFamily(CreateFamilyRequest(familyName, memberName, deviceName, prefs.tzOffsetMinutes))
            remember(hubUrl, client, creds)
        }

    suspend fun joinFamily(hubUrl: String, inviteCode: String, memberName: String): Credentials =
        withContext(Dispatchers.IO) {
            val client = HubClient(hubUrl)
            val creds = client.join(JoinFamilyRequest(inviteCode, memberName, deviceName))
            remember(hubUrl, client, creds)
        }

    private fun remember(hubUrl: String, client: HubClient, creds: Credentials): Credentials {
        prefs.saveCredentials(creds)
        prefs.hubUrl = hubUrl
        prefs.saveFamily(client.family(creds.token))
        return creds
    }

    /** One silent sync round. Returns false when the hub is unreachable (caller may retry later). */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val token = prefs.token ?: return@withContext true
        val base = locateHub() ?: return@withContext false
        try {
            val client = HubClient(base)
            val result = repo.sync(client, token, System.currentTimeMillis())
            prefs.saveFamily(client.family(token))
            prefs.lastStatus = client.status(token)
            prefs.hubUrl = base
            Log.i(TAG, "sync ok: pushed=${result.pushed} pulled=${result.pulled} rejected=${result.rejected.size}")
            true
        } catch (e: IOException) {
            Log.w(TAG, "sync failed: ${e.message}")
            false
        }
    }

    suspend fun logout() {
        repo.wipe()
        prefs.clear()
    }

    companion object {
        const val TAG = "Hearth"
    }
}
