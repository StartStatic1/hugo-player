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
Headers `x-device-id`, `x-device-key` (or `?deviceId=&deviceKey=`). Returns `{ "device": {...}, "playlists": 1 }`. `401` on bad credentials.

### `GET /playlists`  *(auth)*
Returns `{ "playlists": [ { "id", "name", "type", "url", "username?", "epgUrl?", "createdAt", "hasPassword" } ] }`. Passwords are never returned.

### `POST /playlists`  *(auth)*

```json
{ "name": "Main", "type": "m3u", "url": "https://provider.example/list.m3u" }
{ "name": "Xtream", "type": "xtream", "url": "http://provider.example:8080", "username": "user", "password": "pass" }
```

- `201` created · `400` validation · `401` bad credentials · `402` license expired · `409` limit of 20 playlists

## Security notes

- Device Keys are stored only as salted **scrypt** hashes and compared with `timingSafeEqual`.
- `helmet`, CORS allow-list, JSON body limit (32 KB), global and registration-specific rate limits.
- Request validation with **zod**.
- The JSON file store is for development; replace it with PostgreSQL (or similar) before production, and encrypt stored Xtream passwords.

## Roadmap

- Media3/ExoPlayer playback, M3U and Xtream parsing, EPG
- Premium licensing with payments and an admin dashboard
- Database persistence and HTTPS deployment

## License

Proprietary. All rights reserved.
