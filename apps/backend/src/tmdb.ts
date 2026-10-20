const TMDB_BASE = "https://api.themoviedb.org/3";
const TMDB_KEY = process.env.TMDB_API_KEY || "";

export async function searchTmdb(type: "movie" | "tv", query: string) {
  if (!TMDB_KEY) throw new Error("TMDB_API_KEY not set");
  const url = `${TMDB_BASE}/search/${type}?api_key=${TMDB_KEY}&language=pt-BR&query=${encodeURIComponent(query)}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`TMDB error ${res.status}`);
  return res.json();
}

export async function getTmdbDetails(type: "movie" | "tv", id: string) {
  if (!TMDB_KEY) throw new Error("TMDB_API_KEY not set");
  const url = `${TMDB_BASE}/${type}/${id}?api_key=${TMDB_KEY}&language=pt-BR`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`TMDB error ${res.status}`);
  return res.json();
}

export async function getTmdbSimilar(type: "movie" | "tv", id: string) {
  if (!TMDB_KEY) throw new Error("TMDB_API_KEY not set");
  const url = `${TMDB_BASE}/${type}/${id}/similar?api_key=${TMDB_KEY}&language=pt-BR`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`TMDB error ${res.status}`);
  return res.json();
}
