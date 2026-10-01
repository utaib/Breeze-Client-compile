# Sourced by launcher-facts.sh and launcher-deps.sh: the choices the Breeze
# launcher (1.0.26, src-tauri/src/lib.rs) makes before a Fabric launch.
#
#   launcher_loader <mc>          fetch_latest_fabric_loader: the first stable
#                                 loader Fabric's meta lists, else the first
#   launcher_modrinth <slug> <mc> install_modrinth_project_recursive: Modrinth
#                                 versions for that exact game version and
#                                 loader fabric, featured first, then Modrinth's
#                                 own order; the primary file. Prints one line:
#                                 "<version> <file name> <sha1> <url> deps=<ids>",
#                                 or "none"
# shellcheck shell=bash

launcher_ua='utaib/Breeze-Client launcher-facts (CI)'

launcher_get() { curl -sSf -A "$launcher_ua" --retry 3 --retry-delay 2 "$@"; }

launcher_loader() {
  launcher_get "https://meta.fabricmc.net/v2/versions/loader/$1" \
    | jq -r '(map(select(.loader.stable)) | first // null) // first | .loader.version // "none"'
}

launcher_modrinth() {
  local q
  q=$(jq -rn --arg mc "$2" '"loaders=" + (["fabric"] | tojson | @uri) + "&game_versions=" + ([$mc] | tojson | @uri)')
  launcher_get "https://api.modrinth.com/v2/project/$1/version?$q" | jq -r '
    (sort_by(if .featured then 0 else 1 end) | first) as $v
    | if $v == null then "none"
      else ($v.files | (map(select(.primary)) | first) // first) as $f
        | "\($v.version_number) \($f.filename) \($f.hashes.sha1) \($f.url) deps=\([$v.dependencies[] | select(.dependency_type == "required") | .project_id] | join(","))"
      end'
}
