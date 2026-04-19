package com.tripleu.mcon2codm

import android.app.Application
import android.util.Log
import org.conscrypt.Conscrypt
import java.security.Security

class Mcon2CodmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install bundled Conscrypt as the top-priority security provider so
        // all crypto operations (RSA key generation, AES-GCM for SPAKE2 peer
        // info, TLS) use the SAME implementation. Without this, Android 16's
        // internal com.android.org.conscrypt differs from our bundled
        // org.conscrypt just enough that libadb-android's peer-info exchange
        // fails to decrypt.
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            Log.i(TAG, "Conscrypt provider installed: ${Conscrypt.version()}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to install Conscrypt provider: ${t.message}")
        }
    }
    companion object { private const val TAG = "Mcon2CodmApp" }
}
