import { test } from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import { Readable } from "node:stream";
import { bodyToStream, epgNameKey, nowNext, parseXmltv, parseXmltvTime, resolveChannel } from "../epg/xmltv.js";
import { SAMPLE_XMLTV } from "./fixtures.js";

test("horário XMLTV com fuso", () => {
  assert.equal(parseXmltvTime("20261017180000 +0000"), Date.UTC(2026, 9, 17, 18, 0, 0));
  assert.equal(parseXmltvTime("20261017150000 -0300"), Date.UTC(2026, 9, 17, 18, 0, 0));
  assert.equal(parseXmltvTime("20261017180000"), Date.UTC(2026, 9, 17, 18, 0, 0));
  assert.equal(parseXmltvTime("202610171800 +0100"), Date.UTC(2026, 9, 17, 17, 0, 0));
  assert.equal(parseXmltvTime("lixo"), null);
});

test("parse em streaming + agora/a seguir", async () => {
  const now = new Date();
  const epg = await parseXmltv(SAMPLE_XMLTV(now), { now: now.getTime() });
  assert.equal(epg.channels.size, 2);
  // programa de +48 h fica fora da janela
  assert.equal(epg.programmes.get("sport1")!.length, 2);
  const news = nowNext(epg, "news.br", now.getTime());
  assert.equal(news.now?.title, "Jornal Agora");
  assert.equal(news.now?.desc, "Notícias & mais");
  assert.equal(news.next?.title, "Próximo Jornal");
  const sport = nowNext(epg, "sport1", now.getTime());
  assert.equal(sport.now?.title, "Futebol Ao Vivo");
  assert.equal(sport.next?.title, "Mesa Redonda");
});

test("resolve canal por id, caixa e nome", async () => {
  const now = new Date();
  const epg = await parseXmltv(SAMPLE_XMLTV(now), { now: now.getTime() });
  assert.equal(resolveChannel(epg, "news.br"), "news.br");
  assert.equal(resolveChannel(epg, "NEWS.BR"), "news.br");
  assert.equal(resolveChannel(epg, "Esporte Um FHD"), "sport1");
  assert.equal(resolveChannel(epg, "Canal Notícias HD"), "news.br");
  assert.equal(resolveChannel(epg, "Inexistente"), null);
  assert.equal(epgNameKey("Globo SP (Alternativo) HD"), "globosp");
});

test("tolera XML mal formado", async () => {
  const xml = `<tv><channel id="a"><display-name>A & B</display-name></channel><programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="a"><title>T</title></programme></tv>`;
  const epg = await parseXmltv(xml, { now: Date.UTC(2026, 0, 1, 0, 30) });
  assert.equal(epg.channels.size, 1);
  assert.equal(nowNext(epg, "a", Date.UTC(2026, 0, 1, 0, 30)).now?.title, "T");
});

test("descompacta .gz automaticamente", async () => {
  const now = new Date();
  const gz = gzipSync(Buffer.from(SAMPLE_XMLTV(now)));
  const web = Readable.toWeb(Readable.from([gz.subarray(0, 10), gz.subarray(10)])) as unknown as ReadableStream<Uint8Array>;
  const epg = await parseXmltv(await bodyToStream(web), { now: now.getTime() });
  assert.equal(epg.channels.size, 2);
});

test("keep: guarda só os canais da playlist (por tvg-id ou nome)", async () => {
  const now = new Date();
  const byId = await parseXmltv(SAMPLE_XMLTV(now), { now: now.getTime(), keep: { ids: new Set(["news.br"]), names: new Set() } });
  assert.deepEqual([...byId.programmes.keys()], ["news.br"]);
  assert.equal(byId.channels.has("sport1"), false);
  const byName = await parseXmltv(SAMPLE_XMLTV(now), { now: now.getTime(), keep: { ids: new Set(), names: new Set([epgNameKey("Esporte Um FHD")]) } });
  assert.deepEqual([...byName.programmes.keys()], ["sport1"]);
  // janela padrão: -2 h .. +24 h ("Jornal da Manhã" terminou há 1 h e fica; +48 h sai)
  const all = await parseXmltv(SAMPLE_XMLTV(now), { now: now.getTime() });
  assert.equal(all.programmes.get("news.br")!.length, 3);
});

test("origem do EPG: link próprio > xmltv.php do Xtream", async () => {
  const { epgSourceFor } = await import("../routes-epg.js");
  const xt = { type: "xtream", url: "http://panel.example.test:8080/get.php?username=u1&password=p1&type=m3u_plus" };
  assert.deepEqual(await epgSourceFor({ ...xt, epgUrl: "https://epg.test/g.xml.gz" }), { url: "https://epg.test/g.xml.gz", kind: "custom" });
  const auto = await epgSourceFor(xt);
  assert.equal(auto?.kind, "xtream");
  assert.equal(auto?.url, "http://panel.example.test:8080/xmltv.php?username=u1&password=p1");
});
