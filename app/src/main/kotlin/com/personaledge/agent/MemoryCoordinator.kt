package com.personaledge.agent

import com.personaledge.core.data.MemoryCategory
import com.personaledge.core.data.MemoryEntity
import com.personaledge.core.data.RememberResult
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MemorySummaryUi(
    val id: String,
    val content: String,
    val category: MemoryCategory,
    val createdAtEpochMillis: Long,
    val validUntilEpochMillis: Long?,
    val lastConfirmedAtEpochMillis: Long,
    val expired: Boolean,
    val needsReconfirmation: Boolean,
    val superseded: Boolean,
    val updatedAtEpochMillis: Long,
)

data class MemorySetupState(
    val enabled: Boolean = false,
    val memories: List<MemorySummaryUi> = emptyList(),
    val error: String? = null,
)

/** Owns long-term-memory settings and CRUD; the main ViewModel only delegates UI events. */
class MemoryCoordinator(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    private val _state = MutableStateFlow(MemorySetupState())
    val state: StateFlow<MemorySetupState> = _state.asStateFlow()
    private val refreshSequence = AtomicLong(0)

    fun refresh() {
        requestLoad()
    }

    fun setEnabled(enabled: Boolean) {
        val mutation = container.ownerConsentMutator.setEnabled(
            OwnerConsentFeature.MEMORY,
            enabled,
        )
        if (!enabled) {
            refreshSequence.incrementAndGet()
            _state.value = _state.value.copy(enabled = false)
        }
        scope.launch {
            val outcome = mutation.await()
            requestLoad(
                if (outcome == OwnerConsentMutationOutcome.FAILED) {
                    "장기 기억 설정을 저장하지 못했습니다."
                } else {
                    null
                },
            )
        }
    }

    /** Direct settings entry is already an explicit user action, so it bypasses model selection. */
    fun store(
        rawContent: String,
        category: MemoryCategory = MemoryCategory.FACT,
        rawValidUntil: String? = null,
    ) {
        scope.launch {
            val enabled = runCatching {
                val settings = container.settings.current()
                container.ownerConsentInterlock.allowed(
                    OwnerConsentFeature.MEMORY,
                    settings.memoryEnabled,
                )
            }.getOrDefault(false)
            val validity = parseValidity(rawValidUntil)
            val error = if (!enabled) {
                "장기 기억을 먼저 켜 주세요."
            } else if (validity is MemoryValidity.Invalid) {
                validity.message
            } else if (!runCatching {
                    val current = container.settings.current()
                    container.ownerConsentInterlock.allowed(
                        OwnerConsentFeature.MEMORY,
                        current.memoryEnabled,
                    )
                }.getOrDefault(false)
            ) {
                "장기 기억을 먼저 켜 주세요."
            } else {
                when (
                    runCatching {
                        container.memories.remember(
                            rawContent = rawContent,
                            category = category,
                            validUntilEpochMillis = (validity as MemoryValidity.Valid).epochMillis,
                        )
                    }.getOrNull()
                ) {
                    is RememberResult.Saved -> null
                    RememberResult.CapacityReached ->
                        "기억은 최대 ${com.personaledge.core.data.MemoryRepository.MAX_MEMORIES}개까지 저장할 수 있습니다."
                    RememberResult.Invalid ->
                        "기억은 비밀정보 없이 240자 이하의 안전한 한 문장으로 입력하세요."
                    null -> "기억을 저장하지 못했습니다."
                }
            }
            requestLoad(error)
        }
    }

    fun replace(
        memoryId: String,
        rawContent: String,
        category: MemoryCategory,
        rawValidUntil: String? = null,
    ) {
        scope.launch {
            val validity = parseValidity(rawValidUntil)
            val error = if (validity is MemoryValidity.Invalid) {
                validity.message
            } else {
                when (
                    runCatching {
                        container.memories.replace(
                            memoryId = memoryId,
                            rawContent = rawContent,
                            category = category,
                            validUntilEpochMillis = (validity as MemoryValidity.Valid).epochMillis,
                        )
                    }.getOrNull()
                ) {
                    is RememberResult.Saved -> null
                    RememberResult.CapacityReached ->
                        "기억은 최대 ${com.personaledge.core.data.MemoryRepository.MAX_MEMORIES}개까지 저장할 수 있습니다."
                    RememberResult.Invalid -> "대체할 기억이나 새 내용을 확인해 주세요."
                    null -> "기억을 대체하지 못했습니다."
                }
            }
            requestLoad(error)
        }
    }

    fun reconfirm(memoryId: String) {
        scope.launch {
            val confirmed = runCatching { container.memories.reconfirm(memoryId) }
                .getOrDefault(false)
            requestLoad(if (confirmed) null else "기억을 다시 확인하지 못했습니다.")
        }
    }

    fun delete(memoryId: String) {
        scope.launch {
            val deleted = runCatching { container.memories.delete(memoryId) }.getOrDefault(false)
            requestLoad(if (deleted) null else "기억을 삭제하지 못했습니다.")
        }
    }

    fun deleteAll() {
        scope.launch {
            val deleted = runCatching { container.memories.deleteAll() }
            requestLoad(if (deleted.isSuccess) null else "기억을 모두 삭제하지 못했습니다.")
        }
    }

    private fun requestLoad(error: String? = null) {
        val sequence = refreshSequence.incrementAndGet()
        scope.launch {
            val settings = runCatching { container.settings.current() }.getOrNull()
            val memories = runCatching { container.memories.recent() }.getOrNull()
            val now = clock()
            val loadedMemories = memories.orEmpty()
            val supersededIds = loadedMemories.mapNotNull(MemoryEntity::supersedesId).toSet()
            val refreshed = MemorySetupState(
                enabled = settings?.let { current ->
                    container.ownerConsentInterlock.allowed(
                        OwnerConsentFeature.MEMORY,
                        current.memoryEnabled,
                    )
                } ?: false,
                memories = loadedMemories.map { memory ->
                    memory.toSummaryUi(now = now, superseded = memory.id in supersededIds)
                },
                error = error ?: when {
                    settings == null -> "장기 기억 설정을 읽지 못했습니다."
                    memories == null -> "저장된 기억을 읽지 못했습니다."
                    else -> null
                },
            )
            if (refreshSequence.get() == sequence) _state.value = refreshed
        }
    }

    private fun parseValidity(raw: String?): MemoryValidity {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return MemoryValidity.Valid(null)
        return try {
            val endExclusive = LocalDate.parse(value)
                .plusDays(1)
                .atStartOfDay(zoneProvider())
                .toInstant()
                .toEpochMilli()
            if (endExclusive <= clock()) {
                MemoryValidity.Invalid("기억 유효일은 오늘 이후여야 합니다.")
            } else {
                MemoryValidity.Valid(endExclusive)
            }
        } catch (_: DateTimeException) {
            MemoryValidity.Invalid("기억 유효일은 YYYY-MM-DD 형식으로 입력하세요.")
        }
    }
}

private sealed interface MemoryValidity {
    data class Valid(val epochMillis: Long?) : MemoryValidity

    data class Invalid(val message: String) : MemoryValidity
}

private fun MemoryEntity.toSummaryUi(now: Long, superseded: Boolean) = MemorySummaryUi(
    id = id,
    content = content,
    category = category,
    createdAtEpochMillis = createdAtEpochMillis,
    validUntilEpochMillis = validUntilEpochMillis,
    lastConfirmedAtEpochMillis = lastConfirmedAtEpochMillis,
    expired = validUntilEpochMillis?.let { it <= now } ?: false,
    needsReconfirmation = now - lastConfirmedAtEpochMillis >
        com.personaledge.core.data.MemoryRepository.RECONFIRM_AFTER_MILLIS,
    superseded = superseded,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
