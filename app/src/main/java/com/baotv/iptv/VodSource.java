package com.baotv.iptv;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * User-added VOD sources only. No built-in VOD source, no bundled VOD URL.
 * A source is either:
 *  (a) a TVBox-style JSON subscription URL whose JSON contains a "sites" array
 *      (each site: name + api), or
 *  (b) a single VOD site API URL (CMS-style JSON API, e.g. an api.php endpoint).
 * Sites are saved locally in filesDir/vod-sites.txt, one "name\tapi" per line.
 * If that file is empty/missing, the VOD page has NO source and must show the
 * empty-state prompt, never fake results.
 */
public final class VodSource {
    public static final class Site {
        public final String name;
        public final String api;
        public Site(String name, String api) { this.name = name; this.api = api; }
        @Override public String toString() { return name; }
    }
    private VodSource() {}

    public static File sitesFile(Context ctx) {
        return new File(ctx.getFilesDir(), "vod-sites.txt");
    }

    public static List<Site> loadSites(Context ctx) {
        List<Site> out = new ArrayList<>();
        File f = sitesFile(ctx);
        if (!f.exists()) return out;
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t", 2);
                if (parts.length == 2 && parts[1].startsWith("http")) {
                    out.add(new Site(parts[0], parts[1]));
                }
            }
            r.close();
        } catch (Exception ignored) {}
        return out;
    }

    public static void saveSites(Context ctx, List<Site> sites) {
        try {
            FileWriter w = new FileWriter(sitesFile(ctx));
            for (Site s : sites) {
                w.write(s.name.replace("\t", " ").replace("\n", " ") + "\t" + s.api + "\n");
            }
            w.close();
        } catch (Exception ignored) {}
    }

    public static void clearSites(Context ctx) {
        File f = sitesFile(ctx);
        if (f.exists()) f.delete();
        File cfg = new File(ctx.getFilesDir(), "vod-config.json");
        if (cfg.exists()) cfg.delete();
    }

    /** Parse a downloaded config file. Returns sites found (may be empty). */
    public static List<Site> parseConfig(String text, String fallbackUrl) {
        List<Site> out = new ArrayList<>();
        if (text == null) return out;
        String t = text.trim();
        try {
            if (t.startsWith("{")) {
                JSONObject jo = new JSONObject(t);
                JSONArray sites = jo.optJSONArray("sites");
                if (sites != null) {
                    for (int i = 0; i < sites.length(); i++) {
                        JSONObject s = sites.optJSONObject(i);
                        if (s == null) continue;
                        String name = s.optString("name", "").trim();
                        String api = s.optString("api", "").trim();
                        if (api.startsWith("http") && !name.isEmpty()) {
                            out.add(new Site(name, api));
                        } else if (api.startsWith("http")) {
                            out.add(new Site("点播源" + (i + 1), api));
                        }
                    }
                    return out;
                }
            }
        } catch (Exception ignored) {}
        // Fallback: single API URL. Strip query string; CMS search appends ?ac=...
        if (fallbackUrl != null && fallbackUrl.startsWith("http")) {
            String base = fallbackUrl.split("\\?")[0];
            String host = fallbackUrl;
            try {
                java.net.URL u = new java.net.URL(fallbackUrl);
                host = u.getHost();
            } catch (Exception ignored) {}
            out.add(new Site(host, base));
        }
        return out;
    }
}
