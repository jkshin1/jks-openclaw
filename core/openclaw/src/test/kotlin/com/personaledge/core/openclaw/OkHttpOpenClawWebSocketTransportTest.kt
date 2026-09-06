package com.personaledge.core.openclaw

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OkHttpOpenClawWebSocketTransportTest {
    @Test
    fun `leaf DER fingerprint parser is exact case insensitive and redacted`() {
        val pin = OpenClawLeafCertificateSha256.parse(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
        )

        assertNotNull(pin)
        assertTrue(requireNotNull(pin).matches("abc".toByteArray()))
        assertTrue(pin.toString().contains("<redacted>"))
        assertNull(OpenClawLeafCertificateSha256.parse("ab".repeat(31)))
        assertNull(OpenClawLeafCertificateSha256.parse("zz".repeat(32)))
    }

    @Test
    fun `concrete transport exchanges text over bounded test-only loopback websocket`() {
        val server = MockWebServer()
        val serverSocket = AtomicReference<WebSocket>()
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.set(webSocket)
                        webSocket.send("server-ready")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("echo:$text")
                    }
                },
            ).build(),
        )
        server.start()
        val client = OkHttpOpenClawWebSocketTransport.defaultClient()
        try {
            val opened = CountDownLatch(1)
            val received = CountDownLatch(2)
            val texts = mutableListOf<String>()
            val endpoint = GatewayEndpoint.parseLoopbackForTest(
                server.url("/socket").toString().replaceFirst("http://", "ws://"),
            )
            assertNotNull(endpoint)
            val socket = OkHttpOpenClawWebSocketTransport.withClientForTest(client).open(
                requireNotNull(endpoint),
                object : OpenClawWebSocketTransport.Listener {
                    override fun onOpen() {
                        opened.countDown()
                    }

                    override fun onText(text: String) {
                        synchronized(texts) { texts += text }
                        received.countDown()
                    }

                    override fun onBinary(byteCount: Int) = Unit

                    override fun onClosing(code: Int) = Unit

                    override fun onClosed(code: Int) = Unit

                    override fun onFailure(kind: OpenClawWebSocketTransport.FailureKind) = Unit
                },
            )

            assertTrue(opened.await(5, TimeUnit.SECONDS))
            assertTrue(socket.sendText("client-frame"))
            assertTrue(received.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("server-ready", "echo:client-frame"), synchronized(texts) { texts.toList() })
            assertTrue(socket.close())
        } finally {
            serverSocket.get()?.close(1_000, "")
            server.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
