"""Scanner and playlist cases behind the 2026-11-17 reliability fixes (local server only)."""
import gzip
import os
import urllib.error

import pytest
from conftest import SEGMENT, Handler, playlist

from iptv4free.config import Config, load_config
from iptv4free.pipeline import run_build, run_scan
from iptv4free.playlists import Entry, drop_browser_agent, render_m3u, request_parts
from iptv4free.scanner import Result, Target, _refused, probe, sniff

HTML = b"<!doctype html><html><body>Access denied in your region</body></html>\n" * 40
JSON = b'{"status": "error", "message": "geo blocked"}' + b" " * 2000
PT_HTML = ("<!doctype html><html><body><h1>Conteúdo indisponível</h1><p>Não é possível ver este "
           "vídeo na sua região. Pedimos desculpa: só está disponível em Portugal, Açores e Madeira."
           "</p></body></html>\n").encode() * 20  # under 90 % ASCII, 512-byte head ends inside a character


FMP4 = b"\x00\x00\x00\x18ftypiso6" + b"\x00" * 2000


def dash(init):
    return (b'<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic"><Period>'
            b'<AdaptationSet mimeType="audio/mp4"><SegmentTemplate initialization="a.mp4"/><Representation id="a"/></AdaptationSet>'
            b'<AdaptationSet contentType="video"><SegmentTemplate initialization="' + init.encode() + b'" media="$Number$.m4s"/>'
            b'<Representation id="v" mimeType="video/mp4" height="720"/></AdaptationSet></Period></MPD>')


def media(segment, *tags):
    return playlist("#EXT-X-TARGETDURATION:6", *tags, "#EXTINF:6,", segment)


def master(*variants):
    return playlist(*(line for tag, uri in variants for line in (f"#EXT-X-STREAM-INF:{tag}", uri)))


@pytest.fixture(scope="module")
def base(http_server):
    Handler.routes.update({
        "/r/gz.m3u8": (200, gzip.compress(media("ok.ts")), None),
        "/r/ok.ts": (200, SEGMENT, None),
        "/r/html.m3u8": (200, media("page.ts"), None),
        "/r/page.ts": (200, HTML, None),
        "/r/json.m3u8": (200, media("err.ts"), None),
        "/r/err.ts": (200, JSON, None),
        "/r/html_direct": (200, HTML, None),
        "/r/pt_direct": (200, PT_HTML, None),
        "/r/aes.m3u8": (200, media("pt.ts", '#EXT-X-KEY:METHOD=AES-128,URI="k.key"'), None),
        "/r/pt.ts": (200, PT_HTML, None),
        "/r/blank.m3u8": (200, b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n\ngz.m3u8\n", None),
        "/r/empty.m3u8": (204, b"", None),
        "/r/deny.m3u8": (403, b"", None),
        "/r/audio.m3u8": (200, master(("BANDWIDTH=3000000,RESOLUTION=1280x720", "gone.m3u8"),
                                      ('BANDWIDTH=64000,CODECS="mp4a.40.2"', "gz.m3u8")), None),
        "/r/radio.m3u8": (200, master(('BANDWIDTH=64000,CODECS="mp4a.40.2"', "gz.m3u8")), None),
        "/r/fallback.m3u8": (200, master(("BANDWIDTH=1000000,RESOLUTION=640x360", "gz.m3u8"),
                                         ("BANDWIDTH=5000000,RESOLUTION=1920x1080", "gone.m3u8")), None),
        "/r/refused.m3u8": (200, master(("BANDWIDTH=5000000,RESOLUTION=1920x1080", "deny.m3u8"),
                                        ("BANDWIDTH=1000000,RESOLUTION=640x360", "gz.m3u8")), None),
        "/r/live.mpd": (200, dash("v/init.mp4"), None),
        "/r/v/init.mp4": (200, FMP4, None),
        "/r/gone.mpd": (200, dash("v/missing.mp4"), None),
        "/r/page.mpd": (200, dash("page.ts"), None),
        "/r/drm.mpd": (200, dash("v/init.mp4").replace(b"<Period", b'<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011"/><Period'), None),
    })
    return http_server + "/r"


def test_dash_needs_a_video_initialization_segment(base):
    assert check(f"{base}/live.mpd") == Result(f"{base}/live.mpd", True, "DASH initialization segment, fMP4")
    assert not check(f"{base}/gone.mpd").ok                      # valid manifest, segments unavailable
    assert "HTML page" in check(f"{base}/page.mpd").detail       # an error page where the video should be
    drm = check(f"{base}/drm.mpd")
    assert not drm.ok and "DRM" in drm.detail


def check(url):
    return probe(Target(url, url, ()), timeout=2)


def test_gzip_playlist_is_decoded(base):
    r = check(base + "/gz.m3u8")
    assert r.ok and "MPEG-TS" in r.detail, r


@pytest.mark.parametrize("path, what", [("/html.m3u8", "HTML"), ("/json.m3u8", "JSON"),
                                        ("/html_direct", "HTML"), ("/pt_direct", "HTML"),
                                        ("/aes.m3u8", "HTML")])
def test_error_pages_answered_with_200_are_not_media(base, path, what):
    r = check(base + path)
    assert not r.ok and what in r.detail, r


def test_204_means_the_server_refuses_this_player(base):
    r = check(base + "/empty.m3u8")
    assert not r.ok and r.detail == "empty (server refuses this player)"


def test_audio_only_variant_is_not_a_tv_channel(base):
    r = check(base + "/audio.m3u8")
    assert not r.ok and "404" in r.detail, r
    r = check(base + "/radio.m3u8")
    assert not r.ok and "audio-only" in r.detail, r


def test_blank_line_between_variant_tag_and_uri(base):
    r = check(base + "/blank.m3u8")
    assert r.ok and "MPEG-TS" in r.detail, r


@pytest.mark.parametrize("data, what", [
    (b"\x00\x00\x00\x18ftyp" + bytes(2000), "fMP4"),
    (b"\x00\x00\x00\x18moof" + bytes(2000), "fMP4"),
    (b"\x00\x00\x00\x20emsg" + bytes(2000), "fMP4"),
    (b"\x00\x00\x00\x20prft" + bytes(2000), "fMP4"),
    (b"ID3\x04" + bytes(2000), "ID3"),
    (b"\xff\xf1\x50" + bytes(2000), "ADTS"),
    (b"\x00\x00\x01\xba" + bytes(2000), "MPEG-PS"),
    (b"FLV\x01\x05" + bytes(3000), "FLV"),
    (b"\x1aE\xdf\xa3" + bytes(3000), "Matroska"),
    (b"OggS\x00" + bytes(3000), "Ogg"),
    (b"\xff\xfb\x90" + bytes(3000), "MPEG audio"),
])
def test_sniff_recognises_containers(data, what):
    ok, detail = sniff(data)
    assert ok and what in detail, detail


def test_sniff_encrypted_segment():
    noise = b"\x00" + os.urandom(4095)  # never a container signature or an audio frame sync
    assert sniff(noise) == (False, "unknown data (4096 bytes), not a video stream")
    assert sniff(noise, encrypted=True) == (True, "encrypted segment")
    assert sniff(HTML, encrypted=True) == (False, "HTML page instead of video")
    assert sniff(PT_HTML, encrypted=True) == (False, "HTML page instead of video")


def test_timeouts_and_403_stop_tries_on_a_host():
    assert _refused(TimeoutError())
    assert _refused(urllib.error.URLError(TimeoutError()))
    assert _refused(urllib.error.HTTPError("u", 403, "Forbidden", None, None))
    assert not _refused(urllib.error.HTTPError("u", 404, "Not Found", None, None))


def test_broken_best_variant_falls_back_to_a_lower_one(base):
    r = check(base + "/fallback.m3u8")
    assert r.ok and "variant 2" in r.detail, r


def test_403_on_the_best_variant_stops_on_that_host(base):
    r = check(base + "/refused.m3u8")
    assert not r.ok and "403" in r.detail, r


def test_browser_user_agent_dropped_but_referer_kept():
    e = Entry("RTP 1", "http://x/a.m3u8|User-Agent=Mozilla%2F5.0%20(X11)&referer=https://www.rtp.pt/",
              options=["#EXTVLCOPT:http-user-agent=Mozilla/5.0 (X11; rv:159.0) Gecko/20100101 Firefox/159.0",
                       "#EXTVLCOPT:http-referrer=https://www.rtp.pt/play",
                       "#KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Chrome/120&Referer=https://r",
                       "#KODIPROP:inputstream=inputstream.adaptive"])
    out = drop_browser_agent(e)
    assert request_parts(out) == ("http://x/a.m3u8", {"Referer": "https://www.rtp.pt/"})
    text = render_m3u([out])
    assert "Mozilla" not in text and "Chrome" not in text
    assert "#KODIPROP:inputstream.adaptive.stream_headers=Referer=https://r" in text
    assert "#KODIPROP:inputstream=inputstream.adaptive" in text


def test_non_browser_user_agent_is_kept():
    e = Entry("A", "http://x/a.m3u8", options=["#EXTVLCOPT:http-user-agent=VLC/3.0.20"])
    assert drop_browser_agent(e) == e
    assert request_parts(e)[1] == {"User-Agent": "VLC/3.0.20"}


def test_pipeline_scans_and_builds_without_the_browser_agent(http_server, make_config):
    Handler.routes.update({  # answers only Kodi's own user agent, like a server refusing browsers
        "/b/list.m3u": (200, playlist("#EXTINF:-1,Browser UA",
                                      "#EXTVLCOPT:http-user-agent=Mozilla/5.0 (X11) Firefox/159.0",
                                      f"{http_server}/b/index.m3u8"), None),
        "/b/index.m3u8": (200, media("seg.ts"), Config.user_agent),
        "/b/seg.ts": (200, SEGMENT, Config.user_agent),
    })
    cfg = load_config(make_config(
        f'[[lineup]]\nid = "b"\nname = "B"\nsources = ["{http_server}/b/list.m3u"]\n',
        scan={"timeout": "2", "workers": "2"}))
    assert run_scan(cfg).alive == 1
    out, _ = run_build(cfg)["b"]
    text = out.read_text()
    assert "Browser UA" in text and "Mozilla" not in text
