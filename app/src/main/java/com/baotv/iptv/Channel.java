package com.baotv.iptv;

/** One channel: lightweight, no images, no extra fields. */
public class Channel {
    public final String group;
    public final String name;
    public final String url;

    public Channel(String group, String name, String url) {
        this.group = group;
        this.name = name;
        this.url = url;
    }

    @Override
    public String toString() {
        if (group == null || group.isEmpty()) return name;
        return group + " · " + name;
    }
}
