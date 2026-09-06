package com.personaledge.agent

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly selected, provider-free production UI receipt for a matched signed release pair.
 *
 * This only selects the remote pane, opens its configuration explanation, cancels it, and returns
 * to the local pane. It never enters credentials, connects, submits a prompt, deletes data, opens
 * a provider, or changes Android permissions. Run after artifact and preservation preflight.
 */
@RunWith(AndroidJUnit4::class)
class Fold8OpenClawSurfaceAcceptanceTest {
    @Test
    fun ownerCanInspectRemoteSetupAndReturnToTheLocalModelWithoutSending() {
        assumeTrue(
            "Remote surface acceptance requires -e openClawSurfaceAcceptance true.",
            InstrumentationRegistry.getArguments().getString("openClawSurfaceAcceptance") == "true",
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitOpenClawText("Mac · 원격")
            clickOpenClawText("Mac · 원격")
            awaitOpenClawText("연결 설정")
            awaitOpenClawText("Mac에 보낼 질문")
            assertFalse(hasOpenClawText("원격 실행 취소"))
            clickOpenClawText("연결 설정")
            awaitOpenClawText("Mac 원격 연결")
            awaitOpenClawText("Gateway 토큰")
            awaitOpenClawPasswordField()
            clickOpenClawText("취소")
            awaitOpenClawTextAbsent("Mac 원격 연결")
            clickOpenClawText("휴대폰 · 로컬")
            awaitOpenClawTextAbsent("연결 설정")
        }
    }
}
