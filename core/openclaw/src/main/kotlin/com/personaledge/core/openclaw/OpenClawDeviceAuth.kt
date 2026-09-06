package com.personaledge.core.openclaw

import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Serializable key material. Persistence belongs to the app's SecretVault integration. */
class OpenClawDeviceIdentity private constructor(
    val deviceId: String,
    val publicKeyRawBase64: String,
    val privateKeyPkcs8Base64: String,
) {
    override fun toString(): String = "OpenClawDeviceIdentity(<redacted>)"

    companion object {
        fun restore(
            deviceId: String,
            publicKeyRawBase64: String,
            privateKeyPkcs8Base64: String,
        ): OpenClawDeviceIdentity? = runCatching {
            if (!SHA256_ID.matches(deviceId) ||
                publicKeyRawBase64.length > MAX_PUBLIC_KEY_BASE64_CHARACTERS ||
                privateKeyPkcs8Base64.length > MAX_PRIVATE_KEY_BASE64_CHARACTERS
            ) {
                return null
            }
            val publicKey = BASE64_DECODER.decode(publicKeyRawBase64)
            val privateKey = BASE64_DECODER.decode(privateKeyPkcs8Base64)
            if (publicKey.size != Ed25519PublicKeyParameters.KEY_SIZE) return null
            if (BASE64_ENCODER.encodeToString(publicKey) != publicKeyRawBase64) return null
            if (BASE64_ENCODER.encodeToString(privateKey) != privateKeyPkcs8Base64) return null
            if (sha256Hex(publicKey) != deviceId) return null
            val rawPrivate = DEROctetString
                .getInstance(PrivateKeyInfo.getInstance(privateKey).parsePrivateKey())
                .octets
            if (rawPrivate.size != Ed25519PrivateKeyParameters.KEY_SIZE) return null
            val derivedPublic = Ed25519PrivateKeyParameters(rawPrivate, 0).generatePublicKey().encoded
            if (!MessageDigest.isEqual(publicKey, derivedPublic)) return null
            OpenClawDeviceIdentity(deviceId, publicKeyRawBase64, privateKeyPkcs8Base64)
        }.getOrNull()

        internal fun createForTest(rawPrivateKey: ByteArray): OpenClawDeviceIdentity? = runCatching {
            if (rawPrivateKey.size != Ed25519PrivateKeyParameters.KEY_SIZE) return null
            fromPrivateKey(Ed25519PrivateKeyParameters(rawPrivateKey.copyOf(), 0))
        }.getOrNull()

        internal fun fromPrivateKey(privateKey: Ed25519PrivateKeyParameters): OpenClawDeviceIdentity {
            val rawPublic = privateKey.generatePublicKey().encoded
            return OpenClawDeviceIdentity(
                deviceId = sha256Hex(rawPublic),
                publicKeyRawBase64 = BASE64_ENCODER.encodeToString(rawPublic),
                privateKeyPkcs8Base64 = BASE64_ENCODER.encodeToString(
                    PrivateKeyInfoFactory.createPrivateKeyInfo(privateKey).encoded,
                ),
            )
        }

        private val BASE64_ENCODER = Base64.getEncoder()
        private val BASE64_DECODER = Base64.getDecoder()
        private val SHA256_ID = Regex("[0-9a-f]{64}")
        private const val MAX_PUBLIC_KEY_BASE64_CHARACTERS = 64
        private const val MAX_PRIVATE_KEY_BASE64_CHARACTERS = 128
    }
}

/** Exact OpenClaw v3 challenge-bound device proof implementation. */
object OpenClawDeviceAuth {
    fun generate(random: SecureRandom = SecureRandom()): OpenClawDeviceIdentity {
        val generator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(random))
        }
        val privateKey = generator.generateKeyPair().private as Ed25519PrivateKeyParameters
        return OpenClawDeviceIdentity.fromPrivateKey(privateKey)
    }

    fun buildPayloadV3(
        deviceId: String,
        role: String,
        scopes: List<String>,
        signedAtMillis: Long,
        token: String?,
        nonce: String,
        platform: String?,
        deviceFamily: String?,
        clientId: String = OpenClawProtocol.CLIENT_ID,
        clientMode: String = OpenClawProtocol.CLIENT_MODE,
    ): String {
        require(deviceId.isSafeSignedField())
        require(role.isSafeSignedField())
        require(scopes.all { it.isSafeSignedField() && ',' !in it })
        require(signedAtMillis >= 0L)
        require(nonce.isSafeSignedField())
        require(clientId.isSafeSignedField())
        require(clientMode.isSafeSignedField())
        require(token == null || token.isSafeSignedField())
        require(platform == null || platform.isSafeOptionalSignedMetadata())
        require(deviceFamily == null || deviceFamily.isSafeOptionalSignedMetadata())
        return listOf(
            "v3",
            deviceId,
            clientId,
            clientMode,
            role,
            scopes.joinToString(","),
            signedAtMillis.toString(),
            token.orEmpty(),
            nonce,
            normalizeMetadata(platform),
            normalizeMetadata(deviceFamily),
        ).joinToString("|")
    }

    fun signPayload(identity: OpenClawDeviceIdentity, payload: String): String {
        val privateBytes = Base64.getDecoder().decode(identity.privateKeyPkcs8Base64)
        val rawPrivate = DEROctetString
            .getInstance(PrivateKeyInfo.getInstance(privateBytes).parsePrivateKey())
            .octets
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(rawPrivate, 0))
        }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        signer.update(payloadBytes, 0, payloadBytes.size)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.generateSignature())
    }

    fun publicKeyBase64Url(identity: OpenClawDeviceIdentity): String = Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(Base64.getDecoder().decode(identity.publicKeyRawBase64))

    internal fun verify(
        identity: OpenClawDeviceIdentity,
        payload: String,
        signatureBase64Url: String,
    ): Boolean = runCatching {
        val publicBytes = Base64.getDecoder().decode(identity.publicKeyRawBase64)
        val signature = Base64.getUrlDecoder().decode(signatureBase64Url)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(publicBytes, 0))
        }
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        verifier.update(payloadBytes, 0, payloadBytes.size)
        verifier.verifySignature(signature)
    }.getOrDefault(false)

    internal fun normalizeMetadata(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return buildString(trimmed.length) {
            trimmed.forEach { character ->
                append(if (character in 'A'..'Z') character + 32 else character)
            }
        }
    }
}

private fun String.isSafeSignedField(): Boolean =
    isNotBlank() && '|' !in this && none(Char::isISOControl)

private fun String.isSafeOptionalSignedMetadata(): Boolean =
    '|' !in this && none(Char::isISOControl)

private fun sha256Hex(data: ByteArray): String {
    val digits = "0123456789abcdef"
    val result = CharArray(64)
    var index = 0
    MessageDigest.getInstance("SHA-256").digest(data).forEach { byte ->
        val value = byte.toInt() and 0xff
        result[index++] = digits[value ushr 4]
        result[index++] = digits[value and 0x0f]
    }
    return String(result)
}
