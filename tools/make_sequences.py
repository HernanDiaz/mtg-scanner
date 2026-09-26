"""Genera secuencias de fotogramas que simulan escanear cartas una tras otra,
para probar las transiciones: cuándo se añade una carta, que no se duplique,
que no se pierda la siguiente...

Uso:
    tools/.venv/Scripts/python.exe tools/make_sequences.py [--seed 7]

Salida: testset/secuencias/<escenario>/ con los fotogramas, frames.csv
(fichero, instante en ms) y expected.csv (cartas que deberían añadirse, en orden).
"""

import argparse
import csv
import os
import random
import shutil
import sqlite3
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_catalog import OUT_PATH as CATALOG, ROOT  # noqa: E402
from make_testset import CANVAS_H, CANVAS_W, background, lighting, place_card, scan, sensor  # noqa: E402

OUT_DIR = os.path.join(ROOT, "testset", "secuencias")

# Un móvil procesa ~5 fotogramas por segundo a 1080p.
FRAME_MS = 200


class Sequence:
    def __init__(self, name, rng):
        self.name = name
        self.rng = rng
        self.dir = os.path.join(OUT_DIR, name)
        os.makedirs(self.dir, exist_ok=True)
        self.table = background(rng)  # la misma mesa en toda la secuencia
        self.frames = []
        self.expected = []
        self.distractor = None

    def _save(self, img_or_array, hard):
        data = sensor(img_or_array if isinstance(img_or_array, np.ndarray) else np.asarray(img_or_array, np.float32),
                      hard, self.rng)
        fname = f"f{len(self.frames):03d}.jpg"
        with open(os.path.join(self.dir, fname), "wb") as f:
            f.write(data)
        self.frames.append((fname, len(self.frames) * FRAME_MS))

    def _table(self):
        bg = self.table.copy()
        if self.distractor is not None:
            # Otra carta asomando por arriba, fuera del marco guía (un mazo, la carta anterior...).
            d = self.distractor.resize((int(CANVAS_W * 0.62), int(CANVAS_W * 0.62 * 88 / 63)))
            d = d.rotate(self.rng.uniform(-6, 6), expand=True, resample=Image.BICUBIC)
            bg.paste(d, (int(CANVAS_W * 0.18), int(CANVAS_H * 0.01)), d)  # su nombre queda visible sobre el marco
        return bg

    def card(self, card, frames, hard_ratio=0.15, expect=True):
        """La carta quieta en el marco durante varios fotogramas (con el temblor del pulso)."""
        card_id, name = card
        img = scan(card_id)
        for _ in range(frames):
            hard = self.rng.random() < hard_ratio
            self._save(lighting(place_card(img, self._table(), hard, self.rng), hard, self.rng), hard)
        if expect:
            self.expected.append((name, card_id))

    def hand(self, frames):
        """La mano cambiando la carta: tapa el marco o lo deja vacío, con movimiento."""
        for _ in range(frames):
            bg = self._table()
            if self.rng.random() < 0.6:
                d = ImageDraw.Draw(bg)
                cx, cy = CANVAS_W * self.rng.uniform(0.3, 0.7), CANVAS_H * self.rng.uniform(0.35, 0.65)
                rx, ry = CANVAS_W * self.rng.uniform(0.35, 0.5), CANVAS_H * self.rng.uniform(0.25, 0.35)
                skin = tuple(int(c * self.rng.uniform(0.8, 1.1)) for c in (205, 160, 130))
                d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=skin)
                bg = bg.filter(ImageFilter.GaussianBlur(6))
            self._save(lighting(bg, True, self.rng), True)

    def write(self):
        with open(os.path.join(self.dir, "frames.csv"), "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["file", "t_ms"])
            w.writerows(self.frames)
        with open(os.path.join(self.dir, "expected.csv"), "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["name", "card_id"])
            w.writerows(self.expected)
        print(f"{self.name}: {len(self.frames)} fotogramas, {len(self.expected)} cartas esperadas")


def cards(db, where, n, rng):
    rows = db.execute(f"SELECT id, name FROM cards WHERE promo = 0 AND {where} ORDER BY id").fetchall()
    return rng.sample(rows, n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    db = sqlite3.connect(CATALOG)
    if os.path.exists(OUT_DIR):
        shutil.rmtree(OUT_DIR)

    modern = "released_at >= '2015-01-01' AND name NOT LIKE '%//%' AND name NOT IN " \
             "('Plains','Island','Swamp','Mountain','Forest')"

    # 1. Cartas distintas una tras otra, cambio normal con la mano.
    s = Sequence("distintas_seguidas", rng)
    for c in cards(db, modern, 6, rng):
        s.card(c, 5)
        s.hand(2)
    s.write()

    # 2. Cambio rápido: la carta nueva tapa a la anterior sin que el marco quede vacío.
    s = Sequence("cambio_sin_pausa", rng)
    for c in cards(db, modern, 5, rng):
        s.card(c, 4)
    s.write()

    # 3. Copias idénticas (misma impresión): hay que retirar y volver a poner.
    swamp = cards(db, "name = 'Swamp' AND set_code = 'm20'", 1, rng)[0]
    s = Sequence("copias_identicas", rng)
    for gap in (4, 3, 2, 5):  # pausas de 800, 600, 400 y 1000 ms
        s.card(swamp, 5)
        s.hand(gap)
    s.card(swamp, 5)
    s.write()

    # 4. Pantanos de ediciones distintas, sin pausa.
    s = Sequence("pantanos_distintos", rng)
    for c in cards(db, "name = 'Swamp' AND released_at >= '2015-01-01'", 4, rng):
        s.card(c, 5)
        s.hand(1)
    s.write()

    # 5. Una carta quieta mucho rato (con fotogramas malos): debe contarse una sola vez.
    s = Sequence("carta_quieta", rng)
    s.card(cards(db, modern, 1, rng)[0], 30, hard_ratio=0.4)
    s.write()

    # 6. Otra carta asomando por arriba todo el rato (un mazo junto al marco).
    s = Sequence("con_carta_al_lado", rng)
    s.distractor = scan(cards(db, modern, 1, rng)[0][0])
    for c in cards(db, modern, 4, rng):
        s.card(c, 5)
        s.hand(2)
    s.write()


if __name__ == "__main__":
    main()
