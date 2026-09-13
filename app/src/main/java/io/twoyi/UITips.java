/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.app.Activity;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.RomManager;
import io.twoyi.utils.UIHelper;

/**
 * @author weishu
 * @date 2022/2/24.
 */

public class UITips {

    /**
     * @param activity the context
     * @param bootCallback 不是 Android 12 或者用户点了确认时触发的回调
     * @return 返回 true 代表不是 Android 12，或者允许用户启动
     */
    public static boolean checkForAndroid12(Activity activity, Runnable bootCallback) {
        return checkForAndroid12Plus(activity, bootCallback);
    }

    public static boolean checkForAndroid12Plus(Activity activity, Runnable bootCallback) {
        boolean showTips = AppKV.getBooleanConfig(activity, AppKV.SHOW_ANDROID12_TIPS, true);

        // Android 12 (API 31) requires explicit exported declarations
        // Android 13+ (API 33) requires POST_NOTIFICATIONS runtime permission
        // Android 14+ (API 34) requires foregroundServiceType in manifest
        // Android 15+ (API 35) enforces edge-to-edge by default
        // Android 16 (API 36) requires latest target SDK features
        if (!RomManager.isAndroid12() || !showTips) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                handleAndroid13PlusPermission(activity, bootCallback);
                return true;
            }
            bootCallback.run();
            return true;
        }

        UIHelper.getDialogBuilder(activity)
                .setTitle(android.R.string.dialog_alert_title)
                .setMessage(R.string.tips_for_android12)
                .setCancelable(false)
                .setPositiveButton(R.string.look_it, ((dialog, which) -> {
                    UIHelper.visitSite(activity, "https://twoyi.app/guide/android-12.html");
                    dialog.dismiss();

                    activity.finish();
                }))
                .setNegativeButton(R.string.donate_never_show, (dialog, which) -> {
                    dialog.dismiss();

                    confirmForAndroid12(activity, bootCallback);
                })
                .show();

        return false;
    }

    private static void handleAndroid13PlusPermission(Activity activity, Runnable callback) {
        // On Android 13+, POST_NOTIFICATIONS is needed if notifications are shown
        // For now, just proceed with boot
        if (callback != null) {
            callback.run();
        }
    }

    private static void confirmForAndroid12(Activity activity, Runnable callback) {
        UIHelper.getDialogBuilder(activity)
                .setTitle(android.R.string.dialog_alert_title)
                .setCancelable(false)
                .setMessage(R.string.confirm_for_android12)
                .setPositiveButton(R.string.i_confirm_it, (((dialog1, which1) -> {

                    dialog1.dismiss();

                    AppKV.setBooleanConfig(activity, AppKV.SHOW_ANDROID12_TIPS, false);

                    // 点不再展示才开始启动系统
                    if (callback != null) {
                        callback.run();
                    }
                })))
                .setNegativeButton(R.string.look_it, (dialog12, which12) -> {
                    UIHelper.visitSite(activity, "https://twoyi.app/guide/android-12.html");
                    dialog12.dismiss();

                    activity.finish();
                })
                .show();
    }
}
