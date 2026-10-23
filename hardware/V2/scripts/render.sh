#!/bin/sh
# render.sh <pcb> <out.png> <layers> [dpi]
S=$(mktemp -d)
kicad-cli pcb export pdf --layers "$3" -o $S/_r.pdf "$1" >/dev/null 2>&1 && pdftoppm -png -r ${4:-500} -singlefile $S/_r.pdf $S/_r && python3 -c "
from PIL import Image, ImageOps
im=Image.open('$S/_r.png').convert('RGB'); bb=ImageOps.invert(im).getbbox(); im.crop((bb[0]-10,bb[1]-10,bb[2]+10,bb[3]+10)).save('$2')"
