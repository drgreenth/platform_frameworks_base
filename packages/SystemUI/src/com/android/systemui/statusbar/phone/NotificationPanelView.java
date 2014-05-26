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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.GestureRecorder;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.stack.StackStateAnimator;

import java.util.ArrayList;

public class NotificationPanelView extends PanelView implements
        ExpandableView.OnHeightChangedListener, ObservableScrollView.Listener,
        View.OnClickListener, KeyguardPageSwipeHelper.Callback {

    private KeyguardPageSwipeHelper mPageSwiper;
    PhoneStatusBar mStatusBar;
    private StatusBarHeaderView mHeader;
    private View mQsContainer;
    private View mQsPanel;
    private View mKeyguardStatusView;
    private ObservableScrollView mScrollView;
    private View mStackScrollerContainer;

    private NotificationStackScrollLayout mNotificationStackScroller;
    private int mNotificationTopPadding;
    private boolean mAnimateNextTopPaddingChange;

    private int mTrackingPointer;
    private VelocityTracker mVelocityTracker;
    private boolean mQsTracking;

    /**
     * Whether we are currently handling a motion gesture in #onInterceptTouchEvent, but haven't
     * intercepted yet.
     */
    private boolean mIntercepting;
    private boolean mQsExpanded;
    private boolean mKeyguardShowing;
    private float mInitialHeightOnTouch;
    private float mInitialTouchX;
    private float mInitialTouchY;
    private float mLastTouchX;
    private float mLastTouchY;
    private float mQsExpansionHeight;
    private int mQsMinExpansionHeight;
    private int mQsMaxExpansionHeight;
    private int mMinStackHeight;
    private int mQsPeekHeight;
    private float mNotificationTranslation;
    private int mStackScrollerIntrinsicPadding;
    private boolean mQsExpansionEnabled = true;
    private ValueAnimator mQsExpansionAnimator;
    private FlingAnimationUtils mFlingAnimationUtils;
    private int mStatusBarMinHeight;

    private Interpolator mFastOutSlowInInterpolator;
    private ObjectAnimator mClockAnimator;
    private int mClockAnimationTarget = -1;
    private int mTopPaddingAdjustment;
    private KeyguardClockPositionAlgorithm mClockPositionAlgorithm =
            new KeyguardClockPositionAlgorithm();
    private KeyguardClockPositionAlgorithm.Result mClockPositionResult =
            new KeyguardClockPositionAlgorithm.Result();
    private boolean mIsSwipedHorizontally;
    private boolean mIsExpanding;
    private KeyguardBottomAreaView mKeyguardBottomArea;
    private boolean mBlockTouches;
    private ArrayList<View> mSwipeTranslationViews = new ArrayList<>();

    public NotificationPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setStatusBar(PhoneStatusBar bar) {
        if (mStatusBar != null) {
            mStatusBar.setOnFlipRunnable(null);
        }
        mStatusBar = bar;
        if (bar != null) {
            mStatusBar.setOnFlipRunnable(new Runnable() {
                @Override
                public void run() {
                    requestPanelHeightUpdate();
                }
            });
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mHeader = (StatusBarHeaderView) findViewById(R.id.header);
        mHeader.getBackgroundView().setOnClickListener(this);
        mHeader.setOverlayParent(this);
        mKeyguardStatusView = findViewById(R.id.keyguard_status_view);
        mStackScrollerContainer = findViewById(R.id.notification_container_parent);
        mQsContainer = findViewById(R.id.quick_settings_container);
        mQsPanel = findViewById(R.id.quick_settings_panel);
        mScrollView = (ObservableScrollView) findViewById(R.id.scroll_view);
        mScrollView.setListener(this);
        mNotificationStackScroller = (NotificationStackScrollLayout)
                findViewById(R.id.notification_stack_scroller);
        mNotificationStackScroller.setOnHeightChangedListener(this);
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.fast_out_slow_in);
        mKeyguardBottomArea = (KeyguardBottomAreaView) findViewById(R.id.keyguard_bottom_area);
        mSwipeTranslationViews.add(mNotificationStackScroller);
        mSwipeTranslationViews.add(mKeyguardStatusView);
        mPageSwiper = new KeyguardPageSwipeHelper(this, getContext());
    }

    @Override
    protected void loadDimens() {
        super.loadDimens();
        mNotificationTopPadding = getResources().getDimensionPixelSize(
                R.dimen.notifications_top_padding);
        mMinStackHeight = getResources().getDimensionPixelSize(R.dimen.collapsed_stack_height);
        mFlingAnimationUtils = new FlingAnimationUtils(getContext(), 0.4f);
        mStatusBarMinHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height);
        mQsPeekHeight = getResources().getDimensionPixelSize(R.dimen.qs_peek_height);
        mClockPositionAlgorithm.loadDimens(getResources());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Calculate quick setting heights.
        mQsMinExpansionHeight = mHeader.getCollapsedHeight() + mQsPeekHeight;
        mQsMaxExpansionHeight = mHeader.getExpandedHeight() + mQsContainer.getHeight();
        if (!mQsExpanded) {
            setQsExpansion(mQsMinExpansionHeight);
            positionClockAndNotifications();
            mNotificationStackScroller.setStackHeight(getExpandedHeight());
        }
    }

    /**
     * Positions the clock and notifications dynamically depending on how many notifications are
     * showing.
     */
    private void positionClockAndNotifications() {
        boolean animateClock = mNotificationStackScroller.isAddOrRemoveAnimationPending();
        if (mStatusBar.getBarState() != StatusBarState.KEYGUARD) {
            mStackScrollerIntrinsicPadding = mHeader.getBottom() + mQsPeekHeight
                    + mNotificationTopPadding;
            mTopPaddingAdjustment = 0;
        } else {
            mClockPositionAlgorithm.setup(
                    mStatusBar.getMaxKeyguardNotifications(),
                    getMaxPanelHeight(),
                    getExpandedHeight(),
                    mNotificationStackScroller.getNotGoneChildCount(),
                    getHeight(),
                    mKeyguardStatusView.getHeight());
            mClockPositionAlgorithm.run(mClockPositionResult);
            if (animateClock || mClockAnimator != null) {
                startClockAnimation(mClockPositionResult.clockY);
            } else {
                mKeyguardStatusView.setY(mClockPositionResult.clockY);
            }
            applyClockAlpha(mClockPositionResult.clockAlpha);
            mStackScrollerIntrinsicPadding = mClockPositionResult.stackScrollerPadding;
            mTopPaddingAdjustment = mClockPositionResult.stackScrollerPaddingAdjustment;
        }
        mNotificationStackScroller.setTopPadding(mStackScrollerIntrinsicPadding,
                mAnimateNextTopPaddingChange || animateClock);
        mAnimateNextTopPaddingChange = false;
    }

    private void startClockAnimation(int y) {
        if (mClockAnimationTarget == y) {
            return;
        }
        mClockAnimationTarget = y;
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                getViewTreeObserver().removeOnPreDrawListener(this);
                if (mClockAnimator != null) {
                    mClockAnimator.removeAllListeners();
                    mClockAnimator.cancel();
                }
                mClockAnimator =
                        ObjectAnimator.ofFloat(mKeyguardStatusView, View.Y, mClockAnimationTarget);
                mClockAnimator.setInterpolator(mFastOutSlowInInterpolator);
                mClockAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
                mClockAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mClockAnimator = null;
                        mClockAnimationTarget = -1;
                    }
                });
                mClockAnimator.start();
                return true;
            }
        });
    }

    private void applyClockAlpha(float alpha) {
        if (alpha != 1.0f) {
            mKeyguardStatusView.setLayerType(LAYER_TYPE_HARDWARE, null);
        } else {
            mKeyguardStatusView.setLayerType(LAYER_TYPE_NONE, null);
        }
        mKeyguardStatusView.setAlpha(alpha);
    }

    public void animateToFullShade() {
        mAnimateNextTopPaddingChange = true;
        mNotificationStackScroller.goToFullShade();
        requestLayout();
    }

    /**
     * @return Whether Quick Settings are currently expanded.
     */
    public boolean isQsExpanded() {
        return mQsExpanded;
    }

    public void setQsExpansionEnabled(boolean qsExpansionEnabled) {
        mQsExpansionEnabled = qsExpansionEnabled;
    }

    public void resetViews() {
        mBlockTouches = false;
        mPageSwiper.reset();
        closeQs();
    }

    public void closeQs() {
        cancelAnimation();
        setQsExpansion(mQsMinExpansionHeight);
    }

    public void openQs() {
        cancelAnimation();
        if (mQsExpansionEnabled) {
            setQsExpansion(mQsMaxExpansionHeight);
        }
    }

    @Override
    public void fling(float vel, boolean always) {
        GestureRecorder gr = ((PhoneStatusBarView) mBar).mBar.getGestureRecorder();
        if (gr != null) {
            gr.tag("fling " + ((vel > 0) ? "open" : "closed"), "notifications,v=" + vel);
        }
        super.fling(vel, always);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.getText()
                    .add(getContext().getString(R.string.accessibility_desc_notification_shade));
            return true;
        }

        return super.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (mBlockTouches) {
            return false;
        }
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mIntercepting = true;
                mInitialTouchY = y;
                mInitialTouchX = x;
                initVelocityTracker();
                trackMovement(event);
                if (shouldQuickSettingsIntercept(mInitialTouchX, mInitialTouchY, 0)) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialTouchX = event.getX(newIndex);
                    mInitialTouchY = event.getY(newIndex);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY;
                trackMovement(event);
                if (mQsTracking) {

                    // Already tracking because onOverscrolled was called. We need to update here
                    // so we don't stop for a frame until the next touch event gets handled in
                    // onTouchEvent.
                    setQsExpansion(h + mInitialHeightOnTouch);
                    trackMovement(event);
                    mIntercepting = false;
                    return true;
                }
                if (Math.abs(h) > mTouchSlop && Math.abs(h) > Math.abs(x - mInitialTouchX)
                        && shouldQuickSettingsIntercept(mInitialTouchX, mInitialTouchY, h)) {
                    onQsExpansionStarted();
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mInitialTouchY = y;
                    mInitialTouchX = x;
                    mQsTracking = true;
                    mIntercepting = false;
                    return true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                trackMovement(event);
                if (mQsTracking) {
                    flingQsWithCurrentVelocity();
                    mQsTracking = false;
                }
                mIntercepting = false;
                break;
        }
        return !mQsExpanded && super.onInterceptTouchEvent(event);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {

        // Block request when interacting with the scroll view so we can still intercept the
        // scrolling when QS is expanded.
        if (mScrollView.isDispatchingTouchEvent()) {
            return;
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    private void flingQsWithCurrentVelocity() {
        float vel = getCurrentVelocity();

        // TODO: Better logic whether we should expand or not.
        flingSettings(vel, vel > 0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mBlockTouches) {
            return false;
        }
        // TODO: Handle doublefinger swipe to notifications again. Look at history for a reference
        // implementation.
        if (!mIsExpanding && !mQsExpanded && mStatusBar.getBarState() != StatusBarState.SHADE) {
            mPageSwiper.onTouchEvent(event);
            if (mPageSwiper.isSwipingInProgress()) {
                return true;
            }
        }
        if (mQsTracking || mQsExpanded) {
            return onQsTouch(event);
        }

        super.onTouchEvent(event);
        return true;
    }

    @Override
    protected boolean hasConflictingGestures() {
        return mStatusBar.getBarState() != StatusBarState.SHADE;
    }

    private boolean onQsTouch(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mTrackingPointer);
        if (pointerIndex < 0) {
            pointerIndex = 0;
            mTrackingPointer = event.getPointerId(pointerIndex);
        }
        final float y = event.getY(pointerIndex);
        final float x = event.getX(pointerIndex);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mQsTracking = true;
                mInitialTouchY = y;
                mInitialTouchX = x;
                onQsExpansionStarted();
                mInitialHeightOnTouch = mQsExpansionHeight;
                initVelocityTracker();
                trackMovement(event);
                break;

            case MotionEvent.ACTION_POINTER_UP:
                final int upPointer = event.getPointerId(event.getActionIndex());
                if (mTrackingPointer == upPointer) {
                    // gesture is ongoing, find a new pointer to track
                    final int newIndex = event.getPointerId(0) != upPointer ? 0 : 1;
                    final float newY = event.getY(newIndex);
                    final float newX = event.getX(newIndex);
                    mTrackingPointer = event.getPointerId(newIndex);
                    mInitialHeightOnTouch = mQsExpansionHeight;
                    mInitialTouchY = newY;
                    mInitialTouchX = newX;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                final float h = y - mInitialTouchY;
                setQsExpansion(h + mInitialHeightOnTouch);
                trackMovement(event);
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mQsTracking = false;
                mTrackingPointer = -1;
                trackMovement(event);
                flingQsWithCurrentVelocity();
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
        }
        return true;
    }

    @Override
    public void onOverscrolled(int amount) {
        if (mIntercepting) {
            onQsExpansionStarted(amount);
            mInitialHeightOnTouch = mQsExpansionHeight;
            mInitialTouchY = mLastTouchY;
            mInitialTouchX = mLastTouchX;
            mQsTracking = true;
        }
    }

    private void onQsExpansionStarted() {
        onQsExpansionStarted(0);
    }

    private void onQsExpansionStarted(int overscrollAmount) {
        cancelAnimation();

        // Reset scroll position and apply that position to the expanded height.
        float height = mQsExpansionHeight - mScrollView.getScrollY() - overscrollAmount;
        mScrollView.scrollTo(0, 0);
        setQsExpansion(height);
    }

    private void setQsExpanded(boolean expanded) {
        boolean changed = mQsExpanded != expanded;
        if (changed) {
            mQsExpanded = expanded;
            updateQsState();
        }
    }

    public void setKeyguardShowing(boolean keyguardShowing) {
        mKeyguardShowing = keyguardShowing;
        updateQsState();
    }

    private void updateQsState() {
        mHeader.setExpanded(mQsExpanded);
        mNotificationStackScroller.setEnabled(!mQsExpanded);
        mQsPanel.setVisibility(mQsExpanded ? View.VISIBLE : View.INVISIBLE);
        mQsContainer.setVisibility(mKeyguardShowing && !mQsExpanded
                ? View.INVISIBLE
                : View.VISIBLE);
        mScrollView.setTouchEnabled(mQsExpanded);
    }

    private void setQsExpansion(float height) {
        height = Math.min(Math.max(height, mQsMinExpansionHeight), mQsMaxExpansionHeight);
        if (height > mQsMinExpansionHeight && !mQsExpanded) {
            setQsExpanded(true);
        } else if (height <= mQsMinExpansionHeight && mQsExpanded) {
            setQsExpanded(false);
        }
        mQsExpansionHeight = height;
        mHeader.setExpansion(height - mQsPeekHeight);
        setQsTranslation(height);
        setQsStackScrollerPadding(height);
        mStatusBar.userActivity();
    }

    private void setQsTranslation(float height) {
        mQsContainer.setY(height - mQsContainer.getHeight());
    }

    private void setQsStackScrollerPadding(float height) {
        float start = height - mScrollView.getScrollY() + mNotificationTopPadding;
        float stackHeight = mNotificationStackScroller.getHeight() - start;
        if (stackHeight <= mMinStackHeight) {
            float overflow = mMinStackHeight - stackHeight;
            stackHeight = mMinStackHeight;
            start = mNotificationStackScroller.getHeight() - stackHeight;
            mNotificationStackScroller.setTranslationY(overflow);
            mNotificationTranslation = overflow + mScrollView.getScrollY();
        } else {
            mNotificationStackScroller.setTranslationY(0);
            mNotificationTranslation = mScrollView.getScrollY();
        }
        mNotificationStackScroller.setTopPadding(clampQsStackScrollerPadding((int) start), false);
    }

    private int clampQsStackScrollerPadding(int desiredPadding) {
        return Math.max(desiredPadding, mStackScrollerIntrinsicPadding);
    }

    private void trackMovement(MotionEvent event) {
        if (mVelocityTracker != null) mVelocityTracker.addMovement(event);
        mLastTouchX = event.getX();
        mLastTouchY = event.getY();
    }

    private void initVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
        }
        mVelocityTracker = VelocityTracker.obtain();
    }

    private float getCurrentVelocity() {
        if (mVelocityTracker == null) {
            return 0;
        }
        mVelocityTracker.computeCurrentVelocity(1000);
        return mVelocityTracker.getYVelocity();
    }

    private void cancelAnimation() {
        if (mQsExpansionAnimator != null) {
            mQsExpansionAnimator.cancel();
        }
    }
    private void flingSettings(float vel, boolean expand) {
        float target = expand ? mQsMaxExpansionHeight : mQsMinExpansionHeight;
        if (target == mQsExpansionHeight) {
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(mQsExpansionHeight, target);
        mFlingAnimationUtils.apply(animator, mQsExpansionHeight, target, vel);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setQsExpansion((Float) animation.getAnimatedValue());
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mQsExpansionAnimator = null;
            }
        });
        animator.start();
        mQsExpansionAnimator = animator;
    }

    /**
     * @return Whether we should intercept a gesture to open Quick Settings.
     */
    private boolean shouldQuickSettingsIntercept(float x, float y, float yDiff) {
        if (!mQsExpansionEnabled) {
            return false;
        }
        boolean onHeader = x >= mHeader.getLeft() && x <= mHeader.getRight()
                && y >= mHeader.getTop() && y <= mHeader.getBottom();
        if (mQsExpanded) {
            return onHeader || (mScrollView.isScrolledToBottom() && yDiff < 0);
        } else {
            return onHeader;
        }
    }

    @Override
    public void setVisibility(int visibility) {
        int oldVisibility = getVisibility();
        super.setVisibility(visibility);
        if (visibility != oldVisibility) {
            reparentStatusIcons(visibility == VISIBLE);
        }
    }

    /**
     * When the notification panel gets expanded, we need to move the status icons in the header
     * card.
     */
    private void reparentStatusIcons(boolean toHeader) {
        if (mStatusBar == null) {
            return;
        }
        LinearLayout systemIcons = mStatusBar.getSystemIcons();
        if (systemIcons.getParent() != null) {
            ((ViewGroup) systemIcons.getParent()).removeView(systemIcons);
        }
        if (toHeader) {
            mHeader.attachSystemIcons(systemIcons);
        } else {
            mHeader.onSystemIconsDetached();
            mStatusBar.reattachSystemIcons();
        }
    }

    @Override
    protected boolean isScrolledToBottom() {
        if (!isInSettings()) {
            return mNotificationStackScroller.isScrolledToBottom();
        }
        return super.isScrolledToBottom();
    }

    @Override
    protected int getMaxPanelHeight() {
        // TODO: Figure out transition for collapsing when QS is open, adjust height here.
        int maxPanelHeight = super.getMaxPanelHeight();
        int emptyBottomMargin = mStackScrollerContainer.getHeight()
                - mNotificationStackScroller.getHeight()
                + mNotificationStackScroller.getEmptyBottomMargin();
        int maxHeight = maxPanelHeight - emptyBottomMargin - mTopPaddingAdjustment;
        maxHeight = Math.max(maxHeight, mStatusBarMinHeight);
        return maxHeight;
    }

    private boolean isInSettings() {
        return mQsExpanded;
    }

    @Override
    protected void onHeightUpdated(float expandedHeight) {
        if (!mQsExpanded) {
            positionClockAndNotifications();
        }
        mNotificationStackScroller.setStackHeight(expandedHeight);
    }

    @Override
    protected void onExpandingStarted() {
        super.onExpandingStarted();
        mNotificationStackScroller.onExpansionStarted();
        mIsExpanding = true;
    }

    @Override
    protected void onExpandingFinished() {
        super.onExpandingFinished();
        mNotificationStackScroller.onExpansionStopped();
        mIsExpanding = false;
    }

    @Override
    protected void onOverExpansionChanged(float overExpansion) {
        float currentOverScroll = mNotificationStackScroller.getCurrentOverScrolledPixels(true);
        mNotificationStackScroller.setOverScrolledPixels(currentOverScroll + overExpansion
                        - mOverExpansion, true /* onTop */, false /* animate */);
        super.onOverExpansionChanged(overExpansion);
    }

    @Override
    protected void onTrackingStopped(boolean expand) {
        super.onTrackingStopped(expand);
        mOverExpansion = 0.0f;
        mNotificationStackScroller.setOverScrolledPixels(0.0f, true /* onTop */,
                true /* animate */);
    }


    @Override
    public void onHeightChanged(ExpandableView view) {
        requestPanelHeightUpdate();
    }

    @Override
    public void onScrollChanged() {
        if (mQsExpanded) {
            mNotificationStackScroller.setTranslationY(
                    mNotificationTranslation - mScrollView.getScrollY());
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mPageSwiper.onConfigurationChanged();
    }

    @Override
    public void onClick(View v) {
        if (v == mHeader.getBackgroundView()) {
            onQsExpansionStarted();
            if (mQsExpanded) {
                flingSettings(0 /* vel */, false /* expand */);
            } else if (mQsExpansionEnabled) {
                flingSettings(0 /* vel */, true /* expand */);
            }
        }
    }

    @Override
    public void onAnimationToSideStarted(boolean rightPage) {
        if (rightPage) {
            mKeyguardBottomArea.launchCamera();
        } else {
            mKeyguardBottomArea.launchPhone();
        }
        mBlockTouches = true;
    }


    @Override
    public float getPageWidth() {
        return getWidth();
    }

    @Override
    public ArrayList<View> getTranslationViews() {
        return mSwipeTranslationViews;
    }

    @Override
    public View getLeftIcon() {
        return mKeyguardBottomArea.getPhoneImageView();
    }

    @Override
    public View getCenterIcon() {
        return mKeyguardBottomArea.getLockIcon();
    }

    @Override
    public View getRightIcon() {
        return mKeyguardBottomArea.getCameraImageView();
    }
}
