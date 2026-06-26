package com.fongmi.vod.utils;

import android.app.Notification;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

/** 插件版精简 Notify：原 fongmi 用 Toast/对话框/通知提示 UI，插件无 UI，全部降级为日志或空。 */
public class Notify {

    private static final String TAG = "VodPlugin";

    public static void createChannel() {}

    public static String getError(int resId, Throwable e) {
        if (e == null || TextUtils.isEmpty(e.getMessage())) return "";
        return e.getMessage();
    }

    public static void show(Notification notification) {}

    public static void show(int resId) {}

    public static void show(String text) {
        if (!TextUtils.isEmpty(text)) Log.d(TAG, text);
    }

    public static void progress(Context context) {}

    public static void dismiss() {}
}
