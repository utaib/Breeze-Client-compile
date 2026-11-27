//! The game's own sound for the Breeze recorder (launcher 1.2.3, mod 2.30.0).
//!
//! The mod records the screen and the microphone itself; the game's sound has
//! to be taken from Windows, which can hand one program's sound (and the
//! programs it started) to another since Windows 10 version 2004: WASAPI
//! "process loopback". Only the game is recorded, never Discord, Spotify or
//! anything else playing. The launcher started the game, so it knows its
//! process id, and it keeps running while the game plays.
//!
//! The mod and the launcher talk through files in the instance's
//! `recordings/.sound/` folder, the way `recordings/.open` and
//! `ffmpeg/wanted` already work (no socket, no port; SECURITY_BACKLOG.md):
//!
//! - `ready`, written here when the game starts and removed when it ends:
//!   `pid=<game pid>`, `game=1` when this system can record it, else `game=0`
//!   and a `reason=` the mod shows in its preferences. The mod believes it
//!   only when the pid is its own.
//! - `start` (from the mod, `name=<recording>`): recording the game's sound
//!   begins into `<recording>.game.pcm` (16-bit little-endian, 48 kHz,
//!   stereo) with `<recording>.game.info`, which says the first sample's
//!   moment in microseconds since 1970 so the mod can line it up with its
//!   video, and `state=recording|done|failed`.
//! - `stop` (from the mod, the same name): the track ends and its info says
//!   done. It also ends when the game exits.
//!
//! The launcher writes only inside that folder, under a name it checks
//! (letters, digits, `.`, `_`, `-`), and records only the process it started.
//! Holes (Windows sends nothing while the game is silent) are filled with
//! silence by the clock, as the mod's own tracks are (`SoundTrack.java`).

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub const DIR: &str = ".sound";
pub const RATE: u32 = 48_000;
pub const CHANNELS: u16 = 2;
/// A hole longer than this is filled with silence (as in the mod).
pub const GAP_MS: i64 = 60;

/// The folder the mod and the launcher share for one instance.
pub fn dir(game_dir: &Path) -> PathBuf {
    game_dir.join("recordings").join(DIR)
}

/// A recording name the launcher accepts: 1 to 96 of letters, digits, `.`, `_`, `-`, never `..`.
pub fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 96
        && !name.contains("..")
        && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-')
}

/// The `name=` of a start or stop request, when it is a valid one.
pub fn request_name(text: &str) -> Option<String> {
    text.lines()
        .filter_map(|l| l.trim().strip_prefix("name="))
        .map(|n| n.trim().to_string())
        .find(|n| valid_name(n))
}

/// What `ready` says for this game.
pub fn ready_text(pid: u32, support: &Result<(), String>) -> String {
    match support {
        Ok(()) => format!("pid={pid}\ngame=1\nversion=1\n"),
        Err(reason) => format!("pid={pid}\ngame=0\nversion=1\nreason={}\n", reason.replace('\n', " ")),
    }
}

/// Microseconds since 1970 by the system's clock (the mod ties its own clock to it).
pub fn system_us() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_micros() as i64).unwrap_or(0)
}

/// Whether this system can record one program's sound, else why not (in words for the player).
pub fn supported() -> Result<(), String> {
    #[cfg(windows)]
    {
        wasapi::supported()
    }
    #[cfg(not(windows))]
    {
        Err("Game sound is recorded on Windows 10 version 2004 and later. The microphone works here.".into())
    }
}

/// One track being written: samples to `<name>.game.pcm`, its facts to `<name>.game.info`.
pub struct TrackWriter {
    pcm: fs::File,
    info: PathBuf,
    rate: u32,
    channels: u16,
    start_us: Option<i64>,
    frames: i64,
    filled: i64,
    last_end_us: i64,
}

impl TrackWriter {
    pub fn create(pcm: &Path, info: &Path, rate: u32, channels: u16) -> std::io::Result<Self> {
        if let Some(parent) = pcm.parent() {
            fs::create_dir_all(parent)?;
        }
        let file = fs::File::create(pcm)?;
        let w = TrackWriter {
            pcm: file,
            info: info.to_path_buf(),
            rate,
            channels,
            start_us: None,
            frames: 0,
            filled: 0,
            last_end_us: 0,
        };
        w.write_info("recording", None)?;
        Ok(w)
    }

    fn frame_bytes(&self) -> usize {
        2 * self.channels as usize
    }

    #[allow(dead_code)]
    pub fn frames(&self) -> i64 {
        self.frames
    }

    #[allow(dead_code)]
    pub fn filled(&self) -> i64 {
        self.filled
    }

    pub fn start_us(&self) -> Option<i64> {
        self.start_us
    }

    /// A piece of sound (whole frames) whose last sample was heard at `end_us`.
    pub fn write(&mut self, bytes: &[u8], end_us: i64) -> std::io::Result<()> {
        let n = (bytes.len() / self.frame_bytes()) as i64;
        if n == 0 {
            return Ok(());
        }
        let start = match self.start_us {
            Some(s) => s,
            None => {
                let s = end_us - n * 1_000_000 / self.rate as i64;
                self.start_us = Some(s);
                self.write_info("recording", None)?;
                s
            }
        };
        let expected = (end_us - start) * self.rate as i64 / 1_000_000;
        let missing = expected - (self.frames + n);
        if missing * 1000 > GAP_MS * self.rate as i64 {
            self.silence(missing)?;
        }
        self.pcm.write_all(&bytes[..n as usize * self.frame_bytes()])?;
        self.frames += n;
        self.last_end_us = end_us;
        Ok(())
    }

    /// `n` frames of silence (also what Windows marks as silent).
    pub fn silence(&mut self, n: i64) -> std::io::Result<()> {
        if n <= 0 {
            return Ok(());
        }
        let zero = vec![0u8; 1 << 16];
        let mut left = n as usize * self.frame_bytes();
        while left > 0 {
            let k = left.min(zero.len());
            self.pcm.write_all(&zero[..k])?;
            left -= k;
        }
        self.frames += n;
        self.filled += n;
        Ok(())
    }

    /// Silence written for a piece Windows marked silent: counted as sound, not as a hole.
    pub fn write_silent(&mut self, n: usize, end_us: i64) -> std::io::Result<()> {
        let zeros = vec![0u8; n * self.frame_bytes()];
        self.write(&zeros, end_us)
    }

    /// Ends the track at `end_us` (silence up to then if the game was quiet at the end).
    pub fn finish(mut self, end_us: i64) -> std::io::Result<()> {
        if let Some(start) = self.start_us {
            if end_us > self.last_end_us {
                let expected = (end_us - start) * self.rate as i64 / 1_000_000;
                let missing = expected - self.frames;
                if missing * 1000 > GAP_MS * self.rate as i64 {
                    self.silence(missing)?;
                }
            }
        } else {
            // Not a sound all along: the track is silence from the start request to now.
            self.start_us = Some(self.last_end_us.max(0));
        }
        self.pcm.flush()?;
        self.pcm.sync_data().ok();
        self.write_info("done", None)
    }

    /// Ends the track as failed, with the reason in words.
    pub fn fail(mut self, error: &str) -> std::io::Result<()> {
        self.pcm.flush().ok();
        self.write_info("failed", Some(error))
    }

    fn write_info(&self, state: &str, error: Option<&str>) -> std::io::Result<()> {
        let mut text = format!(
            "rate={}\nchannels={}\nformat=s16le\nstart_us={}\nframes={}\nstate={}\n",
            self.rate,
            self.channels,
            self.start_us.unwrap_or(0),
            self.frames,
            state
        );
        if let Some(e) = error {
            text.push_str(&format!("error={}\n", e.replace('\n', " ")));
        }
        let tmp = self.info.with_extension("info.tmp");
        fs::write(&tmp, text)?;
        fs::rename(&tmp, &self.info)
    }
}

/// Watches one running game's `recordings/.sound/` folder; [`Watch::end`] when the game exits.
pub struct Watch {
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl Watch {
    pub fn end(mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

struct Running {
    name: String,
    stop: Arc<AtomicBool>,
    handle: JoinHandle<()>,
}

impl Running {
    fn end(self) {
        self.stop.store(true, Ordering::SeqCst);
        let _ = self.handle.join();
    }
}

/// Writes `ready` for the game `pid` and answers the mod's requests until the game ends.
pub fn watch(game_dir: &Path, pid: u32) -> Watch {
    let folder = dir(game_dir);
    let support = supported();
    let _ = fs::create_dir_all(&folder);
    // Requests left by an earlier game mean nothing to this one.
    let _ = fs::remove_file(folder.join("start"));
    let _ = fs::remove_file(folder.join("stop"));
    let _ = fs::write(folder.join("ready"), ready_text(pid, &support));
    let stop = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&stop);
    let handle = thread::Builder::new()
        .name("breeze-game-sound".into())
        .spawn(move || {
            let mut running: Option<Running> = None;
            while !flag.load(Ordering::SeqCst) {
                thread::sleep(Duration::from_millis(100));
                if let Some(name) = take_request(&folder.join("start")) {
                    if support.is_ok() {
                        if let Some(r) = running.take() {
                            r.end();
                        }
                        running = Some(start_track(&folder, &name, pid));
                    }
                }
                if let Some(name) = take_request(&folder.join("stop")) {
                    if running.as_ref().map(|r| r.name == name).unwrap_or(false) {
                        if let Some(r) = running.take() {
                            r.end();
                        }
                    }
                }
            }
            if let Some(r) = running.take() {
                r.end();
            }
            let _ = fs::remove_file(folder.join("ready"));
        })
        .ok();
    Watch { stop, handle }
}

/// Reads and deletes a request file; its name when valid.
fn take_request(path: &Path) -> Option<String> {
    let text = fs::read_to_string(path).ok()?;
    let _ = fs::remove_file(path);
    request_name(&text)
}

fn start_track(folder: &Path, name: &str, pid: u32) -> Running {
    let stop = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&stop);
    let pcm = folder.join(format!("{name}.game.pcm"));
    let info = folder.join(format!("{name}.game.info"));
    let handle = thread::spawn(move || {
        let mut writer = match TrackWriter::create(&pcm, &info, RATE, CHANNELS) {
            Ok(w) => w,
            Err(e) => {
                eprintln!("[breeze] game sound: {e}");
                return;
            }
        };
        let requested_us = system_us();
        let result = capture(pid, &mut writer, &flag);
        match result {
            Ok(()) => {
                if writer.start_us().is_none() {
                    // Silent all along: a track of silence from the request on.
                    writer.start_us = Some(requested_us);
                    writer.last_end_us = requested_us;
                }
                let _ = writer.finish(system_us());
            }
            Err(e) => {
                let _ = writer.fail(&e);
            }
        }
    });
    Running { name: name.to_string(), stop, handle }
}

/// Records the sound of `pid` and its child processes until `stop`.
fn capture(pid: u32, writer: &mut TrackWriter, stop: &AtomicBool) -> Result<(), String> {
    #[cfg(windows)]
    {
        wasapi::capture(pid, writer, stop)
    }
    #[cfg(not(windows))]
    {
        let _ = (pid, writer, stop);
        Err("Game sound is recorded on Windows 10 version 2004 and later.".into())
    }
}

#[cfg(windows)]
pub mod wasapi {
    //! WASAPI process loopback (Windows 10 2004, build 19041, and later):
    //! `ActivateAudioInterfaceAsync` on the virtual device
    //! `VAD\Process_Loopback` with the game's process id and its process tree,
    //! then a shared-mode, event-driven capture client asked for 48 kHz 16-bit
    //! stereo (Windows converts). The COM calls are made through the
    //! interfaces' tables by hand, as windows-sys has no COM wrappers.

    use super::TrackWriter;
    use std::ffi::c_void;
    use std::ptr::{null, null_mut};
    use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
    use windows_sys::core::{GUID, HRESULT};
    use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
    use windows_sys::Win32::Media::Audio::{
        ActivateAudioInterfaceAsync, AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
        AUDCLNT_STREAMFLAGS_EVENTCALLBACK, AUDCLNT_STREAMFLAGS_LOOPBACK, VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, WAVEFORMATEX,
        WAVE_FORMAT_PCM,
    };
    use windows_sys::Win32::System::Com::{CoInitializeEx, CoUninitialize, COINIT_MULTITHREADED};
    use windows_sys::Win32::System::Threading::{CreateEventW, SetEvent, WaitForSingleObject};

    const IID_IUNKNOWN: GUID = GUID::from_u128(0x00000000_0000_0000_c000_000000000046);
    const IID_IAGILE_OBJECT: GUID = GUID::from_u128(0x94ea2b94_e9cc_49e0_c0ff_ee64ca8f5b90);
    const IID_COMPLETION_HANDLER: GUID = GUID::from_u128(0x41d949ab_9862_444a_80f6_c261334da5eb);
    const IID_IAUDIO_CLIENT: GUID = GUID::from_u128(0x1cb9ad4c_dbfa_4c32_b178_c2f568a703b2);
    const IID_IAUDIO_CAPTURE_CLIENT: GUID = GUID::from_u128(0xc8adbd64_e71e_48a0_a4de_185c395cd317);

    const E_NOINTERFACE: HRESULT = 0x80004002u32 as i32;
    const VT_BLOB: u16 = 65;
    const AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK: i32 = 1;
    const PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE: i32 = 0;

    /// AUDIOCLIENT_ACTIVATION_PARAMS with its process loopback member.
    #[repr(C)]
    struct ActivationParams {
        activation_type: i32,
        target_process_id: u32,
        process_loopback_mode: i32,
    }

    /// A PROPVARIANT holding a VT_BLOB (the only member used), laid out as on 64-bit Windows.
    #[repr(C)]
    struct BlobVariant {
        vt: u16,
        reserved1: u16,
        reserved2: u16,
        reserved3: u16,
        cb_size: u32,
        p_blob_data: *mut c_void,
        _pad: usize,
    }

    #[repr(C)]
    struct UnknownVtbl {
        query_interface: unsafe extern "system" fn(*mut c_void, *const GUID, *mut *mut c_void) -> HRESULT,
        add_ref: unsafe extern "system" fn(*mut c_void) -> u32,
        release: unsafe extern "system" fn(*mut c_void) -> u32,
    }

    #[repr(C)]
    struct AsyncOperationVtbl {
        base: UnknownVtbl,
        get_activate_result: unsafe extern "system" fn(*mut c_void, *mut HRESULT, *mut *mut c_void) -> HRESULT,
    }

    #[repr(C)]
    struct AudioClientVtbl {
        base: UnknownVtbl,
        initialize: unsafe extern "system" fn(*mut c_void, i32, u32, i64, i64, *const WAVEFORMATEX, *const GUID) -> HRESULT,
        get_buffer_size: usize,
        get_stream_latency: usize,
        get_current_padding: usize,
        is_format_supported: usize,
        get_mix_format: usize,
        get_device_period: usize,
        start: unsafe extern "system" fn(*mut c_void) -> HRESULT,
        stop: unsafe extern "system" fn(*mut c_void) -> HRESULT,
        reset: usize,
        set_event_handle: unsafe extern "system" fn(*mut c_void, HANDLE) -> HRESULT,
        get_service: unsafe extern "system" fn(*mut c_void, *const GUID, *mut *mut c_void) -> HRESULT,
    }

    #[repr(C)]
    struct CaptureClientVtbl {
        base: UnknownVtbl,
        get_buffer: unsafe extern "system" fn(*mut c_void, *mut *mut u8, *mut u32, *mut u32, *mut u64, *mut u64) -> HRESULT,
        release_buffer: unsafe extern "system" fn(*mut c_void, u32) -> HRESULT,
        get_next_packet_size: unsafe extern "system" fn(*mut c_void, *mut u32) -> HRESULT,
    }

    /// The completion handler ActivateAudioInterfaceAsync calls (also agile, as it must be).
    #[repr(C)]
    struct Handler {
        vtbl: *const HandlerVtbl,
        refs: AtomicU32,
        event: HANDLE,
    }

    #[repr(C)]
    struct HandlerVtbl {
        base: UnknownVtbl,
        activate_completed: unsafe extern "system" fn(*mut c_void, *mut c_void) -> HRESULT,
    }

    unsafe extern "system" fn h_query(this: *mut c_void, iid: *const GUID, out: *mut *mut c_void) -> HRESULT {
        let iid = &*iid;
        if guid_eq(iid, &IID_IUNKNOWN) || guid_eq(iid, &IID_IAGILE_OBJECT) || guid_eq(iid, &IID_COMPLETION_HANDLER) {
            h_add_ref(this);
            *out = this;
            0
        } else {
            *out = null_mut();
            E_NOINTERFACE
        }
    }

    unsafe extern "system" fn h_add_ref(this: *mut c_void) -> u32 {
        (*(this as *mut Handler)).refs.fetch_add(1, Ordering::SeqCst) + 1
    }

    unsafe extern "system" fn h_release(this: *mut c_void) -> u32 {
        let left = (*(this as *mut Handler)).refs.fetch_sub(1, Ordering::SeqCst) - 1;
        if left == 0 {
            drop(Box::from_raw(this as *mut Handler));
        }
        left
    }

    unsafe extern "system" fn h_completed(this: *mut c_void, _operation: *mut c_void) -> HRESULT {
        SetEvent((*(this as *mut Handler)).event);
        0
    }

    static HANDLER_VTBL: HandlerVtbl = HandlerVtbl {
        base: UnknownVtbl { query_interface: h_query, add_ref: h_add_ref, release: h_release },
        activate_completed: h_completed,
    };

    fn guid_eq(a: &GUID, b: &GUID) -> bool {
        a.data1 == b.data1 && a.data2 == b.data2 && a.data3 == b.data3 && a.data4 == b.data4
    }

    unsafe fn release(p: *mut c_void) {
        if !p.is_null() {
            let v = *(p as *const *const UnknownVtbl);
            ((*v).release)(p);
        }
    }

    fn check(hr: HRESULT, what: &str) -> Result<(), String> {
        if hr < 0 {
            Err(format!("{what} failed (0x{:08X})", hr as u32))
        } else {
            Ok(())
        }
    }

    /// Windows' build number (CurrentBuildNumber in the registry), when it can be read.
    pub fn build_number() -> Option<u32> {
        use windows_sys::Win32::System::Registry::{RegGetValueW, HKEY_LOCAL_MACHINE, RRF_RT_REG_SZ};
        let key: Vec<u16> = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\0".encode_utf16().collect();
        let value: Vec<u16> = "CurrentBuildNumber\0".encode_utf16().collect();
        let mut buf = [0u16; 32];
        let mut size = (buf.len() * 2) as u32;
        let rc = unsafe {
            RegGetValueW(HKEY_LOCAL_MACHINE, key.as_ptr(), value.as_ptr(), RRF_RT_REG_SZ, null_mut(), buf.as_mut_ptr() as *mut c_void, &mut size)
        };
        if rc != 0 {
            return None;
        }
        let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
        String::from_utf16_lossy(&buf[..len]).trim().parse().ok()
    }

    pub fn supported() -> Result<(), String> {
        match build_number() {
            Some(b) if b < 19041 => Err("Game sound needs Windows 10 version 2004 or later. The microphone works here.".into()),
            _ => Ok(()),
        }
    }

    /// The format asked for: 48 kHz, 16-bit, stereo.
    fn format() -> WAVEFORMATEX {
        let channels = super::CHANNELS;
        let block = channels * 2;
        WAVEFORMATEX {
            wFormatTag: WAVE_FORMAT_PCM as u16,
            nChannels: channels,
            nSamplesPerSec: super::RATE,
            nAvgBytesPerSec: super::RATE * block as u32,
            nBlockAlign: block,
            wBitsPerSample: 16,
            cbSize: 0,
        }
    }

    /// Activates a process loopback audio client for `pid` (and its children).
    unsafe fn activate(pid: u32) -> Result<*mut c_void, String> {
        let event = CreateEventW(null(), 0, 0, null());
        if event.is_null() {
            return Err("CreateEvent failed".into());
        }
        let mut params = ActivationParams {
            activation_type: AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK,
            target_process_id: pid,
            process_loopback_mode: PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE,
        };
        let variant = BlobVariant {
            vt: VT_BLOB,
            reserved1: 0,
            reserved2: 0,
            reserved3: 0,
            cb_size: std::mem::size_of::<ActivationParams>() as u32,
            p_blob_data: &mut params as *mut ActivationParams as *mut c_void,
            _pad: 0,
        };
        let handler = Box::into_raw(Box::new(Handler { vtbl: &HANDLER_VTBL, refs: AtomicU32::new(1), event }));
        let mut operation: *mut c_void = null_mut();
        let hr = ActivateAudioInterfaceAsync(
            VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK,
            &IID_IAUDIO_CLIENT,
            &variant as *const BlobVariant as *const _,
            handler as *mut c_void,
            &mut operation,
        );
        let result = (|| {
            check(hr, "ActivateAudioInterfaceAsync")?;
            if WaitForSingleObject(event, 10_000) != 0 {
                return Err("Windows did not answer the request for the game's sound".to_string());
            }
            let vtbl = *(operation as *const *const AsyncOperationVtbl);
            let mut activate_hr: HRESULT = 0;
            let mut unknown: *mut c_void = null_mut();
            check(((*vtbl).get_activate_result)(operation, &mut activate_hr, &mut unknown), "GetActivateResult")?;
            check(activate_hr, "Activating the game's sound")?;
            let mut client: *mut c_void = null_mut();
            let qv = *(unknown as *const *const UnknownVtbl);
            let qhr = ((*qv).query_interface)(unknown, &IID_IAUDIO_CLIENT, &mut client);
            release(unknown);
            check(qhr, "IAudioClient")?;
            Ok(client)
        })();
        release(operation);
        h_release(handler as *mut c_void);
        CloseHandle(event);
        result
    }

    /// Process loopback's position is in 100 ns units of the performance counter's time.
    fn qpc_100ns() -> i64 {
        use windows_sys::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};
        let (mut c, mut f) = (0i64, 0i64);
        unsafe {
            QueryPerformanceCounter(&mut c);
            QueryPerformanceFrequency(&mut f);
        }
        if f <= 0 {
            return 0;
        }
        ((c as i128) * 10_000_000 / f as i128) as i64
    }

    pub fn capture(pid: u32, writer: &mut TrackWriter, stop: &AtomicBool) -> Result<(), String> {
        unsafe {
            let init = CoInitializeEx(null(), COINIT_MULTITHREADED as u32);
            let result = capture_inner(pid, writer, stop);
            if init >= 0 {
                CoUninitialize();
            }
            result
        }
    }

    unsafe fn capture_inner(pid: u32, writer: &mut TrackWriter, stop: &AtomicBool) -> Result<(), String> {
        let client = activate(pid)?;
        let cv = *(client as *const *const AudioClientVtbl);
        let fmt = format();
        let event = CreateEventW(null(), 0, 0, null());
        let mut capture_client: *mut c_void = null_mut();
        let result = (|| {
            check(
                ((*cv).initialize)(
                    client,
                    AUDCLNT_SHAREMODE_SHARED,
                    AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM,
                    200_000,
                    0,
                    &fmt,
                    null(),
                ),
                "IAudioClient::Initialize",
            )?;
            check(((*cv).set_event_handle)(client, event), "SetEventHandle")?;
            check(((*cv).get_service)(client, &IID_IAUDIO_CAPTURE_CLIENT, &mut capture_client), "GetService")?;
            let cc = *(capture_client as *const *const CaptureClientVtbl);
            check(((*cv).start)(client), "IAudioClient::Start")?;
            let frame_bytes = fmt.nBlockAlign as usize;
            while !stop.load(Ordering::SeqCst) {
                WaitForSingleObject(event, 100);
                loop {
                    let mut packet = 0u32;
                    check(((*cc).get_next_packet_size)(capture_client, &mut packet), "GetNextPacketSize")?;
                    if packet == 0 {
                        break;
                    }
                    let mut data: *mut u8 = null_mut();
                    let (mut frames, mut flags, mut dev_pos, mut qpc_pos) = (0u32, 0u32, 0u64, 0u64);
                    check(
                        ((*cc).get_buffer)(capture_client, &mut data, &mut frames, &mut flags, &mut dev_pos, &mut qpc_pos),
                        "GetBuffer",
                    )?;
                    // The packet's first sample was heard at qpc_pos: its last at that plus its length.
                    let now_us = super::system_us();
                    let age_us = if qpc_pos > 0 { (qpc_100ns() - qpc_pos as i64).max(0) / 10 } else { 0 };
                    let end_us = now_us - age_us + frames as i64 * 1_000_000 / super::RATE as i64;
                    let written = if flags & (AUDCLNT_BUFFERFLAGS_SILENT as u32) != 0 || data.is_null() {
                        writer.write_silent(frames as usize, end_us)
                    } else {
                        let bytes = std::slice::from_raw_parts(data, frames as usize * frame_bytes);
                        writer.write(bytes, end_us)
                    };
                    ((*cc).release_buffer)(capture_client, frames);
                    written.map_err(|e| format!("the game's sound could not be written: {e}"))?;
                }
            }
            ((*cv).stop)(client);
            Ok(())
        })();
        release(capture_client);
        release(client);
        if !event.is_null() {
            CloseHandle(event);
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("breeze-game-sound-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn names_are_checked() {
        assert!(valid_name("2026-11-26_10.00.00"));
        assert!(valid_name("rec_1"));
        assert!(!valid_name(""));
        assert!(!valid_name("../x"));
        assert!(!valid_name("a/b"));
        assert!(!valid_name("a\\b"));
        assert!(!valid_name(".."));
        assert!(!valid_name(&"a".repeat(97)));
        assert_eq!(request_name("name=rec1\n"), Some("rec1".into()));
        assert_eq!(request_name("name=../../x\n"), None);
        assert_eq!(request_name("nothing"), None);
    }

    #[test]
    fn ready_says_why_not() {
        assert_eq!(ready_text(7, &Ok(())), "pid=7\ngame=1\nversion=1\n");
        let t = ready_text(7, &Err("Needs Windows.".into()));
        assert!(t.contains("game=0") && t.contains("reason=Needs Windows."));
    }

    /// Pieces with a 500 ms hole: the file keeps the time, the info says done.
    #[test]
    fn holes_are_filled() {
        let d = temp("holes");
        let (pcm, info) = (d.join("a.game.pcm"), d.join("a.game.info"));
        let mut w = TrackWriter::create(&pcm, &info, RATE, CHANNELS).unwrap();
        let piece = vec![1u8; 960 * 4];
        let mut us = 1_700_000_000_000_000i64;
        for _ in 0..50 {
            us += 20_000;
            w.write(&piece, us).unwrap();
        }
        us += 500_000;
        for _ in 0..50 {
            us += 20_000;
            w.write(&piece, us).unwrap();
        }
        let start = w.start_us().unwrap();
        let end = us + 300_000;
        w.finish(end).unwrap();
        let frames = fs::metadata(&pcm).unwrap().len() as i64 / 4;
        let expected = (end - start) * RATE as i64 / 1_000_000;
        assert!((frames - expected).abs() <= RATE as i64 * GAP_MS / 1000, "{frames} vs {expected}");
        let text = fs::read_to_string(&info).unwrap();
        assert!(text.contains("state=done"), "{text}");
        assert!(text.contains(&format!("start_us={start}")), "{text}");
        assert!(text.contains("rate=48000") && text.contains("channels=2"));
        let _ = fs::remove_dir_all(&d);
    }

    /// The exchange with the mod: ready with the pid, start and stop answered,
    /// ready gone after the game. On systems without process loopback the
    /// start is ignored and ready says why.
    #[test]
    fn watch_answers_requests() {
        let d = temp("watch");
        let w = watch(&d, 4242);
        let folder = dir(&d);
        let ready = fs::read_to_string(folder.join("ready")).unwrap();
        assert!(ready.starts_with("pid=4242\n"), "{ready}");
        fs::write(folder.join("start"), "name=../evil\n").unwrap();
        thread::sleep(Duration::from_millis(400));
        assert!(!folder.join("start").exists(), "a bad request is removed");
        assert!(!d.join("evil.game.pcm").exists());
        fs::write(folder.join("start"), "name=rec1\n").unwrap();
        thread::sleep(Duration::from_millis(600));
        fs::write(folder.join("stop"), "name=rec1\n").unwrap();
        thread::sleep(Duration::from_millis(600));
        let info = fs::read_to_string(folder.join("rec1.game.info"));
        if supported().is_ok() {
            // Windows: the capture of a process id that plays nothing either
            // records silence or says why Windows refused; either way the info is final.
            let text = info.expect("info written");
            assert!(text.contains("state=done") || text.contains("state=failed"), "{text}");
            println!("process loopback on this machine: {}", text.replace('\n', " "));
        } else {
            assert!(info.is_err(), "no track where game sound is not supported");
            assert!(ready.contains("game=0"));
        }
        w.end();
        assert!(!folder.join("ready").exists());
        let _ = fs::remove_dir_all(&d);
    }

    /// Windows only: process loopback of this test's own process, which plays
    /// nothing, for one second. The build machines have no sound device, so
    /// this prints what Windows answered rather than requiring sound.
    #[cfg(windows)]
    #[test]
    fn process_loopback_on_this_machine() {
        let d = temp("loopback");
        let (pcm, info) = (d.join("self.game.pcm"), d.join("self.game.info"));
        let mut w = TrackWriter::create(&pcm, &info, RATE, CHANNELS).unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let flag = Arc::clone(&stop);
        let stopper = thread::spawn(move || {
            thread::sleep(Duration::from_secs(1));
            flag.store(true, Ordering::SeqCst);
        });
        let r = capture(std::process::id(), &mut w, &stop);
        stopper.join().unwrap();
        println!(
            "process loopback (build {:?}): {:?}, frames {} (filled {})",
            wasapi::build_number(),
            r,
            w.frames(),
            w.filled()
        );
        let _ = fs::remove_dir_all(&d);
    }
}
