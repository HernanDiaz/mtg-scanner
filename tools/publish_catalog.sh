#!/bin/bash
# Sube el catálogo (app/src/main/assets/catalog.sqlite, generado con build_catalog.py) a la
# release "catalog" de GitHub, de donde lo descarga el workflow de iOS (no está en el
# repositorio: ocupa ~43 MB). Repetir cada vez que se regenere el catálogo.
set -e
cd "$(dirname "$0")/.."
f=app/src/main/assets/catalog.sqlite
if ! gh release view catalog >/dev/null 2>&1; then
    gh release create catalog --title "Catálogo de cartas" --latest=false \
        --notes "catalog.sqlite para compilar la app de iPhone (lo genera tools/build_catalog.py)."
fi
gh release upload catalog "$f" --clobber
echo "Subido: $(du -h "$f" | cut -f1)"
