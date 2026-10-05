"""Comprueba las traducciones de shared/src/commonMain/composeResources: que cada idioma
tenga todas las claves del inglés, los mismos marcadores (%1$s, %2$d...) y un XML válido.
Uso: python tools/check_translations.py"""
import os, re, sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(__file__), '..', 'shared', 'src', 'commonMain', 'composeResources')
OPTIONAL = {'app_name'}  # igual en todos los idiomas


def load(folder):
    tree = ET.parse(os.path.join(ROOT, folder, 'strings.xml'))
    out = {}
    for el in tree.getroot():
        name = el.get('name')
        if el.tag == 'string':
            out[name] = [el.text or '']
        elif el.tag == 'plurals':
            out[name] = [item.text or '' for item in el]
    return out


def marks(texts):
    return sorted({m for t in texts for m in re.findall(r'%\d+\$[sd]', t)})


def main():
    base = load('values')
    ok = True
    for folder in sorted(os.listdir(ROOT)):
        if not folder.startswith('values-'):
            continue
        tr = load(folder)
        missing = sorted(set(base) - set(tr) - OPTIONAL)
        extra = sorted(set(tr) - set(base))
        bad = [k for k in base if k in tr and marks(base[k]) != marks(tr[k])]
        status = 'ok' if not (missing or extra or bad) else 'MAL'
        print(f'{folder}: {len(tr)} textos, {status}')
        for label, keys in (('faltan', missing), ('sobran', extra), ('marcadores distintos', bad)):
            if keys:
                ok = False
                print(f'  {label}: {", ".join(keys[:20])}')
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main()
