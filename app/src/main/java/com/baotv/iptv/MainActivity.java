package com.baotv.iptv;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bao TV v2 SHARE version for Telegram free testing (not personal version).
 * - Built-in: 央视卫视51 only (nationwide asset), plus 网络更新 (iptv4 remote, cached)
 *   and 自定义订阅 (user-pasted M3U URL, cached). Only the selected source is in memory.
 * - HLS (.m3u8) only; .flv skipped; duplicate URLs skipped. No VPN, no VpnService, no Xray.
 * - Fullscreen: OK plays fullscreen (if enabled in Settings); Back/Menu returns to list.
 * - EPG: best-effort, from epg.51zmt.top e.xml.gz, matched by channel name; unmatched = nothing shown.
 * - Settings and Browser are separate activities, opened from the top bar.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    static final String[] SOURCE_NAMES = {"央视卫视51", "网络更新", "自定义订阅"};
    static final String[] SOURCE_FILES = {"central-51.m3u", null, null};

    private View topBar, contentRow;
    private SurfaceView surfaceView;
    private ExoPlayer player;
    private ListView channelList;
    private TextView nowPlaying, statusText, epgText, hintText;
    private Button[] sourceButtons = new Button[3];
    private Button btnSettings, btnBrowser, btnVod;

    private List<Object> items = new ArrayList<>(); // String group header or Channel
    private List<Channel> channels = new ArrayList<>();
    private ChannelAdapter adapter;
    private int currentSource = 0;
    private int currentIndex = -1;
    private boolean surfaceReady = false;
    private boolean fullscreen = false;
    private SharedPreferences prefs;
    private EpgStore epg = new EpgStore();
    private long lastRemoteAttempt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("baotv", MODE_PRIVATE);
        setContentView(R.layout.activity_main);

        topBar = findViewById(R.id.top_bar);
        contentRow = findViewById(R.id.content_row);
        surfaceView = findViewById(R.id.video_surface);
        surfaceView.getHolder().addCallback(this);
        channelList = findViewById(R.id.channel_list);
        nowPlaying = findViewById(R.id.now_playing);
        statusText = findViewById(R.id.status_text);
        epgText = findViewById(R.id.epg_text);
        hintText = findViewById(R.id.hint_text);
        sourceButtons[0] = findViewById(R.id.btn_central);
        sourceButtons[1] = findViewById(R.id.btn_remote);
        sourceButtons[2] = findViewById(R.id.btn_custom);
        btnSettings = findViewById(R.id.btn_settings);
        btnBrowser = findViewById(R.id.btn_browser);
        btnVod = findViewById(R.id.btn_vod);

        player = new ExoPlayer.Builder(this).build();
        adapter = new ChannelAdapter();
        channelList.setAdapter(adapter);
        channelList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object o = items.get(position);
                if (o instanceof Channel) playChannel(channels.indexOf(o));
            }
        });
        for (int i = 0; i < sourceButtons.length; i++) {
            final int idx = i;
            sourceButtons[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { switchSource(idx); }
            });
        }
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        btnBrowser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, BrowserActivity.class)); }
        });

        applyFontSize();
        hintText.setText("↑ ↓ 选台    OK 播放全屏    返回 回列表    菜单 设置");
        currentSource = prefs.getInt("default_source", 0);
        if (currentSource < 0 || currentSource >= SOURCE_NAMES.length) currentSource = 0;
        switchSource(currentSource);
        channelList.requestFocus();
        autoUpdateInBackground(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFontSize();
        // Settings may have changed default source / font size; reload current source labels only.
        if (prefs.getBoolean("sources_dirty", false)) {
            prefs.edit().putBoolean("sources_dirty", false).apply();
            switchSource(currentSource);
        }
    }

    void applyFontSize() {
        int fs = prefs.getInt("font_size", 1); // 0 小 1 中 2 大
        float listSize = fs == 0 ? 20f : (fs == 2 ? 28f : 24f);
        float headSize = fs == 0 ? 22f : (fs == 2 ? 28f : 24f);
        nowPlaying.setTextSize(headSize);
        statusText.setTextSize(fs == 0 ? 18f : (fs == 2 ? 24f : 20f));
        adapter.listSize = listSize;
        adapter.notifyDataSetChanged();
    }

    void switchSource(int idx) {
        currentSource = idx;
        for (int i = 0; i < sourceButtons.length; i++) sourceButtons[i].setSelected(i == idx);
        channels.clear();
        items.clear();
        currentIndex = -1;
        List<Channel> parsed = loadSource(idx);
        channels.addAll(parsed);
        // Build group-header items, preserving file order.
        Map<String, List<Channel>> groups = new LinkedHashMap<>();
        for (Channel c : channels) {
            String g = (c.group == null || c.group.isEmpty()) ? "全部频道" : c.group;
            List<Channel> l = groups.get(g);
            if (l == null) { l = new ArrayList<>(); groups.put(g, l); }
            l.add(c);
        }
        for (Map.Entry<String, List<Channel>> e : groups.entrySet()) {
            items.add(e.getKey());
            items.addAll(e.getValue());
        }
        adapter.notifyDataSetChanged();
        if (parsed.isEmpty()) {
            if (idx == 1) statusText.setText("网络更新：暂无已下载的网络源（离线时用内置源），点设置 → 立即更新源");
            else if (idx == 2) statusText.setText("自定义订阅：未设置，点设置 → 粘贴订阅链接");
            else statusText.setText(SOURCE_NAMES[idx] + "：读取失败");
        } else {
            statusText.setText(SOURCE_NAMES[idx] + "：共 " + parsed.size() + " 台，上下键选台，OK 播放全屏");
        }
        epgText.setText("");
        if (!items.isEmpty()) { channelList.setSelection(0); channelList.requestFocus(); }
        exitFullscreen();
    }

    List<Channel> loadSource(int idx) {
        try {
            if (idx == 0) {
                return M3uParser.parse(new InputStreamReader(getAssets().open(SOURCE_FILES[idx]), StandardCharsets.UTF_8));
            } else if (idx == 1) {
                File f = new File(getFilesDir(), "remote-iptv4.m3u");
                if (f.exists()) return M3uParser.parse(new FileReader(f));
            } else if (idx == 2) {
                File f = new File(getFilesDir(), "custom.m3u");
                if (f.exists()) return M3uParser.parse(new FileReader(f));
            }
        } catch (Exception e) {
            // fall through to empty
        }
        return new ArrayList<>();
    }

    void playChannel(int position) {
        if (position < 0 || position >= channels.size()) return;
        currentIndex = position;
        Channel c = channels.get(position);
        nowPlaying.setText("正在播放：" + c);
        String e = epg.currentNext(c.name);
        epgText.setText(e);
        if (surfaceReady) player.setVideoSurface(surfaceView.getHolder().getSurface());
        player.stop();
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.prepare();
        player.play();
        if (prefs.getBoolean("fullscreen", true)) enterFullscreen();
        else surfaceView.requestFocus();
    }

    void enterFullscreen() {
        if (fullscreen) return;
        fullscreen = true;
        topBar.setVisibility(View.GONE);
        nowPlaying.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        epgText.setVisibility(View.GONE);
        hintText.setVisibility(View.GONE);
        channelList.setVisibility(View.GONE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        surfaceView.requestFocus();
    }

    void exitFullscreen() {
        if (!fullscreen) return;
        fullscreen = false;
        topBar.setVisibility(View.VISIBLE);
        nowPlaying.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        epgText.setVisibility(View.VISIBLE);
        hintText.setVisibility(View.VISIBLE);
        channelList.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        channelList.requestFocus();
    }

    void autoUpdateInBackground(final boolean force) {
        long now = System.currentTimeMillis();
        long last = prefs.getLong("last_remote_update", 0);
        if (!force && now - last < 24L * 3600L * 1000L && now - lastRemoteAttempt < 3600L * 1000L) return;
        lastRemoteAttempt = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File remote = new File(getFilesDir(), "remote-iptv4.m3u");
                int merged = SourceMerger.mergeTo(remote, getFilesDir());
                boolean ok = merged >= 50;
                if (!ok) remote.delete();
                // EPG, best-effort, never blocks playback.
                File epgFile = new File(getFilesDir(), "epg.xml.gz");
                if (RemoteSource.download(RemoteSource.EPG_URL, epgFile, 20000)) {
                    epg.parse(epgFile);
                }
                if (ok) prefs.edit().putLong("last_remote_update", System.currentTimeMillis()).apply();
                final boolean updated = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (updated && currentSource == 3) switchSource(3);
                        else if (updated) statusText.setText(statusText.getText() + "  ·  网络源已更新");
                    }
                });
            }
        }).start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (fullscreen) { exitFullscreen(); return true; }
            if (surfaceView.hasFocus()) { channelList.requestFocus(); return true; }
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (fullscreen) { exitFullscreen(); return true; }
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (channelList.hasFocus()) {
                int pos = channelList.getSelectedItemPosition();
                if (pos >= 0 && pos < items.size() && items.get(pos) instanceof Channel) {
                    playChannel(channels.indexOf(items.get(pos)));
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (player != null) player.setVideoSurface(holder.getSurface());
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (player != null) player.setVideoSurface(null);
    }

    @Override protected void onStop() { super.onStop(); if (player != null) player.pause(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
    }

    class ChannelAdapter extends BaseAdapter {
        float listSize = 24f;
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public boolean isEnabled(int p) { return items.get(p) instanceof Channel; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = new TextView(MainActivity.this);
            tv.setPadding(16, 8, 8, 8);
            Object o = items.get(position);
            if (o instanceof String) {
                tv.setText("—— " + o + " ——");
                tv.setTextColor(0xFF9AB0CC);
                tv.setTextSize(listSize - 2);
            } else {
                Channel c = (Channel) o;
                int num = channels.indexOf(c) + 1;
                tv.setText(String.format("%03d  %s", num, c.name));
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(listSize);
            }
            return tv;
        }
    }
}
