package com.hearth.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Finds the Hearth hub advertised as `_hearth._tcp` on the current Wi-Fi (mDNS / DNS-SD). */
class HubDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun find(timeoutMs: Long = 6_000): String? {
        var listener: NsdManager.DiscoveryListener? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { cont ->
                    val resolving = AtomicBoolean(false)
                    val l = object : NsdManager.DiscoveryListener {
                        override fun onDiscoveryStarted(serviceType: String) = Unit
                        override fun onDiscoveryStopped(serviceType: String) = Unit
                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                        override fun onServiceLost(service: NsdServiceInfo) = Unit

                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onServiceFound(service: NsdServiceInfo) {
                            if (!resolving.compareAndSet(false, true)) return
                            @Suppress("DEPRECATION")
                            nsd.resolveService(service, resolver(cont, resolving))
                        }
                    }
                    listener = l
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
                }
            }
        } finally {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }

    private fun resolver(
        cont: kotlinx.coroutines.CancellableContinuation<String?>,
        resolving: AtomicBoolean,
    ) = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            resolving.set(false) // let the next sighting try again
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            val host = info.host?.hostAddress ?: return
            val literal = if (host.contains(':')) "[$host]" else host
            if (cont.isActive) cont.resume("http://$literal:${info.port}")
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_hearth._tcp"
    }
}
