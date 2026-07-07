package com.fongmi.vod.live.extractor;

import android.net.Uri;
import com.fongmi.vod.bean.Result;

/** YouTube协议 */
public class Youtube implements Extractor {
    @Override public String fetch(Result r) { return fetch(r.getUrl().v()); }
    @Override public String fetch(String url) { return url; }
    @Override public boolean match(Uri uri) {
        String host = uri.getHost();
        return host != null && (host.contains("youtube.com") || host.contains("youtu.be") || "youtube".equals(uri.getScheme()));
    }
    @Override public void stop() {}
    @Override public void exit() {}
}
