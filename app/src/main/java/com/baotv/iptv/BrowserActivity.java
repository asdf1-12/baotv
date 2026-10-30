package com.baotv.iptv;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Built-in browser: Android WebView (Chromium). No VPN, no proxy client. */
public class BrowserActivity extends Activity {
    private WebView web;
    private EditText urlBar;

    private static String normalise(String s) {
        s = s.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            if (s.contains(".")) return "https://" + s;
            return "https://www.bing.com/search?q=" + s;
        }
        return s;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        urlBar = new EditText(this);
        urlBar.setText("https://www.bing.com");
        urlBar.setTextSize(20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(urlBar, lp);
        Button go = new Button(this); go.setText("前往");
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { web.loadUrl(normalise(urlBar.getText().toString())); }
        });
        bar.addView(go);
        Button back = new Button(this); back.setText("后退");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (web.canGoBack()) web.goBack(); }
        });
        bar.addView(back);
        Button fwd = new Button(this); fwd.setText("前进");
        fwd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (web.canGoForward()) web.goForward(); }
        });
        bar.addView(fwd);
        Button close = new Button(this); close.setText("返回播放");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(close);
        root.addView(bar);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        web.loadUrl("https://www.bing.com");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }
}
