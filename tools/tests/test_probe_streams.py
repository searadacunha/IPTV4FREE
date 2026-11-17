import unittest
from unittest.mock import patch

from tools import probe_streams


class HlsProbeTests(unittest.TestCase):
    def test_falls_back_to_working_lower_quality(self):
        master = ("#EXTM3U\n"
                  "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080\nhigh.m3u8\n"
                  "#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360\nlow.m3u8\n")
        media = "#EXTM3U\n#EXTINF:6,\nsegment.ts\n"
        requested = []

        def fetch(url, limit=262144):
            requested.append(url)
            if url.endswith("high.m3u8"):
                return 404, url, "", b""
            if url.endswith("low.m3u8"):
                return 200, url, "application/vnd.apple.mpegurl", media.encode()
            if url.endswith("segment.ts"):
                return 200, url, "video/MP2T", b"\x47" * 400
            raise AssertionError(url)

        with patch.object(probe_streams, "fetch", side_effect=fetch):
            result = probe_streams.probe_hls("https://example.com/master.m3u8", master)

        self.assertEqual("OK", result["status"])
        self.assertTrue(result["quality"].startswith("360p"))
        self.assertIn("https://example.com/low.m3u8", requested)

    def test_http_200_html_segment_is_not_media(self):
        media = "#EXTM3U\n#EXTINF:6,\nsegment.ts\n"
        with patch.object(probe_streams, "fetch", return_value=(
            200, "https://example.com/segment.ts", "text/html",
            b"<html><body>Access denied</body></html>" * 20,
        )):
            result = probe_streams.probe_hls("https://example.com/live.m3u8", media)
        self.assertEqual("SEGMENT_FORMAT", result["status"])

    def test_all_failed_variants_are_rejected(self):
        master = ("#EXTM3U\n"
                  "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080\nhigh.m3u8\n"
                  "#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360\nlow.m3u8\n")
        with patch.object(probe_streams, "fetch", return_value=(404, "", "", b"")):
            result = probe_streams.probe_hls("https://example.com/master.m3u8", master)
        self.assertEqual("VARIANT404", result["status"])


if __name__ == "__main__":
    unittest.main()
