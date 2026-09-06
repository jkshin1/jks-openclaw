package com.personaledge.core.agent

import com.personaledge.core.llm.TurnId
import com.personaledge.core.tools.ReminderCreateTool
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-owned association with one confirmed, tool-free remote terminal response. Construct this
 * from the actual connection/run receipt, never from model-supplied metadata or restored history.
 * It is transient: importing a transcript cannot recreate execution authority after restart.
 */
class RemoteReminderProposalSource private constructor(
    endpointSha256: String,
    runId: RemoteAgentRunId,
    internal val responseSha256: String,
    val receivedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    /** Same endpoint/run always maps to the same durable local turn, even if its text changes. */
    internal val requiredLocalTurnId = TurnId(
        "remote-reminder-" + remoteProposalDigest(listOf("turn-v1", endpointSha256, runId.value)),
    )
    val bindingSha256: String = remoteProposalDigest(listOf(
        "source-v1", endpointSha256, runId.value, responseSha256,
        receivedAtEpochMillis.toString(), expiresAtEpochMillis.toString(),
    ))
    private val parsed = AtomicBoolean(false)

    internal fun isCurrent(nowEpochMillis: Long): Boolean = nowEpochMillis > 0L &&
        nowEpochMillis >= receivedAtEpochMillis - MAX_FUTURE_SKEW_MILLIS &&
        nowEpochMillis < expiresAtEpochMillis

    internal fun consumeParsing(): Boolean = parsed.compareAndSet(false, true)

    override fun toString(): String = "RemoteReminderProposalSource(binding=<redacted>)"

    companion object {
        const val MAX_LIFETIME_MILLIS: Long = 120_000L
        const val MAX_RESPONSE_UTF8_BYTES: Int = 4_096
        private const val MAX_FUTURE_SKEW_MILLIS = 5_000L
        private val digestPattern = Regex("[a-f0-9]{64}")

        fun create(
            endpointSha256: String,
            runId: RemoteAgentRunId,
            responseText: String,
            receivedAtEpochMillis: Long,
            lifetimeMillis: Long = MAX_LIFETIME_MILLIS,
        ): RemoteReminderProposalSource? {
            if (!digestPattern.matches(endpointSha256) ||
                lifetimeMillis !in 1_000L..MAX_LIFETIME_MILLIS ||
                receivedAtEpochMillis !in 1L..(Long.MAX_VALUE - lifetimeMillis)
            ) return null
            val bounded = RemoteAgentPrompt.create(responseText) ?: return null
            if (bounded.utf8Bytes > MAX_RESPONSE_UTF8_BYTES) return null
            return RemoteReminderProposalSource(
                endpointSha256 = endpointSha256,
                runId = runId,
                responseSha256 = remoteProposalDigest(listOf(responseText)),
                receivedAtEpochMillis = receivedAtEpochMillis,
                expiresAtEpochMillis = receivedAtEpochMillis + lifetimeMillis,
            )
        }
    }
}

/**
 * One immutable parsed proposal. JSON canonicalization fixes field order/escaping and preserves
 * every supplied string exactly. The existing Tool still owns semantic canonicalization and the
 * final confirmation preview. No model-authored preview, permit, risk, or ledger key is accepted.
 */
class RemoteReminderProposal internal constructor(
    private val source: RemoteReminderProposalSource,
    val canonicalArgumentsJson: String,
) {
    val version: Int = 1
    val toolName: String = ReminderCreateTool.NAME
    val requiredLocalTurnId: TurnId = source.requiredLocalTurnId
    val sourceBindingSha256: String = source.bindingSha256
    val expiresAtEpochMillis: Long = source.expiresAtEpochMillis
    val argumentsSha256: String = remoteProposalDigest(listOf(canonicalArgumentsJson))
    private val consumed = AtomicBoolean(false)

    internal fun matches(source: RemoteReminderProposalSource): Boolean = this.source === source
    internal fun isCurrent(nowEpochMillis: Long): Boolean = source.isCurrent(nowEpochMillis)
    internal fun consumeExecution(): Boolean = consumed.compareAndSet(false, true)

    override fun toString(): String =
        "RemoteReminderProposal(version=$version, tool=$toolName, source=<redacted>, arguments=<redacted>)"
}

enum class RemoteReminderProposalRejection {
    SOURCE_MISMATCH,
    EXPIRED,
    MALFORMED_ENVELOPE,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_TOOL,
    INVALID_ARGUMENTS,
    REPLAYED,
}

sealed interface RemoteReminderProposalParseResult {
    data class Accepted(val proposal: RemoteReminderProposal) : RemoteReminderProposalParseResult
    data class Rejected(val reason: RemoteReminderProposalRejection) : RemoteReminderProposalParseResult
}

/** Strict flat string-only envelope; it contains one reminder, never a plan or arbitrary Tool. */
object RemoteReminderProposalParser {
    private val metadataFields = setOf("version", "tool")
    private val argumentFields = setOf(
        "title", "trigger_at", "zone_id", "recurrence_rule", "precision",
        "lead_time_minutes", "escalation_policy",
    )
    private val requiredFields = metadataFields + setOf("title", "trigger_at", "zone_id")
    internal const val MAX_ARGUMENT_UTF8_BYTES = 2_048

    fun parse(
        responseText: String,
        source: RemoteReminderProposalSource,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): RemoteReminderProposalParseResult {
        if (responseText.length > RemoteReminderProposalSource.MAX_RESPONSE_UTF8_BYTES ||
            remoteProposalDigest(listOf(responseText)) != source.responseSha256
        ) return rejected(RemoteReminderProposalRejection.SOURCE_MISMATCH)
        if (!source.isCurrent(nowEpochMillis)) return rejected(RemoteReminderProposalRejection.EXPIRED)
        val fields = when (val parsed = StrictToolArgumentsReader(
            RemoteReminderProposalSource.MAX_RESPONSE_UTF8_BYTES,
        ).read(responseText, metadataFields + argumentFields, requiredFields)) {
            is FlatFieldsResult.Valid -> parsed.fields
            is FlatFieldsResult.Invalid -> return rejected(RemoteReminderProposalRejection.MALFORMED_ENVELOPE)
        }
        if (fields["version"] != "1") return rejected(RemoteReminderProposalRejection.UNSUPPORTED_VERSION)
        if (fields["tool"] != ReminderCreateTool.NAME) {
            return rejected(RemoteReminderProposalRejection.UNSUPPORTED_TOOL)
        }
        val arguments = encodeRemoteReminderArguments(fields.filterKeys(argumentFields::contains))
        if (ReminderCreateArgumentsParser(MAX_ARGUMENT_UTF8_BYTES).parse(arguments)
            !is ToolArgumentsParseResult.Valid
        ) return rejected(RemoteReminderProposalRejection.INVALID_ARGUMENTS)
        if (!source.consumeParsing()) return rejected(RemoteReminderProposalRejection.REPLAYED)
        return RemoteReminderProposalParseResult.Accepted(RemoteReminderProposal(source, arguments))
    }

    private fun rejected(reason: RemoteReminderProposalRejection) =
        RemoteReminderProposalParseResult.Rejected(reason)
}

private fun encodeRemoteReminderArguments(fields: Map<String, String>): String =
    fields.toSortedMap().entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
        (key, value) -> "${remoteProposalQuote(key)}:${remoteProposalQuote(value)}"
    }

private fun remoteProposalQuote(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

private fun remoteProposalDigest(fields: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (field in fields) {
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
    return buildString(64) {
        for (byte in digest.digest()) {
            val unsigned = byte.toInt() and 0xff
            append("0123456789abcdef"[unsigned ushr 4])
            append("0123456789abcdef"[unsigned and 0x0f])
        }
    }
}
