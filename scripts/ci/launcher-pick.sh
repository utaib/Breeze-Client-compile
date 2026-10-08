# Sourced by launcher-facts.sh and launcher-deps.sh: the choices the Breeze
# launcher (1.0.27, src-tauri/src/lib.rs) makes before a Fabric launch.
#
#   launcher_loader <mc>          fetch_latest_fabric_loader: the first stable
#                                 loader Fabric's meta lists, else the first
#   launcher_modrinth <slug> <mc> install_modrinth_project_recursive: Modrinth
#                                 versions for that exact game version and
#                                 loader fabric, featured first, then Modrinth's
#                                 own order; the primary file. Prints one line:
#                                 "<version> <file name> <sha1> <url> deps=<ids>",
#                                 or "none"
#   launcher_fetch <slug> <mc> <dir>
#                                 downloads that pick into <dir>, sha1 checked;
#                                 nothing (and a note) when there is none
#   launcher_fetch_deps <slug> <mc> <dir>
#                                 the same, then every required dependency the
#                                 same way (as the launcher's recursive install
#                                 does), each project once; Fabric API is left
#                                 to the caller, which installs it already
#   launcher_older <slug> <mc> <dir>
#                                 the second newest build for that version, for
#                                 a duplicate beside the newest
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

launcher_fetch() {
  local pick version file sha1 url
  pick=$(launcher_modrinth "$1" "$2")
  if [ "$pick" = none ]; then echo "$1: none for $2" >&2; return 0; fi
  read -r version file sha1 url _ <<< "$pick"
  launcher_get -L -o "$3/$file" "$url"
  echo "$sha1  $3/$file" | sha1sum -c --quiet - >&2
  echo "$1: $version ($file, sha1 checked)" >&2
}

launcher_fabric_api_id=P7dR8mSH

launcher_fetch_deps() {
  local seen="$3/.fetched-projects" pick deps dep
  touch "$seen"
  grep -qxF "$1" "$seen" && return 0
  echo "$1" >> "$seen"
  pick=$(launcher_modrinth "$1" "$2")
  launcher_fetch "$1" "$2" "$3"
  [ "$pick" = none ] && return 0
  deps=${pick##*deps=}
  for dep in ${deps//,/ }; do
    [ "$dep" = "$launcher_fabric_api_id" ] && continue
    launcher_fetch_deps "$dep" "$2" "$3"
  done
}

launcher_older() {
  local q version file sha1 url
  q=$(jq -rn --arg mc "$2" '"loaders=" + (["fabric"] | tojson | @uri) + "&game_versions=" + ([$mc] | tojson | @uri)')
  # Every build after the newest, newest first; the first whose file is not
  # in <dir> already (the caller's own copy may be one of them).
  while read -r version file sha1 url; do
    [ -n "$file" ] || continue
    [ -e "$3/$file" ] && continue
    launcher_get -L -o "$3/$file" "$url"
    echo "$sha1  $3/$file" | sha1sum -c --quiet - >&2
    echo "$1: older $version ($file, sha1 checked)" >&2
    echo "$3/$file"
    return 0
  done < <(launcher_get "https://api.modrinth.com/v2/project/$1/version?$q" | jq -r '
    .[1:][] | (.files | (map(select(.primary)) | first) // first) as $f
    | "\(.version_number) \($f.filename) \($f.hashes.sha1) \($f.url)"')
  echo "$1: no second build for $2" >&2
  return 1
}
