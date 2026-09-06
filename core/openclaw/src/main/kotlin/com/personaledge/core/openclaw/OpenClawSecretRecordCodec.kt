package com.personaledge.core.openclaw

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/**
 * Versioned, origin-bound serialization for the two OpenClaw records stored in SecretVault.
 *
 * The returned strings are secrets and must never be logged. A record can only be restored when
 * the caller supplies the same canonical [GatewayEndpoint] that was used to create it. This keeps
 * a copied vault entry from becoming a generic credential that can be replayed to another origin.
 * The embedded checksum detects storage corruption; confidentiality and tamper resistance remain
 * the responsibility of SecretVault.
 */
object OpenClawSecretRecordCodec {
    /**
     * Encodes either the initial Gateway bearer token or a server-issued device token.
     *
     * The result is one deterministic String suitable for a single SecretVault record.
     */
    fun encodeGatewayCredential(
        endpoint: GatewayEndpoint,
        credential: OpenClawAuthToken,
    ): String {
        val secret = credential.value
        val secretBytes = encodeUtf8Strict(secret)
        require(secret.length <= MAX_CREDENTIAL_CHARS)
        require(secretBytes != null && secretBytes.size <= MAX_CREDENTIAL_UTF8_BYTES)
        return encode(
            kind = KIND_CREDENTIAL,
            fields = listOf(
                Field(FIELD_ORIGIN, endpoint.stableId),
                Field(FIELD_SECRET, secret),
            ),
        ).also(::requireCredentialRecordWithinLimits)
    }

    /** Restores a credential only when [storedRecord] is bound to [endpoint]. */
    fun restoreGatewayCredential(
        endpoint: GatewayEndpoint,
        storedRecord: String,
    ): OpenClawAuthToken? = runCatching {
        val fields = parse(
            storedRecord = storedRecord,
            expectedKind = KIND_CREDENTIAL,
            expectedFields = CREDENTIAL_FIELDS,
            maxRecordChars = MAX_RECORD_CHARS,
            maxRecordUtf8Bytes = MAX_RECORD_UTF8_BYTES,
        ) ?: return null
        if (!sameOrigin(fields.getValue(FIELD_ORIGIN), endpoint.stableId)) return null
        val secret = fields.getValue(FIELD_SECRET)
        if (secret.length > MAX_CREDENTIAL_CHARS ||
            secret.toByteArray(Charsets.UTF_8).size > MAX_CREDENTIAL_UTF8_BYTES
        ) {
            return null
        }
        OpenClawAuthToken.parse(secret)
    }.getOrNull()

    /** Encodes one endpoint-bound Ed25519 device identity for a single SecretVault record. */
    fun encodeDeviceIdentity(
        endpoint: GatewayEndpoint,
        identity: OpenClawDeviceIdentity,
    ): String {
        require(
            OpenClawDeviceIdentity.restore(
                deviceId = identity.deviceId,
                publicKeyRawBase64 = identity.publicKeyRawBase64,
                privateKeyPkcs8Base64 = identity.privateKeyPkcs8Base64,
            ) != null,
        )
        return encode(
            kind = KIND_IDENTITY,
            fields = listOf(
                Field(FIELD_ORIGIN, endpoint.stableId),
                Field(FIELD_DEVICE_ID, identity.deviceId),
                Field(FIELD_PUBLIC_KEY, identity.publicKeyRawBase64),
                Field(FIELD_PRIVATE_KEY, identity.privateKeyPkcs8Base64),
            ),
        ).also(::requireIdentityRecordWithinLimits)
    }

    /** Restores an identity only when [storedRecord] is bound to [endpoint]. */
    fun restoreDeviceIdentity(
        endpoint: GatewayEndpoint,
        storedRecord: String,
    ): OpenClawDeviceIdentity? = runCatching {
        val fields = parse(
            storedRecord = storedRecord,
            expectedKind = KIND_IDENTITY,
            expectedFields = IDENTITY_FIELDS,
            maxRecordChars = MAX_IDENTITY_RECORD_CHARS,
            maxRecordUtf8Bytes = MAX_IDENTITY_RECORD_UTF8_BYTES,
        ) ?: return null
        if (!sameOrigin(fields.getValue(FIELD_ORIGIN), endpoint.stableId)) return null
        val deviceId = fields.getValue(FIELD_DEVICE_ID)
        val publicKey = fields.getValue(FIELD_PUBLIC_KEY)
        val privateKey = fields.getValue(FIELD_PRIVATE_KEY)
        if (!STABLE_ID.matches(deviceId) ||
            decodeCanonicalBase64(publicKey, MAX_PUBLIC_KEY_BYTES)?.size != RAW_PUBLIC_KEY_BYTES ||
            decodeCanonicalBase64(privateKey, MAX_PRIVATE_KEY_BYTES) == null
        ) {
            return null
        }
        OpenClawDeviceIdentity.restore(
            deviceId = deviceId,
            publicKeyRawBase64 = publicKey,
            privateKeyPkcs8Base64 = privateKey,
        )
    }.getOrNull()

    override fun toString(): String = "OpenClawSecretRecordCodec(<redacted>)"

    private fun encode(kind: String, fields: List<Field>): String {
        val unsignedRecord = encodeUnsigned(kind, fields)
        return buildString(unsignedRecord.length + CHECKSUM_FIELD_OVERHEAD) {
            append(unsignedRecord)
            append(SEPARATOR)
            append(FIELD_CHECKSUM)
            append(ASSIGNMENT)
            append(encodeBase64Url(sha256Hex(unsignedRecord)))
        }
    }

    private fun encodeUnsigned(kind: String, fields: List<Field>): String = buildString {
        append(MAGIC)
        append(SEPARATOR)
        append(VERSION)
        append(SEPARATOR)
        append(kind)
        fields.forEach { field ->
            append(SEPARATOR)
            append(field.name)
            append(ASSIGNMENT)
            append(encodeBase64Url(field.value))
        }
    }

    private fun parse(
        storedRecord: String,
        expectedKind: String,
        expectedFields: List<FieldPolicy>,
        maxRecordChars: Int,
        maxRecordUtf8Bytes: Int,
    ): ParsedFields? {
        if (storedRecord.isEmpty() ||
            storedRecord.length > maxRecordChars ||
            storedRecord.toByteArray(Charsets.UTF_8).size > maxRecordUtf8Bytes ||
            storedRecord.any(Char::isISOControl) ||
            storedRecord.any { it.code !in PRINTABLE_ASCII_RANGE }
        ) {
            return null
        }
        val allFieldPolicies = expectedFields + CHECKSUM_POLICY
        val expectedPartCount = allFieldPolicies.size + HEADER_FIELD_COUNT
        val parts = splitPreservingEmpty(storedRecord, expectedPartCount)
            ?: return null
        if (parts.size != expectedPartCount ||
            parts[0] != MAGIC ||
            parts[1] != VERSION ||
            parts[2] != expectedKind
        ) {
            return null
        }

        val decoded = LinkedHashMap<String, String>(allFieldPolicies.size)
        allFieldPolicies.forEachIndexed { index, policy ->
            val part = parts[index + HEADER_FIELD_COUNT]
            val assignmentIndex = part.indexOf(ASSIGNMENT)
            if (assignmentIndex <= 0 ||
                assignmentIndex != part.lastIndexOf(ASSIGNMENT) ||
                part.substring(0, assignmentIndex) != policy.name ||
                decoded.containsKey(policy.name)
            ) {
                return null
            }
            val encodedValue = part.substring(assignmentIndex + 1)
            val value = decodeBase64Url(
                encodedValue = encodedValue,
                maxChars = policy.maxChars,
                maxUtf8Bytes = policy.maxUtf8Bytes,
            ) ?: return null
            if (value.any(Char::isISOControl)) return null
            decoded[policy.name] = value
        }
        val checksum = decoded[FIELD_CHECKSUM]
        if (checksum == null || !STABLE_ID.matches(checksum)) return null
        val unsignedRecord = encodeUnsigned(
            kind = expectedKind,
            fields = expectedFields.map { policy ->
                Field(policy.name, decoded.getValue(policy.name))
            },
        )
        if (!constantTimeAsciiEquals(checksum, sha256Hex(unsignedRecord))) return null
        decoded.remove(FIELD_CHECKSUM)
        return ParsedFields(decoded)
    }

    private fun splitPreservingEmpty(value: String, expectedParts: Int): List<String>? {
        val parts = ArrayList<String>(expectedParts)
        var start = 0
        value.forEachIndexed { index, character ->
            if (character == SEPARATOR) {
                if (parts.size >= expectedParts) return null
                parts += value.substring(start, index)
                start = index + 1
            }
        }
        parts += value.substring(start)
        return parts
    }

    private fun encodeBase64Url(value: String): String = BASE64_URL_ENCODER.encodeToString(
        requireNotNull(encodeUtf8Strict(value)),
    )

    private fun encodeUtf8Strict(value: String): ByteArray? = runCatching {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(encoded.remaining()).also(encoded::get)
    }.getOrNull()

    private fun decodeBase64Url(
        encodedValue: String,
        maxChars: Int,
        maxUtf8Bytes: Int,
    ): String? {
        if (encodedValue.isEmpty() ||
            encodedValue.length > maxEncodedLength(maxUtf8Bytes) ||
            !BASE64_URL_VALUE.matches(encodedValue)
        ) {
            return null
        }
        val bytes = runCatching { BASE64_URL_DECODER.decode(encodedValue) }.getOrNull()
            ?: return null
        if (bytes.isEmpty() || bytes.size > maxUtf8Bytes) return null
        val value = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        if (value.length > maxChars || encodeBase64Url(value) != encodedValue) return null
        return value
    }

    private fun sameOrigin(recordOrigin: String, expectedOrigin: String): Boolean {
        if (!STABLE_ID.matches(recordOrigin) || !STABLE_ID.matches(expectedOrigin)) return false
        return constantTimeAsciiEquals(recordOrigin, expectedOrigin)
    }

    private fun decodeCanonicalBase64(value: String, maxBytes: Int): ByteArray? {
        if (value.isEmpty() || value.length > maxEncodedLength(maxBytes) ||
            !BASE64_VALUE.matches(value)
        ) {
            return null
        }
        val bytes = runCatching { BASE64_DECODER.decode(value) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > maxBytes || BASE64_ENCODER.encodeToString(bytes) != value) {
            return null
        }
        return bytes
    }

    private fun constantTimeAsciiEquals(first: String, second: String): Boolean =
        MessageDigest.isEqual(
            first.toByteArray(Charsets.US_ASCII),
            second.toByteArray(Charsets.US_ASCII),
        )

    private fun sha256Hex(value: String): String {
        val digits = "0123456789abcdef"
        val result = CharArray(SHA256_HEX_CHARS)
        var index = 0
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.US_ASCII))
            .forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                result[index++] = digits[unsigned ushr 4]
                result[index++] = digits[unsigned and 0x0f]
            }
        return String(result)
    }

    private fun requireCredentialRecordWithinLimits(record: String) =
        requireRecordWithinLimits(record, MAX_RECORD_CHARS, MAX_RECORD_UTF8_BYTES)

    private fun requireIdentityRecordWithinLimits(record: String) =
        requireRecordWithinLimits(
            record,
            MAX_IDENTITY_RECORD_CHARS,
            MAX_IDENTITY_RECORD_UTF8_BYTES,
        )

    private fun requireRecordWithinLimits(record: String, maxChars: Int, maxUtf8Bytes: Int) {
        require(record.length <= maxChars)
        require(record.toByteArray(Charsets.UTF_8).size <= maxUtf8Bytes)
    }

    private fun maxEncodedLength(maxDecodedBytes: Int): Int = ((maxDecodedBytes + 2) / 3) * 4

    private class Field(val name: String, val value: String) {
        override fun toString(): String = "Field(name=$name, value=<redacted>)"
    }

    private class ParsedFields(private val values: Map<String, String>) {
        fun getValue(name: String): String = values.getValue(name)

        override fun toString(): String = "ParsedFields(values=<redacted>)"
    }

    private data class FieldPolicy(
        val name: String,
        val maxChars: Int,
        val maxUtf8Bytes: Int,
    )

    private const val MAGIC = "ocsr"
    private const val VERSION = "1"
    private const val KIND_CREDENTIAL = "credential"
    private const val KIND_IDENTITY = "identity"
    private const val FIELD_ORIGIN = "origin"
    private const val FIELD_SECRET = "secret"
    private const val FIELD_DEVICE_ID = "deviceId"
    private const val FIELD_PUBLIC_KEY = "publicKey"
    private const val FIELD_PRIVATE_KEY = "privateKey"
    private const val FIELD_CHECKSUM = "checksum"
    private const val SEPARATOR = '|'
    private const val ASSIGNMENT = '='
    private const val HEADER_FIELD_COUNT = 3
    /*
     * SecretVault stores at most 4,096 characters. This format has 215 fixed ASCII characters
     * around the unpadded base64url credential. 2,910 bytes encode to 3,880 characters for a
     * 4,095-character record; 2,911 bytes encode to 3,882 and would make a 4,097-character record.
     */
    private const val MAX_CREDENTIAL_CHARS = 2_910
    private const val MAX_CREDENTIAL_UTF8_BYTES = 2_910
    private const val MAX_RECORD_CHARS = 4_096
    private const val MAX_RECORD_UTF8_BYTES = 4_096
    private const val MAX_IDENTITY_RECORD_CHARS = 2_048
    private const val MAX_IDENTITY_RECORD_UTF8_BYTES = 2_048
    private const val SHA256_HEX_CHARS = 64
    private const val CHECKSUM_FIELD_OVERHEAD = 100
    private const val RAW_PUBLIC_KEY_BYTES = 32
    private const val MAX_PUBLIC_KEY_BYTES = 128
    private const val MAX_PRIVATE_KEY_BYTES = 1_024
    private val PRINTABLE_ASCII_RANGE = 0x21..0x7e
    private val BASE64_URL_VALUE = Regex("[A-Za-z0-9_-]+")
    private val BASE64_VALUE = Regex("[A-Za-z0-9+/]+={0,2}")
    private val STABLE_ID = Regex("[0-9a-f]{64}")
    private val BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val BASE64_URL_DECODER = Base64.getUrlDecoder()
    private val BASE64_ENCODER = Base64.getEncoder()
    private val BASE64_DECODER = Base64.getDecoder()
    private val CHECKSUM_POLICY = FieldPolicy(
        FIELD_CHECKSUM,
        maxChars = SHA256_HEX_CHARS,
        maxUtf8Bytes = SHA256_HEX_CHARS,
    )
    private val CREDENTIAL_FIELDS = listOf(
        FieldPolicy(FIELD_ORIGIN, maxChars = 64, maxUtf8Bytes = 64),
        FieldPolicy(
            FIELD_SECRET,
            maxChars = MAX_CREDENTIAL_CHARS,
            maxUtf8Bytes = MAX_CREDENTIAL_UTF8_BYTES,
        ),
    )
    private val IDENTITY_FIELDS = listOf(
        FieldPolicy(FIELD_ORIGIN, maxChars = 64, maxUtf8Bytes = 64),
        FieldPolicy(FIELD_DEVICE_ID, maxChars = 64, maxUtf8Bytes = 64),
        FieldPolicy(FIELD_PUBLIC_KEY, maxChars = 128, maxUtf8Bytes = 128),
        FieldPolicy(FIELD_PRIVATE_KEY, maxChars = 1_024, maxUtf8Bytes = 1_024),
    )
}
