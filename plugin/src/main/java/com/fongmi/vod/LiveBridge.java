package com.fongmi.vod;

import android.text.TextUtils;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fongmi.vod.bean.Channel;
import com.fongmi.vod.bean.Group;
import com.fongmi.vod.bean.Live;
import com.fongmi.vod.bean.Result;
import com.fongmi.vod.live.LiveApi;
import com.fongmi.vod.live.LiveConfig;
import com.fongmi.vod.live.Source;
import com.github.catvod.utils.Json;

import java.util.List;
import java.util.Map;

import io.dcloud.feature.uniapp.bridge.UniJSCallback;

/**
 * 直播桥接层 — 与 VodBridge 平行
 * 接收 LiveModule 的调用，委托给 LiveConfig/LiveApi 执行
 * 统一返回 {code, msg, data} 结构
 */
public class LiveBridge {

    /** 初始化直播订阅源 */
    public static void init(JSONObject args, UniJSCallback cb) {
        if (cb == null) return;
        try {
            String url = args != null ? args.getString("url") : "";
            if (TextUtils.isEmpty(url)) {
                android.util.Log.w("LivePlugin", "liveInit: url 为空");
                cb.invoke(error(-1, "need url"));
                return;
            }
            android.util.Log.i("LivePlugin", "liveInit start, url=" + url);
            LiveConfig.get().init(url);
            int groupCount = LiveConfig.get().getHome().getGroups().size();
            int liveCount = LiveConfig.get().getLives().size();
            android.util.Log.i("LivePlugin", "liveInit done, lives=" + liveCount + " groups=" + groupCount);
            JSONObject data = new JSONObject();
            data.put("groups", groupCount);
            data.put("lives", liveCount);
            cb.invoke(ok(data));
        } catch (Throwable e) {
            android.util.Log.e("LivePlugin", "liveInit error", e);
            cb.invoke(error(-2, e.getMessage()));
        }
    }

    /** 获取分组列表 */
    public static void getGroups(JSONObject args, UniJSCallback cb) {
        if (cb == null) return;
        try {
            Live home = LiveConfig.get().getHome();
            List<Group> groups = home.getGroups();
            android.util.Log.i("LivePlugin", "liveGetGroups: groups=" + groups.size() + " lives=" + LiveConfig.get().getLives().size());
            JSONArray arr = new JSONArray();
            for (Group g : groups) {
                if (g.getName().isEmpty()) continue;
                JSONObject obj = new JSONObject();
                obj.put("name", g.getName());
                obj.put("channelCount", g.getChannel().size());
                arr.add(obj);
            }
            cb.invoke(ok(arr));
        } catch (Throwable e) {
            android.util.Log.e("LivePlugin", "liveGetGroups error", e);
            cb.invoke(error(-2, e.getMessage()));
        }
    }

    /** 获取指定分组的频道列表 */
    public static void getChannels(JSONObject args, UniJSCallback cb) {
        if (cb == null) return;
        try {
            String groupName = args != null ? args.getString("group") : "";
            Live home = LiveConfig.get().getHome();
            Group target = null;
            for (Group g : home.getGroups()) {
                if (g.getName().equals(groupName)) {
                    target = g;
                    break;
                }
            }
            if (target == null) {
                cb.invoke(error(-1, "group not found: " + groupName));
                return;
            }
            JSONArray arr = new JSONArray();
            for (Channel ch : target.getChannel()) {
                JSONObject obj = new JSONObject();
                obj.put("name", ch.getName());
                obj.put("number", ch.getNumber());
                obj.put("logo", ch.getLogo());
                obj.put("tvgId", ch.getTvgId());
                obj.put("displayName", ch.getShow());
                obj.put("currentLine", ch.getIndex());

                JSONArray urlsArr = new JSONArray();
                for (String u : ch.getUrls()) {
                    if (!TextUtils.isEmpty(u)) urlsArr.add(u);
                }
                obj.put("urls", urlsArr);
                arr.add(obj);
            }
            cb.invoke(ok(arr));
        } catch (Throwable e) {
            android.util.Log.e("LivePlugin", "liveGetChannels error", e);
            cb.invoke(error(-2, e.getMessage()));
        }
    }

    /** 获取频道播放地址 */
    public static void getUrl(JSONObject args, UniJSCallback cb) {
        if (cb == null) return;
        try {
            String channelName = args != null ? args.getString("channel") : "";
            String groupName = args != null ? args.getString("group") : "";
            int line = args != null ? args.getIntValue("line") : -1;

            Live home = LiveConfig.get().getHome();
            Channel channel = findChannel(home, groupName, channelName);
            if (channel == null) {
                cb.invoke(error(-1, "channel not found: " + channelName));
                return;
            }
            // 指定线路
            if (line >= 0 && line < channel.getUrls().size()) {
                channel.setIndex(line);
            }
            // 获取真实播放地址
            Source.get().stop();
            Result result = LiveApi.getUrl(channel);

            JSONObject obj = new JSONObject();
            obj.put("url", result.getUrl().v());
            obj.put("line", channel.getIndex());
            // 请求头
            Map<String, String> headers = result.getHeader();
            if (headers != null && !headers.isEmpty()) {
                JSONObject headerObj = new JSONObject();
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    headerObj.put(entry.getKey(), entry.getValue());
                }
                obj.put("header", headerObj);
            }
            cb.invoke(ok(obj));
        } catch (Throwable e) {
            android.util.Log.e("LivePlugin", "liveGetUrl error", e);
            cb.invoke(error(-2, e.getMessage()));
        }
    }

    private static Channel findChannel(Live live, String groupName, String channelName) {
        for (Group g : live.getGroups()) {
            if (!g.getName().equals(groupName)) continue;
            for (Channel ch : g.getChannel()) {
                if (ch.getName().equals(channelName)) return ch;
            }
        }
        return null;
    }

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
