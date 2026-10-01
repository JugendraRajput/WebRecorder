package com.jdpublication.webrecorder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cloud sync hub: configure the GitHub storage repo and run uploads / downloads / two-way syncs.
 * The actual work runs in {@link SyncService}, so it continues in the background.
 */
public class SyncActivity extends AppCompatActivity implements SyncService.Observer {

    private static final int[] THREAD_OPTIONS = {2, 4, 6, 8};

    private EditText editRepo;
    private EditText editBranch;
    private EditText editToken;
    private TextView textServerStatusBadge;
    private TextView textLastSync;
    private Spinner spinnerThreads;
    private Button buttonTestConnection;
    private Button buttonSave;

    private Spinner spinnerFolders;
    private TextView textSelectedFolderTitle;
    private TextView textSelectedFolderStats;
    private Button buttonUploadSelected;
    private Button buttonDownloadSelected;
    private Button buttonSyncSelected;
    private Button buttonSyncAll;
    private Button buttonDownloadAll;
    private Button buttonUploadAll;
    private Button buttonOpenOfflineManager;
    private Button buttonCancelSync;

    private LinearProgressIndicator syncProgressBar;
    private TextView textProgressPercentage;
    private TextView textSyncStatus;
    private TextView textSyncLog;
    private ScrollView scrollLog;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final AtomicBoolean logFlushScheduled = new AtomicBoolean(false);
    private final List<String> availableFolders = new ArrayList<>();
    private final Map<String, Integer> remoteFolderCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean remoteKnown = false;
    private String selectedFolder = "";
    private boolean suppressFolderEvent = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Cloud Sync");
        }

        initializeViews();
        setupThreadSpinner();
        setupListeners();

        editRepo.setText(OfflineStore.getCloudRepo(this));
        editBranch.setText(OfflineStore.getCloudBranch(this));
        editToken.setText(OfflineStore.getCloudToken(this));
        selectedFolder = OfflineStore.getCurrentFolderName(this);

        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        SyncService.addObserver(this);
        renderLog();
        applyRunningState(SyncService.isRunning());
        if (SyncService.isRunning()) {
            onSyncProgress(SyncService.getProgressDone(), SyncService.getProgressTotal(), SyncService.getStatus());
        }
        updateLastSyncText();
        refreshFolderList();
        if (OfflineStore.isCloudConfigured(this)) {
            testConnection(false);
        } else {
            setBadge("Not set up", R.color.badge_warning_text);
        }
    }

    @Override
    protected void onStop() {
        SyncService.removeObserver(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
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
        editRepo = findViewById(R.id.edit_repo);
        editBranch = findViewById(R.id.edit_branch);
        editToken = findViewById(R.id.edit_token);
        textServerStatusBadge = findViewById(R.id.text_server_status_badge);
        textLastSync = findViewById(R.id.text_last_sync);
        spinnerThreads = findViewById(R.id.spinner_sync_threads);
        buttonTestConnection = findViewById(R.id.button_test_connection);
        buttonSave = findViewById(R.id.button_save_server);

        spinnerFolders = findViewById(R.id.spinner_folders);
        textSelectedFolderTitle = findViewById(R.id.text_selected_folder_title);
        textSelectedFolderStats = findViewById(R.id.text_selected_folder_stats);
        buttonUploadSelected = findViewById(R.id.button_upload_selected);
        buttonDownloadSelected = findViewById(R.id.button_download_selected);
        buttonSyncSelected = findViewById(R.id.button_sync_selected);
        buttonSyncAll = findViewById(R.id.button_sync_all);
        buttonDownloadAll = findViewById(R.id.button_download_all);
        buttonUploadAll = findViewById(R.id.button_upload_all);
        buttonOpenOfflineManager = findViewById(R.id.button_open_offline_manager);
        buttonCancelSync = findViewById(R.id.button_cancel_sync);

        syncProgressBar = findViewById(R.id.sync_progress_bar);
        textProgressPercentage = findViewById(R.id.text_progress_percentage);
        textSyncStatus = findViewById(R.id.text_sync_status);
        textSyncLog = findViewById(R.id.text_sync_log);
        scrollLog = findViewById(R.id.scroll_log);
    }

    private void setupThreadSpinner() {
        String[] labels = new String[THREAD_OPTIONS.length];
        int selected = 1;
        int current = OfflineStore.getSyncThreads(this);
        for (int i = 0; i < THREAD_OPTIONS.length; i++) {
            labels[i] = THREAD_OPTIONS[i] + " at a time" + (THREAD_OPTIONS[i] == 4 ? " (recommended)" : "");
            if (THREAD_OPTIONS[i] == current) selected = i;
        }
        spinnerThreads.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        spinnerThreads.setSelection(selected);
    }

    private int selectedThreads() {
        int pos = spinnerThreads.getSelectedItemPosition();
        return pos >= 0 && pos < THREAD_OPTIONS.length ? THREAD_OPTIONS[pos] : 4;
    }

    private void setupListeners() {
        buttonSave.setOnClickListener(v -> {
            if (saveSettings(true)) testConnection(true);
        });
        buttonTestConnection.setOnClickListener(v -> {
            if (saveSettings(false)) testConnection(true);
        });

        spinnerFolders.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressFolderEvent) return;
                if (position >= 0 && position < availableFolders.size()) {
                    selectedFolder = availableFolders.get(position);
                    updateSelectedFolderUi();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        buttonUploadSelected.setOnClickListener(v -> startSync(SyncEngine.Mode.UPLOAD_ONLY, true));
        buttonDownloadSelected.setOnClickListener(v -> startSync(SyncEngine.Mode.DOWNLOAD_ONLY, true));
        buttonSyncSelected.setOnClickListener(v -> startSync(SyncEngine.Mode.TWO_WAY, true));
        buttonSyncAll.setOnClickListener(v -> startSync(SyncEngine.Mode.TWO_WAY, false));
        buttonDownloadAll.setOnClickListener(v -> startSync(SyncEngine.Mode.DOWNLOAD_ONLY, false));
        buttonUploadAll.setOnClickListener(v -> startSync(SyncEngine.Mode.UPLOAD_ONLY, false));
        buttonCancelSync.setOnClickListener(v -> SyncService.cancel(this));

        buttonOpenOfflineManager.setOnClickListener(v -> {
            Intent intent = new Intent(SyncActivity.this, OfflineManagerActivity.class);
            if (!selectedFolder.isEmpty()) intent.putExtra("folder_name", selectedFolder);
            startActivity(intent);
        });

        findViewById(R.id.button_clear_log).setOnClickListener(v -> {
            SyncService.clearLog();
            textSyncLog.setText("");
        });
    }

    private boolean saveSettings(boolean announce) {
        String repo = textOf(editRepo);
        String branch = textOf(editBranch);
        String token = textOf(editToken);
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            editRepo.setError("Use the form owner/repo");
            return false;
        }
        if (token.isEmpty()) {
            editToken.setError("Paste your GitHub access token");
            return false;
        }
        OfflineStore.saveCloudSettings(this, repo, branch.isEmpty() ? "main" : branch, token, selectedThreads());
        if (announce) Toast.makeText(this, "Cloud settings saved", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void startSync(SyncEngine.Mode mode, boolean selectedOnly) {
        if (!saveSettings(false)) {
            Toast.makeText(this, "Set up the GitHub repository and token first", Toast.LENGTH_LONG).show();
            return;
        }
        if (SyncService.isRunning()) {
            Toast.makeText(this, "A sync is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> folders = null;
        if (selectedOnly) {
            if (selectedFolder.isEmpty()) {
                Toast.makeText(this, "Select a folder first", Toast.LENGTH_SHORT).show();
                return;
            }
            folders = new ArrayList<>();
            folders.add(selectedFolder);
        }
        if (mode == SyncEngine.Mode.DOWNLOAD_ONLY && !selectedOnly && remoteFolderCounts.isEmpty() && remoteKnown) {
            Toast.makeText(this, "The cloud is empty – nothing to download", Toast.LENGTH_SHORT).show();
            return;
        }
        SyncService.start(this, mode, folders);
        applyRunningState(true);
    }

    private void testConnection(boolean notify) {
        if (!OfflineStore.isCloudConfigured(this)) return;
        setBadge("Connecting…", R.color.badge_warning_text);
        buttonTestConnection.setEnabled(false);
        background.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                GitHubStorage storage = OfflineStore.createCloudStorage(this);
                SyncEngine engine = new SyncEngine(this, storage, 1, new AtomicBoolean(false), null);
                Map<String, Integer> counts = engine.remoteFolderCounts();
                long ms = System.currentTimeMillis() - start;
                int total = 0;
                for (int c : counts.values()) total += c;
                int finalTotal = total;
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    buttonTestConnection.setEnabled(true);
                    remoteFolderCounts.clear();
                    remoteFolderCounts.putAll(counts);
                    remoteKnown = true;
                    setBadge("Connected", R.color.badge_valid_text);
                    if (notify) {
                        SyncService.log(String.format(Locale.US, "🔗 Connected in %d ms · %d cloud folders · %,d pages",
                                ms, counts.size(), finalTotal));
                        Toast.makeText(this, "Connected to GitHub", Toast.LENGTH_SHORT).show();
                    }
                    refreshFolderList();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (isFinishing()) return;
                    buttonTestConnection.setEnabled(true);
                    remoteKnown = false;
                    setBadge("Error", R.color.badge_corrupt_text);
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    SyncService.log("❌ Connection failed: " + msg);
                    if (notify) {
                        new AlertDialog.Builder(this)
                                .setTitle("Could not connect")
                                .setMessage(msg + "\n\nCheck that you are online, the repository name is correct and the token has \"Contents: Read and write\" access to it.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            }
        });
    }

    private void refreshFolderList() {
        background.execute(() -> {
            TreeSet<String> all = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            all.addAll(OfflineStore.listAllLocalFolders(this));
            all.addAll(remoteFolderCounts.keySet());
            String current = OfflineStore.getCurrentFolderName(this);
            if (!current.isEmpty()) all.add(current);
            List<String> folders = new ArrayList<>(all);
            List<String> labels = new ArrayList<>();
            for (String f : folders) {
                int local = OfflineStore.countHtmlFiles(this, f);
                Integer cloud = remoteFolderCounts.get(f);
                labels.add(OfflineStore.prettifyFolderName(f) + "   •  device " + local
                        + (remoteKnown ? "  •  cloud " + (cloud == null ? 0 : cloud) : ""));
            }
            mainHandler.post(() -> {
                if (isFinishing()) return;
                availableFolders.clear();
                availableFolders.addAll(folders);
                if (folders.isEmpty()) {
                    labels.add("No folders yet – save some pages first");
                }
                suppressFolderEvent = true;
                spinnerFolders.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
                int idx = folders.indexOf(selectedFolder);
                if (idx < 0 && !folders.isEmpty()) {
                    idx = 0;
                    selectedFolder = folders.get(0);
                }
                if (idx >= 0) spinnerFolders.setSelection(idx, false);
                spinnerFolders.post(() -> suppressFolderEvent = false);
                updateSelectedFolderUi();
            });
        });
    }

    private void updateSelectedFolderUi() {
        if (selectedFolder.isEmpty()) {
            textSelectedFolderTitle.setText("No folder selected");
            textSelectedFolderStats.setText("");
            return;
        }
        String folder = selectedFolder;
        textSelectedFolderTitle.setText(OfflineStore.prettifyFolderName(folder));
        textSelectedFolderStats.setText("Calculating…");
        background.execute(() -> {
            List<java.io.File> files = OfflineStore.listHtmlFiles(this, folder);
            long size = 0;
            for (java.io.File f : files) size += f.length();
            int pending = OfflineStore.getPendingDeletionNames(this, folder).size();
            Integer cloud = remoteFolderCounts.get(folder);
            String text = "On this device: " + files.size() + " pages (" + OfflineStore.formatFileSize(size) + ")"
                    + "\nIn the cloud: " + (remoteKnown ? (cloud == null ? 0 : cloud) + " pages" : "unknown (not connected)")
                    + (pending > 0 ? "\nWaiting to delete from cloud: " + pending : "");
            mainHandler.post(() -> {
                if (!isFinishing() && folder.equals(selectedFolder)) textSelectedFolderStats.setText(text);
            });
        });
    }

    private void applyRunningState(boolean running) {
        boolean enabled = !running;
        buttonUploadSelected.setEnabled(enabled);
        buttonDownloadSelected.setEnabled(enabled);
        buttonSyncSelected.setEnabled(enabled);
        buttonSyncAll.setEnabled(enabled);
        buttonDownloadAll.setEnabled(enabled);
        buttonUploadAll.setEnabled(enabled);
        buttonSave.setEnabled(enabled);
        buttonCancelSync.setEnabled(running);
        if (!running) {
            syncProgressBar.setIndeterminate(false);
        }
    }

    private void setBadge(String text, int colorRes) {
        textServerStatusBadge.setText(text);
        textServerStatusBadge.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    private void updateLastSyncText() {
        long at = OfflineStore.getLastSyncTime(this);
        if (at <= 0) {
            textLastSync.setText("Never synced");
            return;
        }
        textLastSync.setText("Last sync " + DateUtils.getRelativeTimeSpanString(at) + " · " + OfflineStore.getLastSyncSummary(this));
    }

    private void renderLog() {
        StringBuilder sb = new StringBuilder();
        for (String line : SyncService.recentLog()) sb.append(line).append('\n');
        textSyncLog.setText(sb.toString());
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private static String textOf(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }

    // ───────────── SyncService.Observer ─────────────

    @Override
    public void onSyncLog(String line) {
        if (logFlushScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(() -> {
                logFlushScheduled.set(false);
                if (!isFinishing()) renderLog();
            }, 300);
        }
    }

    @Override
    public void onSyncProgress(int done, int total, String status) {
        if (total > 0) {
            int percent = (int) (done * 100L / total);
            syncProgressBar.setIndeterminate(false);
            syncProgressBar.setProgressCompat(percent, true);
            textProgressPercentage.setText(String.format(Locale.US, "%d%% (%d/%d)", percent, done, total));
        } else {
            syncProgressBar.setIndeterminate(true);
            textProgressPercentage.setText("Working…");
        }
        textSyncStatus.setText(status);
    }

    @Override
    public void onSyncStateChanged(boolean running, String summary) {
        applyRunningState(running);
        if (running) {
            textSyncStatus.setText("Starting…");
            syncProgressBar.setIndeterminate(true);
            return;
        }
        syncProgressBar.setProgressCompat(100, true);
        textProgressPercentage.setText("Done");
        if (summary != null) {
            textSyncStatus.setText(summary);
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
        }
        updateLastSyncText();
        testConnection(false);
        refreshFolderList();
    }
}
