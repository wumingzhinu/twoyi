// Local replacement for uinput-sys crate (avoids git dependency)
// Linux input event subsystem constants from <linux/input.h> and <linux/uinput.h>

use libc::{c_int, c_ushort, c_char};
use std::mem;

pub const EV_SYN: u32 = 0x00;
pub const EV_KEY: u32 = 0x01;
pub const EV_ABS: u32 = 0x03;

pub const SYN_REPORT: u32 = 0;

pub const BTN_TOUCH: u32 = 0x14a;
pub const BTN_TOOL_FINGER: u32 = 0x145;
pub const KEY_BACK: u32 = 0x9e;
pub const KEY_SEND: u32 = 0x160;

pub const ABS_MT_SLOT: u32 = 0x2f;
pub const ABS_MT_TOUCH_MAJOR: u32 = 0x30;
pub const ABS_MT_TOUCH_MINOR: u32 = 0x31;
pub const ABS_MT_POSITION_X: u32 = 0x35;
pub const ABS_MT_POSITION_Y: u32 = 0x36;
pub const ABS_MT_PRESSURE: u32 = 0x3a;
pub const ABS_MT_TRACKING_ID: u32 = 0x39;
pub const ABS_RZ: u32 = 0x05;
pub const ABS_THROTTLE: u32 = 0x06;
pub const ABS_RUDDER: u32 = 0x07;

pub const ABS_MAX: u32 = 0x3f;
pub const ABS_CNT: usize = (ABS_MAX + 1) as usize;

pub const KEY_MAX: u32 = 0x2ff;
pub const REL_MAX: u32 = 0x0f;
pub const SW_MAX: u32 = 0x0f;
pub const LED_MAX: u32 = 0x0f;
pub const INPUT_PROP_MAX: u32 = 0x1f;
pub const INPUT_PROP_BUTTONPAD: u32 = 0x02;

pub const G_INPUT_MT: u32 = 0;

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct UinputInputId {
    pub bustype: c_ushort,
    pub vendor: c_ushort,
    pub product: c_ushort,
    pub version: c_ushort,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct uinput_user_dev {
    pub name: [c_char; 80],
    pub id: UinputInputId,
    pub ff_effects_max: c_int,
    pub abs_max: [i32; ABS_CNT],
    pub abs_min: [i32; ABS_CNT],
    pub abs_fuzz: [i32; ABS_CNT],
    pub abs_flat: [i32; ABS_CNT],
}

impl Default for uinput_user_dev {
    fn default() -> Self {
        unsafe { mem::zeroed() }
    }
}

pub unsafe fn input_event_write(fd: c_int, type_: u32, code: u32, value: i32) {
    let event = UinputEvent {
        time: libc::timeval {
            tv_sec: 0,
            tv_usec: 0,
        },
        type_: type_ as u16,
        code: code as u16,
        value,
    };
    libc::write(
        fd,
        &event as *const UinputEvent as *const libc::c_void,
        mem::size_of::<UinputEvent>(),
    );
}

#[repr(C)]
#[derive(Clone, Copy)]
struct UinputEvent {
    time: libc::timeval,
    type_: u16,
    code: u16,
    value: i32,
}
