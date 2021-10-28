/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.core.content.FileProvider;

import com.android.launcher3.model.AppShareabilityChecker;
import com.android.launcher3.model.AppShareabilityJobService;
import com.android.launcher3.model.AppShareabilityManager;
import com.android.launcher3.model.AppShareabilityManager.ShareabilityStatus;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.popup.PopupDataProvider;
import com.android.launcher3.popup.SystemShortcut;

import java.io.File;

/**
 * Defines the Share system shortcut and its factory.
 * This shortcut can be added to the app long-press menu on the home screen.
 * Clicking the button will initiate peer-to-peer sharing of the app.
 */
public final class AppSharing {
    /**
     * This flag enables this feature. It is defined here rather than in launcher3's FeatureFlags
     * because it is unique to Go and not toggleable at runtime.
     */
    public static final boolean ENABLE_APP_SHARING = true;
    /**
     * With this flag enabled, the Share App button will be dynamically enabled/disabled based
     * on each app's shareability status.
     */
    public static final boolean ENABLE_SHAREABILITY_CHECK = false;

    private static final String TAG = "AppSharing";
    private static final String FILE_PROVIDER_SUFFIX = ".overview.fileprovider";
    private static final String APP_EXSTENSION = ".apk";
    private static final String APP_MIME_TYPE = "application/application";

    private final String mSharingComponent;
    private AppShareabilityManager mShareabilityMgr;

    private AppSharing(Launcher launcher) {
        mSharingComponent = launcher.getText(R.string.app_sharing_component).toString();
    }

    private Uri getShareableUri(Context context, String path, String displayName) {
        String authority = BuildConfig.APPLICATION_ID + FILE_PROVIDER_SUFFIX;
        File pathFile = new File(path);
        return FileProvider.getUriForFile(context, authority, pathFile, displayName);
    }

    private SystemShortcut<Launcher> getShortcut(Launcher launcher, ItemInfo info) {
        if (TextUtils.isEmpty(mSharingComponent)) {
            return null;
        }
        return new Share(launcher, info);
    }

    /**
     * Instantiates AppShareabilityManager, which then reads app shareability data from disk
     * Also schedules a job to update those data
     * @param context The application context
     * @param checker An implementation of AppShareabilityChecker to perform the actual checks
     *                when updating the data
     */
    public static void setUpShareabilityCache(Context context, AppShareabilityChecker checker) {
        AppShareabilityManager shareMgr = AppShareabilityManager.INSTANCE.get(context);
        shareMgr.setShareabilityChecker(checker);
        AppShareabilityJobService.schedule(context);
    }

    /**
     * The Share App system shortcut, used to initiate p2p sharing of a given app
     */
    public final class Share extends SystemShortcut<Launcher> {
        private PopupDataProvider mPopupDataProvider;

        public Share(Launcher target, ItemInfo itemInfo) {
            super(R.drawable.ic_share, R.string.app_share_drop_target_label, target, itemInfo);
            mPopupDataProvider = target.getPopupDataProvider();

            if (ENABLE_SHAREABILITY_CHECK) {
                mShareabilityMgr = AppShareabilityManager.INSTANCE.get(target);
                checkShareability(/* requestUpdateIfUnknown */ true);
            }
        }

        @Override
        public void onClick(View view) {
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);

            ComponentName targetComponent = mItemInfo.getTargetComponent();
            if (targetComponent == null) {
                Log.e(TAG, "Item missing target component");
                return;
            }
            String packageName = targetComponent.getPackageName();
            PackageManager packageManager = view.getContext().getPackageManager();
            String sourceDir, appLabel;
            try {
                PackageInfo packageInfo = packageManager.getPackageInfo(packageName, 0);
                sourceDir = packageInfo.applicationInfo.sourceDir;
                appLabel = packageManager.getApplicationLabel(packageInfo.applicationInfo)
                        .toString() + APP_EXSTENSION;
            } catch (Exception e) {
                Log.e(TAG, "Could not find info for package \"" + packageName + "\"");
                return;
            }
            Uri uri = getShareableUri(view.getContext(), sourceDir, appLabel);
            sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
            sendIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);

            sendIntent.setType(APP_MIME_TYPE);
            sendIntent.setComponent(ComponentName.unflattenFromString(mSharingComponent));

            mTarget.startActivitySafely(view, sendIntent, mItemInfo);

            AbstractFloatingView.closeAllOpenViews(mTarget);
        }

        private void onStatusUpdated(boolean success) {
            if (!success) {
                // Something went wrong. Specific error logged in AppShareabilityManager.
                return;
            }
            checkShareability(/* requestUpdateIfUnknown */ false);
            mTarget.runOnUiThread(() -> {
                mPopupDataProvider.redrawSystemShortcuts();
            });
        }

        private void checkShareability(boolean requestUpdateIfUnknown) {
            String packageName = mItemInfo.getTargetComponent().getPackageName();
            @ShareabilityStatus int status = mShareabilityMgr.getStatus(packageName);
            setEnabled(status == ShareabilityStatus.SHAREABLE);

            if (requestUpdateIfUnknown && status == ShareabilityStatus.UNKNOWN) {
                mShareabilityMgr.requestAppStatusUpdate(packageName, this::onStatusUpdated);
            }
        }
    }

    /**
     * Shortcut factory for generating the Share App button
     */
    public static final SystemShortcut.Factory<Launcher> SHORTCUT_FACTORY = (launcher, itemInfo) ->
            (new AppSharing(launcher)).getShortcut(launcher, itemInfo);
}
