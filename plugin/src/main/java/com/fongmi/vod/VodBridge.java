package com.fongmi.vod;

import android.text.TextUtils;

import com.alibaba.fastjson.JSONObject;
import com.fongmi.vod.api.SiteApi;
import com.fongmi.vod.api.config.VodConfig;
import com.fongmi.vod.bean.Config;
import com.fongmi.vod.bean.Result;
import com.fongmi.vod.bean.Site;
import com.fongmi.vod.bean.Vod;
import com.fongmi.vod.impl.Callback;
import com.fongmi.vod.utils.Task;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.dcloud.feature.uniapp.bridge.UniJSCallback;

/**
 * 桥接层：JS 参数 → SiteApi 调用 → 标准 CatVod JSON 回传 UniJSCallback。
 * 所有方法统一返回 {@code {code, msg, data}} 结构，前端据此判断成功/失败。
 *
 * 重要：回调数据使用 fastjson 的 JSONObject（而非 Gson JsonObject），
 * 因为 weex 的 UniJSCallback.invoke 内部用 fastjson 序列化 Java→JS，
 * 传入 Gson JsonObject 会抛 JSONException: toJSON error 导致回调丢失。
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
            android.util.Log.e("VodPlugin", "invoke catch异常: " + e.getClass().getName() + ": " + e.getMessage(), e);
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
                    JSONObject data = new JSONObject();
                    data.put("sites", VodConfig.get().getSites().size());
                    cb.invoke(ok(data));
                }

                @Override
                public void error(String msg) {
                    android.util.Log.e("VodPlugin", "VodConfig.load error: " + msg);
                    cb.invoke(VodBridge.error(-2, msg));
                }
            });
        }, cb);
    }

    public static void home(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            Site site = VodConfig.get().getHome();
            if (site == null || TextUtils.isEmpty(site.getKey())) {
                android.util.Log.w("VodPlugin", "home 跳过：订阅尚未加载完成");
                cb.invoke(ok(Result.list(new ArrayList<>()).toString()));
                return;
            }
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
            // 异步执行，不阻塞 UniSDK 调度线程
            Task.largeExecutor().execute(() -> {
                try {
                    Result result = SiteApi.detailContent(key, id);
                    cb.invoke(ok(result.toString()));
                } catch (Exception e) {
                    android.util.Log.e("VodPlugin", "detail error: " + e.getMessage());
                    cb.invoke(ok(Result.list(new ArrayList<>()).toString()));
                }
            });
        }, cb);
    }

    public static void search(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            String keyword = Json.safeString(args, "keyword");
            String page = str(args, "page", "1");
            boolean quick = bool(args, "quick", false);
            // 对标 lyoTV VodBrowse.search()：largeExecutor 并行搜全部 searchable 站点
            List<Site> allSites = VodConfig.get().getSites().stream().filter(s -> s.getSearchable() != 0).toList();
            // 最多 10 线程并行，每个站 3 秒超时，总时限 8 秒
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(allSites.size(), 10));
            List<Future<Result>> futures = new ArrayList<>();
            for (Site site : allSites) {
                futures.add(pool.submit(() -> SiteApi.searchContent(site, keyword, quick, page)));
            }
            // 按原版 collectResults 逻辑：逐个收结果，满 50 条或超时则停
            List<Vod> allResults = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 8000;
            for (int i = 0; i < futures.size() && allResults.size() < 50; i++) {
                try {
                    long remaining = Math.max(500, deadline - System.currentTimeMillis());
                    Result result = futures.get(i).get(Math.min(remaining, 3000), TimeUnit.MILLISECONDS);
                    allResults.addAll(result.getList());
                } catch (TimeoutException e) {
                } catch (Exception e) {
                    android.util.Log.e("VodPlugin", "search error for site " + allSites.get(i).getName() + ": " + e.getMessage());
                }
                if (System.currentTimeMillis() > deadline) break;
            }
            pool.shutdownNow();
            // 原版按 matchScore 排序 → 简化为直接按 name 匹配度排序
            String kw = keyword.toLowerCase();
            allResults.sort((a, b) -> {
                boolean aMatch = a.getName().toLowerCase().contains(kw);
                boolean bMatch = b.getName().toLowerCase().contains(kw);
                if (aMatch && !bMatch) return -1;
                if (!aMatch && bMatch) return 1;
                return 0;
            });
            if (allResults.size() > 50) allResults = allResults.subList(0, 50);
            cb.invoke(ok(Result.list(allResults).toString()));
        }, cb);
    }

    /**
     * 搜索单个站点（前端分站展示用）。
     * 对标 ViewModelSearchRunner：提交到 Task.largeExecutor() 异步执行，
     * 不阻塞 UniSDK 调度线程，确保每个站点的结果一旦返回就立即回调前端，
     * 实现肉眼可见的逐个站点渐进加载效果。
     */
    public static void searchSite(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            String keyword = Json.safeString(args, "keyword");
            String siteKey = Json.safeString(args, "siteKey");
            Site site = VodConfig.get().getSite(siteKey);
            if (TextUtils.isEmpty(site.getKey())) {
                cb.invoke(ok(Result.list(new ArrayList<>()).toString()));
                return;
            }
            // 对标 ViewModelSearchRunner: 提交到共享线程池，不阻塞当前调度线程
            Task.largeExecutor().execute(() -> {
                try {
                    Result result = SiteApi.searchContent(site, keyword, false, "1");
                    // site 信息会通过 Vod.setSite() 自动携带 site_name
                    cb.invoke(ok(result.toString()));
                } catch (Exception e) {
                    android.util.Log.e("VodPlugin", "searchSite error for " + site.getName() + ": " + e.getMessage());
                    cb.invoke(ok(Result.list(new ArrayList<>()).toString()));
                }
            });
        }, cb);
    }

    /** 播放解析。args: { key, flag, id }。返回爬虫给出的播放地址（parse=1 时由前端自行嗅探）。 */
    public static void player(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            String key = str(args, "key", VodConfig.get().getHome().getKey());
            String flag = Json.safeString(args, "flag");
            String id = Json.safeString(args, "id");
            // 异步执行，不阻塞 UniSDK 调度线程
            Task.largeExecutor().execute(() -> {
                try {
                    Result result = SiteApi.playerContent(key, flag, id);
                    cb.invoke(ok(result.toString()));
                } catch (Exception e) {
                    android.util.Log.e("VodPlugin", "player error: " + e.getMessage());
                    cb.invoke(ok(Result.list(new ArrayList<>()).toString()));
                }
            });
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

    /** 返回 fastjson JSONObject，weex UniJSCallback 原生支持，避免 Gson 对象序列化失败 */
    public static JSONObject ok(Object data) {
        JSONObject obj = new JSONObject();
        obj.put("code", 0);
        obj.put("msg", "ok");
        if (data != null) obj.put("data", data);
        return obj;
    }

    public static JSONObject error(int code, String msg) {
        JSONObject obj = new JSONObject();
        obj.put("code", code);
        obj.put("msg", TextUtils.isEmpty(msg) ? "error" : msg);
        return obj;
    }
}
