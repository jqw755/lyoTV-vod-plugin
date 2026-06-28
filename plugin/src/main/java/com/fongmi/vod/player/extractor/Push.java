package com.fongmi.vod.player.extractor;

import android.net.Uri;

import com.fongmi.vod.utils.UrlUtil;

public class Push implements Source.Extractor {

    @Override
    public boolean match(Uri uri) {
        return "push".equals(UrlUtil.scheme(uri));
    }

    @Override
    public String fetch(String url) throws Exception {
        // 插件无播放器 UI：原 fongmi 这里拉起 VideoActivity 推流到播放页，插件版直接回传地址给前端
        return url.startsWith("push://") ? url.substring(7) : url;
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
    }
}
