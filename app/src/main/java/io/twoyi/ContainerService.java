/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Pins the container process out of Android's cached/frozen bucket.
 *
 * twoyi runs a whole Android system inside this process, and the design is to
 * hand control back to the host (SWITCH_HOST -&gt; HOME) while the container keeps
 * working in the background. On Android 11+ a cached app is SIGSTOPped by the
 * cached-app freezer (and CPU-starved even before that), which froze the
 * container outright: switching back to twoyi showed a hung screen until the
 * process was thawed, and anything the container tried to do meanwhile simply
 * never ran. That is the "lags, then freezes while in use" symptom.
 *
 * A foreground service keeps the process in the foreground-service state,
 * which is neither cached nor freezable.
 */
public class ContainerService extends Service {

    private static final String TAG = "ContainerService";

    private static final String CHANNEL_ID = "twoyi_container";
    private static final int NOTIFICATION_ID = 0x7477;

    /**
     * Idempotent: safe to call every time the container comes up. A failure
     * (background-start restriction, OEM policy) must never take the container
     * down with it - it only means the process may be frozen later.
     */
    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, ContainerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            Log.i(TAG, "foreground service requested");
        } catch (Throwable t) {
            Log.w(TAG, "start foreground service failed", t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.container_service_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            nm.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.container_running))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();

        // Called from onCreate(), i.e. right after startForegroundService(): the
        // 5s startForeground() deadline on Android 12+ is met.
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Never resurrect the service on its own: if it goes away, the
        // container it was pinning is gone too.
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        //noinspection deprecation
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
