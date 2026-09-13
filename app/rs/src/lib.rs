// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use jni::objects::JValue;
use jni::sys::{jclass, jint, jobject, jfloatArray, jintArray, JNI_ERR, jstring};
use jni::JNIEnv;
use jni::{JavaVM, NativeMethod};
use log::{error, info, Level, debug};
use ndk_sys;
use std::ffi::c_void;

use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

use android_logger::Config;

use std::fs::File;
use std::process::{Command, Stdio};

mod input;
mod renderer_bindings;

/// ## Examples
/// ```
/// let method:NativeMethod = jni_method!(native_method, "(Ljava/lang/String;)V");
/// ```
macro_rules! jni_method {
    ( $name: tt, $method:tt, $signature:expr ) => {{
        jni::NativeMethod {
            name: jni::strings::JNIString::from(stringify!($name)),
            sig: jni::strings::JNIString::from($signature),
            fn_ptr: $method as *mut c_void,
        }
    }};
}

static RENDERER_STARTED: AtomicBool = AtomicBool::new(false);

#[no_mangle]
pub fn renderer_init(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
    loader: jstring,
    xdpi: jfloat,
    ydpi: jfloat,
    fps: jint,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        renderer_init_inner(&env, surface, loader, xdpi, ydpi, fps);
    }));
    if let Err(e) = result {
        error!("renderer_init: panicked: {:?}", e);
    }
}

fn renderer_init_inner(
    env: &JNIEnv,
    surface: jobject,
    loader: jstring,
    xdpi: jfloat,
    ydpi: jfloat,
    fps: jint,
) {
    debug!("renderer_init");
    let window = unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface) };

    let window = match std::ptr::NonNull::new(window) {
        Some(x) => x,
        None => {
            error!("ANativeWindow_fromSurface was null!");
            return;
        }
    };

    let window = unsafe { ndk::native_window::NativeWindow::from_ptr(window) };

    let width = window.width();
    let height = window.height();

    info!(
        "renderer_init width: {}, height: {}, fps: {}",
        width, height, fps
    );

    if RENDERER_STARTED.compare_exchange(false, true, 
        Ordering::Acquire, Ordering::Relaxed).is_err() {
        let win = window.ptr().as_ptr() as *mut c_void;
        unsafe {
            renderer_bindings::setNativeWindow(win);
            renderer_bindings::resetSubWindow(win, 0, 0, width, height, width, height, 1.0, 0.0);
        }
    } else {
        input::start_input_system(width, height);

        thread::spawn(move || {
            let win = window.ptr().as_ptr() as *mut c_void;
            info!("win: {:#?}", win);
            unsafe {
                renderer_bindings::startOpenGLRenderer(
                    win,
                    width,
                    height,
                    xdpi as i32,
                    ydpi as i32,
                    fps as i32,
                );
            }
        });

        let loader_path: String = match env.get_string(loader.into()) {
            Ok(s) => s.into(),
            Err(e) => {
                error!("renderer_init: get_string failed: {:?}", e);
                return;
            }
        };
        let working_dir = "/data/data/io.twoyi/rootfs";
        let log_path = "/data/data/io.twoyi/log.txt";
        let outputs = match File::create(log_path) {
            Ok(f) => f,
            Err(e) => {
                error!("renderer_init: create log file failed: {}", e);
                return;
            }
        };
        let errors = match outputs.try_clone() {
            Ok(f) => f,
            Err(e) => {
                error!("renderer_init: clone log file handle failed: {}", e);
                return;
            }
        };
        match Command::new("./init")
            .current_dir(working_dir)
            .env("TYLOADER", loader_path)
            .stdout(Stdio::from(outputs))
            .stderr(Stdio::from(errors))
            .spawn()
        {
            Ok(child) => {
                info!("init process started, pid: {}", child.id());
                // 启动后立即给子进程更多执行机会
                std::thread::sleep(std::time::Duration::from_millis(100));
            }
            Err(e) => {
                error!("failed to start init process: {}", e);
            }
        }
    }
}

#[no_mangle]
pub fn renderer_reset_window(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
    _top: jint,
    _left: jint,
    _width: jint,
    _height: jint,
) {
    debug!("reset_window");
    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        if window.is_null() {
            error!("renderer_reset_window: ANativeWindow_fromSurface returned null");
            return;
        }
        renderer_bindings::resetSubWindow(window as *mut c_void, 0, 0, _width, _height, _width, _height, 1.0, 0.0);
    }
}

#[no_mangle]
pub fn renderer_remove_window(env: JNIEnv, _clz: jclass, surface: jobject) {
    debug!("renderer_remove_window");
    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        if window.is_null() {
            error!("renderer_remove_window: ANativeWindow_fromSurface returned null");
            return;
        }
        renderer_bindings::removeSubWindow(window as *mut c_void);
    }
}

#[no_mangle]
pub fn native_handle_touch(
    env: JNIEnv,
    _clz: jclass,
    action: jint,
    pointer_index: jint,
    pointer_count: jint,
    x_arr: jfloatArray,
    y_arr: jfloatArray,
    pressure_arr: jfloatArray,
    pointer_ids_arr: jintArray,
) {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let count = pointer_count as usize;
        let mut x_buf = vec![0.0f32; count];
        let mut y_buf = vec![0.0f32; count];
        let mut p_buf = vec![0.0f32; count];
        let mut id_buf = vec![0i32; count];

        if let Err(e) = env.get_float_array_region(x_arr, 0, &mut x_buf) {
            error!("native_handle_touch: get x failed: {:?}", e);
            return;
        }
        if let Err(e) = env.get_float_array_region(y_arr, 0, &mut y_buf) {
            error!("native_handle_touch: get y failed: {:?}", e);
            return;
        }
        if let Err(e) = env.get_float_array_region(pressure_arr, 0, &mut p_buf) {
            error!("native_handle_touch: get pressure failed: {:?}", e);
            return;
        }
        if let Err(e) = env.get_int_array_region(pointer_ids_arr, 0, &mut id_buf) {
            error!("native_handle_touch: get pointer_ids failed: {:?}", e);
            return;
        }

        input::handle_touch_primitives(
            action,
            pointer_index as usize,
            count,
            &x_buf,
            &y_buf,
            &p_buf,
            &id_buf,
        );
    }));

    if let Err(e) = result {
        error!("native_handle_touch: panicked: {:?}", e);
    }
}

#[no_mangle]
pub fn send_key_code(_env: JNIEnv, _clz: jclass, keycode: jint) {
    debug!("send key code!");
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        input::send_key_code(keycode);
    }));
}

unsafe fn register_natives(jvm: &JavaVM, class_name: &str, methods: &[NativeMethod]) -> jint {
    let env: JNIEnv = match jvm.get_env() {
        Ok(e) => e,
        Err(e) => {
            error!("register_natives: get_env failed: {:?}", e);
            return JNI_ERR;
        }
    };
    let jni_version = match env.get_version() {
        Ok(v) => v,
        Err(e) => {
            error!("register_natives: get_version failed: {:?}", e);
            return JNI_ERR;
        }
    };
    let version: jint = jni_version.into();

    debug!("JNI Version : {:#?} ", jni_version);

    let clazz = match env.find_class(class_name) {
        Ok(clazz) => clazz,
        Err(e) => {
            error!("java class not found : {:?}", e);
            return JNI_ERR;
        }
    };
    debug!("clazz: {:#?}", clazz);

    let result = env.register_native_methods(clazz, &methods);

    if result.is_ok() {
        debug!("register_natives : succeed");
        version
    } else {
        error!("register_natives : failed ");
        JNI_ERR
    }
}

#[no_mangle]
#[allow(non_snake_case)]
unsafe fn JNI_OnLoad(jvm: JavaVM, _reserved: *mut c_void) -> jint {
    android_logger::init_once(
        Config::default()
            .with_min_level(Level::Info)
            .with_tag("CLIENT_EGL"),
    );

    debug!("JNI_OnLoad");

    let class_name: &str = "io/twoyi/Renderer";
    let jni_methods = [
        jni_method!(init, renderer_init, "(Landroid/view/Surface;Ljava/lang/String;FFI)V"),
        jni_method!(
            resetWindow,
            renderer_reset_window,
            "(Landroid/view/Surface;IIII)V"
        ),
        jni_method!(
            removeWindow,
            renderer_remove_window,
            "(Landroid/view/Surface;)V"
        ),
        jni_method!(nativeHandleTouch, native_handle_touch, "(III[F[F[F[I)V"),
        jni_method!(sendKeycode, send_key_code, "(I)V"),
    ];

    register_natives(&jvm, class_name, jni_methods.as_ref())
}
