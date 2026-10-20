import { Router } from "express";
import { searchTmdb, getTmdbDetails, getTmdbSimilar } from "./tmdb.js";

export const tmdbRouter = Router();

tmdbRouter.get("/search", async (req, res) => {
  const type = (req.query.type as string) === "tv" ? "tv" : "movie";
  const query = req.query.q as string;
  if (!query) return res.status(400).json({ error: "missing_query" });
  try {
    const data = await searchTmdb(type, query);
    res.json(data);
  } catch (e: any) {
    res.status(500).json({ error: e.message });
  }
});

tmdbRouter.get("/details/:type/:id", async (req, res) => {
  const type = req.params.type === "tv" ? "tv" : "movie";
  try {
    const data = await getTmdbDetails(type, req.params.id);
    res.json(data);
  } catch (e: any) {
    res.status(500).json({ error: e.message });
  }
});

tmdbRouter.get("/similar/:type/:id", async (req, res) => {
  const type = req.params.type === "tv" ? "tv" : "movie";
  try {
    const data = await getTmdbSimilar(type, req.params.id);
    res.json(data);
  } catch (e: any) {
    res.status(500).json({ error: e.message });
  }
});
