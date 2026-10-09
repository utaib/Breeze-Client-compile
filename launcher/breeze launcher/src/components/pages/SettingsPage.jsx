import { useRef, useState } from "react";

const I = {
  Spin: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className="installing"><path d="M21 12a9 9 0 11-6.219-8.56" /></svg>,
  Update: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round"><path d="M21 2v6h-6" /><path d="M3 12a9 9 0 0115-6.7L21 8" /><path d="M3 22v-6h6" /><path d="M21 12a9 9 0 01-15 6.7L3 16" /></svg>,
};

export default function SettingsPage({
  themes, settings, patchSettings, memoryInfo, apiStatus,
  performanceMessage, performanceBusy,
  handleApplyPerformanceProfile, handleAutoDetectPerformance,
  javaStatus, javaProgress, javaBusy, handleInstallJava,
  importResults, importBusy, detection, detectionBusy,
  importEverythingSummary,
  handleImportClient, handleDetectClients, handleImportEverything,
  mrpackInputRef, updateRelease, notify, getSystemVersion, setApiStatus, importProgress,
  APP_VERSION, onCheckUpdates, onInstallUpdate, onOpenDownloadPage, updateError,
}) {
  return (
    <div className="sv page-enter">
      <div className="vtl">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" style={{ width: 18, height: 18 }}>
          <line x1="4" y1="21" x2="4" y2="14" /><line x1="4" y1="10" x2="4" y2="3" />
          <line x1="12" y1="21" x2="12" y2="12" /><line x1="12" y1="8" x2="12" y2="3" />
          <line x1="20" y1="21" x2="20" y2="16" /><line x1="20" y1="12" x2="20" y2="3" />
          <line x1="1" y1="14" x2="7" y2="14" /><line x1="9" y1="8" x2="15" y2="8" /><line x1="17" y1="16" x2="23" y2="16" />
        </svg>
        Launcher Settings
      </div>

      {/* Performance */}
      <div className="sg">
        <div className="sgl">Performance</div>
        <div className="scd">
          <div className="sr">
            <div className="si">
              <div className="sn">RAM Allocation</div>
              <div className="sd">
                System: {Math.round((memoryInfo?.totalRamMb || 16384) / 1024)} GB ·
                Recommended: {Math.round((memoryInfo?.recommendedRamMb || 4096) / 1024)} GB ·
                Safe max: {Math.round((memoryInfo?.safeMaxRamMb || 12288) / 1024)} GB
              </div>
            </div>
            <div className="rc">
              <div className="rv">{settings.allocatedRamMb > 0 ? `${Math.round(settings.allocatedRamMb / 1024)} GB` : "Auto"}</div>
              <input
                type="range"
                min={1024}
                max={memoryInfo?.safeMaxRamMb || 12288}
                step={256}
                value={settings.allocatedRamMb || (memoryInfo?.recommendedRamMb || 4096)}
                onChange={(e) => patchSettings({ allocatedRamMb: Number(e.target.value) })}
              />
            </div>
          </div>

          <div className="sr">
            <div className="si">
              <div className="sn">Auto-Set Recommended RAM</div>
              <div className="sd">Apply the optimal allocation for your {Math.round((memoryInfo?.totalRamMb || 16384) / 1024)} GB system</div>
            </div>
            <button className="btn accent" onClick={() => patchSettings({ allocatedRamMb: memoryInfo?.recommendedRamMb || 4096 })}>
              Apply {Math.round((memoryInfo?.recommendedRamMb || 4096) / 1024)} GB
            </button>
          </div>

          {handleApplyPerformanceProfile && (
            <div className="sr">
              <div className="si">
                <div className="sn">Performance Profile</div>
                <div className="sd">{performanceMessage || "Choose your optimization preset"}</div>
              </div>
              <div className="seg">
                {["Performance", "Balanced", "Quality"].map((preset) => (
                  <button
                    key={preset}
                    className={`segb ${settings.performanceProfile === preset ? "on" : ""}`}
                    onClick={() => handleApplyPerformanceProfile(preset)}
                    disabled={performanceBusy}
                  >{preset}</button>
                ))}
                {handleAutoDetectPerformance && (
                  <button className="segb" onClick={handleAutoDetectPerformance} disabled={performanceBusy} title="Auto-detect best preset">
                    Auto
                  </button>
                )}
              </div>
            </div>
          )}

          <div className="sr">
            <div className="si">
              <div className="sn">Background Prewarm</div>
              <div className="sd">Pre-download assets at startup</div>
            </div>
            <div className={`pill ${settings.prewarmEnabled ? "on" : ""}`} onClick={() => patchSettings({ prewarmEnabled: !settings.prewarmEnabled })} />
          </div>

          <div className="sr">
            <div className="si">
              <div className="sn">Reduce Launcher GPU Usage</div>
              <div className="sd">Keep launcher lightweight while game is running</div>
            </div>
            <div className={`pill ${settings.graphicsPerformance ? "on" : ""}`} onClick={() => patchSettings({ graphicsPerformance: !settings.graphicsPerformance })} />
          </div>
        </div>
      </div>

      {/* Launcher Settings */}
      <div className="sg">
        <div className="sgl">Launcher</div>
        <div className="scd">
          <div className="sr">
            <div className="si">
              <div className="sn">Auto-Update Launcher</div>
              <div className="sd">Check for new Breeze releases on startup</div>
            </div>
            <div className={`pill ${settings.autoUpdate ? "on" : ""}`} onClick={() => patchSettings({ autoUpdate: !settings.autoUpdate })} />
          </div>
          <div className="sr">
            <div className="si">
              <div className="sn">Auto-Update Mods</div>
              <div className="sd">Keep managed mods up to date</div>
            </div>
            <div className={`pill ${settings.modAutoUpdate ? "on" : ""}`} onClick={() => patchSettings({ modAutoUpdate: !settings.modAutoUpdate })} />
          </div>
          <div className="sr">
            <div className="si">
              <div className="sn">Keep Launcher Open</div>
              <div className="sd">Stay visible while Minecraft is running</div>
            </div>
            <div className={`pill ${settings.afterLaunch === "keep-open" ? "on" : ""}`} onClick={() => patchSettings({ afterLaunch: settings.afterLaunch === "keep-open" ? "minimize" : "keep-open" })} />
          </div>
          <div className="sr">
            <div className="si">
              <div className="sn">Include Snapshot Versions</div>
              <div className="sd">Show Minecraft snapshots in the version list</div>
            </div>
            <div className={`pill ${settings.includeSnapshots ? "on" : ""}`} onClick={() => patchSettings({ includeSnapshots: !settings.includeSnapshots })} />
          </div>
        </div>
      </div>

      {/* Themes */}
      <div className="sg">
        <div className="sgl">Themes</div>
        <div className="scd" style={{ paddingTop: 4 }}>
          <div className="theme-grid">
            {(themes || []).map((theme) => (
              <button
                key={theme.id}
                className={`theme-card ${String(settings.theme).toLowerCase() === theme.id ? "sel" : ""}`}
                onClick={() => patchSettings({ theme: theme.id })}
              >
                <div className="theme-bg" style={{ background: theme.gradient }} />
                <div className="theme-name">{theme.name}</div>
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Migrate from Other Clients */}
      {handleImportClient && (
        <div className="sg">
          <div className="sgl">Migrate from Other Clients</div>
          <div className="scd">
            {handleImportEverything && (
              <div className="sr">
                <div className="si">
                  <div className="sn">One-Click Migration</div>
                  <div className="sd">
                    {importEverythingSummary
                      ? `Imported from ${importEverythingSummary.succeeded?.join(", ") || "nothing detected"}, ${importEverythingSummary.totalMods || 0} mods`
                      : "Detect and import mods, settings, resourcepacks, and shaderpacks from every supported client"}
                  </div>
                </div>
                <button className="btn accent" onClick={handleImportEverything} disabled={importBusy === "__all__"}>
                  {importBusy === "__all__" ? <I.Spin /> : "Import Everything"}
                </button>
              </div>
            )}

            {handleDetectClients && (
              <div className="sr">
                <div className="si">
                  <div className="sn">Detect Installed Clients</div>
                  <div className="sd">
                    {detection
                      ? detection.clients?.filter((c) => c.detected).map((c) => c.name).join(", ") || "No supported clients found"
                      : "Scan known client directories"}
                  </div>
                </div>
                <button className="btn" onClick={handleDetectClients} disabled={detectionBusy}>
                  {detectionBusy ? <I.Spin /> : detection ? "Re-scan" : "Detect"}
                </button>
              </div>
            )}

            {[
              { id: "vanilla", name: "Vanilla Minecraft", desc: "Options, keybinds, resourcepacks, shaderpacks, and mods" },
              { id: "feather", name: "Feather Client", desc: "Mods, Modrinth profiles, and launcher preferences" },
              { id: "lunar", name: "Lunar Client", desc: "User mods and config overrides" },
              { id: "badlion", name: "Badlion Client", desc: "User mods and config files" },
              { id: "modrinth", name: "Modrinth App", desc: "Profiles, mods, resourcepacks, shaderpacks, and configs" },
            ].map((client) => {
              const result = importResults?.[client.id];
              const detected = detection?.clients?.find((c) => c.id === client.id);
              let status = client.desc;
              if (result?.error) status = `${result.error}`;
              else if (result) status = `Imported successfully`;
              else if (detected?.detected) status = `Found${detected.activeVersion ? ` · ${detected.activeVersion}` : ""}`;
              return (
                <div key={client.id} className="sr">
                  <div className="si">
                    <div className="sn">{client.name}</div>
                    <div className="sd">{status}</div>
                  </div>
                  <button
                    className={`btn ${result && !result.error ? "accent" : ""}`}
                    onClick={() => handleImportClient(client.id)}
                    disabled={importBusy === client.id || (detection && !detection.clients?.find((c) => c.id === client.id)?.detected)}
                  >
                    {importBusy === client.id ? <I.Spin /> : result && !result.error ? "Re-import" : "Import"}
                  </button>
                </div>
              );
            })}

            {mrpackInputRef && (
              <div className="sr">
                <div className="si">
                  <div className="sn">Modrinth Pack (.mrpack)</div>
                  <div className="sd">
                    {/* Large packs are streamed to disk in chunks, so show real
                        progress rather than an indefinite spinner. */}
                    {importProgress
                      ? importProgress.stage === "staging"
                        ? `Reading ${importProgress.name} · ${Math.round(importProgress.fraction * 100)}%`
                        : `Installing ${importProgress.name}, downloading mods...`
                      : "Install a complete mod setup from a Modrinth .mrpack file"}
                  </div>
                  {importProgress?.stage === "staging" && (
                    <div className="import-progress-track">
                      <div className="import-progress-fill" style={{ width: `${Math.round(importProgress.fraction * 100)}%` }} />
                    </div>
                  )}
                </div>
                <button className="btn" onClick={() => mrpackInputRef.current?.click()} disabled={importBusy === "mrpack"}>
                  {importBusy === "mrpack" ? <I.Spin /> : "Choose File"}
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Java Runtime */}
      {handleInstallJava && (
        <div className="sg">
          <div className="sgl">Java Runtime</div>
          <div className="scd">
            <div className="sr">
              <div className="si">
                <div className="sn">
                  {javaStatus?.installed ? `Java ${javaStatus.major ?? 21} ready` : "Java 21 not installed yet"}
                  {javaStatus?.source ? ` · ${javaStatus.source}` : ""}
                </div>
                <div className="sd">
                  {javaProgress
                    ? javaProgress.message
                    : javaStatus?.javaPath || "Each Minecraft version gets the Java it needs, downloaded from Mojang the first time you launch it."}
                </div>
                {javaBusy && javaProgress && typeof javaProgress.progress === "number" && (
                  <div className="java-progress-bar" style={{ marginTop: 6 }}>
                    <div className="java-progress-fill" style={{ width: `${Math.round(javaProgress.progress * 100)}%` }} />
                  </div>
                )}
              </div>
              <button className="btn" onClick={handleInstallJava} disabled={javaBusy || !handleInstallJava}>
                {javaBusy ? <I.Spin /> : javaStatus?.installed ? "Re-check" : "Install Java 21"}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* About */}
      <div className="sg">
        <div className="sgl">About</div>
        <div className="scd">
          <div className="sr">
            <div className="si">
              <div className="sn">Breeze Launcher</div>
              <div className="sd">
                Version {APP_VERSION || "0.9.0"}{updateRelease?.channel && updateRelease.channel !== "stable" ? ` · ${updateRelease.channel} update available` : ""}
                {updateError && (
                  <>
                    {" · "}
                    <span style={{ color: "var(--danger, #e5484d)" }}>{updateError}</span>
                    {" · "}
                    <button className="link-btn" onClick={() => onOpenDownloadPage?.()}>download manually</button>
                  </>
                )}
              </div>
            </div>
            {updateRelease ? (
              // window.open is a no-op inside the Tauri webview, so this runs
              // the same native download-and-install path as the header banner.
              <button className="btn accent" onClick={() => onInstallUpdate?.()}>
                <I.Update /> {updateError ? "Retry" : "Update"} {updateRelease.latestVersion}
              </button>
            ) : (
              <button className="btn" onClick={() => onCheckUpdates?.()}>
                <I.Update /> Check for updates
              </button>
            )}
          </div>
          <div className="sr">
            <div className="si">
              <div className="sn">System Memory</div>
              <div className="sd">{Math.round((memoryInfo?.totalRamMb || 16384) / 1024)} GB total · safe max {Math.round((memoryInfo?.safeMaxRamMb || 12288) / 1024)} GB</div>
            </div>
            <span style={{ fontSize: 11, color: "var(--text-faint)" }}>Live</span>
          </div>
          <div className="sr">
            <div className="si">
              <div className="sn">Breeze API</div>
              <div className="sd">{apiStatus || "Checking..."}</div>
            </div>
            {getSystemVersion && setApiStatus && (
              <button className="btn" onClick={() => getSystemVersion().then((s) => setApiStatus(`Breeze API ${s.latestVersion || "?"} online`)).catch(() => setApiStatus("API offline"))}>
                Refresh
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
