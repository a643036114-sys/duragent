"""Render docs/demo.gif from a real run of examples/crash_demo.py.

The GIF is not hand-written: it captures the actual output, so it cannot drift
away from what the code does.

    cd python && python docs/make_demo_gif.py
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import textwrap
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
DEMO = ROOT / "examples" / "crash_demo.py"
OUT_GIF = ROOT.parent / "docs" / "demo.gif"
OUT_PNG = ROOT.parent / "docs" / "demo-preview.png"

WIDTH, HEIGHT = 940, 520
PAD = 24
LINE = 26
WRAP = 88
BG = (15, 17, 21)
BAR = (24, 27, 34)
FG = (226, 229, 236)
DIM = (138, 146, 166)
GREEN = (86, 211, 100)
RED = (248, 81, 73)
BLUE = (88, 166, 255)

MONO = ImageFont.truetype("C:/Windows/Fonts/consola.ttf", 17)
MONO_BOLD = ImageFont.truetype("C:/Windows/Fonts/consolab.ttf", 17)

JOURNAL_ROW = re.compile(r"^\s{2}\S+\s+(SUCCEEDED|FAILED|RUNNING)\s+\d+\s+(\d+)$")


def capture() -> list[str]:
    env = dict(os.environ, PYTHONPATH=str(ROOT / "src"), PYTHONIOENCODING="utf-8")
    result = subprocess.run([sys.executable, str(DEMO)], capture_output=True,
                            text=True, encoding="utf-8", env=env, timeout=120)
    if result.returncode != 0:
        raise SystemExit(f"demo failed:\n{result.stderr}")
    lines: list[str] = []
    for line in result.stdout.splitlines():
        if not line.strip():
            continue
        if len(line) <= WRAP:
            lines.append(line)
            continue
        lines.extend(textwrap.wrap(line, width=WRAP, subsequent_indent="      ",
                                   break_long_words=True, break_on_hyphens=False))
    return lines


def style(line: str) -> tuple[tuple[int, int, int], bool]:
    if line.startswith("✗") or " crashed at " in line:
        return RED, True
    if line.lstrip().startswith(("──", "run id", "db", "-- ")):
        return DIM, False
    match = JOURNAL_ROW.match(line)
    if match:
        # One execution means the step was replayed, not redone: that is the point.
        return (GREEN, False) if match.group(2) == "1" else (FG, False)
    return FG, False


def render(lines: list[str]) -> Image.Image:
    image = Image.new("RGB", (WIDTH, HEIGHT), BG)
    draw = ImageDraw.Draw(image)

    draw.rectangle([0, 0, WIDTH, 34], fill=BAR)
    for index, colour in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
        draw.ellipse([16 + index * 22, 11, 28 + index * 22, 23], fill=colour)
    draw.text((100, 9), "duragent — crash and resume", font=MONO, fill=DIM)

    y = 34 + PAD
    draw.text((PAD, y), "$ python examples/crash_demo.py", font=MONO_BOLD, fill=BLUE)
    y += LINE + 4

    for line in lines:
        colour, bold = style(line)
        draw.text((PAD, y), line, font=MONO_BOLD if bold else MONO, fill=colour)
        y += LINE
    return image


def build() -> None:
    lines = capture()
    frames: list[Image.Image] = []
    durations: list[int] = []

    for end in range(2, len(lines) + 2, 2):
        frames.append(render(lines[:end]))
        durations.append(300)
    for _ in range(3):
        frames.append(render(lines))
        durations.append(1000)

    frames[0].save(OUT_GIF, save_all=True, append_images=frames[1:],
                   duration=durations, loop=0, optimize=True)
    frames[-1].save(OUT_PNG)
    print(f"wrote {OUT_GIF} ({len(frames)} frames) and {OUT_PNG}")


if __name__ == "__main__":
    build()
