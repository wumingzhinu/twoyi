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
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.cleveroad.androidmanimation.LoadingAnimationView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
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
        boolean romExist = RomManager.romExist(this);
        boolean factoryRomUpdated = RomManager.needsUpgrade(this);
        boolean forceInstall = AppKV.getBooleanConfig(getApplicationContext(), AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        boolean use3rdRom = AppKV.getBooleanConfig(getApplicationContext(), AppKV.SHOULD_USE_THIRD_PARTY_ROM, false);

        boolean shouldExtractRom = !romExist || forceInstall || (!use3rdRom && factoryRomUpdated);

        if (shouldExtractRom) {
            Log.i(TAG, "extracting rom...");

            showTipsForFirstBoot();

new Thread(() -> {
                mIsExtracting.set(true);
                boolean extractSuccess = false;
                try {
                    extractSuccess = RomManager.extractRootfs(getApplicationContext(), romExist, factoryRomUpdated, forceInstall, use3rdRom);
                    if (extractSuccess) {
                        RomManager.initRootfs(getApplicationContext());
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "extract rootfs error", e);
                }
                mIsExtracting.set(false);

                if (!extractSuccess) {
                    // 解压失败，给出明确错误提示，避免无限重试
                    runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                            R.string.boot_failed, Toast.LENGTH_LONG).show());
                    return;
                }

                runOnUiThread(() -> {
                    if (mSurfaceView.getParent() != null) {
                        ((ViewGroup) mSurfaceView.getParent()).removeView(mSurfaceView);
                    }
                    mRootView.addView(mSurfaceView, 0);
                    showBootingProcedure(true);
                });
            }, "extract-rom").start();
        } else {
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
        mLoadingText.setVisibility(View.GONE);
        mBootLogView.setVisibility(View.VISIBLE);

        // 自适应超时：冷启动（首次解压后）给更长时间
        int timeoutSeconds = coldBoot ? 180 : 60;

        new Thread(() -> {
            boolean success = false;
            int elapsed = 0;
            int pollInterval = 5; // 每5秒检查一次容器健康状态

            try {
                while (elapsed < timeoutSeconds) {
                    if (TwoyiStatusManager.getInstance().isStarted()) {
                        success = true;
                        break;
                    }

                    if (elapsed % pollInterval == 0 && elapsed > 0) {
                        // 监控容器进程：如果 init 已死，立即失败
                        if (!isContainerAlive()) {
                            Log.e(TAG, "container process died at " + elapsed + "s");
                            break;
                        }
                    }

                    Thread.sleep(pollInterval * 1000);
                    elapsed += pollInterval;
                }

                if (!TwoyiStatusManager.getInstance().isStarted() && !success) {
                    // 最后一次尝试：短等
                    success = TwoyiStatusManager.getInstance().waitBoot(5, TimeUnit.SECONDS);
                }
            } catch (Throwable ignored) {
            }

            if (!success) {
                LogEvents.trackBootFailure(getApplicationContext());
                dumpBootFailureLogs();

                runOnUiThread(() -> {
                    Toast.makeText(getApplicationContext(), R.string.boot_failed, Toast.LENGTH_LONG).show();
                    mRootView.postDelayed(() -> {
                        TwoyiStatusManager.getInstance().reset();
                        bootSystem();
                    }, 5000);
                });
                return;
            }

            Log.i(TAG, "boot completed in " + elapsed + "s");
            runOnUiThread(() -> {
                mLoadingView.stopAnimation();
                mLoadingLayout.setVisibility(View.GONE);
            });
        }, "waiting-boot").start();

        // 显示启动进度
        startBootProgressMonitor();
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
                // 检查 init 进程是否还在
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "ps -ef"});
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("rootfs/init") || line.contains("/init ")) {
                        reader.close();
                        p.waitFor();
                        return true;
                    }
                }
                reader.close();
                p.waitFor();
                Log.e(TAG, "container init process not found, log.txt stale for " + (now - lastModified) / 1000 + "s");
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /**
     * 后台监控容器 log.txt，显示启动进度到 UI。
     */
    private void startBootProgressMonitor() {
        new Thread(() -> {
            File logFile = new File(getDataDir(), "log.txt");
            long lastSize = 0;
            long lastUpdate = SystemClock.uptimeMillis();

            while (!TwoyiStatusManager.getInstance().isStarted()) {
                try {
                    Thread.sleep(3000);

                    if (!logFile.exists()) {
                        runOnUiThread(() -> mBootLogView.setText("Waiting for container..."));
                        continue;
                    }

                    long size = logFile.length();
                    if (size == lastSize && SystemClock.uptimeMillis() - lastUpdate > 10_000) {
                        // 超过 10 秒无新日志
                        runOnUiThread(() -> mBootLogView.setText("Container idle, waiting..."));
                        continue;
                    }

                    if (size > lastSize) {
                        // 读取最后几行
                        String tail = readLogTail(logFile, 5);
                        runOnUiThread(() -> mBootLogView.setText(tail));
                        lastSize = size;
                        lastUpdate = SystemClock.uptimeMillis();
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "boot progress monitor error", e);
                }
            }
        }, "boot-progress").start();
    }

    private String readLogTail(File file, int maxLines) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append("\n");
            }
            return sb.toString().trim();
        } catch (Throwable e) {
            return "(cannot read log)";
        }
    }

    private void dumpBootFailureLogs() {
        new Thread(() -> {
            try {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File appDir = getFilesDir();
                File logDir = new File(appDir, "boot_logs");
                logDir.mkdirs();
                File outFile = new File(logDir, "boot_fail_" + ts + ".txt");

                StringBuilder sb = new StringBuilder();
                sb.append("=== twoyi boot failure log ===\n");
                sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
                sb.append("device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
                sb.append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");

                // 容器日志（最关键）
                sb.append("=== container log.txt ===\n");
                File containerLog = new File(getDataDir(), "log.txt");
                if (containerLog.exists()) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(containerLog)))) {
                        String line;
                        int count = 0;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append("\n");
                            if (++count > 1000) {
                                sb.append("... (truncated at 1000 lines)\n");
                                break;
                            }
                        }
                    }
                } else {
                    sb.append("(log.txt not found — container never started)\n");
                }
                sb.append("\n");

                // logcat: io.twoyi 相关
                sb.append("=== logcat (io.twoyi) ===\n");
                appendCmdOutput(sb, new String[]{
                        "logcat", "-d", "-v", "time",
                        "-s", "io.twoyi:*", "RomManager:*", "TwoyiStatus:*", "TwoyiSocketServer:*",
                        "Render2Activity:*", "Renderer:*"
                });
                sb.append("\n");

                // logcat: native crash / init / linker
                sb.append("=== logcat (native/init/linker) ===\n");
                appendCmdOutput(sb, new String[]{
                        "logcat", "-d", "-v", "time",
                        "-s", "DEBUG:*", "linker:*", "init:*", "libc:*"
                });
                sb.append("\n");

                // 检查容器关键文件是否存在
                sb.append("=== container files check ===\n");
                File rootfsDir = RomManager.getRootfsDir(getApplicationContext());
                for (String name : new String[]{"init", "libloader.so", "rom.ini", "system", "vendor"}) {
                    File f = new File(rootfsDir, name);
                    sb.append(name).append(": ").append(f.exists() ? "OK" : "MISSING").append("\n");
                }
                sb.append("\n");

                // 检查 socket 目录
                File socketDir = new File(getDataDir(), "socket");
                sb.append("socket dir: ").append(socketDir.exists() ? "OK" : "MISSING").append("\n");

                // 写入 app 私有目录（不需要权限）
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(sb.toString().getBytes());
                }

                // 同时尝试复制到外部存储（方便用户导出）
                File externalOut = new File(Environment.getExternalStorageDirectory(),
                        "twoyi_boot_log_" + ts + ".txt");
                try (FileOutputStream fos = new FileOutputStream(externalOut)) {
                    fos.write(sb.toString().getBytes());
                } catch (Throwable ignored) {
                }

                String msg = "日志: " + outFile.getAbsolutePath();
                Log.i(TAG, msg);
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
            } catch (Throwable e) {
                Log.e(TAG, "dumpBootFailureLogs failed", e);
            }
        }, "dump-boot-log").start();
    }

    private static void appendCmdOutput(StringBuilder sb, String[] cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null) {
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
