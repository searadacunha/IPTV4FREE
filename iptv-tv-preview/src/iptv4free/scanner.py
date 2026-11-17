"""Concurrent stream validation.

A stream counts as alive only if real media bytes come back: for HLS the master playlist,
one variant and the newest segment are fetched; DASH manifests and direct streams are
fetched directly. Media is recognised by its container bytes, so an HTML, JSON or text
error page answered with HTTP 200 is not mistaken for a stream. Headers are the ones Kodi
will send (see playlists.request_parts).
"""
from __future__ import annotations

import codecs
import re
import ssl
import urllib.parse
import urllib.request
import zlib
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Callable, Iterable

PLAYLIST_LIMIT = 512 * 1024
MEDIA_PROBE_BYTES = 64 * 1024
MIN_MEDIA_BYTES = 1024
EXTRA_VARIANTS = 2  # lower variants tried after the best one
EMPTY = "empty (server refuses this player)"
FMP4_BOXES = (b"ftyp", b"styp", b"moof", b"sidx", b"moov", b"emsg", b"prft")  # CMAF chunks may open with emsg/prft
VIDEO_CODECS = re.compile(r"\b(avc|hvc|hev|vp0?[89]|av01|mp4v|dvh|dva)", re.I)


@dataclass(frozen=True)
class Target:
    key: str  # effective URL (URL + Kodi header options), unique per stream
    url: str
    headers: tuple[tuple[str, str], ...]


@dataclass(frozen=True)
class Result:
    key: str
    ok: bool
    detail: str


class Unwatchable(Exception):
    """A failure whose message is already worded for reports."""


def _get(url: str, headers: dict[str, str], limit: int, timeout: float,
         ctx: ssl.SSLContext) -> tuple[str, bytes]:
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
        body = resp.read(limit)
        if resp.status == 204 or not body:  # RTP answers 204 to Kodi and ffmpeg agents
            raise Unwatchable(EMPTY)
        if body[:2] == b"\x1f\x8b":  # gzip sent with or without Content-Encoding
            body = zlib.decompressobj(wbits=31).decompress(body, limit)
        return resp.geturl(), body


def _uris(base: str, text: str) -> list[str]:
    """Media URIs of an HLS playlist."""
    return [urllib.parse.urljoin(base, ln.strip()) for ln in text.splitlines()
            if ln.strip() and not ln.strip().startswith("#")]


def _variants(base: str, text: str) -> list[tuple[bool, str]]:
    """(audio only, URI) of each HLS master variant, highest BANDWIDTH first (the one Kodi picks)."""
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]  # RFC 8216 ignores blank lines
    found = []
    for tag, uri in zip(lines, lines[1:]):
        if tag.startswith("#EXT-X-STREAM-INF") and uri and not uri.startswith("#"):
            bandwidth = re.search(r"[:,]BANDWIDTH=(\d+)", tag)
            codec_attr = re.search(r'CODECS="([^"]*)"', tag)
            audio = bool(codec_attr) and not VIDEO_CODECS.search(codec_attr.group(1))
            found.append((-int(bandwidth.group(1)) if bandwidth else 0, audio,
                          urllib.parse.urljoin(base, uri)))
    found.sort(key=lambda v: v[0])  # stable: equal bandwidths keep playlist order
    return [(audio, u) for _, audio, u in found]


def _is_text(head: bytes) -> bool:
    if sum(32 <= b < 127 or b in b"\t\r\n" for b in head) >= 0.9 * len(head):
        return True
    try:  # UTF-8 error pages with accents (geo-block pages in PT or FR); final=False allows a cut character
        text = codecs.getincrementaldecoder("utf-8")().decode(head)
    except UnicodeDecodeError:
        return False
    return sum(c.isprintable() or c in "\t\r\n" for c in text) >= 0.9 * len(text)


def sniff(data: bytes, encrypted: bool = False) -> tuple[bool, str]:
    """(is media, what it is), from the first bytes of a segment or direct stream."""
    if any(data[i] == 0x47 == data[i + 188] == data[i + 376] for i in range(min(188, len(data) - 376))):
        return True, "MPEG-TS"
    if data[4:8] in FMP4_BOXES:
        return True, "fMP4"
    if data[:3] == b"ID3":
        return True, "audio with ID3"
    if len(data) > 1 and data[0] == 0xFF and data[1] & 0xF6 == 0xF0:
        return True, "AAC (ADTS)"
    if data[:4] == b"\x00\x00\x01\xba":
        return True, "MPEG-PS"
    if data[:3] == b"FLV" or data[:4] in (b"\x1aE\xdf\xa3", b"OggS"):
        return True, "FLV, Matroska/WebM or Ogg"
    if len(data) > 1 and data[0] == 0xFF and data[1] & 0xE0 == 0xE0 and data[1] & 0x06:  # MPEG audio frame sync, layer set
        return True, "MPEG audio"
    head = data[:512]
    if not head:
        return False, EMPTY
    if _is_text(head):
        first = head.removeprefix(codecs.BOM_UTF8).lstrip()[:1]
        kind = "HTML page" if first == b"<" else "JSON" if first in (b"{", b"[") else "text"
        return False, f"{kind} instead of video"
    if encrypted and len(data) >= MIN_MEDIA_BYTES:  # AES-128 segments look like noise
        return True, "encrypted segment"
    return False, f"unknown data ({len(data)} bytes), not a video stream"


def _segment(base: str, text: str, headers: dict[str, str], timeout: float,
             ctx: ssl.SSLContext) -> tuple[bool, str]:
    segments = _uris(base, text)
    if not segments:
        return False, "HLS media playlist without segments"
    _, data = _get(segments[-1], headers, MEDIA_PROBE_BYTES, timeout, ctx)
    ok, what = sniff(data, "METHOD=AES-128" in text)
    return ok, f"HLS segment, {what}, {len(data)} bytes" if ok else f"HLS segment: {what}"


def _reason(e: Exception) -> str:
    return str(e) if isinstance(e, Unwatchable) else f"{type(e).__name__}: {str(e)[:120]}"


def _refused(e: Exception) -> bool:
    """403 or timeout: other variants on the same host would fail the same way."""
    return (getattr(e, "code", None) == 403 or isinstance(e, TimeoutError)
            or isinstance(getattr(e, "reason", None), TimeoutError))


def probe(target: Target, timeout: float, default_user_agent: str = "") -> Result:
    headers = dict(target.headers)
    if default_user_agent:
        headers.setdefault("User-Agent", default_user_agent)
    ctx = ssl.create_default_context()  # certificates verified, as Kodi does
    try:
        final, body = _get(target.url, headers, PLAYLIST_LIMIT, timeout, ctx)
        if not body.lstrip().startswith(b"#EXTM3U"):
            if b"<MPD" in body[:4096]:
                return Result(target.key, True, "DASH manifest")
            ok, what = sniff(body)
            return Result(target.key, ok, f"direct stream, {what}, {len(body)} bytes" if ok
                          else f"direct stream: {what}")
        text = body.decode("utf-8", "replace")
        if "#EXT-X-STREAM-INF" not in text:
            return Result(target.key, *_segment(final, text, headers, timeout, ctx))
        found = _variants(final, text)
        variants = [u for audio, u in found if not audio]  # sound without picture is not TV
        if not variants:
            what = "with only audio-only variants" if found else "without variants"
            return Result(target.key, False, f"HLS master playlist {what}")
    except Exception as e:  # every failure mode means "not watchable"; keep the reason
        return Result(target.key, False, _reason(e))

    failures: list[str] = []
    refused_hosts: set[str] = set()
    for n, url in enumerate(variants[:1 + EXTRA_VARIANTS], 1):
        host = urllib.parse.urlsplit(url).netloc
        if host in refused_hosts:
            continue
        try:
            vfinal, vbody = _get(url, headers, PLAYLIST_LIMIT, timeout, ctx)
            ok, detail = _segment(vfinal, vbody.decode("utf-8", "replace"), headers, timeout, ctx)
        except Exception as e:
            ok, detail = False, _reason(e)
            if _refused(e):
                refused_hosts.add(host)
        if ok:
            return Result(target.key, True,
                          detail + (f" (variant {n} of {len(variants)})" if n > 1 else ""))
        failures.append(detail)
    if len(failures) == 1:
        return Result(target.key, False, failures[0])
    return Result(target.key, False, f"{len(failures)} variants tried, best one: {failures[0]}")


def scan(targets: Iterable[Target], timeout: float, workers: int, default_user_agent: str = "",
         progress: Callable[[int, int], None] | None = None) -> list[Result]:
    """Probe every target concurrently. Results come back in input order."""
    targets = list(targets)
    results: list[Result] = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        it = pool.map(lambda t: probe(t, timeout, default_user_agent), targets)
        for done, result in enumerate(it, 1):
            results.append(result)
            if progress:
                progress(done, len(targets))
    return results
