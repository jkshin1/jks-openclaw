package com.personaledge.agent

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.personaledge.core.data.ReminderCreateResult
import com.personaledge.core.data.CommitmentProposalEntity
import com.personaledge.core.data.ProposalStoreResult
import com.personaledge.core.data.ReminderCreator
import com.personaledge.core.data.ReminderDeliveryOutcome
import com.personaledge.core.data.ReminderDraft
import com.personaledge.core.data.ReminderEntity
import com.personaledge.core.data.ReminderPrecision
import com.personaledge.core.data.ReminderScheduleState
import com.personaledge.core.data.ReminderSourceType
import com.personaledge.core.data.takeCodePoints
import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReminderUiItem(
    val id: String,
    val title: String,
    val triggerText: String,
    val zoneId: String,
    val recurrenceRule: String?,
    val scheduleVersion: Long,
    val sourceLabel: String,
    val scheduleLabel: String,
    val delivered: Boolean,
)

internal object ReminderSourcePresentation {
    fun label(sourceType: ReminderSourceType): String = when (sourceType) {
        ReminderSourceType.DIRECT -> "직접 생성"
        ReminderSourceType.INBOX -> "잊지 말 것 Inbox"
        ReminderSourceType.MODEL_TOOL -> "확인한 모델 제안"
        ReminderSourceType.CALENDAR -> "캘린더 출발 알림"
        ReminderSourceType.NOTIFICATION_PROPOSAL -> "알림 후보에서 승격"
    }
}

data class CommitmentProposalUiItem(
    val id: String,
    val summary: String,
    val suggestedTriggerAt: String?,
    val zoneId: String?,
    val confidence: Int,
    val sourceLabel: String,
    val todayEveningTriggerAt: String?,
    val tomorrowMorningTriggerAt: String?,
)

data class InboxClarificationOptions(
    val todayEveningTriggerAt: String?,
    val tomorrowMorningTriggerAt: String?,
)

/** Exact, model-free options for the single clarification shown by an undated Inbox row. */
internal object InboxClarificationPolicy {
    fun options(nowEpochMillis: Long, zone: ZoneId): InboxClarificationOptions {
        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zone)
        val todayEvening = uniqueFutureText(
            LocalDateTime.of(now.toLocalDate(), LocalTime.of(19, 0)),
            zone,
            nowEpochMillis,
        )
        val tomorrowMorning = uniqueFutureText(
            LocalDateTime.of(now.toLocalDate().plusDays(1), LocalTime.of(9, 0)),
            zone,
            nowEpochMillis,
        )
        return InboxClarificationOptions(todayEvening, tomorrowMorning)
    }

    private fun uniqueFutureText(
        local: LocalDateTime,
        zone: ZoneId,
        nowEpochMillis: Long,
    ): String? {
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size != 1 || local.toInstant(offsets.single()).toEpochMilli() <= nowEpochMillis) {
            return null
        }
        return local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT))
    }
}

internal object DailyBriefTimePolicy {
    private val FORMAT = Regex("""([01]\d|2[0-3]):([0-5]\d)""")

    fun parse(value: String): Int? = FORMAT.matchEntire(value.trim())?.let { match ->
        match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    fun format(minutesOfDay: Int): String = String.format(
        Locale.ROOT,
        "%02d:%02d",
        minutesOfDay / 60,
        minutesOfDay % 60,
    )
}

data class ReminderSetupState(
    val notificationPermissionGranted: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val exactAlarmAvailable: Boolean = false,
    val deviceZoneId: String = ZoneId.systemDefault().id,
    val reminders: List<ReminderUiItem> = emptyList(),
    val proposals: List<CommitmentProposalUiItem> = emptyList(),
    val todayBrief: TodayBrief = TodayBrief(),
    val dailyBriefEnabled: Boolean = false,
    val dailyBriefTime: String = "08:00",
    val proactiveRoutePlanningEnabled: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val weekendBriefEnabled: Boolean = true,
    val commitmentProposalsEnabled: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canNotify: Boolean
        get() = notificationPermissionGranted && notificationsEnabled
}

/** Model-independent use cases shared by the cover-friendly Reminder sheet and Activity lifecycle. */
class ReminderCoordinator(
    context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    private val applicationContext = context.applicationContext
    private val _state = MutableStateFlow(ReminderSetupState())
    val state: StateFlow<ReminderSetupState> = _state.asStateFlow()
    private val refreshSequence = AtomicLong(0)

    init {
        refresh()
    }

    fun refresh() = refresh(ownerConsentError = null)

    private fun refresh(ownerConsentError: String?) {
        val sequence = refreshSequence.incrementAndGet()
        scope.launch {
            if (refreshSequence.get() == sequence) {
                _state.update { it.copy(loading = true, error = ownerConsentError) }
            }
            val zone = ZoneId.systemDefault()
            val settings = runCatching { container.settings.current() }.getOrNull()
            val proposals = runCatching { container.commitmentProposals.pending() }.getOrNull()
            val brief = settings?.let { current ->
                runCatching { TodayBriefEngine(container).compose(current) }.getOrNull()
            }
            if (settings != null && brief != null) {
                runCatching { LeaveByReminderReconciler(container).reconcile(settings, brief) }
                    .onFailure { container.reminderScheduler.requestReconcile() }
            }
            val remindersResult = runCatching { container.reminders.active() }
            remindersResult.onSuccess { reminders ->
                val refreshed = ReminderSetupState(
                    notificationPermissionGranted = permissionGranted(),
                    notificationsEnabled = NotificationManagerCompat
                        .from(applicationContext)
                        .areNotificationsEnabled(),
                    exactAlarmAvailable = container.reminderScheduler.exactAlarmAvailable(),
                    deviceZoneId = zone.id,
                    reminders = reminders.map { it.toUi(zone) },
                    proposals = proposals.orEmpty().map { it.toUi(zone) },
                    todayBrief = brief ?: TodayBrief(calendarAvailable = false),
                    dailyBriefEnabled = settings?.let { current ->
                        container.ownerConsentInterlock.allowed(
                            OwnerConsentFeature.DAILY_BRIEF,
                            current.dailyBriefEnabled,
                        )
                    } ?: false,
                    dailyBriefTime = DailyBriefTimePolicy.format(
                        settings?.dailyBriefMinutesOfDay
                            ?: com.personaledge.core.data.AgentSettings.DEFAULT_DAILY_BRIEF_MINUTES_OF_DAY,
                    ),
                    proactiveRoutePlanningEnabled = settings?.let { current ->
                        container.ownerConsentInterlock.allowed(
                            OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING,
                            current.proactiveRoutePlanningEnabled,
                        )
                    } ?: false,
                    quietHoursEnabled = settings?.quietHoursEnabled ?: false,
                    weekendBriefEnabled = settings?.weekendBriefEnabled ?: true,
                    commitmentProposalsEnabled = settings?.let { current ->
                        container.ownerConsentInterlock.allowed(
                            OwnerConsentFeature.COMMITMENT_PROPOSALS,
                            current.commitmentProposalsEnabled,
                        )
                    } ?: false,
                    error = ownerConsentError ?: when {
                        settings == null -> "비서 설정을 읽지 못했습니다."
                        proposals == null -> "일정 후보 제안함을 읽지 못했습니다."
                        else -> null
                    },
                )
                if (refreshSequence.get() == sequence) _state.value = refreshed
            }
                .onFailure {
                    if (refreshSequence.get() == sequence) {
                        _state.update {
                            it.copy(
                                loading = false,
                                error = ownerConsentError
                                    ?: "리마인더 저장소를 읽지 못했습니다.",
                            )
                        }
                    }
                }
        }
    }

    fun onNotificationPermissionResult() {
        container.reminderScheduler.requestReconcile()
        refresh()
    }

    fun setDailyBriefEnabled(enabled: Boolean) {
        setOwnerConsent(
            feature = OwnerConsentFeature.DAILY_BRIEF,
            enabled = enabled,
            failureMessage = "오늘 브리핑 설정을 저장하지 못했습니다.",
            afterApplied = { ReminderWorkBootstrap.start(applicationContext) },
        )
    }

    fun setDailyBriefTime(value: String) {
        val minutesOfDay = DailyBriefTimePolicy.parse(value)
        if (minutesOfDay == null) {
            _state.update { it.copy(error = "브리핑 시각을 HH:mm 형식으로 입력해 주세요.") }
            return
        }
        scope.launch {
            runCatching { container.settings.setDailyBriefMinutesOfDay(minutesOfDay) }
                .onSuccess {
                    ReminderWorkBootstrap.scheduleDailyBrief(applicationContext, minutesOfDay)
                }
                .onFailure {
                    _state.update { it.copy(error = "오늘 브리핑 시각을 저장하지 못했습니다.") }
                }
            refresh()
        }
    }

    fun setProactiveRoutePlanningEnabled(enabled: Boolean) {
        setOwnerConsent(
            feature = OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING,
            enabled = enabled,
            failureMessage = "출발 시각 설정을 저장하지 못했습니다.",
            afterApplied = {
                ReminderWorkBootstrap.enqueueLeaveByReconcile(applicationContext)
            },
        )
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        scope.launch {
            runCatching { container.settings.setQuietHoursEnabled(enabled) }
                .onFailure { _state.update { it.copy(error = "조용한 시간 설정을 저장하지 못했습니다.") } }
            container.reminderScheduler.requestReconcile()
            refresh()
        }
    }

    fun setWeekendBriefEnabled(enabled: Boolean) {
        scope.launch {
            runCatching { container.settings.setWeekendBriefEnabled(enabled) }
                .onFailure { _state.update { it.copy(error = "주말 브리핑 설정을 저장하지 못했습니다.") } }
            refresh()
        }
    }

    fun setCommitmentProposalsEnabled(enabled: Boolean) {
        setOwnerConsent(
            feature = OwnerConsentFeature.COMMITMENT_PROPOSALS,
            enabled = enabled,
            failureMessage = "일정 후보 설정을 저장하지 못했습니다.",
        )
    }

    private fun setOwnerConsent(
        feature: OwnerConsentFeature,
        enabled: Boolean,
        failureMessage: String,
        afterApplied: () -> Unit = {},
    ) {
        val mutation = container.ownerConsentMutator.setEnabled(feature, enabled)
        if (!enabled) {
            refreshSequence.incrementAndGet()
            _state.update { state ->
                when (feature) {
                    OwnerConsentFeature.DAILY_BRIEF ->
                        state.copy(dailyBriefEnabled = false)
                    OwnerConsentFeature.PROACTIVE_ROUTE_PLANNING ->
                        state.copy(proactiveRoutePlanningEnabled = false)
                    OwnerConsentFeature.COMMITMENT_PROPOSALS ->
                        state.copy(commitmentProposalsEnabled = false)
                    else -> state
                }
            }
        }
        container.ownerConsentMutationScope.launch {
            when (mutation.await()) {
                OwnerConsentMutationOutcome.APPLIED -> {
                    afterApplied()
                    refresh()
                }
                OwnerConsentMutationOutcome.FAILED -> refresh(failureMessage)
                OwnerConsentMutationOutcome.SUPERSEDED -> Unit
            }
        }
    }

    fun dismissProposal(id: String) {
        scope.launch {
            val dismissed = runCatching { container.commitmentProposals.dismiss(id) }
                .getOrDefault(false)
            if (!dismissed) _state.update { it.copy(error = "일정 후보가 이미 변경되었습니다.") }
            refresh()
        }
    }

    fun captureInbox(summary: String) {
        val sourceRefHash = sha256("direct-inbox\u0000${UUID.randomUUID()}")
        val draft = CommitmentProposalDetector.draftForDirectInbox(summary, sourceRefHash)
            ?.copy(sourcePackage = DIRECT_INBOX_SOURCE)
        if (draft == null) {
            _state.update { it.copy(error = "Inbox에 적어 둘 내용을 입력해 주세요.") }
            return
        }
        scope.launch {
            when (container.commitmentProposals.offer(draft)) {
                is ProposalStoreResult.Stored -> refresh()
                ProposalStoreResult.CapacityReached ->
                    _state.update { it.copy(error = "Inbox 후보 한도에 도달했습니다.") }
                ProposalStoreResult.AlreadyHandled ->
                    _state.update { it.copy(error = "같은 Inbox 요청이 이미 처리되었습니다.") }
                ProposalStoreResult.Invalid ->
                    _state.update { it.copy(error = "Inbox 내용을 저장할 수 없습니다.") }
            }
        }
    }

    fun promoteProposal(id: String, triggerAt: String, exact: Boolean) {
        scope.launch {
            val proposal = container.commitmentProposals.findPending(id)
            if (proposal == null) {
                _state.update { it.copy(error = "일정 후보가 이미 변경되었습니다.") }
                refresh()
                return@launch
            }
            val existing = container.reminders.findBySourceRefHash(proposal.sourceRefHash)
            if (existing != null) {
                container.commitmentProposals.markPromoted(proposal.id, existing.id)
                runCatching { container.reminderScheduler.schedule(existing) }
                refresh()
                return@launch
            }
            val zone = proposal.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: ZoneId.systemDefault()
            val trigger = triggerAt.trim().takeIf(String::isNotEmpty)?.let {
                parseLocalDateTime(it, zone)
            } ?: proposal.proposedDueAtEpochMillis
            if (trigger == null || trigger <= System.currentTimeMillis()) {
                _state.update {
                    it.copy(error = "후보를 저장할 미래 시각을 yyyy-MM-dd'T'HH:mm 형식으로 지정해 주세요.")
                }
                return@launch
            }
            val title = proposal.summary.takeCodePoints(
                com.personaledge.core.data.ReminderTextPolicy.MAX_TITLE_CODE_POINTS,
            )
            val digest = sha256(
                listOf(proposal.id, title, trigger, zone.id, exact).joinToString("\u0000"),
            )
            when (
                val result = container.reminders.create(
                    ReminderDraft(
                        title = title,
                        triggerAtEpochMillis = trigger,
                        zoneId = zone.id,
                        precision = if (exact) ReminderPrecision.EXACT else ReminderPrecision.FLEXIBLE,
                        sourceType = when {
                            proposal.sourcePackage == DIRECT_INBOX_SOURCE -> ReminderSourceType.INBOX
                            proposal.sourcePackage != null -> ReminderSourceType.NOTIFICATION_PROPOSAL
                            else -> ReminderSourceType.MODEL_TOOL
                        },
                        sourceRefHash = proposal.sourceRefHash,
                        createdBy = ReminderCreator.USER,
                        confirmationDigest = digest,
                    ),
                )
            ) {
                is ReminderCreateResult.Created -> {
                    val marked = container.commitmentProposals.markPromoted(
                        proposal.id,
                        result.reminder.id,
                    )
                    runCatching { container.reminderScheduler.schedule(result.reminder) }
                        .onFailure { container.reminderScheduler.requestReconcile() }
                    if (!marked) {
                        _state.update {
                            it.copy(error = "리마인더는 저장됐지만 후보 상태 갱신을 재확인해야 합니다.")
                        }
                    }
                    refresh()
                }
                ReminderCreateResult.CapacityReached ->
                    _state.update { it.copy(error = "활성 리마인더 한도에 도달했습니다.") }
                ReminderCreateResult.Invalid ->
                    _state.update { it.copy(error = "후보 제목 또는 시각을 확인해 주세요.") }
            }
        }
    }

    fun create(
        title: String,
        triggerAt: String,
        exact: Boolean,
        recurrenceRule: String? = null,
        untilCompleted: Boolean = false,
    ) {
        scope.launch {
            val zone = ZoneId.systemDefault()
            val trigger = parseLocalDateTime(triggerAt, zone)
            if (trigger == null || trigger <= System.currentTimeMillis()) {
                _state.update {
                    it.copy(error = "미래 시각을 yyyy-MM-dd'T'HH:mm 형식으로 입력해 주세요.")
                }
                return@launch
            }
            val digest = sha256(
                listOf(
                    title,
                    trigger,
                    zone.id,
                    exact,
                    recurrenceRule.orEmpty(),
                    untilCompleted,
                ).joinToString("\u0000"),
            )
            val result = container.reminders.create(
                ReminderDraft(
                    title = title,
                    triggerAtEpochMillis = trigger,
                    zoneId = zone.id,
                    recurrenceRule = recurrenceRule,
                    escalationPolicy = if (untilCompleted) "until_completed" else "once",
                    precision = if (exact) ReminderPrecision.EXACT else ReminderPrecision.FLEXIBLE,
                    sourceType = ReminderSourceType.DIRECT,
                    createdBy = ReminderCreator.USER,
                    confirmationDigest = digest,
                ),
            )
            when (result) {
                is ReminderCreateResult.Created -> {
                    runCatching { container.reminderScheduler.schedule(result.reminder) }
                        .onFailure { container.reminderScheduler.requestReconcile() }
                    refresh()
                }
                ReminderCreateResult.CapacityReached ->
                    _state.update { it.copy(error = "활성 리마인더 한도에 도달했습니다.") }
                ReminderCreateResult.Invalid ->
                    _state.update { it.copy(error = "제목, 시각 또는 반복 규칙을 확인해 주세요.") }
            }
        }
    }

    fun complete(id: String, version: Long) = mutate(id, version) { before ->
        val completed = container.reminders.complete(id, version) ?: return@mutate false
        container.reminders.recordDelivery(before, ReminderDeliveryOutcome.COMPLETED_FROM_APP)
        container.reminderScheduler.cancel(completed.id)
        true
    }

    fun snooze(id: String, version: Long, minutes: Int) = mutate(id, version) { before ->
        if (minutes !in setOf(10, 60, 24 * 60)) return@mutate false
        val snoozed = container.reminders.snooze(
            id,
            System.currentTimeMillis() + minutes.toLong() * 60_000,
            version,
        ) ?: return@mutate false
        container.reminders.recordDelivery(before, ReminderDeliveryOutcome.SNOOZED_FROM_APP)
        container.reminderScheduler.schedule(snoozed)
        true
    }

    fun cancel(id: String, version: Long) = mutate(id, version) { before ->
        val cancelled = container.reminders.cancel(id, version) ?: return@mutate false
        container.reminders.recordDelivery(before, ReminderDeliveryOutcome.CANCELLED_FROM_APP)
        container.reminderScheduler.cancel(cancelled.id)
        true
    }

    private fun mutate(
        id: String,
        version: Long,
        action: suspend (ReminderEntity) -> Boolean,
    ) {
        scope.launch {
            val before = container.reminders.find(id)
            if (before == null || before.scheduleVersion != version || !action(before)) {
                _state.update { it.copy(error = "리마인더가 이미 변경되었습니다. 목록을 새로 고쳤습니다.") }
            }
            refresh()
        }
    }

    private fun permissionGranted(): Boolean =
        NotificationPermissionPolicy.isGranted(applicationContext)

    private fun ReminderEntity.toUi(fallbackZone: ZoneId): ReminderUiItem {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(fallbackZone)
        val trigger = Instant.ofEpochMilli(
            ReminderDeliveryTimePolicy.requestedAt(
                triggerAtEpochMillis = triggerAtEpochMillis,
                snoozeUntilEpochMillis = snoozeUntilEpochMillis,
                leadTimeMinutes = leadTimeMinutes,
            ),
        )
            .atZone(zone)
            .format(DISPLAY_FORMAT)
        return ReminderUiItem(
            id = id,
            title = title,
            triggerText = trigger,
            zoneId = zone.id,
            recurrenceRule = recurrenceRule,
            scheduleVersion = scheduleVersion,
            sourceLabel = ReminderSourcePresentation.label(sourceType),
            scheduleLabel = when (scheduleState) {
                ReminderScheduleState.PENDING -> "예약 확인 중"
                ReminderScheduleState.SCHEDULED_INEXACT -> "근사 시각 예약됨"
                ReminderScheduleState.SCHEDULED_EXACT -> "정확 시각 예약됨"
                ReminderScheduleState.DEGRADED_TO_INEXACT -> "근사 예약 · 정확 알람 권한 없음"
                ReminderScheduleState.BLOCKED_NOTIFICATION_PERMISSION -> "알림 권한 필요"
                ReminderScheduleState.BLOCKED_EXACT_PERMISSION -> "정확 알람 권한 필요"
                ReminderScheduleState.DELIVERY_FAILED -> "전달 실패 · 재조정 예정"
                ReminderScheduleState.DELIVERED -> "알림 전달됨 · 완료 대기"
            },
            delivered = scheduleState == ReminderScheduleState.DELIVERED,
        )
    }

    private fun CommitmentProposalEntity.toUi(fallbackZone: ZoneId): CommitmentProposalUiItem {
        val zone = zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: fallbackZone
        val suggested = proposedDueAtEpochMillis?.let { epoch ->
            Instant.ofEpochMilli(epoch).atZone(zone).format(INPUT_FORMATTER)
        }
        val clarification = if (suggested == null) {
            InboxClarificationPolicy.options(System.currentTimeMillis(), zone)
        } else {
            InboxClarificationOptions(null, null)
        }
        return CommitmentProposalUiItem(
            id = id,
            summary = summary,
            suggestedTriggerAt = suggested,
            zoneId = zoneId,
            confidence = confidence,
            sourceLabel = when {
                sourcePackage == DIRECT_INBOX_SOURCE -> "직접 입력"
                sourcePackage != null -> "허용한 알림에서 탐지"
                else -> "확인한 모델 제안"
            },
            todayEveningTriggerAt = clarification.todayEveningTriggerAt,
            tomorrowMorningTriggerAt = clarification.tomorrowMorningTriggerAt,
        )
    }

    companion object {
        private const val DIRECT_INBOX_SOURCE = "com.personaledge.agent.direct_inbox"
        private val INPUT_FORMAT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
        private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("M월 d일 E HH:mm", Locale.KOREAN)
        private val INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT)

        private fun parseLocalDateTime(value: String, zone: ZoneId): Long? = try {
            val trimmed = value.trim()
            if (!INPUT_FORMAT.matches(trimmed)) return null
            val local = LocalDateTime.parse(trimmed)
            val offsets = zone.rules.getValidOffsets(local)
            if (offsets.size != 1) return null
            local.toInstant(offsets.single()).toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }

        private fun sha256(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }
}
