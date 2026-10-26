package io.github.samolego.canta.core

import android.content.Context
import android.os.Build
import io.github.rhythmcache.adb.AdbClient
import io.github.rhythmcache.adb.FileKeyProvider
import io.github.rhythmcache.adb.ShellChunk
import io.github.samolego.canta.core.adb.AdbCantaHandler
import io.github.samolego.canta.core.adb.AdbTransport
import io.github.samolego.canta.core.adb.ShellCommandException
import io.github.samolego.canta.core.adb.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/** ADB wireless-debugging connection on this Android device. */
class AndroidWirelessAdbHandler private constructor(
    private val adbTransport: AndroidAdbTransport,
    context: Context,
) : AdbCantaHandler(adbTransport) {
    constructor(context: Context) : this(AndroidAdbTransport(), context.applicationContext)

    private val keyProvider = FileKeyProvider(
        File(context.noBackupFilesDir, "wireless-adb/adbkey"),
        identityComment = "Canta Wireless Debugging",
    )

    override val isConnected: Boolean
        get() = adbTransport.client != null

    var lastWirelessError: String? = null
        private set

    suspend fun pairAndConnect(host: String, pairingPort: Int, pairingCode: String, connectPort: Int): Boolean =
        withContext(Dispatchers.IO) {
            lastWirelessError = null
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                lastWirelessError = "يتطلب التصحيح اللاسلكي عبر TLS إصدار Android 11 أو أحدث."
                return@withContext false
            }
            if (host.isBlank() || pairingPort !in 1..65535 || connectPort !in 1..65535 || !pairingCode.matches(Regex("\\d{6}"))) {
                lastWirelessError = "أدخل عنوان IP والمنفذين ورمز الاقتران المكوّن من 6 أرقام."
                return@withContext false
            }
            runCatching {
                AdbClient.pairTls(host.trim(), pairingPort, pairingCode, keyProvider).getOrThrow()
                val connected = AdbClient.connectTls(host.trim(), connectPort, keyProvider)
                adbTransport.client?.close()
                adbTransport.client = connected
                if (onConnected()) {
                    loadApps()
                    true
                } else {
                    lastWirelessError = lastConnectionFailure?.toString() ?: "تعذّر تجهيز مساعد Canta على الجهاز."
                    adbTransport.client?.close()
                    adbTransport.client = null
                    false
                }
            }.getOrElse { error ->
                adbTransport.client?.close()
                adbTransport.client = null
                if (error is CancellationException) throw error
                lastWirelessError = error.message ?: error.toString()
                false
            }
        }

    fun disconnectWireless() {
        adbTransport.client?.close()
        adbTransport.client = null
    }
}

private class AndroidAdbTransport : AdbTransport {
    @Volatile
    var client: AdbClient? = null

    override suspend fun shell(command: String): ShellResult {
        val result = requireClient().shell(command)
        return ShellResult(result.exitCode, result.stdoutText, result.stderrText)
    }

    override fun shellLines(command: String): Flow<String> = flow {
        val pending = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = 0
        requireClient().shellFlow(command).collect { chunk ->
            when (chunk) {
                is ShellChunk.Stdout -> {
                    pending.append(chunk.text)
                    var newline = pending.indexOf("\n")
                    while (newline >= 0) {
                        emit(pending.substring(0, newline).removeSuffix("\r"))
                        pending.delete(0, newline + 1)
                        newline = pending.indexOf("\n")
                    }
                }
                is ShellChunk.Stderr -> stderr.append(chunk.text)
                is ShellChunk.Exit -> exitCode = chunk.code
            }
        }
        if (pending.isNotEmpty()) emit(pending.toString())
        if (exitCode != 0) throw ShellCommandException(command, exitCode, stderr.toString())
    }.flowOn(Dispatchers.IO)

    override suspend fun push(bytes: ByteArray, remotePath: String, mode: Int) {
        requireClient().sync.push(
            ByteArrayInputStream(bytes),
            remotePath,
            mode,
            (System.currentTimeMillis() / 1000L).toInt(),
        )
    }

    private fun requireClient(): AdbClient = client ?: error("Wireless debugging is not connected")
}
