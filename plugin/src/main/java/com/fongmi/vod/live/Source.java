package com.fongmi.vod.live;

import android.net.Uri;

import com.fongmi.vod.bean.Result;
import com.fongmi.vod.live.extractor.Extractor;
import com.fongmi.vod.live.extractor.Force;
import com.fongmi.vod.live.extractor.JianPian;
import com.fongmi.vod.live.extractor.Push;
import com.fongmi.vod.live.extractor.Strm;
import com.fongmi.vod.live.extractor.Thunder;
import com.fongmi.vod.live.extractor.TVBus;
import com.fongmi.vod.live.extractor.Video;
import com.fongmi.vod.live.extractor.Youtube;

import java.util.ArrayList;
import java.util.List;

/**
 * URL 提取引擎 — 8个Extractor，完全移植Fongmi
 */
public class Source {

    private final List<Extractor> extractors;

    public Source() {
        extractors = new ArrayList<>();
        extractors.add(new Force());
        extractors.add(new JianPian());
        extractors.add(new Push());
        extractors.add(new Strm());
        extractors.add(new Thunder());
        extractors.add(new TVBus());
        extractors.add(new Video());
        extractors.add(new Youtube());
    }

    public static Source get() {
        return Loader.INSTANCE;
    }

    private Extractor getExtractor(Uri uri) {
        for (Extractor e : extractors) {
            if (e.match(uri)) return e;
        }
        return null;
    }

    public String fetch(Result result) throws Exception {
        Uri uri = result.getUrl().uri();
        Extractor extractor = getExtractor(uri);
        return extractor == null ? result.getUrl().v() : extractor.fetch(result);
    }

    public void stop() {
        for (Extractor e : extractors) e.stop();
    }

    public void exit() {
        for (Extractor e : extractors) e.exit();
    }

    private static class Loader {
        static volatile Source INSTANCE = new Source();
    }
}
