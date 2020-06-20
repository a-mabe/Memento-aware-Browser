// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.offlinepages.indicator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.chromium.chrome.browser.offlinepages.indicator.OfflineIndicatorControllerV2.STATUS_INDICATOR_COOLDOWN_BEFORE_NEXT_ACTION_MS;
import static org.chromium.chrome.browser.offlinepages.indicator.OfflineIndicatorControllerV2.STATUS_INDICATOR_WAIT_BEFORE_HIDE_DURATION_MS;
import static org.chromium.chrome.browser.offlinepages.indicator.OfflineIndicatorControllerV2.setMockElapsedTimeSupplier;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.chromium.base.TimeUtils;
import org.chromium.base.TimeUtilsJni;
import org.chromium.base.supplier.ObservableSupplierImpl;
import org.chromium.base.supplier.Supplier;
import org.chromium.base.test.BaseRobolectricTestRunner;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.offlinepages.indicator.ConnectivityDetector.ConnectionState;
import org.chromium.chrome.browser.status_indicator.StatusIndicatorCoordinator;

/**
 * Unit tests for {@link OfflineIndicatorControllerV2}.
 */
@RunWith(BaseRobolectricTestRunner.class)
public class OfflineIndicatorControllerV2UnitTest {
    @Mock
    private Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private StatusIndicatorCoordinator mStatusIndicator;
    @Mock
    private ConnectivityDetector mConnectivityDetector;
    @Mock
    private OfflineDetector mOfflineDetector;
    @Mock
    private Handler mHandler;
    @Mock
    private Supplier<Boolean> mCanAnimateNativeBrowserControls;
    @Mock
    private TimeUtils.Natives mTimeUtils;

    private ObservableSupplierImpl<Boolean> mIsUrlBarFocusedSupplier =
            new ObservableSupplierImpl<>();
    private OfflineIndicatorControllerV2 mController;
    private long mElapsedTimeMs;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getTheme()).thenReturn(null);
        when(mContext.getString(R.string.offline_indicator_v2_offline_text)).thenReturn("Offline");
        when(mContext.getString(R.string.offline_indicator_v2_back_online_text))
                .thenReturn("Online");
        when(mResources.getDrawable(anyInt(), any())).thenReturn(null);
        when(mResources.getColor(anyInt(), any())).thenReturn(0);
        when(mCanAnimateNativeBrowserControls.get()).thenReturn(true);
        TimeUtilsJni.TEST_HOOKS.setInstanceForTesting(mTimeUtils);
        when(mTimeUtils.getTimeTicksNowUs()).thenReturn(0L);

        mIsUrlBarFocusedSupplier.set(false);
        OfflineDetector.setMockConnectivityDetector(mConnectivityDetector);
        OfflineIndicatorControllerV2.setMockOfflineDetector(mOfflineDetector);
        mElapsedTimeMs = 0;
        OfflineIndicatorControllerV2.setMockElapsedTimeSupplier(() -> mElapsedTimeMs);
        mController = new OfflineIndicatorControllerV2(mContext, mStatusIndicator,
                mIsUrlBarFocusedSupplier, mCanAnimateNativeBrowserControls);
        mController.setHandlerForTesting(mHandler);
    }

    @After
    public void tearDown() {
        OfflineIndicatorControllerV2.setMockElapsedTimeSupplier(null);
    }

    /**
     * Tests that the offline indicator shows when the device goes offline.
     */
    @Test
    public void testShowsStatusIndicatorWhenOffline() {
        // Show.
        changeConnectionState(true);
        verify(mStatusIndicator).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
    }

    /**
     * Tests that the offline indicator hides when the device goes online.
     */
    @Test
    public void testHidesStatusIndicatorWhenOnline() {
        // First, show.
        changeConnectionState(true);
        // Fast forward the cool-down.
        advanceTimeByMs(STATUS_INDICATOR_COOLDOWN_BEFORE_NEXT_ACTION_MS);
        // Now, hide.
        changeConnectionState(false);
        // When hiding, the indicator will get an #updateContent() call, then #hide() 2 seconds
        // after that. First, verify the #updateContent() call.
        final ArgumentCaptor<Runnable> endAnimationCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mStatusIndicator)
                .updateContent(eq("Online"), any(), anyInt(), anyInt(), anyInt(),
                        endAnimationCaptor.capture());
        // Simulate browser controls animation ending.
        endAnimationCaptor.getValue().run();
        // This should post a runnable to hide w/ a delay.
        final ArgumentCaptor<Runnable> hideCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(
                hideCaptor.capture(), eq(STATUS_INDICATOR_WAIT_BEFORE_HIDE_DURATION_MS));
        // Let's see if the Runnable we captured actually hides the indicator.
        hideCaptor.getValue().run();
        verify(mStatusIndicator).hide();
    }

    /**
     * Tests that the indicator doesn't hide before the cool-down is complete.
     */
    @Test
    public void testCoolDown_Hide() {
        // First, show.
        changeConnectionState(true);
        // Advance time.
        advanceTimeByMs(3000);
        // Now, try to hide.
        changeConnectionState(false);

        // Cool-down should prevent it from hiding and post a runnable for after the time is up.
        verify(mStatusIndicator, never())
                .updateContent(any(), any(), anyInt(), anyInt(), anyInt(), any(Runnable.class));
        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(
                captor.capture(), eq(STATUS_INDICATOR_COOLDOWN_BEFORE_NEXT_ACTION_MS - 3000L));

        // Advance the time and simulate the |Handler| running the posted runnable.
        advanceTimeByMs(2000);
        captor.getValue().run();
        // #updateContent() should be called since the cool-down is complete.
        verify(mStatusIndicator)
                .updateContent(
                        eq("Online"), any(), anyInt(), anyInt(), anyInt(), any(Runnable.class));
    }

    /**
     * Tests that the indicator doesn't show before the cool-down is complete.
     */
    @Test
    public void testCoolDown_Show() {
        // First, show.
        changeConnectionState(true);
        verify(mStatusIndicator, times(1)).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
        // Advance time so we can hide.
        advanceTimeByMs(STATUS_INDICATOR_COOLDOWN_BEFORE_NEXT_ACTION_MS);
        // Now, hide.
        changeConnectionState(false);

        // Try to show again, but before the cool-down is completed.
        advanceTimeByMs(1000);
        changeConnectionState(true);
        // Cool-down should prevent it from showing and post a runnable for after the time is up.
        // times(1) because it's been already called once above, no new calls.
        verify(mStatusIndicator, times(1)).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(
                captor.capture(), eq(STATUS_INDICATOR_COOLDOWN_BEFORE_NEXT_ACTION_MS - 1000L));

        // Advance the time and simulate the |Handler| running the posted runnable.
        advanceTimeByMs(4000);
        captor.getValue().run();
        // #show() should be called since the cool-down is complete.
        verify(mStatusIndicator, times(2)).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
    }

    /**
     * Tests that the indicator doesn't show if the device went back online after the show was
     * scheduled.
     */
    @Test
    public void testCoolDown_ChangeConnectionAfterShowScheduled() {
        changeConnectionState(true);
        advanceTimeByMs(STATUS_INDICATOR_COOLDOWN_BEFORE_NEXT_ACTION_MS);
        changeConnectionState(false);

        // Try to show, but before the cool-down is completed.
        advanceTimeByMs(1000);
        changeConnectionState(true);
        // Cool-down should prevent it from showing and post a runnable for after the time is up.
        // times(1) because it's been already called once above, no new calls.
        verify(mStatusIndicator, times(1)).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(
                captor.capture(), eq(STATUS_INDICATOR_COOLDOWN_BEFORE_NEXT_ACTION_MS - 1000L));
        // Callbacks to show/hide are removed every time the connectivity changes. We use this to
        // capture the callback.
        verify(mHandler, times(3)).removeCallbacks(captor.getValue());
        // Advance time and change connection.
        advanceTimeByMs(2000);
        changeConnectionState(false);

        // Since we're back online, the posted runnable won't show the indicator.
        advanceTimeByMs(2000);
        captor.getValue().run();
        // Still times(1), no new call after the last one.
        verify(mStatusIndicator, times(1)).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
    }

    /**
     * Tests that the indicator doesn't show until the omnibox is unfocused.
     */
    @Test
    public void testOmniboxFocus_DelayShowing() {
        // Simulate focusing the omnibox.
        mIsUrlBarFocusedSupplier.set(true);
        // Now show, at least try.
        changeConnectionState(true);
        // Shouldn't show because the omnibox is focused.
        verify(mStatusIndicator, never()).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());

        // Should show once unfocused.
        mIsUrlBarFocusedSupplier.set(false);
        verify(mStatusIndicator).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
    }

    /**
     * Tests that the indicator doesn't show when the omnibox is unfocused if the device goes back
     * online before the omnibox is unfocused.
     */
    @Test
    public void testOmniboxFocus_ChangeConnectionAfterShowScheduled() {
        // Simulate focusing the omnibox.
        mIsUrlBarFocusedSupplier.set(true);
        // Now show, at least try.
        changeConnectionState(true);
        // Shouldn't show because the omnibox is focused.
        verify(mStatusIndicator, never()).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());

        // Now, simulate going back online.
        changeConnectionState(false);
        // Unfocusing shouldn't cause a show because we're not offline.
        mIsUrlBarFocusedSupplier.set(false);
        verify(mStatusIndicator, never()).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
    }

    /**
     * Tests that the indicator waits for the omnibox to be unfocused if the omnibox was focused
     * when the cool-down ended and the indicator was going to be shown.
     */
    @Test
    public void testOmniboxIsFocusedWhenShownAfterCoolDown() {
        changeConnectionState(true);
        advanceTimeByMs(STATUS_INDICATOR_COOLDOWN_BEFORE_NEXT_ACTION_MS);
        changeConnectionState(false);

        // Try to show, but before the cool-down is completed.
        advanceTimeByMs(1000);
        changeConnectionState(true);
        // times(1) because it's been already called once above, no new calls.
        verify(mStatusIndicator, times(1)).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
        final ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(mHandler).postDelayed(
                captor.capture(), eq(STATUS_INDICATOR_COOLDOWN_BEFORE_NEXT_ACTION_MS - 1000L));

        // Now, simulate focusing the omnibox.
        mIsUrlBarFocusedSupplier.set(true);
        // Then advance the time and run the runnable.
        advanceTimeByMs(4000);
        captor.getValue().run();
        // Still times(1), no new calls. The indicator shouldn't show since the omnibox is focused.
        verify(mStatusIndicator, times(1)).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
        // Should show once unfocused.
        mIsUrlBarFocusedSupplier.set(false);
        verify(mStatusIndicator, times(2)).show(eq("Offline"), any(), anyInt(), anyInt(), anyInt());
    }

    private void changeConnectionState(boolean offline) {
        final int state = offline ? ConnectionState.NO_INTERNET : ConnectionState.VALIDATED;
        when(mOfflineDetector.isConnectionStateOffline()).thenReturn(offline);
        mController.onConnectionStateChanged(offline);
    }

    private void advanceTimeByMs(long delta) {
        mElapsedTimeMs += delta;
        setMockElapsedTimeSupplier(() -> mElapsedTimeMs);
    }
}
