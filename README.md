# Hugo Player

**Hugo Player** is a premium IPTV/OTT media player for Android phones, tablets and Android TV, with a backend API and a web portal for device activation and playlist management.

> Hugo Player is a player only. It does not provide, sell or host any audiovisual content; users load their own legally obtained playlists.

## Overview

- **Android app**: native Kotlin + Jetpack Compose, one APK for mobile and Android TV (leanback launcher, TV banner, no touchscreen requirement).
- **Device activation**: each install generates a **Device ID** (MAC-style `XX:XX:XX:XX:XX:XX`, derived from a random UUID, not the hardware MAC) and an 8-character **Device Key**.
- **Backend API**: Node.js 20 + TypeScript + Express. Device registration, trial/premium license checks and playlist management.
- **Web portal**: dark, self-contained landing page where users sign in with Device ID + Key and manage playlists.

## Architecture

```
 ┌──────────────────┐      POST /devices/register      ┌──────────────────────┐
 │  Android / TV    │ ───────────────────────────────▶ │                      │
 │  (Compose app)   │      GET /devices/status         │   Hugo Player API      │
 │  Device ID + Key │ ◀─────────────────────────────── │   Express + TS       │
 └──────────────────┘      GET /playlists              │   scrypt-hashed keys │
                                                       │   JSON file store    │
 ┌──────────────────┐      POST/GET /playlists         │                      │
 │  Web portal      │ ───────────────────────────────▶ │                      │
 │  (index.html)    │      GET /devices/status         └──────────────────────┘
 └──────────────────┘
```

Authentication for device endpoints uses the `x-device-id` and `x-device-key` headers (query parameters `deviceId` / `deviceKey` are also accepted for `GET` requests from simple clients).

## Repository structure

```
apps/android   Kotlin + Jetpack Compose app (mobile + Android TV)
apps/backend   Node.js + TypeScript + Express API
apps/web       Self-contained landing page + device portal
docs/          Documentation (see docs/STRUCTURE.md)
```

See [docs/STRUCTURE.md](docs/STRUCTURE.md) for the full tree.

## Requirements

- Node.js 20+ and npm 10+
- Android Studio Ladybug (2024.2) or newer, JDK 17, Android SDK 35

## Setup

### 1. Backend

```bash
npm install                 # installs workspace dependencies from the repo root
cp apps/backend/.env.example apps/backend/.env
npm run dev:backend         # hot reload with tsx on http://localhost:8080
# or production build:
npm run build:backend && npm run start:backend
```

Environment variables (`apps/backend/.env`):

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | HTTP port |
| `CORS_ORIGIN` | `*` | Allowed origins, comma-separated, or `*` |
| `DATA_FILE` | `./data/db.json` | JSON data store path |
| `TRIAL_DAYS` | `7` | Trial length for new devices |

### 2. Web portal

`apps/web/index.html` has no build step. Serve it with any static server:

```bash
npm run serve:web           # http://localhost:5173
```

The API base defaults to `http://<current-host>:8080`. Change it under **Server settings** on the page or with `?api=https://api.example.com`.

### 3. Android app

1. Open `apps/android` in Android Studio and let Gradle sync, or build from the CLI with `cd apps/android && ./gradlew assembleDebug` (the included wrapper downloads Gradle 8.11.1; requires JDK 17 and the Android SDK).
2. The debug build points to `http://10.0.2.2:8080` (your computer as seen from the emulator). On a physical device change `API_BASE_URL` in `app/build.gradle.kts` to your machine's LAN IP and add it to `res/xml/network_security_config.xml`, or use HTTPS.
3. Run the `app` configuration on a phone/emulator or an Android TV emulator.

On first launch the app shows its Device ID and Device Key, registers with the API and displays the license status.

## API reference

Base URL: `http://localhost:8080`. All bodies are JSON.

### `GET /health`
Returns `{ "status": "ok", "time": "..." }`.

### `POST /devices/register`
Registers a device or refreshes an existing registration (same key required).

```json
{ "deviceId": "02:1A:2B:3C:4D:5E", "deviceKey": "ABCD2345", "platform": "android-tv", "appVersion": "0.1.0" }
```

- `201` new device created with a trial license
- `200` already registered with the same key
- `400` validation error · `409` device registered with a different key · `429` rate limited

```json
{
  "registered": true,
  "created": true,
  "device": {
    "deviceId": "02:1A:2B:3C:4D:5E",
    "platform": "android-tv",
    "appVersion": "0.1.0",
    "license": { "type": "trial", "active": true, "expiresAt": "2026-10-24T00:00:00.000Z", "daysLeft": 7 },
    "createdAt": "...",
    "lastSeenAt": "..."
  }
}
```

### `GET /devices/status`  *(auth)*
Headers `x-device-id`, `x-device-key`. Returns `{ "deviceId", "platform", "license": { "type", "expiresAt" }, "playlists": 1, "createdAt", "lastSeenAt" }`. `401` on bad credentials.

### Playlists  *(auth: `x-device-id` + `x-device-key`)*

| Method | Path | Body | Notes |
| --- | --- | --- | --- |
| `GET` | `/playlists` | – | `{ playlists: [{ id, name, type, protected, url, createdAt, updatedAt }] }`. `url` is `null` for PIN-protected lists unless header `x-playlist-pin` is correct. |
| `POST` | `/playlists` | `{ name, type: "m3u"\|"xtream", url, username?, password?, pin? }` | Xtream is converted to `get.php?type=m3u_plus`. `pin` = 4–8 digits (optional). `201`. |
| `PATCH` | `/playlists/:id` | `{ name?, url?, type?, username?, password?, newPin?, removePin? }` | Rename / change URL / set, change or remove PIN. Protected lists need `x-playlist-pin`. |
| `DELETE` | `/playlists/:id` | – | Protected lists need `x-playlist-pin`. |
| `POST` | `/playlists/:id/verify-pin` | `{ pin }` | `200 { ok, playlist }` with the URL, `403 invalid_pin`. Rate limited (20/min per device+IP). |

PINs are stored only as scrypt hash + salt (never in plain text). Errors: `400 validation_error`, `401 invalid_credentials`, `403 invalid_pin`, `404 playlist_not_found`, `429 too_many_pin_attempts`.

### `GET /catalog`  *(Part A — content detection)*
Fetches the playlist and returns content split into **live / movies / series**, with categories (provider order) and logos.

- Auth mode (recommended): `?playlistId=<id>` + headers `x-device-id` / `x-device-key` — the playlist URL never leaves the server.
- Direct mode: `?url=<playlist url>&type=m3u|xtream`.
- Options: `refresh=1` (bypass the 15 min cache), `liveFormat=m3u8|ts` (Xtream live URLs, default `m3u8`), `sections=live,movies,series` (omit item arrays you don't need).

```json
{
  "source": "m3u | xtream",
  "epgUrl": "http://…/xmltv.php?… or url-tvg from #EXTM3U",
  "live":   { "categories": [{ "id", "name", "count" }], "items": [{ "id", "name", "url", "logo", "group", "categoryId", "epgId", "streamId?", "catchup?" }] },
  "movies": { "categories": [...], "items": [{ "id", "name", "url", "poster", "group", "categoryId", "year?", "rating?" }] },
  "series": { "categories": [...], "items": [{ "id", "name", "cover", "group", "categoryId", "seriesId?", "seasons": [{ "season", "episodes": [...] }], "episodeCount" }] },
  "stats":  { "live", "movies", "series", "episodes" }
}
```

**How the type is detected (M3U):** score-based, combining Xtream URL paths (`/live/`, `/movie/`, `/series/` — strongest), episode patterns in the name (`S01E02`, `S1 E2`, `T01E02`, `1x02`, `Temporada 1 Episódio 2`, `Season 1 Episode 2`), file extension (`.m3u8/.ts` → live, `.mp4/.mkv/.avi…` → VOD), `tvg-id` / `catchup` attributes (live), `group-title` keywords in PT/EN/ES/AR (Filmes, Movies, VOD, Películas, أفلام · Séries, Series, Temporada, Doramas, مسلسلات · Canais, Live, Ao Vivo, 24/7, قنوات…) and a year in the title (movie). Episodes are grouped into show → season → episode.

**Xtream Codes:** detected when the URL is `get.php` / `player_api.php` with `username` + `password` (or the playlist type is `xtream`). Uses `player_api.php` actions `get_live_categories`, `get_live_streams`, `get_vod_categories`, `get_vod_streams`, `get_series_categories`, `get_series`; falls back to parsing the M3U if the API fails.

### `GET /catalog/series/:id`
Seasons and episodes of a series. Xtream ids (`x-series-123`) call `get_series_info`; M3U series are returned from the grouped catalog. Same auth/`url` params as `/catalog`.

### `GET /catalog/category/:section/:categoryId`
Only the items of one category (`section` = `live | movies | series`).

### `POST /epg/now-next`  *(Part D — EPG)*
Now/next for many channels at once. The XMLTV is read from `url-tvg` / `x-tvg-url` in the M3U header or Xtream `xmltv.php`, parsed in **streaming** (sax, `.gz` supported, malformed XML tolerated), keeping only −3 h…+36 h, and cached for 6 h (`EPG_TTL_HOURS`).

```json
// body (auth headers as in /catalog)
{ "playlistId": "…", "ids": ["news.br", "Canal Sem tvg-id HD"], "epgUrl": "optional override" }
// response
{ "available": true, "epgUrl": "…", "channels": { "news.br": { "channelId": "news.br", "now": { "title", "start", "stop", "desc?" }, "next": { … } } } }
```

Keys are matched by XMLTV channel id (exact, then case-insensitive) and then by normalized `display-name` (accents, `HD/FHD/4K` and brackets ignored). `available: false` when the playlist has no EPG.

- `GET /epg/channel/:key` — remaining programmes for one channel.
- `GET /epg/short/:streamId` — Xtream `get_short_epg` (base64 decoded).

### `GET /channels?url=` *(legacy)*
Flat list used by older app builds, now powered by the same parser/classifier (`type` per channel, `epgUrl`, `limit`, `type` filter).

### Tests

```bash
npm test -w apps/backend         # tsc + node --test (synthetic fixtures, no real streams)
npm run smoke -w apps/backend    # end-to-end: fake provider (M3U + XMLTV.gz + Xtream API) + real API
```

## Security notes

- Device Keys are stored only as salted **scrypt** hashes and compared with `timingSafeEqual`.
- `helmet`, CORS allow-list, JSON body limit (32 KB), global and registration-specific rate limits.
- Request validation with **zod**.
- Playlist/EPG fetches only allow `http(s)` to public hosts (no `localhost`/private IPs; set `ALLOW_PRIVATE_URLS=1` for local testing). DNS rebinding is not covered yet.
- The JSON file store is for development; replace it with PostgreSQL (or similar) before production, and encrypt stored Xtream passwords.

## Roadmap

- Media3/ExoPlayer playback, M3U and Xtream parsing, EPG
- Premium licensing with payments and an admin dashboard
- Database persistence and HTTPS deployment

## License

Proprietary. All rights reserved.
