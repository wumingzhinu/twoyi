/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.topjohnwu.superuser.CallbackList;
import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.ShellUtil;

/**
 * @author weishu
 * @date 2022/1/1.
 */

public class BootLogTexture extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "BootLogTexture";

    private final AtomicBoolean mRendering = new AtomicBoolean(false);

    private final LimitedQueue<String> mLogMessages = new LimitedQueue<>(160);
    private final LinkedList<String> mSnapShot = new LinkedList<>();

    private final SparseArray<Paint> mPaints = new SparseArray<>();
    private final Paint mDefaultPaint = new Paint();

    private static final SparseIntArray COLOR_MAP = new SparseIntArray();

    static {
        COLOR_MAP.put('V', 0xBBBBBB);
        COLOR_MAP.put('D', 0x5EBB1E);
        COLOR_MAP.put('I', 0x4CBBA2);
        COLOR_MAP.put('W', 0xFFD21C);
        COLOR_MAP.put('E', 0xFF6B68);
        COLOR_MAP.put('F', Color.RED);
        COLOR_MAP.put('S', Color.WHITE);

//        COLOR_MAP.put('V', 0xFFFFFF);
//        COLOR_MAP.put('D', 0x5FAFFE);
//        COLOR_MAP.put('I', 0x02D701);
//        COLOR_MAP.put('W', 0xD75E02);
//        COLOR_MAP.put('E', 0xFF2600);
//        COLOR_MAP.put('F', 0xFF2600);
//        COLOR_MAP.put('S', Color.WHITE);
    }

    public BootLogTexture(@NonNull Context context) {
        this(context, null);
    }

    public BootLogTexture(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BootLogTexture(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BootLogTexture(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {
        setSurfaceTextureListener(this);

        for (int i = 0; i < COLOR_MAP.size(); i++) {
            int key = COLOR_MAP.keyAt(i);
            int value = COLOR_MAP.valueAt(i);

            Paint paint = new Paint();
            setPaint(paint, value);

            mPaints.put(key, paint);
        }

        setPaint(mDefaultPaint, Color.WHITE);
    }

    private void setPaint(Paint paint, int color) {
        paint.setColor(color);
        paint.setAntiAlias(true);
        paint.setTextSize(16);
        paint.setAlpha(128);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mRendering.set(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mRendering.set(false);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        mRendering.set(visibility == VISIBLE);
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {

        Shell.EXECUTOR.execute(() -> {
            List<String> callbackList = new CallbackList<String>() {
                @Override
                public void onAddElement(String s) {
                    // Once the boot log is hidden the render loop is gone, so
                    // queueing lines only feeds a LinkedList nobody drains -
                    // thousands of allocations a second for as long as the
                    // logcat job lives, i.e. steady GC churn right after boot.
                    if (!mRendering.get() || TextUtils.isEmpty(s)) {
                        return;
                    }
                    synchronized (mLogMessages) {
                        mLogMessages.add(s);
                    }
                }
            };

            Shell shell = ShellUtil.newSh();
            // 5 minutes instead of 30s: the boot window is now much longer.
            shell.newJob().add("timeout -s 9 300 logcat -v brief *I").to(callbackList).submit();

            startRomLogTail();

            while (mRendering.get()) {
                try {
                    render();
                } catch (Throwable t) {
                    // The surface can be torn down under us (onSurfaceTextureDestroyed
                    // now releases it), so lockCanvas() may legitimately fail on the
                    // last frame. Swallow it: throwing here would unwind out of this
                    // loop and skip waitAndClose() below, leaking the root shell.
                    Log.w(TAG, "boot log render failed", t);
                    break;
                }
                SystemClock.sleep(16);
            }

            try {
                shell.waitAndClose(1, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }

        });
    }

    /**
     * Tail the container's own stdout/stderr (/data/data/io.twoyi/log.txt,
     * written by the native side when it spawns rootfs/init) so that hangs
     * inside the ROM boot are visible on screen.
     */
    private void startRomLogTail() {
        new Thread(() -> {
            File logFile = new File(getContext().getDataDir(), "log.txt");
            long pos = 0;
            while (mRendering.get()) {
                try {
                    if (logFile.exists()) {
                        long len = logFile.length();
                        if (len < pos) {
                            pos = 0; // file was recreated (new boot)
                        }
                        if (len > pos) {
                            try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                                raf.seek(pos);
                                String line;
                                boolean added = false;
                                while ((line = raf.readLine()) != null) {
                                    synchronized (mLogMessages) {
                                        mLogMessages.add("[ROM] " + line);
                                    }
                                    added = true;
                                }
                                pos = raf.getFilePointer();
                                if (added) {
                                    synchronized (mLogMessages) {
                                        // keep the trailing marker visible
                                        mLogMessages.add("[ROM] ...");
                                    }
                                }
                            }
                        }
                    }
                    Thread.sleep(200);
                } catch (Throwable t) {
                    break;
                }
            }
        }, "rom-log-tail").start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        mRendering.set(false);
        // true == "release the SurfaceTexture for me". Returning false means
        // the caller takes ownership, and nothing here ever released it, so
        // every boot leaked a SurfaceTexture (and the GL texture behind it).
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

    }

    private void render() {
        Canvas canvas = null;
        try {
            canvas = lockCanvas();
            // lockCanvas() can return null once the SurfaceTexture has gone
            // away (the render thread is separate from the one that tears the
            // view down, so it can still be inside this loop). The old code
            // dereferenced it unguarded, which NPE'd, killed the worker and
            // left the logcat job it owned running forever.
            if (canvas == null) {
                return;
            }

            // clear canvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

            mSnapShot.clear();

            // TODO: lock free
            synchronized (mLogMessages) {
                mSnapShot.addAll(mLogMessages);
            }

            int count = 0;
            for (String log : mSnapShot) {

                char chr = log.charAt(0);

                Paint paint = mPaints.get(chr);
                if (paint == null) {
                    paint = mDefaultPaint;
                }

                canvas.drawText(log, 0, count++ * 20, paint);
            }

        } finally {
            if (canvas != null) {
                unlockCanvasAndPost(canvas);
            }
        }

    }
}
