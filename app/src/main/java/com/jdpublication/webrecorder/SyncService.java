package com.jdpublication.webrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Foreground service that runs cloud syncs so they keep going when the user leaves the
 * screen or turns it off. Screens observe it through {@link Observer}.
 */
public class SyncService extends Service {

    private static final String TAG = "SyncService";
    private static final String CHANNEL_ID = "cloud_sync";
    private static final int NOTIFICATION_ID = 42;
    private static final String EXTRA_MODE = "mode";
    private static final String EXTRA_FOLDERS = "folders";
    private static final String EXTRA_QUIET = "quiet";
    private static final String ACTION_CANCEL = "com.jdpublication.webrecorder.SYNC_CANCEL";
    private static final int MAX_LOG = 200;

    public interface Observer {
        void onSyncLog(String line);

        void onSyncProgress(int done, int total, String status);

        void onSyncStateChanged(boolean running, String summary);
    }

    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<>();
    private static final Deque<String> LOG = new ArrayDeque<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final AtomicInteger PENDING_JOBS = new AtomicInteger();
    private static volatile boolean running;
    private static volatile String status = "Idle";
    private static volatile int progressDone;
    private static volatile int progressTotal;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    private long lastNotificationUpdate;

    // ───────────── Public API ─────────────

    public static void start(Context context, SyncEngine.Mode mode, @Nullable ArrayList<String> folders) {
        start(context, mode, folders, false);
    }

    /** quiet = no toast-style summary log lines (used for automatic delete pushes). */
    public static void start(Context context, SyncEngine.Mode mode, @Nullable ArrayList<String> folders, boolean quiet) {
        Intent intent = new Intent(context, SyncService.class);
        intent.putExtra(EXTRA_MODE, mode.name());
        if (folders != null) intent.putStringArrayListExtra(EXTRA_FOLDERS, folders);
        intent.putExtra(EXTRA_QUIET, quiet);
        PENDING_JOBS.incrementAndGet();
        ContextCompat.startForegroundService(context, intent);
    }

    public static void cancel(Context context) {
        Intent intent = new Intent(context, SyncService.class);
        intent.setAction(ACTION_CANCEL);
        context.startService(intent);
    }

    public static boolean isRunning() {
        return running || PENDING_JOBS.get() > 0;
    }

    public static String getStatus() {
        return status;
    }

    public static int getProgressDone() {
        return progressDone;
    }

    public static int getProgressTotal() {
        return progressTotal;
    }

    public static List<String> recentLog() {
        synchronized (LOG) {
            return new ArrayList<>(LOG);
        }
    }

    public static void clearLog() {
        synchronized (LOG) {
            LOG.clear();
        }
    }

    public static void addObserver(Observer o) {
        OBSERVERS.addIfAbsent(o);
    }

    public static void removeObserver(Observer o) {
        OBSERVERS.remove(o);
    }

    public static void log(String line) {
        String stamped = "[" + TIME.format(new Date()) + "] " + line;
        synchronized (LOG) {
            while (LOG.size() >= MAX_LOG) LOG.pollFirst();
            LOG.addLast(stamped);
        }
        MAIN.post(() -> {
            for (Observer o : OBSERVERS) o.onSyncLog(stamped);
        });
    }

    // ───────────── Service ─────────────

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Cloud sync", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows progress while offline pages sync with the cloud");
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground("Preparing sync…", 0, 0);
        if (intent == null) {
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled.set(true);
            log("⏹ Cancelling…");
            stopIfIdle();
            return START_NOT_STICKY;
        }

        SyncEngine.Mode mode;
        try {
            mode = SyncEngine.Mode.valueOf(intent.getStringExtra(EXTRA_MODE));
        } catch (Exception e) {
            mode = SyncEngine.Mode.TWO_WAY;
        }
        ArrayList<String> folders = intent.getStringArrayListExtra(EXTRA_FOLDERS);
        boolean quiet = intent.getBooleanExtra(EXTRA_QUIET, false);
        SyncEngine.Mode finalMode = mode;
        worker.execute(() -> runJob(finalMode, folders, quiet));
        return START_NOT_STICKY;
    }

    private void runJob(SyncEngine.Mode mode, @Nullable List<String> folders, boolean quiet) {
        running = true;
        cancelled.set(false);
        acquireWakeLock();
        notifyState(true, null);
        String summary;
        boolean ok = false;
        try {
            if (!OfflineStore.isCloudConfigured(this)) {
                throw new IllegalStateException("Cloud storage is not set up. Open Cloud Sync and enter your GitHub repository and token.");
            }
            GitHubStorage storage = OfflineStore.createCloudStorage(this);
            String label = mode == SyncEngine.Mode.UPLOAD_ONLY ? "Upload" : mode == SyncEngine.Mode.DOWNLOAD_ONLY ? "Download" : "Sync";
            if (!quiet) log("▶ " + label + (folders == null ? " (all folders)" : " " + folders));
            SyncEngine engine = new SyncEngine(this, storage, OfflineStore.getSyncThreads(this), cancelled, new SyncEngine.Listener() {
                @Override
                public void onLog(String line) {
                    log(line);
                }

                @Override
                public void onProgress(int done, int total, String s) {
                    publishProgress(done, total, s);
                }
            });
            SyncEngine.Result result = engine.sync(folders, mode);
            summary = (cancelled.get() ? "Cancelled · " : "") + result.summary();
            ok = !cancelled.get() && result.failed == 0;
            OfflineStore.saveLastSync(this, summary);
        } catch (Exception e) {
            Log.e(TAG, "sync failed", e);
            summary = "Sync failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log("❌ " + summary);
        } finally {
            releaseWakeLock();
            running = false;
            PENDING_JOBS.decrementAndGet();
        }
        status = summary;
        notifyState(false, summary);
        showFinishedNotification(ok, summary, quiet);
        stopIfIdle();
    }

    private void publishProgress(int done, int total, String s) {
        progressDone = done;
        progressTotal = total;
        status = s;
        MAIN.post(() -> {
            for (Observer o : OBSERVERS) o.onSyncProgress(done, total, s);
        });
        long now = System.currentTimeMillis();
        if (now - lastNotificationUpdate > 700 || done == total) {
            lastNotificationUpdate = now;
            startInForeground(s, done, total);
        }
    }

    private void notifyState(boolean isRunning, String summary) {
        MAIN.post(() -> {
            for (Observer o : OBSERVERS) o.onSyncStateChanged(isRunning, summary);
        });
    }

    private void startInForeground(String text, int done, int total) {
        Intent open = new Intent(this, SyncActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent cancel = new Intent(this, SyncService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelPi = PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cloud_sync)
                .setContentTitle("Syncing offline pages")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_stop, "Cancel", cancelPi);
        if (total > 0) b.setProgress(total, Math.min(done, total), false);
        else b.setProgress(0, 0, true);
        Notification n = b.build();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type);
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
        }
    }

    private void showFinishedNotification(boolean ok, String summary, boolean quiet) {
        if (notificationManager == null || quiet) return;
        Intent open = new Intent(this, SyncActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(ok ? R.drawable.ic_check_circle : R.drawable.ic_alert_corrupt)
                .setContentTitle(ok ? "Cloud sync complete" : "Cloud sync finished with problems")
                .setContentText(summary)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(summary))
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .build();
        try {
            notificationManager.notify(NOTIFICATION_ID + 1, n);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS not granted
        }
    }

    private void stopIfIdle() {
        MAIN.post(() -> {
            if (!running && PENDING_JOBS.get() <= 0) {
                PENDING_JOBS.set(0);
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        });
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebRecorder:CloudSync");
            wakeLock.acquire(3 * 60 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        cancelled.set(true);
        worker.shutdownNow();
        releaseWakeLock();
        running = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
