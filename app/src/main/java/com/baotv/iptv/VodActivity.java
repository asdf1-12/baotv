package com.baotv.iptv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * VOD (点播) page, share version.
 * - 榜单 layout: 热播榜 / 电影榜 / 电视剧榜 with TITLE NAMES ONLY.
 *   These titles are plain text placeholders for search, NOT streams and
 *   NOT a built-in VOD source. Tapping a title searches the USER's own
 *   added VOD source(s) for that title.
 * - Search box at the top, same behaviour as tapping a chart title.
 * - If the user has added NO VOD source: the page still shows the charts
 *   (so it does not look empty), but tapping/searching shows the exact
 *   empty-state prompt "请先在设置里添加点播源" and offers a jump to Settings.
 *   No fake results are ever shown.
 * - Results -> episode selection -> playback with the same ExoPlayer player.
 */
public class VodActivity extends Activity implements SurfaceHolder.Callback {
    // Chart titles: names only, no URLs, no streams attached.
    private static final String[][] CHARTS = {
        {"热播榜", "狂飙", "庆余年", "繁花", "漫长的季节", "三体"},
        {"电影榜", "流浪地球2", "满江红", "长安三万里", "封神第一部", "热辣滚烫"},
        {"电视剧榜", "甄嬛传", "琅琊榜", "人民的名义", "都挺好", "觉醒年代"},
    };

    private static final int MODE_CHARTS = 0;
    private static final int MODE_RESULTS = 1;
    private static final int MODE_EPISODES = 2;
    private static final int MODE_PLAYING = 3;

    private EditText queryBox;
    private TextView statusText;
    private ListView listView;
    private SurfaceView surfaceView;
    private Button btnSettingsJump;
    private ArrayAdapter<String> adapter;
    private List<String> display = new ArrayList<>();

    private int mode = MODE_CHARTS;
    private List<VodApi.VodItem> results = new ArrayList<>();
    private VodApi.VodItem currentItem;
    private VodSource.Site currentSite;
    private List<VodApi.Episode> episodes = new ArrayList<>();
    private ExoPlayer player;
    private boolean surfaceReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("宝视TV · 点播");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(28);
        root.addView(title);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        queryBox = new EditText(this);
        queryBox.setHint("输入片名搜索");
        queryBox.setTextSize(22);
        searchRow.addView(queryBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button searchBtn = new Button(this);
        searchBtn.setText("搜索");
        searchBtn.setTextSize(22);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSearch(queryBox.getText().toString().trim()); }
        });
        searchRow.addView(searchBtn);
        Button backBtn = new Button(this);
        backBtn.setText("返回播放");
        backBtn.setTextSize(22);
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        searchRow.addView(backBtn);
        root.addView(searchRow);

        statusText = new TextView(this);
        statusText.setTextColor(0xFFFFD54F);
        statusText.setTextSize(22);
        root.addView(statusText);

        btnSettingsJump = new Button(this);
        btnSettingsJump.setText("去设置添加点播源");
        btnSettingsJump.setTextSize(22);
        btnSettingsJump.setVisibility(View.GONE);
        btnSettingsJump.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(VodActivity.this, SettingsActivity.class)); }
        });
        root.addView(btnSettingsJump);

        listView = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, display);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onListClick(position);
            }
        });
        root.addView(listView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setVisibility(View.GONE);
        root.addView(surfaceView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        player = new ExoPlayer.Builder(this).build();
        showCharts();
        listView.requestFocus();
    }

    private List<VodSource.Site> sites() {
        return VodSource.loadSites(this);
    }

    private void showEmptyPrompt() {
        statusText.setText("请先在设置里添加点播源");
        btnSettingsJump.setVisibility(View.VISIBLE);
    }

    private void showCharts() {
        mode = MODE_CHARTS;
        display.clear();
        for (String[] chart : CHARTS) {
            display.add("—— " + chart[0] + " ——");
            for (int i = 1; i < chart.length; i++) display.add(chart[i]);
        }
        adapter.notifyDataSetChanged();
        listView.setVisibility(View.VISIBLE);
        surfaceView.setVisibility(View.GONE);
        if (sites().isEmpty()) {
            // Page still looks full (charts above), but the prompt is explicit.
            showEmptyPrompt();
        } else {
            statusText.setText("点播：点榜单片名或输入片名搜索（只搜你自己添加的点播源）");
            btnSettingsJump.setVisibility(View.GONE);
        }
    }

    private void doSearch(final String keyword) {
        if (keyword.isEmpty()) {
            statusText.setText("请先输入片名");
            return;
        }
        List<VodSource.Site> s = sites();
        if (s.isEmpty()) {
            showEmptyPrompt();
            return;
        }
        statusText.setText("正在搜索“" + keyword + "”……");
        btnSettingsJump.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<VodApi.VodItem> found = new ArrayList<>();
                List<VodSource.Site> all = sites();
                for (VodSource.Site site : all) {
                    found.addAll(VodApi.search(site, keyword));
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (found.isEmpty()) {
                            statusText.setText("没有搜到“" + keyword + "”（你添加的点播源里没有，或源暂时不可用）");
                            showChartsKeepStatus();
                        } else {
                            results = found;
                            mode = MODE_RESULTS;
                            display.clear();
                            for (VodApi.VodItem it : results) display.add(it.toString());
                            adapter.notifyDataSetChanged();
                            statusText.setText("搜到 " + results.size() + " 条，点一条选集");
                            listView.setVisibility(View.VISIBLE);
                            surfaceView.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }).start();
    }

    private void showChartsKeepStatus() {
        String keep = statusText.getText().toString();
        showCharts();
        // showCharts overwrites status when a source exists; restore search outcome.
        if (!sites().isEmpty()) statusText.setText(keep);
    }

    private void onListClick(int position) {
        if (position < 0 || position >= display.size()) return;
        String text = display.get(position);
        if (mode == MODE_CHARTS) {
            if (text.startsWith("——")) return;
            // Chart title: search it in the user's source, or show empty prompt.
            if (sites().isEmpty()) {
                showEmptyPrompt();
                return;
            }
            queryBox.setText(text);
            doSearch(text);
        } else if (mode == MODE_RESULTS) {
            if (position >= results.size()) return;
            currentItem = results.get(position);
            // Find its site by name.
            currentSite = null;
            for (VodSource.Site s : sites()) {
                if (s.name.equals(currentItem.site)) { currentSite = s; break; }
            }
            if (currentSite == null && !sites().isEmpty()) currentSite = sites().get(0);
            statusText.setText("正在取“" + currentItem.name + "”的选集……");
            new Thread(new Runnable() {
                @Override public void run() {
                    final List<VodApi.Episode> eps = VodApi.episodes(currentSite, currentItem);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (eps.isEmpty()) {
                                statusText.setText("这个结果没有可播的选集（源返回为空）");
                            } else {
                                episodes = eps;
                                mode = MODE_EPISODES;
                                display.clear();
                                for (VodApi.Episode e : episodes) display.add(e.toString());
                                adapter.notifyDataSetChanged();
                                statusText.setText(currentItem.name + "：共 " + episodes.size() + " 集，点一集播放");
                            }
                        }
                    });
                }
            }).start();
        } else if (mode == MODE_EPISODES) {
            if (position >= episodes.size()) return;
            VodApi.Episode ep = episodes.get(position);
            statusText.setText("正在播放：" + currentItem.name + " " + ep.name);
            mode = MODE_PLAYING;
            listView.setVisibility(View.GONE);
            surfaceView.setVisibility(View.VISIBLE);
            if (surfaceReady) player.setVideoSurface(surfaceView.getHolder().getSurface());
            player.stop();
            player.setMediaItem(MediaItem.fromUri(ep.url));
            player.prepare();
            player.play();
            surfaceView.requestFocus();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mode == MODE_PLAYING) {
                if (player != null) player.pause();
                mode = MODE_EPISODES;
                surfaceView.setVisibility(View.GONE);
                listView.setVisibility(View.VISIBLE);
                listView.requestFocus();
                return true;
            }
            if (mode == MODE_EPISODES) {
                mode = MODE_RESULTS;
                display.clear();
                for (VodApi.VodItem it : results) display.add(it.toString());
                adapter.notifyDataSetChanged();
                listView.requestFocus();
                return true;
            }
            if (mode == MODE_RESULTS) {
                showCharts();
                listView.requestFocus();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
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
}
