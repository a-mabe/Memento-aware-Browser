// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.media.ui;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.IBinder;
import android.support.v4.media.session.MediaSessionCompat;
import android.util.SparseArray;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.mediarouter.media.MediaRouter;

import org.chromium.base.ContextUtils;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.notifications.NotificationBuilderFactory;
import org.chromium.chrome.browser.notifications.NotificationConstants;
import org.chromium.chrome.browser.notifications.NotificationUmaTracker;
import org.chromium.chrome.browser.notifications.channels.ChromeChannelDefinitions;
import org.chromium.components.browser_ui.notifications.ChromeNotification;
import org.chromium.components.browser_ui.notifications.ChromeNotificationBuilder;
import org.chromium.components.browser_ui.notifications.ForegroundServiceUtils;
import org.chromium.components.browser_ui.notifications.NotificationMetadata;

/** A class that provides Chrome-specific behavior to {@link MediaNotificationController}. */
class ChromeMediaNotificationControllerDelegate implements MediaNotificationController.Delegate {
    private int mNotificationId;

    @VisibleForTesting
    static class NotificationOptions {
        public Class<?> serviceClass;
        public String groupName;

        public NotificationOptions(Class<?> serviceClass, String groupName) {
            this.serviceClass = serviceClass;
            this.groupName = groupName;
        }
    }

    // Maps the notification ids to their corresponding choices of the service, button receiver and
    // group name.
    @VisibleForTesting
    static SparseArray<NotificationOptions> sMapNotificationIdToOptions;

    static {
        sMapNotificationIdToOptions = new SparseArray<NotificationOptions>();

        sMapNotificationIdToOptions.put(PlaybackListenerService.NOTIFICATION_ID,
                new NotificationOptions(
                        PlaybackListenerService.class, NotificationConstants.GROUP_MEDIA_PLAYBACK));
        sMapNotificationIdToOptions.put(PresentationListenerService.NOTIFICATION_ID,
                new NotificationOptions(PresentationListenerService.class,
                        NotificationConstants.GROUP_MEDIA_PRESENTATION));
        sMapNotificationIdToOptions.put(CastListenerService.NOTIFICATION_ID,
                new NotificationOptions(
                        CastListenerService.class, NotificationConstants.GROUP_MEDIA_REMOTE));
    }

    /**
     * Service used to transform intent requests triggered from the notification into
     * {@code MediaNotificationListener} callbacks. We have to create a separate derived class for
     * each type of notification since one class corresponds to one instance of the service only.
     */
    @VisibleForTesting
    abstract static class ListenerService extends Service {
        private int mNotificationId;

        ListenerService(int notificationId) {
            mNotificationId = notificationId;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return null;
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            MediaNotificationController controller = getController();
            if (controller != null) controller.onServiceDestroyed();
            MediaNotificationManager.clear(mNotificationId);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (!processIntent(intent)) {
                // The service has been started with startForegroundService() but the
                // notification hasn't been shown. On O it will lead to the app crash.
                // So show an empty notification before stopping the service.
                MediaNotificationController.finishStartingForegroundService(this,
                        createChromeNotificationBuilder(mNotificationId).buildChromeNotification());
                stopListenerService();
            }
            return START_NOT_STICKY;
        }

        @VisibleForTesting
        void stopListenerService() {
            // Call stopForeground to guarantee Android unset the foreground bit.
            ForegroundServiceUtils.getInstance().stopForeground(
                    this, Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
        }

        @VisibleForTesting
        boolean processIntent(Intent intent) {
            MediaNotificationController controller = getController();
            if (controller == null) return false;

            return controller.processIntent(this, intent);
        }

        @Nullable
        private MediaNotificationController getController() {
            return MediaNotificationManager.getController(mNotificationId);
        }
    }

    /**
     * A {@link ListenerService} for the MediaSession web api.
     * This class is used internally but has to be public to be able to launch the service.
     */
    public static final class PlaybackListenerService extends ListenerService {
        static final int NOTIFICATION_ID = R.id.media_playback_notification;

        public PlaybackListenerService() {
            super(NOTIFICATION_ID);
        }

        @Override
        public void onCreate() {
            super.onCreate();
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            registerReceiver(mAudioBecomingNoisyReceiver, filter);
        }

        @Override
        public void onDestroy() {
            unregisterReceiver(mAudioBecomingNoisyReceiver);
            super.onDestroy();
        }

        private BroadcastReceiver mAudioBecomingNoisyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                    return;
                }

                Intent i = new Intent(getContext(), PlaybackListenerService.class);
                i.setAction(intent.getAction());
                getContext().startService(i);
            }
        };
    }

    /**
     * A {@link ListenerService} for casting.
     * This class is used internally but has to be public to be able to launch the service.
     */
    public static final class PresentationListenerService extends ListenerService {
        static final int NOTIFICATION_ID = R.id.presentation_notification;

        public PresentationListenerService() {
            super(NOTIFICATION_ID);
        }
    }

    /**
     * A {@link ListenerService} for remoting.
     * This class is used internally but has to be public to be able to launch the service.
     */
    public static final class CastListenerService extends ListenerService {
        static final int NOTIFICATION_ID = R.id.remote_notification;

        public CastListenerService() {
            super(NOTIFICATION_ID);
        }
    }

    ChromeMediaNotificationControllerDelegate(int id) {
        mNotificationId = id;
    }

    @Override
    public Intent createServiceIntent() {
        Class<?> serviceClass = sMapNotificationIdToOptions.get(mNotificationId).serviceClass;
        return (serviceClass != null) ? new Intent(getContext(), serviceClass) : null;
    }

    @Override
    public String getNotificationGroupName() {
        String groupName = sMapNotificationIdToOptions.get(mNotificationId).groupName;

        assert groupName != null;
        return groupName;
    }

    @Override
    public ChromeNotificationBuilder createChromeNotificationBuilder() {
        return createChromeNotificationBuilder(mNotificationId);
    }

    @Override
    public void onMediaSessionUpdated(MediaSessionCompat session) {
        try {
            // Tell the MediaRouter about the session, so that Chrome can control the volume
            // on the remote cast device (if any).
            // Pre-MR1 versions of JB do not have the complete MediaRouter APIs,
            // so getting the MediaRouter instance will throw an exception.
            MediaRouter.getInstance(getContext()).setMediaSessionCompat(session);
        } catch (NoSuchMethodError e) {
            // Do nothing. Chrome can't be casting without a MediaRouter, so there is nothing
            // to do here.
        }
    }

    @Override
    public void logNotificationShown(ChromeNotification notification) {
        NotificationUmaTracker.getInstance().onNotificationShown(
                NotificationUmaTracker.SystemNotificationType.MEDIA,
                notification.getNotification());
    }

    private static ChromeNotificationBuilder createChromeNotificationBuilder(int notificationId) {
        NotificationMetadata metadata =
                new NotificationMetadata(NotificationUmaTracker.SystemNotificationType.MEDIA,
                        null /* notificationTag */, notificationId);
        return NotificationBuilderFactory.createChromeNotificationBuilder(true /* preferCompat */,
                ChromeChannelDefinitions.ChannelId.MEDIA_PLAYBACK, null /* remoteAppPackageName*/,
                metadata);
    }

    private static Context getContext() {
        return ContextUtils.getApplicationContext();
    }
}
