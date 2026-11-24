# IPTV4FREE 2.0 — one Android TV app

IPTV4FREE is now a single native Android TV app. It keeps the look of the IPTV4FREE Kodi interface
(official logo, electric blue on black, Noto Sans, rail → groups → channels → now/next with a live preview)
and holds everything the STREAMING4FREE hub had, plus the channels, groups and TV guide the Kodi
interface showed. Kodi is no longer needed to watch IPTV4FREE.

| | |
|---|---|
| Project | [`hub/`](../hub/) (Gradle, Kotlin, Media3) — one APK, one entry point (`MainActivity`) |
| Package | `com.local.streaming4free`, kept on purpose: the update installs over the hub and keeps its favorites, history, resume positions and settings |
| Version | 2.0 (versionCode 3), signed by the release key of [`signing/release-cert.sha256`](../signing/release-cert.sha256) |
| Label and tile | `IPTV4FREE`, official logo (built by [`hub/make_art.py`](../hub/make_art.py) from `iptv-tv-preview/assets/branding/IPTV4FREE-original.png`) |

## Navigation

Home → category → sub-category → content → playback, with the remote only:

| Rail | Sub-categories (column 2) and what they hold (column 3) |
|---|---|
| **TÉLÉVISION** | **France**: TNT (channel-number order), list favorites, information, regional, themes (films, series, kids, sport, docs, music, religion, other), free FAST channels, francophone channels, full France list · **Portugal**: list favorites, national, information, regional and themed, themes, full Portugal list · **En portugais**: Brazil, lusophone Africa, Macao, other · **Gratuite et FAST**: one row per platform (Pluto TV, Samsung TV Plus, Rakuten TV, Plex…, other FAST), each by genre · **Internationale**: international news, English-language, European, by country, by language, music, kids, culture, religion and institutions, general, films and series, sport, full World list · **Autres chaînes**: what nothing classifies |
| **STREAMING** | Built-in sites (CINEFUSE, WATCHED) · YouTube (YouTube lives of Free-TV, YouTube channels of the lists, YouTube apps) · Applications (films and series, live TV, sport, docs, YouTube, IPTV players, free apps to install) · Public IPTV lists (M3UPT, Free-TV, iptv-org, by group) |
| **FILMS / SÉRIES** | Sites and apps, in French, in Portuguese, other languages, by genre, all film (series) channels |
| **SPORT** | In French, in Portuguese, international, by discipline (football, combat sports, basketball, motor sports, tennis, golf, cycling, rugby, horse racing, extreme), sport programmes of the guide, apps, all sport channels |
| **DOCUMENTAIRES** | In French, in Portuguese, international, by theme (history, science and technology, nature, travel and food, economy, society), apps, all documentary channels |
| **FAVORIS** | All, channels, apps and sites (one list, shared by every section) |
| **REPRENDRE** | Recently watched, channels, apps and sites. The app opens on the last thing watched: OK plays it |
| **RECHERCHE** | One search: channels, TV programmes, apps and sites, public lists already downloaded, YouTube (SmartTube) |
| **GUIDE TV** | Now (TNT order), France (TNT), Portugal, tonight (20:30–22:30), sport |
| **CHAÎNES** | All channels, the three IPTV4FREE lists, the groups of the former Kodi interface (same names and order), the checked catalog by row |
| **PARAMÈTRES** | Language, maximum quality, data (refresh lists, refresh guide, clear history), help, where to watch live, about |

Remote: arrows move, OK plays or opens, → on a channel shows its programme, MENU or a long press on OK
toggles a favorite, Back goes one step back (programme → content → level up → groups → menu; from the menu it
stops the preview, then leaves). Full screen: up/down or CH+/CH− change channel within the list it was started
from, OK shows the player's controls (tracks, quality), Back returns to the menu with the channel still playing
in the preview box; OK on that channel (or PLAY) goes back to full screen. CH+/CH− in a list jump ten rows.

## Where the channels come from

| Source | In the app | Notes |
|---|---|---|
| Checked catalog | `hub/src/main/assets/catalog.json`, 896 channels | Unchanged; its checked alternates are used when a stream fails |
| IPTV4FREE lists (France 216, Portugal 72, Monde 7 753 entries) | `hub/src/main/assets/tv/*.m3u`, copied by [`hub/import_tv_lists.sh`](../hub/import_tv_lists.sh) from the copy deployed to the TV by `tcl_tv/iptv` | **Kept out of git** (`.gitignore`): they hold relays and an operator stream that the public catalog excludes. A release build refuses to package without them; CI and `S4F_TEST_BUILD=1` builds carry the catalog only |
| M3UPT, Free-TV, iptv-org | Downloaded by the TV, kept 12 h | As in the hub |

The 8 041 list entries and the catalog merge into 7 992 channels (one per address; a catalog channel keeps its
checked alternates and gets the groups of the same address). Every channel stays reachable at least in
**CHAÎNES → Toutes les chaînes** and in its list's full row; a channel no rule classifies stays in its country,
its language and the full lists. Kodi's YouTube add-on link (franceinfo) becomes the YouTube page SmartTube opens.

Classification ([`Classify.kt`](../hub/src/main/kotlin/com/local/streaming4free/Classify.kt), unit-tested):
country from the tvg-id (`Name.cc@feed`, a country feed such as `@FR` or `@BR` wins), language from the list,
the feed or the catalog row, else the country; kind of programmes from the list groups (Kodi lists, hub
rubrics, catalog rows), else from the name; FAST platform from the stream and logo addresses; TNT from the TNT
guide's ids; finer cuts (regional, discipline, theme, genre) from names. Name rules are heuristics.

## TV guide

[`Guide.kt`](../hub/src/main/kotlin/com/local/streaming4free/Guide.kt): the two XMLTV files the Kodi instances
used (`xmltvfr.fr` TNT, `epgshare01` Portugal), downloaded by the TV every 12 hours (one retry), read as a
stream, the last 4 and next 30 hours kept, cached on the TV. A channel gets a guide by its tvg-id (and the TNT
guide's old ids, as the list builder did), then a display name equal to its name, then a loose name only one
guide channel has, in its own country only. Measured on the TV on 23 Nov 2026: France 30 channels / 1 188
programmes, Portugal 174 / 8 180.

## Migration matrix

| Function | Source app | Former module | In IPTV4FREE 2.0 | How | State (23 Nov 2026) |
|---|---|---|---|---|---|
| Live channels of the Kodi interface (8 041 entries, 33 groups) | IPTV4FREE (Kodi) | `script.iptv4free` + IPTV Simple, `/sdcard/Download/iptv/*.m3u` | TÉLÉVISION, CHAÎNES (incl. former groups) | Lists shipped in the APK | Merged; played on the TV: TF1, France 2 |
| Now/next, channel programme, TV guide | IPTV4FREE (Kodi) | IPTV Simple EPG | Rows, details panel, → programme, GUIDE TV | Native XMLTV reader | Merged; seen on the TV |
| Live preview while browsing | IPTV4FREE (Kodi) | Kodi video window | Preview box, "EN LECTURE" | One Media3 player moved between full screen and the box | Merged; seen on the TV |
| Favorites of the Kodi interface | IPTV4FREE (Kodi) | `addon_data/script.iptv4free/favorites.json` | FAVORIS | Nothing to move: the file was empty (`[]`, read on the TV) | — |
| Kodi search, groups, quit | IPTV4FREE (Kodi) | add-on buttons | RECHERCHE, CHAÎNES → groups, Back | Native | Merged |
| Checked catalog, rubrics, apps, YouTube lives, public lists | STREAMING4FREE hub | Leanback rows | Every section | Same code and data | Merged |
| Player with source fallback, diagnosis, network wait | STREAMING4FREE hub | `PlayerActivity` | `Playback` | Moved unchanged | Merged; same unit tests |
| Favorites, history, resume positions, settings | STREAMING4FREE hub | SharedPreferences | Same | In-place update (same package and key) | History kept, checked on the TV |
| Search (apps, catalog, lists, YouTube) | STREAMING4FREE hub | `SearchActivity` | RECHERCHE (+ every channel, + TV programmes) | Native panel, TV keyboard and its microphone | Merged; seen on the TV |
| watched.cx full screen | WATCHED (`com.local.watchedtv`) | `src/` | STREAMING → Sites intégrés, FILMS, SÉRIES | Same Java source compiled into IPTV4FREE (not exported) | Opens; the site answered HTTP 451 to the TV (see limits) |
| cinefuse full screen, cursor, site lock | CINEFUSE TV (`com.local.cinefusetv`) | `cinefuse/src/` | Same | Same source; address moved to `cinefuse.xyz` | Opens; the site shows Cloudflare's "verify you are human" check |
| Kodi tile | IPTV4FREE launcher (`com.iptv4free.launcher`) | Android TV launcher | The IPTV4FREE tile itself | — | Replaced |
| Official apps (RTP Play, ARTE, Plex, TVI Player, Molotov…) | hub | app cards | Applications, Sites et applis | Still the providers' own apps | Third-party (by design) |
| YouTube | hub | SmartTube or YouTube | Same | Google allows no other player | Third-party (by design) |

## Choices made without a decision from the owner

- **Package kept** (`com.local.streaming4free`): another package would have started empty and left the hub installed.
- **Kodi lists out of git**, bundled at build time: the repository's catalog policy excludes relays.
- **No browser headers**: 578 list entries ask for a Referer or browser user agent (Kodi sent them); IPTV4FREE
  does not, as the hub never did. They stay listed, marked "en-têtes requis", and may refuse to play.
- **Noto Sans from Google** (2.015, SIL OFL 1.1): the copy shipped with Kodi's Estuary skin rendered wrong glyphs on the TV.
- **Old tiles disabled, not uninstalled** (see below): WebView logins and the test build's data stay on the TV.

## Rollback

- Android refuses a downgrade of a release app. To go back to the hub 1.1: check out `main`, set
  `versionCode = 4` in `hub/build.gradle.kts`, build and `adb install -r` (data kept: same preferences format).
  The 1.1 APK and the other former APKs are kept on the build Mac in
  `~/.local/state/iptv4free-tcl-legacy/backups/20261123-avant-iptv4free-2.0/`.
- Former tiles: `adb shell pm enable <package>` brings each back with its data.

## Tests

- `hub`: 55 JVM unit tests (`./gradlew testDebugUnitTest`): the 42 of the hub (M3U, reliability, refresh policy)
  plus classification, XMLTV reading and guide matching (`MenuRulesTest`, `GuideTest`, Kodi YouTube links).
- On the TCL TV (Android 12) on 23 Nov 2026, sound off: start and data kept, rail → TV → France → TNT, now/next,
  → programme, TF1 (1.1 s to the first picture, hardware decoder) and France 2 full screen, up/down zapping,
  Back to the preview, favorite (MENU), search, guide sections, CINEFUSE and WATCHED opening. Someone switched
  the TV off with the remote at 20:15: the rest (settings, apps, YouTube hand-off, restart) was not run on the TV.
