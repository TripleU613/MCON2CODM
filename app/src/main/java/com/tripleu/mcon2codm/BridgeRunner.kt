package com.tripleu.mcon2codm

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import rikka.shizuku.Shizuku

/**
 * Extracts and executes the native mcon_bridge binary via one of three
 * privilege backends: ADB_WIFI (embedded wireless ADB), SHIZUKU, or ROOT.
 *
 * /dev/uhid and /dev/input/event* require group 3011 (uhid) / 1004 (input),
 * not granted to regular apps by Android — hence the need for a shell-level
 * backend.
 */
object BridgeRunner {
    private const val TAG = "BridgeRunner"
    private const val BIN_NAME = "mcon_bridge"
    const val SHIZUKU_REQUEST_CODE = 1001

    enum class Backend { NONE, ADB_WIFI, SHIZUKU, ROOT }

    @Volatile private var process: Process? = null
    @Volatile var lastOutput: String = ""
        private set

    fun isRunning(): Boolean = process?.isAlive == true

    /** Best available backend in priority order. */
    fun backend(ctx: Context): Backend {
        try {
            val mgr = AdbConnectionManager.get(ctx)
            if (mgr.isConnected) return Backend.ADB_WIFI
        } catch (_: Throwable) {}
        try {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) return Backend.SHIZUKU
        } catch (_: Throwable) {}
        val suPaths = arrayOf("/system/xbin/su", "/system/bin/su", "/su/bin/su", "/sbin/su")
        if (suPaths.any { File(it).exists() }) return Backend.ROOT
        return Backend.NONE
    }

    fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (_: Throwable) {}
    }

    /**
     * Return a shell-exec-friendly absolute path to the bridge binary.
     *
     * The jniLibs extraction path (/data/app/~~XXX==/pkg-YYY==/lib/arm64/...)
     * contains "~~" and "==" which /system/bin/sh chokes on when the command
     * comes through libadb-android's TLS shell channel — it rejects the path
     * as "inaccessible or not found" even though it exists and has 0755 perms.
     *
     * Workaround: hand the binary off through the app's external files dir
     * (writable by us, readable by shell user via the sdcard group), then have
     * the shell side copy it to /data/local/tmp/ once.
     */
    fun ensureBinary(ctx: Context): File {
        val staged = File("/data/local/tmp/mcon_bridge")
        val verFile = File(ctx.filesDir, "staged_v2.ver")
        val currentVer = ctx.packageManager
            .getPackageInfo(ctx.packageName, 0).longVersionCode.toString()

        // Source file: the jniLibs-extracted binary (guaranteed readable by us).
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val nativeBin = File(nativeDir, "libmcon_bridge.so")

        // Fast path: already staged this version.
        if (verFile.readTextOrEmpty() == currentVer) {
            return staged
        }

        // Stage via /sdcard handoff (app-writable, shell-readable).
        val handoff = ctx.getExternalFilesDir(null)?.let {
            File(it, "mcon_bridge").also { f ->
                try {
                    nativeBin.inputStream().use { ins ->
                        FileOutputStream(f).use { outs -> ins.copyTo(outs) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "handoff copy failed: ${e.message}")
                    return nativeBin  // fall back; probably won't work but worth a try
                }
            }
        } ?: return nativeBin

        // Ask shell to copy it to /data/local/tmp/ and chmod +x.
        val copyCmd = "cp '${handoff.absolutePath}' '${staged.absolutePath}' && " +
                      "chmod 755 '${staged.absolutePath}' && echo DONE"
        val p = execShell(ctx, copyCmd)
        if (p != null) {
            val reply = readAllTolerant(p.inputStream).trim()
            try { p.waitFor() } catch (_: Exception) {}
            if (reply.endsWith("DONE")) {
                verFile.writeText(currentVer)
                Log.i(TAG, "Staged binary to ${staged.absolutePath}")
                return staged
            }
            Log.w(TAG, "Stage failed; shell said: '$reply'")
        }
        return nativeBin
    }

    /**
     * Push the binary to a location shell can exec. The app's files dir is
     * typically not accessible from shell context, so we stage at /data/local/tmp/.
     */
    private fun stageBinary(ctx: Context): String {
        val bin = ensureBinary(ctx)
        val stagedPath = "/data/local/tmp/mcon_bridge"
        // Copy via shell since app can't write /data/local/tmp on non-rooted Android.
        val copyCmd = "cp ${bin.absolutePath} $stagedPath && chmod +x $stagedPath && echo OK"
        val p = execShell(ctx, copyCmd) ?: return bin.absolutePath
        p.inputStream.bufferedReader().readText()
        p.waitFor()
        return stagedPath
    }

    fun listDevices(ctx: Context): List<Pair<String, String>> {
        val bin = ensureBinary(ctx).absolutePath
        Log.i(TAG, "listDevices via $bin --list (backend=${backend(ctx)})")
        val p = execShell(ctx, "$bin --list") ?: run {
            Log.w(TAG, "listDevices: execShell returned null")
            return emptyList()
        }
        val output = readAllTolerant(p.inputStream)
        try { p.waitFor() } catch (_: Exception) {}
        Log.i(TAG, "listDevices: raw=${output.replace('\t', ' ').replace('\n', '|')}")
        val parsed = output.lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toList()
        Log.i(TAG, "listDevices: parsed ${parsed.size} nodes")
        return parsed
    }

    /**
     * Read every byte available before the stream's "Stream closed" barrier.
     * AdbStream throws once its recv-queue is empty AND the remote has closed,
     * even if bytes were already buffered — so drain + recover instead of
     * letting readText blow up.
     */
    private fun readAllTolerant(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            try {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            } catch (e: Exception) {
                // "Stream closed" after we've read some data is effectively EOF.
                if (e.message?.contains("closed", ignoreCase = true) != true) {
                    Log.w(TAG, "readAllTolerant: ${e.javaClass.simpleName} ${e.message}")
                }
                break
            }
        }
        return out.toString("UTF-8")
    }

    fun start(ctx: Context, devicePath: String): Result<Unit> {
        if (isRunning()) return Result.success(Unit)
        val bin = ensureBinary(ctx).absolutePath
        val p = execShell(ctx, "$bin --device $devicePath")
            ?: return Result.failure(IllegalStateException("No backend available"))
        process = p
        Thread {
            try {
                p.inputStream.bufferedReader().useLines { seq ->
                    val sb = StringBuilder()
                    for (line in seq) {
                        Log.i(TAG, "[bridge] $line")
                        sb.appendLine(line)
                        lastOutput = sb.takeLast(2000).toString()
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
        return Result.success(Unit)
    }

    fun stop(ctx: Context) {
        process?.destroy()
        try { execShell(ctx, "pkill -TERM -f mcon_bridge")?.waitFor() } catch (_: Exception) {}
        process = null
    }

    /** Run a shell command via the best available backend. */
    private fun execShell(ctx: Context, command: String): Process? =
        when (backend(ctx)) {
            Backend.ADB_WIFI -> AdbConnectionManager.get(ctx).runShell(ctx, command)
            Backend.SHIZUKU  -> shizukuShell(command)
            Backend.ROOT     -> rootShell(command)
            Backend.NONE     -> null
        }

    private fun shizukuShell(command: String): Process? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        m.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
    } catch (e: Throwable) { Log.e(TAG, "shizukuShell: ${e.message}"); null }

    private fun rootShell(command: String): Process? = try {
        ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    } catch (e: Exception) { Log.e(TAG, "rootShell: ${e.message}"); null }

    private fun File.readTextOrEmpty(): String =
        if (exists()) runCatching { readText() }.getOrDefault("") else ""
}
