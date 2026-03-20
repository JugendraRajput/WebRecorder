package com.jdpublication.webrecorder;

public class UrlData {
    private final int rowIndex;
    private String filename;
    private String webUrl;

    public UrlData(int rowIndex, String filename, String webUrl) {
        this.rowIndex = rowIndex;
        this.filename = filename;
        this.webUrl = webUrl;
    }

    public int getRowIndex() {
        return rowIndex;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getWebUrl() {
        return webUrl;
    }

    public void setWebUrl(String webUrl) {
        this.webUrl = webUrl;
    }
}
