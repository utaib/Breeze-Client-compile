/**
 * The line Settings and the play page show about the Breeze API. The version
 * is only ever what the API reported: when it reports none, the line says the
 * API is online without a number (it used to show a made-up "0.1.0-beta").
 */
export function apiStatusText(manifest) {
  const launcher = manifest?.launcher || manifest?.latestLauncher || manifest;
  const version = typeof launcher?.latestVersion === "string" ? launcher.latestVersion.trim() : "";
  return /^\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?$/.test(version) ? `Breeze API ${version} online` : "Breeze API online";
}
