package com.fongmi.vod;

import android.text.TextUtils;

import com.fongmi.vod.api.SiteApi;
import com.fongmi.vod.api.config.VodConfig;
import com.fongmi.vod.bean.Config;
import com.fongmi.vod.bean.Result;
import com.fongmi.vod.bean.Site;
import com.fongmi.vod.impl.Callback;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.HashMap;

import io.dcloud.feature.uniapp.bridge.UniJSCallback;

/**
 * 桥接层：JS 参数 → SiteApi 调用 → 标准 CatVod JSON 回传 UniJSCallback。
 * 所有方法统一返回 {@code {code, msg, data}} 结构，前端据此判断成功/失败。
 */
public class VodBridge {

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** code: 0 成功，-1 参数错，-2 运行时异常 */
    public static void invoke(ThrowingRunnable task, UniJSCallback cb) {
        if (cb == null) return;
        try {
            task.run();
        } catch (Throwable e) {
            cb.invoke(error(-2, e.getMessage()));
        }
    }

    /** 站点订阅加载。args: { url }（订阅地址，支持网络/本地） */
    public static void init(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            String url = Json.safeString(args, "url");
            if (TextUtils.isEmpty(url)) {
                cb.invoke(error(-1, "need url"));
                return;
            }
            Config config = Config.vod().url(url);
            VodConfig.load(config, new Callback() {
                @Override
                public void start() {
                }

                @Override
                public void success() {
                    cb.invoke(ok(new JsonObject()));
                }

                @Override
                public void error(String msg) {
                    cb.invoke(VodBridge.error(-2, msg));
                }
            });
        }, cb);
    }

    public static void home(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            Site site = VodConfig.get().getHome();
            Result result = SiteApi.homeContent(site);
            cb.invoke(ok(result.toString()));
        }, cb);
    }

    public static void category(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            String key = str(args, "key", VodConfig.get().getHome().getKey());
            String tid = Json.safeString(args, "tid");
            String page = str(args, "page", "1");
            boolean filter = bool(args, "filter", false);
            HashMap<String, String> extend = new HashMap<>();
            if (args.has("extend") && args.get("extend").isJsonObject()) {
                for (String k : args.getAsJsonObject("extend").keySet()) extend.put(k, args.getAsJsonObject("extend").get(k).getAsString());
            }
            Result result = SiteApi.categoryContent(key, tid, page, filter, extend);
            cb.invoke(ok(result.toString()));
        }, cb);
    }

    public static void detail(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            String key = str(args, "key", VodConfig.get().getHome().getKey());
            String id = Json.safeString(args, "id");
            Result result = SiteApi.detailContent(key, id);
            cb.invoke(ok(result.toString()));
        }, cb);
    }

    public static void search(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            String keyword = Json.safeString(args, "keyword");
            String page = str(args, "page", "1");
            boolean quick = bool(args, "quick", false);
            Result result = SiteApi.searchContent(VodConfig.get().getHome(), keyword, quick, page);
            cb.invoke(ok(result.toString()));
        }, cb);
    }

    /** 播放解析。args: { flag, id }。parse=1 时插件内部嗅探出真实地址（Phase 3 接 WebSniffer）。 */
    public static void player(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            String key = str(args, "key", VodConfig.get().getHome().getKey());
            String flag = Json.safeString(args, "flag");
            String id = Json.safeString(args, "id");
            Result result = SiteApi.playerContent(key, flag, id);
            cb.invoke(ok(result.toString()));
        }, cb);
    }

    private static String str(JsonObject obj, String key, String def) {
        String v = Json.safeString(obj, key);
        return TextUtils.isEmpty(v) ? def : v;
    }

    private static boolean bool(JsonObject obj, String key, boolean def) {
        try {
            if (!obj.has(key) || obj.get(key).isJsonNull()) return def;
            return obj.getAsJsonPrimitive(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    public static JsonObject ok(Object data) {
        JsonObject obj = new JsonObject();
        obj.addProperty("code", 0);
        obj.addProperty("msg", "ok");
        if (data instanceof String) obj.addProperty("data", (String) data);
        else obj.add("data", App.gson().toJsonTree(data));
        return obj;
    }

    public static JsonObject error(int code, String msg) {
        JsonObject obj = new JsonObject();
        obj.addProperty("code", code);
        obj.addProperty("msg", TextUtils.isEmpty(msg) ? "error" : msg);
        return obj;
    }
}
