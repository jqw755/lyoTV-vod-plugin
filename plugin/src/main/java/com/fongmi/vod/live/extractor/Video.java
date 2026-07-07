package com.fongmi.vod.live.extractor;

import android.net.Uri;
import com.fongmi.vod.bean.Result;

/** 标准视频协议 http/rtmp/rtsp/mms */
public class Video implements Extractor {
    @Override public String fetch(Result r) { return r.getUrl().v(); }
    @Override public String fetch(String url) { return url; }
    @Override public boolean match(Uri uri) {
        String s = uri.getScheme();
        return s != null && (s.startsWith("http") || s.startsWith("rtmp") || s.startsWith("rtsp") || s.startsWith("mms"));
    }
    @Override public void stop() {}
    @Override public void exit() {}
}
