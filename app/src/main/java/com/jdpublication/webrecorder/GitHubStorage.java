package com.jdpublication.webrecorder;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal GitHub REST client used as free, permanent cloud storage for offline pages.
 *
 * Repository layout:
 *   pages/&lt;folder&gt;/&lt;md5&gt;.mht|.html   offline page
 *   pages/&lt;folder&gt;/metadata.json      titles / urls / row indexes
 *   history.json                     last sync events (read by the dashboard)
 *
 * Writes are batched through the Git Data API (one tree + one commit per batch) so
 * thousands of files only cost a handful of "content creation" requests.
 */
public final class GitHubStorage {

    private static final String TAG = "GitHubStorage";
    /** Overridable only by tests. */
    static String API = "https://api.github.com";
    static String RAW = "https://raw.githubusercontent.com";
    public static final String PAGES_ROOT = "pages";
    public static final String HISTORY_PATH = "history.json";
    private static final int MAX_RETRIES = 4;

    private final String owner;
    private final String repo;
    private final String branch;
    private final String token;
    /** raw.githubusercontent.com does not always accept tokens; remember when it fails. */
    private volatile Boolean rawWorks = null;

    public GitHubStorage(String ownerSlashRepo, String branch, String token) {
        String clean = ownerSlashRepo == null ? "" : ownerSlashRepo.trim();
        clean = clean.replaceFirst("^https?://github\\.com/", "").replaceAll("\\.git$", "").replaceAll("/+$", "");
        int slash = clean.indexOf('/');
        if (slash <= 0 || slash == clean.length() - 1) {
            throw new IllegalArgumentException("Repository must look like owner/repo");
        }
        this.owner = clean.substring(0, slash);
        this.repo = clean.substring(slash + 1);
        this.branch = (branch == null || branch.trim().isEmpty()) ? "main" : branch.trim();
        this.token = token == null ? "" : token.trim();
        if (this.token.isEmpty()) {
            throw new IllegalArgumentException("GitHub token is missing");
        }
    }

    public String getRepoLabel() {
        return owner + "/" + repo + " @ " + branch;
    }

    // ───────────────────────────── Models ─────────────────────────────

    public static final class RemoteFile {
        public final String name;
        public final String sha;
        public final long size;

        RemoteFile(String name, String sha, long size) {
            this.name = name;
            this.sha = sha;
            this.size = size;
        }
    }

    /** Snapshot of the branch head. */
    public static final class Head {
        public final String commitSha;
        public final String treeSha;

        Head(String commitSha, String treeSha) {
            this.commitSha = commitSha;
            this.treeSha = treeSha;
        }
    }

    /** One change to apply in a commit. Exactly one of file / text / delete is used. */
    public static final class Change {
        final String path;
        final File file;
        final String text;
        final boolean delete;
        String blobSha; // filled when uploaded via the blob API

        private Change(String path, File file, String text, boolean delete) {
            this.path = path;
            this.file = file;
            this.text = text;
            this.delete = delete;
        }

        public static Change upload(String path, File file) {
            return new Change(path, file, null, false);
        }

        public static Change text(String path, String text) {
            return new Change(path, null, text, false);
        }

        public static Change delete(String path) {
            return new Change(path, null, null, true);
        }

        long approxSize() {
            if (file != null) return file.length();
            if (text != null) return text.length();
            return 64;
        }
    }

    // ───────────────────────────── Reads ─────────────────────────────

    /** Returns the current head, initialising an empty repository if needed. */
    public Head getHead() throws IOException {
        Response ref = request("GET", "/repos/" + owner + "/" + repo + "/git/ref/heads/" + branch, null);
        if (ref.code == 404 || ref.code == 409) {
            // 409 = empty repository, 404 = missing branch. Create a first commit via Contents API.
            initialiseRepository();
            ref = request("GET", "/repos/" + owner + "/" + repo + "/git/ref/heads/" + branch, null);
        }
        ref.ensureOk("read branch " + branch);
        String commitSha = ref.json().getJSONObjectSafe("object").optString("sha");
        Response commit = request("GET", "/repos/" + owner + "/" + repo + "/git/commits/" + commitSha, null);
        commit.ensureOk("read commit");
        String treeSha = commit.json().getJSONObjectSafe("tree").optString("sha");
        return new Head(commitSha, treeSha);
    }

    private void initialiseRepository() throws IOException {
        String body = "{\"message\":\"Initialise WebRecorder storage\",\"branch\":\"" + branch + "\",\"content\":\""
                + Base64.encodeToString("# WebRecorder offline pages\n".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "\"}";
        Response r = request("PUT", "/repos/" + owner + "/" + repo + "/contents/README.md", body);
        if (r.code == 422) {
            // Repository not empty but the branch does not exist: create it from the default branch.
            Response repoInfo = request("GET", "/repos/" + owner + "/" + repo, null);
            repoInfo.ensureOk("read repository");
            String defaultBranch = repoInfo.json().optString("default_branch", "main");
            Response def = request("GET", "/repos/" + owner + "/" + repo + "/git/ref/heads/" + defaultBranch, null);
            def.ensureOk("read default branch");
            String sha = def.json().getJSONObjectSafe("object").optString("sha");
            Response create = request("POST", "/repos/" + owner + "/" + repo + "/git/refs",
                    "{\"ref\":\"refs/heads/" + branch + "\",\"sha\":\"" + sha + "\"}");
            create.ensureOk("create branch " + branch);
            return;
        }
        r.ensureOk("initialise repository");
    }

    /** Lists cloud folders with their files (one request for the whole pages tree). */
    public Map<String, Map<String, RemoteFile>> listAllFolders(Head head) throws IOException {
        Map<String, Map<String, RemoteFile>> result = new HashMap<>();
        String pagesSha = findChildTreeSha(head.treeSha, PAGES_ROOT);
        if (pagesSha == null) {
            return result;
        }
        Response r = request("GET", "/repos/" + owner + "/" + repo + "/git/trees/" + pagesSha + "?recursive=1", null);
        r.ensureOk("list cloud folders");
        JSONObject json = r.json();
        if (json.optBoolean("truncated", false)) {
            // Very large repository: list folder by folder instead.
            Response top = request("GET", "/repos/" + owner + "/" + repo + "/git/trees/" + pagesSha, null);
            top.ensureOk("list cloud folders");
            JSONArray entries = top.json().optJSONArray("tree");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.optJSONObject(i);
                    if (e != null && "tree".equals(e.optString("type"))) {
                        result.put(e.optString("path"), listTree(e.optString("sha")));
                    }
                }
            }
            return result;
        }
        JSONArray entries = json.optJSONArray("tree");
        if (entries == null) return result;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            String path = e.optString("path");
            int slash = path.indexOf('/');
            if ("tree".equals(e.optString("type"))) {
                if (slash < 0 && !result.containsKey(path)) result.put(path, new HashMap<>());
                continue;
            }
            if (slash <= 0 || path.indexOf('/', slash + 1) >= 0) continue;
            String folder = path.substring(0, slash);
            String name = path.substring(slash + 1);
            Map<String, RemoteFile> files = result.get(folder);
            if (files == null) {
                files = new HashMap<>();
                result.put(folder, files);
            }
            files.put(name, new RemoteFile(name, e.optString("sha"), e.optLong("size", 0)));
        }
        return result;
    }

    /** Lists files of one cloud folder (empty map when the folder does not exist). */
    public Map<String, RemoteFile> listFolder(Head head, String folder) throws IOException {
        String pagesSha = findChildTreeSha(head.treeSha, PAGES_ROOT);
        if (pagesSha == null) return new HashMap<>();
        String folderSha = findChildTreeSha(pagesSha, folder);
        if (folderSha == null) return new HashMap<>();
        return listTree(folderSha);
    }

    private Map<String, RemoteFile> listTree(String treeSha) throws IOException {
        Map<String, RemoteFile> files = new HashMap<>();
        Response r = request("GET", "/repos/" + owner + "/" + repo + "/git/trees/" + treeSha, null);
        r.ensureOk("list folder");
        JSONArray entries = r.json().optJSONArray("tree");
        if (entries == null) return files;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null || !"blob".equals(e.optString("type"))) continue;
            String name = e.optString("path");
            files.put(name, new RemoteFile(name, e.optString("sha"), e.optLong("size", 0)));
        }
        return files;
    }

    private String findChildTreeSha(String treeSha, String childName) throws IOException {
        Response r = request("GET", "/repos/" + owner + "/" + repo + "/git/trees/" + treeSha, null);
        r.ensureOk("read tree");
        JSONArray entries = r.json().optJSONArray("tree");
        if (entries == null) return null;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e != null && childName.equals(e.optString("path")) && "tree".equals(e.optString("type"))) {
                return e.optString("sha");
            }
        }
        return null;
    }

    /** Downloads a file at the given commit, verifying the git blob hash. Returns bytes written. */
    public long downloadFile(Head head, String path, String expectedSha, File destination) throws IOException {
        File temp = new File(destination.getParentFile(), destination.getName() + ".part");
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                long bytes = -1;
                if (rawWorks != Boolean.FALSE) {
                    bytes = downloadRaw(head.commitSha, path, temp);
                    rawWorks = bytes >= 0;
                }
                if (bytes < 0) {
                    bytes = downloadBlob(expectedSha, temp);
                }
                String actual = gitBlobSha(temp);
                if (expectedSha != null && !expectedSha.isEmpty() && !expectedSha.equals(actual)) {
                    throw new IOException("Checksum mismatch for " + path);
                }
                if (destination.exists() && !destination.delete()) {
                    throw new IOException("Cannot replace " + destination.getName());
                }
                if (!temp.renameTo(destination)) {
                    throw new IOException("Cannot move downloaded file into place");
                }
                return bytes;
            } catch (IOException e) {
                last = e;
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                sleepQuietly(1000L * (attempt + 1));
            }
        }
        throw last != null ? last : new IOException("Download failed: " + path);
    }

    /** Reads a small text file from the branch head (null when it does not exist). */
    public String readText(Head head, String path) throws IOException {
        File temp = File.createTempFile("webrecorder-", ".txt");
        try {
            long bytes = downloadRaw(head.commitSha, path, temp);
            if (bytes < 0) {
                // raw.githubusercontent may answer 404 for private repos; ask the API instead.
                HttpURLConnection c = open(API + "/repos/" + owner + "/" + repo + "/contents/" + encodePath(path)
                        + "?ref=" + head.commitSha, "GET");
                c.setRequestProperty("Accept", "application/vnd.github.raw+json");
                int code = c.getResponseCode();
                if (code == 404) {
                    c.disconnect();
                    return null;
                }
                if (code < 200 || code >= 300) {
                    String err = readAll(c.getErrorStream());
                    c.disconnect();
                    throw new IOException("HTTP " + code + " reading " + path + " " + abbreviate(err));
                }
                copyToFile(c, temp);
            }
            return new String(java.nio.file.Files.readAllBytes(temp.toPath()), StandardCharsets.UTF_8);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private long downloadRaw(String commitSha, String path, File destination) throws IOException {
        HttpURLConnection c = open(RAW + "/" + owner + "/" + repo + "/" + commitSha + "/" + encodePath(path), "GET");
        c.setReadTimeout(60000);
        int code = c.getResponseCode();
        if (code == 404) {
            c.disconnect();
            return -1;
        }
        if (code < 200 || code >= 300) {
            String err = readAll(c.getErrorStream());
            c.disconnect();
            throw new IOException("HTTP " + code + " downloading " + path + " " + abbreviate(err));
        }
        return copyToFile(c, destination);
    }

    private long downloadBlob(String sha, File destination) throws IOException {
        if (sha == null || sha.isEmpty()) throw new IOException("File not found in cloud");
        HttpURLConnection c = open(API + "/repos/" + owner + "/" + repo + "/git/blobs/" + sha, "GET");
        c.setRequestProperty("Accept", "application/vnd.github.raw+json");
        c.setReadTimeout(60000);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            String err = readAll(c.getErrorStream());
            c.disconnect();
            throw new IOException("HTTP " + code + " downloading blob " + abbreviate(err));
        }
        return copyToFile(c, destination);
    }

    private long copyToFile(HttpURLConnection c, File destination) throws IOException {
        long total = 0;
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
        } finally {
            c.disconnect();
        }
        return total;
    }

    // ───────────────────────────── Writes ─────────────────────────────

    /**
     * Applies changes as a single commit on top of the branch head. Retries automatically
     * when another device pushed in between. Returns the new head.
     */
    public Head commit(Head base, List<Change> changes, String message) throws IOException {
        if (changes.isEmpty()) return base;
        Head current = base;
        IOException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                String newTree = createTree(current.treeSha, changes);
                String commitBody = "{\"message\":" + JSONObject.quote(message)
                        + ",\"tree\":\"" + newTree + "\",\"parents\":[\"" + current.commitSha + "\"]}";
                Response commit = request("POST", "/repos/" + owner + "/" + repo + "/git/commits", commitBody);
                commit.ensureOk("create commit");
                String newCommit = commit.json().optString("sha");
                Response ref = request("PATCH", "/repos/" + owner + "/" + repo + "/git/refs/heads/" + branch,
                        "{\"sha\":\"" + newCommit + "\",\"force\":false}");
                if (ref.code == 422 || ref.code == 409) {
                    // Someone else pushed: rebuild on top of the new head.
                    current = getHead();
                    continue;
                }
                ref.ensureOk("update branch");
                return new Head(newCommit, newTree);
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "commit attempt " + attempt + " failed", e);
                sleepQuietly(1500L * (attempt + 1));
                current = getHead();
            }
        }
        throw last != null ? last : new IOException("Could not commit changes (branch keeps moving)");
    }

    /** Creates a tree; file contents are streamed inline (UTF-8) or via the blob API (binary). */
    private String createTree(String baseTree, List<Change> changes) throws IOException {
        // Binary / non UTF-8 files go through the blob endpoint first.
        for (Change ch : changes) {
            if (ch.file != null && ch.blobSha == null && !isValidUtf8(ch.file)) {
                ch.blobSha = createBlob(ch.file);
            }
        }
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection c = open(API + "/repos/" + owner + "/" + repo + "/git/trees", "POST");
            c.setDoOutput(true);
            c.setChunkedStreamingMode(64 * 1024);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setReadTimeout(180000);
            try (Writer w = new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8)) {
                w.write("{\"base_tree\":\"" + baseTree + "\",\"tree\":[");
                boolean first = true;
                for (Change ch : changes) {
                    if (!first) w.write(',');
                    first = false;
                    w.write("{\"path\":");
                    w.write(JSONObject.quote(ch.path));
                    w.write(",\"mode\":\"100644\",\"type\":\"blob\",");
                    if (ch.delete) {
                        w.write("\"sha\":null}");
                    } else if (ch.blobSha != null) {
                        w.write("\"sha\":\"" + ch.blobSha + "\"}");
                    } else if (ch.text != null) {
                        w.write("\"content\":");
                        w.write(JSONObject.quote(ch.text));
                        w.write('}');
                    } else {
                        w.write("\"content\":\"");
                        try (Reader r = new InputStreamReader(new BufferedInputStream(new FileInputStream(ch.file)), StandardCharsets.UTF_8)) {
                            writeJsonEscaped(r, w);
                        }
                        w.write("\"}");
                    }
                }
                w.write("]}");
            }
            Response r = readResponse(c);
            if (isRetryable(r.code) && attempt < MAX_RETRIES) {
                waitForRetry(r, attempt);
                continue;
            }
            r.ensureOk("upload files");
            return r.json().optString("sha");
        }
    }

    private String createBlob(File file) throws IOException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection c = open(API + "/repos/" + owner + "/" + repo + "/git/blobs", "POST");
            c.setDoOutput(true);
            c.setChunkedStreamingMode(64 * 1024);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setReadTimeout(180000);
            try (OutputStream out = c.getOutputStream()) {
                out.write("{\"encoding\":\"base64\",\"content\":\"".getBytes(StandardCharsets.US_ASCII));
                try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                    byte[] buf = new byte[3 * 16384];
                    int n;
                    while ((n = readFully(in, buf)) > 0) {
                        out.write(Base64.encode(buf, 0, n, Base64.NO_WRAP));
                    }
                }
                out.write("\"}".getBytes(StandardCharsets.US_ASCII));
            }
            Response r = readResponse(c);
            if (isRetryable(r.code) && attempt < MAX_RETRIES) {
                waitForRetry(r, attempt);
                continue;
            }
            r.ensureOk("upload " + file.getName());
            return r.json().optString("sha");
        }
    }

    // ───────────────────────────── Helpers ─────────────────────────────

    /** Git blob SHA-1 of a file: sha1("blob " + size + "\0" + bytes). */
    public static String gitBlobSha(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("blob " + file.length() + "\0").getBytes(StandardCharsets.US_ASCII));
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return toHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    public static String gitBlobSha(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            md.update(bytes);
            return toHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static boolean isValidUtf8(File file) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader r = new InputStreamReader(new BufferedInputStream(new FileInputStream(file)), decoder)) {
            char[] buf = new char[16384];
            while (r.read(buf) != -1) {
                // just decode
            }
            return true;
        } catch (CharacterCodingException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeJsonEscaped(Reader r, Writer w) throws IOException {
        char[] buf = new char[16384];
        StringBuilder sb = new StringBuilder(20000);
        int n;
        while ((n = r.read(buf)) != -1) {
            sb.setLength(0);
            for (int i = 0; i < n; i++) {
                char ch = buf[i];
                switch (ch) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    default:
                        if (ch < 0x20 || ch == 0x2028 || ch == 0x2029) {
                            sb.append(String.format(Locale.US, "\\u%04x", (int) ch));
                        } else {
                            sb.append(ch);
                        }
                }
            }
            w.write(sb.toString());
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("/")) {
            if (sb.length() > 0) sb.append('/');
            try {
                sb.append(java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20"));
            } catch (java.io.UnsupportedEncodingException e) {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method); // Android's HttpURLConnection supports PATCH
        } catch (java.net.ProtocolException e) {
            // Some JVM implementations reject PATCH: set the method field directly.
            try {
                java.lang.reflect.Field f = HttpURLConnection.class.getDeclaredField("method");
                f.setAccessible(true);
                f.set(c, method);
            } catch (Exception reflectionFailure) {
                throw e;
            }
        }
        c.setConnectTimeout(20000);
        c.setReadTimeout(45000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "WebRecorder-Android");
        return c;
    }

    private Response request(String method, String path, String body) throws IOException {
        for (int attempt = 0; ; attempt++) {
            HttpURLConnection c = open(API + path, method);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            Response r = readResponse(c);
            if (isRetryable(r.code) && attempt < MAX_RETRIES) {
                waitForRetry(r, attempt);
                continue;
            }
            return r;
        }
    }

    private static Response readResponse(HttpURLConnection c) throws IOException {
        try {
            int code = c.getResponseCode();
            InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            String body = readAll(stream);
            return new Response(code, body, c.getHeaderField("Retry-After"),
                    c.getHeaderField("X-RateLimit-Remaining"), c.getHeaderField("X-RateLimit-Reset"));
        } finally {
            c.disconnect();
        }
    }

    private static boolean isRetryable(int code) {
        return code == 429 || code == 403 || code == 502 || code == 503 || code == 504 || code == 500;
    }

    private static void waitForRetry(Response r, int attempt) throws IOException {
        if (r.code == 403 && !r.isRateLimited()) {
            // Real permission problem: do not wait.
            throw new IOException(r.describe("GitHub refused the request"));
        }
        long waitMs = 2000L * (1L << attempt);
        try {
            if (r.retryAfter != null) waitMs = Long.parseLong(r.retryAfter.trim()) * 1000L;
            else if ("0".equals(r.rateRemaining) && r.rateReset != null) {
                waitMs = Long.parseLong(r.rateReset.trim()) * 1000L - System.currentTimeMillis() + 1000L;
            }
        } catch (NumberFormatException ignored) {
        }
        if (waitMs > 90_000L) {
            throw new IOException("GitHub rate limit reached. Please try again in "
                    + Math.max(1, waitMs / 60000L) + " min.");
        }
        sleepQuietly(Math.max(waitMs, 1000L));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }

    private static final class Response {
        final int code;
        final String body;
        final String retryAfter;
        final String rateRemaining;
        final String rateReset;

        Response(int code, String body, String retryAfter, String rateRemaining, String rateReset) {
            this.code = code;
            this.body = body;
            this.retryAfter = retryAfter;
            this.rateRemaining = rateRemaining;
            this.rateReset = rateReset;
        }

        boolean isRateLimited() {
            return code == 429 || "0".equals(rateRemaining) || retryAfter != null
                    || (body != null && body.toLowerCase(Locale.US).contains("rate limit"));
        }

        SafeJson json() throws IOException {
            try {
                return new SafeJson(body == null || body.isEmpty() ? "{}" : body);
            } catch (Exception e) {
                throw new IOException("Unexpected response from GitHub: " + abbreviate(body));
            }
        }

        String describe(String action) {
            String msg = "";
            try {
                msg = new JSONObject(body).optString("message", "");
            } catch (Exception ignored) {
            }
            switch (code) {
                case 401:
                    return "GitHub token is invalid or expired (401).";
                case 403:
                    return "Token has no write access to this repository (403). " + msg;
                case 404:
                    return "Repository or branch not found, or token cannot see it (404).";
                default:
                    return action + " failed: HTTP " + code + (msg.isEmpty() ? "" : " – " + msg);
            }
        }

        void ensureOk(String action) throws IOException {
            if (code < 200 || code >= 300) {
                throw new IOException(describe("Could not " + action));
            }
        }
    }

    /** JSONObject that never throws on nested lookups. */
    private static final class SafeJson extends JSONObject {
        SafeJson(String json) throws org.json.JSONException {
            super(json);
        }

        JSONObject getJSONObjectSafe(String key) {
            JSONObject o = optJSONObject(key);
            return o != null ? o : new JSONObject();
        }
    }

    /** Small helper used by SyncEngine to split work into request-sized batches. */
    public static List<List<Change>> batch(List<Change> changes, long maxBytes, int maxFiles) {
        List<List<Change>> batches = new ArrayList<>();
        List<Change> current = new ArrayList<>();
        long size = 0;
        for (Change c : changes) {
            long s = c.approxSize();
            if (!current.isEmpty() && (size + s > maxBytes || current.size() >= maxFiles)) {
                batches.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(c);
            size += s;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }
}
