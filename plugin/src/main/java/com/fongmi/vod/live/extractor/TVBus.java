package com.fongmi.vod.live.extractor;

import android.net.Uri;
import com.fongmi.vod.bean.Result;

/** TVBus协议 */
public class TVBus implements Extractor {
    @Override public String fetch(Result r) { return fetch(r.getUrl().v()); }
    @Override public String fetch(String url) { return url; }
    @Override public boolean match(Uri uri) { return "tvbus".equals(uri.getScheme()); }
    @Override public void stop() {}
    @Override public void exit() {}
}
