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
        NO_IAP2_LINK_FRAME, LINK_ACK_NOT_OBSERVED,
        CONTROL_MESSAGE_OBSERVED, CARPLAY_START_SESSION_OBSERVED,
        CARPLAY_START_SESSION_MALFORMED, CARPLAY_START_SESSION_UNKNOWN_SCHEMA,
        IDENTIFICATION_REQUEST_SENT, IDENTIFICATION_UNSUPPORTED_CONTROL_VERSION,
        IDENTIFICATION_INFO_OBSERVED, IDENTIFICATION_INFO_EMPTY,
        IDENTIFICATION_INFO_UNKNOWN_PARAMETERS,
        LINK_DUPLICATE_IGNORED, LINK_GAP_IGNORED,
        LINK_UNEXPECTED_SESSION, IDENTIFICATION_REQUEST_ACKED,
        PROBE_TIMEOUT, IO_FAILURE, CANCELLED
    }

    data class Event(
        val status: Observation,
        val validLinkFrames: Int = 0,
        val messageId: Int? = null,
        val parameterCount: Int = 0
    )

    companion object {
        /**
         * Protocol identification lead from an independently studied public phone-side
         * reference (cfraz89/carjack Android Bluetooth.kt); must be verified with
         * physical Toyota before treating it as a supported interoperability profile.
         * NOT a fake MAC, MFi key or certificate.
         */
        const val PUBLIC_IAP2_DEVICE_UUID_CANDIDATE = "00000000-deca-fade-deca-deafdecacafe"
        private const val ACCEPT_TIMEOUT_MS = 15_000
        private const val PROBE_TIMEOUT_MS = 30_000L
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
    @Volatile private var generation = 0L
    private var timeout: Runnable? = null

    /**
     * No pairing, scans, HFP manipulation or calls to privileged OEM services.
     * Call from an owner-initiated diagnostic UI; never auto-connect.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun listen(
        selectedToyotaAddress: String,
        serviceUuid: String = PUBLIC_IAP2_DEVICE_UUID_CANDIDATE,
        requestIdentification: Boolean = false,
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
        val run = ++generation
        val deadline = Runnable {
            if (active.get() && generation == run) {
                emit(run, Observation.PROBE_TIMEOUT)
                close() // Closing RFCOMM unblocks a blocked physical Bluetooth read.
            }
        }
        timeout = deadline
        main.postDelayed(deadline, PROBE_TIMEOUT_MS)
        val job = Thread({
            emit(run, Observation.SDP_LISTENER_READY)
            try {
                val connection = serverSocket.accept(ACCEPT_TIMEOUT_MS)
                val admitted = synchronized(this@CarPlayIncomingIapProbe) {
                    if (active.get() && generation == run) {
                        session = connection
                        true
                    } else false
                }
                if (!admitted) {
                    runCatching { connection.close() }
                    return@Thread
                }
                if (!connection.remoteDevice.address.equals(target.address, ignoreCase = true) ||
                    connection.remoteDevice.bondState != BluetoothDevice.BOND_BONDED
                ) {
                    emit(run, Observation.PEER_NOT_SELECTED_TOYOTA)
                    return@Thread
                }
                emit(run, Observation.ACTUAL_TOYOTA_RFCOMM_CONNECTED)
                // A genuine link frame (not a fake ACK) is the first positive protocol
                // signal. A peer can still be incompatible or unauthenticated.
                val observer = Iap2PhoneWireObserver()
                val negotiator = Iap2LinkNegotiator()
                val control = Iap2ControlStreamParser()
                val identify = Iap2PhoneIdentificationProbe()
                var reliable: Iap2ReliableLinkWindow? = null
                val received = ByteArray(4096)
                // A first read might contain only a fragment; do not reject it
                // prematurely. Limit total data and receive operations.
                var bytesSeen = 0
                var found = false
                for (attempt in 0 until 64) {
                    if (!active.get() || generation != run || bytesSeen >= 65535) break
                    // read() may block; close() cancels by closing the socket.
                    val count = connection.inputStream.read(received)
                    if (count <= 0) break
                    bytesSeen += count
                    val event = observer.accept(received.copyOf(count))
                    if (event.detectSeen) {
                        emit(run, Observation.IAP2_DETECT_SEEN)
                        found = true
                        // A physical accessory's legacy iAP detect is echoed verbatim.
                        // This is a real response on the selected bonded RFCOMM socket,
                        // not an MFi response or a fabricated transport ACK.
                        connection.outputStream.write(IAP1_DETECT_REPLY)
                        connection.outputStream.flush()
                        emit(run, Observation.DETECT_ECHO_SENT)
                    }
                    if (event.linkFrameCount > 0) {
                        emit(run, Observation.REAL_LINK_FRAME_SEEN, event.linkFrameCount)
                        found = true
                        for (frame in event.frames) {
                            if (negotiator.phase == Iap2LinkNegotiator.Phase.LINK_NEGOTIATED) {
                                // The real accessory chose the CONTROL session ID in SYN.
                                // Do not interpret a random LINK or data frame as 0x4301.
                                val accepted = reliable?.observe(frame)
                                if (accepted?.ackOfOutstanding == true)
                                    emit(run, Observation.IDENTIFICATION_REQUEST_ACKED)
                                when (accepted?.status) {
                                    Iap2ReliableLinkWindow.Status.DUPLICATE ->
                                        emit(run, Observation.LINK_DUPLICATE_IGNORED)
                                    Iap2ReliableLinkWindow.Status.OUT_OF_ORDER ->
                                        emit(run, Observation.LINK_GAP_IGNORED)
                                    Iap2ReliableLinkWindow.Status.UNKNOWN_SESSION ->
                                        emit(run, Observation.LINK_UNEXPECTED_SESSION)
                                    else -> Unit
                                }
                                val controlSessionId = negotiator.sessions
                                    .firstOrNull { it.type == 0 }?.id
                                if (accepted?.status == Iap2ReliableLinkWindow.Status.ACCEPTED_IN_ORDER &&
                                    accepted.sessionId == controlSessionId &&
                                    accepted.payload != null
                                ) {
                                    for (message in control.accept(accepted.payload)) {
                                        // 0x1D01 carries potentially sensitive Toyota
                                        // identification values; observe only IDs/count.
                                        // Never auto-send 0x1D02 or promote to MFi PASS.
                                        val observation = if (message.id ==
                                            Iap2IdentificationInfoInspector.IDENTIFICATION_INFORMATION
                                        ) {
                                            when (Iap2IdentificationInfoInspector.inspect(message).result) {
                                                Iap2IdentificationInfoInspector.Result.NO_PARAMETERS ->
                                                    Observation.IDENTIFICATION_INFO_EMPTY
                                                Iap2IdentificationInfoInspector.Result.UNKNOWN_PARAMETER_IDS ->
                                                    Observation.IDENTIFICATION_INFO_UNKNOWN_PARAMETERS
                                                Iap2IdentificationInfoInspector.Result.STRUCTURE_OBSERVED ->
                                                    Observation.IDENTIFICATION_INFO_OBSERVED
                                                Iap2IdentificationInfoInspector.Result.NOT_IDENTIFICATION ->
                                                    Observation.CONTROL_MESSAGE_OBSERVED
                                            }
                                        } else if (message.id ==
                                            Iap2ControlSessionCodec.CARPLAY_START_SESSION
                                        ) {
                                            // Two-level 0x4301 schema must be validated
                                            // without exposing SSID, PSK or device ID.
                                            // Shape success is not MFi/Wi-Fi/AirPlay success.
                                            when (Iap2CarPlayStartSchema.inspect(message).result) {
                                                Iap2CarPlayStartSchema.Result.STRUCTURE_OBSERVED ->
                                                    Observation.CARPLAY_START_SESSION_OBSERVED
                                                Iap2CarPlayStartSchema.Result.UNKNOWN_ONLY ->
                                                    Observation.CARPLAY_START_SESSION_UNKNOWN_SCHEMA
                                                else -> Observation.CARPLAY_START_SESSION_MALFORMED
                                            }
                                        } else {
                                            Observation.CONTROL_MESSAGE_OBSERVED
                                        }
                                        emit(run, observation, messageId = message.id,
                                            parameterCount = message.parameters.size)
                                    }
                                }
                                continue
                            }
                            val negotiated = negotiator.onRealLinkFrame(frame)
                            if (negotiated.reply != null) {
                                connection.outputStream.write(negotiated.reply)
                                connection.outputStream.flush()
                                emit(run, Observation.LINK_SYN_ACK_SENT)
                            }
                            if (negotiated.phase == Iap2LinkNegotiator.Phase.LINK_NEGOTIATED) {
                                // LINK ACK is real wire evidence, but not MFi/Identify/Auth.
                                // Continue reading the actual negotiated CONTROL session.
                                emit(run, Observation.LINK_NEGOTIATED)
                                // Source-only reliability tracker: consume only
                                // fresh, in-order payload; reject duplicate/gap
                                // before reaching the sensitive TLV decoder.
                                reliable = negotiator.confirmedLink()
                                    ?.let { Iap2ReliableLinkWindow(it) }
                                if (requestIdentification && active.get() && generation == run) {
                                    val proposal = identify.prepare(negotiator.confirmedLink(), true)
                                    when (proposal.result) {
                                        Iap2PhoneIdentificationProbe.Result.REQUEST_PREPARED -> {
                                            // Only after real LINK ACK and explicit diagnostic opt-in.
                                            // This does NOT claim IdentityAccepted, MFi, or CarPlay.
                                            val controlId = proposal.controlSessionId ?: -1
                                            val message = Iap2ControlSessionCodec.encode(
                                                Iap2ControlSessionCodec.Message(0x1D00, emptyList())
                                            )
                                            val outgoing = reliable?.prepareData(controlId, message)
                                            // Both independently constructed
                                            // frames must be byte-for-byte the
                                            // same (true session/seq/ACK). Never
                                            // write a mismatched frame to Toyota.
                                            if (outgoing?.status ==
                                                Iap2ReliableLinkWindow.SendStatus.READY &&
                                                outgoing.wire != null &&
                                                outgoing.wire.contentEquals(proposal.frame)
                                            ) {
                                                connection.outputStream.write(outgoing.wire)
                                                connection.outputStream.flush()
                                                emit(run, Observation.IDENTIFICATION_REQUEST_SENT,
                                                    messageId = 0x1D00)
                                            } else {
                                                emit(run, Observation.IO_FAILURE)
                                                return@Thread
                                            }
                                        }
                                        Iap2PhoneIdentificationProbe.Result.UNSUPPORTED_CONTROL_VERSION ->
                                            emit(run, Observation.IDENTIFICATION_UNSUPPORTED_CONTROL_VERSION)
                                        else -> Unit
                                    }
                                }
                            }
                            if (negotiated.phase == Iap2LinkNegotiator.Phase.REJECTED) {
                                emit(run, Observation.LINK_REJECTED)
                                return@Thread
                            }
                        }
                    }
                }
                if (active.get() && generation == run) {
                    if (!found) emit(run, Observation.NO_IAP2_LINK_FRAME)
                    else if (negotiator.phase == Iap2LinkNegotiator.Phase.AWAITING_ACCESSORY_ACK)
                        emit(run, Observation.LINK_ACK_NOT_OBSERVED)
                }
            } catch (_: SocketTimeoutException) {
                if (active.get() && generation == run) emit(run, Observation.ACCEPT_TIMEOUT)
            } catch (_: IOException) {
                if (active.get() && generation == run) emit(run, Observation.IO_FAILURE)
            } catch (_: SecurityException) {
                if (active.get() && generation == run) emit(run, Observation.PERMISSION_MISSING)
            } catch (_: RuntimeException) {
                if (active.get() && generation == run) emit(run, Observation.IO_FAILURE)
            } finally {
                synchronized(this@CarPlayIncomingIapProbe) {
                    // An old listener must never close a newer owner-started probe.
                    if (generation == run) close()
                }
            }
        }, "ft200-phone-iap2-observation")
        job.isDaemon = true
        thread = job
        job.start()
        return Observation.SDP_LISTENER_READY
    }

    private fun emit(
        run: Long, status: Observation, frames: Int = 0,
        messageId: Int? = null, parameterCount: Int = 0
    ) {
        if (!active.get() || generation != run) return
        val callback = onEvent ?: return
        // Capture the result before close() cancels listening. Values in CONTROL
        // TLVs (including potential credentials) NEVER leave the parser here.
        main.post {
            // A queued callback from an OLD listen() must never update the UI
            // after a replacement probe has started. Permit terminal events
            // of a just-closed session when no replacement is active.
            if (generation == run || (generation == run + 1 && !active.get())) {
                callback(Event(status, frames, messageId, parameterCount))
            }
        }
    }

    @Synchronized
    override fun close() {
        generation++
        active.set(false)
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        val currentSession = session
        session = null
        val currentServer = server
        server = null
        onEvent = null
        runCatching { currentSession?.close() }
        runCatching { currentServer?.close() }
    }
}
