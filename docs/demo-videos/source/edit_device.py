#!/usr/bin/env python3
"""Compose the physical-device recordings into landscape instructional videos.

App pixels come only from the raw screenrecord takes in ../recordings. This
script crops, scales, rotates, retimes and places those pixels, then draws
captions, app labels and supporting graphics (tap rings, field outlines,
connectors and a back-to-back phone diagram) around them. No app UI is redrawn.
The timelines live in scenes.py.

Run with a Python that has Pillow (on this Mac: /usr/bin/python3).
Requires ffmpeg and ffprobe. No narration or audio is generated.

    /usr/bin/python3 docs/demo-videos/source/edit_device.py --video all
    /usr/bin/python3 docs/demo-videos/source/edit_device.py --preview
"""
import argparse
import json
import math
import os
import subprocess
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

import scenes as timeline

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / 'recordings'
CACHE = ROOT / 'source' / '.edit-cache'
W, H, FPS = 1920, 1080, 24
SRC_W, SRC_H = 1080, 2340
FONT = '/System/Library/Fonts/SFNS.ttf'
BG_TOP, BG_BOTTOM = (14, 20, 27), (24, 33, 44)
ACCENT = {'smart': (88, 150, 230), 'precheck': (130, 190, 70)}
APP_NAME = {'smart': 'PassportEmu', 'precheck': 'Precheck'}
HIGHLIGHT = (255, 197, 61)
RADIUS = 22


# ---------------------------------------------------------------- utilities

def run(cmd, **kw):
    subprocess.run(cmd, check=True, **kw)


def probe(path):
    return json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_format', '-show_streams', '-of', 'json', str(path)
    ]))


_fonts = {}


def font(size, weight='Semibold'):
    key = (size, weight)
    if key not in _fonts:
        f = ImageFont.truetype(FONT, size)
        f.set_variation_by_name(weight)
        _fonts[key] = f
    return _fonts[key]


def ease(p):
    p = min(1.0, max(0.0, p))
    return p * p * (3 - 2 * p)


def lerp(a, b, p):
    if isinstance(a, (tuple, list)):
        return tuple(lerp(x, y, p) for x, y in zip(a, b))
    return a + (b - a) * p


def ramp(t, t0, t1, fade=0.3):
    """0→1→0 envelope for an element visible between t0 and t1."""
    if t < t0 or t > t1:
        return 0.0
    return ease(min((t - t0) / fade, (t1 - t) / fade, 1.0))


# ------------------------------------------------------------- source clips

_durations = {}


def clip_duration(clip):
    if clip not in _durations:
        _durations[clip] = float(probe(RAW / f'{clip}.mp4')['format']['duration'])
    return _durations[clip]


class Decoder:
    """Sequential frame reader for one source range at a fixed scale.

    The fps filter runs before trimming so a static screen that Android stopped
    encoding is still represented by its last real frame. Past the end of the
    recording, the final encoded frame is held.
    """

    def __init__(self, clip, start, end, scale):
        self.w = max(2, int(round(SRC_W * scale / 2)) * 2)
        self.h = max(2, int(round(SRC_H * scale / 2)) * 2)
        # A short pre-roll guarantees a frame even for holds at the very end
        # of a recording, where fps output can stop before the requested time.
        start = max(0.0, min(start, clip_duration(clip)) - 0.25)
        self.first = math.ceil(start * FPS - 1e-6) / FPS
        end = min(end, clip_duration(clip)) + 2 / FPS
        vf = (f'fps={FPS},trim=start={start:.4f}:end={max(end, start + 2 / FPS):.4f},'
              f'setpts=PTS-STARTPTS,scale={self.w}:{self.h}:flags=area')
        self.proc = subprocess.Popen(
            ['ffmpeg', '-v', 'error', '-i', str(RAW / f'{clip}.mp4'), '-vf', vf,
             '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.index, self.frame = -1, None

    def get(self, t):
        want = max(0, int(round((t - self.first) * FPS)))
        size = self.w * self.h * 3
        while self.index < want:
            data = self.proc.stdout.read(size)
            if len(data) < size:
                break
            self.frame = Image.frombytes('RGB', (self.w, self.h), data)
            self.index += 1
        if self.frame is None:
            raise RuntimeError('No decodable frame')
        return self.frame

    def close(self):
        self.proc.stdout.close()
        self.proc.kill()
        self.proc.wait()


# ------------------------------------------------------------------- panels

def resolve(videos):
    """Fill in continued panel bases and segment start times."""
    for scenes in videos:
        last = {}
        for scene in scenes:
            for panel in scene['panels']:
                if panel['base'] == 'continue':
                    panel['base'] = dict(last[panel['id']])
                panel['base'] = {**timeline.DEFAULT_LAYOUT, **panel['base']}
                at = 0.0
                for seg in panel['clips']:
                    seg['start'] = at
                    at += seg['duration']
                last[panel['id']] = layout_at(panel, scene['duration'])
    return videos


def layout_at(panel, t):
    layout = dict(panel['base'])
    for move in panel.get('moves', []):
        if t < move['t']:
            continue
        p = ease((t - move['t']) / move['dur']) if move['dur'] > 0 else 1.0
        for key, value in move['to'].items():
            layout[key] = lerp(layout[key], value, p)
    return layout


def source_at(panel, t):
    clips = panel['clips']
    seg = clips[-1]
    for candidate in clips:
        if t < candidate['start'] + candidate['duration']:
            seg = candidate
            break
    local = min(max(0.0, t - seg['start']), seg['duration'])
    if seg['rate'] == 0:
        return seg, seg['src_in']
    return seg, min(seg['src_in'] + local * seg['rate'], seg['src_out'])


def local_time(panel, clip, src_t):
    for seg in panel['clips']:
        if seg['clip'] == clip and seg['rate'] > 0 and seg['src_in'] <= src_t <= seg['src_out']:
            return seg['start'] + (src_t - seg['src_in']) / seg['rate']
    raise ValueError(f'{panel["id"]}: {clip}@{src_t} is not in the edit')


def max_scale(panel, duration):
    best = 0
    states = [panel['base']] + [m['to'] for m in panel.get('moves', [])]
    layout = dict(panel['base'])
    for state in states:
        layout.update(state)
        best = max(best, layout['h'] / layout['vp'][3])
    return min(1.0, best * 1.04)


def geometry(layout):
    vx, vy, vw, vh = layout['vp']
    scale = layout['h'] / vh
    return scale, vw * scale, layout['h']


def map_point(layout, x, y):
    vx, vy, vw, vh = layout['vp']
    scale, dw, dh = geometry(layout)
    px, py = (x - vx) * scale - dw / 2, (y - vy) * scale - dh / 2
    a = math.radians(layout['rot'])
    return (layout['cx'] + px * math.cos(a) + py * math.sin(a),
            layout['cy'] - px * math.sin(a) + py * math.cos(a))


def map_rect(layout, rect):
    x0, y0, x1, y1 = rect
    pts = [map_point(layout, x, y) for x, y in ((x0, y0), (x1, y0), (x0, y1), (x1, y1))]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)


def panel_bounds(layout):
    vx, vy, vw, vh = layout['vp']
    return map_rect(layout, (vx, vy, vx + vw, vy + vh))


_masks = {}


def rounded_mask(size):
    if size not in _masks:
        big = Image.new('L', (size[0] * 2, size[1] * 2), 0)
        ImageDraw.Draw(big).rounded_rectangle((0, 0, big.width - 1, big.height - 1), RADIUS * 2, fill=255)
        _masks[size] = big.resize(size, Image.LANCZOS)
        if len(_masks) > 400:
            _masks.pop(next(iter(_masks)))
    return _masks[size]


def draw_panel(canvas, frame, layout):
    if layout['alpha'] <= 0.01:
        return
    vx, vy, vw, vh = layout['vp']
    _, dw, dh = geometry(layout)
    size = (max(2, int(round(dw))), max(2, int(round(dh))))
    sx, sy = frame.width / SRC_W, frame.height / SRC_H
    crop = (max(0, vx * sx), max(0, vy * sy), min(frame.width, (vx + vw) * sx), min(frame.height, (vy + vh) * sy))
    img = frame.resize(size, Image.BICUBIC, box=crop)
    if layout['dim'] > 0.01:
        img = ImageEnhance.Brightness(img).enhance(1 - layout['dim'])
    mask = rounded_mask(size)
    if abs(layout['rot']) > 0.01:
        img = img.rotate(layout['rot'], Image.BICUBIC, expand=True)
        mask = mask.rotate(layout['rot'], Image.BICUBIC, expand=True)
    if layout['alpha'] < 0.999:
        mask = mask.point(lambda a, f=layout['alpha']: int(a * f))
    x = int(round(layout['cx'] - img.width / 2))
    y = int(round(layout['cy'] - img.height / 2))
    # Soft shadow from the panel's own (possibly rotated) silhouette.
    small = mask.resize((max(1, mask.width // 4), max(1, mask.height // 4)))
    pad = 8
    shadow = Image.new('L', (small.width + pad * 2, small.height + pad * 2), 0)
    shadow.paste(small, (pad, pad))
    shadow = shadow.filter(ImageFilter.GaussianBlur(4)).resize(
        (shadow.width * 4, shadow.height * 4), Image.BILINEAR).point(lambda a: int(a * 0.6))
    black = Image.new('RGBA', shadow.size, (0, 0, 0, 0))
    black.putalpha(shadow)
    canvas.alpha_composite(black, (x - pad * 4, y - pad * 4 + 14))
    rgba = img.convert('RGBA')
    rgba.putalpha(mask)
    canvas.alpha_composite(rgba, (x, y))


class AA:
    """Supersampled drawing patch for antialiased vector graphics."""

    def __init__(self, box, ss=3):
        self.x0, self.y0 = int(box[0]) - 4, int(box[1]) - 4
        self.x1, self.y1 = int(box[2]) + 5, int(box[3]) + 5
        self.ss = ss
        self.img = Image.new('RGBA', ((self.x1 - self.x0) * ss, (self.y1 - self.y0) * ss), (0, 0, 0, 0))
        self.draw = ImageDraw.Draw(self.img)

    def p(self, x, y):
        return ((x - self.x0) * self.ss, (y - self.y0) * self.ss)

    def box(self, b):
        return (*self.p(b[0], b[1]), *self.p(b[2], b[3]))

    def commit(self, canvas):
        out = self.img.resize((self.x1 - self.x0, self.y1 - self.y0), Image.LANCZOS)
        canvas.alpha_composite(out, (self.x0, self.y0))


def rgba(color, alpha):
    return (*color, int(max(0, min(1, alpha)) * 255))


def draw_outline(canvas, bounds, color, alpha, pad=8, width=4):
    if alpha <= 0.01:
        return
    b = (bounds[0] - pad, bounds[1] - pad, bounds[2] + pad, bounds[3] + pad)
    aa = AA(b)
    aa.draw.rounded_rectangle(aa.box(b), (RADIUS + pad) * aa.ss, outline=rgba(color, alpha), width=width * aa.ss)
    aa.commit(canvas)


_labels = {}


def draw_label(canvas, panel_id, bounds, alpha):
    if alpha <= 0.01:
        return
    if panel_id not in _labels:
        f = font(30)
        text = APP_NAME[panel_id]
        tw = int(f.getlength(text))
        img = Image.new('RGBA', (tw + 40, 46), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        big = Image.new('RGBA', (42, 42), (0, 0, 0, 0))
        ImageDraw.Draw(big).ellipse((0, 0, 41, 41), fill=(*ACCENT[panel_id], 255))
        img.alpha_composite(big.resize((14, 14), Image.LANCZOS), (0, 17))
        d.text((26, 23), text, font=f, fill=(236, 241, 246, 255), anchor='lm')
        _labels[panel_id] = img
    img = _labels[panel_id]
    if alpha < 0.999:
        img = img.copy()
        img.putalpha(img.getchannel('A').point(lambda a: int(a * alpha)))
    cx = (bounds[0] + bounds[2]) / 2
    canvas.alpha_composite(img, (int(cx - img.width / 2), int(bounds[1] - 58)))


# ----------------------------------------------------------------- graphics

def draw_caption(canvas, text, alpha, rise):
    if alpha <= 0.01:
        return
    f = font(40)
    tw = f.getlength(text)
    while tw > 1640:
        f = font(f.size - 1)
        tw = f.getlength(text)
    cy = 1024 + rise
    b = (960 - tw / 2 - 30, cy - 34, 960 + tw / 2 + 30, cy + 34)
    aa = AA(b, ss=2)
    aa.draw.rounded_rectangle(aa.box(b), 34 * aa.ss, fill=(255, 255, 255, int(26 * alpha)))
    aa.commit(canvas)
    layer = Image.new('RGBA', (int(tw) + 20, 80), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((10, 40), text, font=f, fill=(255, 255, 255, int(255 * alpha)), anchor='lm')
    canvas.alpha_composite(layer, (int(960 - tw / 2 - 10), int(cy - 40)))


def draw_tap(canvas, layouts, g, t):
    layout = layouts[g['panel']]
    start = g['local']
    if not (start - 0.3 <= t <= start + 0.6) or layout['alpha'] < 0.5:
        return
    x, y = map_point(layout, g['x'], g['y'])
    color = ACCENT[g['panel']]
    aa = AA((x - 70, y - 70, x + 70, y + 70))
    if t < start:
        p = ease((t - (start - 0.3)) / 0.3)
        r = 34 - 8 * p
        aa.draw.ellipse(aa.box((x - r, y - r, x + r, y + r)), fill=(255, 255, 255, int(120 * p)),
                        outline=rgba(color, p), width=4 * aa.ss)
    else:
        p = (t - start) / 0.6
        r = 26 + 38 * ease(p)
        fade = 1 - ease(p)
        aa.draw.ellipse(aa.box((x - 26, y - 26, x + 26, y + 26)), fill=(255, 255, 255, int(120 * fade)))
        aa.draw.ellipse(aa.box((x - r, y - r, x + r, y + r)), outline=(255, 255, 255, int(230 * fade)),
                        width=5 * aa.ss)
    aa.commit(canvas)


def draw_box(canvas, layouts, g, t):
    a = ramp(t, g['t0'], g['t1'])
    if a <= 0.01:
        return
    b = map_rect(layouts[g['panel']], g['rect'])
    grow = 1 - ease((t - g['t0']) / 0.35)
    draw_outline(canvas, b, HIGHLIGHT, a * layouts[g['panel']]['alpha'], pad=8 + 14 * grow, width=4)


def anchor(b, toward):
    cx, cy = (b[0] + b[2]) / 2, (b[1] + b[3]) / 2
    if abs(toward[0] - cx) > abs(toward[1] - cy):
        return (b[2] + 12 if toward[0] > cx else b[0] - 12, cy)
    return (cx, b[3] + 12 if toward[1] > cy else b[1] - 12)


def draw_link(canvas, layouts, g, t):
    a = ramp(t, g['t0'], g['t1'])
    if a <= 0.01:
        return
    ba = map_rect(layouts[g['a'][0]], g['a'][1])
    bb = map_rect(layouts[g['b'][0]], g['b'][1])
    pa = anchor(ba, ((bb[0] + bb[2]) / 2, (bb[1] + bb[3]) / 2))
    pb = anchor(bb, ((ba[0] + ba[2]) / 2, (ba[1] + ba[3]) / 2))
    p = ease((t - g['t0'] - 0.15) / 0.5)
    if p <= 0:
        return
    end = lerp(pa, pb, p)
    aa = AA((min(pa[0], pb[0]) - 20, min(pa[1], pb[1]) - 20, max(pa[0], pb[0]) + 20, max(pa[1], pb[1]) + 20))
    col = rgba(HIGHLIGHT, a)
    aa.draw.line([aa.p(*pa), aa.p(*end)], fill=col, width=4 * aa.ss)
    r = 7
    aa.draw.ellipse(aa.box((pa[0] - r, pa[1] - r, pa[0] + r, pa[1] + r)), fill=col)
    if p > 0.98:
        ang = math.atan2(pb[1] - pa[1], pb[0] - pa[0])
        tip = pb
        left = (tip[0] - 18 * math.cos(ang - 0.5), tip[1] - 18 * math.sin(ang - 0.5))
        right = (tip[0] - 18 * math.cos(ang + 0.5), tip[1] - 18 * math.sin(ang + 0.5))
        aa.draw.polygon([aa.p(*tip), aa.p(*left), aa.p(*right)], fill=col)
    aa.commit(canvas)


def draw_nfc(canvas, g, t):
    """Side view of two phones moving back to back, then an NFC pulse."""
    a = ramp(t, g['t0'], g['t1'], 0.35)
    if a <= 0.01:
        return
    cx, cy = g['cx'], g['cy']
    local = t - g['t0']
    gap = 70 * (1 - ease((local - 0.4) / 0.9))
    pw, ph = 30, 300
    aa = AA((cx - 170, cy - 200, cx + 170, cy + 230))
    d = aa.draw
    for side, app in ((-1, 'smart'), (1, 'precheck')):
        x_back = cx + side * gap
        body = (x_back, cy - ph / 2, x_back + side * pw, cy + ph / 2)
        body = (min(body[0], body[2]), body[1], max(body[0], body[2]), body[3])
        d.rounded_rectangle(aa.box(body), 9 * aa.ss, fill=(58, 70, 84, int(255 * a)),
                            outline=(120, 134, 150, int(255 * a)), width=2 * aa.ss)
        screen_x = body[0] if side < 0 else body[2] - 7
        d.rounded_rectangle(aa.box((screen_x, body[1] + 10, screen_x + 7, body[3] - 10)), 3 * aa.ss,
                            fill=rgba(ACCENT[app], a))
    touching = local > 1.3
    if touching:
        for k in range(2):
            phase = ((local - 1.3) / 1.1 + k * 0.5) % 1.0
            r = 22 + 70 * phase
            alpha = a * (1 - phase) * 0.9
            d.ellipse(aa.box((cx - r, cy - 40 - r, cx + r, cy - 40 + r)),
                      outline=(255, 255, 255, int(255 * alpha)), width=3 * aa.ss)
    aa.commit(canvas)
    f = font(22, 'Medium')
    for side, app in ((-1, 'smart'), (1, 'precheck')):
        layer = Image.new('RGBA', (180, 34), (0, 0, 0, 0))
        ImageDraw.Draw(layer).text((90, 17), APP_NAME[app], font=f, fill=rgba(ACCENT[app], a), anchor='mm')
        canvas.alpha_composite(layer, (int(cx + side * 75 - 90), int(cy + ph / 2 + 22)))


# ------------------------------------------------------------------ render

_background = None


def background():
    global _background
    if _background is None:
        col = Image.new('RGB', (1, H))
        for y in range(H):
            col.putpixel((0, y), tuple(int(lerp(a, b, y / (H - 1))) for a, b in zip(BG_TOP, BG_BOTTOM)))
        bg = col.resize((W, H)).convert('RGBA')
        glow = Image.new('L', (W // 8, H // 8), 0)
        ImageDraw.Draw(glow).ellipse((W // 32, -H // 16, W // 8 - W // 32, H // 10), fill=40)
        glow = glow.filter(ImageFilter.GaussianBlur(18)).resize((W, H), Image.BILINEAR)
        white = Image.new('RGBA', (W, H), (60, 90, 120, 0))
        white.putalpha(glow)
        bg.alpha_composite(white)
        _background = bg
    return _background


def render_scene(job):
    kind, index, scene, out = job
    frames = int(round(scene['duration'] * FPS))
    for g in scene.get('graphics', []):
        if g['type'] == 'tap':
            panel = next(p for p in scene['panels'] if p['id'] == g['panel'])
            g['local'] = local_time(panel, g['clip'], g['change'] - 0.22)
    scales = {p['id']: max_scale(p, scene['duration']) for p in scene['panels']}
    decoders = {}
    enc = subprocess.Popen(
        ['ffmpeg', '-y', '-v', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}',
         '-r', str(FPS), '-i', '-', '-an', '-c:v', 'libx264', '-preset', 'medium', '-crf', '18',
         '-pix_fmt', 'yuv420p', '-g', str(FPS * 2), '-threads', '2', str(out)],
        stdin=subprocess.PIPE)
    for n in range(frames):
        t = n / FPS
        canvas = background().copy()
        layouts = {}
        for panel in scene['panels']:
            layout = layout_at(panel, t)
            layouts[panel['id']] = layout
            if layout['alpha'] <= 0.01:
                continue
            seg, src_t = source_at(panel, t)
            key = (panel['id'], id(seg))
            if key not in decoders:
                for k in [k for k in decoders if k[0] == panel['id']]:
                    decoders.pop(k).close()
                end = seg['src_out'] if seg['rate'] > 0 else seg['src_in']
                decoders[key] = Decoder(seg['clip'], seg['src_in'], end, scales[panel['id']])
            frame = decoders[key].get(src_t)
            draw_panel(canvas, frame, layout)
            bounds = panel_bounds(layout)
            if abs(layout['rot'] % 90) < 0.5 or abs(layout['rot'] % 90) > 89.5:
                draw_outline(canvas, bounds, ACCENT[panel['id']], layout['active'] * layout['alpha'])
            draw_label(canvas, panel['id'], bounds, layout['label'] * layout['alpha'])
        for g in scene.get('graphics', []):
            {'tap': lambda: draw_tap(canvas, layouts, g, t),
             'box': lambda: draw_box(canvas, layouts, g, t),
             'link': lambda: draw_link(canvas, layouts, g, t),
             'nfc': lambda: draw_nfc(canvas, g, t)}[g['type']]()
        for c in scene.get('captions', []):
            a = ramp(t, c['t0'], c['t1'], 0.35)
            draw_caption(canvas, c['text'], a, 10 * (1 - ease((t - c['t0']) / 0.35)))
        img = canvas.convert('RGB')
        fade = 1.0
        if scene.get('fade_in'):
            fade = min(fade, ease(t / scene['fade_in']))
        if scene.get('fade_out'):
            fade = min(fade, ease((scene['duration'] - t - 1 / FPS) / scene['fade_out']))
        if fade < 0.999:
            img = ImageEnhance.Brightness(img).enhance(fade)
        enc.stdin.write(img.tobytes())
    for d in decoders.values():
        d.close()
    enc.stdin.close()
    if enc.wait() != 0:
        raise RuntimeError(f'Encoding failed: {kind} scene {index}')
    print(f'{kind}: rendered scene {index + 1} {scene["id"]}', flush=True)
    return str(out)


# ------------------------------------------------------------------ outputs

def stamp(seconds):
    ms = round(seconds * 1000)
    return f'{ms//3600000:02}:{ms//60000%60:02}:{ms//1000%60:02},{ms%1000:03}'


def manifest(scenes):
    out, at = [], 0.0
    for scene in scenes:
        entry = dict(id=scene['id'], purpose=scene['purpose'], layout=scene['layout'],
                     timeline_start=round(at, 3), duration=scene['duration'], panels=[],
                     captions=[dict(text=c['text'], start=round(at + c['t0'], 3), end=round(at + c['t1'], 3))
                               for c in scene.get('captions', [])],
                     graphics=[])
        for panel in scene['panels']:
            entry['panels'].append(dict(
                app=APP_NAME[panel['id']],
                segments=[dict(source=f"{s['clip']}.mp4", source_in=round(s['src_in'], 3),
                               source_out=round(s['src_out'], 3),
                               playback_rate=s['rate'] if s['rate'] else 'hold',
                               timeline_start=round(at + s['start'], 3), duration=round(s['duration'], 3))
                          for s in panel['clips']],
                start_layout=panel['base'], moves=panel.get('moves', [])))
        for g in scene.get('graphics', []):
            item = {k: v for k, v in g.items() if k != 'local'}
            if 'local' in g:
                item['timeline_time'] = round(at + g['local'], 3)
            for key in ('t0', 't1'):
                if key in item:
                    item[key] = round(at + item[key], 3)
            entry['graphics'].append(item)
        out.append(entry)
        at += scene['duration']
    return out


def captions_srt(scenes):
    lines, at = [], 0.0
    for scene in scenes:
        for c in scene.get('captions', []):
            lines.append(f'{len(lines)+1}\n{stamp(at + c["t0"])} --> {stamp(at + c["t1"])}\n{c["text"]}\n')
        at += scene['duration']
    return '\n'.join(lines)


def render(kind, scenes, final, workers):
    work = CACHE / kind
    work.mkdir(parents=True, exist_ok=True)
    for old in work.glob('*.mp4'):
        old.unlink()
    jobs = [(kind, i, s, work / f'{i:02}.mp4') for i, s in enumerate(scenes)]
    # Longest scenes first keeps the worker pool busy.
    order = sorted(jobs, key=lambda j: -j[2]['duration'])
    with ProcessPoolExecutor(workers) as pool:
        list(pool.map(render_scene, order))
    playlist = work / 'concat.txt'
    playlist.write_text(''.join(f"file '{j[3].resolve()}'\n" for j in jobs))
    run(['ffmpeg', '-y', '-hide_banner', '-loglevel', 'error', '-f', 'concat', '-safe', '0',
         '-i', str(playlist), '-map', '0:v:0', '-c', 'copy', '-an', '-movflags', '+faststart', str(final)])
    total = sum(s['duration'] for s in scenes)
    result = probe(final)
    streams = result['streams']
    assert len(streams) == 1 and streams[0]['codec_type'] == 'video', 'unexpected streams'
    assert (streams[0]['width'], streams[0]['height']) == (W, H)
    assert abs(float(result['format']['duration']) - total) < 0.05, result['format']['duration']
    print(f'Saved {final} ({total:g}s, no audio)', flush=True)


def edit(kind, scenes, workers):
    total = sum(s['duration'] for s in scenes)
    assert abs(total - timeline.TARGET[kind]) < 1e-6, f'{kind} runs {total}s'
    for s in scenes:
        assert abs(s['duration'] * FPS - round(s['duration'] * FPS)) < 1e-6, s['id']
    render(kind, scenes, ROOT / f'passportemu-precheck-{kind}.mp4', workers)
    (ROOT / f'passportemu-precheck-{kind}.srt').write_text(captions_srt(scenes))
    (ROOT / 'source' / f'{kind}-edit.json').write_text(json.dumps(manifest(scenes), indent=1) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--video', choices=['demo', 'tutorial', 'all'], default='all')
    parser.add_argument('--preview', action='store_true',
                        help='render only the capture, reading and outcome scenes of the demo')
    parser.add_argument('--workers', type=int, default=max(1, min(8, (os.cpu_count() or 2) - 2)))
    args = parser.parse_args()
    demo, tutorial = resolve([timeline.demo(), timeline.tutorial()])
    if args.preview:
        picked = [s for s in demo if s['id'] in timeline.PREVIEW]
        render('preview', picked, CACHE / 'preview.mp4', args.workers)
    else:
        if args.video in ('demo', 'all'):
            edit('demo', demo, args.workers)
        if args.video in ('tutorial', 'all'):
            edit('tutorial', tutorial, args.workers)
