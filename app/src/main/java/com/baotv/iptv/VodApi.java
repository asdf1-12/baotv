package com.baotv.iptv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * CMS-style JSON VOD API client (the common Chinese VOD CMS search/detail format).
 * Search:  {api}?ac=videolist&wd={keyword}
 * Detail:  {api}?ac=detail&ids={vod_id}
 * Response list items: vod_name, vod_id, vod_play_url, vod_remarks.
 * If a site is not a CMS JSON API (e.g. a spider script), the fetch/parse
 * fails and that site simply yields no results -- never fake results.
 */
public final class VodApi {
    public static final class VodItem {
        public final String site;
        public final String name;
        public final String id;
        public final String remarks;
        public final String playUrl; // may be null; then detail fetch is needed
        public VodItem(String site, String name, String id, String remarks, String playUrl) {
            this.site = site; this.name = name; this.id = id;
            this.remarks = remarks; this.playUrl = playUrl;
        }
        @Override public String toString() {
            return name + (remarks != null && !remarks.isEmpty() ? " (" + remarks + ")" : "") + " [" + site + "]";
        }
    }
    public static final class Episode {
        public final String name;
        public final String url;
        public Episode(String name, String url) { this.name = name; this.url = url; }
        @Override public String toString() { return name; }
    }
    private VodApi() {}

    private static String fetch(String urlStr) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "BaoTV/2.0");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) >= 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 2 * 1024 * 1024) break;
            }
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String searchUrl(VodSource.Site site, String keyword) {
        try {
            String enc = URLEncoder.encode(keyword, "UTF-8");
            String base = site.api;
            String sep = base.contains("?") ? "&" : "?";
            return base + sep + "ac=videolist&wd=" + enc;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<VodItem> search(VodSource.Site site, String keyword) {
        List<VodItem> out = new ArrayList<>();
        String url = searchUrl(site, keyword);
        if (url == null) return out;
        String body = fetch(url);
        if (body == null) return out;
        try {
            JSONObject jo = new JSONObject(body.trim());
            JSONArray list = jo.optJSONArray("list");
            if (list == null) return out;
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("vod_name", "").trim();
                if (name.isEmpty()) continue;
                String id = o.optString("vod_id", "");
                if (id.isEmpty()) id = String.valueOf(o.optLong("vod_id", 0));
                out.add(new VodItem(site.name, name, id,
                        o.optString("vod_remarks", ""),
                        o.optString("vod_play_url", null)));
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Episodes for one item: use embedded playUrl, else fetch detail by id. */
    public static List<Episode> episodes(VodSource.Site site, VodItem item) {
        String playUrl = item.playUrl;
        if (playUrl == null || playUrl.isEmpty()) {
            try {
                String base = site.api;
                String sep = base.contains("?") ? "&" : "?";
                String body = fetch(base + sep + "ac=detail&ids=" + URLEncoder.encode(item.id, "UTF-8"));
                if (body != null) {
                    JSONObject jo = new JSONObject(body.trim());
                    JSONArray list = jo.optJSONArray("list");
                    if (list != null && list.length() > 0) {
                        playUrl = list.optJSONObject(0).optString("vod_play_url", "");
                    }
                }
            } catch (Exception ignored) {}
        }
        return parseEpisodes(playUrl);
    }

    /** vod_play_url format: [source$$$]ep1$url#ep2$url  or a single URL. */
    public static List<Episode> parseEpisodes(String playUrl) {
        List<Episode> out = new ArrayList<>();
        if (playUrl == null || playUrl.isEmpty()) return out;
        String eps = playUrl;
        if (eps.contains("$$$")) eps = eps.substring(eps.lastIndexOf("$$$") + 3);
        String[] parts = eps.split("#");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) continue;
            if (p.contains("$")) {
                String name = p.substring(0, p.lastIndexOf('$')).trim();
                String url = p.substring(p.lastIndexOf('$') + 1).trim();
                if (url.startsWith("http")) out.add(new Episode(name.isEmpty() ? ("第" + (i + 1) + "集") : name, url));
            } else if (p.startsWith("http")) {
                out.add(new Episode("第" + (i + 1) + "集", p));
            }
        }
        return out;
    }
}
