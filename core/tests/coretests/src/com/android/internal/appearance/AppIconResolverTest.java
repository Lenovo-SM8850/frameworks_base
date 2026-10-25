// SPDX-License-Identifier: Apache-2.0

package com.android.internal.appearance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class AppIconResolverTest {
    @Test
    public void arbitraryResourceRetainsItsExactDrawableInstance() {
        Drawable raw = new ColorDrawable(Color.RED);
        assertSame(raw, AppIconResolver.resolveResource(Resources.getSystem(),
                android.R.drawable.ic_delete, 0, null, raw));
    }

    @Test
    public void nestedRawLoadsCannotReenterTheItemAdapter() {
        Drawable raw = new ColorDrawable(Color.RED);
        AppIconResolver.beginRawLoad();
        try {
            AppIconResolver.beginRawLoad();
            try {
                assertSame(raw, AppIconResolver.resolveItem(null, null, null, raw));
            } finally {
                AppIconResolver.endRawLoad();
            }
            assertSame(raw, AppIconResolver.resolveItem(null, null, null, raw));
        } finally {
            AppIconResolver.endRawLoad();
        }
    }

    @Test
    public void oversizedLayerAccountsForAdaptiveInset() {
        assertEquals(1f, AppIconResolver.visibleLayerFraction(1f)
                * (1f + 2f * AdaptiveIconDrawable.getExtraInsetFraction()), 0.0001f);
    }

    @Test
    public void fallbackMaskErasesSourceBeforeBackground() {
        Bitmap result = AppIconResolver.composeFallbackBitmap(new ColorDrawable(Color.RED),
                new ColorDrawable(Color.BLUE), new ColorDrawable(Color.WHITE), null, 1f, 64);
        assertEquals(Color.BLUE, result.getPixel(32, 32));
        assertEquals(Color.BLUE, result.getPixel(0, 0));
    }

    @Test
    public void fallbackScaleAndUponAreAppliedInOrder() {
        Bitmap result = AppIconResolver.composeFallbackBitmap(new ColorDrawable(Color.RED),
                new ColorDrawable(Color.BLUE), null, null, 0.5f, 64);
        assertEquals(Color.RED, result.getPixel(32, 32));
        assertEquals(Color.BLUE, result.getPixel(0, 0));
        Bitmap withUpon = AppIconResolver.composeFallbackBitmap(new ColorDrawable(Color.RED),
                new ColorDrawable(Color.BLUE), new ColorDrawable(Color.WHITE),
                new ColorDrawable(Color.GREEN), 0.5f, 64);
        assertEquals(Color.GREEN, withUpon.getPixel(32, 32));
    }
}
