// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.autofill_assistant;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.assertThat;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;

import static org.chromium.chrome.browser.autofill_assistant.AutofillAssistantUiTestUtil.waitUntilViewMatchesCondition;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import org.chromium.base.Callback;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.chrome.autofill_assistant.R;
import org.chromium.chrome.browser.ChromeActivity;
import org.chromium.chrome.browser.autofill_assistant.overlay.AssistantOverlayCoordinator;
import org.chromium.chrome.browser.autofill_assistant.overlay.AssistantOverlayModel;
import org.chromium.chrome.browser.autofill_assistant.overlay.AssistantOverlayState;
import org.chromium.chrome.browser.customtabs.CustomTabActivityTestRule;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.test.ChromeJUnit4ClassRunner;
import org.chromium.components.browser_ui.bottomsheet.BottomSheetController;
import org.chromium.content_public.browser.test.util.TestThreadUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Tests {@link AssistantOnboardingCoordinator}
 */
@RunWith(ChromeJUnit4ClassRunner.class)
@CommandLineFlags.Add(ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE)
public class AssistantOnboardingCoordinatorTest {
    @Rule
    public CustomTabActivityTestRule mCustomTabActivityTestRule = new CustomTabActivityTestRule();

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    Callback<Boolean> mCallback;

    private ChromeActivity mActivity;
    private BottomSheetController mBottomSheetController;
    private Tab mTab;

    @Before
    public void setUp() throws Exception {
        AutofillAssistantUiTestUtil.startOnBlankPage(mCustomTabActivityTestRule);
        mActivity = mCustomTabActivityTestRule.getActivity();
        mBottomSheetController = TestThreadUtils.runOnUiThreadBlocking(
                () -> AutofillAssistantUiTestUtil.getBottomSheetController(mActivity));
        mTab = mActivity.getTabModelSelector().getCurrentTab();
    }

    private AssistantOnboardingCoordinator createCoordinator(Tab tab) {
        AssistantOnboardingCoordinator coordinator =
                new AssistantOnboardingCoordinator("", new HashMap<String, String>(), mActivity,
                        mBottomSheetController, mActivity.getFullscreenManager(),
                        mActivity.getCompositorViewHolder(), mActivity.getScrim());
        coordinator.disableAnimationForTesting();
        return coordinator;
    }

    @Test
    @MediumTest
    public void testAcceptOnboarding() throws Exception {
        testOnboarding(R.id.button_init_ok, true);
    }

    @Test
    @MediumTest
    public void testRejectOnboarding() throws Exception {
        testOnboarding(R.id.button_init_not_ok, false);
    }

    private void testOnboarding(@IdRes int buttonToClick, boolean expectAccept) throws Exception {
        AutofillAssistantPreferencesUtil.setInitialPreferences(!expectAccept);

        AssistantOnboardingCoordinator coordinator = createCoordinator(mTab);
        showOnboardingAndWait(coordinator, mCallback);

        assertTrue(TestThreadUtils.runOnUiThreadBlocking(coordinator::isInProgress));
        onView(is(mActivity.getScrim())).check(matches(isDisplayed()));
        onView(withId(buttonToClick)).perform(scrollTo(), click());

        verify(mCallback).onResult(expectAccept);
        assertFalse(TestThreadUtils.runOnUiThreadBlocking(coordinator::isInProgress));
        assertEquals(expectAccept, AutofillAssistantPreferencesUtil.isAutofillOnboardingAccepted());
    }

    @Test
    @MediumTest
    public void testOnboardingWithNoTabs() {
        AssistantOnboardingCoordinator coordinator = createCoordinator(/* tab= */ null);
        showOnboardingAndWait(coordinator, mCallback);

        onView(withId(R.id.button_init_ok)).perform(click());

        verify(mCallback).onResult(true);
    }

    @Test
    @MediumTest
    public void testTransferControls() throws Exception {
        AssistantOnboardingCoordinator coordinator = createCoordinator(mTab);

        List<AssistantOverlayCoordinator> capturedOverlays =
                Collections.synchronizedList(new ArrayList<>());
        showOnboardingAndWait(coordinator,
                (accepted) -> { capturedOverlays.add(coordinator.transferControls()); });

        onView(withId(R.id.button_init_ok)).perform(click());
        assertFalse(TestThreadUtils.runOnUiThreadBlocking(coordinator::isInProgress));

        // An overlay was captured, and it is still shown.
        onView(is(mActivity.getScrim())).check(matches(isDisplayed()));
        assertEquals(1, capturedOverlays.size());
        AssistantOverlayCoordinator overlay = capturedOverlays.get(0);
        assertNotNull(overlay);
        assertEquals(
                AssistantOverlayState.FULL, overlay.getModel().get(AssistantOverlayModel.STATE));

        // The bottom sheet content is still the assistant one.
        assertThat(mBottomSheetController.getCurrentSheetContent(),
                instanceOf(AssistantBottomSheetContent.class));
    }

    @Test
    @MediumTest
    public void testShownFlag() throws Exception {
        AssistantOnboardingCoordinator coordinator = createCoordinator(/* tab= */ null);
        assertFalse(coordinator.getOnboardingShown());

        showOnboardingAndWait(coordinator, mCallback);
        assertTrue(coordinator.getOnboardingShown());
    }

    @Test
    @MediumTest
    public void testShowDifferentInformationalText() throws Exception {
        AutofillAssistantPreferencesUtil.setInitialPreferences(true);

        HashMap<String, String> parameters = new HashMap();
        parameters.put("INTENT", "RENT_CAR");
        AssistantOnboardingCoordinator coordinator = new AssistantOnboardingCoordinator("",
                parameters, mActivity, mBottomSheetController, mActivity.getFullscreenManager(),
                mActivity.getCompositorViewHolder(), mActivity.getScrim());
        coordinator.disableAnimationForTesting();
        showOnboardingAndWait(coordinator, mCallback);

        TextView termsView = mActivity.findViewById(R.id.onboarding_subtitle);
        assertEquals(
                mActivity.getResources().getText(R.string.autofill_assistant_init_message_short),
                termsView.getText());
        TextView titleView = mActivity.findViewById(R.id.onboarding_try_assistant);
        assertEquals(
                mActivity.getResources().getText(R.string.autofill_assistant_init_message_rent_car),
                titleView.getText());
    }

    @Test
    @MediumTest
    public void testShowExperimentalInformationalText() throws Exception {
        AutofillAssistantPreferencesUtil.setInitialPreferences(true);

        HashMap<String, String> parameters = new HashMap();
        parameters.put("INTENT", "BUY_MOVIE_TICKET");
        AssistantOnboardingCoordinator coordinator = new AssistantOnboardingCoordinator("4363482",
                parameters, mActivity, mBottomSheetController, mActivity.getFullscreenManager(),
                mActivity.getCompositorViewHolder(), mActivity.getScrim());
        coordinator.disableAnimationForTesting();
        showOnboardingAndWait(coordinator, mCallback);

        TextView termsView = mActivity.findViewById(R.id.onboarding_subtitle);
        assertEquals(
                mActivity.getResources().getText(R.string.autofill_assistant_init_message_short),
                termsView.getText());
        TextView titleView = mActivity.findViewById(R.id.onboarding_try_assistant);
        assertEquals(mActivity.getResources().getText(
                             R.string.autofill_assistant_init_message_buy_movie_tickets),
                titleView.getText());
    }

    @Test
    @MediumTest
    public void testShowStandardInformationalText() throws Exception {
        AutofillAssistantPreferencesUtil.setInitialPreferences(true);

        HashMap<String, String> parameters = new HashMap();
        AssistantOnboardingCoordinator coordinator = new AssistantOnboardingCoordinator("",
                parameters, mActivity, mBottomSheetController, mActivity.getFullscreenManager(),
                mActivity.getCompositorViewHolder(), mActivity.getScrim());
        coordinator.disableAnimationForTesting();
        showOnboardingAndWait(coordinator, mCallback);

        TextView termsView = mActivity.findViewById(R.id.onboarding_subtitle);
        assertEquals(View.VISIBLE, termsView.getVisibility());
        assertEquals(mActivity.getResources().getText(R.string.autofill_assistant_init_message),
                termsView.getText());
        TextView titleView = mActivity.findViewById(R.id.onboarding_try_assistant);
        assertEquals(mActivity.getResources().getText(R.string.autofill_assistant_init_title),
                titleView.getText());
    }

    /** Trigger onboarding and wait until it is fully displayed. */
    private void showOnboardingAndWait(
            AssistantOnboardingCoordinator coordinator, Callback<Boolean> callback) {
        TestThreadUtils.runOnUiThreadBlocking(() -> coordinator.show(callback));
        waitUntilViewMatchesCondition(withId(R.id.button_init_ok), isCompletelyDisplayed());
    }
}
