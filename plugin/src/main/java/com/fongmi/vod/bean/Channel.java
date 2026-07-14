package com.fongmi.vod.bean;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 直播频道模型
 * 包含频道信息、多线路URL、请求头等
 */
public class Channel {

    private String name;
    private String number;
    private String logo;
    private String epg;
    private String ua;
    private String click;
    private String format;
    private String origin;
    private String referer;
    private String tvgId;
    private String tvgName;
    private int index;
    private Integer parse;
    private Drm drm;
    private List<String> urls;
    private Map<String, String> header;
    private Group group;

    public Channel() {
        urls = new ArrayList<>();
        header = new HashMap<>();
    }

    public Channel(String name) {
        this();
        this.name = name;
    }

    public static Channel create(int number) {
        return new Channel().setNumber2(number);
    }

    public static Channel create(String name) {
        return new Channel(name);
    }

    public static Channel create(Channel channel) {
        Channel c = new Channel(channel.getName());
        c.urls.addAll(channel.getUrls());
        c.logo = channel.logo;
        c.number = channel.number;
        c.ua = channel.ua;
        c.referer = channel.referer;
        c.tvgId = channel.tvgId;
        c.tvgName = channel.tvgName;
        c.header.putAll(channel.header);
        c.format = channel.format;
        c.origin = channel.origin;
        c.click = channel.click;
        if (channel.parse != null) c.parse = channel.parse;
        if (channel.drm != null) c.drm = channel.drm;
        return c;
    }

    public String getName() { return TextUtils.isEmpty(name) ? "" : name; }
    public void setName(String name) { this.name = name; }

    public String getNumber() { return TextUtils.isEmpty(number) ? "" : number; }
    public void setNumber(String number) { this.number = number; }
    public void setNumber(int number) { this.number = String.valueOf(number); }
    private Channel setNumber2(int number) { this.number = String.valueOf(number); return this; }

    public String getLogo() { return TextUtils.isEmpty(logo) ? "" : logo; }
    public void setLogo(String logo) { this.logo = logo; }

    public String getEpg() { return TextUtils.isEmpty(epg) ? "" : epg; }
    public void setEpg(String epg) { this.epg = epg; }

    public String getUa() { return TextUtils.isEmpty(ua) ? "" : ua; }
    public void setUa(String ua) { this.ua = ua; }

    public String getClick() { return TextUtils.isEmpty(click) ? "" : click; }
    public void setClick(String click) { this.click = click; }

    public String getFormat() { return TextUtils.isEmpty(format) ? "" : format; }
    public void setFormat(String format) { this.format = format; }

    public String getOrigin() { return TextUtils.isEmpty(origin) ? "" : origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getReferer() { return TextUtils.isEmpty(referer) ? "" : referer; }
    public void setReferer(String referer) { this.referer = referer; }

    public String getTvgId() { return TextUtils.isEmpty(tvgId) ? "" : tvgId; }
    public void setTvgId(String tvgId) { this.tvgId = tvgId; }

    public String getTvgName() { return TextUtils.isEmpty(tvgName) ? "" : tvgName; }
    public void setTvgName(String tvgName) { this.tvgName = tvgName; }

    public Integer getParse() { return parse; }
    public void setParse(Integer parse) { this.parse = parse; }

    public Drm getDrm() { return drm; }
    public void setDrm(Drm drm) { this.drm = drm; }

    public int getIndex() { return Math.max(0, Math.min(index, urls.size() - 1)); }
    public void setIndex(int index) { this.index = Math.max(0, Math.min(index, urls.size() - 1)); }
    public void setIndex(String line) {
        try { this.index = Integer.parseInt(line) - 1; }
        catch (NumberFormatException e) { this.index = 0; }
    }

    public List<String> getUrls() { return urls == null ? (urls = new ArrayList<>()) : urls; }
    public void setUrls(List<String> urls) { this.urls = urls; }

    public Map<String, String> getHeader() { return header == null ? (header = new HashMap<>()) : header; }
    public void setHeader(Map<String, String> header) { this.header = header; }

    public Group getGroup() { return group; }
    public void setGroup(Group group) { this.group = group; }

    /** 当前线路URL */
    public String getCurrent() {
        if (urls == null || urls.isEmpty()) return "";
        return urls.get(Math.max(0, Math.min(index, urls.size() - 1)));
    }

    public boolean isOnly() { return urls == null || urls.size() <= 1; }
    public boolean isLast() { return urls != null && index >= urls.size() - 1; }

    /** 切换线路 */
    public void switchLine(boolean next) {
        if (urls == null || urls.size() <= 1) return;
        if (next) index = (index + 1) % urls.size();
        else index = (index - 1 + urls.size()) % urls.size();
    }

    public String getShow() {
        if (!getNumber().isEmpty()) return getNumber() + " " + getName();
        return getName();
    }

    public String getLine() {
        if (isOnly()) return "";
        return "源" + (getIndex() + 1);
    }

    public Channel group(Group group) {
        this.group = group;
        return this;
    }

    /** 生成Result对象 */
    public Result result() {
        Result result = new Result();
        result.setUrl(getCurrent());
        Map<String, String> h = getHeaders();
        if (!h.isEmpty()) result.setHeader(h);
        if (!getUa().isEmpty()) result.getHeader().put("User-Agent", getUa());
        if (!getReferer().isEmpty()) result.getHeader().put("Referer", getReferer());
        if (!getOrigin().isEmpty()) result.getHeader().put("Origin", getOrigin());
        if (!getFormat().isEmpty()) result.setFormat(getFormat());
        return result;
    }

    public Map<String, String> getHeaders() {
        Map<String, String> map = new HashMap<>();
        if (!getUa().isEmpty()) map.put("User-Agent", getUa());
        if (!getOrigin().isEmpty()) map.put("Origin", getOrigin());
        if (!getReferer().isEmpty()) map.put("Referer", getReferer());
        if (header != null) map.putAll(header);
        return map;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Channel)) return false;
        Channel it = (Channel) obj;
        String name1 = getName(), name2 = it.getName();
        String number1 = getNumber(), number2 = it.getNumber();
        if (!name1.isEmpty() && !name2.isEmpty()) return name1.equals(name2);
        if (!number1.isEmpty() && !number2.isEmpty()) return number1.equals(number2);
        return false;
    }

    @Override
    public int hashCode() {
        String n = getName();
        String num = getNumber();
        if (!n.isEmpty()) return n.hashCode();
        if (!num.isEmpty()) return num.hashCode();
        return 0;
    }
}
