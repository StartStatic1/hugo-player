/** Playlists sintéticas para testes (nenhuma URL real). */
export const SAMPLE_M3U = `#EXTM3U url-tvg="http://epg.example.test/guide.xml.gz"
#EXTINF:-1 tvg-id="news.br" tvg-name="Canal Notícias" tvg-logo="http://img.example.test/news.png" group-title="Canais | Notícias",Canal Notícias HD
http://iptv.example.test:8080/demo/demo/1001
#EXTINF:-1 tvg-id="sport1" tvg-logo="http://img.example.test/sport.png" group-title="Esportes",Esporte Um FHD
http://iptv.example.test:8080/live/demo/demo/1002.m3u8
#EXTINF:-1 tvg-id="" group-title="24/7",Desenhos 24/7
http://iptv.example.test:8080/demo/demo/1003.ts
#EXTINF:-1 tvg-logo="http://img.example.test/movie1.jpg" group-title="Filmes | Ação",O Grande Teste (2021)
http://iptv.example.test:8080/movie/demo/demo/2001.mp4
#EXTINF:-1 tvg-logo="http://img.example.test/movie2.jpg" group-title="Lançamentos",Outro Filme Exemplo [2023]
http://iptv.example.test:8080/demo/demo/2002.mkv
#EXTINF:-1 tvg-logo="http://img.example.test/m3.jpg" group-title="أفلام عربية",فيلم تجريبي
http://iptv.example.test:8080/demo/demo/2003.mp4
#EXTINF:-1 tvg-logo="http://img.example.test/show.jpg" group-title="Séries | Drama",Série Exemplo S01E01
http://iptv.example.test:8080/series/demo/demo/3001.mp4
#EXTINF:-1 tvg-logo="http://img.example.test/show.jpg" group-title="Séries | Drama",Série Exemplo S01E02
http://iptv.example.test:8080/series/demo/demo/3002.mp4
#EXTINF:-1 group-title="Séries | Drama",Série Exemplo S02 E01
http://iptv.example.test:8080/series/demo/demo/3003.mp4
#EXTINF:-1 group-title="Doramas",Outra Série 1x03
http://iptv.example.test:8080/demo/demo/3004.mkv
#EXTINF:-1 group-title="Series",Show Temporada 2 Episódio 5
http://iptv.example.test:8080/demo/demo/3005.mp4
#EXTVLCOPT:http-user-agent=CustomUA/1.0
#EXTINF:-1 tvg-id="radio1" catchup="default" catchup-days="3" group-title="Variedades, Música",Canal Com, Vírgula
http://iptv.example.test:8080/demo/demo/1004
`;

export const SAMPLE_XMLTV = (now: Date) => {
  const fmt = (d: Date) =>
    d.toISOString().replace(/[-:T]/g, "").slice(0, 14) + " +0000";
  const h = 3600_000;
  const t = now.getTime();
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tv SYSTEM "xmltv.dtd">
<tv generator-info-name="teste">
  <channel id="news.br"><display-name>Canal Notícias</display-name><icon src="http://img.example.test/news.png"/></channel>
  <channel id="sport1"><display-name>Esporte Um</display-name></channel>
  <programme start="${fmt(new Date(t - 2 * h))}" stop="${fmt(new Date(t - h))}" channel="news.br"><title lang="pt">Jornal da Manhã</title></programme>
  <programme start="${fmt(new Date(t - h / 2))}" stop="${fmt(new Date(t + h / 2))}" channel="news.br"><title lang="pt">Jornal Agora</title><desc lang="pt">Notícias &amp; mais</desc></programme>
  <programme start="${fmt(new Date(t + h / 2))}" stop="${fmt(new Date(t + h))}" channel="news.br"><title>Próximo Jornal</title></programme>
  <programme start="${fmt(new Date(t - h))}" stop="${fmt(new Date(t + h))}" channel="sport1"><title>Futebol Ao Vivo</title></programme>
  <programme start="${fmt(new Date(t + h))}" stop="${fmt(new Date(t + 3 * h))}" channel="sport1"><title><![CDATA[Mesa Redonda]]></title></programme>
  <programme start="${fmt(new Date(t + 48 * h))}" stop="${fmt(new Date(t + 49 * h))}" channel="sport1"><title>Muito Longe</title></programme>
</tv>`;
};
