package com.fongmi.vod.impl;

public interface Diffable<T> {

    boolean isSameItem(T other);

    boolean isSameContent(T other);
}
