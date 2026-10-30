package com.baotv.iptv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;

/** Real separate Settings screen (Chinese, large fonts, D-pad focusable buttons). */
public class SettingsActivity extends Activity {
    private SharedPreferences prefs;
    private TextView status;
    private EditText urlInput;

    private Button add(String label, LinearLayout parent, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(22);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }
    private void info(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFBBBBBB);
        t.setTextSize(20);
        parent.addView(t);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("baotv", MODE_PRIVATE);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        sv.addView(root);
        setContentView(sv);

        TextView title = new TextView(this);
        title.setText("宝视TV · 设置");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(28);
        root.addView(title);

        info(root, "播放设置");
        add("开机默认源：" + MainActivity.SOURCE_NAMES[prefs.getInt("default_source", 0)] + "（点按切换）", root, new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cur = prefs.getInt("default_source", 0);
                int next = (cur + 1) % MainActivity.SOURCE_NAMES.length;
                prefs.edit().putInt("default_source", next).apply();
                status.setText("开机默认源已改为：" + MainActivity.SOURCE_NAMES[next]);
            }
        });
        add("开机自启：" + (prefs.getBoolean("autostart", false) ? "开" : "关") + "（点按切换）", root, new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean cur = prefs.getBoolean("autostart", false);
                prefs.edit().putBoolean("autostart", !cur).apply();
                status.setText("开机自启已改为：" + (!cur ? "开（电视需在电视设置里允许宝视TV 自启动，否则会被系统拦截）" : "关"));
            }
        });
        add("全屏播放：" + (prefs.getBoolean("fullscreen", true) ? "开" : "关") + "（点按切换）", root, new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean cur = prefs.getBoolean("fullscreen", true);
                prefs.edit().putBoolean("fullscreen", !cur).apply();
                status.setText("全屏播放已改为：" + (!cur ? "开（OK 播放即全屏，返回回列表）" : "关"));
            }
        });
        add("字体大小：" + new String[]{"小", "中", "大"}[prefs.getInt("font_size", 1)] + "（点按切换）", root, new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cur = prefs.getInt("font_size", 1);
                int next = (cur + 1) % 3;
                prefs.edit().putInt("font_size", next).apply();
                status.setText("字体大小已改为：" + new String[]{"小", "中", "大"}[next] + "（返回播放页生效）");
            }
        });

        info(root, "频道源");
        add("立即更新网络源（iptv4 + iptv-org 中国 + 全球中文台）", root, new View.OnClickListener() {
            @Override public void onClick(View v) {
                status.setText("正在更新网络源，请稍候……");
                new Thread(new Runnable() {
                    @Override public void run() {
                        final int merged = SourceMerger.mergeTo(new File(getFilesDir(), "remote-iptv4.m3u"), getFilesDir());
                        final boolean ok = merged >= 50;
                        if (ok) prefs.edit().putLong("last_remote_update", System.currentTimeMillis()).putBoolean("sources_dirty", true).apply();
                        runOnUiThread(new Runnable() {
                            @Override public void run() { status.setText(ok ? "网络源更新成功（共 " + merged + " 台），返回播放页选“网络更新”即可看" : "网络源更新失败，已保留内置源"); }
                        });
                    }
                }).start();
            }
        });
        info(root, "自定义订阅：粘贴一个 M3U 订阅链接，点保存并下载，即成为第 3 个源“自定义订阅”。只支持 M3U 频道订阅，不支持 VPN 节点订阅。");
        urlInput = new EditText(this);
        urlInput.setHint("粘贴 M3U 订阅链接（http 开头）");
        urlInput.setTextSize(20);
        urlInput.setText(prefs.getString("custom_url", ""));
        root.addView(urlInput);
        add("保存并下载自定义订阅", root, new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String url = urlInput.getText().toString().trim();
                if (!url.startsWith("http")) { status.setText("链接不对：请粘贴 http 开头的 M3U 链接"); return; }
                prefs.edit().putString("custom_url", url).apply();
                status.setText("正在下载自定义订阅，请稍候……");
                new Thread(new Runnable() {
                    @Override public void run() {
                        final boolean ok = RemoteSource.download(url, new File(getFilesDir(), "custom.m3u"), 20000);
                        if (ok) prefs.edit().putBoolean("sources_dirty", true).apply();
                        runOnUiThread(new Runnable() {
                            @Override public void run() { status.setText(ok ? "自定义订阅下载成功，返回播放页选“自定义订阅”" : "自定义订阅下载失败，请检查链接"); }
                        });
                    }
                }).start();
            }
        });

        info(root, "点播源（初始为空，未内置任何点播源）");
        info(root, "粘贴你自己的 TVBox 风格 JSON 订阅地址，或单个点播站 API 地址，点保存并下载后，点播页即可用它搜索。不要把直播 TXT 列表当点播源粘贴，那不是点播源。");
        final EditText vodInput = new EditText(this);
        vodInput.setHint("粘贴点播源地址（http 开头）");
        vodInput.setTextSize(20);
        vodInput.setText(prefs.getString("vod_url", ""));
        root.addView(vodInput);
        add("保存点播源并下载", root, new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String url = vodInput.getText().toString().trim();
                if (!url.startsWith("http")) { status.setText("链接不对：请粘贴 http 开头的点播源地址"); return; }
                prefs.edit().putString("vod_url", url).apply();
                status.setText("正在下载点播源，请稍候……");
                new Thread(new Runnable() {
                    @Override public void run() {
                        final java.io.File cfg = new java.io.File(getFilesDir(), "vod-config.json");
                        final boolean ok = RemoteSource.download(url, cfg, 20000);
                        String text = null;
                        if (ok) {
                            try {
                                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(cfg));
                                StringBuilder sb = new StringBuilder();
                                String line;
                                while ((line = r.readLine()) != null) sb.append(line).append("\n");
                                r.close();
                                text = sb.toString();
                            } catch (Exception ignored) {}
                        }
                        final java.util.List<VodSource.Site> sites = ok ? VodSource.parseConfig(text, url) : new java.util.ArrayList<VodSource.Site>();
                        final boolean parsed = !sites.isEmpty();
                        if (parsed) VodSource.saveSites(SettingsActivity.this, sites);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                status.setText(parsed ? ("点播源保存成功，共 " + sites.size() + " 个点播站，点播页即可搜索") : "点播源下载/解析失败：请检查链接是否为 TVBox 风格 JSON 订阅或单个点播站 API 地址");
                            }
                        });
                    }
                }).start();
            }
        });
        add("清除点播源", root, new View.OnClickListener() {
            @Override public void onClick(View v) {
                VodSource.clearSites(SettingsActivity.this);
                prefs.edit().remove("vod_url").apply();
                vodInput.setText("");
                status.setText("点播源已清除，点播页将恢复为空状态");
            }
        });

        info(root, "其他");
        add("打开内置浏览器", root, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, BrowserActivity.class)); }
        });
        add("返回播放页", root, new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        info(root, "关于：宝视TV 分享测试版，免费分享，免商店安装。内置仅全国通用央视卫视源 + 网络更新 + 自定义订阅，不含云南/贵州地方省内源，不含任何私人节点，不含任何内置点播源（点播初始为空，需自己添加）。没有 VPN 功能，网络走电视系统本身；节目单为公开 EPG 尽力匹配，对不上不显示。");

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(22);
        root.addView(status);
    }
}
