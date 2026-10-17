import { Router, type Request, type Response } from "express";
import { loadCatalog, loadSeriesInfo, type PlaylistRef } from "./content/service.js";
import { UpstreamError } from "./content/net.js";
import type { Catalog } from "./content/catalog.js";

/**
 * Resolve a playlist a partir da requisição:
 *  - ?playlistId=... com cabeçalhos x-device-id / x-device-key (recomendado: a URL não sai do servidor)
 *  - ?url=...&type=m3u|xtream (compatível com o app atual)
 */
export type PlaylistResolver = (req: Request) => PlaylistRef | "unauthorized" | null;

export function sendError(res: Response, e: unknown) {
  if (e instanceof UpstreamError) return res.status(e.status).json({ error: e.code });
  const msg = e instanceof Error ? e.message : "internal_error";
  return res.status(500).json({ error: msg });
}

function pickFields(catalog: Catalog, sections: string | undefined) {
  if (!sections) return catalog;
  const want = new Set(sections.split(",").map((s) => s.trim()));
  return {
    ...catalog,
    live: want.has("live") ? catalog.live : { categories: catalog.live.categories, items: [] },
    movies: want.has("movies") ? catalog.movies : { categories: catalog.movies.categories, items: [] },
    series: want.has("series") ? catalog.series : { categories: catalog.series.categories, items: [] },
  };
}

export function createCatalogRouter(resolve: PlaylistResolver) {
  const router = Router();

  const ref = (req: Request, res: Response): PlaylistRef | null => {
    const r = resolve(req);
    if (r === "unauthorized") {
      res.status(401).json({ error: "invalid_credentials" });
      return null;
    }
    if (!r) {
      res.status(400).json({ error: "missing_playlist" });
      return null;
    }
    return r;
  };

  /** Catálogo completo separado em live / movies / series, com categorias e logos. */
  router.get("/", async (req, res) => {
    const r = ref(req, res);
    if (!r) return;
    try {
      const catalog = await loadCatalog(r, {
        force: req.query.refresh === "1",
        liveFormat: req.query.liveFormat === "ts" ? "ts" : "m3u8",
      });
      res.json(pickFields(catalog, req.query.sections as string | undefined));
    } catch (e) {
      sendError(res, e);
    }
  });

  /** Só os itens de uma categoria (útil para listas enormes). */
  router.get("/category/:section/:categoryId", async (req, res) => {
    const r = ref(req, res);
    if (!r) return;
    const section = req.params.section as "live" | "movies" | "series";
    if (!["live", "movies", "series"].includes(section)) return res.status(400).json({ error: "invalid_section" });
    try {
      const catalog = await loadCatalog(r);
      const items = (catalog[section].items as { categoryId: string }[]).filter((i) => i.categoryId === req.params.categoryId);
      res.json({ section, categoryId: req.params.categoryId, items });
    } catch (e) {
      sendError(res, e);
    }
  });

  /** Temporadas/episódios de uma série. Xtream: get_series_info. M3U: já vem agrupado. */
  router.get("/series/:id", async (req, res) => {
    const r = ref(req, res);
    if (!r) return;
    const id = req.params.id;
    try {
      const xtreamId = /^x-series-(\d+)$/.exec(id)?.[1] ?? null;
      if (xtreamId) {
        const info = await loadSeriesInfo(r, Number(xtreamId));
        return res.json(info);
      }
      const catalog = await loadCatalog(r);
      const s = catalog.series.items.find((x) => x.id === id);
      if (!s) return res.status(404).json({ error: "series_not_found" });
      res.json({ seriesId: s.id, name: s.name, cover: s.cover, plot: s.plot, year: s.year, rating: s.rating, seasons: s.seasons });
    } catch (e) {
      sendError(res, e);
    }
  });

  return router;
}
