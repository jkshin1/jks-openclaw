package com.personaledge.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.personaledge.agent.R
import com.personaledge.agent.VoiceCapturePolicy
import com.personaledge.agent.ui.components.SettingsSection
import com.personaledge.agent.ui.components.ToggleRow
import com.personaledge.core.llm.PinnedModelManifest

/**
 * The one switch that opens photo and voice input.
 *
 * The footnote is doing real work, not decoration. Two facts change what the owner should expect
 * and neither is visible from the UI: payload storage is bounded to an in-memory attachment plus
 * a short-lived camera cache file, and an attachment turn is given no Tool. The second is why a
 * photographed note saying "일정 등록해줘" produces a description rather than a calendar event,
 * which would otherwise look like a bug.
 */
@Composable
internal fun MediaInputSection(
    enabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
) {
    val manifest = PinnedModelManifest.value
    val supported = manifest.supportsImageInput || manifest.supportsAudioInput

    SettingsSection(
        title = "사진·음성 입력",
        footnote = buildString {
            append("사진과 음성은 기기 안에서만 처리합니다. 촬영 사진은 카메라 앱에서 넘겨받는 동안 ")
            append("앱의 임시 캐시에 저장될 수 있으며, 읽은 직후 삭제를 시도합니다. 비정상 종료로 ")
            append("남은 오래된 파일은 다음 앱 시작에, 모든 잔여 파일은 다음 촬영 준비 때 다시 ")
            append("정리합니다. 개별 삭제가 실패하면 다음 정리와 운영체제 캐시 회수를 기다립니다. ")
            append("녹음은 파일로 저장하지 않고 메모리에서 처리합니다. 대화와 내보내기에는 첨부 ")
            append("종류·출처·음성 초 수 표시만 남고 사진이나 녹음 payload는 포함되지 않습니다. ")
            append("첨부가 있는 요청은 도구를 전혀 사용하지 않으므로, 사진이나 음성에 담긴 지시는 ")
            append("실행되지 않고 내용으로만 다뤄집니다. ")
            append("음성 명령은 받아쓰기로 입력창에 옮긴 뒤 직접 확인하고 보내는 방식입니다. ")
            append("녹음은 최대 ")
            append(VoiceCapturePolicy.MAX_MILLIS / 1_000)
            append("초까지 가능합니다. ")
            append("모델은 실행할 때 필요한 인코더만 불러오므로, 이 설정을 켠 뒤에는 앱을 다시 ")
            append("시작해야 첨부를 보낼 수 있습니다. 꺼 두면 사진·음성 인코더를 아예 불러오지 ")
            append("않아 일반 대화 속도와 메모리 사용이 그대로 유지됩니다.")
        },
    ) {
        ToggleRow(
            title = "사진·음성 첨부 허용",
            subtitle = if (supported) {
                "촬영·선택한 사진과 녹음한 음성을 요청에 첨부"
            } else {
                "설치된 모델이 이 입력 형식을 지원하지 않습니다"
            },
            checked = enabled && supported,
            onCheckedChange = onSetEnabled,
            enabled = supported,
            iconRes = R.drawable.ic_camera,
            iconTint = MaterialTheme.colorScheme.primary,
            iconContainer = MaterialTheme.colorScheme.primaryContainer,
        )
    }
}
