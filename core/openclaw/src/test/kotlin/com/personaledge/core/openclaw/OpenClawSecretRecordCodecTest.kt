package com.personaledge.core.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class OpenClawSecretRecordCodecTest {
    @Test
    fun `credential round trip is deterministic and origin bound`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val token = token("gateway-bearer-secret")

        val first = OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, token)
        val second = OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, token)
        val restored = OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, first)

        assertEquals(first, second)
        assertEquals("gateway-bearer-secret", restored?.value)
        assertFalse(first.contains(endpoint.url))
        assertFalse(first.contains("gateway-bearer-secret"))
    }

    @Test
    fun `server issued device token uses the same endpoint-bound credential record`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val record = OpenClawSecretRecordCodec.encodeGatewayCredential(
            endpoint,
            token("issued-device-token"),
        )

        assertEquals(
            "issued-device-token",
            OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, record)?.value,
        )
    }

    @Test
    fun `credential rejects cross-origin replay`() {
        val source = endpoint("wss://gateway.example.test/socket")
        val destination = endpoint("wss://other.example.test/socket")
        val record = OpenClawSecretRecordCodec.encodeGatewayCredential(source, token("secret"))

        assertNull(OpenClawSecretRecordCodec.restoreGatewayCredential(destination, record))
    }

    @Test
    fun `device identity round trip is deterministic and origin bound`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val identity = identity()

        val first = OpenClawSecretRecordCodec.encodeDeviceIdentity(endpoint, identity)
        val second = OpenClawSecretRecordCodec.encodeDeviceIdentity(endpoint, identity)
        val restored = OpenClawSecretRecordCodec.restoreDeviceIdentity(endpoint, first)

        assertEquals(first, second)
        assertNotNull(restored)
        assertEquals(identity.deviceId, restored?.deviceId)
        assertEquals(identity.publicKeyRawBase64, restored?.publicKeyRawBase64)
        assertEquals(identity.privateKeyPkcs8Base64, restored?.privateKeyPkcs8Base64)
        assertTrue(first.length <= 4_096)
        assertEquals(first.length, first.toByteArray(Charsets.UTF_8).size)
        assertFalse(first.contains(endpoint.url))
        assertFalse(first.contains(identity.privateKeyPkcs8Base64))
    }

    @Test
    fun `device identity rejects cross-origin replay`() {
        val source = endpoint("wss://gateway.example.test/socket")
        val destination = endpoint("wss://gateway.example.test/other")
        val record = OpenClawSecretRecordCodec.encodeDeviceIdentity(source, identity())

        assertNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(destination, record))
    }

    @Test
    fun `credential rejects truncation corruption and non-canonical base64url`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val record = OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, token("secret"))
        val invalidUtf8 = replaceField(record, "secret", "_w")
        val canonicalCorruption = replaceField(record, "secret", b64("secreu"))

        listOf(
            record.dropLast(1),
            record.replace("secret=", "secret=*"),
            "$record=",
            invalidUtf8,
            canonicalCorruption,
        ).forEach { candidate ->
            assertNull(candidate, OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, candidate))
        }
    }

    @Test
    fun `identity rejects truncated or corrupted key material`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val record = OpenClawSecretRecordCodec.encodeDeviceIdentity(endpoint, identity())
        val badPrivateKey = replaceFieldAndChecksum(record, "privateKey", b64("not-a-private-key"))
        val badPublicKey = replaceFieldAndChecksum(record, "publicKey", b64("not-a-public-key"))
        val mismatchedDeviceId = replaceFieldAndChecksum(
            record,
            "deviceId",
            b64("0".repeat(64)),
        )

        assertNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(endpoint, record.dropLast(1)))
        assertNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(endpoint, badPrivateKey))
        assertNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(endpoint, badPublicKey))
        assertNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(endpoint, mismatchedDeviceId))
    }

    @Test
    fun `identity rejects oversized and malformed adversarial records`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val record = OpenClawSecretRecordCodec.encodeDeviceIdentity(endpoint, identity())
        val oversizedKeyField = replaceFieldAndChecksum(
            record,
            "privateKey",
            b64("A".repeat(1_025)),
        )
        val nonCanonicalInnerBase64 = replaceFieldAndChecksum(
            record,
            "publicKey",
            b64(Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32))),
        )
        val oversizedRecord = "x".repeat(2_049)

        assertNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(endpoint, oversizedKeyField))
        assertNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(endpoint, nonCanonicalInnerBase64))
        assertNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(endpoint, oversizedRecord))
    }

    @Test
    fun `strict parser rejects duplicate wrong-order missing and trailing fields`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val record = OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, token("secret"))
        val parts = record.split('|')
        val duplicateOrigin = parts.dropLast(1).plus(parts[3]).joinToString("|")
        val wrongOrder = listOf(
            parts[0],
            parts[1],
            parts[2],
            parts[4],
            parts[3],
            parts[5],
        ).joinToString("|")

        listOf(
            duplicateOrigin,
            wrongOrder,
            parts.dropLast(1).joinToString("|"),
            "$record|extra=${b64("data")}",
            "$record|",
        ).forEach { candidate ->
            assertNull(candidate, OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, candidate))
        }
    }

    @Test
    fun `strict parser rejects wrong magic version kind and raw controls`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val credential = OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, token("secret"))
        val identity = OpenClawSecretRecordCodec.encodeDeviceIdentity(endpoint, identity())

        listOf(
            credential.replaceFirst("ocsr", "other"),
            credential.replaceFirst("|1|", "|2|"),
            credential.replaceFirst("|credential|", "|identity|"),
            "$credential\n",
            " $credential",
        ).forEach { candidate ->
            assertNull(candidate, OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, candidate))
        }
        assertNull(OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, identity))
        assertNull(OpenClawSecretRecordCodec.restoreDeviceIdentity(endpoint, credential))
    }

    @Test
    fun `strict parser rejects empty and malformed field assignments`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val record = OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, token("secret"))

        listOf(
            "",
            record.replace("origin=", "origin"),
            record.replace("secret=", "=secret="),
            record.replace("secret=", "secret=="),
            record.replaceAfter("secret=", ""),
        ).forEach { candidate ->
            assertNull(candidate, OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, candidate))
        }
    }

    @Test
    fun `encoding rejects credentials that exceed the utf8 byte limit`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val manyMultibyteCharacters = token("가".repeat(971))

        assertThrows(IllegalArgumentException::class.java) {
            OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, manyMultibyteCharacters)
        }
    }

    @Test
    fun `credential record fits the vault at the exact ascii payload boundary`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val payload = "x".repeat(2_910)

        val record = OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, token(payload))

        assertEquals(4_095, record.length)
        assertEquals(record.length, record.toByteArray(Charsets.UTF_8).size)
        assertEquals(
            payload,
            OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, record)?.value,
        )
    }

    @Test
    fun `credential rejects the first ascii payload that would exceed the vault`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")

        assertThrows(IllegalArgumentException::class.java) {
            OpenClawSecretRecordCodec.encodeGatewayCredential(
                endpoint,
                token("x".repeat(2_911)),
            )
        }
    }

    @Test
    fun `credential byte boundary is deterministic for multibyte payloads`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val exactPayload = "가".repeat(970)
        val oversizedPayload = exactPayload + "x"

        val record = OpenClawSecretRecordCodec.encodeGatewayCredential(
            endpoint,
            token(exactPayload),
        )

        assertEquals(4_095, record.length)
        assertEquals(
            exactPayload,
            OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, record)?.value,
        )
        assertThrows(IllegalArgumentException::class.java) {
            OpenClawSecretRecordCodec.encodeGatewayCredential(
                endpoint,
                token(oversizedPayload),
            )
        }
    }

    @Test
    fun `encoding rejects malformed unicode instead of replacing credential bytes`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val malformed = token("\uD800secret")

        assertThrows(IllegalArgumentException::class.java) {
            OpenClawSecretRecordCodec.encodeGatewayCredential(endpoint, malformed)
        }
    }

    @Test
    fun `decoding rejects oversized credential and record before materialization`() {
        val endpoint = endpoint("wss://gateway.example.test/socket")
        val oversizedSecret = b64("x".repeat(2_911))
        val oversizedField = "ocsr|1|credential|origin=${b64(endpoint.stableId)}|secret=$oversizedSecret"
        val oversizedRecord = "x".repeat(4_097)

        assertNull(OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, oversizedField))
        assertNull(OpenClawSecretRecordCodec.restoreGatewayCredential(endpoint, oversizedRecord))
    }

    @Test
    fun `toString representations do not disclose endpoint token or private key`() {
        val endpoint = endpoint("wss://private-host.example.test/secret-path")
        val token = token("super-secret-token")
        val identity = identity()

        val combined = listOf(
            OpenClawSecretRecordCodec.toString(),
            endpoint.toString(),
            token.toString(),
            identity.toString(),
        ).joinToString(" ")

        assertFalse(combined.contains(endpoint.url))
        assertFalse(combined.contains("private-host"))
        assertFalse(combined.contains("super-secret-token"))
        assertFalse(combined.contains(identity.privateKeyPkcs8Base64))
        assertEquals("OpenClawSecretRecordCodec(<redacted>)", OpenClawSecretRecordCodec.toString())
    }

    private fun endpoint(url: String): GatewayEndpoint = requireNotNull(
        GatewayEndpoint.parseRelease(url),
    )

    private fun token(value: String): OpenClawAuthToken = requireNotNull(
        OpenClawAuthToken.parse(value),
    )

    private fun identity(): OpenClawDeviceIdentity = requireNotNull(
        OpenClawDeviceIdentity.createForTest(ByteArray(32) { index -> (index + 1).toByte() }),
    )

    private fun b64(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun replaceField(record: String, field: String, replacement: String): String =
        record.replace(Regex("$field=[^|]+"), "$field=$replacement")

    private fun replaceFieldAndChecksum(
        record: String,
        field: String,
        replacement: String,
    ): String {
        val changed = replaceField(record, field, replacement)
        val unsigned = changed.substringBeforeLast("|checksum=")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(unsigned.toByteArray(Charsets.US_ASCII))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return replaceField(changed, "checksum", b64(digest))
    }
}
