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

    /**
     * Forward a touch contact to the container.
     * action: event.getActionMasked() (mirrors AMOTION_EVENT_ACTION_* codes)
     * pointerId/x/y/pressure: values of the pointer at the action index.
     * Reads only public MotionEvent APIs so the hidden mNativePtr field
     * (removed in newer Android releases) is never touched.
     */
    public static native void handleTouch(int action, int pointerId, float x, float y, float pressure);

    public static native void sendKeycode(int keycode);
}
