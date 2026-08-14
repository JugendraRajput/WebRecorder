/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  android.app.Activity
 *  android.content.BroadcastReceiver
 *  android.content.Context
 *  android.content.Intent
 *  android.content.IntentFilter
 *  android.content.UriPermission
 *  android.media.projection.MediaProjectionManager
 *  android.net.Uri
 *  android.os.Build$VERSION
 *  android.os.Bundle
 *  android.os.Handler
 *  android.os.Looper
 *  android.os.ParcelFileDescriptor
 *  android.os.Parcelable
 *  android.util.Base64
 *  android.util.Log
 *  android.view.LayoutInflater
 *  android.view.Menu
 *  android.view.MenuItem
 *  android.view.View
 *  android.webkit.WebView
 *  android.webkit.WebViewClient
 *  android.widget.Button
 *  android.widget.EditText
 *  android.widget.TextView
 *  android.widget.Toast
 *  androidx.activity.OnBackPressedCallback
 *  androidx.activity.result.ActivityResultLauncher
 *  androidx.activity.result.contract.ActivityResultContract
 *  androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult
 *  androidx.annotation.NonNull
 *  androidx.appcompat.app.ActionBar
 *  androidx.appcompat.app.AlertDialog
 *  androidx.appcompat.app.AlertDialog$Builder
 *  androidx.appcompat.app.AppCompatActivity
 *  androidx.appcompat.widget.Toolbar
 *  androidx.core.app.ActivityCompat
 *  androidx.core.content.ContextCompat
 *  androidx.lifecycle.LifecycleOwner
 *  androidx.localbroadcastmanager.content.LocalBroadcastManager
 *  com.google.android.material.floatingactionbutton.FloatingActionButton
 *  com.jdpublication.webrecorder.R$drawable
 *  com.jdpublication.webrecorder.R$id
 *  com.jdpublication.webrecorder.R$layout
 *  com.jdpublication.webrecorder.R$menu
 *  com.jdpublication.webrecorder.R$string
 *  org.apache.poi.ss.usermodel.Cell
 *  org.apache.poi.ss.usermodel.DataFormatter
 *  org.apache.poi.ss.usermodel.Row
 *  org.apache.poi.ss.usermodel.Sheet
 *  org.apache.poi.ss.usermodel.Workbook
 *  org.apache.poi.ss.usermodel.WorkbookFactory
 *  org.json.JSONArray
 *  org.json.JSONObject
 *  org.json.JSONTokener
 */
package com.jdpublication.webrecorder;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.UriPermission;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.jdpublication.webrecorder.OfflineStore;
import com.jdpublication.webrecorder.R;
import com.jdpublication.webrecorder.RecordingService;
import com.jdpublication.webrecorder.SyncActivity;
import com.jdpublication.webrecorder.UrlData;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

public class MainActivity
extends AppCompatActivity {
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
    private View placeholderContainer;
    private TextView offlineBadgeView;
    private final List<UrlData> urlDataList = new ArrayList<UrlData>();
    private final DataFormatter dataFormatter = new DataFormatter();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int currentIndex = -1;
    private int currentSec = 0;
    private int pendingRestoreIndex = 0;
    private long lastBackPressedAt = 0L;
    private Uri selectedExcelUri;
    private boolean hasExcelWriteAccess = false;
    private boolean isCurrentSourceExcel = false;
    private String currentSheetFolderName = "";
    private String currentSheetDisplayName = "";
    private MediaProjectionManager mediaProjectionManager;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private boolean isStartingRecording = false;
    private boolean isLoadedFromExcel = true;
    private boolean currentPageLoadedOffline = false;
    private PendingOfflineSave pendingOfflineSave;
    private final ActivityResultLauncher<Intent> filePickerLauncher = this.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == -1 && result.getData() != null) {
            this.handleSelectedExcelFile(result.getData());
        }
    });
    private final BroadcastReceiver recordingStateReceiver = new BroadcastReceiver(){

        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            switch (intent.getAction()) {
                case "com.jdpublication.webrecorder.RECORDING_STARTED": {
                    MainActivity.this.isStartingRecording = false;
                    MainActivity.this.isRecording = true;
                    MainActivity.this.isPaused = false;
                    MainActivity.this.currentSec = 0;
                    MainActivity.this.handler.removeCallbacks(MainActivity.this.runnable);
                    MainActivity.this.handler.post(MainActivity.this.runnable);
                    MainActivity.this.updateUiForRecordingState();
                    break;
                }
                case "com.jdpublication.webrecorder.RECORDING_PAUSED": {
                    MainActivity.this.isPaused = true;
                    MainActivity.this.updateUiForRecordingState();
                    break;
                }
                case "com.jdpublication.webrecorder.RECORDING_RESUMED": {
                    MainActivity.this.isPaused = false;
                    MainActivity.this.handler.removeCallbacks(MainActivity.this.runnable);
                    MainActivity.this.handler.post(MainActivity.this.runnable);
                    MainActivity.this.updateUiForRecordingState();
                    break;
                }
                case "com.jdpublication.webrecorder.RECORDING_ERROR": {
                    MainActivity.this.isStartingRecording = false;
                    MainActivity.this.isRecording = false;
                    MainActivity.this.isPaused = false;
                    MainActivity.this.handler.removeCallbacks(MainActivity.this.runnable);
                    MainActivity.this.updateUiForRecordingState();
                    String message = intent.getStringExtra("message");
                    if (message == null || message.isEmpty()) break;
                    Toast.makeText((Context)MainActivity.this, (CharSequence)message, (int)1).show();
                    break;
                }
                case "com.jdpublication.webrecorder.RECORDING_STOPPED": {
                    MainActivity.this.isStartingRecording = false;
                    MainActivity.this.onRecordingStopped();
                }
            }
        }
    };
    private final Runnable runnable = new Runnable(){

        @Override
        public void run() {
            if (MainActivity.this.isRecording && !MainActivity.this.isPaused) {
                ++MainActivity.this.currentSec;
                MainActivity.this.updateActionBarForCurrentState();
            }
            MainActivity.this.handler.postDelayed((Runnable)this, 1000L);
        }
    };

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar)this.findViewById(R.id.toolbar);
        this.setSupportActionBar(toolbar);
        this.currentSheetFolderName = OfflineStore.getCurrentFolderName((Context)this);
        this.currentSheetDisplayName = OfflineStore.getCurrentDisplayName((Context)this);
        this.initializeViews();
        this.setupWebView();
        this.setupClickListeners();
        this.mediaProjectionManager = (MediaProjectionManager)this.getSystemService("media_projection");
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.jdpublication.webrecorder.RECORDING_STARTED");
        filter.addAction("com.jdpublication.webrecorder.RECORDING_PAUSED");
        filter.addAction("com.jdpublication.webrecorder.RECORDING_RESUMED");
        filter.addAction("com.jdpublication.webrecorder.RECORDING_STOPPED");
        filter.addAction("com.jdpublication.webrecorder.RECORDING_ERROR");
        LocalBroadcastManager.getInstance((Context)this).registerReceiver(this.recordingStateReceiver, filter);
        this.getOnBackPressedDispatcher().addCallback((LifecycleOwner)this, new OnBackPressedCallback(true){

            public void handleOnBackPressed() {
                MainActivity.this.handleBackNavigation();
            }
        });
        this.updateUiForRecordingState();
    }

    protected void onResume() {
        super.onResume();
        this.isRecording = RecordingService.isRecording;
        this.isPaused = RecordingService.isPaused;
        this.handler.removeCallbacks(this.runnable);
        if (this.isRecording && !this.isPaused) {
            this.handler.post(this.runnable);
        }
        this.updateUiForRecordingState();
    }

    protected void onDestroy() {
        this.handler.removeCallbacks(this.runnable);
        LocalBroadcastManager.getInstance((Context)this).unregisterReceiver(this.recordingStateReceiver);
        super.onDestroy();
    }

    private void initializeViews() {
        this.webView = (WebView)this.findViewById(R.id.webView);
        this.nextButton = (Button)this.findViewById(R.id.nextButton);
        this.prevButton = (Button)this.findViewById(R.id.prevButton);
        this.fabRecord = (FloatingActionButton)this.findViewById(R.id.fab_record);
        this.fabPause = (FloatingActionButton)this.findViewById(R.id.fab_pause);
        this.placeholderView = (TextView)this.findViewById(R.id.placeholder_view);
        this.placeholderContainer = this.findViewById(R.id.placeholder_container);
        this.offlineBadgeView = (TextView)this.findViewById(R.id.offline_badge);
    }

    private void setupClickListeners() {
        this.nextButton.setOnClickListener(v -> this.navigate(true));
        this.prevButton.setOnClickListener(v -> this.navigate(false));
        this.fabRecord.setOnClickListener(v -> {
            if (this.isRecording) {
                this.stopRecording();
            } else if (this.currentIndex != -1) {
                this.startRecording();
            } else {
                Toast.makeText((Context)this, (int)R.string.select_source_first, (int)0).show();
            }
        });
        this.fabPause.setOnClickListener(v -> {
            if (this.isPaused) {
                this.resumeRecording();
            } else {
                this.pauseRecording();
            }
        });
    }

    private void setupWebView() {
        this.webView.getSettings().setJavaScriptEnabled(true);
        this.webView.getSettings().setDomStorageEnabled(true);
        this.webView.getSettings().setDatabaseEnabled(true);
        this.webView.getSettings().setAllowFileAccess(true);
        this.webView.getSettings().setAllowContentAccess(true);
        this.webView.getSettings().setAllowFileAccessFromFileURLs(true);
        this.webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        this.webView.getSettings().setLoadWithOverviewMode(false);
        this.webView.getSettings().setUseWideViewPort(false);
        this.webView.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");
        this.webView.getSettings().setCacheMode(1);
        this.webView.setWebViewClient(new WebViewClient(){

            public void onPageFinished(WebView view, String url) {
                if (MainActivity.this.isLoadedFromExcel) {
                    view.clearHistory();
                }
                MainActivity.this.isLoadedFromExcel = false;
                if (!MainActivity.this.currentPageLoadedOffline && MainActivity.this.pendingOfflineSave != null) {
                    PendingOfflineSave save = MainActivity.this.pendingOfflineSave;
                    MainActivity.this.pendingOfflineSave = null;
                    MainActivity.this.scheduleOfflinePageSave(save, url);
                }
                MainActivity.this.updateOfflineBadge();
                MainActivity.this.updateBackButtonVisibility();
                MainActivity.this.updateActionBarForCurrentState();
                super.onPageFinished(view, url);
            }
        });
    }

    private boolean isUnwantedTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return false;
        }
        String lower = title.toLowerCase(Locale.US);
        return lower.contains("sex") || lower.contains("porn") || lower.contains("xxx")
                || lower.contains("adult") || lower.contains("nude") || lower.contains("abuse")
                || lower.contains("erotic") || lower.contains("hentai");
    }

    private void showSourcePicker() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_source, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        View cardExcel = dialogView.findViewById(R.id.card_source_excel);
        View cardDownloaded = dialogView.findViewById(R.id.card_source_downloaded);
        View cardAssets = dialogView.findViewById(R.id.card_source_assets);
        View cardResume = dialogView.findViewById(R.id.card_source_resume);

        if (cardExcel != null) {
            cardExcel.setOnClickListener(v -> {
                dialog.dismiss();
                this.openExcelPicker();
            });
        }
        if (cardDownloaded != null) {
            cardDownloaded.setOnClickListener(v -> {
                dialog.dismiss();
                this.openDownloadedFolderPicker();
            });
        }
        if (cardAssets != null) {
            cardAssets.setOnClickListener(v -> {
                dialog.dismiss();
                this.openAssetsFolderPicker();
            });
        }
        if (cardResume != null) {
            cardResume.setOnClickListener(v -> {
                dialog.dismiss();
                this.resumeLastSession();
            });
        }

        dialog.show();
    }

    private void openExcelPicker() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        intent.putExtra("android.intent.extra.MIME_TYPES", new String[]{"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel"});
        intent.addFlags(1);
        intent.addFlags(2);
        intent.addFlags(64);
        this.filePickerLauncher.launch(intent);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * this.getResources().getDisplayMetrics().density);
    }

    private void openDownloadedFolderPicker() {
        List<String> folders = OfflineStore.listDownloadedFolders(this);
        if (folders.isEmpty()) {
            Toast.makeText(this, R.string.no_downloaded_folders, Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder_picker, null);
        TextView titleView = dialogView.findViewById(R.id.dialog_folder_title);
        if (titleView != null) {
            titleView.setText(R.string.open_downloaded_folder);
        }

        LinearLayout container = dialogView.findViewById(R.id.folder_list_container);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        for (String folderName : folders) {
            int fileCount = OfflineStore.countHtmlFiles(this, folderName);
            String displayName = OfflineStore.prettifyFolderName(folderName);
            View cardView = this.createFolderCardView(displayName, fileCount + " offline pages cached", R.drawable.ic_cloud_sync, v -> {
                dialog.dismiss();
                this.loadDownloadedFolder(folderName, 0);
            });
            container.addView(cardView);
        }

        dialog.show();
    }

    private void openAssetsFolderPicker() {
        List<String> folders = OfflineStore.listAssetFolders(this);
        if (folders.isEmpty()) {
            Toast.makeText(this, R.string.no_assets_folders, Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_folder_picker, null);
        TextView titleView = dialogView.findViewById(R.id.dialog_folder_title);
        if (titleView != null) {
            titleView.setText(R.string.load_from_assets);
        }

        LinearLayout container = dialogView.findViewById(R.id.folder_list_container);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        for (String folderName : folders) {
            String displayName = OfflineStore.prettifyFolderName(folderName);
            View cardView = this.createFolderCardView(displayName, "Pre-packaged offline archive", R.drawable.ic_files, v -> {
                dialog.dismiss();
                this.loadAssetsFolder(folderName, 0);
            });
            container.addView(cardView);
        }

        dialog.show();
    }

    private View createFolderCardView(String title, String subtitle, int iconResId, View.OnClickListener listener) {
        com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, this.dpToPx(12));
        card.setLayoutParams(params);
        card.setRadius(this.dpToPx(16));
        card.setCardElevation(0);
        card.setStrokeColor(ContextCompat.getColor(this, R.color.card_stroke));
        card.setStrokeWidth(1);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_background));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        layout.setPadding(this.dpToPx(16), this.dpToPx(16), this.dpToPx(16), this.dpToPx(16));

        FrameLayout iconContainer = new FrameLayout(this);
        iconContainer.setLayoutParams(new LinearLayout.LayoutParams(this.dpToPx(40), this.dpToPx(40)));
        iconContainer.setBackgroundResource(R.drawable.bg_pill_counter);

        ImageView icon = new ImageView(this);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(this.dpToPx(22), this.dpToPx(22));
        iconParams.gravity = android.view.Gravity.CENTER;
        icon.setLayoutParams(iconParams);
        icon.setImageResource(iconResId);
        icon.setColorFilter(ContextCompat.getColor(this, R.color.text_primary));
        iconContainer.addView(icon);

        LinearLayout textLayout = new LinearLayout(this);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        textParams.setMarginStart(this.dpToPx(14));
        textLayout.setLayoutParams(textParams);
        textLayout.setOrientation(LinearLayout.VERTICAL);

        TextView nameText = new TextView(this);
        nameText.setText(title);
        nameText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        nameText.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        nameText.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView countText = new TextView(this);
        countText.setText(subtitle);
        countText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        countText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));

        textLayout.addView(nameText);
        textLayout.addView(countText);

        ImageView arrow = new ImageView(this);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(this.dpToPx(20), this.dpToPx(20)));
        arrow.setImageResource(R.drawable.ic_chevron_right);
        arrow.setColorFilter(ContextCompat.getColor(this, R.color.text_tertiary));

        layout.addView(iconContainer);
        layout.addView(textLayout);
        layout.addView(arrow);

        card.addView(layout);
        card.setOnClickListener(listener);

        return card;
    }

    private void loadAssetsFolder(String assetFolderName, int restoreIndex) {
        this.placeholderView.setText(R.string.loading_assets_folder);
        new Thread(() -> {
            boolean imported = OfflineStore.importAssetFolderToLocal((Context)this, assetFolderName, true);
            if (!imported) {
                this.runOnUiThread(() -> Toast.makeText((Context)this, (int)R.string.no_items_found_for_source, (int)1).show());
                return;
            }
            String localFolderName = OfflineStore.sanitizeFolderName(assetFolderName);
            this.runOnUiThread(() -> this.loadDownloadedFolder(localFolderName, restoreIndex));
        }).start();
    }

    private void resumeLastSession() {
        if (!OfflineStore.hasLastSession((Context)this)) {
            Toast.makeText((Context)this, (int)R.string.no_last_session_available, (int)1).show();
            return;
        }
        int restoreIndex = OfflineStore.getLastIndex((Context)this);
        String sourceType = OfflineStore.getLastSourceType((Context)this);
        if ("excel".equals(sourceType)) {
            Uri excelUri = OfflineStore.getLastExcelUri((Context)this);
            if (excelUri == null) {
                Toast.makeText((Context)this, (int)R.string.no_last_session_available, (int)1).show();
                return;
            }
            this.loadExcelSource(excelUri, restoreIndex);
            return;
        }
        if ("folder".equals(sourceType)) {
            String folderName = OfflineStore.getLastFolderName((Context)this);
            if (folderName == null || folderName.trim().isEmpty()) {
                Toast.makeText((Context)this, (int)R.string.no_last_session_available, (int)1).show();
                return;
            }
            this.loadDownloadedFolder(folderName, restoreIndex);
            return;
        }
        Toast.makeText((Context)this, (int)R.string.no_last_session_available, (int)1).show();
    }

    private void handleSelectedExcelFile(Intent data) {
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        this.persistDocumentPermissions(uri, data);
        this.loadExcelSource(uri, 0);
    }

    private void loadExcelSource(Uri uri, int restoreIndex) {
        this.selectedExcelUri = uri;
        this.hasExcelWriteAccess = false;
        this.isCurrentSourceExcel = true;
        this.pendingRestoreIndex = Math.max(restoreIndex, 0);
        this.currentSheetDisplayName = OfflineStore.resolveSheetDisplayName((Context)this, uri);
        this.currentSheetFolderName = OfflineStore.resolveSheetFolderName((Context)this, uri);
        this.hasExcelWriteAccess = this.hasWriteAccess(uri);
        if (!this.hasExcelWriteAccess) {
            Toast.makeText((Context)this, (int)R.string.excel_loaded_read_only, (int)1).show();
        }
        this.parseExcelFile(uri);
    }

    private void loadDownloadedFolder(String folderName, int restoreIndex) {
        this.selectedExcelUri = null;
        this.hasExcelWriteAccess = false;
        this.isCurrentSourceExcel = false;
        this.pendingRestoreIndex = Math.max(restoreIndex, 0);
        this.currentSheetFolderName = folderName;
        this.currentSheetDisplayName = OfflineStore.prettifyFolderName(folderName);
        this.currentPageLoadedOffline = false;
        this.pendingOfflineSave = null;
        this.placeholderView.setText(R.string.loading_downloaded_folder);
        new Thread(() -> {
            List<UrlData> tempList = OfflineStore.loadFolderEntries((Context)this, folderName);
            this.runOnUiThread(() -> this.applyLoadedList(tempList));
        }).start();
    }

    private void persistDocumentPermissions(Uri uri, Intent data) {
        int grantedFlags = data.getFlags() & 3;
        if (grantedFlags == 0) {
            grantedFlags = 1;
        }
        try {
            this.getContentResolver().takePersistableUriPermission(uri, grantedFlags);
        }
        catch (SecurityException e) {
            Log.w((String)TAG, (String)"Could not persist all document permissions", (Throwable)e);
            try {
                this.getContentResolver().takePersistableUriPermission(uri, 1);
            }
            catch (SecurityException inner) {
                Log.w((String)TAG, (String)"Could not persist read permission either", (Throwable)inner);
            }
        }
    }

        private boolean hasWriteAccess(Uri uri) {
        for (UriPermission permission : this.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals((Object)uri) && permission.isWritePermission()) {
                return true;
            }
        }
        try (ParcelFileDescriptor ignored = this.getContentResolver().openFileDescriptor(uri, "rw");){
            return ignored != null;
        }
        catch (Exception e) {
            return false;
        }
    }

    private void parseExcelFile(Uri uri) {
        this.placeholderView.setText(R.string.loading_excel_file);
        this.currentPageLoadedOffline = false;
        this.pendingOfflineSave = null;
        new Thread(() -> {
            try (InputStream is = this.getContentResolver().openInputStream(uri);
                 Workbook workbook = WorkbookFactory.create((InputStream)is);){
                if (workbook == null) {
                    throw new IllegalStateException("Workbook could not be opened.");
                }
                Sheet sheet = workbook.getSheetAt(0);
                Iterator rowIterator = sheet.iterator();
                ArrayList<UrlData> tempList = new ArrayList<UrlData>();
                if (rowIterator.hasNext()) {
                    rowIterator.next();
                }
                while (rowIterator.hasNext()) {
                    Row row = (Row)rowIterator.next();
                    Cell fileNameCell = row.getCell(0);
                    Cell urlCell = row.getCell(1);
                    if (fileNameCell == null || urlCell == null) continue;
                    String filename = this.dataFormatter.formatCellValue(fileNameCell).trim();
                    String url = this.dataFormatter.formatCellValue(urlCell).trim();
                    if (filename.isEmpty() || url.isEmpty()) continue;
                    if (this.isUnwantedTitle(filename)) {
                        Log.w(TAG, "Skipping unwanted/adult item from Excel: " + filename);
                        continue;
                    }
                    tempList.add(new UrlData(row.getRowNum(), filename, url));
                }
                this.backfillExistingOfflineMetadata(tempList);
                this.runOnUiThread(() -> this.applyLoadedList(tempList));
            }
            catch (Exception e) {
                Log.e((String)TAG, (String)"parseExcelFile", (Throwable)e);
                this.runOnUiThread(() -> {
                    this.placeholderView.setText(R.string.failed_to_load_file);
                    this.currentIndex = -1;
                    this.currentPageLoadedOffline = false;
                    this.updateUiForRecordingState();
                    Toast.makeText((Context)this, (int)R.string.failed_to_read_excel, (int)1).show();
                });
            }
        }).start();
    }

    private void backfillExistingOfflineMetadata(List<UrlData> items) {
        if (this.currentSheetFolderName == null || this.currentSheetFolderName.isEmpty()) {
            return;
        }
        for (UrlData data : items) {
            File offlineFile = OfflineStore.getOfflineHtmlFile((Context)this, this.currentSheetFolderName, data);
            if (!offlineFile.exists()) continue;
            OfflineStore.upsertMetadataEntry((Context)this, this.currentSheetFolderName, data);
        }
    }

    private void applyLoadedList(List<UrlData> tempList) {
        this.urlDataList.clear();
        if (tempList != null) {
            for (UrlData item : tempList) {
                if (item != null && !this.isUnwantedTitle(item.getFilename())) {
                    this.urlDataList.add(item);
                }
            }
        }
        OfflineStore.saveCurrentListInfo((Context)this, this.currentSheetDisplayName, this.currentSheetFolderName, this.urlDataList.size());
        if (!this.urlDataList.isEmpty()) {
            if (this.placeholderContainer != null) {
                this.placeholderContainer.setVisibility(8);
            }
            this.placeholderView.setVisibility(8);
            this.webView.setVisibility(0);
            int targetIndex = Math.min(Math.max(this.pendingRestoreIndex, 0), this.urlDataList.size() - 1);
            this.pendingRestoreIndex = 0;
            this.navigateToIndex(targetIndex);
        } else {
            this.currentIndex = -1;
            this.currentPageLoadedOffline = false;
            this.webView.setVisibility(8);
            if (this.placeholderContainer != null) {
                this.placeholderContainer.setVisibility(0);
            }
            this.placeholderView.setVisibility(0);
            this.placeholderView.setText(R.string.no_items_found_for_source);
            this.updateUiForRecordingState();
            Toast.makeText((Context)this, (int)R.string.no_items_found_for_source, (int)1).show();
        }
    }

    private void navigate(boolean isNext) {
        if (isNext && this.currentIndex < this.urlDataList.size() - 1) {
            this.navigateToIndex(this.currentIndex + 1);
        } else if (!isNext && this.currentIndex > 0) {
            this.navigateToIndex(this.currentIndex - 1);
        }
    }

    private void navigateToIndex(int index) {
        if (index < 0 || index >= this.urlDataList.size()) {
            return;
        }
        this.currentIndex = index;
        this.persistCurrentSession();
        this.loadCurrentUrl();
        this.updateUiForRecordingState();
    }

    private void persistCurrentSession() {
        if (this.currentIndex < 0 || this.currentIndex >= this.urlDataList.size()) {
            return;
        }
        OfflineStore.saveCurrentSession((Context)this, this.isCurrentSourceExcel ? "excel" : "folder", this.selectedExcelUri, this.currentSheetDisplayName, this.currentSheetFolderName, this.currentIndex);
    }

    private void loadCurrentUrl() {
        if (this.currentIndex < 0 || this.currentIndex >= this.urlDataList.size()) {
            return;
        }
        UrlData currentData = this.urlDataList.get(this.currentIndex);
        File offlineFile = this.getOfflineHtmlFile(currentData);
        this.isLoadedFromExcel = true;
        if (offlineFile.exists()) {
            if (offlineFile.getName().toLowerCase(Locale.US).endsWith(".mht")) {
                this.currentPageLoadedOffline = true;
                this.pendingOfflineSave = null;
                OfflineStore.upsertMetadataEntry((Context)this, this.currentSheetFolderName, currentData);
                this.webView.loadUrl(Uri.fromFile(offlineFile).toString());
            } else {
                try {
                    String offlineHtml = this.readOfflineHtml(offlineFile);
                    this.currentPageLoadedOffline = true;
                    this.pendingOfflineSave = null;
                    OfflineStore.upsertMetadataEntry((Context)this, this.currentSheetFolderName, currentData);
                    String folderBaseUrl = Uri.fromFile(offlineFile.getParentFile()).toString() + "/";
                    String historyUrl = currentData.getWebUrl() == null || currentData.getWebUrl().trim().isEmpty() ? folderBaseUrl : currentData.getWebUrl().trim();
                    this.webView.loadDataWithBaseURL(folderBaseUrl, offlineHtml, "text/html", "UTF-8", historyUrl);
                }
                catch (IOException e) {
                    Log.e((String)TAG, (String)"Failed to read offline HTML", (Throwable)e);
                    this.loadLiveUrlOrShowError(currentData);
                }
            }
        } else {
            this.loadLiveUrlOrShowError(currentData);
        }
        this.updateOfflineBadge();
    }

    private void loadLiveUrlOrShowError(UrlData currentData) {
        String liveUrl;
        String string2 = liveUrl = currentData.getWebUrl() == null ? "" : currentData.getWebUrl().trim();
        if (!liveUrl.isEmpty()) {
            this.currentPageLoadedOffline = false;
            this.pendingOfflineSave = PendingOfflineSave.from(currentData);
            this.webView.loadUrl(liveUrl);
            return;
        }
        this.currentPageLoadedOffline = false;
        this.pendingOfflineSave = null;
        this.webView.loadDataWithBaseURL("https://offline.local/", "<html><body><h3>Offline file missing</h3><p>This item was loaded from a downloaded folder, but its local HTML file is not available yet.</p></body></html>", "text/html", "UTF-8", null);
        Toast.makeText((Context)this, (int)R.string.offline_file_missing, (int)1).show();
    }

    private void scheduleOfflinePageSave(PendingOfflineSave save, String baseUrl) {
        if (save == null) {
            return;
        }

        String prepJs = "(function() {" +
                "  try {" +
                "    var imgs = document.querySelectorAll('img');" +
                "    for (var i = 0; i < imgs.length; i++) {" +
                "      var img = imgs[i];" +
                "      var lazyUrl = img.getAttribute('data-src') || img.getAttribute('data-original') || img.getAttribute('data-lazy-src') || img.getAttribute('data-url');" +
                "      if (lazyUrl && !img.src) { img.src = lazyUrl; }" +
                "    }" +
                "  } catch(e){}" +
                "})();";
        this.webView.evaluateJavascript(prepJs, null);

        this.handler.postDelayed(() -> {
            if (this.isDestroyed() || this.isFinishing()) {
                return;
            }
            this.saveCurrentPageOffline(save, baseUrl);
        }, 1500L);
    }

    private void saveCurrentPageOffline(PendingOfflineSave save, String baseUrl) {
        if (this.currentSheetFolderName == null || this.currentSheetFolderName.isEmpty() || save == null) {
            return;
        }
        File folderDirectory = OfflineStore.getFolderDirectory((Context)this, this.currentSheetFolderName);
        if (!folderDirectory.exists() && !folderDirectory.mkdirs()) {
            Log.w((String)TAG, (String)"Could not create offline cache directory");
            return;
        }
        String mhtFileName = this.ensureMhtFileName(save.offlineFileName, save.originalUrl);
        File mhtFile = new File(folderDirectory, mhtFileName);
        this.webView.saveWebArchive(mhtFile.getAbsolutePath(), false, savedPath -> {
            if (savedPath != null && !savedPath.trim().isEmpty() && new File(savedPath).length() > 1024) {
                if (save.targetData != null) {
                    save.targetData.setOfflineFileName(mhtFileName);
                }
                OfflineStore.upsertMetadataEntry((Context)this, this.currentSheetFolderName, mhtFileName, save.originalUrl, save.title, save.rowIndex);
                this.runOnUiThread(() -> {
                    if (this.currentIndex >= 0 && this.currentIndex < this.urlDataList.size() && this.urlDataList.get(this.currentIndex) == save.targetData) {
                        this.currentPageLoadedOffline = true;
                        this.updateOfflineBadge();
                    }
                });
                return;
            }
            Log.w((String)TAG, (String)"saveWebArchive failed or incomplete, falling back to complete HTML package snapshot");
            this.fallbackCaptureAndSave(save, baseUrl);
        });
    }

    private void fallbackCaptureAndSave(PendingOfflineSave save, String baseUrl) {
        this.webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();", value -> {
            String html = this.decodeJavascriptString((String)value);
            if (html != null && !html.trim().isEmpty()) {
                String htmlFileName = this.ensureHtmlFileName(save.offlineFileName);
                new Thread(() -> this.writeOfflineHtmlSnapshot(save, baseUrl, htmlFileName, html)).start();
            }
        });
    }

    private void writeOfflineHtmlSnapshot(PendingOfflineSave save, String baseUrl, String offlineFileName, String html) {
        try {
            File offlineFile = new File(OfflineStore.getFolderDirectory((Context)this, this.currentSheetFolderName), offlineFileName);
            File parent = offlineFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w((String)TAG, (String)"Could not create offline cache directory");
                return;
            }
            String enrichedHtml = this.inlineCompleteHtmlPackage(html, baseUrl);
            try (FileOutputStream outputStream = new FileOutputStream(offlineFile);){
                outputStream.write(enrichedHtml.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
            if (save.targetData != null) {
                save.targetData.setOfflineFileName(offlineFileName);
            }
            OfflineStore.upsertMetadataEntry((Context)this, this.currentSheetFolderName, offlineFileName, save.originalUrl, save.title, save.rowIndex);
            this.runOnUiThread(() -> {
                if (this.currentIndex >= 0 && this.currentIndex < this.urlDataList.size() && this.urlDataList.get(this.currentIndex) == save.targetData) {
                    this.currentPageLoadedOffline = true;
                    this.updateOfflineBadge();
                }
            });
        }
        catch (Exception e) {
            Log.e((String)TAG, (String)"writeOfflineHtmlSnapshot", (Throwable)e);
        }
    }

    private String inlineCompleteHtmlPackage(String html, String baseUrl) {
        if (html == null || html.trim().isEmpty()) {
            return html;
        }

        String enriched = this.injectBaseHref(html, baseUrl);
        enriched = this.inlineHtmlImages(enriched, baseUrl);
        enriched = this.inlineExternalStylesheets(enriched, baseUrl);
        enriched = this.inlineExternalScripts(enriched, baseUrl);
        return enriched;
    }

    private String inlineHtmlImages(String html, String baseUrl) {
        if (html == null) return "";
        String result = html;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(?:src|data-src)=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(html);
        java.util.Set<String> urls = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String src = matcher.group(1);
            if (src != null && !src.trim().isEmpty() && !src.startsWith("data:")) {
                urls.add(src.trim());
            }
        }

        int maxImageBytes = 3 * 1024 * 1024;
        int totalBytes = 0;
        int maxTotalBytes = 25 * 1024 * 1024;

        for (String relativeOrAbsoluteUrl : urls) {
            String absoluteUrl = resolveAbsoluteUrl(baseUrl, relativeOrAbsoluteUrl);
            DownloadedResource resource = this.downloadResourceData(absoluteUrl, maxImageBytes);
            if (resource != null && resource.bytes != null && resource.bytes.length > 0) {
                totalBytes += resource.bytes.length;
                if (totalBytes > maxTotalBytes) {
                    break;
                }
                String dataUri = "data:" + resource.mimeType + ";base64," + Base64.encodeToString(resource.bytes, Base64.NO_WRAP);
                result = result.replace(relativeOrAbsoluteUrl, dataUri);
            }
        }
        return result;
    }

    private String inlineExternalStylesheets(String html, String baseUrl) {
        if (html == null) return "";
        String result = html;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<link[^>]+href=[\"']([^\"']+)[\"'][^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(html);
        java.util.Set<String> cssUrls = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String fullTag = matcher.group(0);
            if (fullTag.toLowerCase(Locale.US).contains("stylesheet")) {
                String href = matcher.group(1);
                if (href != null && !href.trim().isEmpty() && !href.startsWith("data:")) {
                    cssUrls.add(href.trim());
                }
            }
        }

        int maxCssBytes = 2 * 1024 * 1024;
        for (String relativeOrAbsoluteUrl : cssUrls) {
            String absoluteUrl = resolveAbsoluteUrl(baseUrl, relativeOrAbsoluteUrl);
            DownloadedResource resource = this.downloadResourceData(absoluteUrl, maxCssBytes);
            if (resource != null && resource.bytes != null && resource.bytes.length > 0) {
                String cssText = new String(resource.bytes, StandardCharsets.UTF_8);
                cssText = rewriteCssUrls(cssText, absoluteUrl);
                String styleTag = "<style>\n" + cssText + "\n</style>";
                java.util.regex.Pattern linkTagPattern = java.util.regex.Pattern.compile("<link[^>]+href=[\"']" + java.util.regex.Pattern.quote(relativeOrAbsoluteUrl) + "[\"'][^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);
                result = linkTagPattern.matcher(result).replaceAll(java.util.regex.Matcher.quoteReplacement(styleTag));
            }
        }
        return result;
    }

    private String rewriteCssUrls(String cssText, String cssBaseUrl) {
        if (cssText == null) return "";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("url\\(([^)]+)\\)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(cssText);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String rawUrl = matcher.group(1).trim().replaceAll("^[\"']|[\"']$", "");
            if (rawUrl.isEmpty() || rawUrl.startsWith("data:") || rawUrl.startsWith("#")) {
                continue;
            }
            String absoluteUrl = resolveAbsoluteUrl(cssBaseUrl, rawUrl);
            matcher.appendReplacement(sb, "url(\"" + java.util.regex.Matcher.quoteReplacement(absoluteUrl) + "\")");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String inlineExternalScripts(String html, String baseUrl) {
        if (html == null) return "";
        String result = html;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<script[^>]+src=[\"']([^\"']+)[\"'][^>]*>\\s*</script>", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(html);
        java.util.Set<String> scriptUrls = new java.util.LinkedHashSet<>();
        while (matcher.find()) {
            String src = matcher.group(1);
            if (src != null && !src.trim().isEmpty() && !src.startsWith("data:")) {
                scriptUrls.add(src.trim());
            }
        }

        int maxJsBytes = 2 * 1024 * 1024;
        for (String relativeOrAbsoluteUrl : scriptUrls) {
            String absoluteUrl = resolveAbsoluteUrl(baseUrl, relativeOrAbsoluteUrl);
            DownloadedResource resource = this.downloadResourceData(absoluteUrl, maxJsBytes);
            if (resource != null && resource.bytes != null && resource.bytes.length > 0) {
                String jsText = new String(resource.bytes, StandardCharsets.UTF_8);
                String scriptTag = "<script>\n" + jsText + "\n</script>";
                java.util.regex.Pattern scriptTagPattern = java.util.regex.Pattern.compile("<script[^>]+src=[\"']" + java.util.regex.Pattern.quote(relativeOrAbsoluteUrl) + "[\"'][^>]*>\\s*</script>", java.util.regex.Pattern.CASE_INSENSITIVE);
                result = scriptTagPattern.matcher(result).replaceAll(java.util.regex.Matcher.quoteReplacement(scriptTag));
            }
        }
        return result;
    }

    private String resolveAbsoluteUrl(String baseUrl, String candidateUrl) {
        if (candidateUrl == null || candidateUrl.trim().isEmpty()) {
            return "";
        }
        String clean = candidateUrl.trim();
        if (clean.startsWith("http://") || clean.startsWith("https://") || clean.startsWith("data:")) {
            return clean;
        }
        try {
            URL base = new URL(baseUrl);
            return new URL(base, clean).toString();
        } catch (Exception e) {
            return clean;
        }
    }

    private DownloadedResource downloadResourceData(String urlString, int maxBytes) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) WebRecorder");
            connection.connect();
            String contentType = connection.getContentType();
            try (InputStream inputStream = connection.getInputStream();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()){
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    total += read;
                    if (total > maxBytes) {
                        return null;
                    }
                    outputStream.write(buffer, 0, read);
                }
                if (total == 0) {
                    return null;
                }
                String mimeType = this.resolveMimeTypeFromResponse(urlString, contentType);
                return new DownloadedResource(outputStream.toByteArray(), mimeType);
            }
        }
        catch (Exception e) {
            Log.w((String)TAG, (String)("Failed to download resource: " + urlString), (Throwable)e);
            return null;
        }
        finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String resolveMimeTypeFromResponse(String url, String contentType) {
        if (contentType != null) {
            String clean = contentType.toLowerCase(Locale.US).trim();
            int semicolon = clean.indexOf(';');
            if (semicolon > 0) {
                clean = clean.substring(0, semicolon).trim();
            }
            if (clean.startsWith("image/") || clean.startsWith("text/") || clean.startsWith("font/") || clean.startsWith("application/")) {
                return clean;
            }
        }
        return this.resolveMimeTypeFromUrl(url);
    }

    private String resolveMimeTypeFromUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (lower.endsWith(".woff")) {
            return "font/woff";
        }
        if (lower.endsWith(".ttf")) {
            return "font/ttf";
        }
        return "image/png";
    }

    private String decodeJavascriptString(String value) {
        try {
            JSONTokener tokener = new JSONTokener(value);
            Object decoded = tokener.nextValue();
            if (decoded instanceof String) {
                return (String)decoded;
            }
        }
        catch (Exception e) {
            Log.w((String)TAG, (String)"Failed to decode WebView HTML", (Throwable)e);
        }
        return null;
    }

    private String ensureMhtFileName(String offlineFileName, String originalUrl) {
        String target = offlineFileName == null ? "" : offlineFileName.trim();
        if (target.isEmpty()) {
            return OfflineStore.buildOfflineFileName(originalUrl);
        }
        String lower = target.toLowerCase(Locale.US);
        if (lower.endsWith(".mht")) {
            return target;
        }
        if (lower.endsWith(".html")) {
            return target.substring(0, target.length() - 5) + ".mht";
        }
        return target + ".mht";
    }

    private String ensureHtmlFileName(String offlineFileName) {
        String target = offlineFileName == null ? "" : offlineFileName.trim();
        if (target.isEmpty()) {
            return "offline_snapshot.html";
        }
        String lower = target.toLowerCase(Locale.US);
        if (lower.endsWith(".html")) {
            return target;
        }
        if (lower.endsWith(".mht")) {
            return target.substring(0, target.length() - 4) + ".html";
        }
        return target + ".html";
    }

    private String injectBaseHref(String html, String baseUrl) {
        int htmlEnd;
        int headEnd;
        String safeBaseUrl = this.escapeHtmlAttribute(baseUrl);
        String baseTag = "<base href=\"" + safeBaseUrl + "\" />";
        String lowerHtml = html.toLowerCase(Locale.ROOT);
        int headStart = lowerHtml.indexOf("<head");
        if (headStart >= 0 && (headEnd = html.indexOf(62, headStart)) >= 0) {
            return html.substring(0, headEnd + 1) + baseTag + html.substring(headEnd + 1);
        }
        int htmlStart = lowerHtml.indexOf("<html");
        if (htmlStart >= 0 && (htmlEnd = html.indexOf(62, htmlStart)) >= 0) {
            return html.substring(0, htmlEnd + 1) + "<head>" + baseTag + "</head>" + html.substring(htmlEnd + 1);
        }
        return "<html><head>" + baseTag + "</head><body>" + html + "</body></html>";
    }

    private String escapeHtmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String readOfflineHtml(File offlineFile) throws IOException {
        byte[] bytes = Files.readAllBytes(offlineFile.toPath());
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private File getOfflineHtmlFile(UrlData data) {
        return OfflineStore.getOfflineHtmlFile((Context)this, this.currentSheetFolderName, data);
    }

    private String resolveBaseUrl(UrlData data) {
        String url;
        String string2 = url = data.getWebUrl() == null ? "" : data.getWebUrl().trim();
        if (!url.isEmpty()) {
            return url;
        }
        return "https://offline.local/" + OfflineStore.resolveOfflineFileName(data);
    }

    private void handleBackNavigation() {
        if (this.isRecording) {
            this.moveTaskToBack(true);
            Toast.makeText((Context)this, (int)R.string.recording_continues_in_background, (int)0).show();
            return;
        }
        if (this.webView.canGoBack()) {
            this.webView.goBack();
            return;
        }
        if (this.currentIndex > 0) {
            this.navigateToIndex(this.currentIndex - 1);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastBackPressedAt <= 1500L) {
            this.finish();
            return;
        }
        this.lastBackPressedAt = now;
        Toast.makeText((Context)this, (int)R.string.press_back_again_to_exit, (int)0).show();
    }

    private void updateBackButtonVisibility() {
        ActionBar actionBar = this.getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        boolean showBack = !this.isRecording && (this.webView.canGoBack() || this.currentIndex > 0);
        actionBar.setDisplayHomeAsUpEnabled(showBack);
    }

    private void startRecording() {
        if (ContextCompat.checkSelfPermission((Context)this, (String)"android.permission.RECORD_AUDIO") != 0) {
            ActivityCompat.requestPermissions((Activity)this, (String[])new String[]{"android.permission.RECORD_AUDIO"}, (int)102);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission((Context)this, (String)"android.permission.POST_NOTIFICATIONS") != 0) {
            ActivityCompat.requestPermissions((Activity)this, (String[])new String[]{"android.permission.POST_NOTIFICATIONS"}, (int)103);
            return;
        }
        this.launchMediaProjectionRequest();
    }

    private void launchMediaProjectionRequest() {
        if (this.mediaProjectionManager == null) {
            Toast.makeText((Context)this, (CharSequence)"Screen capture service is not available on this device.", (int)1).show();
            return;
        }
        this.startActivityForResult(this.mediaProjectionManager.createScreenCaptureIntent(), 101);
    }

    private void stopRecording() {
        this.isStartingRecording = false;
        this.stopService(new Intent((Context)this, RecordingService.class));
        this.updateUiForRecordingState();
    }

    private void pauseRecording() {
        Intent intent = new Intent((Context)this, RecordingService.class);
        intent.setAction("com.jdpublication.webrecorder.PAUSE");
        this.startService(intent);
    }

    private void resumeRecording() {
        Intent intent = new Intent((Context)this, RecordingService.class);
        intent.setAction("com.jdpublication.webrecorder.RESUME");
        this.startService(intent);
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 101) {
            if (resultCode == -1 && data != null && this.currentIndex != -1) {
                Intent serviceIntent = new Intent((Context)this, RecordingService.class);
                serviceIntent.putExtra("resultCode", resultCode);
                serviceIntent.putExtra("data", (Parcelable)data);
                serviceIntent.putExtra("filename", this.urlDataList.get(this.currentIndex).getFilename());
                this.isStartingRecording = true;
                this.updateUiForRecordingState();
                ContextCompat.startForegroundService((Context)this, (Intent)serviceIntent);
            } else {
                this.isStartingRecording = false;
                this.updateUiForRecordingState();
            }
        }
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 102) {
            if (grantResults.length > 0 && grantResults[0] == 0) {
                this.startRecording();
            } else {
                Toast.makeText((Context)this, (CharSequence)"Audio permission is required.", (int)1).show();
            }
            return;
        }
        if (requestCode == 103) {
            this.launchMediaProjectionRequest();
        }
    }

    private void showEditCurrentEntryDialog() {
        if (this.currentIndex < 0 || this.currentIndex >= this.urlDataList.size()) {
            return;
        }
        if (this.isCurrentSourceExcel && (this.selectedExcelUri == null || !this.hasExcelWriteAccess)) {
            Toast.makeText((Context)this, (int)R.string.select_excel_to_edit, (int)1).show();
            this.openExcelPicker();
            return;
        }
        UrlData currentData = this.urlDataList.get(this.currentIndex);
        View dialogView = LayoutInflater.from((Context)this).inflate(R.layout.dialog_edit_entry, null);
        EditText filenameInput = (EditText)dialogView.findViewById(R.id.edit_filename);
        EditText urlInput = (EditText)dialogView.findViewById(R.id.edit_url);
        filenameInput.setText((CharSequence)currentData.getFilename());
        urlInput.setText((CharSequence)currentData.getWebUrl());
        filenameInput.setSelection(filenameInput.getText().length());
        AlertDialog dialog = new AlertDialog.Builder((Context)this).setTitle(R.string.edit_current_entry).setView(dialogView).setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save_changes, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            String newFilename = filenameInput.getText().toString().trim();
            String newUrl = urlInput.getText().toString().trim();
            if (newFilename.isEmpty() || newUrl.isEmpty()) {
                Toast.makeText((Context)this, (int)R.string.invalid_entry_values, (int)1).show();
                return;
            }
            String previousOfflineFileName = OfflineStore.resolveOfflineFileName(currentData);
            boolean urlChanged = !newUrl.equals(currentData.getWebUrl());
            currentData.setFilename(newFilename);
            currentData.setWebUrl(newUrl);
            if (urlChanged) {
                currentData.setOfflineFileName(OfflineStore.buildOfflineFileName(newUrl));
            } else if (currentData.getOfflineFileName() == null || currentData.getOfflineFileName().trim().isEmpty()) {
                currentData.setOfflineFileName(previousOfflineFileName);
            }
            this.updateActionBarForCurrentState();
            if (this.isCurrentSourceExcel) {
                this.saveCurrentEntryToExcel(currentData, previousOfflineFileName, urlChanged);
            } else {
                this.saveCurrentEntryLocally(currentData, previousOfflineFileName, urlChanged);
            }
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void saveCurrentEntryToExcel(UrlData updatedData, String previousOfflineFileName, boolean reloadWebPage) {
        if (this.selectedExcelUri == null) {
            Toast.makeText((Context)this, (int)R.string.select_excel_to_edit, (int)1).show();
            return;
        }
        new Thread(() -> {
            boolean saveSucceeded = false;
            try (InputStream is = this.getContentResolver().openInputStream(this.selectedExcelUri);
                 Workbook workbook = WorkbookFactory.create((InputStream)is);){
                Cell filenameCell;
                Sheet sheet = workbook.getSheetAt(0);
                Row row = sheet.getRow(updatedData.getRowIndex());
                if (row == null) {
                    row = sheet.createRow(updatedData.getRowIndex());
                }
                if ((filenameCell = row.getCell(0)) == null) {
                    filenameCell = row.createCell(0);
                }
                filenameCell.setCellValue(updatedData.getFilename());
                Cell urlCell = row.getCell(1);
                if (urlCell == null) {
                    urlCell = row.createCell(1);
                }
                urlCell.setCellValue(updatedData.getWebUrl());
                try (ParcelFileDescriptor pfd = this.getContentResolver().openFileDescriptor(this.selectedExcelUri, "rwt");
                     FileOutputStream outputStream = new FileOutputStream(pfd.getFileDescriptor());){
                    workbook.write((OutputStream)outputStream);
                    outputStream.flush();
                    saveSucceeded = true;
                }
            }
            catch (Exception e) {
                Log.e((String)TAG, (String)"saveCurrentEntryToExcel", (Throwable)e);
            }
            boolean finalSaveSucceeded = saveSucceeded;
            this.runOnUiThread(() -> {
                if (finalSaveSucceeded) {
                    this.updateLocalEntryMetadata(updatedData, previousOfflineFileName, reloadWebPage);
                    Toast.makeText((Context)this, (int)R.string.excel_changes_saved, (int)0).show();
                    if (reloadWebPage) {
                        this.loadCurrentUrl();
                    } else {
                        this.updateUiForRecordingState();
                    }
                } else {
                    Toast.makeText((Context)this, (int)R.string.excel_changes_failed, (int)1).show();
                }
            });
        }).start();
    }

    private void saveCurrentEntryLocally(UrlData updatedData, String previousOfflineFileName, boolean reloadWebPage) {
        new Thread(() -> {
            this.updateLocalEntryMetadata(updatedData, previousOfflineFileName, reloadWebPage);
            this.runOnUiThread(() -> {
                Toast.makeText((Context)this, (int)R.string.local_changes_saved, (int)0).show();
                if (reloadWebPage) {
                    this.loadCurrentUrl();
                } else {
                    this.updateUiForRecordingState();
                }
            });
        }).start();
    }

    private void updateLocalEntryMetadata(UrlData updatedData, String previousOfflineFileName, boolean urlChanged) {
        if (this.currentSheetFolderName == null || this.currentSheetFolderName.isEmpty()) {
            return;
        }
        String nextOfflineFileName = OfflineStore.resolveOfflineFileName(updatedData);
        if (urlChanged && previousOfflineFileName != null && !previousOfflineFileName.equals(nextOfflineFileName)) {
            this.deleteOfflineEntry(previousOfflineFileName);
            OfflineStore.queuePendingDeletion((Context)this, this.currentSheetFolderName, previousOfflineFileName);
        }
        OfflineStore.upsertMetadataEntry((Context)this, this.currentSheetFolderName, updatedData);
    }

    private void deleteOfflineEntry(String offlineFileName) {
        if (offlineFileName == null || offlineFileName.trim().isEmpty()) {
            return;
        }
        File file = new File(OfflineStore.getFolderDirectory((Context)this, this.currentSheetFolderName), offlineFileName);
        if (file.exists() && !file.delete()) {
            Log.w((String)TAG, (String)("Could not delete stale offline file: " + offlineFileName));
        }
        OfflineStore.removeMetadataEntry((Context)this, this.currentSheetFolderName, offlineFileName);
    }

    private void updateNavigationButtons() {
        boolean hasSelection = this.currentIndex != -1 && !this.urlDataList.isEmpty();
        View navPanel = this.findViewById(R.id.navigation_controls);
        if (navPanel != null) {
            navPanel.setVisibility(hasSelection ? 0 : 8);
        }
        this.prevButton.setEnabled(!this.isRecording && !this.isStartingRecording && this.currentIndex > 0);
        this.nextButton.setEnabled(!this.isRecording && !this.isStartingRecording && hasSelection && this.currentIndex < this.urlDataList.size() - 1);
        this.fabRecord.setEnabled(hasSelection && !this.isStartingRecording);
        this.invalidateOptionsMenu();
    }

    private void updateActionBarForCurrentState() {
        ActionBar actionBar = this.getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        if (this.currentIndex >= 0 && this.currentIndex < this.urlDataList.size()) {
            UrlData data = this.urlDataList.get(this.currentIndex);
            actionBar.setTitle((CharSequence)data.getFilename());
            if (this.isStartingRecording) {
                actionBar.setSubtitle((CharSequence)this.getString(R.string.recording_starting));
            } else if (this.isRecording) {
                String timerText = this.formatElapsedTime(this.currentSec);
                actionBar.setSubtitle((CharSequence)(this.isPaused ? timerText + " (Paused)" : timerText));
            } else {
                actionBar.setSubtitle((CharSequence)("(" + (this.currentIndex + 1) + "/" + this.urlDataList.size() + ")"));
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
        this.isRecording = false;
        this.isPaused = false;
        this.handler.removeCallbacks(this.runnable);
        this.updateUiForRecordingState();
    }

    private void updateUiForRecordingState() {
        boolean hideNavigation = this.isRecording || this.isStartingRecording || this.currentIndex == -1 || this.urlDataList.isEmpty();
        View navigationControls = this.findViewById(R.id.navigation_controls);
        if (navigationControls != null) {
            navigationControls.setVisibility(hideNavigation ? 8 : 0);
        }
        this.fabRecord.setImageResource(this.isRecording ? R.drawable.ic_stop : R.drawable.ic_record);
        this.fabPause.setVisibility(this.isRecording ? 0 : 8);
        this.fabPause.setImageResource(this.isPaused ? R.drawable.ic_play : R.drawable.ic_pause);
        this.fabPause.setEnabled(this.isRecording);
        this.updateNavigationButtons();
        this.updateBackButtonVisibility();
        this.updateActionBarForCurrentState();
        this.updateOfflineBadge();
    }

    private void updateOfflineBadge() {
        if (this.offlineBadgeView == null) {
            return;
        }
        boolean showBadge = this.currentPageLoadedOffline && this.webView.getVisibility() == 0;
        this.offlineBadgeView.setVisibility(showBadge ? 0 : 8);
    }

    public boolean onCreateOptionsMenu(Menu menu2) {
        this.getMenuInflater().inflate(R.menu.main_menu, menu2);
        return true;
    }

    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean hasSelection = this.currentIndex >= 0 && this.currentIndex < this.urlDataList.size();
        MenuItem editItem = menu.findItem(R.id.action_edit_current);
        MenuItem deleteItem = menu.findItem(R.id.action_delete_entry);
        if (editItem != null) {
            editItem.setVisible(hasSelection);
        }
        if (deleteItem != null) {
            deleteItem.setVisible(hasSelection);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 16908332) {
            this.handleBackNavigation();
            return true;
        }
        if (item.getItemId() == R.id.action_select_file) {
            this.showSourcePicker();
            return true;
        }
        if (item.getItemId() == R.id.action_edit_current) {
            this.showEditCurrentEntryDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_delete_entry) {
            this.confirmAndDeleteCurrentEntry();
            return true;
        }
        if (item.getItemId() == R.id.action_sync) {
            this.startActivity(new Intent((Context)this, SyncActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmAndDeleteCurrentEntry() {
        if (this.currentIndex < 0 || this.currentIndex >= this.urlDataList.size()) {
            Toast.makeText((Context)this, (int)R.string.select_source_first, (int)0).show();
            return;
        }

        UrlData currentData = this.urlDataList.get(this.currentIndex);
        String title = currentData.getFilename() == null ? "" : currentData.getFilename();
        String message = String.format(Locale.getDefault(), this.getString(R.string.confirm_delete_msg), title);

        boolean hasSensitiveKeyword = title.toLowerCase(Locale.US).contains("sex");
        if (hasSensitiveKeyword) {
            message = "⚠️ Sensitive keyword detected in title!\n\n" + message;
        }

        new AlertDialog.Builder((Context)this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage((CharSequence)message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_entry, (dialog, which) -> this.performEntryDeletion(currentData))
                .show();
    }

    private void performEntryDeletion(UrlData targetData) {
        if (targetData == null) {
            return;
        }

        String offlineFileName = OfflineStore.resolveOfflineFileName(targetData);
        int targetRowIndex = targetData.getRowIndex();

        // 1. Delete local file and remove from local metadata
        this.deleteOfflineEntry(offlineFileName);

        // 2. Queue for server deletion
        if (this.currentSheetFolderName != null && !this.currentSheetFolderName.isEmpty()) {
            OfflineStore.queuePendingDeletion((Context)this, this.currentSheetFolderName, offlineFileName);
            this.postImmediateServerDelete(this.currentSheetFolderName, offlineFileName, targetData.getFilename());
        }

        // 3. Update Excel file if write access is enabled
        if (this.isCurrentSourceExcel && targetRowIndex >= 0) {
            this.removeExcelRow(targetRowIndex);
        }

        // 4. Update memory list & UI
        this.urlDataList.remove(targetData);
        Toast.makeText((Context)this, (int)R.string.entry_deleted, (int)0).show();

        if (this.urlDataList.isEmpty()) {
            this.currentIndex = -1;
            this.currentPageLoadedOffline = false;
            this.webView.setVisibility(8);
            if (this.placeholderContainer != null) {
                this.placeholderContainer.setVisibility(0);
            }
            this.placeholderView.setVisibility(0);
            this.placeholderView.setText(R.string.no_items_found_for_source);
            this.updateUiForRecordingState();
        } else {
            int newIndex = Math.min(Math.max(this.currentIndex, 0), this.urlDataList.size() - 1);
            this.navigateToIndex(newIndex);
        }
    }

    private void removeExcelRow(int rowIndex) {
        if (this.selectedExcelUri == null || !this.hasExcelWriteAccess) {
            return;
        }
        new Thread(() -> {
            try (InputStream is = this.getContentResolver().openInputStream(this.selectedExcelUri);
                 Workbook workbook = WorkbookFactory.create(is)) {
                Sheet sheet = workbook.getSheetAt(0);
                Row row = sheet.getRow(rowIndex);
                if (row != null) {
                    sheet.removeRow(row);
                }
                try (OutputStream os = this.getContentResolver().openOutputStream(this.selectedExcelUri, "w")) {
                    workbook.write(os);
                }
            } catch (Exception e) {
                Log.e(TAG, "removeExcelRow", e);
            }
        }).start();
    }

    private void postImmediateServerDelete(String folderName, String offlineFileName, String title) {
        String serverUrl = OfflineStore.getServerBaseUrl(this);
        if (serverUrl.isEmpty() || folderName.isEmpty() || offlineFileName.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                URL url = new URL(serverUrl + "/sync_api.php");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                String postData = "action=delete&folder=" + URLEncoder.encode(folderName, "UTF-8")
                        + "&file=" + URLEncoder.encode(offlineFileName, "UTF-8")
                        + "&reason=moderated";
                try (OutputStream os = connection.getOutputStream()) {
                    os.write(postData.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                connection.getResponseCode();
            } catch (Exception e) {
                Log.w(TAG, "postImmediateServerDelete", e);
            }
        }).start();
    }


    private static final class DownloadedResource {
        final byte[] bytes;
        final String mimeType;

        DownloadedResource(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }
    }
    private static final class PendingOfflineSave {
        final String offlineFileName;
        final String originalUrl;
        final String title;
        final int rowIndex;
        final UrlData targetData;

        PendingOfflineSave(String offlineFileName, String originalUrl, String title, int rowIndex, UrlData targetData) {
            this.offlineFileName = offlineFileName;
            this.originalUrl = originalUrl;
            this.title = title;
            this.rowIndex = rowIndex;
            this.targetData = targetData;
        }

        static PendingOfflineSave from(UrlData data) {
            return new PendingOfflineSave(OfflineStore.resolveOfflineFileName(data), data.getWebUrl(), data.getFilename(), data.getRowIndex(), data);
        }
    }
}
