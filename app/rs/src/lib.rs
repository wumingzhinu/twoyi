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
fn native_trace(msg: &str) {    use std::io::Write as _;
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

/// Trace existence/metadata of a path; follows symlinks when requested.
fn trace_state(path: &str, follow: bool) {
    let meta = if follow {
        std::fs::metadata(path)
    } else {
        std::fs::symlink_metadata(path)
    };
    match meta {
        Ok(m) => {
            use std::os::unix::fs::MetadataExt;
            use std::os::unix::fs::PermissionsExt;
            native_trace(&format!(
                "state {}: mode={:o} uid={} size={}",
                path,
                m.permissions().mode(),
                m.uid(),
                m.len()
            ));
        }
        Err(e) => native_trace(&format!("state {}: ERR {:?}", path, e.kind())),
    }
    if follow {
        if let Ok(target) = std::fs::read_link(path) {
            native_trace(&format!("readlink {} -> {:?}", path, target));
        }
    }
}

/// Log the SELinux xattr label of a path, when readable.
fn trace_selinux_label(path: &str) {    if let Ok(p) = std::ffi::CString::new(path) {
        let mut buf = [0u8; 128];
        let n = unsafe {
            libc::getxattr(
                p.as_ptr(),
                b"security.selinux\0".as_ptr() as *const libc::c_char,
                buf.as_mut_ptr() as *mut libc::c_void,
                buf.len(),
            )
        };
        if n < 0 {
            native_trace(&format!(
                "xattr {}: errno={:?}",
                path,
                std::io::Error::last_os_error().raw_os_error()
            ));
        } else {
            let label = String::from_utf8_lossy(&buf[..n as usize]);
            native_trace(&format!("xattr {}: {}", path, label.trim_end_matches('\0')));
        }
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
        return;
    }

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

    // The loader argument is a JNI string: read it while we still own the
    // JNIEnv. The container is launched through the loader64 symlink anyway
    // (see TYLOADER below), so the value only has to validate here.
    if let Err(e) = env.get_string(loader.into()) {
        error!("renderer_init: get loader path failed: {:?}", e);
        native_trace(&format!("get loader path FAILED: {:?}", e));
        return;
    }

    // Container bootstrap does real filesystem work - stat/chmod of init plus
    // a full copy of the init binary - and used to run synchronously right
    // here, i.e. on the UI thread inside SurfaceHolder.Callback.surfaceCreated().
    // That stalled the very first frames after the surface appeared and could
    // ANR the activity on slow storage. None of it needs the JNIEnv, so hand
    // it to a worker thread and return to the UI thread immediately.
    thread::spawn(spawn_container);
}

/// Verify/repair the container's init binary, wire its stdout to log.txt and
/// spawn it. Runs on a worker thread (see renderer_init).
fn spawn_container() {
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
    if let Ok(ctx) = std::fs::read_to_string("/proc/self/attr/current") {
        native_trace(&format!("selinux context={}", ctx.trim()));
    }
    trace_selinux_label(init_path);

    // Recreate the file through this process. An extraction-created file
    // can carry a stale SELinux label (e.g. left from a previous install
    // or restorecon) that denies execve even though DAC allows it; a file
    // written by this process inherits the parent dir's current context.
    let fresh_path = "/data/data/io.twoyi/rootfs/init.fresh";
    match std::fs::copy(init_path, fresh_path) {
        Ok(_) => {
            let _ = std::fs::set_permissions(
                fresh_path,
                std::fs::Permissions::from_mode(0o700),
            );
            match std::fs::rename(fresh_path, init_path) {
                Ok(_) => {
                    native_trace("init refreshed via copy+rename");
                    trace_selinux_label(init_path);
                }
                Err(e) => native_trace(&format!("rename init.fresh FAILED: {:?}", e)),
            }
        }
        Err(e) => native_trace(&format!("copy init FAILED: {:?}", e)),
    }

    let outputs = match File::create(log_path) {
        Ok(f) => f,
        Err(e) => {
            error!("renderer_init: create {} failed: {:?}", log_path, e);
            native_trace(&format!("create {} FAILED: {:?}", log_path, e));
            return;
        }
    };
    let errors = outputs.try_clone().unwrap();
    let info_loader = "/data/data/io.twoyi/loader64";
    info!(
        "renderer_init: spawning ./init in {} with TYLOADER={}, log={}",
        working_dir, info_loader, log_path
    );
    native_trace(&format!(
        "spawning ./init in {} TYLOADER={} log={} cwd={:?}",
        working_dir, info_loader, log_path, std::env::current_dir()
    ));
    // pre-spawn fs state: is the ROM loader64 symlink resolvable, is init intact?
    trace_state(info_loader, true);
    trace_state(init_path, false);

    match Command::new("./init")
        .current_dir(working_dir)
        .env("TYLOADER", info_loader)
        .stdout(Stdio::from(outputs))
        .stderr(Stdio::from(errors))
        .spawn()
    {
        Ok(child) => {
            let id = child.id();
            info!("renderer_init: ./init spawned, pid={}", id);
            native_trace(&format!("./init spawned pid={}", id));
            // Watch for 5s: if the container init dies immediately (its panic
            // path umounts host storage!) record the exit status and whether
            // init deleted itself - decisive evidence for the execv failure.
            let init_owned = init_path.to_string();
            let mut child = child;
            std::thread::spawn(move || {
                std::thread::sleep(std::time::Duration::from_secs(5));
                let status = child.try_wait();
                native_trace(&format!("child status after 5s: {:?}", status));
                trace_state(&init_owned, false);
            });
        }
        Err(e) => {
            error!("renderer_init: spawn ./init failed: {:?}", e);
            native_trace(&format!("spawn ./init FAILED: {:?}", e));
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
            error!("reset_window: ANativeWindow_fromSurface was null!");
            return;
        }
        renderer_bindings::resetSubWindow(window as *mut c_void, 0, 0, _width, _height, _width, _height, 1.0, 0.0);
        release_surface_window(window);
    }
}

#[no_mangle]
pub fn renderer_remove_window(env: JNIEnv, _clz: jclass, surface: jobject) {
    debug!("renderer_remove_window");

    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        if window.is_null() {
            error!("remove_window: ANativeWindow_fromSurface was null!");
            return;
        }
        renderer_bindings::removeSubWindow(window as *mut c_void);
        release_surface_window(window);
    }
}

/// Hand back the strong reference that `ANativeWindow_fromSurface` granted.
///
/// That call returns a *new* reference the caller owns, which must go back to
/// the system via `ANativeWindow_release`. Nothing in this crate ever did
/// that: the `ndk` 0.6 `NativeWindow` type is a bare pointer wrapper with no
/// `Drop` impl (unlike `Asset`, `Image`, `ForeignLooper` ... in the same
/// crate), so it silently leaked. Both `renderer_reset_window` and
/// `renderer_remove_window` call `ANativeWindow_fromSurface`, so every
/// background/foreground round trip leaked another reference and left the
/// Surface's BufferQueue pinned - slow, unbounded growth over a long session.
///
/// Releasing here cannot pull the rug out from under the renderer:
/// `ANativeWindow_fromSurface` returns the *same* `ANativeWindow` object for a
/// given `Surface` and only bumps its refcount, and `renderer_init` already
/// holds a reference that lives as long as the renderer thread does. The
/// pointer the renderer stores therefore stays valid; the reference taken by
/// these two callbacks is surplus.
///
/// Deliberately not routed through `ndk::native_window::NativeWindow`: since
/// that type does not release on drop, wrapping it here would re-introduce
/// exactly the leak it is meant to fix.
unsafe fn release_surface_window(window: *mut ndk_sys::ANativeWindow) {
    ndk_sys::ANativeWindow_release(window);
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
