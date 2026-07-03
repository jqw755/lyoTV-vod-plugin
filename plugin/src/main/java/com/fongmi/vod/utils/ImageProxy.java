package com.fongmi.vod.utils;

import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 本地图片代理，复刻 lyoTV ImgUtil.getUrl() 全部逻辑：
 * 前端 &lt;image&gt; 无法设置自定义请求头，故插件启动本服务代为下载图片并带上 Referer/User-Agent/Cookie/Headers。
 * <p>
 * 调用方式：{@link #rewrite(String)} 将含 @Referer= 等后缀的 URL 转为 http://127.0.0.1:{port}/img?…，
 * 前端直接加载该地址即可。
 */
public class ImageProxy {

    private static final String TAG = "VodPlugin";
    private static ImageProxy instance;
    private int port;
    private ServerSocket serverSocket;
    private volatile boolean running;

    private ImageProxy() {
    }

    public static ImageProxy get() {
        if (instance == null) synchronized (ImageProxy.class) {
            if (instance == null) instance = new ImageProxy();
        }
        return instance;
    }

    /** 启动代理服务器（幂等，仅首次有效）。返回端口号，-1 表示失败。 */
    public synchronized int start() {
        if (running) return port;
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            port = serverSocket.getLocalPort();
            running = true;
            new Thread(this::acceptLoop, "img-proxy").start();
            return port;
        } catch (IOException e) {
            return -1;
        }
    }

    // ========== 外部入口 ==========

    /**
     * 复刻 lyoTV ImgUtil.getUrl() 的 header 提取逻辑，将含 @Referer= 等后缀的 URL
     * 转为本地代理地址。若 URL 无后缀则原样返回。
     */
    public String rewrite(String picUrl) {
        if (TextUtils.isEmpty(picUrl)) return picUrl;
        String url = picUrl.trim();
        // 无爬虫后缀则直接返回
        if (!url.contains("@")) return url;
        // 提取各 header
        String referer = null, ua = null, cookie = null, rawHeaders = null;
        String param;
        if (url.contains("@Referer=")) {
            param = url.split("@Referer=")[1].split("@")[0];
            referer = param;
        }
        if (url.contains("@User-Agent=")) {
            param = url.split("@User-Agent=")[1].split("@")[0];
            ua = param;
        }
        if (url.contains("@Cookie=")) {
            param = url.split("@Cookie=")[1].split("@")[0];
            cookie = param;
        }
        if (url.contains("@Headers=")) {
            param = url.split("@Headers=")[1].split("@")[0];
            rawHeaders = param;
        }
        // 拿到干净 URL（去掉 @ 后缀）
        String cleanUrl = url.split("@")[0];
        // 若无需自定义 header，直接返回干净 URL
        if (referer == null && ua == null && cookie == null && rawHeaders == null) {
            return cleanUrl;
        }
        // 构建代理 URL，将所有 header 信息编码为查询参数
        try {
            StringBuilder sb = new StringBuilder("http://127.0.0.1:").append(port).append("/img?url=");
            sb.append(URLEncoder.encode(cleanUrl, "UTF-8"));
            if (referer != null) sb.append("&referer=").append(URLEncoder.encode(referer, "UTF-8"));
            if (ua != null) sb.append("&ua=").append(URLEncoder.encode(ua, "UTF-8"));
            if (cookie != null) sb.append("&cookie=").append(URLEncoder.encode(cookie, "UTF-8"));
            if (rawHeaders != null) sb.append("&headers=").append(URLEncoder.encode(rawHeaders, "UTF-8"));
            return sb.toString();
        } catch (Exception e) {
            return cleanUrl;
        }
    }

    // ========== HTTP 服务端 ==========

    private void acceptLoop() {
        ExecutorService pool = Executors.newCachedThreadPool();
        while (running) {
            try {
                Socket client = serverSocket.accept();
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                if (running) Log.e(TAG, "ImageProxy accept 异常", e);
            }
        }
        pool.shutdown();
    }

    private void handle(Socket client) {
        try (
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream()
        ) {
            // 只读请求行
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != -1 && b != '\n') sb.append((char) b);
            String requestLine = sb.toString().trim();
            if (TextUtils.isEmpty(requestLine) || !requestLine.startsWith("GET /img?")) {
                writeError(out, 400, "Bad Request");
                return;
            }

            // 解析查询参数
            String query = requestLine.split(" ")[1];
            if (query.contains("?")) query = query.substring(query.indexOf("?") + 1);
            String imgUrl = null, referer = null, ua = null, cookie = null, rawHeaders = null;
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) continue;
                String val = URLDecoder.decode(kv[1], "UTF-8");
                switch (kv[0]) {
                    case "url" -> imgUrl = val;
                    case "referer" -> referer = val;
                    case "ua" -> ua = val;
                    case "cookie" -> cookie = val;
                    case "headers" -> rawHeaders = val;
                }
            }

            if (TextUtils.isEmpty(imgUrl)) {
                writeError(out, 400, "missing url");
                return;
            }

            // —— 复刻 lyoTV ImgUtil.getUrl() 的 Header 构建逻辑 ——
            okhttp3.Headers.Builder headerBuilder = new okhttp3.Headers.Builder();
            if (rawHeaders != null) {
                try {
                    Map<String, String> map = Json.toMap(Json.parse(rawHeaders));
                    for (Map.Entry<String, String> e : map.entrySet()) {
                        headerBuilder.add(UrlUtil.fixHeader(e.getKey()), e.getValue());
                    }
                } catch (Exception ignored) {
                }
            }
            if (cookie != null) headerBuilder.add(HttpHeaders.COOKIE, cookie);
            if (referer != null) headerBuilder.add(HttpHeaders.REFERER, referer);
            if (ua != null) headerBuilder.add(HttpHeaders.USER_AGENT, ua);

            Request.Builder reqBuilder = new Request.Builder().url(imgUrl);
            if (rawHeaders != null || cookie != null || referer != null || ua != null) {
                reqBuilder.headers(headerBuilder.build());
            }
            try (Response resp = OkHttp.client().newCall(reqBuilder.build()).execute()) {
                if (!resp.isSuccessful()) {
                    writeError(out, resp.code(), resp.message());
                    return;
                }
                byte[] body = resp.body() == null ? new byte[0] : resp.body().bytes();
                String contentType = resp.header("Content-Type", "image/jpeg");
                String response = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(response.getBytes());
                out.write(body);
                out.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "ImageProxy handle 异常", e);
        } finally {
            try { client.close(); } catch (IOException ignored) {
            }
        }
    }

    private void writeError(OutputStream out, int code, String msg) throws IOException {
        String body = "{\"error\":\"" + msg + "\"}";
        String response = "HTTP/1.1 " + code + " " + msg + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
        out.write(response.getBytes());
        out.flush();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }
}
