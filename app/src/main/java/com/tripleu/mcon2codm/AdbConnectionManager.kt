package com.tripleu.mcon2codm

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.LocalServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Wraps libadb-android so this APK can pair with the device's own Wireless
 * Debugging and run shell commands — without Shizuku or root. After one-time
 * pairing (user enters pairing code), the key is persisted and every run
 * auto-connects via mDNS.
 */
class AdbConnectionManager private constructor(private val appCtx: Context) :
    AbsAdbConnectionManager() {

    // Versioned file names — bump to invalidate old keys/certs when fixing issues.
    private val keyFile = File(appCtx.filesDir, "adb_private_v4.pk8")
    private val pubFile = File(appCtx.filesDir, "adb_public_v4.der")
    private val certFile = File(appCtx.filesDir, "adb_cert_v4.der")

    private val keyPair: KeyPair by lazy { loadOrGenerateKeyPair() }
    private val cert: Certificate by lazy { loadOrGenerateCert() }

    init {
        api = Build.VERSION.SDK_INT
        setTimeout(10, TimeUnit.SECONDS)
    }

    override fun getDeviceName(): String = "mcon2codm"
    override fun getPrivateKey(): PrivateKey = keyPair.private
    override fun getCertificate(): Certificate = cert

    private fun loadOrGenerateKeyPair(): KeyPair {
        if (keyFile.exists() && pubFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val pub: PublicKey = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            } catch (e: Exception) {
                Log.w(TAG, "Stored keypair unusable; regenerating: ${e.message}")
            }
        }
        val g = KeyPairGenerator.getInstance("RSA")
        g.initialize(2048)
        val kp = g.generateKeyPair()
        keyFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        certFile.delete() // force cert regen for new key
        return kp
    }

    private fun loadOrGenerateCert(): Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        if (certFile.exists()) {
            try {
                return cf.generateCertificate(certFile.inputStream())
            } catch (e: Exception) {
                Log.w(TAG, "Stored cert unusable; regenerating: ${e.message}")
            }
        }
        val cert = generateSelfSignedCert(keyPair, "CN=${deviceName}")
        certFile.writeBytes(cert.encoded)
        return cert
    }

    /**
     * Generate a self-signed X.509 certificate for ADB pairing.
     *
     * Matches the pattern used by AppManager / libadb-android reference impl:
     * SHA512withRSA, V3, self-signed, with the signed cert re-parsed through
     * a standard CertificateFactory so the result is guaranteed to be a fully
     * encoded DER cert that Android's TLS stack will accept during pairing.
     */
    @Suppress("DEPRECATION")
    private fun generateSelfSignedCert(kp: KeyPair, dn: String): Certificate {
        val notBefore = Date(System.currentTimeMillis() - 24L * 3600 * 1000)
        val notAfter  = Date(System.currentTimeMillis() + 50L * 365 * 24 * 3600 * 1000)

        val info = android.sun.security.x509.X509CertInfo()
        val validity = android.sun.security.x509.CertificateValidity(notBefore, notAfter)
        val owner = android.sun.security.x509.X500Name(dn)

        info.set(android.sun.security.x509.X509CertInfo.VERSION,
            android.sun.security.x509.CertificateVersion(android.sun.security.x509.CertificateVersion.V3))
        info.set(android.sun.security.x509.X509CertInfo.SERIAL_NUMBER,
            android.sun.security.x509.CertificateSerialNumber(
                BigInteger(64, java.security.SecureRandom())))
        val algo = android.sun.security.x509.AlgorithmId(
            android.sun.security.x509.AlgorithmId.sha512WithRSAEncryption_oid)
        info.set(android.sun.security.x509.X509CertInfo.ALGORITHM_ID,
            android.sun.security.x509.CertificateAlgorithmId(algo))
        info.set(android.sun.security.x509.X509CertInfo.SUBJECT,
            android.sun.security.x509.CertificateSubjectName(owner))
        info.set(android.sun.security.x509.X509CertInfo.ISSUER,
            android.sun.security.x509.CertificateIssuerName(owner))
        info.set(android.sun.security.x509.X509CertInfo.KEY,
            android.sun.security.x509.CertificateX509Key(kp.public))
        info.set(android.sun.security.x509.X509CertInfo.VALIDITY, validity)

        val signed = android.sun.security.x509.X509CertImpl(info)
        signed.sign(kp.private, "SHA512withRSA")

        // Re-parse through the standard CertificateFactory so the returned
        // Certificate is a platform X509Certificate with consistent DER.
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(signed.encoded))
    }

    /**
     * One-time: pair with Wireless Debugging using the 6-digit code.
     *
     * Tries 127.0.0.1 first (more reliable for same-device pairing), then the
     * user-supplied host as fallback. After a successful pair, we IMMEDIATELY
     * open an authenticated connection via the TLS_CONNECT mDNS service — this
     * is what actually makes the ADB_WIFI backend "active".
     */
    fun pairOnce(host: String, pairingPort: Int, code: String): Result<Unit> {
        val candidates = linkedSetOf("127.0.0.1", host).filter { it.isNotBlank() }
        var lastException: Exception? = null
        for (h in candidates) {
            try {
                Log.i(TAG, "Pairing attempt on $h:$pairingPort")
                if (pair(h, pairingPort, code)) {
                    Prefs.of(appCtx).adbPaired = true
                    // Connect right away so the UI flips to 'adb wifi'. mDNS
                    // may take ~1s to resolve the TLS_CONNECT service; retry a
                    // few times before giving up silently.
                    repeat(6) { attempt ->
                        if (isConnected) return@repeat
                        try {
                            if (autoConnect(appCtx, 3000)) {
                                Log.i(TAG, "Connected after pair (attempt ${attempt + 1})")
                                return Result.success(Unit)
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "autoConnect attempt ${attempt + 1}: ${e.message}")
                        }
                        Thread.sleep(500)
                    }
                    // Pair succeeded but connect hasn't landed yet — that's OK,
                    // the app's polling loop will pick it up within ~1.5s.
                    return Result.success(Unit)
                }
                Log.w(TAG, "pair() returned false on $h:$pairingPort")
            } catch (e: Exception) {
                Log.e(TAG, "pair failed on $h:$pairingPort — ${e.javaClass.simpleName}: ${e.message}")
                lastException = e
            }
        }
        val msg = when {
            lastException?.message?.contains("refused", ignoreCase = true) == true ->
                "Connection refused — open ‘Pair device with pairing code’ in Settings first, then try immediately."
            lastException?.message?.contains("timeout", ignoreCase = true) == true ->
                "Timed out — is Wireless Debugging still on?"
            lastException != null -> "${lastException.javaClass.simpleName}: ${lastException.message}"
            else -> "Pairing rejected — wrong code?"
        }
        return Result.failure(IllegalStateException(msg))
    }

    /**
     * Pair using the live mDNS-advertised pairing port and the 6-digit code.
     * The UI doesn't need to know about IP / port — pairing always happens on
     * 127.0.0.1 and the port is advertised by the Settings pairing screen.
     */
    suspend fun pairAuto(ctx: Context, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            // Wait up to 4s for a live pairing advertisement. If none arrives,
            // the user hasn't opened 'Pair device with pairing code' yet.
            val endpoint = withTimeoutOrNull(4000) {
                AdbDiscovery.pairingPortFlow(ctx).firstOrNull { it != null }
            }
            val port = endpoint?.port
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "Pairing screen isn't open. Tap 'Pair device with pairing code' in Settings first."
                    )
                )
            pairOnce(host = "127.0.0.1", pairingPort = port, code = code)
        }

    /** Connect via mDNS (auto-detects the wireless adb port). */
    fun ensureConnected(ctx: Context): Boolean {
        if (isConnected) return true
        return try {
            // autoConnect uses mDNS to find the tls-connect service and connect.
            autoConnect(ctx, 5000)
        } catch (e: Throwable) {
            Log.e(TAG, "connect failed: ${e.message}")
            false
        }
    }

    /** Run a shell command; returns the stream wrapped as a Process.
     *
     *  We build the "shell:<cmd>" service string manually rather than using
     *  openStream(LocalServices.SHELL, …vararg). The vararg helper wraps any
     *  arg containing spaces in double quotes, which makes sh parse the whole
     *  command as a literal filename and fail with "inaccessible or not found".
     */
    fun runShell(ctx: Context, command: String): Process? {
        if (!ensureConnected(ctx)) {
            Log.w(TAG, "runShell: not connected")
            return null
        }
        return try {
            val stream: AdbStream = openStream("shell:$command")
            StreamProcess(stream)
        } catch (e: Throwable) {
            Log.e(TAG, "runShell(${command.take(80)}): ${e.javaClass.simpleName} ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "AdbManager"
        @Volatile private var INSTANCE: AdbConnectionManager? = null
        fun get(ctx: Context): AdbConnectionManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AdbConnectionManager(ctx.applicationContext).also { INSTANCE = it }
            }
    }
}

/** Adapts an AdbStream → Process so BridgeRunner treats all backends uniformly. */
private class StreamProcess(private val stream: AdbStream) : Process() {
    override fun getOutputStream() = stream.openOutputStream()
    override fun getInputStream() = stream.openInputStream()
    override fun getErrorStream() = stream.openInputStream()
    override fun waitFor(): Int {
        while (!stream.isClosed) Thread.sleep(100)
        return 0
    }
    override fun exitValue(): Int =
        if (stream.isClosed) 0 else throw IllegalThreadStateException()
    override fun destroy() { try { stream.close() } catch (_: Exception) {} }
}
