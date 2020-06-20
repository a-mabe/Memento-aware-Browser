// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.payments;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeDown;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.support.test.InstrumentationRegistry;
import android.support.test.uiautomator.UiDevice;

import androidx.test.filters.SmallTest;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.test.params.ParameterAnnotations;
import org.chromium.base.test.params.ParameterProvider;
import org.chromium.base.test.params.ParameterSet;
import org.chromium.base.test.params.ParameterizedRunner;
import org.chromium.base.test.util.CommandLineFlags;
import org.chromium.base.test.util.Feature;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeActivity;
import org.chromium.chrome.browser.flags.ChromeSwitches;
import org.chromium.chrome.browser.payments.handler.PaymentHandlerCoordinator;
import org.chromium.chrome.browser.payments.handler.PaymentHandlerCoordinator.PaymentHandlerUiObserver;
import org.chromium.chrome.browser.payments.handler.PaymentHandlerCoordinator.PaymentHandlerWebContentsObserver;
import org.chromium.chrome.test.ChromeJUnit4RunnerDelegate;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.test.util.CriteriaHelper;
import org.chromium.net.test.EmbeddedTestServer;
import org.chromium.net.test.ServerCertificate;
import org.chromium.ui.test.util.DisableAnimationsTestRule;
import org.chromium.url.GURL;

import java.util.Arrays;
import java.util.List;

/**
 * A test for the Expandable PaymentHandler {@link PaymentHandlerCoordinator}.
 */
@RunWith(ParameterizedRunner.class)
@ParameterAnnotations.UseRunnerDelegate(ChromeJUnit4RunnerDelegate.class)
@CommandLineFlags.Add({ChromeSwitches.DISABLE_FIRST_RUN_EXPERIENCE})
public class ExpandablePaymentHandlerTest {
    // Disable animations to reduce flakiness.
    @ClassRule
    public static DisableAnimationsTestRule sNoAnimationsRule = new DisableAnimationsTestRule();

    // Open a tab on the blank page first to initiate the native bindings required by the test
    // server.
    @Rule
    public PaymentRequestTestRule mRule = new PaymentRequestTestRule("about:blank");

    // Host the tests on https://127.0.0.1, because file:// URLs cannot have service workers.
    private EmbeddedTestServer mServer;
    private boolean mUiShownCalled = false;
    private boolean mUiClosedCalled = false;
    private boolean mWebContentsInitializedCallbackInvoked = false;
    private UiDevice mDevice;
    private boolean mDefaultIsIncognito = false;
    private ChromeActivity mDefaultActivity;

    /**
     * A list of bad server-certificates used for parameterized tests.
     */
    public static class BadCertParams implements ParameterProvider {
        @Override
        public List<ParameterSet> getParameters() {
            return Arrays.asList(new ParameterSet()
                                         .value(ServerCertificate.CERT_MISMATCHED_NAME)
                                         .name("CERT_MISMATCHED_NAME"),
                    new ParameterSet().value(ServerCertificate.CERT_EXPIRED).name("CERT_EXPIRED"),
                    new ParameterSet()
                            .value(ServerCertificate.CERT_CHAIN_WRONG_ROOT)
                            .name("CERT_CHAIN_WRONG_ROOT"),
                    new ParameterSet()
                            .value(ServerCertificate.CERT_COMMON_NAME_ONLY)
                            .name("CERT_COMMON_NAME_ONLY"),
                    new ParameterSet()
                            .value(ServerCertificate.CERT_SHA1_LEAF)
                            .name("CERT_SHA1_LEAF"),
                    new ParameterSet()
                            .value(ServerCertificate.CERT_BAD_VALIDITY)
                            .name("CERT_BAD_VALIDITY"),
                    new ParameterSet()
                            .value(ServerCertificate.CERT_TEST_NAMES)
                            .name("CERT_TEST_NAMES"));
        }
    }

    /**
     * A list of good server-certificates used for parameterized tests.
     */
    public static class GoodCertParams implements ParameterProvider {
        @Override
        public List<ParameterSet> getParameters() {
            return Arrays.asList(
                    new ParameterSet().value(ServerCertificate.CERT_OK).name("CERT_OK"),
                    new ParameterSet()
                            .value(ServerCertificate.CERT_COMMON_NAME_IS_DOMAIN)
                            .name("CERT_COMMON_NAME_IS_DOMAIN"),
                    new ParameterSet()
                            .value(ServerCertificate.CERT_OK_BY_INTERMEDIATE)
                            .name("CERT_OK_BY_INTERMEDIATE"),
                    new ParameterSet().value(ServerCertificate.CERT_AUTO).name("CERT_AUTO"));
        }
    }

    @Before
    public void setUp() throws Throwable {
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mDefaultActivity = mRule.getActivity();
    }

    private PaymentHandlerCoordinator createPaymentHandlerAndShow(boolean isIncognito)
            throws Throwable {
        PaymentHandlerCoordinator paymentHandler = new PaymentHandlerCoordinator();
        mRule.runOnUiThread(
                ()
                        -> paymentHandler.show(mDefaultActivity, defaultPaymentAppUrl(),
                                isIncognito, defaultWebContentObserver(), defaultUiObserver()));
        return paymentHandler;
    }

    private String getOrigin(EmbeddedTestServer server) {
        String longOrigin = server.getURL("/");
        String begin = "https://";
        String end = "/";
        assert longOrigin.startsWith(begin);
        assert longOrigin.endsWith(end);
        return longOrigin.substring(begin.length(), longOrigin.length() - end.length());
    }

    private void startServer(@ServerCertificate int serverCertificate) {
        mServer = EmbeddedTestServer.createAndStartHTTPSServer(
                InstrumentationRegistry.getContext(), serverCertificate);
    }

    private void startDefaultServer() {
        startServer(ServerCertificate.CERT_OK);
    }

    private GURL defaultPaymentAppUrl() {
        return new GURL(mServer.getURL(
                "/components/test/data/payments/maxpay.com/payment_handler_window.html"));
    }

    private PaymentHandlerWebContentsObserver defaultWebContentObserver() {
        return new PaymentHandlerWebContentsObserver() {
            @Override
            public void onWebContentsInitialized(WebContents webContents) {
                mWebContentsInitializedCallbackInvoked = true;
            }
        };
    }

    private PaymentHandlerUiObserver defaultUiObserver() {
        return new PaymentHandlerUiObserver() {
            @Override
            public void onPaymentHandlerUiClosed() {
                mUiClosedCalled = true;
            }

            @Override
            public void onPaymentHandlerUiShown() {
                mUiShownCalled = true;
            }
        };
    }

    private void waitForUiShown() {
        CriteriaHelper.pollInstrumentationThread(() -> mUiShownCalled);
    }

    private void waitForTitleShown(WebContents paymentAppWebContents) {
        waitForTitleShown(paymentAppWebContents, "Max Pay");
    }

    private void waitForTitleShown(WebContents paymentAppWebContents, String title) {
        CriteriaHelper.pollInstrumentationThread(
                () -> paymentAppWebContents.getTitle().equals(title));
    }

    private void waitForUiClosed() {
        CriteriaHelper.pollInstrumentationThread(() -> mUiClosedCalled);
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    public void testOpenClose() throws Throwable {
        startDefaultServer();
        PaymentHandlerCoordinator paymentHandler = createPaymentHandlerAndShow(mDefaultIsIncognito);
        waitForUiShown();

        mRule.runOnUiThread(() -> paymentHandler.hide());
        waitForUiClosed();
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    public void testSwipeDownCloseUI() throws Throwable {
        startDefaultServer();
        createPaymentHandlerAndShow(mDefaultIsIncognito);
        waitForUiShown();

        onView(withId(org.chromium.components.browser_ui.bottomsheet.R.id
                               .bottom_sheet_control_container))
                .perform(swipeDown());
        waitForUiClosed();
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    public void testClickCloseButtonCloseUI() throws Throwable {
        startDefaultServer();
        createPaymentHandlerAndShow(mDefaultIsIncognito);
        waitForUiShown();

        onView(withId(R.id.close)).perform(click());
        waitForUiClosed();
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    public void testWebContentsInitializedCallbackInvoked() throws Throwable {
        startDefaultServer();
        PaymentHandlerCoordinator paymentHandler = createPaymentHandlerAndShow(mDefaultIsIncognito);
        waitForUiShown();

        Assert.assertTrue(mWebContentsInitializedCallbackInvoked);

        mRule.runOnUiThread(() -> paymentHandler.hide());
        waitForUiClosed();
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    public void testWebContentsDestroy() throws Throwable {
        startDefaultServer();
        PaymentHandlerCoordinator paymentHandler = createPaymentHandlerAndShow(mDefaultIsIncognito);
        waitForUiShown();

        Assert.assertFalse(paymentHandler.getWebContentsForTest().isDestroyed());
        mRule.runOnUiThread(() -> paymentHandler.hide());
        waitForUiClosed();
        Assert.assertTrue(paymentHandler.getWebContentsForTest().isDestroyed());
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    public void testIncognitoTrue() throws Throwable {
        startDefaultServer();
        PaymentHandlerCoordinator paymentHandler =
                createPaymentHandlerAndShow(/*isIncognito=*/true);
        waitForUiShown();

        Assert.assertTrue(paymentHandler.getWebContentsForTest().isIncognito());

        mRule.runOnUiThread(() -> paymentHandler.hide());
        waitForUiClosed();
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    public void testIncognitoFalse() throws Throwable {
        startDefaultServer();
        PaymentHandlerCoordinator paymentHandler =
                createPaymentHandlerAndShow(/*isIncognito=*/false);
        waitForUiShown();

        Assert.assertFalse(paymentHandler.getWebContentsForTest().isIncognito());

        mRule.runOnUiThread(() -> paymentHandler.hide());
        waitForUiClosed();
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    public void testUiElements() throws Throwable {
        startDefaultServer();
        PaymentHandlerCoordinator paymentHandler = createPaymentHandlerAndShow(mDefaultIsIncognito);
        waitForUiShown();

        onView(withId(org.chromium.components.browser_ui.bottomsheet.R.id.bottom_sheet))
                .check(matches(
                        withContentDescription("Payment handler sheet. Swipe down to close.")));

        CriteriaHelper.pollInstrumentationThread(
                () -> paymentHandler.getWebContentsForTest().getTitle().equals("Max Pay"));

        onView(withId(R.id.title))
                .check(matches(isDisplayed()))
                .check(matches(withText("Max Pay")));
        onView(withId(org.chromium.components.browser_ui.bottomsheet.R.id.bottom_sheet))
                .check(matches(isDisplayed()))
                .check(matches(
                        withContentDescription("Payment handler sheet. Swipe down to close.")));
        onView(withId(R.id.close))
                .check(matches(isDisplayed()))
                .check(matches(withContentDescription("Close")));
        onView(withId(R.id.security_icon))
                .check(matches(isDisplayed()))
                .check(matches(withContentDescription("Connection is secure. Site information")));
        onView(withId(R.id.origin))
                .check(matches(isDisplayed()))
                .check(matches(withText(getOrigin(mServer))));

        mRule.runOnUiThread(() -> paymentHandler.hide());
        waitForUiClosed();
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    public void testOpenPageInfoDialog() throws Throwable {
        startDefaultServer();
        PaymentHandlerCoordinator paymentHandler = createPaymentHandlerAndShow(mDefaultIsIncognito);
        waitForTitleShown(paymentHandler.getWebContentsForTest());

        onView(withId(R.id.security_icon)).perform(click());

        String paymentAppUrl = mServer.getURL(
                "/components/test/data/payments/maxpay.com/payment_handler_window.html");
        onView(withId(R.id.page_info_url))
                .check(matches(isDisplayed()))
                .check(matches(withText(paymentAppUrl)));

        mDevice.pressBack();

        onView(withId(R.id.page_info_url)).check(doesNotExist());

        mRule.runOnUiThread(() -> paymentHandler.hide());
        waitForUiClosed();
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    public void testNavigateBackWithSystemBackButton() throws Throwable {
        startDefaultServer();

        PaymentHandlerCoordinator paymentHandler = createPaymentHandlerAndShow(mDefaultIsIncognito);

        waitForTitleShown(paymentHandler.getWebContentsForTest(), "Max Pay");
        onView(withId(R.id.origin)).check(matches(withText(getOrigin(mServer))));

        String anotherUrl =
                mServer.getURL("/components/test/data/payments/bobpay.com/app1/index.html");
        mRule.runOnUiThread(
                ()
                        -> paymentHandler.getWebContentsForTest().getNavigationController().loadUrl(
                                new LoadUrlParams(anotherUrl)));
        waitForTitleShown(paymentHandler.getWebContentsForTest(), "Bob Pay 1");
        onView(withId(R.id.origin)).check(matches(withText(getOrigin(mServer))));

        // Press back button would navigate back if it has previous pages.
        mDevice.pressBack();
        waitForTitleShown(paymentHandler.getWebContentsForTest(), "Max Pay");
        onView(withId(R.id.origin)).check(matches(withText(getOrigin(mServer))));

        // Press back button would be no-op if it does not have any previous page.
        mDevice.pressBack();
        waitForTitleShown(paymentHandler.getWebContentsForTest(), "Max Pay");
        onView(withId(R.id.origin)).check(matches(withText(getOrigin(mServer))));

        mRule.runOnUiThread(() -> paymentHandler.hide());
        waitForUiClosed();
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    @ParameterAnnotations.UseMethodParameter(BadCertParams.class)
    public void testInsecureConnectionNotShowUi(int badCertificate) throws Throwable {
        startServer(badCertificate);
        PaymentHandlerCoordinator paymentHandler = createPaymentHandlerAndShow(mDefaultIsIncognito);

        CriteriaHelper.pollInstrumentationThread(
                () -> paymentHandler.getWebContentsForTest().isDestroyed());

        waitForUiClosed();
    }

    @Test
    @SmallTest
    @Feature({"Payments"})
    @ParameterAnnotations.UseMethodParameter(GoodCertParams.class)
    public void testSecureConnectionShowUi(int goodCertificate) throws Throwable {
        startServer(goodCertificate);
        PaymentHandlerCoordinator paymentHandler = createPaymentHandlerAndShow(mDefaultIsIncognito);
        waitForTitleShown(paymentHandler.getWebContentsForTest());

        onView(withId(R.id.security_icon))
                .check(matches(isDisplayed()))
                .check(matches(withContentDescription("Connection is secure. Site information")));

        mRule.runOnUiThread(() -> paymentHandler.hide());
        waitForUiClosed();
    }
}
