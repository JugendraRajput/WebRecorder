package com.jdpublication.webrecorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.app.PendingIntent;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class RecordingService extends Service {

    private static final String TAG = "RecordingService";
    public static final String ACTION_RECORDING_STARTED = "com.jdpublication.webrecorder.RECORDING_STARTED";
    public static final String ACTION_RECORDING_PAUSED = "com.jdpublication.webrecorder.RECORDING_PAUSED";
    public static final String ACTION_RECORDING_RESUMED = "com.jdpublication.webrecorder.RECORDING_RESUMED";
    public static final String ACTION_RECORDING_STOPPED = "com.jdpublication.webrecorder.RECORDING_STOPPED";
    public static final String ACTION_RECORDING_ERROR = "com.jdpublication.webrecorder.RECORDING_ERROR";
    public static final String ACTION_PAUSE = "com.jdpublication.webrecorder.PAUSE";
    public static final String ACTION_RESUME = "com.jdpublication.webrecorder.RESUME";
    public static final String ACTION_STOP = "com.jdpublication.webrecorder.STOP";
    private static final int NOTIFICATION_ID = 1;
    private static final long MIN_RECORDING_MS = 1500L;
    public static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID = "RecordingServiceChannel";

    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;
    private int screenWidth;
    private int screenHeight;
    private MediaProjection.Callback mediaProjectionCallback;
    private ParcelFileDescriptor outputFileDescriptor;
    private Uri videoUri;
    private String currentTitle = "";

    public static boolean isRecording = false;
    public static boolean isPaused = false;

    private boolean recorderStarted = false;
    private long recordingStartTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (action != null) {
            switch (action) {
                case ACTION_PAUSE:
                    if (!isRecording) { stopSelf(); return START_NOT_STICKY; }
                    pauseRecording();
                    return START_NOT_STICKY;
                case ACTION_RESUME:
                    if (!isRecording) { stopSelf(); return START_NOT_STICKY; }
                    resumeRecording();
                    return START_NOT_STICKY;
                case ACTION_STOP:
                    stopSelf();
                    return START_NOT_STICKY;
            }
        }

        Log.d(TAG, "onStartCommand received for starting");
        // Start Foreground Service
        currentTitle = intent.getStringExtra("filename") == null ? "" : intent.getStringExtra("filename");
        Notification notification = buildNotification(false);
        int foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType);

        // Extract data from intent
        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");
        String filename = intent.getStringExtra("filename");

        if (resultCode == 0 || data == null || filename == null) {
            Log.e(TAG, "Invalid data received, stopping service.");
            broadcastError("Recording could not start. Please try again.");
            stopSelf();
            return START_NOT_STICKY;
        }

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null, stopping service.");
            broadcastError("Screen capture permission was not accepted.");
            stopSelf();
            return START_NOT_STICKY;
        }

        mediaProjectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (isRecording) stopSelf();
            }
        };
        mediaProjection.registerCallback(mediaProjectionCallback, null);

        if (initRecorder(filename)) {
            createVirtualDisplay();
            try {
                mediaRecorder.start();
                recorderStarted = true;
                recordingStartTime = System.currentTimeMillis();
                isRecording = true;
                isPaused = false;
                broadcastState(ACTION_RECORDING_STARTED);
                Log.d(TAG, "MediaRecorder started successfully.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to start MediaRecorder", e);
                broadcastError("Recording could not start on this device.");
                stopSelf();
            }
        } else {
            Log.e(TAG, "Recorder initialization failed.");
            broadcastError("Recorder setup failed. Please try another page or filename.");
            stopSelf();
        }

        return START_STICKY;
    }

    private void pauseRecording() {
        if (mediaRecorder != null && isRecording && !isPaused) {
            try {
                mediaRecorder.pause();
                isPaused = true;
                updateNotification();
                broadcastState(ACTION_RECORDING_PAUSED);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to pause MediaRecorder", e);
            }
        }
    }

    private void resumeRecording() {
        if (mediaRecorder != null && isRecording && isPaused) {
            try {
                mediaRecorder.resume();
                isPaused = false;
                updateNotification();
                broadcastState(ACTION_RECORDING_RESUMED);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to resume MediaRecorder", e);
            }
        }
    }

    private boolean initRecorder(String filename) {

        mediaRecorder = new MediaRecorder();
        videoUri = null;

        try {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int[] size = pickVideoSize(metrics.widthPixels, metrics.heightPixels);
            screenWidth = size[0];
            screenHeight = size[1];

            int frameRate = 30;
            int bitRate = Math.max(4_000_000, Math.min(12_000_000, screenWidth * screenHeight * 4));
            boolean withAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

            // Order matters: sources → output format → encoders → sizes → output file.
            if (withAudio) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if (withAudio) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioEncodingBitRate(128000);
                mediaRecorder.setAudioSamplingRate(44100);
            }
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(screenWidth, screenHeight);
            mediaRecorder.setVideoFrameRate(frameRate);
            mediaRecorder.setVideoEncodingBitRate(bitRate);

            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, safeFileName(filename) + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WebRecordings");
                values.put(MediaStore.Video.Media.IS_PENDING, 1); // hidden from the gallery until finished
            }

            videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (videoUri == null) return false;

            outputFileDescriptor = resolver.openFileDescriptor(videoUri, "w");
            if (outputFileDescriptor == null) {
                return false;
            }
            mediaRecorder.setOutputFile(outputFileDescriptor.getFileDescriptor());
            mediaRecorder.prepare();
            Log.d(TAG, "Recorder ready " + screenWidth + "x" + screenHeight + " @" + bitRate + "bps audio=" + withAudio);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Recorder init failed", e);
            discardOutput();
            if (mediaRecorder != null) mediaRecorder.release();
            mediaRecorder = null;
            return false;
        }
    }

    /** Even dimensions the device's H.264 encoder supports, keeping the screen aspect ratio. */
    private static int[] pickVideoSize(int width, int height) {
        int w = width & ~1;
        int h = height & ~1;
        MediaCodecInfo.VideoCapabilities caps = null;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(type)) {
                        caps = info.getCapabilitiesForType(type).getVideoCapabilities();
                        break;
                    }
                }
                if (caps != null) break;
            }
        } catch (Exception ignored) {
        }
        if (caps == null) {
            // Unknown encoder limits: stay within 1080p which every device supports.
            double scale = Math.min(1.0, 1920.0 / Math.max(w, h));
            return new int[]{((int) (w * scale)) & ~15, ((int) (h * scale)) & ~15};
        }
        double scale = 1.0;
        for (int i = 0; i < 20; i++) {
            int cw = ((int) (w * scale)) & ~15;
            int ch = ((int) (h * scale)) & ~15;
            if (cw > 0 && ch > 0 && caps.isSizeSupported(cw, ch)) {
                return new int[]{cw, ch};
            }
            scale *= 0.9;
        }
        return new int[]{720, 1280};
    }

    private static String safeFileName(String name) {
        String clean = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) clean = "Recording";
        if (clean.length() > 120) clean = clean.substring(0, 120).trim();
        return clean;
    }

    private void discardOutput() {
        try {
            if (outputFileDescriptor != null) {
                outputFileDescriptor.close();
                outputFileDescriptor = null;
            }
        } catch (Exception ignored) {
        }
        if (videoUri != null) {
            try {
                getContentResolver().delete(videoUri, null, null);
            } catch (Exception ignored) {
            }
            videoUri = null;
        }
    }

    private void publishOutput() {
        if (videoUri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(videoUri, values, null, null);
        } catch (Exception e) {
            Log.w(TAG, "Could not publish recording", e);
        }
    }

    private Notification buildNotification(boolean paused) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent toggle = new Intent(this, RecordingService.class).setAction(paused ? ACTION_RESUME : ACTION_PAUSE);
        PendingIntent togglePi = PendingIntent.getService(this, 11, toggle, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, RecordingService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 12, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(paused ? "Recording paused" : "Recording screen")
                .setContentText(currentTitle.isEmpty() ? "Web Recorder" : currentTitle)
                .setSmallIcon(R.drawable.ic_record)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPi)
                .addAction(paused ? R.drawable.ic_play : R.drawable.ic_pause, paused ? "Resume" : "Pause", togglePi)
                .addAction(R.drawable.ic_stop, "Stop", stopPi)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            try {
                nm.notify(NOTIFICATION_ID, buildNotification(isPaused));
            } catch (SecurityException ignored) {
            }
        }
    }

    private void createVirtualDisplay() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        virtualDisplay = mediaProjection.createVirtualDisplay(TAG, screenWidth, screenHeight, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mediaRecorder.getSurface(), null, null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        boolean wasRecording = recorderStarted;
        isRecording = false;
        isPaused = false;
        String message = null;

        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "virtual display release", e);
        }

        boolean keepFile = false;
        if (mediaRecorder != null) {
            if (recorderStarted) {
                long duration = System.currentTimeMillis() - recordingStartTime;
                try {
                    if (duration >= MIN_RECORDING_MS) {
                        mediaRecorder.stop();
                        keepFile = true;
                    }
                } catch (RuntimeException e) {
                    // stop() throws when no frames were captured; the file is unusable.
                    Log.e(TAG, "MediaRecorder.stop failed", e);
                }
            }
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
            } catch (Exception ignored) {
            }
            mediaRecorder = null;
        }

        try {
            if (outputFileDescriptor != null) {
                outputFileDescriptor.close();
                outputFileDescriptor = null;
            }
        } catch (Exception ignored) {
        }

        if (keepFile) {
            publishOutput();
            message = getString(R.string.recording_saved);
        } else {
            discardOutput();
            if (wasRecording) message = getString(R.string.recording_too_short);
        }

        if (mediaProjection != null) {
            try {
                if (mediaProjectionCallback != null) mediaProjection.unregisterCallback(mediaProjectionCallback);
                mediaProjection.stop();
            } catch (Exception ignored) {
            }
            mediaProjection = null;
        }

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);

        Intent intent = new Intent(ACTION_RECORDING_STOPPED);
        if (message != null) intent.putExtra(EXTRA_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void broadcastState(String action) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(action));
    }

    private void broadcastError(String message) {
        Intent intent = new Intent(ACTION_RECORDING_ERROR);
        intent.putExtra(EXTRA_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
