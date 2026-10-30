package com.baotv.iptv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Multi-source auto-update (v2 follow-up).
 * Fetches, SEQUENTIALLY, one source at a time:
 *   1. https://live.zbds.top/tv/iptv4.m3u (domestic, primary)
 *   2. https://iptv-org.github.io/iptv/countries/cn.m3u
 *   3. https://iptv-org.github.io/iptv/index.m3u (large global list)
 * Each source is downloaded to a single temp file, parsed streaming line by
 * line, then the temp file is deleted before the next fetch. Raw files are
 * never all held in memory at once; only the merged channel objects are kept,
 * capped at MAX_CHANNELS.
 *
 * Merge rules: dedup by URL globally, and by normalised channel name.
 * For the same normalised name, keep the highest-quality entry:
 * 1080p (5) > HD/高清/4K (4) > 720p (3) > others (1). First-seen order wins on ties.
 *
 * The global index.m3u is filtered to Chinese-named entries (CJK in name or
 * group) — this is a Chinese-user TV app, and merging the whole global list
 * verbatim would bloat a 2 GB RAM TV with thousands of foreign channels.
 * This filter is documented in README; the source is still fetched in full.
 * Returns merged channel count, or -1 if nothing usable was fetched.
 */
public final class SourceMerger {
    public static final String[] SOURCES = {
            RemoteSource.REMOTE_URL,
            "https://iptv-org.github.io/iptv/countries/cn.m3u",
            "https://iptv-org.github.io/iptv/index.m3u"
    };
    static final int MAX_CHANNELS = 4000;
    private SourceMerger() {}

    private static int score(String name) {
        String n = name == null ? "" : name;
        if (n.contains("1080")) return 5;
        if (n.contains("HD") || n.contains("高清") || n.contains("4K")) return 4;
        if (n.contains("720")) return 3;
        return 1;
    }

    private static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    public static int mergeTo(File dest, File workDir) {
        Map<String, Channel> byName = new LinkedHashMap<>();
        Map<String, Integer> scores = new HashMap<>();
        Set<String> seenUrls = new HashSet<>();
        boolean anyOk = false;
        for (int si = 0; si < SOURCES.length; si++) {
            File tmp = new File(workDir, "merge-src-" + si + ".m3u.tmp");
            if (!RemoteSource.download(SOURCES[si], tmp, 20000)) continue;
            anyOk = true;
            try {
                BufferedReader r = new BufferedReader(new FileReader(tmp));
                String line, group = "", name = null;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#EXTINF")) {
                        group = "";
                        String key = "group-title=\"";
                        int s = line.indexOf(key);
                        if (s >= 0) {
                            s += key.length();
                            int e = line.indexOf('"', s);
                            if (e > s) group = line.substring(s, e);
                        }
                        int comma = line.lastIndexOf(',');
                        name = (comma >= 0 && comma + 1 < line.length()) ? line.substring(comma + 1).trim() : null;
                    } else if (line.startsWith("http") && name != null) {
                        String url = line, nm = name;
                        name = null;
                        if (!url.contains(".m3u8")) continue;
                        if (!seenUrls.add(url)) continue;
                        // Global index: Chinese entries only (see class doc).
                        if (si == 2 && !hasCjk(nm) && !hasCjk(group)) continue;
                        String norm = M3uParser.norm(nm);
                        if (norm.isEmpty()) continue;
                        int sc = score(nm);
                        Channel old = byName.get(norm);
                        if (old == null) {
                            if (byName.size() < MAX_CHANNELS) {
                                byName.put(norm, new Channel(group, nm, url));
                                scores.put(norm, sc);
                            }
                        } else if (sc > scores.get(norm)) {
                            byName.put(norm, new Channel(old.group, nm, url));
                            scores.put(norm, sc);
                        }
                    }
                }
                r.close();
            } catch (Exception e) {
                // Keep what was merged from earlier sources.
            } finally {
                tmp.delete();
            }
        }
        if (!anyOk || byName.size() < 50) return -1;
        try {
            // Group channels by their group, preserving first-seen group order.
            Map<String, List<Channel>> groups = new LinkedHashMap<>();
            for (Channel c : byName.values()) {
                String g = (c.group == null || c.group.isEmpty()) ? "网络更新" : c.group;
                List<Channel> l = groups.get(g);
                if (l == null) { l = new ArrayList<>(); groups.put(g, l); }
                l.add(c);
            }
            File tmpOut = new File(dest.getAbsolutePath() + ".tmp");
            BufferedWriter w = new BufferedWriter(new FileWriter(tmpOut));
            w.write("#EXTM3U\n");
            w.write("# Bao TV 网络更新：iptv4 + iptv-org 中国 + iptv-org 全球（仅中文台），按台名去重，1080p/HD 优先\n");
            for (Map.Entry<String, List<Channel>> e : groups.entrySet()) {
                for (Channel c : e.getValue()) {
                    w.write("#EXTINF:-1 group-title=\"" + e.getKey() + "\"," + c.name + "\n");
                    w.write(c.url + "\n");
                }
            }
            w.close();
            if (!tmpOut.renameTo(dest)) return -1;
            return byName.size();
        } catch (IOException e) {
            return -1;
        }
    }
}
