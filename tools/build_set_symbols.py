"""Símbolos de edición de referencia, para decidir la edición por la imagen.

Para cada edición y rareza descarga la imagen de una carta de Scryfall, busca
en ella el símbolo (a la derecha de la línea de tipo) con el icono SVG de la
edición y guarda el recorte en color. Así la referencia es el símbolo tal como
se imprime (con el color de la rareza y su borde), no el dibujo plano del icono.

Si el icono de la edición no aparece en sus cartas es que esa edición imprime
el símbolo de otra (Chronicles, The List, las colecciones Salvat...) o ninguno
(Alpha, Cuarta Edición...): se anota para no decidir entre ellas por el símbolo.

Resultado: data/set_symbols.json, que build_catalog.py guarda en el catálogo.

Uso: python tools/build_set_symbols.py
Requiere PyMuPDF (dibujar los SVG) y OpenCV:  pip install pymupdf opencv-python-headless
"""
import glob
import json
import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor

import cv2
import numpy as np
import pymupdf
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from build_catalog import DATA_DIR, http_get, jsonl, symbol_style  # noqa: E402

ICONS = os.path.join(DATA_DIR, "set_icons")
REFS = os.path.join(DATA_DIR, "symbol_refs")
OUT = os.path.join(DATA_DIR, "set_symbols.json")

# Todo se mide con la carta a este ancho (px). En la app, igual.
CARD_W = 400
# Dónde buscar el símbolo según el marco (fracciones de la carta: x0, y0, x1, y1).
WINDOWS = {
    "2015": (0.78, 0.53, 0.98, 0.65), "2003": (0.76, 0.53, 0.97, 0.64),
    "1997": (0.72, 0.52, 0.96, 0.63), "1993": (0.68, 0.51, 0.98, 0.64),
    "future": (0.75, 0.50, 0.98, 0.66),
}
# Ediciones cuyas cartas no llevan símbolo de edición.
NO_SYMBOL = {"lea", "leb", "2ed", "3ed", "4ed", "5ed", "sum", "ced", "cei", "fbb", "4bb", "ren", "rin"}
# The List lleva el símbolo de la edición original (y una marca aparte).
OTHER_SYMBOL = {"plst"}
# Por debajo, el "símbolo" localizado a menudo es un trozo del marco (medido a ojo).
LOCATE_MIN = 0.7
VALIDATE_MIN = 0.7
SINGLE_MIN = 0.75
# Alto mínimo del símbolo (px con la carta a CARD_W) y contraste mínimo del recorte.
MIN_HEIGHT = 13
MIN_CONTRAST = 0.08
TRIES = 3


def svg_mask(path):
    """Icono como máscara 0-1 (1 = símbolo), recortado a su contenido, a 128 px."""
    page = pymupdf.open(path)[0]
    zoom = 128 / max(page.rect.width, page.rect.height)
    pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), alpha=True)
    rgba = np.frombuffer(pix.samples, np.uint8).reshape(pix.height, pix.width, pix.n).astype(np.float32)
    # Algunos iconos llevan huecos pintados en blanco en vez de transparentes.
    cover = rgba[..., 3] / 255 * (1 - rgba[..., :3].mean(axis=2) / 255)
    ys, xs = np.nonzero(cover > 0.08)
    if len(xs) == 0:
        return None
    return cover[ys.min():ys.max() + 1, xs.min():xs.max() + 1]


def locate(window_gray, mask):
    """(puntuación, (x, y, ancho, alto)) del icono en la ventana, probando tamaños."""
    best = (-1.0, None)
    # Por altura: los símbolos miden 3-7 % del ancho de la carta de alto; los hay muy anchos ("M14").
    for height in range(MIN_HEIGHT, 29):
        h, w = mask.shape
        k = min(height / h, 60 / w)
        t = cv2.resize(mask, (max(3, round(w * k)), max(3, round(h * k))), interpolation=cv2.INTER_AREA)
        if t.shape[0] > window_gray.shape[0] or t.shape[1] > window_gray.shape[1] or t.std() < 1e-3:
            continue
        r = np.nan_to_num(cv2.matchTemplate(window_gray, t, cv2.TM_CCOEFF_NORMED))
        for sign in (1, -1):  # símbolo oscuro sobre claro o al revés
            v = float((sign * r).max())
            if v > best[0]:
                y, x = np.unravel_index(np.argmax(sign * r), r.shape)
                best = (v, (int(x), int(y), t.shape[1], t.shape[0]))
    return best


def card_window(image_path, style):
    """Zona del símbolo de una carta (RGB 0-1), con la carta a CARD_W px."""
    im = Image.open(image_path).convert("RGB")
    im = im.resize((CARD_W, round(CARD_W * im.height / im.width)), Image.LANCZOS)
    x0, y0, x1, y1 = WINDOWS[style.rstrip("x")]
    return np.asarray(im.crop((int(x0 * CARD_W), int(y0 * im.height), int(x1 * CARD_W), int(y1 * im.height))),
                      np.float32) / 255


def crop_symbol(win, mask):
    """(puntuación, centro, recorte) del símbolo localizado con el icono; recorte None si no se encuentra.

    Un recorte casi liso (fondo) no sirve: se parece a cualquier otra zona lisa."""
    score, box = locate(win.mean(axis=2), mask)
    if score < LOCATE_MIN:
        return score, None, None
    x, y, w, h = box
    pad = 2
    crop = win[max(0, y - pad):y + h + pad, max(0, x - pad):x + w + pad]
    if crop.mean(axis=2).std() < MIN_CONTRAST:
        return score, None, None
    return score, (x + w / 2, y + h / 2), crop


def match(win, crop):
    """Correlación del recorte dentro de otra ventana, con un poco de margen de tamaño."""
    best = -1.0
    for k in (0.9, 0.95, 1.0, 1.05, 1.1):
        t = cv2.resize(crop, (max(3, round(crop.shape[1] * k)), max(3, round(crop.shape[0] * k))),
                       interpolation=cv2.INTER_AREA)
        if t.shape[0] <= win.shape[0] and t.shape[1] <= win.shape[1]:
            best = max(best, float(np.nan_to_num(cv2.matchTemplate(win, t, cv2.TM_CCOEFF_NORMED)).max()))
    return best


def candidates(default_cards):
    """Hasta TRIES cartas por (edición, rareza, estilo), las más normales primero."""
    found = {}
    for line in jsonl(default_cards):
        c = json.loads(line)
        uris = c.get("image_uris")
        if not uris or c.get("frame") not in WINDOWS or c.get("layout") not in ("normal", "leveler", "class", "adventure"):
            continue
        penalty = (c.get("promo", False), c.get("lang") != "en")
        found.setdefault((c["set"], c["rarity"], symbol_style(c)), []).append((penalty, c["id"], uris["normal"]))
    return {k: sorted(v)[:TRIES] for k, v in found.items()}


def download(url, path):
    if not os.path.exists(path):
        with http_get(url) as r, open(path + ".part", "wb") as f:
            f.write(r.read())
        os.replace(path + ".part", path)
    return path


def main():
    os.makedirs(ICONS, exist_ok=True)
    os.makedirs(REFS, exist_ok=True)
    sets = json.load(http_get("https://api.scryfall.com/sets", "application/json"))["data"]
    # Prefijo "i_": "con.svg" es un nombre reservado en Windows.
    icon_file = {s["code"]: "i_" + re.sub(r"\?.*", "", s["icon_svg_uri"].rsplit("/", 1)[-1])
                 for s in sets if s.get("icon_svg_uri")}
    urls = {icon_file[s["code"]]: s["icon_svg_uri"] for s in sets if s.get("icon_svg_uri")}
    with ThreadPoolExecutor(6) as pool:
        list(pool.map(lambda kv: download(kv[1], os.path.join(ICONS, kv[0])), urls.items()))
    masks = {name: svg_mask(os.path.join(ICONS, name)) for name in urls}

    default_cards = sorted(glob.glob(os.path.join(DATA_DIR, "default-cards-*.jsonl.gz")))[-1]
    todo = {k: v for k, v in candidates(default_cards).items() if k[0] not in NO_SYMBOL | OTHER_SYMBOL}
    print(f"{len(todo)} combinaciones de edición, rareza y estilo")

    def process(item):
        """Recorte validado: el símbolo de una carta tiene que aparecer igual en otra de la
        misma edición, rareza y estilo (descarta recortes mal localizados)."""
        (set_code, rarity, style), cards = item
        mask = masks.get(icon_file.get(set_code))
        if mask is None:
            return item[0], None
        windows = []
        for _, card_id, url in cards:
            try:
                windows.append(card_window(download(url, os.path.join(REFS, card_id + ".jpg")), style))
            except Exception as e:  # noqa: BLE001 - una imagen que falla no para el resto
                print("  error", card_id, e)
        found = [crop_symbol(w, mask) for w in windows]
        # La mejor localización primero.
        for i in sorted(range(len(found)), key=lambda k: -found[k][0]):
            score, center, crop = found[i]
            if crop is None:
                continue
            # Otra carta en la que el icono también se encuentra, en el mismo sitio,
            # y donde este recorte coincide bien.
            confirmed = any(
                other[2] is not None and abs(other[1][0] - center[0]) <= 4 and abs(other[1][1] - center[1]) <= 4
                and match(windows[j], crop) >= VALIDATE_MIN
                for j, other in enumerate(found) if j != i
            )
            # Sin otra carta con que comparar: solo si el icono se encontró con mucha claridad.
            if confirmed or (len(windows) == 1 and score >= SINGLE_MIN):
                return item[0], (crop, score)
        return item[0], None

    with ThreadPoolExecutor(6) as pool:
        results = dict(pool.map(process, sorted(todo.items())))

    refs = {"|".join(k): {"w": int(v.shape[1]), "h": int(v.shape[0]), "score": round(sc, 3),
                          "rgb": (v * 255).round().astype(np.uint8).tobytes().hex()}
            for k, (v, sc) in ((k, r) for k, r in results.items() if r is not None)}
    # Edición que no imprime su propio símbolo: no se encontró en ninguna de sus cartas.
    own = {k[0] for k, v in results.items() if v is not None}
    other = sorted(({k[0] for k in results} - own) | OTHER_SYMBOL)
    with open(OUT, "w") as f:
        json.dump({"card_width": CARD_W, "refs": refs, "icons": icon_file, "no_symbol": sorted(NO_SYMBOL),
                   "other_symbol": other}, f)
    print(f"{len(refs)} referencias; {len(other)} ediciones sin su propio símbolo -> {OUT}")


if __name__ == "__main__":
    main()
