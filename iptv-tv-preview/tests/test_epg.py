import gzip
import hashlib
import os
import time

from conftest import EPG_XML

from iptv4free import epg


def test_parse_channels_plain_and_gzip():
    for data in (EPG_XML, gzip.compress(EPG_XML)):
        index = epg.parse_channels(data)
        assert len(index) == 3
        assert index.ids["betanews.xx"] == "BetaNews.xx"
        assert index.names[epg.match_key("Gamma TV")] == "Gamma.xx"


def test_match_order_alias_then_id_then_name():
    index = epg.parse_channels(EPG_XML)
    assert epg.match("Whatever", "Alpha.xx@SD", {}, index) == "Alpha.xx"
    assert epg.match("Whatever", "alpha.XX", {}, index) == "Alpha.xx"
    assert epg.match("Beta News", "", {}, index) == "BetaNews.xx"
    assert epg.match("Alpha", "Alpha.xx", {"alpha": "gamma.xx"}, index) == "Gamma.xx"
    assert epg.match("Nobody", "Nobody.xx", {}, index) is None


def test_alias_to_an_id_missing_from_the_guide_is_ignored():
    index = epg.parse_channels(EPG_XML)
    assert epg.match("Alpha", "", {"alpha": "Renamed.xx"}, index) == "Alpha.xx"
    assert epg.assign([("TFX", "", "")], {"TFX": "Missing.xx"}, index) == [None]


def test_tvg_name_matches_display_name_with_spaces_or_underscores():
    index = epg.parse_channels(EPG_XML)
    assert epg.match("BN", "", {}, index, tvg_name="Beta News") == "BetaNews.xx"
    assert epg.match("BN", "", {}, index, tvg_name="Beta_News") == "BetaNews.xx"
    assert epg.match("Gamma TV", "", {}, index, tvg_name="Alpha") == "Alpha.xx"  # tvg-name before name


def test_loose_name_match_never_gives_one_guide_to_two_channels():
    index = epg.parse_channels(EPG_XML)
    assert epg.match("Beta News (1080p)", "", {}, index) is None  # strict match only
    assert epg.assign([("Beta News (1080p)", "", "")], {}, index) == ["BetaNews.xx"]
    assert epg.assign([("Beta News (1080p)", "", ""), ("BETA-NEWS", "", "")], {}, index) == [None, None]
    assert epg.assign([("Beta News", "", ""), ("Beta-News!", "", "")], {}, index) == ["BetaNews.xx", None]


def _cache_file(cache_dir, url):
    return cache_dir / (hashlib.sha256(url.encode()).hexdigest()[:16] + ".xmltv")


def test_load_index_downloads_and_caches(http_server, tmp_path):
    url = f"{http_server}/epg.xml.gz"
    index = epg.load_index(url, tmp_path, 12, 5, "test")
    assert index is not None and len(index) == 3
    assert _cache_file(tmp_path, url).exists()


def test_load_index_falls_back_to_stale_cache_when_download_fails(tmp_path):
    url = "http://127.0.0.1:9/unreachable.xml.gz"
    cached = _cache_file(tmp_path, url)
    cached.write_bytes(gzip.compress(EPG_XML))
    os.utime(cached, (time.time() - 48 * 3600,) * 2)
    index = epg.load_index(url, tmp_path, 12, 2, "test")
    assert index is not None and len(index) == 3


def test_load_index_without_cache_or_with_corrupt_cache_returns_none(tmp_path):
    assert epg.load_index("http://127.0.0.1:9/x.xml", tmp_path, 12, 2, "test") is None
    url = "http://127.0.0.1:9/corrupt.xml"
    _cache_file(tmp_path, url).write_bytes(b"<tv><chan")  # fresh but unreadable
    assert epg.load_index(url, tmp_path, 12, 2, "test") is None


def test_ambiguous_guide_names_never_choose_the_first_provider():
    guide = b"""<tv>
      <channel id="north.xx"><display-name>World News HD</display-name><display-name>Shared</display-name></channel>
      <channel id="south.xx"><display-name>World News SD</display-name><display-name>Shared</display-name></channel>
      <channel id="sports.xx"><display-name>Sports Network</display-name></channel>
    </tv>"""
    index = epg.parse_channels(guide)
    assert len(index) == 3
    assert epg.match_key("World News") not in index.names
    assert "shared" not in index.display
    assert epg.match("Shared", "", {}, index) is None
    assert epg.assign([("World News (4K)", "", "")], {}, index) == [None]
    assert epg.match("Shared", "north.xx", {}, index) == "north.xx"
    assert epg.match("Sports Network", "", {}, index) == "sports.xx"


def test_invalid_update_preserves_valid_cached_guide(tmp_path, monkeypatch):
    import io

    url = "https://example.com/guide.xml"
    cached = _cache_file(tmp_path, url)
    previous = gzip.compress(EPG_XML)
    cached.write_bytes(previous)
    os.utime(cached, (time.time() - 86400,) * 2)
    monkeypatch.setattr(epg.urllib.request, "urlopen", lambda req, timeout: io.BytesIO(b"<bad"))
    index = epg.load_index(url, tmp_path, 1, 1, "test")
    assert index is not None and len(index) == 3
    assert cached.read_bytes() == previous


def test_empty_xmltv_update_preserves_valid_cached_guide(tmp_path, monkeypatch):
    import io

    url = "https://example.com/empty.xml"
    cached = _cache_file(tmp_path, url)
    previous = EPG_XML
    cached.write_bytes(previous)
    os.utime(cached, (time.time() - 86400,) * 2)
    monkeypatch.setattr(epg.urllib.request, "urlopen", lambda req, timeout: io.BytesIO(b"<tv></tv>"))
    index = epg.load_index(url, tmp_path, 1, 1, "test")
    assert index is not None and len(index) == 3
    assert cached.read_bytes() == previous
