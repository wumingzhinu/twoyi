/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import com.topjohnwu.superuser.Shell;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

/**
 * @author weishu
 * @date 2021/10/22.
 */

public final class RomManager {

    private static final String TAG = "RomManager";

    private static final String ROOTFS_NAME = "rootfs.7z";

    private static final String ROM_INFO_FILE = "rom.ini";

    private static final String DEFAULT_INFO = "unknown";

    private static final String LOADER_FILE = "libloader.so";

    private static final String CUSTOM_ROM_FILE_NAME = "rootfs_3rd.7z";

    private RomManager() {
    }

    public static String lastExtractError = null;

    public static void initRootfs(Context context) {
        ensureCriticalDirs(context);
        File propFile = getVendorPropFile(context);
        String language = Locale.getDefault().getLanguage();
        String country = Locale.getDefault().getCountry();

        Properties properties = new Properties();

        properties.setProperty("persist.sys.language", language);
        properties.setProperty("persist.sys.country", country);

        TimeZone timeZone = TimeZone.getDefault();
        String timeZoneID = timeZone.getID();
        Log.i(TAG, "timezone: " + timeZoneID);
        properties.setProperty("persist.sys.timezone", timeZoneID);

        properties.setProperty("ro.sf.lcd_density", String.valueOf(DisplayMetrics.DENSITY_DEVICE_STABLE));

        // GPU 相关属性 - 尝试绕过 OpenGL ES 驱动缺失问题
        // 禁用 Zygote 的 OpenGL 预加载，避免因缺少 GPU 驱动而崩溃
        properties.setProperty("ro.zygote.disable_gl_preload", "true");

        try (Writer writer = new FileWriter(propFile)) {
            properties.store(writer, null);
        } catch (IOException ignored) {
        }
    }

    public static void ensureBootFiles(Context context) {
        // 关键目录已在 ensureLoaderReady 同步创建，这里只做非关键操作
        saveLastKmsg(context);
    }

    // 轻量级同步方法：准备所有容器启动必需的文件和目录
    public static void ensureLoaderReady(Context context) {
        try {
            createLoaderSymlink(context);
            ensureDir(new File(context.getDataDir(), "socket"));
            // 容器启动必需的目录（同步创建，避免竞态）
            ensureCriticalDirs(context);
            File devDir = new File(getRootfsDir(context), "rootfs/dev");
            ensureDir(new File(devDir, "input"));
            ensureDir(new File(devDir, "socket"));
            ensureDir(new File(devDir, "maps"));
            // 确保 init 在 Android 12+ 的 noexec 数据目录上仍然可执行
            ensureExecutableInNativeLib(context);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 确保 rootfs 中关键目录存在且为目录（非文件）。
     * rootfs.7z 中 'rootfs/vendor' 等可能是文件条目，会阻止后续子条目创建目录。
     * 每次启动前调用此方法修复。
     */
    private static void ensureCriticalDirs(Context context) {
        File rootfsRoot = new File(getRootfsDir(context), "rootfs");
        for (String dirName : new String[]{"vendor", "system", "data", "dev", "proc", "sys"}) {
            File dirFile = new File(rootfsRoot, dirName);
            if (dirFile.exists() && dirFile.isFile()) {
                Log.w(TAG, "CRITICAL: '" + dirName + "' is a FILE — deleting and creating as directory");
                dirFile.delete();
                dirFile.mkdirs();
            } else if (!dirFile.exists()) {
                dirFile.mkdirs();
            }
            if (dirFile.isDirectory()) {
                File[] children = dirFile.listFiles();
                Log.i(TAG, "CRITICAL: '" + dirName + "' OK (dir, " + (children != null ? children.length : 0) + " children)");
            } else {
                Log.e(TAG, "CRITICAL: '" + dirName + "' STILL not a directory!");
            }
        }
    }

    private static void createLoaderSymlink(Context context) {
        File loaderSymlinkFile = new File(context.getDataDir(), "loader64");
        String loaderSymlink = loaderSymlinkFile.getAbsolutePath();
        String loaderPath = getLoaderPath(context);
        try {
            loaderSymlinkFile.delete();
            android.system.Os.symlink(loaderPath, loaderSymlink);
        } catch (Exception e) {
            throw new RuntimeException("symlink loader failed.", e);
        }
    }

    private static void killOrphanProcess() {
        Shell shell = ShellUtil.newSh();
        shell.newJob().add("ps -ef | awk '{if($3==1) print $2}' | xargs kill -9").exec();
    }

    private static void saveLastKmsg(Context context) {
        File lastKmsgFile = LogEvents.getLastKmsgFile(context);
        File kmsgFile = LogEvents.getKmsgFile(context);
        try {
            if (kmsgFile.exists()) {
                IOUtils.copyFile(kmsgFile, lastKmsgFile);
                kmsgFile.delete();
            }
        } catch (IOException ignored) {
        }
    }

    public static class RomInfo {
        public String author = DEFAULT_INFO;
        public String version = DEFAULT_INFO;
        public String desc = DEFAULT_INFO;
        public String md5 = "";
        public long code = 0;

        @Override
        public String toString() {
            return "RomInfo{" +
                    "author='" + author + '\'' +
                    ", version='" + version + '\'' +
                    ", md5='" + md5 + '\'' +
                    ", code=" + code +
                    '}';
        }

        public boolean isValid() {
            return this != DEFAULT_ROM_INFO;
        }
    }

    public static final RomInfo DEFAULT_ROM_INFO = new RomInfo();

    public static boolean romExist(Context context) {
        File initFile = new File(getRootfsDir(context), "init");
        return initFile.exists() && initFile.length() > 0;
    }

    public static boolean needsUpgrade(Context context) {
        RomInfo currentRomInfo = getCurrentRomInfo(context);
        Log.i(TAG, "current rom: " + currentRomInfo);
        if (currentRomInfo.equals(DEFAULT_ROM_INFO)) {
            return true;
        }

        RomInfo romInfoFromAssets = getRomInfoFromAssets(context);
        Log.i(TAG, "asset rom: " + romInfoFromAssets);
        return romInfoFromAssets.code > currentRomInfo.code;
    }

    public static RomInfo getCurrentRomInfo(Context context) {
        File infoFile = new File(getRootfsDir(context), ROM_INFO_FILE);
        try (FileInputStream inputStream = new FileInputStream(infoFile)) {
            return getRomInfo(inputStream);
        } catch (Throwable e) {
            return DEFAULT_ROM_INFO;
        }
    }

    public static String getLoaderPath(Context context) {
        ApplicationInfo applicationInfo = context.getApplicationInfo();
        return new File(applicationInfo.nativeLibraryDir, LOADER_FILE).getAbsolutePath();
    }

    public static RomInfo getRomInfo(File rom) {
        try (SevenZFile zFile = new SevenZFile(rom)) {

            SevenZArchiveEntry entry;

            while ((entry = zFile.getNextEntry()) != null) {
                if (entry.getName().equals("rootfs/rom.ini")) {
                    byte[] content = new byte[(int) entry.getSize()];
                    zFile.read(content, 0, content.length);
                    ByteArrayInputStream bais = new ByteArrayInputStream(content);
                    return getRomInfo(bais);
                }
            }
        } catch (Throwable e) {
            LogEvents.trackError(e);
        }
        return DEFAULT_ROM_INFO;
    }

    public static RomInfo getRomInfoFromAssets(Context context) {
        AssetManager assets = context.getAssets();
        try (InputStream open = assets.open(ROM_INFO_FILE)) {
            return getRomInfo(open);
        } catch (Throwable ignored) {
        }
        return DEFAULT_ROM_INFO;
    }

    public static boolean extractRootfs(Context context, boolean romExist, boolean needsUpgrade, boolean forceInstall, boolean use3rdRom) {

        // force remove system dir to avoiding wired issues
        removeSystemPartition(context);
        removeVendorPartition(context);

        if (!romExist) {
            // first init
            return extractRootfsInAssets(context);
        }

        if (forceInstall) {
            if (use3rdRom) {
                // install 3rd rom
                boolean success = extract3rdRootfs(context);
                if (!success) {
                    showRootfsInstallationFailure(context);
                    return false;
                }
            } else {
                // factory reset!!
                if (!extractRootfsInAssets(context)) {
                    showRootfsInstallationFailure(context);
                    return false;
                }
            }

            // force install finish, reset the state.
            AppKV.setBooleanConfig(context, AppKV.FORCE_ROM_BE_RE_INSTALL, false);
        } else {
            if (use3rdRom) {
                Log.w(TAG, "WTF? 3rd ROM must be force install!");
            }
            if (needsUpgrade) {
                Log.i(TAG, "upgrade factory rom..");
                if (!extractRootfsInAssets(context)) {
                    showRootfsInstallationFailure(context);
                    return false;
                }
            }
        }
        return true;
    }

    private static void showRootfsInstallationFailure(Context context) {
        // TODO
    }

    public static void reboot(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        context.getApplicationContext().startActivity(intent);

        shutdown(context);
    }

    public static void shutdown(Context context) {
        System.exit(0);
        Process.killProcess(Process.myPid());
    }

    public static boolean extract3rdRootfs(Context context) {
        File rootfs3rd = get3rdRootfsFile(context);
        if (!rootfs3rd.exists()) {
            return false;
        }
        int err = extractRootfs(context, rootfs3rd);
        return err == 0;
    }

    public static int extractRootfs(Context context, File rootfs7z) {
        long startTime = SystemClock.elapsedRealtime();
        lastExtractError = null;
        int entryCount = 0;
        int skippedCount = 0;
        try (SevenZFile zFile = new SevenZFile(rootfs7z)) {
            SevenZArchiveEntry entry;
            File rootfsDir = context.getDataDir();
            byte[] buffer = new byte[64 * 1024]; // 增大缓冲区到 64KB，加快解压
            
            while ((entry = zFile.getNextEntry()) != null) {
                entryCount++;
                File outFile = new File(rootfsDir, entry.getName());
                boolean isDirEntry = entry.isDirectory();
                String entryName = entry.getName();

                // Resolve path conflicts: if an ancestor is a FILE but should be a DIR,
                // delete it. This handles rootfs.7z having e.g. 'rootfs/vendor' as a FILE
                // while 'rootfs/vendor/etc/...' entries need it to be a directory.
                File check = outFile.isDirectory() ? outFile : outFile.getParentFile();
                while (check != null && !check.equals(rootfsDir)) {
                    if (check.exists() && check.isFile()) {
                        Log.w(TAG, "Deleting ancestor file blocking path: " + check.getPath());
                        check.delete();
                    }
                    check = check.getParentFile();
                }

                if (isDirEntry) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    // If the target is now a DIRECTORY (from ancestor cleanup or prior extraction)
                    // but this entry is a FILE with a known directory name, skip it.
                    if (outFile.exists() && outFile.isDirectory()) {
                        Log.w(TAG, "Skipping file '" + entryName + "' — target is already a directory");
                        skippedCount++;
                        while (zFile.read(buffer) > 0) {}
                        continue;
                    }
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outFile), 64 * 1024)) {
                        int len;
                        while ((len = zFile.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
            }

            // Post-extraction safety: ensure critical directories exist
            // The 7z may have 'rootfs/vendor' as a FILE entry, and if there are no
            // 'rootfs/vendor/...' sub-entries, vendor would be missing as a directory.
            File rootfsRoot = new File(rootfsDir, "rootfs");
            for (String dirName : new String[]{"vendor", "system", "data", "dev", "proc", "sys"}) {
                File dirFile = new File(rootfsRoot, dirName);
                if (dirFile.exists() && dirFile.isFile()) {
                    Log.w(TAG, "POST-EXTRACT: '" + dirName + "' is a FILE — deleting and creating as directory");
                    dirFile.delete();
                    dirFile.mkdirs();
                } else if (!dirFile.exists()) {
                    Log.w(TAG, "POST-EXTRACT: '" + dirName + "' missing — creating directory");
                    dirFile.mkdirs();
                }
                if (dirFile.isDirectory()) {
                    File[] children = dirFile.listFiles();
                    Log.i(TAG, "POST-EXTRACT: '" + dirName + "' OK (dir, " + (children != null ? children.length : 0) + " children)");
                } else {
                    Log.e(TAG, "POST-EXTRACT: '" + dirName + "' STILL not a directory!");
                    lastExtractError = "Failed to create directory: rootfs/" + dirName;
                }
            }

            // Android 12+ 将应用数据目录挂载为 noexec，导致容器二进制无法执行。
            // 解决方案：将 init 复制到 nativeLibraryDir（允许执行），然后用符号链接替换原位置。
            ensureExecutableInNativeLib(context);

            Log.i(TAG, "extractRootfs done: " + entryCount + " entries (skipped " + skippedCount + ") in " + (SystemClock.elapsedRealtime() - startTime) + "ms");
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "extract rootfs failed at entry #" + entryCount, e);
            lastExtractError = "Failed at entry #" + entryCount + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
            return -1;
        }
    }

    /**
     * Android 12+ 对应用数据目录启用 W^X，noexec 挂载阻止执行其中的二进制。
     * 将 init 复制到 nativeLibraryDir 并用符号链接指回，使容器进程可以 exec。
     */
    /**
     * Android 12+ 对应用数据目录启用 W^X，noexec 挂载阻止执行其中的二进制。
     * 将 rootfs/init 替换为指向 nativeLibraryDir 中副本的符号链接，使容器进程可以 exec。
     *
* 同时处理两种来源：
     *   - APK 构建时已将 init 放入 jniLibs 作为 libtwoyi_init.so，系统安装自动提取到 nativeLibDir（推荐路径）
     *   - 运行时从 rootfs/init 尝试复制到 nativeLibDir（可能被 Android 11+ 沙箱阻止）
     */
    private static void ensureExecutableInNativeLib(Context context) {
        try {
            ApplicationInfo ai = context.getApplicationInfo();
            File nativeLibDir = new File(ai.nativeLibraryDir);
            File rootfsDir = getRootfsDir(context);
            File initInRootfs = new File(rootfsDir, "init");
            File initInLib = new File(nativeLibDir, "libtwoyi_init.so");

            // 确定 init 来源
            File initSource = null;
            // 优先使用 APK 安装的副本（系统提取，保证可执行）
            if (initInLib.exists() && initInLib.length() > 0) {
                initSource = initInLib;
                Log.i(TAG, "using APK-installed init: " + initInLib.getAbsolutePath());
            } else if (initInRootfs.exists() && initInRootfs.length() > 0) {
                // 尝试复制到 nativeLibDir（Android 11+ 写入此位置通常被沙箱阻止）
                try {
                    IOUtils.copyFile(initInRootfs, initInLib);
                    initInLib.setExecutable(true, false);
                    initInLib.setReadable(true, false);
                    if (initInLib.exists() && initInLib.length() > 0) {
                        initSource = initInLib;
                        Log.i(TAG, "copied init to nativeLibDir: " + initInLib.getAbsolutePath());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "cannot copy init to nativeLibDir, will try fallback", t);
                }
            }

            if (initSource == null) {
                Log.w(TAG, "no usable init found, boot may fail on Android 12+");
                return;
            }

            // 检查 rootfs/init 是否已经指向正确位置
            try {
                String cannonical = initInRootfs.getCanonicalPath();
                if (cannonical.equals(initSource.getAbsolutePath())) {
                    Log.i(TAG, "rootfs/init already at executable path");
                    return;
                }
                // 检测循环 symlink（指向自身）
                if (cannonical.equals(initInRootfs.getAbsolutePath())) {
                    Log.w(TAG, "circular symlink detected! forcing re-link");
                }
            } catch (Throwable t) {
                // file might not exist, proceed to create symlink
            }

            // 创建符号链接 rootfs/init -> 可执行位置
            try {
                initInRootfs.delete();
            } catch (Throwable ignored) {}
            try {
                android.system.Os.symlink(initSource.getAbsolutePath(), initInRootfs.getAbsolutePath());
                Log.i(TAG, "symlinked rootfs/init -> " + initSource.getAbsolutePath());
            } catch (Throwable t) {
                Log.e(TAG, "symlink creation failed", t);
            }

            // 验证 symlink 是否正确
            try {
                String verify = initInRootfs.getCanonicalPath();
                if (!verify.equals(initSource.getAbsolutePath())) {
                    Log.e(TAG, "symlink verification FAILED: got " + verify + " expected " + initSource.getAbsolutePath());
                } else {
                    Log.i(TAG, "symlink verified OK: " + verify);
                }
            } catch (Throwable t) {
                Log.e(TAG, "symlink verification error", t);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureExecutableInNativeLib failed", t);
        }
    }

    public static boolean extractRootfsInAssets(Context context) {

        // Delete old rootfs to avoid permission conflicts (e.g. init file locked)
        File rootfsDir = new File(context.getDataDir(), "rootfs");
        if (rootfsDir.exists()) {
            Log.i(TAG, "Deleting old rootfs directory before extraction");
            deleteRecursive(rootfsDir);
        }

        // read assets
        long t1 = SystemClock.elapsedRealtime();
        File rootfs7z = context.getFileStreamPath(ROOTFS_NAME);
        long copiedSize = 0;
        try (InputStream inputStream = new BufferedInputStream(context.getAssets().open(ROOTFS_NAME));
             OutputStream os = new BufferedOutputStream(new FileOutputStream(rootfs7z))) {
            byte[] buffer = new byte[10240];
            int count;
            while ((count = inputStream.read(buffer)) > 0) {
                os.write(buffer, 0, count);
                copiedSize += count;
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy rootfs.7z from assets", e);
        }
        long t2 = SystemClock.elapsedRealtime();

        Log.i(TAG, "rootfs.7z copied: " + copiedSize + " bytes in " + (t2 - t1) + "ms");

        if (copiedSize < 1000) {
            Log.e(TAG, "rootfs.7z too small (" + copiedSize + " bytes), likely LFS pointer");
            return false;
        }

        // Check magic bytes: 7z signature is 0x37 0x7A 0xBC 0xAF 0x27 0x1C
        try (FileInputStream fis = new FileInputStream(rootfs7z)) {
            byte[] header = new byte[6];
            int read = fis.read(header);
            if (read < 6) {
                Log.e(TAG, "rootfs.7z too small to read header");
                return false;
            }
            if (header[0] != 0x37 || header[1] != 0x7A || header[2] != (byte)0xBC || header[3] != (byte)0xAF) {
                Log.e(TAG, "rootfs.7z invalid magic: " + String.format("%02x %02x %02x %02x", header[0], header[1], header[2], header[3]));
                // Read first 200 bytes for debugging
                byte[] preview = new byte[200];
                fis.close();
                try (FileInputStream fis2 = new FileInputStream(rootfs7z)) {
                    int previewRead = fis2.read(preview);
                    String previewStr = new String(preview, 0, Math.min(previewRead, 200));
                    Log.e(TAG, "rootfs.7z content preview: " + previewStr);
                }
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to check rootfs.7z magic", e);
        }

        int ret = extractRootfs(context, rootfs7z);

        long t3 = SystemClock.elapsedRealtime();

        Log.i(TAG, "extract rootfs, read assets: " + (t2 - t1) + " un7z: " + (t3 - t2) + " ret: " + ret);

        return ret == 0;
    }

    public static File getRootfsDir(Context context) {
        return new File(context.getDataDir(), "rootfs");
    }

    public static File getRomSdcardDir(Context context) {
        return new File(getRootfsDir(context), "sdcard");
    }

    public static File getVendorDir(Context context) {
        return new File(getRootfsDir(context), "vendor");
    }

    public static File getVendorPropFile(Context context) {
        return new File(getVendorDir(context), "default.prop");
    }

    public static File get3rdRootfsFile(Context context) {
        return context.getFileStreamPath(CUSTOM_ROM_FILE_NAME);
    }

    public static boolean isAndroid12() {
        return Build.VERSION.PREVIEW_SDK_INT + Build.VERSION.SDK_INT == Build.VERSION_CODES.S;
    }

    private static void removePartition(Context context, String partition) {
        File rootfsDir = getRootfsDir(context);
        File systemDir = new File(rootfsDir, partition);

        IOUtils.deleteDirectory(systemDir);
    }

    private static void removeSystemPartition(Context context) {
        removePartition(context, "system");
    }

    private static void removeVendorPartition(Context context) {
        removePartition(context, "vendor");
    }

    private static RomInfo getRomInfo(InputStream in) {
        Properties prop = new Properties();
        try {
            prop.load(in);

            RomInfo info = new RomInfo();
            info.author = prop.getProperty("author");
            info.code = Long.parseLong(prop.getProperty("code"));
            info.version = prop.getProperty("version");
            info.desc = prop.getProperty("desc", DEFAULT_INFO);
            info.md5 = prop.getProperty("md5");
            return info;
        } catch (Throwable e) {
            Log.e(TAG, "read rom info err", e);
            return DEFAULT_ROM_INFO;
        }
    }

    private static void ensureDir(File file) {
        if (file.exists()) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        file.mkdirs();
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
