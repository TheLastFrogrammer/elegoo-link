package io.github.thelastfrogrammer.elink;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The two live tests of the Camera check (run off the main thread): one camera-access request to Elegoo, and one picture
 * from the printer's camera stream on this network. Neither sends anything to the printer. Results are one line each,
 * without addresses or tokens.
 */
final class CameraCheck {
    private CameraCheck() { }

    /** "issued in 215 ms", "answered after … but gave no access", or why it failed. */
    static String cloudAccess(Context context) {
        long start = SystemClock.elapsedRealtime();
        try {
            CloudAccountStore store = new CloudAccountStore(context); CloudLogin.Account account = store.load();
            if (account == null) return "no saved account; sign in again in Settings";
            CloudApi api = new CloudApi(store.china(), account, CloudApi.agent(context), CloudApi::https);
            CloudApi.AgoraCredential issued = api.agoraCredential(); if (api.account() != account) store.save(api.account());
            long took = SystemClock.elapsedRealtime() - start;
            return issued.rtcToken.isEmpty() || issued.rtcUserId.isEmpty() ? "answered after " + took + " ms but gave no access" : "issued in " + took + " ms";
        } catch (Exception failed) {
            return "failed after " + (SystemClock.elapsedRealtime() - start) + " ms: " + Diagnostics.scrub(failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
        }
    }

    /** Opens the stream for one picture (at most 12 s), then closes it: "first picture after 640 ms, 1280x720", or why not. */
    static String localStream(String url, NetworkRoute route) {
        CountDownLatch done = new CountDownLatch(1); String[] result = {null}; long start = SystemClock.elapsedRealtime();
        MjpegPlayer[] test = new MjpegPlayer[1];
        try {
            test[0] = new MjpegPlayer(url, route.http(), new MjpegPlayer.Listener() {
                public void frame(Bitmap image) { if (result[0] == null) result[0] = "first picture after " + (SystemClock.elapsedRealtime() - start) + " ms, " + image.getWidth() + "x" + image.getHeight(); done.countDown(); }
                public void error(String text) { if (result[0] == null) result[0] = "failed after " + (SystemClock.elapsedRealtime() - start) + " ms: " + Diagnostics.scrub(text); done.countDown(); }
            });
            if (!done.await(12, TimeUnit.SECONDS)) result[0] = "no picture within 12 s";
        } catch (Exception failed) { result[0] = "could not start: " + Diagnostics.scrub(String.valueOf(failed.getMessage())); }
        finally { if (test[0] != null) test[0].close(); }
        return result[0];
    }
}
