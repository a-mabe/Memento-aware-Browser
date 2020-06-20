// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.installedapp;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.chromium.base.Callback;
import org.chromium.base.ContextUtils;
import org.chromium.base.Log;
import org.chromium.base.annotations.NativeMethods;
import org.chromium.base.task.PostTask;
import org.chromium.base.task.TaskTraits;
import org.chromium.chrome.browser.instantapps.InstantAppsHandler;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.content_public.browser.UiThreadTaskTraits;
import org.chromium.installedapp.mojom.InstalledAppProvider;
import org.chromium.installedapp.mojom.RelatedApplication;
import org.chromium.mojo.system.MojoException;
import org.chromium.url.GURL;
import org.chromium.url.mojom.Url;
import org.chromium.webapk.lib.client.WebApkValidator;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Android implementation of the InstalledAppProvider service defined in
 * installed_app_provider.mojom
 */
public class InstalledAppProviderImpl implements InstalledAppProvider {
    @VisibleForTesting
    public static final String ASSET_STATEMENTS_KEY = "asset_statements";
    private static final String ASSET_STATEMENT_FIELD_TARGET = "target";
    private static final String ASSET_STATEMENT_FIELD_NAMESPACE = "namespace";
    private static final String ASSET_STATEMENT_FIELD_SITE = "site";
    @VisibleForTesting
    public static final String ASSET_STATEMENT_NAMESPACE_WEB = "web";
    @VisibleForTesting
    public static final String RELATED_APP_PLATFORM_ANDROID = "play";
    @VisibleForTesting
    public static final String RELATED_APP_PLATFORM_WEBAPP = "webapp";
    @VisibleForTesting
    public static final String INSTANT_APP_ID_STRING = "instantapp";
    @VisibleForTesting
    public static final String INSTANT_APP_HOLDBACK_ID_STRING = "instantapp:holdback";

    // The maximum number of related apps declared in the Web Manifest taken into account when
    // determining whether the related app is installed and mutually related.
    @VisibleForTesting
    static final int MAX_ALLOWED_RELATED_APPS = 3;

    private static final String TAG = "InstalledAppProvider";

    private final FrameUrlDelegate mFrameUrlDelegate;
    private final PackageManagerDelegate mPackageManagerDelegate;
    private final InstantAppsHandler mInstantAppsHandler;

    /**
     * Small interface for dynamically getting the URL of the current frame.
     *
     * Abstract to allow for testing.
     */
    public static interface FrameUrlDelegate {
        /**
         * Gets the URL of the current frame. Can return null (if the frame has disappeared).
         */
        public GURL getUrl();

        /**
         * Checks if we're in incognito. If the frame has disappeared this returns true.
         */
        public boolean isIncognito();
    }

    public InstalledAppProviderImpl(FrameUrlDelegate frameUrlDelegate,
            PackageManagerDelegate packageManagerDelegate, InstantAppsHandler instantAppsHandler) {
        assert instantAppsHandler != null;
        mFrameUrlDelegate = frameUrlDelegate;
        mPackageManagerDelegate = packageManagerDelegate;
        mInstantAppsHandler = instantAppsHandler;
    }

    @Override
    public void close() {}

    @Override
    public void onConnectionError(MojoException e) {}

    /** Utility class for waiting for all the installation/verification results. */
    @UiThread
    private static class ResultHolder {
        private int mNumTasks;
        Callback mCallback;
        private ArrayList<RelatedApplication> mInstalledApps;
        private int mDelayMs;

        /**
         * @param numTasks How many results to wait for.
         * @param callback Gets called with the results once all the tasks are complete.
         */
        public ResultHolder(
                int numTasks, Callback<Pair<ArrayList<RelatedApplication>, Integer>> callback) {
            mNumTasks = numTasks;
            mCallback = callback;
            mInstalledApps =
                    new ArrayList<RelatedApplication>(Collections.nCopies(mNumTasks, null));
            mDelayMs = 0;
            if (mNumTasks == 0) {
                mCallback.onResult(Pair.create(mInstalledApps, mDelayMs));
            }
        }

        public void onResult(@Nullable RelatedApplication app, int taskIdx, int delayMs) {
            assert mNumTasks > 0;

            if (app != null) mInstalledApps.add(taskIdx, app);
            mDelayMs += delayMs;

            if (--mNumTasks == 0) {
                mInstalledApps.removeAll(Collections.singleton(null));
                mCallback.onResult(Pair.create(mInstalledApps, mDelayMs));
            }
        }
    }

    @Override
    @UiThread
    public void filterInstalledApps(final RelatedApplication[] relatedApps, final Url manifestUrl,
            final FilterInstalledAppsResponse callback) {
        final GURL frameUrl = mFrameUrlDelegate.getUrl();
        int delayMillis = 0;
        int numTasks = Math.min(relatedApps.length, MAX_ALLOWED_RELATED_APPS);
        ResultHolder resultHolder = new ResultHolder(numTasks, (resultPair) -> {
            onFilteredInstalledApps(resultPair.first, resultPair.second, callback);
        });

        // NOTE: For security, it must not be possible to distinguish (from the time taken to
        // respond) between the app not being installed and the origin not being associated with the
        // app (otherwise, arbitrary websites would be able to test whether un-associated apps are
        // installed on the user's device).
        // NOTE: A manual loop is used to take MAX_ALLOWED_RELATED_APPS into account.
        for (int i = 0; i < numTasks; i++) {
            RelatedApplication app = relatedApps[i];
            int taskIdx = i;

            if (isInstantNativeApp(app)) {
                PostTask.postTask(TaskTraits.BEST_EFFORT_MAY_BLOCK,
                        () -> checkInstantApp(resultHolder, taskIdx, app, frameUrl));
            } else if (isRegularNativeApp(app)) {
                PostTask.postTask(TaskTraits.BEST_EFFORT_MAY_BLOCK,
                        () -> checkPlayApp(resultHolder, taskIdx, app, frameUrl));
            } else if (isWebApk(app) && app.url.equals(manifestUrl.url)) {
                // The website wants to check whether its own WebAPK is installed.
                PostTask.postTask(TaskTraits.BEST_EFFORT_MAY_BLOCK,
                        () -> checkWebApkInstalled(resultHolder, taskIdx, app));
            } else if (isWebApk(app)) {
                // The website wants to check whether another WebAPK is installed.
                checkWebApk(resultHolder, taskIdx, app, manifestUrl);
            } else {
                // The app did not match any category.
                resultHolder.onResult(null, taskIdx, 0);
            }
        }
    }

    /**
     * The callback called with the verified installed apps.
     * @param installedApps The list of apps from the provided related apps that have been verified
     *         and are installed.
     * @param delayMs The artificial delay to apply before returning the results.
     * @param callback The mojo callback for sending the installed apps.
     */
    @UiThread
    private void onFilteredInstalledApps(ArrayList<RelatedApplication> installedApps,
            Integer delayMs, FilterInstalledAppsResponse callback) {
        RelatedApplication[] installedAppsArray;

        if (mFrameUrlDelegate.isIncognito()) {
            // Don't expose the related apps if in incognito mode. This is done at
            // the last stage to prevent using this API as an incognito detector by
            // timing how long it takes the Promise to resolve.
            installedAppsArray = new RelatedApplication[0];
        } else {
            installedAppsArray = new RelatedApplication[installedApps.size()];
            installedApps.toArray(installedAppsArray);
        }

        delayThenRun(() -> callback.call(installedAppsArray), delayMs);
    }

    @WorkerThread
    private void checkInstantApp(
            ResultHolder resultHolder, int taskIdx, RelatedApplication app, GURL frameUrl) {
        int delayMs = calculateDelayForPackageMs(app.id);

        if (!mInstantAppsHandler.isInstantAppAvailable(frameUrl.getSpec(),
                    INSTANT_APP_HOLDBACK_ID_STRING.equals(app.id),
                    true /* includeUserPrefersBrowser */)) {
            delayThenRun(() -> resultHolder.onResult(null, taskIdx, delayMs), 0);
            return;
        }

        setVersionInfo(app);
        delayThenRun(() -> resultHolder.onResult(app, taskIdx, delayMs), 0);
    }

    @WorkerThread
    private void checkPlayApp(
            ResultHolder resultHolder, int taskIdx, RelatedApplication app, GURL frameUrl) {
        int delayMs = calculateDelayForPackageMs(app.id);

        if (!isAppInstalledAndAssociatedWithOrigin(app.id, frameUrl, mPackageManagerDelegate)) {
            delayThenRun(() -> resultHolder.onResult(null, taskIdx, delayMs), 0);
            return;
        }

        setVersionInfo(app);
        delayThenRun(() -> resultHolder.onResult(app, taskIdx, delayMs), 0);
    }

    @WorkerThread
    private void checkWebApkInstalled(
            ResultHolder resultHolder, int taskIdx, RelatedApplication app) {
        int delayMs = calculateDelayForPackageMs(app.url);

        if (!isWebApkInstalled(app.url)) {
            delayThenRun(() -> resultHolder.onResult(null, taskIdx, delayMs), 0);
            return;
        }

        // TODO(crbug.com/1043970): Should we expose the package name and the
        // version?
        delayThenRun(() -> resultHolder.onResult(app, taskIdx, delayMs), 0);
    }

    @UiThread
    private void checkWebApk(
            ResultHolder resultHolder, int taskIdx, RelatedApplication app, Url manifestUrl) {
        int delayMs = calculateDelayForPackageMs(app.url);

        InstalledAppProviderImplJni.get().checkDigitalAssetLinksRelationshipForWebApk(
                getProfile(), app.url, manifestUrl.url, (verified) -> {
                    if (verified) {
                        PostTask.postTask(TaskTraits.BEST_EFFORT_MAY_BLOCK,
                                () -> checkWebApkInstalled(resultHolder, taskIdx, app));
                    } else {
                        resultHolder.onResult(null, taskIdx, delayMs);
                    }
                });
    }

    protected Profile getProfile() {
        return Profile.getLastUsedRegularProfile();
    }

    /**
     * Sets the version information, if available, to |installedApp|.
     * @param installedApp
     */
    private void setVersionInfo(RelatedApplication installedApp) {
        assert installedApp.id != null;
        try {
            PackageInfo info = mPackageManagerDelegate.getPackageInfo(installedApp.id, 0);
            installedApp.version = info.versionName;
        } catch (NameNotFoundException e) {
        }
    }

    /**
     * Returns whether or not the app is for an instant app/instant app holdback.
     */
    private boolean isInstantNativeApp(RelatedApplication app) {
        if (!app.platform.equals(RELATED_APP_PLATFORM_ANDROID)) return false;

        if (app.id == null) return false;

        return INSTANT_APP_ID_STRING.equals(app.id)
                || INSTANT_APP_HOLDBACK_ID_STRING.equals(app.id);
    }

    /**
     * Returns whether or not the app is for a regular native app.
     */
    private boolean isRegularNativeApp(RelatedApplication app) {
        if (!app.platform.equals(RELATED_APP_PLATFORM_ANDROID)) return false;

        if (app.id == null) return false;

        return !isInstantNativeApp(app);
    }

    /**
     * Returns whether or not the app is for a WebAPK.
     */
    private boolean isWebApk(RelatedApplication app) {
        if (!app.platform.equals(RELATED_APP_PLATFORM_WEBAPP)) return false;

        if (app.url == null) return false;

        return true;
    }

    /**
     * Return whether the WebAPK identified by |manifestUurl| is installed.
     */
    @VisibleForTesting
    public boolean isWebApkInstalled(String manifestUrl) {
        return WebApkValidator.queryBoundWebApkForManifestUrl(
                       ContextUtils.getApplicationContext(), manifestUrl)
                != null;
    }

    /**
     * Determines how long to artifically delay for, for a particular package name.
     */
    private int calculateDelayForPackageMs(String packageName) {
        // Important timing-attack prevention measure: delay by a pseudo-random amount of time, to
        // add significant noise to the time taken to check whether this app is installed and
        // related. Otherwise, it would be possible to tell whether a non-related app is installed,
        // based on the time this operation takes.
        short hash = PackageHash.hashForPackage(packageName, mFrameUrlDelegate.isIncognito());

        // The time delay is the low 10 bits of the hash in 100ths of a ms (between 0 and 10ms).
        int delayHundredthsOfMs = hash & 0x3ff;
        return delayHundredthsOfMs / 100;
    }

    /**
     * Determines whether a particular app is installed and matches the origin.
     *
     * @param packageName Name of the Android package to check if installed. Returns false if the
     *                    app is not installed.
     * @param frameUrl Returns false if the Android package does not declare association with the
     *                origin of this URL. Can be null.
     */
    @WorkerThread
    public static boolean isAppInstalledAndAssociatedWithOrigin(
            String packageName, GURL frameUrl, PackageManagerDelegate pm) {
        // TODO(yusufo): Move this to a better/shared location before crbug.com/749876 is closed.

        if (frameUrl == null) return false;

        // Early-exit if the Android app is not installed.
        JSONArray statements;
        try {
            statements = getAssetStatements(packageName, pm);
        } catch (NameNotFoundException e) {
            return false;
        }

        // The installed Android app has provided us with a list of asset statements. If any one of
        // those statements is a web asset that matches the given origin, return true.
        for (int i = 0; i < statements.length(); i++) {
            JSONObject statement;
            try {
                statement = statements.getJSONObject(i);
            } catch (JSONException e) {
                // If an element is not an object, just ignore it.
                continue;
            }

            GURL site = getSiteForWebAsset(statement);

            // The URI is considered equivalent if the scheme, host, and port match, according
            // to the DigitalAssetLinks v1 spec.
            if (site != null && statementTargetMatches(frameUrl, site)) {
                return true;
            }
        }

        // No asset matched the origin.
        return false;
    }

    /**
     * Gets the asset statements from an Android app's manifest.
     *
     * This retrieves the list of statements from the Android app's "asset_statements" manifest
     * resource, as specified in Digital Asset Links v1.
     *
     * @param packageName Name of the Android package to get statements from.
     * @return The list of asset statements, parsed from JSON.
     * @throws NameNotFoundException if the application is not installed.
     */
    private static JSONArray getAssetStatements(String packageName, PackageManagerDelegate pm)
            throws NameNotFoundException {
        // Get the <meta-data> from this app's manifest.
        // Throws NameNotFoundException if the application is not installed.
        ApplicationInfo appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        if (appInfo == null || appInfo.metaData == null) {
            return new JSONArray();
        }

        int identifier = appInfo.metaData.getInt(ASSET_STATEMENTS_KEY);
        if (identifier == 0) {
            return new JSONArray();
        }

        // Throws NameNotFoundException in the rare case that the application was uninstalled since
        // getting |appInfo| (or resources could not be loaded for some other reason).
        Resources resources = pm.getResourcesForApplication(appInfo);

        String statements;
        try {
            statements = resources.getString(identifier);
        } catch (Resources.NotFoundException e) {
            // This should never happen, but it could if there was a broken APK, so handle it
            // gracefully without crashing.
            Log.w(TAG,
                    "Android package " + packageName + " missing asset statements resource (0x"
                            + Integer.toHexString(identifier) + ").");
            return new JSONArray();
        }

        try {
            return new JSONArray(statements);
        } catch (JSONException e) {
            // If the JSON is invalid or not an array, assume it is empty.
            Log.w(TAG,
                    "Android package " + packageName
                            + " has JSON syntax error in asset statements resource (0x"
                            + Integer.toHexString(identifier) + ").");
            return new JSONArray();
        }
    }

    /**
     * Gets the "site" URI from an Android asset statement.
     *
     * @return The site, or null if the asset string was invalid or not related to a web site. This
     *         could be because: the JSON string was invalid, there was no "target" field, this was
     *         not a web asset, there was no "site" field, or the "site" field was invalid.
     */
    private static GURL getSiteForWebAsset(JSONObject statement) {
        JSONObject target;
        try {
            // Ignore the "relation" field and allow an asset with any relation to this origin.
            // TODO(mgiuca): [Spec issue] Should we require a specific relation string, rather
            // than any or no relation?
            target = statement.getJSONObject(ASSET_STATEMENT_FIELD_TARGET);
        } catch (JSONException e) {
            return null;
        }

        // If it is not a web asset, skip it.
        if (!isAssetWeb(target)) {
            return null;
        }

        try {
            return new GURL(target.getString(ASSET_STATEMENT_FIELD_SITE));
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Determines whether an Android asset statement is for a website.
     *
     * @param target The "target" field of the asset statement.
     */
    private static boolean isAssetWeb(JSONObject target) {
        String namespace;
        try {
            namespace = target.getString(ASSET_STATEMENT_FIELD_NAMESPACE);
        } catch (JSONException e) {
            return false;
        }

        return namespace.equals(ASSET_STATEMENT_NAMESPACE_WEB);
    }

    private static boolean statementTargetMatches(GURL frameUrl, GURL assetUrl) {
        if (assetUrl.getScheme() == null || assetUrl.getHost() == null) return false;

        return assetUrl.getScheme().equals(frameUrl.getScheme())
                && assetUrl.getHost().equals(frameUrl.getHost())
                && assetUrl.getPort().equals(frameUrl.getPort());
    }

    /**
     * Runs a Runnable task after a given delay.
     *
     * Protected and non-static for testing.
     *
     * @param r The Runnable that will be executed.
     * @param delayMillis The delay (in ms) until the Runnable will be executed.
     * @return True if the Runnable was successfully placed into the message queue.
     */
    protected void delayThenRun(Runnable r, long delayMillis) {
        PostTask.postDelayedTask(UiThreadTaskTraits.DEFAULT, r, delayMillis);
    }

    @NativeMethods
    interface Natives {
        void checkDigitalAssetLinksRelationshipForWebApk(
                Profile profile, String webDomain, String manifestUrl, Callback<Boolean> callback);
    }
}
