#!/usr/bin/env bash
# Gera os pacotes: LG webOS (.ipk, via ares-package) e Samsung Tizen (.wgt).
# O .wgt sai sem assinatura: para instalar numa TV Samsung, assine no Tizen Studio com o
# certificado Samsung (autor + distribuidor) da sua conta Samsung: tizen package -t wgt -s <perfil>.
set -e
cd "$(dirname "$0")"
OUT=${1:-dist}; mkdir -p "$OUT"
FILES="index.html css js img"
ares-package . -o "$OUT" -e config.xml -e build.sh -e README.md -e dist
rm -f "$OUT/HugoPlayer.wgt"; python3 -c "import sys,zipfile,os;z=zipfile.ZipFile(sys.argv[1],'w',zipfile.ZIP_DEFLATED);[z.write(os.path.join(r,f)) for p in sys.argv[2:] for r,_,fs in (os.walk(p) if os.path.isdir(p) else [('',0,[p])]) for f in fs]" "$OUT/HugoPlayer.wgt" config.xml $FILES
ls -la "$OUT"
