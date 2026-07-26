"""Render a few refreshed vector-drawable icons to a PNG sheet for visual review.

Offline-only helper: converts the subset of AndroidVectorDrawable this project's
icons use (paths + one linear aapt:attr gradient) into SVG, then rasterises with
svglib/reportlab. Not part of the build; just so the icon refresh can be eyeballed
without the watch.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from io import BytesIO
from pathlib import Path

from reportlab.graphics import renderPM
from svglib.svglib import svg2rlg

ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"


def vector_to_svg(path: Path) -> str:
    tree = ET.parse(path)
    root = tree.getroot()
    vw = root.get(f"{ANDROID}viewportWidth", "160")
    vh = root.get(f"{ANDROID}viewportHeight", "160")
    defs: list[str] = []
    body: list[str] = []
    grad_id = 0

    def emit_path(el: ET.Element, clip: str | None) -> None:
        nonlocal grad_id
        d = el.get(f"{ANDROID}pathData", "")
        if not d:
            return
        fill = el.get(f"{ANDROID}fillColor")
        alpha = el.get(f"{ANDROID}fillAlpha")
        grad_el = el.find(f"{AAPT}attr")
        fill_ref = fill or "#000000"
        if grad_el is not None:
            g = grad_el.find(f"{ANDROID}gradient") or grad_el.find("gradient")
            if g is not None:
                grad_id += 1
                gid = f"g{grad_id}"
                x1 = g.get(f"{ANDROID}startX", "0")
                y1 = g.get(f"{ANDROID}startY", "0")
                x2 = g.get(f"{ANDROID}endX", vw)
                y2 = g.get(f"{ANDROID}endY", vh)
                c1 = g.get(f"{ANDROID}startColor", "#000000")
                c2 = g.get(f"{ANDROID}endColor", "#000000")
                defs.append(
                    f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
                    f'x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">'
                    f'<stop offset="0" stop-color="{c1}"/>'
                    f'<stop offset="1" stop-color="{c2}"/></linearGradient>'
                )
                fill_ref = f"url(#{gid})"
        opacity = f' fill-opacity="{alpha}"' if alpha else ""
        clip_attr = f' clip-path="url(#{clip})"' if clip else ""
        body.append(f'<path d="{d}" fill="{fill_ref}"{opacity}{clip_attr}/>')

    for child in root:
        tag = child.tag.split("}")[-1]
        if tag == "path":
            emit_path(child, None)
        elif tag == "group":
            body.append("<g>")
            for gc in child:
                if gc.tag.split("}")[-1] == "path":
                    emit_path(gc, None)
            body.append("</g>")

    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{vw}" height="{vh}" '
        f'viewBox="0 0 {vw} {vh}"><defs>{"".join(defs)}</defs>'
        f'{"".join(body)}</svg>'
    )


def main() -> None:
    icon_dir = Path("work/suixin-apktool/res/drawable-anydpi-v24")
    names = sys.argv[1:] or [
        "ic_default_scan_music",
        "ic_default_interface",
        "ic_default_theme",
        "ic_default_transfer",
        "ic_default_lyric",
        "ic_default_player",
        "ic_default_help",
        "ic_default_about",
    ]
    out_dir = Path("artifacts/icons")
    out_dir.mkdir(parents=True, exist_ok=True)
    cells = []
    for name in names:
        svg = vector_to_svg(icon_dir / f"{name}.xml")
        drawing = svg2rlg(BytesIO(svg.encode("utf-8")))
        png = out_dir / f"{name}.png"
        renderPM.drawToFile(drawing, str(png), fmt="PNG", bg=0x101014)
        cells.append(png)
        print(name, "->", png)


if __name__ == "__main__":
    main()
