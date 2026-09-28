"""Draws the restraint art MCA: Crime authors itself, so the pixels are readable rather than merely present.

Every file this script writes is bucket (c) in `docs/0.7.5/PROVENANCE.md`: original MCA: Crime
work, under the same registry ids, with no upstream pixel copied into it. That distinction is
the reason this file exists at all. The bucket (b) art beside it is adapted from Cuffed with
attribution and is simply committed; the art here has to be demonstrably ours, and a committed
PNG cannot demonstrate anything. The script is the demonstration: every colour below is either
sampled from MCA: Crime's own protected cuff icons or stated outright.

What it writes:

  assets/mcacrime/textures/entity/restraint/handcuffs.png  worn, arms and legs (32x32)
  assets/mcacrime/textures/entity/restraint/shackles.png   worn, arms and legs (32x32)

What it deliberately does not touch:

  assets/mcacrime/textures/item/restraint_cuffs.png        bucket (a), protected
  assets/mcacrime/textures/item/restraint_locked_cuffs.png bucket (a), protected

Those two are guarded by `ProtectedTextureHashTest` and are only ever *read* here, to sample the
steel palette so the worn art matches the icons a player already knows.

Run by hand, like `tools/gui/generate_gui_sheet.py`. The build must stay
`git clone && ./gradlew build` on a machine with no Python, so the PNGs are committed art:

    python3 tools/art/generate_restraint_art.py            # rewrite the textures
    python3 tools/art/generate_restraint_art.py --check    # prove the committed files match

The UV layout is not arbitrary. Minecraft unwraps a w x h x d box placed at texOffs(u, v) as

    top    (u + d,         v)         w x d
    bottom (u + d + w,     v)         w x d
    east   (u,             v + d)     d x h
    north  (u + d,         v + d)     w x h
    west   (u + d + w,     v + d)     d x h
    south  (u + d + w + d, v + d)     w x h

so a 5 x 2 x 5 cuff band occupies 2*(5+5) = 20 across and 5 + 2 = 7 down from its offset. The
two regions below are exactly the two boxes `client/render/restraint/` builds: a band at (0, 0)
and a flat chain strip at (0, 10). Changing a model's texOffs without changing this file is how
a worn restraint ends up textured with whatever happens to be next to it.
"""

import argparse
import sys
from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parents[2]
ITEM_DIR = REPO / "src/main/resources/assets/mcacrime/textures/item"
ENTITY_DIR = REPO / "src/main/resources/assets/mcacrime/textures/entity/restraint"

# The steel palette, sampled from restraint_cuffs.png and restraint_locked_cuffs.png. Reading
# those two files is the whole point: the worn cuffs have to look like the icon in the hand that
# applied them, and re-inventing a grey would make them look like a different mod's restraint.
STEEL_DARK = (42, 42, 47, 255)
STEEL = (69, 69, 73, 255)
STEEL_LIT = (98, 98, 104, 255)
STEEL_HIGHLIGHT = (126, 126, 134, 255)
BRASS = (207, 116, 8, 255)
BRASS_LIT = (229, 178, 46, 255)

CLEAR = (0, 0, 0, 0)

# Where each box lands, mirroring client/render/restraint/RestraintModels.
BAND = (0, 0, 20, 7)      # a 5 x 2 x 5 cuff band
CHAIN = (0, 10, 10, 2)    # a 5 x 2 x 0 flat chain strip


def _fill(image, box, colour):
    x, y, w, h = box
    for px in range(x, x + w):
        for py in range(y, y + h):
            image.putpixel((px, py), colour)


def _band(image, base, lit, highlight):
    """A cuff band: a lit top edge, a body, and a dark bottom edge.

    Three rows rather than a flat fill because the band wraps a limb and a flat one reads as a
    painted stripe. The lit row is the top because Minecraft's fixed light comes from above.
    """
    x, y, w, h = BAND
    _fill(image, BAND, base)
    for px in range(x, x + w):
        image.putpixel((px, y), lit)
        image.putpixel((px, y + h - 1), STEEL_DARK)
    # Two rivets per face width, at the quarter points, so the band has a direction.
    for px in range(x + 2, x + w, 5):
        image.putpixel((px, y + h // 2), highlight)


def _chain(image, base, lit):
    """The link strip between two cuffs: alternating lit and dark pixels, which reads as links."""
    x, y, w, h = CHAIN
    _fill(image, CHAIN, base)
    for px in range(x, x + w):
        image.putpixel((px, y), lit if px % 2 == 0 else STEEL_DARK)
        image.putpixel((px, y + 1), STEEL_DARK if px % 2 == 0 else lit)


def worn_handcuffs():
    """Strong steel: a dark band, brass rivets, a heavy chain."""
    image = Image.new("RGBA", (32, 32), CLEAR)
    _band(image, STEEL, STEEL_LIT, BRASS_LIT)
    _chain(image, STEEL, STEEL_HIGHLIGHT)
    return image


def worn_shackles():
    """Lighter metal: a paler band, no brass, a thinner chain. Visibly the weaker restraint."""
    image = Image.new("RGBA", (32, 32), CLEAR)
    _band(image, STEEL_LIT, STEEL_HIGHLIGHT, STEEL_HIGHLIGHT)
    _chain(image, STEEL_LIT, STEEL_HIGHLIGHT)
    return image


TARGETS = [
    (lambda: ENTITY_DIR / "handcuffs.png", worn_handcuffs),
    (lambda: ENTITY_DIR / "shackles.png", worn_shackles),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="verify the committed PNGs match this script instead of rewriting them")
    args = parser.parse_args()

    ENTITY_DIR.mkdir(parents=True, exist_ok=True)
    failures = []
    for path_of, draw in TARGETS:
        path = path_of()
        image = draw()
        if args.check:
            if not path.exists():
                failures.append(f"missing: {path.relative_to(REPO)}")
                continue
            committed = Image.open(path).convert("RGBA")
            if committed.tobytes() != image.tobytes() or committed.size != image.size:
                failures.append(f"differs: {path.relative_to(REPO)}")
        else:
            image.save(path)
            print(f"wrote {path.relative_to(REPO)}")

    if args.check:
        if failures:
            for failure in failures:
                print(failure, file=sys.stderr)
            return 1
        print("every authored restraint texture matches this script")
    return 0


if __name__ == "__main__":
    sys.exit(main())
