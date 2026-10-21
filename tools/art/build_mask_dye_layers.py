#!/usr/bin/env python3
"""Draws the dyeable mask art, so a dyed mask shows its dye on every one of the sixteen faces.

Why this file exists (0.8.0, workstream D4). Until 0.8.0 a dyed mask was its original texture
multiplied by the dye, on the face and in the inventory. Multiplying can only darken, so it only
works on art that is already near white. Four styles are near-black (the Highwayman's Domino, the
Balaclava, the Jackal Mask, the Wrapped Scarf) and four are strongly coloured (the red Bandana, the
purple Half Veil, the two brown leather faces); on those, most of the sixteen dyes landed on the same
mud. On the pale styles the dye also flooded the parts that are not paint at all: the Plague Doctor's
blue lenses, the Hockey Mask's red marks and black straps, the Owl's yellow eyes.

A dyed mask therefore draws different art from an undyed one, in two layers:

  <style>_dyed.png         the dyeable surface, redrawn in neutral greys. Every pixel keeps its place
                           in the original's light-to-dark order, so shading, seams and silhouette
                           survive; only the hue is gone, and the dye supplies it.
  <style>_dyed_detail.png  what must not take dye -- eyes, lenses, straps, trim, prints -- copied
                           unchanged from the original and transparent everywhere else.

Both exist for the item (textures/item, drawn by models/item/<style>_dyed.json, which the style's
own model selects through the mcacrime:dyed predicate) and for the worn model
(textures/models/armor, drawn by MaskArmorRenderer and its detail layer). Every style ships all four
files, including the five whose detail layer is empty, so the models and the renderer have no
per-style exceptions and a resource pack can paint details onto any of them.

The originals are only read here, never rewritten: an undyed mask keeps drawing exactly the art it
always drew, through the model it always used.

How the greys are chosen. The dyeable pixels' luminance is mapped piecewise-linearly so that their
darkest lands on LO, their median on MID and their brightest on HI -- and never darker than the pixel
already was, so art that is already a pale neutral (the clay faces, the owl's enamel) is left nearly
alone while dark art is lifted until a dye can show on it. A texture whose median is also its darkest
value (black is the cloth, not an outline) puts that black on MID. Each texture is mapped over its
own range, so an icon and its worn face land on the same greys whatever their palettes.

What the build needs from this file: nothing. The build must stay `git clone && ./gradlew build` on a
machine with no Python, so the PNGs and models are committed art and this is an author tool:

    python3 tools/art/build_mask_dye_layers.py            # rewrite the dyed textures and models
    python3 tools/art/build_mask_dye_layers.py --check    # prove the committed files match
    python3 tools/art/build_mask_dye_layers.py --preview sheet.png   # all styles under all 16 dyes

The preview is a design aid composited here, not a rendering. The game-rendered contact sheets under
docs/0.8.0/ are the evidence that each colour is visible on each style.
"""

import argparse
import colorsys
import json
import statistics
import sys
from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parents[2]
ASSETS = REPO / "src/main/resources/assets/mcacrime"
ITEM_TEXTURES = ASSETS / "textures/item"
ARMOR_TEXTURES = ASSETS / "textures/models/armor"
ITEM_MODELS = ASSETS / "models/item"

PREDICATE = "mcacrime:dyed"

# (LO, MID, HI) per texture kind. An icon keeps a darker floor than a worn face: its darkest
# dyeable pixels are usually the outline that separates it from the slot behind it.
TONES = {"item": (60, 190, 250), "armor": (90, 190, 250)}


def _lum(p):
    return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]


def _hsv(p):
    h, s, v = colorsys.rgb_to_hsv(p[0] / 255, p[1] / 255, p[2] / 255)
    return h * 360, s, v


def _nothing(p, x, y, kind):
    return False


def _bandana(p, x, y, kind):
    # The white polka dots; the red cloth is the dyeable part.
    _, s, v = _hsv(p)
    return s < 0.28 and v > 0.55


def _raven(p, x, y, kind):
    # The blue lenses, the black eye slits and beak line, and the brown leather straps. The
    # tan-and-cream beak is the dyeable leather (its hue sits above 30, the straps' below).
    h, s, _ = _hsv(p)
    return (195 <= h <= 235 and s > 0.25) or _lum(p) < 20 or (h < 30 and s > 0.5)


def _jackal(p, x, y, kind):
    # The gold trim around the worn head; the black-and-purple head is dyed.
    h, s, _ = _hsv(p)
    return 40 <= h <= 60 and s > 0.45


def _hockey(p, x, y, kind):
    # The red marks on the face, and on the worn texture everything above the face: the black
    # straps and their blue-grey buckles.
    h, s, _ = _hsv(p)
    return (s > 0.35 and (h < 20 or h > 340)) or (kind == "armor" and y <= 10)


def _stitched(p, x, y, kind):
    # The bright orange thread: the stitches and the cross-stitched patch on top. The leather between
    # them is dark, so mapping it bright enough to take a dye would leave the thread only a shade lighter
    # than the hide, and a Stitched Mask whose stitches cannot be seen is not one; the thread keeps its
    # own colour instead, and the hide takes the dye.
    h, s, v = _hsv(p)
    return h < 30 and s > 0.6 and v > 0.6


def _clay(p, x, y, kind):
    # The black eyes, mouth and strap.
    return _lum(p) < 45


def _ribbon(p, x, y, kind):
    # Comedy and Tragedy: the coloured ribbon that ties the worn mask on.
    return _hsv(p)[1] > 0.3


def _balaclava(p, x, y, kind):
    # The skull print and the olive cord. The print is drawn with the knit's own black for its
    # sockets and teeth, so no colour rule can tell it from the cloth: it is a region, the
    # bounding box of the print's light pixels.
    if kind == "item":
        return (5 <= x <= 10 and 9 <= y <= 13) or (y <= 6 and x <= 8)
    return (9 <= x <= 14 and 11 <= y <= 15) or y >= 36


def _brigand(p, x, y, kind):
    # The dark eye slits and outline, and the brown strap rows below the worn plate.
    return _lum(p) < 125 or (kind == "armor" and y >= 30)


def _owl(p, x, y, kind):
    # The white enamel face is the dyeable panel. The brown frame and feathers, the yellow eyes,
    # the black pupils and beak, and the straps keep their own colours: an owl is read by them.
    _, s, _ = _hsv(p)
    return not (s < 0.12 and _lum(p) > 100) or (kind == "armor" and y >= 30)


def _blank_iron(p, x, y, kind):
    # The black outline, and the worn strap rows. The worn face is mostly a dark-grey frame around
    # small plate pieces; that frame is the mask's own iron and takes the dye (as its darkest shade),
    # or a dyed Blank Iron Mask would show its colour on a fifth of its face.
    return _lum(p) < 40 or (kind == "armor" and y in (25, 26))


# Every style, in MaskVariant's order, with what on it keeps its own colour. The domino, scarf,
# veil and leather face are cloth or leather all over: their openings are already holes in the
# texture, and their seams are shading the grey map keeps.
STYLES = {
    "bandana": _bandana,
    "highwaymans_domino": _nothing,
    "wrapped_scarf": _nothing,
    "half_veil": _nothing,
    "leather_mask": _nothing,
    "raven_mask": _raven,
    "jackal_mask": _jackal,
    "stitched_mask": _stitched,
    "hockey_mask": _hockey,
    "clay_mask": _clay,
    "comedy_mask": _ribbon,
    "tragedy_mask": _ribbon,
    "iron_skull_mask": _balaclava,
    "brigand_visor": _brigand,
    "owl_mask": _owl,
    "blank_iron_mask": _blank_iron,
}


def _tone(y, low, median, high, tones):
    # Detail pixels are toned too (the base keeps the whole silhouette), and their luminance can fall
    # outside the dyeable range; the clamp below absorbs whatever the extrapolation gives them.
    lo, mid, hi = tones
    if y <= median:
        v = mid if median == low else lo + (mid - lo) * (y - low) / (median - low)
    else:
        v = hi if high == median else mid + (hi - mid) * (y - median) / (high - median)
    return max(0, min(255, round(max(v, y))))


def split(original, rule, kind):
    """The neutral base and the detail layer for one original texture."""
    width, height = original.size
    base = Image.new("RGBA", original.size, (0, 0, 0, 0))
    detail = Image.new("RGBA", original.size, (0, 0, 0, 0))
    opaque = [(x, y) for y in range(height) for x in range(width) if original.getpixel((x, y))[3] > 0]
    dyeable = [(x, y) for x, y in opaque if not rule(original.getpixel((x, y)), x, y, kind)]
    if not dyeable:
        raise ValueError("no dyeable pixels")
    levels = [_lum(original.getpixel(xy)) for xy in dyeable]
    low, median, high = min(levels), statistics.median_low(levels), max(levels)
    for x, y in opaque:
        p = original.getpixel((x, y))
        v = _tone(_lum(p), low, median, high, TONES[kind])
        # The base keeps the original's whole silhouette, detail pixels included, so the detail
        # layer only ever draws over it and an item's generated edges stay the original's edges.
        base.putpixel((x, y), (v, v, v, 255))
        if rule(p, x, y, kind):
            detail.putpixel((x, y), (p[0], p[1], p[2], 255))
    return base, detail


def dyed_model(style, original_model):
    """models/item/<style>_dyed.json: the base tinted by layer 0's tint index, the detail untinted."""
    base = "mcacrime:item/" + style + "_dyed"
    detail = "mcacrime:item/" + style + "_dyed_detail"
    if original_model.get("parent") == "item/generated":
        return {"parent": "item/generated", "textures": {"layer0": base, "layer1": detail}}
    # A hand-built model (the Highwayman's Domino samples its icon from a flat plane). Each element
    # is drawn twice in the same place: tinted from the base, then untinted from the detail. The
    # quads are coplanar on purpose -- item/generated stacks its layers exactly the same way, and a
    # later quad at the same depth wins.
    model = {key: value for key, value in original_model.items() if key not in ("textures", "elements", "overrides")}
    model["textures"] = {"base": base, "detail": detail, "particle": "#base"}
    elements = []
    for element in original_model["elements"]:
        tinted = json.loads(json.dumps(element))
        for face in tinted["faces"].values():
            face["texture"] = "#base"
            face["tintindex"] = 0
        untinted = json.loads(json.dumps(element))
        for face in untinted["faces"].values():
            face["texture"] = "#detail"
            face.pop("tintindex", None)
        elements += [tinted, untinted]
    model["elements"] = elements
    return model


def with_override(style, original_model):
    """The style's own model, selecting the dyed model for any stack that stores a colour."""
    model = {key: value for key, value in original_model.items() if key != "overrides"}
    model["overrides"] = [{"predicate": {PREDICATE: 1}, "model": "mcacrime:item/" + style + "_dyed"}]
    return model


def _json_text(value):
    return json.dumps(value, indent=2) + "\n"


def build():
    """Every output as {path: PIL image or JSON text}."""
    outputs = {}
    for style, rule in STYLES.items():
        for kind, folder, source in (("item", ITEM_TEXTURES, style + ".png"),
                                     ("armor", ARMOR_TEXTURES, style + "_layer_1.png")):
            original = Image.open(folder / source).convert("RGBA")
            try:
                base, detail = split(original, rule, kind)
            except ValueError as problem:
                raise SystemExit(f"{style} {kind}: {problem}")
            outputs[folder / (style + "_dyed.png")] = base
            outputs[folder / (style + "_dyed_detail.png")] = detail
        model_path = ITEM_MODELS / (style + ".json")
        original_model = json.loads(model_path.read_text())
        outputs[ITEM_MODELS / (style + "_dyed.json")] = _json_text(dyed_model(style, original_model))
        outputs[model_path] = _json_text(with_override(style, original_model))
    return outputs


def _composite(base, detail, rgb):
    tinted = Image.new("RGBA", base.size, (0, 0, 0, 0))
    for y in range(base.size[1]):
        for x in range(base.size[0]):
            p = base.getpixel((x, y))
            if p[3]:
                tinted.putpixel((x, y), (p[0] * rgb[0] // 255, p[1] * rgb[1] // 255, p[2] * rgb[2] // 255, 255))
    return Image.alpha_composite(tinted, detail)


# The sixteen standard colours as MaskColors.standardColor computes them (vanilla's leather blend of
# one dye on an undyed item), in DyeColor order. Only the preview uses these.
STANDARD = [0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21]


def preview(outputs, target):
    cell, gap = 64, 4
    rows = []
    for style in STYLES:
        for kind, folder, source in (("item", ITEM_TEXTURES, style + ".png"),
                                     ("armor", ARMOR_TEXTURES, style + "_layer_1.png")):
            original = Image.open(folder / source).convert("RGBA")
            base = outputs[folder / (style + "_dyed.png")]
            detail = outputs[folder / (style + "_dyed_detail.png")]
            row = [original] + [_composite(base, detail, ((c >> 16) & 255, (c >> 8) & 255, c & 255))
                                for c in STANDARD]
            rows.append(row)
    sheet = Image.new("RGBA", (17 * (cell + gap) + gap, len(rows) * (cell + gap) + gap), (139, 139, 139, 255))
    for r, row in enumerate(rows):
        for c, image in enumerate(row):
            scale = cell / max(image.size)
            shown = image.resize((round(image.size[0] * scale), round(image.size[1] * scale)), Image.NEAREST)
            sheet.alpha_composite(shown, (gap + c * (cell + gap), gap + r * (cell + gap)))
    sheet.save(target)
    print(f"wrote preview {target}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help="verify the committed textures and models match this script instead of rewriting them")
    parser.add_argument("--preview", type=Path, metavar="PNG",
                        help="also write an offline contact sheet of every style under every standard dye")
    args = parser.parse_args()

    outputs = build()
    if args.preview:
        preview(outputs, args.preview)
    failures = []
    for path, value in outputs.items():
        relative = path.relative_to(REPO)
        if args.check:
            if not path.exists():
                failures.append(f"missing: {relative}")
            elif isinstance(value, str):
                if path.read_text() != value:
                    failures.append(f"differs: {relative}")
            else:
                committed = Image.open(path).convert("RGBA")
                if committed.size != value.size or committed.tobytes() != value.tobytes():
                    failures.append(f"differs: {relative}")
        elif isinstance(value, str):
            if not path.exists() or path.read_text() != value:
                path.write_text(value)
                print(f"wrote {relative}")
        else:
            value.save(path, "PNG", optimize=False, compress_level=9)
            print(f"wrote {relative}")

    if args.check:
        if failures:
            for failure in failures:
                print(failure, file=sys.stderr)
            return 1
        print(f"all {len(outputs)} dyed-mask files match this script")
    return 0


if __name__ == "__main__":
    sys.exit(main())
