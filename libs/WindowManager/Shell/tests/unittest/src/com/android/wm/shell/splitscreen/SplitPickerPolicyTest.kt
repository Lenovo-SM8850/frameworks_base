// SPDX-License-Identifier: Apache-2.0

package com.android.wm.shell.splitscreen

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Binder
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.splitscreen.SplitPickerPolicy.Availability
import com.android.wm.shell.splitscreen.SplitScreenController.AppDragSlot
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class SplitPickerPolicyTest : ShellTestCase() {
    private val info = ActivityInfo().apply { resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE }
    private val bounds = Rect(0, 0, 600, 500)

    private fun availability(enabled: Boolean = true, suspended: Boolean = false,
        quiet: Boolean = false, unlocked: Boolean = true, visible: Boolean = false,
        force: Boolean = false, compat: Boolean = false, minWidth: Int = 100,
        minHeight: Int = 100) = SplitPickerPolicy.availability(info, bounds, enabled,
        suspended, quiet, unlocked, visible, force, compat, minWidth, minHeight)

    @Test fun replacementSelectsOnlyAnotherPaneInTheSameUserAndGroup() {
        val first = ComponentName("first", "Main")
        val second = ComponentName("second", "Main")
        val selection = SplitPickerSession(0, 42, mapOf(
            42 to SplitPickerSession.Member(10, Binder(), first),
            43 to SplitPickerSession.Member(10, Binder(), second)),
            slot(bounds, occupied = true), 7, 10, true)
        assertThat(SplitPickerPolicy.replacementMember(selection, second, 10)).isEqualTo(43)
        assertThat(SplitPickerPolicy.replacementMember(selection, first, 10)).isNull()
        assertThat(SplitPickerPolicy.replacementMember(selection, second, 11)).isNull()
        assertThat(SplitPickerPolicy.replacementMember(selection, ComponentName("other", "Main"), 10)).isNull()
        assertThat(SplitPickerPolicy.replacementMember(selection.copy(replace = false), second, 10)).isNull()
    }

    @Test fun launchableResizableAppFitsDestination() {
        assertThat(availability()).isEqualTo(Availability.AVAILABLE)
    }
    @Test fun visibleAppRemainsPresentButDisabled() {
        assertThat(availability(visible = true)).isEqualTo(Availability.ALREADY_OPEN)
    }
    @Test fun suspendedQuietLockedAndDisabledAppsCannotLaunch() {
        assertThat(availability(suspended = true)).isEqualTo(Availability.UNAVAILABLE)
        assertThat(availability(quiet = true)).isEqualTo(Availability.UNAVAILABLE)
        assertThat(availability(unlocked = false)).isEqualTo(Availability.UNAVAILABLE)
        assertThat(availability(enabled = false)).isEqualTo(Availability.UNAVAILABLE)
    }
    @Test fun compatibilityAndForceResizablePermitNonResizableApp() {
        info.resizeMode = ActivityInfo.RESIZE_MODE_UNRESIZEABLE
        assertThat(availability()).isEqualTo(Availability.INCOMPATIBLE)
        assertThat(availability(compat = true)).isEqualTo(Availability.AVAILABLE)
        assertThat(availability(force = true)).isEqualTo(Availability.AVAILABLE)
    }
    @Test fun compatibilityDoesNotBypassProposedLeafMinimums() {
        assertThat(availability(minWidth = 601, compat = true)).isEqualTo(Availability.INCOMPATIBLE)
        assertThat(availability(minHeight = 501, force = true)).isEqualTo(Availability.INCOMPATIBLE)
    }

    private fun slot(rect: Rect, hit: Rect = rect, occupied: Boolean = false) =
        AppDragSlot(0, 2, rect, hit, occupied, 0, false, 3, true)

    @Test fun floatingCentreSelectsItsOwnHalfInDisplayCoordinates() {
        val left = slot(Rect(0, 0, 490, 800))
        val right = slot(Rect(510, 0, 1000, 800))
        assertThat(SplitPickerPolicy.chooseFloatingSlot(listOf(left, right),
            Rect(700, 200, 900, 400), 100, 100)).isSameInstanceAs(right)
    }
    @Test fun floatingCentreSelectsLowerHalfOfHoveredLeaf() {
        val upper = slot(Rect(0, 0, 500, 390))
        val lower = slot(Rect(0, 410, 500, 800))
        assertThat(SplitPickerPolicy.chooseFloatingSlot(listOf(upper, lower),
            Rect(100, 500, 300, 700), 100, 100)).isSameInstanceAs(lower)
    }
    @Test fun floatingFallsBackToRemainingFeasibleRegion() {
        val tooSmall = slot(Rect(0, 0, 100, 200))
        val fallback = slot(Rect(200, 0, 800, 600))
        assertThat(SplitPickerPolicy.chooseFloatingSlot(listOf(tooSmall, fallback),
            Rect(0, 0, 80, 80), 300, 300)).isSameInstanceAs(fallback)
    }
    @Test fun fullGroupHasNoFloatingInsertionDestination() {
        assertThat(SplitPickerPolicy.chooseFloatingSlot(listOf(slot(bounds, occupied = true)),
            Rect(100, 100, 200, 200), 0, 0)).isNull()
    }
    @Test fun pickerPanelClampsToEveryStableEdge() {
        val stable = Rect(20, 80, 1000, 750)
        assertThat(SplitPickerPolicy.panelBounds(stable, Rect(900, 700, 1100, 900), 480, 560))
            .isEqualTo(Rect(520, 190, 1000, 750))
        assertThat(SplitPickerPolicy.panelBounds(stable, Rect(), 2000, 2000)).isEqualTo(stable)
    }

    private val member = SplitPickerSession.Member(10, Binder(), ComponentName("test", "App"))
    private val members = mapOf(42 to member)
    private fun session() = SplitPickerSession(0, 42, members, slot(bounds), 7, 0)

    @Test fun sessionRequiresExactGenerationAndCurrentUser() {
        val session = session()
        assertThat(session.matches(slot(bounds), members, 7, 0)).isTrue()
        assertThat(session.matches(slot(bounds), members, 8, 0)).isFalse()
        assertThat(session.matches(slot(bounds), members, 7, 10)).isFalse()
    }
    @Test fun taskDeathAndTaskIdReuseInvalidateSession() {
        assertThat(session().matches(slot(bounds), emptyMap(), 7, 0)).isFalse()
        assertThat(session().matches(slot(bounds), mapOf(42 to member.copy(token = Binder())), 7, 0))
            .isFalse()
        assertThat(session().matches(slot(bounds), mapOf(42 to member.copy(userId = 11)), 7, 0))
            .isFalse()
    }
    @Test fun resizeTopologyAndCapacityChangeInvalidateSession() {
        assertThat(session().matches(null, members, 7, 0)).isFalse()
        assertThat(session().matches(slot(Rect(0, 0, 600, 490)), members, 7, 0)).isFalse()
        val revision = AppDragSlot(0, 2, bounds, bounds, false, 0, false, 4, true)
        assertThat(session().matches(revision, members, 7, 0)).isFalse()
        val otherLeaf = AppDragSlot(0, 2, bounds, bounds, false, 1, false, 3, true)
        assertThat(session().matches(otherLeaf, members, 7, 0)).isFalse()
    }
    @Test fun replacementSessionCannotTurnIntoInsertion() {
        val occupied = slot(bounds, occupied = true)
        val replacement = SplitPickerSession(0, 42, members, occupied, 7, 0, true)
        assertThat(replacement.matches(occupied, members, 7, 0)).isTrue()
        assertThat(replacement.matches(slot(bounds), members, 7, 0)).isFalse()
        assertThat(session().matches(occupied, members, 7, 0)).isFalse()
    }

    @Test fun searchMatchesLabelsWithoutCaseOrSurroundingWhitespace() {
        assertThat(SplitPickerPolicy.matchesQuery("Calendar", " cal ")).isTrue()
        assertThat(SplitPickerPolicy.matchesQuery("Calendar", "DAR")).isTrue()
        assertThat(SplitPickerPolicy.matchesQuery("Calendar", "")).isTrue()
        assertThat(SplitPickerPolicy.matchesQuery("Calendar", "Clock")).isFalse()
        assertThat(SplitPickerPolicy.matchesQuery("Café", "CAFE", java.util.Locale.ENGLISH)).isTrue()
        assertThat(SplitPickerPolicy.matchesQuery("Cafe\u0301", "fé", java.util.Locale.ENGLISH)).isTrue()
        val turkish = java.util.Locale.forLanguageTag("tr")
        assertThat(SplitPickerPolicy.matchesQuery("Işık", "ışık", turkish)).isTrue()
        assertThat(SplitPickerPolicy.matchesQuery("İzmir", "izmir", turkish)).isTrue()
        assertThat(SplitPickerPolicy.matchesQuery("Işık", "isik", turkish)).isFalse()
    }

}
