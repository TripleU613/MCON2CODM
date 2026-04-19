package com.tripleu.mcon2codm

import android.content.Context
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetAddress

/**
 * Flow-wrapped mDNS discovery for ADB wireless services advertised by this
 * device's own Wireless Debugging subsystem.
 *
 *  - SERVICE_TYPE_TLS_PAIRING : advertised only while 'Pair device with
 *    pairing code' screen is open. Pre-fills the pairing port.
 *  - SERVICE_TYPE_TLS_CONNECT : advertised whenever Wireless Debugging is on
 *    and ready for authenticated connections.
 */
object AdbDiscovery {
    data class Endpoint(val host: InetAddress, val port: Int)

    fun pairingPortFlow(ctx: Context): Flow<Endpoint?> =
        mdnsFlow(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING)

    fun connectPortFlow(ctx: Context): Flow<Endpoint?> =
        mdnsFlow(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT)

    private fun mdnsFlow(ctx: Context, type: String): Flow<Endpoint?> = callbackFlow {
        val mdns = AdbMdns(ctx, type) { host, port ->
            if (host != null && port > 0) trySend(Endpoint(host, port))
            else trySend(null)
        }
        mdns.start()
        awaitClose { mdns.stop() }
    }
}
