package com.personaledge.agent

import com.personaledge.core.tools.UnresolvedActionCheck
import java.util.concurrent.atomic.AtomicBoolean

/** App-authored, content-free warning derived only from an aggregate ledger count. */
internal object UnresolvedActionWarning {
    fun text(check: UnresolvedActionCheck): String? = when (check) {
        is UnresolvedActionCheck.Available -> if (check.count == 0L) {
            null
        } else {
            "이전 도구 실행 ${check.count}건의 결과를 확정할 수 없습니다. " +
                "쓰기 작업이 포함됐을 수 있으므로 캘린더·시계 등 대상 앱 상태를 직접 " +
                "확인하기 전 같은 요청을 다시 실행하지 마세요."
        }
        UnresolvedActionCheck.Unavailable ->
            "이전 도구 실행 상태를 확인할 수 없습니다. 쓰기 작업이 있었을 수 있으므로 " +
                "캘린더·시계 등 대상 앱 상태를 직접 확인하기 전 같은 요청을 다시 실행하지 마세요."
    }
}

/** Ensures one startup check cannot append duplicate STATUS entries during repeated state loads. */
internal class UnresolvedActionWarningGate {
    private val checked = AtomicBoolean(false)

    suspend fun load(check: suspend () -> UnresolvedActionCheck): String? {
        if (!checked.compareAndSet(false, true)) return null
        return UnresolvedActionWarning.text(check())
    }
}
