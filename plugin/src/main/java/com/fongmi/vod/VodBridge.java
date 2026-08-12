package com.fongmi.vod;

import android.text.TextUtils;

import com.alibaba.fastjson.JSONObject;
import com.fongmi.vod.api.SiteApi;
import com.fongmi.vod.api.config.VodConfig;
import com.fongmi.vod.bean.Config;
import com.fongmi.vod.bean.Depot;
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
import java.util.stream.Collectors;

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

    /** HHKan standalone home page. Falls back to WebView for HTTP 850 browser verification. */
    public static void hhkanHome(JsonObject args, UniJSCallback cb) {
        String inputUrl = Json.safeString(args, "url");
        Task.largeExecutor().execute(() -> {
            try {
                cb.invoke(ok(com.fongmi.vod.hhkan.HhkanService.home(inputUrl).toString()));
            } catch (com.fongmi.vod.hhkan.HhkanService.BrowserChallengeException challenge) {
                loadHhkanHome(inputUrl, challenge.url, cb);
            } catch (Exception e) {
                android.util.Log.e("VodPlugin", "hhkan home error", e);
                cb.invoke(error(-2, e.getMessage()));
            }
        });
    }

    /** HHKan standalone search. It shares the verified home DOM and Cookie session. */
    public static void hhkanSearch(JsonObject args, UniJSCallback cb) {
        String inputUrl = Json.safeString(args, "url");
        String keyword = Json.safeString(args, "keyword");
        Task.largeExecutor().execute(() -> {
            try {
                cb.invoke(ok(com.fongmi.vod.hhkan.HhkanService.search(inputUrl, keyword).toString()));
            } catch (com.fongmi.vod.hhkan.HhkanService.BrowserChallengeException challenge) {
                loadHhkanSearch(inputUrl, challenge.url, keyword, cb);
            } catch (Exception e) {
                android.util.Log.e("VodPlugin", "hhkan search error", e);
                cb.invoke(error(-2, e.getMessage()));
            }
        });
    }

    public static void hhkanCategory(JsonObject args, UniJSCallback cb) {
        loadHhkanCategory(Json.safeString(args, "url"), cb);
    }

    private static void loadHhkanCategory(String url, UniJSCallback cb) {
        if (TextUtils.isEmpty(url)) {
            cb.invoke(error(-1, "need url"));
            return;
        }
        com.fongmi.vod.hhkan.HhkanWebLoader.load(url, (finalUrl, html, cookie, loadError) -> {
            if (loadError != null) {
                cb.invoke(error(-2, loadError.getMessage()));
                return;
            }
            Task.largeExecutor().execute(() -> {
                try {
                    com.fongmi.vod.hhkan.HhkanService.rememberPageSession(finalUrl, cookie);
                    cb.invoke(ok(com.fongmi.vod.hhkan.HhkanService.categoryFromHtml(finalUrl, html).toString()));
                } catch (Exception e) {
                    android.util.Log.e("VodPlugin", "hhkan category error", e);
                    cb.invoke(error(-2, e.getMessage()));
                }
            });
        });
    }
    private static void loadHhkanHome(String inputUrl, String challengeUrl, UniJSCallback cb) {
        com.fongmi.vod.hhkan.HhkanWebLoader.load(challengeUrl, (finalUrl, html, cookie, error) -> {
            if (error != null) {
                android.util.Log.e("VodPlugin", "hhkan browser verification error", error);
                cb.invoke(VodBridge.error(-2, error.getMessage()));
                return;
            }
            Task.largeExecutor().execute(() -> {
                try {
                    com.fongmi.vod.hhkan.HhkanService.primeHome(inputUrl, finalUrl, html, cookie);
                    cb.invoke(ok(com.fongmi.vod.hhkan.HhkanService.homeFromHtml(finalUrl, html).toString()));
                } catch (Exception e) {
                    android.util.Log.e("VodPlugin", "hhkan verified home parse error", e);
                    cb.invoke(VodBridge.error(-2, e.getMessage()));
                }
            });
        });
    }

    private static void loadHhkanSearch(String inputUrl, String challengeUrl, String keyword, UniJSCallback cb) {
        com.fongmi.vod.hhkan.HhkanWebLoader.load(challengeUrl, (finalUrl, html, cookie, error) -> {
            if (error != null) {
                android.util.Log.e("VodPlugin", "hhkan browser verification error", error);
                cb.invoke(VodBridge.error(-2, error.getMessage()));
                return;
            }
            Task.largeExecutor().execute(() -> {
                try {
                    com.fongmi.vod.hhkan.HhkanService.primeHome(inputUrl, finalUrl, html, cookie);
                    cb.invoke(ok(com.fongmi.vod.hhkan.HhkanService.search(inputUrl, keyword).toString()));
                } catch (Exception e) {
                    android.util.Log.e("VodPlugin", "hhkan verified search error", e);
                    cb.invoke(VodBridge.error(-2, e.getMessage()));
                }
            });
        });
    }

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
            VodConfig.get().clearDepots();
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

    public static void switchDepot(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            String url = Json.safeString(args, "url");
            Depot target = null;
            for (Depot item : VodConfig.get().getDepots()) {
                if (item.getUrl().equals(url)) {
                    target = item;
                    break;
                }
            }
            if (target == null) {
                cb.invoke(error(-1, "warehouse not found"));
                return;
            }
            Config config = Config.find(target, 0);
            VodConfig.load(config, new Callback() {
                @Override public void start() {}
                @Override public void success() {
                    JSONObject data = new JSONObject();
                    data.put("sites", VodConfig.get().getSites().size());
                    data.put("name", config.getName());
                    data.put("url", config.getUrl());
                    cb.invoke(ok(data));
                }
                @Override public void error(String msg) {
                    cb.invoke(VodBridge.error(-2, msg));
                }
            });
        }, cb);
    }

    public static void home(JsonObject args, UniJSCallback cb) {
        invoke(() -> {
            Site site = VodConfig.get().getHome();
            if (site == null || TextUtils.isEmpty(site.getKey())) {
                android.util.Log.w("VodPlugin", "home skipped: config is not ready");
                cb.invoke(ok(Result.list(new ArrayList<>()).toString()));
                return;
            }
            // A JS spider may perform network I/O during its first initialization. Return from
            // the UniSDK dispatch thread immediately so UI events and the JS timeout can run.
            Task.largeExecutor().execute(() -> {
                long startedAt = System.currentTimeMillis();
                android.util.Log.i("VodPlugin", "home start: " + site.getKey());
                try {
                    Result result = SiteApi.homeContent(site);
                    // Fongmi 在首页点击时会把当前 home site key 一并传给 VideoActivity。
                    // UniApp 列表没有 Activity 上下文，因此把所属站点写回每条 Vod。
                    for (Vod vod : result.getList()) vod.setSite(site);
                    android.util.Log.i("VodPlugin", "home success: " + site.getKey()
                            + ", elapsed=" + (System.currentTimeMillis() - startedAt) + "ms");
                    cb.invoke(ok(result.toString()));
                } catch (Exception e) {
                    android.util.Log.e("VodPlugin", "home error: " + site.getKey()
                            + ", elapsed=" + (System.currentTimeMillis() - startedAt)
                            + "ms, " + e.getMessage(), e);
                    cb.invoke(error(-2, e.getMessage()));
                }
            });
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
            Site site = VodConfig.get().getSite(key);
            for (Vod vod : result.getList()) vod.setSite(site);
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
            // 对标 lyoTV SiteViewModel.SearchTask：quick=true 时仅搜 isQuickSearch() 的站点，
            // 否则不支持的站点会返回空串拖慢整体；同时缩短 deadline 适配前端联想 5s 超时。
            List<Site> allSites = VodConfig.get().getSites().stream()
                .filter(s -> s.getSearchable() != 0)
                .filter(s -> !SiteApi.isBlockedSearchSite(s))
                .filter(s -> !quick || s.isQuickSearch())
                .collect(Collectors.toList());
            // quick 模式总时限 4s（前端 5s 超时留 1s 余量），普通模式 8s
            long deadlineMs = quick ? 4000L : 8000L;
            // quick 模式每站 1.5s 超时，普通模式 3s
            long perSiteMs = quick ? 1500L : 3000L;
            // 最多 10 线程并行
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(Math.max(allSites.size(), 1), 10));
            List<Future<Result>> futures = new ArrayList<>();
            for (Site site : allSites) {
                futures.add(pool.submit(() -> SiteApi.searchContent(site, keyword, quick, page)));
            }
            // 按原版 collectResults 逻辑：逐个收结果，满 50 条或超时则停
            List<Vod> allResults = new ArrayList<>();
            long deadline = System.currentTimeMillis() + deadlineMs;
            for (int i = 0; i < futures.size() && allResults.size() < 50; i++) {
                try {
                    long remaining = Math.max(500, deadline - System.currentTimeMillis());
                    Result result = futures.get(i).get(Math.min(remaining, perSiteMs), TimeUnit.MILLISECONDS);
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
            if (site == null || TextUtils.isEmpty(site.getKey()) || SiteApi.isBlockedSearchSite(site)) {
                App.post(() -> cb.invoke(ok(Result.list(new ArrayList<>()).toString())));
                return;
            }
            // 搜索使用独立小线程池，避免几十个站点挤占 largeExecutor。
            Task.searchExecutor().execute(() -> {
                try {
                    Result result = SiteApi.searchContent(site, keyword, false, "1");
                    JSONObject payload = ok(result.toString());
                    // UniJSCallback 统一回到主线程，规避部分厂商系统的并发桥接兼容问题。
                    App.post(() -> cb.invoke(payload));
                } catch (Throwable e) {
                    android.util.Log.e("VodPlugin", "searchSite error for " + site.getName() + ": " + e.getMessage());
                    App.post(() -> cb.invoke(ok(Result.list(new ArrayList<>()).toString())));
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
