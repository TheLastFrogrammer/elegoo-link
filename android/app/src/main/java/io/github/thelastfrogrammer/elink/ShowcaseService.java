package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import java.io.File;

/**
 * Records the screen to an MP4 for the showcase tour, after the person allows it (Android asks every time). Video only, no
 * sound. The file stays in the app's cache until the person saves it somewhere. Stops when asked, from its notification, or
 * when Android ends the capture.
 */
public final class ShowcaseService extends Service {
    static final String EXTRA_RESULT = "result", EXTRA_DATA = "data", ACTION_STOP = "stop";
    private static final String CHANNEL = "showcase";
    /** Called on the main thread once recording has started (true) or could not (false), and when a recording is finished. */
    interface Listener { void started(boolean ok, String problem); void finished(File video); }
    static Listener listener;
    static volatile boolean recording;

    private MediaProjection projection; private VirtualDisplay display; private MediaRecorder recorder; private File out;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { finish(); return START_NOT_STICKY; }
        if (recording || intent == null) return START_NOT_STICKY;
        // Android 14 and later: the service must be in the foreground, as a screen capture, before the capture is taken.
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && notifications.getNotificationChannel(CHANNEL) == null)
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Showcase recording", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, ShowcaseService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification note = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Recording the showcase")
            .setContentText("The tour is being recorded. Tap Stop to end it early.").setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(9, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION); else startForeground(9, note);
            Intent data = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(EXTRA_DATA, Intent.class) : intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED), data);
            if (projection == null) throw new IllegalStateException("Android did not allow the screen recording.");
            projection.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { main.post(ShowcaseService.this::finish); } }, main);
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int[] size = videoSize(metrics.widthPixels, metrics.heightPixels);
            out = new File(new File(getCacheDir(), "showcase"), "link-workshop-showcase.mp4"); out.getParentFile().mkdirs(); out.delete();
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(size[0], size[1]); recorder.setVideoFrameRate(30); recorder.setVideoEncodingBitRate(size[0] * size[1] * 6);
            recorder.setOutputFile(out.getPath()); recorder.prepare();
            display = projection.createVirtualDisplay("showcase", size[0], size[1], metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder.getSurface(), null, null);
            recorder.start(); recording = true;
            if (listener != null) listener.started(true, "");
        } catch (Exception failure) {
            String problem = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            if (listener != null) listener.started(false, problem);
        }
        return START_NOT_STICKY;
    }

    /** Video size for a screen: at most 1080 wide, both sides even (encoders need it), keeping the shape. */
    static int[] videoSize(int width, int height) {
        double scale = width > 1080 ? 1080.0 / width : 1;
        return new int[] {Math.max(2, (int) (width * scale) / 2 * 2), Math.max(2, (int) (height * scale) / 2 * 2)};
    }

    private boolean finished;
    private void finish() {
        if (finished) return; finished = true;
        boolean had = recording; recording = false;
        if (recorder != null) try { recorder.stop(); } catch (RuntimeException tooShort) { had = false; }
        release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        if (listener != null) listener.finished(had && out != null && out.length() > 0 ? out : null);
    }
    private void release() {
        if (display != null) { display.release(); display = null; }
        if (recorder != null) { recorder.release(); recorder = null; }
        if (projection != null) { MediaProjection p = projection; projection = null; try { p.stop(); } catch (RuntimeException ignored) { } }
    }
    @Override public void onDestroy() { if (recording) finish(); super.onDestroy(); }
}
