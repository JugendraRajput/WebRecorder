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
                    pauseRecording();
                    return START_STICKY;
                case ACTION_RESUME:
                    resumeRecording();
                    return START_STICKY;
            }
        }

        Log.d(TAG, "onStartCommand received for starting");
        // Start Foreground Service
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("Screen Recording").setContentText("Recording in progress...").setSmallIcon(R.drawable.ic_record).build();
        int foregroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            foregroundServiceType |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        ServiceCompat.startForeground(this, 1, notification, foregroundServiceType);

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
                broadcastState(ACTION_RECORDING_RESUMED);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to resume MediaRecorder", e);
            }
        }
    }

    private boolean initRecorder(String filename) {

        mediaRecorder = new MediaRecorder();
        Uri videoUri = null;

        try {

            DisplayMetrics metrics = getResources().getDisplayMetrics();
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;

            int frameRate = 30;
            int bitRate = 6 * 1000 * 1000;

            // 1️⃣ Sources FIRST
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }

            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

            // 2️⃣ Output format BEFORE encoders
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            // 3️⃣ Encoders AFTER output format
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {

                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                mediaRecorder.setAudioEncodingBitRate(128000);
                mediaRecorder.setAudioSamplingRate(44100);
            }

            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

            // 4️⃣ Video config
            mediaRecorder.setVideoSize(screenWidth, screenHeight);
            mediaRecorder.setVideoFrameRate(frameRate);
            mediaRecorder.setVideoEncodingBitRate(bitRate);

            // 5️⃣ File
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/WebRecordings");
            values.put(MediaStore.Video.Media.DISPLAY_NAME, filename + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");

            videoUri = resolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

            if (videoUri == null) return false;

            outputFileDescriptor = resolver.openFileDescriptor(videoUri, "w");
            if (outputFileDescriptor == null) {
                return false;
            }

            mediaRecorder.setOutputFile(outputFileDescriptor.getFileDescriptor());

            mediaRecorder.prepare();

            return true;

        } catch (Exception e) {

            Log.e(TAG, "Recorder init failed", e);

            if (videoUri != null)
                getContentResolver().delete(videoUri, null, null);

            if (mediaRecorder != null)
                mediaRecorder.release();

            mediaRecorder = null;
            return false;
        }
    }

    private void createVirtualDisplay() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        virtualDisplay = mediaProjection.createVirtualDisplay(TAG, screenWidth, screenHeight, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mediaRecorder.getSurface(), null, null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        isRecording = false;
        isPaused = false;

        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }

            if (mediaRecorder != null && recorderStarted) {

                long duration = System.currentTimeMillis() - recordingStartTime;

                if (duration > 1000) {
                    mediaRecorder.stop();
                }

                mediaRecorder.reset();
                mediaRecorder.release();
            }

            if (outputFileDescriptor != null) {
                outputFileDescriptor.close();
                outputFileDescriptor = null;
            }

        } catch (Exception e) {
            Log.e(TAG, "Safe stop error", e);
        }

        if (mediaProjection != null) {
            if (mediaProjectionCallback != null)
                mediaProjection.unregisterCallback(mediaProjectionCallback);

            mediaProjection.stop();
        }

        stopForeground(true);

        Intent intent = new Intent(ACTION_RECORDING_STOPPED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Recording Service Channel", NotificationManager.IMPORTANCE_DEFAULT);
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
