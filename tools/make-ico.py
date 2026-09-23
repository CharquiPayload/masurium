#!/usr/bin/env python3
"""launcher/gui/appicon/masurium-launcher.ico, the icon Windows shows for the
Start menu entry, made from the PNGs beside it: an .ico is a small directory
followed by the images, and since Windows Vista each one may simply be a PNG.

Run it when the icon changes:  python3 tools/make-ico.py
"""
import pathlib
import struct

HERE = pathlib.Path(__file__).resolve().parent.parent / "launcher" / "gui" / "appicon"
# The sizes Windows asks an icon for; 256 is the largest an .ico holds.
SIZES = (32, 48, 64, 128, 256)


def ico(images):
    """The bytes of an .ico holding these (size, png bytes)."""
    head = struct.pack("<HHH", 0, 1, len(images))
    offset = len(head) + 16 * len(images)
    entries, data = b"", b""
    for size, png in images:
        # A width or height of 256 is written as 0.
        entries += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(png), offset + len(data))
        data += png
    return head + entries + data


def main():
    images = [(s, (HERE / f"masurium-launcher-{s}.png").read_bytes()) for s in SIZES]
    out = HERE / "masurium-launcher.ico"
    out.write_bytes(ico(images))
    print(f"{out}: {out.stat().st_size} bytes, sizes {', '.join(map(str, SIZES))}")


if __name__ == "__main__":
    main()
