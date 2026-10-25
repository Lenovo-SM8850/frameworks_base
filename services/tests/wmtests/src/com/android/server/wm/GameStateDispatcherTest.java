/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.util.AtomicFile;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import lineageos.health.HealthInterface;
import lineageos.providers.LineageSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class GameStateDispatcherTest {
    private static final String ENABLED = LineageSettings.System.CHARGING_CONTROL_ENABLED;
    private static final String MODE = LineageSettings.System.CHARGING_CONTROL_MODE;
    private static final String LIMIT = LineageSettings.System.CHARGING_CONTROL_LIMIT;
    private static final ComponentName SESSION = new ComponentName(
            "io.chaldeaprjkt.gamespace", "io.chaldeaprjkt.gamespace.gamebar.SessionService");

    @Rule public TemporaryFolder mFolder = new TemporaryFolder();

    private final Map<String, String> mSettings = new HashMap<>();
    private final Context mContext = mock(Context.class);
    private final ContentResolver mResolver = mock(ContentResolver.class);
    private final IActivityManager mActivityManager = mock(IActivityManager.class);
    private final HealthInterface mHealth = mock(HealthInterface.class);
    private final BatteryManager mBattery = mock(BatteryManager.class);
    private final TestLooper mLooper = new TestLooper();
    private final Handler mHandler = new Handler(mLooper.getLooper());
    private MockitoSession mMocks;
    private AtomicFile mSaved;
    private GameStateDispatcher mDispatcher;
    private ServiceConnection mConnection;
    private ContentObserver mObserver;
    private boolean mToggle = true;
    private int mSessionStartCount;

    @Before
    public void setUp() throws Exception {
        mMocks = mockitoSession().strictness(Strictness.LENIENT)
                .mockStatic(ActivityManager.class)
                .mockStatic(Settings.System.class)
                .mockStatic(Settings.Secure.class)
                .mockStatic(LineageSettings.System.class)
                .startMocking();
        doReturn(0).when(ActivityManager::getCurrentUser);
        doAnswer(invocation -> mToggle ? 1 : 0).when(() -> Settings.System.getIntForUser(
                mResolver, "bypass_charge_enabled", 0, 0));
        doAnswer(invocation -> mSettings.get(invocation.getArgument(1))).when(
                () -> LineageSettings.System.getStringForUser(any(), any(), anyInt()));
        doAnswer(invocation -> {
            String key = invocation.getArgument(1);
            String value = invocation.getArgument(2);
            if (value == null) {
                mSettings.remove(key);
            } else {
                mSettings.put(key, value);
            }
            return true;
        }).when(() -> LineageSettings.System.putStringForUser(any(), any(), any(), anyInt()));
        when(mContext.getContentResolver()).thenReturn(mResolver);
        when(mContext.getSystemService(BatteryManager.class)).thenReturn(mBattery);
        when(mBattery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).thenReturn(50);
        when(mHealth.isChargingControlSupported()).thenReturn(true);
        when(mHealth.getEnabled()).thenAnswer(invocation -> "1".equals(mSettings.get(ENABLED)));
        when(mHealth.getMode()).thenAnswer(invocation ->
                Integer.parseInt(mSettings.getOrDefault(MODE, "1")));
        when(mHealth.getLimit()).thenAnswer(invocation ->
                Integer.parseInt(mSettings.getOrDefault(LIMIT, "80")));
        when(mHealth.setEnabled(anyBoolean())).thenAnswer(invocation -> {
            mSettings.put(ENABLED, (boolean) invocation.getArgument(0) ? "1" : "0");
            return true;
        });
        when(mHealth.setMode(anyInt())).thenAnswer(invocation -> {
            mSettings.put(MODE, invocation.getArgument(0).toString());
            return true;
        });
        when(mHealth.setLimit(anyInt())).thenAnswer(invocation -> {
            mSettings.put(LIMIT, invocation.getArgument(0).toString());
            return true;
        });
        when(mActivityManager.startService(any(), any(), any(), eq(false),
                any(), any(), anyInt())).thenAnswer(invocation -> {
                    mSessionStartCount++;
                    return SESSION;
                });
        doAnswer(invocation -> {
            mConnection = invocation.getArgument(1);
            return true;
        }).when(mContext).bindServiceAsUser(any(), any(), eq(0), eq(mHandler), any());
        doAnswer(invocation -> {
            mObserver = invocation.getArgument(2);
            return null;
        }).when(mResolver).registerContentObserver(any(), eq(false), any(),
                eq(UserHandle.USER_ALL));
        mSettings.put(ENABLED, "1");
        mSettings.put(MODE, "2");
        mSettings.put(LIMIT, "90");
        mSaved = new AtomicFile(mFolder.newFile());
        mSaved.delete();
        mDispatcher = newDispatcher();
        mDispatcher.initialize();
    }

    @After
    public void tearDown() {
        mMocks.finishMocking();
    }

    private GameStateDispatcher newDispatcher() {
        return new GameStateDispatcher(mContext, mHandler, mActivityManager, mSaved, () -> mHealth);
    }

    @Test
    public void visibleFloatingOrMiniWindow_focusElsewhere_keepsBypass() {
        Task task = visibleGameTask(WINDOWING_MODE_FREEFORM);
        assertFalse(task.isFocused());
        enterGame();
        setGameVisible(GameSpaceService.isGameTaskVisible(task));
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS + 1);
        assertBypass();
        verify(mHealth, times(1)).setLimit(50);
    }

    @Test
    public void fullscreenGame_underSidebarOrGameSpaceWindow_keepsBypass() {
        Task task = visibleGameTask(WINDOWING_MODE_FULLSCREEN);
        Task floatingApp = mock(Task.class);
        when(floatingApp.getBaseIntent()).thenReturn(new Intent().setComponent(
                new ComponentName("test.browser", "test.browser.MainActivity")));
        when(floatingApp.isFocused()).thenReturn(true);
        when(floatingApp.getWindowingMode()).thenReturn(WINDOWING_MODE_FREEFORM);
        assertEquals(null, GameSpaceService.getGamePackageName(floatingApp, "test.game"::equals));
        assertFalse(task.isFocused());
        assertTrue(floatingApp.isFocused());
        enterGame();
        setGameVisible(GameSpaceService.isGameTaskVisible(task));
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS + 1);
        assertBypass();
        verify(mHealth, times(1)).setLimit(50);
    }

}
