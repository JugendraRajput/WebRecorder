package com.jdpublication.webrecorder;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Advanced Offline Page Repair System.
 *
 * Scans existing offline HTML/MHT files for missing resources (images, CSS,
 * srcset, lazy-load, OG images, favicons) and downloads ONLY the missing parts
 * to patch them in, making each page fully self-contained for offline viewing.
 *
 * Key features:
 * - Deep resource analysis: finds ALL resource types (img src, srcset, data-src,
 *   CSS stylesheets, CSS url() refs, OG images, favicons)
 * - Selective download: only fetches what's missing (skips already-inlined data: URIs)
 * - CSS inlining with recursive url() resolution
 * - srcset processing: downloads best image, replaces srcset with inlined src
 * - Atomic save: writes to temp file then renames to prevent corruption
 * - Progress reporting via callback
 * - Batch repair for entire folders
 */
public class OfflinePageRepairer {

    private static final String TAG = "OfflinePageRepairer";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    // Maximum single resource size to inline (2 MB)
    private static final int MAX_RESOURCE_SIZE = 2 * 1024 * 1024;

    // Timeout for resource downloads
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;

    /**
     * Result of analyzing a single offline page for missing resources.
     */
    public static class PageAnalysis {
        public final File file;
        public final String title;
        public int missingImages = 0;
        public int missingCss = 0;
        public int missingSrcset = 0;
        public int missingLazy = 0;
        public int alreadyInlined = 0;
        public int totalExternalRefs = 0;

        public PageAnalysis(File file, String title) {
            this.file = file;
            this.title = title;
        }

        public int totalMissing() {
            return missingImages + missingCss + missingSrcset + missingLazy;
        }

        public boolean isComplete() {
            return totalMissing() == 0;
        }

        public String getSummary() {
            if (isComplete()) return "✅ Complete (" + alreadyInlined + " inlined)";
            return "⚠️ " + totalMissing() + " missing: " +
                    (missingImages > 0 ? missingImages + " img " : "") +
                    (missingCss > 0 ? missingCss + " css " : "") +
                    (missingSrcset > 0 ? missingSrcset + " srcset " : "") +
                    (missingLazy > 0 ? missingLazy + " lazy " : "");
        }
    }

    /**
     * Result of repairing a single page.
     */
    public static class RepairResult {
        public final File file;
        public int resourcesFixed = 0;
        public int resourcesFailed = 0;
        public long bytesDownloaded = 0;
        public boolean saved = false;
        public String error = null;

        public RepairResult(File file) {
            this.file = file;
        }
    }

    /**
     * Result of a batch repair operation.
     */
    public static class BatchRepairResult {
        public int totalFiles = 0;
        public int filesRepaired = 0;
        public int filesAlreadyComplete = 0;
        public int filesFailed = 0;
        public int totalResourcesFixed = 0;
        public int totalResourcesFailed = 0;
        public long totalBytesDownloaded = 0;
    }

    /**
     * Progress callback for repair operations.
     */
    public interface RepairProgressListener {
        void onProgress(int current, int total, String currentFile, String status);
        void onFileComplete(String fileName, RepairResult result);
        void onComplete(BatchRepairResult result);
    }

    // ══════════════════════════════════════════════════════════════
    //  ANALYSIS — Scan files to find what's missing
    // ══════════════════════════════════════════════════════════════

    /**
     * Analyzes a single offline HTML file to determine what resources are missing.
     */
    public static PageAnalysis analyzePage(File file) {
        OfflineStore.ExtractedMetadata meta = OfflineStore.extractMetadata(file);
        PageAnalysis analysis = new PageAnalysis(file, meta.title);

        try {
            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

            // 1. Count inlined vs external images in <img src="...">
            Pattern imgSrcPattern = Pattern.compile(
                    "<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher m = imgSrcPattern.matcher(html);
            while (m.find()) {
                String src = m.group(1);
                if (src.startsWith("data:")) {
                    analysis.alreadyInlined++;
                } else if (!src.isEmpty()) {
                    analysis.missingImages++;
                    analysis.totalExternalRefs++;
                }
            }

            // 2. Count srcset attributes with external URLs
            Pattern srcsetPattern = Pattern.compile(
                    "srcset=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
            m = srcsetPattern.matcher(html);
            while (m.find()) {
                String srcset = m.group(1);
                if (!srcset.startsWith("data:")) {
                    analysis.missingSrcset++;
                    analysis.totalExternalRefs++;
                }
            }

            // 3. Count external CSS stylesheets
            Pattern cssPattern = Pattern.compile(
                    "<link[^>]+rel=[\"']stylesheet[\"'][^>]+href=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE);
            m = cssPattern.matcher(html);
            while (m.find()) {
                String href = m.group(1);
                if (!href.startsWith("data:")) {
                    analysis.missingCss++;
                    analysis.totalExternalRefs++;
                }
            }

            // 4. Count lazy-loaded images (data-src not yet promoted)
            Pattern lazyPattern = Pattern.compile(
                    "<img[^>]+data-src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
            m = lazyPattern.matcher(html);
            while (m.find()) {
                String dataSrc = m.group(1);
                if (!dataSrc.startsWith("data:")) {
                    analysis.missingLazy++;
                    analysis.totalExternalRefs++;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "analyzePage failed: " + file.getName(), e);
        }

        return analysis;
    }

    /**
     * Analyzes all files in a folder and returns aggregate stats.
     */
    public static List<PageAnalysis> analyzeFolder(Context context, String folderName) {
        List<PageAnalysis> results = new ArrayList<>();
        List<File> files = OfflineStore.listHtmlFiles(context, folderName);
        for (File f : files) {
            if (OfflineStore.isTrueMhtmlArchive(f)) {
                // True MHTML files already have resources embedded — skip
                continue;
            }
            results.add(analyzePage(f));
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════
    //  REPAIR — Download missing resources and patch them in
    // ══════════════════════════════════════════════════════════════

    /**
     * Repairs a single offline HTML file by downloading missing resources
     * and inlining them. Only downloads what's actually missing.
     */
    public static RepairResult repairPage(File file) {
        RepairResult result = new RepairResult(file);

        if (OfflineStore.isTrueMhtmlArchive(file)) {
            result.saved = true; // MHTML files are already self-contained
            return result;
        }

        try {
            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String originalHtml = html;

            // Extract base URL from <base href="..."> tag
            String baseUrl = extractBaseUrl(html);
            String origin = extractOrigin(baseUrl);

            // ── PHASE 1: Inline missing CSS stylesheets ──
            html = repairCssStylesheets(html, origin, result);

            // ── PHASE 2: Inline missing <img src="..."> images ──
            html = repairImgSrcAttributes(html, origin, result);

            // ── PHASE 3: Fallback for Wikipedia infobox poster / lead image ──
            html = repairLeadImageFallback(html, origin, result);

            // ── PHASE 4: Process srcset → download best image, replace ──
            html = repairSrcsetAttributes(html, origin, result);

            // ── PHASE 5: Promote and inline lazy-loaded images ──
            html = repairLazyLoadImages(html, origin, result);

            // ── PHASE 6: Strip dead external scripts ──
            html = stripDeadScripts(html);

            // ── PHASE 7: Inject/update fallback CSS ──
            if (!html.contains("id='offline-fallback-css'") && !html.contains("id=\"offline-fallback-css\"")) {
                html = injectFallbackCss(html);
            }

            // Save only if we actually changed something
            if (!html.equals(originalHtml) && result.resourcesFixed > 0) {
                File tempFile = new File(file.getParentFile(), file.getName() + ".repair.tmp");
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    fos.write(html.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                // Atomic replace
                if (file.delete() && tempFile.renameTo(file)) {
                    result.saved = true;
                } else {
                    // Fallback: overwrite directly
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        fos.write(html.getBytes(StandardCharsets.UTF_8));
                        fos.flush();
                    }
                    tempFile.delete();
                    result.saved = true;
                }
            } else if (result.resourcesFixed == 0) {
                result.saved = true; // Nothing to fix
            }

        } catch (Exception e) {
            result.error = e.getMessage();
            Log.e(TAG, "repairPage failed: " + file.getName(), e);
        }

        return result;
    }

    /**
     * Repairs all pages in a folder using multiple threads.
     */
    public static BatchRepairResult repairFolder(Context context, String folderName,
                                                  int threadCount, RepairProgressListener listener) {
        BatchRepairResult batchResult = new BatchRepairResult();
        List<File> files = OfflineStore.listHtmlFiles(context, folderName);
        batchResult.totalFiles = files.size();

        // Filter to only non-MHTML files that need repair
        List<File> repairableFiles = new ArrayList<>();
        for (File f : files) {
            if (!OfflineStore.isTrueMhtmlArchive(f)) {
                repairableFiles.add(f);
            } else {
                batchResult.filesAlreadyComplete++;
            }
        }

        if (repairableFiles.isEmpty()) {
            if (listener != null) listener.onComplete(batchResult);
            return batchResult;
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threadCount, 4));
        AtomicInteger completedCount = new AtomicInteger(0);
        int totalRepairable = repairableFiles.size();

        for (File file : repairableFiles) {
            pool.submit(() -> {
                try {
                    RepairResult result = repairPage(file);
                    int current = completedCount.incrementAndGet();

                    synchronized (batchResult) {
                        if (result.resourcesFixed > 0) {
                            batchResult.filesRepaired++;
                        } else if (result.error != null) {
                            batchResult.filesFailed++;
                        } else {
                            batchResult.filesAlreadyComplete++;
                        }
                        batchResult.totalResourcesFixed += result.resourcesFixed;
                        batchResult.totalResourcesFailed += result.resourcesFailed;
                        batchResult.totalBytesDownloaded += result.bytesDownloaded;
                    }

                    if (listener != null) {
                        listener.onProgress(current, totalRepairable, file.getName(),
                                result.resourcesFixed + " fixed, " + result.resourcesFailed + " failed");
                        listener.onFileComplete(file.getName(), result);
                    }
                } catch (Exception e) {
                    completedCount.incrementAndGet();
                    synchronized (batchResult) {
                        batchResult.filesFailed++;
                    }
                }
            });
        }

        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            pool.shutdownNow();
        }

        if (listener != null) listener.onComplete(batchResult);
        return batchResult;
    }

    // ══════════════════════════════════════════════════════════════
    //  INTERNAL REPAIR PHASES
    // ══════════════════════════════════════════════════════════════

    /**
     * Phase 1: Download and inline external CSS stylesheets.
     */
    private static String repairCssStylesheets(String html, String origin, RepairResult result) {
        Pattern linkPattern = Pattern.compile(
                "<link[^>]+rel=[\"']stylesheet[\"'][^>]+href=[\"']([^\"']+)[\"'][^>]*/?>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = linkPattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String href = matcher.group(1);
            if (href.startsWith("data:")) continue;

            String cssUrl = resolveUrl(href, origin);
            try {
                byte[] cssBytes = fetchBinary(cssUrl);
                if (cssBytes != null && cssBytes.length > 0) {
                    String css = new String(cssBytes, StandardCharsets.UTF_8);
                    // Recursively inline url() references inside CSS
                    css = inlineCssUrlReferences(css, cssUrl, result);
                    String replacement = "<style data-repaired='css'>" +
                            Matcher.quoteReplacement(css) + "</style>";
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    result.resourcesFixed++;
                    result.bytesDownloaded += cssBytes.length;
                    continue;
                }
            } catch (Exception ignored) {}
            // Remove unreachable stylesheet link
            matcher.appendReplacement(sb, "<!-- repair: css unavailable -->");
            result.resourcesFailed++;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Phase 2: Download and inline missing <img src="..."> images.
     */
    private static String repairImgSrcAttributes(String html, String origin, RepairResult result) {
        Pattern imgPattern = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher matcher = imgPattern.matcher(html);
        Set<String> externalUrls = new HashSet<>();
        while (matcher.find()) {
            String src = matcher.group(1);
            if (src != null && !src.isEmpty() && !src.startsWith("data:") && !src.startsWith("blob:") && !src.startsWith("file:")) {
                externalUrls.add(src);
            }
        }

        for (String src : externalUrls) {
            String fullUrl = resolveUrl(src, origin);
            try {
                byte[] imgBytes = fetchBinary(fullUrl);
                if (imgBytes != null && imgBytes.length > 0 && imgBytes.length < MAX_RESOURCE_SIZE) {
                    String mime = guessMimeType(fullUrl);
                    String dataUri = "data:" + mime + ";base64," + Base64.encodeToString(imgBytes, Base64.NO_WRAP);

                    // Replace exact attribute with double quotes and single quotes
                    html = html.replace("src=\"" + src + "\"", "src=\"" + dataUri + "\" loading=\"eager\"");
                    html = html.replace("src='" + src + "'", "src=\"" + dataUri + "\" loading=\"eager\"");

                    // In case the URL in HTML had HTML-escaped ampersands
                    String unescaped = src.replace("&amp;", "&");
                    if (!unescaped.equals(src)) {
                        html = html.replace("src=\"" + unescaped + "\"", "src=\"" + dataUri + "\" loading=\"eager\"");
                        html = html.replace("src='" + unescaped + "'", "src=\"" + dataUri + "\" loading=\"eager\"");
                    }

                    result.resourcesFixed++;
                    result.bytesDownloaded += imgBytes.length;
                } else {
                    result.resourcesFailed++;
                }
            } catch (Exception e) {
                result.resourcesFailed++;
            }
        }
        return html;
    }

    /**
     * Phase 3: Fallback for Wikipedia infobox poster / lead image:
     * If infobox has an img tag whose image failed or wasn't loaded,
     * extract image from JSON-LD schema or meta og:image and patch it in.
     */
    private static String repairLeadImageFallback(String html, String origin, RepairResult result) {
        try {
            // Check if page already has an inlined infobox image
            boolean infoboxHasImage = false;
            int infoboxStart = html.indexOf("class=\"infobox");
            if (infoboxStart < 0) infoboxStart = html.indexOf("class='infobox'");
            int infoboxEnd = -1;
            if (infoboxStart >= 0) {
                infoboxEnd = html.indexOf("</table>", infoboxStart);
                if (infoboxEnd > infoboxStart) {
                    String infoboxSnippet = html.substring(infoboxStart, infoboxEnd);
                    if (infoboxSnippet.contains("src=\"data:image") || infoboxSnippet.contains("src='data:image")) {
                        infoboxHasImage = true;
                    }
                }
            }

            if (!infoboxHasImage) {
                // Extract lead image from JSON-LD schema: "image":"https:\/\/upload.wikimedia.org\/..."
                String leadImageUrl = null;
                Pattern ldJsonPattern = Pattern.compile("<script[^>]+type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
                Matcher ldMatcher = ldJsonPattern.matcher(html);
                if (ldMatcher.find()) {
                    String json = ldMatcher.group(1);
                    Pattern imgFieldPattern = Pattern.compile("\"image\"\\s*:\\s*\"([^\"]+)\"");
                    Matcher imgMatcher = imgFieldPattern.matcher(json);
                    if (imgMatcher.find()) {
                        leadImageUrl = imgMatcher.group(1).replace("\\/", "/");
                    }
                }

                // If not found in JSON-LD, try meta og:image
                if (leadImageUrl == null || leadImageUrl.isEmpty()) {
                    Pattern ogPattern = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
                    Matcher ogMatcher = ogPattern.matcher(html);
                    if (ogMatcher.find()) {
                        leadImageUrl = ogMatcher.group(1);
                    }
                }

                if (leadImageUrl != null && !leadImageUrl.isEmpty() && !leadImageUrl.startsWith("data:")) {
                    String fullUrl = resolveUrl(leadImageUrl, origin);
                    byte[] imgBytes = fetchBinary(fullUrl);
                    if (imgBytes != null && imgBytes.length > 0) {
                        String mime = guessMimeType(fullUrl);
                        String dataUri = "data:" + mime + ";base64," + Base64.encodeToString(imgBytes, Base64.NO_WRAP);

                        // If infobox exists, replace any external/empty img src inside it
                        if (infoboxStart >= 0 && infoboxEnd > infoboxStart) {
                            String infobox = html.substring(infoboxStart, infoboxEnd);
                            String updatedInfobox = infobox.replaceAll("src=[\"'](?:https?:)?//[^\"']+[\"']", "src=\"" + dataUri + "\" loading=\"eager\"");
                            if (!updatedInfobox.equals(infobox)) {
                                html = html.substring(0, infoboxStart) + updatedInfobox + html.substring(infoboxEnd);
                                result.resourcesFixed++;
                                result.bytesDownloaded += imgBytes.length;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return html;
    }

    /**
     * Phase 4: Process srcset attributes — download the best (largest) image,
     * inline it as the src if needed, and remove all remaining srcset attributes
     * so browser doesn't try to make network requests for them when offline.
     */
    private static String repairSrcsetAttributes(String html, String origin, RepairResult result) {
        Pattern srcsetPattern = Pattern.compile(
                "<img[^>]+srcset=[\"']([^\"']+)[\"'][^>]*>",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = srcsetPattern.matcher(html);
        Set<String> srcsets = new HashSet<>();
        while (matcher.find()) {
            String ss = matcher.group(1);
            if (ss != null && !ss.startsWith("data:")) {
                srcsets.add(ss);
            }
        }

        for (String ss : srcsets) {
            String bestUrl = pickBestSrcsetUrl(ss, origin);
            if (bestUrl != null && !bestUrl.isEmpty() && !bestUrl.startsWith("data:")) {
                try {
                    byte[] imgBytes = fetchBinary(bestUrl);
                    if (imgBytes != null && imgBytes.length > 0 && imgBytes.length < MAX_RESOURCE_SIZE) {
                        String mime = guessMimeType(bestUrl);
                        String dataUri = "data:" + mime + ";base64," + Base64.encodeToString(imgBytes, Base64.NO_WRAP);
                        // If there is an img tag with this srcset and empty/placeholder src, update it
                        html = html.replace("srcset=\"" + ss + "\"", "src=\"" + dataUri + "\" loading=\"eager\"");
                        html = html.replace("srcset='" + ss + "'", "src=\"" + dataUri + "\" loading=\"eager\"");
                        result.resourcesFixed++;
                        result.bytesDownloaded += imgBytes.length;
                        continue;
                    }
                } catch (Exception ignored) {}
            }
        }

        // Clean up any remaining srcset and picture/source elements to prevent offline network hangs
        html = html.replaceAll("\\s+srcset=[\"'][^\"']*[\"']", "");
        html = html.replaceAll("<source[^>]+srcset=[^>]*>", "");
        return html;
    }

    /**
     * Phase 5: Promote data-src / data-original (lazy-loaded) images to src and inline them.
     */
    private static String repairLazyLoadImages(String html, String origin, RepairResult result) {
        Pattern lazyPattern = Pattern.compile(
                "data-(?:src|lazy-src|original)=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = lazyPattern.matcher(html);
        Set<String> lazyUrls = new HashSet<>();
        while (matcher.find()) {
            String src = matcher.group(1);
            if (src != null && !src.isEmpty() && !src.startsWith("data:")) {
                lazyUrls.add(src);
            }
        }

        for (String lazyUrl : lazyUrls) {
            String fullUrl = resolveUrl(lazyUrl, origin);
            try {
                byte[] imgBytes = fetchBinary(fullUrl);
                if (imgBytes != null && imgBytes.length > 0 && imgBytes.length < MAX_RESOURCE_SIZE) {
                    String mime = guessMimeType(fullUrl);
                    String dataUri = "data:" + mime + ";base64," + Base64.encodeToString(imgBytes, Base64.NO_WRAP);

                    html = html.replace("data-src=\"" + lazyUrl + "\"", "src=\"" + dataUri + "\" loading=\"eager\"");
                    html = html.replace("data-src='" + lazyUrl + "'", "src=\"" + dataUri + "\" loading=\"eager\"");
                    html = html.replace("data-lazy-src=\"" + lazyUrl + "\"", "src=\"" + dataUri + "\" loading=\"eager\"");
                    html = html.replace("data-original=\"" + lazyUrl + "\"", "src=\"" + dataUri + "\" loading=\"eager\"");

                    result.resourcesFixed++;
                    result.bytesDownloaded += imgBytes.length;
                }
            } catch (Exception ignored) {}
        }

        // Strip loading="lazy" to ensure immediate offline rendering
        html = html.replaceAll("\\s+loading=[\"']lazy[\"']", " loading=\"eager\"");
        return html;
    }

    /**
     * Phase 6: Strip external scripts that will never work offline.
     */
    private static String stripDeadScripts(String html) {
        // Remove external script tags referencing Wikipedia modules
        html = html.replaceAll(
                "<script[^>]+src=[\"'][^\"']*(?:/w/load\\.php|/w/resources|modules)[^\"']*[\"'][^>]*>[^<]*</script>", "");
        // Remove RLQ module loader
        html = html.replaceAll(
                "<script>\\(RLQ=window\\.RLQ[^<]*</script>", "");
        // Remove async startup scripts
        html = html.replaceAll(
                "<script[^>]*async[^>]*src=[\"'][^\"']*startup[^\"']*[\"'][^>]*>[^<]*</script>", "");
        return html;
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════

    /**
     * Inlines url() references inside CSS content.
     */
    private static String inlineCssUrlReferences(String css, String cssUrl, RepairResult result) {
        String cssBase;
        int lastSlash = cssUrl.lastIndexOf('/');
        cssBase = (lastSlash > 8) ? cssUrl.substring(0, lastSlash + 1) : cssUrl;

        Pattern urlPattern = Pattern.compile("url\\([\"']?([^\"')]+)[\"']?\\)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = urlPattern.matcher(css);
        StringBuffer sb = new StringBuffer();
        int inlinedCount = 0;

        while (matcher.find() && inlinedCount < 50) {
            String ref = matcher.group(1).trim();
            if (ref.startsWith("data:") || ref.startsWith("#") || ref.startsWith("about:")) {
                continue;
            }

            String fullUrl;
            try {
                if (ref.startsWith("//")) fullUrl = "https:" + ref;
                else if (ref.startsWith("http")) fullUrl = ref;
                else fullUrl = new URL(new URL(cssBase), ref).toString();
            } catch (Exception e) {
                continue;
            }

            try {
                byte[] bytes = fetchBinary(fullUrl);
                if (bytes != null && bytes.length > 0 && bytes.length < 500_000) {
                    String mime = guessMimeType(fullUrl);
                    String dataUri = "data:" + mime + ";base64," +
                            Base64.encodeToString(bytes, Base64.NO_WRAP);
                    matcher.appendReplacement(sb, Matcher.quoteReplacement("url(\"" + dataUri + "\")"));
                    inlinedCount++;
                    result.bytesDownloaded += bytes.length;
                    continue;
                }
            } catch (Exception ignored) {}
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Extracts the base URL from a <base href="..."> tag.
     */
    private static String extractBaseUrl(String html) {
        Pattern basePattern = Pattern.compile(
                "<base[^>]+href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
        Matcher m = basePattern.matcher(html);
        if (m.find()) return m.group(1);
        return "";
    }

    /**
     * Extracts the origin (protocol + host) from a URL.
     */
    private static String extractOrigin(String urlStr) {
        try {
            URL parsed = new URL(urlStr);
            return parsed.getProtocol() + "://" + parsed.getHost();
        } catch (Exception e) {
            return "https://en.wikipedia.org";
        }
    }

    /**
     * Picks the best (highest-resolution) URL from a srcset value.
     */
    private static String pickBestSrcsetUrl(String srcset, String origin) {
        String bestUrl = null;
        double bestValue = 0;

        String[] entries = srcset.split(",");
        for (String entry : entries) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;

            String[] parts = entry.split("\\s+");
            if (parts.length == 0) continue;

            String url = parts[0].trim();
            double value = 1;

            if (parts.length >= 2) {
                String descriptor = parts[1].trim().toLowerCase(Locale.US);
                try {
                    if (descriptor.endsWith("w")) {
                        value = Double.parseDouble(descriptor.replace("w", ""));
                    } else if (descriptor.endsWith("x")) {
                        value = Double.parseDouble(descriptor.replace("x", "")) * 1000;
                    }
                } catch (NumberFormatException ignored) {}
            }

            if (value > bestValue) {
                bestValue = value;
                bestUrl = url;
            }
        }

        return bestUrl != null ? resolveUrl(bestUrl, origin) : null;
    }

    /**
     * Resolves a potentially relative URL against the origin.
     */
    private static String resolveUrl(String href, String origin) {
        if (href == null) return "";
        href = href.trim();
        href = href.replace("&amp;", "&");
        if (href.startsWith("data:") || href.startsWith("blob:") || href.startsWith("file:")) return href;
        if (href.startsWith("//")) return "https:" + href;
        if (href.startsWith("/")) return origin + href;
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        return origin + "/" + href;
    }

    /**
     * Guesses MIME type from URL extension.
     */
    private static String guessMimeType(String url) {
        String lower = url.toLowerCase(Locale.US);
        int qIdx = lower.indexOf('?');
        if (qIdx > 0) lower = lower.substring(0, qIdx);
        int hIdx = lower.indexOf('#');
        if (hIdx > 0) lower = lower.substring(0, hIdx);

        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".avif")) return "image/avif";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".eot")) return "application/vnd.ms-fontobject";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        return "image/jpeg";
    }

    /**
     * Fetches binary content from a URL.
     */
    private static byte[] fetchBinary(String urlStr) {
        HttpURLConnection conn = null;
        try {
            urlStr = urlStr.replace("&amp;", "&");
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                if (loc != null) {
                    conn.disconnect();
                    return fetchBinary(loc);
                }
            }
            if (code != 200) return null;

            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_RESOURCE_SIZE) return null; // Too large
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Injects fallback CSS for clean offline rendering.
     */
    private static String injectFallbackCss(String html) {
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

        int headEnd = html.toLowerCase(Locale.US).indexOf("</head>");
        if (headEnd >= 0) {
            return html.substring(0, headEnd) + fallbackCss + html.substring(headEnd);
        }
        return fallbackCss + html;
    }
}
