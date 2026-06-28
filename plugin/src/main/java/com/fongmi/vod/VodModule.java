package com.fongmi.vod;

import com.alibaba.fastjson.JSONObject;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * uni 原生插件入口。JS 侧通过 uni.requireNativePlugin('Fongmi-VodPlugin') 获取本实例，
 * 调用 init/home/category/detail/search/player，结果以 {code,msg,data} JSON 回传。
 *
 * 所有方法均保证回调一定会被调用，不会导致前端 Promise 永久 pending。
 */
public class VodModule extends UniModule {

    private boolean initialized;

    private void ensureInit() {
        if (initialized) return;
        initialized = true;
        try {
            if (mWXSDKInstance != null && mWXSDKInstance.getContext() != null) {
                android.content.Context ctx = mWXSDKInstance.getContext().getApplicationContext();
                App.init(ctx);
                com.github.catvod.Init.set(ctx);
            }
        } catch (Throwable ignored) {
        }
    }

    private com.google.gson.JsonObject args(JSONObject args) {
        ensureInit();
        return args == null ? new com.google.gson.JsonObject() : App.gson().fromJson(args.toJSONString(), com.google.gson.JsonObject.class);
    }

    /** 调用插件方法并保证回调必达 */
    private void safeCall(JSONObject args, UniJSCallback cb, java.util.function.BiConsumer<com.google.gson.JsonObject, UniJSCallback> action) {
        if (cb == null) return;
        try {
            action.accept(args(args), cb);
        } catch (Throwable e) {
            e.printStackTrace();
            cb.invoke(error(-2, e.getMessage()));
        }
    }

    private static com.google.gson.JsonObject error(int code, String msg) {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty("code", code);
        obj.addProperty("msg", msg == null ? "error" : msg);
        return obj;
    }

    @UniJSMethod(uiThread = false)
    public void init(JSONObject args, UniJSCallback cb) {
        safeCall(args, cb, VodBridge::init);
    }

    @UniJSMethod(uiThread = false)
    public void home(JSONObject args, UniJSCallback cb) {
        safeCall(args, cb, VodBridge::home);
    }

    @UniJSMethod(uiThread = false)
    public void category(JSONObject args, UniJSCallback cb) {
        safeCall(args, cb, VodBridge::category);
    }

    @UniJSMethod(uiThread = false)
    public void detail(JSONObject args, UniJSCallback cb) {
        safeCall(args, cb, VodBridge::detail);
    }

    @UniJSMethod(uiThread = false)
    public void search(JSONObject args, UniJSCallback cb) {
        safeCall(args, cb, VodBridge::search);
    }

    @UniJSMethod(uiThread = false)
    public void player(JSONObject args, UniJSCallback cb) {
        safeCall(args, cb, VodBridge::player);
    }
}
