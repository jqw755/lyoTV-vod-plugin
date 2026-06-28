package com.fongmi.vod.sniffer;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Message;
import android.view.KeyEvent;
import android.webkit.ClientCertRequest;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import com.fongmi.vod.impl.ParseCallback;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量嗅探器：从原 CustomWebView 抽出的非 UI 核心，基于 WebView 拦截视频格式回应。
 * <p>
 * 原 CustomWebView 功能：显示弹窗 + 拦截视频回应 + 广告过滤。
 * 插件版砍掉 WebDialog（UI）、RuleConfig（广告过滤）、Setting（UA 用默认 Chrome）。
 * <p>
 * 使用方式：
 * <pre>{@code
 *   WebSniffer sniffer = new WebSniffer(context, callback);
 *   sniffer.start(url);
 * }</pre>
 * <p>
 * 注意：必须在主线程且 Activity Context 下创建 WebView。
 * Phase 3 时实现完整逻辑。
 */
@SuppressLint("SetJavaScriptEnabled")
public class WebSniffer {

    private static final Pattern PLAYER = Pattern.compile("https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\\?.*)?|https?://.*?video/tos[^\\s]*|rtmp:[^\\s]+");

    private final WebView webView;
    private final ParseCallback callback;

    public WebSniffer(android.content.Context context, ParseCallback callback) {
        this.callback = callback;
        this.webView = new WebView(context);
        this.webView.getSettings().setJavaScriptEnabled(true);
        this.webView.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36");
        this.webView.setWebViewClient(new SniffClient());
    }

    /** 开始嗅探指定 URL。结果通过 {@link ParseCallback#onParseSuccess(Map, String, String)} 回传。 */
    public void start(String url) {
        webView.loadUrl(url);
    }

    /** 停止加载并销毁 WebView，释放资源。 */
    public void stop() {
        webView.stopLoading();
        webView.destroy();
    }

    private class SniffClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (isVideoFormat(url)) {
                callback.onParseSuccess(Map.of(), url, "");
                return true;
            }
            return super.shouldOverrideUrlLoading(view, request);
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (isVideoFormat(url)) {
                callback.onParseSuccess(Map.of(), url, "");
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            String html = view.getTitle();
            Matcher matcher = PLAYER.matcher(html);
            if (matcher.find()) {
                callback.onParseSuccess(Map.of(), matcher.group(), "");
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            callback.onParseError();
        }
    }

    private boolean isVideoFormat(String url) {
        return PLAYER.matcher(url).find();
    }
}
