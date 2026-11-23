#!/usr/bin/env python3
"""Probes the public IPTV playlists from this network and builds the hub's curated catalog.

Each candidate stream is opened the way the hub's player opens it (plain GET, platform user agent, no
Referer/Origin, no DRM key): playlist, best video variant up to 1080p (then at most the next ones when it fails),
then the first bytes of a media segment, which must be audio/video data, not a page (an AES-128 one: binary, not
text; its key is not fetched). DASH: no DRM, and the video initialization segment must be MP4. Only streams that answer from here end up in the
catalog, so run it from the network the TV uses. Each catalog channel also keeps up to 3 other working streams of the
same channel (same tvg-id, quality feeds such as @SD and @HD merged) as fallbacks.

    python3 tools/probe_streams.py            # download, probe, write the catalog and the report
    python3 tools/probe_streams.py --reuse    # rebuild the outputs from the last probe (no network)

Writes hub/src/main/assets/catalog.json and docs/SOURCES.md. Standard library only.
"""
import argparse, collections, concurrent.futures, datetime, ipaddress, json, re, sys, threading, urllib.parse, urllib.request, zlib
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "tools" / ".cache"
# what HttpURLConnection sends on the TV when the app sets no user agent (Media3's default)
UA = "Dalvik/2.1.0 (Linux; U; Android 12; Smart TV Pro Build/STT2.230203.001)"
LISTS = {
    "M3UPT": "https://m3upt.com/iptv",
    "Free-TV": "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8",
    "iptv-org": "https://iptv-org.github.io/iptv/index.m3u",
}
LANGS = {l: f"https://iptv-org.github.io/iptv/languages/{l}.m3u" for l in ("fra", "por", "eng", "spa")}
OFFICIAL = Path(__file__).resolve().parent / "official.m3u"  # broadcasters' own streams outside the lists, kept in the repository
FREETV_GROUPS = {"France": "fra", "Portugal": "por", "Brazil": "por", "Belgium": "fra", "Switzerland": "fra",
                 "Luxembourg": "fra", "Monaco": "fra", "News": "eng", "Documentaries (EN)": "eng"}
THEMES = {"Sports": "sport", "Documentary": "docs", "Science": "docs", "Outdoor": "docs", "Travel": "docs",
          "Movies": "movies", "Classic": "movies", "Series": "series"}  # iptv-org category -> hub category
LIVE_ROWS = {"Kids": "kids", "Animation": "kids", "Family": "kids", "Music": "music", "News": "news"}
TIMEOUT = 8
MAIN_CHANNELS = {  # tvg-id without feed, lower case -> name, for the report's "Main channels" table
    "tf1.fr": "TF1", "france2.fr": "France 2", "france3.fr": "France 3", "france4.fr": "France 4", "france5.fr": "France 5",
    "m6.fr": "M6", "arte.fr": "Arte", "tmc.fr": "TMC", "tfx.fr": "TFX", "w9.fr": "W9", "6ter.fr": "6ter", "lci.fr": "LCI",
    "bfmtv.fr": "BFMTV", "cnews.fr": "CNews", "rtp1.pt": "RTP 1", "rtp2.pt": "RTP 2", "rtpnoticias.pt": "RTP Notícias",
    "sic.pt": "SIC", "sicnoticias.pt": "SIC Notícias", "tvi.pt": "TVI", "cnnportugal.pt": "CNN Portugal", "portocanal.pt": "Porto Canal",
}
WS = b"\xef\xbb\xbf \r\n\t"
VIDEO = re.compile(r"avc1|avc3|hvc1|hev1|vp09|av01|mp4v")
THROTTLED = {"HTTP403", "VARIANT0", "SEGMENT0", "VARIANT429", "SEGMENT429"}  # timeouts, refusals, rate limits
LIMIT = 262144  # bytes read from a playlist
DRM_PROP = re.compile(r"license|drm|clearkey|widevine|playready", re.I)  # also drm_legacy=org.w3.clearkey|... and drm={...}


def fetch(url, limit=LIMIT):
    """GET like the hub: returns (status, final_url, content_type, first bytes)."""
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*", "Accept-Encoding": "gzip"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            body = r.read(limit)
            if r.headers.get("Content-Encoding", "").lower() == "gzip":
                try:  # only when declared, like Media3; a truncated read still inflates up to the limit
                    body = zlib.decompressobj(31).decompress(body, limit)
                except zlib.error:
                    pass  # declared gzip but is not: keep the raw bytes for the sniffers
            return r.status, r.geturl(), r.headers.get("Content-Type", ""), body
    except urllib.error.HTTPError as e:
        return e.code, url, "", b""
    except Exception as e:  # DNS, timeout, TLS, refused...
        return 0, url, type(e).__name__, b""


def playlist(body):
    """Playlist text; a body cut at the read limit loses its last line, which may be a partial URI."""
    text = body.decode("utf-8", "replace")
    return text.rsplit("\n", 1)[0] if len(body) >= LIMIT else text


def parse_m3u(text, source):
    out, cur = [], None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
            cur = {"name": line[line.find(",", line.rfind('"') + 1) + 1:].strip(), "source": source,
                   "id": attrs.get("tvg-id", ""), "logo": attrs.get("tvg-logo", ""), "group": attrs.get("group-title", ""),
                   "headers": "http-user-agent" in attrs or "http-referrer" in attrs, "drm": False}
        elif cur and line.startswith("#EXTVLCOPT:http-"):
            cur["headers"] = True
        elif cur and line.startswith("#KODIPROP") and DRM_PROP.search(line):
            cur["drm"] = True
        elif cur and line and not line.startswith("#"):
            cur["url"] = line
            out.append(cur)
            cur = None
    return out


def media(ctype, body):
    """'TS', 'MP4', 'PS' or 'AAC' when the first bytes are audio/video; None for a page, JSON, a playlist or anything else."""
    if ctype.lower().startswith(("text/html", "application/json")):
        return None
    if body[:3] == b"ID3" and len(body) >= 10:  # timed metadata ahead of the media (packed audio, some TS)
        body = body[10 + ((body[6] & 127) << 21 | (body[7] & 127) << 14 | (body[8] & 127) << 7 | body[9] & 127):]
    if len(body) >= 188 and body[0] == 0x47 and body[188:189] in (b"G", b""):
        return "TS"
    if body[4:8] in (b"ftyp", b"styp", b"moof", b"sidx", b"moov", b"emsg", b"prft"):
        return "MP4"
    if body[:4] == b"\0\0\1\xba":
        return "PS"
    if len(body) > 1 and body[0] == 0xFF and body[1] & 0xF6 == 0xF0:
        return "AAC"
    return None


def probe(url):
    """Returns a dict: status ('OK' or a failure class), detail, and quality when the playlist tells it."""
    host = urllib.parse.urlsplit(url).hostname or ""
    if re.search(r"(^|\.)(youtube\.com|youtu\.be)$", host):
        return {"status": "YOUTUBE", "detail": "opened by the YouTube app"}
    code, final, ctype, body = fetch(url)
    if code == 204 or code == 200 and not body:  # RTP answers 204 to some user agents
        return {"status": "EMPTY", "detail": f"HTTP {code} without a body"}
    if code != 200:
        return {"status": {403: "HTTP403", 451: "HTTP403", 404: "HTTP404", 0: "UNREACHABLE"}.get(code, "HTTP" + str(code)), "detail": ctype or str(code)}
    head = body[:2048].lstrip(WS)
    if head.startswith(b"#EXTM3U"):
        return probe_hls(final, playlist(body))
    if b"<MPD" in body[:4096]:
        if b"ContentProtection" in body:
            return {"status": "DRM", "detail": "DASH with ContentProtection"}
        return probe_dash(final, body)
    kind = media(ctype, body)
    if kind == "AAC":
        return {"status": "AUDIO", "detail": "direct audio-only stream"}
    if kind:
        return {"status": "OK", "detail": "direct " + kind}
    return {"status": "WEBPAGE", "detail": ctype[:40] or "not audio or video data"}


def dash_init(base, body):
    """URL of the initialization segment of the best video representation up to 1080p, in the last period (the
    current one of a live manifest). None when the manifest gives none: SegmentBase, SegmentList, a template without
    initialization. Raises ET.ParseError on a manifest that is not XML."""
    def tag(e):
        return e.tag.rsplit("}", 1)[-1]

    def kids(e, name):
        return [c for c in e if tag(c) == name]

    def join(b, e):
        u = next((c.text.strip() for c in kids(e, "BaseURL") if c.text and c.text.strip()), None)
        return urllib.parse.urljoin(b, u) if u else b

    root = ET.fromstring(body)
    best, base = None, join(base, root)
    for period in kids(root, "Period")[-1:]:
        pbase = join(base, period)
        for aset in kids(period, "AdaptationSet"):
            abase = join(pbase, aset)
            for rep in kids(aset, "Representation"):
                kind = rep.get("mimeType") or aset.get("mimeType") or aset.get("contentType") or ""
                tpl = (kids(rep, "SegmentTemplate") or kids(aset, "SegmentTemplate") or kids(period, "SegmentTemplate") or [None])[0]
                init = tpl.get("initialization") if tpl is not None else None
                if "video" not in kind or not init:
                    continue
                h = int(rep.get("height") or aset.get("height") or 0)
                url = urllib.parse.urljoin(join(abase, rep), init.replace("$RepresentationID$", rep.get("id", ""))
                                           .replace("$Bandwidth$", rep.get("bandwidth", "")))
                if best is None or (h <= 1080, h) > best[0]:
                    best = ((h <= 1080, h), url)
    return best and best[1]


def probe_dash(base, body):
    """A DASH manifest passes when the initialization segment of its video answers with MP4 data.
    shortcut: media segments ($Number$, $Time$) are not computed, so a live stream whose init answers but whose
    segments are gone still passes; add timeline maths if such streams show up."""
    try:
        init = dash_init(base, body)
    except ET.ParseError:
        return {"status": "MANIFEST_FORMAT", "detail": "DASH manifest is not XML"}
    if not init:
        return {"status": "OK", "detail": "DASH manifest (no initialization segment to check)"}
    code, _, ctype, data = fetch(init, 32768)
    if code == 204 or code == 200 and not data:
        return {"status": "EMPTY", "detail": "DASH initialization segment without a body"}
    if code != 200:
        return {"status": "HTTP403" if code in (403, 451) else "SEGMENT" + str(code), "detail": "DASH initialization segment"}
    if media(ctype, data) != "MP4":
        return {"status": "SEGMENT_FORMAT", "detail": "DASH initialization segment is not MP4"}
    return {"status": "OK", "detail": "DASH MP4"}


def probe_hls(base, text, depth=0):
    """Check a real segment, falling back to lower video variants if the best one fails."""
    variants = re.findall(r"#EXT-X-STREAM-INF:([^\n]*)\n(?:#[^\n]*\n)*([^#\n][^\n]*)", text)
    if variants:
        if depth > 1:
            return {"status": "VARIANT_FORMAT", "detail": "master playlist nested twice"}

        def height(v):
            m = re.search(r"RESOLUTION=\d+x(\d+)", v[0])
            return int(m.group(1)) if m else 0

        def rate(v):
            m = re.search(r"[^-]BANDWIDTH=(\d+)", "," + v[0])
            return int(m.group(1)) if m else 0

        def codecs(v):
            m = re.search(r'CODECS="([^"]*)"', v[0])
            return m.group(1) if m else ""

        video = [v for v in variants if not codecs(v) or VIDEO.search(codecs(v))]
        if any(height(v) or codecs(v) for v in video):  # siblings describe their video: one that declares nothing is audio
            video = [v for v in video if height(v) or codecs(v)]
        if not video:
            return {"status": "AUDIO", "detail": "audio-only renditions"}
        fit = [v for v in video if height(v) <= 1080] or video
        failure, wary, retried = None, None, False
        for variant in sorted(fit, key=lambda v: (height(v), rate(v)), reverse=True):
            url = urllib.parse.urljoin(base, variant[1].strip())
            host = urllib.parse.urlsplit(url).hostname
            if host == wary:
                if retried:
                    continue
                retried = True
            code, final, ctype, body = fetch(url)
            if code in (403, 451):
                result = {"status": "HTTP403", "detail": "variant playlist"}
            elif code == 204 or code == 200 and not body:
                result = {"status": "EMPTY", "detail": "variant playlist without a body"}
            elif code != 200:
                result = {"status": "VARIANT" + str(code), "detail": "variant playlist"}
            elif not body.lstrip(WS).startswith(b"#EXTM3U"):
                result = {"status": "VARIANT_FORMAT", "detail": "not an HLS playlist"}
            else:
                result = probe_hls(final, playlist(body), depth + 1)
            result.setdefault("quality", " ".join(filter(None, [f"{height(variant)}p" if height(variant) else "", codecs(variant)])))
            if result["status"] == "OK":
                return result
            if failure is None:
                failure = result
                if result["status"] in THROTTLED:  # do not hammer a host that refuses or rate-limits: one more variant there at most
                    wary = host
        return failure

    keys = re.findall(r"#EXT-X-KEY:[^\n]*?METHOD=([A-Z0-9-]+)", text)
    drm = [k for k in keys if k not in ("NONE", "AES-128")]
    if drm:
        return {"status": "DRM", "detail": "HLS " + drm[0]}
    segments = [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]
    if not segments:
        return {"status": "EMPTY", "detail": "playlist without segments"}
    code, _, ctype, body = fetch(urllib.parse.urljoin(base, segments[-1]), 32768)
    if code == 204 or code == 200 and not body:
        return {"status": "EMPTY", "detail": "media segment without a body"}
    if code != 200:
        return {"status": "HTTP403" if code in (403, 451) else "SEGMENT" + str(code), "detail": "media segment"}
    if len(body) < 188:
        return {"status": "SEGMENT_FORMAT", "detail": "media segment too short"}
    if keys and keys[-1] == "AES-128":  # ciphertext cannot be sniffed: reject only what is plainly text or audio
        if ctype.lower().startswith(("text/html", "application/json")) or all(b in b"\t\n\r" or 32 <= b < 127 for b in body[:64]):
            return {"status": "SEGMENT_FORMAT", "detail": "server returned a page instead of media"}
        if ctype.lower().startswith("audio/"):
            return {"status": "AUDIO", "detail": "audio-only segments"}
        return {"status": "OK", "detail": "HLS AES-128"}
    kind = media(ctype, body)
    if kind is None:
        return {"status": "SEGMENT_FORMAT", "detail": "server returned a page instead of media"}
    if kind == "AAC":
        return {"status": "AUDIO", "detail": "audio-only segments"}
    return {"status": "OK", "detail": "HLS " + kind}


def probe_all(urls, workers=48, per_host=2, second=True):
    """At most 2 requests at a time per host: lists point hundreds of channels at the same CDN. The failures are
    probed once more, one request per host, so a refusal caused by the burst (TVI's CDN answers 403) does not drop a channel."""
    gates = collections.defaultdict(lambda: threading.Semaphore(per_host))
    done = [0]
    def one(u):
        with gates[urllib.parse.urlsplit(u).hostname]:
            try:
                r = probe(u)
            except Exception as e:
                r = {"status": "ERROR", "detail": type(e).__name__}
        done[0] += 1
        if done[0] % 100 == 0:
            print(f"  {done[0]}/{len(urls)}", file=sys.stderr, flush=True)
        return u, r
    with concurrent.futures.ThreadPoolExecutor(workers) as pool:
        results = dict(pool.map(one, urls))
    if second:
        again = [u for u, r in results.items() if r["status"] not in ("OK", "YOUTUBE")]
        print(f"  second pass: {len(again)} failures", file=sys.stderr, flush=True)
        for u, r in probe_all(again, 8, 1, False).items():
            if r["status"] == "OK":
                results[u] = r
    return results


def bare_ip(url):
    try:
        ipaddress.ip_address(urllib.parse.urlsplit(url).hostname or "")
        return True
    except ValueError:
        return False


# iptv-org lists any public stream, re-streams of paid channels included: from it, only the free ad-supported (FAST)
# platforms and broadcast CDNs below are kept, or a channel served from a domain carrying its own name.
FAST_HOSTS = re.compile(r"(^|\.)(jmp2\.uk|amagi\.tv|wurl\.com|wurl\.tv|transmit\.live|getpublica\.com|frequency\.stream|"
                        r"otteravision\.com|tubi\.video|amazonaws\.com|zype\.com|klowdtv\.com|sofast\.tv|akamaized\.net|"
                        r"brightcove\.com|cloudfront\.net)$"
                        r"|^ncdn-live-bfm\.pfd\.sfr\.net$")  # BFM's channels on SFR's CDN, as Free-TV lists BFM TV
GENERIC_LABELS = {"live", "stream", "streams", "streaming", "hls", "cdn", "video", "edge", "origin", "media", "www", "play",
                  "player", "channels", "ott", "multimedia", "global", "prod", "linear", "tv", "web", "online", "server"}


# EU Council Regulation 2022/350 and its extensions forbid broadcasting RT and Sputnik in the EU, the internet included
SANCTIONED_NAME = re.compile(r"^(rt|russia today|sputnik)\b", re.I)
SANCTIONED_HOST = re.compile(r"(^|\.)(rttv\.com|rt\.com|sputniknews\.com)$")


def sanctioned(name, url):
    return bool(SANCTIONED_NAME.match(name.strip()) or SANCTIONED_HOST.search(urllib.parse.urlsplit(url).hostname or ""))


def official(name, url):
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    if "premium" in host:  # a subscriber CDN reachable without login is not an authorised free stream
        return False
    if FAST_HOSTS.search(host):
        return True
    n = re.sub(r"[^a-z0-9]", "", name.lower())
    return any(len(l) >= 3 and l not in GENERIC_LABELS and (l in n or (len(n) >= 3 and n in l)) for l in host.split(".")[:-1])


def base_id(tvg_id):
    return tvg_id.split("@")[0]


def identity(tvg_id):
    """The channel behind a tvg-id: quality feeds are the same programme, other feeds (@Arabic, @Brazil...) are not."""
    return re.sub(r"@(SD|HD|FHD|UHD|4K)$", "", tvg_id, flags=re.I).lower()


def group(entries):
    """Yields (best entry, up to 3 other working URLs) per channel. Identity is the tvg-id without a quality feed, never
    the display name: entries without a tvg-id stay on their own. The input order decides which entry is best."""
    groups = {}
    for c in entries:
        groups.setdefault(identity(c["id"]) or c["url"], []).append(c)
    used = set()
    for same in groups.values():
        same = [c for c in same if c["url"] not in used]
        if same:
            # the player cannot open a YouTube link, only the first entry may be one
            alts = [u for u in dict.fromkeys(c["url"] for c in same[1:] if c["probe"]["status"] == "OK") if u != same[0]["url"]][:3]
            used.add(same[0]["url"])  # an alternate may still be another channel's own stream
            yield same[0], alts


def label(name):
    """Display name without list markers, and whether a list flags it geo-blocked (iptv-org "[Geo-blocked]", Free-TV Ⓖ)."""
    clean = re.sub(r"\s*[ⓈⒼⓎⒹ]\s*|\s*\((?:\d{3,4}[pi]|[^)]*Geo-blocked[^)]*)\)|\s*\[(?:Geo-blocked|Not 24/7)\]", "", name).strip()
    return clean, bool(re.search("Geo-blocked|Ⓖ", name))


def geo_flagged(entries):
    """Identities that any list flags geo-blocked, Free-TV included even though its entries never reach the catalog."""
    return {identity(c["id"]) for c in entries if c["id"] and label(c["name"])[1]}


def country(tvg_id):
    m = re.search(r"\.([a-z]{2})$", base_id(tvg_id))
    return m.group(1) if m else ""


def self_check():
    assert official("Pluto TV Cine", "https://jmp2.uk/plu-5f1.m3u8")
    assert official("France 24 Français", "https://live.france24.com/hls/live/x/master.m3u8")
    assert official("Real Madrid TV", "https://rmtv.akamaized.net/hls/live/2043153/rmtv-es-web/master.m3u8")
    assert not official("ESPN Latin America", "https://barinaslive1.boldmss.com/espn/index.m3u8")
    assert not official("Canal+ Sport (France)", "https://tv.streambox.fr/canalsport/index.m3u8")
    assert not official("Fox Sports News", "https://skynewsau-live-premium.akamaized.net/x.m3u8")
    assert not official("Live TV", "https://live.example.net/tv.m3u8")  # generic labels never count as the channel's own domain
    assert not official("TF1", "https://r.eu.cdn-redge.media/livehls/oil/freecdn/live/tf1/live.livx/playlist.m3u8")  # unofficial relay
    assert bare_ip("http://151.80.18.177:86/TF1_HD/index.m3u8") and not bare_ip("https://example.com/a.m3u8")
    assert official("BFM Lyon", "https://ncdn-live-bfm.pfd.sfr.net/shls/LIVE$BFM_LYON/index.m3u8")
    assert sanctioned("RT en français", "https://rt-fra.rttv.com/dvr/rtfrance/playlist.m3u8") and sanctioned("RT News", "https://rumble.com/x.m3u8")
    assert not any(sanctioned(n, "https://example.com/x.m3u8") for n in ("RTP 1", "RTL", "RTVE", "RTS Un", "Runtime"))
    assert not official("TMC", "https://viamotionhsi.netplus.ch/live/eds/tmc/browser-HLS8/tmc.m3u8")  # Swiss operator feed


def main():
    self_check()
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--reuse", action="store_true", help="rebuild the outputs from tools/.cache without any request")
    args = ap.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    texts = {}
    for name, url in {**LISTS, **LANGS}.items():
        f = CACHE / (name + ".m3u")
        if not args.reuse:
            code, _, _, body = fetch(url, 1 << 26)
            if code != 200 or not body.lstrip(b"\xef\xbb\xbf").startswith(b"#EXTM3U"):
                sys.exit(f"{name}: download failed ({code}) {url}")
            f.write_bytes(body)
        texts[name] = f.read_text("utf-8", "replace")
    lists = {n: parse_m3u(texts[n], n) for n in LISTS}
    lang_of = {}  # stream URL -> language, from iptv-org's per-language lists
    for l in LANGS:
        for c in parse_m3u(texts[l], l):
            lang_of.setdefault(c["url"], l)
    cats = collections.defaultdict(set)  # tvg-id without feed -> iptv-org categories
    for c in lists["iptv-org"]:
        cats[base_id(c["id"])].update(c["group"].split(";"))

    # candidates: Portuguese and French first, English and Spanish only for the themed categories
    cand = []
    for c in lists["M3UPT"]:
        if c["group"] == "TV":
            cand.append({**c, "lang": "por" if country(c["id"]) in ("pt", "br", "ao", "mz", "mo", "cv", "st", "gw", "tl") else
                         lang_of.get(c["url"]) or ("fra" if re.search(r"Fran[cç]ais|French|\bFR$", c["name"]) else "eng")})
    for c in parse_m3u(OFFICIAL.read_text("utf-8"), "Officiel"):
        cand.append({**c, "lang": "por" if country(c["id"]) == "pt" else "fra"})
    for c in lists["Free-TV"]:
        if c["group"] in FREETV_GROUPS:
            cand.append({**c, "lang": FREETV_GROUPS[c["group"]]})
    for c in lists["iptv-org"]:
        l = lang_of.get(c["url"])
        themed = any(k in THEMES for k in c["group"].split(";"))
        if l in ("fra", "por") or (l in ("eng", "spa") and themed):
            cand.append({**c, "lang": l})
    for c in cand:
        c["cats"] = sorted(cats.get(base_id(c["id"]), set()) | ({"News"} if c["group"] == "News" else set())
                           | ({"Documentary"} if c["group"].startswith("Documentaries") else set()))

    results_file = CACHE / "probe.json"
    if args.reuse:
        results = json.loads(results_file.read_text())
    else:
        urls = sorted({c["url"] for c in cand if c["url"].startswith("http") and not c["drm"]})
        print(f"probing {len(urls)} streams ({len(cand)} candidates)...", file=sys.stderr)
        results = probe_all(urls)
        results_file.write_text(json.dumps(results, indent=0, sort_keys=True))
    for c in cand:
        c["probe"] = {"status": "DRM", "detail": "DRM key in the playlist"} if c["drm"] else \
            results.get(c["url"], {"status": "UNSUPPORTED", "detail": c["url"].split(":")[0]})

    # catalog: working streams only, official-looking hosts only, one card per channel with its other streams as fallbacks
    def authorised(c):  # M3UPT, Free-TV and tools/official.m3u list official streams; from iptv-org only official-looking hosts
        return not bare_ip(c["url"]) and (c["source"] != "iptv-org" or official(c["name"], c["url"]))

    def usable(c):
        # Free-TV states no license: its entries are probed for the report but not copied into the app, the TV downloads that list itself
        return c["probe"]["status"] in ("OK", "YOUTUBE") and c["source"] != "Free-TV" and authorised(c) and not sanctioned(c["name"], c["url"])
    order = {"M3UPT": 0, "Officiel": 1, "Free-TV": 2, "iptv-org": 3}
    rows = collections.defaultdict(list)
    geo_ids = geo_flagged(cand)
    for c, alts in group(sorted((c for c in cand if usable(c)), key=lambda c: order[c["source"]])):  # stable: keeps each list's own order
        theme = next((THEMES[k] for k in c["cats"] if k in THEMES), None)
        if theme:
            row = (theme, c["lang"] if c["lang"] in ("por", "fra") else "int")
        else:
            special = next((LIVE_ROWS[k] for k in c["cats"] if k in LIVE_ROWS), None)
            if c["lang"] not in ("por", "fra"):
                row = ("live", "news") if special == "news" else None  # foreign generalist channels stay in the IPTV section
            elif special in ("kids", "music"):
                row = ("live", special)
            elif {"Religious", "Shop", "Legislative"} & set(c["cats"]):
                row = ("live", "other")
            else:
                row = ("live", "pt" if country(c["id"]) == "pt" else "por" if c["lang"] == "por" else "fra")
        if row:
            name, geo = label(c["name"])  # geo: answered from here, but a list says it may not elsewhere
            geo = geo or identity(c["id"]) in geo_ids
            item = {"n": name, "u": c["url"], "a": alts, "l": c["logo"], "s": c["source"], "id": c["id"],
                    "q": c["probe"].get("quality", ""), "c": country(c["id"]), "geo": geo}
            rows[row].append({k: v for k, v in item.items() if v or k not in ("a", "geo")})
    row_order = [("live", r) for r in ("pt", "fra", "por", "kids", "music", "news", "other")] + \
                [(t, r) for t in ("sport", "docs", "movies", "series") for r in ("por", "fra", "int")]
    today = datetime.date.today().isoformat()
    catalog = {"checked": today, "rows": [{"cat": t, "row": r, "items": rows[(t, r)]} for t, r in row_order if rows[(t, r)]]}
    out = ROOT / "hub" / "src" / "main" / "assets" / "catalog.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")) + "\n")

    # report
    md = [f"# Free sources checked on {today}", "",
          "Generated by `tools/probe_streams.py` from the network the TV uses (Portugal). A stream counts as working when its",
          "playlist, its best video variant up to 1080p (or, when that one fails, a lower one) and a media segment all answer",
          "to a plain request: no Referer, no browser user agent, no DRM key. The segment must be audio/video data (MPEG-TS,",
          "fMP4, MPEG-PS), not a page. Streams declared audio-only by their codecs, or whose segments are ADTS audio, are",
          "refused. An AES-128 segment cannot be sniffed, so it only has to be binary and not served as HTML, JSON or audio (its",
          "key is not fetched). A DASH stream must have no DRM and its video initialization segment must be MP4 data (media",
          "segments are not computed). Streams hosted on a bare IP address are left",
          "out of the catalog. Each catalog channel keeps up to 3 other working streams of the same channel as fallbacks:",
          "same tvg-id, quality feeds (@SD, @HD...) merged, other feeds such as languages kept apart, never matched by name.",
          "M3UPT and Free-TV only list official free streams; iptv-org lists any public stream, so from it only free ad-supported",
          "platforms (Pluto TV, Samsung TV Plus, Amagi, Wurl...), broadcast CDNs and channels served from their own domain are",
          "kept. Free-TV states no license, so none of its entries is copied into the app: the TV downloads that list itself",
          "(IPTV section).", "",
          "| List | Entries | Probed | Working | Needs browser headers | DRM | In the catalog |", "|---|---|---|---|---|---|---|"]
    in_catalog = collections.Counter(i["s"] for r in catalog["rows"] for i in r["items"])
    for n in LISTS:
        mine = [c for c in cand if c["source"] == n]
        ok = sum(1 for c in mine if c["probe"]["status"] in ("OK", "YOUTUBE"))
        md.append(f"| {n} | {len(lists[n])} | {len(mine)} | {ok} | {sum(1 for c in mine if c['headers'] and c['probe']['status'] not in ('OK', 'YOUTUBE'))} "
                  f"| {sum(1 for c in mine if c['probe']['status'] == 'DRM')} | {in_catalog[n]} |")
    md += ["", "Probed = the Portuguese and French candidates, plus English and Spanish channels of the themed categories.", "",
           "## Failure classes", "",
           "EMPTY: HTTP 204 or an empty body or playlist. WEBPAGE: answered 200 with something that is not a stream.",
           "SEGMENT_FORMAT: the segment is a page, JSON or a playlist. AUDIO: audio-only. VARIANT_FORMAT: a variant is not an",
           "HLS playlist. MANIFEST_FORMAT: a DASH manifest that is not XML. UNREACHABLE: DNS, TLS, refused connection or",
           "timeout on the first request. HTTP403: refused",
           "(403 or 451) at any step. HTTPnnn, VARIANTnnn, SEGMENTnnn: that HTTP status (VARIANT0, SEGMENT0 = timeout).", "",
           "| Result | Streams |", "|---|---|"]
    for status, n in collections.Counter(c["probe"]["status"] for c in cand).most_common():
        md.append(f"| {status} | {n} |")
    md += ["", "## Catalog rows", "", "| Category | Row | Channels | Fallback streams |", "|---|---|---|---|"]
    md += [f"| {r['cat']} | {r['row']} | {len(r['items'])} | {sum(len(i.get('a', [])) for i in r['items'])} |" for r in catalog["rows"]]
    md += ["", "## Portuguese and French channels that did not pass", "",
           "| Channel | List | Result | Declares browser headers |", "|---|---|---|---|"]
    for c in cand:
        if c["source"] != "iptv-org" and c["lang"] in ("por", "fra") and c["probe"]["status"] not in ("OK", "YOUTUBE"):
            md.append(f"| {c['name']} | {c['source']} | {c['probe']['status']} ({c['probe']['detail']}) | {'yes' if c['headers'] else 'no'} |")
    md += ["", "## Main channels", "",
           "Every stream of these channels in the three lists, by tvg-id. A stream that answers counts for the catalog only from an",
           "official host; an unofficial relay (operator CDN, bare IP) that answers is listed as such and never used.", "",
           "| Channel | Verdict | Streams (list: result) |", "|---|---|---|"]
    in_hub = {identity(i["id"]) for r in catalog["rows"] for i in r["items"]}
    for tid, title in MAIN_CHANNELS.items():
        mine = [c for c in cand if base_id(c["id"]).lower() == tid]
        works = [c for c in mine if c["probe"]["status"] == "OK"]
        statuses = {c["probe"]["status"] for c in mine}
        verdict = ("working (in the catalog)" if any(identity(c["id"]) in in_hub for c in mine) else
                   "working from an official host, IPTV section only (Free-TV is not copied)" if any(map(authorised, works)) else
                   "no authorised stream: only unofficial relays answer" if works else
                   "DRM: official app only" if "DRM" in statuses else
                   "refused from here (403: geo-blocking or access control)" if "HTTP403" in statuses else
                   "unavailable" if mine else "not verified: no stream in the lists")
        streams = "; ".join(f"{c['source']}: {c['probe']['status']}{'' if authorised(c) or c['probe']['status'] != 'OK' else ' (relay)'}" for c in mine)
        md.append(f"| {title} | {verdict} | {streams or '-'} |")
    (ROOT / "docs").mkdir(exist_ok=True)
    (ROOT / "docs" / "SOURCES.md").write_text("\n".join(md) + "\n")
    total = sum(len(r["items"]) for r in catalog["rows"])
    assert total > 0, "empty catalog: probe failed or the lists changed format"
    print(f"catalog: {total} channels in {len(catalog['rows'])} rows -> {out.relative_to(ROOT)}; report -> docs/SOURCES.md")


if __name__ == "__main__":
    main()
