package com.fongmi.vod;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.core.os.HandlerCompat;

import com.fongmi.vod.utils.ImageProxy;
import com.google.gson.Gson;

/**
 * 插件全局上下文，替代原 fongmi 的 App Application 类。
 * 由 VodModule 在 uni 初始化时调用 {@link #init(Context)} 注入 Application Context。
 * 注意：原 fongmi 的 App.get() 返回 Application（既是 Context 又是 App 实例）；
 * 插件版拆分为 get()(返回 Context) 和内部 instance()。
 */
public class App {

    private static volatile App instance;

    private final Handler handler;
    private final Gson gson;
    private Context context;
    private volatile Activity activity;

    private App() {
        gson = new Gson();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    private static App instance() {
        if (instance == null) synchronized (App.class) {
            if (instance == null) instance = new App();
        }
        return instance;
    }

    public static void init(Context context) {
        instance().context = context.getApplicationContext();
        // 启动本地图片代理（懒初始化，start() 幂等）
        ImageProxy.get().start();
    }

    public static Gson gson() {
        return instance().gson;
    }

    /** 当 Context 用：Doh.get(App.get())、App.get().getClassLoader() 等 */
    public static Context get() {
        return instance().context;
    }

    public static Activity activity() {
        return instance().activity;
    }

    public static void setActivity(Activity activity) {
        instance().activity = activity;
    }

    public static void post(Runnable runnable) {
        instance().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        instance().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) instance().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        instance().handler.removeCallbacks(runnable);
    }
}
