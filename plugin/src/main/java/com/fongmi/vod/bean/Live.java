package com.fongmi.vod.bean;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 直播源数据模型
 * 对应订阅JSON中lives数组的一个元素
 */
public class Live {

    private String name;
    private String url;
    private String api;
    private String ext;
    private String jar;
    private String click;
    private String logo;
    private String epg;
    private String ua;
    private String origin;
    private String referer;
    private String timeZone;
    private String keep;
    private long timeout;
    private boolean boot;
    private boolean pass;
    private boolean selected;
    private int width;
    private List<Group> groups;
    private Map<String, String> header;

    public Live() {
        groups = new ArrayList<>();
        header = new HashMap<>();
    }

    public Live(String name, String url) {
        this();
        this.name = name;
        this.url = url;
    }

    public String getName() { return TextUtils.isEmpty(name) ? "" : name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return TextUtils.isEmpty(url) ? "" : url; }
    public void setUrl(String url) { this.url = url; }

    public String getApi() { return TextUtils.isEmpty(api) ? "" : api; }
    public void setApi(String api) { this.api = api; }

    public String getExt() { return TextUtils.isEmpty(ext) ? "" : ext; }
    public void setExt(String ext) { this.ext = ext; }

    public String getJar() { return TextUtils.isEmpty(jar) ? "" : jar; }
    public void setJar(String jar) { this.jar = jar; }

    public String getClick() { return TextUtils.isEmpty(click) ? "" : click; }
    public String getLogo() { return TextUtils.isEmpty(logo) ? "" : logo; }

    public String getEpg() { return TextUtils.isEmpty(epg) ? "" : epg; }
    public void setEpg(String epg) { this.epg = epg; }

    public String getUa() { return TextUtils.isEmpty(ua) ? "" : ua; }
    public void setUa(String ua) { this.ua = ua; }
    public String getOrigin() { return TextUtils.isEmpty(origin) ? "" : origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getReferer() { return TextUtils.isEmpty(referer) ? "" : referer; }
    public void setReferer(String referer) { this.referer = referer; }
    public String getTimeZone() { return TextUtils.isEmpty(timeZone) ? "" : timeZone; }
    public String getKeep() { return TextUtils.isEmpty(keep) ? "" : keep; }
    public void setKeep(String keep) { this.keep = keep; }

    public long getTimeout() { return timeout; }
    public void setTimeout(long timeout) { this.timeout = timeout; }

    public Map<String, String> getHeader() { return header == null ? new HashMap<>() : header; }
    public void setHeader(Map<String, String> header) { this.header = header; }

    public boolean isBoot() { return boot; }
    public void setBoot(boolean boot) { this.boot = boot; }

    public boolean isPass() { return pass; }
    public void setPass(boolean pass) { this.pass = pass; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public List<Group> getGroups() { return groups == null ? (groups = new ArrayList<>()) : groups; }
    public void setGroups(List<Group> groups) { this.groups = groups; }

    public boolean isEmpty() { return getName().isEmpty() && getUrl().isEmpty(); }

    public Group find(Group item) {
        for (Group g : getGroups()) {
            if (g.getName().equals(item.getName())) return g;
        }
        getGroups().add(item);
        return item;
    }

    public Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        if (!getUa().isEmpty()) headers.put("User-Agent", getUa());
        if (!getOrigin().isEmpty()) headers.put("Origin", getOrigin());
        if (!getReferer().isEmpty()) headers.put("Referer", getReferer());
        if (header != null) headers.putAll(header);
        return headers;
    }
}
