package fr.loufou.soundtouch.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import kotlin.coroutines.resume

object Discovery {

    private const val SERVICE_TYPE = "_soundtouch._tcp."

    /** mDNS d'abord ; si rien ne répond (box qui filtre le multicast…), on sonde tout le /24. */
    suspend fun scan(context: Context): List<SpeakerInfo> {
        val ips = mutableSetOf<String>()
        try {
            withTimeoutOrNull(3000) { announced(context).collect { ips += it } }
        } catch (e: IOException) {
            // mDNS indisponible sur ce réseau : le balayage prend le relais
        }
        val found = coroutineScope { ips.map { async { probe(it) } }.awaitAll() }.filterNotNull()
        return found.ifEmpty { sweep(context) }
    }

    /** Adresses des enceintes qui s'annoncent en mDNS, au fil de l'eau, tant qu'on collecte. */
    fun announced(context: Context): Flow<String> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        // Avant Android 14, NsdManager ne sait résoudre qu'un service à la fois
        val resolving = Mutex()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                launch { resolving.withLock { resolve(context, nsd, service) }?.let { trySend(it) } }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IOException("mDNS indisponible (code $errorCode)"))
            }

            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    suspend fun probe(ip: String): SpeakerInfo? =
        try {
            SoundTouchClient(ip, timeoutMs = 800).info()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

    private suspend fun resolve(context: Context, nsd: NsdManager, service: NsdServiceInfo): String? =
        withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { cont ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val callback = object : NsdManager.ServiceInfoCallback {
                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            val ip = info.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress ?: return
                            runCatching { nsd.unregisterServiceInfoCallback(this) }
                            if (cont.isActive) cont.resume(ip)
                        }

                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onServiceLost() {}
                        override fun onServiceInfoCallbackUnregistered() {}
                    }
                    nsd.registerServiceInfoCallback(service, context.mainExecutor, callback)
                    cont.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
                } else {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (cont.isActive) cont.resume((info.host as? Inet4Address)?.hostAddress)
                        }

                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            }
        }

    /** Solution de secours : sonde le port 8090 de chaque adresse du /24 local. */
    private suspend fun sweep(context: Context): List<SpeakerInfo> = coroutineScope {
        val prefix = localSubnetPrefix(context) ?: return@coroutineScope emptyList()
        val gate = Semaphore(48)
        (1..254)
            .map { n -> async { gate.withPermit { probe("$prefix.$n") } } }
            .awaitAll()
            .filterNotNull()
    }

    private fun localSubnetPrefix(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val address = props.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: return null
        return address.hostAddress?.substringBeforeLast('.')
    }
}
