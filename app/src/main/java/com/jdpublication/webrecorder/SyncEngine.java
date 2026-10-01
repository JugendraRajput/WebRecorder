package com.jdpublication.webrecorder;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Incremental two-way sync between the local offline folders and the GitHub storage repo.
 *
 * Change detection uses git blob hashes, so only files whose content really differs are
 * transferred. A per-folder "base" (remote hashes after the last successful sync) lets the
 * engine tell "deleted on another device" apart from "new on this device".
 */
public final class SyncEngine {

    public enum Mode {
        /** Upload local changes, download remote changes, apply deletions both ways. */
        TWO_WAY,
        /** Only push local changes and queued deletions. Never touches local files. */
        UPLOAD_ONLY,
        /** Only pull remote changes. Never changes the cloud. */
        DOWNLOAD_ONLY
    }

    public interface Listener {
        void onLog(String line);

        void onProgress(int done, int total, String status);
    }

    public static final class Result {
        public int uploaded;
        public int downloaded;
        public int deletedRemote;
        public int deletedLocal;
        public int failed;
        public int unchanged;
        public long bytes;
        final Set<String> failedNames = Collections.synchronizedSet(new HashSet<>());
        final Set<String> committedNames = Collections.synchronizedSet(new HashSet<>());

        void add(Result o) {
            uploaded += o.uploaded;
            downloaded += o.downloaded;
            deletedRemote += o.deletedRemote;
            deletedLocal += o.deletedLocal;
            failed += o.failed;
            unchanged += o.unchanged;
            bytes += o.bytes;
        }

        public String summary() {
            return String.format(Locale.US, "↑ %d uploaded · ↓ %d downloaded · %d removed · %d unchanged%s",
                    uploaded, downloaded, deletedRemote + deletedLocal, unchanged,
                    failed > 0 ? " · " + failed + " failed" : "");
        }
    }

    private static final long MAX_BATCH_BYTES = 12L * 1024 * 1024;
    private static final int MAX_BATCH_FILES = 40;
    private static final int MAX_HISTORY = 500;

    private final Context context;
    private final GitHubStorage storage;
    private final Listener listener;
    private final AtomicBoolean cancelled;
    private final int downloadThreads;

    public SyncEngine(Context context, GitHubStorage storage, int downloadThreads, AtomicBoolean cancelled, Listener listener) {
        this.context = context.getApplicationContext();
        this.storage = storage;
        this.downloadThreads = Math.max(1, Math.min(downloadThreads, 8));
        this.cancelled = cancelled;
        this.listener = listener;
    }

    /** Remote folder → file count, used for UI summaries. */
    public Map<String, Integer> remoteFolderCounts() throws IOException {
        GitHubStorage.Head head = storage.getHead();
        Map<String, Map<String, GitHubStorage.RemoteFile>> all = storage.listAllFolders(head);
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Map<String, GitHubStorage.RemoteFile>> e : all.entrySet()) {
            int n = 0;
            for (String name : e.getValue().keySet()) {
                if (isPageFile(name)) n++;
            }
            counts.put(e.getKey(), n);
        }
        return counts;
    }

    /** Syncs the given folders (null = every local and cloud folder). */
    public Result sync(List<String> folders, Mode mode) throws IOException {
        log("Connecting to " + storage.getRepoLabel() + "…");
        GitHubStorage.Head head = storage.getHead();
        Map<String, Map<String, GitHubStorage.RemoteFile>> remote = storage.listAllFolders(head);

        Set<String> targets = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (folders == null) {
            if (mode != Mode.DOWNLOAD_ONLY) targets.addAll(OfflineStore.listAllLocalFolders(context));
            if (mode != Mode.UPLOAD_ONLY) targets.addAll(remote.keySet());
        } else {
            targets.addAll(folders);
        }

        List<String> history = new ArrayList<>();
        Result total = new Result();
        int index = 0;
        for (String folder : targets) {
            if (cancelled.get()) break;
            index++;
            log(String.format(Locale.US, "📁 [%d/%d] %s", index, targets.size(), folder));
            Map<String, GitHubStorage.RemoteFile> remoteFiles = remote.get(folder);
            if (remoteFiles == null) remoteFiles = new HashMap<>();
            FolderOutcome outcome = syncFolder(head, folder, remoteFiles, mode, history);
            head = outcome.head;
            total.add(outcome.result);
        }

        if (!history.isEmpty() && mode != Mode.DOWNLOAD_ONLY && !cancelled.get()) {
            try {
                head = appendHistory(head, history);
            } catch (IOException e) {
                log("⚠️ Could not update cloud history: " + e.getMessage());
            }
        }
        if (cancelled.get()) log("⏹ Sync cancelled.");
        log("✅ " + total.summary());
        return total;
    }

    private static final class FolderOutcome {
        GitHubStorage.Head head;
        Result result = new Result();
    }

    private FolderOutcome syncFolder(GitHubStorage.Head head, String folder,
                                     Map<String, GitHubStorage.RemoteFile> remoteFiles,
                                     Mode mode, List<String> history) throws IOException {
        FolderOutcome out = new FolderOutcome();
        out.head = head;
        Result res = out.result;

        File dir = OfflineStore.getFolderDirectory(context, folder);
        Map<String, String> base = OfflineStore.readSyncBase(context, folder);
        Set<String> pendingDeletes = new HashSet<>(OfflineStore.getPendingDeletionNames(context, folder));
        Map<String, String> localShas = hashLocalFolder(folder);

        Set<String> names = new HashSet<>();
        names.addAll(localShas.keySet());
        for (String n : remoteFiles.keySet()) if (isPageFile(n)) names.add(n);
        names.addAll(pendingDeletes);

        List<String> toUpload = new ArrayList<>();
        List<String> toDownload = new ArrayList<>();
        List<String> toDeleteRemote = new ArrayList<>();
        List<String> toDeleteLocal = new ArrayList<>();

        for (String name : names) {
            String local = localShas.get(name);
            GitHubStorage.RemoteFile rf = remoteFiles.get(name);
            String remoteSha = rf != null ? rf.sha : null;
            String baseSha = base.get(name);

            if (pendingDeletes.contains(name) && local != null) {
                // The page exists again (re-saved or re-downloaded): forget the old delete request.
                OfflineStore.clearPendingDeletion(context, folder, name);
            }
            if (pendingDeletes.contains(name) && local == null) {
                if (remoteSha != null && mode != Mode.DOWNLOAD_ONLY) toDeleteRemote.add(name);
                else if (remoteSha == null) OfflineStore.clearPendingDeletion(context, folder, name);
                continue;
            }
            if (local != null && remoteSha != null) {
                if (local.equals(remoteSha)) {
                    res.unchanged++;
                } else if (remoteSha.equals(baseSha)) {
                    if (mode != Mode.DOWNLOAD_ONLY) toUpload.add(name);       // changed here
                } else if (local.equals(baseSha)) {
                    if (mode != Mode.UPLOAD_ONLY) toDownload.add(name);       // changed in cloud
                } else {
                    // Changed on both sides (or never synced): keep the larger, more complete page.
                    File lf = new File(dir, name);
                    boolean localWins = lf.length() >= (rf != null ? rf.size : 0);
                    if (localWins && mode != Mode.DOWNLOAD_ONLY) toUpload.add(name);
                    else if (!localWins && mode != Mode.UPLOAD_ONLY) toDownload.add(name);
                    else res.unchanged++;
                }
            } else if (local != null) {
                if (baseSha != null && mode == Mode.TWO_WAY) {
                    toDeleteLocal.add(name);                                  // deleted on another device
                } else if (mode != Mode.DOWNLOAD_ONLY) {
                    toUpload.add(name);                                       // new here
                }
            } else if (remoteSha != null) {
                if (mode != Mode.UPLOAD_ONLY) toDownload.add(name);           // new in cloud
            }
        }

        int work = toUpload.size() + toDownload.size() + toDeleteRemote.size() + toDeleteLocal.size();
        if (work == 0) {
            log("   ✓ Already in sync (" + res.unchanged + " files)");
            JSONObject merged = mergeMetadataFromCloud(out.head, folder, remoteFiles, new HashSet<>());
            if (merged != null && mode != Mode.DOWNLOAD_ONLY && !localShas.isEmpty()) {
                out.head = pushMetadataIfChanged(out.head, folder, merged);
            }
            OfflineStore.writeSyncBase(context, folder, computeBase(base, localShas, shaMap(remoteFiles)));
            return out;
        }
        log(String.format(Locale.US, "   ↑ %d to upload · ↓ %d to download · 🗑 %d to remove",
                toUpload.size(), toDownload.size(), toDeleteRemote.size() + toDeleteLocal.size()));

        // ── 1. Downloads (parallel, verified) ──
        if (!toDownload.isEmpty()) {
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create folder " + folder);
            downloadAll(out.head, folder, toDownload, remoteFiles, localShas, res);
        }

        // ── 2. Local deletions (file was removed on another device) ──
        for (String name : toDeleteLocal) {
            File f = new File(dir, name);
            if (!f.exists() || f.delete()) {
                localShas.remove(name);
                OfflineStore.removeMetadataEntry(context, folder, name);
                res.deletedLocal++;
                log("   🗑 Removed locally (deleted in cloud): " + name);
            }
        }

        // ── 3. Uploads + remote deletions, batched into commits ──
        if (!cancelled.get() && (!toUpload.isEmpty() || !toDeleteRemote.isEmpty())) {
            List<GitHubStorage.Change> changes = new ArrayList<>();
            for (String name : toUpload) {
                changes.add(GitHubStorage.Change.upload(remotePath(folder, name), new File(dir, name)));
            }
            for (String name : toDeleteRemote) {
                changes.add(GitHubStorage.Change.delete(remotePath(folder, name)));
            }
            List<List<GitHubStorage.Change>> batches = GitHubStorage.batch(changes, MAX_BATCH_BYTES, MAX_BATCH_FILES);
            int done = 0;
            for (List<GitHubStorage.Change> batch : batches) {
                if (cancelled.get()) break;
                out.head = commitWithSplit(out.head, folder, batch, res);
                done += batch.size();
                progress(done, changes.size(), "Uploading " + folder);
            }
            for (String name : toUpload) {
                if (!res.committedNames.contains(name)) continue;
                history.add(event("upload", folder, name));
                res.uploaded++;
            }
            for (String name : toDeleteRemote) {
                if (!res.committedNames.contains(name)) continue;
                history.add(event(pendingReason(folder, name), folder, name));
                OfflineStore.clearPendingDeletion(context, folder, name);
                res.deletedRemote++;
            }
        }

        // ── 4. Metadata merge (titles / urls) ──
        Set<String> removed = new HashSet<>(toDeleteLocal);
        removed.addAll(toDeleteRemote);
        JSONObject merged = mergeMetadataFromCloud(out.head, folder, remoteFiles, removed);
        if (merged != null && mode != Mode.DOWNLOAD_ONLY && !cancelled.get()) {
            out.head = pushMetadataIfChanged(out.head, folder, merged);
        }

        // ── 5. Verify uploads & store new base ──
        Map<String, String> finalRemote = shaMap(remoteFiles);
        boolean remoteChanged = !toUpload.isEmpty() || !toDeleteRemote.isEmpty();
        if (remoteChanged) {
            try {
                finalRemote = shaMap(storage.listFolder(out.head, folder));
                for (String name : toUpload) {
                    String expected = localShas.get(name);
                    String actual = finalRemote.get(name);
                    if (res.committedNames.contains(name) && expected != null && !expected.equals(actual)) {
                        log("   ⚠️ Cloud copy of " + name + " does not match – will retry next sync");
                        res.failedNames.add(name);
                    }
                }
            } catch (IOException e) {
                log("   ⚠️ Could not verify uploads: " + e.getMessage());
            }
        }
        res.failed += res.failedNames.size();
        if (!cancelled.get()) {
            OfflineStore.writeSyncBase(context, folder, computeBase(base, localShas, finalRemote));
        }
        return out;
    }

    /** Base = files both sides agree on; disagreements keep their previous base entry. */
    private static Map<String, String> computeBase(Map<String, String> oldBase, Map<String, String> local,
                                                   Map<String, String> remote) {
        Map<String, String> base = new HashMap<>();
        Set<String> names = new HashSet<>(oldBase.keySet());
        names.addAll(local.keySet());
        names.addAll(remote.keySet());
        for (String n : names) {
            if (!isPageFile(n)) continue;
            String l = local.get(n);
            String r = remote.get(n);
            if (l != null && l.equals(r)) base.put(n, l);
            else if (l == null && r == null) continue;
            else if (oldBase.containsKey(n)) base.put(n, oldBase.get(n));
        }
        return base;
    }

    private static Map<String, String> shaMap(Map<String, GitHubStorage.RemoteFile> files) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, GitHubStorage.RemoteFile> e : files.entrySet()) out.put(e.getKey(), e.getValue().sha);
        return out;
    }

    private GitHubStorage.Head commitWithSplit(GitHubStorage.Head head, String folder,
                                               List<GitHubStorage.Change> batch, Result res) throws IOException {
        try {
            String msg = String.format(Locale.US, "%s: sync %d file(s) from %s", folder, batch.size(), deviceName());
            GitHubStorage.Head next = storage.commit(head, batch, msg);
            for (GitHubStorage.Change c : batch) {
                if (c.file != null) res.bytes += c.file.length();
                res.committedNames.add(c.path.substring(c.path.lastIndexOf('/') + 1));
            }
            log(String.format(Locale.US, "   ⬆ Committed %d change(s)", batch.size()));
            return next;
        } catch (IOException e) {
            if (batch.size() > 1) {
                log("   ↻ Batch too large or rejected, splitting… (" + e.getMessage() + ")");
                int mid = batch.size() / 2;
                head = commitWithSplit(head, folder, new ArrayList<>(batch.subList(0, mid)), res);
                return commitWithSplit(head, folder, new ArrayList<>(batch.subList(mid, batch.size())), res);
            }
            GitHubStorage.Change c = batch.get(0);
            String name = c.path.substring(c.path.lastIndexOf('/') + 1);
            res.failedNames.add(name);
            log("   ❌ " + name + ": " + e.getMessage());
            if (e.getMessage() != null && (e.getMessage().contains("401") || e.getMessage().contains("403")
                    || e.getMessage().contains("404") || e.getMessage().contains("rate limit"))) {
                throw e; // configuration problems: stop instead of failing every file
            }
            return head;
        }
    }

    private void downloadAll(GitHubStorage.Head head, String folder, List<String> names,
                             Map<String, GitHubStorage.RemoteFile> remoteFiles,
                             Map<String, String> localShas, Result res) {
        File dir = OfflineStore.getFolderDirectory(context, folder);
        ExecutorService pool = Executors.newFixedThreadPool(downloadThreads);
        AtomicInteger done = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        List<Future<?>> futures = new ArrayList<>();
        Set<String> failed = Collections.synchronizedSet(new HashSet<>());
        for (String name : names) {
            futures.add(pool.submit(() -> {
                if (cancelled.get()) return;
                GitHubStorage.RemoteFile rf = remoteFiles.get(name);
                try {
                    long n = storage.downloadFile(head, remotePath(folder, name), rf.sha, new File(dir, name));
                    bytes.addAndGet(n);
                    synchronized (localShas) {
                        localShas.put(name, rf.sha);
                    }
                    OfflineStore.rememberHash(context, folder, new File(dir, name), rf.sha);
                    log("   ⬇ " + name + " (" + OfflineStore.formatFileSize(n) + ")");
                } catch (Exception e) {
                    failed.add(name);
                    log("   ❌ " + name + ": " + e.getMessage());
                } finally {
                    progress(done.incrementAndGet(), names.size(), "Downloading " + folder);
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        res.downloaded += names.size() - failed.size();
        res.failedNames.addAll(failed);
        res.bytes += bytes.get();
    }

    private JSONObject mergeMetadataFromCloud(GitHubStorage.Head head, String folder,
                                              Map<String, GitHubStorage.RemoteFile> remoteFiles,
                                              Set<String> removed) {
        JSONObject local = OfflineStore.readFolderMetadata(context, folder);
        JSONObject merged = local;
        try {
            if (remoteFiles.containsKey(OfflineStore.METADATA_FILE_NAME)) {
                String text = storage.readText(head, remotePath(folder, OfflineStore.METADATA_FILE_NAME));
                if (text != null && !text.trim().isEmpty()) {
                    JSONObject remote = new JSONObject(text);
                    merged = OfflineStore.mergeMetadata(local, remote);
                }
            }
        } catch (Exception e) {
            // Never overwrite the cloud copy with a partial view: skip pushing metadata this time.
            log("   ⚠️ Could not read cloud metadata: " + e.getMessage());
            return null;
        }
        for (String name : removed) merged.remove(name);
        // Only keep entries for pages that still exist locally or in the cloud.
        File dir = OfflineStore.getFolderDirectory(context, folder);
        JSONObject cleaned = new JSONObject();
        for (Iterator<String> it = merged.keys(); it.hasNext(); ) {
            String key = it.next();
            boolean inCloud = remoteFiles.containsKey(key) && !removed.contains(key);
            if (new File(dir, key).exists() || inCloud) {
                try {
                    cleaned.put(key, merged.opt(key));
                } catch (Exception ignored) {
                }
            }
        }
        if (dir.exists()) OfflineStore.saveFolderMetadata(context, folder, cleaned);
        return cleaned;
    }

    private GitHubStorage.Head pushMetadataIfChanged(GitHubStorage.Head head, String folder,
                                                     JSONObject metadata) {
        try {
            String text = metadata.toString(2);
            Map<String, GitHubStorage.RemoteFile> current = storage.listFolder(head, folder);
            GitHubStorage.RemoteFile rf = current.get(OfflineStore.METADATA_FILE_NAME);
            boolean hasPages = false;
            for (String n : current.keySet()) if (isPageFile(n)) hasPages = true;
            if (!hasPages) {
                if (rf != null) {
                    List<GitHubStorage.Change> del = new ArrayList<>();
                    del.add(GitHubStorage.Change.delete(remotePath(folder, OfflineStore.METADATA_FILE_NAME)));
                    return storage.commit(head, del, folder + ": remove empty metadata");
                }
                return head;
            }
            String sha = GitHubStorage.gitBlobSha(text.getBytes(StandardCharsets.UTF_8));
            if (rf != null && sha.equals(rf.sha)) return head;
            List<GitHubStorage.Change> changes = new ArrayList<>();
            changes.add(GitHubStorage.Change.text(remotePath(folder, OfflineStore.METADATA_FILE_NAME), text));
            GitHubStorage.Head next = storage.commit(head, changes, folder + ": update metadata");
            log("   📝 Metadata updated in cloud");
            return next;
        } catch (Exception e) {
            log("   ⚠️ Metadata upload failed: " + e.getMessage());
            return head;
        }
    }

    private GitHubStorage.Head appendHistory(GitHubStorage.Head head, List<String> eventsJson) throws IOException {
        JSONArray existing = new JSONArray();
        try {
            String text = storage.readText(head, GitHubStorage.HISTORY_PATH);
            if (text != null && !text.trim().isEmpty()) existing = new JSONArray(text);
        } catch (Exception ignored) {
        }
        JSONArray merged = new JSONArray();
        try {
            for (int i = eventsJson.size() - 1; i >= 0; i--) merged.put(new JSONObject(eventsJson.get(i)));
            for (int i = 0; i < existing.length() && merged.length() < MAX_HISTORY; i++) merged.put(existing.get(i));
        } catch (Exception ignored) {
        }
        List<GitHubStorage.Change> changes = new ArrayList<>();
        String text;
        try {
            text = merged.toString(1);
        } catch (Exception e) {
            text = merged.toString();
        }
        changes.add(GitHubStorage.Change.text(GitHubStorage.HISTORY_PATH, text));
        return storage.commit(head, changes, "Update sync history");
    }

    private String event(String action, String folder, String name) {
        JSONObject o = new JSONObject();
        try {
            JSONObject meta = OfflineStore.readFolderMetadata(context, folder).optJSONObject(name);
            o.put("timestamp", System.currentTimeMillis() / 1000L);
            o.put("action", action);
            o.put("folder", folder);
            o.put("filename", name);
            o.put("title", meta != null ? meta.optString("title", name) : name);
            o.put("device", deviceName());
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private String pendingReason(String folder, String name) {
        return OfflineStore.isModeratedDeletion(context, folder, name) ? "moderated" : "delete";
    }

    private Map<String, String> hashLocalFolder(String folder) {
        Map<String, String> result = new HashMap<>();
        List<File> files = OfflineStore.listHtmlFiles(context, folder);
        int i = 0;
        for (File f : files) {
            if (cancelled.get()) break;
            try {
                result.put(f.getName(), OfflineStore.cachedHash(context, folder, f));
            } catch (IOException e) {
                log("   ⚠️ Cannot read " + f.getName() + ": " + e.getMessage());
            }
            if (++i % 50 == 0) progress(i, files.size(), "Checking " + folder);
        }
        OfflineStore.flushHashCache(context, folder);
        return result;
    }

    static boolean isPageFile(String name) {
        String l = name.toLowerCase(Locale.US);
        return l.endsWith(".mht") || l.endsWith(".html");
    }

    static String remotePath(String folder, String name) {
        return GitHubStorage.PAGES_ROOT + "/" + folder + "/" + name;
    }

    static String deviceName() {
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        return model.toLowerCase(Locale.US).startsWith(maker.toLowerCase(Locale.US)) ? model : (maker + " " + model).trim();
    }

    private void log(String line) {
        if (listener != null) listener.onLog(line);
    }

    private void progress(int done, int total, String status) {
        if (listener != null) listener.onProgress(done, total, status);
    }
}
