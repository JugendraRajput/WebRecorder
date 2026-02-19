package com.jdpublication.webrecorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_MEDIA_PROJECTION = 101;
    private static final int REQUEST_CODE_OVERLAY_PERMISSION = 102;
    private static final int REQUEST_CODE_AUDIO_PERMISSION = 103;

    private WebView webView;
    private Button nextButton, prevButton;
    private FloatingActionButton fabRecord, fabPause;
    private TextView placeholderView;
    private final List<UrlData> urlDataList = new ArrayList<>();
    private int currentIndex = -1;

    private MediaProjectionManager mediaProjectionManager;
    private boolean isRecording = false;
    private boolean isPaused = false;
    boolean isLoadedFromExcel = true;

    private final ActivityResultLauncher<String[]> filePickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null) {
            parseExcelFile(uri);
        }
    });

    private final BroadcastReceiver recordingStoppedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onRecordingStopped();
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
        LocalBroadcastManager.getInstance(this).registerReceiver(recordingStoppedReceiver, new IntentFilter(RecordingService.ACTION_RECORDING_STOPPED));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackNavigation();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        isRecording = RecordingService.isRecording;
        isPaused = RecordingService.isPaused;
        updateUiForRecordingState();

        if (isRecording && !isPaused) {
            handler.removeCallbacks(runnable);
            handler.post(runnable);
        }
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(recordingStoppedReceiver);
        super.onDestroy();
    }

    private void initializeViews() {
        webView = findViewById(R.id.webView);
        nextButton = findViewById(R.id.nextButton);
        prevButton = findViewById(R.id.prevButton);
        fabRecord = findViewById(R.id.fab_record);
        fabPause = findViewById(R.id.fab_pause);
        placeholderView = findViewById(R.id.placeholder_view);
        updateNavigationButtons();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDatabaseEnabled(true);
    }

    private void handleBackNavigation() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }

        if (currentIndex > 0) {
            currentIndex--;
            loadCurrentUrl();
            updateNavigationButtons();
        }
    }

    private void updateBackButtonVisibility() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) return;

        boolean canGoBackInWeb = webView.canGoBack();
        boolean canGoBackInList = currentIndex > 0;

        actionBar.setDisplayHomeAsUpEnabled(canGoBackInWeb || canGoBackInList);
    }

    private void setupClickListeners() {
        nextButton.setOnClickListener(v -> navigate(true));
        prevButton.setOnClickListener(v -> navigate(false));
        fabRecord.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                if (currentIndex != -1) {
                    startRecording();
                } else {
                    Toast.makeText(this, "Please select a file and load a URL first.", Toast.LENGTH_SHORT).show();
                }
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

    private void pauseRecording() {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_PAUSE);
        startService(intent);
        isPaused = true;
        updateUiForRecordingState();
    }

    private void resumeRecording() {
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(RecordingService.ACTION_RESUME);
        startService(intent);
        isPaused = false;
        updateUiForRecordingState();
    }

    private void parseExcelFile(Uri uri) {
        placeholderView.setText("Loading Excel File...");

        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                assert is != null;
                Workbook workbook = new XSSFWorkbook(is);
                Sheet sheet = workbook.getSheetAt(0);
                Iterator<Row> rowIterator = sheet.iterator();

                List<UrlData> tempList = new ArrayList<>();
                if (rowIterator.hasNext()) rowIterator.next(); // Skip header
                while (rowIterator.hasNext()) {
                    Row row = rowIterator.next();
                    Cell fileNameCell = row.getCell(0);
                    Cell urlCell = row.getCell(1);
                    if (fileNameCell != null && urlCell != null) {
                        tempList.add(new UrlData(fileNameCell.getStringCellValue(), urlCell.getStringCellValue()));
                    }
                }

                // Post results back to the Main Thread
                runOnUiThread(() -> {
                    urlDataList.clear();
                    urlDataList.addAll(tempList);
                    if (!urlDataList.isEmpty()) {
                        currentIndex = 0;
                        loadCurrentUrl();
                        placeholderView.setVisibility(View.GONE);
                        webView.setVisibility(View.VISIBLE);
                    } else {
                        Toast.makeText(MainActivity.this, "Excel file is empty or in wrong format.", Toast.LENGTH_LONG).show();
                    }
                    updateNavigationButtons();
                });

            } catch (Exception e) {
                Log.e("ExcelError", "parseExcelFile: ", e);
                runOnUiThread(() -> {
                    placeholderView.setText("Failed to load file.");
                    Toast.makeText(MainActivity.this, "Failed to read Excel file.", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void loadCurrentUrl() {
        if (currentIndex >= 0 && currentIndex < urlDataList.size()) {

            UrlData data = urlDataList.get(currentIndex);
            isLoadedFromExcel = true;

            webView.loadUrl(data.getWebUrl());

            // Clear history AFTER new page becomes base
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    if (isLoadedFromExcel) {
                        view.clearHistory();
                    }
                    updateBackButtonVisibility();
                    isLoadedFromExcel = false;
                    super.onPageFinished(view, url);
                }
            });

            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setTitle(data.getFilename());
                actionBar.setSubtitle("(" + (currentIndex + 1) + "/" + urlDataList.size() + ")");
            }
        }
    }

    private void navigate(boolean isNext) {
        if (isNext) {
            if (currentIndex < urlDataList.size() - 1) {
                currentIndex++;
                loadCurrentUrl();
            }
        } else {
            if (currentIndex > 0) {
                currentIndex--;
                loadCurrentUrl();
            }
        }
        updateNavigationButtons();
    }

    private void updateNavigationButtons() {
        prevButton.setEnabled(currentIndex > 0);
        nextButton.setEnabled(currentIndex != -1 && currentIndex < urlDataList.size() - 1);
        fabRecord.setEnabled(currentIndex != -1);
    }

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_AUDIO_PERMISSION);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION);
            return;
        }
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE_MEDIA_PROJECTION);
    }

    private void stopRecording() {
        Intent serviceIntent = new Intent(this, RecordingService.class);
        stopService(serviceIntent);
        // onRecordingStopped() is called by the broadcast receiver
    }

    int currentSec = 0;
    Handler handler = new Handler();
    Runnable runnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording && !isPaused) { // Timer only runs if not paused
                int minutes = currentSec / 60;
                int seconds = currentSec % 60;
                String time = String.format("%02d:%02d", minutes, seconds);
                ActionBar actionBar = getSupportActionBar();
                if (actionBar != null) actionBar.setSubtitle(time);
                currentSec++;
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION && resultCode == RESULT_OK) {
            // Start immediately to avoid background-execution limits
            Intent serviceIntent = new Intent(this, RecordingService.class);
            serviceIntent.putExtra("resultCode", resultCode);
            serviceIntent.putExtra("data", data);
            serviceIntent.putExtra("filename", urlDataList.get(currentIndex).getFilename());

            ContextCompat.startForegroundService(this, serviceIntent); // Use ContextCompat for safety

            isRecording = true;
            isPaused = false;
            currentSec = 0;
            handler.removeCallbacks(runnable); // Clear any existing callbacks first
            handler.post(runnable);
            updateUiForRecordingState();
        } else if (requestCode == REQUEST_CODE_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) startRecording();
            else Toast.makeText(this, "Overlay permission is required.", Toast.LENGTH_LONG).show();
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
        }
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
            return true; // Add this to consume the click
        }

        if (item.getItemId() == R.id.action_select_file) {
            filePickerLauncher.launch(new String[]{"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel"});
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public void onRecordingStopped() {
        isRecording = false;
        isPaused = false;
        handler.removeCallbacks(runnable);
        updateUiForRecordingState();
    }

    private void updateUiForRecordingState() {
        runOnUiThread(() -> {
            //View appBar = findViewById(R.id.app_bar);
            View navigationControls = findViewById(R.id.navigation_controls);

            if (isRecording) {
                //if (appBar != null) appBar.setVisibility(View.GONE);
                if (navigationControls != null) navigationControls.setVisibility(View.GONE);

                fabRecord.setImageResource(R.drawable.ic_stop);
                fabPause.setVisibility(View.VISIBLE);
                fabPause.setImageResource(isPaused ? R.drawable.ic_play : R.drawable.ic_pause);
            } else {
                //if (appBar != null) appBar.setVisibility(View.VISIBLE);
                if (navigationControls != null) navigationControls.setVisibility(View.VISIBLE);

                fabRecord.setImageResource(R.drawable.ic_record);
                fabPause.setVisibility(View.GONE);
                loadCurrentUrl(); // Restore filename/count subtitle
            }
        });
    }
}