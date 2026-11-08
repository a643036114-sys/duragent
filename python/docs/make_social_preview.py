# -*- coding: utf-8 -*-
"""生成 1280x640 的 GitHub Social preview（与 demo.gif 同一份真实输出）。"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import make_demo_gif as g
from PIL import Image, ImageDraw, ImageFont

W, H = 1280, 640
PAD = 40
LINE = 30
BG = (15, 17, 21)
BAR = (24, 27, 34)
DIM = (138, 146, 166)
BLUE = (88, 166, 255)

MONO = ImageFont.truetype("C:/Windows/Fonts/consola.ttf", 19)
MONO_BOLD = ImageFont.truetype("C:/Windows/Fonts/consolab.ttf", 19)

lines = g.capture()

image = Image.new("RGB", (W, H), BG)
draw = ImageDraw.Draw(image)
draw.rectangle([0, 0, W, 44], fill=BAR)
for index, colour in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
    draw.ellipse([20 + index * 26, 14, 36 + index * 26, 30], fill=colour)
draw.text((118, 12), "duragent — crash and resume", font=MONO, fill=DIM)

y = 44 + PAD
draw.text((PAD, y), "$ python examples/crash_demo.py", font=MONO_BOLD, fill=BLUE)
y += LINE + 8

for line in lines:
    colour, bold = g.style(line)
    draw.text((PAD, y), line, font=MONO_BOLD if bold else MONO, fill=colour)
    y += LINE

out = Path(__file__).resolve().parents[2] / "docs" / "social-preview.png"
image.save(out, optimize=True)
print("wrote", out, image.size)
