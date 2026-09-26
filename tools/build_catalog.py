"""Genera el catálogo de cartas (SQLite) que va empaquetado dentro de la app.

Uso:
    py -3.13 tools/build_catalog.py            # descarga (si hace falta) y genera
    py -3.13 tools/build_catalog.py --refresh  # fuerza a descargar de nuevo

Salida: app/src/main/assets/catalog.sqlite

Fuentes (ficheros masivos de Scryfall, JSONL comprimido):
  - "default_cards": cada impresión en papel una vez (en inglés si existe; si no,
    en su idioma, p. ej. Fourth Edition Foreign Black Border).
  - "all_cards": solo para los nombres impresos en otros idiomas (--languages),
    que se guardan como alias del nombre inglés: "Ritual siniestro" -> "Dark Ritual".
"""

import argparse
import gzip
import json
import os
import re
import sqlite3
import ssl
import sys
import time
import unicodedata
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(ROOT, "data")
OUT_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "catalog.sqlite")

USER_AGENT = "MTGScanner/0.1 (proyecto personal)"
BULK_INDEX = "https://api.scryfall.com/bulk-data"

# Versión del esquema: la app la compara para saber si debe recopiar el asset.
SCHEMA_VERSION = 6

# Layouts que no son cartas "de colección" normales.
SKIP_LAYOUTS = {"art_series", "token", "double_faced_token", "emblem", "vanguard", "scheme", "planar"}

FINISH_BITS = {"nonfoil": 1, "foil": 2, "etched": 4}


def ssl_context():
    # Python 3.13 activa VERIFY_X509_STRICT, que rechaza los certificados que
    # inyectan algunos antivirus. Se mantiene la verificación normal.
    ctx = ssl.create_default_context()
    ctx.verify_flags &= ~ssl.VERIFY_X509_STRICT
    return ctx


def http_get(url, accept="*/*"):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": accept})
    return urllib.request.urlopen(req, context=ssl_context(), timeout=60)


def download_bulk(kind, refresh):
    os.makedirs(DATA_DIR, exist_ok=True)
    index = json.load(http_get(BULK_INDEX, "application/json"))
    entry = next(b for b in index["data"] if b["type"] == kind)
    url = entry["jsonl_download_uri"]
    path = os.path.join(DATA_DIR, url.rsplit("/", 1)[1])
    if os.path.exists(path) and not refresh:
        print(f"Usando {path} (ya descargado)")
        return path, entry["updated_at"]
    print(f"Descargando {url} ...")
    tmp = path + ".part"
    with http_get(url) as resp, open(tmp, "wb") as f:
        while chunk := resp.read(1 << 20):
            f.write(chunk)
    os.replace(tmp, path)
    return path, entry["updated_at"]


def norm_name(s):
    """Normaliza un nombre para compararlo con el texto del OCR."""
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]", "", s.lower())


def norm_number(s):
    """'0123' -> '123'; las cartas nuevas imprimen ceros a la izquierda."""
    s = s.lower()
    stripped = s.lstrip("0")
    return stripped if stripped else s


def price(prices, key):
    v = prices.get(key)
    return float(v) if v else None


def fetch_sets():
    """Tipo y tamaño impreso de cada edición (no vienen en el fichero de cartas)."""
    data = json.load(http_get("https://api.scryfall.com/sets", "application/json"))["data"]
    return {x["code"]: (x.get("set_type"), x.get("printed_size") or x.get("card_count")) for x in data}


def jsonl(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip().rstrip(",")
            if line and line not in "[]":
                yield line


def foreign_names(all_cards_path, languages):
    """Alias (nombre impreso normalizado -> nombre inglés) en los idiomas pedidos.

    También guarda en data/ una lista de impresiones con escaneo en alta
    resolución por idioma, para generar fotos de prueba (tools/make_testset.py).
    """
    counts = {}
    samples = []
    markers = tuple(f'"lang":"{lang}"' for lang in languages)
    for line in jsonl(all_cards_path):
        if not any(m in line for m in markers):  # filtro rápido antes de parsear
            continue
        c = json.loads(line)
        if c.get("lang") not in languages or c.get("digital") or c.get("layout") in SKIP_LAYOUTS:
            continue
        printed = [c.get("printed_name")] + [f.get("printed_name") for f in c.get("card_faces", [])]
        for pn in filter(None, printed):
            key = (norm_name(pn), c["name"])
            counts[key] = counts.get(key, 0) + 1
        if c.get("image_status") == "highres_scan" and c.get("printed_name"):
            samples.append((c["id"], c["lang"], c["name"], c["printed_name"], c["set"],
                            c["collector_number"], c.get("released_at")))
    with open(os.path.join(DATA_DIR, "foreign_printings.csv"), "w", newline="", encoding="utf-8") as f:
        import csv
        w = csv.writer(f)
        w.writerow(["id", "lang", "name", "printed_name", "set_code", "collector_number", "released_at"])
        w.writerows(samples)
    # Hay algún error en los datos ("Isla" -> Plains en una impresión): por cada
    # nombre traducido se queda la traducción mayoritaria.
    best = {}
    for (norm, name), n in counts.items():
        if norm not in best or n > best[norm][1]:
            best[norm] = (name, n)
    return {(norm, name) for norm, (name, _) in best.items()}


def load_art_signatures():
    """illustration_id -> (id, miniatura) de data/art_signatures.csv, si existe."""
    path = os.path.join(DATA_DIR, "art_signatures.csv")
    if not os.path.exists(path):
        return {}
    import csv
    with open(path, encoding="utf-8") as f:
        return {r["illustration_id"]: (i, bytes.fromhex(r["thumb"]))
                for i, r in enumerate(csv.DictReader(f), 1) if r.get("thumb")}


def build(jsonl_path, updated_at, extra_names):
    arts = load_art_signatures()
    art_ids = {ill: v[0] for ill, v in arts.items()}
    set_info = fetch_sets()
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    tmp = OUT_PATH + ".tmp"
    if os.path.exists(tmp):
        os.remove(tmp)
    db = sqlite3.connect(tmp)
    db.executescript(
        """
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE sets (
            code TEXT PRIMARY KEY,       -- en minúsculas, como Scryfall
            name TEXT NOT NULL,
            released_at TEXT,
            set_type TEXT,               -- core, expansion, masters, promo...
            printed_size INTEGER         -- el "/274" impreso en la carta
        );
        CREATE TABLE cards (
            id TEXT PRIMARY KEY,         -- uuid de Scryfall
            name TEXT NOT NULL,          -- nombre completo ("A // B" en dobles)
            set_code TEXT NOT NULL,
            collector_number TEXT NOT NULL,
            number_norm TEXT NOT NULL,   -- sin ceros a la izquierda
            rarity TEXT,
            released_at TEXT,
            finishes INTEGER NOT NULL,   -- bits: 1 normal, 2 foil, 4 etched
            promo INTEGER NOT NULL,
            lang TEXT NOT NULL,          -- "en" salvo impresiones que no existen en inglés
            usd REAL, usd_foil REAL, eur REAL, eur_foil REAL,
            art_id INTEGER               -- arts.id, solo si la carta tiene varias ilustraciones
        );
        CREATE TABLE arts (
            id INTEGER PRIMARY KEY,
            thumb BLOB NOT NULL          -- 16×12 RGB, 4 bits/canal (tools/build_art_signatures.py)
        );
        CREATE TABLE names (
            norm TEXT NOT NULL,          -- nombre normalizado de una cara
            name TEXT NOT NULL,          -- cards.name al que corresponde
            PRIMARY KEY (norm, name)
        ) WITHOUT ROWID;
        """
    )

    sets = {}
    rows = []
    names = set()
    kept = skipped = 0
    for line in jsonl(jsonl_path):
        c = json.loads(line)
        if (
            c.get("digital")
            or "paper" not in c.get("games", [])
            or c.get("layout") in SKIP_LAYOUTS
            or c.get("set_type") in ("token", "memorabilia", "minigame")
        ):
            skipped += 1
            continue
        kept += 1
        p = c.get("prices", {})
        finishes = sum(FINISH_BITS.get(x, 0) for x in c.get("finishes", []))
        rows.append((
            c["id"], c["name"], c["set"], c["collector_number"],
            norm_number(c["collector_number"]), c.get("rarity"), c.get("released_at"),
            finishes, 1 if c.get("promo") else 0, c.get("lang", "en"),
            price(p, "usd"), price(p, "usd_foil"), price(p, "eur"), price(p, "eur_foil"),
            art_ids.get(c.get("illustration_id") or (c.get("card_faces") or [{}])[0].get("illustration_id")),
        ))
        sets.setdefault(c["set"], (c["set"], c["set_name"], c.get("released_at")))
        # "_____ Goblin" (Unfinity) se normalizaría a "goblin" y casaría con cualquier
        # carta de goblin: los nombres con huecos solo se encuentran a mano o por número.
        if "_" not in c["name"]:
            names.add((norm_name(c["name"]), c["name"]))
            for face in c.get("card_faces", []):
                names.add((norm_name(face["name"]), c["name"]))

    db.executemany(
        "INSERT INTO cards (id,name,set_code,collector_number,number_norm,rarity,released_at,"
        "finishes,promo,lang,usd,usd_foil,eur,eur_foil,art_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        rows,
    )
    db.executemany("INSERT INTO arts VALUES (?,?)", arts.values())
    db.executemany(
        "INSERT INTO sets VALUES (?,?,?,?,?)",
        [(*v, *set_info.get(v[0], (None, None))) for v in sets.values()],
    )
    # Alias en otros idiomas, solo de cartas que están en el catálogo.
    # Un alias nunca tapa un nombre inglés que se escriba igual.
    known = {n for _, n in names}
    english_norms = {norm for norm, _ in names}
    foreign = {(norm, n) for norm, n in extra_names if n in known and norm and norm not in english_norms}
    db.executemany("INSERT OR IGNORE INTO names VALUES (?,?)", names | foreign)
    db.executescript(
        """
        CREATE INDEX cards_set_number ON cards (set_code, number_norm);
        CREATE INDEX cards_name ON cards (name);
        """
    )
    db.executemany(
        "INSERT INTO meta VALUES (?,?)",
        [
            ("schema_version", str(SCHEMA_VERSION)),
            ("built_at", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
            ("prices_updated_at", updated_at),
        ],
    )
    db.commit()
    db.execute("VACUUM")
    db.close()
    os.replace(tmp, OUT_PATH)

    size_mb = os.path.getsize(OUT_PATH) / 1e6
    print(f"Cartas: {kept} (descartadas {skipped}) | ediciones: {len(sets)} | nombres: {len(names)}"
          f" + {len(foreign)} alias en otros idiomas | huellas de ilustración: {len(arts)}")
    print(f"Generado {OUT_PATH} ({size_mb:.1f} MB)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true", help="volver a descargar los datos")
    ap.add_argument("--languages", default="es",
                    help="idiomas de los nombres alternativos, separados por comas ('' para ninguno)")
    args = ap.parse_args()
    path, updated_at = download_bulk("default_cards", args.refresh)
    languages = [x for x in args.languages.split(",") if x]
    extra = foreign_names(download_bulk("all_cards", args.refresh)[0], languages) if languages else set()
    build(path, updated_at, extra)


if __name__ == "__main__":
    sys.exit(main())
