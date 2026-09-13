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
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.twoyi.ui.SettingsActivity;
import io.twoyi.utils.IOUtils;
import io.twoyi.utils.UIHelper;

/**
 * @author weishu
 * @date 2021/10/27.
 */

public class TwoyiSocketServer {

    private static final String TAG = "TwoyiSocketServer";

    private static TwoyiSocketServer INSTANCE;

    private static final String SOCK_NAME = "TWOYI_SOCK";

    private static final String SWITCH_HOST = "SWITCH_HOST";
    private static final String BOOT_COMPLETED = "BOOT_COMPLETED";

    private static final String JUMP_HOST_SETTINGS= "SETTINGS";

    private static ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private final AtomicBoolean mStarted = new AtomicBoolean(false);
    private final Context mContext;

    private TwoyiSocketServer(Context context) {
        mContext = context;
    }

    public static TwoyiSocketServer getInstance(Context context) {
        if (INSTANCE == null) {
            INSTANCE = new TwoyiSocketServer(context);
        }

        return INSTANCE;
    }

    public void start() {
        if (mStarted.compareAndSet(false, true)) {
            EXECUTOR.submit(this::start0);

            EXECUTOR.submit(()-> {

                // some device restrict local socket, just connect it to prompt the permission dialog.
                SystemClock.sleep(3000);

                // SEND PING
                TwoyiMessenger.getInstance().send(TwoyiMessenger.PING);
            });
        }
    }

    private void start0() {
        LocalSocket socket = null;
        try {
            // 先尝试 SEQPACKET，失败则回退到 STREAM
            try {
                socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
                Log.i(TAG, "using SEQPACKET socket");
            } catch (Throwable e) {
                Log.w(TAG, "SEQPACKET not available, falling back to STREAM: " + e.getMessage());
                socket = new LocalSocket(LocalSocket.SOCKET_STREAM);
                Log.i(TAG, "using STREAM socket");
            }

            socket.bind(new LocalSocketAddress(SOCK_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            Log.i(TAG, "socket bound to @" + SOCK_NAME);
            LocalServerSocket localServerSocket = new LocalServerSocket(socket.getFileDescriptor());
            Log.i(TAG, "LocalServerSocket created, waiting for connections");

            Thread currentThread = Thread.currentThread();
            int acceptCount = 0;
            while (!currentThread.isInterrupted()) {
                LocalSocket localSocket = localServerSocket.accept();
                acceptCount++;
                Log.i(TAG, "accepted connection #" + acceptCount + " from " + localSocket);
                handleSocket(localSocket);
            }
        } catch (IOException e) {
            Log.e(TAG, "start socket failed: " + e.getMessage(), e);

            mStarted.set(false);

            SystemClock.sleep(1000);

            start();

        } finally {
            IOUtils.closeSilently(socket);
        }
    }

    private void handleSocket(LocalSocket socket) {
        EXECUTOR.submit(() -> handleSocket0(socket));
    }

    private void handleSocket0(LocalSocket socket) {
        try {
            InputStream inputStream = socket.getInputStream();
            Thread currentThread = Thread.currentThread();

            while (!currentThread.isInterrupted()) {
                byte[] data = new byte[1024];
                int read = inputStream.read(data);
                handleData(new String(data, 0, read, StandardCharsets.US_ASCII));
            }

        } catch (IOException ignored) {
        }
    }

    private void handleData(String msg) {
        Log.i(TAG, "received: " + msg);
        if (msg.startsWith(SWITCH_HOST)) {
            TwoyiStatusManager.getInstance().switchOs(mContext);
        } else if (msg.startsWith(BOOT_COMPLETED)) {
            Log.i(TAG, "BOOT_COMPLETED received!");
            TwoyiStatusManager.getInstance().markStarted();
        } else if (msg.startsWith(JUMP_HOST_SETTINGS)) {
            UIHelper.startActivity(mContext, SettingsActivity.class);
        }
    }
}
