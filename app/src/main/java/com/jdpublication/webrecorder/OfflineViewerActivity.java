package com.jdpublication.webrecorder;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class OfflineViewerActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FOLDER_NAME = "folder_name";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_ROW_INDEX = "row_index";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView textUrl;
    private TextView textMeta;
    private Button buttonOpenInRecorder;

    private String filePath = "";
    private String folderName = "";
    private String pageTitle = "";
    private String originalUrl = "";
    private int rowIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_viewer);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        webView = findViewById(R.id.web_view_offline);
        progressBar = findViewById(R.id.viewer_progress_bar);
        textUrl = findViewById(R.id.text_viewer_url);
        textMeta = findViewById(R.id.text_viewer_meta);
        buttonOpenInRecorder = findViewById(R.id.button_open_in_recorder);

        handleIntent(getIntent());
        setupWebView();
        loadFile();

        buttonOpenInRecorder.setOnClickListener(v -> openInMainRecorder());
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        filePath = intent.getStringExtra(EXTRA_FILE_PATH);
        if (filePath == null || filePath.isEmpty()) {
            filePath = intent.getStringExtra("extra_file_path");
        }
        if ((filePath == null || filePath.isEmpty()) && intent.getData() != null) {
            filePath = intent.getData().getPath();
        }

        folderName = intent.getStringExtra(EXTRA_FOLDER_NAME);
        if (folderName == null || folderName.isEmpty()) {
            folderName = intent.getStringExtra("extra_folder_name");
        }

        pageTitle = intent.getStringExtra(EXTRA_TITLE);
        if (pageTitle == null || pageTitle.isEmpty()) {
            pageTitle = intent.getStringExtra("extra_title");
        }

        originalUrl = intent.getStringExtra(EXTRA_URL);
        if (originalUrl == null || originalUrl.isEmpty()) {
            originalUrl = intent.getStringExtra("extra_url");
        }

        rowIndex = intent.getIntExtra(EXTRA_ROW_INDEX, intent.getIntExtra("extra_row_index", -1));

        if (filePath != null && !filePath.isEmpty()) {
            File f = new File(filePath);
            if (folderName == null || folderName.isEmpty() || "default_list".equals(folderName)) {
                File parent = f.getParentFile();
                if (parent != null) {
                    folderName = parent.getName();
                }
            }
            if (pageTitle == null || pageTitle.isEmpty() || "Offline Page".equals(pageTitle)) {
                pageTitle = f.getName();
            }
        }

        if (folderName == null) folderName = "default_list";
        if (pageTitle == null || pageTitle.isEmpty()) pageTitle = "Offline Page";
        if (originalUrl == null) originalUrl = "";

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(pageTitle);
            getSupportActionBar().setSubtitle("Folder: " + OfflineStore.prettifyFolderName(folderName));
        }

        textUrl.setText(originalUrl.isEmpty() ? "Local Offline Document" : originalUrl);

        if (filePath != null && !filePath.isEmpty()) {
            File f = new File(filePath);
            if (f.exists()) {
                OfflineStore.ValidationResult val = OfflineStore.validateOfflineFile(f);
                String status = val.isValid() ? "Valid" : (val.isWarning() ? "Warning" : "Corrupted");
                textMeta.setText("Size: " + OfflineStore.formatFileSize(f.length()) + " | Status: " + status + " (" + val.reason + ")");
            } else {
                textMeta.setText("File not found on disk");
            }
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // OFFLINE MODE: Block all network requests to prevent timeouts
        // and ensure instant loading from local files only
        settings.setBlockNetworkLoads(true);
        settings.setBlockNetworkImage(true);
        settings.setCacheMode(WebSettings.LOAD_CACHE_ONLY);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // Silently ignore network errors for external resources — we're offline
                if (failingUrl != null && !failingUrl.startsWith("file://") && !failingUrl.startsWith("data:")) {
                    return; // Suppress errors for external URLs that can't load offline
                }
                super.onReceivedError(view, errorCode, description, failingUrl);
            }
        });
    }

    private void loadFile() {
        if (filePath == null || filePath.isEmpty()) {
            String emptyHtml = "<html><body style='font-family:sans-serif;text-align:center;padding:40px;color:#333;'>"
                    + "<h2 style='color:#e53935;'>No Document Path Provided</h2>"
                    + "<p>Please select an offline page from the <b>Offline Manager</b>.</p>"
                    + "</body></html>";
            webView.loadDataWithBaseURL(null, emptyHtml, "text/html", "UTF-8", null);
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            String notFoundHtml = "<html><body style='font-family:sans-serif;text-align:center;padding:40px;color:#333;'>"
                    + "<h2 style='color:#e53935;'>File Not Found on Disk</h2>"
                    + "<p>Expected: <br><code>" + file.getAbsolutePath() + "</code></p>"
                    + "</body></html>";
            webView.loadDataWithBaseURL(null, notFoundHtml, "text/html", "UTF-8", null);
            return;
        }

        if (OfflineStore.isTrueMhtmlArchive(file)) {
            // True MHTML: WebView handles these natively with embedded resources
            webView.loadUrl("file://" + file.getAbsolutePath());
        } else {
            progressBar.setVisibility(View.VISIBLE);
            new Thread(() -> {
                String html = null;
                try {
                    html = OfflineStore.readOfflineHtml(file);
                    // Fallback CSS keeps pages readable when external stylesheets cannot load offline
                    html = injectOfflineFallbackCss(html);
                    // Strip external link/script refs that will never load offline
                    html = stripExternalResources(html);
                } catch (Exception | OutOfMemoryError e) {
                    html = null;
                }
                final String finalHtml = html;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (finalHtml == null) {
                        webView.loadUrl("file://" + file.getAbsolutePath());
                        return;
                    }
                    String baseUrl = "file://" + file.getParentFile().getAbsolutePath() + "/";
                    String history = (originalUrl != null && !originalUrl.isEmpty()) ? originalUrl : baseUrl;
                    webView.loadDataWithBaseURL(baseUrl, finalHtml, "text/html", "UTF-8", history);
                });
            }).start();
        }
    }

    /**
     * Injects a minimal fallback CSS into the HTML &lt;head&gt; so pages remain
     * readable when external stylesheets (e.g. Wikipedia's /w/load.php) can't load.
     */
    public static String injectOfflineFallbackCss(String html) {
        String fallbackCss = "<style id='offline-fallback-css'>"
                + "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; "
                + "  line-height: 1.6; color: #222; margin: 0; padding: 12px 16px; "
                + "  word-wrap: break-word; -webkit-text-size-adjust: 100%; }"
                + "h1, h2, h3 { border-bottom: 1px solid #eee; padding-bottom: 0.3em; }"
                + "a { color: #0645ad; text-decoration: none; }"
                + "img { max-width: 100%; height: auto; }"
                + "table { border-collapse: collapse; width: 100%; margin: 0.5em 0; }"
                + "td, th { border: 1px solid #ddd; padding: 6px 8px; vertical-align: top; }"
                + "th { background: #f5f5f5; }"
                + ".infobox { float: right; width: 260px; margin: 0 0 12px 16px; "
                + "  border: 1px solid #ccc; padding: 8px; background: #f9f9f9; font-size: 0.9em; }"
                + ".infobox img { width: 100%; }"
                + "pre, code { background: #f5f5f5; padding: 2px 6px; border-radius: 3px; "
                + "  font-size: 0.9em; overflow-x: auto; }"
                + ".mw-parser-output { max-width: 100%; }"
                + ".navbox, .catlinks, .mw-indicators, .noprint, "
                + "  .mw-editsection, #footer, .mw-footer { display: none; }"
                + "</style>";

        int headEnd = html.toLowerCase(java.util.Locale.US).indexOf("</head>");
        if (headEnd >= 0) {
            return html.substring(0, headEnd) + fallbackCss + html.substring(headEnd);
        }
        // No </head> tag — prepend to body
        return fallbackCss + html;
    }

    /**
     * Strips external &lt;link&gt; stylesheet and &lt;script src="..."&gt; tags
     * referencing network URLs that will never load when offline.
     * Keeps local file:// and data: references intact.
     */
    public static String stripExternalResources(String html) {
        // Remove external <link rel="stylesheet" href="/w/load.php..."> tags
        html = html.replaceAll("<link[^>]+href=[\"'][^\"']*(?:/w/load\\.php|://)[^\"']*[\"'][^>]*/?>", "");
        // Remove external <script src="/w/load.php..."> tags  
        html = html.replaceAll("<script[^>]+src=[\"'][^\"']*(?:/w/load\\.php|://)[^\"']*[\"'][^>]*>[^<]*</script>", "");
        // Remove inline <script> blocks that reference Wikipedia modules (they'll error without network)
        html = html.replaceAll("<script>\\(RLQ=window\\.RLQ[^<]*</script>", "");
        return html;
    }

    private void openInMainRecorder() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("extra_folder_name", folderName);
        if (filePath != null && !filePath.isEmpty()) {
            intent.putExtra("extra_file_name", new File(filePath).getName());
        }
        startActivity(intent);
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Open in Recorder");
        menu.add(0, 2, 1, "Copy URL");
        menu.add(0, 3, 2, "File Info & Validation");
        menu.add(0, 4, 3, "Share Document");
        menu.add(0, 5, 4, "🔧 Complete Missing Parts (Repair)");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == 1) {
            openInMainRecorder();
            return true;
        } else if (id == 2) {
            if (!originalUrl.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("URL", originalUrl);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "No URL available", Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == 3) {
            showFileInfoDialog();
            return true;
        } else if (id == 4) {
            shareFile();
            return true;
        } else if (id == 5) {
            repairCurrentPage();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void repairCurrentPage() {
        if (filePath == null || filePath.isEmpty()) return;
        File file = new File(filePath);
        if (!file.exists()) {
            Toast.makeText(this, "File does not exist on disk", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Scanning & downloading missing images/styles...", Toast.LENGTH_SHORT).show();
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            OfflinePageRepairer.RepairResult result = OfflinePageRepairer.repairPage(file);
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (result.resourcesFixed > 0) {
                    Toast.makeText(this, "✅ Patched " + result.resourcesFixed + " missing items (" + OfflineStore.formatFileSize(result.bytesDownloaded) + ")", Toast.LENGTH_LONG).show();
                    loadFile();
                } else if (result.error != null) {
                    Toast.makeText(this, "❌ Repair failed: " + result.error, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "✅ Page is already 100% complete!", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void showFileInfoDialog() {
        if (filePath == null) return;
        File f = new File(filePath);
        OfflineStore.ValidationResult val = OfflineStore.validateOfflineFile(f);

        String info = "Title: " + pageTitle
                + "\nFolder: " + folderName
                + "\nRow Index: " + (rowIndex >= 0 ? rowIndex : "N/A")
                + "\nFile: " + f.getName()
                + "\nSize: " + f.length() + " bytes (" + OfflineStore.formatFileSize(f.length()) + ")"
                + "\nModified: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(f.lastModified()))
                + "\n\nValidation Status: " + val.status.name()
                + "\nDetails: " + val.reason
                + "\n\nPath:\n" + f.getAbsolutePath()
                + (originalUrl.isEmpty() ? "" : "\n\nURL:\n" + originalUrl);

        new AlertDialog.Builder(this)
                .setTitle("Offline File Inspection")
                .setMessage(info)
                .setPositiveButton("Close", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    private void shareFile() {
        if (filePath == null) return;
        File file = new File(filePath);
        if (!file.exists()) return;

        try {
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType(file.getName().endsWith(".mht") ? "message/rfc822" : "text/html");
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share Offline File"));
        } catch (Exception e) {
            // Fallback plain share
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, pageTitle);
            shareIntent.putExtra(Intent.EXTRA_TEXT, pageTitle + "\n" + originalUrl);
            startActivity(Intent.createChooser(shareIntent, "Share Movie Info"));
        }
    }
}
