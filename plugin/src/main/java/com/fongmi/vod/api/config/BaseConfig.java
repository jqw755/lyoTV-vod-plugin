package com.fongmi.vod.api.config;

import android.text.TextUtils;

import com.fongmi.vod.App;
import com.fongmi.vod.bean.Config;
import com.fongmi.vod.impl.Callback;
import com.fongmi.vod.utils.Task;
import com.fongmi.vod.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 从 lyoTV 抽取，剥离 Server（本地代理服务器）/Notify（UI 通知）/ConfigEvent（EventBus）依赖。
 * 插件无本地服务器、无 UI、无 EventBus，加载结果通过 Callback 回传桥接层。
 */
abstract class BaseConfig {

    public static final int VOD = 0;
    public static final int LIVE = 1;
    public static final int WALL = 2;

    private final AtomicInteger taskId = new AtomicInteger(0);

    protected boolean sync;
    protected volatile Config config;
    private volatile Future<?> future;

    protected abstract String getTag();

    protected abstract Config defaultConfig();

    protected abstract void load(Config config) throws Throwable;

    protected abstract boolean isLoaded();

    public synchronized void ensureLoaded() {
        try {
            if (isLoaded()) return;
            if (config == null) config = defaultConfig();
            load(config);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    protected void postEvent() {
        // 插件无 EventBus，加载结果由 Callback 承载
    }

    public boolean needSync(String url) {
        return sync || config == null || TextUtils.isEmpty(config.getUrl()) || url.equals(config.getUrl());
    }

    public Config getConfig() {
        return config == null ? defaultConfig() : config;
    }

    protected void setHeaders(List<Header> headers) {
        OkHttp.responseInterceptor().addAll(headers);
    }

    protected void setProxy(List<Proxy> proxy) {
        OkHttp.selector().addAll(proxy);
    }

    protected void setHosts(List<String> hosts) {
        OkHttp.dns().addAll(hosts);
    }

    public void load(Callback callback) {
        int id = taskId.incrementAndGet();
        if (future != null && !future.isDone()) future.cancel(true);
        future = Task.submit(() -> loadConfig(id, config, callback));
        callback.start();
    }

    protected void loadConfig(int id, Config config, Callback callback) {
        try {
            OkHttp.cancel(getTag());
            load(config);
            if (taskId.get() != id) return;
            if (config.equals(this.config)) config.update();
            App.post(callback::success);
        } catch (Throwable e) {
            android.util.Log.e(getTag(), "loadConfig 异常: " + e.getClass().getName() + ": " + e.getMessage(), e);
            e.printStackTrace();
            if (isCanceled(e)) {
                android.util.Log.w(getTag(), "loadConfig 被取消(忽略回调): " + e.getMessage());
                return;
            }
            if (taskId.get() != id) return;
            if (TextUtils.isEmpty(config.getUrl())) App.post(() -> callback.error(""));
            else App.post(() -> callback.error(e.getMessage() == null ? "error" : e.getMessage()));
        } finally {
            if (taskId.get() == id) postEvent();
        }
    }

    protected boolean isCanceled(Throwable e) {
        if ("Canceled".equals(e.getMessage())) return true;
        if (e instanceof InterruptedException) return true;
        // SocketTimeoutException 是 InterruptedIOException 的子类，但代表真实网络超时，
        // 必须回调 error 让前端感知，不能当作"主动取消"静默丢弃
        if (e instanceof java.net.SocketTimeoutException) return false;
        if (e instanceof InterruptedIOException) return true;
        if (e.getCause() instanceof java.net.SocketTimeoutException) return false;
        return e.getCause() instanceof InterruptedIOException;
    }

    protected JsonArray fetchArray(JsonObject object, String key) {
        if (!object.has(key)) return new JsonArray();
        JsonElement element = object.get(key);
        if (element.isJsonObject()) return new JsonArray();
        if (element.isJsonPrimitive()) element = fetch(element.getAsString());
        JsonArray result = new JsonArray();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) result.addAll(fetch(item.getAsString()));
            else if (item.isJsonObject()) result.add(item);
        }
        return result;
    }

    private JsonArray fetch(String url) {
        try {
            JsonElement parsed = Json.parse(OkHttp.string(UrlUtil.convert(url)));
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}
