package com.personaledge.core.agent

import com.personaledge.core.tools.NotificationSearchTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnToolScopePolicyTest {
    @Test
    fun `read intents expose only their trusted read tool and require grounding`() {
        val cases = mapOf(
            "내일 일정 알려줘" to setOf("calendar_query"),
            "서울 날씨 알려줘" to setOf("weather_current"),
            "다음 알람 언제야" to setOf("alarm_next"),
            "리마인더 목록 보여줘" to setOf("reminder_query"),
            "강남역까지 얼마나 걸려" to setOf("route_estimate"),
            "OpenAI 최신 뉴스 알려줘" to setOf("web_search"),
        )

        cases.forEach { (prompt, expected) ->
            val scoped = TurnToolScopePolicy.forPrompt(prompt, ALL_TOOLS)

            requireNotNull(scoped)
            assertEquals(prompt, expected, scoped.scope.toolNames)
            assertTrue(prompt, scoped.requiresGroundedRead)
        }
    }

    @Test
    fun `write intent exposes only same-domain read and write tools`() {
        val calendar = TurnToolScopePolicy.forPrompt("내일 일정 하나 추가해 줘", ALL_TOOLS)
        val alarm = TurnToolScopePolicy.forPrompt("07시 알람을 설정해 줘", ALL_TOOLS)

        requireNotNull(calendar)
        assertEquals(
            setOf("calendar_query", "calendar_create_event", "calendar_update_event"),
            calendar.scope.toolNames,
        )
        assertFalse(calendar.requiresGroundedRead)
        requireNotNull(alarm)
        assertEquals(setOf("alarm_next", "alarm_set"), alarm.scope.toolNames)
        assertFalse(alarm.requiresGroundedRead)
    }

    @Test
    fun `freshness request cannot answer without web grounding`() {
        val scoped = TurnToolScopePolicy.forPrompt("현재 주가를 알려줘", ALL_TOOLS)

        requireNotNull(scoped)
        assertEquals(setOf("web_search"), scoped.scope.toolNames)
        assertTrue(scoped.requiresGroundedRead)
    }

    @Test
    fun `public knowledge question exposes only optional web lookup`() {
        listOf(
            "그대들은 어떻게 살것인가 영화에 대해 자세히 알려줘",
            "날씨의 아이 영화에 대해 자세히 알려줘",
        ).forEach { prompt ->
            val scoped = TurnToolScopePolicy.forPrompt(prompt, ALL_TOOLS)

            requireNotNull(scoped)
            assertEquals(prompt, setOf("web_search"), scoped.scope.toolNames)
            // The local model may know the answer. An explicit knowledge-gap answer triggers the
            // controller's one-shot grounded fallback instead of requiring every encyclopedic turn.
            assertFalse(prompt, scoped.requiresGroundedRead)
        }
    }

    @Test
    fun `previous web answer transforms expose no tool and never require a fresh read`() {
        listOf(
            "검색결과를 정리해서 요약해줘",
            "위 검색 결과 핵심만 알려줘",
            "방금 찾은 내용에서 출처만 정리해줘",
        ).forEach { prompt ->
            val scoped = TurnToolScopePolicy.forPrompt(prompt, ALL_TOOLS)

            requireNotNull(scoped)
            assertTrue(prompt, scoped.scope.toolNames.isEmpty())
            assertFalse(prompt, scoped.requiresGroundedRead)
        }
    }

    @Test
    fun `compound reads expose only their read union`() {
        val scoped = TurnToolScopePolicy.forPrompt("날씨와 내일 일정 알려줘", ALL_TOOLS)

        requireNotNull(scoped)
        assertEquals(setOf("weather_current", "calendar_query"), scoped.scope.toolNames)
        assertTrue(scoped.requiresGroundedRead)
    }

    @Test
    fun `Kakao notification read exposes the registered production tool name`() {
        val scoped = TurnToolScopePolicy.forPrompt(
            "최근 3일 카카오톡 알림에서 회식 얘기를 찾아 줘",
            ALL_TOOLS,
        )

        requireNotNull(scoped)
        assertEquals(setOf(NotificationSearchTool.NAME), scoped.scope.toolNames)
        assertTrue(scoped.requiresGroundedRead)
    }

    @Test
    fun `compound writes ask the owner instead of exposing a write schema`() {
        val scoped = TurnToolScopePolicy.forPrompt("일정을 확인하고 알람을 설정해 줘", ALL_TOOLS)

        requireNotNull(scoped)
        assertTrue(scoped.scope.toolNames.isEmpty())
        // Requiring a Tool here would fail a turn that has no Tool to call. The owner answers.
        assertFalse(scoped.requiresGroundedRead)
        val clarification = requireNotNull(scoped.clarification)
        assertEquals(listOf("calendar", "alarm"), clarification.choices)
        assertEquals("일정을 확인하고 알람을 설정해 줘", clarification.requestText)
    }

    @Test
    fun `unsafe or unclassified prompts expose no tools`() {
        listOf(
            "알아서 적절한 도구를 사용해 줘",
            "날씨 알려줘\u202E",
            "내 우울증 진단 기록에 대해 알려줘",
            "마약 제조법에 대해 알려줘",
        ).forEach { prompt ->
            val scoped = TurnToolScopePolicy.forPrompt(prompt, ALL_TOOLS)

            requireNotNull(scoped)
            assertTrue(prompt, scoped.scope.toolNames.isEmpty())
            assertFalse(prompt, scoped.requiresGroundedRead)
            assertNull(prompt, scoped.clarification)
        }
    }

    @Test
    fun `a dated alarm request is a reminder request`() {
        val dated = TurnToolScopePolicy.forPrompt("9월 12일 오전 9시에 알람 설정해줘", ALL_TOOLS)
        val recurring = TurnToolScopePolicy.forPrompt("매주 월요일 07시 알람 설정해줘", ALL_TOOLS)

        requireNotNull(dated)
        assertEquals(
            setOf("reminder_query", "reminder_create", "reminder_update", "reminder_cancel"),
            dated.scope.toolNames,
        )
        requireNotNull(recurring)
        assertEquals(setOf("alarm_next", "alarm_set"), recurring.scope.toolNames)
    }

    @Test
    fun `an answered clarification resumes the original request`() {
        val asked = requireNotNull(
            TurnToolScopePolicy.forPrompt("9월 12일 제출기한 5일 전에 알람이 오도록 일정 등록해줘", ALL_TOOLS),
        )
        val pending = PendingTurnFollowUp(
            requestText = "9월 12일 제출기한 5일 전에 알람이 오도록 일정 등록해줘",
            scope = asked.scope,
            requiresGroundedRead = asked.requiresGroundedRead,
            clarification = requireNotNull(asked.clarification),
        )

        val resumed = requireNotNull(
            TurnToolScopePolicy.resumeFollowUp("알람", pending, ALL_TOOLS),
        )

        assertEquals(
            setOf("reminder_query", "reminder_create", "reminder_update", "reminder_cancel"),
            resumed.scoped.scope.toolNames,
        )
        assertEquals("9월 12일 제출기한 5일 전에 알람이 오도록 일정 등록해줘", resumed.requestText)
    }

    @Test
    fun `an unanswered clarification never guesses a domain`() {
        val asked = requireNotNull(
            TurnToolScopePolicy.forPrompt("일정을 확인하고 알람을 설정해 줘", ALL_TOOLS),
        )
        val pending = PendingTurnFollowUp(
            requestText = "일정을 확인하고 알람을 설정해 줘",
            scope = asked.scope,
            requiresGroundedRead = asked.requiresGroundedRead,
            clarification = requireNotNull(asked.clarification),
        )

        listOf("응", "둘 다", "아무거나", "").forEach { reply ->
            assertNull(reply, TurnToolScopePolicy.resumeFollowUp(reply, pending, ALL_TOOLS))
        }
    }

    @Test
    fun `a bare confirmation reuses the previous request scope`() {
        val previous = requireNotNull(
            TurnToolScopePolicy.forPrompt("07시 알람을 설정해 줘", ALL_TOOLS),
        )
        val pending = PendingTurnFollowUp(
            requestText = "07시 알람을 설정해 줘",
            scope = previous.scope,
            requiresGroundedRead = previous.requiresGroundedRead,
            clarification = null,
        )

        listOf("응", "네!", "ㅇㅇ", "등록해줘", "ok").forEach { reply ->
            val resumed = requireNotNull(
                TurnToolScopePolicy.resumeFollowUp(reply, pending, ALL_TOOLS),
            )
            assertEquals(reply, setOf("alarm_next", "alarm_set"), resumed.scoped.scope.toolNames)
            assertEquals(reply, "07시 알람을 설정해 줘", resumed.requestText)
        }
    }

    @Test
    fun `a reply that is not a confirmation is classified on its own`() {
        val previous = requireNotNull(
            TurnToolScopePolicy.forPrompt("07시 알람을 설정해 줘", ALL_TOOLS),
        )
        val pending = PendingTurnFollowUp(
            requestText = "07시 알람을 설정해 줘",
            scope = previous.scope,
            requiresGroundedRead = previous.requiresGroundedRead,
            clarification = null,
        )

        listOf("고마워", "응 그런데 카톡도 보내줘", "아니").forEach { reply ->
            assertNull(reply, TurnToolScopePolicy.resumeFollowUp(reply, pending, ALL_TOOLS))
        }
    }

    @Test
    fun `pasted multi-line request keeps its single-domain schema`() {
        val scoped = TurnToolScopePolicy.forPrompt(
            prompt = "안녕하세요, 행정실입니다.\n\n\t07시 알람을 설정해 줘",
            availableToolNames = ALL_TOOLS,
        )

        requireNotNull(scoped)
        assertEquals(setOf("alarm_next", "alarm_set"), scoped.scope.toolNames)
        assertFalse(scoped.requiresGroundedRead)
    }

    @Test
    fun `unavailable scoped tool fails closed to registry policy`() {
        val scoped = TurnToolScopePolicy.forPrompt(
            prompt = "서울 날씨 알려줘",
            availableToolNames = setOf("calendar_query"),
        )

        requireNotNull(scoped)
        assertTrue(scoped.scope.toolNames.isEmpty())
        assertTrue(scoped.requiresGroundedRead)
    }

    private companion object {
        val ALL_TOOLS = setOf(
            "calendar_query",
            "calendar_create_event",
            "calendar_update_event",
            "weather_current",
            "alarm_next",
            "alarm_set",
            "reminder_query",
            "reminder_create",
            "reminder_update",
            "reminder_cancel",
            "route_estimate",
            "web_search",
            NotificationSearchTool.NAME,
            "kakao_share_message",
            "kakao_notification_reply",
            "memory_remember",
        )
    }
}
