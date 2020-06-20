// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.media.ui;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.chromium.base.CollectionUtil;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.chrome.R;
import org.chromium.components.browser_ui.notifications.ChromeNotification;
import org.chromium.components.browser_ui.notifications.ChromeNotificationBuilder;
import org.chromium.components.browser_ui.notifications.ForegroundServiceUtils;
import org.chromium.components.browser_ui.notifications.NotificationManagerProxy;
import org.chromium.components.browser_ui.notifications.NotificationManagerProxyImpl;
import org.chromium.media_session.mojom.MediaSessionAction;
import org.chromium.services.media_session.MediaMetadata;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A class that manages the notification, foreground service, and {@link MediaSessionCompat} for a
 * specific type of media.
 */
public class MediaNotificationController {
    private static final String TAG = "MediaNotification";

    // The maximum number of actions in CompactView media notification.
    private static final int COMPACT_VIEW_ACTIONS_COUNT = 3;

    // The maximum number of actions in BigView media notification.
    private static final int BIG_VIEW_ACTIONS_COUNT = 5;

    // These string values reflect legacy class hierarchy.
    public static final String ACTION_PLAY = "MediaNotificationManager.ListenerService.PLAY";
    public static final String ACTION_PAUSE = "MediaNotificationManager.ListenerService.PAUSE";
    public static final String ACTION_STOP = "MediaNotificationManager.ListenerService.STOP";
    public static final String ACTION_SWIPE = "MediaNotificationManager.ListenerService.SWIPE";
    public static final String ACTION_CANCEL = "MediaNotificationManager.ListenerService.CANCEL";
    public static final String ACTION_PREVIOUS_TRACK =
            "MediaNotificationManager.ListenerService.PREVIOUS_TRACK";
    public static final String ACTION_NEXT_TRACK =
            "MediaNotificationManager.ListenerService.NEXT_TRACK";
    public static final String ACTION_SEEK_FORWARD =
            "MediaNotificationManager.ListenerService.SEEK_FORWARD";
    public static final String ACTION_SEEK_BACKWARD =
            "MediaNotificationmanager.ListenerService.SEEK_BACKWARD";

    // Overrides N detection. The production code will use |null|, which uses the Android version
    // code. Otherwise, |isRunningAtLeastN()| will return whatever value is set.
    @VisibleForTesting
    static Boolean sOverrideIsRunningNForTesting;

    // ListenerService running for the notification. Only non-null when showing.
    @VisibleForTesting
    Service mService;

    @VisibleForTesting
    Delegate mDelegate;

    private SparseArray<MediaButtonInfo> mActionToButtonInfo;

    @VisibleForTesting
    ChromeNotificationBuilder mNotificationBuilder;

    @VisibleForTesting
    Bitmap mDefaultNotificationLargeIcon;

    // |mMediaNotificationInfo| should be not null if and only if the notification is showing.
    @VisibleForTesting
    MediaNotificationInfo mMediaNotificationInfo;

    @VisibleForTesting
    MediaSessionCompat mMediaSession;

    @VisibleForTesting
    Throttler mThrottler;

    @VisibleForTesting
    static class Throttler {
        @VisibleForTesting
        static final int THROTTLE_MILLIS = 500;

        @VisibleForTesting
        MediaNotificationController mController;

        private final Handler mHandler;

        @VisibleForTesting
        Throttler(@NonNull MediaNotificationController manager) {
            mController = manager;
            mHandler = new Handler();
        }

        // When |mTask| is non-null, it will always be queued in mHandler. When |mTask| is non-null,
        // all notification updates will be throttled and their info will be stored as
        // mLastPendingInfo. When |mTask| fires, it will call {@link showNotification()} with
        // the latest queued notification info.
        @VisibleForTesting
        Runnable mTask;

        // The last pending info. If non-null, it will be the latest notification info.
        // Otherwise, the latest notification info will be |mController.mMediaNotificationInfo|.
        @VisibleForTesting
        MediaNotificationInfo mLastPendingInfo;

        /**
         * Queue |mediaNotificationInfo| for update. In unthrottled state (i.e. |mTask| != null),
         * the notification will be updated immediately and enter the throttled state. In
         * unthrottled state, the method will only update the pending notification info, which will
         * be used for updating the notification when |mTask| is fired.
         *
         * @param mediaNotificationInfo The notification info to be queued.
         */
        public void queueNotification(MediaNotificationInfo mediaNotificationInfo) {
            assert mediaNotificationInfo != null;

            MediaNotificationInfo latestMediaNotificationInfo = mLastPendingInfo != null
                    ? mLastPendingInfo
                    : mController.mMediaNotificationInfo;

            if (shouldIgnoreMediaNotificationInfo(
                        latestMediaNotificationInfo, mediaNotificationInfo)) {
                return;
            }

            if (mTask == null) {
                showNotificationImmediately(mediaNotificationInfo);
            } else {
                mLastPendingInfo = mediaNotificationInfo;
            }
        }

        /**
         * Clears the pending notification and enter unthrottled state.
         */
        public void clearPendingNotifications() {
            mHandler.removeCallbacks(mTask);
            mLastPendingInfo = null;
            mTask = null;
        }

        @VisibleForTesting
        void showNotificationImmediately(MediaNotificationInfo mediaNotificationInfo) {
            // If no notification hasn't been updated in the last THROTTLE_MILLIS, update
            // immediately and queue a task for blocking further updates.
            mController.showNotification(mediaNotificationInfo);
            mTask = new Runnable() {
                @Override
                public void run() {
                    if (mLastPendingInfo != null) {
                        // If any notification info is pended during the throttling time window,
                        // update the notification.
                        showNotificationImmediately(mLastPendingInfo);
                        mLastPendingInfo = null;
                    } else {
                        // Otherwise, clear the task so further update is unthrottled.
                        mTask = null;
                    }
                }
            };
            if (!mHandler.postDelayed(mTask, THROTTLE_MILLIS)) {
                Log.w(TAG, "Failed to post the throttler task.");
                mTask = null;
            }
        }
    }

    private final MediaSessionCompat
            .Callback mMediaSessionCallback = new MediaSessionCompat.Callback() {
        @Override
        public void onPlay() {
            MediaNotificationController.this.onPlay(
                    MediaNotificationListener.ACTION_SOURCE_MEDIA_SESSION);
        }

        @Override
        public void onPause() {
            MediaNotificationController.this.onPause(
                    MediaNotificationListener.ACTION_SOURCE_MEDIA_SESSION);
        }

        @Override
        public void onSkipToPrevious() {
            MediaNotificationController.this.onMediaSessionAction(
                    MediaSessionAction.PREVIOUS_TRACK);
        }

        @Override
        public void onSkipToNext() {
            MediaNotificationController.this.onMediaSessionAction(MediaSessionAction.NEXT_TRACK);
        }

        @Override
        public void onFastForward() {
            MediaNotificationController.this.onMediaSessionAction(MediaSessionAction.SEEK_FORWARD);
        }

        @Override
        public void onRewind() {
            MediaNotificationController.this.onMediaSessionAction(MediaSessionAction.SEEK_BACKWARD);
        }

        @Override
        public void onSeekTo(long pos) {
            MediaNotificationController.this.onMediaSessionSeekTo(pos);
        }
    };

    // On O, if startForegroundService() was called, the app MUST call startForeground on the
    // created service no matter what or it will crash. Show the minimal notification. The caller is
    // responsible for hiding it afterwards.
    public static void finishStartingForegroundService(Service s, ChromeNotification notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        ForegroundServiceUtils.getInstance().startForeground(s, notification.getMetadata().id,
                notification.getNotification(), 0 /* foregroundServiceType */);
    }

    private PendingIntent createPendingIntent(String action) {
        Intent intent = mDelegate.createServiceIntent().setAction(action);
        return PendingIntent.getService(getContext(), 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
    }

    private static boolean isRunningAtLeastN() {
        return (sOverrideIsRunningNForTesting != null)
                ? sOverrideIsRunningNForTesting
                : Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    /**
     * The class containing all the information for adding a button in the notification for an
     * action.
     */
    private static final class MediaButtonInfo {
        /** The resource ID of this media button icon. */
        public int iconResId;

        /** The resource ID of this media button description. */
        public int descriptionResId;

        /** The intent string to be fired when this media button is clicked. */
        public String intentString;

        public MediaButtonInfo(int buttonResId, int descriptionResId, String intentString) {
            this.iconResId = buttonResId;
            this.descriptionResId = descriptionResId;
            this.intentString = intentString;
        }
    }

    /** An interface for separating embedder-specific logic. */
    interface Delegate {
        /** Returns an intent that will start a Service which listens to notification actions. */
        Intent createServiceIntent();

        /** Returns the notification group name used to prevent automatic grouping. */
        String getNotificationGroupName();

        /** Returns a builder suitable as a starting point for creating the notification. */
        ChromeNotificationBuilder createChromeNotificationBuilder();

        /** Called when the Android MediaSession has been updated. */
        void onMediaSessionUpdated(MediaSessionCompat session);

        /** Called when a notification has been shown and should be logged in UMA. */
        void logNotificationShown(ChromeNotification notification);
    }

    @VisibleForTesting
    MediaNotificationController(Delegate delegate) {
        mDelegate = delegate;

        mActionToButtonInfo = new SparseArray<>();

        mActionToButtonInfo.put(MediaSessionAction.PLAY,
                new MediaButtonInfo(R.drawable.ic_play_arrow_white_36dp,
                        R.string.accessibility_play, ACTION_PLAY));
        mActionToButtonInfo.put(MediaSessionAction.PAUSE,
                new MediaButtonInfo(R.drawable.ic_pause_white_36dp, R.string.accessibility_pause,
                        ACTION_PAUSE));
        mActionToButtonInfo.put(MediaSessionAction.STOP,
                new MediaButtonInfo(
                        R.drawable.ic_stop_white_36dp, R.string.accessibility_stop, ACTION_STOP));
        mActionToButtonInfo.put(MediaSessionAction.PREVIOUS_TRACK,
                new MediaButtonInfo(R.drawable.ic_skip_previous_white_36dp,
                        R.string.accessibility_previous_track, ACTION_PREVIOUS_TRACK));
        mActionToButtonInfo.put(MediaSessionAction.NEXT_TRACK,
                new MediaButtonInfo(R.drawable.ic_skip_next_white_36dp,
                        R.string.accessibility_next_track, ACTION_NEXT_TRACK));
        mActionToButtonInfo.put(MediaSessionAction.SEEK_FORWARD,
                new MediaButtonInfo(R.drawable.ic_fast_forward_white_36dp,
                        R.string.accessibility_seek_forward, ACTION_SEEK_FORWARD));
        mActionToButtonInfo.put(MediaSessionAction.SEEK_BACKWARD,
                new MediaButtonInfo(R.drawable.ic_fast_rewind_white_36dp,
                        R.string.accessibility_seek_backward, ACTION_SEEK_BACKWARD));

        mThrottler = new Throttler(this);
    }

    /**
     * Registers the started {@link Service} with the manager and creates the notification.
     *
     * @param service the service that was started
     */
    @VisibleForTesting
    void onServiceStarted(Service service) {
        if (mService == service) return;

        mService = service;
        updateNotification(true /*serviceStarting*/, true /*shouldLogNotification*/);
    }

    /** Handles the service destruction. */
    @VisibleForTesting
    void onServiceDestroyed() {
        mService = null;
    }

    public boolean processIntent(Service service, Intent intent) {
        if (intent == null || mMediaNotificationInfo == null) return false;

        if (intent.getAction() == null) {
            // The intent comes from  {@link AppHooks#startForegroundService}.
            onServiceStarted(service);
        } else {
            // The intent comes from the notification. In this case, {@link onServiceStarted()}
            // does need to be called.
            processAction(intent.getAction());
        }
        return true;
    }

    public void processAction(String action) {
        if (ACTION_STOP.equals(action) || ACTION_SWIPE.equals(action)
                || ACTION_CANCEL.equals(action)) {
            onStop(MediaNotificationListener.ACTION_SOURCE_MEDIA_NOTIFICATION);
            stopListenerService();
        } else if (ACTION_PLAY.equals(action)) {
            onPlay(MediaNotificationListener.ACTION_SOURCE_MEDIA_NOTIFICATION);
        } else if (ACTION_PAUSE.equals(action)) {
            onPause(MediaNotificationListener.ACTION_SOURCE_MEDIA_NOTIFICATION);
        } else if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(action)) {
            onPause(MediaNotificationListener.ACTION_SOURCE_HEADSET_UNPLUG);
        } else if (ACTION_PREVIOUS_TRACK.equals(action)) {
            onMediaSessionAction(MediaSessionAction.PREVIOUS_TRACK);
        } else if (ACTION_NEXT_TRACK.equals(action)) {
            onMediaSessionAction(MediaSessionAction.NEXT_TRACK);
        } else if (ACTION_SEEK_FORWARD.equals(action)) {
            onMediaSessionAction(MediaSessionAction.SEEK_FORWARD);
        } else if (ACTION_SEEK_BACKWARD.equals(action)) {
            onMediaSessionAction(MediaSessionAction.SEEK_BACKWARD);
        }
    }

    @VisibleForTesting
    void onPlay(int actionSource) {
        // MediaSessionCompat calls this sometimes when `mMediaNotificationInfo`
        // is no longer available. It's unclear if it is a Support Library issue
        // or something that isn't properly cleaned up but given that the
        // crashes are rare and the fix is simple, null check was enough.
        if (mMediaNotificationInfo == null || !mMediaNotificationInfo.isPaused) return;
        mMediaNotificationInfo.listener.onPlay(actionSource);
    }

    @VisibleForTesting
    void onPause(int actionSource) {
        // MediaSessionCompat calls this sometimes when `mMediaNotificationInfo`
        // is no longer available. It's unclear if it is a Support Library issue
        // or something that isn't properly cleaned up but given that the
        // crashes are rare and the fix is simple, null check was enough.
        if (mMediaNotificationInfo == null || mMediaNotificationInfo.isPaused) return;
        mMediaNotificationInfo.listener.onPause(actionSource);
    }

    @VisibleForTesting
    void onStop(int actionSource) {
        // MediaSessionCompat calls this sometimes when `mMediaNotificationInfo`
        // is no longer available. It's unclear if it is a Support Library issue
        // or something that isn't properly cleaned up but given that the
        // crashes are rare and the fix is simple, null check was enough.
        if (mMediaNotificationInfo == null) return;
        mMediaNotificationInfo.listener.onStop(actionSource);
    }

    @VisibleForTesting
    void onMediaSessionAction(int action) {
        // MediaSessionCompat calls this sometimes when `mMediaNotificationInfo`
        // is no longer available. It's unclear if it is a Support Library issue
        // or something that isn't properly cleaned up but given that the
        // crashes are rare and the fix is simple, null check was enough.
        if (mMediaNotificationInfo == null) return;
        mMediaNotificationInfo.listener.onMediaSessionAction(action);
    }

    @VisibleForTesting
    void onMediaSessionSeekTo(long pos) {
        // MediaSessionCompat calls this sometimes when `mMediaNotificationInfo`
        // is no longer available. It's unclear if it is a Support Library issue
        // or something that isn't properly cleaned up but given that the
        // crashes are rare and the fix is simple, null check was enough.
        if (mMediaNotificationInfo == null) return;
        mMediaNotificationInfo.listener.onMediaSessionSeekTo(pos);
    }

    @VisibleForTesting
    void showNotification(MediaNotificationInfo mediaNotificationInfo) {
        if (shouldIgnoreMediaNotificationInfo(mMediaNotificationInfo, mediaNotificationInfo)) {
            return;
        }

        mMediaNotificationInfo = mediaNotificationInfo;

        // If there's no pending service start request, don't try to start service. If there is a
        // pending service start request but the service haven't started yet, only update the
        // |mMediaNotificationInfo|. The service will update the notification later once it's
        // started.
        if (mService == null && mediaNotificationInfo.isPaused) return;

        if (mService == null) {
            updateMediaSession();
            updateNotificationBuilder();
            ForegroundServiceUtils.getInstance().startForegroundService(
                    mDelegate.createServiceIntent());
        } else {
            updateNotification(false, false);
        }
    }

    private static boolean shouldIgnoreMediaNotificationInfo(
            MediaNotificationInfo oldInfo, MediaNotificationInfo newInfo) {
        // If we don't have actions then we shouldn't display the notification.
        if (newInfo.mediaSessionActions.isEmpty()) return true;

        return newInfo.equals(oldInfo)
                || ((newInfo.isPaused && oldInfo != null && newInfo.tabId != oldInfo.tabId));
    }

    @VisibleForTesting
    void clearNotification() {
        mThrottler.clearPendingNotifications();
        if (mMediaNotificationInfo == null) return;

        NotificationManagerCompat.from(getContext()).cancel(mMediaNotificationInfo.id);

        if (mMediaSession != null) {
            mMediaSession.setCallback(null);
            mMediaSession.setActive(false);
            mMediaSession.release();
            mMediaSession = null;
        }
        stopListenerService();
        mMediaNotificationInfo = null;
        mNotificationBuilder = null;
    }

    public void queueNotification(MediaNotificationInfo mediaNotificationInfo) {
        mThrottler.queueNotification(mediaNotificationInfo);
    }

    public void hideNotification(int tabId) {
        if (mMediaNotificationInfo == null || tabId != mMediaNotificationInfo.tabId) return;
        clearNotification();
    }

    @VisibleForTesting
    void stopListenerService() {
        if (mService == null) return;

        ForegroundServiceUtils.getInstance().stopForeground(
                mService, Service.STOP_FOREGROUND_REMOVE);
        mService.stopSelf();
    }

    @NonNull
    @VisibleForTesting
    MediaMetadataCompat createMetadata() {
        // Can't return null as {@link MediaSessionCompat#setMetadata()} will crash in some versions
        // of the Android compat library.
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();
        if (mMediaNotificationInfo.isPrivate) return metadataBuilder.build();

        metadataBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_TITLE, mMediaNotificationInfo.metadata.getTitle());
        metadataBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_ARTIST, mMediaNotificationInfo.origin);

        if (!TextUtils.isEmpty(mMediaNotificationInfo.metadata.getArtist())) {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                    mMediaNotificationInfo.metadata.getArtist());
        }
        if (!TextUtils.isEmpty(mMediaNotificationInfo.metadata.getAlbum())) {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM,
                    mMediaNotificationInfo.metadata.getAlbum());
        }
        if (mMediaNotificationInfo.mediaSessionImage != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                    mMediaNotificationInfo.mediaSessionImage);
        }
        if (mMediaNotificationInfo.mediaPosition != null) {
            metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION,
                    mMediaNotificationInfo.mediaPosition.getDuration());
        }

        return metadataBuilder.build();
    }

    @VisibleForTesting
    void updateNotification(boolean serviceStarting, boolean shouldLogNotification) {
        if (mService == null) return;

        if (mMediaNotificationInfo == null) {
            if (serviceStarting) {
                finishStartingForegroundService(mService,
                        mDelegate.createChromeNotificationBuilder().buildChromeNotification());
                ForegroundServiceUtils.getInstance().stopForeground(
                        mService, Service.STOP_FOREGROUND_REMOVE);
            }
            return;
        }
        updateMediaSession();
        updateNotificationBuilder();

        ChromeNotification notification = mNotificationBuilder.buildChromeNotification();

        // On O, finish starting the foreground service nevertheless, or Android will
        // crash Chrome.
        boolean foregroundedService = false;
        if (serviceStarting) {
            finishStartingForegroundService(mService, notification);
            foregroundedService = true;
        }

        // We keep the service as a foreground service while the media is playing. When it is not,
        // the service isn't stopped but is no longer in foreground, thus at a lower priority.
        // While the service is in foreground, the associated notification can't be swipped away.
        // Moving it back to background allows the user to remove the notification.
        if (mMediaNotificationInfo.supportsSwipeAway() && mMediaNotificationInfo.isPaused) {
            ForegroundServiceUtils.getInstance().stopForeground(
                    mService, Service.STOP_FOREGROUND_DETACH);
            NotificationManagerProxy manager = new NotificationManagerProxyImpl(getContext());
            manager.notify(notification);
        } else if (!foregroundedService) {
            ForegroundServiceUtils.getInstance().startForeground(mService,
                    mMediaNotificationInfo.id, notification.getNotification(),
                    0 /*foregroundServiceType*/);
        }
        if (shouldLogNotification) {
            mDelegate.logNotificationShown(notification);
        }
    }

    @VisibleForTesting
    void updateNotificationBuilder() {
        assert (mMediaNotificationInfo != null);

        mNotificationBuilder = mDelegate.createChromeNotificationBuilder();
        setMediaStyleLayoutForNotificationBuilder(mNotificationBuilder);

        // TODO(zqzhang): It's weird that setShowWhen() doesn't work on K. Calling setWhen() to
        // force removing the time.
        mNotificationBuilder.setShowWhen(false).setWhen(0);
        mNotificationBuilder.setSmallIcon(mMediaNotificationInfo.notificationSmallIcon);
        mNotificationBuilder.setAutoCancel(false);
        mNotificationBuilder.setLocalOnly(true);
        mNotificationBuilder.setGroup(mDelegate.getNotificationGroupName());
        mNotificationBuilder.setGroupSummary(true);

        if (mMediaNotificationInfo.supportsSwipeAway()) {
            mNotificationBuilder.setOngoing(!mMediaNotificationInfo.isPaused);
            mNotificationBuilder.setDeleteIntent(createPendingIntent(ACTION_SWIPE));
        }

        // The intent will currently only be null when using a custom tab.
        // TODO(avayvod) work out what we should do in this case. See https://crbug.com/585395.
        if (mMediaNotificationInfo.contentIntent != null) {
            mNotificationBuilder.setContentIntent(PendingIntent.getActivity(getContext(),
                    mMediaNotificationInfo.tabId, mMediaNotificationInfo.contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT));
            // Set FLAG_UPDATE_CURRENT so that the intent extras is updated, otherwise the
            // intent extras will stay the same for the same tab.
        }

        mNotificationBuilder.setVisibility(mMediaNotificationInfo.isPrivate
                        ? NotificationCompat.VISIBILITY_PRIVATE
                        : NotificationCompat.VISIBILITY_PUBLIC);
    }

    @VisibleForTesting
    void updateMediaSession() {
        if (!mMediaNotificationInfo.supportsPlayPause()) return;

        if (mMediaSession == null) mMediaSession = createMediaSession();

        activateAndroidMediaSession(mMediaNotificationInfo.tabId);

        mDelegate.onMediaSessionUpdated(mMediaSession);

        mMediaSession.setMetadata(createMetadata());

        mMediaSession.setPlaybackState(createPlaybackState());
    }

    @VisibleForTesting
    PlaybackStateCompat createPlaybackState() {
        PlaybackStateCompat.Builder playbackStateBuilder =
                new PlaybackStateCompat.Builder().setActions(computeMediaSessionActions());

        int state = mMediaNotificationInfo.isPaused ? PlaybackStateCompat.STATE_PAUSED
                                                    : PlaybackStateCompat.STATE_PLAYING;

        if (mMediaNotificationInfo.mediaPosition != null) {
            playbackStateBuilder.setState(state, mMediaNotificationInfo.mediaPosition.getPosition(),
                    mMediaNotificationInfo.mediaPosition.getPlaybackRate(),
                    mMediaNotificationInfo.mediaPosition.getLastUpdatedTime());
        } else {
            playbackStateBuilder.setState(
                    state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        }

        return playbackStateBuilder.build();
    }

    private long computeMediaSessionActions() {
        assert mMediaNotificationInfo != null;

        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE;
        if (mMediaNotificationInfo.mediaSessionActions.contains(
                    MediaSessionAction.PREVIOUS_TRACK)) {
            actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        }
        if (mMediaNotificationInfo.mediaSessionActions.contains(MediaSessionAction.NEXT_TRACK)) {
            actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        }
        if (mMediaNotificationInfo.mediaSessionActions.contains(MediaSessionAction.SEEK_FORWARD)) {
            actions |= PlaybackStateCompat.ACTION_FAST_FORWARD;
        }
        if (mMediaNotificationInfo.mediaSessionActions.contains(MediaSessionAction.SEEK_BACKWARD)) {
            actions |= PlaybackStateCompat.ACTION_REWIND;
        }
        if (mMediaNotificationInfo.mediaSessionActions.contains(MediaSessionAction.SEEK_TO)) {
            actions |= PlaybackStateCompat.ACTION_SEEK_TO;
        }
        return actions;
    }

    private MediaSessionCompat createMediaSession() {
        Context context = getContext();
        MediaSessionCompat mediaSession =
                new MediaSessionCompat(context, context.getString(R.string.app_name));
        mediaSession.setCallback(mMediaSessionCallback);
        mediaSession.setActive(true);
        return mediaSession;
    }

    public void activateAndroidMediaSession(int tabId) {
        if (mMediaNotificationInfo == null) return;
        if (mMediaNotificationInfo.tabId != tabId) return;
        if (!mMediaNotificationInfo.supportsPlayPause() || mMediaNotificationInfo.isPaused) return;
        if (mMediaSession == null) return;
        mMediaSession.setActive(true);
    }

    private void setMediaStyleLayoutForNotificationBuilder(ChromeNotificationBuilder builder) {
        setMediaStyleNotificationText(builder);
        if (!mMediaNotificationInfo.supportsPlayPause()) {
            // Non-playback (Cast) notification will not use MediaStyle, so not
            // setting the large icon is fine.
            builder.setLargeIcon(null);
            // Notifications in incognito shouldn't show an icon to avoid leaking information.
        } else if (mMediaNotificationInfo.notificationLargeIcon != null
                && !mMediaNotificationInfo.isPrivate) {
            builder.setLargeIcon(mMediaNotificationInfo.notificationLargeIcon);
        } else if (!isRunningAtLeastN()) {
            if (mDefaultNotificationLargeIcon == null
                    && mMediaNotificationInfo.defaultNotificationLargeIcon != 0) {
                mDefaultNotificationLargeIcon =
                        MediaNotificationImageUtils.downscaleIconToIdealSize(
                                BitmapFactory.decodeResource(getContext().getResources(),
                                        mMediaNotificationInfo.defaultNotificationLargeIcon));
            }
            builder.setLargeIcon(mDefaultNotificationLargeIcon);
        }

        addNotificationButtons(builder);
    }

    private void addNotificationButtons(ChromeNotificationBuilder builder) {
        Set<Integer> actions = new HashSet<>();

        // TODO(zqzhang): handle other actions when play/pause is not supported? See
        // https://crbug.com/667500
        if (mMediaNotificationInfo.supportsPlayPause()) {
            actions.addAll(mMediaNotificationInfo.mediaSessionActions);
            if (mMediaNotificationInfo.isPaused) {
                actions.remove(MediaSessionAction.PAUSE);
                actions.add(MediaSessionAction.PLAY);
            } else {
                actions.remove(MediaSessionAction.PLAY);
                actions.add(MediaSessionAction.PAUSE);
            }
        }

        if (mMediaNotificationInfo.supportsStop()) {
            actions.add(MediaSessionAction.STOP);
        } else {
            actions.remove(MediaSessionAction.STOP);
        }

        List<Integer> bigViewActions = computeBigViewActions(actions);

        for (int action : bigViewActions) {
            MediaButtonInfo buttonInfo = mActionToButtonInfo.get(action);
            builder.addAction(buttonInfo.iconResId,
                    getContext().getResources().getString(buttonInfo.descriptionResId),
                    createPendingIntent(buttonInfo.intentString));
        }

        // Only apply MediaStyle when NotificationInfo supports play/pause.
        if (mMediaNotificationInfo.supportsPlayPause()) {
            builder.setMediaStyle(mMediaSession, computeCompactViewActionIndices(bigViewActions),
                    createPendingIntent(ACTION_CANCEL), true);
        }
    }

    private void setMediaStyleNotificationText(ChromeNotificationBuilder builder) {
        if (mMediaNotificationInfo.isPrivate) {
            // Notifications in incognito shouldn't show what is playing to avoid leaking
            // information.
            if (isRunningAtLeastN()) {
                builder.setContentTitle(getContext().getResources().getString(
                        R.string.media_notification_incognito));
                builder.setSubText(
                        getContext().getResources().getString(R.string.notification_incognito_tab));
            } else {
                // App name is automatically added to the title from Android N,
                // but needs to be added explicitly for prior versions.
                builder.setContentTitle(getContext().getString(R.string.app_name))
                        .setContentText(getContext().getResources().getString(
                                R.string.media_notification_incognito));
            }
            return;
        }

        builder.setContentTitle(mMediaNotificationInfo.metadata.getTitle());
        String artistAndAlbumText = getArtistAndAlbumText(mMediaNotificationInfo.metadata);
        if (isRunningAtLeastN() || !artistAndAlbumText.isEmpty()) {
            builder.setContentText(artistAndAlbumText);
            builder.setSubText(mMediaNotificationInfo.origin);
        } else {
            // Leaving ContentText empty looks bad, so move origin up to the ContentText.
            builder.setContentText(mMediaNotificationInfo.origin);
        }
    }

    private static String getArtistAndAlbumText(MediaMetadata metadata) {
        String artist = (metadata.getArtist() == null) ? "" : metadata.getArtist();
        String album = (metadata.getAlbum() == null) ? "" : metadata.getAlbum();
        if (artist.isEmpty() || album.isEmpty()) {
            return artist + album;
        }
        return artist + " - " + album;
    }

    /**
     * Compute the actions to be shown in BigView media notification.
     *
     * The method assumes STOP cannot coexist with switch track actions and seeking actions. It also
     * assumes PLAY and PAUSE cannot coexist.
     */
    private static List<Integer> computeBigViewActions(Set<Integer> actions) {
        // STOP cannot coexist with switch track actions and seeking actions.
        assert !actions.contains(MediaSessionAction.STOP)
                || !(actions.contains(MediaSessionAction.PREVIOUS_TRACK)
                        && actions.contains(MediaSessionAction.NEXT_TRACK)
                        && actions.contains(MediaSessionAction.SEEK_BACKWARD)
                        && actions.contains(MediaSessionAction.SEEK_FORWARD));
        // PLAY and PAUSE cannot coexist.
        assert !actions.contains(MediaSessionAction.PLAY)
                || !actions.contains(MediaSessionAction.PAUSE);

        int[] actionByOrder = {
                MediaSessionAction.PREVIOUS_TRACK,
                MediaSessionAction.SEEK_BACKWARD,
                MediaSessionAction.PLAY,
                MediaSessionAction.PAUSE,
                MediaSessionAction.SEEK_FORWARD,
                MediaSessionAction.NEXT_TRACK,
                MediaSessionAction.STOP,
        };

        // Sort the actions based on the expected ordering in the UI.
        List<Integer> sortedActions = new ArrayList<>();
        for (int action : actionByOrder) {
            if (actions.contains(action)) sortedActions.add(action);
        }

        // There can't be move actions than BIG_VIEW_ACTIONS_COUNT. We do this check after we have
        // sorted the actions since there may be more actions that we do not support.
        assert sortedActions.size() <= BIG_VIEW_ACTIONS_COUNT;

        return sortedActions;
    }

    /**
     * Compute the actions to be shown in CompactView media notification.
     *
     * The method assumes STOP cannot coexist with switch track actions and seeking actions. It also
     * assumes PLAY and PAUSE cannot coexist.
     *
     * Actions in pairs are preferred if there are more actions than |COMPACT_VIEW_ACTIONS_COUNT|.
     */
    @VisibleForTesting
    static int[] computeCompactViewActionIndices(List<Integer> actions) {
        // STOP cannot coexist with switch track actions and seeking actions.
        assert !actions.contains(MediaSessionAction.STOP)
                || !(actions.contains(MediaSessionAction.PREVIOUS_TRACK)
                        && actions.contains(MediaSessionAction.NEXT_TRACK)
                        && actions.contains(MediaSessionAction.SEEK_BACKWARD)
                        && actions.contains(MediaSessionAction.SEEK_FORWARD));
        // PLAY and PAUSE cannot coexist.
        assert !actions.contains(MediaSessionAction.PLAY)
                || !actions.contains(MediaSessionAction.PAUSE);

        if (actions.size() <= COMPACT_VIEW_ACTIONS_COUNT) {
            // If the number of actions is less than |COMPACT_VIEW_ACTIONS_COUNT|, just return an
            // array of 0, 1, ..., |actions.size()|-1.
            int[] actionsArray = new int[actions.size()];
            for (int i = 0; i < actions.size(); ++i) actionsArray[i] = i;
            return actionsArray;
        }

        if (actions.contains(MediaSessionAction.STOP)) {
            List<Integer> compactActions = new ArrayList<>();
            if (actions.contains(MediaSessionAction.PLAY)) {
                compactActions.add(actions.indexOf(MediaSessionAction.PLAY));
            }
            compactActions.add(actions.indexOf(MediaSessionAction.STOP));
            return CollectionUtil.integerListToIntArray(compactActions);
        }

        int[] actionsArray = new int[COMPACT_VIEW_ACTIONS_COUNT];
        if (actions.contains(MediaSessionAction.PREVIOUS_TRACK)
                && actions.contains(MediaSessionAction.NEXT_TRACK)) {
            actionsArray[0] = actions.indexOf(MediaSessionAction.PREVIOUS_TRACK);
            if (actions.contains(MediaSessionAction.PLAY)) {
                actionsArray[1] = actions.indexOf(MediaSessionAction.PLAY);
            } else {
                actionsArray[1] = actions.indexOf(MediaSessionAction.PAUSE);
            }
            actionsArray[2] = actions.indexOf(MediaSessionAction.NEXT_TRACK);
            return actionsArray;
        }

        assert actions.contains(MediaSessionAction.SEEK_BACKWARD)
                && actions.contains(MediaSessionAction.SEEK_FORWARD);
        actionsArray[0] = actions.indexOf(MediaSessionAction.SEEK_BACKWARD);
        if (actions.contains(MediaSessionAction.PLAY)) {
            actionsArray[1] = actions.indexOf(MediaSessionAction.PLAY);
        } else {
            actionsArray[1] = actions.indexOf(MediaSessionAction.PAUSE);
        }
        actionsArray[2] = actions.indexOf(MediaSessionAction.SEEK_FORWARD);

        return actionsArray;
    }

    private static Context getContext() {
        return ContextUtils.getApplicationContext();
    }
}
