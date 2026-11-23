# IPTV playback reliability audit, 2026-11-17

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

**TF1, TMC, M6, France 2 to 5 (checked 2026-11-17):** none has a free legal live stream from Portugal. TF1+ lists France, Belgium, Luxembourg, Switzerland and some African countries ([TF1 help](https://help.tf1.fr/hc/fr/articles/19337708703634-La-plateforme-TF1-est-elle-disponible-%C3%A0-l-International)); M6+ is for France, Monaco and Andorra only ([M6 terms](https://www.m6.fr/conditions-generales-d-utilisation)); france.tv excludes rights-restricted programmes abroad ([france.tv terms](https://www.france.tv/confidentialite/cgu/)). TF1+, M6+ and france.tv cannot be installed from the Portuguese Play Store. The streams that still answer from the house line are unofficial relays (an operator CDN that also carries pay channels, and bare-IP relays of a satellite feed): they are **not** added to the catalog and never used as fallbacks. Legal options: France 24, TV5MONDE and ARTE (free), and MEO's paid "Pack France TV" (France 2, 3, 5). The hub says this when one of these channels fails.

## What this pull request changes

**Hub (Media3 player)**

1. **Several sources per channel.** A channel is identified by its tvg-id (without the `@feed` part), never by its name. Its sources are: the URL chosen, the alternates checked by `tools/probe_streams.py` (catalog field `"a"`), and the M3UPT entries with the same tvg-id. Free-TV and iptv-org entries are never used as fallbacks for another URL, and bare-IP hosts never are.
2. **Automatic fallback.** A source that is refused, missing, geo-blocked, not video, DRM-protected or not decodable is left at once for the next one ("Source 1 ne répond pas (HTTP 403), essai de la source 2/3").
3. **Health per URL.** Last success, last failure, its kind and the failure count are kept on the TV. Sources are tried in that order. A card is greyed for 24 h only for a failure of the stream itself; a failure while the TV is offline never counts. A clock in the future is ignored.
4. **Geo-blocking told apart.** HTTP 451, an `x-errortype: geo` header, an explicit geo message in the body or a list's geo flag mean "blocked in this country"; a plain 403 is "access refused".
5. **Bounded reconnection.** Network errors, timeouts and 5xx are retried after 1, 2, 4 and 8 s (one retry only before the first picture when another source is left), live streams restart at the live edge, a stream that plays 15 s gets its retries back, "behind live window" is handled 3 times a minute, a 20 s start-up watchdog and a 30 s stuck-buffering limit end silent hangs. Offline, the player waits for Android's network callback or the OK key: it never loops.
6. **Lower quality before giving up.** On a live stream, the 3rd retry is capped at 720p and the 4th at SD.
7. **Codecs.** An audio or video track the TV cannot decode is named on screen (for example E-AC-3). On a final codec failure, OK opens the stream in VLC or mpv when one is installed (no mpv inside the app: GPL and about 30 MB per ABI).
8. **Diagnostics.** The final message says why (blocked in this country, access refused, dead link, not a video, DRM, format not read by this TV, server down, no connection), the HTTP status, the host and whether other sources exist. Each attempt is logged under the `S4F` tag without query strings.
9. **Playlists.** One download at a time per list, no new attempt for 10 minutes after a failure (unless refreshed by hand), and a download that parses to no channel or to less than half of the previous list keeps the last good copy (a list that really shrank is accepted on manual refresh or once the copy is 3 days old).
10. **Other fixes.** Search no longer crashes when left within 300 ms of typing; background tasks catch every exception; a name containing a double quote is parsed; `#KODIPROP` licence lines before `#EXTINF` mark DRM; France 24 and TV5MONDE+ apps added; Settings text about where to watch live updated.

**Catalog builder (`tools/probe_streams.py`)**

- Gzip playlists declared by the server are inflated (RTP 1 used to be classed as a web page).
- HTTP 204 or an empty body is `EMPTY`. Segments and direct streams must really be TS, MP4/CMAF, MPEG-PS or ADTS (HTML, JSON or text is rejected; AES-128 segments are checked for text only).
- Nested master playlists, every `#EXT-X-KEY`, audio-only renditions and playlists cut at the read limit are handled.
- Up to 3 working alternates per tvg-id are written as `"a"`. The `official()` filter is unchanged: unofficial relays stay out.
- Failures are probed a second time, one request per host, so a refusal caused by the burst does not drop a channel.

**Kodi side (`iptv/`, `iptv-tv-preview/`)**

- The scanner inflates gzip, sniffs real media, tries lower video variants and reports `204` as "server refuses this player".
- Browser user agents declared by lists are no longer forwarded to Kodi.
- EPG aliases only count when the guide has that id; matching follows tvg-id, then tvg-name, then channel name.
- CI runs both full test suites, also on demand.
- RTP answers HTTP 204 to Kodi and ffmpeg user agents: RTP 1, 2 and 3 play in the hub or the free RTP Play app, not in Kodi. PVR IPTV Simple has one URL per channel, so automatic fallback lives in the hub.

## Still not done

1. **Not tested on the TV.** Every result above comes from unit tests and from HTTP probes on the house line; nothing has been played on the TCL yet.
2. **No EPG in the hub.** The programme guide exists only on the Kodi side.
3. **DASH segments** are still not downloaded by the probe (a non-DRM MPD is taken as OK).
4. **Relays in the runtime lists.** The iptv-org and Free-TV folders are shown as published by those lists, unofficial relays included, as before this change. They are only excluded from the catalog and from automatic fallback.

Useful upstream patterns, **not copied into this repository**: [iptv-org/iptv](https://github.com/iptv-org/iptv) (playlist validation), [dearbulut/iptv](https://github.com/dearbulut/iptv) (geo-aware health scoring), [streamline-iptv/iptv-playlists](https://github.com/streamline-iptv/iptv-playlists) (daily segment checks), [Thedurancode/owntv](https://github.com/Thedurancode/owntv) (multiple engines), [Hunter-Clipper/OpenIPTV](https://github.com/Hunter-Clipper/OpenIPTV) (Media3 on Android TV). Check their individual licences before adopting code.

## How to verify on the user's TV

- Build: `cd hub && S4F_TEST_BUILD=1 ./gradlew testDebugUnitTest assembleRelease` (test build; an APK for the TV needs the release key, see `docs/SIGNING.md`).
- Run the Python regression suite: `python3 -m unittest discover -s tools/tests -p 'test_*.py' -v`.
- On the actual television, launch the affected channels from both IPTV4FREE (Kodi) and the STREAMING4FREE hub; record whether each fails immediately or after playback begins.
- While reproducing errors on authorised ADB connection: `adb logcat -d -s S4F:V ExoPlayerImplInternal:E`. Avoid sharing full URLs containing signed credentials or tokens.
- For a refreshed **catalog snapshot**, run `python3 tools/probe_streams.py` from the network used by the television, review the generated changes, and rebuild the hub. Do not claim that a fresh catalog is already on the installed TV.

## Follow-up, 2026-11-23

Probed again from the house line (Portugal, the network the TV uses). Per-channel verdicts are generated into
`docs/SOURCES.md` ("Main channels"); every stream of TF1 in the three lists was checked by hand as well.

**TF1: no authorised source from Portugal.** Free-TV's TF1 (bare IP `151.80.18.177`) times out. The only TF1 that
answers is iptv-org's `r.eu.cdn-redge.media/.../oeu/mt/live/tf1/...`: plain HLS, H.264 + AAC up to 1080p, playable by
Media3, but an operator's distribution feed (the same host carries France 2 to 5, M6, TMC, TFX, W9, 6ter, LCI, BFM TV
under one operator path), not TF1's. It stays out of the catalog and out of the fallbacks, as decided on
2026-11-11; the iptv-org folder still shows what iptv-org publishes. TF1's own live is TF1+, limited to France,
Belgium, Luxembourg, Switzerland and some African countries; its CDN refuses Kodi and ffmpeg user agents, and
disguising the player is not done. Same situation for TMC, TFX, LCI, M6, W9, 6ter and France 2 to 5.

**RTP 1 and RTP 2 moved.** Their HLS URLs (catalog, Free-TV, iptv-org) now answer 404; M3UPT lists them as DASH with
a ClearKey written in the playlist (`drm_legacy`). Decrypting with a key published by a third party is not done: both
leave the catalog, and their failure message now names the free RTP Play app. RTP Notícias, RTP Memória, TVI, CNN
Portugal, Porto Canal and BFM TV play from official hosts and are in the catalog.

**Changes**

1. Hub: every `#KODIPROP` DRM syntax (`license_*`, `drm_legacy`, `drm`, ClearKey, Widevine, PlayReady) marks an entry
   as DRM. Before, M3UPT's new RTP 1 and RTP 2 entries were listed and offered as fallbacks.
2. Kodi lineups: among copies of one channel, the one that passed the last scan wins over a sharper one that failed it
   (a failing copy stays "alive" for 2 more scans and used to hide a working one).
3. DASH, in the probe and the Kodi scanner: a manifest declaring `ContentProtection` is DRM (the Kodi scanner used to
   pass it), and the video initialization segment must be MP4 data. Media segments are not computed yet.
4. Catalog: 885 channels (was 946): 63 streams left the lists, 12 are dead (404, RTP 1 and RTP 2 among them),
   1 changed its tvg-id; 14 new.

**Required tests, where they are**

| Case | Test |
|---|---|
| Working HLS stream | `tools/tests` `test_falls_back_to_working_lower_quality`, `iptv/tests/test_scanner.py` |
| Valid manifest, segments unavailable | `test_empty_segment_is_empty` (HLS), `test_valid_manifest_but_segments_unavailable` (DASH), `test_dash_needs_a_video_initialization_segment` (Kodi) |
| Expired URL or HTTP error | `ReliabilityTest.httpStatuses`, `deadLinkSwitchesToTheNextSource` |
| Drop during playback | `interruptedLiveStreamRetriesWithBackoffAtTheLiveEdgeThenLowersQuality`, `liveStreamDroppingEveryThirtySecondsKeepsPlaying` |
| Reconnection and source switch | `deadHostBeforeTheFirstPictureGetsOneRetryWhenAnotherSourceIsLeft`, `offlineTvWaitsWithoutUsingTriesThenRecovers` |
| No replacement source | `everySourceFailingEndsWithGiveUpAndNoLoop` |
| Geo-blocking | `geoOnlyWhenTheBodySaysSo`, `geoSwitchesAtOnce` |
| No endless reconnection | `streamThatDropsEverySecondsDoesNotRetryForever`, `behindLiveWindowRejoinsThreeTimesAMinuteThenCountsAsTimeout` |

**Still not verified:** playback on the TCL itself (nothing installed on the TV for this change), DASH media segments,
and whether the catalog's 885 streams keep working after the probe.
