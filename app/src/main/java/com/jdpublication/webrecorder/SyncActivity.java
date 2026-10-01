package com.jdpublication.webrecorder;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.progressindicator.LinearProgressIndicator;

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
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SyncActivity extends AppCompatActivity {

    private static final String TAG = "SyncActivity";
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private EditText editServerUrl;
    private TextView textServerStatusBadge;
    private Spinner spinnerThreads;
    private Button buttonTestConnection;
    private Button buttonSaveServer;

    private Spinner spinnerFolders;
    private TextView textSelectedFolderTitle;
    private TextView textSelectedFolderStats;
    private Button buttonUploadSelected;
    private Button buttonDownloadSelected;
    private Button buttonSyncSelected;

    private Button buttonDownloadAll;
    private Button buttonUploadAll;
    private Button buttonOpenOfflineManager;

    private LinearProgressIndicator syncProgressBar;
    private TextView textProgressPercentage;
    private TextView textSyncStatus;
    private TextView textSyncLog;
    private ScrollView scrollLog;
    private Button buttonClearLog;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int MAX_LOG_LINES = 60;
    private final Deque<String> logLines = new ArrayDeque<>(MAX_LOG_LINES);
    private final AtomicBoolean logUpdateScheduled = new AtomicBoolean(false);

    private boolean isBusy = false;
    private int syncThreadCount = 8;
    private final List<String> availableFolders = new ArrayList<>();
    private final Map<String, Integer> remoteFolderCounts = new HashMap<>();
    private String selectedFolder = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Cloud Sync & Backup Hub");
        }

        initializeViews();
        setupListeners();
        setupThreadSpinner();

        editServerUrl.setText(OfflineStore.getServerBaseUrl(this));
        appendLog("[System] Cloud Sync Hub initialized with Multi-Threading.");
        testConnection(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFolderList();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void initializeViews() {
        editServerUrl = findViewById(R.id.edit_server_url);
        textServerStatusBadge = findViewById(R.id.text_server_status_badge);
        spinnerThreads = findViewById(R.id.spinner_sync_threads);
        buttonTestConnection = findViewById(R.id.button_test_connection);
        buttonSaveServer = findViewById(R.id.button_save_server);

        spinnerFolders = findViewById(R.id.spinner_folders);
        textSelectedFolderTitle = findViewById(R.id.text_selected_folder_title);
        textSelectedFolderStats = findViewById(R.id.text_selected_folder_stats);
        buttonUploadSelected = findViewById(R.id.button_upload_selected);
        buttonDownloadSelected = findViewById(R.id.button_download_selected);
        buttonSyncSelected = findViewById(R.id.button_sync_selected);

        buttonDownloadAll = findViewById(R.id.button_download_all);
        buttonUploadAll = findViewById(R.id.button_upload_all);
        buttonOpenOfflineManager = findViewById(R.id.button_open_offline_manager);

        syncProgressBar = findViewById(R.id.sync_progress_bar);
        textProgressPercentage = findViewById(R.id.text_progress_percentage);
        textSyncStatus = findViewById(R.id.text_sync_status);
        textSyncLog = findViewById(R.id.text_sync_log);
        scrollLog = findViewById(R.id.scroll_log);
        buttonClearLog = findViewById(R.id.button_clear_log);
    }

    private void setupThreadSpinner() {
        String[] threadOptions = {"4 Threads (Normal)", "8 Threads (Fast - Recommended)", "12 Threads (High-Speed)", "16 Threads (Ultra / Max)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, threadOptions);
        spinnerThreads.setAdapter(adapter);
        spinnerThreads.setSelection(1); // Default to 8 threads

        spinnerThreads.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                switch (position) {
                    case 0: syncThreadCount = 4; break;
                    case 1: syncThreadCount = 8; break;
                    case 2: syncThreadCount = 12; break;
                    case 3: syncThreadCount = 16; break;
                    default: syncThreadCount = 8; break;
                }
                appendLog("[Config] Multi-thread concurrency set to " + syncThreadCount + " parallel workers.");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupListeners() {
        buttonSaveServer.setOnClickListener(v -> saveServerUrl());
        buttonTestConnection.setOnClickListener(v -> testConnection(true));

        spinnerFolders.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < availableFolders.size()) {
                    selectedFolder = availableFolders.get(position);
                    updateSelectedFolderUi();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        buttonUploadSelected.setOnClickListener(v -> performUploadSelected());
        buttonDownloadSelected.setOnClickListener(v -> performDownloadSelected());
        buttonSyncSelected.setOnClickListener(v -> performSyncSelected());

        buttonDownloadAll.setOnClickListener(v -> performDownloadAll());
        buttonUploadAll.setOnClickListener(v -> performUploadAll());

        buttonOpenOfflineManager.setOnClickListener(v -> {
            Intent intent = new Intent(SyncActivity.this, OfflineManagerActivity.class);
            if (!selectedFolder.isEmpty()) {
                intent.putExtra("folder_name", selectedFolder);
            }
            startActivity(intent);
        });

        buttonClearLog.setOnClickListener(v -> {
            synchronized (logLines) {
                logLines.clear();
            }
            textSyncLog.setText("");
        });
    }

    private void setBusy(boolean busy) {
        this.isBusy = busy;
        buttonTestConnection.setEnabled(!busy);
        buttonSaveServer.setEnabled(!busy);
        buttonUploadSelected.setEnabled(!busy);
        buttonDownloadSelected.setEnabled(!busy);
        buttonSyncSelected.setEnabled(!busy);
        buttonDownloadAll.setEnabled(!busy);
        buttonUploadAll.setEnabled(!busy);
        spinnerFolders.setEnabled(!busy);
        spinnerThreads.setEnabled(!busy);
    }

    private void saveServerUrl() {
        String url = normalizeServerBaseUrl(editServerUrl.getText().toString());
        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a valid server URL", Toast.LENGTH_SHORT).show();
            return;
        }
        OfflineStore.saveServerBaseUrl(this, url);
        appendLog("[Settings] Saved server base URL: " + url);
        Toast.makeText(this, "Server URL saved", Toast.LENGTH_SHORT).show();
        testConnection(true);
    }

    private void appendLog(String message) {
        String timestamp = TIME_FORMAT.format(new Date());
        String line = "[" + timestamp + "] " + message;
        synchronized (logLines) {
            if (logLines.size() >= MAX_LOG_LINES) {
                logLines.pollFirst();
            }
            logLines.addLast(line);
        }
        if (logUpdateScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(this::flushLogToUi, 250);
        }
    }

    private void flushLogToUi() {
        logUpdateScheduled.set(false);
        if (isFinishing() || isDestroyed()) return;
        StringBuilder sb = new StringBuilder(2048);
        synchronized (logLines) {
            for (String l : logLines) {
                sb.append(l).append('\n');
            }
        }
        try {
            textSyncLog.setText(sb.toString());
            scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
        } catch (Throwable t) {
            Log.e(TAG, "Log render error: " + t.getMessage());
            synchronized (logLines) {
                logLines.clear();
            }
        }
    }

    private void setProgress(int current, int total, String statusText) {
        mainHandler.post(() -> {
            if (total > 0) {
                int percent = (int) (((float) current / total) * 100);
                syncProgressBar.setProgress(percent);
                textProgressPercentage.setText(percent + "% (" + current + "/" + total + ")");
            } else {
                syncProgressBar.setProgress(0);
                textProgressPercentage.setText("Ready");
            }
            textSyncStatus.setText(statusText);
        });
    }

    private void testConnection(boolean notifySuccess) {
        String serverUrl = normalizeServerBaseUrl(editServerUrl.getText().toString());
        if (serverUrl.isEmpty()) return;

        setBusy(true);
        textServerStatusBadge.setText("Pinging...");
        textServerStatusBadge.setTextColor(getColor(R.color.badge_warning_text));

        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            try {
                JSONObject json = requestJson(serverUrl + "/sync_api.php?action=folders");
                long ping = System.currentTimeMillis() - startTime;
                boolean ok = json.optBoolean("ok", false);
                JSONArray folders = json.optJSONArray("folders");
                int folderCount = folders != null ? folders.length() : 0;
                int totalCloudFiles = 0;

                remoteFolderCounts.clear();
                if (folders != null) {
                    for (int i = 0; i < folders.length(); i++) {
                        JSONObject fo = folders.optJSONObject(i);
                        if (fo != null) {
                            String fName = fo.optString("folder");
                            int fCount = fo.optInt("file_count", 0);
                            remoteFolderCounts.put(fName, fCount);
                            totalCloudFiles += fCount;
                        }
                    }
                }

                int finalTotalCloudFiles = totalCloudFiles;
                mainHandler.post(() -> {
                    setBusy(false);
                    if (ok) {
                        textServerStatusBadge.setText("Connected (" + ping + "ms)");
                        textServerStatusBadge.setTextColor(getColor(R.color.badge_valid_text));
                        appendLog("[Ping] Server online! Ping: " + ping + "ms | Cloud folders: " + folderCount + " (" + finalTotalCloudFiles + " files)");
                        if (notifySuccess) {
                            Toast.makeText(SyncActivity.this, "Connected! Ping: " + ping + "ms", Toast.LENGTH_SHORT).show();
                        }
                        refreshFolderList();
                    } else {
                        textServerStatusBadge.setText("Error");
                        textServerStatusBadge.setTextColor(getColor(R.color.badge_corrupt_text));
                        appendLog("[Ping Error] Server returned: " + json.optString("message", "Unknown error"));
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    setBusy(false);
                    textServerStatusBadge.setText("Offline");
                    textServerStatusBadge.setTextColor(getColor(R.color.badge_corrupt_text));
                    appendLog("[Ping Error] " + e.getMessage());
                });
            }
        }).start();
    }

    private void refreshFolderList() {
        Set<String> allFoldersSet = new HashSet<>();

        // Add all local folders
        List<String> localFolders = OfflineStore.listAllLocalFolders(this);
        allFoldersSet.addAll(localFolders);

        // Add current folder if any
        String currentList = OfflineStore.getCurrentFolderName(this);
        if (!currentList.isEmpty()) {
            allFoldersSet.add(currentList);
        }

        // Add all remote folders
        allFoldersSet.addAll(remoteFolderCounts.keySet());

        availableFolders.clear();
        availableFolders.addAll(allFoldersSet);
        availableFolders.sort(String::compareToIgnoreCase);

        List<String> spinnerLabels = new ArrayList<>();
        int selectedIndex = 0;

        for (int i = 0; i < availableFolders.size(); i++) {
            String folder = availableFolders.get(i);
            int localFiles = OfflineStore.countHtmlFiles(this, folder);
            int cloudFiles = remoteFolderCounts.getOrDefault(folder, 0);

            spinnerLabels.add(folder + "  [Local: " + localFiles + " | Cloud: " + cloudFiles + "]");

            if (folder.equals(selectedFolder) || (selectedFolder.isEmpty() && folder.equals(currentList))) {
                selectedIndex = i;
            }
        }

        if (availableFolders.isEmpty()) {
            availableFolders.add("default_list");
            spinnerLabels.add("default_list [Local: 0 | Cloud: 0]");
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, spinnerLabels);
        spinnerFolders.setAdapter(adapter);
        spinnerFolders.setSelection(selectedIndex);

        if (!availableFolders.isEmpty()) {
            selectedFolder = availableFolders.get(selectedIndex);
            updateSelectedFolderUi();
        }
    }

    private void updateSelectedFolderUi() {
        if (selectedFolder.isEmpty()) return;

        int localCount = OfflineStore.countHtmlFiles(this, selectedFolder);
        int cloudCount = remoteFolderCounts.getOrDefault(selectedFolder, 0);
        int pendingDeletes = OfflineStore.getPendingDeletionNames(this, selectedFolder).size();
        OfflineStore.FolderStats stats = OfflineStore.getFolderStats(this, selectedFolder);

        textSelectedFolderTitle.setText("Folder: " + OfflineStore.prettifyFolderName(selectedFolder));

        String statusSummary;
        if (localCount == cloudCount && localCount > 0) {
            statusSummary = "In Sync (" + localCount + " files)";
        } else if (localCount > cloudCount) {
            statusSummary = "Needs Upload (" + (localCount - cloudCount) + " local files ahead)";
        } else if (cloudCount > localCount) {
            statusSummary = "Needs Download (" + (cloudCount - localCount) + " cloud files ahead)";
        } else {
            statusSummary = "Empty";
        }

        textSelectedFolderStats.setText("Local files: " + localCount + " (" + OfflineStore.formatFileSize(stats.totalSizeBytes) + ")"
                + " | Cloud files: " + cloudCount
                + (pendingDeletes > 0 ? " | Pending deletes: " + pendingDeletes : "")
                + "\nStatus: " + statusSummary);
    }

    private void performUploadSelected() {
        if (isBusy || selectedFolder.isEmpty()) return;
        String serverUrl = normalizeServerBaseUrl(editServerUrl.getText().toString());
        setBusy(true);
        appendLog("[Multi-Thread Upload] Starting parallel upload for folder: " + selectedFolder + " with " + syncThreadCount + " workers.");

        new Thread(() -> {
            try {
                int uploaded = uploadLocalFolderConcurrent(serverUrl, selectedFolder);
                mainHandler.post(() -> {
                    setBusy(false);
                    setProgress(100, 100, "Upload completed! " + uploaded + " files uploaded.");
                    Toast.makeText(SyncActivity.this, "Uploaded " + uploaded + " file(s)", Toast.LENGTH_SHORT).show();
                    testConnection(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "uploadLocalFolder", e);
                mainHandler.post(() -> {
                    setBusy(false);
                    setProgress(0, 0, "Upload failed: " + e.getMessage());
                    appendLog("[Upload Error] " + e.getMessage());
                });
            }
        }).start();
    }

    private void performDownloadSelected() {
        if (isBusy || selectedFolder.isEmpty()) return;
        String serverUrl = normalizeServerBaseUrl(editServerUrl.getText().toString());
        setBusy(true);
        appendLog("[Multi-Thread Download] Starting parallel download for folder: " + selectedFolder + " with " + syncThreadCount + " workers.");

        new Thread(() -> {
            try {
                int downloaded = downloadFolderConcurrent(serverUrl, selectedFolder, true);
                mainHandler.post(() -> {
                    setBusy(false);
                    setProgress(100, 100, "Download completed! " + downloaded + " files downloaded.");
                    Toast.makeText(SyncActivity.this, "Downloaded " + downloaded + " file(s)", Toast.LENGTH_SHORT).show();
                    refreshFolderList();
                });
            } catch (Exception e) {
                Log.e(TAG, "downloadFolder", e);
                mainHandler.post(() -> {
                    setBusy(false);
                    setProgress(0, 0, "Download failed: " + e.getMessage());
                    appendLog("[Download Error] " + e.getMessage());
                });
            }
        }).start();
    }

    private void performSyncSelected() {
        if (isBusy || selectedFolder.isEmpty()) return;
        String serverUrl = normalizeServerBaseUrl(editServerUrl.getText().toString());
        setBusy(true);
        appendLog("[Multi-Thread Sync] Starting two-way parallel sync for: " + selectedFolder + " with " + syncThreadCount + " workers.");

        new Thread(() -> {
            try {
                int uploaded = uploadLocalFolderConcurrent(serverUrl, selectedFolder);
                int deleted = processPendingDeletes(serverUrl, selectedFolder);
                int downloaded = downloadFolderConcurrent(serverUrl, selectedFolder, true);

                mainHandler.post(() -> {
                    setBusy(false);
                    setProgress(100, 100, "Sync complete! Up: " + uploaded + ", Down: " + downloaded + ", Del: " + deleted);
                    appendLog("[Sync Complete] Up: " + uploaded + " | Down: " + downloaded + " | Removed: " + deleted);
                    Toast.makeText(SyncActivity.this, "Sync complete!", Toast.LENGTH_SHORT).show();
                    testConnection(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "syncSelected", e);
                mainHandler.post(() -> {
                    setBusy(false);
                    setProgress(0, 0, "Sync failed: " + e.getMessage());
                    appendLog("[Sync Error] " + e.getMessage());
                });
            }
        }).start();
    }

    private void performDownloadAll() {
        if (isBusy) return;
        String serverUrl = normalizeServerBaseUrl(editServerUrl.getText().toString());
        setBusy(true);
        appendLog("[Batch Download] Downloading all folders from cloud via " + syncThreadCount + " threads...");

        new Thread(() -> {
            try {
                JSONObject foldersJson = requestJson(serverUrl + "/sync_api.php?action=folders");
                JSONArray folders = foldersJson.optJSONArray("folders");
                int totalDownloaded = 0;
                if (folders != null) {
                    for (int i = 0; i < folders.length(); i++) {
                        JSONObject fo = folders.optJSONObject(i);
                        if (fo == null) continue;
                        String fName = fo.optString("folder");
                        if (fName.isEmpty()) continue;

                        int finalI = i;
                        int totalFolders = folders.length();
                        mainHandler.post(() -> appendLog("[Folder " + (finalI + 1) + "/" + totalFolders + "] Downloading: " + fName));
                        totalDownloaded += downloadFolderConcurrent(serverUrl, fName, true);
                    }
                }

                int finalDownloaded = totalDownloaded;
                mainHandler.post(() -> {
                    setBusy(false);
                    setProgress(100, 100, "All cloud folders downloaded (" + finalDownloaded + " files).");
                    appendLog("[Batch Complete] Downloaded " + finalDownloaded + " files from cloud.");
                    Toast.makeText(SyncActivity.this, "Downloaded " + finalDownloaded + " files", Toast.LENGTH_SHORT).show();
                    refreshFolderList();
                });
            } catch (Exception e) {
                Log.e(TAG, "performDownloadAll", e);
                mainHandler.post(() -> {
                    setBusy(false);
                    setProgress(0, 0, "Download all failed: " + e.getMessage());
                    appendLog("[Batch Error] " + e.getMessage());
                });
            }
        }).start();
    }

    private void performUploadAll() {
        if (isBusy) return;
        String serverUrl = normalizeServerBaseUrl(editServerUrl.getText().toString());
        setBusy(true);
        appendLog("[Batch Upload] Uploading all local folders to cloud via " + syncThreadCount + " threads...");

        new Thread(() -> {
            try {
                List<String> localFolders = OfflineStore.listAllLocalFolders(this);
                int totalUploaded = 0;
                for (int i = 0; i < localFolders.size(); i++) {
                    String folder = localFolders.get(i);
                    int count = OfflineStore.countHtmlFiles(this, folder);
                    if (count == 0) continue;

                    int finalI = i;
                    mainHandler.post(() -> appendLog("[Folder " + (finalI + 1) + "/" + localFolders.size() + "] Uploading: " + folder + " (" + count + " files)"));
                    totalUploaded += uploadLocalFolderConcurrent(serverUrl, folder);
                    processPendingDeletes(serverUrl, folder);
                }

                int finalTotal = totalUploaded;
                mainHandler.post(() -> {
                    setBusy(false);
                    setProgress(100, 100, "All local folders uploaded (" + finalTotal + " files).");
                    appendLog("[Batch Complete] Uploaded " + finalTotal + " files to cloud.");
                    Toast.makeText(SyncActivity.this, "Uploaded " + finalTotal + " files", Toast.LENGTH_SHORT).show();
                    testConnection(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "performUploadAll", e);
                mainHandler.post(() -> {
                    setBusy(false);
                    setProgress(0, 0, "Upload all failed: " + e.getMessage());
                    appendLog("[Batch Error] " + e.getMessage());
                });
            }
        }).start();
    }

    /**
     * Multi-Threaded Upload of Local Folder
     */
    private int uploadLocalFolderConcurrent(String serverUrl, String folderName) throws Exception {
        List<File> localFiles = OfflineStore.listHtmlFiles(this, folderName);
        JSONObject localMetadata = OfflineStore.readFolderMetadata(this, folderName);
        if (localFiles.isEmpty()) return 0;

        int total = localFiles.size();
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicLong totalBytes = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(syncThreadCount);
        CountDownLatch latch = new CountDownLatch(total);

        appendLog("[Multi-Thread Upload] Dispatching " + total + " files across " + syncThreadCount + " concurrent worker threads...");

        for (int i = 0; i < total; i++) {
            File file = localFiles.get(i);
            JSONObject fileMetadata = localMetadata.optJSONObject(file.getName());
            String originalUrl = fileMetadata != null ? fileMetadata.optString("original_url", "") : "";
            String title = fileMetadata != null ? fileMetadata.optString("title", stripExtension(file.getName())) : stripExtension(file.getName());
            int rowIndex = fileMetadata != null ? fileMetadata.optInt("row_index", -1) : -1;

            pool.submit(() -> {
                try {
                    uploadFile(serverUrl, folderName, file, originalUrl, title, rowIndex);
                    successCount.incrementAndGet();
                    totalBytes.addAndGet(file.length());
                    appendLog("  ⬆ [Thread " + (Thread.currentThread().getId() % 100) + "] Uploaded: " + file.getName() + " (" + OfflineStore.formatFileSize(file.length()) + ")");
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    appendLog("  ❌ [Upload Error] " + file.getName() + ": " + e.getMessage());
                } finally {
                    int c = completedCount.incrementAndGet();
                    setProgress(c, total, "Uploading (" + c + "/" + total + ") via " + syncThreadCount + " threads • " + OfflineStore.formatFileSize(totalBytes.get()));
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
        appendLog("[Upload Summary] " + successCount.get() + " uploaded, " + failCount.get() + " failed (" + OfflineStore.formatFileSize(totalBytes.get()) + " transferred).");
        return successCount.get();
    }

    private int processPendingDeletes(String serverUrl, String folderName) throws Exception {
        List<String> pendingDeletes = OfflineStore.getPendingDeletionNames(this, folderName);
        int deletedCount = 0;
        for (int i = 0; i < pendingDeletes.size(); i++) {
            String fileName = pendingDeletes.get(i);
            appendLog("  [Del] Removing from cloud: " + fileName);
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

    /**
     * Multi-Threaded Download of Cloud Folder with Integrity Validation
     */
    private int downloadFolderConcurrent(String serverUrl, String folderName, boolean overwriteExisting) throws Exception {
        JSONObject manifestJson = requestJson(serverUrl + "/sync_api.php?action=manifest&folder=" + URLEncoder.encode(folderName, "UTF-8"));
        JSONArray files = manifestJson.optJSONArray("files");
        if (files == null || files.length() == 0) return 0;

        File folderDirectory = OfflineStore.getFolderDirectory(this, folderName);
        if (!folderDirectory.exists() && !folderDirectory.mkdirs()) {
            throw new IllegalStateException("Could not create local folder for " + folderName);
        }

        JSONObject metadata = OfflineStore.readFolderMetadata(this, folderName);
        int total = files.length();
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger downloadedCount = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicLong totalBytes = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(syncThreadCount);
        CountDownLatch latch = new CountDownLatch(total);

        appendLog("[Multi-Thread Download] Dispatching " + total + " files across " + syncThreadCount + " concurrent worker threads...");

        for (int i = 0; i < total; i++) {
            JSONObject fileObject = files.optJSONObject(i);
            if (fileObject == null) {
                completedCount.incrementAndGet();
                latch.countDown();
                continue;
            }

            String fileName = fileObject.optString("name");
            if (fileName == null || fileName.isEmpty()) {
                completedCount.incrementAndGet();
                latch.countDown();
                continue;
            }

            File destination = new File(folderDirectory, fileName);
            String downloadUrl = serverUrl + "/sync_api.php?action=download&folder=" + URLEncoder.encode(folderName, "UTF-8") + "&file=" + URLEncoder.encode(fileName, "UTF-8");

            pool.submit(() -> {
                try {
                    // Skip if exists with valid size and overwrite is false
                    if (!overwriteExisting && destination.exists() && destination.length() > 1024) {
                        skippedCount.incrementAndGet();
                        appendLog("  ⏩ [Skip Existing] " + fileName + " (" + OfflineStore.formatFileSize(destination.length()) + ")");
                    } else {
                        // Stream download to temp file to avoid OOM on large files
                        File tempFile = new File(folderDirectory, fileName + ".tmp");
                        long downloadedBytes = streamDownloadToFile(downloadUrl, tempFile);
                        if (downloadedBytes <= 0) {
                            if (tempFile.exists()) tempFile.delete();
                            throw new IllegalStateException("Empty 0-byte file from server");
                        }

                        // Validate content: Reject small error responses (e.g. 50-byte 404/JSON stubs)
                        if (downloadedBytes < 500) {
                            byte[] probe = new byte[(int) downloadedBytes];
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile)) {
                                fis.read(probe);
                            }
                            String probeStr = new String(probe, StandardCharsets.UTF_8).toLowerCase(Locale.US);
                            if (probeStr.contains("\"ok\":false") || probeStr.contains("404 not found") || probeStr.contains("error")) {
                                tempFile.delete();
                                throw new IllegalStateException("Server returned error stub: " + probeStr.trim());
                            }
                        }

                        // Atomic rename: temp -> final destination
                        if (destination.exists()) destination.delete();
                        if (!tempFile.renameTo(destination)) {
                            // Fallback: copy bytes if rename fails (cross-filesystem)
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(tempFile);
                                 FileOutputStream fos = new FileOutputStream(destination)) {
                                byte[] buf = new byte[8192];
                                int read;
                                while ((read = fis.read(buf)) != -1) {
                                    fos.write(buf, 0, read);
                                }
                            }
                            tempFile.delete();
                        }

                        downloadedCount.incrementAndGet();
                        totalBytes.addAndGet(downloadedBytes);
                        appendLog("  ⬇ [Thread " + (Thread.currentThread().getId() % 100) + "] Saved: " + fileName + " (" + OfflineStore.formatFileSize(downloadedBytes) + ")");
                    }

                    // Thread-safe update of metadata
                    synchronized (metadata) {
                        JSONObject itemMetadata = new JSONObject();
                        itemMetadata.put("original_url", fileObject.optString("original_url", ""));
                        itemMetadata.put("title", fileObject.optString("title", stripExtension(fileName)));
                        itemMetadata.put("row_index", fileObject.optInt("row_index", -1));
                        itemMetadata.put("updated_at", fileObject.optLong("updated_at", System.currentTimeMillis() / 1000L));
                        metadata.put(fileName, itemMetadata);
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    appendLog("  ❌ [Download Error] " + fileName + ": " + e.getMessage());
                } finally {
                    int c = completedCount.incrementAndGet();
                    setProgress(c, total, "Downloading (" + c + "/" + total + ") via " + syncThreadCount + " threads • " + OfflineStore.formatFileSize(totalBytes.get()));
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        synchronized (metadata) {
            OfflineStore.saveFolderMetadata(this, folderName, metadata);
        }

        appendLog("[Download Summary] " + downloadedCount.get() + " downloaded (" + OfflineStore.formatFileSize(totalBytes.get()) + "), " + skippedCount.get() + " skipped, " + failCount.get() + " failed.");
        return downloadedCount.get();
    }

    private void uploadFile(String serverUrl, String folderName, File file, String originalUrl, String title, int rowIndex) throws Exception {
        String boundary = "----WebRecorderBoundary" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
        HttpURLConnection connection = (HttpURLConnection) new URL(serverUrl + "/sync_api.php?action=upload").openConnection();
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
        String lowerName = file.getName().toLowerCase(Locale.US);
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

    /**
     * Streams an HTTP download directly to a file without buffering in memory.
     * Returns the number of bytes written, or -1 on error.
     */
    private long streamDownloadToFile(String url, File destination) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(true);

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream errStream = connection.getErrorStream();
            if (errStream != null) errStream.close();
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code);
        }

        long totalWritten = 0;
        try (InputStream in = connection.getInputStream();
             FileOutputStream fos = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                totalWritten += read;
            }
            fos.flush();
        } finally {
            connection.disconnect();
        }
        return totalWritten;
    }

    private JSONObject requestJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
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
        if (inputStream == null) return new byte[0];
        try (InputStream stream = inputStream; ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toByteArray();
        }
    }

    private String normalizeServerBaseUrl(String value) {
        if (value == null) return "";
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
}
