"""Extract the supplied HUD's frame silhouettes and numeral shapes at native 2K size.

Usage: python prepare_target_lock_hud.py optical.png aircraft.png ground.png detail.png
Source screenshots stay outside the distributed resources; only transparent symbols ship.
"""
from pathlib import Path
import json
import math
import sys
from collections import deque
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageStat

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/assets/create_fire_control/textures/gui/target_lock'
QA = ROOT / 'build/hud-style'
CHARS = '0123456789.-kms/'
CELL_W, CELL_H, ADVANCE = 24, 40, 15


def foreground(source, box, black=False, frame=False):
    """Recover coverage against locally sampled sky, retaining subpixel edge coverage."""
    x0, y0, x1, y1 = box
    crop = source.crop((x0-2, y0-2, x1+2, y1+2)).convert('RGB')
    w, h = crop.size
    border = [crop.getpixel((x, y)) for y in range(h) for x in range(w)
              if x in (0, w-1) or y in (0, h-1)]
    background = [max(1.0, sum(c[k] for c in border)/len(border)) for k in range(3)]
    coverage = Image.new('L', crop.size)
    data = []
    for r, g, b in crop.getdata():
        if black:
            alpha = max(0.0, 1.0 - min(r/background[0], g/background[1], b/background[2]))
        else:
            # Green = green coverage + the attenuated background. Comparing
            # red/blue also removes the dark font shadow from the silhouette.
            attenuation = (r/background[0] + b/background[2])*0.5
            alpha = max(0.0, (g-background[1]*attenuation)/255.0)
        data.append(round(min(1.0, alpha)*255))
    coverage.putdata(data)
    # Reject sky gradients, the target silhouette, and neighboring text. Keep
    # connected ink components, then recover only their one-pixel AA fringe.
    points = {(x,y) for y in range(h) for x in range(w) if coverage.getpixel((x,y)) >= 140}
    components = []
    while points:
        first = points.pop();queue = deque([first]);component = [first]
        while queue:
            x,y = queue.popleft()
            for point in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
                if point in points:
                    points.remove(point);queue.append(point);component.append(point)
        components.append(component)
    components.sort(key=len,reverse=True)
    selected = [c for c in components if len(c)>=50] if frame else components[:1]
    support = Image.new('L',crop.size)
    rejected = Image.new('L',crop.size)
    for c in components:
        layer = support if c in selected else rejected
        for point in c: layer.putpixel(point,255)
    support = support.filter(ImageFilter.MaxFilter(3))
    coverage = ImageChops.multiply(coverage,support)
    coverage = ImageChops.subtract(coverage,rejected)
    return coverage


def paint(mask, black=False, text=False):
    result = Image.new('RGBA', mask.size)
    if text:
        if black:
            halo = mask.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.GaussianBlur(0.65))
            halo = halo.point(lambda v: round(v*0.78))
            result.alpha_composite(Image.merge('RGBA', (Image.new('L', mask.size, 255),)*3+(halo,)))
        else:
            shadow = ImageChops.offset(mask.filter(ImageFilter.GaussianBlur(1.0)), 1, 1)
            shadow = shadow.point(lambda v: round(v*0.60))
            result.alpha_composite(Image.merge('RGBA', (Image.new('L', mask.size, 0),)*3+(shadow,)))
    color = (0, 0, 0) if black else (0, 255, 0)
    ink = Image.new('RGBA', mask.size, color+(0,))
    ink.putalpha(mask)
    result.alpha_composite(ink)
    return result


def main():
    sources = [Image.open(p).convert('RGB') for p in sys.argv[1:5]]
    if len(sources) != 4 or any(im.size != (2560, 1440) for im in sources[:3]):
        raise SystemExit('Expected the three original 2560x1440 references and the detail crop.')
    opt, air, ground, detail = sources
    # Tight native-pixel ink boxes; the first two crops are the same regions
    # used in the measured reference comparison sheet.
    o = opt.crop((815, 175, 1055, 335))
    a = air.crop((1230, 455, 1450, 625))
    g = ground.crop((1570, 428, 1795, 620))
    glyphs = {
        '0': (o, (13,94,24,113), 0, 1),
        '1': (o, (115,67,125,86), 1, 1),
        '2': (o, (99,20,109,39), 0, 1),
        '3': (ground, (1259,8,1270,27), 0, 1),
        '4': (o, (42,94,55,113), -1, 1),
        '5': (o, (129,20,140,39), 0, 1),
        '6': (detail, (81,131,92,150), -1, 1),
        '7': (ground, (31,278,42,297), 0, 1),
        '8': (g, (111,95,123,115), 0, 0),
        '9': (ground, (502,278,513,297), 0, 1),
        '.': (o, (117,35,121,39), 3, 16),
        '-': (o, (100,77,110,79), 1, 11),
        'k': (o, (160,20,171,39), 1, 1),
        'm': (o, (174,25,186,39), 0, 6),
        's': (g, (172,100,181,115), 1, 5),
        '/': (g, (156,91,168,119), -1, -4),
    }
    black_glyphs = {
        '1': (a, (105,71,114,90), 1, 1),
        '2': (a, (104,24,113,43), 0, 1),
        '3': (a, (48,98,59,117), 0, 1),
        '4': (a, (118,24,130,43), -1, 1),
        '6': (a, (119,71,129,90), 0, 1),
        '.': (a, (137,39,140,43), 3, 16),
        'k': (a, (180,24,190,43), 0, 1),
        'm': (a, (194,29,205,43), -1, 6),
        's': (a, (195,76,203,90), 1, 6),
        '/': (a, (178,67,190,93), -1, -3),
    }
    OUT.mkdir(parents=True, exist_ok=True)
    QA.mkdir(parents=True, exist_ok=True)
    atlas = Image.new('RGBA', (CELL_W*len(CHARS), CELL_H*2))
    for row, black in enumerate((False, True)):
        for index, ch in enumerate(CHARS):
            source, box, bearing, top = black_glyphs.get(ch, glyphs[ch]) if black else glyphs[ch]
            mask = foreground(source, box, black and ch in black_glyphs)
            cell = Image.new('L', (CELL_W, CELL_H))
            cell.paste(mask, (4+bearing-2, 6+top-2))
            atlas.alpha_composite(paint(cell, black, text=True), (index*CELL_W, row*CELL_H))
    atlas.save(OUT/'numerals.png')

    for name, source, box, black in (
        ('optical', opt, (835,191,908,264), False),
        ('ground_radar', ground, (1588,473,1660,545), False),
        ('aircraft_radar', air, (1255,475,1327,547), True),
    ):
        mask = foreground(source, box, black, frame=True)
        if name == 'optical':
            # Keep the complete continuous frame and its internal optical ring.
            pass
        sprite = paint(mask, black)
        sprite.save(OUT/(name+'.png'))
        if name == 'ground_radar':
            contact = Image.new('RGBA',mask.size,(255,255,255,0))
            contact.putalpha(mask)
            contact.save(OUT/'radar_contact.png')

    # The heading ring has a 25px outer diameter, unlike the previous 20px ring.
    mask = foreground(opt, (859,299,884,324))
    cx, cy = (mask.width-1)/2, (mask.height-1)/2
    pixels = mask.load()
    for y in range(mask.height):
        for x in range(mask.width):
            if math.hypot(x-cx,y-cy) > 12.75:
                pixels[x,y] = 0
    for black in (False, True):
        paint(mask, black).save(OUT/('heading_black.png' if black else 'heading_green.png'))

    # Subpixel-sampled round caps for the five-pixel heading stalk.
    cap = Image.new('L', (8*8,8*8));d=ImageDraw.Draw(cap)
    d.ellipse((12,12,52,52), fill=255)
    cap = cap.resize((8,8),Image.Resampling.LANCZOS)
    for black in (False,True):
        paint(cap,black).save(OUT/('cap_black.png' if black else 'cap_green.png'))
    (QA/'measurements.json').write_text(json.dumps({
        'reference_resolution':[2560,1440], 'optical_side':73, 'radar_side':72,
        'font_advance':ADVANCE, 'font_cap_height':20, 'glyph_order':CHARS,
        'readout_x':43, 'distance_cap_y':-34, 'closing_cap_y':13,
        'altitude_cap_y':41, 'heading_center_y':84, 'heading_diameter':25,
    },indent=2),encoding='utf-8')
    print('Created reference-derived HUD silhouettes and numeral atlas:', OUT)


if __name__ == '__main__':
    main()
