/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.utils;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent boot trace. Every gate of the boot pipeline writes here
 * synchronously, so a failed boot can be diagnosed even when the logcat
 * ring buffer has already evicted the lines (which happens within minutes
 * on busy devices). Mirrors to logcat as well.
 */
public final class BootTrace {

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final Object LOCK = new Object();
    private static File sFile;

    private BootTrace() {
    }

    public static void init(Context context) {
        sFile = new File(context.getFilesDir(), "boot-trace.txt");
        // fresh trace per app start
        try {
            if (sFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                sFile.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            sFile.createNewFile();
        } catch (Throwable ignored) {
        }
        write("BootTrace", "==== boot trace start, pid=" + Process.myPid() + " ====");
    }

    public static File file() {
        return sFile;
    }

    public static void i(String tag, String msg) {
        Log.i(tag, msg);
        write(tag, msg);
    }

    public static void w(String tag, String msg) {
        Log.w(tag, msg);
        write(tag, msg);
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
        write(tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        Log.e(tag, msg, t);
        write(tag, msg + " ex=" + t);
    }

    private static void write(String tag, String msg) {
        synchronized (LOCK) {
            if (sFile == null) {
                return;
            }
            try (FileWriter w = new FileWriter(sFile, true)) {
                w.write(FMT.format(new Date()) + " " + Process.myPid() + " " + tag + ": " + msg + "\n");
            } catch (Throwable ignored) {
            }
        }
    }
}
