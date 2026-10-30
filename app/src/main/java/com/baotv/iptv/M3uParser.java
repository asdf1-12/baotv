package com.baotv.iptv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** M3U parser: HLS (.m3u8) only, .flv and other entries skipped, duplicate URLs skipped. */
public final class M3uParser {
    private M3uParser() {}

    public static List<Channel> parse(Reader in) throws IOException {
        List<Channel> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        BufferedReader r = new BufferedReader(in);
        String line;
        String group = "";
        String name = null;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.startsWith("#EXTINF")) {
                group = extractGroup(line);
                int comma = line.lastIndexOf(',');
                name = (comma >= 0 && comma + 1 < line.length()) ? line.substring(comma + 1).trim() : null;
                if (name != null) {
                    // Strip resolution tags like [1080p] from display name? Keep them: they help the user.
                    name = name.replace("[Not 24/7]", "").trim();
                }
            } else if (line.startsWith("http") && name != null) {
                if (line.contains(".m3u8") && seen.add(line)) {
                    out.add(new Channel(group, name, line));
                }
                name = null;
            }
        }
        return out;
    }

    private static String extractGroup(String extinf) {
        String key = "group-title=\"";
        int s = extinf.indexOf(key);
        if (s < 0) return "";
        s += key.length();
        int e = extinf.indexOf('"', s);
        return (e > s) ? extinf.substring(s, e) : "";
    }

    /** Normalise a channel name for EPG matching: strip spaces, brackets, HD/4K tags, case. */
    public static String norm(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\[.*?\\]", "").replaceAll("\\(.*?\\)", "");
        t = t.replace("高清", "").replace("HD", "").replace("4K", "").replace("超清", "");
        t = t.replaceAll("[\\s·\\-_.]", "");
        return t.toLowerCase();
    }
}
