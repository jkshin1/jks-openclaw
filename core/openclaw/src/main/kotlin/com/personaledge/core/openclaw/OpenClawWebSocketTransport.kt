package com.personaledge.core.openclaw

/** Minimal WebSocket seam; protocol code never receives throwable messages or HTTP bodies. */
interface OpenClawWebSocketTransport {
    fun open(endpoint: GatewayEndpoint, listener: Listener): Socket

    interface Socket {
        val queueSizeBytes: Long

        fun sendText(text: String): Boolean

        fun close(code: Int = 1_000): Boolean

        fun cancel()
    }

    interface Listener {
        fun onOpen()

        fun onText(text: String)

        fun onBinary(byteCount: Int)

        fun onClosing(code: Int)

        fun onClosed(code: Int)

        fun onFailure(kind: FailureKind)
    }

    enum class FailureKind {
        TLS,
        IO,
        PROTOCOL,
    }
}
