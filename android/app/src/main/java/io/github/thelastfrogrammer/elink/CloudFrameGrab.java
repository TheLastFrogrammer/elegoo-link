package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.agora.base.VideoFrame;
import io.agora.rtc2.ChannelMediaOptions;
import io.agora.rtc2.Constants;
import io.agora.rtc2.IRtcEngineEventHandler;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.RtcEngineConfig;
import io.agora.rtc2.video.IVideoFrameObserver;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One picture from the printer's cloud video with no window at all, so it works with the app closed and the screen off:
 * joins the printer's video channel with Agora's native library as the cloud camera page does (live profile, role host,
 * nothing published, no audio), takes the first decoded frame, and leaves. The printer joins its channel on its own when
 * a viewer does; nothing is sent to the printer. One grab at a time in the app, and leave() ends it at once (another view
 * wants the channel: one join per account).
 */
final class CloudFrameGrab {
    interface Done {
        /** A JPEG of the printer's camera, or null with what happened (for the camera log). Called on the main thread. */
        void done(byte[] jpeg, String what);
    }

    private static final ExecutorService ENGINE = Executors.newSingleThreadExecutor();   // create, join, leave and destroy in order
    private static Handler main() { return new Handler(Looper.getMainLooper()); }
    private static volatile CloudFrameGrab running;
    /** The native library could not load or start on this phone: not tried again this app run. */
    private static volatile String broken;

    static boolean busy() { return running != null; }
    static String broken() { return broken; }
    /** Ends a running grab now, without a picture (a camera view is about to join). */
    static void leave() { CloudFrameGrab g = running; if (g != null) g.finish(null, "left for another camera view"); }

    private final AtomicBoolean finished = new AtomicBoolean();
    private final Done done;
    private final long startedAt = SystemClock.elapsedRealtime();
    private RtcEngine engine;
    private volatile boolean sawPrinter;

    private CloudFrameGrab(Done done) { this.done = done; }

    /** Starts a grab (false when one is already running or the library cannot run here). Gives up after `timeoutMs`. */
    static boolean start(Context context, String appId, String channel, String token, int uid, long timeoutMs, Done done) {
        if (running != null || broken != null) return false;
        CloudFrameGrab grab = new CloudFrameGrab(done); running = grab;
        Context app = context.getApplicationContext();
        ENGINE.execute(() -> grab.join(app, appId, channel, token, uid));
        main().postDelayed(() -> grab.finish(null, grab.sawPrinter ? "the printer joined but sent no picture within " + timeoutMs / 1000 + " s"
            : "the printer did not join its video channel within " + timeoutMs / 1000 + " s"), timeoutMs);
        return true;
    }

    private long since() { return SystemClock.elapsedRealtime() - startedAt; }

    private void join(Context app, String appId, String channel, String token, int uid) {
        if (finished.get()) return;
        try {
            RtcEngineConfig config = new RtcEngineConfig();
            config.mContext = app; config.mAppId = appId; config.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING;
            RtcEngineConfig.LogConfig log = new RtcEngineConfig.LogConfig();   // the library's own log: small, errors only, in the cache
            log.filePath = new File(app.getCacheDir(), "rtc.log").getAbsolutePath(); log.fileSizeInKB = 512; log.level = Constants.LOG_LEVEL_ERROR;
            config.mLogConfig = log;
            config.mEventHandler = new IRtcEngineEventHandler() {
                @Override public void onUserJoined(int user, int elapsed) { sawPrinter = true; }
                @Override public void onError(int code) { if (code == 109 || code == 110 || code == 101 || code == 102) finish(null, "the cloud refused the camera access (" + code + ")"); }
                @Override public void onConnectionStateChanged(int state, int reason) { if (state == 5) finish(null, "the connection failed (" + reason + ")"); }
            };
            engine = RtcEngine.create(config);
            engine.enableVideo(); engine.enableLocalVideo(false); engine.enableLocalAudio(false); engine.muteAllRemoteAudioStreams(true);
            engine.registerVideoFrameObserver(new IVideoFrameObserver() {
                @Override public boolean onRenderVideoFrame(String channelId, int user, VideoFrame frame) { take(frame); return true; }
                @Override public boolean onCaptureVideoFrame(int type, VideoFrame frame) { return true; }
                @Override public boolean onPreEncodeVideoFrame(int type, VideoFrame frame) { return true; }
                @Override public boolean onMediaPlayerVideoFrame(VideoFrame frame, int id) { return true; }
                @Override public int getVideoFrameProcessMode() { return PROCESS_MODE_READ_ONLY; }
                @Override public int getVideoFormatPreference() { return VIDEO_PIXEL_I420; }
                @Override public boolean getRotationApplied() { return false; }
                @Override public boolean getMirrorApplied() { return false; }
                @Override public int getObservedFramePosition() { return POSITION_PRE_RENDERER; }
            });
            ChannelMediaOptions options = new ChannelMediaOptions();
            options.channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING; options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER;
            options.autoSubscribeVideo = true; options.autoSubscribeAudio = false;
            options.publishCameraTrack = false; options.publishMicrophoneTrack = false; options.enableAudioRecordingOrPlayout = false;
            int result = engine.joinChannel(token, channel, uid, options);
            if (result != 0) finish(null, "joining the video channel failed (" + result + ")");
        } catch (Throwable failed) {   // UnsatisfiedLinkError and the like: this phone cannot run the library
            broken = failed.getClass().getSimpleName();
            finish(null, "the native video library could not start (" + broken + ")");
        }
    }

    /** The first decoded frame: kept, then the grab ends. Frames after it are ignored. */
    private void take(VideoFrame frame) {
        if (finished.get()) return;
        byte[] jpeg = null;
        VideoFrame.I420Buffer i420 = null;
        try {
            i420 = frame.getBuffer().toI420();
            int width = i420.getWidth(), height = i420.getHeight();
            byte[] nv21 = nv21(width, height, i420.getDataY(), i420.getStrideY(), i420.getDataU(), i420.getStrideU(), i420.getDataV(), i420.getStrideV());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (new YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(new Rect(0, 0, width, height), 85, out)) jpeg = turn(out.toByteArray(), frame.getRotation());
        } catch (Throwable unreadable) { jpeg = null; }
        finally { if (i420 != null) i420.release(); }
        if (jpeg != null) finish(jpeg, "picture after " + since() + " ms");
    }

    /** A frame the printer sends turned (rarely needed: the CC2 sends it upright). */
    private static byte[] turn(byte[] jpeg, int rotation) {
        if (rotation % 360 == 0) return jpeg;
        Bitmap picture = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (picture == null) return jpeg;
        Matrix matrix = new Matrix(); matrix.postRotate(rotation);
        Bitmap turned = Bitmap.createBitmap(picture, 0, 0, picture.getWidth(), picture.getHeight(), matrix, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); turned.compress(Bitmap.CompressFormat.JPEG, 85, out);
        return out.toByteArray();
    }

    /** I420 planes (with their row strides) as one NV21 picture: the Y plane, then V and U interleaved. Pure, for tests. */
    static byte[] nv21(int width, int height, ByteBuffer y, int strideY, ByteBuffer u, int strideU, ByteBuffer v, int strideV) {
        int chromaWidth = (width + 1) / 2, chromaHeight = (height + 1) / 2;
        byte[] out = new byte[width * height + 2 * chromaWidth * chromaHeight];
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) out[row * width + col] = y.get(row * strideY + col);
        int at = width * height;
        for (int row = 0; row < chromaHeight; row++) for (int col = 0; col < chromaWidth; col++) {
            out[at++] = v.get(row * strideV + col); out[at++] = u.get(row * strideU + col);
        }
        return out;
    }

    /** Once: reports on the main thread, then leaves the channel and frees the library on its own thread. */
    private void finish(byte[] jpeg, String what) {
        if (!finished.compareAndSet(false, true)) return;
        if (running == this) running = null;
        main().post(() -> done.done(jpeg, what));
        ENGINE.execute(() -> {
            RtcEngine e = engine; engine = null;
            if (e == null) return;
            try { e.registerVideoFrameObserver(null); e.leaveChannel(); } catch (Throwable ignored) { }
            try { RtcEngine.destroy(); } catch (Throwable ignored) { }
        });
    }
}
