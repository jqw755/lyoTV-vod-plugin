package com.fongmi.vod.utils;

import android.content.Context;
import android.content.res.Configuration;

import com.fongmi.vod.App;

/** 插件版精简 ResUtil：只保留源解析链路用到的 getString/isLand，其余砍。 */
public class ResUtil {

    public static String getString(int resId) {
        try {
            return App.get().getResources().getString(resId);
        } catch (Exception e) {
            return "";
        }
    }

    public static String getString(int resId, Object... formatArgs) {
        try {
            return App.get().getResources().getString(resId, formatArgs);
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean isLand(Context context) {
        return context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
}
