package com.personaledge.agent

import java.util.Locale

/**
 * Whether an incoming share is one photo this app is willing to stage.
 *
 * Kept free of Android types so the accept/refuse matrix is host-testable. The decision is
 * deliberately narrow: a turn carries at most one attachment, so a multi-item share is refused
 * here rather than silently reduced to whichever item happened to be first.
 */
internal object SharedImageIntentPolicy {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    fun acceptsSharedImage(action: String?, mimeType: String?, hasStream: Boolean): Boolean {
        if (action != ACTION_SEND || !hasStream) return false
        val normalized = mimeType?.lowercase(Locale.ROOT)?.trim() ?: return false
        // "image/*" is accepted: plenty of gallery and camera share sheets send the wildcard with
        // a perfectly good URI, and the decoder validates the actual bytes regardless. Refusing it
        // here would only make real shares fail for a label.
        return normalized.startsWith("image/")
    }

    /** Explains a refused share in the owner's terms rather than silently doing nothing. */
    fun refusalMessageOrNull(action: String?, mimeType: String?, hasStream: Boolean): String? =
        when {
            acceptsSharedImage(action, mimeType, hasStream) -> null
            action == ACTION_SEND_MULTIPLE ->
                "첨부는 한 번에 하나만 보낼 수 있어 여러 장 공유는 받지 않았습니다."

            action == ACTION_SEND && hasStream ->
                "이 앱은 사진 공유만 받습니다."

            else -> null
        }
}
