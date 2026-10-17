# Repository structure

Hugo Player is an npm-workspaces monorepo with three independent applications.

```
hugo-player/
├── package.json                 # Root: npm workspaces + convenience scripts
├── .gitignore
├── README.md
├── docs/
│   └── STRUCTURE.md             # This file
└── apps/
    ├── android/                 # Native app (Kotlin + Jetpack Compose), phone + Android TV
    │   ├── settings.gradle.kts
    │   ├── build.gradle.kts     # Root Gradle build (plugin aliases)
    │   ├── gradle.properties
    │   ├── gradle/
    │   │   ├── libs.versions.toml           # Version catalog (AGP, Kotlin 2.x, Compose BOM)
    │   │   └── wrapper/gradle-wrapper.{jar,properties}
    │   └── app/
    │       ├── build.gradle.kts             # App module, BuildConfig.API_BASE_URL per build type
    │       ├── proguard-rules.pro
    │       └── src/main/
    │           ├── AndroidManifest.xml      # INTERNET, LEANBACK_LAUNCHER, TV banner
    │           ├── java/com/hugo/player/MainActivity.kt
    │           └── res/                     # themes, strings, colors, vector icon + TV banner
    ├── backend/                 # REST API (Node.js 20 + TypeScript + Express)
    │   ├── package.json
    │   ├── tsconfig.json
    │   ├── .env.example
    │   └── src/main.ts          # Devices, licenses, playlists; JSON file store
    └── web/
        └── index.html           # Self-contained landing page + device portal
```

## Responsibilities

| Path | Responsibility |
|------|----------------|
| `apps/android` | Generates and persists Device ID + Device Key, registers with the API, shows activation status. Future home of the player (Media3/ExoPlayer), EPG and catalog UI. |
| `apps/backend` | Device registration, license (trial/premium) checks, playlist storage per device. Keys are stored only as scrypt hashes. |
| `apps/web` | Commercial landing page and portal where users sign in with Device ID + Key to add and list playlists. |
| `docs` | Project documentation. |

## Data flow

1. The app generates a Device ID (MAC-style, derived from a random UUID) and an 8-character Device Key on first launch.
2. The app calls `POST /devices/register`; the backend creates the device with a trial license.
3. The user opens the web portal, signs in with the same Device ID + Key and adds playlists (`POST /playlists`).
4. The app reads its status and playlists with the same credentials (`GET /devices/status`, `GET /playlists`).
