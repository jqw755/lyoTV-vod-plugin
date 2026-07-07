package com.fongmi.vod.live.extractor;

import android.net.Uri;
import com.fongmi.vod.bean.Result;

/** 剪片协议 — 直接返回原URL */
public class JianPian implements Extractor {
    @Override public String fetch(Result r) { return fetch(r.getUrl().v()); }
    @Override public String fetch(String url) { return url; }
    @Override public boolean match(Uri uri) { return "jianpian".equals(uri.getScheme()) || "jp".equals(uri.getScheme()); }
    @Override public void stop() {}
    @Override public void exit() {}
}
