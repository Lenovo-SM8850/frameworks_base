/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

import static com.android.server.display.BrightnessMappingStrategy.INVALID_LUX;

import android.animation.ValueAnimator;
import android.util.FloatProperty;
import android.util.Spline;
import android.view.Choreographer;
import android.view.Choreographer.VsyncCallback;

import androidx.annotation.Nullable;

import com.android.internal.display.BrightnessUtils;

/**
 * A custom animator that progressively updates a property value at
 * a given variable rate until it reaches a particular target value.
 * The ramping at the given rate is done in the perceptual space using
 * the HLG transfer functions, or optionally in linear brightness space.
 */
class RampAnimator<T> {
    private final T mObject;
    private final FloatProperty<T> mProperty;

    private final Clock mClock;

    // Current brightness in the selected ramp space
    private float mCurrentValue;

    // Target in the selected ramp space
    private float mTargetValue;

    // target in linear space
    private float mTargetLinearValue;
    private float mRate;
    private boolean mLinearSpace;
    private float mAnimationIncreaseMaxTimeSecs;
    private float mAnimationDecreaseMaxTimeSecs;

    // Given a lux change, defines the max brightness animation duration
    @Nullable
    private Spline mLuxDeltaToRampIncreaseMaxMillis;
    @Nullable
    private Spline mLuxDeltaToRampDecreaseMaxMillis;

    // Values used to tune the degree of curvature of the brightness transition
    private float mBrighteningRampGamma = Float.NaN;
    private float mDarkeningRampGamma = Float.NaN;
    private float mCurrentRampGamma = Float.NaN;

    private boolean mAnimating;
    private float mAnimatedValue; // higher precision copy of mCurrentValue
    private long mLastFrameTimeNanos;

    private boolean mFirstTime = true;

    RampAnimator(T object, FloatProperty<T> property) {
        this(object, property, System::nanoTime);
    }

    RampAnimator(T object, FloatProperty<T> property, Clock clock) {
        mObject = object;
        mProperty = property;
        mClock = clock;
    }

    /**
     * Sets the maximum time that a brightness animation can take.
     */
    void setAnimationTimeLimits(long animationRampIncreaseMaxTimeMillis,
            long animationRampDecreaseMaxTimeMillis, Spline luxDeltaToRampIncreaseMaxMillis,
            Spline luxDeltaToRampDecreaseMaxMillis) {
        mAnimationIncreaseMaxTimeSecs = (animationRampIncreaseMaxTimeMillis > 0)
                ? (animationRampIncreaseMaxTimeMillis / 1000.0f) : 0.0f;
        mAnimationDecreaseMaxTimeSecs = (animationRampDecreaseMaxTimeMillis > 0)
                ? (animationRampDecreaseMaxTimeMillis / 1000.0f) : 0.0f;
        mLuxDeltaToRampIncreaseMaxMillis = luxDeltaToRampIncreaseMaxMillis;
        mLuxDeltaToRampDecreaseMaxMillis = luxDeltaToRampDecreaseMaxMillis;
    }

    /**
     * Sets the gammas for the brightening and darkening ramp animations.
     */
    void setRampGammaValues(float brighteningRampGamma, float darkeningRampGamma) {
        mBrighteningRampGamma = brighteningRampGamma;
        mDarkeningRampGamma = darkeningRampGamma;
    }

    /**
     * Sets the animation target and the rate of this ramp animator.
     * Animation rate will be set ignoring maxTime animation limits
     * If this is the first time the property is being set or if the rate is 0,
     * the value jumps directly to the target.
     *
     * @param targetLinear The target value.
     * @param rate The convergence rate in units per second, or 0 to set the value immediately.
     * @param ignoreAnimationLimits if mAnimationIncreaseMaxTimeSecs and
     *                              mAnimationDecreaseMaxTimeSecs should be respected when adjusting
     *                              animation speed
     * @param luxDelta The change in ambient light that triggered this animation.
     * @return True if the target differs from the previous target.
     */
    boolean setAnimationTarget(float targetLinear, float rate, boolean ignoreAnimationLimits,
            float luxDelta) {
        return setAnimationTarget(targetLinear, rate, ignoreAnimationLimits, luxDelta,
                /* linearSpace= */ false);
    }

    /** Sets the target with a rate measured in the selected brightness space. */
    boolean setAnimationTarget(float targetLinear, float rate, boolean ignoreAnimationLimits,
            float luxDelta, boolean linearSpace) {
        float maxIncreaseTimeSecs = 0;
        float maxDecreaseTimeSecs = 0;
        if (!ignoreAnimationLimits) {
            if (mLuxDeltaToRampIncreaseMaxMillis != null && mLuxDeltaToRampDecreaseMaxMillis != null
                    && luxDelta != INVALID_LUX) {
                maxIncreaseTimeSecs = mLuxDeltaToRampIncreaseMaxMillis.interpolate(luxDelta)
                        / 1000.0f;
                maxDecreaseTimeSecs = mLuxDeltaToRampDecreaseMaxMillis.interpolate(luxDelta)
                        / 1000.0f;
            } else {
                maxIncreaseTimeSecs = mAnimationIncreaseMaxTimeSecs;
                maxDecreaseTimeSecs = mAnimationDecreaseMaxTimeSecs;
            }
        }
        return setAnimationTarget(targetLinear, rate, maxIncreaseTimeSecs, maxDecreaseTimeSecs,
                linearSpace);
    }

    private boolean setAnimationTarget(float targetLinear, float rate,
            float maxIncreaseTimeSecs, float maxDecreaseTimeSecs, boolean linearSpace) {
        final boolean spaceChanged = mLinearSpace != linearSpace;
        final float currentLinear = spaceChanged ? mProperty.get(mObject) : 0;
        mCurrentRampGamma = targetLinear > (spaceChanged ? currentLinear
                : BrightnessUtils.convertGammaToLinear(mCurrentValue))
                ? mBrighteningRampGamma : mDarkeningRampGamma;
        if (linearSpace) {
            mCurrentRampGamma = 1.0f;
        }
        final float target = convertLinearToRampSpace(targetLinear, linearSpace);

        if (spaceChanged) {
            // Rebase both endpoints without changing the displayed brightness or frame time.
            mCurrentValue = convertLinearToRampSpace(currentLinear, linearSpace);
            mAnimatedValue = mCurrentValue;
            mTargetValue = convertLinearToRampSpace(mTargetLinearValue, linearSpace);
            mLinearSpace = linearSpace;
        }
        if (Float.isNaN(mCurrentRampGamma)) {
            mCurrentRampGamma = 1.0f;
        }

        // Immediately jump to the target the first time.
        if (mFirstTime || rate <= 0 || mCurrentValue == 0) {
            if (mFirstTime || target != mCurrentValue) {
                mFirstTime = false;
                mRate = 0;
                mTargetValue = target;
                mTargetLinearValue = targetLinear;
                mCurrentValue = target;
                setPropertyValue(target);
                mAnimating = false;
                return true;
            }
            return false;
        }

        // Adjust the rate so that we do not exceed our maximum animation time.
        if (target > mCurrentValue && maxIncreaseTimeSecs > 0.0f
                && ((target - mCurrentValue) / rate) > maxIncreaseTimeSecs) {
            rate = (target - mCurrentValue) / maxIncreaseTimeSecs;
        } else if (target < mCurrentValue && maxDecreaseTimeSecs > 0.0f
                && ((mCurrentValue - target) / rate) > maxDecreaseTimeSecs) {
            rate = (mCurrentValue - target) / maxDecreaseTimeSecs;
        }

        // Adjust the rate based on the closest target.
        // If a faster rate is specified, then use the new rate so that we converge
        // more rapidly based on the new request.
        // If a slower rate is specified, then use the new rate only if the current
        // value is somewhere in between the new and the old target meaning that
        // we will be ramping in a different direction to get there.
        // Otherwise, continue at the previous rate.
        if (!mAnimating
                || spaceChanged
                || rate > mRate
                || (target <= mCurrentValue && mCurrentValue <= mTargetValue)
                || (mTargetValue <= mCurrentValue && mCurrentValue <= target)) {
            mRate = rate;
        }

        final boolean changed = (mTargetValue != target);
        mTargetValue = target;
        mTargetLinearValue = targetLinear;

        // Start animating.
        if (!mAnimating && target != mCurrentValue) {
            mAnimating = true;
            mAnimatedValue = mCurrentValue;
            mLastFrameTimeNanos = mClock.nanoTime();
        }

        return changed;
    }

    private float convertLinearToRampSpace(float value, boolean linearSpace) {
        if (linearSpace) {
            return value;
        }
        if (!Float.isNaN(mCurrentRampGamma)) {
            value = (float) Math.pow(value, 1.0f / mCurrentRampGamma);
        }
        return BrightnessUtils.convertLinearToGamma(value);
    }

    /**
     * Returns true if the animation is running.
     */
    boolean isAnimating() {
        return mAnimating;
    }

    /**
     * Sets the brightness property by converting the selected ramp space into linear space.
     */
    private void setPropertyValue(float val) {
        if (mLinearSpace) {
            mProperty.setValue(mObject, val == mTargetValue ? mTargetLinearValue : val);
            return;
        }
        float newValue = BrightnessUtils.convertGammaToLinear(val);
        if (!Float.isNaN(mCurrentRampGamma)) {
            newValue = (float) Math.pow(newValue, mCurrentRampGamma);
        }

        // To avoid linearVal inconsistency when converting to HLG and back to linear space
        // used original target linear value for final animation step
        float linearVal = val == mTargetValue ? mTargetLinearValue : newValue;
        mProperty.setValue(mObject, linearVal);
    }

    void performNextAnimationStep(long frameTimeNanos) {
        final float timeDelta = (frameTimeNanos - mLastFrameTimeNanos) * 0.000000001f;
        mLastFrameTimeNanos = frameTimeNanos;

        // Advance the animated value towards the target at the specified rate
        // and clamp to the target. This gives us the new current value but
        // we keep the animated value around to allow for fractional increments
        // towards the target.
        final float scale = ValueAnimator.getDurationScale();
        if (scale == 0) {
            // Animation off.
            mAnimatedValue = mTargetValue;
        } else {
            final float amount = timeDelta * mRate / scale;
            if (mTargetValue > mCurrentValue) {
                mAnimatedValue = Math.min(mAnimatedValue + amount, mTargetValue);
            } else {
                mAnimatedValue = Math.max(mAnimatedValue - amount, mTargetValue);
            }
        }
        final float oldCurrentValue = mCurrentValue;
        mCurrentValue = mAnimatedValue;
        if (oldCurrentValue != mCurrentValue) {
            setPropertyValue(mCurrentValue);
        }
        if (mTargetValue == mCurrentValue) {
            mAnimating = false;
        }
    }

    public interface Listener {
        void onAnimationEnd();
    }

    interface Clock {
        /**
         * Returns current system time in nanoseconds.
         */
        long nanoTime();
    }

    static class DualRampAnimator<T> {
        private final Choreographer mChoreographer;
        private final RampAnimator<T> mFirst;
        private final RampAnimator<T> mSecond;

        private Listener mListener;
        private boolean mAwaitingCallback;

        DualRampAnimator(T object, FloatProperty<T> firstProperty,
                FloatProperty<T> secondProperty) {
            mChoreographer = Choreographer.getInstance();
            mFirst = new RampAnimator<>(object, firstProperty);
            mSecond = new RampAnimator<>(object, secondProperty);
        }

        /**
         * Sets the maximum time that a brightness animation can take.
         */
        public void setAnimationTimeLimits(long animationRampIncreaseMaxTimeMillis,
                long animationRampDecreaseMaxTimeMillis, Spline luxDeltaToRampIncreaseMaxMillis,
                Spline luxDeltaToRampDecreaseMaxMillis) {
            mFirst.setAnimationTimeLimits(animationRampIncreaseMaxTimeMillis,
                    animationRampDecreaseMaxTimeMillis, luxDeltaToRampIncreaseMaxMillis,
                    luxDeltaToRampDecreaseMaxMillis);
            mSecond.setAnimationTimeLimits(animationRampIncreaseMaxTimeMillis,
                    animationRampDecreaseMaxTimeMillis, luxDeltaToRampIncreaseMaxMillis,
                    luxDeltaToRampDecreaseMaxMillis);
        }

        /**
         * Sets the gammas for the brightening and darkening ramp animations.
         */
        public void setRampGammaValues(float brighteningRampGamma, float darkeningRampGamma) {
            mFirst.setRampGammaValues(brighteningRampGamma, darkeningRampGamma);
            mSecond.setRampGammaValues(brighteningRampGamma, darkeningRampGamma);
        }

        /**
         * Starts animating towards the specified values.
         *
         * If this is the first time the property is being set or if the rate is 0,
         * the value jumps directly to the target.
         *
         * @param linearFirstTarget The first target value in linear space.
         * @param linearSecondTarget The second target value in linear space.
         * @param rate The convergence rate in units per second, or 0 to set the value immediately.
         * @param ignoreAnimationLimits if mAnimationIncreaseMaxTimeSecs and
         *                              mAnimationDecreaseMaxTimeSecs should be respected
         *                              when adjusting animation speed
         * @param luxDelta The change in ambient light that triggered this animation.
         * @return True if either target differs from the previous target.
         */
        public boolean animateTo(float linearFirstTarget, float linearSecondTarget, float rate,
                boolean ignoreAnimationLimits, float luxDelta) {
            return animateTo(linearFirstTarget, linearSecondTarget, rate, ignoreAnimationLimits,
                    luxDelta, /* linearSpace= */ false);
        }

        /** Starts both ramps with a rate measured in the selected brightness space. */
        public boolean animateTo(float linearFirstTarget, float linearSecondTarget, float rate,
                boolean ignoreAnimationLimits, float luxDelta, boolean linearSpace) {
            boolean animationTargetChanged = mFirst.setAnimationTarget(linearFirstTarget, rate,
                    ignoreAnimationLimits, luxDelta, linearSpace);
            animationTargetChanged |= mSecond.setAnimationTarget(linearSecondTarget, rate,
                    ignoreAnimationLimits, luxDelta, linearSpace);
            boolean shouldBeAnimating = isAnimating();

            if (shouldBeAnimating != mAwaitingCallback) {
                if (shouldBeAnimating) {
                    mAwaitingCallback = true;
                    postAnimationCallback();
                } else if (mAwaitingCallback) {
                    mChoreographer.removeVsyncCallback(mAnimationCallback);
                    mAwaitingCallback = false;
                }
            }
            return animationTargetChanged;
        }

        /**
        * Sets a listener to watch for animation events.
        */
        public void setListener(Listener listener) {
            mListener = listener;
        }

        /**
        * Returns true if the animation is running.
        */
        public boolean isAnimating() {
            return mFirst.isAnimating() || mSecond.isAnimating();
        }

        private void postAnimationCallback() {
            mChoreographer.postVsyncCallback(mAnimationCallback);
        }

        private final VsyncCallback mAnimationCallback = new VsyncCallback() {
            @Override // Choreographer callback
            public void onVsync(Choreographer.FrameData frameData) {
                long frameTimeNanos = frameData.getFrameTimeNanos();
                mFirst.performNextAnimationStep(frameTimeNanos);
                mSecond.performNextAnimationStep(frameTimeNanos);
                if (isAnimating()) {
                    postAnimationCallback();
                } else {
                    if (mListener != null) {
                        mListener.onAnimationEnd();
                    }
                    mAwaitingCallback = false;
                }
            }
        };
    }
}
