// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use libc::*;
use libc::{c_char, c_int};
use std::collections::VecDeque;
use std::mem;
use std::net::Shutdown;
use std::thread;
use std::{io::Write};
use uinput_sys::*;

use std::sync::{Condvar, Mutex, MutexGuard};
use once_cell::sync::Lazy;

use log::{info, debug, warn};

use unix_socket::{UnixListener, UnixStream};

const FF_MAX: u16 = 0x7f;

const TOUCH_PATH: &'static str = "/data/data/io.twoyi/rootfs/dev/input/touch";
const TOUCH_DEVICE_NAME: &'static str = "vtouch";
const TOUCH_DEVICE_UNIQUE_ID: &'static str = "<vtouch 0>";

const KEY_DEVICE_NAME: &'static str = "vkey";
const KEY_DEVICE_UNIQUE_ID: &'static str = "<keyboard 0>";
const KEY_PATH: &'static str = "/data/data/io.twoyi/rootfs/dev/input/key0";

/// Upper bound for the pending input backlog. The reader is expected to keep
/// this near zero; the cap only exists so a container that stops reading can
/// never turn a hiccup into an unbounded queue (which is what used to make
/// touch latency grow forever and never recover).
const MAX_PENDING_FRAMES: usize = 512;

#[repr(C)]
#[derive(Clone, Copy)]
struct device_info {
    name: [c_char; 80],
    driver_version: c_int,
    id: input_id,
    physical_location: [c_char; 80],
    unique_id: [c_char; 80],
    key_bitmask: [u8; (KEY_MAX as usize + 1) / 8],
    abs_bitmask: [u8; (ABS_MAX as usize + 1) / 8],
    rel_bitmask: [u8; (REL_MAX as usize + 1) / 8],
    sw_bitmask: [u8; (SW_MAX as usize + 1) / 8],
    led_bitmask: [u8; (LED_MAX as usize + 1) / 8],
    ff_bitmask: [u8; (FF_MAX as usize + 1) / 8],
    prop_bitmask: [u8; (INPUT_PROP_MAX as usize + 1) / 8],
    abs_max: [u32; ABS_CNT as usize],
    abs_min: [u32; ABS_CNT as usize],
}

unsafe fn any_as_u8_slice<T: Sized>(p: &T) -> &[u8] {
    ::std::slice::from_raw_parts((p as *const T) as *const u8, ::std::mem::size_of::<T>())
}

fn copy_to_cstr<const COUNT: usize>(data: &str, arr: &mut [u8; COUNT]) {
    let cstr = std::ffi::CString::new(data).expect("create cstring failed");
    let bytes = cstr.as_bytes_with_nul();
    let mut len = bytes.len();
    if len >= COUNT {
        len = COUNT;
    }
    arr[..len].copy_from_slice(bytes);
}

const MAX_POINTERS: usize = 5;

/// Lock a mutex without panicking on a poisoned lock: a panic in a previous
/// frame must not take touch input down with it.
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

/// One evdev frame: every event belonging to a single SYN_REPORT.
///
/// Frames are written to the container socket in one `write()` instead of one
/// syscall per event, and move frames are marked `coalescable` so that a newer
/// move replaces the queued one instead of piling up behind a slow reader.
struct Frame {
    evs: Vec<input_event>,
    coalescable: bool,
}

impl Frame {
    fn bytes(&self) -> &[u8] {
        unsafe {
            std::slice::from_raw_parts(
                self.evs.as_ptr() as *const u8,
                std::mem::size_of_val(self.evs.as_slice()),
            )
        }
    }
}

/// Bounded, coalescing, non-blocking producer queue feeding one writer thread.
///
/// The old implementation pushed every event into an unbounded channel from
/// the UI thread. Whenever the container's input reader stalled (GC, thermal
/// throttle, background freeze) the channel kept absorbing events, so the
/// backlog - and with it the touch latency - grew monotonically and never
/// recovered: the app felt laggy and then completely frozen while the host UI
/// stayed perfectly smooth. Moves now collapse onto the tail, so latency is
/// bounded by the stall itself and the queue drains the moment the reader
/// wakes up. `push` never blocks, so the UI thread can never be stalled by a
/// wedged container either.
struct FrameQueue {
    frames: Mutex<VecDeque<Frame>>,
    ready: Condvar,
}

impl FrameQueue {
    fn new() -> Self {
        FrameQueue {
            frames: Mutex::new(VecDeque::new()),
            ready: Condvar::new(),
        }
    }

    fn push(&self, frame: Frame) {
        let mut q = lock(&self.frames);
        if frame.coalescable {
            if let Some(last) = q.back_mut() {
                if last.coalescable {
                    // Only the newest position matters; drop the stale one.
                    *last = frame;
                    drop(q);
                    self.ready.notify_one();
                    return;
                }
            }
        } else if q.len() >= MAX_PENDING_FRAMES {
            // Reader hopelessly stalled: keep the backlog fresh instead of
            // growing it. Prefer dropping an old move, else the oldest frame.
            let victim = q
                .iter()
                .position(|f| f.coalescable)
                .or_else(|| if q.is_empty() { None } else { Some(0) });
            if let Some(i) = victim {
                q.remove(i);
            }
            log_backlog_once_per_second();
        }
        q.push_back(frame);
        drop(q);
        self.ready.notify_one();
    }

    fn pop(&self) -> Frame {
        let mut q = lock(&self.frames);
        loop {
            if let Some(f) = q.pop_front() {
                return f;
            }
            q = self.ready.wait(q).unwrap_or_else(|p| p.into_inner());
        }
    }
}

/// Warn at most once a second: a wedged reader can overrun the queue
/// thousands of times a second and logcat spam would become its own stall.
fn log_backlog_once_per_second() {
    use std::sync::atomic::{AtomicU64, Ordering};
    static LAST_WARN_MS: AtomicU64 = AtomicU64::new(0);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    let last = LAST_WARN_MS.load(Ordering::Relaxed);
    if now.saturating_sub(last) >= 1000
        && LAST_WARN_MS
            .compare_exchange(last, now, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
    {
        warn!(
            "input queue saturated ({} frames pending): container input reader is stalling, dropping stale frames",
            MAX_PENDING_FRAMES
        );
    }
}

static TOUCH_QUEUE: Lazy<FrameQueue> = Lazy::new(FrameQueue::new);
static KEY_QUEUE: Lazy<FrameQueue> = Lazy::new(FrameQueue::new);

/// Current client connection for each device; replaced when the container
/// reconnects.
static TOUCH_OUT: Lazy<Mutex<Option<UnixStream>>> = Lazy::new(|| Mutex::new(None));
static KEY_OUT: Lazy<Mutex<Option<UnixStream>>> = Lazy::new(|| Mutex::new(None));

pub fn start_input_system(width: i32, height: i32) {
    thread::spawn(move || {
        touch_server(width, height);
    });
    thread::spawn(|| {
        key_server();
    });
}

/// Drain `queue` to `out` forever. Cloning the socket handle per frame means a
/// reconnect can swap the connection in from the server thread without having
/// to wait for a write that may be blocked on a wedged reader.
fn drain_queue(queue: &FrameQueue, out: &Mutex<Option<UnixStream>>, what: &str) {
    loop {
        let frame = queue.pop();
        let stream = {
            let g = lock(out);
            g.as_ref().and_then(|s| s.try_clone().ok())
        };
        // No client yet (container still booting): the frame is dropped, the
        // same as before when no sender had been installed.
        let mut stream = match stream {
            Some(s) => s,
            None => continue,
        };
        if let Err(e) = stream.write_all(frame.bytes()) {
            debug!("{}: write failed ({}), frame dropped", what, e);
        }
    }
}

/// Bind a device socket, retrying briefly: an un-bindable socket used to
/// `unwrap()` and kill input for the whole session.
fn bind_server(path: &'static str) -> Option<UnixListener> {
    for _ in 0..3 {
        let _ = std::fs::remove_file(path);
        match UnixListener::bind(path) {
            Ok(l) => return Some(l),
            Err(e) => {
                warn!("bind {} failed: {}, retrying", path, e);
                thread::sleep(std::time::Duration::from_millis(200));
            }
        }
    }
    None
}

/// Install a fresh client connection, shutting down the previous one so a
/// writer blocked on it fails fast instead of wedging forever.
fn install_client(out: &Mutex<Option<UnixStream>>, stream: UnixStream) {
    let mut g = lock(out);
    if let Some(old) = g.take() {
        let _ = old.shutdown(Shutdown::Both);
    }
    *g = Some(stream);
}

fn make_event(kind: i32, code: i32, val: i32) -> input_event {
    let mut tp = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    let _ = unsafe { clock_gettime(CLOCK_MONOTONIC, &mut tp) };
    let tv = timeval {
        tv_sec: tp.tv_sec,
        tv_usec: tp.tv_nsec / 1000,
    };

    input_event {
        kind: kind as u16,
        code: code as u16,
        value: val,
        time: tv,
    }
}

// AMOTION_EVENT_ACTION_* values, stable since API level 1.
const ACTION_DOWN: i32 = 0;
const ACTION_UP: i32 = 1;
const ACTION_MOVE: i32 = 2;
const ACTION_CANCEL: i32 = 3;
const ACTION_POINTER_DOWN: i32 = 5;
const ACTION_POINTER_UP: i32 = 6;

pub fn handle_touch(action: i32, pointer_id: i32, x: f32, y: f32, pressure: f32) {
    // Guard the MT slot table: Android may hand out pointer ids >= MAX_POINTERS
    // with 6+ fingers on screen; the original code panicked on those.
    if pointer_id < 0 || (pointer_id as usize) >= MAX_POINTERS {
        return;
    }

    let x = x as i32;
    let y = y as i32;
    let pressure = pressure as i32;

    static G_INPUT_MT: Lazy<Mutex<[i32;MAX_POINTERS]>> = Lazy::new(|| {std::sync::Mutex::new([0i32;MAX_POINTERS])});

    let mut evs: Vec<input_event> = Vec::with_capacity(32);
    let mut coalescable = false;

    let mut mt = lock(&G_INPUT_MT);

    match action {
        ACTION_DOWN | ACTION_POINTER_DOWN => {
            mt[pointer_id as usize] = 1;

            let mut index = 0;
            while index < MAX_POINTERS {
                if mt[index] != 0 {
                    evs.push(make_event(EV_ABS, ABS_MT_SLOT, pointer_id));
                    evs.push(make_event(EV_ABS, ABS_MT_TRACKING_ID, pointer_id + 1));

                    if index == 0 {
                        evs.push(make_event(EV_KEY, BTN_TOUCH, 108));
                        evs.push(make_event(EV_KEY, BTN_TOOL_FINGER, 108));
                    }

                    evs.push(make_event(EV_ABS, ABS_MT_POSITION_X, x));
                    evs.push(make_event(EV_ABS, ABS_MT_POSITION_Y, y));

                    evs.push(make_event(EV_ABS, ABS_MT_PRESSURE, pressure));

                    evs.push(make_event(EV_SYN, SYN_REPORT, SYN_REPORT));
                }
                index = index + 1;
            }
        }
        ACTION_UP => {
            let mut index = 0;
            while index != MAX_POINTERS {
                if mt[index] != 0 {
                    mt[index] = 0;
                    evs.push(make_event(EV_ABS, ABS_MT_SLOT, index.try_into().unwrap()));
                    evs.push(make_event(EV_ABS, ABS_MT_TRACKING_ID, -1));
                    evs.push(make_event(EV_SYN, SYN_REPORT, SYN_REPORT));
                }
                index = index + 1;
            }
        }
        ACTION_MOVE => {
            // Position-only frame: safe to collapse onto whatever move is
            // already queued, which is what keeps latency flat under load.
            coalescable = true;

            let mut index = 0;
            while index != MAX_POINTERS {
                if mt[index] != 0 {
                    evs.push(make_event(EV_ABS, ABS_MT_SLOT, index.try_into().unwrap()));
                    evs.push(make_event(EV_ABS, ABS_MT_POSITION_X, x));
                    evs.push(make_event(EV_ABS, ABS_MT_POSITION_Y, y));

                    evs.push(make_event(EV_ABS, ABS_MT_PRESSURE, pressure));

                    evs.push(make_event(EV_SYN, SYN_REPORT, SYN_REPORT));
                }
                index = index + 1;
            }
        }
        ACTION_CANCEL | ACTION_POINTER_UP => {
            if mt[pointer_id as usize] == 0 {
                return;
            }

            mt[pointer_id as usize] = 0;
            evs.push(make_event(EV_ABS, ABS_MT_SLOT, pointer_id));
            evs.push(make_event(EV_ABS, ABS_MT_TRACKING_ID, -1));
            evs.push(make_event(EV_SYN, SYN_REPORT, SYN_REPORT));
        }
        _ => {}
    }

    drop(mt);

    if evs.is_empty() {
        return;
    }
    TOUCH_QUEUE.push(Frame { evs, coalescable });
}

fn generate_touch_device(width: i32, height: i32) -> device_info {
    let iid = input_id {
        product: 0x1,
        version: 0,
        vendor: 0,
        bustype: 0,
    };

    let mut info = device_info {
        name: unsafe { mem::zeroed() },
        driver_version: 0x1,
        id: iid,
        physical_location: unsafe { mem::zeroed() },
        unique_id: unsafe { mem::zeroed() },
        key_bitmask: unsafe { mem::zeroed() },
        abs_bitmask: unsafe { mem::zeroed() },
        rel_bitmask: unsafe { mem::zeroed() },
        sw_bitmask: unsafe { mem::zeroed() },
        led_bitmask: unsafe { mem::zeroed() },
        ff_bitmask: unsafe { mem::zeroed() },
        prop_bitmask: unsafe { mem::zeroed() },
        abs_max: unsafe { mem::zeroed() },
        abs_min: unsafe { mem::zeroed() },
    };

    copy_to_cstr(TOUCH_DEVICE_NAME, &mut info.name);
    copy_to_cstr(TOUCH_PATH, &mut info.physical_location);
    copy_to_cstr(TOUCH_DEVICE_UNIQUE_ID, &mut info.unique_id);

    info.prop_bitmask[0] = INPUT_PROP_BUTTONPAD as u8;

    info.abs_bitmask[ABS_RZ as usize] = 0x80;
    info.abs_bitmask[ABS_THROTTLE as usize] = 0x60;
    info.abs_bitmask[ABS_RUDDER as usize] = 0x2;

    info.abs_min[ABS_MT_POSITION_X as usize] = 0;
    info.abs_max[ABS_MT_POSITION_X as usize] = width as u32;

    info.abs_min[ABS_MT_POSITION_Y as usize] = 0;
    info.abs_max[ABS_MT_POSITION_Y as usize] = height as u32;

    info.abs_min[ABS_MT_TOUCH_MAJOR as usize] = 0;
    info.abs_min[ABS_MT_TOUCH_MINOR as usize] = 15;

    info.abs_min[ABS_MT_SLOT as usize] = 4;
    info.abs_min[ABS_MT_PRESSURE as usize] = 0;
    info.abs_max[ABS_MT_PRESSURE as usize] = 80;

    info
}

fn touch_server(width: i32, height: i32) {
    let device = generate_touch_device(width, height);

    let listener = match bind_server(TOUCH_PATH) {
        Some(l) => l,
        None => {
            warn!("touch server: cannot bind {}, touch input disabled", TOUCH_PATH);
            return;
        }
    };

    // A single writer thread lives for the whole session. The old code spawned
    // one per connection and left the previous ones behind: their channel was
    // already disconnected, so `recv()` returned an error immediately and the
    // loop busy-spun at 100% CPU forever - one leaked core per reconnect,
    // which is exactly the "gets laggier the longer you use it" symptom.
    thread::spawn(|| drain_queue(&TOUCH_QUEUE, &TOUCH_OUT, "touch"));

    for stream in listener.incoming() {
        match stream {
            Ok(mut stream) => {
                info!("touch client connected!");

                let _ = stream.write_all(unsafe { any_as_u8_slice(&device) });

                install_client(&TOUCH_OUT, stream);
            }
            Err(_) => {
                info!("touch server error happened!");
                break;
            }
        }
    }

    info!("drop listener!");
}

fn generate_key_device() -> device_info {
    let mut info: device_info = unsafe { std::mem::MaybeUninit::zeroed().assume_init() };

    info.driver_version = 0x1;
    info.id.product = 0x1;

    copy_to_cstr(KEY_DEVICE_NAME, &mut info.name);
    copy_to_cstr(KEY_PATH, &mut info.physical_location);
    copy_to_cstr(KEY_DEVICE_UNIQUE_ID, &mut info.unique_id);

    info.key_bitmask[14] = 0x1C;

    info
}

pub fn send_key_code(keycode: i32) {
    debug!("send key code: {}", keycode);
    // KEYCODE_HOME(3) means HOME; everything else falls back to BACK.
    let ev_key = if keycode == 3 { KEY_HOME } else { KEY_BACK };
    let evs = vec![
        make_event(EV_KEY, ev_key, 1),
        make_event(EV_SYN, SYN_REPORT, SYN_REPORT),
        make_event(EV_KEY, ev_key, 0),
        make_event(EV_SYN, SYN_REPORT, SYN_REPORT),
    ];
    // Never blocks: the back key must not be able to stall the UI thread.
    KEY_QUEUE.push(Frame { evs, coalescable: false });
}

fn key_server() {
    let device = generate_key_device();

    let listener = match bind_server(KEY_PATH) {
        Some(l) => l,
        None => {
            warn!("key server: cannot bind {}, key input disabled", KEY_PATH);
            return;
        }
    };

    thread::spawn(|| drain_queue(&KEY_QUEUE, &KEY_OUT, "key"));

    for stream in listener.incoming() {
        match stream {
            Ok(mut stream) => {
                info!("key client connected!");

                let _ = stream.write_all(unsafe { any_as_u8_slice(&device) });

                install_client(&KEY_OUT, stream);
            }
            Err(_) => {
                info!("key server error happened!");
                break;
            }
        }
    }
}
