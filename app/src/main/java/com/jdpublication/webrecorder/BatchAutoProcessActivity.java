package com.jdpublication.webrecorder;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BatchAutoProcessActivity extends AppCompatActivity {

    private static final String TAG = "BatchAutoProcess";
    private static final long PAGE_TIMEOUT_MS = 30000L;
    /** Disk work (metadata updates) off the UI thread. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private TextView textHardware;
    private TextView textBenchmark;
    private TextView textSelectedSource;
    private EditText editBatchSize;
    private EditText editThreadCount;
    private EditText editCoolOff;
    private CheckBox checkFilter;
    private CheckBox checkSkipExisting;
    private RadioGroup radioGroupEngine;
    private RadioButton radioEngineWebview;
    private RadioButton radioEngineInliner;
    private FrameLayout hiddenWebviewContainer;

    private LinearProgressIndicator progressBar;
    private TextView textStatProgress;
    private TextView textStatThreads;
    private TextView textStatSaved;
    private TextView textStatSkipped;
    private TextView textStatSpeed;
    private TextView textLiveLog;
    private Button btnBenchmark;
    private Button btnPickFolder;
    private Button btnPickExcel;
    private Button btnQuickLoadDevice;
    private Button btnStart;
    private Button btnPause;
    private Button btnOpenViewer;

    private String activeSourceSummary = "No Excel folder or sheet selected";
    private final List<BatchItem> pendingBatchItems = new ArrayList<>();
    private final List<BatchItem> currentBatchItems = new ArrayList<>();
    private final Deque<BatchItem> webViewQueue = new ArrayDeque<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService batchExecutor;
    private PowerManager.WakeLock wakeLock;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicInteger activeThreads = new AtomicInteger(0);
    private final AtomicInteger savedCounter = new AtomicInteger(0);
    private final AtomicInteger skippedCounter = new AtomicInteger(0);
    private final AtomicInteger failedCounter = new AtomicInteger(0);
    private final AtomicInteger completedCounter = new AtomicInteger(0);

    private long startTimeMs = 0;
    private static final int MAX_LOG_LINES = 60;
    private final Deque<String> logLines = new ArrayDeque<>(MAX_LOG_LINES);
    private final AtomicBoolean logUpdateScheduled = new AtomicBoolean(false);
    private final AtomicBoolean statsUpdateScheduled = new AtomicBoolean(false);
    private final List<WebViewWorker> webViewWorkers = new ArrayList<>();

    // Folder Picker Launcher (ACTION_OPEN_DOCUMENT_TREE)
    private final ActivityResultLauncher<Intent> folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    handleFolderTreeUri(result.getData().getData());
                }
            }
    );

    // Single Excel Picker Launcher (ACTION_OPEN_DOCUMENT)
    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    handleSingleExcelUri(result.getData().getData());
                }
            }
    );

    public static class BatchItem {
        final int rowIndex;
        final String title;
        final String webUrl;
        final String targetFolderName;
        final String sourceSheetName;

        public BatchItem(int rowIndex, String title, String webUrl, String targetFolderName, String sourceSheetName) {
            this.rowIndex = rowIndex;
            this.title = title;
            this.webUrl = webUrl;
            this.targetFolderName = targetFolderName;
            this.sourceSheetName = sourceSheetName;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_auto);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Batch Auto-Downloader");
            getSupportActionBar().setSubtitle("Folder Scanner & Native MHT Engine");
        }

        initializeViews();
        displayDeviceHardware();
        setupListeners();
        checkQuickLoadAvailability();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkQuickLoadAvailability();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        stopProcessing();
        cleanupWebViews();
        releaseWakeLock();
        io.shutdown();
        super.onDestroy();
    }

    private void initializeViews() {
        textHardware = findViewById(R.id.text_device_hardware);
        textBenchmark = findViewById(R.id.text_benchmark_result);
        textSelectedSource = findViewById(R.id.text_selected_source);
        editBatchSize = findViewById(R.id.edit_batch_size);
        editThreadCount = findViewById(R.id.edit_thread_count);
        editCoolOff = findViewById(R.id.edit_cool_off);
        checkFilter = findViewById(R.id.check_filter_unwanted);
        checkSkipExisting = findViewById(R.id.check_skip_existing);
        radioGroupEngine = findViewById(R.id.radio_group_engine);
        radioEngineWebview = findViewById(R.id.radio_engine_webview);
        radioEngineInliner = findViewById(R.id.radio_engine_inliner);
        hiddenWebviewContainer = findViewById(R.id.hidden_webview_container);

        progressBar = findViewById(R.id.progress_bar);
        textStatProgress = findViewById(R.id.text_stat_progress);
        textStatThreads = findViewById(R.id.text_stat_threads);
        textStatSaved = findViewById(R.id.text_stat_saved);
        textStatSkipped = findViewById(R.id.text_stat_skipped);
        textStatSpeed = findViewById(R.id.text_stat_speed);
        textLiveLog = findViewById(R.id.text_live_log);

        btnBenchmark = findViewById(R.id.btn_run_benchmark);
        btnPickFolder = findViewById(R.id.btn_pick_folder);
        btnPickExcel = findViewById(R.id.btn_pick_excel);
        btnQuickLoadDevice = findViewById(R.id.btn_quick_load_device);
        btnStart = findViewById(R.id.btn_start);
        btnPause = findViewById(R.id.btn_pause);
        btnOpenViewer = findViewById(R.id.btn_open_viewer);
    }

    private void setupListeners() {
        btnBenchmark.setOnClickListener(v -> runBenchmark());
        btnPickFolder.setOnClickListener(v -> openFolderPicker());
        btnPickExcel.setOnClickListener(v -> openSingleExcelPicker());
        btnQuickLoadDevice.setOnClickListener(v -> {
            if (needsAllFilesAccess()) {
                requestAllFilesAccess();
            } else {
                scanDeviceExcelFolder();
            }
        });

        btnStart.setOnClickListener(v -> {
            if (isRunning.get()) {
                stopProcessing();
            } else {
                startBatchProcessing();
            }
        });
        btnPause.setOnClickListener(v -> togglePause());
        btnOpenViewer.setOnClickListener(v -> {
            Intent intent = new Intent(this, OfflineManagerActivity.class);
            startActivity(intent);
        });

        radioGroupEngine.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.radio_engine_webview) {
                editThreadCount.setText("3");
                editThreadCount.setHint("Concurrent WebViews (1-5)");
                appendLog("[Config] Native Recorder Engine (WebView saveWebArchive with all images). Recommended concurrency: 2-4 WebViews.");
            } else {
                editThreadCount.setText("20");
                editThreadCount.setHint("Concurrent Threads (1-100)");
                appendLog("[Config] Advanced Turbo Image Inliner (Multi-Threaded). Recommended concurrency: 10-30 threads.");
            }
        });
    }

    /** Android 11+ needs "All files access" to read .xlsx files from /sdcard/Movies/Excel directly. */
    private boolean needsAllFilesAccess() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !Environment.isExternalStorageManager();
    }

    private void requestAllFilesAccess() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Allow file access")
                .setMessage("To auto-load the Excel sheets in /Movies/Excel, allow \"All files access\" for Web Recorder on the next screen, then come back.\n\nYou can also use \"Pick Folder\" or \"Pick Excel\" instead.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkQuickLoadAvailability() {
        if (needsAllFilesAccess()) {
            btnQuickLoadDevice.setVisibility(View.VISIBLE);
            btnQuickLoadDevice.setText("⚡ Allow access to auto-load /Movies/Excel/");
            return;
        }
        File defaultExcelDir = new File("/sdcard/Movies/Excel/");
        if (!defaultExcelDir.exists()) {
            defaultExcelDir = new File(Environment.getExternalStorageDirectory(), "Movies/Excel");
        }

        if (defaultExcelDir.exists() && defaultExcelDir.isDirectory()) {
            File[] files = defaultExcelDir.listFiles((dir, name) -> {
                String l = name.toLowerCase(Locale.US);
                return l.endsWith(".xlsx") || l.endsWith(".xls");
            });

            if (files != null && files.length > 0) {
                btnQuickLoadDevice.setVisibility(View.VISIBLE);
                btnQuickLoadDevice.setText("⚡ Auto-Load /Movies/Excel/ (" + files.length + " Sheets Found)");
                return;
            }
        }
        btnQuickLoadDevice.setVisibility(View.GONE);
    }

    private void displayDeviceHardware() {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        long maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        String info = String.format(Locale.US,
                "Device: %s %s | CPU Cores: %d | App Heap Max: %d MB",
                android.os.Build.MANUFACTURER, android.os.Build.MODEL, cpuCores, maxMemoryMb);
        textHardware.setText(info);
    }

    private void runBenchmark() {
        btnBenchmark.setEnabled(false);
        textBenchmark.setText("Benchmarking device concurrency (testing 4, 8, 16, 24, 32 threads)...");
        appendLog("[Benchmark] Starting mobile multi-threading concurrency test...");

        new Thread(() -> {
            int[] testTiers = {4, 8, 16, 24, 32};
            int bestThreadCount = 8;
            long fastestDuration = Long.MAX_VALUE;

            for (int threads : testTiers) {
                long duration = runSimulatedBatch(threads, 20);
                long dur = Math.max(1, duration);
                appendLog(String.format(Locale.US, "  Tested %d threads: 20 requests in %d ms (%.1f req/s)",
                        threads, dur, (20.0 / (dur / 1000.0))));

                if (duration < fastestDuration) {
                    fastestDuration = duration;
                    bestThreadCount = threads;
                }
            }

            int finalBest = bestThreadCount;
            mainHandler.post(() -> {
                btnBenchmark.setEnabled(true);
                String result = String.format(Locale.US,
                        "Benchmark Complete! Optimal concurrency: %d concurrent threads.\nRecommended: %d threads for Turbo Inliner, or 2-4 WebViews for Native MHT.",
                        finalBest, finalBest);
                textBenchmark.setText(result);
                if (radioEngineInliner.isChecked()) {
                    editThreadCount.setText(String.valueOf(finalBest));
                } else {
                    editThreadCount.setText("3");
                }
                appendLog("[Benchmark] " + result);
            });
        }).start();
    }

    private long runSimulatedBatch(int poolSize, int totalTasks) {
        ExecutorService exec = Executors.newFixedThreadPool(poolSize);
        long start = System.currentTimeMillis();
        for (int i = 0; i < totalTasks; i++) {
            exec.submit(() -> {
                try {
                    URL url = new URL("https://en.wikipedia.org/wiki/Special:Random");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    conn.setRequestMethod("HEAD");
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            });
        }
        exec.shutdown();
        try {
            exec.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return System.currentTimeMillis() - start;
    }

    /**
     * Folder Picker: SAF Intent.ACTION_OPEN_DOCUMENT_TREE
     */
    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        folderPickerLauncher.launch(intent);
    }

    private void handleFolderTreeUri(Uri treeUri) {
        try {
            getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }

        appendLog("[Folder Scan] Scanning selected folder for Excel files...");
        textSelectedSource.setText("Scanning folder...");

        new Thread(() -> {
            List<BatchItem> loadedItems = new ArrayList<>();
            List<String> sheetsProcessed = new ArrayList<>();

            try {
                String treeDocId = DocumentsContract.getTreeDocumentId(treeUri);
                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId);
                Cursor cursor = getContentResolver().query(childrenUri, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                }, null, null, null);

                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String docId = cursor.getString(0);
                        String name = cursor.getString(1);
                        if (name == null) continue;
                        String lower = name.toLowerCase(Locale.US);

                        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                            String folderName = OfflineStore.sanitizeFolderName(stripExtension(name));

                            try (InputStream is = getContentResolver().openInputStream(docUri)) {
                                List<BatchItem> sheetItems = parseExcelRows(is, name, folderName);
                                loadedItems.addAll(sheetItems);
                                sheetsProcessed.add(name + " (" + sheetItems.size() + " items)");
                                mainHandler.post(() -> appendLog("  • " + name + ": " + sheetItems.size() + " movies -> Folder: " + folderName));
                            } catch (Exception e) {
                                Log.e(TAG, "Failed reading " + name, e);
                                mainHandler.post(() -> appendLog("  ⚠️ Error reading " + name + ": " + e.getMessage()));
                            }
                        }
                    }
                    cursor.close();
                }

                mainHandler.post(() -> applyLoadedBatchItems("Folder (SAF)", sheetsProcessed.size(), loadedItems));
            } catch (Exception e) {
                Log.e(TAG, "handleFolderTreeUri", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Failed to scan folder: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    appendLog("[Error] Failed to scan folder: " + e.getMessage());
                });
            }
        }).start();
    }

    /**
     * Direct Path Scanner: /sdcard/Movies/Excel/
     */
    private void scanDeviceExcelFolder() {
        File defaultExcelDir = new File("/sdcard/Movies/Excel/");
        if (!defaultExcelDir.exists()) {
            defaultExcelDir = new File(Environment.getExternalStorageDirectory(), "Movies/Excel");
        }

        if (!defaultExcelDir.exists() || !defaultExcelDir.isDirectory()) {
            Toast.makeText(this, "Folder /sdcard/Movies/Excel/ not found on device", Toast.LENGTH_SHORT).show();
            return;
        }

        appendLog("[Folder Scan] Scanning /sdcard/Movies/Excel/ directly...");
        textSelectedSource.setText("Scanning /sdcard/Movies/Excel/...");
        File finalExcelDir = defaultExcelDir;

        new Thread(() -> {
            File[] files = finalExcelDir.listFiles((dir, name) -> {
                String l = name.toLowerCase(Locale.US);
                return l.endsWith(".xlsx") || l.endsWith(".xls");
            });

            if (files == null || files.length == 0) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "No Excel files found in " + finalExcelDir.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                    textSelectedSource.setText("No Excel files found in /Movies/Excel/");
                });
                return;
            }

            List<BatchItem> loadedItems = new ArrayList<>();
            List<String> sheetsProcessed = new ArrayList<>();

            for (File f : files) {
                String name = f.getName();
                String folderName = OfflineStore.sanitizeFolderName(stripExtension(name));
                try (InputStream is = new FileInputStream(f)) {
                    List<BatchItem> sheetItems = parseExcelRows(is, name, folderName);
                    loadedItems.addAll(sheetItems);
                    sheetsProcessed.add(name + " (" + sheetItems.size() + " items)");
                    mainHandler.post(() -> appendLog("  • " + name + ": " + sheetItems.size() + " movies -> Folder: " + folderName));
                } catch (Exception e) {
                    Log.e(TAG, "Failed reading " + f.getName(), e);
                    mainHandler.post(() -> appendLog("  ⚠️ Error reading " + f.getName() + ": " + e.getMessage()));
                }
            }

            System.gc();
            mainHandler.post(() -> applyLoadedBatchItems("/Movies/Excel/", sheetsProcessed.size(), loadedItems));
        }).start();
    }

    /**
     * Single Excel File Picker
     */
    private void openSingleExcelPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
        });
        filePickerLauncher.launch(intent);
    }

    private void handleSingleExcelUri(Uri uri) {
        String displayName = OfflineStore.resolveSheetDisplayName(this, uri);
        String folderName = OfflineStore.resolveSheetFolderName(this, uri);
        textSelectedSource.setText("Loading: " + displayName);
        appendLog("[Excel] Selected single sheet: " + displayName);

        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                List<BatchItem> loaded = parseExcelRows(is, displayName, folderName);
                mainHandler.post(() -> applyLoadedBatchItems(displayName, 1, loaded));
            } catch (Exception e) {
                Log.e(TAG, "handleSingleExcelUri", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "Failed to read Excel: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    appendLog("[Error] Failed to read Excel: " + e.getMessage());
                });
            }
        }).start();
    }

    private List<BatchItem> parseExcelRows(InputStream is, String sheetName, String folderName) throws Exception {
        List<BatchItem> list = new ArrayList<>();
        Workbook wb = WorkbookFactory.create(is);
        Sheet sheet = wb.getSheetAt(0);
        DataFormatter formatter = new DataFormatter();
        Iterator<Row> rowIt = sheet.iterator();

        if (rowIt.hasNext()) rowIt.next(); // Skip header
        while (rowIt.hasNext()) {
            Row row = rowIt.next();
            Cell nameCell = row.getCell(0);
            Cell urlCell = row.getCell(1);
            if (nameCell == null || urlCell == null) continue;
            String title = formatter.formatCellValue(nameCell).trim();
            String url = formatter.formatCellValue(urlCell).trim();
            if (title.isEmpty() || url.isEmpty()) continue;
            list.add(new BatchItem(row.getRowNum(), title, url, folderName, sheetName));
        }
        wb.close();
        return list;
    }

    private void applyLoadedBatchItems(String sourceLabel, int sheetCount, List<BatchItem> items) {
        pendingBatchItems.clear();
        pendingBatchItems.addAll(items);

        activeSourceSummary = String.format(Locale.US, "%s (%d sheets, %,d total movies)",
                sourceLabel, sheetCount, pendingBatchItems.size());
        textSelectedSource.setText(activeSourceSummary);
        progressBar.setMax(pendingBatchItems.size());
        progressBar.setProgress(0);

        appendLog(String.format(Locale.US,
                "[Batch Source Loaded] %d sheets loaded. Total %,d movies ready for batching.",
                sheetCount, pendingBatchItems.size()));
        Toast.makeText(this, String.format(Locale.US, "Loaded %,d movies from %d sheets!", pendingBatchItems.size(), sheetCount), Toast.LENGTH_SHORT).show();
    }

    private void startBatchProcessing() {
        if (pendingBatchItems.isEmpty()) {
            Toast.makeText(this, "Please choose an Excel folder or sheet first.", Toast.LENGTH_SHORT).show();
            return;
        }

        int batchLimit = 200;
        try {
            batchLimit = Integer.parseInt(editBatchSize.getText().toString().trim());
        } catch (Exception ignored) {
        }

        int threads = 20;
        try {
            threads = Math.max(1, Integer.parseInt(editThreadCount.getText().toString().trim()));
        } catch (Exception ignored) {
        }
        int safeThreads = Math.min(threads, 32);

        float coolOffSec = 1.5f;
        try {
            coolOffSec = Math.max(0.5f, Float.parseFloat(editCoolOff.getText().toString().trim()));
        } catch (Exception ignored) {
        }
        long coolOffMs = (long) (coolOffSec * 1000L);

        boolean filterUnwanted = checkFilter.isChecked();
        boolean skipExisting = checkSkipExisting.isChecked();
        boolean useNativeWebView = radioEngineWebview.isChecked();

        // Populate current batch slice
        currentBatchItems.clear();
        if (batchLimit > 0 && batchLimit < pendingBatchItems.size()) {
            currentBatchItems.addAll(pendingBatchItems.subList(0, batchLimit));
        } else {
            currentBatchItems.addAll(pendingBatchItems);
        }

        isRunning.set(true);
        isPaused.set(false);
        savedCounter.set(0);
        skippedCounter.set(0);
        failedCounter.set(0);
        completedCounter.set(0);
        activeThreads.set(0);
        startTimeMs = System.currentTimeMillis();

        progressBar.setMax(currentBatchItems.size());
        progressBar.setProgress(0);
        textStatProgress.setText(String.format(Locale.US, "0 / %d", currentBatchItems.size()));
        textStatThreads.setText("0");
        textStatSaved.setText("0");
        textStatSkipped.setText("0");
        textStatSpeed.setText("0.0/sec");

        btnStart.setText("Stop Batch");
        btnStart.setBackgroundTintList(getColorStateList(R.color.color_record));
        btnPause.setEnabled(true);
        btnPause.setText("Pause");

        acquireWakeLock();

        if (useNativeWebView) {
            // ENGINE 1: Native Chromium WebView saveWebArchive (Exact Same as Main Recorder Screen)
            int webViewWorkersCount = Math.min(threads, 4);
            appendLog(String.format(Locale.US,
                    "[Start - Native MHT Engine] Processing batch of %,d items across %d WebViews, %.1fs cool-off.",
                    currentBatchItems.size(), webViewWorkersCount, coolOffSec));
            startNativeWebViewPipeline(webViewWorkersCount, coolOffMs, filterUnwanted, skipExisting);
        } else {
            // ENGINE 2: Advanced Turbo Multi-Threaded Inliner
            appendLog(String.format(Locale.US,
                    "[Start - Turbo Inliner] Processing batch of %,d items with %d threads (queue bound: %d), %.1fs cool-off.",
                    currentBatchItems.size(), safeThreads, currentBatchItems.size(), coolOffSec));
            startTurboInlinerPipeline(safeThreads, coolOffMs, filterUnwanted, skipExisting);
        }
    }

    /**
     * ENGINE 1: Native Chromium saveWebArchive Pipeline (Same engine as MainActivity)
     */
    private void startNativeWebViewPipeline(int workerCount, long coolOffMs, boolean filterUnwanted, boolean skipExisting) {
        cleanupWebViews();
        webViewQueue.clear();
        webViewQueue.addAll(currentBatchItems);

        for (int i = 0; i < workerCount; i++) {
            WebViewWorker worker = new WebViewWorker(i + 1, coolOffMs, filterUnwanted, skipExisting);
            webViewWorkers.add(worker);
            hiddenWebviewContainer.addView(worker.webView);
            worker.startNextTask();
        }
    }

    private class WebViewWorker {
        final int workerId;
        final WebView webView;
        final long coolOffMs;
        final boolean filterUnwanted;
        final boolean skipExisting;
        BatchItem currentItem;
        Runnable timeoutRunnable;
        Runnable saveRunnable;
        int pagesProcessed = 0;
        /** Changes for every task; callbacks carrying an old id are ignored. */
        int taskId = 0;
        boolean saving = false;
        boolean mainFrameFailed = false;

        WebViewWorker(int workerId, long coolOffMs, boolean filterUnwanted, boolean skipExisting) {
            this.workerId = workerId;
            this.coolOffMs = coolOffMs;
            this.filterUnwanted = filterUnwanted;
            this.skipExisting = skipExisting;

            webView = new WebView(BatchAutoProcessActivity.this);
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(1080, 1920);
            webView.setLayoutParams(lp);

            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setLoadsImagesAutomatically(true);
            settings.setBlockNetworkImage(false);
            settings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (!isRunning.get() || currentItem == null || saving || mainFrameFailed) return;
                    if (url == null || url.startsWith("about:")) return;
                    final int id = taskId;

                    String prepJs = "(function() {" +
                            "  try {" +
                            "    var imgs = document.querySelectorAll('img');" +
                            "    for (var i = 0; i < imgs.length; i++) {" +
                            "      var img = imgs[i];" +
                            "      var lazyUrl = img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy-src') || img.getAttribute('data-url');" +
                            "      if (lazyUrl && !img.src) { img.src = lazyUrl; }" +
                            "      img.loading = 'eager';" +
                            "    }" +
                            "  } catch(e){}" +
                            "})();";
                    view.evaluateJavascript(prepJs, null);

                    // Wait for the cool-off so posters & images finish rendering. A second
                    // onPageFinished (redirect) simply restarts the wait instead of saving twice.
                    if (saveRunnable != null) mainHandler.removeCallbacks(saveRunnable);
                    saveRunnable = () -> {
                        saveRunnable = null;
                        if (!isRunning.get() || id != taskId || saving || currentItem == null) return;
                        savePageArchive(view, id);
                    };
                    mainHandler.postDelayed(saveRunnable, coolOffMs);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request.isForMainFrame()) {
                        mainFrameFailed = true;
                        Log.w(TAG, "WebView worker #" + workerId + " error: " + error.getDescription());
                        finishTask(taskId, false, "network error: " + error.getDescription());
                    }
                }

                @Override
                public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                    if (request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 400) {
                        mainFrameFailed = true;
                        finishTask(taskId, false, "HTTP " + errorResponse.getStatusCode());
                    }
                }
            });
        }

        void startNextTask() {
            if (!isRunning.get()) return;

            if (isPaused.get()) {
                mainHandler.postDelayed(this::startNextTask, 1000);
                return;
            }

            // Skips are handled in a loop (not recursion) so thousands of existing pages are fine.
            while (true) {
                BatchItem item;
                synchronized (webViewQueue) {
                    item = webViewQueue.poll();
                }
                if (item == null) {
                    currentItem = null;
                    checkAllWorkersFinished();
                    return;
                }
                String title = item.title;
                String url = item.webUrl;
                String targetFolder = item.targetFolderName;

                if (filterUnwanted && isUnwanted(title)) {
                    skippedCounter.incrementAndGet();
                    completedCounter.incrementAndGet();
                    updateStatsUi();
                    appendLog("  [Filter] Skipped: \"" + title + "\"");
                    continue;
                }

                File targetDir = OfflineStore.getFolderDirectory(BatchAutoProcessActivity.this, targetFolder);
                String mhtName = OfflineStore.buildOfflineFileName(url);
                File destinationMht = new File(targetDir, mhtName);
                if (skipExisting && destinationMht.exists() && destinationMht.length() > 1024) {
                    skippedCounter.incrementAndGet();
                    completedCounter.incrementAndGet();
                    io.execute(() -> OfflineStore.upsertMetadataEntry(BatchAutoProcessActivity.this, targetFolder, mhtName, url, title, item.rowIndex));
                    updateStatsUi();
                    appendLog("  [Skip] Already offline: \"" + title + "\" (" + targetFolder + ")");
                    continue;
                }

                currentItem = item;
                taskId++;
                saving = false;
                mainFrameFailed = false;
                final int id = taskId;
                activeThreads.incrementAndGet();
                updateStatsUi();
                appendLog(String.format(Locale.US, "  🌐 [Worker #%d] Loading: \"%s\" [%s]", workerId, title, targetFolder));

                if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
                timeoutRunnable = () -> {
                    if (id == taskId && !saving) {
                        finishTask(id, false, "timed out");
                    }
                };
                mainHandler.postDelayed(timeoutRunnable, PAGE_TIMEOUT_MS + coolOffMs);
                webView.loadUrl(url);
                return;
            }
        }

        /** Completes the current task exactly once and moves on. */
        void finishTask(int id, boolean saved, String reason) {
            if (id != taskId || currentItem == null) return;
            BatchItem item = currentItem;
            currentItem = null;
            taskId++; // invalidate any late callbacks for this page
            if (timeoutRunnable != null) mainHandler.removeCallbacks(timeoutRunnable);
            if (saveRunnable != null) mainHandler.removeCallbacks(saveRunnable);
            activeThreads.decrementAndGet();
            completedCounter.incrementAndGet();
            if (saved) {
                savedCounter.incrementAndGet();
            } else {
                failedCounter.incrementAndGet();
                appendLog("  ❌ [Failed] Worker #" + workerId + ": \"" + item.title + "\" (" + reason + ")");
            }
            updateStatsUi();
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
            } catch (Exception ignored) {
            }
            pagesProcessed++;
            if (pagesProcessed % 20 == 0) {
                try {
                    webView.clearCache(true);
                    webView.clearHistory();
                } catch (Exception ignored) {
                }
            }
            mainHandler.post(this::startNextTask);
        }

        private void savePageArchive(WebView view, int id) {
            if (currentItem == null || id != taskId) return;
            saving = true;
            BatchItem item = currentItem;
            String title = item.title;
            String url = item.webUrl;
            String targetFolder = item.targetFolderName;

            File targetDir = OfflineStore.getFolderDirectory(BatchAutoProcessActivity.this, targetFolder);
            if (!targetDir.exists()) targetDir.mkdirs();
            String mhtName = OfflineStore.buildOfflineFileName(url);
            File destinationMht = new File(targetDir, mhtName);
            File tempMht = new File(targetDir, mhtName + ".saving" + workerId);

            view.saveWebArchive(tempMht.getAbsolutePath(), false, savedPath -> {
                if (id != taskId) {
                    //noinspection ResultOfMethodCallIgnored
                    tempMht.delete();
                    return;
                }
                boolean ok = savedPath != null && !savedPath.trim().isEmpty() && tempMht.length() > 1024;
                if (ok) {
                    if (destinationMht.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        destinationMht.delete();
                    }
                    ok = tempMht.renameTo(destinationMht);
                }
                if (ok) {
                    long size = destinationMht.length();
                    io.execute(() -> OfflineStore.upsertMetadataEntry(BatchAutoProcessActivity.this, targetFolder, mhtName, url, title, item.rowIndex));
                    appendLog(String.format(Locale.US, "  ✅ [Saved] Worker #%d: \"%s\" [%s] → %s",
                            workerId, title, targetFolder, OfflineStore.formatFileSize(size)));
                    finishTask(id, true, null);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    tempMht.delete();
                    finishTask(id, false, "could not save archive");
                }
            });
        }
    }

    private void checkAllWorkersFinished() {
        if (!isRunning.get()) return;
        for (WebViewWorker w : webViewWorkers) {
            if (w.currentItem != null) return; // someone is still working
        }
        boolean queueEmpty;
        synchronized (webViewQueue) {
            queueEmpty = webViewQueue.isEmpty();
        }
        if (queueEmpty) {
            onBatchExecutionFinished("Native MHT Engine");
        }
    }

    private void onBatchExecutionFinished(String engineName) {
        stopProcessing(false);
        int batchCount = currentBatchItems.size();
        if (batchCount > 0 && batchCount <= pendingBatchItems.size()) {
            pendingBatchItems.subList(0, batchCount).clear();
        }
        int remaining = pendingBatchItems.size();
        activeSourceSummary = String.format(Locale.US, "Remaining in queue: %,d movies", remaining);
        textSelectedSource.setText(activeSourceSummary);

        appendLog(String.format(Locale.US,
                "[Complete - %s] Batch of %,d items finished! Saved: %d, Skipped/Filtered: %d, Failed: %d. (%,d remaining in queue)",
                engineName, batchCount, savedCounter.get(), skippedCounter.get(), failedCounter.get(), remaining));

        String toastMsg = remaining > 0 ?
                String.format(Locale.US, "Batch of %,d done! %,d remaining in queue.", batchCount, remaining) :
                "All items in queue successfully completed!";
        Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show();
    }

    private void cleanupWebViews() {
        for (WebViewWorker worker : webViewWorkers) {
            try {
                if (worker.timeoutRunnable != null) mainHandler.removeCallbacks(worker.timeoutRunnable);
                worker.webView.stopLoading();
                worker.webView.destroy();
            } catch (Exception ignored) {
            }
        }
        webViewWorkers.clear();
        hiddenWebviewContainer.removeAllViews();
    }

    /**
     * ENGINE 2: Advanced Turbo Multi-Threaded Inliner (Downloads HTML + all images as Base64)
     */
    private void startTurboInlinerPipeline(int threads, long coolOffMs, boolean filterUnwanted, boolean skipExisting) {
        batchExecutor = Executors.newFixedThreadPool(threads);

        for (BatchItem item : currentBatchItems) {
            batchExecutor.submit(() -> {
                if (!isRunning.get()) return;

                while (isPaused.get() && isRunning.get()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }

                activeThreads.incrementAndGet();
                updateStatsUi();

                try {
                    processSingleMovieInliner(item, filterUnwanted, skipExisting, coolOffMs);
                } finally {
                    activeThreads.decrementAndGet();
                    completedCounter.incrementAndGet();
                    updateStatsUi();
                }
            });
        }

        new Thread(() -> {
            batchExecutor.shutdown();
            try {
                batchExecutor.awaitTermination(4, TimeUnit.HOURS);
            } catch (InterruptedException ignored) {
            }

            mainHandler.post(() -> {
                if (isRunning.get()) {
                    onBatchExecutionFinished("Turbo Inliner");
                }
            });
        }).start();
    }

    private void processSingleMovieInliner(BatchItem item, boolean filterUnwanted, boolean skipExisting, long coolOffMs) {
        String title = item.title;
        String url = item.webUrl;
        String targetFolder = item.targetFolderName;

        if (filterUnwanted && isUnwanted(title)) {
            skippedCounter.incrementAndGet();
            appendLog("  [Filter] Skipped sensitive title: \"" + title + "\"");
            return;
        }

        File targetDir = OfflineStore.getFolderDirectory(this, targetFolder);
        String offlineFileName = OfflineStore.buildOfflineFileName(url);
        File destinationFile = new File(targetDir, offlineFileName);

        if (destinationFile.exists() && destinationFile.length() > 500) {
            // Intelligent incremental check: Does the existing offline file need remaining parts?
            OfflinePageRepairer.PageAnalysis analysis = OfflinePageRepairer.analyzePage(destinationFile);
            if (analysis.isComplete()) {
                if (skipExisting) {
                    skippedCounter.incrementAndGet();
                    OfflineStore.upsertMetadataEntry(this, targetFolder, destinationFile.getName(), url, title, item.rowIndex);
                    appendLog("  ⏩ [Skip Complete] \"" + title + "\" already fully offline & complete");
                    return;
                }
            } else {
                // Incomplete offline file: Download ONLY missing parts!
                appendLog("  🔧 [Incremental Download] \"" + title + "\" missing " + analysis.totalMissing() + " parts -> fetching missing images/CSS only...");
                OfflinePageRepairer.RepairResult repairResult = OfflinePageRepairer.repairPage(destinationFile);
                if (repairResult.resourcesFixed > 0) {
                    savedCounter.incrementAndGet();
                    OfflineStore.upsertMetadataEntry(this, targetFolder, destinationFile.getName(), url, title, item.rowIndex);
                    appendLog("  ✅ [Completed Parts] \"" + title + "\" patched " + repairResult.resourcesFixed + " missing items (" + OfflineStore.formatFileSize(repairResult.bytesDownloaded) + ")");
                    return;
                } else if (skipExisting && repairResult.saved) {
                    skippedCounter.incrementAndGet();
                    return;
                }
            }
        }

        boolean success = downloadAndInlinePageWithImages(url, destinationFile, title, item.rowIndex, targetFolder);
        if (success) {
            savedCounter.incrementAndGet();
            appendLog("  [Saved Inlined MHT] \"" + title + "\" [" + targetFolder + "] -> " + OfflineStore.formatFileSize(destinationFile.length()));
        } else {
            failedCounter.incrementAndGet();
            appendLog("  [Failed] \"" + title + "\" (" + url + ")");
        }

        if (coolOffMs > 0 && isRunning.get()) {
            try {
                Thread.sleep(coolOffMs);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private boolean downloadAndInlinePageWithImages(String pageUrl, File destinationFile, String title, int rowIndex, String targetFolder) {
        HttpURLConnection conn = null;
        try {
            File parent = destinationFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            URL url = new URL(pageUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");

            if (conn.getResponseCode() != 200) return false;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
            }

            String html = new String(baos.toByteArray(), StandardCharsets.UTF_8);

            // Determine base URL for resolving relative paths
            String baseUrl = pageUrl;
            int lastSlash = baseUrl.lastIndexOf('/');
            String baseDir = (lastSlash > 8) ? baseUrl.substring(0, lastSlash + 1) : baseUrl;
            // Extract origin (e.g. https://en.wikipedia.org)
            String origin;
            try {
                URL parsed = new URL(pageUrl);
                origin = parsed.getProtocol() + "://" + parsed.getHost();
            } catch (Exception e) {
                origin = "https://en.wikipedia.org";
            }

            // ── PHASE 1: Inline CSS Stylesheets ──
            html = inlineCssStylesheets(html, origin);

            // ── PHASE 2: Inline images from <img> tags (src attribute) ──
            html = inlineImgSrcAttributes(html, origin, baseDir);

            // ── PHASE 3: Handle srcset attributes ──
            html = inlineSrcsetAttributes(html);

            // ── PHASE 4: Handle lazy-loaded images (data-src, data-lazy) ──
            html = promoteLazyLoadImages(html, origin, baseDir);

            // ── PHASE 5: Strip external JS scripts that won't work offline ──
            html = stripExternalScripts(html);

            // ── PHASE 6: Inject base tag ──
            String baseTag = "<base href=\"" + pageUrl + "\" />";
            int headIdx = html.toLowerCase(Locale.US).indexOf("<head>");
            if (headIdx >= 0) {
                // Also inject a meta viewport for mobile rendering
                String metaVp = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">";
                html = html.substring(0, headIdx + 6) + baseTag + metaVp + html.substring(headIdx + 6);
            }

            if (!OfflineStore.writeAtomically(destinationFile, html.getBytes(StandardCharsets.UTF_8))) {
                return false;
            }

            OfflineStore.upsertMetadataEntry(this, targetFolder, destinationFile.getName(), pageUrl, title, rowIndex);
            // Secondary completion pass to ensure poster/infobox images from JSON-LD/OpenGraph are inlined
            try {
                OfflinePageRepairer.repairPage(destinationFile);
            } catch (Exception ignored) {}
            return true;
        } catch (Exception e) {
            Log.e(TAG, "downloadAndInlinePageWithImages failed: " + pageUrl, e);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Downloads external CSS stylesheets and inlines them as &lt;style&gt; blocks.
     * Also recursively inlines any url() references within the CSS (images, fonts).
     */
    private String inlineCssStylesheets(String html, String origin) {
        Pattern linkPattern = Pattern.compile(
                "<link[^>]+rel=[\"']stylesheet[\"'][^>]+href=[\"']([^\"']+)[\"'][^>]*/?>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = linkPattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String href = matcher.group(1);
            String cssUrl = resolveUrl(href, origin);
            try {
                byte[] cssBytes = fetchBinary(cssUrl, 8000);
                if (cssBytes != null && cssBytes.length > 0) {
                    String css = new String(cssBytes, StandardCharsets.UTF_8);
                    // Inline url() references inside the CSS (background images, fonts)
                    css = inlineCssUrlReferences(css, cssUrl);
                    // Escape for regex replacement
                    String replacement = "<style>" + Matcher.quoteReplacement(css) + "</style>";
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    continue;
                }
            } catch (Exception ignored) {}
            // If download fails, remove the <link> tag (it'll fail offline anyway)
            matcher.appendReplacement(sb, "<!-- offline: stylesheet unavailable -->");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Inlines url(...) references inside CSS content (background images, etc).
     */
    private String inlineCssUrlReferences(String css, String cssUrl) {
        // Extract the base URL of the CSS file for relative resolution
        String cssBase;
        int lastSlash = cssUrl.lastIndexOf('/');
        cssBase = (lastSlash > 8) ? cssUrl.substring(0, lastSlash + 1) : cssUrl;

        Pattern urlPattern = Pattern.compile("url\\([\"']?([^\"')]+)[\"']?\\)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = urlPattern.matcher(css);
        StringBuffer sb = new StringBuffer();
        int inlinedCount = 0;
        while (matcher.find() && inlinedCount < 30) { // Limit to avoid huge CSS
            String ref = matcher.group(1).trim();
            if (ref.startsWith("data:") || ref.startsWith("#")) {
                continue; // Already inlined or a fragment
            }
            String fullUrl;
            if (ref.startsWith("//")) fullUrl = "https:" + ref;
            else if (ref.startsWith("/")) {
                try { fullUrl = new URL(new URL(cssBase), ref).toString(); } 
                catch (Exception e) { continue; }
            }
            else if (ref.startsWith("http")) fullUrl = ref;
            else {
                try { fullUrl = new URL(new URL(cssBase), ref).toString(); }
                catch (Exception e) { continue; }
            }
            try {
                byte[] imgBytes = fetchBinary(fullUrl, 5000);
                if (imgBytes != null && imgBytes.length > 0 && imgBytes.length < 500_000) {
                    String mime = guessMimeType(fullUrl);
                    String dataUri = "data:" + mime + ";base64," + Base64.encodeToString(imgBytes, Base64.NO_WRAP);
                    matcher.appendReplacement(sb, Matcher.quoteReplacement("url(\"" + dataUri + "\")"));
                    inlinedCount++;
                    continue;
                }
            } catch (Exception ignored) {}
            // Leave as-is if we can't inline
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Inlines all &lt;img src="..."&gt; attributes by downloading images and converting to base64.
     */
    private String inlineImgSrcAttributes(String html, String origin, String baseDir) {
        Pattern imgPattern = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher matcher = imgPattern.matcher(html);
        Set<String> imageUrls = new HashSet<>();
        while (matcher.find()) {
            String src = matcher.group(1);
            if (src != null && !src.startsWith("data:")) {
                imageUrls.add(src);
            }
        }

        for (String imgSrc : imageUrls) {
            String fullUrl = resolveUrl(imgSrc, origin);
            try {
                byte[] imgBytes = fetchBinary(fullUrl, 6000);
                if (imgBytes != null && imgBytes.length > 0) {
                    String mime = guessMimeType(fullUrl);
                    String base64Data = "data:" + mime + ";base64," + Base64.encodeToString(imgBytes, Base64.NO_WRAP);
                    // Replace all occurrences of this image URL
                    html = html.replace("\"" + imgSrc + "\"", "\"" + base64Data + "\"");
                    html = html.replace("'" + imgSrc + "'", "'" + base64Data + "'");
                }
            } catch (Exception ignored) {}
        }
        return html;
    }

    /**
     * Processes srcset attributes: keeps only the largest image and inlines it.
     * Removes the srcset attribute entirely (replaced by the inlined src).
     */
    private String inlineSrcsetAttributes(String html) {
        // Remove srcset attributes entirely — the src is already inlined above
        // This prevents the browser from trying to load srcset URLs that aren't available offline
        html = html.replaceAll("\\s+srcset=[\"'][^\"']*[\"']", "");
        return html;
    }

    /**
     * Promotes lazy-loaded images: copies data-src to src and inlines them.
     */
    private String promoteLazyLoadImages(String html, String origin, String baseDir) {
        // Find data-src attributes and promote them to src
        Pattern lazySrcPattern = Pattern.compile(
                "(<img[^>]*?)\\s+data-src=[\"']([^\"']+)[\"']([^>]*>)",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = lazySrcPattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String before = matcher.group(1);
            String dataSrc = matcher.group(2);
            String after = matcher.group(3);

            if (dataSrc.startsWith("data:")) {
                continue; // Already inlined
            }

            String fullUrl = resolveUrl(dataSrc, origin);
            try {
                byte[] imgBytes = fetchBinary(fullUrl, 6000);
                if (imgBytes != null && imgBytes.length > 0) {
                    String mime = guessMimeType(fullUrl);
                    String base64Data = "data:" + mime + ";base64," + Base64.encodeToString(imgBytes, Base64.NO_WRAP);
                    // Replace data-src with src pointing to inlined data
                    String replacement = before + " src=\"" + base64Data + "\"" + after;
                    // Remove any existing empty or placeholder src
                    replacement = replacement.replaceAll("src=[\"'][^\"']*?1x1[^\"']*?[\"']", "");
                    replacement = replacement.replaceAll("src=[\"']about:blank[\"']", "");
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    continue;
                }
            } catch (Exception ignored) {}
            // Leave as-is
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Strips external script tags that won't work offline (Wikipedia JS modules, etc.).
     */
    private String stripExternalScripts(String html) {
        // Remove external <script src="..."> tags
        html = html.replaceAll("<script[^>]+src=[\"'][^\"']*(?:/w/load\\.php|modules)[^\"']*[\"'][^>]*>[^<]*</script>", "");
        // Remove Wikipedia module loader scripts
        html = html.replaceAll("<script>\\(RLQ=window\\.RLQ[^<]*</script>", "");
        // Remove the startup script
        html = html.replaceAll("<script[^>]*async[^>]*src=[\"'][^\"']*startup[^\"']*[\"'][^>]*>[^<]*</script>", "");
        return html;
    }

    /**
     * Resolves a potentially relative URL against the origin.
     */
    private String resolveUrl(String href, String origin) {
        if (href == null) return "";
        href = href.trim();
        if (href.startsWith("data:") || href.startsWith("blob:")) return href;
        if (href.startsWith("//")) return "https:" + href;
        if (href.startsWith("/")) return origin + href;
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        return origin + "/" + href;
    }

    /**
     * Guesses MIME type from URL extension.
     */
    private String guessMimeType(String url) {
        String lower = url.toLowerCase(Locale.US);
        // Remove query params for extension check
        int qIdx = lower.indexOf('?');
        if (qIdx > 0) lower = lower.substring(0, qIdx);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".eot")) return "application/vnd.ms-fontobject";
        return "image/jpeg"; // Default for .jpg, .jpeg, and unknown
    }

    private byte[] fetchBinary(String urlStr, int timeoutMs) {
        try {
            URL u = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 WebRecorder-Inliner");
            if (c.getResponseCode() != 200) {
                c.disconnect();
                return null;
            }
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    b.write(buf, 0, n);
                }
                c.disconnect();
                return b.toByteArray();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void stopProcessing() {
        stopProcessing(true);
    }

    private void stopProcessing(boolean byUser) {
        if (!isRunning.get()) return;
        isRunning.set(false);
        isPaused.set(false);

        if (batchExecutor != null) {
            batchExecutor.shutdownNow();
        }
        cleanupWebViews();

        btnStart.setText("Start Batch");
        btnStart.setBackgroundTintList(getColorStateList(R.color.color_offline_badge));
        btnPause.setEnabled(false);
        btnPause.setText("Pause");
        releaseWakeLock();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (byUser) appendLog("[Control] Processing stopped by user.");
    }

    private void togglePause() {
        if (!isRunning.get()) return;
        boolean nowPaused = !isPaused.get();
        isPaused.set(nowPaused);
        if (nowPaused) {
            btnPause.setText("Resume");
            appendLog("[Control] Processing paused.");
        } else {
            btnPause.setText("Pause");
            appendLog("[Control] Processing resumed.");
            if (radioEngineWebview.isChecked()) {
                for (WebViewWorker worker : webViewWorkers) {
                    worker.startNextTask();
                }
            }
        }
    }

    private boolean isUnwanted(String title) {
        return ContentFilter.isUnwanted(title);
    }

    private void updateStatsUi() {
        if (statsUpdateScheduled.compareAndSet(false, true)) {
            mainHandler.postDelayed(this::flushStatsToUi, 250);
        }
    }

    private void flushStatsToUi() {
        statsUpdateScheduled.set(false);
        if (isFinishing() || isDestroyed()) return;
        int comp = completedCounter.get();
        int total = currentBatchItems.isEmpty() ? pendingBatchItems.size() : currentBatchItems.size();
        progressBar.setProgress(comp);
        textStatProgress.setText(String.format(Locale.US, "%d / %d", comp, total));
        textStatThreads.setText(String.valueOf(activeThreads.get()));
        textStatSaved.setText(String.valueOf(savedCounter.get()));
        textStatSkipped.setText(String.valueOf(skippedCounter.get()));

        long elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000;
        if (elapsedSec > 0 && comp > 0) {
            float speed = (float) comp / elapsedSec;
            textStatSpeed.setText(String.format(Locale.US, "%.1f/sec", speed));
        } else {
            textStatSpeed.setText("0.0/sec");
        }
    }

    private void appendLog(String line) {
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
            textLiveLog.setText(sb.toString());
        } catch (Throwable t) {
            Log.e(TAG, "Log render error: " + t.getMessage());
            synchronized (logLines) {
                logLines.clear();
                logLines.addLast("[Log buffer reset to preserve memory]");
            }
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebRecorder:BatchAutoDownloadLock");
                wakeLock.acquire(4 * 60 * 60 * 1000L); // Max 4 hours
            }
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }
    }

    private String stripExtension(String value) {
        if (value == null) return "";
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }
}
