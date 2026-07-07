package com.fongmi.vod.bean;

import java.util.HashMap;
import java.util.Map;

/**
 * DRM 模型 — 移植自 Fongmi
 */
public class Drm {

    private String key;
    private String type;
    private Map<String, String> header;
    private boolean forceKey;

    public static Drm create(String key, String type, Map<String, String> header, boolean forceKey) {
        Drm d = new Drm();
        d.key = key;
        d.type = type;
        d.header = header == null ? new HashMap<>() : new HashMap<>(header);
        d.forceKey = forceKey;
        return d;
    }

    public String getKey() { return key == null ? "" : key; }
    public String getType() { return type == null ? "" : type; }
    public Map<String, String> getHeader() { return header == null ? new HashMap<>() : header; }
    public boolean isForceKey() { return forceKey; }
}
