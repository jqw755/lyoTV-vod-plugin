package com.fongmi.vod.hhkan;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.fongmi.vod.App;

import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the system WebView to complete the site's normal JavaScript browser verification. */
public final class HhkanWebLoader {
    private static final long TIMEOUT_MS = 20_000L;
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    public interface Callback {
        void onResult(String finalUrl, String html, String cookie, Throwable error);
    }

    private HhkanWebLoader() {}

    public static void load(String url, Callback callback) {
        new Handler(Looper.getMainLooper()).post(() -> create(url, callback));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static void create(String url, Callback callback) {
        Context context = App.activity() != null ? App.activity() : App.get();
        if (context == null) {
            callback.onResult(url, "", "", new IllegalStateException("Android context is unavailable"));
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicBoolean finished = new AtomicBoolean(false);
        WebView webView = new WebView(context);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(UA);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        Runnable timeout = () -> finish(webView, finished, callback, url, "", cookies.getCookie(url),
                new IllegalStateException("HHKan browser verification timed out"));
        handler.postDelayed(timeout, TIMEOUT_MS);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String finalUrl) {
                if (finished.get()) return;
                view.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''", value -> {
                    if (finished.get()) return;
                    String html;
                    try {
                        html = App.gson().fromJson(value, String.class);
                    } catch (Exception e) {
                        html = "";
                    }
                    if (html == null || html.length() < 300 || html.contains("Protected by cdndefend") || !html.contains("</html>")) return;
                    handler.removeCallbacks(timeout);
                    finish(view, finished, callback, finalUrl, html, cookies.getCookie(finalUrl), null);
                });
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
        webView.loadUrl(url);
    }

    private static void finish(WebView webView, AtomicBoolean finished, Callback callback,
                               String finalUrl, String html, String cookie, Throwable error) {
        if (!finished.compareAndSet(false, true)) return;
        try {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
        } catch (Throwable ignored) {
        }
        callback.onResult(finalUrl, html == null ? "" : html, cookie == null ? "" : cookie, error);
    }
}
