// SPDX-License-Identifier: Apache-2.0
package android.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AudioPlaybackConfigurationTest {
    private static AudioPlaybackConfiguration player() {
        PlayerBase.PlayerIdCard card = new PlayerBase.PlayerIdCard(
                AudioPlaybackConfiguration.PLAYER_TYPE_JAM_AUDIOTRACK,
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
                null, 12);
        return new AudioPlaybackConfiguration(card, 1, 1010001, 42);
    }

    @Test
    public void markerDoesNotChangeActivityAndUpdatesIdempotently() {
        AudioPlaybackConfiguration config = player();
        assertFalse(config.hasStartedJocCodecForUid());
        assertTrue(config.setHasStartedJocCodecForUid(true));
        assertFalse(config.setHasStartedJocCodecForUid(true));
        assertFalse(config.isActive());
        config.handleStateEvent(AudioPlaybackConfiguration.PLAYER_STATE_STARTED, new int[0]);
        assertTrue(config.isActive());
        assertTrue(config.setHasStartedJocCodecForUid(false));
        assertTrue(config.isActive());
    }

    @Test
    public void markerRoundTripsWithFullAndroidUid() {
        AudioPlaybackConfiguration config = player();
        config.setHasStartedJocCodecForUid(true);
        Parcel parcel = Parcel.obtain();
        try {
            config.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            AudioPlaybackConfiguration copy =
                    AudioPlaybackConfiguration.CREATOR.createFromParcel(parcel);
            assertEquals(1010001, copy.getClientUid());
            assertTrue(copy.hasStartedJocCodecForUid());
            assertEquals(config, copy);
            assertEquals(config.hashCode(), copy.hashCode());
            assertTrue(copy.toString().contains("hasStartedJocCodecForUid:true"));
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void anonymizedCopyRemovesUidSelectionMarker() {
        AudioPlaybackConfiguration config = player();
        config.setHasStartedJocCodecForUid(true);
        AudioPlaybackConfiguration anonymous = AudioPlaybackConfiguration.anonymizedCopy(config);
        assertEquals(-1, anonymous.getClientUid());
        assertFalse(anonymous.hasStartedJocCodecForUid());
    }

    @Test
    public void equalityIncludesUidSelectionMarker() {
        AudioPlaybackConfiguration first = player();
        AudioPlaybackConfiguration second = player();
        assertEquals(first, second);
        first.setHasStartedJocCodecForUid(true);
        assertNotEquals(first, second);
        second.setHasStartedJocCodecForUid(true);
        assertEquals(first, second);
    }
}
