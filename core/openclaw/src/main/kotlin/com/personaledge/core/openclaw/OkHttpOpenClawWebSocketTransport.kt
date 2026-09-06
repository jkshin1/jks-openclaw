package com.personaledge.core.openclaw

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.security.cert.CertificateException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** OkHttp 5 transport using platform certificate and hostname verification. Redirects are off. */
class OkHttpOpenClawWebSocketTransport private constructor(
    private val client: OkHttpClient = defaultClient(),
    private val leafCertificatePin: OpenClawLeafCertificateSha256?,
) : OpenClawWebSocketTransport {
    constructor(
        leafCertificatePin: OpenClawLeafCertificateSha256? = null,
    ) : this(defaultClient(), leafCertificatePin)

    override fun open(
        endpoint: GatewayEndpoint,
        listener: OpenClawWebSocketTransport.Listener,
    ): OpenClawWebSocketTransport.Socket {
        val request = Request.Builder().url(endpoint.url).build()
        val webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val pin = leafCertificatePin
                    if (pin != null) {
                        val leafDer = runCatching {
                            response.handshake?.peerCertificates?.firstOrNull()?.encoded
                        }.getOrNull()
                        if (leafDer == null || !pin.matches(leafDer)) {
                            webSocket.cancel()
                            listener.onFailure(OpenClawWebSocketTransport.FailureKind.TLS)
                            return
                        }
                    }
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                    listener.onBinary(bytes.size)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosing(code)
                    webSocket.close(code, "")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    listener.onClosed(code)

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) = listener.onFailure(classify(t, response))
            },
        )
        return SocketAdapter(webSocket)
    }

    private class SocketAdapter(private val delegate: WebSocket) :
        OpenClawWebSocketTransport.Socket {
        override val queueSizeBytes: Long
            get() = delegate.queueSize()

        override fun sendText(text: String): Boolean = delegate.send(text)

        override fun close(code: Int): Boolean = delegate.close(code, "")

        override fun cancel() = delegate.cancel()
    }

    companion object {
        internal fun withClientForTest(
            client: OkHttpClient,
            leafCertificatePin: OpenClawLeafCertificateSha256? = null,
        ): OkHttpOpenClawWebSocketTransport =
            OkHttpOpenClawWebSocketTransport(client, leafCertificatePin)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(OpenClawProtocol.CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(0L, TimeUnit.MILLISECONDS)
            .writeTimeout(OpenClawProtocol.WRITE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .pingInterval(OpenClawProtocol.PING_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        private fun classify(
            failure: Throwable,
            response: Response?,
        ): OpenClawWebSocketTransport.FailureKind = when {
            response != null -> OpenClawWebSocketTransport.FailureKind.PROTOCOL
            failure is SSLPeerUnverifiedException ||
                failure is SSLHandshakeException ||
                failure is SSLException ||
                failure is CertificateException -> OpenClawWebSocketTransport.FailureKind.TLS
            failure is IOException -> OpenClawWebSocketTransport.FailureKind.IO
            else -> OpenClawWebSocketTransport.FailureKind.PROTOCOL
        }
    }
}

/** Exact SHA-256 over the peer leaf certificate's DER encoding, rendered as 64 hex digits. */
class OpenClawLeafCertificateSha256 private constructor(
    private val expected: ByteArray,
) {
    internal fun matches(leafDer: ByteArray): Boolean = MessageDigest.isEqual(
        expected,
        MessageDigest.getInstance("SHA-256").digest(leafDer),
    )

    override fun toString(): String = "OpenClawLeafCertificateSha256(<redacted>)"

    companion object {
        fun parse(hex: String): OpenClawLeafCertificateSha256? {
            if (!HEX.matches(hex)) return null
            val decoded = ByteArray(32) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            return OpenClawLeafCertificateSha256(decoded)
        }

        private val HEX = Regex("[0-9A-Fa-f]{64}")
    }
}
