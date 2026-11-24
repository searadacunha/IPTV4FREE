#!/usr/bin/env bash
# Copies the IPTV4FREE lists (France, Portugal, Monde: what the Kodi interface showed) into the app's assets.
# Source: the folder given, else the newest copy deployed to the TV by tcl_tv/iptv (favoris.py, rubriques.py).
# The lists stay out of git (.gitignore): they hold relays and an operator stream the public catalog excludes.
set -euo pipefail
cd "$(dirname "$0")"
SRC=${1:-$(ls -d "$HOME"/.local/state/iptv4free-tcl-legacy/deployed/*/ 2>/dev/null | sort | tail -1)}
[ -n "$SRC" ] || { echo "no deployed lists found; give the folder holding france.m3u portugal.m3u monde.m3u" >&2; exit 1; }
mkdir -p src/main/assets/tv
for f in france portugal monde; do
  head -1 "$SRC/$f.m3u" | grep -q '^#EXTM3U' || { echo "$SRC/$f.m3u is not a playlist" >&2; exit 1; }
  cp "$SRC/$f.m3u" src/main/assets/tv/
done
echo "from $SRC"
(cd src/main/assets/tv && shasum -a 256 france.m3u portugal.m3u monde.m3u && grep -c '^#EXTINF' france.m3u portugal.m3u monde.m3u)
