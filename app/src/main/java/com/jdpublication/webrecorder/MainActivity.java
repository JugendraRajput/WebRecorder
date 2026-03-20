package com.jdpublication.webrecorder;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_MEDIA_PROJECTION = 101;
    private static final int REQUEST_CODE_AUDIO_PERMISSION = 102;
    private static final int REQUEST_CODE_NOTIFICATION_PERMISSION = 103;
    private static final long BACK_PRESS_INTERVAL_MS = 1500L;

    private WebView webView;
    private Button nextButton;
    private Button prevButton;
    private FloatingActionButton fabRecord;
    private FloatingActionButton fabPause;
    private TextView placeholderView;

    private final List<UrlData> urlDataList = new ArrayList<>();
    private final DataFormatter dataFormatter = new DataFormatter();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int currentIndex = -1;
    private int currentSec = 0;
    private long lastBackPressedAt = 0L;

    private Uri selectedExcelUri;
    private boolean hasExcelWriteAccess = false;

    private MediaProjectionManager mediaProjectionManager;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private boolean isStartingRecording = false;
    private boolean isLoadedFromExcel = true;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    handleSelectedExcelFile(result.getData());
                }
            });

    private final BroadcastReceiver recordingStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }

            switch (intent.getAction()) {
                case RecordingService.ACTION_RECORDING_STARTED:
                    isStartingRecording = false;
                    isRecording = true;
                    isPaused = false;
                    currentSec = 0;
                    handler.removeCallbacks(runnable);
                    handler.post(runnable);
                    updateUiForRecordingState();
                    break;
                case RecordingService.ACTION_RECORDING_PAUSED:
                    isPaused = true;
                    updateUiForRecordingState();
                    break;
                case RecordingService.ACTION_RECORDING_RESUMED:
                    isPaused = false;
                    handler.removeCallbacks(runnable);
                    handler.post(runnable);
                    updateUiForRecordingState();
                    break;
                case RecordingService.ACTION_RECORDING_ERROR:
                    isStartingRecording = false;
                    isRecording = false;
                    isPaused = false;
                    handler.removeCallbacks(runnable);
                    updateUiForRecordingState();
                    String message = intent.getStringExtra(RecordingService.EXTRA_MESSAGE);
                    if (message != null && !message.isEmpty()) {
                        Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                    break;
                case RecordingService.ACTION_RECORDING_STOPPED:
                    isStartingRecording = false;
                    onRecordingStopped();
                    break;
            }
        }
    };

    private final Runnable runnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording && !isPaused) {
                currentSec++;
                updateActionBarForCurrentState();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        initializeViews();
        setupWebView();
        setupClickListeners();

        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(RecordingService.ACTION_RECORDING_STARTED);
        filter.addAction(RecordingService.ACTION_RECORDING_PAUSED);
        filter.addAction(RecordingService.ACTION_RECORDING_RESUMED);
        filter.addAction(RecordingService.ACTION_RECORDING_STOPPED);
        filter.addAction(RecordingService.ACTION_RECORDING_ERROR);
        LocalBroadcastManager.getInstance(this).registerReceiver(recordingStateReceiver, filter);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackNavigation();
            }
        });

        updateUiForRecordingState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isRecording = RecordingService.isRecording;
        isPaused = RecordingService.isPaused;

        handler.removeCallbacks(runnable);
        if (isRecording && !isPaused) {
            handler.post(runnable);
        }

        updateUiForRecordingState();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(runnable);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(recordingStateReceiver);
        super.onDestroy();
    }

    private void initializeViews() {
        webView = findViewById(R.id.webView);
        nextButton = findViewById(R.id.nextButton);
        prevButton = findViewById(R.id.prevButton);
        fabRecord = findViewById(R.id.fab_record);
        fabPause = findViewById(R.id.fab_pause);
        placeholderView = findViewById(R.id.placeholder_view);
    }

    private void setupClickListeners() {
        nextButton.setOnClickListener(v -> navigate(true));
        prevButton.setOnClickListener(v -> navigate(false));

        fabRecord.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else if (currentIndex != -1) {
                startRecording();
            } else {
                Toast.makeText(this, "Please select a file and load a URL first.", Toast.LENGTH_SHORT).show();
            }
        });

        fabPause.setOnClickListener(v -> {
            if (isPaused) {
                resumeRecording();
            } else {
                pauseRecording();
            }
        });
    }

    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (isLoadedFromExcel) {
                    view.clearHistory();
                }
                isLoadedFromExcel = false;
                updateBackButtonVisibility();
                updateActionBarForCurrentState();
                super.onPageFinished(view, url);
            }
        });
    }

    private void openExcelPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        filePickerLauncher.launch(intent);
    }

    private void handleSelectedExcelFile(Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        selectedExcelUri = uri;
        persistDocumentPermissions(uri, data);
        hasExcelWriteAccess = hasWriteAccess(uri);

        if (!hasExcelWriteAccess) {
            Toast.makeText(this, R.string.excel_loaded_read_only, Toast.LENGTH_LONG).show();
        }

        parseExcelFile(uri);
    }

    private void persistDocumentPermissions(Uri uri, Intent data) {
        int grantedFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (grantedFlags == 0) {
            grantedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        }

        try {
            getContentResolver().takePersistableUriPermission(uri, grantedFlags);
        } catch (SecurityException e) {
            Log.w(TAG, "Could not persist all document permissions", e);
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException inner) {
                Log.w(TAG, "Could not persist read permission either", inner);
            }
        }
    }

    private boolean hasWriteAccess(Uri uri) {
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri) && permission.isWritePermission()) {
                return true;
            }
        }

        try (android.os.ParcelFileDescriptor ignored = getContentResolver().openFileDescriptor(uri, "rw")) {
            return ignored != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void parseExcelFile(Uri uri) {
        placeholderView.setText("Loading Excel File...");

        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri);
                 Workbook workbook = WorkbookFactory.create(is)) {

                if (workbook == null) {
                    throw new IllegalStateException("Workbook could not be opened.");
                }

                Sheet sheet = workbook.getSheetAt(0);
                Iterator<Row> rowIterator = sheet.iterator();

                List<UrlData> tempList = new ArrayList<>();
                if (rowIterator.hasNext()) {
                    rowIterator.next();
                }

                while (rowIterator.hasNext()) {
                    Row row = rowIterator.next();
                    Cell fileNameCell = row.getCell(0);
                    Cell urlCell = row.getCell(1);

                    if (fileNameCell != null && urlCell != null) {
                        String filename = dataFormatter.formatCellValue(fileNameCell).trim();
                        String url = dataFormatter.formatCellValue(urlCell).trim();
                        if (!filename.isEmpty() && !url.isEmpty()) {
                            tempList.add(new UrlData(row.getRowNum(), filename, url));
                        }
                    }
                }

                runOnUiThread(() -> {
                    urlDataList.clear();
                    urlDataList.addAll(tempList);

                    if (!urlDataList.isEmpty()) {
                        placeholderView.setVisibility(View.GONE);
                        webView.setVisibility(View.VISIBLE);
                        navigateToIndex(0);
                    } else {
                        currentIndex = -1;
                        updateUiForRecordingState();
                        Toast.makeText(MainActivity.this, "Excel file is empty or in wrong format.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "parseExcelFile", e);
                runOnUiThread(() -> {
                    placeholderView.setText("Failed to load file.");
                    currentIndex = -1;
                    updateUiForRecordingState();
                    Toast.makeText(MainActivity.this, "Failed to read Excel file.", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void navigate(boolean isNext) {
        if (isNext && currentIndex < urlDataList.size() - 1) {
            navigateToIndex(currentIndex + 1);
        } else if (!isNext && currentIndex > 0) {
            navigateToIndex(currentIndex - 1);
        }
    }

    private void navigateToIndex(int index) {
        if (index < 0 || index >= urlDataList.size()) {
            return;
        }

        currentIndex = index;
        loadCurrentUrl();
        updateUiForRecordingState();
    }

    private void loadCurrentUrl() {
        if (currentIndex < 0 || currentIndex >= urlDataList.size()) {
            return;
        }

        isLoadedFromExcel = true;
        webView.loadUrl(urlDataList.get(currentIndex).getWebUrl());
    }

    private void handleBackNavigation() {
        if (isRecording) {
            moveTaskToBack(true);
            Toast.makeText(this, R.string.recording_continues_in_background, Toast.LENGTH_SHORT).show();
            return;
        }

        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }

        if (currentIndex > 0) {
            navigateToIndex(currentIndex - 1);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt <= BACK_PRESS_INTERVAL_MS) {
            finish();
            return;
        }

        lastBackPressedAt = now;
        Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
    }

    private void updateBackButtonVisibility() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }

        boolean showBack = !isRecording && (webView.canGoBack() || currentIndex > 0);
        actionBar.setDisplayHomeAsUpEnabled(showBack);
    }

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_AUDIO_PERMISSION);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE_NOTIFICATION_PERMISSION);
            return;
        }

        launchMediaProjectionRequest();
    }

    private void launchMediaProjectionRequest() {
        if (mediaProjectionManager == null) {
            Toast.makeText(this, "Screen capture service is not available on this device.", Toast.LENGTH_LONG).show();
            return;
        }

        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_MEDIA_PROJECTION);
    }

    private void stopRecording() {
        isStartingRecording = false;
        stopService(new Intent(this, RecordingService.class));
        updateUiForRecordingState();
    }

    private void pauseRecording() {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_PAUSE);
        startService(intent);
    }

    private void resumeRecording() {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_RESUME);
        startService(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null && currentIndex != -1) {
                Intent serviceIntent = new Intent(this, RecordingService.class);
                serviceIntent.putExtra("resultCode", resultCode);
                serviceIntent.putExtra("data", data);
                serviceIntent.putExtra("filename", urlDataList.get(currentIndex).getFilename());

                isStartingRecording = true;
                updateUiForRecordingState();
                ContextCompat.startForegroundService(this, serviceIntent);
            } else {
                isStartingRecording = false;
                updateUiForRecordingState();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording();
            } else {
                Toast.makeText(this, "Audio permission is required.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == REQUEST_CODE_NOTIFICATION_PERMISSION) {
            launchMediaProjectionRequest();
        }
    }

    private void showEditCurrentEntryDialog() {
        if (currentIndex < 0 || currentIndex >= urlDataList.size()) {
            return;
        }

        if (selectedExcelUri == null || !hasExcelWriteAccess) {
            Toast.makeText(this, R.string.select_excel_to_edit, Toast.LENGTH_LONG).show();
            openExcelPicker();
            return;
        }

        UrlData currentData = urlDataList.get(currentIndex);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_entry, null);
        EditText filenameInput = dialogView.findViewById(R.id.edit_filename);
        EditText urlInput = dialogView.findViewById(R.id.edit_url);

        filenameInput.setText(currentData.getFilename());
        urlInput.setText(currentData.getWebUrl());
        filenameInput.setSelection(filenameInput.getText().length());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.edit_current_entry)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save_changes, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newFilename = filenameInput.getText().toString().trim();
            String newUrl = urlInput.getText().toString().trim();

            if (newFilename.isEmpty() || newUrl.isEmpty()) {
                Toast.makeText(MainActivity.this, R.string.invalid_entry_values, Toast.LENGTH_LONG).show();
                return;
            }

            boolean urlChanged = !newUrl.equals(currentData.getWebUrl());
            currentData.setFilename(newFilename);
            currentData.setWebUrl(newUrl);

            updateActionBarForCurrentState();
            saveCurrentEntryToExcel(currentData, urlChanged);
            dialog.dismiss();
        }));

        dialog.show();
    }

    private void saveCurrentEntryToExcel(UrlData updatedData, boolean reloadWebPage) {
        if (selectedExcelUri == null) {
            Toast.makeText(this, R.string.select_excel_to_edit, Toast.LENGTH_LONG).show();
            return;
        }

        new Thread(() -> {
            boolean saveSucceeded = false;

            try (InputStream is = getContentResolver().openInputStream(selectedExcelUri);
                 Workbook workbook = WorkbookFactory.create(is)) {

                Sheet sheet = workbook.getSheetAt(0);
                Row row = sheet.getRow(updatedData.getRowIndex());
                if (row == null) {
                    row = sheet.createRow(updatedData.getRowIndex());
                }

                Cell filenameCell = row.getCell(0);
                if (filenameCell == null) {
                    filenameCell = row.createCell(0);
                }
                filenameCell.setCellValue(updatedData.getFilename());

                Cell urlCell = row.getCell(1);
                if (urlCell == null) {
                    urlCell = row.createCell(1);
                }
                urlCell.setCellValue(updatedData.getWebUrl());

                try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(selectedExcelUri, "rwt");
                     FileOutputStream outputStream = new FileOutputStream(pfd.getFileDescriptor())) {
                    workbook.write(outputStream);
                    outputStream.flush();
                    saveSucceeded = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "saveCurrentEntryToExcel", e);
            }

            boolean finalSaveSucceeded = saveSucceeded;
            runOnUiThread(() -> {
                if (finalSaveSucceeded) {
                    Toast.makeText(MainActivity.this, R.string.excel_changes_saved, Toast.LENGTH_SHORT).show();
                    if (reloadWebPage) {
                        loadCurrentUrl();
                    } else {
                        updateUiForRecordingState();
                    }
                } else {
                    Toast.makeText(MainActivity.this, R.string.excel_changes_failed, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void updateNavigationButtons() {
        boolean hasSelection = currentIndex != -1;
        prevButton.setEnabled(!isRecording && !isStartingRecording && currentIndex > 0);
        nextButton.setEnabled(!isRecording && !isStartingRecording && hasSelection && currentIndex < urlDataList.size() - 1);
        fabRecord.setEnabled(hasSelection && !isStartingRecording);
    }

    private void updateActionBarForCurrentState() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }

        if (currentIndex >= 0 && currentIndex < urlDataList.size()) {
            UrlData data = urlDataList.get(currentIndex);
            actionBar.setTitle(data.getFilename());

            if (isStartingRecording) {
                actionBar.setSubtitle(getString(R.string.recording_starting));
            } else if (isRecording) {
                String timerText = formatElapsedTime(currentSec);
                actionBar.setSubtitle(isPaused ? timerText + " (Paused)" : timerText);
            } else {
                actionBar.setSubtitle("(" + (currentIndex + 1) + "/" + urlDataList.size() + ")");
            }
        } else {
            actionBar.setTitle(R.string.app_name);
            actionBar.setSubtitle(null);
        }
    }

    private String formatElapsedTime(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    public void onRecordingStopped() {
        isRecording = false;
        isPaused = false;
        handler.removeCallbacks(runnable);
        updateUiForRecordingState();
    }

    private void updateUiForRecordingState() {
        View navigationControls = findViewById(R.id.navigation_controls);
        boolean hideNavigation = isRecording || isStartingRecording;

        if (navigationControls != null) {
            navigationControls.setVisibility(hideNavigation ? View.GONE : View.VISIBLE);
        }

        fabRecord.setImageResource(isRecording ? R.drawable.ic_stop : R.drawable.ic_record);
        fabPause.setVisibility(isRecording ? View.VISIBLE : View.GONE);
        fabPause.setImageResource(isPaused ? R.drawable.ic_play : R.drawable.ic_pause);
        fabPause.setEnabled(isRecording);

        updateNavigationButtons();
        updateBackButtonVisibility();
        updateActionBarForCurrentState();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            handleBackNavigation();
            return true;
        }

        if (item.getItemId() == R.id.action_select_file) {
            openExcelPicker();
            return true;
        }

        if (item.getItemId() == R.id.action_edit_current) {
            showEditCurrentEntryDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
