package com.personaledge.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawGatewaySettingsTest {
    @Test
    fun `defaults are disabled system trusted and foreground only`() {
        val settings = OpenClawGatewaySettings()

        assertFalse(settings.enabled)
        assertNull(settings.endpointUrl)
        assertEquals(OpenClawGatewayTrustMode.SYSTEM, settings.trustMode)
        assertNull(settings.leafCertificateDerSha256)
        assertTrue(settings.foregroundOnly)
    }

    @Test
    fun `valid system and pinned gateway settings are accepted`() {
        val system = OpenClawGatewaySettings(
            enabled = true,
            endpointUrl = "wss://personal-edge.example.test/openclaw",
        )
        val fingerprint = "A5".repeat(32)
        val pinned = OpenClawGatewaySettings(
            endpointUrl = "wss://personal-edge.example.test:8443/",
            trustMode = OpenClawGatewayTrustMode.PINNED_CERT_SHA256,
            leafCertificateDerSha256 = fingerprint,
        )

        assertTrue(system.enabled)
        assertEquals(fingerprint, pinned.leafCertificateDerSha256)
    }

    @Test
    fun `connection policy merge preserves latest consent`() {
        val current = OpenClawGatewaySettings(
            enabled = false,
            endpointUrl = "wss://personal-edge.example.test/original",
        )
        val staleSnapshotPolicy = OpenClawGatewayConnectionPolicy(
            endpointUrl = "wss://personal-edge.example.test/updated",
        )

        val merged = current.withConnectionPolicy(staleSnapshotPolicy)

        assertFalse(merged.enabled)
        assertEquals("wss://personal-edge.example.test/updated", merged.endpointUrl)
    }

    @Test
    fun `connection policy string never reveals endpoint or fingerprint`() {
        val endpoint = "wss://personal-edge.example.test/private-policy-path"
        val fingerprint = "bc".repeat(32)
        val rendered = OpenClawGatewayConnectionPolicy(
            endpointUrl = endpoint,
            trustMode = OpenClawGatewayTrustMode.PINNED_CERT_SHA256,
            leafCertificateDerSha256 = fingerprint,
        ).toString()

        assertFalse(rendered.contains(endpoint))
        assertFalse(rendered.contains(fingerprint))
        assertTrue(rendered.contains("endpointConfigured=true"))
        assertTrue(rendered.contains("leafFingerprintConfigured=true"))
    }

    @Test
    fun `endpoint rejects insecure ambiguous and oversized URLs`() {
        val invalidEndpoints = listOf(
            "ws://personal-edge.example.test/",
            "https://personal-edge.example.test/",
            "wss://owner@personal-edge.example.test/",
            "wss://personal-edge.example.test/?token=secret",
            "wss://personal-edge.example.test/#fragment",
            "wss://personal-edge.example.test/a/../b",
            " wss://personal-edge.example.test/",
            "wss://personal-edge.example.test/${"a".repeat(2_048)}",
        )

        invalidEndpoints.forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                OpenClawGatewaySettings(endpointUrl = endpoint)
            }
        }
    }

    @Test
    fun `enable pin and foreground invariants fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenClawGatewaySettings(enabled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenClawGatewaySettings(
                endpointUrl = "wss://personal-edge.example.test/",
                trustMode = OpenClawGatewayTrustMode.PINNED_CERT_SHA256,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenClawGatewaySettings(
                leafCertificateDerSha256 = "ab".repeat(32),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenClawGatewaySettings(
                endpointUrl = "wss://personal-edge.example.test/",
                trustMode = OpenClawGatewayTrustMode.PINNED_CERT_SHA256,
                leafCertificateDerSha256 = "not-a-fingerprint",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenClawGatewaySettings(foregroundOnly = false)
        }
    }

    @Test
    fun `string form never reveals endpoint or fingerprint`() {
        val endpoint = "wss://personal-edge.example.test/private-path"
        val fingerprint = "ab".repeat(32)
        val rendered = OpenClawGatewaySettings(
            enabled = true,
            endpointUrl = endpoint,
            trustMode = OpenClawGatewayTrustMode.PINNED_CERT_SHA256,
            leafCertificateDerSha256 = fingerprint,
        ).toString()

        assertFalse(rendered.contains(endpoint))
        assertFalse(rendered.contains(fingerprint))
        assertTrue(rendered.contains("endpointConfigured=true"))
        assertTrue(rendered.contains("leafFingerprintConfigured=true"))
    }

    @Test
    fun `gateway credential and device identity use separate record slots`() {
        val credential = SecretKeyName.OPENCLAW_GATEWAY_CREDENTIAL_RECORD
        val identity = SecretKeyName.OPENCLAW_DEVICE_IDENTITY_RECORD

        assertNotEquals(credential, identity)
        assertNotEquals(credential.fileName, identity.fileName)
        assertTrue(credential.fileName.contains("credential-record"))
        assertTrue(identity.fileName.contains("identity-record"))
    }
}
