package com.fongmi.vod.live;

import com.fongmi.vod.bean.Channel;
import com.fongmi.vod.bean.Group;
import com.fongmi.vod.bean.Live;
import com.github.catvod.utils.Json;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 直播配置管理器
 * 管理订阅JSON中lives字段的解析和缓存
 */
public class LiveConfig {

    private static final String TAG = "LiveConfig";

    private Live home;
    private List<Live> lives;
    private Map<String, String> headers;

    private LiveConfig() {
        lives = new ArrayList<>();
        home = new Live();
        headers = new HashMap<>();
    }

    public static LiveConfig get() {
        return Loader.INSTANCE;
    }

    /** 清空缓存（切换订阅时调用） */
    public synchronized void clear() {
        home = new Live();
        lives = new ArrayList<>();
        headers = new HashMap<>();
    }

    /** 初始化：拉取订阅JSON → 解析lives → LiveParser解析频道 */
    public synchronized void init(String url) throws Exception {
        android.util.Log.i("LivePlugin", "LiveConfig.init url=" + url);
        clear();  // 清空旧数据，防止订阅切换后残留
        // 与 VodConfig 对齐：用 Decoder.getJson 处理加密/编码内容
        String json = com.fongmi.vod.api.Decoder.getJson(com.fongmi.vod.utils.UrlUtil.convert(url), "LiveConfig");
        android.util.Log.i("LivePlugin", "LiveConfig.init fetched, len=" + (json == null ? 0 : json.length()) + " isObj=" + Json.isObj(json));
        if (Json.isObj(json)) {
            JSONObject obj = new JSONObject(json);
            parseHeaders(obj);
            parseLives(obj);
            android.util.Log.i("LivePlugin", "LiveConfig.init done, lives=" + lives.size() + " groups=" + getHome().getGroups().size());
        } else {
            // 纯文本 M3U/TXT
            Live live = new Live(url, url);
            lives = new ArrayList<>();
            lives.add(live);
            LiveParser.text(live, json);
            setHome(live);
            android.util.Log.i("LivePlugin", "LiveConfig.init text-mode done, groups=" + getHome().getGroups().size());
        }
    }

    private void parseHeaders(JSONObject obj) {
        JSONArray arr = obj.optJSONArray("headers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject h = arr.optJSONObject(i);
                if (h != null) headers.put(h.optString("key", ""), h.optString("value", ""));
            }
        }
    }

    private void parseLives(JSONObject obj) throws Exception {
        JSONArray livesArr = obj.optJSONArray("lives");
        if (livesArr == null || livesArr.length() == 0) {
            StringBuilder ks = new StringBuilder();
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) { if (ks.length() > 0) ks.append(","); ks.append(it.next()); }
            android.util.Log.w("LivePlugin", "parseLives: 订阅JSON无 lives 字段或为空, keys=[" + ks + "]");
            home = new Live();
            return;
        }
        android.util.Log.i("LivePlugin", "parseLives: lives 数量=" + livesArr.length());
        lives = new ArrayList<>();
        for (int i = 0; i < livesArr.length(); i++) {
            JSONObject l = livesArr.getJSONObject(i);
            Live live = new Live();
            if (l.has("name")) live.setName(l.optString("name"));
            if (l.has("url")) live.setUrl(l.optString("url"));
            if (l.has("api")) live.setApi(l.optString("api"));
            if (l.has("ext")) live.setExt(l.optString("ext"));
            if (l.has("jar")) live.setJar(l.optString("jar"));
            if (l.has("pass")) live.setPass(l.optBoolean("pass"));
            if (l.has("ua")) live.setUa(l.optString("ua"));
            if (l.has("referer")) live.setReferer(l.optString("referer"));

            android.util.Log.i("LivePlugin", "parseLives[" + i + "] name=" + live.getName()
                + " url=" + (live.getUrl().isEmpty() ? "(无)" : live.getUrl())
                + " hasGroups=" + l.has("groups")
                + " keys=" + l.keys().toString());

            if (l.has("groups")) {
                android.util.Log.i("LivePlugin", "parseLives[" + i + "]: 内嵌JSON groups, name=" + live.getName());
                JSONArray gArr = l.getJSONArray("groups");
                live.setGroups(Group.arrayFrom(gArr.toString()));
                android.util.Log.i("LivePlugin", "parseLives[" + i + "]: JSON groups解析完成, groups=" + live.getGroups().size() + " 首个group频道数=" + (live.getGroups().isEmpty() ? 0 : live.getGroups().get(0).getChannel().size()));
            }
            // 对齐 fongmi 原版 LiveConfig.initLive：JSON 已含 groups 元数据时不再拉远端 M3U/TXT，
            // 由 LiveParser.start 的判空短路兜底（已含 groups 直接 return）。
            // 仅当 JSON 没给 groups 但给了 url 时，才同步拉 M3U/TXT 解析频道——这是 fongmi 的延迟解析策略。
            if (live.getGroups().isEmpty() && !live.getUrl().isEmpty()) {
                android.util.Log.i("LivePlugin", "parseLives[" + i + "]: JSON 无 groups 元数据，拉取M3U/TXT url=" + live.getUrl());
                LiveParser.start(live);
                android.util.Log.i("LivePlugin", "parseLives[" + i + "]: M3U/TXT解析完成, groups=" + live.getGroups().size());
            } else if (!live.getGroups().isEmpty()) {
                android.util.Log.i("LivePlugin", "parseLives[" + i + "]: 沿用 JSON 内嵌 groups，跳过拉取M3U/TXT");
            } else {
                android.util.Log.w("LivePlugin", "parseLives[" + i + "]: url为空且无groups元数据，无法解析");
            }
            lives.add(live);
        }
        if (!lives.isEmpty()) setHome(lives.get(0));
    }

    private void setHome(Live live) {
        this.home = live;
        this.home.setSelected(true);
    }

    public Live getHome() {
        return home == null ? new Live() : home;
    }

    public List<Live> getLives() {
        return lives == null ? new ArrayList<>() : lives;
    }

    public boolean isEmpty() {
        return getHome().isEmpty();
    }

    public boolean isLoaded() {
        return !getLives().isEmpty() && !getHome().getGroups().isEmpty();
    }

    private static class Loader {
        static volatile LiveConfig INSTANCE = new LiveConfig();
    }
}
