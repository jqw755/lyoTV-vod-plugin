package com.fongmi.vod.live.extractor;

import android.net.Uri;
import com.fongmi.vod.bean.Result;

/** 迅雷协议 */
public class Thunder implements Extractor {
    @Override public String fetch(Result r) { return fetch(r.getUrl().v()); }
    @Override public String fetch(String url) { return url; }
    @Override public boolean match(Uri uri) { return "thunder".equals(uri.getScheme()) || "thunderx".equals(uri.getScheme()); }
    @Override public void stop() {}
    @Override public void exit() {}
}
