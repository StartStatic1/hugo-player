# Enable TMDB Proxy

In `src/main.ts`, add these lines near the other routes:

```ts
import { tmdbRouter } from "./routes-tmdb.js";
app.use("/tmdb", tmdbRouter);
```

Then set the keys in `.env`:

```
TMDB_API_KEY=your_key
OMDB_API_KEY=your_key
OPENSUBTITLES_API_KEY=your_key
OPENSUBTITLES_PASSWORD=your_password
```

The Android app will call `/tmdb/search?q=...&type=movie` instead of talking to TMDB directly.
