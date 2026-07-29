package com.fongmi.quickjs.utils;

import android.text.TextUtils;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * drpy/Hiker 规则使用的 HTML 解析函数。
 *
 * JS 蜘蛛包不一定携带 com.github.catvod.js.Function；解析能力必须由宿主提供，
 * 否则通用 API 脚本在模块初始化阶段会因 pdfh/pdfa/pd 未定义而直接失效。
 */
public final class HtmlParser {

    private static final Pattern NO_AUTO_INDEX =
            Pattern.compile(":eq|:lt|:gt|:first|:last|^body$|^#");
    private static final Pattern URL_ATTRIBUTE =
            Pattern.compile("(url|src|href|-original|-src|-play|-url|style)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPECIAL_URL =
            Pattern.compile("^(ftp|magnet|thunder|ws):", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_URL =
            Pattern.compile("url\\((.*?)\\)", Pattern.MULTILINE | Pattern.DOTALL);

    private HtmlParser() {
    }

    public static String parseDomForUrl(String html, String rule, String baseUrl) {
        if (TextUtils.isEmpty(rule)) return "";
        Document document = Jsoup.parse(html == null ? "" : html);
        if ("Text".equals(rule) || "body&&Text".equals(rule)) return document.text();
        if ("Html".equals(rule) || "body&&Html".equals(rule)) return document.html();

        String option = "";
        String selector = rule;
        if (rule.contains("&&")) {
            String[] parts = rule.split("&&");
            option = parts[parts.length - 1];
            selector = TextUtils.join("&&", Arrays.copyOf(parts, parts.length - 1));
        }

        Elements elements = select(document, selector, true);
        if (elements.isEmpty()) return "";
        if (TextUtils.isEmpty(option)) return elements.outerHtml();
        if ("Text".equals(option)) return elements.text();
        if ("Html".equals(option)) return elements.html();

        String result = elements.attr(option);
        if (option.toLowerCase().contains("style") && result.contains("url(")) {
            Matcher matcher = STYLE_URL.matcher(result);
            if (matcher.find()) result = matcher.group(1);
            result = result.replaceAll("^['\"](.*)['\"]$", "$1");
        }
        if (!TextUtils.isEmpty(result)
                && !TextUtils.isEmpty(baseUrl)
                && URL_ATTRIBUTE.matcher(option).find()
                && !SPECIAL_URL.matcher(result).find()) {
            result = result.contains("http")
                    ? result.substring(result.indexOf("http"))
                    : resolve(baseUrl, result);
        }
        return result;
    }

    public static List<String> parseDomForArray(String html, String rule) {
        Document document = Jsoup.parse(html == null ? "" : html);
        Elements elements = select(document, rule, false);
        List<String> result = new ArrayList<>(elements.size());
        for (Element element : elements) result.add(element.outerHtml());
        return result;
    }

    public static List<String> parseDomForList(
            String html,
            String listRule,
            String textRule,
            String urlRule,
            String baseUrl
    ) {
        List<String> result = new ArrayList<>();
        for (String item : parseDomForArray(html, listRule)) {
            String text = parseDomForUrl(item, textRule, "").trim();
            String url = parseDomForUrl(item, urlRule, baseUrl);
            result.add(text + "$" + url);
        }
        return result;
    }

    private static Elements select(Document document, String rule, boolean first) {
        if (TextUtils.isEmpty(rule)) return new Elements();
        String normalized = normalize(rule, first);
        Elements result = new Elements();
        for (String selector : normalized.split(" ")) {
            if (TextUtils.isEmpty(selector)) continue;
            result = selectOne(document, result, selector);
            if (result.isEmpty()) break;
        }
        return result;
    }

    private static String normalize(String rule, boolean first) {
        if (!rule.contains("&&")) {
            String last = lastToken(rule);
            return first && !NO_AUTO_INDEX.matcher(last).find() ? rule + ":eq(0)" : rule;
        }
        String[] parts = rule.split("&&");
        List<String> selectors = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            String last = lastToken(part);
            boolean addIndex = !NO_AUTO_INDEX.matcher(last).find() && (first || i < parts.length - 1);
            selectors.add(addIndex ? part + ":eq(0)" : part);
        }
        return TextUtils.join(" ", selectors);
    }

    private static String lastToken(String value) {
        String[] parts = value.trim().split(" ");
        return parts.length == 0 ? "" : parts[parts.length - 1];
    }

    private static Elements selectOne(Document document, Elements current, String rawSelector) {
        String[] excludes = rawSelector.split("--");
        String selector = excludes[0];
        int index = Integer.MIN_VALUE;
        Matcher matcher = Pattern.compile(":eq\\((-?\\d+)\\)").matcher(selector);
        if (matcher.find()) {
            index = Integer.parseInt(matcher.group(1));
            selector = matcher.replaceFirst("");
        }

        Elements selected = current.isEmpty() ? document.select(selector) : current.select(selector);
        if (index != Integer.MIN_VALUE) {
            int actual = index < 0 ? selected.size() + index : index;
            selected = actual >= 0 && actual < selected.size() ? selected.eq(actual) : new Elements();
        }
        if (excludes.length > 1 && !selected.isEmpty()) {
            selected = selected.clone();
            for (int i = 1; i < excludes.length; i++) selected.select(excludes[i]).remove();
        }
        return selected;
    }

    private static String resolve(String parent, String child) {
        try {
            return new URL(new URL(parent), child).toExternalForm();
        } catch (MalformedURLException e) {
            return child;
        }
    }
}
