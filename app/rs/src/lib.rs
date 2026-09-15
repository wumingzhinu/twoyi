// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use jni::objects::JValue;
use jni::sys::{jclass, jint, jobject, jfloatArray, jintArray, JNI_ERR, jstring};
use jni::JNIEnv;
use jni::{JavaVM, NativeMethod};
use log::{error, info, warn, Level, debug};
use ndk_sys;
use std::ffi::c_void;

use std::os::unix::fs::FileTypeExt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

use android_logger::Config;

use std::fs::File;
use std::process::{Command, Stdio};
use std::ffi::CString;

mod uinput_defs;
mod input;
mod renderer_bindings;

fn ensure_named_pipes() {
    let working_dir = "/data/data/io.twoyi/rootfs";
    let pipes = [
        format!("{}/opengles", working_dir),
        format!("{}/opengles2", working_dir),
        format!("{}/opengles3", working_dir),
    ];
    for pipe in &pipes {
        // 先删除已有文件（可能是上次遗留的 socket 或 fifo）
        let _ = std::fs::remove_file(pipe);
        let c_path = match CString::new(pipe.as_str()) {
            Ok(s) => s,
            Err(_) => continue,
        };
        let ret = unsafe { libc::mkfifo(c_path.as_ptr(), 0o666) };
        if ret == 0 {
            info!("Created named pipe: {}", pipe);
        } else {
            let err = std::io::Error::last_os_error();
            warn!("Failed to create named pipe {}: {}", pipe, err);
        }
    }
}

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
    xdpi: f32,
    ydpi: f32,
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
    xdpi: f32,
    ydpi: f32,
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

        // 即时诊断文件 — 在 Rust renderer 路径的最开始写入
        // 不依赖任何超时，即使 app 崩溃也能确认代码执行到这里
        {
            let diag_dir = "/data/data/io.twoyi/logs";
            let _ = std::fs::create_dir_all(diag_dir);
            let diag_path = format!("{}/renderer_start.txt", diag_dir);
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            let diag_content = format!(
                "renderer_init called at epoch={}\nwidth={} height={} dpi={} fps={}\n",
                now, width, height, xdpi, fps
            );
            match std::fs::write(&diag_path, &diag_content) {
                Ok(_) => info!("Wrote diagnostic: {}", diag_path),
                Err(e) => error!("Failed to write diagnostic: {}", e),
            }

            // 检查 named pipe 路径是否存在
            let working_dir = "/data/data/io.twoyi/rootfs";
            for name in &["opengles", "opengles2", "opengles3"] {
                let pipe_path = format!("{}/{}", working_dir, name);
                let exists = std::path::Path::new(&pipe_path).exists();
                let meta = std::fs::metadata(&pipe_path).ok();
                let is_fifo = meta.as_ref().map(|m| m.file_type().is_fifo()).unwrap_or(false);
                info!("Pipe {}: exists={} is_fifo={}", name, exists, is_fifo);
            }
        }

        ensure_named_pipes();

        thread::spawn(move || {
            let win = window.ptr().as_ptr() as *mut c_void;
            info!("win: {:#?}", win);
            let ret = unsafe {
                renderer_bindings::startOpenGLRenderer(
                    win,
                    width,
                    height,
                    xdpi as i32,
                    ydpi as i32,
                    fps as i32,
                )
            };
            let diag_path = "/data/data/io.twoyi/logs/renderer_result.txt";
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            let result_line = format!("startOpenGLRenderer returned {} at epoch={}\n", ret, now);
            let _ = std::fs::write(diag_path, &result_line);
            if ret == 0 {
                error!("startOpenGLRenderer failed (returned 0)");
            } else {
                info!("startOpenGLRenderer succeeded (returned {})", ret);
            }
        });

        std::thread::sleep(std::time::Duration::from_secs(2));

        let loader_path: String = match env.get_string(loader.into()) {
            Ok(s) => s.into(),
            Err(e) => {
                error!("renderer_init: get_string failed: {:?}", e);
                return;
            }
        };
        let working_dir = "/data/data/io.twoyi/rootfs";
        let log_path = "/data/data/io.twoyi/log.txt";
        // 每次启动时清空日志文件
        let _ = std::fs::remove_file(log_path);
        let outputs = match std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log_path) {
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
// Android 12+ data 目录 noexec：先尝试 ./init（symlink 到 nativeLibDir），
        // 若失败则从 TYLOADER 路径推导 nativeLibDir 直接执行 libtwoyi_init.so
        let init_candidates = {
            let mut candidates: Vec<String> = vec![
                format!("{}/init", working_dir),
            ];
            // 从 loader_path 推导 nativeLibDir: .../lib/arm64/libloader.so -> .../lib/arm64/
            if let Some(parent) = std::path::Path::new(&loader_path).parent() {
                candidates.push(format!("{}/libtwoyi_init.so", parent.display()));
            }
            candidates
        };

        let mut spawned = false;
        for init_path in &init_candidates {
            info!("trying init: {}", init_path);
            let out = outputs.try_clone().expect("clone stdout");
            let err = errors.try_clone().expect("clone stderr");
            match Command::new(init_path)
                .current_dir(working_dir)
                .env("TYLOADER", &loader_path)
                .stdout(Stdio::from(out))
                .stderr(Stdio::from(err))
                .spawn()
            {
                Ok(child) => {
                    info!("init process started from {}, pid: {}", init_path, child.id());
                    spawned = true;
                    let diag_path = "/data/data/io.twoyi/logs/init_started.txt";
                    let now = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_secs())
                        .unwrap_or(0);
                    let _ = std::fs::write(diag_path, format!(
                        "init started from={} pid={} at epoch={}\n", init_path, child.id(), now
                    ));
                    std::thread::sleep(std::time::Duration::from_millis(100));
                    break;
                }
                Err(e) => {
                    error!("failed to start init from {}: {}", init_path, e);
                }
            }
        }

        if !spawned {
            error!("all init candidates failed, boot will timeout");
            let diag_path = "/data/data/io.twoyi/logs/init_failed.txt";
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0);
            let _ = std::fs::write(diag_path, format!(
                "all init candidates failed at epoch={}\nloader_path={}\nworking_dir={}\n",
                now, loader_path, working_dir
            ));
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
