"""Generates the hub's TV banner (320x180, xhdpi) and square icon in src/main/res/ from the official
IPTV4FREE logo, unmodified and scaled like the IPTV4FREE launcher tile (Benjamin, 2026-11-23: the hub
is the one IPTV4FREE app). Needs Pillow, build time only."""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "iptv-tv-preview" / "scripts"))
from prepare_android_launcher import make_fit, visible_logo  # noqa: E402
from PIL import Image  # noqa: E402

RES = ROOT / "src" / "main" / "res"


def save(img, rel):
    p = RES / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    img.save(p, optimize=True)
    print(p.relative_to(ROOT), img.size)


if __name__ == "__main__":
    logo = visible_logo()
    banner = Image.new("RGBA", (320, 180), (5, 8, 23, 255))  # same backdrop as the launcher tile
    art, point = make_fit(logo, (320, 180), 0.91)
    banner.alpha_composite(art, point)
    save(banner.convert("RGB"), "drawable-xhdpi/banner.png")
    master = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    art, point = make_fit(logo, (512, 512), 0.88)
    master.alpha_composite(art, point)
    for dpi, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        save(master.resize((px, px), Image.LANCZOS), f"mipmap-{dpi}/ic_launcher.png")
    # the menu's logo and the start screen's (1080 x 720 px on the 1080p UI, as the Kodi interface's loading view)
    art, _ = make_fit(logo, (1080, 720), 1.0)
    save(art, "drawable-nodpi/iptv4free_logo.png")
