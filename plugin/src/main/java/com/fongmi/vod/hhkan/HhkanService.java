package com.fongmi.vod.hhkan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.github.catvod.net.OkHttp;
import com.fongmi.vod.utils.ImageProxy;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** HHKan 独立首页采集器，不依赖现有 VodConfig 或播放链路。 */
public final class HhkanService {
    private static final String HOME = "https://www.kkys14.com/";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final long HOME_CACHE_MS = 60_000L;
    private static final Map<String, CachedHome> HOME_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> SESSION_COOKIES = new ConcurrentHashMap<>();
    private static final Set<String> CATEGORY_NAMES = Set.of("电影", "连续剧", "动漫", "综艺纪录", "短剧", "今日更新", "专题列表", "排行榜");

    private HhkanService() {}

    public static JsonObject home(String inputUrl) throws Exception {
        String homeUrl = normalizeHome(inputUrl);
        FetchResult fetched = fetchHome(homeUrl);
        return homeFromHtml(fetched.url, fetched.html);
    }

    public static JsonObject homeFromHtml(String finalUrl, String html) {
        Document doc = Jsoup.parse(html, finalUrl);
        JsonObject result = new JsonObject();
        result.addProperty("source", finalUrl);
        result.addProperty("title", doc.title());
        result.add("class", parseCategories(doc));
        result.add("banner", parseBanner(doc));
        result.add("sections", parseSections(doc));
        int count = result.getAsJsonArray("banner").size();
        for (var section : result.getAsJsonArray("sections")) count += section.getAsJsonObject().getAsJsonArray("list").size();
        if (count == 0) throw new IllegalStateException("HHKan parsed no video items; url=" + finalUrl + ", title=" + doc.title() + ", length=" + html.length());
        return result;
    }

    public static void primeHome(String inputUrl, String finalUrl, String html, String cookie) {
        String homeUrl = normalizeHome(inputUrl);
        String value = cookie == null ? "" : cookie;
        rememberSession(finalUrl, value);
        HOME_CACHE.put(homeUrl, new CachedHome(System.currentTimeMillis(), new FetchResult(finalUrl, html, value)));
    }

    public static void rememberPageSession(String finalUrl, String cookie) {
        rememberSession(finalUrl, cookie == null ? "" : cookie);
    }

    public static JsonObject categoryFromHtml(String finalUrl, String html) {
        Document doc = Jsoup.parse(html, finalUrl);
        JsonObject result = new JsonObject();
        result.addProperty("source", finalUrl);
        try { result.add("tabs", parseCategoryTabs(doc)); } catch (Exception e) { result.add("tabs", new JsonArray()); }
        try { result.add("filters", parseFilters(doc)); } catch (Exception e) { result.add("filters", new JsonArray()); }
        try { result.add("sections", parseSections(doc)); } catch (Exception e) { result.add("sections", new JsonArray()); }
        try {
            Element container = findLargestCardContainer(doc);
            result.add("list", container == null ? parseCards(doc, 80) : parseSectionCards(container, 80));
        } catch (Exception e) { result.add("list", new JsonArray()); }
        try { result.addProperty("next", findNextPage(doc)); } catch (Exception e) { result.addProperty("next", ""); }
        return result;
    }

    private static JsonArray parseCategoryTabs(Document doc) {
        JsonArray tabs = new JsonArray();
        boolean activeFound = false;
        for (Element link : doc.select(".tab-box a.tab-item[href]")) {
            String name = clean(link.text());
            String url = absolute(link, "href");
            if (name.isEmpty() || url.isEmpty()) continue;
            JsonObject tab = new JsonObject();
            tab.addProperty("name", name);
            tab.addProperty("url", url);
            // 部分专题页把 active class 复制到了整组节点；最多只允许一个顶部分类高亮。
            boolean active = !activeFound && (samePage(url, doc.location()) || link.hasClass("tab-item-active"));
            tab.addProperty("active", active);
            if (active) activeFound = true;
            tabs.add(tab);
        }
        return tabs;
    }

    private static JsonArray parseFilters(Document doc) {
        JsonArray filters = new JsonArray();
        for (Element row : doc.select(".filter-box .filter-row")) {
            Element label = row.selectFirst(".filter-row-side strong");
            String name = label == null ? "" : clean(label.text()).replaceAll("[:：]$", "");
            JsonArray values = new JsonArray();
            for (Element link : row.select(".filter-row-main a.filter-item[href]")) {
                String text = clean(link.text());
                String url = absolute(link, "href");
                if (text.isEmpty() || url.isEmpty()) continue;
                JsonObject value = new JsonObject();
                value.addProperty("name", text);
                value.addProperty("url", url);
                value.addProperty("active", link.hasClass("filter-item-active"));
                values.add(value);
            }
            if (name.isEmpty() || values.size() == 0) continue;
            JsonObject filter = new JsonObject();
            filter.addProperty("name", name);
            filter.add("values", values);
            filters.add(filter);
        }
        return filters;
    }

    public static JsonObject detailFromHtml(String finalUrl, String html) {
        Document doc = Jsoup.parse(html, finalUrl);
        JsonObject vod = new JsonObject();
        String name = first(attr(doc, "meta[property=og:title]", "content"), text(doc, "h1"), doc.title());
        String pic = first(attr(doc, "meta[property=og:image]", "content"), attr(doc, "meta[name=og:image]", "content"));
        if (pic.isEmpty()) {
            Element poster = doc.selectFirst("[class*=poster] img,[class*=video-cover] img,[class*=module-item-pic] img");
            if (poster != null) pic = findImage(poster.parent() == null ? poster : poster.parent(), poster);
        }
        if (!pic.isEmpty()) pic = proxyImage(finalUrl, resolve(finalUrl, pic));
        String content = first(attr(doc, "meta[name=description]", "content"),
                text(doc, "[class*=video-info-content]"), text(doc, "[class*=module-info-introduction]"), text(doc, "[class*=vod-content]"));
        vod.addProperty("vod_id", finalUrl);
        vod.addProperty("vod_name", clean(name));
        vod.addProperty("vod_pic", pic);
        vod.addProperty("vod_content", clean(content));

        JsonArray sources = parsePlaySources(doc);
        JsonArray episodes = sources.size() == 0
                ? parseEpisodes(doc, "a[href*='/play/'],a[href*='/vodplay/'],[class*=playlist] a[href],[class*=play-list] a[href]")
                : sources.get(0).getAsJsonObject().getAsJsonArray("episodes");
        JsonObject result = new JsonObject();
        result.addProperty("source", finalUrl);
        result.add("vod", vod);
        result.add("episodes", episodes);
        result.add("sources", sources);
        return result;
    }

    private static JsonArray parsePlaySources(Document doc) {
        JsonArray result = new JsonArray();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Element tab : doc.select(".module-tab-item,.module-tab-title,[class*=play] [class*=tab-item]")) {
            String name = clean(tab.text());
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }

        Set<String> signatures = new LinkedHashSet<>();
        int sourceIndex = 0;
        for (Element container : doc.select(".module-play-list,.module-play-list-content,[class*=playlist],[class*=play-list]")) {
            JsonArray episodes = parseEpisodes(container, "a[href*='/play/'],a[href*='/vodplay/'],a[href]");
            if (episodes.size() == 0) continue;
            StringBuilder signature = new StringBuilder();
            for (int i = 0; i < episodes.size(); i++) {
                signature.append(episodes.get(i).getAsJsonObject().get("url").getAsString()).append('|');
            }
            if (!signatures.add(signature.toString())) continue;

            String name = sourceIndex < names.size() ? names.get(sourceIndex) : "线路" + (sourceIndex + 1);
            JsonObject source = new JsonObject();
            source.addProperty("name", name);
            source.add("episodes", episodes);
            result.add(source);
            sourceIndex++;
        }
        return result;
    }

    private static JsonArray parseEpisodes(Element root, String selector) {
        JsonArray episodes = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : root.select(selector)) {
            String href = absolute(link, "href");
            String episodeName = clean(link.text());
            if (href.isEmpty() || episodeName.isEmpty() || href.startsWith("javascript:") || !seen.add(href)) continue;
            if (!href.contains("/play/") && !href.contains("/vodplay/")) continue;
            JsonObject episode = new JsonObject();
            episode.addProperty("name", episodeName);
            episode.addProperty("url", href);
            episodes.add(episode);
        }
        return episodes;
    }

    public static JsonObject playFromHtml(String finalUrl, String html) {
        Document doc = Jsoup.parse(html, finalUrl);
        String direct = first(attr(doc, "video[src]", "src"), attr(doc, "video source[src]", "src"));
        if (!direct.isEmpty()) direct = resolve(finalUrl, direct);
        if (direct.isEmpty()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("https?:(?:\\\\/|/){2}[^\"'<>\\s]+?\\.(?:m3u8|mp4)(?:\\?[^\"'<>\\s]*)?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
            if (matcher.find()) direct = matcher.group().replace("\\/", "/");
        }
        JsonObject headers = new JsonObject();
        headers.addProperty("Referer", finalUrl);
        headers.addProperty("User-Agent", UA);
        String cookie = cookieFor(finalUrl);
        if (!cookie.isEmpty()) headers.addProperty("Cookie", cookie);
        JsonObject result = new JsonObject();
        result.addProperty("source", finalUrl);
        result.addProperty("url", direct);
        result.add("headers", headers);
        return result;
    }

    public static JsonObject search(String inputUrl, String keyword) throws Exception {
        String word = keyword == null ? "" : keyword.trim();
        if (word.isEmpty()) throw new IllegalArgumentException("Search keyword is empty");
        String homeUrl = normalizeHome(inputUrl);
        FetchResult home = fetchHome(homeUrl);
        Document homeDoc = Jsoup.parse(home.html, home.url);
        Element form = findSearchForm(homeDoc);
        if (form == null) throw new IllegalStateException("HHKan search form was not found");
        Element keywordInput = findKeywordInput(form);
        if (keywordInput == null || keywordInput.attr("name").isEmpty()) throw new IllegalStateException("HHKan search keyword field was not found");

        String action = form.absUrl("action");
        if (action.isEmpty()) action = home.url;
        String method = form.attr("method").trim().toUpperCase(Locale.ROOT);
        if (method.isEmpty()) method = "GET";
        FormBody.Builder fields = new FormBody.Builder();
        HttpUrl parsedAction = HttpUrl.parse(action);
        if (parsedAction == null) throw new IllegalStateException("Invalid HHKan search action");
        HttpUrl.Builder query = parsedAction.newBuilder();
        for (Element input : form.select("input[name]")) {
            String name = input.attr("name").trim();
            if (name.isEmpty()) continue;
            String value = input == keywordInput ? word : input.attr("value");
            if ("GET".equals(method)) query.addQueryParameter(name, value); else fields.add(name, value);
        }

        Request.Builder request = new Request.Builder().url("GET".equals(method) ? query.build() : parsedAction);
        applyBrowserHeaders(request, home.url);
        if (!home.cookie.isEmpty()) request.header("Cookie", home.cookie);
        if (!"GET".equals(method)) request.post(fields.build());
        try (Response response = OkHttp.client(30_000).newCall(request.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (response.code() == 429) throw new IllegalStateException("HHKan request is rate limited; retry later" + retryAfter(response));
            if (!response.isSuccessful()) throw new IllegalStateException("HHKan search HTTP " + response.code());
            Document resultDoc = Jsoup.parse(body, response.request().url().toString());
            JsonObject result = new JsonObject();
            result.addProperty("keyword", word);
            result.addProperty("source", response.request().url().toString());
            result.add("list", parseCards(resultDoc, 60));
            return result;
        }
    }

    private static Element findLargestCardContainer(Document doc) {
        Element best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Element container : doc.select("main,section,[class*=module],[class*=grid],[class*=list],[class*=row],[class*=box]")) {
            int count = container.select(SECTION_CARD_SELECTOR).size();
            if (count < 2 || count > 120) continue;
            String marker = (container.className() + " " + container.id()).toLowerCase(Locale.ROOT);
            int score = count;
            if (marker.contains("rank") || marker.contains("sidebar") || marker.contains("side-")) score -= 1000;
            if (marker.contains("module-items") || marker.contains("module-list") || marker.contains("page-list")) score += 300;
            if (container.closest("main") != null) score += 100;
            if (score > bestScore) {
                best = container;
                bestScore = score;
            }
        }
        return best;
    }

    private static String findNextPage(Document doc) {
        for (Element link : doc.select("a[href]")) {
            String text = clean(link.text());
            String rel = link.attr("rel").toLowerCase(Locale.ROOT);
            String cls = link.className().toLowerCase(Locale.ROOT);
            if (rel.contains("next") || text.equals("下一页") || text.equals("下页") || cls.contains("next")) {
                String href = absolute(link, "href");
                if (!href.isEmpty() && !href.startsWith("javascript:")) return href;
            }
        }
        Element current = doc.selectFirst(".page-current,.page-link.active,.pagination .active,.page-item.active");
        if (current != null) {
            Element sibling = current.nextElementSibling();
            Element link = sibling == null ? null : (sibling.tagName().equals("a") ? sibling : sibling.selectFirst("a[href]"));
            if (link != null) {
                String href = absolute(link, "href");
                if (!href.isEmpty() && !href.startsWith("javascript:")) return href;
            }
        }
        return "";
    }

    private static boolean samePage(String left, String right) {
        HttpUrl a = HttpUrl.parse(left);
        HttpUrl b = HttpUrl.parse(right);
        if (a == null || b == null) return false;
        return a.host().equalsIgnoreCase(b.host())
                && a.encodedPath().equals(b.encodedPath())
                && String.valueOf(a.encodedQuery()).equals(String.valueOf(b.encodedQuery()));
    }

    private static String attr(Document doc, String selector, String name) {
        Element element = doc.selectFirst(selector);
        return element == null ? "" : element.attr(name).trim();
    }

    private static String text(Document doc, String selector) {
        Element element = doc.selectFirst(selector);
        return element == null ? "" : clean(element.text());
    }

    private static Element findSearchForm(Document doc) {
        for (Element form : doc.select("form")) if (findKeywordInput(form) != null) return form;
        return null;
    }

    private static Element findKeywordInput(Element form) {
        for (Element input : form.select("input[name]")) {
            String name = input.attr("name").toLowerCase(Locale.ROOT);
            String type = input.attr("type").toLowerCase(Locale.ROOT);
            String placeholder = input.attr("placeholder");
            if ("search".equals(type) || name.equals("wd") || name.equals("keyword") || name.equals("q")
                    || name.contains("search") || placeholder.contains("\u641c\u7d22")) return input;
        }
        return null;
    }

    private static FetchResult fetchHome(String homeUrl) throws Exception {
        long now = System.currentTimeMillis();
        CachedHome cached = HOME_CACHE.get(homeUrl);
        if (cached != null && now - cached.createdAt < HOME_CACHE_MS) return cached.result;
        FetchResult result = fetch(homeUrl, "", homeUrl);
        rememberSession(result.url, result.cookie);
        HOME_CACHE.put(homeUrl, new CachedHome(now, result));
        return result;
    }

    private static void applyBrowserHeaders(Request.Builder request, String referer) {
        request.header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                .header("DNT", "1")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-User", "?1");
        if (referer != null && !referer.isEmpty()) request.header("Referer", referer);
    }

    private static String retryAfter(Response response) {
        String value = response.header("Retry-After", "").trim();
        return value.isEmpty() ? "" : " (Retry-After: " + value + ")";
    }

    private static FetchResult fetch(String url, String cookie, String referer) throws Exception {
        Request.Builder builder = new Request.Builder().url(url);
        applyBrowserHeaders(builder, referer);
        if (!cookie.isEmpty()) builder.header("Cookie", cookie);
        try (Response response = OkHttp.client(30_000).newCall(builder.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            String finalUrl = response.request().url().toString();
            if (response.code() == 850) throw new BrowserChallengeException(finalUrl);
            if (response.code() == 429) throw new IllegalStateException("HHKan request is rate limited; retry later" + retryAfter(response));
            if (!response.isSuccessful()) throw new IllegalStateException("HHKan HTTP " + response.code());
            String nextCookie = mergeCookies(cookie, response.headers("Set-Cookie"));
            if (cookie.isEmpty() && !nextCookie.isEmpty() && !containsVideo(body)) return fetch(finalUrl, nextCookie, referer);
            return new FetchResult(finalUrl, body, nextCookie);
        }
    }

    private static String normalizeHome(String inputUrl) {
        String value = inputUrl == null || inputUrl.trim().isEmpty() ? HOME : inputUrl.trim();
        if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null || parsed.host().isEmpty()) throw new IllegalArgumentException("Invalid HHKan domain");
        return parsed.newBuilder().encodedPath("/").query(null).fragment(null).build().toString();
    }

    private static boolean containsVideo(String html) { return html.contains("/detail/") || html.contains("近期热门") || html.contains("热门推荐"); }

    private static String mergeCookies(String current, java.util.List<String> headers) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!current.isEmpty()) for (String part : current.split(";")) putCookie(values, part);
        for (String header : headers) putCookie(values, header.split(";", 2)[0]);
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) { if (joined.length() > 0) joined.append("; "); joined.append(entry.getKey()).append('=').append(entry.getValue()); }
        return joined.toString();
    }

    private static void putCookie(Map<String, String> values, String pair) {
        int split = pair.indexOf('=');
        if (split > 0) values.put(pair.substring(0, split).trim(), pair.substring(split + 1).trim());
    }

    private static JsonArray parseCategories(Document doc) {
        JsonArray result = new JsonArray(); Set<String> seen = new LinkedHashSet<>();
        JsonObject recommend = new JsonObject();
        recommend.addProperty("type_name", "推荐");
        recommend.addProperty("type_id", doc.baseUri());
        result.add(recommend);
        seen.add("推荐");
        for (Element anchor : doc.select("a[href]")) {
            String name = clean(anchor.text());
            if (!CATEGORY_NAMES.contains(name) || !seen.add(name)) continue;
            JsonObject item = new JsonObject(); item.addProperty("type_name", name); item.addProperty("type_id", absolute(anchor, "href")); result.add(item);
        }
        return result;
    }

    private static JsonArray parseBanner(Document doc) {
        for (Element container : doc.select("[class*=banner], [class*=swiper], [class*=slide], [class*=carousel]")) {
            JsonArray items = parseCards(container, 8); if (items.size() >= 2) return items;
        }
        JsonArray fallback = new JsonArray();
        for (Element anchor : doc.select("a[href*='/detail/']")) {
            JsonObject item = parseCard(anchor);
            if (item == null || item.get("vod_pic").getAsString().isEmpty()) continue;
            fallback.add(item);
            if (fallback.size() >= 6) break;
        }
        return fallback;
    }

    private static final String SECTION_CARD_SELECTOR =
            "a[href]:has(img),a[href*='/detail/'],a[href*='/topic/'],a[href*='/special/'],a[href*='/collection/']";

    private static JsonArray parseSections(Document doc) {
        JsonArray sections = new JsonArray();
        Set<String> titles = new LinkedHashSet<>();
        String headings = "h1,h2,h3,h4,h5,h6,[class*=module-title],[class*=section-title],"
                + "[class*=box-title],[class*=list-title],[class*=heading],[class*=module-tab-name],[class*=module-name]";
        for (Element heading : doc.select(headings)) {
            String title = clean(heading.text());
            if (!isUsableSectionTitle(title) || isInsideLink(heading) || !titles.add(title)) continue;
            Element container = findSectionContainer(heading);
            if (container == null) continue;
            JsonArray list = parseSectionCards(container, 40);
            if (list.size() == 0) continue;
            JsonObject section = new JsonObject();
            section.addProperty("title", title);
            section.add("list", list);
            Element more = container.selectFirst(".section-header-more[href],a[class*=more][href]");
            if (more != null) section.addProperty("more", absolute(more, "href"));
            sections.add(section);
        }
        for (Element container : doc.select("main > section,section,[class*=module],[class*=section]")) {
            int count = container.select(SECTION_CARD_SELECTOR).size();
            if (count < 2 || count > 60) continue;
            Element heading = container.selectFirst(".section-header-title,h1,h2,h3,h4,h5,h6,[class*=heading],[class*=module-tab-name],[class*=module-name],strong");
            String title = heading == null ? "" : clean(heading.text());
            if (!isUsableSectionTitle(title) || isBrandText(title) || !titles.add(title)) continue;
            JsonArray list = parseSectionCards(container, 40);
            if (list.size() == 0) continue;
            JsonObject section = new JsonObject();
            section.addProperty("title", title);
            section.add("list", list);
            Element more = container.selectFirst(".section-header-more[href],a[class*=more][href]");
            if (more != null) section.addProperty("more", absolute(more, "href"));
            sections.add(section);
        }
        return sections;
    }


	/** 根据目标页面真实 DOM 结构识别目录或影视详情，不依赖标题及 URL 关键词。 */
	public static JsonObject routeFromHtml(String finalUrl, String html) {
		Document doc = Jsoup.parse(html, finalUrl);
		JsonObject result = new JsonObject();
		result.addProperty("source", finalUrl);
		JsonObject detail = detailFromHtml(finalUrl, html);
		JsonArray episodes = detail.getAsJsonArray("episodes");
		int cardCount = doc.select(SECTION_CARD_SELECTOR).size();
		boolean hasDirectoryControls = !doc.select(".tab-box a.tab-item[href],.filter-box .filter-row,a[class*=more][href]").isEmpty();
		boolean isDetail = episodes != null && episodes.size() > 0;
		boolean isCategory = !isDetail && (hasDirectoryControls || cardCount >= 2);
		result.addProperty("type", isCategory ? "category" : "detail");
		return result;
	}

    private static Element findSectionContainer(Element heading) {
        Element current = heading.parent();
        while (current != null && !current.tagName().equals("body")) {
            int count = current.select(SECTION_CARD_SELECTOR).size();
            if (count >= 2 && count <= 60) return current;
            current = current.parent();
        }
        return null;
    }

    private static boolean isInsideLink(Element element) {
        Element current = element.parent();
        while (current != null && !current.tagName().equals("body")) {
            if (current.tagName().equals("a")) return true;
            current = current.parent();
        }
        return false;
    }

    /** Accepts section titles from DOM structure instead of a fixed business-name whitelist. */
    private static boolean isUsableSectionTitle(String title) {
        if (title.length() < 2 || title.length() > 30) return false;
        String compact = title.replace(" ", "");
        return !compact.equals("查看更多") && !compact.equals("大家都在搜")
                && !compact.equals("观看记录") && !compact.equals("我的观影记录")
                && !compact.equals("下载APP") && !compact.equals("回到顶部")
                && !compact.contains("搜索电影") && !compact.startsWith("好好看,");
    }

    private static JsonArray parseSectionCards(Element container, int limit) {
        JsonArray result = new JsonArray();
        Set<String> seen = new LinkedHashSet<>();
        for (Element anchor : container.select(SECTION_CARD_SELECTOR)) {
            JsonObject item = parseCard(anchor);
            if (item == null) continue;
            String id = item.get("vod_id").getAsString();
            if (!seen.add(id)) continue;
            result.add(item);
            if (result.size() >= limit) break;
        }
        return result;
    }

    private static JsonArray parseCards(Element container, int limit) {
        JsonArray result = new JsonArray(); Set<String> seen = new LinkedHashSet<>();
        for (Element anchor : container.select("a[href*='/detail/']")) {
            JsonObject item = parseCard(anchor); if (item == null) continue;
            if (!seen.add(item.get("vod_id").getAsString())) continue; result.add(item); if (result.size() >= limit) break;
        }
        return result;
    }

    private static JsonObject parseCard(Element anchor) {
        Element image = anchor.selectFirst("img");
        String href = absolute(anchor, "href");
        String name = clean(anchor.attr("title"));
        if (name.isEmpty()) {
            for (Element named : anchor.select(".carousel-item-title,.v-item-title,[class*=video-name],[class*=vod-name],[class*=item-title]")) {
                String candidate = clean(first(named.attr("title"), named.text()));
                if (!candidate.isEmpty() && !isBrandText(candidate)) { name = candidate; break; }
            }
        }
        Element parent = anchor.parent();
        if (name.isEmpty() && parent != null) {
            Element siblingTitle = parent.selectFirst("[class*=module-item-title],[class*=video-name],[class*=vod-name],[class*=item-title]");
            if (siblingTitle != null) name = clean(first(siblingTitle.attr("title"), siblingTitle.text()));
        }
        if (name.isEmpty() && image != null) name = clean(first(image.attr("alt"), image.attr("title")));
        if (isBrandText(name)) name = "";
        if (name.isEmpty()) name = inferName(anchor.text());
        if (isBrandText(name) || name.equals("查看更多") || href.isEmpty() || name.isEmpty()) return null;
        String pic = findImage(anchor, image);
        if (!pic.isEmpty()) pic = resolveImage(anchor.baseUri(), pic);
        JsonObject item = new JsonObject();
        item.addProperty("vod_id", href);
        item.addProperty("vod_name", name);
        item.addProperty("vod_pic", pic);
        item.addProperty("vod_remarks", extractRemarks(clean(anchor.text()), name));
        return item;
    }

    private static boolean isBrandText(String value) {
        String text = clean(value).toLowerCase(Locale.ROOT).replace(" ", "");
        return text.contains("可可影视") || text.contains("kekys") || text.equals("好好看") || text.startsWith("好好看_");
    }

    private static String findImage(Element anchor, Element image) {
        Element scope = anchor;
        for (int depth = 0; scope != null && depth < 4; depth++, scope = scope.parent()) {
            for (Element element : scope.select("[data-original],[data-src],[data-lazy-src],[data-background],[data-bg],[style]")) {
                String value = first(element.attr("data-original"), element.attr("data-background"),
                        element.attr("data-bg"), element.attr("data-lazy-src"), element.attr("data-src"));
                if (!value.isEmpty() && !isPlaceholderImage(value)) return value;
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("background(?:-image)?\\s*:\\s*url\\(['\"]?([^'\")]+)")
                        .matcher(element.attr("style"));
                if (matcher.find() && !isPlaceholderImage(matcher.group(1))) return matcher.group(1);
            }
            if (depth >= 1 && scope.select(SECTION_CARD_SELECTOR).size() > 1) break;
        }
        if (image != null) {
            String value = first(image.attr("data-original"), image.attr("data-background"), image.attr("data-bg"),
                    image.attr("data-lazy-src"), image.attr("data-src"), image.attr("src"));
            if (!isPlaceholderImage(value)) return value;
        }
        return "";
    }

    private static boolean isPlaceholderImage(String value) {
        String url = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return url.isEmpty() || url.startsWith("data:") || url.contains("loading") || url.contains("lazy")
                || url.contains("default") || url.contains("placeholder") || url.contains("no-pic") || url.contains("nopic") || url.contains("kekys");
    }
    private static String resolveImage(String baseUrl, String imageUrl) {
        String value = imageUrl == null ? "" : imageUrl.trim();
        if (value.startsWith("/vod1/")) return "https://vres.cyscyy.com" + value;
        String resolved = resolve(baseUrl, value);
        if (resolved.contains("/vod1/")) {
            int path = resolved.indexOf("/vod1/");
            return "https://vres.cyscyy.com" + resolved.substring(path);
        }
        return resolved;
    }
    private static String proxyImage(String baseUrl, String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty() || imageUrl.startsWith("data:")) return imageUrl == null ? "" : imageUrl;
        HttpUrl base = HttpUrl.parse(baseUrl);
        String cookie = base == null ? "" : SESSION_COOKIES.getOrDefault(base.host(), "");
        String decorated = imageUrl + "@Referer=" + baseUrl + "@User-Agent=" + UA;
        if (!cookie.isEmpty()) decorated += "@Cookie=" + cookie;
        ImageProxy.get().start();
        return ImageProxy.get().rewrite(decorated);
    }

    private static void rememberSession(String url, String cookie) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed != null && cookie != null && !cookie.isEmpty()) SESSION_COOKIES.put(parsed.host(), cookie);
    }

    private static String cookieFor(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed == null ? "" : SESSION_COOKIES.getOrDefault(parsed.host(), "");
    }

    private static String inferName(String text) {
        return clean(text).replaceAll("豆瓣[:：]?\\s*\\d+(?:\\.\\d+)?分?", " ").replaceAll("正片|高清版|HD|完结|已完结|全\\d+集|第\\d+集|更新(?:至|第)?\\d+集", " ").replace("可可影视-kekys.com", " ").trim();
    }

    private static String extractRemarks(String text, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(豆瓣[:：]?\\s*\\d+(?:\\.\\d+)?分?|更新(?:至|第)?\\d+集|全\\d+集(?:完结)?|第\\d+集(?:完结)?|已完结|完结|正片|高清版|HD)").matcher(text.replace(name, " "));
        // 卡片左上角只展示点分隔前的主 tag，与 APP 链路的 topLeftLabel 规则一致。
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String absolute(Element element, String attr) { String value = element.absUrl(attr); return value.isEmpty() ? resolve(element.baseUri(), element.attr(attr)) : value; }
    private static String resolve(String base, String value) { if (value == null || value.isEmpty() || value.startsWith("data:")) return value == null ? "" : value; HttpUrl b = HttpUrl.parse(base.isEmpty() ? HOME : base); HttpUrl r = b == null ? null : b.resolve(value); return r == null ? value : r.toString(); }
    private static String first(String... values) { for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim(); return ""; }
    private static String clean(String value) { return value == null ? "" : value.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim(); }
    public static final class BrowserChallengeException extends Exception {
        public final String url;
        public BrowserChallengeException(String url) {
            super("HHKan requires browser verification");
            this.url = url;
        }
    }

    private record CachedHome(long createdAt, FetchResult result) {}
    private record FetchResult(String url, String html, String cookie) {}
}
