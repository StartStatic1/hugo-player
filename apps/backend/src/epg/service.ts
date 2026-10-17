import { assertPublicHttpUrl, IPTV_USER_AGENT, TtlCache, UpstreamError } from "../content/net.js";
import { bodyToStream, parseXmltv, type EpgData } from "./xmltv.js";

const EPG_TTL_MS = Number(process.env.EPG_TTL_HOURS ?? 6) * 3600_000;
const epgCache = new TtlCache<EpgData>(EPG_TTL_MS, Number(process.env.EPG_CACHE_ENTRIES ?? 5));

export async function loadEpg(url: string, force = false): Promise<EpgData> {
  return epgCache.getOrLoad(
    url,
    async () => {
      assertPublicHttpUrl(url);
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), Number(process.env.EPG_TIMEOUT_MS ?? 120_000));
      try {
        const res = await fetch(url, { signal: controller.signal, redirect: "follow", headers: { "User-Agent": IPTV_USER_AGENT } });
        if (!res.ok || !res.body) throw new UpstreamError(502, `upstream_${res.status}`);
        const data = await parseXmltv(await bodyToStream(res.body));
        if (data.channels.size === 0 && data.programmeCount === 0) throw new UpstreamError(422, "empty_epg");
        return data;
      } catch (e: any) {
        if (e?.name === "AbortError") throw new UpstreamError(504, "timeout_fetching_epg");
        if (e instanceof UpstreamError) throw e;
        throw new UpstreamError(502, "epg_fetch_failed");
      } finally {
        clearTimeout(timer);
      }
    },
    force
  );
}
