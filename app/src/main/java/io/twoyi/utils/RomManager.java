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

        // GPU 相关属性：使用原版 goldfish 仿真 GPU 驱动
        // EGL loader 在 sphal 命名空间 (/vendor/lib64/egl) 中查找
        // libEGL_${ro.hardware.egl}.so，即 vendor/lib64/egl/libEGL_emulation.so。
        // 该驱动通过 qemu pipe/opengles socket 连接 app 侧 libOpenglRender 完成实际渲染。
        properties.setProperty("ro.hardware.egl", "emulation");

        // 关键：Android init 的 zygote-start 触发器依赖此属性
        // on nonencrypted && zygote-start → start zygote
        // 如果 ro.crypto.state 未设置，zygote 永远不会启动，boot 卡死
        properties.setProperty("ro.crypto.state", "unencrypted");

        try (Writer writer = new FileWriter(propFile)) {
            properties.store(writer, null);
        } catch (IOException ignored) {
        }

        createStubHalServices(context);
        restoreVendorLink(context);
        restoreEmulationGpu(context);

        // 修复：在 init.goldfish.rc 中添加 class_start core 触发器
        // init.rc 中 class_start core 被注释掉了，依赖 property 触发器
        // 但容器中 property 系统可能不工作，导致 surfaceflinger 永远不启动
        patchInitGoldfishRc(context);

        // 禁用没有真实硬件就会无限 crash-loop 的服务
        // keystore 需要 keymaster HAL → 没有 → crash → 重启 → crash 循环
        // 注意：audioserver 绝对不能禁用！system_server 的 AudioService 在
        // StartAudioService 阶段同步等待 audioserver 发布的 media.audio_policy
        // 服务（ServiceManager: Waiting for service media.audio_policy），
        // 每秒重试、永不超时 → boot 卡死在 90% 处（真机日志已确认）。
        // crash-loop 由 init 的重启退避控制，CPU 开销可接受；
        // 而 media.audio_policy 缺失是致命的 boot 阻塞。
        // mediaserver 可能依赖 audioserver
        disableCrashingServices(context);
        ensureAudioserverRc(context);
    }

    /**
     * 恢复可能被旧版本删除的 audioserver.rc。
     *
     * 旧版本曾把 audioserver.rc 当作 crash-loop 源删除，导致 system_server
     * 的 AudioService 永远等不到 media.audio_policy（boot 卡死在 90%）。
     * 用户设备上的 rootfs 是持久的，所以每次启动都要检查恢复。
     */
    public static void ensureAudioserverRc(Context context) {
        try {
            File rootfsDir = getRootfsDir(context);
            File rc = new File(rootfsDir, "system/etc/init/audioserver.rc");
            if (rc.exists()) {
                return;
            }
            File bin = new File(rootfsDir, "system/bin/audioserver");
            if (!bin.exists()) {
                Log.w(TAG, "ensureAudioserverRc: audioserver binary missing, cannot restore rc");
                return;
            }
            File parent = rc.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (Writer w = new FileWriter(rc)) {
                // 与 AOSP 8.1 audioserver.rc 一致（去掉 seclabel/writepid：
                // 容器里 cpuset 不可写，服务继承 init 的 SELinux 上下文）
                w.write("service audioserver /system/bin/audioserver\n");
                w.write("    class core\n");
                w.write("    user audioserver\n");
                w.write("    group audio camera drmrpc media\n");
                w.write("    ioprio rt 4\n");
            }
            Log.i(TAG, "restored audioserver.rc (was deleted by old version)");
        } catch (Throwable t) {
            Log.w(TAG, "ensureAudioserverRc failed", t);
        }
    }

    /**
     * 删除部分服务的 RC 文件，防止无限 crash 循环。
     *
     * 重要：audioserver 不在此列。system_server 的 AudioService 同步等待
     * media.audio_policy，audioserver 被禁用时 boot 永远卡死在 StartAudioService
     * （"Waiting for service media.audio_policy" 无限重试）。
     */
    private static void disableCrashingServices(Context context) {
        File rootfsDir = getRootfsDir(context);
        String[] toDisable = {
            "system/etc/init/keystore.rc",
            "system/etc/init/mediaserver.rc",
            "system/etc/init/mediametrics.rc",
            "system/etc/init/mediaextractor.rc",
        };
        for (String path : toDisable) {
            File f = new File(rootfsDir, path);
            if (f.exists()) {
                if (f.delete()) {
                    Log.i(TAG, "disabled " + path);
                } else {
                    // delete 失败时写入空文件覆盖
                    try { new FileWriter(f).close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    /**
     * 在 init.goldfish.rc 中添加 class_start core 触发器。
     *
     * init.rc 中 class_start core 被注释掉了，只在 on property:init.svc.servicemanager=running 时触发。
     * 但容器中 property 系统可能不工作，导致 surfaceflinger 永远不启动 → 黑屏。
     *
     * 修复：在 init.goldfish.rc 的 on post-fs-data 阶段直接执行 class_start core。
     */
    private static void patchInitGoldfishRc(Context context) {
        File rootfsDir = getRootfsDir(context);
        File goldfishRc = new File(rootfsDir, "init.goldfish.rc");
        if (!goldfishRc.exists()) {
            Log.w(TAG, "init.goldfish.rc not found, skipping patch");
            return;
        }

        try {
            String content = new String(java.nio.file.Files.readAllBytes(goldfishRc.toPath()));
            if (content.contains("class_start core")) {
                Log.i(TAG, "init.goldfish.rc already has class_start core");
                return;
            }
            // 在 on post-fs-data 阶段添加 class_start core
            // 如果文件没有 on post-fs-data，就追加一个新的
            if (content.contains("on post-fs-data")) {
                content = content.replace("on post-fs-data", "on post-fs-data\n    class_start core");
            } else {
                content = content + "\n\non post-fs-data\n    class_start core\n";
            }
            java.nio.file.Files.write(goldfishRc.toPath(), content.getBytes());
            Log.i(TAG, "Patched init.goldfish.rc: added class_start core");
        } catch (IOException e) {
            Log.e(TAG, "Failed to patch init.goldfish.rc", e);
        }
    }

    /**
     * 判断 7z 条目是否为符号链接。
     * commons-compress 1.26 的 SevenZArchiveEntry 无 isSymbolicLink() API，
     * 但 Windows 属性的高 16 位保留了 unix st_mode：0xA000 (S_IFLNK) 表示符号链接。
     * 实测 rootfs.7z 中 vendor 条目 attr=0xa1ff8020，即 mode=0xa1ff (S_IFLNK|0777)，
     * 内容即链接目标（如 "system/vendor"）。已用 commons-compress 1.26.0 对真实包验证。
     */
    private static boolean isSymlinkEntry(SevenZArchiveEntry entry) {
        try {
            return entry.getHasWindowsAttributes()
                    && ((entry.getWindowsAttributes() >>> 16) & 0xF000) == 0xA000;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readEntryAsString(SevenZFile zFile, SevenZArchiveEntry entry) {
        try {
            long size = entry.getSize();
            if (size <= 0 || size > 4096) {
                return null;
            }
            byte[] content = new byte[(int) size];
            int off = 0;
            while (off < content.length) {
                int n = zFile.read(content, off, content.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(content, 0, off).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 创建符号链接；若目标位置已存在（普通文件/旧链接）先删除。
     * 不递归删除 —— 破坏性删除交给调用方显式处理。
     */
    private static boolean createSymlinkReplacing(String target, File linkFile) {
        try {
            File parent = linkFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            linkFile.delete();
            android.system.Os.symlink(target, linkFile.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "createSymlinkReplacing failed: " + linkFile + " -> " + target, t);
            return false;
        }
    }

    /**
     * 修正 rootfs/vendor 为指向 system/vendor 的符号链接。
     *
     * rootfs.7z 中 vendor 是符号链接（rootfs/vendor -> system/vendor），所有真实
     * vendor 文件（GPU 驱动 egl/emulation、HAL rc、hw/ 等）都在 rootfs/system/vendor 下。
     * 旧版本解压时把该链接当成普通 13 字节文件写入，ensureCriticalDirs 又把
     * "vendor 文件" 删除后重建为空目录，导致容器内 /vendor 下没有任何驱动，
     * surfaceflinger 因 "couldn't find an OpenGL ES implementation" 无限崩溃。
     *
     * 升级修复：旧版本把 vendor 建成了真实目录（可能含 default.prop 等生成文件），
     * 本方法把其内容合并回 system/vendor 后替换为符号链接；已是正确链接则不动。
     */
    private static void restoreVendorLink(Context context) {
        try {
            File rootfsDir = getRootfsDir(context);
            File vendor = new File(rootfsDir, "vendor");
            File systemVendor = new File(rootfsDir, "system/vendor");
            if (!systemVendor.isDirectory()) {
                Log.w(TAG, "restoreVendorLink: system/vendor missing, cannot fix vendor link");
                return;
            }
            if (isSymlinkTo(vendor, systemVendor)) {
                Log.i(TAG, "restoreVendorLink: vendor link already OK");
                return;
            }
            if (vendor.exists() && vendor.isDirectory()) {
                // 旧版本 bug 产生的真实目录（内含 default.prop / stub rc 等 app 生成文件）。
                // 把内容合并进 system/vendor（目标已存在则保留目标），再替换为符号链接。
                if (!mergeDirInto(vendor, systemVendor) && listNonEmpty(vendor)) {
                    Log.w(TAG, "restoreVendorLink: vendor dir merge incomplete, leaving as-is");
                    return;
                }
                IOUtils.deleteDir(vendor);
            } else if (vendor.exists()) {
                vendor.delete();
            }
            android.system.Os.symlink("system/vendor", vendor.getAbsolutePath());
            Log.i(TAG, "restoreVendorLink: recreated vendor -> system/vendor");
        } catch (Throwable t) {
            Log.w(TAG, "restoreVendorLink failed", t);
        }
    }

    /**
     * 把 src 目录内容移动到 dst（递归）；dst 已存在的同名条目保留 dst 版本。
     * 返回是否全部移动成功（src 变空）。
     */
    private static boolean mergeDirInto(File src, File dst) {
        if (!dst.isDirectory() && !dst.mkdirs()) {
            return false;
        }
        boolean ok = true;
        File[] children = src.listFiles();
        if (children == null) return true;
        for (File child : children) {
            File target = new File(dst, child.getName());
            if (child.isDirectory() && target.isDirectory()) {
                ok &= mergeDirInto(child, target);
                if (listNonEmpty(child)) {
                    ok = false;
                    continue;
                }
            } else if (target.exists()) {
                continue; // 保留 dst 已有版本
            } else if (!child.renameTo(target)) {
                ok = false;
                continue;
            }
            if (child.isDirectory()) {
                IOUtils.deleteDir(child);
            } else {
                child.delete();
            }
        }
        return ok;
    }

    private static boolean isSymlinkTo(File link, File target) {
        try {
            if (!android.system.OsConstants.S_ISLNK(
                    android.system.Os.lstat(link.getAbsolutePath()).st_mode)) {
                return false;
            }
            return link.getCanonicalPath().equals(target.getCanonicalPath());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean listNonEmpty(File dir) {
        String[] children = dir.list();
        return children != null && children.length > 0;
    }

    /**
     * 恢复原版 goldfish 仿真 GPU 驱动架构。
     *
     * 原版（rootfs.7z, 2022）方案：vendor/lib64/egl/lib*_emulation.so 通过
     * qemu pipe/opengles socket 连接宿主侧 libOpenglRender（app 已内置），
     * 这就是 twoyi 的设计渲染路径。vendor 是符号链接，驱动实际位于
     * system/vendor/lib64/egl/。
     *
     * Android 8.1 EGL loader (Loader.cpp) 的加载顺序：
     *   1. load_driver("GLES") → 扫描 libGLES_*.so，但显式跳过 libGLES_android.so
     *      （"always skip the software renderer"），也找不到 libGLES.so → 失败
     *   2. load_driver("EGL") → dlopen 走 android_load_sphal_library，搜索路径
     *      /vendor/lib64/egl（sphal 命名空间）。
     *
     * 因此：
     *   - 驱动必须能在 /vendor/lib64/egl 下访问到（vendor -> system/vendor 链接有效）
     *   - ro.hardware.egl 必须是 emulation（匹配 libEGL_emulation.so）
     *   - system/lib64/egl/libEGL_android.so 等链接没有意义，还会遮蔽
     *     loader 的 lib*_ 匹配扫描，必须删除（libGLES_android.so 本体保留，
     *     是 rom 的软件回退渲染器）。
     */
    private static void restoreEmulationGpu(Context context) {
        File rootfsDir = getRootfsDir(context);

        // 1. 删除旧版本错误创建的 system/lib64/egl 符号链接/文件
        //    （loader 扫描 egl 目录时按 lib*_ 前缀匹配，这些假链接会干扰匹配）
        File eglDir = new File(rootfsDir, "system/lib64/egl");
        if (eglDir.isDirectory()) {
            String[] badLinks = {"libEGL_android.so", "libGLESv2_android.so", "libGLESv1_CM_android.so"};
            for (String name : badLinks) {
                File f = new File(eglDir, name);
                if (f.exists()) {
                    f.delete();
                    Log.i(TAG, "removed stale " + name);
                }
            }
            // libGLES_android.so 本体是 rom 的软件回退渲染器，必须保留；
            // 但若是旧版本创建的符号链接则删除（sphal 下无法加载且会干扰扫描）。
            File glesAndroid = new File(eglDir, "libGLES_android.so");
            try {
                int mode = android.system.Os.lstat(glesAndroid.getAbsolutePath()).st_mode;
                if (android.system.OsConstants.S_ISLNK(mode)) {
                    glesAndroid.delete();
                    Log.i(TAG, "removed stale libGLES_android.so symlink");
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. 确认 vendor/lib64/egl 仿真驱动存在（通过 vendor 符号链接）
        File vendorEgl = new File(rootfsDir, "vendor/lib64/egl");
        File[] drivers = vendorEgl.isDirectory() ? vendorEgl.listFiles() : null;
        int found = 0;
        if (drivers != null) {
            for (File d : drivers) {
                String n = d.getName();
                if (n.startsWith("libEGL_") || n.startsWith("libGLESv2_") || n.startsWith("libGLESv1_CM_")) {
                    found++;
                }
            }
        }
        if (found > 0) {
            Log.i(TAG, "restoreEmulationGpu: found " + found + " GL driver(s) in vendor/lib64/egl");
        } else {
            Log.w(TAG, "restoreEmulationGpu: no GL drivers under vendor/lib64/egl "
                    + "(rootfs too old or extraction incomplete)");
        }
    }

    /**
     * 创建缺失的 HAL 服务定义文件。
     * rootfs 的 vendor 目录是空的（rootfs.7z 中只有占位文件），
     * 但 audioserver/keystore 等服务依赖 audio-hal-2-0 等 HAL 服务。
     * 这些 HAL 服务不存在时，audioserver 崩溃-重启形成死循环，
     * 吃光 CPU 导致 boot 永远无法完成。
     *
     * 解决方案：在 vendor/etc/init/ 下创建 stub rc 文件，
     * 定义这些缺失的 HAL 服务使用 nativeLibDir 中的 ELF stub 二进制。
     * 不能用 /system/bin/sleep 因为它是 toybox 脚本，在 noexec 挂载下无法执行。
     */
    private static void createStubHalServices(Context context) {
        File vendorInitDir = new File(getVendorDir(context), "etc/init");
        if (!vendorInitDir.exists()) {
            vendorInitDir.mkdirs();
        }

        // 创建 hal_stub 符号链接：rootfs/system/bin/hal_stub -> nativeLibDir/libtwoyi_hal_stub.so
        // 这样 init 启动 service 时 loader 能拦截 execve 并正确加载 ELF 二进制
        try {
            ApplicationInfo ai = context.getApplicationInfo();
            File stubInLib = new File(ai.nativeLibraryDir, "libtwoyi_hal_stub.so");
            File stubInRootfs = new File(getRootfsDir(context), "system/bin/hal_stub");
            if (stubInLib.exists() && stubInLib.length() > 0) {
                stubInRootfs.delete();
                android.system.Os.symlink(stubInLib.getAbsolutePath(), stubInRootfs.getAbsolutePath());
                Log.i(TAG, "symlinked hal_stub -> " + stubInLib.getAbsolutePath());
            }
        } catch (Throwable t) {
            Log.w(TAG, "hal_stub symlink failed", t);
        }

        // audio-hal-2-0: audioserver 依赖此服务，缺失会导致崩溃循环
        File audioHalRc = new File(vendorInitDir, "audio-hal-2-0.rc");
        if (!audioHalRc.exists()) {
            try (Writer w = new FileWriter(audioHalRc)) {
                w.write("service audio-hal-2-0 /system/bin/hal_stub\n");
                w.write("    class hal\n");
                w.write("    user audio\n");
                w.write("    group audio\n");
            } catch (IOException ignored) {
            }
        }

        // keymaster 服务 stub (keystore 依赖)
        File keymasterRc = new File(vendorInitDir, "keymaster-3-0.rc");
        if (!keymasterRc.exists()) {
            try (Writer w = new FileWriter(keymasterRc)) {
                w.write("service keymaster-3-0 /system/bin/hal_stub\n");
                w.write("    class hal\n");
                w.write("    user system\n");
                w.write("    group system drmrpc\n");
            } catch (IOException ignored) {
            }
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
                    // Symbolic link entry: windows attributes low 16 bits carry
                    // the unix mode (0xA000 = S_IFLNK); content is the target.
                    // MUST be checked BEFORE the directory-skip below: on upgrade the
                    // link path may already exist as an empty dir (created by old
                    // ensureCriticalDirs), and that dir must be replaced by the link.
                    if (isSymlinkEntry(entry)) {
                        String linkTarget = readEntryAsString(zFile, entry);
                        if (linkTarget != null && !linkTarget.isEmpty()) {
                            if (createSymlinkReplacing(linkTarget, outFile)) {
                                Log.i(TAG, "symlinked " + entryName + " -> " + linkTarget);
                            } else {
                                Log.w(TAG, "symlink failed for " + entryName + " -> " + linkTarget);
                                lastExtractError = "Failed to create symlink: " + entryName;
                            }
                            continue;
                        }
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
        // 符号链接只 unlink 本身，不递归进目标（vendor -> system/vendor 等）
        try {
            int mode = android.system.Os.lstat(file.getAbsolutePath()).st_mode;
            if (android.system.OsConstants.S_ISLNK(mode)) {
                file.delete();
                return;
            }
        } catch (Throwable ignored) {
        }
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
