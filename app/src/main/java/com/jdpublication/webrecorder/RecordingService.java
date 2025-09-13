package com.jdpublication.webrecorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.util.Objects;

public class RecordingService extends Service {

    private static final String TAG = "RecordingService";
    public static final String ACTION_RECORDING_STOPPED = "com.jdpublication.webrecorder.RECORDING_STOPPED";
    public static final String ACTION_PAUSE = "com.jdpublication.webrecorder.PAUSE";
    public static final String ACTION_RESUME = "com.jdpublication.webrecorder.RESUME";

    private static final String CHANNEL_ID = "RecordingServiceChannel";

    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;
    private int screenWidth;
    private int screenHeight;
    private MediaProjection.Callback mediaProjectionCallback;

    public static boolean isRecording = false;
    public static boolean isPaused = false;


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
        startForeground(1, notification);

        // Extract data from intent
        int resultCode = intent.getIntExtra("resultCode", -1);
        Intent data = intent.getParcelableExtra("data");
        String filename = intent.getStringExtra("filename");

        if (resultCode == 0 || data == null || filename == null) {
            Log.e(TAG, "Invalid data received, stopping service.");
            stopSelf();
            return START_NOT_STICKY;
        }

        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection is null, stopping service.");
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
                isRecording = true;
                isPaused = false;
                Log.d(TAG, "MediaRecorder started successfully.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to start MediaRecorder", e);
                stopSelf();
            }
        } else {
            Log.e(TAG, "Recorder initialization failed.");
            stopSelf();
        }

        return START_STICKY;
    }

    private void pauseRecording() {
        if (mediaRecorder != null && isRecording && !isPaused) {
            try {
                mediaRecorder.pause();
                isPaused = true;
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
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to resume MediaRecorder", e);
            }
        }
    }

    private boolean initRecorder(String filename) {
        mediaRecorder = new MediaRecorder();
        Uri videoUri = null;
        ContentResolver resolver = getContentResolver();

        try {
            String resolution = "480x854";
            int frameRate = 15;
            int bitRate = 1000000;
            int audioSource = MediaRecorder.AudioSource.MIC;
            int videoEncoder = MediaRecorder.VideoEncoder.H264;

            String[] dimensions = resolution.split("x");
            screenWidth = Integer.parseInt(dimensions[0]);
            screenHeight = Integer.parseInt(dimensions[1]);

            mediaRecorder.setAudioSource(audioSource);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(videoEncoder);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setVideoSize(screenWidth, screenHeight);
            mediaRecorder.setVideoEncodingBitRate(bitRate);
            mediaRecorder.setVideoFrameRate(frameRate);

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WebRecordings");
            values.put(MediaStore.Video.Media.TITLE, filename);
            values.put(MediaStore.Video.Media.DISPLAY_NAME, filename + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

            if (videoUri == null) {
                Log.e(TAG, "Failed to create new MediaStore record.");
                return false;
            }

            mediaRecorder.setOutputFile(Objects.requireNonNull(resolver.openFileDescriptor(videoUri, "w")).getFileDescriptor());
            mediaRecorder.prepare();
            return true;
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            Log.e(TAG, "MediaRecorder initialization failed", e);
            if (videoUri != null) resolver.delete(videoUri, null, null);
            if (mediaRecorder != null) mediaRecorder.release();
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

        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (RuntimeException e) {
                Log.e(TAG, "Error stopping MediaRecorder", e);
            }
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
        if (virtualDisplay != null) virtualDisplay.release();
        if (mediaProjection != null) {
            if (mediaProjectionCallback != null)
                mediaProjection.unregisterCallback(mediaProjectionCallback);
            mediaProjection.stop();
        }

        Intent intent = new Intent(ACTION_RECORDING_STOPPED);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Recording Service Channel", NotificationManager.IMPORTANCE_DEFAULT);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}