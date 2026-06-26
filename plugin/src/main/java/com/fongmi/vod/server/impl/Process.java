package com.fongmi.vod.server.impl;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public interface Process {
    boolean isRequest(IHTTPSession session, String url);
    default Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        return NanoHTTPD.ok("OK");
    }
}
