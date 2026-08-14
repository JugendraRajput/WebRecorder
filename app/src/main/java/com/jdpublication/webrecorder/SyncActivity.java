package com.jdpublication.webrecorder;

import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SyncActivity extends AppCompatActivity {

    private static final String TAG = "SyncActivity";

    private EditText serverUrlInput;
    private TextView currentListView;
    private TextView countsView;
    private TextView syncStatusView;
    private Button saveServerButton;
    private Button refreshStatusButton;
    private Button syncCurrentButton;
    private Button syncAllButton;

    private boolean busy = false;
    private int remoteCurrentFileCount = -1;
    private int remoteFolderCount = 0;
    private int remoteTotalFileCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.sync_settings);
        }

        serverUrlInput = findViewById(R.id.edit_server_url);
        currentListView = findViewById(R.id.text_current_list);
        countsView = findViewById(R.id.text_counts);
        syncStatusView = findViewById(R.id.text_sync_status);
        saveServerButton = findViewById(R.id.button_save_server);
        refreshStatusButton = findViewById(R.id.button_refresh_status);
        syncCurrentButton = findViewById(R.id.button_sync_current);
        syncAllButton = findViewById(R.id.button_sync_all);

        saveServerButton.setOnClickListener(v -> saveServerUrl());
        refreshStatusButton.setOnClickListener(v -> refreshServerStatus());
        syncCurrentButton.setOnClickListener(v -> syncCurrentList());
        syncAllButton.setOnClickListener(v -> syncAllFolders());
    }

    @Override
    protected void onResume() {
        super.onResume();
        serverUrlInput.setText(OfflineStore.getServerBaseUrl(this));
        refreshSummary();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveServerUrl() {
        String serverUrl = normalizeServerBaseUrl(serverUrlInput.getText().toString());
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, R.string.server_url_required, Toast.LENGTH_LONG).show();
            return;
        }
        OfflineStore.saveServerBaseUrl(this, serverUrl);
        syncStatusView.setText("Saved server URL. You can refresh sync status now.");
        refreshServerStatus();
    }

    private void refreshSummary() {
        String listName = OfflineStore.getCurrentDisplayName(this);
        String folderName = OfflineStore.getCurrentFolderName(this);
        int excelRows = OfflineStore.getCurrentTotalCount(this);
        int localFiles = folderName.isEmpty() ? 0 : OfflineStore.countHtmlFiles(this, folderName);

        currentListView.setText(folderName.isEmpty()
                ? getString(R.string.no_list_selected)
                : "Current list: " + listName + " (" + folderName + ")");

        countsView.setText(buildCountsText(excelRows, localFiles, remoteCurrentFileCount, remoteFolderCount, remoteTotalFileCount));
    }

    private String buildCountsText(int excelRows, int localFiles, int currentRemoteFiles, int folderCount, int totalRemoteFiles) {
        String remoteCountText = currentRemoteFiles >= 0 ? String.valueOf(currentRemoteFiles) : "Not checked";
        return "Excel rows: " + excelRows
                + "\nLocal offline files: " + localFiles
                + "\nServer files for current list: " + remoteCountText
                + "\nServer folders: " + folderCount
                + "\nServer total HTML files: " + totalRemoteFiles;
    }

    private void setBusyState(boolean isBusy) {
        busy = isBusy;
        saveServerButton.setEnabled(!isBusy);
        refreshStatusButton.setEnabled(!isBusy);
        syncCurrentButton.setEnabled(!isBusy);
        syncAllButton.setEnabled(!isBusy);
    }

    private void postStatus(String message) {
        runOnUiThread(() -> syncStatusView.setText(message));
    }

    private void refreshServerStatus() {
        if (busy) {
            return;
        }

        String serverUrl = normalizeServerBaseUrl(serverUrlInput.getText().toString());
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, R.string.server_url_required, Toast.LENGTH_LONG).show();
            return;
        }

        OfflineStore.saveServerBaseUrl(this, serverUrl);
        setBusyState(true);
        postStatus("Checking server status...");

        new Thread(() -> {
            try {
                RemoteSummary summary = fetchRemoteSummary(serverUrl, OfflineStore.getCurrentFolderName(this));
                remoteCurrentFileCount = summary.currentFolderFileCount;
                remoteFolderCount = summary.folderCount;
                remoteTotalFileCount = summary.totalFileCount;
                runOnUiThread(() -> {
                    refreshSummary();
                    syncStatusView.setText("Server status refreshed successfully.");
                    setBusyState(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "refreshServerStatus", e);
                runOnUiThread(() -> {
                    syncStatusView.setText("Failed to check server: " + e.getMessage());
                    setBusyState(false);
                });
            }
        }).start();
    }

    private void syncCurrentList() {
        if (busy) {
            return;
        }

        String serverUrl = normalizeServerBaseUrl(serverUrlInput.getText().toString());
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, R.string.server_url_required, Toast.LENGTH_LONG).show();
            return;
        }

        String folderName = OfflineStore.getCurrentFolderName(this);
        if (folderName.isEmpty()) {
            Toast.makeText(this, R.string.no_list_selected, Toast.LENGTH_LONG).show();
            return;
        }

        OfflineStore.saveServerBaseUrl(this, serverUrl);
        setBusyState(true);
        postStatus("Syncing current list...");

        new Thread(() -> {
            try {
                int uploaded = uploadLocalFolder(serverUrl, folderName);
                int deleted = processPendingDeletes(serverUrl, folderName);
                int downloaded = downloadFolder(serverUrl, folderName, true);
                RemoteSummary summary = fetchRemoteSummary(serverUrl, folderName);
                remoteCurrentFileCount = summary.currentFolderFileCount;
                remoteFolderCount = summary.folderCount;
                remoteTotalFileCount = summary.totalFileCount;

                runOnUiThread(() -> {
                    refreshSummary();
                    syncStatusView.setText("Current list synced. Uploaded " + uploaded + " file(s), removed " + deleted + " old file(s), downloaded " + downloaded + " file(s).");
                    setBusyState(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "syncCurrentList", e);
                runOnUiThread(() -> {
                    syncStatusView.setText("Current list sync failed: " + e.getMessage());
                    setBusyState(false);
                });
            }
        }).start();
    }

    private void syncAllFolders() {
        if (busy) {
            return;
        }

        String serverUrl = normalizeServerBaseUrl(serverUrlInput.getText().toString());
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, R.string.server_url_required, Toast.LENGTH_LONG).show();
            return;
        }

        OfflineStore.saveServerBaseUrl(this, serverUrl);
        setBusyState(true);
        postStatus("Downloading all server folders...");

        new Thread(() -> {
            try {
                JSONObject foldersJson = requestJson(buildApiUrl(serverUrl, "folders", null, null));
                JSONArray folders = foldersJson.optJSONArray("folders");
                int folderDownloadCount = 0;
                int fileDownloadCount = 0;

                if (folders != null) {
                    for (int i = 0; i < folders.length(); i++) {
                        JSONObject folderObject = folders.optJSONObject(i);
                        if (folderObject == null) {
                            continue;
                        }
                        String folderName = folderObject.optString("folder");
                        if (folderName == null || folderName.isEmpty()) {
                            continue;
                        }
                        postStatus("Downloading folder " + (i + 1) + " of " + folders.length() + ": " + folderName);
                        fileDownloadCount += downloadFolder(serverUrl, folderName, true);
                        folderDownloadCount++;
                    }
                }
                RemoteSummary summary = fetchRemoteSummary(serverUrl, OfflineStore.getCurrentFolderName(this));
                remoteCurrentFileCount = summary.currentFolderFileCount;
                remoteFolderCount = summary.folderCount;
                remoteTotalFileCount = summary.totalFileCount;

                int finalFolderDownloadCount = folderDownloadCount;
                int finalFileDownloadCount = fileDownloadCount;
                runOnUiThread(() -> {
                    refreshSummary();
                    syncStatusView.setText("Downloaded " + finalFileDownloadCount + " file(s) from " + finalFolderDownloadCount + " server folder(s).");
                    setBusyState(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "syncAllFolders", e);
                runOnUiThread(() -> {
                    syncStatusView.setText("Download all folders failed: " + e.getMessage());
                    setBusyState(false);
                });
            }
        }).start();
    }

    private int uploadLocalFolder(String serverUrl, String folderName) throws Exception {
        List<File> localFiles = OfflineStore.listHtmlFiles(this, folderName);
        JSONObject localMetadata = OfflineStore.readFolderMetadata(this, folderName);
        int uploadedCount = 0;
        for (int i = 0; i < localFiles.size(); i++) {
            File file = localFiles.get(i);
            JSONObject fileMetadata = localMetadata.optJSONObject(file.getName());
            String originalUrl = fileMetadata != null ? fileMetadata.optString("original_url", "") : "";
            String title = fileMetadata != null ? fileMetadata.optString("title", stripExtension(file.getName())) : stripExtension(file.getName());
            int rowIndex = fileMetadata != null ? fileMetadata.optInt("row_index", -1) : -1;
            postStatus("Uploading " + (i + 1) + " of " + localFiles.size() + ": " + file.getName());
            uploadFile(serverUrl, folderName, file, originalUrl, title, rowIndex);
            uploadedCount++;
        }
        return uploadedCount;
    }

    private int processPendingDeletes(String serverUrl, String folderName) throws Exception {
        List<String> pendingDeletes = OfflineStore.getPendingDeletionNames(this, folderName);
        int deletedCount = 0;
        for (int i = 0; i < pendingDeletes.size(); i++) {
            String fileName = pendingDeletes.get(i);
            postStatus("Removing old server file " + (i + 1) + " of " + pendingDeletes.size() + ": " + fileName);
            try {
                deleteRemoteFile(serverUrl, folderName, fileName);
                OfflineStore.clearPendingDeletion(this, folderName, fileName);
                deletedCount++;
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                    OfflineStore.clearPendingDeletion(this, folderName, fileName);
                } else {
                    throw e;
                }
            }
        }
        return deletedCount;
    }

    private int downloadFolder(String serverUrl, String folderName, boolean overwriteExisting) throws Exception {
        JSONObject manifestJson = requestJson(buildApiUrl(serverUrl, "manifest", folderName, null));
        JSONArray files = manifestJson.optJSONArray("files");
        if (files == null) {
            return 0;
        }

        File folderDirectory = OfflineStore.getFolderDirectory(this, folderName);
        if (!folderDirectory.exists() && !folderDirectory.mkdirs()) {
            throw new IllegalStateException("Could not create local folder for " + folderName);
        }

        JSONObject metadata = new JSONObject();
        int downloadedCount = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject fileObject = files.optJSONObject(i);
            if (fileObject == null) {
                continue;
            }
            String fileName = fileObject.optString("name");
            if (fileName == null || fileName.isEmpty()) {
                continue;
            }

            File destination = new File(folderDirectory, fileName);
            if (overwriteExisting || !destination.exists()) {
                byte[] bytes = requestBytes(buildApiUrl(serverUrl, "download", folderName, fileName));
                try (FileOutputStream outputStream = new FileOutputStream(destination)) {
                    outputStream.write(bytes);
                    outputStream.flush();
                }
                downloadedCount++;
            }

            JSONObject itemMetadata = new JSONObject();
            itemMetadata.put("original_url", fileObject.optString("original_url", ""));
            itemMetadata.put("title", fileObject.optString("title", stripExtension(fileName)));
            itemMetadata.put("row_index", fileObject.optInt("row_index", -1));
            itemMetadata.put("updated_at", fileObject.optLong("updated_at", System.currentTimeMillis() / 1000L));
            metadata.put(fileName, itemMetadata);
        }

        OfflineStore.saveFolderMetadata(this, folderName, metadata);
        return downloadedCount;
    }

    private RemoteSummary fetchRemoteSummary(String serverUrl, String currentFolder) throws Exception {
        JSONObject foldersJson = requestJson(buildApiUrl(serverUrl, "folders", null, null));
        JSONArray folders = foldersJson.optJSONArray("folders");
        RemoteSummary summary = new RemoteSummary();

        if (folders != null) {
            summary.folderCount = folders.length();
            for (int i = 0; i < folders.length(); i++) {
                JSONObject folderObject = folders.optJSONObject(i);
                if (folderObject == null) {
                    continue;
                }
                summary.totalFileCount += folderObject.optInt("file_count", 0);
            }
        }

        if (currentFolder != null && !currentFolder.isEmpty()) {
            JSONObject statusJson = requestJson(buildApiUrl(serverUrl, "status", currentFolder, null));
            summary.currentFolderFileCount = statusJson.optInt("file_count", 0);
        }
        return summary;
    }

    private void uploadFile(String serverUrl, String folderName, File file, String originalUrl, String title, int rowIndex) throws Exception {
        String boundary = "----WebRecorderBoundary" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL(buildApiUrl(serverUrl, "upload", null, null)).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (OutputStream outputStream = connection.getOutputStream()) {
            writeFormField(outputStream, boundary, "action", "upload");
            writeFormField(outputStream, boundary, "folder", folderName);
            writeFormField(outputStream, boundary, "filename", file.getName());
            writeFormField(outputStream, boundary, "original_url", originalUrl == null ? "" : originalUrl);
            writeFormField(outputStream, boundary, "title", title == null ? "" : title);
            writeFormField(outputStream, boundary, "row_index", String.valueOf(rowIndex));
            writeFileField(outputStream, boundary, "html_file", file);
            outputStream.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }

        JSONObject response = readJsonResponse(connection);
        if (!response.optBoolean("ok", false)) {
            throw new IllegalStateException(response.optString("message", "Upload failed"));
        }
    }

    private void deleteRemoteFile(String serverUrl, String folderName, String fileName) throws Exception {
        String requestBody = "action=" + URLEncoder.encode("delete", "UTF-8")
                + "&folder=" + URLEncoder.encode(folderName, "UTF-8")
                + "&file=" + URLEncoder.encode(fileName, "UTF-8");

        HttpURLConnection connection = (HttpURLConnection) new URL(serverUrl + "/sync_api.php").openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(requestBody.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }

        JSONObject response = readJsonResponse(connection);
        if (!response.optBoolean("ok", false)) {
            throw new IllegalStateException(response.optString("message", "Delete failed"));
        }
    }

    private void writeFormField(OutputStream outputStream, String boundary, String name, String value) throws Exception {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        outputStream.write(header.getBytes(StandardCharsets.UTF_8));
    }
    private void writeFileField(OutputStream outputStream, String boundary, String name, File file) throws Exception {
        String lowerName = file.getName().toLowerCase();
        String contentType = lowerName.endsWith(".mht") ? "message/rfc822" : "text/html";
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getName() + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        outputStream.write(header.getBytes(StandardCharsets.UTF_8));
        try (FileInputStream inputStream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
        }
        outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private JSONObject requestJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("GET");
        return readJsonResponse(connection);
    }

    private JSONObject readJsonResponse(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = readText(stream);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + body);
        }
        return new JSONObject(body);
    }

    private byte[] requestBytes(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("GET");
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        byte[] bytes = readBytes(stream);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + new String(bytes, StandardCharsets.UTF_8));
        }
        return bytes;
    }

    private String readText(InputStream inputStream) throws Exception {
        return new String(readBytes(inputStream), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(InputStream inputStream) throws Exception {
        if (inputStream == null) {
            return new byte[0];
        }
        try (InputStream stream = inputStream; ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    private String buildApiUrl(String serverUrl, String action, String folder, String fileName) throws Exception {
        StringBuilder builder = new StringBuilder();
        builder.append(serverUrl).append("/sync_api.php?action=").append(URLEncoder.encode(action, "UTF-8"));
        if (folder != null && !folder.isEmpty()) {
            builder.append("&folder=").append(URLEncoder.encode(folder, "UTF-8"));
        }
        if (fileName != null && !fileName.isEmpty()) {
            builder.append("&file=").append(URLEncoder.encode(fileName, "UTF-8"));
        }
        return builder.toString();
    }

    private String normalizeServerBaseUrl(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String stripExtension(String value) {
        int dotIndex = value.lastIndexOf('.');
        return dotIndex > 0 ? value.substring(0, dotIndex) : value;
    }

    private static final class RemoteSummary {
        int currentFolderFileCount = -1;
        int folderCount = 0;
        int totalFileCount = 0;
    }
}
