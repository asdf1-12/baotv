package com.baotv.iptv;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Fetches a remote M3U over plain HTTP(S). No VPN, no proxy client: system network only. */
public final class RemoteSource {
    public static final String REMOTE_URL = "https://live.zbds.top/tv/iptv4.m3u";
    public static final String EPG_URL = "http://epg.51zmt.top:8000/e.xml.gz";
    private RemoteSource() {}

    /** Downloads url to dest. Returns true only if dest is non-trivial (>100 lines). */
    public static boolean download(String urlStr, File dest, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(urlStr);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "BaoTV/2.0");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return false;
            File tmp = new File(dest.getAbsolutePath() + ".tmp");
            InputStream in = new BufferedInputStream(c.getInputStream());
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 30 * 1024 * 1024) { out.close(); in.close(); return false; }
            }
            out.close(); in.close();
            if (total < 1024) return false;
            if (!tmp.renameTo(dest)) return false;
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
