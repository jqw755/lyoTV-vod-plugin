package com.fongmi.vod.utils;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.vod.App;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 直播流本地代理 — 解决 uni-app {@code <video>} 无法注入请求头导致部分直播源 403 的问题。
 * <p>
 * 借鉴 fongmi {@code Server.get().start()} + {@code OkHttp.player()} 的思路：
 * 启动 {@code 127.0.0.1:port} 本地服务器，处理 {@code /live?url=...&header=...} 路径，
 * 用 OkHttp 带头拉 HLS 流并透传字节给 {@code <video>}。
 * <p>
 * 前端把播放 URL 改写为 {@code http://127.0.0.1:port/live?url=<原url>&header=<json>} 即可，
 * 由 {@link #rewrite(String, Map)} 在插件侧完成改写，前端拿到的 url 已是代理地址。
 */
public class LiveProxy {

    private static final String TAG = "LivePlugin";
    /** 默认 UA — 模拟 fongmi OkHttp.player() 默认 UA，咪咕/移动等服务器接受 */
    private static final String DEFAULT_UA = "okhttp/4.12.0";
    private static LiveProxy instance;
    private int port;
    private ServerSocket serverSocket;
    private volatile boolean running;

    private LiveProxy() {}

    public static LiveProxy get() {
        if (instance == null) synchronized (LiveProxy.class) {
            if (instance == null) instance = new LiveProxy();
        }
        return instance;
    }

    /** 启动代理服务器（幂等）。返回端口号，-1 表示失败。 */
    public synchronized int start() {
        if (running) return port;
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            port = serverSocket.getLocalPort();
            running = true;
            new Thread(this::acceptLoop, "live-proxy").start();
            Log.i(TAG, "LiveProxy started on 127.0.0.1:" + port);
            return port;
        } catch (IOException e) {
            Log.e(TAG, "LiveProxy start failed", e);
            return -1;
        }
    }

    /**
     * 将原始直播 URL 无条件改写为本地代理地址。
     * <p>
     * 关键：uni-app {@code <video>} 走系统 MediaPlayer，UA 是 {@code libstagefright} 类，
     * 咪咕/移动等直播服务器拒绝此 UA 返回 403。fongmi 用 ExoPlayer + {@code OkHttp.player()}
     * 默认带 {@code okhttp/x.y.z} UA 服务器接受。
     * 故即使 channel 无自定义 header，也必须走代理注入默认 UA，才能避开 403。
     *
     * @param url    原始直播 URL
     * @param headers channel 自定义 header（可为空）
     * @return 代理改写后的 URL；代理启动失败或 url 非 http 时原样返回
     */
    public String rewrite(String url, Map<String, String> headers) {
        if (TextUtils.isEmpty(url)) return url;
        if (!url.startsWith("http")) return url;
        if (!running && start() < 0) return url;
        try {
            // 合并默认 UA + channel 自定义头（channel 头优先）
            Map<String, String> merged = new HashMap<>();
            merged.put(HttpHeaders.USER_AGENT, DEFAULT_UA);
            if (headers != null && !headers.isEmpty()) merged.putAll(headers);
            StringBuilder sb = new StringBuilder("http://127.0.0.1:").append(port).append("/live?url=");
            sb.append(URLEncoder.encode(url, "UTF-8"));
            sb.append("&header=").append(URLEncoder.encode(App.gson().toJson(merged), "UTF-8"));
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "LiveProxy rewrite failed", e);
            return url;
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
                if (running) Log.e(TAG, "LiveProxy accept failed", e);
            }
        }
        pool.shutdown();
    }

    private void handle(Socket client) {
        Call call = null;
        try (InputStream in = client.getInputStream(); OutputStream out = client.getOutputStream()) {
            // 读请求行（GET /live?... HTTP/1.1）
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = in.read()) != -1 && b != '\n') sb.append((char) b);
            String requestLine = sb.toString().trim();
            if (TextUtils.isEmpty(requestLine) || !requestLine.startsWith("GET /live?")) {
                writeError(out, 400, "Bad Request");
                return;
            }
            // 解析查询参数
            String query = requestLine.split(" ")[1];
            if (query.contains("?")) query = query.substring(query.indexOf("?") + 1);
            String liveUrl = null, headerJson = null;
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) continue;
                String val = URLDecoder.decode(kv[1], "UTF-8");
                if (kv[0].equals("url")) liveUrl = val;
                else if (kv[0].equals("header")) headerJson = val;
            }
            if (TextUtils.isEmpty(liveUrl)) {
                writeError(out, 400, "missing url");
                return;
            }
            // 构建请求头
            okhttp3.Headers.Builder hb = new okhttp3.Headers.Builder();
            if (!TextUtils.isEmpty(headerJson)) {
                try {
                    Map<String, String> map = App.gson().fromJson(headerJson, new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
                    if (map != null) for (Map.Entry<String, String> e : map.entrySet()) {
                        if (!TextUtils.isEmpty(e.getKey()) && e.getValue() != null) hb.add(e.getKey(), e.getValue());
                    }
                } catch (Exception ignored) {}
            }
            Request.Builder reqBuilder = new Request.Builder().url(liveUrl);
            okhttp3.Headers builtHeaders = hb.build();
            if (builtHeaders.size() > 0) reqBuilder.headers(builtHeaders);
            // 拉流并透传字节 — HLS 直播流持续推送，必须用 chunked 或流式写入
            call = OkHttp.client().newCall(reqBuilder.build());
            try (Response resp = call.execute()) {
                if (!resp.isSuccessful()) {
                    writeError(out, resp.code(), resp.message());
                    return;
                }
                ResponseBody body = resp.body();
                if (body == null) {
                    writeError(out, 502, "empty body");
                    return;
                }
                // 透传响应头（Content-Type 必传，video 按 mime 选解码器）
                StringBuilder respHeader = new StringBuilder("HTTP/1.1 200 OK\r\n");
                String contentType = resp.header("Content-Type", "application/octetstream");
                respHeader.append("Content-Type: ").append(contentType).append("\r\n");
                // chunked 透传：不写 Content-Length，用 chunked 编码让 <video> 持续接收
                respHeader.append("Transfer-Encoding: chunked\r\n");
                respHeader.append("Connection: close\r\n\r\n");
                out.write(respHeader.toString().getBytes());
                out.flush();
                // 流式透传字节
                try (InputStream bodyStream = body.byteStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while (running && (n = bodyStream.read(buf)) != -1) {
                        // chunked 编码：<hexLen>\r\n<data>\r\n
                        out.write((Integer.toHexString(n) + "\r\n").getBytes());
                        out.write(buf, 0, n);
                        out.write("\r\n".getBytes());
                        out.flush();
                    }
                    // 结束 chunk
                    out.write("0\r\n\r\n".getBytes());
                    out.flush();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "LiveProxy handle failed", e);
        } finally {
            if (call != null) try { call.cancel(); } catch (Exception ignored) {}
            try { client.close(); } catch (IOException ignored) {}
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
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }
}
