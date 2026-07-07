package com.fongmi.vod.live.extractor;

import android.net.Uri;
import com.fongmi.vod.bean.Result;

/** force:// 协议 — 强制解析 */
public class Force implements Extractor {
    @Override public String fetch(Result r) { return fetch(r.getUrl().v()); }
    @Override public String fetch(String url) {
        if (url.startsWith("force://")) return url.substring(8);
        if (url.startsWith("force:")) return url.substring(6);
        return url;
    }
    @Override public boolean match(Uri uri) { return "force".equals(uri.getScheme()); }
    @Override public void stop() {}
    @Override public void exit() {}
}
