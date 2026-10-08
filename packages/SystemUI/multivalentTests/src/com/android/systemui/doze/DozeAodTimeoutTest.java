// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.doze;

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_DOCKED;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_MINMODE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSED;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSE_DONE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSING_AUTH_UI;
import static com.android.systemui.doze.DozeMachine.State.FINISH;
import static com.android.systemui.doze.DozeMachine.State.INITIALIZED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.flags.DisableSceneContainer;
import com.android.systemui.flags.EnableSceneContainer;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.wakelock.WakeLockFake;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

@SmallTest
@DisableSceneContainer
@RunWith(AndroidJUnit4.class)
@UiThreadTest
public class DozeAodTimeoutTest extends SysuiTestCase {
    private final AlarmManager mAlarmManager = mock(AlarmManager.class);
    private final DozeHost mHost = mock(DozeHost.class);
    private final ScreenLifecycle mScreenLifecycle = mock(ScreenLifecycle.class);
    private final WakefulnessLifecycle mWakefulness = mock(WakefulnessLifecycle.class);
    private final UserTracker mUserTracker = mock(UserTracker.class);
    private final AmbientDisplayConfiguration mConfig = mock(AmbientDisplayConfiguration.class);
    private final KeyguardTransitionInteractor mTransitions =
            mock(KeyguardTransitionInteractor.class);
    private final MutableStateFlow<Boolean> mAodReady = StateFlowKt.MutableStateFlow(false);
    private final CoroutineScope mScope =
            CoroutineScopeKt.CoroutineScope(Dispatchers.getUnconfined());
    private DozeAodTimeout mTimeout;
    private DozeMachine mMachine;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Before
    public void setUp() {
        when(mConfig.alwaysOnEnabled(anyInt())).thenReturn(true);
        when(mWakefulness.getWakefulness()).thenReturn(WakefulnessLifecycle.WAKEFULNESS_ASLEEP);
        when(mTransitions.isFinishedIn(KeyguardState.AOD)).thenReturn(mAodReady);
        when(mHost.isAodScrimReady()).thenReturn(true);
        when(mScreenLifecycle.getScreenState()).thenReturn(ScreenLifecycle.SCREEN_ON);
    }

    @After
    public void tearDownTimeout() {
        if (mTimeout != null) {
            mTimeout.destroy();
        }
    }

    private void initialize(int timeoutMillis) {
        mContext.getOrCreateTestableResources().addOverride(
                R.integer.config_dozeAodTimeoutMillis, timeoutMillis);
        mTimeout = new DozeAodTimeout(mContext, mAlarmManager, mHost, mHandler,
                mScreenLifecycle, mWakefulness, mUserTracker, mTransitions, mScope);
        mMachine = new DozeMachine(new DozeServiceFake(), mConfig, new WakeLockFake(),
                mWakefulness, mock(DozeLog.class), mock(DockManager.class), Optional.empty(),
                mHost, new DozeMachine.Part[]{mTimeout}, mUserTracker);
        mMachine.requestState(INITIALIZED);
        mMachine.onScreenState(Display.STATE_ON);
    }

    private void fireTimeout() {
        ArgumentCaptor<AlarmManager.OnAlarmListener> listener =
                ArgumentCaptor.forClass(AlarmManager.OnAlarmListener.class);
        verify(mAlarmManager).setExact(eq(AlarmManager.ELAPSED_REALTIME_WAKEUP), anyLong(),
                eq("doze_aod_timeout"), listener.capture(), eq(mHandler));
        listener.getValue().onAlarm();
    }

    @Test
    public void defaultZero_keepsAodAndDoesNotRegisterOrSchedule() {
        initialize(0);
        verify(mAlarmManager, never()).setExact(anyInt(), anyLong(), any(), any(), any());
        verify(mHost, never()).addCallback(any());
        assertEquals(DOZE_AOD, mMachine.getState());
        assertFalse(mTimeout.isAodTimedOut());
    }

    @Test
    public void deadlineStartsOnlyAfterScrimAndScreenCompletion() {
        when(mHost.isAodScrimReady()).thenReturn(false);
        when(mScreenLifecycle.getScreenState()).thenReturn(ScreenLifecycle.SCREEN_TURNING_ON);
        initialize(6000);
        verify(mAlarmManager, never()).setExact(anyInt(), anyLong(), any(), any(), any());
        ArgumentCaptor<DozeHost.Callback> hostCallback =
                ArgumentCaptor.forClass(DozeHost.Callback.class);
        verify(mHost).addCallback(hostCallback.capture());
        when(mHost.isAodScrimReady()).thenReturn(true);
        hostCallback.getValue().onAodScrimReady();
        verify(mAlarmManager, never()).setExact(anyInt(), anyLong(), any(), any(), any());
        ArgumentCaptor<ScreenLifecycle.Observer> screenObserver =
                ArgumentCaptor.forClass(ScreenLifecycle.Observer.class);
        verify(mScreenLifecycle).addObserver(screenObserver.capture());
        when(mScreenLifecycle.getScreenState()).thenReturn(ScreenLifecycle.SCREEN_ON);
        long before = SystemClock.elapsedRealtime();
        screenObserver.getValue().onScreenTurnedOn();
        ArgumentCaptor<Long> deadline = ArgumentCaptor.forClass(Long.class);
        verify(mAlarmManager).setExact(eq(AlarmManager.ELAPSED_REALTIME_WAKEUP),
                deadline.capture(), eq("doze_aod_timeout"), any(), eq(mHandler));
        assertTrue(deadline.getValue() >= before + 6000);
        assertTrue(deadline.getValue() <= SystemClock.elapsedRealtime() + 6000);
    }

    @Test
    @EnableSceneContainer
    public void sceneAod_waitsForFinishedTransition() {
        initialize(6000);
        verify(mAlarmManager, never()).setExact(anyInt(), anyLong(), any(), any(), any());
        mAodReady.setValue(true);
        fireTimeout();
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void timeout_turnsOffAodAndClampsEveryAlwaysOnVariant() {
        initialize(6000);
        fireTimeout();
        assertEquals(DOZE, mMachine.getState());
        assertTrue(mTimeout.isAodTimedOut());
        for (DozeMachine.State state : new DozeMachine.State[]{DOZE_AOD, DOZE_AOD_DOCKED,
                DOZE_AOD_MINMODE, DOZE_AOD_PAUSING, DOZE_AOD_PAUSED}) {
            mMachine.requestState(state);
            assertEquals(DOZE, mMachine.getState());
        }
    }

    @Test
    public void notificationAfterTimeout_pulsesAndReturnsToDoze() {
        initialize(6000);
        fireTimeout();
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);
        assertEquals(DOZE_PULSING, mMachine.getState());
        mMachine.requestState(DOZE_PULSE_DONE);
        assertEquals(DOZE, mMachine.getState());
        assertTrue(mTimeout.isAodTimedOut());
    }

    @Test
    public void timeoutDuringHeldPulse_waitsForPulseDone() {
        initialize(6000);
        mMachine.requestPulse(DozeLog.PULSE_REASON_NOTIFICATION);
        mMachine.requestState(DOZE_PULSING);
        fireTimeout();
        assertEquals(DOZE_PULSING, mMachine.getState());
        assertTrue(mTimeout.isAodTimedOut());
        mMachine.requestState(DOZE_AOD);
        mMachine.requestState(DOZE_AOD_DOCKED);
        mMachine.requestState(DOZE_AOD_MINMODE);
        assertEquals(DOZE_PULSING, mMachine.getState());
        mMachine.requestState(DOZE_PULSE_DONE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void timeoutDuringAuthentication_waitsForPulseDone() {
        initialize(6000);
        mMachine.requestPulse(DozeLog.PULSE_REASON_FINGERPRINT_PULSE_SHOW_AUTH_UI);
        mMachine.requestState(DOZE_PULSING_AUTH_UI);
        fireTimeout();
        assertEquals(DOZE_PULSING_AUTH_UI, mMachine.getState());
        mMachine.requestState(DOZE_AOD);
        assertEquals(DOZE_PULSING_AUTH_UI, mMachine.getState());
        mMachine.requestState(DOZE_PULSE_DONE);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void proximityAndScheduleChanges_doNotRenewBudget() {
        initialize(6000);
        mMachine.requestState(DOZE_AOD_PAUSING);
        mMachine.requestState(DOZE_AOD_PAUSED);
        mMachine.requestState(DOZE_AOD);
        mMachine.requestState(DOZE);
        mMachine.requestState(DOZE_AOD);
        verify(mAlarmManager, times(1)).setExact(anyInt(), anyLong(), any(), any(), any());
        fireTimeout();
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void timeoutWhileProximityPaused_exhaustsSession() {
        initialize(6000);
        mMachine.requestState(DOZE_AOD_PAUSING);
        mMachine.requestState(DOZE_AOD_PAUSED);
        fireTimeout();
        mMachine.requestState(DOZE_AOD);
        assertEquals(DOZE, mMachine.getState());
    }

    @Test
    public void wakeClearsLatchAndNextSleepGetsFreshBudget() {
        initialize(6000);
        fireTimeout();
        ArgumentCaptor<WakefulnessLifecycle.Observer> observer =
                ArgumentCaptor.forClass(WakefulnessLifecycle.Observer.class);
        verify(mWakefulness).addObserver(observer.capture());
        observer.getValue().onStartedWakingUp();
        assertFalse(mTimeout.isAodTimedOut());
        mMachine.requestState(FINISH);
        mMachine.destroy();
        initialize(6000);
        assertEquals(DOZE_AOD, mMachine.getState());
        verify(mAlarmManager, times(2)).setExact(anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    public void userSwitchCancelsAlarmAndExhaustsSleepingSession() {
        initialize(6000);
        ArgumentCaptor<UserTracker.Callback> callback =
                ArgumentCaptor.forClass(UserTracker.Callback.class);
        verify(mUserTracker).addCallback(callback.capture(), any());
        callback.getValue().onUserChanging(10, mContext);
        assertEquals(DOZE, mMachine.getState());
        assertTrue(mTimeout.isAodTimedOut());
        verify(mAlarmManager).cancel(any(AlarmManager.OnAlarmListener.class));
    }
}
