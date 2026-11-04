#!/usr/bin/env bash
# Gera os pacotes LG webOS (.ipk) e Samsung Tizen (.wgt, sem assinatura).
#   STORE=1 (padrão): build de loja — só login Servidor/Usuário/Senha, sem js/extra.js.
#   STORE=0: build completo (inclui js/extra.js).
# Assinar o .wgt: python3 sign_wgt.py <pasta> <saida.wgt> <arquivo-senha>  (ou tizen package -s <perfil>)
set -e
cd "$(dirname "$0")"
STORE=${STORE:-1}; OUT=$(realpath -m "${1:-dist}"); mkdir -p "$OUT"
STAGE=$(mktemp -d); trap 'rm -rf "$STAGE"' EXIT
cp -r index.html css js img config.xml appinfo.json "$STAGE/"
if [ "$STORE" = 1 ]; then
  rm -f "$STAGE/js/extra.js"; sed -i '/js\/extra.js/d' "$STAGE/index.html"
  sed -i 's/STORE: [a-z]*/STORE: true/' "$STAGE/js/config.js"
else
  sed -i 's/STORE: [a-z]*/STORE: false/' "$STAGE/js/config.js"
fi
( cd "$STAGE" && ares-package . -o "$OUT" -e config.xml )
mkdir -p "$OUT/wgt-src"; rm -rf "$OUT/wgt-src"/*; cp -r "$STAGE"/{index.html,css,js,img,config.xml} "$OUT/wgt-src/"
rm -f "$OUT/HugoPlayer.wgt"; ( cd "$OUT/wgt-src" && python3 -c "import zipfile,os;z=zipfile.ZipFile('../HugoPlayer.wgt','w',zipfile.ZIP_DEFLATED);[z.write(os.path.join(r,f)) for r,_,fs in os.walk('.') for f in fs]" )
ls -la "$OUT"
