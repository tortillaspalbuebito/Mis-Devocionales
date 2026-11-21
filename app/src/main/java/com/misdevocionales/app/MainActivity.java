package com.misdevocionales.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.util.Locale;

/** Mis Devocionales: muestra la app (HTML) y le da voz para leer en voz alta, copiar y abrir enlaces. */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        web.setBackgroundColor(Color.parseColor(night ? "#0F172A" : "#F7F1E8"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(false);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new AppBridge(), "AndroidApp");

        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("https://" + HOST + "/assets/index.html");

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(new Locale("es", "MX"));
            if (r < 0) r = tts.setLanguage(new Locale("es", "US"));
            if (r < 0) tts.setLanguage(new Locale("es"));
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { notifyJs(id, false); }
                @Override public void onError(String id) { notifyJs(id, true); }
                @Override public void onStop(String id, boolean interrupted) { }
            });
            ttsReady = true;
        });
    }

    private void notifyJs(String id, boolean error) {
        final String js = "window.__ttsDone && window.__ttsDone(" + jsString(id) + "," + error + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private static String jsString(String v) {
        return "'" + (v == null ? "" : v.replace("\\", "\\\\").replace("'", "\\'")) + "'";
    }

    private void openExternal(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay una app para abrir este enlace", Toast.LENGTH_SHORT).show();
        }
    }

    /** Funciones que la página puede llamar como window.AndroidApp.xxx() */
    class AppBridge {
        @JavascriptInterface public boolean isApp() { return true; }
        @JavascriptInterface public int versionCode() {
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                return android.os.Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception e) { return 0; }
        }
        @JavascriptInterface public boolean canSpeak() { return ttsReady; }
        @JavascriptInterface public void speak(String text, float rate, String id) {
            if (!ttsReady || text == null) { notifyJs(id, true); return; }
            tts.setSpeechRate(rate > 0 ? rate : 1f);
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, id);
        }
        @JavascriptInterface public void stopSpeak() { if (ttsReady) tts.stop(); }
        @JavascriptInterface public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Mis Devocionales", text == null ? "" : text));
            });
        }
        @JavascriptInterface public void openUrl(String url) {
            runOnUiThread(() -> openExternal(Uri.parse(url)));
        }
        /** Pinta la barra de arriba (hora y batería) y la de abajo (botones de Android) con los colores de la app. */
        @JavascriptInterface public void setBars(String top, boolean topLight, String bottom, boolean bottomLight) {
            runOnUiThread(() -> {
                try {
                    android.view.Window w = getWindow();
                    w.setStatusBarColor(Color.parseColor(top));
                    w.setNavigationBarColor(Color.parseColor(bottom));
                    android.view.View d = w.getDecorView();
                    int f = d.getSystemUiVisibility();
                    f = topLight ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                    if (android.os.Build.VERSION.SDK_INT >= 26)
                        f = bottomLight ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                    d.setSystemUiVisibility(f);
                } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public void exitApp() { runOnUiThread(() -> finish()); }
    }

    @Override
    public void onBackPressed() {
        // El botón «atrás» cierra ventanas o regresa a la pantalla anterior; si ya no hay a dónde regresar, cierra la app.
        web.evaluateJavascript("(window.appBack ? appBack() : false)", value -> {
            if (!"true".equals(value)) finish();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.evaluateJavascript("window.appPause && appPause()", null);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
