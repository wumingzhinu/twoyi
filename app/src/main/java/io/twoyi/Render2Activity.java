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

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.BootTrace;
import io.twoyi.utils.NavUtils;
import io.twoyi.utils.RomManager;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Render2Activity extends AppCompatActivity implements View.OnTouchListener {

    private static final String TAG = "Render2Activity";

    /** Container boot window; rendering appears only after BOOT_COMPLETED. */
    private static final int BOOT_TIMEOUT_SECONDS = 120;

    private final java.util.concurrent.atomic.AtomicBoolean mSurfaceCreated = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean mInitCalled = new java.util.concurrent.atomic.AtomicBoolean(false);

    private SurfaceView mSurfaceView;

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private View mBootLogView;

    private final AtomicBoolean mIsExtracting = new AtomicBoolean(false);

    private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            BootTrace.i(TAG, "surfaceCreated");
            mSurfaceCreated.set(true);
            Surface surface = holder.getSurface();
            WindowManager windowManager = getWindowManager();
            Display defaultDisplay = windowManager.getDefaultDisplay();
            DisplayMetrics displayMetrics = new DisplayMetrics();
            defaultDisplay.getRealMetrics(displayMetrics);

            float xdpi = displayMetrics.xdpi;
            float ydpi = displayMetrics.ydpi;

            BootTrace.i(TAG, "Renderer.init surface=" + surface.isValid() + " loader=" + RomManager.getLoaderPath(getApplicationContext()));
            mInitCalled.set(true);
            Renderer.init(surface, RomManager.getLoaderPath(getApplicationContext()), xdpi, ydpi, (int) getBestFps());

            Log.i(TAG, "surfaceCreated");
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            BootTrace.i(TAG, "surfaceChanged " + width + "x" + height);
            Surface surface = holder.getSurface();
            Renderer.resetWindow(surface, 0, 0, mSurfaceView.getWidth(), mSurfaceView.getHeight());
            Log.i(TAG, "surfaceChanged: " + mSurfaceView.getWidth() + "x" + mSurfaceView.getHeight());
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            BootTrace.w(TAG, "surfaceDestroyed");
            Renderer.removeWindow(holder.getSurface());
            Log.i(TAG, "surfaceDestroyed!");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean started = TwoyiStatusManager.getInstance().isStarted();
        BootTrace.i(TAG, "onCreate savedInstanceState=" + savedInstanceState + " isStarted=" + started);
        Log.i(TAG, "onCreate: " + savedInstanceState + " isStarted: " + started);

        if (started) {
            // we have been started, but WTF we are onCreate again? just reboot ourself.
            finish();
            RomManager.reboot(this);
            return;
        }

        // reset state
        TwoyiStatusManager.getInstance().reset();

        NavUtils.hideNavigation(getWindow());

        super.onCreate(savedInstanceState);

        setContentView(R.layout.ac_render);
        mRootView = findViewById(R.id.root);

        mSurfaceView = new SurfaceView(this);
        mSurfaceView.getHolder().addCallback(mSurfaceCallback);

        mLoadingLayout = findViewById(R.id.loadingLayout);
        mLoadingView = findViewById(R.id.loading);
        mLoadingText = findViewById(R.id.loadingText);
        mBootLogView = findViewById(R.id.bootlog);

        mLoadingLayout.setVisibility(View.VISIBLE);
        mLoadingView.startAnimation();

        UITips.checkForAndroid12(this, this::bootSystem);

        mSurfaceView.setOnTouchListener(this);

    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Log.i(TAG, "onRestoreInstanceState: " + savedInstanceState);

        // we don't support state restore, just reboot.
        finish();
        RomManager.reboot(this);
    }

    private void bootSystem() {
        BootTrace.i(TAG, "bootSystem begin");
        boolean romExist = RomManager.romExist(this);
        boolean factoryRomUpdated = RomManager.needsUpgrade(this);
        boolean forceInstall = AppKV.getBooleanConfig(getApplicationContext(), AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        boolean use3rdRom = AppKV.getBooleanConfig(getApplicationContext(), AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);

        BootTrace.i(TAG, "bootSystem romExist=" + romExist + " needsUpgrade=" + factoryRomUpdated
                + " force=" + forceInstall + " thirdParty=" + use3rdRom);

        boolean shouldExtractRom = !romExist || forceInstall || (!use3rdRom && factoryRomUpdated);

        if (shouldExtractRom) {
            BootTrace.i(TAG, "extracting rom...");

            showTipsForFirstBoot();

            new Thread(() -> {
                mIsExtracting.set(true);
                RomManager.extractRootfs(getApplicationContext(), romExist, factoryRomUpdated, forceInstall, use3rdRom);
                mIsExtracting.set(false);

                RomManager.initRootfs(getApplicationContext());

                runOnUiThread(() -> {
                    BootTrace.i(TAG, "rom extracted, attaching SurfaceView");
                    mRootView.addView(mSurfaceView, 0);
                    showBootingProcedure();
                });
            }, "extract-rom").start();
        } else {
            BootTrace.i(TAG, "rom present, attaching SurfaceView");
            mRootView.addView(mSurfaceView, 0);
            showBootingProcedure();
        }
    }

    private void showTipsForFirstBoot() {
        mLoadingText.setText(R.string.extracting_tips);
        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips);
            }
        }, 5000);

        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips2);
            }
        }, 10 * 1000);

        mRootView.postDelayed(() -> {
            if (mIsExtracting.get()) {
                mLoadingText.setText(R.string.first_boot_tips3);
            }
        }, 15 * 1000);
    }

    private void showBootingProcedure() {
        // mLoadingText.setText(R.string.booting_tips);
        mLoadingText.setVisibility(View.VISIBLE);
        mBootLogView.setVisibility(View.VISIBLE);
        final long start = SystemClock.elapsedRealtime();
        new Thread(() -> {

            if (true) {
                boolean success = false;
                try {
                    while (SystemClock.elapsedRealtime() - start < BOOT_TIMEOUT_SECONDS * 1000L) {
                        success = TwoyiStatusManager.getInstance().waitBoot(5, TimeUnit.SECONDS);
                        if (success) {
                            break;
                        }
                        final long secs = (SystemClock.elapsedRealtime() - start) / 1000;
                        Log.i(TAG, "waiting boot: " + secs + "s / " + BOOT_TIMEOUT_SECONDS + "s");
                        BootTrace.i(TAG, "waiting boot " + secs + "s, surfaceCreated=" + mSurfaceCreated.get()
                                + " initCalled=" + mInitCalled.get());
                        runOnUiThread(() -> mLoadingText.setText(getString(R.string.booting_tips) + " " + secs + "s / " + BOOT_TIMEOUT_SECONDS + "s"));
                    }
                } catch (Throwable ignored) {
                }

                if (success) {
                    Log.i(TAG, "boot completed in " + (SystemClock.elapsedRealtime() - start) + "ms");
                    runOnUiThread(() -> {
                        mLoadingView.stopAnimation();
                        mLoadingLayout.setVisibility(View.GONE);
                    });
                    return;
                }

                Log.e(TAG, "boot give up after " + ((SystemClock.elapsedRealtime() - start) / 1000) + "s (limit " + BOOT_TIMEOUT_SECONDS + "s); exporting logs");
                TwoyiStatusManager.getInstance().onWaitBootFailed((SystemClock.elapsedRealtime() - start) / 1000);
                BootTrace.e(TAG, "boot give up; surfaceCreated=" + mSurfaceCreated.get()
                        + " initCalled=" + mInitCalled.get());
                exportRomLog();
                exportHostLog();
                exportTraceLogs();

                runOnUiThread(() -> Toast.makeText(getApplicationContext(), R.string.boot_failed, Toast.LENGTH_LONG).show());

                // keep the boot log on screen so the hang point can be read
                SystemClock.sleep(30 * 1000);

                finish();
                System.exit(0);
                return;
            }
        }, "waiting-boot").start();
    }

    /**
     * Copy the container's stdout/stderr ({@code /data/data/io.twoyi/log.txt})
     * into Download/logs so a failed boot can be inspected afterwards.
     */
    private void exportRomLog() {
        try {
            java.io.File log = new java.io.File(getDataDir(), "log.txt");
            if (!log.exists() || log.length() == 0) {
                Log.w(TAG, "no container log at " + log.getAbsolutePath());
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.w(TAG, "pre-Q shared storage not supported for log export");
                return;
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "twoyi-rom-" + System.currentTimeMillis() + ".txt");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/logs");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return;
            }
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                 java.io.InputStream is = new java.io.FileInputStream(log)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = is.read(buffer)) > 0) {
                    os.write(buffer, 0, count);
                }
            }
            Log.i(TAG, "container log exported to " + uri);
        } catch (Throwable t) {
            Log.w(TAG, "export container log failed", t);
        }
    }

    /**
     * Dump the host logcat into the same Download/logs directory. Needs no
     * root; runs the same dump path as {@code adb logcat -d}.
     */
    private void exportHostLog() {        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return;
            }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "twoyi-host-" + System.currentTimeMillis() + ".txt");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/logs");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return;
            }
            java.util.List<String> out = new java.util.ArrayList<>();
            java.util.List<String> err = new java.util.ArrayList<>();
            io.twoyi.utils.ShellUtil.newSh().newJob().add("logcat -d").to(out, err).exec();
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                 java.io.Writer w = new java.io.OutputStreamWriter(os)) {
                for (String line : out) {
                    w.write(line);
                    w.write('\n');
                }
                for (String line : err) {
                    w.write("[stderr] " + line);
                    w.write('\n');
                }
            }
            Log.i(TAG, "host logcat exported to " + uri);
        } catch (Throwable t) {
            Log.w(TAG, "export host logcat failed", t);
        }
    }

    /** Copy the persistent boot trace + native trace into Download/logs too. */
    private void exportTraceLogs() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        try {
            java.io.File trace = io.twoyi.utils.BootTrace.file();
            if (trace != null && trace.length() > 0) {
                Uri uri = createDownloadFile("twoyi-boot-trace-");
                if (uri != null) {
                    copyFile(trace, uri);
                    BootTrace.i(TAG, "boot trace exported to " + uri);
                }
            }
            java.io.File nativeTrace = new java.io.File(getDataDir(), "native-trace.txt");
            if (nativeTrace.exists() && nativeTrace.length() > 0) {
                Uri uri = createDownloadFile("twoyi-native-trace-");
                if (uri != null) {
                    copyFile(nativeTrace, uri);
                    BootTrace.i(TAG, "native trace exported to " + uri);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "export trace logs failed", t);
        }
    }

    private Uri createDownloadFile(String namePrefix) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, namePrefix + System.currentTimeMillis() + ".txt");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/logs");
        return getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    }

    private void copyFile(java.io.File src, Uri dst) throws java.io.IOException {
        try (java.io.OutputStream os = getContentResolver().openOutputStream(dst);
             java.io.InputStream is = new java.io.FileInputStream(src)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = is.read(buffer)) > 0) {
                os.write(buffer, 0, count);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            NavUtils.hideNavigation(getWindow());
        }

        // Update global visibility.
        TwoyiStatusManager.getInstance().updateVisibility(hasFocus);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        // Translate via public APIs only; MotionEvent.mNativePtr is a hidden
        // field that newer Android releases removed, so the native side takes
        // plain scalars instead of the raw event object.
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        Renderer.handleTouch(
                action,
                event.getPointerId(index),
                event.getX(index),
                event.getY(index),
                event.getPressure(index));
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Log.d(TAG, "onKeyDown: " + keyCode);
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // TODO: 2021/10/26 Add Volume control
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // super.onBackPressed();
        Renderer.sendKeycode(KeyEvent.KEYCODE_HOME);
    }

    private float getBestFps() {
        WindowManager windowManager = getWindowManager();
        Display defaultDisplay = windowManager.getDefaultDisplay();
        Display.Mode[] supportedModes = defaultDisplay.getSupportedModes();
        float fps = 45;
        for (Display.Mode supportedMode : supportedModes) {
            float refreshRate = supportedMode.getRefreshRate();
            if (refreshRate > fps) {
                // fps = refreshRate;
            }
        }

        Log.w(TAG, "current fps: " + fps);
        return fps;
    }
}
