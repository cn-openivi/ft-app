package app.ft.carplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in, inbound PHONE-SIDE Bluetooth probe for the already-bonded Toyota.
 *
 * Unlike an outbound RFCOMM client, this uses Android's real local adapter SDP
 * service and waits for the head unit to dial it. Bluetooth identity is never
 * generated or altered. This does not implement MFi auth or claim CarPlay ACTIVE.
 *
 * This is research/diagnostics only: never called by CarConnection, nor by startup.
 * The test UUID is a PUBLICLY DESCRIBED phone-side iAP candidate, NOT an observed
 * FAW Toyota compatible service or a proven standard requirement.
 */
internal class CarPlayIncomingIapProbe(private val context: Context) : AutoCloseable {
    enum class Observation {
        PERMISSION_MISSING, ADAPTER_MISSING, ADAPTER_OFF, INVALID_ADDRESS,
        NOT_BONDED, ALREADY_LISTENING, SDP_LISTENER_READY, ACCEPT_TIMEOUT,
        ACTUAL_TOYOTA_RFCOMM_CONNECTED, PEER_NOT_SELECTED_TOYOTA,
        IAP2_DETECT_SEEN, DETECT_ECHO_SENT, REAL_LINK_FRAME_SEEN,
        LINK_SYN_ACK_SENT, LINK_NEGOTIATED, LINK_REJECTED,
        NO_IAP2_LINK_FRAME, IO_FAILURE,
        CANCELLED
    }

    data class Event(val status: Observation, val validLinkFrames: Int = 0)

    companion object {
        /**
         * Protocol identification lead from an independently studied public phone-side
         * reference (cfraz89/carjack Android Bluetooth.kt); must be verified with
         * physical Toyota before treating it as a supported interoperability profile.
         * NOT a fake MAC, MFi key or certificate.
         */
        const val PUBLIC_IAP2_DEVICE_UUID_CANDIDATE = "00000000-deca-fade-deca-deafdecacafe"
        private const val ACCEPT_TIMEOUT_MS = 15_000
        private val IAP1_DETECT_REPLY = byteArrayOf(
            0xff.toByte(), 0x55, 0x02, 0x00, 0xee.toByte(), 0x10
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var session: BluetoothSocket? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var onEvent: ((Event) -> Unit)? = null

    /**
     * No pairing, scans, HFP manipulation or calls to privileged OEM services.
     * Call from an owner-initiated diagnostic UI; never auto-connect.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun listen(
        selectedToyotaAddress: String,
        serviceUuid: String = PUBLIC_IAP2_DEVICE_UUID_CANDIDATE,
        callback: (Event) -> Unit
    ): Observation {
        if (active.get()) return Observation.ALREADY_LISTENING
        val permission = if (Build.VERSION.SDK_INT >= 31)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            return Observation.PERMISSION_MISSING
        if (!BluetoothAdapter.checkBluetoothAddress(selectedToyotaAddress))
            return Observation.INVALID_ADDRESS
        val parsedUuid = runCatching { UUID.fromString(serviceUuid) }.getOrNull()
            ?: return Observation.INVALID_ADDRESS
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return Observation.ADAPTER_MISSING
        val target = try {
            if (!adapter.isEnabled) return Observation.ADAPTER_OFF
            adapter.bondedDevices.firstOrNull {
                it.address.equals(selectedToyotaAddress, ignoreCase = true) &&
                    it.bondState == BluetoothDevice.BOND_BONDED
            } ?: return Observation.NOT_BONDED
        } catch (_: SecurityException) {
            return Observation.PERMISSION_MISSING
        } catch (_: RuntimeException) {
            return Observation.IO_FAILURE
        }
        val serverSocket = try {
            adapter.listenUsingRfcommWithServiceRecord("Wireless iAP", parsedUuid)
        } catch (_: SecurityException) {
            return Observation.PERMISSION_MISSING
        } catch (_: IOException) {
            return Observation.IO_FAILURE
        } catch (_: RuntimeException) {
            return Observation.IO_FAILURE
        }
        onEvent = callback
        active.set(true)
        server = serverSocket
        val job = Thread({
            emit(Observation.SDP_LISTENER_READY)
            try {
                val connection = serverSocket.accept(ACCEPT_TIMEOUT_MS)
                session = connection
                if (!active.get()) return@Thread
                if (!connection.remoteDevice.address.equals(target.address, ignoreCase = true) ||
                    connection.remoteDevice.bondState != BluetoothDevice.BOND_BONDED
                ) {
                    emit(Observation.PEER_NOT_SELECTED_TOYOTA)
                    return@Thread
                }
                emit(Observation.ACTUAL_TOYOTA_RFCOMM_CONNECTED)
                // A genuine link frame (not a fake ACK) is the first positive protocol
                // signal. A peer can still be incompatible or unauthenticated.
                val observer = Iap2PhoneWireObserver()
                val negotiator = Iap2LinkNegotiator()
                val received = ByteArray(4096)
                // A first read might contain only a fragment; do not reject it
                // prematurely. Limit total data and receive operations.
                var bytesSeen = 0
                var found = false
                for (attempt in 0 until 16) {
                    if (!active.get() || bytesSeen >= 65535) break
                    // read() may block; close() cancels by closing the socket.
                    val count = connection.inputStream.read(received)
                    if (count <= 0) break
                    bytesSeen += count
                    val event = observer.accept(received.copyOf(count))
                    if (event.detectSeen) {
                        emit(Observation.IAP2_DETECT_SEEN)
                        found = true
                        // A physical accessory's legacy iAP detect is echoed verbatim.
                        // This is a real response on the selected bonded RFCOMM socket,
                        // not an MFi response or a fabricated transport ACK.
                        connection.outputStream.write(IAP1_DETECT_REPLY)
                        connection.outputStream.flush()
                        emit(Observation.DETECT_ECHO_SENT)
                    }
                    if (event.linkFrameCount > 0) {
                        emit(Observation.REAL_LINK_FRAME_SEEN, event.linkFrameCount)
                        found = true
                        for (frame in event.frames) {
                            val negotiated = negotiator.onRealLinkFrame(frame)
                            if (negotiated.reply != null) {
                                connection.outputStream.write(negotiated.reply)
                                connection.outputStream.flush()
                                emit(Observation.LINK_SYN_ACK_SENT)
                            }
                            if (negotiated.phase == Iap2LinkNegotiator.Phase.LINK_NEGOTIATED) {
                                emit(Observation.LINK_NEGOTIATED)
                                return@Thread
                            }
                            if (negotiated.phase == Iap2LinkNegotiator.Phase.REJECTED) {
                                emit(Observation.LINK_REJECTED)
                                return@Thread
                            }
                        }
                    }
                }
                if (!found && active.get()) emit(Observation.NO_IAP2_LINK_FRAME)
            } catch (_: SocketTimeoutException) {
                if (active.get()) emit(Observation.ACCEPT_TIMEOUT)
            } catch (_: IOException) {
                if (active.get()) emit(Observation.IO_FAILURE)
            } catch (_: SecurityException) {
                if (active.get()) emit(Observation.PERMISSION_MISSING)
            } catch (_: RuntimeException) {
                if (active.get()) emit(Observation.IO_FAILURE)
            } finally {
                close()
            }
        }, "ft200-phone-iap2-observation")
        job.isDaemon = true
        thread = job
        job.start()
        return Observation.SDP_LISTENER_READY
    }

    private fun emit(status: Observation, frames: Int = 0) {
        if (!active.get()) return
        val callback = onEvent ?: return
        // Capture the result before finally/close() cancels listening. Otherwise
        // all successful observations disappear from the UI as the socket closes.
        main.post { callback(Event(status, frames)) }
    }

    @Synchronized
    override fun close() {
        active.set(false)
        val currentSession = session
        session = null
        val currentServer = server
        server = null
        onEvent = null
        runCatching { currentSession?.close() }
        runCatching { currentServer?.close() }
    }
}
