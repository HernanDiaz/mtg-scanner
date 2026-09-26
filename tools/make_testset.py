"""Genera fotos sintéticas "de móvil" a partir de escaneos de Scryfall, con la
impresión exacta como etiqueta, para medir el reconocimiento.

Uso:
    tools/.venv/Scripts/python.exe tools/make_testset.py [--per-group 25] [--seed 1]

Salida: testset/synthetic/*.jpg + testset/synthetic/labels.csv
"""

import argparse
import csv
import io
import math
import os
import random
import sqlite3
import sys
import time

import numpy as np
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_catalog import OUT_PATH as CATALOG, ROOT, http_get  # noqa: E402

CACHE_DIR = os.path.join(ROOT, "data", "scans")

# Fotograma de análisis de la app: 1080p en vertical.
CANVAS_W, CANVAS_H = 1080, 1920

# Grupos de prueba: (nombre, condición SQL). Cada uno pone a prueba algo distinto.
GROUPS = [
    ("nuevo_2023", "released_at >= '2023-01-01' AND name NOT LIKE '%//%'"),   # "R 0123" + "MOM • EN"
    ("m15_2022", "released_at BETWEEN '2014-07-01' AND '2022-12-31' AND name NOT LIKE '%//%'"),  # "123/274 R" + "M20 • EN"
    ("2003_2014", "released_at BETWEEN '2003-07-01' AND '2014-06-30' AND name NOT LIKE '%//%'"),  # número sin código
    ("antigua", "released_at < '2003-07-01' AND name NOT LIKE '%//%'"),      # sin número ni código
    ("doble_cara", "name LIKE '%//%'"),
    ("muchas_ediciones", "name IN (SELECT name FROM cards GROUP BY name HAVING COUNT(*) >= 12) "
                         "AND name NOT IN ('Plains','Island','Swamp','Mountain','Forest','Wastes')"),
    ("basicas", "name IN ('Plains','Island','Swamp','Mountain','Forest')"),
]


def pick_foreign(n, rng):
    """Cartas en otros idiomas con escaneo propio (lista que genera build_catalog.py).
    La etiqueta es el nombre inglés y la edición/número, que coinciden con la versión inglesa."""
    with open(os.path.join(ROOT, "data", "foreign_printings.csv"), encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    old = [r for r in rows if r["released_at"] < "2015"]
    new = [r for r in rows if r["released_at"] >= "2015" and " // " not in r["name"]]
    if n < 0:  # todas
        pick = rows
    else:
        pick = rng.sample(old, min(n // 2, len(old))) + rng.sample(new, n - min(n // 2, len(old)))
    return [(f"{r['lang']}_{'antigua' if r['released_at'] < '2015' else 'moderna'}", r["id"], r["name"],
             r["set_code"], r["collector_number"], r["lang"]) for r in pick]


def pick_cards(per_group, rng, only=None):
    db = sqlite3.connect(CATALOG)
    picked = []
    for group, cond in GROUPS:
        if only and group not in only:
            continue
        rows = db.execute(
            f"SELECT id, name, set_code, collector_number FROM cards "
            f"WHERE promo = 0 AND lang = 'en' AND {cond} ORDER BY id"
        ).fetchall()
        for row in rng.sample(rows, min(per_group, len(rows))):
            picked.append((group, *row, "en"))
    return picked


def scan(card_id):
    """Escaneo PNG de Scryfall (745×1040, esquinas transparentes), con caché local."""
    os.makedirs(CACHE_DIR, exist_ok=True)
    path = os.path.join(CACHE_DIR, f"{card_id}.png")
    if not os.path.exists(path):
        url = f"https://cards.scryfall.io/png/front/{card_id[0]}/{card_id[1]}/{card_id}.png"
        with http_get(url) as r, open(path, "wb") as f:
            f.write(r.read())
        time.sleep(0.1)  # ritmo respetuoso con Scryfall
    return Image.open(path).convert("RGBA")


# ---------------------------------------------------------------- defectos

def background(rng):
    """Mesa: color liso, degradado o textura con ruido."""
    base = np.array([rng.randint(20, 200) for _ in range(3)], dtype=np.float32)
    kind = rng.choice(["solid", "gradient", "texture"])
    img = np.ones((CANVAS_H, CANVAS_W, 3), np.float32) * base
    if kind == "gradient":
        g = np.linspace(rng.uniform(0.6, 1.0), rng.uniform(1.0, 1.4), CANVAS_H)[:, None, None]
        img *= g
    elif kind == "texture":
        small = np.random.default_rng(rng.randint(0, 1 << 30)).normal(0, 1, (CANVAS_H // 40, CANVAS_W // 40, 1))
        tex = np.array(Image.fromarray(((small[:, :, 0] + 3) * 40).clip(0, 255).astype(np.uint8))
                       .resize((CANVAS_W, CANVAS_H), Image.BICUBIC), np.float32)[:, :, None]
        img += (tex - 120) * rng.uniform(0.2, 0.6)
    return Image.fromarray(img.clip(0, 255).astype(np.uint8))


def perspective_coeffs(src, dst):
    """Coeficientes para Image.transform(PERSPECTIVE) que llevan dst -> src."""
    a = []
    for (x, y), (u, v) in zip(dst, src):
        a.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        a.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    b = [c for p in src for c in p]
    return np.linalg.solve(np.array(a, np.float64), np.array(b, np.float64)).tolist()


def place_card(card, bg, hard, rng):
    """Escala, gira y aplica perspectiva a la carta y la pega en la mesa."""
    width = CANVAS_W * (rng.uniform(0.55, 0.9) if hard else rng.uniform(0.65, 0.85))
    scale = width / card.width
    cw, ch = card.width * scale, card.height * scale
    cx = CANVAS_W / 2 + rng.uniform(-0.06, 0.06) * CANVAS_W
    cy = CANVAS_H / 2 + rng.uniform(-0.06, 0.06) * CANVAS_H
    angle = math.radians(rng.uniform(-12, 12) if hard else rng.uniform(-5, 5))
    jitter = (0.09 if hard else 0.04) * cw

    corners = []
    for dx, dy in [(-cw / 2, -ch / 2), (cw / 2, -ch / 2), (cw / 2, ch / 2), (-cw / 2, ch / 2)]:
        x = cx + dx * math.cos(angle) - dy * math.sin(angle) + rng.uniform(-jitter, jitter)
        y = cy + dx * math.sin(angle) + dy * math.cos(angle) + rng.uniform(-jitter, jitter)
        corners.append((x, y))
    src = [(0, 0), (card.width, 0), (card.width, card.height), (0, card.height)]
    warped = card.transform((CANVAS_W, CANVAS_H), Image.PERSPECTIVE,
                            perspective_coeffs(src, corners), Image.BICUBIC)

    # Sombra suave bajo la carta
    shadow = Image.new("L", (CANVAS_W, CANVAS_H), 0)
    ImageDraw.Draw(shadow).polygon([(x + 12, y + 16) for x, y in corners], fill=110)
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))
    bg = Image.composite(Image.new("RGB", bg.size, (0, 0, 0)), bg, shadow)
    bg.paste(warped, (0, 0), warped)
    return bg


def lighting(img, hard, rng):
    img = ImageEnhance.Brightness(img).enhance(rng.uniform(0.5, 1.25) if hard else rng.uniform(0.75, 1.15))
    img = ImageEnhance.Contrast(img).enhance(rng.uniform(0.7, 1.2))
    a = np.asarray(img, np.float32)
    a *= np.array([rng.uniform(0.9, 1.1), 1.0, rng.uniform(0.85, 1.1)], np.float32)  # temperatura de color
    # Luz desigual: degradado en una dirección aleatoria
    yy, xx = np.mgrid[0:CANVAS_H, 0:CANVAS_W].astype(np.float32)
    t = rng.uniform(0, 2 * math.pi)
    ramp = (xx * math.cos(t) + yy * math.sin(t))
    ramp = (ramp - ramp.min()) / (ramp.max() - ramp.min())
    a *= (1 - rng.uniform(0.0, 0.35 if hard else 0.2) * ramp)[:, :, None]
    # Reflejo (típico en fundas y foils)
    if rng.random() < (0.5 if hard else 0.15):
        gx, gy = rng.uniform(0.2, 0.8) * CANVAS_W, rng.uniform(0.2, 0.8) * CANVAS_H
        sx, sy = rng.uniform(80, 300), rng.uniform(40, 200)
        glare = np.exp(-(((xx - gx) / sx) ** 2 + ((yy - gy) / sy) ** 2))
        a += glare[:, :, None] * rng.uniform(80, 200)
    return a.clip(0, 255)


def sensor(a, hard, rng):
    """Desenfoque, movimiento, ruido y compresión JPEG."""
    img = Image.fromarray(a.astype(np.uint8))
    img = img.filter(ImageFilter.GaussianBlur(rng.uniform(0.8, 2.2) if hard else rng.uniform(0.0, 1.0)))
    if hard and rng.random() < 0.3:  # movimiento: suma de copias desplazadas
        n, dx = 5, rng.uniform(-2, 2)
        acc = np.zeros_like(np.asarray(img, np.float32))
        for i in range(n):
            acc += np.asarray(img.transform(img.size, Image.AFFINE, (1, 0, dx * i, 0, 1, dx * i * 0.3)), np.float32)
        img = Image.fromarray((acc / n).astype(np.uint8))
    a = np.asarray(img, np.float32)
    a += np.random.default_rng(rng.randint(0, 1 << 30)).normal(0, rng.uniform(4, 12) if hard else rng.uniform(2, 6), a.shape)
    img = Image.fromarray(a.clip(0, 255).astype(np.uint8))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=rng.randint(60, 80) if hard else rng.randint(75, 92))
    return buf.getvalue()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-group", type=int, default=25)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--hard-ratio", type=float, default=0.4)
    ap.add_argument("--groups", nargs="*", help="solo estos grupos (por defecto todos menos basicas)")
    ap.add_argument("--out", default="synthetic", help="subcarpeta de testset/")
    ap.add_argument("--foreign", type=int, default=0, help="en vez de los grupos, N cartas en otros idiomas (-1: todas)")
    args = ap.parse_args()
    rng = random.Random(args.seed)

    out_dir = os.path.join(ROOT, "testset", args.out)
    os.makedirs(out_dir, exist_ok=True)
    only = args.groups or [g for g, _ in GROUPS if g != "basicas"]
    cards = pick_foreign(args.foreign, rng) if args.foreign else pick_cards(args.per_group, rng, only)
    with open(os.path.join(out_dir, "labels.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["file", "card_id", "name", "set_code", "collector_number", "group", "difficulty", "lang"])
        for i, (group, card_id, name, set_code, number, lang) in enumerate(cards):
            hard = rng.random() < args.hard_ratio
            img = place_card(scan(card_id), background(rng), hard, rng)
            data = sensor(lighting(img, hard, rng), hard, rng)
            fname = f"{i:04d}_{group}.jpg"
            with open(os.path.join(out_dir, fname), "wb") as out:
                out.write(data)
            w.writerow([fname, card_id, name, set_code, number, group, "dificil" if hard else "normal", lang])
            print(f"\r{i + 1}/{len(cards)}", end="", flush=True)
    print(f"\nGeneradas {len(cards)} imágenes en {out_dir}")


if __name__ == "__main__":
    main()
