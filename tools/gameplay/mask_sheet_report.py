#!/usr/bin/env python3
"""Measure the mask contact sheets the `masks-gallery` client role photographs (0.8.0, D5).

Looking at the sheets is the evidence that matters; this puts numbers beside it. For every style, in
every sheet, it finds the pixels that change between the sixteen dyed cells -- the dyed surface; the
background, the armour stand and the preserved details are the same in every cell and drop out -- and
compares the cells' average colours over exactly those pixels. Two dyes that landed on the same colour
would show up as a near-zero distance.

    python3 tools/gameplay/mask_sheet_report.py <client work dir> [--min 24]

It reads <work>/mask-sheet-layout.json and <work>/screenshots/*.png, prints one line per style and sheet
(the closest pair of colours and their distance in 8-bit RGB), and exits non-zero when any pair is
closer than --min. Author tool: the build never runs it.
"""
import argparse
import itertools
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

from PIL import Image


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('work', type=Path)
    parser.add_argument('--min', type=float, default=24.0, help='smallest acceptable distance between two dyes')
    args = parser.parse_args()
    cells = json.loads((args.work / 'mask-sheet-layout.json').read_text())
    rows = defaultdict(dict)
    for cell in cells:
        rows[(cell['shot'], cell['style'])][cell['colour']] = cell
    images = {}
    failures = 0
    for (shot, style), by_colour in sorted(rows.items()):
        image = images.setdefault(shot, Image.open(args.work / 'screenshots' / shot).convert('RGB'))
        dyed = {name: cell for name, cell in by_colour.items() if name != 'original'}
        w = min(cell['w'] for cell in dyed.values())
        h = min(cell['h'] for cell in dyed.values())
        crops = {name: image.crop((cell['x'] + 2, cell['y'] + 2, cell['x'] + w - 2, cell['y'] + h - 2)).load()
                 for name, cell in dyed.items()}
        names = list(crops)
        changing = [(x, y) for x in range(w - 4) for y in range(h - 4)
                    if len({crops[name][x, y] for name in names}) > 1]
        if not changing:
            print(f'{shot:30} {style:20} no pixel changes between dyes', flush=True)
            failures += 1
            continue
        means = {}
        for name in names:
            pixels = [crops[name][x, y] for x, y in changing]
            means[name] = tuple(sum(p[i] for p in pixels) / len(pixels) for i in range(3))
        closest = min(itertools.combinations(names, 2), key=lambda pair: math.dist(means[pair[0]], means[pair[1]]))
        distance = math.dist(means[closest[0]], means[closest[1]])
        flag = '' if distance >= args.min else '   <-- too close'
        failures += distance < args.min
        print(f'{shot:30} {style:20} {len(changing):5} dyed px  closest {closest[0]}/{closest[1]} = {distance:6.1f}{flag}')
    print(f'{len(rows)} style rows measured, {failures} below {args.min}')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
