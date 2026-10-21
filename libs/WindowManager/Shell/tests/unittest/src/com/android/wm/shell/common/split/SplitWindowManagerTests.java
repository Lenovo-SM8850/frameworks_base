/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.common.split;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.ViewGroup;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.shared.desktopmode.FakeDesktopState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link SplitWindowManager} */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SplitWindowManagerTests extends ShellTestCase {
    @Mock SplitLayout mSplitLayout;
    @Mock SplitWindowManager.ParentContainerCallbacks mCallbacks;
    private FakeDesktopState mDesktopState;
    private SplitWindowManager mSplitWindowManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        final Configuration configuration = new Configuration();
        configuration.setToDefaults();
        mSplitWindowManager = new SplitWindowManager("TestSplitDivider", mContext, configuration,
                mCallbacks);
        mDesktopState = new FakeDesktopState();
        when(mSplitLayout.getDividerBounds()).thenReturn(
                new Rect(0, 0, configuration.windowConfiguration.getBounds().width(),
                        configuration.windowConfiguration.getBounds().height()));
    }

    @Test
    @UiThreadTest
    public void testInitRelease() {
        mSplitWindowManager.init(mSplitLayout, new InsetsState(), false /* isRestoring */,
                mDesktopState);
        assertThat(mSplitWindowManager.getSurfaceControl()).isNotNull();
        mSplitWindowManager.release(null /* t */);
        assertThat(mSplitWindowManager.getSurfaceControl()).isNull();
    }

    @Test
    @UiThreadTest
    public void testRelease_invalidLeash_doesNotCrash() {
        // Initialize the divider, which creates a leash.
        mSplitWindowManager.init(mSplitLayout, new InsetsState(), false /* isRestoring */,
                mDesktopState);
        SurfaceControl leash = mSplitWindowManager.getSurfaceControl();
        assertThat(leash).isNotNull();

        // Manually release the leash to make it invalid before the window manager does.
        new SurfaceControl.Transaction().remove(leash).apply();

        // Act: Call release on the window manager. The change prevents this from crashing
        // by checking leash.isValid().
        mSplitWindowManager.release(null /* t */);

        // Assert: The internal leash reference should be cleared.
        assertThat(mSplitWindowManager.getSurfaceControl()).isNull();
    }

    @Test
    @UiThreadTest
    public void testRelease_calledTwice_doesNotCrash() {
        // Initialize and then release the divider.
        mSplitWindowManager.init(mSplitLayout, new InsetsState(), false /* isRestoring */,
                mDesktopState);
        mSplitWindowManager.release(null /* t */);
        assertThat(mSplitWindowManager.getSurfaceControl()).isNull();

        // Act: Call release again. This should be a no-op and not crash, as the leash
        // is now null.
        mSplitWindowManager.release(null /* t */);

        // Assert: The leash is still null.
        assertThat(mSplitWindowManager.getSurfaceControl()).isNull();
    }
    @Test
    @UiThreadTest
    public void testMultiSplitWindow_recreatesAtBranchSizeAndReleasesOldSurface() {
        when(mSplitLayout.isMultiSplitDivider()).thenReturn(true);
        when(mSplitLayout.isLeftRightSplit()).thenReturn(true);
        when(mSplitLayout.getMultiSplitDividerCrop()).thenReturn(new Rect(0, 0, 100, 800));
        when(mSplitLayout.getDividerBounds()).thenReturn(new Rect(500, 0, 600, 800));
        mSplitWindowManager.init(mSplitLayout, new InsetsState(), false, mDesktopState);
        assertThat(mSplitWindowManager.getDividerView().getLayoutParams().height).isEqualTo(800);
        final SurfaceControl previous = mSplitWindowManager.getSurfaceControl();
        mSplitWindowManager.release(null);
        assertThat(mSplitWindowManager.getSurfaceControl()).isNull();
        when(mSplitLayout.getDividerBounds()).thenReturn(new Rect(500, 808, 600, 2000));
        when(mSplitLayout.getMultiSplitDividerCrop()).thenReturn(new Rect(0, 0, 100, 1192));
        mSplitWindowManager.init(mSplitLayout, new InsetsState(), true, mDesktopState);
        assertThat(mSplitWindowManager.getSurfaceControl()).isNotSameInstanceAs(previous);
        assertThat(mSplitWindowManager.getDividerView().getLayoutParams().height).isEqualTo(1192);
        mSplitWindowManager.release(null);
    }

    @Test
    @UiThreadTest
    public void testPairWindow_fitsInputRegionAcrossAxesAndDesktopModes() {
        assertWindowThickness(false, false);
    }

    @Test
    @UiThreadTest
    public void testBranchWindow_fitsInputRegionAcrossAxesAndDesktopModes() {
        assertWindowThickness(true, false);
    }

    @Test
    @UiThreadTest
    public void testPairWindow_preservesLargerCornerAllowanceAcrossAxes() {
        assertWindowThickness(false, true);
    }

    @Test
    @UiThreadTest
    public void testBranchWindow_preservesLargerCornerAllowanceAcrossAxes() {
        assertWindowThickness(true, true);
    }

    private void assertWindowThickness(boolean branch, boolean largeCorners) {
        final Resources resources = mContext.getResources();
        final int regionThickness = resources.getDimensionPixelSize(
                R.dimen.split_divider_handle_region_height);
        final int baseThickness = resources.getDimensionPixelSize(R.dimen.split_divider_bar_width)
                + 2 * resources.getDimensionPixelSize(
                        com.android.internal.R.dimen.docked_stack_divider_insets);
        for (boolean leftRight : new boolean[]{true, false}) {
            for (boolean desktop : new boolean[]{false, true}) {
                mDesktopState.setCanEnterDesktopMode(desktop);
                final int inputThickness = !leftRight && desktop
                        ? resources.getDimensionPixelSize(
                                R.dimen.desktop_mode_portrait_split_divider_handle_region_height)
                        : regionThickness;
                final int decorationThickness = largeCorners
                        ? Math.max(baseThickness, regionThickness) + 20 : baseThickness;
                final int expectedThickness = Math.max(decorationThickness, inputThickness);
                final int length = 800;
                when(mSplitLayout.isMultiSplitDivider()).thenReturn(branch);
                when(mSplitLayout.isLeftRightSplit()).thenReturn(leftRight);
                when(mSplitLayout.getDividerBounds()).thenReturn(leftRight
                        ? new Rect(500, 0, 500 + decorationThickness, length)
                        : new Rect(0, 500, length, 500 + decorationThickness));
                when(mSplitLayout.getMultiSplitDividerCrop()).thenReturn(leftRight
                        ? new Rect(0, 0, expectedThickness, length)
                        : new Rect(0, 0, length, expectedThickness));
                mSplitWindowManager.init(mSplitLayout, new InsetsState(), false, mDesktopState);
                try {
                    final ViewGroup.LayoutParams params =
                            mSplitWindowManager.getDividerView().getLayoutParams();
                    assertThat(leftRight ? params.width : params.height)
                            .isEqualTo(expectedThickness);
                    assertThat(leftRight ? params.height : params.width).isEqualTo(length);
                    assertThat(mSplitWindowManager.getWindowExpansion())
                            .isEqualTo(expectedThickness - decorationThickness);
                } finally {
                    mSplitWindowManager.release(null);
                }
            }
        }
    }
}
