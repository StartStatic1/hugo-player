# Hugo Player — servidor de revisão (lojas Samsung/LG)

API compatível com Xtream (`player_api.php`, `get.php`, `xmltv.php`) que serve **somente conteúdo livre**:
filmes abertos da Blender Foundation (Big Buck Bunny, Elephants Dream, Sintel, Tears of Steel — CC BY) e
streams públicos de teste (Mux, Unified Streaming, Apple bipbop) e cópias no Internet Archive. Arte genérica gerada pelo próprio servidor.

- Sem dependências: `node server.js`
- Env: `PORT` (8095), `REVIEW_USER`, `REVIEW_PASS`, `PUBLIC_URL` (ex.: https://hugo.streamflixvip.online:8443)
- Produção: pm2 `hugo-review` (127.0.0.1:8095) atrás do nginx em `https://hugo.streamflixvip.online:8443`
- Os streams (`/live|movie|series/u/p/id.ext`) respondem 302 para a fonte pública.
