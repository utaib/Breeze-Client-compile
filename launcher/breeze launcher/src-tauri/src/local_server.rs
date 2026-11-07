//! A Minecraft server on the player's own computer (1.1.0 directive, item
//! fourteen). The page asks first: what the server uses (processor, memory,
//! network, power), what others can reach, and the Minecraft EULA. Nothing is
//! written or started before that, a server only runs while the launcher
//! does, and stopping one asks the server to save and stop before anything
//! is killed.
//!
//! This file holds the rules and the running servers; the Tauri commands that
//! use them are in lib.rs next to the rest of the launcher's commands.

use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    io::Write,
    net::{TcpListener, UdpSocket},
    process::{Child, ChildStdin},
    sync::{Mutex, OnceLock},
    thread,
    time::{Duration, Instant},
};

/// What Breeze knows about a server, kept in its folder.
pub const META_FILE: &str = "breeze-server.json";
/// How long a server has to save its world after `stop` before it is killed.
pub const STOP_GRACE: Duration = Duration::from_secs(45);

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ServerMeta {
    pub id: String,
    pub name: String,
    pub loader: String,
    pub version: String,
    pub port: u16,
    pub ram_mb: u32,
    /// Unix seconds.
    pub created_at: u64,
    /// When the player accepted the Minecraft EULA on the page that made it (Unix seconds).
    pub eula_accepted_at: u64,
    #[serde(default)]
    pub java_major: Option<u32>,
    #[serde(default)]
    pub java_component: Option<String>,
}

pub fn unix_now() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

/// The server software Breeze can fetch from its makers' own APIs.
pub fn normalize_loader(loader: &str) -> Option<&'static str> {
    match loader.trim().to_ascii_lowercase().as_str() {
        "fabric" => Some("fabric"),
        "paper" => Some("paper"),
        "vanilla" => Some("vanilla"),
        _ => None,
    }
}

/// A Minecraft version id, as it goes into download URLs.
pub fn valid_version(version: &str) -> bool {
    !version.is_empty()
        && version.len() <= 32
        && version.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_')
}

/// Ports below 1024 need administrator rights on most systems.
pub fn valid_port(port: u16) -> bool {
    port >= 1024
}

/// The server's name as shown in the server list (its MOTD) and the page.
pub fn clean_name(name: &str) -> String {
    let cleaned: String = name.chars().filter(|c| !c.is_control()).collect();
    cleaned.trim().chars().take(48).collect()
}

/// A line typed into the console: one command, without the slash.
pub fn clean_console_command(input: &str) -> Option<String> {
    let line = input.trim();
    let line = line.strip_prefix('/').unwrap_or(line).trim();
    if line.is_empty() || line.chars().count() > 256 || line.chars().any(char::is_control) {
        return None;
    }
    Some(line.to_string())
}

/// Whether something already listens on the port.
pub fn port_free(port: u16) -> bool {
    TcpListener::bind(("0.0.0.0", port)).is_ok()
}

/// This computer's address on its local network, for friends in the same
/// house. Connecting a UDP socket sends nothing; it only picks the route.
pub fn lan_address() -> Option<String> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    let ip = socket.local_addr().ok()?.ip();
    if ip.is_loopback() || ip.is_unspecified() {
        None
    } else {
        Some(ip.to_string())
    }
}

/// Fabric's server launcher for a game, loader and installer version.
pub fn fabric_server_jar_url(game: &str, loader: &str, installer: &str) -> String {
    format!("https://meta.fabricmc.net/v2/versions/loader/{game}/{loader}/{installer}/server/jar")
}

/// The first stable entry's version in a Fabric meta list, else the first.
pub fn pick_fabric_version(items: &[serde_json::Value], nested: Option<&str>) -> Option<String> {
    let version_of = |item: &serde_json::Value| -> Option<(String, bool)> {
        let entry = match nested {
            Some(key) => item.get(key)?,
            None => item,
        };
        let version = entry.get("version")?.as_str()?;
        // Loader versions can carry "+build"; nothing else outside a version id.
        if version.is_empty() || version.len() > 40 || !version.bytes().all(|b| b.is_ascii_alphanumeric() || b".+-_".contains(&b)) {
            return None;
        }
        Some((version.to_string(), entry.get("stable").and_then(|s| s.as_bool()).unwrap_or(false)))
    };
    let all: Vec<(String, bool)> = items.iter().filter_map(version_of).collect();
    all.iter().find(|(_, stable)| *stable).or_else(|| all.first()).map(|(v, _)| v.clone())
}

/// The server.properties Breeze writes for a new server.
pub fn server_properties(name: &str, port: u16) -> String {
    format!(
        "motd={}\nserver-port={}\nonline-mode=true\nmax-players=10\nview-distance=8\nsimulation-distance=6\nenable-query=false\nenable-rcon=false\n",
        clean_name(name),
        port
    )
}

/// Start a server's jar with the given Java and heap, its console on a piped
/// stdin and its output in logs/breeze-latest.log (fresh for each run). The
/// launcher's Start and the live test below both start servers this way.
pub fn spawn_server(dir: &std::path::Path, java: &std::path::Path, ram_mb: u32) -> Result<(Running, std::path::PathBuf), String> {
    let logs_dir = dir.join("logs");
    std::fs::create_dir_all(&logs_dir).map_err(|e| format!("Could not create the logs folder: {e}"))?;
    let log_path = logs_dir.join("breeze-latest.log");
    let stdout = std::fs::OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&log_path)
        .map_err(|e| format!("Could not open the server log: {e}"))?;
    let stderr = stdout.try_clone().map_err(|e| format!("Could not open the server log: {e}"))?;
    let mut command = std::process::Command::new(java);
    command
        .current_dir(dir)
        .arg("-Xms512M")
        .arg(format!("-Xmx{}M", ram_mb))
        .arg("-jar")
        .arg(dir.join("server.jar"))
        .arg("nogui")
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::from(stdout))
        .stderr(std::process::Stdio::from(stderr));
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        // CREATE_NO_WINDOW: no console window pops up next to the launcher.
        command.creation_flags(0x0800_0000);
    }
    let mut child = command.spawn().map_err(|e| format!("Could not start the server: {e}"))?;
    #[cfg(windows)]
    win_job::bind(&child);
    let stdin = child.stdin.take();
    Ok((Running { child, stdin, stopping: false }, log_path))
}

/// On Windows every server joins one job object that Windows ends together
/// with the launcher, so a launcher that crashes or is ended from Task
/// Manager (when the save-first stop in `stop_all_blocking` cannot run)
/// leaves no server using the computer. A normal close still stops each
/// server with "stop" first.
#[cfg(windows)]
mod win_job {
    use std::os::windows::io::AsRawHandle;
    use std::sync::OnceLock;
    use windows_sys::Win32::Foundation::HANDLE;
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation, SetInformationJobObject,
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    struct Job(HANDLE);
    // The handle is only passed to Windows, which allows that from any thread.
    unsafe impl Send for Job {}
    unsafe impl Sync for Job {}

    fn job() -> Option<&'static Job> {
        static JOB: OnceLock<Option<Job>> = OnceLock::new();
        JOB.get_or_init(|| unsafe {
            let handle = CreateJobObjectW(std::ptr::null(), std::ptr::null());
            if handle.is_null() {
                return None;
            }
            let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            let ok = SetInformationJobObject(
                handle,
                JobObjectExtendedLimitInformation,
                &info as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION as *const core::ffi::c_void,
                std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            );
            // The handle stays open for the launcher's lifetime: closing it is what ends the job.
            if ok == 0 { None } else { Some(Job(handle)) }
        })
        .as_ref()
    }

    pub fn bind(child: &std::process::Child) {
        if let Some(job) = job() {
            unsafe {
                AssignProcessToJobObject(job.0, child.as_raw_handle() as HANDLE);
            }
        }
    }
}

/// The most memory (MB) a server may have on a computer with `total_mb`: a
/// quarter of it, from 1 GB to 8 GB, so the game, the launcher and the system
/// still fit beside it.
pub fn ram_ceiling_for(total_mb: u64) -> u32 {
    (total_mb / 4).clamp(1024, 8192) as u32
}

/// A folder that may be deleted as a server: directly inside the servers
/// folder, never the servers folder itself or anything deeper or outside.
pub fn deletable_folder(root: &std::path::Path, dir: &std::path::Path) -> Result<(), String> {
    if dir != root && dir.parent() == Some(root) {
        Ok(())
    } else {
        Err("Only a server's own folder can be deleted.".into())
    }
}

pub struct Running {
    pub child: Child,
    pub stdin: Option<ChildStdin>,
    pub stopping: bool,
}

pub fn registry() -> &'static Mutex<HashMap<String, Running>> {
    static RUNNING: OnceLock<Mutex<HashMap<String, Running>>> = OnceLock::new();
    RUNNING.get_or_init(|| Mutex::new(HashMap::new()))
}

/// "running", "stopping" or "stopped"; a server that has exited is forgotten.
pub fn status_of(id: &str) -> &'static str {
    let Ok(mut map) = registry().lock() else { return "stopped" };
    let exited = match map.get_mut(id) {
        None => return "stopped",
        Some(run) => !matches!(run.child.try_wait(), Ok(None)),
    };
    if exited {
        map.remove(id);
        return "stopped";
    }
    if map.get(id).map(|r| r.stopping).unwrap_or(false) { "stopping" } else { "running" }
}

/// Type a line into a running server's console.
pub fn send_line(id: &str, line: &str) -> Result<(), String> {
    let mut map = registry().lock().map_err(|_| "The list of running servers is unavailable.".to_string())?;
    let run = map.get_mut(id).ok_or_else(|| "That server is not running.".to_string())?;
    let stdin = run.stdin.as_mut().ok_or_else(|| "That server's console is closed.".to_string())?;
    stdin
        .write_all(format!("{line}\n").as_bytes())
        .and_then(|_| stdin.flush())
        .map_err(|e| format!("Could not send the command: {e}"))
}

/// Ask a server to save and stop; kill it only if it has not stopped after
/// STOP_GRACE. Returns false when it was not running.
pub fn request_stop(id: &str) -> Result<bool, String> {
    {
        let mut map = registry().lock().map_err(|_| "The list of running servers is unavailable.".to_string())?;
        let Some(run) = map.get_mut(id) else { return Ok(false) };
        if run.stopping {
            return Ok(true);
        }
        run.stopping = true;
        let asked = run
            .stdin
            .as_mut()
            .map(|s| s.write_all(b"stop\n").and_then(|_| s.flush()).is_ok())
            .unwrap_or(false);
        if !asked {
            let _ = run.child.kill();
        }
    }
    let id = id.to_string();
    thread::spawn(move || {
        let started = Instant::now();
        loop {
            thread::sleep(Duration::from_millis(500));
            let Ok(mut map) = registry().lock() else { return };
            let Some(run) = map.get_mut(&id) else { return };
            match run.child.try_wait() {
                Ok(None) if started.elapsed() < STOP_GRACE => continue,
                Ok(None) => {
                    let _ = run.child.kill();
                    let _ = run.child.wait();
                }
                _ => {}
            }
            map.remove(&id);
            return;
        }
    });
    Ok(true)
}

/// When the launcher closes: every server is asked to stop, and any still
/// running after `grace` is killed, so none is left using the computer.
pub fn stop_all_blocking(grace: Duration) {
    let Ok(mut map) = registry().lock() else { return };
    for run in map.values_mut() {
        if let Some(stdin) = run.stdin.as_mut() {
            let _ = stdin.write_all(b"stop\n").and_then(|_| stdin.flush());
        }
    }
    let started = Instant::now();
    while started.elapsed() < grace {
        map.retain(|_, run| matches!(run.child.try_wait(), Ok(None)));
        if map.is_empty() {
            return;
        }
        drop(map);
        thread::sleep(Duration::from_millis(250));
        map = match registry().lock() {
            Ok(m) => m,
            Err(_) => return,
        };
    }
    for run in map.values_mut() {
        let _ = run.child.kill();
        let _ = run.child.wait();
    }
    map.clear();
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn only_known_server_software_and_safe_versions() {
        assert_eq!(normalize_loader(" Fabric "), Some("fabric"));
        assert_eq!(normalize_loader("paper"), Some("paper"));
        assert_eq!(normalize_loader("forge"), None);
        assert!(valid_version("1.21.4"));
        assert!(valid_version("26.1"));
        assert!(valid_version("1.21.11-rc1"));
        assert!(!valid_version("1.21/../../x"));
        assert!(!valid_version("1.21?x=1"));
        assert!(!valid_version(""));
        assert!(!valid_version(&"1".repeat(33)));
    }

    #[test]
    fn only_a_servers_own_folder_can_be_deleted() {
        let root = std::path::Path::new("/home/p/.breezeclient/local-servers");
        use std::path::Path;
        assert!(deletable_folder(root, &root.join("srv-1-Friday")).is_ok());
        assert!(deletable_folder(root, root).is_err(), "never every server at once");
        assert!(deletable_folder(root, &root.join("srv-1-Friday").join("world")).is_err());
        assert!(deletable_folder(root, Path::new("/home/p/.breezeclient")).is_err());
        assert!(deletable_folder(root, Path::new("/home/p/Documents")).is_err());
    }

    #[test]
    fn a_server_gets_at_most_a_quarter_of_the_computer() {
        assert_eq!(ram_ceiling_for(16384), 4096);
        assert_eq!(ram_ceiling_for(8192), 2048);
        assert_eq!(ram_ceiling_for(4096), 1024);
        assert_eq!(ram_ceiling_for(2048), 1024, "never under 1 GB");
        assert_eq!(ram_ceiling_for(65536), 8192, "never over 8 GB");
    }

    #[test]
    fn names_ports_and_console_lines_are_cleaned() {
        assert_eq!(clean_name("  My\nServer\u{7}  "), "MyServer");
        assert_eq!(clean_name(&"x".repeat(80)).len(), 48);
        assert!(!valid_port(80));
        assert!(valid_port(25565));
        assert_eq!(clean_console_command(" /say hi "), Some("say hi".into()));
        assert_eq!(clean_console_command("op Steve"), Some("op Steve".into()));
        assert_eq!(clean_console_command("say a\nstop"), None, "one command per line");
        assert_eq!(clean_console_command("   "), None);
        assert_eq!(clean_console_command(&"a".repeat(257)), None);
        assert!(server_properties("Hi\nmotd=evil", 25570).contains("motd=Himotd=evil\nserver-port=25570\n"));
        assert!(server_properties("A", 25565).contains("online-mode=true"), "players are checked against Mojang");
    }

    #[test]
    fn fabric_versions_prefer_stable_and_build_the_launcher_url() {
        let loaders = vec![
            json!({"loader": {"version": "0.19.6-beta", "stable": false}}),
            json!({"loader": {"version": "0.19.5", "stable": true}}),
        ];
        assert_eq!(pick_fabric_version(&loaders, Some("loader")).as_deref(), Some("0.19.5"));
        let installers = vec![json!({"version": "1.1.0", "stable": true})];
        assert_eq!(pick_fabric_version(&installers, None).as_deref(), Some("1.1.0"));
        assert_eq!(pick_fabric_version(&[json!({"version": "../x"})], None), None);
        assert_eq!(
            fabric_server_jar_url("1.21.4", "0.19.5", "1.1.0"),
            "https://meta.fabricmc.net/v2/versions/loader/1.21.4/0.19.5/1.1.0/server/jar"
        );
    }

    #[test]
    fn a_taken_port_is_seen_as_taken() {
        let held = TcpListener::bind(("0.0.0.0", 0)).unwrap();
        let port = held.local_addr().unwrap().port();
        assert!(!port_free(port));
        drop(held);
    }

    #[test]
    fn stop_asks_the_server_first_and_the_registry_forgets_it() {
        // A stand-in server that stops when it reads "stop", as Minecraft does.
        #[cfg(unix)]
        let child = std::process::Command::new("sh")
            .arg("-c")
            .arg("while read l; do [ \"$l\" = stop ] && exit 0; done")
            .stdin(std::process::Stdio::piped())
            .spawn();
        #[cfg(windows)]
        let child = std::process::Command::new("cmd")
            .args(["/C", "set /p l= & exit 0"])
            .stdin(std::process::Stdio::piped())
            .spawn();
        let mut child = child.unwrap();
        let stdin = child.stdin.take();
        registry().lock().unwrap().insert("t1".into(), Running { child, stdin, stopping: false });
        assert_eq!(status_of("t1"), "running");
        assert!(send_line("t1", "say hello").is_ok());
        assert!(request_stop("t1").unwrap());
        let started = Instant::now();
        while status_of("t1") != "stopped" && started.elapsed() < Duration::from_secs(10) {
            thread::sleep(Duration::from_millis(100));
        }
        assert_eq!(status_of("t1"), "stopped", "it stopped on its own well before the grace period");
        assert!(started.elapsed() < Duration::from_secs(10));
        assert!(!request_stop("t1").unwrap(), "stopping a stopped server is a no-op");
        assert!(send_line("t1", "say hi").is_err());
    }
}
