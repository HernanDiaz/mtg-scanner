"""Huellas de las ilustraciones, para distinguir ediciones por la imagen.

Solo hacen falta para las cartas con más de una ilustración (~25.000): si todas
sus impresiones comparten dibujo, la imagen no ayuda a elegir edición.

Uso:
    tools/.venv/Scripts/python.exe tools/build_art_signatures.py

Descarga los "art_crop" de Scryfall a data/art/ (con caché) y escribe
data/art_signatures.csv (illustration_id, phash, color). build_catalog.py los
incorpora al catálogo si existe ese fichero.

Lo que usa la app es "thumb": una miniatura de 16×12 en color de la ilustración
(recortada un 4 % por cada lado para quitar el borde), con 4 bits por canal:
288 bytes. La app la compara por correlación con la foto (scan/ArtSignature.kt).
Se probó también un pHash (columnas phash/color): con fotos acierta bastante
menos (64 % frente a 84 %), porque es muy sensible a giros y recortes.
"""

import csv
import gzip
import json
import math
import os
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_catalog import DATA_DIR, SKIP_LAYOUTS, download_bulk, http_get, jsonl  # noqa: E402

ART_DIR = os.path.join(DATA_DIR, "art")
OUT = os.path.join(DATA_DIR, "art_signatures.csv")

GRID = 32
SAMPLES = 4
DCT = 8

# cos((2x+1)uπ/2N) para u < 8, x < 32
_COS = np.array([[math.cos((2 * x + 1) * u * math.pi / (2 * GRID)) for x in range(GRID)] for u in range(DCT)])


def signature(img):
    """(phash como entero de 63 bits, 12 bytes de color) de una imagen PIL RGB completa."""
    a = np.asarray(img.convert("RGB"), dtype=np.float64)
    h, w = a.shape[:2]
    # Muestras en ((c + (s + 0.5) / SAMPLES) / GRID) de la anchura/altura, igual que en Kotlin.
    pos = (np.arange(GRID)[:, None] + (np.arange(SAMPLES)[None, :] + 0.5) / SAMPLES) / GRID
    xs = np.minimum((pos.reshape(-1) * w).astype(int), w - 1)
    ys = np.minimum((pos.reshape(-1) * h).astype(int), h - 1)
    samples = a[ys][:, xs]  # (GRID*S, GRID*S, 3)
    cells = samples.reshape(GRID, SAMPLES, GRID, SAMPLES, 3).mean(axis=(1, 3))  # (32, 32, 3)
    gray = cells @ np.array([0.299, 0.587, 0.114])
    coef = _COS @ gray @ _COS.T  # (8, 8): filas = v (vertical), columnas = u (horizontal)
    ac = coef.reshape(-1)[1:]
    med = np.median(ac)
    bits = 0
    for i, c in enumerate(ac):
        if c > med:
            bits |= 1 << i
    half = GRID // 2
    color = bytes(int(round(cells[y0:y0 + half, x0:x0 + half, ch].mean()))
                  for y0 in (0, half) for x0 in (0, half) for ch in range(3))
    return bits, color


THUMB_W, THUMB_H = 16, 12
THUMB_INSET = 0.04


def thumb(img):
    """Miniatura 16×12 RGB, 4 bits por canal, empaquetada (2 valores por byte): 288 bytes."""
    w, h = img.size
    box = (int(w * THUMB_INSET), int(h * THUMB_INSET), int(w * (1 - THUMB_INSET)), int(h * (1 - THUMB_INSET)))
    a = np.asarray(img.convert("RGB").crop(box).resize((THUMB_W, THUMB_H), Image.BOX), dtype=np.uint8)
    q = (a >> 4).reshape(-1)  # fila a fila, R G B
    return bytes((int(q[i]) << 4) | int(q[i + 1]) for i in range(0, len(q), 2))


def wanted_illustrations():
    """illustration_id -> url del art_crop, solo de nombres con varias ilustraciones."""
    path, _ = download_bulk("default_cards", False)
    by_name = {}
    urls = {}
    for line in jsonl(path):
        c = json.loads(line)
        if c.get("digital") or "paper" not in c.get("games", []) or c.get("layout") in SKIP_LAYOUTS:
            continue
        face = (c.get("card_faces") or [{}])[0]
        ill = c.get("illustration_id") or face.get("illustration_id")
        uris = c.get("image_uris") or face.get("image_uris") or {}
        if not ill or "art_crop" not in uris:
            continue
        by_name.setdefault(c["name"], set()).add(ill)
        urls.setdefault(ill, uris["art_crop"])
    return {ill: urls[ill] for ills in by_name.values() if len(ills) > 1 for ill in ills}


def main():
    os.makedirs(ART_DIR, exist_ok=True)
    wanted = wanted_illustrations()
    print(f"Ilustraciones necesarias: {len(wanted)}")

    done = {}
    if os.path.exists(OUT):
        with open(OUT, encoding="utf-8") as f:
            done = {r["illustration_id"]: r for r in csv.DictReader(f) if r.get("thumb")}
    todo = [i for i in wanted if i not in done]
    print(f"Ya calculadas: {len(done)}; pendientes: {len(todo)}")

    lock = threading.Lock()
    results = dict(done)
    failed = []
    t0 = time.time()

    def work(ill):
        path = os.path.join(ART_DIR, f"{ill}.jpg")
        for attempt in range(3):
            try:
                if not os.path.exists(path):
                    with http_get(wanted[ill]) as r, open(path + ".part", "wb") as f:
                        f.write(r.read())
                    os.replace(path + ".part", path)
                    time.sleep(0.05)  # ritmo respetuoso con la CDN de Scryfall
                img = Image.open(path)
                bits, color = signature(img)
                t = thumb(img)
                with lock:
                    results[ill] = {"illustration_id": ill, "phash": f"{bits:016x}", "color": color.hex(), "thumb": t.hex()}
                return
            except Exception as e:  # red o fichero corrupto: reintentar
                if os.path.exists(path):
                    os.remove(path)
                if attempt == 2:
                    with lock:
                        failed.append((ill, str(e)))
                time.sleep(1 + attempt)

    with ThreadPoolExecutor(max_workers=6) as pool:
        for n, _ in enumerate(pool.map(work, todo), 1):
            if n % 500 == 0:
                rate = n / (time.time() - t0)
                print(f"  {n}/{len(todo)}  ({rate:.1f}/s, faltan ~{(len(todo) - n) / rate / 60:.0f} min)", flush=True)
                with lock:
                    snapshot = dict(results)
                write(snapshot)
    write(results)
    print(f"Hecho: {len(results)} huellas; fallidas: {len(failed)}")
    for ill, err in failed[:10]:
        print("  ", ill, err)


def write(results):
    with open(OUT + ".tmp", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["illustration_id", "phash", "color", "thumb"])
        w.writeheader()
        w.writerows(results.values())
    os.replace(OUT + ".tmp", OUT)


if __name__ == "__main__":
    main()
