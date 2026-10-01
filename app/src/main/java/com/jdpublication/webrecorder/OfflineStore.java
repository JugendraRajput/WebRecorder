package com.jdpublication.webrecorder;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OfflineStore {

    public static final String SOURCE_EXCEL = "excel";
    public static final String SOURCE_FOLDER = "folder";

    private static final String PREFS_NAME = "webrecorder_sync";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_CURRENT_FOLDER = "current_folder";
    private static final String KEY_CURRENT_DISPLAY_NAME = "current_display_name";
    private static final String KEY_CURRENT_TOTAL = "current_total";
    private static final String KEY_LAST_SOURCE_TYPE = "last_source_type";
    private static final String KEY_LAST_EXCEL_URI = "last_excel_uri";
    private static final String KEY_LAST_FOLDER = "last_folder";
    private static final String KEY_LAST_DISPLAY_NAME = "last_display_name";
    private static final String KEY_LAST_INDEX = "last_index";
    private static final String DEFAULT_SERVER_URL = "https://webrecorder.jdworks.in";
    private static final String METADATA_FILE_NAME = "metadata.json";
    private static final String PENDING_DELETES_FILE_NAME = "pending_deletes.json";

    private OfflineStore() {
    }

    public static String resolveSheetDisplayName(Context context, Uri uri) {
        String displayName = queryDisplayName(context, uri);
        if (displayName == null || displayName.trim().isEmpty()) {
            return "Current List";
        }
        return stripExtension(displayName.trim());
    }

    public static String resolveSheetFolderName(Context context, Uri uri) {
        return sanitizeFolderName(resolveSheetDisplayName(context, uri));
    }

    public static void saveCurrentListInfo(Context context, String displayName, String folderName, int totalCount) {
        prefs(context).edit()
                .putString(KEY_CURRENT_DISPLAY_NAME, displayName)
                .putString(KEY_CURRENT_FOLDER, folderName)
                .putInt(KEY_CURRENT_TOTAL, totalCount)
                .apply();
    }

    public static void saveCurrentSession(Context context, String sourceType, Uri excelUri, String displayName, String folderName, int currentIndex) {
        prefs(context).edit()
                .putString(KEY_LAST_SOURCE_TYPE, sourceType == null ? "" : sourceType)
                .putString(KEY_LAST_EXCEL_URI, excelUri == null ? "" : excelUri.toString())
                .putString(KEY_LAST_DISPLAY_NAME, displayName == null ? "" : displayName)
                .putString(KEY_LAST_FOLDER, folderName == null ? "" : sanitizeFolderName(folderName))
                .putInt(KEY_LAST_INDEX, Math.max(currentIndex, 0))
                .apply();
    }

    public static boolean hasLastSession(Context context) {
        String sourceType = getLastSourceType(context);
        if (SOURCE_EXCEL.equals(sourceType)) {
            return !getLastExcelUriString(context).isEmpty();
        }
        if (SOURCE_FOLDER.equals(sourceType)) {
            return !getLastFolderName(context).isEmpty();
        }
        return false;
    }

    public static String getLastSourceType(Context context) {
        return prefs(context).getString(KEY_LAST_SOURCE_TYPE, "");
    }

    public static Uri getLastExcelUri(Context context) {
        String uri = getLastExcelUriString(context);
        return uri.isEmpty() ? null : Uri.parse(uri);
    }

    public static String getLastFolderName(Context context) {
        return prefs(context).getString(KEY_LAST_FOLDER, "");
    }

    public static String getLastDisplayName(Context context) {
        return prefs(context).getString(KEY_LAST_DISPLAY_NAME, "");
    }

    public static int getLastIndex(Context context) {
        return prefs(context).getInt(KEY_LAST_INDEX, 0);
    }

    public static String getCurrentFolderName(Context context) {
        return prefs(context).getString(KEY_CURRENT_FOLDER, "");
    }

    public static String getCurrentDisplayName(Context context) {
        return prefs(context).getString(KEY_CURRENT_DISPLAY_NAME, "No list selected");
    }

    public static int getCurrentTotalCount(Context context) {
        return prefs(context).getInt(KEY_CURRENT_TOTAL, 0);
    }

    public static void saveServerBaseUrl(Context context, String serverBaseUrl) {
        prefs(context).edit().putString(KEY_SERVER_URL, normalizeServerUrl(serverBaseUrl)).apply();
    }

    public static String getServerBaseUrl(Context context) {
        return normalizeServerUrl(prefs(context).getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
    }

    public static File getRootDirectory(Context context) {
        return new File(context.getFilesDir(), "offline_pages");
    }

    public static File getFolderDirectory(Context context, String folderName) {
        return new File(getRootDirectory(context), sanitizeFolderName(folderName));
    }

    public static File getOfflineHtmlFile(Context context, String folderName, String url) {
        String fileName = buildOfflineFileName(url);
        File directory = getFolderDirectory(context, folderName);
        File primary = new File(directory, fileName);
        if (primary.exists()) {
            return primary;
        }

        String legacyName = toLegacyHtmlName(fileName);
        File legacy = new File(directory, legacyName);
        if (legacy.exists()) {
            return legacy;
        }

        return primary;
    }

    public static File getOfflineHtmlFile(Context context, String folderName, UrlData data) {
        String offlineFileName = data != null ? data.getOfflineFileName() : null;
        if (offlineFileName == null || offlineFileName.trim().isEmpty()) {
            offlineFileName = buildOfflineFileName(data != null ? data.getWebUrl() : "");
        }

        File directory = getFolderDirectory(context, folderName);
        File preferred = new File(directory, offlineFileName);
        if (preferred.exists()) {
            return preferred;
        }

        String legacyHtmlName = toLegacyHtmlName(offlineFileName);
        if (!legacyHtmlName.equals(offlineFileName)) {
            File legacyHtml = new File(directory, legacyHtmlName);
            if (legacyHtml.exists()) {
                return legacyHtml;
            }
        }

        String mhtName = toMhtName(offlineFileName);
        if (!mhtName.equals(offlineFileName)) {
            File mhtFallback = new File(directory, mhtName);
            if (mhtFallback.exists()) {
                return mhtFallback;
            }
        }

        return preferred;
    }

    public static int countHtmlFiles(Context context, String folderName) {
        return listHtmlFiles(context, folderName).size();
    }

    public static List<File> listHtmlFiles(Context context, String folderName) {
        List<File> files = new ArrayList<>();
        File directory = getFolderDirectory(context, folderName);
        File[] children = directory.listFiles();
        if (children == null) {
            return files;
        }

        for (File child : children) {
            String lowerName = child.getName().toLowerCase(Locale.US);
            if (child.isFile() && (lowerName.endsWith(".html") || lowerName.endsWith(".mht"))) {
                files.add(child);
            }
        }
        return files;
    }

    public static List<String> listDownloadedFolders(Context context) {
        List<String> folders = new ArrayList<>();
        File root = getRootDirectory(context);
        File[] children = root.listFiles();
        if (children == null) {
            return folders;
        }

        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            if (countHtmlFiles(context, child.getName()) > 0) {
                folders.add(child.getName());
            }
        }

        folders.sort(String::compareToIgnoreCase);
        return folders;
    }

    public static List<String> listAssetFolders(Context context) {
        List<String> folders = new ArrayList<>();
        AssetManager assetManager = context.getAssets();
        try {
            String[] rootEntries = assetManager.list("offline_pages");
            if (rootEntries == null) {
                return folders;
            }

            for (String folderName : rootEntries) {
                String[] children = assetManager.list("offline_pages/" + folderName);
                if (children == null || children.length == 0) {
                    continue;
                }

                boolean hasOfflineFiles = false;
                for (String childName : children) {
                    String lower = childName.toLowerCase(Locale.US);
                    if (lower.endsWith(".html") || lower.endsWith(".mht")) {
                        hasOfflineFiles = true;
                        break;
                    }
                }

                if (hasOfflineFiles) {
                    folders.add(folderName);
                }
            }
        } catch (Exception ignored) {
        }

        folders.sort(String::compareToIgnoreCase);
        return folders;
    }

    public static boolean importAssetFolderToLocal(Context context, String assetFolderName, boolean overwriteExisting) {
        if (assetFolderName == null || assetFolderName.trim().isEmpty()) {
            return false;
        }

        String localFolderName = sanitizeFolderName(assetFolderName);
        File targetDirectory = getFolderDirectory(context, localFolderName);
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            return false;
        }

        AssetManager assetManager = context.getAssets();
        String assetBasePath = "offline_pages/" + assetFolderName;
        boolean copiedAny = false;

        try {
            String[] assetFiles = assetManager.list(assetBasePath);
            if (assetFiles == null || assetFiles.length == 0) {
                return countHtmlFiles(context, localFolderName) > 0;
            }

            for (String assetFileName : assetFiles) {
                String lowerName = assetFileName.toLowerCase(Locale.US);
                boolean supported = lowerName.endsWith(".html")
                        || lowerName.endsWith(".mht")
                        || "metadata.json".equals(lowerName);

                if (!supported) {
                    continue;
                }

                File destination = new File(targetDirectory, assetFileName);
                if (destination.exists() && !overwriteExisting) {
                    continue;
                }

                try (InputStream inputStream = assetManager.open(assetBasePath + "/" + assetFileName);
                     OutputStream outputStream = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, read);
                    }
                    outputStream.flush();
                    copiedAny = true;
                }
            }
        } catch (Exception ignored) {
        }

        return copiedAny || countHtmlFiles(context, localFolderName) > 0;
    }

    public static List<UrlData> loadFolderEntries(Context context, String folderName) {
        List<UrlData> items = new ArrayList<>();
        List<File> htmlFiles = listHtmlFiles(context, folderName);
        if (htmlFiles.isEmpty()) {
            return items;
        }

        JSONObject metadata = readFolderMetadata(context, folderName);
        boolean metadataChanged = false;
        List<FolderItem> pendingItems = new ArrayList<>();

        for (File file : htmlFiles) {
            String fileName = file.getName();
            JSONObject entry = metadata.optJSONObject(fileName);

            String title = "";
            String originalUrl = "";
            int rowIndex = Integer.MAX_VALUE;

            if (entry != null) {
                title = entry.optString("title", "").trim();
                originalUrl = entry.optString("original_url", "").trim();
                int idx = entry.optInt("row_index", -1);
                if (idx > 0) {
                    rowIndex = idx;
                }
            }

            if (title.isEmpty() || isHexHashString(title) || isHexHashString(stripExtension(title))) {
                ExtractedMetadata extracted = extractMetadata(file);
                title = extracted.title;
                if (originalUrl.isEmpty()) {
                    originalUrl = extracted.originalUrl;
                }

                if (entry == null) {
                    entry = new JSONObject();
                }
                try {
                    entry.put("title", title);
                    entry.put("original_url", originalUrl);
                    if (rowIndex != Integer.MAX_VALUE) {
                        entry.put("row_index", rowIndex);
                    }
                    entry.put("updated_at", System.currentTimeMillis() / 1000L);
                    metadata.put(fileName, entry);
                    metadataChanged = true;
                } catch (Exception ignored) {
                }
            }

            pendingItems.add(new FolderItem(rowIndex, title, originalUrl, fileName));
        }

        pendingItems.sort((first, second) -> {
            int rowCompare = Integer.compare(first.rowIndex, second.rowIndex);
            if (rowCompare != 0) {
                return rowCompare;
            }
            return first.title.compareToIgnoreCase(second.title);
        });

        for (int i = 0; i < pendingItems.size(); i++) {
            FolderItem item = pendingItems.get(i);
            int resolvedRowIndex = item.rowIndex == Integer.MAX_VALUE ? (i + 1) : item.rowIndex;
            items.add(new UrlData(resolvedRowIndex, item.title, item.originalUrl, item.offlineFileName));
        }

        if (metadataChanged) {
            saveFolderMetadata(context, folderName, metadata);
        }

        return items;
    }

    public static JSONObject readFolderMetadata(Context context, String folderName) {
        File metadataFile = getMetadataFile(context, folderName);
        if (!metadataFile.isFile()) {
            return new JSONObject();
        }

        try {
            String json = new String(Files.readAllBytes(metadataFile.toPath()), StandardCharsets.UTF_8);
            return new JSONObject(json);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    public static void saveFolderMetadata(Context context, String folderName, JSONObject metadata) {
        if (folderName == null || folderName.trim().isEmpty()) {
            return;
        }

        File folderDirectory = getFolderDirectory(context, folderName);
        if (!folderDirectory.exists() && !folderDirectory.mkdirs()) {
            return;
        }

        File metadataFile = getMetadataFile(context, folderName);
        try {
            Files.write(metadataFile.toPath(), metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    public static List<String> getPendingDeletionNames(Context context, String folderName) {
        List<String> items = new ArrayList<>();
        File queueFile = getPendingDeletesFile(context, folderName);
        if (!queueFile.isFile()) {
            return items;
        }

        try {
            String json = new String(Files.readAllBytes(queueFile.toPath()), StandardCharsets.UTF_8);
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                String name = array.optString(i, "").trim();
                if (!name.isEmpty()) {
                    items.add(name);
                }
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    public static void queuePendingDeletion(Context context, String folderName, String offlineFileName) {
        if (folderName == null || folderName.trim().isEmpty() || offlineFileName == null || offlineFileName.trim().isEmpty()) {
            return;
        }

        List<String> current = getPendingDeletionNames(context, folderName);
        if (!current.contains(offlineFileName)) {
            current.add(offlineFileName);
            writePendingDeletionNames(context, folderName, current);
        }
    }

    public static void clearPendingDeletion(Context context, String folderName, String offlineFileName) {
        if (folderName == null || folderName.trim().isEmpty() || offlineFileName == null || offlineFileName.trim().isEmpty()) {
            return;
        }

        List<String> current = getPendingDeletionNames(context, folderName);
        if (current.remove(offlineFileName)) {
            writePendingDeletionNames(context, folderName, current);
        }
    }

    public static void upsertMetadataEntry(Context context, String folderName, UrlData data) {
        if (data == null) {
            return;
        }
        upsertMetadataEntry(context, folderName, resolveOfflineFileName(data), data.getWebUrl(), data.getFilename(), data.getRowIndex());
    }

    public static void upsertMetadataEntry(Context context, String folderName, String offlineFileName, String originalUrl, String title, int rowIndex) {
        if (folderName == null || folderName.trim().isEmpty() || offlineFileName == null || offlineFileName.trim().isEmpty()) {
            return;
        }

        JSONObject metadata = readFolderMetadata(context, folderName);
        
        String legacyHtmlName = toLegacyHtmlName(offlineFileName);
        if (!legacyHtmlName.equals(offlineFileName)) {
            metadata.remove(legacyHtmlName);
        }
        String mhtName = toMhtName(offlineFileName);
        if (!mhtName.equals(offlineFileName)) {
            metadata.remove(mhtName);
        }

        JSONObject entry = metadata.optJSONObject(offlineFileName);
        if (entry == null) {
            entry = new JSONObject();
        }

        try {
            entry.put("original_url", originalUrl == null ? "" : originalUrl.trim());
            entry.put("title", title == null ? stripExtension(offlineFileName) : title.trim());
            entry.put("row_index", rowIndex);
            entry.put("updated_at", System.currentTimeMillis() / 1000L);
            metadata.put(offlineFileName, entry);
            saveFolderMetadata(context, folderName, metadata);
        } catch (Exception ignored) {
        }
    }

    public static void removeMetadataEntry(Context context, String folderName, String offlineFileName) {
        if (folderName == null || folderName.trim().isEmpty() || offlineFileName == null || offlineFileName.trim().isEmpty()) {
            return;
        }

        JSONObject metadata = readFolderMetadata(context, folderName);
        metadata.remove(offlineFileName);
        saveFolderMetadata(context, folderName, metadata);
    }

    public static String prettifyFolderName(String folderName) {
        if (folderName == null || folderName.trim().isEmpty()) {
            return "Downloaded Folder";
        }
        return folderName.replace('_', ' ').trim();
    }

    public static String sanitizeFolderName(String rawName) {
        if (rawName == null) {
            return "default_list";
        }

        String sanitized = rawName.trim().replaceAll("\\s+", "_").replaceAll("[^A-Za-z0-9_-]", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        if (sanitized.isEmpty()) {
            sanitized = "default_list";
        }
        if (sanitized.length() > 80) {
            sanitized = sanitized.substring(0, 80);
        }
        return sanitized;
    }

    public static String buildOfflineFileName(String url) {
        String safeUrl = url == null ? "" : url;
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(safeUrl.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hash) {
                builder.append(String.format(Locale.US, "%02x", value));
            }
            return builder + ".mht";
        } catch (Exception e) {
            return Integer.toHexString(safeUrl.hashCode()) + ".mht";
        }
    }

    public static String resolveOfflineFileName(UrlData data) {
        if (data == null) {
            return buildOfflineFileName("");
        }
        String offlineFileName = data.getOfflineFileName();
        if (offlineFileName != null && !offlineFileName.trim().isEmpty()) {
            return offlineFileName;
        }
        return buildOfflineFileName(data.getWebUrl());
    }


    private static String toLegacyHtmlName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "";
        }
        String normalized = fileName.trim();
        String lower = normalized.toLowerCase(Locale.US);
        if (lower.endsWith(".mht")) {
            return normalized.substring(0, normalized.length() - 4) + ".html";
        }
        return normalized;
    }

    private static String toMhtName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "";
        }
        String normalized = fileName.trim();
        String lower = normalized.toLowerCase(Locale.US);
        if (lower.endsWith(".html")) {
            return normalized.substring(0, normalized.length() - 5) + ".mht";
        }
        return normalized;
    }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static File getMetadataFile(Context context, String folderName) {
        return new File(getFolderDirectory(context, folderName), METADATA_FILE_NAME);
    }

    private static File getPendingDeletesFile(Context context, String folderName) {
        return new File(getFolderDirectory(context, folderName), PENDING_DELETES_FILE_NAME);
    }

    private static String getLastExcelUriString(Context context) {
        return prefs(context).getString(KEY_LAST_EXCEL_URI, "");
    }

    private static void writePendingDeletionNames(Context context, String folderName, List<String> names) {
        File folderDirectory = getFolderDirectory(context, folderName);
        if (!folderDirectory.exists() && !folderDirectory.mkdirs()) {
            return;
        }

        File queueFile = getPendingDeletesFile(context, folderName);
        if (names.isEmpty()) {
            if (queueFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                queueFile.delete();
            }
            return;
        }

        JSONArray array = new JSONArray();
        for (String name : names) {
            array.put(name);
        }

        try {
            Files.write(queueFile.toPath(), array.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String queryDisplayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private static String stripExtension(String value) {
        int dotIndex = value.lastIndexOf('.');
        return dotIndex > 0 ? value.substring(0, dotIndex) : value;
    }

    private static String normalizeServerUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            normalized = DEFAULT_SERVER_URL;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static final class FolderItem {
        final int rowIndex;
        final String title;
        final String originalUrl;
        final String offlineFileName;

        FolderItem(int rowIndex, String title, String originalUrl, String offlineFileName) {
            this.rowIndex = rowIndex;
            this.title = title;
            this.originalUrl = originalUrl;
            this.offlineFileName = offlineFileName;
        }
    }

    public static List<String> listAllLocalFolders(Context context) {
        List<String> folders = new ArrayList<>();
        File root = getRootDirectory(context);
        File[] children = root.listFiles();
        if (children == null) {
            return folders;
        }

        for (File child : children) {
            if (child.isDirectory()) {
                folders.add(child.getName());
            }
        }

        folders.sort(String::compareToIgnoreCase);
        return folders;
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        if (digitGroups >= units.length) digitGroups = units.length - 1;
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    public static ValidationResult validateOfflineFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return new ValidationResult(ValidationResult.Status.CORRUPTED, "File missing or inaccessible", 0, false, false);
        }
        long length = file.length();
        if (length == 0) {
            return new ValidationResult(ValidationResult.Status.CORRUPTED, "Empty file (0 B)", 0, false, false);
        }

        String lowerName = file.getName().toLowerCase(Locale.US);
        boolean isMht = lowerName.endsWith(".mht");
        boolean isHtml = lowerName.endsWith(".html") || lowerName.endsWith(".htm");

        int bytesToRead = (int) Math.min(length, 32768L);
        byte[] buffer = new byte[bytesToRead];
        try (FileInputStream fis = new FileInputStream(file)) {
            int read = fis.read(buffer);
            if (read <= 0) {
                return new ValidationResult(ValidationResult.Status.CORRUPTED, "Could not read file data", length, isHtml, isMht);
            }
            String snippet = new String(buffer, 0, read, StandardCharsets.UTF_8).toLowerCase(Locale.US);

            if (snippet.contains("<title>404 not found</title>")
                    || snippet.contains("<title>404 - not found")
                    || snippet.contains("<h1>404 not found</h1>")
                    || snippet.contains("404 page not found")
                    || snippet.contains("wikipedia does not have an article with this exact name")) {
                return new ValidationResult(ValidationResult.Status.CORRUPTED, "404 Not Found error page", length, isHtml, isMht);
            }
            if (snippet.contains("<title>403 forbidden</title>") || snippet.contains("403 forbidden") || snippet.contains("access denied")) {
                return new ValidationResult(ValidationResult.Status.CORRUPTED, "403 Forbidden / Access Denied", length, isHtml, isMht);
            }
            if (snippet.contains("cloudflare ray id") || snippet.contains("attention required! | cloudflare")) {
                return new ValidationResult(ValidationResult.Status.CORRUPTED, "Cloudflare Challenge / Blocked", length, isHtml, isMht);
            }
            if (snippet.contains("<title>502 bad gateway</title>") || snippet.contains("<title>503 service unavailable</title>")) {
                return new ValidationResult(ValidationResult.Status.CORRUPTED, "Server Gateway / 503 error", length, isHtml, isMht);
            }

            if (length < 400 && !snippet.contains("<html") && !snippet.contains("<!doctype")) {
                return new ValidationResult(ValidationResult.Status.CORRUPTED, "Incomplete snippet (" + length + " B)", length, isHtml, isMht);
            }

            if (isMht) {
                boolean hasMhtHeader = snippet.contains("mime-version:")
                        || snippet.contains("content-type: multipart/")
                        || snippet.contains("boundary=")
                        || snippet.contains("snapshot-content-location:")
                        || snippet.contains("from: <saved by webrecorder>");
                if (hasMhtHeader) {
                    return new ValidationResult(ValidationResult.Status.VALID, "Valid MHTML archive (" + formatFileSize(length) + ")", length, false, true);
                }
                if (snippet.contains("<!doctype") || snippet.contains("<html") || snippet.contains("<body")) {
                    return new ValidationResult(ValidationResult.Status.VALID, "Valid HTML format (" + formatFileSize(length) + ")", length, true, true);
                }
                return new ValidationResult(ValidationResult.Status.WARNING, "MHTML header missing or non-standard (" + formatFileSize(length) + ")", length, false, true);
            }

            boolean hasHtmlStructure = snippet.contains("<!doctype")
                    || snippet.contains("<html")
                    || snippet.contains("<body")
                    || snippet.contains("<head")
                    || snippet.contains("<div")
                    || snippet.contains("<p");

            if (hasHtmlStructure) {
                if (length < 1024) {
                    return new ValidationResult(ValidationResult.Status.WARNING, "Short HTML content (" + formatFileSize(length) + ")", length, true, false);
                }
                return new ValidationResult(ValidationResult.Status.VALID, "Valid HTML document (" + formatFileSize(length) + ")", length, true, false);
            }

            return new ValidationResult(ValidationResult.Status.CORRUPTED, "Non-HTML or corrupted file content", length, isHtml, isMht);
        } catch (Exception e) {
            return new ValidationResult(ValidationResult.Status.CORRUPTED, "Read error: " + e.getMessage(), length, isHtml, isMht);
        }
    }

    public static FolderStats getFolderStats(Context context, String folderName) {
        List<File> files = listHtmlFiles(context, folderName);
        int valid = 0;
        int warning = 0;
        int corrupt = 0;
        long totalSize = 0;
        for (File f : files) {
            totalSize += f.length();
            ValidationResult res = validateOfflineFile(f);
            if (res.isValid()) valid++;
            else if (res.isWarning()) warning++;
            else corrupt++;
        }
        return new FolderStats(folderName, files.size(), valid, warning, corrupt, totalSize);
    }

    public static boolean deleteOfflineFile(Context context, String folderName, String fileName) {
        if (folderName == null || fileName == null) return false;
        File dir = getFolderDirectory(context, folderName);
        File file = new File(dir, fileName);
        boolean deleted = false;
        if (file.exists()) {
            deleted = file.delete();
        }
        removeMetadataEntry(context, folderName, fileName);
        queuePendingDeletion(context, folderName, fileName);
        return deleted;
    }

    public static boolean deleteOfflineFolder(Context context, String folderName) {
        if (folderName == null || folderName.trim().isEmpty()) return false;
        File dir = getFolderDirectory(context, folderName);
        if (!dir.exists()) return true;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                child.delete();
            }
        }
        return dir.delete();
    }

    public static final class ValidationResult {
        public enum Status {
            VALID,
            WARNING,
            CORRUPTED
        }

        public final Status status;
        public final String reason;
        public final long fileSizeBytes;
        public final boolean isHtml;
        public final boolean isMht;

        public ValidationResult(Status status, String reason, long fileSizeBytes, boolean isHtml, boolean isMht) {
            this.status = status;
            this.reason = reason;
            this.fileSizeBytes = fileSizeBytes;
            this.isHtml = isHtml;
            this.isMht = isMht;
        }

        public boolean isValid() {
            return status == Status.VALID;
        }

        public boolean isWarning() {
            return status == Status.WARNING;
        }

        public boolean isCorrupted() {
            return status == Status.CORRUPTED;
        }
    }

    public static final class FolderStats {
        public final String folderName;
        public final int totalFiles;
        public final int validFiles;
        public final int warningFiles;
        public final int corruptFiles;
        public final long totalSizeBytes;

        public FolderStats(String folderName, int totalFiles, int validFiles, int warningFiles, int corruptFiles, long totalSizeBytes) {
            this.folderName = folderName;
            this.totalFiles = totalFiles;
            this.validFiles = validFiles;
            this.warningFiles = warningFiles;
            this.corruptFiles = corruptFiles;
            this.totalSizeBytes = totalSizeBytes;
        }
    }

    public static final class ExtractedMetadata {
        public final String title;
        public final String originalUrl;

        public ExtractedMetadata(String title, String originalUrl) {
            this.title = title == null ? "" : title;
            this.originalUrl = originalUrl == null ? "" : originalUrl;
        }
    }

    public static ExtractedMetadata extractMetadata(File file) {
        if (file == null || !file.isFile() || file.length() == 0) {
            return new ExtractedMetadata("", "");
        }
        String title = null;
        String originalUrl = null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[Math.min((int) file.length(), 16384)];
            int read = fis.read(buffer);
            if (read > 0) {
                String snippet = new String(buffer, 0, read, StandardCharsets.UTF_8);

                // Extract title from <title>...</title>
                Matcher titleMatcher = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE).matcher(snippet);
                if (titleMatcher.find()) {
                    title = titleMatcher.group(1).trim();
                    if (title.endsWith(" - Wikipedia")) {
                        title = title.substring(0, title.length() - 12).trim();
                    }
                    title = cleanHtmlEntities(title);
                }

                // Extract originalUrl from <base href="..." />
                Matcher baseMatcher = Pattern.compile("<base[^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(snippet);
                if (baseMatcher.find()) {
                    originalUrl = baseMatcher.group(1).trim();
                } else {
                    Matcher canMatcher = Pattern.compile("<link[^>]+rel=[\"']canonical[\"'][^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(snippet);
                    if (canMatcher.find()) {
                        originalUrl = canMatcher.group(1).trim();
                    }
                }

                // Fallbacks if MHTML MIME header style:
                if (title == null) {
                    Matcher subjMatcher = Pattern.compile("subject:\\s*([^\\r\\n]+)", Pattern.CASE_INSENSITIVE).matcher(snippet);
                    if (subjMatcher.find()) {
                        title = subjMatcher.group(1).trim();
                        if (title.endsWith(" - Wikipedia")) {
                            title = title.substring(0, title.length() - 12).trim();
                        }
                    }
                }
                if (originalUrl == null) {
                    Matcher locMatcher = Pattern.compile("snapshot-content-location:\\s*(\\S+)", Pattern.CASE_INSENSITIVE).matcher(snippet);
                    if (locMatcher.find()) {
                        originalUrl = locMatcher.group(1).trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (title == null || title.trim().isEmpty() || isHexHashString(title)) {
            title = stripExtension(file.getName());
        }
        if (originalUrl == null) {
            originalUrl = "";
        }
        return new ExtractedMetadata(title, originalUrl);
    }

    public static boolean isTrueMhtmlArchive(File file) {
        if (file == null || !file.isFile() || file.length() < 10) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[Math.min((int) file.length(), 4096)];
            int read = fis.read(buffer);
            if (read <= 0) {
                return false;
            }
            String snippet = new String(buffer, 0, read, StandardCharsets.UTF_8).toLowerCase(Locale.US).trim();
            if (snippet.startsWith("<!doctype") || snippet.startsWith("<html") || snippet.startsWith("<?xml")) {
                return false;
            }
            return snippet.contains("mime-version:")
                    || snippet.contains("content-type: multipart/")
                    || snippet.contains("boundary=")
                    || snippet.contains("snapshot-content-location:")
                    || snippet.contains("from: <saved by");
        } catch (Exception e) {
            return false;
        }
    }

    public static String readOfflineHtml(File offlineFile) throws IOException {
        byte[] bytes = Files.readAllBytes(offlineFile.toPath());
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String cleanHtmlEntities(String text) {
        if (text == null) return "";
        return text.replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&apos;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private static boolean isHexHashString(String s) {
        if (s == null || (s.length() != 32 && s.length() != 40 && s.length() != 64)) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }
}
