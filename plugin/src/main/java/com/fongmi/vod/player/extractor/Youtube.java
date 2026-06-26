package com.fongmi.vod.player.extractor;

import android.net.Uri;

import com.fongmi.vod.bean.Episode;
import com.fongmi.vod.bean.Result;
import com.fongmi.vod.bean.Vod;
import com.fongmi.vod.utils.UrlUtil;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/** Simplified YouTube extractor - passes through URLs directly. */
public class Youtube implements Source.Extractor {

    public Youtube() {
    }

    @Override
    public boolean match(Uri uri) {
        String host = UrlUtil.host(uri);
        return host.contains("youtube.com") || host.contains("youtu.be");
    }

    @Override
    public String fetch(String url) throws Exception {
        return url;
    }

    @Override
    public String fetch(Result result) throws Exception {
        return result.getUrl().v();
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
    }

    public record Parser(String url) implements Callable<List<Episode>> {

        private static final Pattern PATTERN = Pattern.compile("(youtube\\.com|youtu\\.be).*list=");

        public static boolean match(String url) {
            return PATTERN.matcher(url).find();
        }

        public static Parser get(String url) {
            return new Parser(url);
        }

        @Override
        public List<Episode> call() {
            return Collections.emptyList();
        }
    }
}
