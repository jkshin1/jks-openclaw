package com.personaledge.agent.ui

/** Content-free, app-owned snapshot of the current application window. */
internal data class WorkspaceWindowState(
    val widthDp: Float = 0f,
    val heightDp: Float = 0f,
    /** Top app chrome already removed from content but still included in [heightDp]. */
    val contentTopInsetDp: Float = 0f,
    val isInMultiWindowMode: Boolean = false,
    val separatingHinge: WorkspaceHinge? = null,
)

internal data class WorkspaceHinge(
    val orientation: WorkspaceHingeOrientation,
    val startDp: Float,
    val endDp: Float,
    val posture: WorkspaceHingePosture,
)

internal enum class WorkspaceHingeOrientation {
    VERTICAL,
    HORIZONTAL,
}

internal enum class WorkspaceHingePosture {
    FLAT,
    HALF_OPENED,
}

/** Primitive hand-off used to erase AndroidX WindowManager types at the Activity boundary. */
internal data class WorkspaceFoldingSnapshot(
    val isSeparating: Boolean,
    val orientation: WorkspaceHingeOrientation,
    val startPx: Int,
    val endPx: Int,
    val posture: WorkspaceHingePosture,
)

internal object WorkspaceWindowStateMapper {
    fun fromPixels(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        isInMultiWindowMode: Boolean,
        folding: WorkspaceFoldingSnapshot?,
    ): WorkspaceWindowState {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        return WorkspaceWindowState(
            widthDp = widthPx.coerceAtLeast(0) / safeDensity,
            heightDp = heightPx.coerceAtLeast(0) / safeDensity,
            isInMultiWindowMode = isInMultiWindowMode,
            separatingHinge = folding
                ?.takeIf(WorkspaceFoldingSnapshot::isSeparating)
                ?.let { feature ->
                    WorkspaceHinge(
                        orientation = feature.orientation,
                        startDp = feature.startPx / safeDensity,
                        endDp = feature.endPx / safeDensity,
                        posture = feature.posture,
                    )
                },
        )
    }
}

internal enum class WorkspaceLayout {
    COVER,
    TWO_PANE,
    BOOK,
    TABLETOP,
}

internal data class WorkspaceLayoutPlan(
    val layout: WorkspaceLayout,
    val hingeStartDp: Float? = null,
    val hingeEndDp: Float? = null,
) {
    val hingeSizeDp: Float
        get() = ((hingeEndDp ?: 0f) - (hingeStartDp ?: 0f)).coerceAtLeast(0f)
}

internal object WorkspaceLayoutPolicy {
    const val TWO_PANE_MIN_WIDTH_DP = 700f
    const val TWO_PANE_MIN_HEIGHT_DP = 480f
    const val MIN_VERTICAL_PANE_WIDTH_DP = 272f
    const val MIN_HORIZONTAL_PANE_HEIGHT_DP = 220f
    const val MIN_TABLETOP_WIDTH_DP = 360f
    const val MAX_HINGE_SIZE_DP = 96f
    const val TWO_PANE_TODAY_WEIGHT = 0.38f
    const val TWO_PANE_CONVERSATION_WEIGHT = 0.62f

    /** A real separating posture wins over a width heuristic, including in multi-window/DeX. */
    fun forWindow(state: WorkspaceWindowState): WorkspaceLayoutPlan {
        val usableHeight = usableHeight(state)
        val hinge = state.separatingHinge
        if (hinge != null) {
            val axisSize = when (hinge.orientation) {
                WorkspaceHingeOrientation.VERTICAL -> state.widthDp
                WorkspaceHingeOrientation.HORIZONTAL -> state.heightDp
            }
            if (isUsableSeparatingHinge(state, hinge, axisSize, usableHeight)) {
                return WorkspaceLayoutPlan(
                    layout = when (hinge.orientation) {
                        WorkspaceHingeOrientation.VERTICAL -> WorkspaceLayout.BOOK
                        WorkspaceHingeOrientation.HORIZONTAL -> WorkspaceLayout.TABLETOP
                    },
                    hingeStartDp = hinge.startDp,
                    hingeEndDp = hinge.endDp,
                )
            }
        }
        return WorkspaceLayoutPlan(
            layout = if (canUseTwoPane(state.widthDp, usableHeight)) {
                WorkspaceLayout.TWO_PANE
            } else {
                WorkspaceLayout.COVER
            },
        )
    }

    fun forWidthDp(widthDp: Float): WorkspaceLayout = forWindow(
        WorkspaceWindowState(
            widthDp = widthDp.coerceAtLeast(0f),
            heightDp = TWO_PANE_MIN_HEIGHT_DP,
        ),
    ).layout

    private fun usableHeight(state: WorkspaceWindowState): Float {
        if (
            !state.heightDp.isFinite() ||
            !state.contentTopInsetDp.isFinite() ||
            state.heightDp <= 0f ||
            state.contentTopInsetDp < 0f ||
            state.contentTopInsetDp >= state.heightDp
        ) {
            return 0f
        }
        return state.heightDp - state.contentTopInsetDp
    }

    private fun canUseTwoPane(widthDp: Float, usableHeightDp: Float): Boolean =
        widthDp.isFinite() &&
            widthDp >= TWO_PANE_MIN_WIDTH_DP &&
            usableHeightDp >= TWO_PANE_MIN_HEIGHT_DP &&
            widthDp * TWO_PANE_TODAY_WEIGHT >= MIN_VERTICAL_PANE_WIDTH_DP &&
            widthDp * TWO_PANE_CONVERSATION_WEIGHT >= MIN_VERTICAL_PANE_WIDTH_DP

    private fun isUsableSeparatingHinge(
        state: WorkspaceWindowState,
        hinge: WorkspaceHinge,
        axisSize: Float,
        usableHeightDp: Float,
    ): Boolean {
        if (
            !axisSize.isFinite() ||
            axisSize <= 0f ||
            !hinge.startDp.isFinite() ||
            !hinge.endDp.isFinite() ||
            hinge.startDp <= 0f ||
            hinge.endDp < hinge.startDp ||
            hinge.endDp >= axisSize ||
            hinge.endDp - hinge.startDp > MAX_HINGE_SIZE_DP
        ) {
            return false
        }
        return when (hinge.orientation) {
            WorkspaceHingeOrientation.VERTICAL ->
                usableHeightDp >= TWO_PANE_MIN_HEIGHT_DP &&
                    hinge.startDp >= MIN_VERTICAL_PANE_WIDTH_DP &&
                    state.widthDp - hinge.endDp >= MIN_VERTICAL_PANE_WIDTH_DP
            WorkspaceHingeOrientation.HORIZONTAL -> {
                val leadingPaneHeight = hinge.startDp - state.contentTopInsetDp
                val trailingPaneHeight = state.heightDp - hinge.endDp
                state.widthDp.isFinite() &&
                    state.widthDp >= MIN_TABLETOP_WIDTH_DP &&
                    leadingPaneHeight >= MIN_HORIZONTAL_PANE_HEIGHT_DP &&
                    trailingPaneHeight >= MIN_HORIZONTAL_PANE_HEIGHT_DP
            }
        }
    }
}
