// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use jni::sys::{jclass, jfloat, jint, jobject, JNI_ERR, jstring};
use jni::JNIEnv;
use jni::{JavaVM, NativeMethod};
use log::{error, info, debug};
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

/// Persistent, lossless trace for the native boot gates. The logcat ring
/// buffer evicts these lines within minutes on busy devices, so mirror them
/// to a file the app exports on boot failure.
fn native_trace(msg: &str) {
    use std::io::Write as _;
    let path = "/data/data/io.twoyi/native-trace.txt";
    match std::fs::OpenOptions::new().create(true).append(true).open(path) {
        Ok(mut f) => {
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0);
            let _ = writeln!(f, "{} {} {}", now, std::process::id(), msg);
        }
        Err(e) => error!("native_trace: open {} failed: {:?}", path, e),
    }
}

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
    native_trace(&format!(
        "renderer_init enter w={} h={} fps={}",
        width, height, fps
    ));

    if RENDERER_STARTED.compare_exchange(false, true,
        Ordering::Acquire, Ordering::Relaxed).is_err() {
        native_trace("renderer_init: already started, re-attach window");
        let win = window.ptr().as_ptr() as *mut c_void;
        unsafe {
            renderer_bindings::setNativeWindow(win);
            renderer_bindings::resetSubWindow(win, 0, 0, width, height, width, height, 1.0, 0.0);
        }
    } else {
        info!("renderer_init: starting input system, screen {}x{}", width, height);
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
                error!("renderer_init: get loader path failed: {:?}", e);
                native_trace(&format!("get loader path FAILED: {:?}", e));
                return;
            }
        };
        let working_dir = "/data/data/io.twoyi/rootfs";
        let init_path = "/data/data/io.twoyi/rootfs/init";
        let log_path = "/data/data/io.twoyi/log.txt";

        // The ROM archive stores init with mode 0700; some extraction paths
        // (AndroidP7zip) drop the exec bit and execve then fails with EACCES.
        // Force the exec bit before spawning, and record attribution data so a
        // remaining denial (mode vs SELinux) is decidable from the trace.
        use std::os::unix::fs::PermissionsExt;
        match std::fs::metadata(init_path) {
            Ok(meta) => {
                let mode = meta.permissions().mode();
                native_trace(&format!("init mode={:o}", mode));
                if mode & 0o111 == 0 {
                    match std::fs::set_permissions(init_path, std::fs::Permissions::from_mode(0o755)) {
                        Ok(_) => native_trace("chmod init 0755 ok"),
                        Err(e) => native_trace(&format!("chmod init FAILED: {:?}", e)),
                    }
                }
            }
            Err(e) => {
                native_trace(&format!("stat init FAILED: {:?}", e));
                error!("renderer_init: stat {} failed: {:?}", init_path, e);
                return;
            }
        }
        if let Ok(p) = std::ffi::CString::new(init_path) {
            if unsafe { libc::access(p.as_ptr(), libc::X_OK) } != 0 {
                native_trace(&format!("access(X_OK) FAILED errno={:?}", std::io::Error::last_os_error()));
            } else {
                native_trace("access(X_OK) ok");
            }
        }
        let enforce = std::fs::read_to_string("/sys/fs/selinux/enforce")
            .unwrap_or_else(|_| "unknown".into());
        native_trace(&format!("selinux enforce={}", enforce.trim()));

        let outputs = match File::create(log_path) {
            Ok(f) => f,
            Err(e) => {
                error!("renderer_init: create {} failed: {:?}", log_path, e);
                native_trace(&format!("create {} FAILED: {:?}", log_path, e));
                return;
            }
        };
        let errors = outputs.try_clone().unwrap();
        info!(
            "renderer_init: spawning ./init in {} with TYLOADER={}, log={}",
            working_dir, loader_path, log_path
        );
        native_trace(&format!(
            "spawning ./init in {} TYLOADER={} log={}",
            working_dir, loader_path, log_path
        ));
        match Command::new("./init")
            .current_dir(working_dir)
            .env("TYLOADER", &loader_path)
            .stdout(Stdio::from(outputs))
            .stderr(Stdio::from(errors))
            .spawn()
        {
            Ok(child) => {
                info!("renderer_init: ./init spawned, pid={}", child.id());
                native_trace(&format!("./init spawned pid={}", child.id()));
            }
            Err(e) => {
                error!("renderer_init: spawn ./init failed: {:?}", e);
                native_trace(&format!("spawn ./init FAILED: {:?}", e));
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
        renderer_bindings::resetSubWindow(window as *mut c_void, 0, 0, _width, _height, _width, _height, 1.0, 0.0);
    }
}

#[no_mangle]
pub fn renderer_remove_window(env: JNIEnv, _clz: jclass, surface: jobject) {
    debug!("renderer_remove_window");

    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        renderer_bindings::removeSubWindow(window as *mut c_void);
    }
}

#[no_mangle]
pub fn handle_touch(
    _env: JNIEnv,
    _clz: jclass,
    action: jint,
    pointer_id: jint,
    x: jfloat,
    y: jfloat,
    pressure: jfloat,
) {
    // The container used to receive the raw MotionEvent object, which required
    // reading the hidden MotionEvent.mNativePtr field. That field no longer
    // exists on newer Android releases, so Java now translates the event into
    // plain scalars (see Render2Activity.onTouch / Renderer.java).
    input::handle_touch(action, pointer_id, x, y, pressure);
}

pub fn send_key_code(_env: JNIEnv, _clz: jclass, keycode: jint) {
    debug!("send key code!");
    input::send_key_code(keycode);
}

unsafe fn register_natives(jvm: &JavaVM, class_name: &str, methods: &[NativeMethod]) -> jint {
    let env: JNIEnv = jvm.get_env().unwrap();
    let jni_version = env.get_version().unwrap();
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
            .with_max_level(log::LevelFilter::Info)
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
        jni_method!(handleTouch, handle_touch, "(IIFFF)V"),
        jni_method!(sendKeycode, send_key_code, "(I)V"),
    ];

    register_natives(&jvm, class_name, jni_methods.as_ref())
}
