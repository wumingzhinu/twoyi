/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.twoyi.utils.LogEvents;

/**
 * @author weishu
 * @date 2021/10/27.
 */

public class TwoyiStatusManager {

    private static final String TAG = "TwoyiStatusManager";
    private static final TwoyiStatusManager INSTANCE = new TwoyiStatusManager();
    private TwoyiStatusManager() {
    }

    private final AtomicBoolean mStarted = new AtomicBoolean(false);
    private final AtomicBoolean mShown = new AtomicBoolean(false);
    private final AtomicLong mBootStartTime = new AtomicLong(0);

    // 使用 CountDownLatch 代替 CyclicBarrier，更可靠且支持重复 reset
    private volatile CountDownLatch mBootLatch = new CountDownLatch(1);

    public static TwoyiStatusManager getInstance() {
        return INSTANCE;
    }

    public void updateVisibility(boolean visible) {
        mShown.set(visible);
    }

    public void markStarted() {
        if (mStarted.compareAndSet(false, true)) {
            mBootLatch.countDown();
            long elapsed = SystemClock.elapsedRealtime() - mBootStartTime.get();
            Log.i(TAG, "boot completed in " + elapsed + "ms");
        }
    }

    public boolean isStarted() {
        return mStarted.get();
    }

    public void reset() {
        mStarted.set(false);
        mBootStartTime.set(SystemClock.elapsedRealtime());
        mBootLatch = new CountDownLatch(1);
    }

    public boolean waitBoot(long timeout, TimeUnit unit) {
        try {
            return mBootLatch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void switchOs(Context context) {
        if (!mStarted.get()) {
            return;
        }

        Intent intent;
        if (mShown.get()) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
        } else {
            intent = new Intent(context, Render2Activity.class);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        context.startActivity(intent);
        mShown.set(!mShown.get());
    }
}