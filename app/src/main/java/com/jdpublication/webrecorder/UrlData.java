package com.jdpublication.webrecorder;

public class UrlData {
    private final String filename;
    private final String webUrl;

    public UrlData(String filename, String webUrl) {
        this.filename = filename;
        this.webUrl = webUrl;
    }

    public String getFilename() {
        return filename;
    }

    public String getWebUrl() {
        return webUrl;
    }
}
