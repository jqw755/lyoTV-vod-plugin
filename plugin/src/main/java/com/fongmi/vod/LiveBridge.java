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
            // fastjson getString/getIntValue 对缺失 key 返回 null，每步必须判空
            String channelName = "";
            String groupName = "";
            int line = 0;
            if (args != null) {
                String ch = args.getString("channel");
                if (ch != null) channelName = ch;
                if (channelName.isEmpty()) {
                    Object chId = args.get("channelId");
                    if (chId instanceof Number) channelName = String.valueOf(chId);
                    else if (chId instanceof String) channelName = (String) chId;
                }
                String g = args.getString("group");
                if (g != null) groupName = g;
                Integer l = args.getInteger("line");
                if (l != null) line = l;
            }
            android.util.Log.i("LivePlugin", "liveGetUrl channel=" + channelName + " group=" + groupName + " line=" + line);

            Live home = LiveConfig.get().getHome();
            Channel channel = findChannel(home, groupName, channelName);
            if (channel == null) {
                // 诊断：打印所有分组和频道名
                StringBuilder sb = new StringBuilder("available: ");
                for (Group g : home.getGroups()) {
                    for (Channel c : g.getChannel()) {
                        if (sb.length() < 300) sb.append("[").append(g.getName()).append("]").append(c.getName()).append(" ");
                    }
                }
                android.util.Log.w("LivePlugin", "channel not found: " + channelName + " in group=" + groupName + " " + sb);
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

            // 直接返回原始 url + header：前端 uni-app <video> 的 header 属性（App 3.1.19+）直接注入 Referer/UA，
            // 比 LiveProxy 代理透传字节流更可靠（HLS 分片 .ts 透传 chunked 编码 <video> 可能无法识别 MIME）
            String playUrl = result.getUrl().v();
            android.util.Log.i("LivePlugin", "liveGetUrl url=" + playUrl);

            JSONObject obj = new JSONObject();
            obj.put("url", playUrl);
            obj.put("line", channel.getIndex());
            // 请求头传给前端 <video :header="...">：补默认 UA 兜底（咪咕等服务器校验 UA）
            Map<String, String> headers = result.getHeader();
            if (headers == null) headers = new java.util.HashMap<>();
            if (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent")) {
                headers.put("User-Agent", "okhttp/4.12.0");
            }
            JSONObject headerObj = new JSONObject();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                headerObj.put(entry.getKey(), entry.getValue());
            }
            obj.put("header", headerObj);
            cb.invoke(ok(obj));
        } catch (Throwable e) {
            android.util.Log.e("LivePlugin", "liveGetUrl error", e);
            cb.invoke(error(-2, e.getMessage()));
        }
    }

    /**
     * 多字段匹配查找频道，对齐原版 fongmi Channel.equals 的 name/number 双匹配逻辑。
     * 前端传 displayName（即 getShow() = number + " " + name），也传 tvgId / number / name。
     */
    private static Channel findChannel(Live live, String groupName, String channelId) {
        if (channelId == null || channelId.isEmpty()) return null;
        for (Group g : live.getGroups()) {
            if (groupName != null && !groupName.isEmpty() && !g.getName().equals(groupName)) continue;
            for (Channel ch : g.getChannel()) {
                if (channelId.equals(ch.getName())) return ch;
                if (channelId.equals(ch.getNumber())) return ch;
                if (channelId.equals(ch.getTvgId())) return ch;
                if (channelId.equals(ch.getTvgName())) return ch;
                if (channelId.equals(ch.getShow())) return ch;
                // 数字字符串兼容：前端可能传 "1" 而 number 是 "1" 或 "001"
                try {
                    int num = Integer.parseInt(channelId);
                    if (ch.getNumber().equals(String.valueOf(num))) return ch;
                } catch (NumberFormatException ignored) {}
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
