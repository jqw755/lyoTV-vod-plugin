package com.fongmi.vod.bean;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.vod.App;
import com.github.catvod.utils.Prefers;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

/**
 * 插件版 Config：原 fongmi 是 Room @Entity，靠 AppDatabase 持久化站点订阅。
 * 插件无数据库（前端自存），所有读写库的方法降级为内存对象 / 空操作，仅保留站点加载链路用到的字段与工厂方法。
 */
public class Config {

    @SerializedName("id")
    private int id;
    @SerializedName("type")
    private int type;
    @SerializedName("time")
    private long time;
    @SerializedName("url")
    private String url;
    @SerializedName("json")
    private String json;
    @SerializedName("name")
    private String name;
    @SerializedName("logo")
    private String logo;
    @SerializedName("home")
    private String home;
    @SerializedName("parse")
    private String parse;

    @SerializedName("notice")
    private String notice;
    @SerializedName("danmaku")
    private String danmaku;

    public static List<Config> arrayFrom(String str) {
        Type listType = new TypeToken<List<Config>>() {}.getType();
        List<Config> items = App.gson().fromJson(str, listType);
        return items == null ? Collections.emptyList() : items;
    }

    public static Config objectFrom(String str) {
        return App.gson().fromJson(str, Config.class);
    }

    public static Config create(int type) {
        return new Config().type(type);
    }

    public static Config create(int type, String url) {
        return new Config().type(type).url(url).insert();
    }

    public static Config create(int type, String url, String name) {
        return new Config().type(type).url(url).name(name).insert();
    }

    /** 插件无数据库：返回空列表，前端自行管理多订阅。 */
    public static List<Config> getAll(int type) {
        return Collections.emptyList();
    }

    public static List<Config> findUrls() {
        return Collections.emptyList();
    }

    public static void delete(String url) {
    }

    public static void delete(String url, int type) {
    }

    public static Config vod() {
        return new Config().type(0);
    }

    public static Config live() {
        return new Config().type(1);
    }

    public static Config wall() {
        return new Config().type(2);
    }

    public static Config find(int id) {
        return new Config().type(0);
    }

    public static Config find(String url, int type) {
        return new Config().type(type).url(url);
    }

    public static Config find(String url, String name, int type) {
        return new Config().type(type).url(url).name(name);
    }

    public static Config find(Config config) {
        return find(config, config.getType());
    }

    public static Config find(Config config, int type) {
        return new Config().type(type).url(config.getUrl()).name(config.getName());
    }

    public static Config find(Depot depot, int type) {
        return new Config().type(type).url(depot.getUrl()).name(depot.getName());
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getJson() {
        return json;
    }

    public void setJson(String json) {
        this.json = json;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLogo() {
        return logo;
    }

    public void setLogo(String logo) {
        this.logo = logo;
    }

    public String getHome() {
        return home;
    }

    public void setHome(String home) {
        this.home = home;
    }

    public String getParse() {
        return parse;
    }

    public void setParse(String parse) {
        this.parse = parse;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public String getNotice() {
        return notice;
    }

    public void setNotice(String notice) {
        this.notice = notice;
    }

    public String getDanmaku() {
        return danmaku;
    }

    public void setDanmaku(String danmaku) {
        this.danmaku = danmaku;
    }

    public Config type(int type) {
        setType(type);
        return this;
    }

    public Config url(String url) {
        setUrl(url);
        return this;
    }

    public Config json(String json) {
        setJson(json);
        return this;
    }

    public Config name(String name) {
        setName(name);
        return this;
    }

    public boolean isEmpty() {
        return TextUtils.isEmpty(getUrl());
    }

    public String getDesc() {
        if (!TextUtils.isEmpty(getName())) return getName();
        if (!TextUtils.isEmpty(getUrl())) return getUrl();
        return "";
    }

    /** 插件无数据库：insert 不再生成主键，仅返回自身。 */
    public Config insert() {
        return this;
    }

    /** 插件无数据库：save 为空操作，仅保留链式返回。 */
    public Config save() {
        return this;
    }

    public Config update() {
        if (isEmpty()) return this;
        setTime(System.currentTimeMillis());
        Prefers.put("config_" + getType(), getUrl());
        return save();
    }

    public void delete() {
    }

    @NonNull
    @Override
    public String toString() {
        return App.gson().toJson(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Config it)) return false;
        return getId() == it.getId();
    }
}
