package com.fongmi.vod.live;

import com.fongmi.vod.bean.Channel;
import com.fongmi.vod.bean.Result;

/**
 * 直播API — 获取频道播放地址
 * 完整移植Fongmi LiveApi.getUrl()
 */
public class LiveApi {

    public static Result getUrl(Channel channel) throws Exception {
        Source.get().stop();
        Result result = channel.result();
        result.setUrl(Source.get().fetch(result));
        return result;
    }
}
