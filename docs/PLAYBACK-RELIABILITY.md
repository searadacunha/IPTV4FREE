# IPTV playback reliability audit — 2026-11-17

## Scope and evidence

This is a **code and report audit**, not an end-to-end playback test on the owner's TV. The last checked-in network probe in `docs/SOURCES.md` is dated **2026-11-11** (Portugal network): 781 entries shipped in the hub catalog. A successful single-segment probe is not a guarantee of long-lived playback, rights, or availability from another country.

The app has two distinct playback paths:

- `hub/`: Android TV app, bundled checked catalog + playlists fetched at runtime, playback with Media3.
- `iptv/` and `iptv-tv-preview/`: playlist management and Kodi/PVR UI. Kodi and PVR IPTV Simple Client actually play these channels. Their troubleshooting and updates are separate from the Android hub.

## Diagnosing a failed channel

| TV symptom / status | Interpretation | First action |
|---|---|---|
| HTTP 403 / 451 | Access refused; possibly territory, token, entitlement, or CDN policy | Compare with broadcaster's authorised service and access conditions, **not** a random replacement URL |
| HTTP 404 | Playlist/variant/segment not found, potentially temporary | Refresh source list and retry later |
| Timeout, no connection | DNS, network, CDN, server outage or connectivity issue | Check the same source using the same TV network |
| HTTP 200 but HTML page | Not video even if the HTTP request succeeded | Reject the candidate at probe time |
| Highest HLS rendition unavailable | Other qualities may still work | Probe next rendition; let Media3 choose playback quality |
| Unsupported codec/container | Media3 or device decoder incompatibility | Record codec/device details; evaluate an opt-in alternative engine |
| HLS/DASH DRM | Protected playback may need authorised service/player | Open authorised official app; never bypass licensing/DRM |

**TF1 / TMC:** The 2026-11-11 report logs unreachable TF1/TMC sources in the Free-TV list. A Free-TV URL is fetched by the television at runtime, and is **not** part of the 781 curated catalog entries. TF1+ publishes territorial restrictions for live TV, so a missing direct stream from Portugal is not evidence of an Android player bug. Official TV-app availability and authorised viewing rights must be checked separately.

## Improvements in this pull request

1. In-memory M3U playlists now respect the documented 12-hour file refresh window. Stale folders and lists are rebuilt instead of being returned indefinitely.
2. Explicit refresh now reports a failed download as a failure, while preserving the previous valid file for offline use.
3. HLS catalog verification falls back to a lower rendition when the preferred variant fails. A 200 response containing HTML/JSON error material is no longer counted as a playable segment.
4. Regression tests cover expiry boundaries, failed-best-variant fallback and false-positive media detection.
5. The README's clone command points to the consolidated repository name.

## Follow-up engineering work (not implemented)

1. **On-device diagnostics:** add channel-level attempts and error classifications (manifest, variant, segment, decoder, HTTP status), with sensitive tokens stripped from logs.
2. **Multi-source fallbacks:** group *verified, authorised* alternatives by `tvg-id`, preserve distinct quality/territory metadata, and try the next source on failure without changing channels.
3. **Health history:** save multiple observations per stream with timestamp, country/network and last-good segment; classify `restricted`, `unknown`, `transient`, `working` separately. A single 403 outside a broadcaster's territory should not retire a stream globally.
4. **DASH and long-run checks:** current probe treats a non-DRM MPD as OK without downloading a segment. Add a real DASH segment test and optional short playback duration check.
5. **EPG and rights provenance:** validate TVG-ID and XMLTV matches; review CDN allowlists against broadcaster authorisation. A hostname under a large CDN or HTTP 200 alone does not establish licensing.
6. **Playback compatibility:** evaluate optional libmpv fallback on unsupported codecs only after measuring Media3 failures on the target TV.

Useful upstream patterns, **not copied into this repository**: [iptv-org/iptv](https://github.com/iptv-org/iptv) (playlist validation), [dearbulut/iptv](https://github.com/dearbulut/iptv) (geo-aware health scoring), [streamline-iptv/iptv-playlists](https://github.com/streamline-iptv/iptv-playlists) (daily segment checks), [Thedurancode/owntv](https://github.com/Thedurancode/owntv) (multiple engines), [Hunter-Clipper/OpenIPTV](https://github.com/Hunter-Clipper/OpenIPTV) (Media3 on Android TV). Check their individual licences before adopting code.

## How to verify on the user's TV

- Build: `cd hub && ./gradlew testDebugUnitTest assembleRelease`.
- Run the Python regression suite: `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v`.
- On the actual television, launch the affected channels from both IPTV4FREE (Kodi) and the STREAMING4FREE hub; record whether each fails immediately or after playback begins.
- While reproducing errors on authorised ADB connection: `adb logcat -d -s S4F:V ExoPlayerImpl:E`. Avoid sharing full URLs containing signed credentials or tokens.
- For a refreshed **catalog snapshot**, run `python3 tools/probe_streams.py` from the network used by the television, review the generated changes, and rebuild the hub. Do not claim that a fresh catalog is already on the installed TV.
