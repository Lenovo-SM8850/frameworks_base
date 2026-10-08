// SPDX-License-Identifier: Apache-2.0

package com.android.systemui.doze;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;

import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.KeyguardState;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.kotlin.JavaAdapterKt;

import java.io.PrintWriter;
import java.util.concurrent.CancellationException;

import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Job;

import javax.inject.Inject;

/** A single, elapsed-realtime budget for visible AOD in each sleep session. */
@DozeScope
public class DozeAodTimeout implements DozeMachine.Part {
    private final int mTimeoutMillis;
    private final AlarmTimeout mAlarm;
    private final DozeHost mHost;
    private final Handler mHandler;
    private final ScreenLifecycle mScreenLifecycle;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final UserTracker mUserTracker;
    private final KeyguardTransitionInteractor mKeyguardTransitionInteractor;
    private final CoroutineScope mScope;
    private Job mAodTransitionJob;
    private boolean mAodTransitionReady;
    private DozeMachine mMachine;
    private DozeMachine.State mState = DozeMachine.State.UNINITIALIZED;
    private int mDisplayState = Display.STATE_UNKNOWN;
    private long mDeadlineElapsed;
    private boolean mExpired;
    private boolean mSessionActive;
    private boolean mRegistered;

    private final DozeHost.Callback mHostCallback = new DozeHost.Callback() {
        @Override
        public void onAodScrimReady() {
            maybeStartTimeout();
        }
    };
    private final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOn() {
            maybeStartTimeout();
        }
    };
    private final WakefulnessLifecycle.Observer mWakefulnessObserver =
            new WakefulnessLifecycle.Observer() {
                @Override
                public void onStartedWakingUp() {
                    endSession();
                }
            };
    private final UserTracker.Callback mUserCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanging(int newUser, Context userContext) {
            // Do not let the next user's settings renew an already sleeping display.
            if (mSessionActive) {
                expire();
                mAlarm.cancel();
            }
        }
    };

    @Inject
    public DozeAodTimeout(Context context, AlarmManager alarmManager, DozeHost host,
            @Main Handler handler, ScreenLifecycle screenLifecycle,
            WakefulnessLifecycle wakefulnessLifecycle, UserTracker userTracker,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            @Application CoroutineScope scope) {
        mTimeoutMillis = context.getResources().getInteger(R.integer.config_dozeAodTimeoutMillis);
        mAlarm = new AlarmTimeout(alarmManager, this::expire, "doze_aod_timeout", handler);
        mHost = host;
        mHandler = handler;
        mScreenLifecycle = screenLifecycle;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mUserTracker = userTracker;
        mKeyguardTransitionInteractor = keyguardTransitionInteractor;
        mScope = scope;
    }

    @Override
    public void setDozeMachine(DozeMachine machine) {
        mMachine = machine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        mState = newState;
        if (mTimeoutMillis <= 0) {
            return;
        }
        if (newState == DozeMachine.State.INITIALIZED) {
            mDeadlineElapsed = 0;
            mExpired = false;
            mSessionActive = true;
            if (SceneContainerFlag.isEnabled()) {
                // Scene AOD has no legacy ScrimController. Its finished transition is the
                // corresponding visibility completion signal.
                mAodTransitionJob = JavaAdapterKt.collectFlow(mScope, mScope.getCoroutineContext(),
                        mKeyguardTransitionInteractor.isFinishedIn(KeyguardState.AOD), ready -> {
                            mAodTransitionReady = ready;
                            maybeStartTimeout();
                        });
            }
            if (!mRegistered) {
                mHost.addCallback(mHostCallback);
                mScreenLifecycle.addObserver(mScreenObserver);
                mWakefulnessLifecycle.addObserver(mWakefulnessObserver);
                mUserTracker.addCallback(mUserCallback, mHandler::post);
                mRegistered = true;
            }
        } else if (newState == DozeMachine.State.FINISH) {
            endSession();
        } else {
            maybeStartTimeout();
        }
    }

    @Override
    public void onScreenState(int state) {
        mDisplayState = state;
        maybeStartTimeout();
    }

    private void maybeStartTimeout() {
        if (mTimeoutMillis <= 0 || !mSessionActive || mExpired || mDeadlineElapsed != 0
                || !mState.isAlwaysOn() || mDisplayState == Display.STATE_UNKNOWN
                || mDisplayState == Display.STATE_OFF
                || !(SceneContainerFlag.isEnabled() ? mAodTransitionReady : mHost.isAodScrimReady())
                || mScreenLifecycle.getScreenState() != ScreenLifecycle.SCREEN_ON) {
            return;
        }
        mDeadlineElapsed = SystemClock.elapsedRealtime() + mTimeoutMillis;
        mAlarm.schedule(mTimeoutMillis, AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
    }

    private void expire() {
        if (!mSessionActive || mTimeoutMillis <= 0) {
            return;
        }
        mExpired = true;
        // Pulses, including authentication and held heads-up notifications, finish themselves.
        // The machine's policy makes their eventual PULSE_DONE return to DOZE.
        if (mState.isAlwaysOn() || mState == DozeMachine.State.DOZE_AOD_PAUSING
                || mState == DozeMachine.State.DOZE_AOD_PAUSED) {
            mMachine.requestState(DozeMachine.State.DOZE);
        }
    }

    @Override
    public boolean isAodTimedOut() {
        return mExpired;
    }

    private void endSession() {
        mAlarm.cancel();
        if (mAodTransitionJob != null) {
            mAodTransitionJob.cancel(new CancellationException("AOD session ended"));
            mAodTransitionJob = null;
        }
        mAodTransitionReady = false;
        mSessionActive = false;
        mDeadlineElapsed = 0;
        mExpired = false;
    }

    @Override
    public void destroy() {
        endSession();
        if (mRegistered) {
            mHost.removeCallback(mHostCallback);
            mScreenLifecycle.removeObserver(mScreenObserver);
            mWakefulnessLifecycle.removeObserver(mWakefulnessObserver);
            mUserTracker.removeCallback(mUserCallback);
            mRegistered = false;
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DozeAodTimeout: timeoutMillis=" + mTimeoutMillis
                + " deadlineElapsed=" + mDeadlineElapsed + " expired=" + mExpired
                + " sessionActive=" + mSessionActive + " displayState=" + mDisplayState);
    }
}
