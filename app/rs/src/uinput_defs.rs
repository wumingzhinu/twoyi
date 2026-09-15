// Local replacement for uinput-sys crate (avoids git dependency)
// Linux input event subsystem constants from <linux/input.h> and <linux/uinput.h>

use libc::{c_int, c_ulong, c_ushort, c_char};
use std::mem;

pub const EV_SYN: u16 = 0x00;
pub const EV_KEY: u16 = 0x01;
pub const EV_ABS: u16 = 0x03;

pub const SYN_REPORT: u16 = 0;

pub const BTN_TOUCH: u16 = 0x14a;
pub const BTN_TOOL_FINGER: u16 = 0x145;

pub const ABS_MT_SLOT: u16 = 0x2f;
pub const ABS_MT_TOUCH_MAJOR: u16 = 0x30;
pub const ABS_MT_TOUCH_MINOR: u16 = 0x31;
pub const ABS_MT_POSITION_X: u16 = 0x35;
pub const ABS_MT_POSITION_Y: u16 = 0x36;
pub const ABS_MT_PRESSURE: u16 = 0x3a;
pub const ABS_MT_TRACKING_ID: u16 = 0x39;
pub const ABS_RZ: u16 = 0x05;
pub const ABS_THROTTLE: u16 = 0x06;
pub const ABS_RUDDER: u16 = 0x07;

pub const ABS_MAX: u16 = 0x3f;
pub const ABS_CNT: usize = (ABS_MAX + 1) as usize;

pub const KEY_MAX: u16 = 0x2ff;
pub const REL_MAX: u16 = 0x0f;
pub const SW_MAX: u16 = 0x0f;
pub const LED_MAX: u16 = 0x0f;
pub const INPUT_PROP_MAX: u16 = 0x1f;
pub const INPUT_PROP_BUTTONPAD: u16 = 0x02;

pub const G_INPUT_MT: u16 = 0;

#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct input_id {
    pub bustype: c_ushort,
    pub vendor: c_ushort,
    pub product: c_ushort,
    pub version: c_ushort,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct uinput_user_dev {
    pub name: [c_char; 80],
    pub id: input_id,
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

pub unsafe fn input_event_write(fd: c_int, type_: u16, code: u16, value: i32) {
    let event = input_event {
        time: libc::timeval {
            tv_sec: 0,
            tv_usec: 0,
        },
        type_,
        code,
        value,
    };
    libc::write(
        fd,
        &event as *const input_event as *const libc::c_void,
        mem::size_of::<input_event>(),
    );
}

#[repr(C)]
#[derive(Clone, Copy)]
struct input_event {
    time: libc::timeval,
    type_: u16,
    code: u16,
    value: i32,
}
