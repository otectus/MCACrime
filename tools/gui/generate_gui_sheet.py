"""Draws the mod's GUI sprite sheet, so the sheet is readable rather than merely present.

A 256x256 PNG is an opaque blob in a diff. Committing only the image means the next person
to touch the GUI cannot tell which pixels are load-bearing -- whether a #555555 row is a
deliberate bevel or a mistake, whether the middle of a sprite may be edited freely or is
tiled by blitNineSliced and must stay flat. This script is that answer, in the same voice as
the Javadoc on every class in this mod: it says why each pixel is the colour it is.

The sheet is committed alongside it. The build must stay `git clone && ./gradlew build` with
no Python on the machine, and this project deliberately runs no data generation, so the PNG
is checked-in art and this file is an author tool run by hand:

    python tools/gui/generate_gui_sheet.py            # rewrite the sheet
    python tools/gui/generate_gui_sheet.py --check    # prove the committed sheet matches

Two constraints shape everything below.

The first is why the sheet is 256x256 and not tightly packed. GuiGraphics.blitNineSliced in
1.20.1 takes no texture size: all three overloads funnel into calls that hardcode 256, 256.
Anything nine-sliced therefore has to live on a 256x256 image, and since the panel, the well,
the row states, the scroll thumb, the progress bar and the HUD plate all want nine-slicing,
they all live here. Empty space in the sheet costs nothing a second sheet would not cost
twice.

The second is why the middle of every sprite is one flat colour. blitNineSliced tiles the
centre and the edge strips with blitRepeating, which crops the final repeat -- so a gradient
or a pattern inside a repeated region produces a seam at whatever width the caller happens to
ask for. Every sprite here keeps its detail in the fixed corners and border, and its centre
uniform. The one apparent exception, BAR_FILL, has a vertical three-tone gradient and is safe
because it is only ever drawn at exactly its own height, which takes the horizontal-only
slicing branch and never tiles vertically.

The palette is vanilla's own container palette, sampled from
assets/minecraft/textures/gui/container/generic_54.png, so the screens read as part of the
game rather than as a mod's idea of what the game looks like.
"""

import argparse
import io
import sys
from pathlib import Path

from PIL import Image

SHEET = 256

# The sprite map, duplicated from CrimeSprites.java on purpose.
#
# This table draws nothing. It exists so verify() can prove the two properties that cannot be
# seen by looking at the image: that no two sprites overlap, and that every region a nine
# slice will tile is uniform. The first of those is not hypothetical -- the scrollbar and the
# progress bar overlapped in the first draft of this file, and the overlap was invisible
# until it was checked, because a scroll track drawn over a bar track still looks like a
# scroll track.
#
# kind is how the sprite is drawn, which is what decides the constraint:
#   "slice9"  -- centre and both edge strips are tiled; centre must be flat
#   "repeatY" -- tiled vertically; every row must be identical
#   "slice9x" -- tiled horizontally only; every column must be identical
#   "blit"    -- drawn whole at its own size; no constraint
SPRITES = [
    ("PANEL",          0,   0, 32, 32, "slice9",  4, 4),
    ("WELL",          32,   0, 32, 32, "slice9",  3, 3),
    ("HUD_PLATE",     64,   0, 32, 32, "slice9",  3, 3),
    ("ROW_IDLE",       0,  32, 32, 20, "slice9",  3, 2),
    ("ROW_HOVER",      0,  52, 32, 20, "slice9",  3, 2),
    ("ROW_DISABLED",   0,  72, 32, 20, "slice9",  3, 2),
    ("ROW_FOCUS",      0,  92, 32, 20, "slice9",  3, 2),
    ("SCROLL_TRACK",   0, 112,  6, 32, "repeatY", 0, 0),
    ("SCROLL_THUMB",   8, 112,  6, 32, "slice9",  1, 1),
    ("CHIP_FILL",     16, 112,  8,  8, "blit",    0, 0),
    ("CHIP_FRAME",    24, 112,  8,  8, "blit",    0, 0),
    ("BAR_TRACK",      0, 144, 32,  8, "slice9",  1, 1),
    ("BAR_FILL",       0, 152, 32,  6, "slice9x", 1, 0),
    ("CARD_BTN_IDLE", 32, 144, 12, 12, "blit",    0, 0),
    ("CARD_BTN_HOVER", 32, 156, 12, 12, "blit",   0, 0),
    ("CARD_BTN_OPEN", 32, 168, 12, 12, "blit",    0, 0),
]

# Vanilla's container palette, sampled from generic_54.png.
BLACK = (0, 0, 0, 255)
FACE = (198, 198, 198, 255)          # #C6C6C6 panel body
HILITE = (255, 255, 255, 255)        # top/left bevel
SHADOW = (85, 85, 85, 255)           # #555555 bottom/right bevel
WELL = (139, 139, 139, 255)          # #8B8B8B slot interior
WELL_DARK = (55, 55, 55, 255)        # #373737 slot top/left, i.e. sunken rather than raised

# Row states. The hover face is 0x80FFFFFF composited over FACE, which is what the old
# fill-based hover actually looked like once it had blended -- the sprite is not a new
# colour, it is the old one made opaque.
FACE_HOVER = (226, 226, 226, 255)
SHADOW_HOVER = (110, 110, 110, 255)
FACE_DISABLED = (173, 173, 173, 255)
HILITE_DISABLED = (198, 198, 198, 255)
SHADOW_DISABLED = (74, 74, 74, 255)
FACE_PRESSED = (176, 176, 176, 255)

# Scrollbar. These two are vanilla's own values: AbstractSelectionList draws its thumb with
# fill(-4144960) and fill(-8355712). We overdraw that bar with this sprite, so matching the
# colours means the seam between them is invisible if the overdraw is ever off by a pixel.
TRACK_EDGE = (0, 0, 0, 255)
TRACK_BODY = (16, 16, 16, 255)
THUMB_FACE = (192, 192, 192, 255)
THUMB_SHADOW = (128, 128, 128, 255)

# Progress bar.
BAR_EMPTY = (58, 58, 58, 255)
BAR_HI = (224, 106, 106, 255)
BAR_MID = (204, 85, 85, 255)         # the old flat 0xFFCC5555, now the middle of a bevel
BAR_LO = (160, 60, 60, 255)

# HUD plate. Not grey: a #C6C6C6 slab floating over the world is not what vanilla does with
# information overlaid on gameplay. This keeps the translucent black of the boss bar and the
# subtitle overlay, and bakes in the exact alpha the fill-based version used.
HUD_EDGE = (0, 0, 0, 255)
HUD_LIP = (255, 255, 255, 24)
HUD_BODY = (0, 0, 0, 144)

TRANSPARENT = (0, 0, 0, 0)


def rect(img, x, y, w, h, colour):
    """Fills a rectangle. Inclusive of x, y; exclusive of x + w, y + h."""
    for py in range(y, y + h):
        for px in range(x, x + w):
            img.putpixel((px, py), colour)


def plate(img, x, y, w, h, face, hi, lo, outline=None):
    """Draws a vanilla bevelled plate: flat face, lit from the top left.

    With an outline the sprite gains a one-pixel black ring first and the bevel moves inside
    it, which is what a panel looks like; without one the bevel is the outermost ring, which
    is what a slot or a row looks like.

    The two corner pixels opposite the light are left as face rather than bevelled. That
    mitre is vanilla's, and skipping it is the single most obvious tell that a texture was
    drawn by someone matching the palette but not the shape.
    """
    if outline is not None:
        rect(img, x, y, w, h, outline)
        x, y, w, h = x + 1, y + 1, w - 2, h - 2

    rect(img, x, y, w, h, face)
    rect(img, x, y, w, 1, hi)               # top
    rect(img, x, y, 1, h, hi)               # left
    rect(img, x, y + h - 1, w, 1, lo)       # bottom
    rect(img, x + w - 1, y, 1, h, lo)       # right
    img.putpixel((x + w - 1, y), face)
    img.putpixel((x, y + h - 1), face)


def draw_sheet():
    img = Image.new("RGBA", (SHEET, SHEET), TRANSPARENT)

    # PANEL (0, 0, 32, 32) -- nine-sliced with border 4. The repeated centre is source
    # rows and columns 4..27, which the plate leaves flat.
    plate(img, 0, 0, 32, 32, FACE, HILITE, SHADOW, outline=BLACK)

    # WELL (32, 0, 32, 32) -- border 3. Bevel inverted against the panel so the list area
    # reads as sunken into it, the same relationship a slot has to an inventory.
    plate(img, 32, 0, 32, 32, WELL, WELL_DARK, HILITE)

    # HUD_PLATE (64, 0, 32, 32) -- border 3. Alpha is baked in, so every draw of this needs
    # blending enabled; CrimeSprites does that rather than trusting the caller's state.
    rect(img, 64, 0, 32, 32, HUD_EDGE)
    rect(img, 65, 1, 30, 30, HUD_LIP)
    rect(img, 66, 2, 28, 28, HUD_BODY)

    # ROW_* (0, 32 + i * 20, 32, 20) -- border 3, 2. The stride of 20 deliberately mirrors
    # vanilla's own 46 + i * 20 button strip in AbstractButton.getTextureY(), so anyone who
    # has read that code already knows how to read this.
    plate(img, 0, 32, 32, 20, FACE, HILITE, SHADOW)
    plate(img, 0, 52, 32, 20, FACE_HOVER, HILITE, SHADOW_HOVER)
    plate(img, 0, 72, 32, 20, FACE_DISABLED, HILITE_DISABLED, SHADOW_DISABLED)

    # ROW_FOCUS -- white outer ring over a black inner ring. That is exactly what
    # AbstractSelectionList.renderSelection would have drawn for a selected entry, which is
    # why keyboard focus here looks like keyboard focus everywhere else in the game, and why
    # the vertical border is 2 rather than 1: it has to cover both rings.
    rect(img, 0, 92, 32, 20, HILITE)
    rect(img, 1, 93, 30, 18, BLACK)
    rect(img, 2, 94, 28, 16, FACE)

    # SCROLL_TRACK (0, 112, 6, 32) -- tiled vertically with blitRepeating, so every row is
    # identical and no seam can appear at any track length.
    rect(img, 0, 112, 6, 32, TRACK_BODY)
    rect(img, 0, 112, 1, 32, TRACK_EDGE)
    rect(img, 5, 112, 1, 32, TRACK_EDGE)

    # SCROLL_THUMB (8, 112, 6, 32) -- border 1. Reproduces vanilla's two-fill thumb: a light
    # face with a shadow along the bottom and right.
    rect(img, 8, 112, 6, 32, THUMB_FACE)
    rect(img, 13, 112, 1, 32, THUMB_SHADOW)
    rect(img, 8, 143, 6, 1, THUMB_SHADOW)

    # CHIP_FILL (16, 112, 8, 8) -- solid white, tinted at draw time to carry a legality,
    # band or resolution colour. White so the tint is the colour, undiluted.
    rect(img, 16, 112, 8, 8, HILITE)

    # CHIP_FRAME (24, 112, 8, 8) -- drawn untinted over the fill. The black ring is what
    # lets a saturated colour sit on a light grey panel and still read as a marker; the
    # translucent inner bevel keeps it from looking like a flat sticker.
    rect(img, 24, 112, 8, 8, BLACK)
    rect(img, 25, 113, 6, 6, TRANSPARENT)
    rect(img, 25, 113, 6, 1, (255, 255, 255, 64))
    rect(img, 25, 113, 1, 6, (255, 255, 255, 64))
    rect(img, 25, 118, 6, 1, (0, 0, 0, 64))
    rect(img, 30, 113, 1, 6, (0, 0, 0, 64))
    rect(img, 26, 114, 4, 4, TRANSPARENT)

    # BAR_TRACK (0, 144, 32, 8) -- border 1. Below the scrollbar strip, not beside it: the
    # scroll sprites are 32 tall and run to row 143.
    rect(img, 0, 144, 32, 8, BLACK)
    rect(img, 1, 145, 30, 6, BAR_EMPTY)

    # BAR_FILL (0, 152, 32, 6) -- border 1, 0. The vertical gradient is safe only because
    # this is always drawn at exactly six pixels tall, which takes blitNineSliced's
    # horizontal-only branch. Drawing it at any other height would tile the gradient and
    # band the bar.
    rect(img, 0, 152, 32, 1, BAR_HI)
    rect(img, 0, 153, 32, 3, BAR_MID)
    rect(img, 0, 156, 32, 2, BAR_LO)

    # CARD_BTN_* (32, 144 + i * 12, 12, 12). The open state inverts the bevel so the button
    # reads as pressed in while the card is showing -- state the player can see without
    # having to compare it to anything.
    plate(img, 32, 144, 12, 12, FACE, HILITE, SHADOW, outline=BLACK)
    plate(img, 32, 156, 12, 12, FACE_HOVER, HILITE, SHADOW_HOVER, outline=BLACK)
    plate(img, 32, 168, 12, 12, FACE_PRESSED, SHADOW, HILITE, outline=BLACK)

    return img


def verify(img):
    """Returns the list of problems with the sheet, empty when it is sound.

    Runs on every generate as well as on --check, because both failures it looks for are
    silent: an overlap draws something plausible over something else, and a non-flat tiled
    region only seams at particular widths, which may not be any of the widths tried by hand.
    """
    problems = []

    owner = {}
    for name, u, v, w, h, _kind, _bx, _by in SPRITES:
        for y in range(v, v + h):
            for x in range(u, u + w):
                if (x, y) in owner:
                    problems.append(f"{name} overlaps {owner[(x, y)]} at ({x}, {y})")
                    break
                owner[(x, y)] = name
            else:
                continue
            break

    for name, u, v, w, h, kind, bx, by in SPRITES:
        if kind == "slice9":
            region = (u + bx, v + by, w - 2 * bx, h - 2 * by)
            if len({img.getpixel((x, y))
                    for y in range(region[1], region[1] + region[3])
                    for x in range(region[0], region[0] + region[2])}) != 1:
                problems.append(f"{name} has a non-flat tiled centre")
        elif kind == "repeatY":
            if len({tuple(img.getpixel((x, y)) for x in range(u, u + w))
                    for y in range(v, v + h)}) != 1:
                problems.append(f"{name} rows are not identical; vertical tiling will seam")
        elif kind == "slice9x":
            if len({tuple(img.getpixel((x, y)) for y in range(v, v + h))
                    for x in range(u + bx, u + w - bx)}) != 1:
                problems.append(f"{name} columns are not identical; tiling will seam")

    return problems


def encode(img):
    """Serialises deterministically, so --check compares like with like.

    Pillow writes no timestamp and this script has no randomness, so re-running produces a
    byte-identical file. That is the property --check depends on.
    """
    buffer = io.BytesIO()
    img.save(buffer, "PNG", optimize=False, compress_level=9)
    return buffer.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help="compare against the committed sheet instead of rewriting it")
    args = parser.parse_args()

    target = (Path(__file__).resolve().parents[2]
              / "src" / "main" / "resources" / "assets" / "mcacrime"
              / "textures" / "gui" / "panel.png")

    sheet = draw_sheet()
    problems = verify(sheet)
    if problems:
        for problem in problems:
            print(f"unsound sheet: {problem}", file=sys.stderr)
        return 1
    data = encode(sheet)

    if args.check:
        if not target.exists():
            print(f"missing: {target}", file=sys.stderr)
            return 1
        if target.read_bytes() != data:
            print(f"stale: {target} does not match this script", file=sys.stderr)
            return 1
        print(f"up to date: {target}")
        return 0

    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    print(f"wrote {target} ({len(data)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
