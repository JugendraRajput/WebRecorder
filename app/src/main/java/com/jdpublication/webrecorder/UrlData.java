package com.jdpublication.webrecorder;

public class UrlData {
    private final int rowIndex;
    private String filename;
    private String webUrl;
    private String offlineFileName;

    public UrlData(int rowIndex, String filename, String webUrl) {
        this(rowIndex, filename, webUrl, null);
    }

    public UrlData(int rowIndex, String filename, String webUrl, String offlineFileName) {
        this.rowIndex = rowIndex;
        this.filename = filename;
        this.webUrl = webUrl;
        this.offlineFileName = offlineFileName;
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

    public String getOfflineFileName() {
        return offlineFileName;
    }

    public void setOfflineFileName(String offlineFileName) {
        this.offlineFileName = offlineFileName;
    }
}
