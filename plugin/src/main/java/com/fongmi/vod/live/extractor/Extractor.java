package com.fongmi.vod.live.extractor;

import android.net.Uri;
import com.fongmi.vod.bean.Result;

public interface Extractor {
    String fetch(Result r) throws Exception;
    String fetch(String url) throws Exception;
    boolean match(Uri uri);
    void stop();
    void exit();
}
