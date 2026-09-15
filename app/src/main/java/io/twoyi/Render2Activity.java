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

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.os.Bundle;
import android.os.SystemClock;
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
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.utils.AppKV;
import io.twoyi.utils.LogEvents;
import io.twoyi.utils.NavUtils;
import io.twoyi.utils.RomManager;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Render2Activity extends Activity implements View.OnTouchListener {

    private static final String TAG = "Render2Activity";

    private SurfaceView mSurfaceView;

    private ViewGroup mRootView;
    private LoadingAnimationView mLoadingView;
    private TextView mLoadingText;
    private View mLoadingLayout;
    private View mBootLogView;

    private final AtomicBoolean mIsExtracting = new AtomicBoolean(false);
    private final AtomicBoolean mBootStarted = new AtomicBoolean(false);
    private int mBootRetryCount = 0;
    private static final int MAX_BOOT_RETRIES = 2;

    /** 启动失败重试计数，超过 3 次停止自动重试，避免无限循环 */
    private final java.util.concurrent.atomic.AtomicInteger mBootFailCount = new java.util.concurrent.atomic.AtomicInteger(0);

    private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            Surface surface = holder.getSurface();
            WindowManager windowManager = getWindowManager();
            Display defaultDisplay = windowManager.getDefaultDisplay();
            DisplayMetrics displayMetrics = new DisplayMetrics();
            defaultDisplay.getRealMetrics(displayMetrics);

            float xdpi = displayMetrics.xdpi;
            float ydpi = displayMetrics.ydpi;

            Renderer.init(surface, RomManager.getLoaderPath(getApplicationContext()), xdpi, ydpi, (int) getBestFps());

            Log.i(TAG, "surfaceCreated");
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
            Surface surface = holder.getSurface();
            Renderer.resetWindow(surface, 0, 0, mSurfaceView.getWidth(), mSurfaceView.getHeight());
            Log.i(TAG, "surfaceChanged: " + mSurfaceView.getWidth() + "x" + mSurfaceView.getHeight());
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            Renderer.removeWindow(holder.getSurface());
            Log.i(TAG, "surfaceDestroyed!");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean started = TwoyiStatusManager.getInstance().isStarted();
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

        // 立即显示状态文字，确保用户能看到
        mLoadingText.setVisibility(View.VISIBLE);
        mLoadingText.setText("Starting twoyi...");
        mLoadingText.setTextSize(14);

        // 浮动诊断按钮：点击保存完整诊断日志到 Downloads（boot_fail/manual_*.txt）
        try {
            TextView debugBtn = new TextView(this);
            debugBtn.setText("诊断");
            debugBtn.setTextSize(12);
            debugBtn.setPadding(10, 6, 10, 6);
            debugBtn.setBackgroundColor(0x88000000);
            debugBtn.setTextColor(0xFFFFFFFF);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            dlp.gravity = Gravity.TOP | Gravity.END;
            dlp.topMargin = 90;
            dlp.rightMargin = 16;
            debugBtn.setLayoutParams(dlp);
            debugBtn.setOnClickListener(v -> {
                new Thread(() -> {
                    try {
                        String p = dumpBootFailureLogs("manual");
                        Log.i(TAG, "manual diagnostic saved: " + p);
                    } catch (Throwable t) {
                        Log.e(TAG, "manual diagnostic failed", t);
                    }
                }, "manual-diagnostic").start();
            });
            mRootView.addView(debugBtn);
        } catch (Throwable ignored) {
        }

        UITips.checkForAndroid12Plus(this, this::bootSystem);

        // Fallback: 如果 5 秒内 bootSystem 没被调用（UITips 被拦截），强制启动
        mRootView.postDelayed(() -> {
            if (!mBootStarted.getAndSet(true)) {
                Log.w(TAG, "UITips callback not fired in 5s, forcing bootSystem");
                runOnUiThread(() -> mLoadingText.setText("UITips timeout, forcing boot..."));
                bootSystem();
            }
        }, 5000);

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
        mBootStarted.set(true);
        try {
            bootSystemInner();
        } catch (Throwable t) {
            Log.e(TAG, "bootSystem crashed", t);
            final String err = "bootSystem CRASHED:\n" + t.getClass().getSimpleName() + ": " + t.getMessage();
            runOnUiThread(() -> {
                mLoadingView.stopAnimation();
                mLoadingText.setVisibility(View.VISIBLE);
                mLoadingText.setText(err);
                mLoadingText.setTextSize(12);
            });
        }
    }

    private void bootSystemInner() {
        runOnUiThread(() -> mLoadingText.setText("Step 1: Checking ROM..."));

        boolean romExist = RomManager.romExist(this);
        boolean factoryRomUpdated = RomManager.needsUpgrade(this);
        boolean forceInstall = AppKV.getBooleanConfig(getApplicationContext(), AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        boolean use3rdRom = AppKV.getBooleanConfig(getApplicationContext(), AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);

        boolean shouldExtractRom = !romExist || forceInstall || (!use3rdRom && factoryRomUpdated);

        runOnUiThread(() -> mLoadingText.setText("Step 1: ROM exist=" + romExist + " upgrade=" + factoryRomUpdated + "\nshouldExtract=" + shouldExtractRom));

        if (shouldExtractRom) {
            Log.i(TAG, "extracting rom...");

            new Thread(() -> {
                mIsExtracting.set(true);

                runOnUiThread(() -> mLoadingText.setText("Step 2: Checking rootfs.7z in assets..."));

                try {
                    // Check rootfs.7z in assets
                    String[] assetFiles = getAssets().list("");
                    boolean hasRootfs = false;
                    for (String f : assetFiles) {
                        if (f.equals("rootfs.7z")) { hasRootfs = true; break; }
                    }
                    final boolean hasRootfsFinal = hasRootfs;
                    runOnUiThread(() -> mLoadingText.setText("Step 2a: rootfs.7z in assets: " + hasRootfsFinal));

                    if (!hasRootfs) {
                        runOnUiThread(() -> mLoadingText.setText("Step 2a: FATAL: rootfs.7z NOT in APK assets!"));
                        mIsExtracting.set(false);
                        return;
                    }

                    // Check rootfs.7z file size from assets
                    try (InputStream is = getAssets().open("rootfs.7z")) {
                        long size = 0;
                        byte[] buf = new byte[8192];
                        int read;
                        while ((read = is.read(buf)) > 0) size += read;
                        final long rootfsSize = size;
                        runOnUiThread(() -> mLoadingText.setText("Step 2b: rootfs.7z size: " + (rootfsSize / 1024 / 1024) + "MB (" + rootfsSize + " bytes)"));

                        if (rootfsSize < 1000) {
                            // Likely an LFS pointer
                            runOnUiThread(() -> mLoadingText.setText("Step 2b: WARNING: rootfs.7z is only " + rootfsSize + " bytes - likely Git LFS pointer!"));
                            mIsExtracting.set(false);
                            return;
                        }
                    }

                    runOnUiThread(() -> mLoadingText.setText("Step 2c: Extracting rootfs.7z..."));

                    boolean extractSuccess = false;
                    try {
                        extractSuccess = RomManager.extractRootfs(getApplicationContext(), romExist, factoryRomUpdated, forceInstall, use3rdRom);
                    } catch (Throwable e) {
                        Log.e(TAG, "extract rootfs error", e);
                        final String err = e.getMessage();
                        runOnUiThread(() -> mLoadingText.setText("Step 2c: EXTRACT EXCEPTION:\n" + err));
                        mIsExtracting.set(false);
                        return;
                    }

                    if (!extractSuccess) {
                        String detail = RomManager.lastExtractError;
                        File rootfs7z = new File(getFilesDir(), "rootfs.7z");
                        final String sizeStr = rootfs7z.exists() ? (rootfs7z.length() / 1024 / 1024) + "MB" : "NOT FOUND";
                        runOnUiThread(() -> mLoadingText.setText("Step 2c: EXTRACT FAILED\nCopied: " + sizeStr + "\nError: " + (detail != null ? detail : "unknown")));
                        mIsExtracting.set(false);
                        return;
                    }

                    runOnUiThread(() -> mLoadingText.setText("Step 3: initRootfs..."));
                    try {
                        RomManager.initRootfs(getApplicationContext());
                    } catch (Throwable e) {
                        final String err = e.getMessage();
                        runOnUiThread(() -> mLoadingText.setText("Step 3: initRootfs FAILED\n" + err));
                        mIsExtracting.set(false);
                        return;
                    }

                    mIsExtracting.set(false);

                    // Step 3.5: Verify rootfs structure
                    runOnUiThread(() -> mLoadingText.setText("Step 3.5: Checking rootfs structure..."));
                    try {
                        File rootfsDir = new File(getFilesDir(), "../rootfs");
                        StringBuilder sb = new StringBuilder();
                        sb.append("rootfs: ").append(rootfsDir.exists() ? "EXISTS" : "MISSING").append("\n");

                        // List top-level contents
                        File[] topFiles = rootfsDir.listFiles();
                        if (topFiles != null) {
                            sb.append("top entries: ").append(topFiles.length).append("\n");
                            int shown = 0;
                            for (File f : topFiles) {
                                if (shown >= 15) { sb.append("... (more)\n"); break; }
                                String type = f.isDirectory() ? "DIR/" : "FILE(" + f.length() + ")";
                                sb.append("  ").append(f.getName()).append(": ").append(type).append("\n");
                                shown++;
                            }
                        } else {
                            sb.append("rootfs: CANNOT LIST\n");
                        }

                        // Check critical paths
                        String[][] checks = {
                            {"init", null},
                            {"system/etc/prop.default", null},
                            {"prop.default", null},
                            {"vendor/default.prop", null},
                            {"vendor/etc/init/hw/init.goldfish.rc", null},
                            {"system/etc/init", null},
                        };
                        for (String[] check : checks) {
                            File f = new File(rootfsDir, check[0]);
                            String status;
                            if (!f.exists()) status = "MISSING";
                            else if (f.isDirectory()) status = "DIR/";
                            else status = "FILE(" + f.length() + ")";
                            sb.append(check[0]).append(": ").append(status).append("\n");
                        }

                        final String rootfsInfo = sb.toString();
                        runOnUiThread(() -> {
                            mLoadingText.setTextSize(9);
                            mLoadingText.setText(rootfsInfo);
                        });
                    } catch (Throwable t) {
                        runOnUiThread(() -> mLoadingText.setText("rootfs check error: " + t.getMessage()));
                    }

                    // Show boot log too
                    try {
                        File logFile = new File("/data/data/io.twoyi/log.txt");
                        if (logFile.exists() && logFile.canRead()) {
                            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(logFile));
                            StringBuilder logSb = new StringBuilder("\n=== INIT LOG ===\n");
                            String line;
                            int lineCount = 0;
                            while ((line = br.readLine()) != null && lineCount < 40) {
                                logSb.append(line).append("\n");
                                lineCount++;
                            }
                            br.close();
                            final String logContent = logSb.toString();
                            runOnUiThread(() -> {
                                String current = mLoadingText.getText().toString();
                                mLoadingText.setText(current + logContent);
                            });
                        }
                    } catch (Throwable ignored) {}

                    mIsExtracting.set(false);
                    runOnUiThread(() -> {});

                    runOnUiThread(() -> {
                        if (mSurfaceView.getParent() != null) {
                            ((ViewGroup) mSurfaceView.getParent()).removeView(mSurfaceView);
                        }
                        mRootView.addView(mSurfaceView, 0);
                        showBootingProcedure(true);
                    });
                } catch (Throwable t) {
                    final String err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    runOnUiThread(() -> mLoadingText.setText("Step 2: UNEXPECTED ERROR:\n" + err));
                    mIsExtracting.set(false);
                }
            }, "extract-rom").start();
        } else {
            runOnUiThread(() -> mLoadingText.setText("Step 2: ROM exists, skip extract"));

            if (mSurfaceView.getParent() != null) {
                ((ViewGroup) mSurfaceView.getParent()).removeView(mSurfaceView);
            }
            mRootView.addView(mSurfaceView, 0);
            showBootingProcedure(false);
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

    private void showBootingProcedure(boolean coldBoot) {
        mLoadingText.setVisibility(View.VISIBLE);
        mLoadingText.setText(coldBoot ? "Booting (cold start)..." : "Booting...");
        mBootLogView.setVisibility(View.VISIBLE);

        int timeoutSeconds = 180;

        new Thread(() -> {
            boolean success = false;
            int elapsed = 0;
            int pollInterval = 5;

            try {
                while (elapsed < timeoutSeconds) {
                    if (TwoyiStatusManager.getInstance().isStarted()) {
                        success = true;
                        break;
                    }

                    if (elapsed % pollInterval == 0 && elapsed > 0) {
                        if (!isContainerAlive()) {
                            Log.e(TAG, "container process died at " + elapsed + "s");
                            break;
                        }
                    }

                    // Fallback: 如果 system_server 在运行，说明 boot 实际已完成
                    // （BOOT_COMPLETED 消息可能因 init 卡在 post-fs-data 而未发送）
                    if (elapsed >= 60 && elapsed % 10 == 0) {
                        if (isSystemServerRunning()) {
                            Log.i(TAG, "system_server detected at " + elapsed + "s, treating as boot success");
                            success = true;
                            break;
                        }
                    }

                    final int sec = elapsed;
                    runOnUiThread(() -> mLoadingText.setText("Booting... " + sec + "s / " + timeoutSeconds + "s"));

                    Thread.sleep(pollInterval * 1000);
                    elapsed += pollInterval;
                }

                if (!TwoyiStatusManager.getInstance().isStarted() && !success) {
                    success = TwoyiStatusManager.getInstance().waitBoot(5, TimeUnit.SECONDS);
                }
            } catch (Throwable ignored) {
            }

            if (!success) {
                mBootFailCount.incrementAndGet();
                LogEvents.trackBootFailure(getApplicationContext());
                Log.e(TAG, "boot timeout at " + elapsed + "s, attempt " + mBootFailCount.get() + "/3");

                runOnUiThread(() -> mLoadingText.setText("Boot timeout, collecting diagnostics..."));

                final String diagnosticInfo = collectDiagnosticInfo();
                dumpBootFailureLogs("boot_fail");

                final boolean shouldRetry = mBootFailCount.get() <= 3;
                runOnUiThread(() -> {
                    mLoadingView.stopAnimation();
                    mLoadingText.setText(diagnosticInfo);
                    mLoadingText.setTextSize(10);
                });

                if (shouldRetry) {
                    mRootView.postDelayed(() -> {
                        TwoyiStatusManager.getInstance().reset();
                        runOnUiThread(() -> {
                            mLoadingView.startAnimation();
                            mLoadingText.setText("Retrying boot...");
                            mLoadingText.setTextSize(12);
                        });
                        bootSystem();
                    }, 10000);
                }
                return;
            }

            Log.i(TAG, "boot completed in " + elapsed + "s");
            runOnUiThread(() -> {
                mLoadingView.stopAnimation();
                mLoadingLayout.setVisibility(View.GONE);
            });

            // 即使 boot 成功也保存诊断日志（黑屏时用于定位 surfaceflinger/GPU 问题）
            try {
                new Thread(() -> {
                    String p = dumpBootFailureLogs("boot_ok");
                    Log.i(TAG, "boot_ok diagnostic: " + p);
                }, "boot-ok-diagnostic").start();
            } catch (Throwable ignored) {
            }
        }, "waiting-boot").start();
    }

    /**
     * 检查容器 init 进程是否仍在运行。
     * 如果容器崩溃，立即失败而不是等待完整超时。
     */
    private boolean isContainerAlive() {
        try {
            File logFile = new File(getDataDir(), "log.txt");
            if (!logFile.exists()) {
                // log.txt 还没创建，init 可能还在启动中
                return true;
            }

            long now = SystemClock.uptimeMillis();
            long lastModified = logFile.lastModified();

            // log.txt 超过 90 秒没有更新 → 可能卡死
            if (now - lastModified > 90_000) {
                boolean found = isInitProcessRunning();
                if (!found) {
                    Log.e(TAG, "container init process not found, log.txt stale for " + (now - lastModified) / 1000 + "s");
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 通过 /proc 扫描容器 init 进程。
     */
    private boolean isInitProcessRunning() {
        try {
            File procDir = new File("/proc");
            File[] entries = procDir.listFiles();
            if (entries == null) return false;
            for (File entry : entries) {
                String name = entry.getName();
                if (!name.matches("\\d+")) continue;
                try {
                    File cmdlineFile = new File(entry, "cmdline");
                    if (!cmdlineFile.exists() || !cmdlineFile.canRead()) continue;
                    byte[] buf = new byte[512];
                    int len;
                    try (FileInputStream fis = new FileInputStream(cmdlineFile)) {
                        len = fis.read(buf);
                    }
                    if (len <= 0) continue;
                    String cmdline = new String(buf, 0, len);
                    if (cmdline.contains("rootfs/init")
                            || cmdline.contains("./init")
                            || cmdline.contains("libtwoyi_init")
                            || cmdline.contains("\0init\0")
                            || cmdline.equals("init")) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 检测容器内 system_server 是否在运行。
     * 作为 BOOT_COMPLETED 消息的备用检测机制。
     */
    private boolean isSystemServerRunning() {
        try {
            File procDir = new File("/proc");
            File[] entries = procDir.listFiles();
            if (entries == null) return false;
            for (File entry : entries) {
                String name = entry.getName();
                if (!name.matches("\\d+")) continue;
                try {
                    File cmdlineFile = new File(entry, "cmdline");
                    if (!cmdlineFile.exists() || !cmdlineFile.canRead()) continue;
                    byte[] buf = new byte[512];
                    int len;
                    try (FileInputStream fis = new FileInputStream(cmdlineFile)) {
                        len = fis.read(buf);
                    }
                    if (len <= 0) continue;
                    String cmdline = new String(buf, 0, len);
                    if (cmdline.contains("system_server")) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String collectDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("twoyi boot failure #").append(mBootFailCount.get()).append("\n\n");

            // Device info
            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n\n");

            // nativeLibDir
            try {
                String nativeLibDir = getApplicationInfo().nativeLibraryDir;
                sb.append("nativeLibDir:\n  ").append(nativeLibDir).append("\n");
                sb.append("  libtwoyi.so: ").append(new File(nativeLibDir, "libtwoyi.so").exists() ? "OK" : "MISSING").append("\n");
                sb.append("  libloader.so: ").append(new File(nativeLibDir, "libloader.so").exists() ? "OK" : "MISSING").append("\n");
                File initSo = new File(nativeLibDir, "libtwoyi_init.so");
                sb.append("  libtwoyi_init.so: ").append(initSo.exists() ? "OK (" + initSo.length() + "b, exec=" + initSo.canExecute() + ")" : "MISSING").append("\n");
            } catch (Throwable t) {
                sb.append("nativeLibDir error: ").append(t.getMessage()).append("\n");
            }
            sb.append("\n");

            // rootfs/init
            try {
                File rootfsDir = RomManager.getRootfsDir(getApplicationContext());
                sb.append("rootfsDir: ").append(rootfsDir.getAbsolutePath()).append("\n");
                File initFile = new File(rootfsDir, "init");
                sb.append("init exists: ").append(initFile.exists()).append("\n");
                if (initFile.exists()) {
                    sb.append("init size: ").append(initFile.length()).append("b");
                    sb.append(" exec=").append(initFile.canExecute());
                    sb.append(" read=").append(initFile.canRead()).append("\n");
                    try (FileInputStream fis = new FileInputStream(initFile)) {
                        byte[] magic = new byte[4];
                        if (fis.read(magic) == 4) {
                            boolean elf = magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
                            sb.append("init ELF: ").append(elf ? "valid" : "NOT ELF").append("\n");
                        }
                    }
                }
                for (String name : new String[]{"rom.ini", "system", "vendor", "data"}) {
                    sb.append(name).append(": ").append(new File(rootfsDir, name).exists() ? "OK" : "MISSING").append("  ");
                }
                sb.append("\n");
            } catch (Throwable t) {
                sb.append("rootfs error: ").append(t.getMessage()).append("\n");
            }
            sb.append("\n");

            // container log.txt
            try {
                File containerLog = new File(getDataDir(), "log.txt");
                if (containerLog.exists()) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(containerLog)))) {
                        String line;
                        int count = 0;
                        while ((line = reader.readLine()) != null && count < 30) {
                            sb.append(line).append("\n");
                            count++;
                        }
                        if (count >= 30) sb.append("... (more in file)\n");
                    }
                } else {
                    sb.append("log.txt: NOT FOUND\n");
                }
            } catch (Throwable t) {
                sb.append("log.txt error: ").append(t.getMessage()).append("\n");
            }
            sb.append("\n");

            // init process
            sb.append("init process running: ").append(isInitProcessRunning()).append("\n");

        } catch (Throwable t) {
            sb.append("DIAGNOSTIC ERROR: ").append(t.getMessage());
        }
        return sb.toString();
    }

    private String dumpBootFailureLogs(String prefix) {
        String savedPath = null;
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("=== twoyi boot failure log ===\n");
            sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
            sb.append("device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            @SuppressWarnings("deprecation")
            String[] abis = new String[]{Build.CPU_ABI, Build.CPU_ABI2};
            StringBuilder abiStr = new StringBuilder();
            for (String abi : abis) {
                if (abi != null && !abi.isEmpty()) {
                    if (abiStr.length() > 0) abiStr.append(", ");
                    abiStr.append(abi);
                }
            }
            sb.append("abi: ").append(abiStr.length() > 0 ? abiStr.toString() : "unknown").append("\n");
            sb.append("fingerprint: ").append(Build.FINGERPRINT).append("\n");
            sb.append("retry: ").append(mBootRetryCount).append("/3\n\n");

            // nativeLibDir
            sb.append("=== nativeLibDir ===\n");
            try {
                String nativeLibDir = getApplicationInfo().nativeLibraryDir;
                sb.append("path: ").append(nativeLibDir).append("\n");
                File libtwoyi = new File(nativeLibDir, "libtwoyi.so");
                sb.append("libtwoyi.so: ").append(libtwoyi.exists() ? "OK (" + libtwoyi.length() + ")" : "MISSING").append("\n");
                File libloader = new File(nativeLibDir, "libloader.so");
                sb.append("libloader.so: ").append(libloader.exists() ? "OK (" + libloader.length() + ")" : "MISSING").append("\n");
                File twoyiInit = new File(nativeLibDir, "libtwoyi_init.so");
                sb.append("libtwoyi_init.so: ").append(twoyiInit.exists() ? "OK (" + twoyiInit.length() + ", exec=" + twoyiInit.canExecute() + ")" : "MISSING").append("\n");
            } catch (Throwable t) {
                sb.append("nativeLibDir error: ").append(t.getMessage()).append("\n");
            }
            sb.append("\n");

            // rootfs/init
            sb.append("=== rootfs/init check ===\n");
            try {
                File rootfsDir = RomManager.getRootfsDir(getApplicationContext());
                sb.append("rootfsDir: ").append(rootfsDir.getAbsolutePath()).append("\n");
                File initFile = new File(rootfsDir, "init");
                sb.append("init exists: ").append(initFile.exists()).append("\n");
                if (initFile.exists()) {
                    sb.append("init size: ").append(initFile.length()).append("\n");
                    sb.append("init canRead: ").append(initFile.canRead()).append("\n");
                    sb.append("init canExecute: ").append(initFile.canExecute()).append("\n");
                    try {
                        String canonical = initFile.getCanonicalPath();
                        String absolute = initFile.getAbsolutePath();
                        sb.append("init isSymlink: ").append(!canonical.equals(absolute)).append("\n");
                        if (!canonical.equals(absolute)) {
                            sb.append("init symlinkTarget: ").append(canonical).append("\n");
                        }
                    } catch (Throwable t) {
                        sb.append("init symlink check error: ").append(t.getMessage()).append("\n");
                    }
                    try (FileInputStream fis = new FileInputStream(initFile)) {
                        byte[] magic = new byte[4];
                        if (fis.read(magic) == 4) {
                            sb.append("init ELF: ").append(String.format("%02x%02x%02x%02x", magic[0], magic[1], magic[2], magic[3]))
                                    .append(magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F' ? " (valid ELF)" : " (NOT ELF)").append("\n");
                        }
                    }
                }
            } catch (Throwable t) {
                sb.append("rootfs check error: ").append(t.getMessage()).append("\n");
            }
            sb.append("\n");

            // 容器文件
            sb.append("=== container files ===\n");
            try {
                File rootfsDir = RomManager.getRootfsDir(getApplicationContext());
                for (String name : new String[]{"init", "rom.ini", "system", "vendor", "data"}) {
                    File f = new File(rootfsDir, name);
                    if (!f.exists()) {
                        sb.append(name).append(": MISSING\n");
                    } else if (f.isFile() && (name.equals("vendor") || name.equals("system") || name.equals("data"))) {
                        sb.append(name).append(": CONFLICT (is FILE, should be DIR!)\n");
                    } else {
                        sb.append(name).append(": OK").append(f.isDirectory() ? " (dir)" : " (file)").append("\n");
                    }
                }
            } catch (Throwable t) {
                sb.append("error: ").append(t.getMessage()).append("\n");
            }
            sb.append("\n");

            // EGL/GLES 驱动诊断
            sb.append("=== EGL/GLES diagnostics ===\n");
            try {
                File rootfsDir = RomManager.getRootfsDir(getApplicationContext());
                // 检查 egl 目录
                for (String eglPath : new String[]{
                        "system/lib64/egl",
                        "vendor/lib64/egl",
                        "vendor/lib/egl"
                }) {
                    File eglDir = new File(rootfsDir, eglPath);
                    if (!eglDir.exists()) {
                        sb.append(eglPath).append(": NOT FOUND\n");
                    } else if (eglDir.isFile()) {
                        sb.append(eglPath).append(": IS FILE (conflict!)\n");
                    } else {
                        File[] files = eglDir.listFiles();
                        sb.append(eglPath).append(": dir, ").append(files != null ? files.length : 0).append(" files\n");
                        if (files != null) {
                            for (File f : files) {
                                sb.append("  ").append(f.getName()).append(" (").append(f.length()).append("b)\n");
                            }
                        }
                    }
                }
                // 检查关键 GPU 库
                for (String libPath : new String[]{
                        "system/lib64/libEGL.so",
                        "system/lib64/libGLESv2.so",
                        "system/lib64/libGLESv1_CM.so",
                        "system/lib64/egl/libGLES_android.so",
                        "system/lib64/egl/libEGL_goldfish.so",
                        "system/lib64/egl/libGLESv2_goldfish.so"
                }) {
                    File libFile = new File(rootfsDir, libPath);
                    if (libFile.exists()) {
                        sb.append(libPath).append(": OK (").append(libFile.length()).append("b)\n");
                    }
                }
                // 检查 vendor/default.prop 内容
                File propFile = new File(rootfsDir, "vendor/default.prop");
                if (propFile.exists()) {
                    sb.append("--- vendor/default.prop ---\n");
                    try (BufferedReader pr = new BufferedReader(new InputStreamReader(new FileInputStream(propFile)))) {
                        String pl;
                        while ((pl = pr.readLine()) != null) {
                            sb.append(pl).append("\n");
                        }
                    }
                } else {
                    sb.append("vendor/default.prop: NOT FOUND\n");
                }
            } catch (Throwable t) {
                sb.append("EGL diagnostics error: ").append(t.getMessage()).append("\n");
            }
            sb.append("\n");

            // 容器 log.txt
            sb.append("=== container log.txt ===\n");
            try {
                File containerLog = new File(getDataDir(), "log.txt");
                if (containerLog.exists()) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(containerLog)))) {
                        String line;
                        int count = 0;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append("\n");
                            if (++count > 500) { sb.append("... (truncated)\n"); break; }
                        }
                    }
                } else {
                    sb.append("(log.txt not found - init never started logging)\n");
                }
            } catch (Throwable t) {
                sb.append("log.txt read error: ").append(t.getMessage()).append("\n");
            }
            sb.append("\n");

            // logcat
            sb.append("=== logcat (io.twoyi) ===\n");
            appendCmdOutput(sb, new String[]{"logcat", "-d", "-v", "time"}, "io.twoyi");
            sb.append("\n");

            sb.append("=== logcat (crash/fatal) ===\n");
            appendCmdOutput(sb, new String[]{"logcat", "-d", "-b", "crash", "-v", "time"}, null);
            sb.append("\n");

            // SELinux denials (logcat only, no dmesg - Android 16 seccomp may kill process)
            sb.append("=== SELinux denials ===\n");
            appendCmdOutput(sb, new String[]{"logcat", "-d", "-b", "events"}, "avc:");
            sb.append("\n");
        } catch (Throwable e) {
            Log.e(TAG, "dumpBootFailureLogs build error", e);
            sb.append("\nBUILD ERROR: ").append(e.getMessage()).append("\n");
        }

        // ALWAYS try to save even if partial
        try {
            String content = sb.toString();
            if (content.length() > 0) {
                savedPath = saveLogForUser(prefix + "_" + ts + ".txt", content);
                Log.i(TAG, "boot failure log: " + (savedPath != null ? savedPath : "save failed"));
            }
        } catch (Throwable e) {
            Log.e(TAG, "saveLogForUser failed", e);
        }
        return savedPath != null ? savedPath : "save failed";
    }

    /**
     * 保存日志：先尝试公共 Downloads，再回退到应用内部目录（始终可用）。
     */
    private String saveLogForUser(String fileName, String content) {
        // 方案 1：应用内部目录（不需要任何权限，始终可用）
        try {
            File internalFile = new File(getFilesDir(), fileName);
            try (FileOutputStream fos = new FileOutputStream(internalFile)) {
                fos.write(content.getBytes("UTF-8"));
                Log.i(TAG, "log saved to internal: " + internalFile.getAbsolutePath());
                // 同时尝试复制到公共 Downloads（不阻塞主流程）
                tryCopyToDownloads(fileName, content);
                return internalFile.getAbsolutePath();
            }
        } catch (Throwable t) {
            Log.w(TAG, "internal save failed", t);
        }
        // 方案 2：直接尝试公共 Downloads（fallback）
        return tryCopyToDownloads(fileName, content);
    }

    private String tryCopyToDownloads(String fileName, String content) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                ContentResolver resolver = getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = resolver.openOutputStream(uri)) {
                        if (os != null) {
                            os.write(content.getBytes("UTF-8"));
                            os.flush();
                        }
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);
                    Log.i(TAG, "log saved to MediaStore Downloads: " + uri);
                    return "Downloads/" + fileName;
                }
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore save failed", t);
            }
        } else {
            try {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
                    File pubFile = new File(downloadsDir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(pubFile)) {
                        fos.write(content.getBytes("UTF-8"));
                    }
                    return pubFile.getAbsolutePath();
                }
            } catch (Throwable t) {
                Log.w(TAG, "public Downloads save failed", t);
            }
        }
        return null;
    }

    private static void appendCmdOutput(StringBuilder sb, String[] cmd) {
        appendCmdOutput(sb, cmd, null);
    }

    private static void appendCmdOutput(StringBuilder sb, String[] cmd, String filterContains) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null) {
                    if (filterContains != null && !line.contains(filterContains)) {
                        continue;
                    }
                    sb.append(line).append("\n");
                    if (++count > 500) {
                        sb.append("... (truncated)\n");
                        break;
                    }
                }
            }
            p.waitFor();
        } catch (Throwable e) {
            sb.append("(failed: ").append(e.getMessage()).append(")\n");
            // Android 16 seccomp may kill subprocesses (e.g. dmesg) — don't let that crash the diagnostic
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {}
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
        Renderer.handleTouch(event);
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

    @SuppressWarnings("deprecation")
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
