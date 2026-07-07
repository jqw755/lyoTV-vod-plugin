package com.fongmi.vod.bean;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 直播分组模型
 * 一个分组包含多个频道
 */
public class Group {

    private String name;
    private String pass;
    private List<Channel> channel;
    private boolean selected;
    private int position;
    private int width;

    public Group() {
        channel = new ArrayList<>();
    }

    public Group(String name) {
        this();
        this.name = name;
    }

    public Group(String name, boolean pass) {
        this(name);
        if (name.contains("_")) parse(pass);
    }

    public static List<Group> arrayFrom(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            List<Group> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Group g = new Group();
                g.setName(obj.optString("name", ""));
                g.setPass(obj.optString("pass", ""));

                JSONArray chArr = obj.optJSONArray("channel");
                if (chArr != null) {
                    List<Channel> channels = new ArrayList<>();
                    for (int j = 0; j < chArr.length(); j++) {
                        JSONObject chObj = chArr.getJSONObject(j);
                        Channel ch = new Channel();
                        ch.setName(chObj.optString("name", ""));
                        ch.setNumber(chObj.optString("number", ""));
                        ch.setLogo(chObj.optString("logo", ""));
                        ch.setTvgId(chObj.optString("tvgId", ""));
                        ch.setTvgName(chObj.optString("tvgName", ""));
                        ch.setUa(chObj.optString("ua", ""));
                        ch.setReferer(chObj.optString("referer", ""));

                        JSONArray urlArr = chObj.optJSONArray("urls");
                        if (urlArr != null) {
                            for (int k = 0; k < urlArr.length(); k++) {
                                ch.getUrls().add(urlArr.optString(k, ""));
                            }
                        }
                        channels.add(ch);
                    }
                    g.setChannel(channels);
                }
                list.add(g);
            }
            return list;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private void parse(boolean pass) {
        String[] splits = name.split("_", 2);
        setName(splits[0]);
        if (pass || splits.length == 1) return;
        setPass(splits[1]);
    }

    public String getName() { return TextUtils.isEmpty(name) ? "" : name; }
    public void setName(String name) { this.name = name; }

    public String getPass() { return TextUtils.isEmpty(pass) ? "" : pass; }
    public void setPass(String pass) { this.pass = pass; }

    public List<Channel> getChannel() { return channel == null ? (channel = new ArrayList<>()) : channel; }
    public void setChannel(List<Channel> channel) { this.channel = channel; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public boolean isHidden() { return !TextUtils.isEmpty(getPass()); }
    public boolean isEmpty() { return getChannel().isEmpty(); }

    public void add(Channel channel) {
        Channel exist = getChannel().stream()
                .filter(c -> c.getName().equals(channel.getName()))
                .findFirst().orElse(null);
        if (exist != null) {
            exist.getUrls().addAll(channel.getUrls());
        } else {
            getChannel().add(Channel.create(channel));
        }
    }

    public int find(String name) {
        for (int i = 0; i < getChannel().size(); i++) {
            if (getChannel().get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    public Channel current() {
        if (getChannel().isEmpty()) return new Channel();
        return getChannel().get(Math.max(0, Math.min(position, getChannel().size() - 1)));
    }
}
