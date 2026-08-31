package com.personaledge.core.tools

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

data class CommitmentProposalParams(
    val summary: String,
    val proposedAt: String? = null,
    val zoneId: String? = null,
) : ToolParams

data class CommitmentProposalWriteRequest(
    val summary: String,
    val proposedDueAtEpochMillis: Long?,
    val zoneId: String?,
    val sourceRefHash: String,
)

enum class CommitmentProposalWriteOutcome {
    SAVED,
    ALREADY_HANDLED,
    CAPACITY_REACHED,
    REJECTED,
}

data class CommitmentProposalResult(val outcome: CommitmentProposalWriteOutcome)

fun interface CommitmentProposalGateway {
    suspend fun offer(request: CommitmentProposalWriteRequest): CommitmentProposalWriteOutcome
}

/** Stores a review-only inbox candidate; it can never schedule or write a calendar by itself. */
class CommitmentProposalTool(
    private val gateway: CommitmentProposalGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) : AgentTool<CommitmentProposalParams, CommitmentProposalResult> {
    override val descriptor = ToolDescriptor(
        name = NAME,
        description = "Propose an ambiguous future commitment for the local review inbox when " +
            "the user wants to remember a task but has not given enough detail for a reminder or " +
            "calendar event. This never schedules anything. The user confirms this proposal now " +
            "and must separately approve promotion from the inbox later.",
        risk = ToolRisk.LOCAL_WRITE,
        minimumConfirmation = ConfirmationRequirement.UserConfirmation,
        requiredCapabilities = setOf(ToolCapability.WRITE_PROPOSALS),
    )

    override suspend fun validateAndCanonicalize(params: CommitmentProposalParams): ValidationResult {
        val summary = sanitize(params.summary) ?: return ValidationResult.Invalid(
            "일정 후보는 1자 이상 180자 이하의 안전한 한 문장이어야 합니다.",
        )
        val proposedAtEpochMillis = when {
            params.proposedAt == null && params.zoneId == null -> null
            params.proposedAt == null || params.zoneId == null -> return ValidationResult.Invalid(
                "후보 시각과 시간대는 함께 지정해야 합니다.",
            )
            else -> parseUniqueInstant(params.proposedAt, params.zoneId)
                ?: return ValidationResult.Invalid("후보 시각 또는 시간대가 올바르지 않습니다.")
        }
        val now = clock()
        if (proposedAtEpochMillis != null &&
            proposedAtEpochMillis !in (now + 1)..(now + MAX_FUTURE_MILLIS)
        ) {
            return ValidationResult.Invalid("후보 시각은 현재부터 5년 이내의 미래여야 합니다.")
        }
        return ValidationResult.Valid(
            CanonicalFields.encode(
                buildMap {
                    put(FIELD_SUMMARY, summary)
                    proposedAtEpochMillis?.let { put(FIELD_DUE, it.toString()) }
                    params.proposedAt?.let { put(FIELD_LOCAL, it) }
                    params.zoneId?.let { put(FIELD_ZONE, ZoneId.of(it).id) }
                },
            ),
        )
    }

    override fun preview(input: CanonicalToolInput): ActionPreview {
        val fields = CanonicalFields.decode(input)
        return ActionPreview(
            title = "검토할 일정 후보 저장",
            summary = buildString {
                append('“').append(fields.requiredString(FIELD_SUMMARY)).append('”')
                fields[FIELD_LOCAL]?.let { local ->
                    append("\n제안 시각: ").append(local).append(" (")
                        .append(fields.requiredString(FIELD_ZONE)).append(')')
                }
                append("\n아직 리마인더나 캘린더에는 등록되지 않습니다.")
            },
        )
    }

    override suspend fun execute(
        input: CanonicalToolInput,
        permit: ExecutionPermit,
    ): CommitmentProposalResult {
        val fields = CanonicalFields.decode(input)
        return CommitmentProposalResult(
            gateway.offer(
                CommitmentProposalWriteRequest(
                    summary = fields.requiredString(FIELD_SUMMARY),
                    proposedDueAtEpochMillis = fields[FIELD_DUE]?.toLong(),
                    zoneId = fields[FIELD_ZONE],
                    sourceRefHash = sha256(input.encoded),
                ),
            ),
        )
    }

    override fun executionOutcome(result: CommitmentProposalResult): ToolExecutionOutcome =
        when (result.outcome) {
            CommitmentProposalWriteOutcome.SAVED,
            CommitmentProposalWriteOutcome.ALREADY_HANDLED,
            -> ToolExecutionOutcome.WRITE_COMPLETED
            CommitmentProposalWriteOutcome.CAPACITY_REACHED,
            CommitmentProposalWriteOutcome.REJECTED,
            -> ToolExecutionOutcome.WRITE_REFUSED
        }

    private fun parseUniqueInstant(localText: String, zoneText: String): Long? = try {
        if (!LOCAL_DATE_TIME.matches(localText)) return null
        val local = LocalDateTime.parse(localText)
        val zone = ZoneId.of(zoneText)
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size != 1) return null
        local.toInstant(offsets.single()).toEpochMilli()
    } catch (_: DateTimeException) {
        null
    }

    private fun sanitize(raw: String): String? {
        val value = raw.trim().replace(WHITESPACE, " ")
        if (value.codePointCount(0, value.length) !in 1..MAX_SUMMARY_CODE_POINTS) return null
        if (value.contains("<|") || value.contains("|>")) return null
        if (value.codePoints().anyMatch { codePoint ->
                Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt()
            }
        ) return null
        return value
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

    companion object {
        const val NAME = "commitment_propose"
        const val MAX_SUMMARY_CODE_POINTS = 180
        internal const val FIELD_SUMMARY = "summary"
        internal const val FIELD_DUE = "due_at_epoch_millis"
        internal const val FIELD_LOCAL = "proposed_at"
        internal const val FIELD_ZONE = "zone_id"
        private const val MAX_FUTURE_MILLIS = 5L * 366L * 24L * 60L * 60L * 1_000L
        private val LOCAL_DATE_TIME = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
        private val WHITESPACE = Regex("\\s+")
    }
}
