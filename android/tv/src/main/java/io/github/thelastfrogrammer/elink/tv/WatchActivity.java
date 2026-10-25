package io.github.thelastfrogrammer.elink.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ScaleDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * Watching: the camera full screen, the status over its lower part (OK hides or shows it), Left and Right for the other
 * cameras, print events as a short banner at the top. When the phone cannot be reached it says so calmly and keeps
 * trying, slower and slower; if the phone's address changed, it finds the phone again on the network. A refused token
 * goes back to pairing. The screen stays on while this is open.
 */
public final class WatchActivity extends Activity {
    /** A link problem shows as a card only after this long, so a quick reconnect does not flash one. */
    static final long NOTICE_AFTER_MS = 4_000, REDRAW_MS = 15_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PhoneStore store;
    private PrinterLink link;
    private String base, token;
    private ConnectivityManager.NetworkCallback network;
    private PhoneFinder finder;
    private final Set<String> triedBases = new HashSet<>();

    private FrameView picture;
    private TextView pictureNote, noticeTitle, noticeBody, noticeRetry, top, headline, file, percent, printLine, faults, hint, bannerTitle, bannerDetail;
    private View dim, bannerStripe;
    private LinearLayout notice, overlay, numbers, banner, progressRow;
    private ProgressBar bar, thinBar;

    // What is known (main thread only).
    private JSONObject status;
    private long statusAt, downSince, linkRetryAt, lastLiveAt, cameraRetryAt;
    private PrinterLink.State linkState = PrinterLink.State.CONNECTING;
    private String linkWhy = "", linkWords = "", cameraWords = "";
    private boolean everLive, statusHidden, started, destroyed, camerasKnown;
    private int unreachable;
    private final List<PhoneApi.Camera> cameras = new ArrayList<>();
    private int cameraIndex = -1;
    private final ArrayDeque<StatusText.Banner> banners = new ArrayDeque<>();
    private boolean bannerUp;

    // Pictures: decoded on the camera thread into reused bitmaps; the newest waits in `pending` for the main thread.
    private static final class Frame { final String camera; final Bitmap bitmap; Frame(String camera, Bitmap bitmap) { this.camera = camera; this.bitmap = bitmap; } }
    private final AtomicReference<Frame> pending = new AtomicReference<>();
    private final Object spareLock = new Object();
    private Bitmap spare, retired;
    private int maxW = 1920, maxH = 1080;

    private final Runnable tick = new Runnable() {
        private long redrawn;
        @Override public void run() {
            long now = System.currentTimeMillis();
            showNotice(); showCameraNote();
            if (now - redrawn > REDRAW_MS) { redrawn = now; showStatus(); }
            if (started) main.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new PhoneStore(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DisplayMetrics m = getResources().getDisplayMetrics();
        maxW = Math.max(1280, Math.max(m.widthPixels, m.heightPixels)); maxH = Math.max(720, Math.min(m.widthPixels, m.heightPixels));
        statusHidden = store.statusHidden();
        build();
        showStatus(); showCameraNote(); showNotice(); showOverlay(false);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        base = store.base(); token = store.token();
        if (base == null || token == null) { startActivity(new Intent(this, base == null ? FindActivity.class : PairActivity.class)); finish(); return; }
        downSince = SystemClock.elapsedRealtime();   // a steady clock: the wall clock can jump when the TV sets its time
        if (FindActivity.networking) { open(); listenToNetwork(true); }
        main.post(tick);
    }
    @Override protected void onStop() {
        super.onStop();
        started = false;
        if (link != null) link.stop();
        link = null;
        listenToNetwork(false); stopFinder();
        main.removeCallbacks(tick);
    }
    @Override protected void onDestroy() { destroyed = true; main.removeCallbacksAndMessages(null); super.onDestroy(); }

    // ------------------------------------------------------------------ the remote

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (e.getRepeatCount() == 0) toggleStatus();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (e.getRepeatCount() == 0) switchCamera(-1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (e.getRepeatCount() == 0) switchCamera(1); return true;
            default: return super.onKeyDown(code, e);
        }
    }

    void toggleStatus() {
        statusHidden = !statusHidden; store.statusHidden(statusHidden);
        showOverlay(true);
        if (!statusHidden) speak(StatusText.of(status, statusAt, System.currentTimeMillis(), DateFormat.getTimeFormat(this), TimeZone.getDefault()).spoken);
    }
    boolean statusShown() { return !statusHidden; }

    void switchCamera(int step) {
        if (cameras.size() < 2) return;
        cameraIndex = (cameraIndex + step + cameras.size()) % cameras.size();
        PhoneApi.Camera c = cameras.get(cameraIndex);
        store.camera(c.id);
        retire(picture.clear()); cameraRetryAt = 0; cameraWords = "";
        if (link != null) link.camera(c.id);
        showCameraNote(); showStatus();
        banner(new StatusText.Banner(c.name, "Camera " + (cameraIndex + 1) + " of " + cameras.size(), false, false), 3_000);
    }
    String cameraShown() { return cameraIndex < 0 || cameraIndex >= cameras.size() ? null : cameras.get(cameraIndex).id; }

    // ------------------------------------------------------------------ the link (callbacks come on its threads)

    private void open() {
        PrinterLink[] mine = new PrinterLink[1];
        mine[0] = new PrinterLink(new PhoneApi(base), token, new PrinterLink.Listener() {
            @Override public void link(PrinterLink.State state, long retryAtMs, String why, String words) { on(() -> linkChanged(state, retryAtMs, why, words)); }
            @Override public void status(JSONObject s) { on(() -> statusArrived(s)); }
            @Override public void event(JSONObject e) { on(() -> eventArrived(e)); }
            @Override public void cameras(List<PhoneApi.Camera> all) { on(() -> camerasArrived(all)); }
            @Override public void frame(String camera, byte[] jpeg) { frameArrived(mine[0], camera, jpeg); }
            @Override public void camera(String camera, long retryAtMs, String words) { on(() -> cameraChanged(camera, retryAtMs, words)); }
            @Override public void unpaired() { on(WatchActivity.this::unpaired); }
            private void on(Runnable r) { main.post(() -> { if (!destroyed && link == mine[0]) r.run(); }); }
        });
        link = mine[0];
        String id = cameraShown(); if (id != null) link.camera(id);
        link.start();
    }

    void linkChanged(PrinterLink.State state, long retryAtMs, String why, String words) {
        long now = System.currentTimeMillis();
        if (state == PrinterLink.State.LIVE) { everLive = true; unreachable = 0; lastLiveAt = now; stopFinder(); }
        else if (linkState == PrinterLink.State.LIVE) { downSince = SystemClock.elapsedRealtime(); lastLiveAt = now; }
        if (state == PrinterLink.State.WAITING) {
            linkWhy = why; linkWords = words; linkRetryAt = retryAtMs;
            if (PrinterLink.UNREACHABLE.equals(why) && ++unreachable >= 2) startFinder();   // perhaps the phone's address changed
        }
        linkState = state;
        showNotice(); showStatus(); showCameraNote();
    }
    void statusArrived(JSONObject s) {
        status = s; statusAt = System.currentTimeMillis();
        showStatus();
    }
    void eventArrived(JSONObject e) {
        StatusText.Banner b = StatusText.banner(e);
        if (b != null) banner(b, b.showMs());
    }
    void camerasArrived(List<PhoneApi.Camera> all) {
        String current = cameraShown(); if (current == null) current = store.camera();
        cameras.clear(); cameras.addAll(all); camerasKnown = true;
        cameraIndex = cameras.isEmpty() ? -1 : 0;
        for (int i = 0; i < cameras.size(); i++) if (cameras.get(i).id.equals(current)) cameraIndex = i;
        String id = cameraShown();
        if (link != null) link.camera(id);
        if (id == null) retire(picture.clear());
        showCameraNote(); showStatus();
    }
    void cameraChanged(String camera, long retryAtMs, String words) {
        if (!camera.equals(cameraShown())) return;
        cameraRetryAt = retryAtMs; cameraWords = words == null ? "" : words;
        showCameraNote();
    }
    private void unpaired() {
        store.forgetToken();
        startActivity(new Intent(this, PairActivity.class).putExtra(PairActivity.EXTRA_WHY, "The phone no longer knows this TV (it may have been removed in Other apps). Pair it again to keep watching."));
        finish();
    }

    // ------------------------------------------------------------------ pictures

    private void frameArrived(PrinterLink from, String camera, byte[] jpeg) {
        Bitmap b = decode(jpeg);
        if (b == null) return;
        Frame old = pending.getAndSet(new Frame(camera, b));
        if (old != null) toSpare(old.bitmap);   // never shown: the newer one replaces it, and a show is already on its way
        else main.post(() -> showPending(from));
    }
    private void showPending(PrinterLink from) {
        Frame f = pending.getAndSet(null);
        if (f == null) return;
        if (destroyed || link != from || !f.camera.equals(cameraShown())) { toSpare(f.bitmap); return; }
        PhoneApi.Camera c = cameras.get(cameraIndex);
        boolean first = !picture.showing();
        retire(picture.show(f.bitmap, c.rotation, c.mirror));
        cameraRetryAt = 0; cameraWords = "";
        if (first) { showCameraNote(); showStatus(); }
    }
    /** Shows this picture as if it came from the current camera (tests). */
    void showPicture(Bitmap b) {
        PhoneApi.Camera c = cameraIndex >= 0 ? cameras.get(cameraIndex) : null;
        retire(picture.show(b, c == null ? 0 : c.rotation, c != null && c.mirror));
        showCameraNote(); showStatus();
    }
    /**
     * A bitmap just taken off the screen is reused only after the next one has been shown too, so a frame still being
     * drawn is never written over.
     */
    private void retire(Bitmap old) { Bitmap before = retired; retired = old; if (before != null) toSpare(before); }
    private void toSpare(Bitmap b) { synchronized (spareLock) { if (spare == null && b != null && b.isMutable()) spare = b; } }

    private Bitmap decode(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        // A large camera (an added 4K one) is shrunk to the screen as it is decoded: the TV has little memory.
        while (o.outWidth / sample > maxW || o.outHeight / sample > maxH) sample *= 2;
        o.inJustDecodeBounds = false; o.inSampleSize = sample; o.inMutable = true;
        synchronized (spareLock) { o.inBitmap = spare; spare = null; }
        try {
            try { Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o); if (b != null) return b; }
            catch (IllegalArgumentException wrongSize) { /* the spare is too small: decode into a new one */ }
            if (o.inBitmap == null) return null;
            o.inBitmap = null;
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        } catch (OutOfMemoryError tooBig) { return null; }   // skip this picture; the next may fit
    }

    // ------------------------------------------------------------------ the phone's address changed

    private void startFinder() {
        if (finder != null || !started || !FindActivity.networking) return;
        finder = new PhoneFinder(this, new PhoneFinder.Listener() {
            @Override public void found(String name, String found) { if (!found.equals(base) && triedBases.add(found)) tryAddress(found); }
            @Override public void lost(String name) { }
            @Override public void failed() { finder = null; }
        });
        finder.start();
        showNotice();
    }
    private void stopFinder() { if (finder != null) finder.stop(); finder = null; }
    /** Asks a phone found on the network for the status with this TV's token: only the same phone says yes. */
    private void tryAddress(String found) {
        String t = token;
        Thread check = new Thread(() -> {
            boolean same;
            try { new PhoneApi(found).status(t); same = true; } catch (IOException no) { same = false; }
            boolean yes = same;
            main.post(() -> {
                if (!yes || destroyed || !started || linkState == PrinterLink.State.LIVE) return;
                store.phone(found); base = found;
                if (link != null) link.stop();
                stopFinder(); unreachable = 0;
                open();
            });
        }, "tv-check");
        check.setDaemon(true); check.start();
    }

    private void listenToNetwork(boolean on) {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            if (on && network == null) {
                network = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network n) { main.post(() -> { if (link != null) link.retryNow(); }); }
                };
                cm.registerDefaultNetworkCallback(network);
            } else if (!on && network != null) { cm.unregisterNetworkCallback(network); network = null; }
        } catch (RuntimeException unavailable) { network = null; }   // without it the waits still end by themselves
    }

    // ------------------------------------------------------------------ showing

    void showStatus() {
        long now = System.currentTimeMillis();
        StatusText.Shown s = StatusText.of(status, statusAt, now, DateFormat.getTimeFormat(this), TimeZone.getDefault());
        boolean live = linkState == PrinterLink.State.LIVE;
        StringBuilder line = new StringBuilder();
        if (!s.printer.isEmpty()) line.append(s.printer);
        String fresh = live ? s.freshness : status == null ? "Connecting to the phone…"
            : "No contact with the phone since " + StatusText.clock(lastLiveAt > 0 ? lastLiveAt : statusAt, now, DateFormat.getTimeFormat(this), TimeZone.getDefault()) + " · showing its last status";
        line.append(line.length() > 0 ? "  ·  " : "").append(fresh);
        PhoneApi.Camera c = cameraIndex >= 0 ? cameras.get(cameraIndex) : null;
        if (c != null) {
            line.append("  ·  ").append(c.name);
            if (cameras.size() > 1) line.append(" (").append(cameraIndex + 1).append(" of ").append(cameras.size()).append(')');
            if (!c.live) line.append(", newest kept picture");
        }
        top.setText(line);
        headline.setText(s.headline);
        headline.setVisibility(status == null ? View.GONE : View.VISIBLE);   // nothing yet: the top line says it
        headline.setTextColor(!s.faults.isEmpty() ? Ui.WARN : s.attention ? Ui.CALM : Ui.INK);
        String under = !s.file.isEmpty() ? s.file : s.detail;
        file.setText(under); file.setVisibility(under.isEmpty() ? View.GONE : View.VISIBLE);
        boolean progress = s.progress >= 0 && (s.active || "complete".equals(s.phase));
        progressRow.setVisibility(progress ? View.VISIBLE : View.GONE);
        if (progress) {
            bar.setProgress(s.progress); percent.setText(s.progressText);
            bar.setContentDescription("Progress " + s.progress + " percent");
        }
        String printed = StatusText.printLine(s);
        printLine.setText(printed); printLine.setVisibility(printed.isEmpty() ? View.GONE : View.VISIBLE);
        faults.setText(s.faults); faults.setVisibility(s.faults.isEmpty() ? View.GONE : View.VISIBLE);
        numbers.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < s.numbers.size(); i++) {
            if (i % 2 == 0) { row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); numbers.addView(row, Ui.gap(Ui.wide(), this, i == 0 ? 0 : 14)); }
            StatusText.Number n = s.numbers.get(i);
            LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL);
            tile.addView(Ui.text(this, n.label, Ui.SMALL, Ui.MUTED));
            TextView value = Ui.heading(this, n.value, 28); value.setSingleLine(); value.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(value);
            tile.setContentDescription(n.spoken);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 2 == 1) p.leftMargin = Ui.dp(this, 20);
            row.addView(tile, p);
        }
        if (s.numbers.size() % 2 == 1 && row != null) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        numbers.setVisibility(s.numbers.isEmpty() ? View.GONE : View.VISIBLE);
        hint.setText(cameras.size() > 1 ? "OK hides this  ·  ◀ ▶ other cameras" : "OK hides this");
        // Shown faded while the phone is away: it is the last status, not the present one.
        overlayContent().setAlpha(live || status == null ? 1f : 0.6f);
        thinBar.setProgress(Math.max(0, s.progress));
        thinBar.setVisibility(statusHidden && progress ? View.VISIBLE : View.GONE);
        overlay.setContentDescription(s.spoken);
    }
    private View overlayContent() { return overlay.getChildAt(0); }

    void showOverlay(boolean animate) {
        float to = statusHidden ? 0f : 1f;
        overlay.animate().cancel();
        if (!statusHidden) overlay.setVisibility(View.VISIBLE);
        if (animate) overlay.animate().alpha(to).setDuration(180).withEndAction(() -> { if (statusHidden) overlay.setVisibility(View.GONE); }).start();
        else { overlay.setAlpha(to); overlay.setVisibility(statusHidden ? View.GONE : View.VISIBLE); }
        showStatus(); showCameraNote();
    }

    /** The card saying the phone cannot be reached, after a few seconds without it. */
    void showNotice() {
        long now = System.currentTimeMillis();
        boolean show = linkState != PrinterLink.State.LIVE && (SystemClock.elapsedRealtime() - downSince >= NOTICE_AFTER_MS || !everLive && linkState == PrinterLink.State.WAITING);
        notice.setVisibility(show ? View.VISIBLE : View.GONE);
        dim.setVisibility(show && picture.showing() ? View.VISIBLE : View.GONE);
        if (!show) return;
        String title, body;
        if (PrinterLink.BUSY.equals(linkWhy)) {
            title = "The phone is busy";
            body = "It is serving as many screens as it can" + (linkWords.isEmpty() ? "." : ": " + linkWords) + " This TV waits its turn.";
        } else if (PrinterLink.REFUSED.equals(linkWhy)) {
            title = "The phone did not answer as expected";
            body = linkWords.isEmpty() ? "It may be updating. This TV keeps trying." : "It said: " + linkWords;
        } else if (everLive) {
            title = "Can't reach the phone right now";
            body = "Link Workshop has to keep running on the phone, on the same Wi-Fi as this TV. The picture and the status come back by themselves.";
        } else {
            title = "Can't reach the phone";
            body = "Check that Link Workshop is open on the phone, that Settings › Automations & integrations › Other apps is turned on, and that the phone is on this Wi-Fi.";
        }
        if (finder != null) body += " Looking for it on the network too, in case its address changed.";
        noticeTitle.setText(title); noticeBody.setText(body);
        long left = (linkRetryAt - now + 999) / 1000;
        noticeRetry.setText(linkState == PrinterLink.State.CONNECTING || left <= 0 ? "Trying now…" : left < 60 ? "Trying again in " + left + " s" : "Trying again in " + StatusText.duration(left));
    }

    /** What the camera area says when there is no picture (centred), or why the picture is still (a corner chip). */
    void showCameraNote() {
        long now = System.currentTimeMillis();
        String words;
        boolean waiting = cameraRetryAt > now;
        long left = (cameraRetryAt - now + 999) / 1000;
        String again = left < 60 ? "Trying again in " + left + " s" : "Trying again in " + StatusText.duration(left);
        if (!camerasKnown) words = linkState == PrinterLink.State.LIVE ? "Opening the camera…" : "";
        else if (cameras.isEmpty()) words = "The phone shares no camera. The status is below.";
        else if (picture.showing()) words = waiting ? "Picture paused · " + again.toLowerCase(java.util.Locale.ROOT) : "";
        else if (waiting) words = "No picture from " + cameras.get(cameraIndex).name.toLowerCase(java.util.Locale.ROOT) + " right now." + (cameraWords.isEmpty() ? "" : "\nThe phone says: " + cameraWords) + "\n" + again;
        else words = "Opening " + cameras.get(cameraIndex).name.toLowerCase(java.util.Locale.ROOT) + "…";
        if (notice.getVisibility() == View.VISIBLE && !picture.showing()) words = "";   // the notice says it all
        pictureNote.setText(words);
        pictureNote.setVisibility(words.isEmpty() ? View.GONE : View.VISIBLE);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) pictureNote.getLayoutParams();
        boolean chip = picture.showing();
        p.gravity = chip ? Gravity.TOP | Gravity.START : Gravity.CENTER;
        p.topMargin = chip ? Ui.dp(this, Ui.TOP) : 0; p.leftMargin = chip ? Ui.dp(this, Ui.SIDE) : Ui.dp(this, 120); p.rightMargin = chip ? 0 : Ui.dp(this, 120);
        p.bottomMargin = chip || statusHidden ? 0 : Ui.dp(this, 125);   // centred in the picture above the status (half its height), not under it
        pictureNote.setLayoutParams(p);
        pictureNote.setTextSize(chip ? Ui.SMALL : Ui.BODY + 2);
        pictureNote.setBackground(chip ? Ui.round(0xCC0E1417, Ui.dp(this, 20)) : null);
        pictureNote.setPadding(chip ? Ui.dp(this, 18) : 0, chip ? Ui.dp(this, 8) : 0, chip ? Ui.dp(this, 18) : 0, chip ? Ui.dp(this, 8) : 0);
        PhoneApi.Camera c = cameraIndex >= 0 && cameraIndex < cameras.size() ? cameras.get(cameraIndex) : null;
        picture.setContentDescription(c == null ? "No camera" : c.name + (c.live ? ", live picture" : ", the newest kept picture"));
    }

    /** A print event (or a camera change) as a banner at the top; several wait their turn. */
    void banner(StatusText.Banner b, long ms) {
        banners.add(b);
        if (!bannerUp) nextBanner(ms);
        else if (banners.size() > 4) banners.poll();   // a burst of events: keep the newest few
    }
    private void nextBanner(long ms) {
        StatusText.Banner b = banners.poll();
        if (b == null) { bannerUp = false; banner.animate().alpha(0f).setDuration(220).withEndAction(() -> banner.setVisibility(View.GONE)).start(); return; }
        bannerUp = true;
        bannerTitle.setText(b.title);
        bannerDetail.setText(b.detail); bannerDetail.setVisibility(b.detail.isEmpty() ? View.GONE : View.VISIBLE);
        ((GradientDrawable) bannerStripe.getBackground()).setColor(b.alert ? Ui.WARN : b.good ? Ui.GOOD : Ui.ACCENT);
        banner.setContentDescription(b.title + (b.detail.isEmpty() ? "" : ". " + b.detail));
        banner.animate().cancel(); banner.setVisibility(View.VISIBLE); banner.setAlpha(0f); banner.animate().alpha(1f).setDuration(220).start();
        long showFor = ms > 0 ? ms : b.showMs();
        main.postDelayed(() -> { if (!destroyed) nextBanner(banners.isEmpty() ? 0 : banners.peek().showMs()); }, showFor);
    }
    boolean bannerShown() { return bannerUp; }

    @SuppressWarnings("deprecation")   // announceForAccessibility: the one way to say a whole status on Android 7.1 to 16
    private void speak(String words) {
        AccessibilityManager a = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (a != null && a.isEnabled()) overlay.announceForAccessibility(words);
    }

    // ------------------------------------------------------------------ the views

    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        picture = new FrameView(this);
        picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        dim = new View(this); dim.setBackgroundColor(0x99000000); dim.setVisibility(View.GONE);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        pictureNote = Ui.text(this, "", Ui.BODY + 2, Ui.MUTED); pictureNote.setGravity(Gravity.CENTER);
        root.addView(pictureNote, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // The status: over the lower part of the picture, on a shade that keeps it readable over any picture.
        overlay = new LinearLayout(this); overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xF2070B0D, 0xD9070B0D, 0x00070B0D}));
        overlay.setPadding(Ui.dp(this, Ui.SIDE), Ui.dp(this, 72), Ui.dp(this, Ui.SIDE), Ui.dp(this, Ui.TOP));
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        top = Ui.text(this, "", Ui.SMALL, Ui.MUTED); top.setSingleLine(); top.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(top, Ui.wide());
        LinearLayout columns = new LinearLayout(this); columns.setOrientation(LinearLayout.HORIZONTAL); columns.setGravity(Gravity.BOTTOM);
        LinearLayout print = new LinearLayout(this); print.setOrientation(LinearLayout.VERTICAL);
        headline = Ui.heading(this, "", 40); headline.setMaxLines(2); headline.setEllipsize(TextUtils.TruncateAt.END);
        headline.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        print.addView(headline, Ui.gap(Ui.wide(), this, 6));
        file = Ui.text(this, "", Ui.BODY, Ui.MUTED); file.setMaxLines(2); file.setEllipsize(TextUtils.TruncateAt.END);
        print.addView(file, Ui.gap(Ui.wide(), this, 2));
        progressRow = new LinearLayout(this); progressRow.setOrientation(LinearLayout.HORIZONTAL); progressRow.setGravity(Gravity.CENTER_VERTICAL);
        bar = bar(12);
        progressRow.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(this, 12), 1f));
        percent = Ui.heading(this, "", 34); percent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams pp = Ui.wrap(); pp.leftMargin = Ui.dp(this, 20);
        progressRow.addView(percent, pp);
        print.addView(progressRow, Ui.gap(Ui.wide(), this, 10));
        printLine = Ui.text(this, "", Ui.BODY, Ui.INK);
        print.addView(printLine, Ui.gap(Ui.wide(), this, 2));
        faults = Ui.text(this, "", Ui.BODY, Ui.WARN);
        print.addView(faults, Ui.gap(Ui.wide(), this, 6));
        columns.addView(print, new LinearLayout.LayoutParams(0, -2, 1.25f));
        numbers = new LinearLayout(this); numbers.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f); np.leftMargin = Ui.dp(this, 40);
        columns.addView(numbers, np);
        content.addView(columns, Ui.wide());
        hint = Ui.text(this, "", 16, 0xFF7F949C); hint.setGravity(Gravity.END);
        content.addView(hint, Ui.gap(Ui.wide(), this, 10));
        overlay.addView(content, Ui.wide());
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        thinBar = bar(6); thinBar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, Ui.dp(this, 6), Gravity.BOTTOM);
        tp.leftMargin = Ui.dp(this, Ui.SIDE); tp.rightMargin = Ui.dp(this, Ui.SIDE); tp.bottomMargin = Ui.dp(this, Ui.TOP);
        root.addView(thinBar, tp);

        notice = new LinearLayout(this); notice.setOrientation(LinearLayout.VERTICAL); notice.setGravity(Gravity.CENTER_HORIZONTAL);
        notice.setBackground(Ui.round(0xF2182227, Ui.dp(this, 26)));
        notice.setPadding(Ui.dp(this, 44), Ui.dp(this, 34), Ui.dp(this, 44), Ui.dp(this, 34));
        noticeTitle = Ui.heading(this, "", Ui.TITLE + 2); noticeTitle.setGravity(Gravity.CENTER);
        noticeTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        notice.addView(noticeTitle, Ui.wide());
        noticeBody = Ui.text(this, "", Ui.BODY, Ui.MUTED); noticeBody.setGravity(Gravity.CENTER);
        notice.addView(noticeBody, Ui.gap(Ui.wide(), this, 12));
        noticeRetry = Ui.text(this, "", Ui.SMALL, Ui.ACCENT); noticeRetry.setGravity(Gravity.CENTER);
        notice.addView(noticeRetry, Ui.gap(Ui.wide(), this, 16));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Ui.dp(this, 640), -2, Gravity.CENTER);
        cp.bottomMargin = Ui.dp(this, 60);
        root.addView(notice, cp);

        banner = new LinearLayout(this); banner.setOrientation(LinearLayout.HORIZONTAL); banner.setVisibility(View.GONE);
        banner.setBackground(Ui.round(0xF21C2A30, Ui.dp(this, 20)));
        banner.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 32), Ui.dp(this, 18));
        banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bannerStripe = new View(this); bannerStripe.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 3)));
        banner.addView(bannerStripe, new LinearLayout.LayoutParams(Ui.dp(this, 6), -1));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        bannerTitle = Ui.heading(this, "", 28); words.addView(bannerTitle);
        bannerDetail = Ui.text(this, "", Ui.SMALL + 1, Ui.MUTED); words.addView(bannerDetail);
        LinearLayout.LayoutParams wp = Ui.wrap(); wp.leftMargin = Ui.dp(this, 18);
        banner.addView(words, wp);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bp.topMargin = Ui.dp(this, Ui.TOP + 6);
        root.addView(banner, bp);
        setContentView(root);
    }

    /** A rounded progress bar in the accent colour. */
    private ProgressBar bar(int heightDp) {
        ProgressBar b = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        b.setIndeterminate(false); b.setMax(100);
        float r = Ui.dp(this, heightDp / 2f);
        Drawable fill = new ScaleDrawable(Ui.round(Ui.ACCENT, r), Gravity.START, 1f, -1f);
        LayerDrawable layers = new LayerDrawable(new Drawable[] {Ui.round(0xB32A3A40, r), fill});
        layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress);
        b.setProgressDrawable(layers);
        return b;
    }
}
