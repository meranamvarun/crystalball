package com.hearth.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hearth.app.HearthApp
import java.util.concurrent.TimeUnit

/** Background push/pull with the home hub. Retries (with backoff) while the hub is unreachable. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as HearthApp).graph
        return if (graph.sync()) Result.success() else Result.retry()
    }
}

object SyncScheduler {
    private const val PERIODIC = "hearth-sync"
    private const val NOW = "hearth-sync-now"

    // Unmetered ≈ Wi-Fi: the hub only exists on the home network.
    private val onWifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(onWifi).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(onWifi).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** Sync the moment the phone joins a Wi-Fi network (e.g. arriving home). */
    fun syncWhenOnWifi(context: Context) {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = syncNow(app)
            },
        )
    }
}
