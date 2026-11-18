"""Extract anti-aliased scope frame and moving-reticle layers from references."""

from __future__ import annotations

from collections import deque
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageOps


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/create_fire_control/textures/gui/scope"
REFERENCES = {
    "american": Path.home() / "Downloads/1787299044096.png",
    "chinese": Path.home() / "Downloads/1787299577016.png",
    "italian": Path.home() / "Downloads/1787300099423.png",
    "french": Path.home() / "Downloads/1787300665611.png",
    "squid": Path.home() / "Downloads/1787301087560.png",
    "elb": Path.home() / "Downloads/1787301415053.png",
    "german": Path.home() / "Downloads/1787302011844.png",
}

# Normalized crop bounds. Every crop keeps the source optical centre as its
# pivot; only this layer moves to the projected cannon aim point in game.
RETICLE_BOUNDS = {
    "american": (0.35, 0.37, 0.65, 0.63),
    "chinese": (0.43, 0.38, 0.57, 0.62),
    "italian": (0.36, 0.35, 0.64, 0.76),
    "french": (0.18, 0.25, 0.82, 0.75),
    "squid": (0.29, 0.22, 0.71, 0.78),
    "elb": (0.37, 0.35, 0.63, 0.72),
    "german": (0.35, 0.35, 0.65, 0.65),
}
CORNER_FRAME_PRESETS = {"american", "italian", "elb", "german"}
HORIZONTALLY_SYMMETRIC_PRESETS = {
    "american", "chinese", "italian", "french", "squid", "german"
}
TEXTURE_SIZE = (2848, 1600)
GEOMETRY_SIZE = (1424, 800)
FRAME_TEXTURE_META = '{"texture":{"blur":true,"clamp":true}}\n'
RETICLE_TEXTURE_META = '{"texture":{"blur":false,"clamp":true}}\n'


def thin(mask: Image.Image) -> Image.Image:
    """Reduce cleaned source strokes to their one-pixel centre lines."""
    pixels = np.asarray(mask.convert("L"), dtype=np.uint8) > 0
    pixels = np.pad(pixels, 1, mode="constant", constant_values=False)
    changed = True
    while changed:
        changed = False
        for first_pass in (True, False):
            centre = pixels[1:-1, 1:-1]
            p2 = pixels[:-2, 1:-1]
            p3 = pixels[:-2, 2:]
            p4 = pixels[1:-1, 2:]
            p5 = pixels[2:, 2:]
            p6 = pixels[2:, 1:-1]
            p7 = pixels[2:, :-2]
            p8 = pixels[1:-1, :-2]
            p9 = pixels[:-2, :-2]
            neighbours = (
                p2.astype(np.uint8) + p3 + p4 + p5 + p6 + p7 + p8 + p9
            )
            transitions = (
                (~p2 & p3).astype(np.uint8)
                + (~p3 & p4)
                + (~p4 & p5)
                + (~p5 & p6)
                + (~p6 & p7)
                + (~p7 & p8)
                + (~p8 & p9)
                + (~p9 & p2)
            )
            remove = centre & (neighbours >= 2) & (neighbours <= 6) & (transitions == 1)
            if first_pass:
                remove &= ~(p2 & p4 & p6)
                remove &= ~(p4 & p6 & p8)
            else:
                remove &= ~(p2 & p4 & p8)
                remove &= ~(p2 & p6 & p8)
            if remove.any():
                centre[remove] = False
                changed = True
    return Image.fromarray(
        (pixels[1:-1, 1:-1] * 255).astype(np.uint8), mode="L"
    )


def horizontal_runs(row: np.ndarray) -> list[tuple[int, int]]:
    """Return inclusive foreground runs in one binary row."""
    padded = np.pad(row.astype(np.int8), (1, 1), constant_values=0)
    changes = np.diff(padded)
    starts = np.flatnonzero(changes == 1)
    ends = np.flatnonzero(changes == -1) - 1
    return list(zip(starts.tolist(), ends.tolist()))


def symmetrize_horizontal(mask: Image.Image) -> Image.Image:
    """Build the closest row-wise mirror image around the optical axis.

    Screenshot antialiasing moves nominally mirrored strokes by one or more
    source pixels.  Mirroring only one side would preserve that side's error;
    pairing runs and averaging their distance and length minimizes the change
    to both sides while guaranteeing exact left/right symmetry.
    """
    source = np.asarray(mask.convert("L"), dtype=np.uint8) > 0
    result = np.zeros_like(source)
    centre_x = source.shape[1] // 2

    for y, row in enumerate(source):
        runs = horizontal_runs(row)
        central: list[tuple[int, int]] = []
        left: list[tuple[int, int]] = []
        right: list[tuple[int, int]] = []
        for start, end in runs:
            if start <= centre_x <= end:
                central.append((start, end))
            elif end < centre_x:
                left.append((start, end))
            else:
                right.append((start, end))

        left.sort(key=lambda run: centre_x - (run[0] + run[1]) * 0.5)
        right.sort(key=lambda run: (run[0] + run[1]) * 0.5 - centre_x)
        if not central and left and left[0][1] == centre_x - 1:
            # A lone one-pixel axis line commonly lands just left of centre
            # when an even-resolution screenshot is reduced by half.  Do not
            # snap it when a nearby right run proves it is one side of a ring.
            if not right or right[0][0] > centre_x + 2:
                left.pop(0)
                central.append((centre_x, centre_x))
        if not central and right and right[0][0] == centre_x + 1:
            if not left or left[0][1] < centre_x - 2:
                right.pop(0)
                central.append((centre_x, centre_x))

        for start, end in central:
            left_extent = centre_x - start
            right_extent = end - centre_x
            half_extent = round((left_extent + right_extent) * 0.5)
            result[y, centre_x - half_extent:centre_x + half_extent + 1] = True

        # Pair from the optical axis outwards.  This correctly matches rings,
        # scale ticks, separated bars, and the French chevrons row by row.
        pair_count = max(len(left), len(right))
        for index in range(pair_count):
            left_run = left[index] if index < len(left) else None
            right_run = right[index] if index < len(right) else None
            available = [run for run in (left_run, right_run) if run is not None]
            lengths = [end - start + 1 for start, end in available]
            distances = []
            if left_run is not None:
                distances.append(centre_x - (left_run[0] + left_run[1]) * 0.5)
            if right_run is not None:
                distances.append((right_run[0] + right_run[1]) * 0.5 - centre_x)

            length = max(1, round(sum(lengths) / len(lengths)))
            distance = sum(distances) / len(distances)
            left_centre = centre_x - distance
            left_start = round(left_centre - (length - 1) * 0.5)
            left_end = left_start + length - 1
            right_start = 2 * centre_x - left_end
            right_end = 2 * centre_x - left_start
            result[y, left_start:left_end + 1] = True
            result[y, right_start:right_end + 1] = True

    return Image.fromarray((result * 255).astype(np.uint8), mode="L")


def symmetry_mismatch(mask: Image.Image) -> int:
    pixels = np.asarray(mask.convert("L"), dtype=np.uint8) > 0
    centre_x = pixels.shape[1] // 2
    mirrored = np.zeros_like(pixels)
    ys, xs = np.nonzero(pixels)
    reflected_x = 2 * centre_x - xs
    valid = (reflected_x >= 0) & (reflected_x < pixels.shape[1])
    mirrored[ys[valid], reflected_x[valid]] = True
    return int(np.count_nonzero(pixels ^ mirrored))


def geometry_layer(
        alpha: Image.Image, preserve_centre: bool, symmetric: bool
) -> Image.Image:
    """Create a clean shape map; runtime code supplies default-sight width."""
    reduced = alpha.resize(GEOMETRY_SIZE, Image.Resampling.LANCZOS)
    binary = reduced.point(lambda value: 255 if value >= 64 else 0)
    result = thin(binary)
    if symmetric:
        result = symmetrize_horizontal(result)
    if preserve_centre:
        # Zhang-Suen can erase an even-sized isolated dot. The references use
        # the optical centre pixel deliberately, most visibly on the squid sight.
        ImageDraw.Draw(result).point((GEOMETRY_SIZE[0] // 2, GEOMETRY_SIZE[1] // 2), fill=255)
    return result


def black_layer(mask: Image.Image) -> Image.Image:
    layer = Image.new("RGBA", mask.size, (0, 0, 0, 0))
    layer.putalpha(mask)
    return layer


def ink_layer(source: Image.Image) -> Image.Image:
    """Turn white into transparency while preserving original edge coverage."""
    rgb = source.convert("RGB")
    alpha = ImageOps.invert(ImageOps.grayscale(rgb))
    # Remove imperceptible white-background noise without clipping antialiasing.
    alpha = alpha.point(lambda value: 0 if value <= 2 else value)
    black = Image.new("RGBA", source.size, (0, 0, 0, 0))
    black.putalpha(alpha)
    return black


def edge_connected_mask(alpha: Image.Image) -> Image.Image:
    """Select the exact dark component connected to any image edge."""
    width, height = alpha.size
    pixels = alpha.load()
    selected = bytearray(width * height)
    pending: deque[tuple[int, int]] = deque()

    def enqueue(x: int, y: int) -> None:
        index = y * width + x
        if selected[index] or pixels[x, y] <= 2:
            return
        selected[index] = 1
        pending.append((x, y))

    for x in range(width):
        enqueue(x, 0)
        enqueue(x, height - 1)
    for y in range(height):
        enqueue(0, y)
        enqueue(width - 1, y)

    while pending:
        x, y = pending.popleft()
        if x > 0:
            enqueue(x - 1, y)
        if x + 1 < width:
            enqueue(x + 1, y)
        if y > 0:
            enqueue(x, y - 1)
        if y + 1 < height:
            enqueue(x, y + 1)

    return Image.frombytes("L", (width, height), bytes(255 if value else 0 for value in selected))


def add_corner_frames(mask: Image.Image, alpha: Image.Image) -> None:
    width, height = mask.size
    # The references place the four fixed brackets at 22.8%/77.2% of width
    # and height. These ROIs include the complete strokes and nothing central.
    for center_x in (0.228, 0.772):
        for center_y in (0.228, 0.772):
            left = round(width * (center_x - 0.055))
            right = round(width * (center_x + 0.055))
            top = round(height * (center_y - 0.065))
            bottom = round(height * (center_y + 0.065))
            roi = alpha.crop((left, top, right, bottom))
            mask.paste(ImageChops.lighter(mask.crop((left, top, right, bottom)), roi), (left, top))


def scaled_crop_box(size: tuple[int, int], bounds: tuple[float, float, float, float]) -> tuple[int, int, int, int]:
    width, height = size
    left, top, right, bottom = bounds
    return round(width * left), round(height * top), round(width * right), round(height * bottom)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, reference in REFERENCES.items():
        if not reference.exists():
            raise FileNotFoundError(f"Missing original gun-sight reference: {reference}")

        border_path = OUTPUT / f"{name}_border.png"
        frame_path = OUTPUT / f"{name}_frame.png"
        reticle_path = OUTPUT / f"{name}_reticle.png"
        frame_geometry_path = OUTPUT / f"{name}_frame_geometry.png"
        reticle_geometry_path = OUTPUT / f"{name}_reticle_geometry.png"

        source = Image.open(reference).convert("RGBA")
        if source.size != TEXTURE_SIZE:
            raise ValueError(f"Unexpected reference dimensions for {name}: {source.size}")
        ink = ink_layer(source)
        alpha = ink.getchannel("A")

        border_mask = edge_connected_mask(alpha)
        border_alpha = ImageChops.multiply(alpha, border_mask)
        if name == "chinese":
            # Remove the unrelated capture/debug text from the black border.
            ImageDraw.Draw(border_alpha).rectangle((0, 0, 150, 50), fill=255)

        fixed_alpha = Image.new("L", TEXTURE_SIZE, 0)
        if name in CORNER_FRAME_PRESETS:
            add_corner_frames(fixed_alpha, alpha)

        reticle_alpha = Image.new("L", TEXTURE_SIZE, 0)
        crop_box = scaled_crop_box(TEXTURE_SIZE, RETICLE_BOUNDS[name])
        reticle_alpha.paste(alpha.crop(crop_box), crop_box[:2])

        # All three layers stay at the source's native 2848x1600 resolution.
        # No thinning, resizing, or synthetic stroke expansion is performed.
        # Their union therefore reproduces the original screenshot pixels.
        black_layer(border_alpha).save(border_path, optimize=True)
        black_layer(fixed_alpha).save(frame_path, optimize=True)
        black_layer(reticle_alpha).save(reticle_path, optimize=True)
        frame_geometry = geometry_layer(
            fixed_alpha, False, name in HORIZONTALLY_SYMMETRIC_PRESETS
        )
        reticle_geometry = geometry_layer(
            reticle_alpha,
            alpha.getpixel((TEXTURE_SIZE[0] // 2, TEXTURE_SIZE[1] // 2)) >= 64,
            name in HORIZONTALLY_SYMMETRIC_PRESETS,
        )
        black_layer(frame_geometry).save(
            frame_geometry_path, optimize=True
        )
        black_layer(reticle_geometry).save(reticle_geometry_path, optimize=True)

        if name in HORIZONTALLY_SYMMETRIC_PRESETS:
            frame_error = symmetry_mismatch(frame_geometry)
            reticle_error = symmetry_mismatch(reticle_geometry)
            if frame_error or reticle_error:
                raise AssertionError(
                    f"Horizontal symmetry validation failed for {name}: "
                    f"frame={frame_error}, reticle={reticle_error}"
                )

        border_path.with_suffix(border_path.suffix + ".mcmeta").write_text(
            FRAME_TEXTURE_META, encoding="utf-8"
        )
        frame_path.with_suffix(frame_path.suffix + ".mcmeta").write_text(
            RETICLE_TEXTURE_META, encoding="utf-8"
        )
        reticle_path.with_suffix(reticle_path.suffix + ".mcmeta").write_text(
            RETICLE_TEXTURE_META, encoding="utf-8"
        )


if __name__ == "__main__":
    main()
