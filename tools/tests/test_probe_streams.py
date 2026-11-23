import gzip
import http.server
import threading
import unittest
from unittest.mock import patch

from tools import probe_streams

TS = (b"\x47" + b"\x00" * 187) * 3
MEDIA = "#EXTM3U\n#EXTINF:6,\nsegment.ts\n"
HTML = b"<html><body>Access denied</body></html>" * 20


def fake(routes, requested=None):
    """fetch() stand-in: routes maps a URL suffix to (status, content type, body)."""
    def fetch(url, limit=262144):
        if requested is not None:
            requested.append(url)
        for suffix, (code, ctype, body) in routes.items():
            if url.endswith(suffix):
                return code, url, ctype, body.encode() if isinstance(body, str) else body
        raise AssertionError(url)
    return patch.object(probe_streams, "fetch", side_effect=fetch)


class LocalServerTests(unittest.TestCase):
    """fetch() itself, against a tiny server on 127.0.0.1."""

    @classmethod
    def setUpClass(cls):
        routes = {
            "/gzip/master.m3u8": (200, "application/vnd.apple.mpegurl", {"Content-Encoding": "gzip"},
                                  gzip.compress(b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720\nmedia.m3u8\n")),
            "/gzip/media.m3u8": (200, "application/vnd.apple.mpegurl", {"Content-Encoding": "gzip"}, gzip.compress(MEDIA.encode())),
            # gzip without the header: Media3 does not inflate it, so neither does the probe
            "/bare-gzip.m3u8": (200, "application/vnd.apple.mpegurl", {}, gzip.compress(MEDIA.encode())),
            "/fake-gzip.m3u8": (200, "application/vnd.apple.mpegurl", {"Content-Encoding": "gzip"}, b"\x1f\x8bnot gzip at all"),
            "/gzip/segment.ts": (200, "video/MP2T", {}, TS),
            "/empty.m3u8": (204, "", {}, b""),
            "/page.ts": (200, "text/html", {}, HTML),
            "/page.bin": (200, "application/octet-stream", {}, HTML),
            "/g.bin": (200, "application/octet-stream", {}, b"G" + b"x" * 400),
            "/live.bin": (200, "application/octet-stream", {}, TS),
        }

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                code, ctype, headers, body = routes.get(self.path, (404, "", {}, b""))
                self.send_response(code)
                for k, v in {"Content-Type": ctype, **headers}.items():
                    self.send_header(k, v)
                if code != 204:
                    self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def test_gzip_playlists_are_inflated(self):
        result = probe_streams.probe(self.base + "/gzip/master.m3u8")
        self.assertEqual("OK", result["status"], result)
        self.assertEqual("720p", result["quality"])

    def test_undeclared_gzip_is_not_inflated(self):
        self.assertEqual("WEBPAGE", probe_streams.probe(self.base + "/bare-gzip.m3u8")["status"])
        self.assertEqual(b"\x1f\x8bnot gzip at all", probe_streams.fetch(self.base + "/fake-gzip.m3u8")[3])

    def test_204_is_empty(self):
        self.assertEqual("EMPTY", probe_streams.probe(self.base + "/empty.m3u8")["status"])

    def test_direct_html_is_a_webpage(self):
        self.assertEqual("WEBPAGE", probe_streams.probe(self.base + "/page.ts")["status"])

    def test_octet_stream_alone_is_not_media(self):
        self.assertEqual("WEBPAGE", probe_streams.probe(self.base + "/page.bin")["status"])
        self.assertEqual("WEBPAGE", probe_streams.probe(self.base + "/g.bin")["status"])
        self.assertEqual({"status": "OK", "detail": "direct TS"}, probe_streams.probe(self.base + "/live.bin"))


class MediaSniffTests(unittest.TestCase):
    def test_media_signatures(self):
        id3 = b"ID3\x04\x00\x00\x00\x00\x00\x02xx"
        adts = b"\xff\xf1\x50\x80" + b"\x00" * 200
        self.assertEqual("TS", probe_streams.media("application/octet-stream", TS))
        self.assertEqual("MP4", probe_streams.media("", b"\x00\x00\x00\x18ftypiso6" + b"\x00" * 200))
        self.assertEqual("MP4", probe_streams.media("", b"\x00\x00\x00\x18moof" + b"\x00" * 200))
        self.assertEqual("MP4", probe_streams.media("video/mp4", b"\x00\x00\x00\x20emsg" + b"\x00" * 200))  # in-band SCTE-35/ID3
        self.assertEqual("MP4", probe_streams.media("video/mp4", b"\x00\x00\x00\x20prft" + b"\x00" * 200))  # low latency
        self.assertEqual("PS", probe_streams.media("", b"\x00\x00\x01\xba" + b"\x00" * 200))
        self.assertEqual("AAC", probe_streams.media("audio/aac", adts))
        self.assertEqual("AAC", probe_streams.media("audio/aac", id3 + adts))

    def test_not_media(self):
        for ctype, body in [("application/octet-stream", b"G" + b"x" * 400), ("application/octet-stream", b'{"error": 1}' * 30),
                            ("video/MP2T", b"#EXTM3U\n" * 40), ("application/octet-stream", HTML), ("text/html", TS),
                            ("application/json", TS)]:
            self.assertIsNone(probe_streams.media(ctype, body), body[:12])


class HlsProbeTests(unittest.TestCase):
    def test_falls_back_to_working_lower_quality(self):
        master = ("#EXTM3U\n"
                  "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080\nhigh.m3u8\n"
                  "#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360\nlow.m3u8\n")
        requested = []
        with fake({"high.m3u8": (404, "", ""), "low.m3u8": (200, "application/vnd.apple.mpegurl", MEDIA),
                   "segment.ts": (200, "video/MP2T", TS)}, requested):
            result = probe_streams.probe_hls("https://example.com/master.m3u8", master)
        self.assertEqual("OK", result["status"])
        self.assertTrue(result["quality"].startswith("360p"))
        self.assertIn("https://example.com/low.m3u8", requested)

    def test_http_200_html_segment_is_not_media(self):
        with fake({"segment.ts": (200, "text/html", HTML)}):
            result = probe_streams.probe_hls("https://example.com/live.m3u8", MEDIA)
        self.assertEqual("SEGMENT_FORMAT", result["status"])

    def test_empty_segment_is_empty(self):
        for code, body, status in [(204, b"", "EMPTY"), (200, b"", "EMPTY"), (200, TS[:100], "SEGMENT_FORMAT"), (404, b"", "SEGMENT404")]:
            with fake({"segment.ts": (code, "video/MP2T", body)}):
                self.assertEqual(status, probe_streams.probe_hls("https://example.com/live.m3u8", MEDIA)["status"], code)

    def test_json_segment_is_not_media(self):
        with fake({"segment.ts": (200, "application/octet-stream", b'{"error": "token expired"}' * 20)}):
            result = probe_streams.probe_hls("https://example.com/live.m3u8", MEDIA)
        self.assertEqual("SEGMENT_FORMAT", result["status"])

    def test_all_failed_variants_are_rejected(self):
        master = ("#EXTM3U\n"
                  "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080\nhigh.m3u8\n"
                  "#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360\nlow.m3u8\n")
        with patch.object(probe_streams, "fetch", return_value=(404, "", "", b"")):
            result = probe_streams.probe_hls("https://example.com/master.m3u8", master)
        self.assertEqual("VARIANT404", result["status"])

    def test_nested_master_is_followed(self):
        master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000\ninner.m3u8\n"
        inner = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS=\"avc1.64001f,mp4a.40.2\"\nmedia.m3u8\n"
        with fake({"inner.m3u8": (200, "application/vnd.apple.mpegurl", inner),
                   "media.m3u8": (200, "application/vnd.apple.mpegurl", MEDIA), "segment.ts": (200, "video/MP2T", TS)}):
            result = probe_streams.probe_hls("https://example.com/master.m3u8", master)
        self.assertEqual("OK", result["status"])
        self.assertEqual("720p avc1.64001f,mp4a.40.2", result["quality"])

    def test_aes128_segments(self):
        aes = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:6,\nsegment.ts\n"
        cipher = bytes(range(256)) * 128  # stands for ciphertext: opaque bytes, starting with a control byte
        for ctype, body, status in [("video/MP2T", cipher, "OK"), ("video/MP2T", b"<" + cipher[:-1], "OK"),
                                    ("video/MP2T", b"{" + cipher[:-1], "OK"), ("video/MP2T", HTML, "SEGMENT_FORMAT"),
                                    ("text/plain", b"Access denied: your country is not allowed" * 10, "SEGMENT_FORMAT"),
                                    ("application/octet-stream", b"ERROR token expired " * 20, "SEGMENT_FORMAT"),
                                    ("audio/aac", cipher, "AUDIO")]:
            with fake({"segment.ts": (200, ctype, body)}):
                self.assertEqual(status, probe_streams.probe_hls("https://example.com/live.m3u8", aes)["status"], body[:12])
        # same content-type rule as clear segments: a CDN that labels binary .ts as text/plain still passes
        with fake({"segment.ts": (200, "text/plain", cipher)}):
            self.assertEqual("OK", probe_streams.probe_hls("https://example.com/live.m3u8", aes)["status"])
        # the key in force for the sniffed segment is NONE: it must be real media
        clear = aes.replace("#EXTINF", "#EXT-X-KEY:METHOD=NONE\n#EXTINF")
        with fake({"segment.ts": (200, "video/MP2T", b"G" + b"x" * 400)}):
            self.assertEqual("SEGMENT_FORMAT", probe_streams.probe_hls("https://example.com/live.m3u8", clear)["status"])

    def test_master_nested_three_levels_is_rejected(self):
        master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3000000\ninner.m3u8\n"
        inner = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\ninner2.m3u8\n"
        inner2 = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nmedia.m3u8\n"
        with fake({"inner.m3u8": (200, "application/vnd.apple.mpegurl", inner), "inner2.m3u8": (200, "application/vnd.apple.mpegurl", inner2),
                   "media.m3u8": (200, "application/vnd.apple.mpegurl", MEDIA), "segment.ts": (200, "video/MP2T", TS)}):
            self.assertEqual("VARIANT_FORMAT", probe_streams.probe_hls("https://example.com/master.m3u8", master)["status"])

    def test_playlist_cut_at_the_read_limit_drops_its_partial_last_line(self):
        long = "#EXTM3U\n" + "#EXTINF:6,\nsegment.ts\n" * 11000
        long += "#" * (probe_streams.LIMIT - len(long) - 15) + "\n#EXTINF:6,\nseg"  # cut inside the last URI
        with fake({"live.m3u8": (200, "application/vnd.apple.mpegurl", long), "segment.ts": (200, "video/MP2T", TS)}):
            self.assertEqual("OK", probe_streams.probe("https://example.com/live.m3u8")["status"])

    def test_second_key_sample_aes_is_drm(self):
        media = ("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\n#EXTINF:6,\na.ts\n"
                 "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\"\n#EXTINF:6,\nsegment.ts\n")
        with fake({}):
            result = probe_streams.probe_hls("https://example.com/live.m3u8", media)
        self.assertEqual("DRM", result["status"])

    def test_audio_only_fallback_is_rejected(self):
        declared = ("#EXTM3U\n"
                    "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"\nhigh.m3u8\n"
                    "#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.2\"\naudio.m3u8\n")
        requested = []
        with fake({"high.m3u8": (404, "", "")}, requested):
            self.assertNotEqual("OK", probe_streams.probe_hls("https://example.com/master.m3u8", declared)["status"])
        self.assertNotIn("https://example.com/audio.m3u8", requested)
        # its siblings declare a resolution: a variant that declares nothing is audio, even when its segments are TS
        silent = ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028\"\nhigh.m3u8\n"
                  "#EXT-X-STREAM-INF:BANDWIDTH=64000\naudio.m3u8\n")
        requested = []
        with fake({"high.m3u8": (404, "", ""), "audio.m3u8": (200, "application/vnd.apple.mpegurl", MEDIA),
                   "segment.ts": (200, "video/MP2T", TS)}, requested):
            self.assertEqual("VARIANT404", probe_streams.probe_hls("https://example.com/master.m3u8", silent)["status"])
        self.assertNotIn("https://example.com/audio.m3u8", requested)
        # nothing declared anywhere: only the segment shows the lower variant is audio
        sniffed = ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhigh.m3u8\n"
                   "#EXT-X-STREAM-INF:BANDWIDTH=64000\naudio.m3u8\n")
        with fake({"high.m3u8": (404, "", ""), "audio.m3u8": (200, "application/vnd.apple.mpegurl", MEDIA),
                   "segment.ts": (200, "audio/aac", b"\xff\xf1\x50\x80" + b"\x00" * 300)}):
            result = probe_streams.probe_hls("https://example.com/master.m3u8", sniffed)
        self.assertEqual("VARIANT404", result["status"])

    def test_refusing_host_gets_one_more_try_and_reports_best(self):
        master = "#EXTM3U\n" + "".join(f"#EXT-X-STREAM-INF:BANDWIDTH={h * 1000},RESOLUTION=1x{h}\nv{h}.m3u8\n" for h in (1080, 720, 480, 360))
        requested = []
        with fake({"v1080.m3u8": (0, "TimeoutError", ""), ".m3u8": (403, "", "")}, requested):
            result = probe_streams.probe_hls("https://example.com/master.m3u8", master)
        self.assertEqual(["https://example.com/v1080.m3u8", "https://example.com/v720.m3u8"], requested)
        self.assertEqual("VARIANT0", result["status"])
        self.assertTrue(result["quality"].startswith("1080p"))


FMP4 = b"\x00\x00\x00\x18ftypiso6" + b"\x00" * 300
MPD = """<?xml version="1.0"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">
  <BaseURL>https://cdn.example.com/live/</BaseURL>
  <Period id="1">
    <AdaptationSet mimeType="audio/mp4"><SegmentTemplate initialization="$RepresentationID$/init.mp4" media="$RepresentationID$/$Number$.m4s"/>
      <Representation id="a1" bandwidth="128000"/></AdaptationSet>
    <AdaptationSet contentType="video" mimeType="video/mp4">
      <SegmentTemplate initialization="$RepresentationID$_$Bandwidth$/init.mp4" media="$RepresentationID$/$Number$.m4s"/>
      <Representation id="v2160" bandwidth="16000000" height="2160"/>
      <Representation id="v1080" bandwidth="6000000" height="1080"/>
      <Representation id="v720" bandwidth="3000000" height="720"/>
    </AdaptationSet>
  </Period>
</MPD>"""


class DashProbeTests(unittest.TestCase):
    def test_best_video_up_to_1080p_initialization_segment(self):
        self.assertEqual("https://cdn.example.com/live/v1080_6000000/init.mp4",
                         probe_streams.dash_init("https://origin.example.com/x/manifest.mpd", MPD.encode()))

    def test_working_dash_stream(self):
        with fake({"v1080_6000000/init.mp4": (200, "video/mp4", FMP4)}):
            self.assertEqual("OK", probe_streams.probe_dash("https://origin.example.com/manifest.mpd", MPD.encode())["status"])

    def test_valid_manifest_but_segments_unavailable(self):
        for code, ctype, body, status in [(404, "", b"", "SEGMENT404"), (403, "", b"", "HTTP403"), (204, "", b"", "EMPTY"),
                                          (200, "text/html", HTML, "SEGMENT_FORMAT"), (0, "TimeoutError", b"", "SEGMENT0")]:
            with fake({"init.mp4": (code, ctype, body)}):
                self.assertEqual(status, probe_streams.probe_dash("https://o.example.com/m.mpd", MPD.encode())["status"], code)

    def test_drm_and_unreadable_manifests(self):
        protected = MPD.replace("<Period", '<ContentProtection schemeIdUri="urn:uuid:e2719d58"/><Period', 1)
        with fake({"m.mpd": (200, "application/dash+xml", protected), "bad.mpd": (200, "application/dash+xml", "<MPD <<")}):
            self.assertEqual("DRM", probe_streams.probe("https://o.example.com/m.mpd")["status"])
            self.assertEqual("MANIFEST_FORMAT", probe_streams.probe("https://o.example.com/bad.mpd")["status"])

    def test_manifest_without_initialization_is_only_read(self):
        base = '<MPD><Period><AdaptationSet mimeType="video/mp4"><Representation id="v" height="720"><BaseURL>v.mp4</BaseURL></Representation></AdaptationSet></Period></MPD>'
        with fake({}):
            self.assertEqual("OK", probe_streams.probe_dash("https://o.example.com/m.mpd", base.encode())["status"])

    def test_every_kodiprop_drm_syntax_marks_the_entry(self):
        text = ("#EXTM3U\n#EXTINF:-1 tvg-id=\"RTP1.pt\",RTP 1\n#KODIPROP:inputstream.adaptive.drm_legacy=org.w3.clearkey|01:02\nhttps://a/m.mpd\n"
                "#EXTINF:-1,B\n#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha\nhttps://b/m.mpd\n"
                "#EXTINF:-1,C\n#KODIPROP:inputstream.adaptive.manifest_type=mpd\nhttps://c/m.mpd\n")
        self.assertEqual([True, True, False], [c["drm"] for c in probe_streams.parse_m3u(text, "M3UPT")])


class CatalogTests(unittest.TestCase):
    @staticmethod
    def entry(name, tvg_id, url, status="OK"):
        return {"name": name, "id": tvg_id, "url": url, "probe": {"status": status}}

    def test_alternates_grouped_by_tvg_id_without_feed(self):
        entries = [self.entry("TF1", "TF1.fr@SD", "https://a/1"), self.entry("TF1 HD", "tf1.fr@HD", "https://a/2"),
                   self.entry("TF1", "TF1.fr", "https://a/1"), self.entry("TF1", "TF1.fr", "https://a/3"),
                   self.entry("TF1", "", "https://a/4")]
        out = [(c["url"], alts) for c, alts in probe_streams.group(entries)]
        self.assertEqual([("https://a/1", ["https://a/2", "https://a/3"]), ("https://a/4", [])], out)

    def test_language_feeds_are_other_channels(self):
        entries = [self.entry("Deutsche Welle Español", "DW.de@Espanol", "https://a/1"),
                   self.entry("Deutsche Welle Arabic", "DW.de@Arabic", "https://a/2"),
                   self.entry("RTP 1", "RTP1.pt", "https://a/3"), self.entry("RTP 1 HD", "RTP1.pt@HD", "https://a/4")]
        out = [(c["url"], alts) for c, alts in probe_streams.group(entries)]
        self.assertEqual([("https://a/1", []), ("https://a/2", []), ("https://a/3", ["https://a/4"])], out)

    def test_at_most_three_alternates(self):
        entries = [self.entry("RTP 1", "RTP1.pt", f"https://a/{i}") for i in range(6)]
        [(best, alts)] = probe_streams.group(entries)
        self.assertEqual(("https://a/0", ["https://a/1", "https://a/2", "https://a/3"]), (best["url"], alts))

    def test_alternate_of_one_channel_stays_another_channels_stream(self):
        entries = [self.entry("A", "A.pt", "https://a/1"), self.entry("A", "A.pt", "https://a/2"), self.entry("B", "B.pt", "https://a/2")]
        out = [(c["id"], c["url"], alts) for c, alts in probe_streams.group(entries)]
        self.assertEqual([("A.pt", "https://a/1", ["https://a/2"]), ("B.pt", "https://a/2", [])], out)

    def test_primary_url_is_not_a_second_card(self):
        entries = [self.entry("A", "A.pt", "https://a/1"), self.entry("B", "B.pt", "https://a/1")]
        self.assertEqual([("A.pt", [])], [(c["id"], alts) for c, alts in probe_streams.group(entries)])

    def test_only_working_streams_become_alternates(self):
        entries = [self.entry("A", "A.pt", "https://a/1"), self.entry("A", "A.pt", "https://youtube.com/a", "YOUTUBE"),
                   self.entry("A", "A.pt", "https://a/2")]
        [(best, alts)] = probe_streams.group(entries)
        self.assertEqual(["https://a/2"], alts)

    def test_similar_names_are_not_merged(self):
        entries = [self.entry("RTP 1", "RTP1.pt", "https://a/1"), self.entry("RTP 1 Madeira", "RTPMadeira.pt", "https://a/2"),
                   self.entry("France 2", "", "https://a/3"), self.entry("France 2", "", "https://a/4")]
        self.assertEqual(4, len(list(probe_streams.group(entries))))

    def test_unofficial_relay_is_rejected(self):
        self.assertFalse(probe_streams.official("TF1", "https://r.eu.cdn-redge.media/livehls/oil/freecdn/live/tf1/live.livx/playlist.m3u8"))
        self.assertTrue(probe_streams.official("France 24 Français", "https://live.france24.com/hls/live/x/master.m3u8"))

    def test_labels_are_stripped_and_geo_kept(self):
        self.assertEqual(("France 24", True), probe_streams.label("France 24 (1080p) [Geo-blocked]"))
        self.assertEqual(("Euronews", False), probe_streams.label("Euronews [Not 24/7]"))
        self.assertEqual(("RTP 1", True), probe_streams.label("RTP 1 Ⓖ"))

    def test_geo_flag_comes_from_any_list(self):
        entries = [self.entry("RTP 2", "RTP2.pt", "https://a/1"), self.entry("RTP2 Ⓖ", "RTP2.pt", "https://b/1"),
                   self.entry("France 24 (1080p) [Geo-blocked]", "France24.fr@HD", "https://c/1"),
                   self.entry("Arte [Geo-blocked]", "", "https://d/1"), self.entry("TF1", "TF1.fr", "https://e/1")]
        self.assertEqual({"rtp2.pt", "france24.fr"}, probe_streams.geo_flagged(entries))


class SecondPassTests(unittest.TestCase):
    def test_a_refusal_during_the_burst_is_probed_again(self):
        calls = {}
        def probe(url):
            calls[url] = calls.get(url, 0) + 1
            if url.endswith("dead"):
                return {"status": "HTTP404", "detail": "404"}
            return {"status": "OK" if calls[url] > 1 else "HTTP403", "detail": ""}
        with patch.object(probe_streams, "probe", side_effect=probe):
            results = probe_streams.probe_all(["https://a/tvi", "https://a/dead"])
        self.assertEqual("OK", results["https://a/tvi"]["status"])
        self.assertEqual("HTTP404", results["https://a/dead"]["status"])
        self.assertEqual({"https://a/tvi": 2, "https://a/dead": 2}, calls)  # one second try each, no loop


if __name__ == "__main__":
    unittest.main()


class OfficialListTests(unittest.TestCase):
    def test_curated_streams_are_plain_broadcaster_urls(self):
        entries = probe_streams.parse_m3u(probe_streams.OFFICIAL.read_text("utf-8"), "Officiel")
        self.assertTrue(entries)
        for c in entries:
            self.assertTrue(c["id"], c["name"])
            self.assertTrue(c["url"].startswith("https://"), c["url"])
            self.assertNotIn("?", c["url"])  # a token or signature expires and is someone's access, not a public stream
            self.assertNotIn("__token__", c["url"])
            self.assertFalse(probe_streams.bare_ip(c["url"]) or c["drm"] or c["headers"], c["url"])
            self.assertFalse(probe_streams.sanctioned(c["name"], c["url"]))
