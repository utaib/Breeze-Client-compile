//! Discord Rich Presence over the local IPC socket.
//!
//! Written against the wire protocol directly rather than pulling in a crate.
//! The protocol is small (a handshake and one command), and adding a dependency
//! for it would mean a registry fetch this project cannot rely on. It also keeps
//! reconnection behaviour under our control, which matters because Discord is
//! routinely not running when the launcher starts and is expected to connect
//! silently whenever it does.
//!
//! Framing: every message is a little-endian u32 opcode, a little-endian u32
//! payload length, then that many bytes of JSON.
//!   opcode 0 = HANDSHAKE, 1 = FRAME, 2 = CLOSE, 3 = PING, 4 = PONG
//!
//! Transport: a named pipe on Windows (`\\.\pipe\discord-ipc-N`) and a unix
//! socket elsewhere. Discord numbers them 0 to 9 so several clients can coexist,
//! so all ten are tried before giving up.
//!
//! Everything here fails soft. Rich Presence is decoration: a user without
//! Discord, with Discord closed, or running a build with a different pipe layout
//! must see no errors and no degraded launcher.

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

#[cfg(windows)]
use std::fs::OpenOptions;
#[cfg(windows)]
type Pipe = std::fs::File;

#[cfg(unix)]
type Pipe = std::os::unix::net::UnixStream;

/// The Breeze application registered on the Discord developer portal.
const APP_ID: &str = "1493174717371388007";

const OP_HANDSHAKE: u32 = 0;
const OP_FRAME: u32 = 1;
const OP_CLOSE: u32 = 2;

/// What the frontend asks us to display. Mirrors Discord's activity payload but
/// only the parts Breeze actually sets, so a typo in an unused field is a
/// compile error rather than a silently ignored key.
#[derive(Debug, Clone, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Presence {
    /// Top line under the app name, e.g. "Browsing the store".
    pub details: Option<String>,
    /// Second line, e.g. a track title.
    pub state: Option<String>,
    pub large_image: Option<String>,
    pub large_text: Option<String>,
    pub small_image: Option<String>,
    pub small_text: Option<String>,
    /// Unix seconds. Discord renders "elapsed" from this.
    pub start: Option<i64>,
    /// Unix seconds. With `start`, Discord renders a progress bar, which is what
    /// makes a playing track show its position rather than just its name.
    pub end: Option<i64>,
}

struct Connection {
    pipe: Pipe,
}

static CONN: Mutex<Option<Connection>> = Mutex::new(None);

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[cfg(windows)]
fn open_pipe(index: u8) -> Option<Pipe> {
    // Named pipes are opened as files on Windows. read(true) matters: Discord
    // replies to the handshake and an unread reply eventually stalls the pipe.
    OpenOptions::new()
        .read(true)
        .write(true)
        .open(format!(r"\\.\pipe\discord-ipc-{index}"))
        .ok()
}

#[cfg(unix)]
fn open_pipe(index: u8) -> Option<Pipe> {
    // Discord puts its socket in whichever of these the platform uses; on Linux
    // it is frequently nested inside a Flatpak or snap directory.
    let base = std::env::var("XDG_RUNTIME_DIR")
        .or_else(|_| std::env::var("TMPDIR"))
        .unwrap_or_else(|_| "/tmp".into());
    let candidates = [
        format!("{base}/discord-ipc-{index}"),
        format!("{base}/app/com.discordapp.Discord/discord-ipc-{index}"),
        format!("{base}/snap.discord/discord-ipc-{index}"),
    ];
    candidates.iter().find_map(|p| Pipe::connect(p).ok())
}

fn write_frame(pipe: &mut Pipe, opcode: u32, payload: &Value) -> std::io::Result<()> {
    let body = serde_json::to_vec(payload)?;
    let mut buf = Vec::with_capacity(8 + body.len());
    buf.extend_from_slice(&opcode.to_le_bytes());
    buf.extend_from_slice(&(body.len() as u32).to_le_bytes());
    buf.extend_from_slice(&body);
    pipe.write_all(&buf)?;
    pipe.flush()
}

/// Read one frame and discard it.
///
/// Discord answers every command. Leaving those replies in the pipe buffer is
/// what causes presence to stop updating after a few dozen writes, which reads
/// as "the presence got stuck" rather than as an IO problem.
fn drain_frame(pipe: &mut Pipe) -> std::io::Result<()> {
    let mut header = [0u8; 8];
    pipe.read_exact(&mut header)?;
    let len = u32::from_le_bytes([header[4], header[5], header[6], header[7]]) as usize;
    // A malformed length must not become a multi-gigabyte allocation.
    if len > 1024 * 64 {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "frame too large"));
    }
    let mut body = vec![0u8; len];
    pipe.read_exact(&mut body)?;
    Ok(())
}

/// Connect and handshake. Returns None when Discord is not reachable, which is
/// an ordinary state rather than an error.
fn connect() -> Option<Connection> {
    for index in 0..10u8 {
        let Some(mut pipe) = open_pipe(index) else { continue };
        let hello = json!({ "v": 1, "client_id": APP_ID });
        if write_frame(&mut pipe, OP_HANDSHAKE, &hello).is_err() {
            continue;
        }
        // Discord replies READY. If it does not, this pipe belongs to something
        // else or the handshake was rejected; try the next one.
        if drain_frame(&mut pipe).is_err() {
            continue;
        }
        return Some(Connection { pipe });
    }
    None
}

fn activity_payload(p: &Presence) -> Value {
    let mut assets = serde_json::Map::new();
    if let Some(v) = &p.large_image { assets.insert("large_image".into(), json!(v)); }
    if let Some(v) = &p.large_text { assets.insert("large_text".into(), json!(v)); }
    if let Some(v) = &p.small_image { assets.insert("small_image".into(), json!(v)); }
    if let Some(v) = &p.small_text { assets.insert("small_text".into(), json!(v)); }

    let mut timestamps = serde_json::Map::new();
    if let Some(v) = p.start { timestamps.insert("start".into(), json!(v)); }
    if let Some(v) = p.end { timestamps.insert("end".into(), json!(v)); }

    let mut activity = serde_json::Map::new();
    // Discord rejects details/state shorter than 2 characters, and silently
    // truncates past 128. Clamp here so a short page name cannot drop the whole
    // activity payload.
    if let Some(v) = &p.details { activity.insert("details".into(), json!(clamp(v))); }
    if let Some(v) = &p.state { activity.insert("state".into(), json!(clamp(v))); }
    if !assets.is_empty() { activity.insert("assets".into(), Value::Object(assets)); }
    if !timestamps.is_empty() { activity.insert("timestamps".into(), Value::Object(timestamps)); }

    activity.insert("buttons".into(), json!([
        { "label": "Play Now", "url": "https://breezeclient.net" },
        { "label": "Join Discord", "url": "https://discord.gg/cSvBXnyKer" }
    ]));

    json!({
        "cmd": "SET_ACTIVITY",
        "nonce": format!("breeze-{}", now_secs()),
        "args": { "pid": std::process::id(), "activity": Value::Object(activity) }
    })
}

fn clamp(s: &str) -> String {
    let t = s.trim();
    if t.len() > 128 {
        // Cut on a character boundary; slicing bytes would panic on multibyte.
        t.chars().take(120).collect::<String>() + "..."
    } else if t.len() < 2 {
        format!("{t} ")
    } else {
        t.to_string()
    }
}

/// Push an activity, connecting or reconnecting as needed.
pub fn set(presence: Presence) {
    let payload = activity_payload(&presence);
    let mut guard = match CONN.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };

    if guard.is_none() {
        *guard = connect();
    }
    let Some(conn) = guard.as_mut() else { return };

    // A write failing usually means Discord closed while we held the handle.
    // Drop it and retry once, so quitting and reopening Discord reconnects
    // instead of leaving presence dead until the launcher restarts.
    let sent = write_frame(&mut conn.pipe, OP_FRAME, &payload)
        .and_then(|_| drain_frame(&mut conn.pipe));
    if sent.is_err() {
        *guard = connect();
        if let Some(conn) = guard.as_mut() {
            let _ = write_frame(&mut conn.pipe, OP_FRAME, &payload)
                .and_then(|_| drain_frame(&mut conn.pipe));
        }
    }
}

/// Remove the presence and close the socket.
///
/// Called on window close. Without it Discord keeps showing the last activity
/// until it notices the process is gone, which can leave "Browsing the store"
/// on a profile for minutes after Breeze has quit.
pub fn clear() {
    let mut guard = match CONN.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    if let Some(conn) = guard.as_mut() {
        let payload = json!({
            "cmd": "SET_ACTIVITY",
            "nonce": format!("breeze-clear-{}", now_secs()),
            "args": { "pid": std::process::id() }
        });
        let _ = write_frame(&mut conn.pipe, OP_FRAME, &payload);
        let _ = drain_frame(&mut conn.pipe);
        let _ = write_frame(&mut conn.pipe, OP_CLOSE, &json!({}));
    }
    *guard = None;
}

// ── Tauri commands ──────────────────────────────────────────────────────────

#[tauri::command]
pub fn discord_set_presence(presence: Presence) {
    // Deliberately returns nothing. Presence is decoration and the frontend has
    // no useful response to a failure, so surfacing one would only produce a
    // toast about Discord on a launcher the user is using for something else.
    set(presence);
}

#[tauri::command]
pub fn discord_clear_presence() {
    clear();
}
