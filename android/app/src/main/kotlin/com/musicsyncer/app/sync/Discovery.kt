package com.musicsyncer.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class Discovery(private val context: Context) : AutoCloseable {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_music-syncer._tcp."
    private var listener: NsdManager.DiscoveryListener? = null
    @Volatile var found: NsdServiceInfo? = null; private set

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == serviceType) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) { found = info }
                    })
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { if (serviceInfo == found) found = null }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, l)
    }

    override fun close() = stop()

    fun stop() {
        listener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        listener = null
    }

    suspend fun find(timeoutMs: Long = 10_000): String? = suspendCancellableCoroutine { cont ->
        start()
        val deadline = System.currentTimeMillis() + timeoutMs
        val poll = Thread {
            while (!cont.isCancelled && System.currentTimeMillis() < deadline) {
                found?.let { info ->
                    cont.resume("http://${info.host.hostAddress}:${info.port}")
                    return@Thread
                }
                Thread.sleep(200)
            }
            if (!cont.isCancelled && found == null) cont.resume(null)
        }
        poll.isDaemon = true
        poll.start()
        cont.invokeOnCancellation { poll.interrupt() }
    }
}