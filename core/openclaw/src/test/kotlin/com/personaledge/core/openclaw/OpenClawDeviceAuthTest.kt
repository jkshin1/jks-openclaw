package com.personaledge.core.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class OpenClawDeviceAuthTest {
    @Test
    fun `identity restore rejects oversized and noncanonical base64 before use`() {
        val identity = requireNotNull(
            OpenClawDeviceIdentity.createForTest(ByteArray(32) { index -> index.toByte() }),
        )

        assertNull(
            OpenClawDeviceIdentity.restore(
                identity.deviceId,
                "A".repeat(65),
                identity.privateKeyPkcs8Base64,
            ),
        )
        assertNull(
            OpenClawDeviceIdentity.restore(
                identity.deviceId,
                identity.publicKeyRawBase64.removeSuffix("="),
                identity.privateKeyPkcs8Base64,
            ),
        )
        assertNull(
            OpenClawDeviceIdentity.restore(
                identity.deviceId,
                identity.publicKeyRawBase64,
                "A".repeat(129),
            ),
        )
    }

    @Test
    fun `canonical v3 fixture is byte exact`() {
        val payload = OpenClawDeviceAuth.buildPayloadV3(
            deviceId = "dev-1",
            clientId = "openclaw-macos",
            clientMode = "ui",
            role = "operator",
            scopes = listOf("operator.admin", "operator.read"),
            signedAtMillis = 1_700_000_000_000L,
            token = "tok-123",
            nonce = "nonce-abc",
            platform = " IOS ",
            deviceFamily = "iPHONE",
        )

        assertEquals(
            "v3|dev-1|openclaw-macos|ui|operator|operator.admin,operator.read|" +
                "1700000000000|tok-123|nonce-abc|ios|iphone",
            payload,
        )
        assertEquals(payload.length, payload.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `RFC seed signs official fixture deterministically with unpadded base64url`() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val identity = requireNotNull(OpenClawDeviceIdentity.createForTest(seed))
        val payload = "v3|dev-1|openclaw-macos|ui|operator|operator.admin,operator.read|" +
            "1700000000000|tok-123|nonce-abc|ios|iphone"
        val signature = OpenClawDeviceAuth.signPayload(identity, payload)

        assertEquals(
            "Wrp1kv2UpRb5ODGAmIevAUA6UZ09pc4PtuMHAtwNLOl8wZqmb1KPDLYfiXRDi_" +
                "Fscj1CLrj-3opHWPACZvrkDw",
            signature,
        )
        assertEquals(
            "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo",
            OpenClawDeviceAuth.publicKeyBase64Url(identity),
        )
        assertFalse('=' in signature)
        assertTrue(OpenClawDeviceAuth.verify(identity, payload, signature))
        assertFalse(OpenClawDeviceAuth.verify(identity, "$payload!", signature))
    }

    @Test
    fun `restoration verifies public private binding and redacts all key material`() {
        val identity = OpenClawDeviceAuth.generate()
        val restored = OpenClawDeviceIdentity.restore(
            identity.deviceId,
            identity.publicKeyRawBase64,
            identity.privateKeyPkcs8Base64,
        )

        assertNotNull(restored)
        assertFalse(identity.toString().contains(identity.deviceId))
        val wrongPublic = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        assertNull(
            OpenClawDeviceIdentity.restore(
                identity.deviceId,
                wrongPublic,
                identity.privateKeyPkcs8Base64,
            ),
        )
    }

    @Test
    fun `signed payload fields reject delimiters and control characters`() {
        val failure = runCatching {
            OpenClawDeviceAuth.buildPayloadV3(
                deviceId = "dev-1",
                role = "operator",
                scopes = listOf("operator.read"),
                signedAtMillis = 1L,
                token = "token",
                nonce = "nonce\nforged",
                platform = "android",
                deviceFamily = "phone",
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun hex(value: String): ByteArray = value.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
