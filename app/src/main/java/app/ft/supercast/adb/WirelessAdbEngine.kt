package app.ft.supercast.adb

import android.content.Context
import app.ft.core.DiagLog
import app.ft.supercast.Helper
import io.github.rhythmcache.adb.AdbConnection
import io.github.rhythmcache.adb.FileKeyProvider
import io.github.rhythmcache.adb.pairing.PairingClient
import io.github.rhythmcache.adb.pairing.PeerInfoBuilder
import io.github.rhythmcache.adb.pairing.PeerInfoType
import io.github.rhythmcache.adb.pairing.buildPairingIdentity
import io.github.rhythmcache.adb.runShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest

/**
 * Local phone ADB transport. Pairing and adbd connect have distinct server
 * certificates. Persist only the SPAKE2-authenticated device GUID at pairing.
 * The connect server's public key is pinned AFTER shell UID2000 and the
 * authenticated SuperCastProvider Binder ping; never from pairing X509 DER.
 */
internal object WirelessAdbEngine {
    private const val HOST = "127.0.0.1"
    private const val KEY_FILE = "ft_adb_tls.key"
    private const val GUID_FILE = "ft_adb_tls.peer.guid"
    private const val CONNECT_KEY_FILE = "ft_adb_tls.connect.spki.sha256"
    private const val TAG = "SuperCast"

    enum class Stage {
        IDLE, PAIRING_CODE_ACCEPTED, PAIRING_VERIFIED, CONNECT_DISCOVERY,
        CONNECT_SOCKET, TCP_CONNECTED, STLS_ACKNOWLEDGED, TLS_VERIFIED,
        ADB_READY, SHELL_UID_VERIFIED, HELPER_DISPATCHED, BINDER_READY
    }

    @Volatile var stage = Stage.IDLE
        private set
    @Volatile private var pendingConnectKeySha256: ByteArray? = null
    @Volatile private var pendingGuid: String? = null

    internal fun markStage(next: Stage) {
        stage = next
        DiagLog.i(TAG, "ADB_TLS_STAGE=" + next.name)
    }

    fun sanitizedFailure(error: Throwable): String {
        var cause = error
        repeat(5) { cause = cause.cause ?: return@repeat }
        return "ADB_TLS_FAILURE stage=" + stage.name +
            " exception=" + error.javaClass.simpleName +
            " root=" + cause.javaClass.simpleName
    }

    fun hasCertificateMismatch(error: Throwable): Boolean {
        var cur: Throwable? = error
        repeat(8) {
            if (cur is java.security.cert.CertificateException) return true
            cur = cur?.cause
        }
        return false
    }

    private fun keyFile(context: Context) = File(context.filesDir, KEY_FILE)
    private fun guidFile(context: Context) = File(context.filesDir, GUID_FILE)
    private fun connectKeyFile(context: Context) = File(context.filesDir, CONNECT_KEY_FILE)
    private fun provider(context: Context) =
        FileKeyProvider(keyFile(context), identityComment = "FT")

    private fun File.readBytesOrEmpty(): ByteArray =
        runCatching { if (isFile) readBytes() else byteArrayOf() }.getOrDefault(byteArrayOf())

    fun pairedGuid(context: Context): String? {
        val value = runCatching { guidFile(context).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        return value?.takeIf { AdbServiceIdentity.isValidGuid(it) }
    }

    fun isPaired(context: Context): Boolean = keyFile(context).isFile && pairedGuid(context) != null

    private fun saveBytes(target: File, data: ByteArray): Boolean {
        val tmp = File(target.parentFile, target.name + ".pending")
        return runCatching {
            tmp.writeBytes(data)
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            if (!tmp.renameTo(target)) error("Cannot save ADB private state")
            true
        }.getOrElse { tmp.delete(); false }
    }

    /** The password is transient. Pairing server X509 is never used as the connect-server pin. */
    suspend fun pair(context: Context, pairingPort: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        require(code.length == 6 && code.all(Char::isDigit))
        require(pairingPort in 1..65535)
        markStage(Stage.PAIRING_CODE_ACCEPTED)
        val store = provider(context)
        val result = withTimeout(12_000L) {
            PairingClient.pair(
                host = HOST, port = pairingPort,
                password = code.toByteArray(Charsets.US_ASCII),
                ourPeerInfo = PeerInfoBuilder.forOurKeyProvider(store),
                identity = buildPairingIdentity(store),
                connectTimeoutMs = 8_000
            )
        }
        if (result.isFailure) return@withContext false
        val peerInfo = result.getOrThrow().peerInfo
        if (peerInfo.type != PeerInfoType.ADB_DEVICE_GUID) return@withContext false
        val guid = PeerInfoBuilder.extractDeviceGuid(peerInfo)
            .takeIf { AdbServiceIdentity.isValidGuid(it) } ?: return@withContext false
        // Explicitly approved new pairing authorizes rebinding the device, but
        // not bypassing later TLS verification or creating an unencrypted path.
        if (!saveBytes(guidFile(context), guid.toByteArray(Charsets.US_ASCII))) return@withContext false
        connectKeyFile(context).delete()
        pendingConnectKeySha256 = null
        pendingGuid = null
        markStage(Stage.PAIRING_VERIFIED)
        true
    }

    /** Current port and GUID are resolved independently; never trust an old port. */
    suspend fun startHelper(context: Context, connectionPort: Int, token: String): Boolean =
        withContext(Dispatchers.IO) {
            require(connectionPort in 1..65535)
            val guid = pairedGuid(context) ?: return@withContext false
            val pinned = connectKeyFile(context).readBytesOrEmpty()
            if (pinned.isNotEmpty() && pinned.size != 32) return@withContext false
            val apk = Helper.apkPath(context) ?: return@withContext false
            if (!token.matches(Regex("[a-f0-9]{48}"))) return@withContext false
            pendingConnectKeySha256 = null
            pendingGuid = null
            val keys = provider(context)
            val identity = buildPairingIdentity(keys)
            markStage(Stage.CONNECT_SOCKET)
            val transport = PinnedTlsPacketTransport.connect(
                HOST, connectionPort, identity, pinned.takeIf { it.size == 32 }
            )
            pendingConnectKeySha256 = transport.connectPublicKeySha256.copyOf()
            pendingGuid = guid
            val connection = try {
                AdbConnection.connect(transport, keys, handshakeTimeoutMs = 8_000, skipInitialCnxn = true)
            } catch (e: Exception) {
                transport.close()
                throw e
            }
            connection.use { conn ->
                markStage(Stage.ADB_READY)
                // Do not log command output. Shell UID must be 2000.
                val probe = withTimeout(8_000L) { conn.runShell("id -u") }
                if (!probe.isSuccess || probe.stdoutText.trim() != "2000") return@withContext false
                markStage(Stage.SHELL_UID_VERIFIED)
                val command = "env CLASSPATH=" + shellQuote(apk) + " app_process / " +
                    Helper.MAIN + " token=" + token
                val launch = "sh -c " + shellQuote("nohup " + command +
                    " >/data/local/tmp/ft-helper.log 2>&1 </dev/null &")
                val launched = withTimeout(8_000L) { conn.runShell(launch) }.isSuccess
                if (launched) markStage(Stage.HELPER_DISPATCHED)
                launched
            }
        }

    /**
     * Called ONLY after SuperCastProvider checked caller UID2000+token and
     * its returned live Binder answered ping. This is a local verified-session
     * trust-on-first-use pin of adbd CONNECT SPKI, not the pairing TLS cert.
     */
    @Synchronized fun commitVerifiedConnect(context: Context): Boolean {
        val pending = pendingConnectKeySha256 ?: return false
        val guid = pendingGuid ?: return false
        if (pending.size != 32 || pairedGuid(context) != guid) return false
        val file = connectKeyFile(context)
        val previous = file.readBytesOrEmpty()
        if (previous.isNotEmpty() &&
            (previous.size != 32 || !MessageDigest.isEqual(previous, pending))) return false
        val saved = if (previous.isEmpty()) saveBytes(file, pending) else true
        if (saved) {
            pendingConnectKeySha256 = null
            pendingGuid = null
            markStage(Stage.BINDER_READY)
        }
        return saved
    }

    private fun shellQuote(raw: String) = "'" + raw.replace("'", "'\\''") + "'"
}
