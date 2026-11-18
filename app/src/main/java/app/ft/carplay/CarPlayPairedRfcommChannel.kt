package app.ft.carplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import java.io.IOException
import java.util.UUID

/**
 * FT200.2 public-API phone-side RFCOMM connector for ONE already paired physical Toyota.
 *
 * A real RFCOMM socket is a *necessary transport primitive in some topologies*,
 * NOT proof that the HU exposes an iAP2 SERVICE, accepts CarPlay, or will preserve HFP.
 * This client mode must ONLY be called with a verified, Toyota-advertised iAP2 service
 * UUID observed from fresh SDP; it will not invent a UUID or run generic SDP as iAP2.
 *
 * Some OEM systems instead initiate a phone-side RFCOMM server connection and require
 * vendor EIR/SDP fields unavailable to an ordinary Android app. In that case this
 * connector is not a workaround and the FT200.2 capability must remain BLOCKED.
 *
 * No bond changes, MAC cloning, MFi keys, reflection, profile manipulation or HU writes.
 * Call open/receive from a dedicated IO thread; never the Android UI thread.
 */
internal class CarPlayPairedRfcommChannel(private val context: Context) : AutoCloseable {
    enum class Result {
        CONNECTED_RFCOMM_ONLY, PERMISSION_MISSING, NO_ADAPTER, ADAPTER_OFF,
        INVALID_IDENTITY, NOT_BONDED, NOT_IN_FRESH_SDP,
        IAP2_SERVICE_NOT_VERIFIED, MAIN_THREAD_FORBIDDEN,
        CONNECT_FAILED, NOT_CONNECTED, IO_FAILURE
    }

    @Volatile private var socket: BluetoothSocket? = null
    private val decoder = Iap2LinkStreamParser()

    /**
     * Caller supplies an exact UUID returned by SDP; no local service is advertised.
     * verifiedIap2Service must come from independent OEM/profile evidence, not a guess.
     */
    @SuppressLint("MissingPermission")
    fun open(
        remoteToyotaAddress: String,
        verifiedServiceUuid: String,
        freshSdpUuids: Collection<String>,
        verifiedIap2Service: Boolean
    ): Result {
        close()
        if (!verifiedIap2Service) return Result.IAP2_SERVICE_NOT_VERIFIED
        if (Looper.myLooper() == Looper.getMainLooper()) return Result.MAIN_THREAD_FORBIDDEN
        val permission = if (Build.VERSION.SDK_INT >= 31)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            return Result.PERMISSION_MISSING
        if (!BluetoothAdapter.checkBluetoothAddress(remoteToyotaAddress))
            return Result.INVALID_IDENTITY
        val uuid = runCatching { UUID.fromString(verifiedServiceUuid) }
            .getOrNull() ?: return Result.IAP2_SERVICE_NOT_VERIFIED
        if (freshSdpUuids.none { it.equals(uuid.toString(), ignoreCase = true) })
            return Result.NOT_IN_FRESH_SDP
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return Result.NO_ADAPTER
        return try {
            if (!adapter.isEnabled) return Result.ADAPTER_OFF
            val paired = adapter.bondedDevices.firstOrNull {
                it.address.equals(remoteToyotaAddress, ignoreCase = true)
            } ?: return Result.NOT_BONDED
            val opened = paired.createRfcommSocketToServiceRecord(uuid)
            // Publish before the blocking connect so close() can interrupt an in-flight
            // Android RFCOMM connect without waiting on the channel monitor.
            socket = opened
            try {
                opened.connect() // real secure Bluetooth socket, no mock callback
                if (!opened.isConnected || socket !== opened) {
                    runCatching { opened.close() }
                    if (socket === opened) close()
                    return Result.CONNECT_FAILED
                }
                Result.CONNECTED_RFCOMM_ONLY
            } catch (_: IOException) {
                if (socket === opened) close() else runCatching { opened.close() }
                Result.CONNECT_FAILED
            } catch (_: SecurityException) {
                if (socket === opened) close() else runCatching { opened.close() }
                Result.PERMISSION_MISSING
            }
        } catch (_: SecurityException) {
            Result.PERMISSION_MISSING
        } catch (_: RuntimeException) {
            Result.CONNECT_FAILED
        }
    }

    /** Read physical bytes and decode iAP2 link frames; no synthetic protocol ACK. */
    fun receiveOnce(): Pair<Result, List<Iap2LinkFrameCodec.Frame>> {
        if (Looper.myLooper() == Looper.getMainLooper())
            return Result.MAIN_THREAD_FORBIDDEN to emptyList()
        val active = socket ?: return Result.NOT_CONNECTED to emptyList()
        val buffer = ByteArray(4096)
        return try {
            val count = active.inputStream.read(buffer)
            if (count <= 0) {
                close()
                Result.IO_FAILURE to emptyList()
            } else {
                Result.CONNECTED_RFCOMM_ONLY to decoder.accept(buffer.copyOf(count))
            }
        } catch (_: IOException) {
            close()
            Result.IO_FAILURE to emptyList()
        } catch (_: RuntimeException) {
            close()
            Result.IO_FAILURE to emptyList()
        }
    }

    /** Only already encoded, structurally valid iAP2 link frames may be transmitted. */
    fun sendLinkFrame(bytes: ByteArray): Result {
        if (Looper.myLooper() == Looper.getMainLooper()) return Result.MAIN_THREAD_FORBIDDEN
        if (Iap2LinkFrameCodec.decode(bytes) == null) return Result.IO_FAILURE
        val active = socket ?: return Result.NOT_CONNECTED
        return try {
            active.outputStream.write(bytes)
            active.outputStream.flush()
            Result.CONNECTED_RFCOMM_ONLY
        } catch (_: IOException) {
            close()
            Result.IO_FAILURE
        } catch (_: RuntimeException) {
            close()
            Result.IO_FAILURE
        }
    }

    @Synchronized
    override fun close() {
        val active = socket
        socket = null
        decoder.reset()
        if (active != null) runCatching { active.close() }
    }
}
