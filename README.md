# MTG Scanner

App Android que reconoce cartas de Magic: The Gathering con la cámara (OCR en el
dispositivo, sin conexión), las añade a una colección y calcula su valor con los
precios de Scryfall y de Cardmarket.

- Escaneo continuo: se pasan cartas por delante de la cámara y se añaden solas.
- Edición por el código y número de coleccionista, el tamaño de la edición, el año
  del copyright y, si hace falta, comparando la ilustración y el símbolo de edición.
- Cartas en inglés y en español (incluidas ediciones solo en español, como Salvat).
- Colección con mazos, filtro por colores, búsqueda y revisión rápida de ediciones dudosas.
- Precios en € (tendencia, más bajo o media de 30 días de Cardmarket) o $.
- Exportar (CSV, Moxfield, texto), copia de seguridad e importar CSV de otras apps.

## Estructura

| Carpeta | Contenido |
|---|---|
| `app/` | App Android (Kotlin, Jetpack Compose, CameraX, ML Kit) |
| `app/src/test/` | Tests unitarios del reconocimiento |
| `app/src/androidTest/` | Evaluación con fotos en el emulador y tests de la colección |
| `tools/` | Scripts de Python: catálogo, huellas de ilustraciones y fotos de prueba |

## Compilar

El catálogo de cartas (`app/src/main/assets/catalog.sqlite`, ~36 MB) no está en el
repositorio: se genera a partir de los datos de Scryfall.

```bash
py -3.13 -m venv tools/.venv
tools/.venv/Scripts/python.exe -m pip install pillow numpy pymupdf opencv-python-headless
tools/.venv/Scripts/python.exe tools/build_art_signatures.py   # ilustraciones (~1 h la primera vez)
tools/.venv/Scripts/python.exe tools/build_set_symbols.py      # símbolos de edición (~10 min)
tools/.venv/Scripts/python.exe tools/build_catalog.py          # catálogo (~1 min)
./gradlew assembleDebug
```

`build_art_signatures.py` y `build_set_symbols.py` son opcionales: sin ellos, la app
funciona pero no compara ilustraciones ni símbolos para elegir edición.

## Evaluar el reconocimiento

Con un emulador o móvil conectado:

```bash
tools/.venv/Scripts/python.exe tools/make_testset.py --out synthetic   # fotos simuladas
tools/run_eval.sh synthetic                                            # resultados en build/eval/
tools/.venv/Scripts/python.exe tools/make_sequences.py
tools/run_sequences.sh                                                 # escaneo continuo simulado
```

Datos de cartas e imágenes: [Scryfall](https://scryfall.com). Precios: Scryfall y la
guía de precios pública de [Cardmarket](https://www.cardmarket.com). Magic: The Gathering
es marca de Wizards of the Coast; este proyecto no está afiliado a ninguno de los dos.
