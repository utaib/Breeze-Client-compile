// Every command registered with generate_handler! in src/lib.rs must be listed
// here, and granted in capabilities/default.json.
//
// Listing the commands gives the app its own ACL manifest. Without one, Tauri
// only enforces capabilities for core and plugin commands, so every Breeze
// command answered any frame that could reach the IPC bridge, including the ad
// iframes (WebView2 injects the bridge into every frame) and remote pages. With
// the manifest in place, a command runs only for the origins and windows a
// capability grants it to.
//
// A test in src/lib.rs (acl_sync_tests) fails if this list, the handler list
// and the capability drift apart, because a command missing here would make
// that launcher feature silently stop working.
const COMMANDS: &[&str] = &[
    "launcher_manifest",
    "list_installed_mods",
    "scan_instance_mods",
    "remove_mod_file",
    "open_instance_folder",
    "list_mod_versions",
    "stage_mod_version",
    "apply_mod_change",
    "discard_mod_change",
    "list_mod_backups",
    "restore_mod_backup",
    "install_modrinth_mod",
    "import_custom_mod",
    "set_mod_enabled",
    "remove_installed_mod",
    "copy_local_mod_to_profile",
    "list_installed_packs",
    "list_pack_library",
    "use_library_pack",
    "download_modrinth_pack",
    "import_custom_pack",
    "set_pack_state",
    "remove_installed_pack",
    "prepare_local_server",
    "import_local_server_file",
    "start_local_server",
    "stop_local_server",
    "read_local_server_log",
    "get_launcher_settings",
    "update_launcher_settings",
    "import_feather_preferences",
    "import_lunar_preferences",
    "import_badlion_preferences",
    "import_vanilla_preferences",
    "import_modrinth_app_preferences",
    "import_mrpack",
    "export_mrpack",
    "stage_import_chunk",
    "clear_staged_import",
    "detect_importable_clients",
    "import_everything",
    "apply_performance_profile",
    "prewarm_version",
    "get_system_memory_info",
    "begin_microsoft_auth",
    "restore_saved_session",
    "clear_saved_session",
    "save_custom_background",
    "save_custom_background_staged",
    "load_custom_background",
    "discord_set_presence",
    "discord_clear_presence",
    "clear_custom_background",
    "list_saved_accounts",
    "switch_account",
    "remove_saved_account",
    "sign_out_all_accounts",
    "prepare_launch",
    "launch_minecraft",
    "ensure_java_runtime",
    "get_java_runtime_status",
    "download_and_install_update",
    "get_platform",
    "get_version_compatibility",
    "get_recordings_dir",
];

fn main() {
    tauri_build::try_build(
        tauri_build::Attributes::new().app_manifest(tauri_build::AppManifest::new().commands(COMMANDS)),
    )
    .expect("failed to run tauri-build");
}
