package com.baotv.iptv;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * Best-effort EPG: streams e.xml.gz, keeps only the current + next programme
 * per channel id, matched to channel names via display-name. Never invents data:
 * if nothing matches, callers show nothing.
 */
public final class EpgStore {
    public static final class Prog {
        public final String title;
        public final String start;
        public final String stop;
        Prog(String t, String s, String e) { title = t; start = s; stop = e; }
    }

    private final Map<String, List<Prog>> byNormName = new HashMap<>();

    public synchronized String currentNext(String channelName) {
        List<Prog> list = byNormName.get(M3uParser.norm(channelName));
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size() && i < 2; i++) {
            if (i == 0) sb.append("正在播：").append(list.get(i).title);
            else sb.append("  |  接下来：").append(list.get(i).title);
        }
        return sb.toString();
    }

    public synchronized boolean isEmpty() { return byNormName.isEmpty(); }

    /** Parse gzipped XMLTV from file. Returns number of channels with data. */
    public int parse(File gzFile) {
        Map<String, String> idToNorm = new HashMap<>();
        Map<String, List<Prog>> tmp = new HashMap<>();
        try {
            InputStream in = new GZIPInputStream(new FileInputStream(gzFile));
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            long now = System.currentTimeMillis();
            String curChannel = null, curTitle = null, curStart = null, curStop = null;
            boolean inTitle = false, inDisplay = false;
            String curId = null;
            int event;
            while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if ("channel".equals(tag)) { curId = p.getAttributeValue(null, "id"); }
                    else if ("display-name".equals(tag)) { inDisplay = true; }
                    else if ("programme".equals(tag)) {
                        curChannel = p.getAttributeValue(null, "channel");
                        curStart = p.getAttributeValue(null, "start");
                        curStop = p.getAttributeValue(null, "stop");
                        curTitle = null;
                    } else if ("title".equals(tag)) { inTitle = true; }
                } else if (event == XmlPullParser.TEXT) {
                    if (inDisplay && curId != null) idToNorm.put(curId, M3uParser.norm(p.getText()));
                    if (inTitle) curTitle = p.getText();
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = p.getName();
                    if ("display-name".equals(tag)) inDisplay = false;
                    else if ("title".equals(tag)) inTitle = false;
                    else if ("channel".equals(tag)) curId = null;
                    else if ("programme".equals(tag)) {
                        if (curChannel != null && curTitle != null && curStart != null && curStop != null) {
                            long s = parseTime(curStart), e = parseTime(curStop);
                            if (e > now - 3600L * 1000L) {
                                String norm = idToNorm.get(curChannel);
                                if (norm != null && !norm.isEmpty()) {
                                    List<Prog> list = tmp.get(norm);
                                    if (list == null) { list = new ArrayList<>(); tmp.put(norm, list); }
                                    // Keep at most current + next per channel.
                                    if (list.size() < 2 && (s <= now || list.isEmpty())) list.add(new Prog(curTitle, curStart, curStop));
                                    else if (list.size() == 1 && s > now) list.add(new Prog(curTitle, curStart, curStop));
                                }
                            }
                        }
                        curChannel = null; curTitle = null;
                    }
                }
            }
            in.close();
        } catch (Exception e) {
            return 0;
        }
        synchronized (this) {
            byNormName.clear();
            byNormName.putAll(tmp);
        }
        return tmp.size();
    }

    private static long parseTime(String s) {
        try {
            // Format: yyyyMMddHHmmss +0800
            String t = s.length() >= 14 ? s.substring(0, 14) : s;
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT+08:00"));
            return f.parse(t).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
