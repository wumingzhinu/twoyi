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

import android.view.MotionEvent;
import android.view.Surface;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Renderer {

    static {
        System.loadLibrary("twoyi");
    }

    public static native void init(Surface surface, String loader, float xdpi, float ydpi, int fps);

    public static native void resetWindow(Surface surface, int top, int left, int width, int height);

    public static native void removeWindow(Surface surface);

    public static void handleTouch(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerCount = event.getPointerCount();
        float[] x = new float[pointerCount];
        float[] y = new float[pointerCount];
        float[] pressure = new float[pointerCount];
        int[] pointerIds = new int[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            x[i] = event.getX(i);
            y[i] = event.getY(i);
            pressure[i] = event.getPressure(i);
            pointerIds[i] = event.getPointerId(i);
        }
        nativeHandleTouch(action, pointerIndex, pointerCount, x, y, pressure, pointerIds);
    }

    private static native void nativeHandleTouch(int action, int pointerIndex, int pointerCount,
            float[] x, float[] y, float[] pressure, int[] pointerIds);

    public static native void sendKeycode(int keycode);
}
