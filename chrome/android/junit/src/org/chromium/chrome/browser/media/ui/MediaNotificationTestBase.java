// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.media.ui;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.support.v4.media.session.MediaSessionCompat;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;

import org.chromium.base.ContextUtils;
import org.chromium.base.test.util.JniMocker;
import org.chromium.chrome.browser.AppHooks;
import org.chromium.chrome.browser.media.ui.ChromeMediaNotificationControllerDelegate.ListenerService;
import org.chromium.chrome.browser.notifications.NotificationUmaTracker;
import org.chromium.components.browser_ui.notifications.ChromeNotification;
import org.chromium.components.browser_ui.notifications.ForegroundServiceUtils;
import org.chromium.media_session.mojom.MediaSessionAction;
import org.chromium.services.media_session.MediaMetadata;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Common test fixtures for MediaNotificationController JUnit tests.
 */
public class MediaNotificationTestBase {
    private static final int NOTIFICATION_ID = 0;
    static final String NOTIFICATION_GROUP_NAME = "group-name";
    static final Set<Integer> DEFAULT_ACTIONS =
            Stream.of(MediaSessionAction.PLAY).collect(Collectors.toSet());
    Context mMockContext;
    MockListenerService mService;
    MediaNotificationListener mListener;
    ForegroundServiceUtils mMockForegroundServiceUtils;
    NotificationUmaTracker mMockUmaTracker;

    MediaNotificationInfo.Builder mMediaNotificationInfoBuilder;

    @Rule
    public JniMocker mocker = new JniMocker();

    protected MediaNotificationTestTabHolder createMediaNotificationTestTabHolder(
            int tabId, String url, String title) {
        return new MediaNotificationTestTabHolder(tabId, url, title, mocker);
    }

    static class MockMediaNotificationController extends MediaNotificationController {
        public MockMediaNotificationController(MediaNotificationController.Delegate delegate) {
            super(delegate);
        }
    }

    class MockListenerService extends ListenerService {
        MockListenerService() {
            super(MediaNotificationTestBase.this.getNotificationId());
        }
    }

    @Before
    public void setUp() {
        // For checking the notification presented to NotificationManager.
        assertTrue(RuntimeEnvironment.getApiLevel() >= Build.VERSION_CODES.N);

        ShadowLog.stream = System.out;

        mMockContext = spy(RuntimeEnvironment.application);
        ContextUtils.initApplicationContextForTests(mMockContext);

        mListener = mock(MediaNotificationListener.class);

        ChromeMediaNotificationControllerDelegate.sMapNotificationIdToOptions.put(
                getNotificationId(),
                new ChromeMediaNotificationControllerDelegate.NotificationOptions(
                        MockListenerService.class, NOTIFICATION_GROUP_NAME));

        mMockUmaTracker = mock(NotificationUmaTracker.class);
        MediaNotificationManager.setControllerForTesting(getNotificationId(),
                spy(new MockMediaNotificationController(
                        new ChromeMediaNotificationControllerDelegate(getNotificationId()) {
                            @Override
                            public void logNotificationShown(ChromeNotification notification) {
                                mMockUmaTracker.onNotificationShown(
                                        NotificationUmaTracker.SystemNotificationType.MEDIA,
                                        notification.getNotification());
                            }
                        })));

        mMediaNotificationInfoBuilder =
                new MediaNotificationInfo.Builder()
                        .setMetadata(new MediaMetadata("title", "artist", "album"))
                        .setOrigin("https://example.com")
                        .setListener(mListener)
                        .setMediaSessionActions(DEFAULT_ACTIONS)
                        .setId(getNotificationId());

        doNothing().when(getController()).onServiceStarted(any(ListenerService.class));
        // Robolectric does not have "ShadowMediaSession".
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) {
                MediaSessionCompat mockSession = mock(MediaSessionCompat.class);
                getController().mMediaSession = mockSession;
                doReturn(null).when(mockSession).getSessionToken();
                return "Created mock media session";
            }
        })
                .when(getController())
                .updateMediaSession();

        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) {
                Intent intent = (Intent) invocation.getArgument(0);
                startService(intent);
                return new ComponentName(mMockContext, MockListenerService.class);
            }
        })
                .when(mMockContext)
                .startService(any(Intent.class));

        mMockForegroundServiceUtils = mock(ForegroundServiceUtils.class);
        ForegroundServiceUtils.setInstanceForTesting(mMockForegroundServiceUtils);
    }

    @After
    public void tearDown() {
        AppHooks.setInstanceForTesting(null);
        MediaNotificationManager.clear(NOTIFICATION_ID);
    }

    MediaNotificationController getController() {
        return MediaNotificationManager.getController(getNotificationId());
    }

    void ensureMediaNotificationInfo() {
        getController().mMediaNotificationInfo = mMediaNotificationInfoBuilder.build();
    }

    void setUpServiceAndClearInvocations() {
        setUpService();
        clearInvocations(getController());
        clearInvocations(mService);
        clearInvocations(mMockContext);
        clearInvocations(mMockUmaTracker);
    }

    void setUpService() {
        ensureMediaNotificationInfo();

        Intent intent = getController().mDelegate.createServiceIntent();
        mMockContext.startService(intent);
    }

    private void startService(Intent intent) {
        ensureService();
        mService.onStartCommand(intent, 0, 0);
    }

    private void ensureService() {
        if (mService != null) return;
        mService = spy(new MockListenerService());

        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) {
                mService.onDestroy();
                mService = null;
                return "service stopped";
            }
        })
                .when(mService)
                .stopListenerService();
    }

    Bitmap iconToBitmap(Icon icon) {
        if (icon == null) return null;

        BitmapDrawable drawable = (BitmapDrawable) icon.loadDrawable(mMockContext);
        assert drawable != null;
        return drawable.getBitmap();
    }

    int getNotificationId() {
        return NOTIFICATION_ID;
    }

    void advanceTimeByMillis(int timeMillis) {
        ShadowLooper.idleMainLooper(timeMillis, TimeUnit.MILLISECONDS);
    }
}
