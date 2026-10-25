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

    private void enterGame() {
        mDispatcher.dispatchGameState(true, "test.game");
        mConnection.onServiceConnected(SESSION, new Binder());
    }

    private void assertBypass() {
        assertEquals("1", mSettings.get(ENABLED));
        assertEquals("3", mSettings.get(MODE));
        assertEquals("50", mSettings.get(LIMIT));
        assertTrue(mSaved.exists());
    }

    @Test
    public void belowSystemRange_enabled_restoresAllSettings() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        assertBypass();
        mDispatcher.dispatchGameState(false, null);
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }

    @Test
    public void belowSystemRange_disabled_restoresConfiguredLimit() {
        mSettings.put(ENABLED, "0");
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        assertBypass();
        mDispatcher.dispatchGameState(false, null);
        assertEquals(original, mSettings);
    }

    @Test
    public void absentSettings_restoredAsAbsent() {
        mSettings.clear();
        enterGame();
        assertBypass();
        mDispatcher.dispatchGameState(false, null);
        assertEquals(null, mSettings.get(ENABLED));
        assertEquals(null, mSettings.get(MODE));
        assertEquals(null, mSettings.get(LIMIT));
    }

    @Test
    public void rememberedOff_doesNotChangeCharging() {
        mToggle = false;
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }

    @Test
    public void noLiveSession_doesNotChangeCharging() {
        Map<String, String> original = new HashMap<>(mSettings);
        mDispatcher.dispatchGameState(true, "test.game");
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }

    @Test
    public void tileDuringGame_appliesAndRestoresImmediately() {
        mToggle = false;
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        mToggle = true;
        mObserver.onChange(false);
        assertBypass();
        mToggle = false;
        mObserver.onChange(false);
        assertEquals(original, mSettings);
    }

    @Test
    public void duplicateStart_doesNotOverwriteOriginal() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        mDispatcher.dispatchGameState(true, "test.game");
        mDispatcher.dispatchGameState(false, null);
        assertEquals(original, mSettings);
    }

    private void setGameVisible(boolean visible) {
        mDispatcher.setGameVisible(visible, () -> mDispatcher.dispatchGameState(false, null));
    }

    private void advanceTime(long millis) {
        mLooper.moveTimeForward(millis);
        mLooper.dispatchAll();
    }

    private Task visibleGameTask(int windowingMode) {
        Task task = mock(Task.class);
        when(task.getBaseIntent()).thenReturn(new Intent().setComponent(
                new ComponentName("test.game", "test.game.MainActivity")));
        when(task.isAttached()).thenReturn(true);
        when(task.isVisibleRequested()).thenReturn(true);
        when(task.getWindowingMode()).thenReturn(windowingMode);
        when(task.isFocused()).thenReturn(false);
        return task;
    }

    @Test
    public void inGameBrowser_keepsGameTaskIdentityAndBypass() {
        Task task = visibleGameTask(WINDOWING_MODE_FULLSCREEN);
        // The top activity is not a listed game; the task's base intent still is.
        ActivityRecord browser = mock(ActivityRecord.class);
        when(task.getTopMostActivity()).thenReturn(browser);
        assertEquals("test.game", GameSpaceService.getGamePackageName(task, "test.game"::equals));
        enterGame();
        setGameVisible(GameSpaceService.isGameTaskVisible(task));
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS + 1);
        assertBypass();
        verify(mHealth, times(1)).setLimit(50);
    }

    @Test
    public void floatingOrMiniWindow_homeHideShow_keepsOriginalSnapshot() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        setGameVisible(false);
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS / 2);
        assertBypass();
        setGameVisible(true);
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS + 1);
        assertBypass();
        verify(mHealth, times(1)).setLimit(50);
        mDispatcher.dispatchGameState(false, null);
        assertEquals(original, mSettings);
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

    @Test
    public void secondGameOpenedFloating_keepsVisibleCurrentOwnerAndBypass() {
        Task current = visibleGameTask(WINDOWING_MODE_FULLSCREEN);
        Task floating = visibleGameTask(WINDOWING_MODE_FREEFORM);
        when(floating.getBaseIntent()).thenReturn(new Intent().setComponent(
                new ComponentName("test.game.second", "test.game.second.MainActivity")));
        java.util.function.Predicate<Task> isGame = task -> GameSpaceService.getGamePackageName(
                task, name -> "test.game".equals(name) || "test.game.second".equals(name)) != null;
        enterGame();
        final int starts = mSessionStartCount;
        final ServiceConnection connection = mConnection;
        assertSame(current, GameSpaceService.getVisibleGameTask(current, floating, isGame));
        setGameVisible(GameSpaceService.isGameTaskVisible(current));
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS + 1);
        assertSame(current, GameSpaceService.getVisibleGameTask(current, current, isGame));
        assertSame(connection, mConnection);
        assertEquals(starts, mSessionStartCount);
        verify(mContext, never()).unbindService(any());
        verify(mHealth, times(1)).setLimit(50);
        assertBypass();
        when(current.isVisibleRequested()).thenReturn(false);
        assertSame(floating, GameSpaceService.getVisibleGameTask(current, floating, isGame));
        when(current.isVisibleRequested()).thenReturn(true);
        assertSame(floating, GameSpaceService.getVisibleGameTask(
                current, floating, task -> task != current && isGame.test(task)));
    }

    @Test
    public void sessionDisconnect_restartsAndReappliesBypassWithoutRebinding() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        final ServiceConnection connection = mConnection;
        final int starts = mSessionStartCount;
        connection.onServiceDisconnected(SESSION);
        assertEquals(original, mSettings);
        advanceTime(GameStateDispatcher.SESSION_RETRY_DELAY_MS / 2);
        assertEquals(starts, mSessionStartCount);
        advanceTime(GameStateDispatcher.SESSION_RETRY_DELAY_MS / 2 + 1);
        assertEquals(starts + 1, mSessionStartCount);
        assertSame(connection, mConnection);
        verify(mContext, never()).unbindService(any());
        connection.onServiceConnected(SESSION, new Binder());
        assertBypass();
        final int reconnectedStarts = mSessionStartCount;
        advanceTime(GameStateDispatcher.SESSION_RETRY_DELAY_MS + 1);
        assertEquals(reconnectedStarts, mSessionStartCount);
        mDispatcher.dispatchGameState(false, null);
        assertEquals(original, mSettings);
    }

    @Test
    public void bindingDied_rebindsAndIgnoresOldCallbacks() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        final ServiceConnection old = mConnection;
        old.onBindingDied(SESSION);
        assertEquals(original, mSettings);
        verify(mContext).unbindService(old);
        advanceTime(GameStateDispatcher.SESSION_RETRY_DELAY_MS + 1);
        assertNotSame(old, mConnection);
        old.onServiceConnected(SESSION, new Binder());
        assertEquals(original, mSettings);
        mConnection.onServiceConnected(SESSION, new Binder());
        old.onServiceDisconnected(SESSION);
        assertBypass();
    }

    @Test
    public void nullBinding_retriesWithNewConnection() {
        enterGame();
        final ServiceConnection old = mConnection;
        old.onNullBinding(SESSION);
        assertFalse(mSaved.exists());
        advanceTime(GameStateDispatcher.SESSION_RETRY_DELAY_MS + 1);
        assertNotSame(old, mConnection);
        mConnection.onServiceConnected(SESSION, new Binder());
        assertBypass();
    }

    @Test
    public void sessionReconnectBeforeRetry_cancelsRestart() {
        enterGame();
        mConnection.onServiceDisconnected(SESSION);
        mConnection.onServiceConnected(SESSION, new Binder());
        final int starts = mSessionStartCount;
        advanceTime(GameStateDispatcher.SESSION_RETRY_DELAY_MS + 1);
        assertEquals(starts, mSessionStartCount);
        assertBypass();
    }

    @Test
    public void sessionDeathThenExit_cancelsRestartAndIgnoresLateConnection() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        final ServiceConnection old = mConnection;
        old.onServiceDisconnected(SESSION);
        mDispatcher.dispatchGameState(false, null);
        final int starts = mSessionStartCount;
        advanceTime(GameStateDispatcher.SESSION_RETRY_DELAY_MS + 1);
        old.onServiceConnected(SESSION, new Binder());
        assertEquals(starts, mSessionStartCount);
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }

    @Test
    public void failedSessionStart_retriesUntilConnected() throws Exception {
        when(mActivityManager.startService(any(), any(), any(), eq(false),
                any(), any(), anyInt())).thenReturn(null);
        mDispatcher.dispatchGameState(true, "test.game");
        assertFalse(mSaved.exists());
        when(mActivityManager.startService(any(), any(), any(), eq(false),
                any(), any(), anyInt())).thenReturn(SESSION);
        advanceTime(GameStateDispatcher.SESSION_RETRY_DELAY_MS + 1);
        mConnection.onServiceConnected(SESSION, new Binder());
        assertBypass();
    }

    @Test
    public void hiddenGame_restoresAndStopsSessionAfterGraceDeadline() throws Exception {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        setGameVisible(false);
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS / 2);
        assertBypass();
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS / 2 + 1);
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
        verify(mContext).unbindService(mConnection);
        verify(mActivityManager).stopService(any(), any(), any(), eq(0));
    }

    @Test
    public void repeatedHiddenEvents_doNotExtendGraceDeadline() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        setGameVisible(false);
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS / 2);
        setGameVisible(false);
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS / 2 + 1);
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }

    @Test
    public void visibleThenHiddenAgain_usesNewGraceDeadline() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        setGameVisible(false);
        advanceTime(1000);
        setGameVisible(true);
        setGameVisible(false);
        advanceTime(500);
        assertBypass();
        advanceTime(1000);
        assertEquals(original, mSettings);
    }

    @Test
    public void taskRemovalOrKeyguardDuringGrace_restoresImmediatelyAndCancelsDeadline() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        setGameVisible(false);
        mDispatcher.dispatchGameState(false, null);
        assertEquals(original, mSettings);
        enterGame();
        advanceTime(GameStateDispatcher.GAME_VISIBILITY_GRACE_MS + 1);
        assertBypass();
    }

    @Test
    public void toggleOffDuringGrace_restoresImmediately() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        setGameVisible(false);
        mToggle = false;
        mObserver.onChange(false);
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }

    @Test
    public void sessionDeathDuringGrace_restoresImmediately() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        setGameVisible(false);
        mConnection.onServiceDisconnected(SESSION);
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }

    @Test
    public void detachedOrForceHiddenTask_isNotVisible() {
        Task task = visibleGameTask(WINDOWING_MODE_FREEFORM);
        when(task.isAttached()).thenReturn(false);
        assertFalse(GameSpaceService.isGameTaskVisible(task));
        when(task.isAttached()).thenReturn(true);
        when(task.isForceHidden()).thenReturn(true);
        assertFalse(GameSpaceService.isGameTaskVisible(task));
        when(task.isForceHidden()).thenReturn(false);
        when(task.isVisibleRequested()).thenReturn(false);
        assertFalse(GameSpaceService.isGameTaskVisible(task));
    }

    @Test
    public void sessionDeath_restoresImmediately() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        mConnection.onServiceDisconnected(SESSION);
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }

    @Test
    public void restart_restoresPersistedSnapshotBeforeNewGame() {
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        assertBypass();
        GameStateDispatcher restarted = newDispatcher();
        restarted.initialize();
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }

    @Test
    public void invalidBatteryLevel_doesNotChangeSettings() {
        when(mBattery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).thenReturn(-1);
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }

    @Test
    public void failedApply_rollsBackOriginalSettings() {
        when(mHealth.setMode(HealthInterface.MODE_LIMIT)).thenReturn(false);
        Map<String, String> original = new HashMap<>(mSettings);
        enterGame();
        assertEquals(original, mSettings);
        assertFalse(mSaved.exists());
    }
}
