#!/usr/bin/env python3
"""Генерация демо-изображений TENIR TV (процедурные горные пейзажи) и иконок лаунчера.

Внешние фотографии не используются, чтобы в репозитории не было материалов с неясной лицензией.
Для эксплуатации замените их фотографиями отеля через content.json (поля image/background).

    python3 tools/gen_demo_assets.py
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main")
ASSETS = os.path.join(ROOT, "assets", "demo")
RES = os.path.join(ROOT, "res")
W, H = 1920, 1080
SERIF = "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"


def hexc(s):
    s = s.lstrip("#")
    return np.array([int(s[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float32)


def vgrad(h, w, stops):
    """Вертикальный градиент по точкам [(позиция 0..1, цвет)]."""
    ys = np.linspace(0, 1, h)
    out = np.zeros((h, w, 3), np.float32)
    pos = [p for p, _ in stops]
    for ch in range(3):
        col = np.interp(ys, pos, [c[ch] for _, c in stops])
        out[:, :, ch] = col[:, None]
    return out


def smooth(v, k):
    """Сглаживание 1D (гауссово ядро k пикселей)."""
    x = np.arange(-3 * k, 3 * k + 1)
    g = np.exp(-(x ** 2) / (2 * k * k))
    return np.convolve(np.pad(v, 3 * k, mode="edge"), g / g.sum(), mode="valid")


def ridge(w, rng, base, amp, rough=0.48, octaves=6, peak_x=None):
    """Линия хребта: сумма синусоид с убывающей амплитудой + выраженная вершина."""
    x = np.linspace(0, 1, w)
    y = np.zeros(w)
    a, f = amp, 1.6
    for _ in range(octaves):
        y += a * np.sin(2 * math.pi * (f * x + rng.random())) * (0.6 + 0.4 * rng.random())
        y += a * 0.5 * np.abs(np.sin(2 * math.pi * (f * 1.7 * x + rng.random())))
        a *= rough
        f *= 2.05
    if peak_x is not None:
        y += amp * 1.6 * np.exp(-((x - peak_x) ** 2) / 0.006)
    return base - smooth(y, 2)


def radial(h, w, cx, cy, r):
    yy, xx = np.mgrid[0:h, 0:w]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
    return np.clip(1 - d, 0, 1) ** 2


def mountains(img, rng, layers, light_from=0.7, snow=False, rim=None):
    h, w, _ = img.shape
    yy = np.arange(h)[:, None]
    for i, (base, amp, color, haze, peak) in enumerate(layers):
        line = ridge(w, rng, base * h, amp * h, peak_x=peak)
        mask = yy >= line[None, :]
        depth = np.clip((yy - line[None, :]) / (h * 0.35), 0, 1)
        col = hexc(color)
        shade = col[None, None, :] * (1 - 0.35 * depth[:, :, None])
        # склоны: освещённая и теневая сторона по наклону хребта
        slope = smooth(np.gradient(line), 30)
        lit = np.clip(-slope * (1 if light_from > 0.5 else -1) * 0.9, -1, 1)
        fade = np.exp(-depth * 3)
        shade *= (1 + 0.25 * lit[None, :, None] * fade[:, :, None])
        if snow and i < 2:
            thick = h * (0.07 + 0.05 * smooth(rng.random(w), 40))[None, :]
            cap = np.clip(1 - (yy - line[None, :]) / thick, 0, 1) ** 1.5
            snow_col = np.array([236, 240, 246])[None, None, :] * (0.82 + 0.18 * lit[None, :, None])
            shade = shade * (1 - cap[:, :, None]) + snow_col * cap[:, :, None]
        if rim is not None:
            edge = np.clip(1 - (yy - line[None, :]) / 2.0, 0, 1) * mask
            shade = shade + hexc(rim)[None, None, :] * edge[:, :, None] * 0.55
        shade = shade * (1 - haze) + img * 0 + haze * shade.mean()
        img = np.where(mask[:, :, None], shade, img)
    return img


def mist(img, y, height, color, strength):
    h, w, _ = img.shape
    yy = np.arange(h)[:, None]
    m = np.exp(-((yy - y * h) ** 2) / (2 * (height * h) ** 2)) * strength
    return img * (1 - m[:, :, None]) + hexc(color)[None, None, :] * m[:, :, None]


def stars(img, rng, n, top):
    h, w, _ = img.shape
    for _ in range(n):
        x, y = rng.integers(0, w), rng.integers(0, int(h * top))
        b = rng.random() ** 3
        img[y, x] = np.clip(img[y, x] + 255 * b, 0, 255)
    return img


def finish(img, name, grain=4.0, blur=0.0, quality=86, size=None):
    rng = np.random.default_rng(7)
    img = img + rng.normal(0, grain, img.shape)
    im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    if blur:
        im = im.filter(ImageFilter.GaussianBlur(blur))
    if size:
        im = im.resize(size, Image.LANCZOS)
    os.makedirs(ASSETS, exist_ok=True)
    im.save(os.path.join(ASSETS, name), "JPEG", quality=quality, optimize=True, progressive=True)
    return im


def scene_background():
    rng = np.random.default_rng(11)
    img = vgrad(H, W, [(0, hexc("#06101C")), (0.45, hexc("#14263A")), (0.62, hexc("#3A4E66")), (0.7, hexc("#8C6E5A"))])
    img += radial(H, W, W * 0.72, H * 0.66, W * 0.45)[:, :, None] * hexc("#7A5A44")[None, None, :] * 0.5
    img = stars(img, rng, 900, 0.5)
    img = mountains(img, rng, [
        (0.60, 0.10, "#4A5A70", 0.25, 0.68),
        (0.66, 0.08, "#2E3C50", 0.15, None),
        (0.74, 0.06, "#1A2534", 0.05, 0.25),
        (0.86, 0.05, "#0C121B", 0.0, None),
    ], snow=True, rim="#F2C48A")
    img = mist(img, 0.71, 0.03, "#5A6A80", 0.35)
    return finish(img, "background.jpg")


def scene_sunset():
    rng = np.random.default_rng(23)
    img = vgrad(H, W, [(0, hexc("#1B1030")), (0.3, hexc("#4A2340")), (0.52, hexc("#C0583A")), (0.64, hexc("#F2A65A")), (0.7, hexc("#FFD9A0"))])
    img += radial(H, W, W * 0.6, H * 0.6, W * 0.35)[:, :, None] * hexc("#FFD8A0")[None, None, :] * 0.9
    yy, xx = np.mgrid[0:H, 0:W]
    sun = (np.sqrt((xx - W * 0.6) ** 2 + (yy - H * 0.6) ** 2) < 46)
    img[sun] = hexc("#FFF2D8")
    img = mountains(img, rng, [
        (0.62, 0.09, "#7A3A4A", 0.2, 0.42),
        (0.70, 0.07, "#4A2236", 0.1, None),
        (0.80, 0.06, "#2A1424", 0.0, 0.8),
        (0.92, 0.04, "#140A12", 0.0, None),
    ], rim="#FFC27A")
    img = mist(img, 0.66, 0.025, "#E08A60", 0.3)
    return finish(img, "banner-sunset.jpg", size=(1600, 900))


def scene_ski():
    rng = np.random.default_rng(5)
    img = vgrad(H, W, [(0, hexc("#2C6BB0")), (0.4, hexc("#5E9AD6")), (0.62, hexc("#B9D7EF"))])
    img += radial(H, W, W * 0.85, H * 0.05, W * 0.5)[:, :, None] * hexc("#FFFFFF")[None, None, :] * 0.35
    img = mountains(img, rng, [
        (0.48, 0.13, "#8FA6C0", 0.25, 0.35),
        (0.60, 0.10, "#6F86A3", 0.12, 0.75),
        (0.74, 0.07, "#4E6582", 0.05, None),
        (0.88, 0.05, "#2F4058", 0.0, None),
    ], snow=True)
    img = mist(img, 0.6, 0.02, "#E6F0FA", 0.4)
    return finish(img, "banner-ski.jpg", size=(1600, 900))


def scene_spa():
    rng = np.random.default_rng(31)
    sky = vgrad(H // 2, W, [(0, hexc("#0E2A30")), (0.6, hexc("#2F5A5E")), (1, hexc("#A8C4BC"))])
    sky = mountains(sky, rng, [
        (0.62, 0.20, "#3E6662", 0.2, 0.5),
        (0.78, 0.14, "#25443F", 0.1, None),
        (0.94, 0.08, "#132824", 0.0, None),
    ], snow=True, rim="#E8F0E0")
    water = sky[::-1].copy() * 0.72 + hexc("#0B1E22")[None, None, :] * 0.28
    rip = (np.sin(np.arange(H - H // 2)[:, None] * 0.9 + rng.random() * 6) * 0.5 + 0.5) * 10
    water += rip[:, :, None] * 0.6
    img = np.concatenate([sky, water], axis=0)
    img = mist(img, 0.5, 0.04, "#CFE3DC", 0.5)
    return finish(img, "banner-spa.jpg", blur=0.6, size=(1600, 900))


def scene_restaurant():
    rng = np.random.default_rng(3)
    img = vgrad(H, W, [(0, hexc("#140C08")), (0.6, hexc("#2A1A10")), (1, hexc("#0E0806"))])
    im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).convert("RGBA")
    for layer, (count, rmin, rmax, blur) in enumerate([(70, 30, 90, 18), (40, 50, 130, 8), (18, 70, 150, 3)]):
        ov = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        for _ in range(count):
            x, y = rng.integers(0, W), int(rng.normal(H * 0.45, H * 0.2))
            r = int(rng.integers(rmin, rmax))
            warm = rng.choice([(255, 196, 120), (255, 170, 90), (255, 220, 170), (240, 140, 70)])
            a = int(rng.integers(40, 120))
            d.ellipse([x - r, y - r, x + r, y + r], fill=(*warm, a), outline=(*warm, min(255, a + 60)), width=2)
        im = Image.alpha_composite(im, ov.filter(ImageFilter.GaussianBlur(blur)))
    arr = np.asarray(im.convert("RGB")).astype(np.float32)
    arr *= (0.55 + 0.45 * radial(H, W, W * 0.5, H * 0.5, W * 0.8))[:, :, None] + 0.25
    return finish(arr, "restaurant.jpg", grain=5, size=(1600, 900))


def launcher_art():
    """Иконка и баннер Android TV: знак-горы и надпись TENIR."""
    gold = (201, 169, 110)

    def mark(draw, x0, y0, s, width):
        pts = [(4, 37), (17, 14), (24.5, 27), (30, 18), (44, 37)]
        draw.line([(x0 + px * s, y0 + py * s) for px, py in pts], fill=gold, width=width, joint="curve")
        draw.line([(x0 + 12.5 * s, y0 + 22 * s), (x0 + 16 * s, y0 + 24.5 * s), (x0 + 19.5 * s, y0 + 21 * s)], fill=gold, width=width)
        draw.line([(x0 + 2 * s, y0 + 42 * s), (x0 + 46 * s, y0 + 42 * s)], fill=gold, width=max(1, width * 3 // 4))

    def background(w, h):
        g = vgrad(h, w, [(0, hexc("#14202E")), (1, hexc("#080B10"))])
        return Image.fromarray(g.astype(np.uint8))

    for folder, scale in (("drawable-xhdpi", 1), ("drawable-xxxhdpi", 2)):
        bw, bh = 320 * scale, 180 * scale
        b = background(bw, bh)
        d = ImageDraw.Draw(b)
        mark(d, 28 * scale, 50 * scale, 1.7 * scale, 3 * scale)
        font = ImageFont.truetype(SERIF, 36 * scale)
        text = "TENIR"
        x = 122 * scale
        for ch in text:  # разрядка как в логотипе
            d.text((x, 64 * scale), ch, font=font, fill=(255, 255, 255))
            x += d.textlength(ch, font=font) + 6 * scale
        small = ImageFont.truetype(SERIF, 11 * scale)
        d.text((124 * scale, 114 * scale), "HOTEL  SHYMBULAK", font=small, fill=gold)
        os.makedirs(os.path.join(RES, folder), exist_ok=True)
        b.save(os.path.join(RES, folder, "banner.png"), optimize=True)

    for folder, px in (("drawable-mdpi", 48), ("drawable-hdpi", 72), ("drawable-xhdpi", 96), ("drawable-xxhdpi", 144), ("drawable-xxxhdpi", 192)):
        big = 512
        ic = background(big, big)
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=110, fill=255)
        d = ImageDraw.Draw(ic)
        mark(d, 64, 70, 8.0, 22)
        out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        out.paste(ic, (0, 0), mask)
        out = out.resize((px, px), Image.LANCZOS)
        os.makedirs(os.path.join(RES, folder), exist_ok=True)
        out.save(os.path.join(RES, folder, "ic_launcher.png"), optimize=True)


if __name__ == "__main__":
    scene_background()
    scene_sunset()
    scene_ski()
    scene_spa()
    scene_restaurant()
    launcher_art()
    print("готово:", ASSETS)
