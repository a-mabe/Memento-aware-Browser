// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.notifications;

import android.app.PendingIntent;
import android.content.Context;

import org.chromium.components.browser_ui.notifications.ChromeNotificationBuilder;
import org.chromium.components.browser_ui.notifications.NotificationMetadata;
import org.chromium.components.browser_ui.notifications.PendingIntentProvider;
import org.chromium.components.browser_ui.notifications.channels.ChannelsInitializer;

/**
 * Extends the base NotificationCompatBuilder to add UMA by way of
 * {@link NotificationIntentInterceptor}.
 */
public class NotificationCompatBuilder
        extends org.chromium.components.browser_ui.notifications.NotificationCompatBuilder {
    NotificationCompatBuilder(Context context, String channelId,
            ChannelsInitializer channelsInitializer, NotificationMetadata metadata) {
        super(context, channelId, channelsInitializer, metadata);
        if (metadata != null) {
            getBuilder().setDeleteIntent(
                    NotificationIntentInterceptor.getDefaultDeletePendingIntent(metadata));
        }
    }

    @Override
    public ChromeNotificationBuilder setContentIntent(PendingIntentProvider contentIntent) {
        PendingIntent pendingIntent = NotificationIntentInterceptor.createInterceptPendingIntent(
                NotificationIntentInterceptor.IntentType.CONTENT_INTENT, 0 /* intentId */,
                getMetadata(), contentIntent);
        return setContentIntent(pendingIntent);
    }

    @Override
    public ChromeNotificationBuilder addAction(int icon, CharSequence title,
            PendingIntentProvider pendingIntentProvider,
            @NotificationUmaTracker.ActionType int actionType) {
        PendingIntent pendingIntent = NotificationIntentInterceptor.createInterceptPendingIntent(
                NotificationIntentInterceptor.IntentType.ACTION_INTENT, actionType, getMetadata(),
                pendingIntentProvider);
        return addAction(icon, title, pendingIntent);
    }

    @Override
    public ChromeNotificationBuilder setDeleteIntent(PendingIntentProvider intent) {
        return setDeleteIntent(NotificationIntentInterceptor.createInterceptPendingIntent(
                NotificationIntentInterceptor.IntentType.DELETE_INTENT, 0 /* intentId */,
                getMetadata(), intent));
    }
}
