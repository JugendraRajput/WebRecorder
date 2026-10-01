package com.jdpublication.webrecorder;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class OfflineManagerActivity extends AppCompatActivity {

    private static final String ALL_FOLDERS = "[All Folders Combined]";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private TextView textStatTotalFiles;
    private TextView textStatStorageSize;
    private TextView textStatFolders;
    private TextView textHealthValid;
    private TextView textHealthWarning;
    private TextView textHealthCorrupt;
    private Button buttonValidateAll;
    private Button buttonCleanCorrupt;
    private Button buttonCloudSyncHub;
    private Button buttonRepairPages;
    private Button buttonAnalyzePages;
    private ProgressBar progressValidation;
    private ProgressBar progressRepair;
    private TextView textRepairStatus;

    private Spinner spinnerFolders;
    private EditText editSearch;
    private ChipGroup chipGroupFilter;
    private TextView textCountSummary;
    private RecyclerView recyclerFiles;
    private TextView textEmptyFiles;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<OfflineFileItem> allLoadedItems = new ArrayList<>();
    private final List<OfflineFileItem> displayedItems = new ArrayList<>();
    private final List<String> folderNamesList = new ArrayList<>();

    private FileAdapter fileAdapter;
    private String selectedFolder = ALL_FOLDERS;
    private String searchQuery = "";
    private int filterMode = 0; // 0 = All, 1 = Valid Only, 2 = Corrupt Only
    private boolean isValidating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_manager);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Offline Storage & Validator");
        }

        initializeViews();
        setupRecyclerView();
        setupListeners();

        // Check if an initial folder was passed via Intent
        String initialFolder = getIntent().getStringExtra("folder_name");
        if (initialFolder != null && !initialFolder.isEmpty()) {
            selectedFolder = initialFolder;
        }

        loadFoldersAndFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadFoldersAndFiles();
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
        textStatTotalFiles = findViewById(R.id.text_stat_total_files);
        textStatStorageSize = findViewById(R.id.text_stat_storage_size);
        textStatFolders = findViewById(R.id.text_stat_folders);
        textHealthValid = findViewById(R.id.text_health_valid);
        textHealthWarning = findViewById(R.id.text_health_warning);
        textHealthCorrupt = findViewById(R.id.text_health_corrupt);
        buttonValidateAll = findViewById(R.id.button_validate_all);
        buttonCleanCorrupt = findViewById(R.id.button_clean_corrupt);
        buttonCloudSyncHub = findViewById(R.id.button_cloud_sync_hub);
        buttonRepairPages = findViewById(R.id.button_repair_pages);
        buttonAnalyzePages = findViewById(R.id.button_analyze_pages);
        progressValidation = findViewById(R.id.progress_validation);
        progressRepair = findViewById(R.id.progress_repair);
        textRepairStatus = findViewById(R.id.text_repair_status);

        spinnerFolders = findViewById(R.id.spinner_manager_folders);
        editSearch = findViewById(R.id.edit_search_files);
        chipGroupFilter = findViewById(R.id.chip_group_filter);
        textCountSummary = findViewById(R.id.text_files_count_summary);
        recyclerFiles = findViewById(R.id.recycler_files);
        textEmptyFiles = findViewById(R.id.text_empty_files);
    }

    private void setupRecyclerView() {
        recyclerFiles.setLayoutManager(new LinearLayoutManager(this));
        fileAdapter = new FileAdapter();
        recyclerFiles.setAdapter(fileAdapter);
    }

    private void setupListeners() {
        buttonValidateAll.setOnClickListener(v -> runFullValidation());
        buttonCleanCorrupt.setOnClickListener(v -> confirmAndCleanCorrupted());
        buttonCloudSyncHub.setOnClickListener(v -> {
            Intent intent = new Intent(OfflineManagerActivity.this, SyncActivity.class);
            startActivity(intent);
        });

        buttonRepairPages.setOnClickListener(v -> confirmAndRepairPages());
        buttonAnalyzePages.setOnClickListener(v -> runAnalysis());

        spinnerFolders.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < folderNamesList.size()) {
                    selectedFolder = folderNamesList.get(position);
                    filterAndApply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        editSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s != null ? s.toString().trim().toLowerCase(Locale.US) : "";
                filterAndApply();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        chipGroupFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chip_filter_valid)) {
                filterMode = 1;
            } else if (checkedIds.contains(R.id.chip_filter_corrupt)) {
                filterMode = 2;
            } else {
                filterMode = 0;
            }
            filterAndApply();
        });
    }

    private void loadFoldersAndFiles() {
        new Thread(() -> {
            List<String> folders = OfflineStore.listAllLocalFolders(this);
            List<OfflineFileItem> items = new ArrayList<>();
            long totalBytes = 0;
            int validCount = 0;
            int warningCount = 0;
            int corruptCount = 0;

            for (String folder : folders) {
                List<File> files = OfflineStore.listHtmlFiles(this, folder);
                JSONObject meta = OfflineStore.readFolderMetadata(this, folder);

                for (File f : files) {
                    totalBytes += f.length();
                    JSONObject entry = meta.optJSONObject(f.getName());
                    String title = entry != null ? entry.optString("title", "") : "";
                    String url = entry != null ? entry.optString("original_url", "") : "";
                    int rowIndex = entry != null ? entry.optInt("row_index", -1) : -1;

                    if (title.isEmpty() || title.length() == 32) {
                        OfflineStore.ExtractedMetadata em = OfflineStore.extractMetadata(f);
                        title = em.title;
                        if (url.isEmpty()) {
                            url = em.originalUrl;
                        }
                    }

                    OfflineStore.ValidationResult val = OfflineStore.validateOfflineFile(f);
                    if (val.isValid()) validCount++;
                    else if (val.isWarning()) warningCount++;
                    else corruptCount++;

                    items.add(new OfflineFileItem(f, folder, title, url, rowIndex, val, f.lastModified(), f.length()));
                }
            }

            long finalTotalBytes = totalBytes;
            int finalValidCount = validCount;
            int finalWarningCount = warningCount;
            int finalCorruptCount = corruptCount;

            mainHandler.post(() -> {
                allLoadedItems.clear();
                allLoadedItems.addAll(items);

                // Update Dashboard stats
                textStatTotalFiles.setText(String.format(Locale.US, "%,d", items.size()));
                textStatStorageSize.setText(OfflineStore.formatFileSize(finalTotalBytes));
                textStatFolders.setText(String.valueOf(folders.size()));

                textHealthValid.setText("🟢 " + finalValidCount + " Valid");
                textHealthWarning.setText("🟡 " + finalWarningCount + " Small/Warn");
                textHealthCorrupt.setText("🔴 " + finalCorruptCount + " Corrupt");

                updateFolderSpinner(folders);
                filterAndApply();
            });
        }).start();
    }

    private void updateFolderSpinner(List<String> folders) {
        folderNamesList.clear();
        folderNamesList.add(ALL_FOLDERS);
        folderNamesList.addAll(folders);

        List<String> displayLabels = new ArrayList<>();
        displayLabels.add("All Folders (" + allLoadedItems.size() + " files)");

        int selectedIdx = 0;
        for (int i = 0; i < folders.size(); i++) {
            String f = folders.get(i);
            int count = OfflineStore.countHtmlFiles(this, f);
            displayLabels.add(f + " (" + count + " files)");
            if (f.equals(selectedFolder)) {
                selectedIdx = i + 1;
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, displayLabels);
        spinnerFolders.setAdapter(adapter);
        spinnerFolders.setSelection(selectedIdx);
    }

    private void filterAndApply() {
        displayedItems.clear();
        for (OfflineFileItem item : allLoadedItems) {
            // Folder check
            if (!ALL_FOLDERS.equals(selectedFolder) && !selectedFolder.equals(item.folderName)) {
                continue;
            }

            // Health filter check
            if (filterMode == 1 && !item.validationResult.isValid()) {
                continue;
            }
            if (filterMode == 2 && !item.validationResult.isCorrupted()) {
                continue;
            }

            // Search query check
            if (!searchQuery.isEmpty()) {
                boolean matchesTitle = item.title.toLowerCase(Locale.US).contains(searchQuery);
                boolean matchesUrl = item.originalUrl.toLowerCase(Locale.US).contains(searchQuery);
                boolean matchesFile = item.file.getName().toLowerCase(Locale.US).contains(searchQuery);
                if (!matchesTitle && !matchesUrl && !matchesFile) {
                    continue;
                }
            }

            displayedItems.add(item);
        }

        textCountSummary.setText("Showing " + displayedItems.size() + " of " + allLoadedItems.size() + " files"
                + (ALL_FOLDERS.equals(selectedFolder) ? "" : " in " + selectedFolder));

        textEmptyFiles.setVisibility(displayedItems.isEmpty() ? View.VISIBLE : View.GONE);
        fileAdapter.notifyDataSetChanged();
    }

    private void runFullValidation() {
        if (isValidating || allLoadedItems.isEmpty()) return;
        isValidating = true;
        progressValidation.setVisibility(View.VISIBLE);
        progressValidation.setMax(allLoadedItems.size());
        progressValidation.setProgress(0);

        buttonValidateAll.setEnabled(false);
        Toast.makeText(this, "Validating all offline files...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            int valid = 0, warn = 0, corrupt = 0;
            for (int i = 0; i < allLoadedItems.size(); i++) {
                OfflineFileItem item = allLoadedItems.get(i);
                OfflineStore.ValidationResult val = OfflineStore.validateOfflineFile(item.file);
                item.validationResult = val;
                if (val.isValid()) valid++;
                else if (val.isWarning()) warn++;
                else corrupt++;

                int currentIdx = i + 1;
                if (currentIdx % 20 == 0 || currentIdx == allLoadedItems.size()) {
                    mainHandler.post(() -> progressValidation.setProgress(currentIdx));
                }
            }

            int finalValid = valid;
            int finalWarn = warn;
            int finalCorrupt = corrupt;

            mainHandler.post(() -> {
                isValidating = false;
                progressValidation.setVisibility(View.GONE);
                buttonValidateAll.setEnabled(true);

                textHealthValid.setText("🟢 " + finalValid + " Valid");
                textHealthWarning.setText("🟡 " + finalWarn + " Small/Warn");
                textHealthCorrupt.setText("🔴 " + finalCorrupt + " Corrupt");

                filterAndApply();
                Toast.makeText(OfflineManagerActivity.this, "Validation complete! " + finalValid + " valid, " + finalCorrupt + " corrupt", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void confirmAndCleanCorrupted() {
        List<OfflineFileItem> corruptList = new ArrayList<>();
        for (OfflineFileItem item : allLoadedItems) {
            if (item.validationResult.isCorrupted()) {
                corruptList.add(item);
            }
        }

        if (corruptList.isEmpty()) {
            Toast.makeText(this, "No corrupted files found! All files are healthy.", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Clean Corrupted Files")
                .setMessage("Found " + corruptList.size() + " corrupted or 0-byte offline file(s).\n\nDo you want to permanently delete them?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    int deleted = 0;
                    for (OfflineFileItem item : corruptList) {
                        if (OfflineStore.deleteOfflineFile(this, item.folderName, item.file.getName())) {
                            deleted++;
                        }
                    }
                    Toast.makeText(this, "Cleaned " + deleted + " corrupted file(s)", Toast.LENGTH_SHORT).show();
                    loadFoldersAndFiles();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openInViewer(OfflineFileItem item) {
        Intent intent = new Intent(this, OfflineViewerActivity.class);
        intent.putExtra(OfflineViewerActivity.EXTRA_FILE_PATH, item.file.getAbsolutePath());
        intent.putExtra(OfflineViewerActivity.EXTRA_FOLDER_NAME, item.folderName);
        intent.putExtra(OfflineViewerActivity.EXTRA_TITLE, item.title);
        intent.putExtra(OfflineViewerActivity.EXTRA_URL, item.originalUrl);
        intent.putExtra(OfflineViewerActivity.EXTRA_ROW_INDEX, item.rowIndex);
        startActivity(intent);
    }

    private void openInMainRecorder(OfflineFileItem item) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("extra_folder_name", item.folderName);
        intent.putExtra("extra_target_index", Math.max(item.rowIndex, 0));
        startActivity(intent);
        finish();
    }

    private void confirmAndDeleteItem(OfflineFileItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Offline Page")
                .setMessage("Delete \"" + item.title + "\" from " + item.folderName + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    boolean ok = OfflineStore.deleteOfflineFile(this, item.folderName, item.file.getName());
                    if (ok) {
                        Toast.makeText(this, "Deleted: " + item.title, Toast.LENGTH_SHORT).show();
                        loadFoldersAndFiles();
                    } else {
                        Toast.makeText(this, "Could not delete file", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ══════════════════════════════════════════════════════════════
    //  OFFLINE PAGE REPAIR SYSTEM
    // ══════════════════════════════════════════════════════════════

    private void confirmAndRepairPages() {
        // Determine which folders to repair
        String targetFolder = (selectedFolder.equals(ALL_FOLDERS)) ? null : selectedFolder;

        String folderDesc = (targetFolder != null) ? targetFolder : "ALL " + folderNamesList.size() + " folders";
        int fileCount = 0;
        for (OfflineFileItem item : allLoadedItems) {
            if (targetFolder == null || item.folderName.equals(targetFolder)) {
                fileCount++;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("🔧 Repair Offline Pages")
                .setMessage("This will scan " + fileCount + " pages in " + folderDesc +
                        " and download ONLY the missing resources (images, CSS, etc.) to make each page fully self-contained for offline viewing.\n\n" +
                        "⚠️ Requires internet connection.\n\n" +
                        "Continue?")
                .setPositiveButton("Repair Now", (dialog, which) -> {
                    if (targetFolder != null) {
                        runRepairOnFolder(targetFolder);
                    } else {
                        runRepairAllFolders();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runRepairOnFolder(String folderName) {
        buttonRepairPages.setEnabled(false);
        buttonAnalyzePages.setEnabled(false);
        progressRepair.setVisibility(View.VISIBLE);
        progressRepair.setIndeterminate(false);
        textRepairStatus.setVisibility(View.VISIBLE);
        textRepairStatus.setText("Starting repair for " + folderName + "...");

        new Thread(() -> {
            OfflinePageRepairer.BatchRepairResult batchResult =
                    OfflinePageRepairer.repairFolder(this, folderName, 3,
                            new OfflinePageRepairer.RepairProgressListener() {
                                @Override
                                public void onProgress(int current, int total, String currentFile, String status) {
                                    mainHandler.post(() -> {
                                        progressRepair.setMax(total);
                                        progressRepair.setProgress(current);
                                        textRepairStatus.setText("Repairing " + current + "/" + total +
                                                ": " + currentFile + " • " + status);
                                    });
                                }

                                @Override
                                public void onFileComplete(String fileName, OfflinePageRepairer.RepairResult result) {
                                    // Individual file complete
                                }

                                @Override
                                public void onComplete(OfflinePageRepairer.BatchRepairResult result) {
                                    // Handled below
                                }
                            });

            mainHandler.post(() -> {
                progressRepair.setVisibility(View.GONE);
                textRepairStatus.setText("✅ Repair complete: " +
                        batchResult.filesRepaired + " repaired, " +
                        batchResult.totalResourcesFixed + " resources fixed (" +
                        OfflineStore.formatFileSize(batchResult.totalBytesDownloaded) + " downloaded), " +
                        batchResult.filesAlreadyComplete + " already complete, " +
                        batchResult.filesFailed + " failed");
                buttonRepairPages.setEnabled(true);
                buttonAnalyzePages.setEnabled(true);
                loadFoldersAndFiles();
            });
        }).start();
    }

    private void runRepairAllFolders() {
        buttonRepairPages.setEnabled(false);
        buttonAnalyzePages.setEnabled(false);
        progressRepair.setVisibility(View.VISIBLE);
        progressRepair.setIndeterminate(true);
        textRepairStatus.setVisibility(View.VISIBLE);

        // Collect unique folder names
        List<String> folders = new ArrayList<>();
        for (String fn : folderNamesList) {
            if (!fn.equals(ALL_FOLDERS)) folders.add(fn);
        }

        new Thread(() -> {
            int totalRepaired = 0;
            int totalFixed = 0;
            int totalFailed = 0;
            long totalBytes = 0;

            for (int i = 0; i < folders.size(); i++) {
                String folder = folders.get(i);
                int folderIdx = i + 1;
                mainHandler.post(() -> {
                    textRepairStatus.setText("Repairing folder " + folderIdx + "/" +
                            folders.size() + ": " + folder + "...");
                    progressRepair.setIndeterminate(false);
                    progressRepair.setMax(folders.size());
                    progressRepair.setProgress(folderIdx);
                });

                OfflinePageRepairer.BatchRepairResult result =
                        OfflinePageRepairer.repairFolder(this, folder, 3,
                                new OfflinePageRepairer.RepairProgressListener() {
                                    @Override
                                    public void onProgress(int current, int total, String currentFile, String status) {
                                        mainHandler.post(() -> textRepairStatus.setText(
                                                "📁 " + folder + " [" + folderIdx + "/" + folders.size() + "] • " +
                                                        "Page " + current + "/" + total + ": " + status));
                                    }

                                    @Override
                                    public void onFileComplete(String fileName, OfflinePageRepairer.RepairResult r) {}

                                    @Override
                                    public void onComplete(OfflinePageRepairer.BatchRepairResult r) {}
                                });

                totalRepaired += result.filesRepaired;
                totalFixed += result.totalResourcesFixed;
                totalFailed += result.filesFailed;
                totalBytes += result.totalBytesDownloaded;
            }

            int fRepaired = totalRepaired;
            int fFixed = totalFixed;
            int fFailed = totalFailed;
            long fBytes = totalBytes;
            mainHandler.post(() -> {
                progressRepair.setVisibility(View.GONE);
                textRepairStatus.setText("✅ All folders repaired: " +
                        fRepaired + " files patched, " +
                        fFixed + " resources fixed (" +
                        OfflineStore.formatFileSize(fBytes) + " downloaded), " +
                        fFailed + " failed");
                buttonRepairPages.setEnabled(true);
                buttonAnalyzePages.setEnabled(true);
                loadFoldersAndFiles();
            });
        }).start();
    }

    private void runAnalysis() {
        buttonAnalyzePages.setEnabled(false);
        buttonRepairPages.setEnabled(false);
        progressRepair.setVisibility(View.VISIBLE);
        progressRepair.setIndeterminate(true);
        textRepairStatus.setVisibility(View.VISIBLE);
        textRepairStatus.setText("Analyzing pages for missing resources...");

        // Determine target
        String targetFolder = (selectedFolder.equals(ALL_FOLDERS)) ? null : selectedFolder;

        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            int totalMissing = 0;
            int totalComplete = 0;
            int totalFiles = 0;
            int totalMissingImg = 0;
            int totalMissingCss = 0;
            int totalMissingSrcset = 0;
            int totalMissingLazy = 0;

            List<String> foldersToAnalyze = new ArrayList<>();
            if (targetFolder != null) {
                foldersToAnalyze.add(targetFolder);
            } else {
                for (String fn : folderNamesList) {
                    if (!fn.equals(ALL_FOLDERS)) foldersToAnalyze.add(fn);
                }
            }

            for (String folder : foldersToAnalyze) {
                List<OfflinePageRepairer.PageAnalysis> analyses =
                        OfflinePageRepairer.analyzeFolder(this, folder);

                int folderMissing = 0;
                int folderComplete = 0;
                int fMissingImg = 0, fMissingCss = 0, fMissingSrcset = 0, fMissingLazy = 0;

                for (OfflinePageRepairer.PageAnalysis a : analyses) {
                    totalFiles++;
                    if (a.isComplete()) {
                        folderComplete++;
                        totalComplete++;
                    } else {
                        folderMissing++;
                        totalMissing++;
                        fMissingImg += a.missingImages;
                        fMissingCss += a.missingCss;
                        fMissingSrcset += a.missingSrcset;
                        fMissingLazy += a.missingLazy;
                    }
                }

                totalMissingImg += fMissingImg;
                totalMissingCss += fMissingCss;
                totalMissingSrcset += fMissingSrcset;
                totalMissingLazy += fMissingLazy;

                report.append("📁 ").append(folder).append(": ");
                if (folderMissing == 0) {
                    report.append("✅ All ").append(analyses.size()).append(" pages complete\n");
                } else {
                    report.append(folderMissing).append(" need repair, ")
                            .append(folderComplete).append(" complete\n");
                    if (fMissingImg > 0) report.append("   🖼 ").append(fMissingImg).append(" missing images\n");
                    if (fMissingCss > 0) report.append("   🎨 ").append(fMissingCss).append(" missing CSS\n");
                    if (fMissingSrcset > 0) report.append("   📐 ").append(fMissingSrcset).append(" srcset refs\n");
                    if (fMissingLazy > 0) report.append("   ⏳ ").append(fMissingLazy).append(" lazy-load imgs\n");
                }
            }

            report.append("\n━━━━━━━━━━━━━━━━━━━━━━\n");
            report.append("📊 Total: ").append(totalFiles).append(" files analyzed\n");
            report.append("✅ Complete: ").append(totalComplete).append("\n");
            report.append("⚠️ Need repair: ").append(totalMissing).append("\n");
            if (totalMissingImg > 0) report.append("🖼 ").append(totalMissingImg).append(" missing images\n");
            if (totalMissingCss > 0) report.append("🎨 ").append(totalMissingCss).append(" missing CSS\n");
            if (totalMissingSrcset > 0) report.append("📐 ").append(totalMissingSrcset).append(" srcset refs\n");
            if (totalMissingLazy > 0) report.append("⏳ ").append(totalMissingLazy).append(" lazy-load imgs\n");

            String reportStr = report.toString();
            int fTotalMissing = totalMissing;
            mainHandler.post(() -> {
                progressRepair.setVisibility(View.GONE);
                textRepairStatus.setText(fTotalMissing > 0 ?
                        "⚠️ " + fTotalMissing + " pages have missing resources — tap Repair to fix" :
                        "✅ All pages are complete — no repair needed");
                buttonAnalyzePages.setEnabled(true);
                buttonRepairPages.setEnabled(true);

                new AlertDialog.Builder(this)
                        .setTitle("📊 Resource Analysis Report")
                        .setMessage(reportStr)
                        .setPositiveButton(fTotalMissing > 0 ? "Repair Now" : "OK",
                                fTotalMissing > 0 ? (dialog, which) -> confirmAndRepairPages() : null)
                        .setNegativeButton(fTotalMissing > 0 ? "Close" : null, null)
                        .show();
            });
        }).start();
    }

    private static class OfflineFileItem {
        final File file;
        final String folderName;
        final String title;
        final String originalUrl;
        final int rowIndex;
        OfflineStore.ValidationResult validationResult;
        final long lastModified;
        final long fileSizeBytes;

        OfflineFileItem(File file, String folderName, String title, String originalUrl, int rowIndex, OfflineStore.ValidationResult validationResult, long lastModified, long fileSizeBytes) {
            this.file = file;
            this.folderName = folderName;
            this.title = title;
            this.originalUrl = originalUrl;
            this.rowIndex = rowIndex;
            this.validationResult = validationResult;
            this.lastModified = lastModified;
            this.fileSizeBytes = fileSizeBytes;
        }
    }

    private class FileAdapter extends RecyclerView.Adapter<FileViewHolder> {

        @NonNull
        @Override
        public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_offline_file, parent, false);
            return new FileViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
            OfflineFileItem item = displayedItems.get(position);
            holder.bind(item);
        }

        @Override
        public int getItemCount() {
            return displayedItems.size();
        }
    }

    private class FileViewHolder extends RecyclerView.ViewHolder {
        private final TextView textBadge;
        private final TextView textIndex;
        private final TextView textFolder;
        private final TextView textTitle;
        private final TextView textUrl;
        private final TextView textFileInfo;
        private final TextView textReason;
        private final Button buttonDelete;
        private final Button buttonRecorder;
        private final Button buttonView;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            textBadge = itemView.findViewById(R.id.text_item_badge);
            textIndex = itemView.findViewById(R.id.text_item_index);
            textFolder = itemView.findViewById(R.id.text_item_folder);
            textTitle = itemView.findViewById(R.id.text_item_title);
            textUrl = itemView.findViewById(R.id.text_item_url);
            textFileInfo = itemView.findViewById(R.id.text_item_file_info);
            textReason = itemView.findViewById(R.id.text_item_reason);
            buttonDelete = itemView.findViewById(R.id.button_item_delete);
            buttonRecorder = itemView.findViewById(R.id.button_item_recorder);
            buttonView = itemView.findViewById(R.id.button_item_view);
        }

        void bind(OfflineFileItem item) {
            textTitle.setText(item.title);
            textUrl.setText(item.originalUrl.isEmpty() ? "No URL specified" : item.originalUrl);
            textFolder.setText(item.folderName);
            textIndex.setText(item.rowIndex >= 0 ? "#" + item.rowIndex : "");

            String ext = item.file.getName().toLowerCase(Locale.US).endsWith(".mht") ? "MHT" : "HTML";
            textFileInfo.setText(OfflineStore.formatFileSize(item.fileSizeBytes) + " • " + ext + " • " + DATE_FORMAT.format(new Date(item.lastModified)));

            OfflineStore.ValidationResult val = item.validationResult;
            if (val.isValid()) {
                textBadge.setText("VALID 🟢");
                textBadge.setBackgroundResource(R.drawable.bg_badge_valid);
                textBadge.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.badge_valid_text));
                textReason.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.badge_valid_text));
            } else if (val.isWarning()) {
                textBadge.setText("WARN 🟡");
                textBadge.setBackgroundResource(R.drawable.bg_badge_warning);
                textBadge.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.badge_warning_text));
                textReason.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.badge_warning_text));
            } else {
                textBadge.setText("CORRUPT 🔴");
                textBadge.setBackgroundResource(R.drawable.bg_badge_corrupt);
                textBadge.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.badge_corrupt_text));
                textReason.setTextColor(ContextCompat.getColor(itemView.getContext(), R.color.badge_corrupt_text));
            }
            textReason.setText(val.reason);

            buttonView.setOnClickListener(v -> openInViewer(item));
            buttonRecorder.setOnClickListener(v -> openInMainRecorder(item));
            buttonDelete.setOnClickListener(v -> confirmAndDeleteItem(item));
        }
    }
}
