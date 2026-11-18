package app.ft.carplay

import android.net.Network
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A real, per-Network RTSP TCP lane, but not an AirPlay authentication engine.
 * Caller must independently verify iAP2 identity, an actual SRV/A/AAAA result,
 * and control consent. No fabricated 7000 port, default route, fake MAC, TLS
 * downgrade or direct vehicle writes. Not connected from production FT yet.
 */
internal object CarPlayAirPlayControlSocket {
    class Endpoint(
        val address: InetAddress,
        val port: Int,
        val discoveredFromSrv: Boolean
    ) {
        override fun toString(): String = "AirPlayEndpoint(address redacted, port redacted)"
    }

    enum class Failure {
        AUTH_NOT_VERIFIED, DISCOVERY_NOT_VERIFIED, INVALID_ENDPOINT,
        WRONG_NETWORK, MAIN_THREAD_FORBIDDEN, CONNECT_FAILED, REQUEST_INVALID,
        TRANSACTION_FAILED, MALFORMED_REPLY, REPLY_TOO_LARGE, CLOSED
    }

    sealed class Opening {
        data class Ready(val session: Session) : Opening()
        data class Blocked(val reason: Failure) : Opening()
    }
    sealed class Transaction {
        data class Complete(val reply: CarPlayRtspCodec.Reply) : Transaction()
        data class Failed(val reason: Failure) : Transaction()
    }

    /** No connection without BOTH actual authenticated iAP2 and SRV identity. */
    fun open(
        connectivity: ConnectivityManager,
        verifiedNetwork: Network?,
        endpoint: Endpoint?,
        realIap2Authentication: Boolean,
        initialCSeqFromPeerProfile: Int,
        connectTimeoutMs: Int = 5000,
        readTimeoutMs: Int = 5000
    ): Opening {
        if (!realIap2Authentication) return Opening.Blocked(Failure.AUTH_NOT_VERIFIED)
        if (endpoint == null || !endpoint.discoveredFromSrv)
            return Opening.Blocked(Failure.DISCOVERY_NOT_VERIFIED)
        if (endpoint.port !in 1..65535 ||
            (endpoint.address is Inet6Address &&
                endpoint.address.isLinkLocalAddress &&
                (endpoint.address as Inet6Address).scopeId <= 0) ||
            endpoint.address.isAnyLocalAddress || endpoint.address.isMulticastAddress ||
            endpoint.address.isLoopbackAddress || initialCSeqFromPeerProfile !in 1..Int.MAX_VALUE ||
            connectTimeoutMs !in 100..30000 || readTimeoutMs !in 100..30000)
            return Opening.Blocked(Failure.INVALID_ENDPOINT)
        if (Looper.myLooper() == Looper.getMainLooper())
            return Opening.Blocked(Failure.MAIN_THREAD_FORBIDDEN)
        if (verifiedNetwork == null || connectivity.getNetworkCapabilities(verifiedNetwork)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true)
            return Opening.Blocked(Failure.WRONG_NETWORK)

        var sock: Socket? = null
        try {
            sock = verifiedNetwork.socketFactory.createSocket()
            sock.soTimeout = readTimeoutMs
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(endpoint.address, endpoint.port), connectTimeoutMs)
            return Opening.Ready(Session(sock, initialCSeqFromPeerProfile))
        } catch (_: IOException) {
            runCatching { sock?.close() }
            return Opening.Blocked(Failure.CONNECT_FAILED)
        } catch (_: SecurityException) {
            runCatching { sock?.close() }
            return Opening.Blocked(Failure.CONNECT_FAILED)
        }
    }

    /**
     * Serialize one transaction at a time. Control request/response bytes
     * never pass through logs, even on error. Socket is released on malformed
     * or missing response to avoid routing the next reply to the wrong CSeq.
     */
    class Session internal constructor(
        private val socket: Socket,
        private var nextCSeq: Int
    ) : AutoCloseable {
        private var closed = false

        @Synchronized
        fun exchange(
            method: String,
            path: String,
            body: ByteArray = byteArrayOf(),
            contentType: String? = null
        ): Transaction {
            if (closed) return Transaction.Failed(Failure.CLOSED)
            if (Looper.myLooper() == Looper.getMainLooper())
                return Transaction.Failed(Failure.MAIN_THREAD_FORBIDDEN)
            if (nextCSeq == Int.MAX_VALUE) {
                close()
                return Transaction.Failed(Failure.TRANSACTION_FAILED)
            }
            val seq = nextCSeq++
            val bytes = CarPlayRtspCodec.request(method,path,seq,body,contentType)
                ?: return Transaction.Failed(Failure.REQUEST_INVALID)
            try {
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
                val buf = ByteArray(4096)
                val received = ByteArrayOutputStream()
                while (received.size() <= 2_100_000) {
                    val n = socket.getInputStream().read(buf)
                    if (n <= 0) break
                    received.write(buf,0,n)
                    when (val decoded = CarPlayRtspCodec.parseReply(received.toByteArray(),seq)) {
                        is CarPlayRtspCodec.Parse.Accepted ->
                            return Transaction.Complete(decoded.reply)
                        is CarPlayRtspCodec.Parse.Rejected -> {
                            close()
                            return Transaction.Failed(Failure.MALFORMED_REPLY)
                        }
                        CarPlayRtspCodec.Parse.NeedMore -> Unit
                    }
                }
                close()
                return Transaction.Failed(if (received.size() > 2_100_000)
                    Failure.REPLY_TOO_LARGE else Failure.TRANSACTION_FAILED)
            } catch (_: IOException) {
                close()
                return Transaction.Failed(Failure.TRANSACTION_FAILED)
            }
        }

        @Synchronized override fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
        }
    }
}
