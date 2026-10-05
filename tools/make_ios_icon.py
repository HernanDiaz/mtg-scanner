"""Icono de la app de iPhone (1024x1024) con el mismo dibujo que el de Android
(app/src/main/res/drawable/ic_launcher_foreground.xml, lienzo de 108).

iOS no recorta el icono como Android (que muestra la zona central de 72 de 108):
se dibuja esa zona central a todo el cuadrado. Uso:
    python tools/make_ios_icon.py
"""
import os
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(__file__), '..', 'iosApp', 'Resources', 'Assets.xcassets',
                   'AppIcon.appiconset', 'icon-1024.png')
SIZE = 1024
SS = 4  # supermuestreo para bordes suaves
VIEW = (18, 18, 90, 90)  # zona visible de un icono adaptativo de Android
K = SIZE * SS / (VIEW[2] - VIEW[0])


def p(x, y):
    return ((x - VIEW[0]) * K, (y - VIEW[1]) * K)


def rgba(hex_color, alpha=1.0):
    h = hex_color.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (round(255 * alpha),)


def main():
    img = Image.new('RGBA', (SIZE * SS, SIZE * SS), rgba('#1B2230'))
    d = ImageDraw.Draw(img, 'RGBA')
    # Carta 63:88 con esquinas redondeadas
    d.rounded_rectangle([p(40, 33), p(68, 73)], radius=3 * K, fill=rgba('#F2C14E'))
    d.rectangle([p(43, 35.5), p(65, 38.5)], fill=rgba('#5B4513'))  # barra del título
    d.rectangle([p(43, 40.5), p(65, 55)], fill=rgba('#2F5D8A'))  # ilustración
    d.polygon([p(43, 55), p(50, 46), p(55, 51), p(59, 47), p(65, 53), p(65, 55)], fill=rgba('#4E86BA'))
    for box in ([44, 58.5, 64, 60], [44, 62, 64, 63.5], [44, 65.5, 58, 67]):  # caja de texto
        d.rectangle([p(box[0], box[1]), p(box[2], box[3])], fill=rgba('#5B4513', 0.55))
    # Esquinas del visor
    teal = rgba('#4FD1C5')
    for pts in ([(33, 35), (33, 27), (41, 27)], [(67, 27), (75, 27), (75, 35)],
                [(75, 71), (75, 79), (67, 79)], [(41, 79), (33, 79), (33, 71)]):
        d.line([p(*q) for q in pts], fill=teal, width=round(3 * K), joint='curve')
        for q in (pts[0], pts[-1]):  # extremos redondeados
            r = 1.5 * K
            x, y = p(*q)
            d.ellipse([x - r, y - r, x + r, y + r], fill=teal)
    # Línea de escaneo
    teal_line = rgba('#4FD1C5', 0.9)
    d.line([p(31, 51), p(77, 51)], fill=teal_line, width=round(2.5 * K))
    for q in ((31, 51), (77, 51)):
        r = 1.25 * K
        x, y = p(*q)
        d.ellipse([x - r, y - r, x + r, y + r], fill=teal_line)
    # Sin transparencia: App Store la rechaza en el icono
    icon = img.resize((SIZE, SIZE), Image.LANCZOS).convert('RGB')
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    icon.save(OUT)
    print('icono:', os.path.abspath(OUT))


if __name__ == '__main__':
    main()
