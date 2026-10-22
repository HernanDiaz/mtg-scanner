# MTG Scanner

**Card Scanner for MTG** on the App Store · Android and iPhone

MTG Scanner recognises physical Magic: The Gathering cards with the camera, entirely on
the device and offline, adds them to a collection and values it with Cardmarket and
Scryfall prices. There is no account and no server: the collection stays on the phone.

- **Continuous scanning**: no buttons to press. Cards are added as soon as they are read
  and the next one can be placed right away. A clear reading is accepted in the first
  camera frame, and text recognition only processes the area of the on-screen guide.
- **Exact printing, not just the name**: the edition is found from the set code and
  collector number when the card has them, and otherwise from the copyright year, the
  language, the border colour (black vs white: 4th vs 5th Edition, Revised vs Unlimited),
  the set symbol and a comparison of the artwork with reference signatures.
- **Old and non-English cards**: English, Spanish, French, German, Italian and Portuguese,
  including printings that only exist in some languages (for example the black-bordered
  Spanish 4th Edition).
- **Review uncertain editions**: when the printing is not certain, the card is flagged and
  can be confirmed with the photo taken during the scan next to the candidates.
- **Collection**: decks, condition and foil, wishlist, value history, colour and format
  filters, prices in € (Cardmarket trend, lowest or 30-day average) or $.
- **Import, export and backups**: CSV from Moxfield, ManaBox, Deckbox and TCGplayer;
  CSV/Moxfield/text export; manual and automatic backups.
- **10 app languages**, selectable inside the app.

Support and privacy policy: https://hernandiaz.github.io/mtg-scanner-web/

## Project structure

Kotlin Multiplatform: the recognition engine, data layer and the whole user interface
(Compose Multiplatform) are shared between Android and iOS.

| Folder | Contents |
|---|---|
| `shared/src/commonMain/.../scan/` | Recognition engine: `FrameRecognizer` (name, edition, language, border, set symbol, artwork), `ScanSession` (when a card counts as read), `GuideRegion`, `ArtSignature`, `SetSymbols` |
| `shared/src/commonMain/.../data/` | Card catalogue, name matching, collection database, prices, import/export, backups |
| `shared/src/commonMain/.../ui/` | Shared Compose UI: scanner, collection, card details, wishlist, value history |
| `shared/src/androidMain/` | Android camera (CameraX) and text recognition (ML Kit) |
| `shared/src/iosMain/` | iOS camera (AVFoundation) and text recognition (Vision) |
| `shared/src/commonTest/` | Recognition unit tests |
| `app/` | Android app; `app/src/androidTest/` has the photo-based evaluations |
| `iosui/`, `iosApp/` | iOS framework and Xcode project (generated with XcodeGen) |
| `tools/` | Python tools: card catalogue, artwork and set-symbol signatures, test sets, evaluation |
| `.github/workflows/` | iOS build and TestFlight upload (`ios.yml`), shared code tests on iOS (`shared.yml`) |

## Building

The card catalogue (`app/src/main/assets/catalog.sqlite`, ~43 MB) is not in the
repository: it is generated from the public [Scryfall](https://scryfall.com) data.

```bash
py -3.13 -m venv tools/.venv
tools/.venv/Scripts/python.exe -m pip install pillow numpy pymupdf opencv-python-headless
tools/.venv/Scripts/python.exe tools/build_art_signatures.py   # artwork signatures (~1 h the first time)
tools/.venv/Scripts/python.exe tools/build_set_symbols.py      # set symbols (~10 min)
tools/.venv/Scripts/python.exe tools/build_catalog.py          # catalogue (~1 min)
./gradlew assembleDebug                                        # Android
./gradlew :shared:testAndroidHostTest                          # unit tests
```

`build_art_signatures.py` and `build_set_symbols.py` are optional: without them the app
works but does not compare artwork or set symbols to choose the edition.

The iPhone app is built on GitHub Actions (`ios.yml`), which downloads the catalogue from
the `catalog` release (`tools/publish_catalog.sh`) and uploads the build to TestFlight.

## Evaluating recognition

Recognition is measured on real photos of cards scanned with the app (a debug-only test
mode saves each photo with what was recognised; every photo is then reviewed by hand) and
on reference images generated from Scryfall. With an emulator or phone connected:

```bash
tools/.venv/Scripts/python.exe tools/make_testset.py --out synthetic   # simulated photos
tools/run_eval.sh synthetic                                            # results in build/eval/
tools/.venv/Scripts/python.exe tools/make_sequences.py
tools/run_sequences.sh                                                 # simulated continuous scanning
```

The photo sets themselves are not in the repository (they are photos of a private
collection).

## Credits

Card data and images: [Scryfall](https://scryfall.com). Prices: Scryfall and the public
[Cardmarket](https://www.cardmarket.com) price guide.

MTG Scanner is unofficial Fan Content permitted under the Fan Content Policy. Not
approved/endorsed by Wizards. Portions of the materials used are property of Wizards of
the Coast. © Wizards of the Coast LLC.
