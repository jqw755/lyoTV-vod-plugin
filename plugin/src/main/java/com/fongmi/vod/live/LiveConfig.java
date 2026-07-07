package com.fongmi.vod.live;

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
        clear();  // 清空旧数据，防止订阅切换后残留
        String json = url.startsWith("http") ? com.github.catvod.net.OkHttp.string(url) : url;
        if (Json.isObj(json)) {
            JSONObject obj = new JSONObject(json);
            parseHeaders(obj);
            parseLives(obj);
        } else {
            // 纯文本 M3U/TXT
            Live live = new Live(url, url);
            lives = new ArrayList<>();
            lives.add(live);
            LiveParser.text(live, json);
            setHome(live);
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
            home = new Live();
            return;
        }
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

            if (l.has("groups")) {
                JSONArray gArr = l.getJSONArray("groups");
                live.setGroups(Group.arrayFrom(gArr.toString()));
            } else if (!live.getUrl().isEmpty()) {
                LiveParser.start(live);
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

    private static class Loader {
        static volatile LiveConfig INSTANCE = new LiveConfig();
    }
}
