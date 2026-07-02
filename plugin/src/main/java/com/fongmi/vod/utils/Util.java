package com.fongmi.vod.utils;

import android.text.Html;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本工具：从 lyoTV 抽取 bean 链路用到的 getNumber/clean/substring，
 * 剥离原 UI/media3/BuildConfig/Notify 等附属方法。
 */
public class Util {

    private static final Pattern EPISODE = Pattern.compile("(?i)(?:ep|第|e|[\\-\\.\\s])\\s?(\\d{1,4})");

    public static int getNumber(String text) {
        try {
            text = text.replaceAll("\\[.*?\\]|\\(.*?\\)", "");
            text = text.replaceAll("\\b(19|20)\\d{2}\\b", "");
            text = text.toLowerCase().replaceAll("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4", "");
            Matcher matcher = EPISODE.matcher(text);
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
            String number = text.replaceAll("\\D+", "");
            return number.isEmpty() ? -1 : Integer.parseInt(number);
        } catch (Exception e) {
            return -1;
        }
    }

    public static String clean(String text) {
        if (!text.contains("<")) return text;
        StringBuilder sb = new StringBuilder();
        text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().replace(" ", " ").replace("　", " ");
        for (String line : text.split("\\r?\\n")) sb.append(line.trim()).append("\n");
        return substring(sb.toString()).trim();
    }

    public static String substring(String text) {
        return substring(text, 1);
    }

    public static String substring(String text, int num) {
        if (text != null && text.length() > num) return text.substring(0, text.length() - num);
        return text;
    }
}
