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
        JSONObject metadata = readFolderMetadata(context, folderName);
        List<FolderItem> pendingItems = new ArrayList<>();

        Iterator<String> keys = metadata.keys();
        while (keys.hasNext()) {
            String offlineFileName = keys.next();
            JSONObject entry = metadata.optJSONObject(offlineFileName);
            if (entry == null) {
                continue;
            }
            String title = entry.optString("title", "").trim();
            if (title.isEmpty()) {
                title = stripExtension(offlineFileName);
            }
            String originalUrl = entry.optString("original_url", "").trim();
            int rowIndex = entry.optInt("row_index", Integer.MAX_VALUE);
            if (rowIndex < 0) {
                rowIndex = Integer.MAX_VALUE;
            }
            pendingItems.add(new FolderItem(rowIndex, title, originalUrl, offlineFileName));
        }

        if (!pendingItems.isEmpty()) {
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
            return items;
        }

        List<File> htmlFiles = listHtmlFiles(context, folderName);
        htmlFiles.sort((first, second) -> first.getName().compareToIgnoreCase(second.getName()));
        for (int i = 0; i < htmlFiles.size(); i++) {
            String fileName = htmlFiles.get(i).getName();
            items.add(new UrlData(i + 1, stripExtension(fileName), "", fileName));
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
}
