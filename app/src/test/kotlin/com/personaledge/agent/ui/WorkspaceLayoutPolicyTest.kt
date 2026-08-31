package com.personaledge.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceLayoutPolicyTest {
    @Test
    fun coverAndUnfoldedWidthsUseDifferentInformationArchitectures() {
        assertEquals(WorkspaceLayout.COVER, WorkspaceLayoutPolicy.forWidthDp(399f))
        assertEquals(WorkspaceLayout.COVER, WorkspaceLayoutPolicy.forWidthDp(699f))
        assertEquals(WorkspaceLayout.COVER, WorkspaceLayoutPolicy.forWidthDp(700f))
        assertEquals(WorkspaceLayout.TWO_PANE, WorkspaceLayoutPolicy.forWidthDp(716f))
        assertEquals(WorkspaceLayout.TWO_PANE, WorkspaceLayoutPolicy.forWidthDp(920f))
    }

    @Test
    fun separatingVerticalBookHingeOverridesTheWidthBreakpoint() {
        val state = WorkspaceWindowStateMapper.fromPixels(
            widthPx = 1_200,
            heightPx = 1_800,
            density = 2f,
            isInMultiWindowMode = false,
            folding = WorkspaceFoldingSnapshot(
                isSeparating = true,
                orientation = WorkspaceHingeOrientation.VERTICAL,
                startPx = 580,
                endPx = 620,
                posture = WorkspaceHingePosture.FLAT,
            ),
        )

        val plan = WorkspaceLayoutPolicy.forWindow(state)

        assertEquals(WorkspaceLayout.BOOK, plan.layout)
        assertEquals(290f, plan.hingeStartDp)
        assertEquals(310f, plan.hingeEndDp)
        assertEquals(20f, plan.hingeSizeDp)
    }

    @Test
    fun separatingHorizontalTabletopHingeOverridesWideTwoPaneLayout() {
        val state = WorkspaceWindowState(
            widthDp = 900f,
            heightDp = 700f,
            separatingHinge = WorkspaceHinge(
                orientation = WorkspaceHingeOrientation.HORIZONTAL,
                startDp = 330f,
                endDp = 350f,
                posture = WorkspaceHingePosture.HALF_OPENED,
            ),
        )

        val plan = WorkspaceLayoutPolicy.forWindow(state)

        assertEquals(WorkspaceLayout.TABLETOP, plan.layout)
        assertEquals(330f, plan.hingeStartDp)
        assertEquals(350f, plan.hingeEndDp)
    }

    @Test
    fun resizeAndMultiWindowUseCurrentBoundsWhenThereIsNoSeparatingFeature() {
        assertEquals(
            WorkspaceLayout.COVER,
            WorkspaceLayoutPolicy.forWindow(
                WorkspaceWindowState(
                    widthDp = 699f,
                    heightDp = 800f,
                    isInMultiWindowMode = true,
                ),
            ).layout,
        )
        assertEquals(
            WorkspaceLayout.COVER,
            WorkspaceLayoutPolicy.forWindow(
                WorkspaceWindowState(
                    widthDp = 700f,
                    heightDp = 800f,
                    isInMultiWindowMode = true,
                ),
            ).layout,
        )
        assertEquals(
            WorkspaceLayout.TWO_PANE,
            WorkspaceLayoutPolicy.forWindow(
                WorkspaceWindowState(
                    widthDp = 716f,
                    heightDp = 800f,
                    isInMultiWindowMode = true,
                ),
            ).layout,
        )
    }

    @Test
    fun nonseparatingAndOutOfWindowFeaturesFallBackWithoutInventingAPosture() {
        val nonSeparating = WorkspaceWindowStateMapper.fromPixels(
            widthPx = 1_800,
            heightPx = 1_400,
            density = 2f,
            isInMultiWindowMode = false,
            folding = WorkspaceFoldingSnapshot(
                isSeparating = false,
                orientation = WorkspaceHingeOrientation.VERTICAL,
                startPx = 880,
                endPx = 920,
                posture = WorkspaceHingePosture.FLAT,
            ),
        )
        assertNull(nonSeparating.separatingHinge)
        assertEquals(WorkspaceLayout.TWO_PANE, WorkspaceLayoutPolicy.forWindow(nonSeparating).layout)

        val invalid = WorkspaceWindowState(
            widthDp = 600f,
            heightDp = 800f,
            separatingHinge = WorkspaceHinge(
                orientation = WorkspaceHingeOrientation.VERTICAL,
                startDp = 500f,
                endDp = 650f,
                posture = WorkspaceHingePosture.FLAT,
            ),
        )
        assertEquals(WorkspaceLayout.COVER, WorkspaceLayoutPolicy.forWindow(invalid).layout)
    }

    @Test
    fun shortDexAndFreeformWindowsStaySinglePaneEvenWhenWide() {
        assertEquals(
            WorkspaceLayout.COVER,
            WorkspaceLayoutPolicy.forWindow(
                WorkspaceWindowState(widthDp = 1_000f, heightDp = 479f),
            ).layout,
        )
        assertEquals(
            WorkspaceLayout.COVER,
            WorkspaceLayoutPolicy.forWindow(
                WorkspaceWindowState(
                    widthDp = 1_000f,
                    heightDp = 560f,
                    contentTopInsetDp = 100f,
                ),
            ).layout,
        )
        assertEquals(
            WorkspaceLayout.TWO_PANE,
            WorkspaceLayoutPolicy.forWindow(
                WorkspaceWindowState(
                    widthDp = 1_000f,
                    heightDp = 580f,
                    contentTopInsetDp = 100f,
                ),
            ).layout,
        )
    }

    @Test
    fun separatingHingesMustLeaveUsablePanesAndHaveSaneBounds() {
        val nearEdgeBook = WorkspaceWindowState(
            widthDp = 700f,
            heightDp = 800f,
            separatingHinge = WorkspaceHinge(
                orientation = WorkspaceHingeOrientation.VERTICAL,
                startDp = 40f,
                endDp = 60f,
                posture = WorkspaceHingePosture.HALF_OPENED,
            ),
        )
        assertEquals(WorkspaceLayout.COVER, WorkspaceLayoutPolicy.forWindow(nearEdgeBook).layout)

        val shortTabletopTopPane = WorkspaceWindowState(
            widthDp = 600f,
            heightDp = 700f,
            contentTopInsetDp = 100f,
            separatingHinge = WorkspaceHinge(
                orientation = WorkspaceHingeOrientation.HORIZONTAL,
                startDp = 300f,
                endDp = 320f,
                posture = WorkspaceHingePosture.HALF_OPENED,
            ),
        )
        assertEquals(
            WorkspaceLayout.COVER,
            WorkspaceLayoutPolicy.forWindow(shortTabletopTopPane).layout,
        )

        val oversizedOcclusion = WorkspaceWindowState(
            widthDp = 700f,
            heightDp = 800f,
            separatingHinge = WorkspaceHinge(
                orientation = WorkspaceHingeOrientation.VERTICAL,
                startDp = 280f,
                endDp = 400f,
                posture = WorkspaceHingePosture.FLAT,
            ),
        )
        assertEquals(
            WorkspaceLayout.COVER,
            WorkspaceLayoutPolicy.forWindow(oversizedOcclusion).layout,
        )
    }
}
