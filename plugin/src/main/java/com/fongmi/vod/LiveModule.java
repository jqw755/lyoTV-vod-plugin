package com.fongmi.vod;

import com.alibaba.fastjson.JSONObject;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * 卫视直播原生模块 — 与 VodModule 平行
 * 处理同一订阅 JSON 中 lives 字段的解析和直播播放
 *
 * 方法列表（4个）：
 *   liveInit(url)        — 初始化直播订阅源
 *   liveGetGroups()      — 获取分组列表
 *   liveGetChannels(group) — 获取某分组的频道
 *   liveGetUrl(ch, group, line) — 获取播放地址
 */
public class LiveModule extends UniModule {

    @UniJSMethod(uiThread = false)
    public void liveInit(JSONObject args, UniJSCallback cb) {
        LiveBridge.init(args, cb);
    }

    @UniJSMethod(uiThread = false)
    public void liveGetGroups(JSONObject args, UniJSCallback cb) {
        LiveBridge.getGroups(args, cb);
    }

    @UniJSMethod(uiThread = false)
    public void liveGetChannels(JSONObject args, UniJSCallback cb) {
        LiveBridge.getChannels(args, cb);
    }

    @UniJSMethod(uiThread = false)
    public void liveGetUrl(JSONObject args, UniJSCallback cb) {
        LiveBridge.getUrl(args, cb);
    }
}
