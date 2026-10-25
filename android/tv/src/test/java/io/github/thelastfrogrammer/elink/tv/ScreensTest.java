package io.github.thelastfrogrammer.elink.tv;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The screens on a 1080p TV (960×540 dp), driven by hand without the network: what each says, that every control can be
 * reached and named, and what the remote's keys do. With -Dscreenshots=<dir> each is also written as a 1920×1080 PNG.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "w960dp-h540dp-land-television-xhdpi")
public class ScreensTest {
    private static final int W = 1920, H = 1080;

    @Before public void offline() throws Exception {
        FindActivity.networking = false; prefs().edit().clear().commit();
        // A TV is never in touch mode: the remote's keys move the focus, and buttons take it.
        Object thread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
        ((android.app.Instrumentation) thread.getClass().getMethod("getInstrumentation").invoke(thread)).setInTouchMode(false);
    }
    @After public void online() { FindActivity.networking = true; }

    private static android.content.SharedPreferences prefs() { return RuntimeEnvironment.getApplication().getSharedPreferences("phone", Context.MODE_PRIVATE); }
    private static void paired() { prefs().edit().putString("base", "http://192.168.1.20:8765").putString("token", "lw1_test").commit(); }
    private static void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    private static void shot(Activity a, String name) throws Exception {
        View root = a.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, W, H);
        String dir = System.getProperty("screenshots", "");
        if (dir.isEmpty()) return;
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        File f = new File(dir, name + ".png"); f.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
    }

    /** Every control can take the remote's focus, says what it is, and lies on the screen (not cut off by the overscan margins). */
    private static void reachableAndNamed(Activity a) {
        List<View> controls = new ArrayList<>(); collect(a.getWindow().getDecorView(), controls);
        assertFalse("something to focus", controls.isEmpty());
        for (View v : controls) {
            if (v.getVisibility() != View.VISIBLE || !v.isShown()) continue;
            assertTrue(v + " can take focus", v.isFocusable());
            CharSequence said = v.getContentDescription() != null ? v.getContentDescription() : v instanceof TextView ? ((TextView) v).getText() : allText(v);
            assertTrue(v + " is named", said != null && said.toString().trim().length() > 0 || v instanceof android.widget.EditText);
            int[] at = new int[2]; v.getLocationInWindow(at);
            assertTrue(v + " inside the safe area", at[0] >= 90 && at[1] >= 50 && at[0] + v.getWidth() <= W - 90 && at[1] + v.getHeight() <= H - 50);
        }
    }
    private static void collect(View v, List<View> out) {
        if (v.isClickable() || v.isFocusable() && !(v instanceof ViewGroup)) { out.add(v); }
        if (v.isFocusable() && v instanceof ViewGroup && !v.isClickable()) fail(v + ": a container that can take the focus itself would hide it");
        if (v instanceof ViewGroup && !(v.isClickable())) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) collect(((ViewGroup) v).getChildAt(i), out);
    }
    private static String allText(View v) {
        if (v instanceof TextView) return ((TextView) v).getText().toString();
        StringBuilder b = new StringBuilder();
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) b.append(allText(((ViewGroup) v).getChildAt(i))).append(' ');
        return b.toString();
    }
    private static View focus(Activity a) { return a.getWindow().getDecorView().findFocus(); }
    private static String screenText(Activity a) { return allText(a.getWindow().getDecorView()).replace(' ', ' '); }

    // ------------------------------------------------------------------ finding the phone

    @Test public void findThePhone() throws Exception {
        FindActivity find = Robolectric.buildActivity(FindActivity.class).setup().get();
        shot(find, "1-find-searching");
        assertTrue(screenText(find).contains("Looking for Link Workshop"));
        assertTrue(screenText(find).contains("Settings › Automations & integrations › Other apps"));
        reachableAndNamed(find);

        prefs().edit().putString("base", "http://192.168.1.20:8765").commit();
        find = Robolectric.buildActivity(FindActivity.class).setup().get();
        find.phoneFound("Link Workshop", "http://192.168.1.31:8765");
        find.phoneFound("Link Workshop (2)", "http://192.168.1.44:8765");
        find.phoneFound("Link Workshop (3)", "http://192.168.1.20:8765");   // the last used phone: not listed twice
        idle(300);
        shot(find, "2-find-phones");
        String all = screenText(find);
        assertTrue(all.contains("Last used phone")); assertTrue(all.contains("192.168.1.31:8765")); assertTrue(all.contains("on the network now"));
        assertTrue("the last used phone has the focus", focus(find) instanceof Ui.Row && ((Ui.Row) focus(find)).title.getText().toString().equals("Last used phone"));
        reachableAndNamed(find);
        assertEquals("rows read as buttons", android.widget.Button.class.getName(), focus(find).getAccessibilityClassName());
        find.phoneLost("Link Workshop (2)");
        assertFalse(screenText(find).contains("192.168.1.44"));
    }
    @Test public void pairedGoesStraightToTheCamera() {
        paired(); FindActivity.networking = true;
        FindActivity find = Robolectric.buildActivity(FindActivity.class).create().get();
        FindActivity.networking = false;
        assertEquals(WatchActivity.class.getName(), shadowOf(find).getNextStartedActivity().getComponent().getClassName());
    }

    @Test public void typeTheAddress() throws Exception {
        AddressActivity address = Robolectric.buildActivity(AddressActivity.class).setup().get();
        assertTrue("the address field first", focus(address) instanceof android.widget.EditText);
        android.widget.EditText host = (android.widget.EditText) focus(address);
        host.setText("192.168.1.300");
        View connect = find(address.getWindow().getDecorView(), "Connect");
        connect.performClick();
        idle(100);
        shot(address, "3-address-problem");
        assertTrue(screenText(address).contains("from 0 to 255"));
        reachableAndNamed(address);
    }

    // ------------------------------------------------------------------ pairing

    @Test public void pairing() throws Exception {
        prefs().edit().putString("base", "http://192.168.1.20:8765").commit();
        PairActivity pair = Robolectric.buildActivity(PairActivity.class).setup().get();
        assertTrue("the screen stays on while waiting", (pair.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
        shot(pair, "4-pair-asking");
        pair.showCode("4821", System.currentTimeMillis() + 272_000);
        idle(50);
        shot(pair, "5-pair-code");
        String all = screenText(pair);
        assertTrue(all.contains("4")); assertTrue(all.contains("The code works for another 4:3"));
        assertTrue(all.contains("Settings › Automations & integrations › Other apps"));
        assertTrue("digits are read one by one", hasDescription(pair.getWindow().getDecorView(), "Code 4 8 2 1"));
        reachableAndNamed(pair);
        pair.waiting(false);
        assertTrue(screenText(pair).contains("not answering just now"));
        pair.refused();
        shot(pair, "6-pair-refused");
        assertTrue(screenText(pair).contains("The phone said no")); assertNotNull(find(pair.getWindow().getDecorView(), "Ask again"));
        assertEquals("the focus is on Ask again", "Ask again", ((TextView) focus(pair)).getText().toString());
        reachableAndNamed(pair);
        pair.expired(); assertTrue(screenText(pair).contains("The code ran out"));
        pair.failed(false, ""); assertTrue(screenText(pair).contains("Could not reach the phone"));
        pair.failed(true, "Too many"); assertTrue(screenText(pair).contains("The phone is busy"));
        shot(pair, "7-pair-busy");
        pair.approved("lw1_new");
        assertEquals("lw1_new", prefs().getString("token", null));
        assertEquals(WatchActivity.class.getName(), shadowOf(pair).getNextStartedActivity().getComponent().getClassName());
    }

    // ------------------------------------------------------------------ watching

    static Bitmap printBed(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(b); Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF3A4248, 0xFF15191C, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, h, p); p.setShader(null);
        p.setColor(0xFF23292D); c.drawRect(0, h * 0.62f, w, h, p);   // the bed
        p.setColor(0xFFE0E4E6); c.drawRect(w * 0.42f, h * 0.38f, w * 0.6f, h * 0.64f, p);   // the part
        p.setColor(0xFFB0B6BA); c.drawRect(0, h * 0.18f, w, h * 0.24f, p);   // the gantry
        p.setColor(0xFF6E767B); c.drawRect(w * 0.47f, h * 0.24f, w * 0.55f, h * 0.36f, p);   // the head
        return b;
    }
    private static List<PhoneApi.Camera> twoCameras() {
        return Arrays.asList(new PhoneApi.Camera("printer", "Printer camera", true, "camera", 0, false), new PhoneApi.Camera("added-ab12", "Bench", true, "camera", 90, true));
    }
    private static boolean key(Activity a, int code) {
        boolean down = a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        return down;
    }

    @Test public void watching() throws Exception {
        paired();
        WatchActivity watch = Robolectric.buildActivity(WatchActivity.class).setup().get();
        assertTrue("the screen stays on", (watch.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
        shot(watch, "8-watch-connecting");
        watch.camerasArrived(twoCameras());
        watch.linkChanged(PrinterLink.State.LIVE, 0, "", "");
        watch.statusArrived(new JSONObject(StatusTextTest.PRINTING.replace("\"done_at\":\"2026-10-25T02:47:05Z\"", "\"done_at\":\"" + iso(System.currentTimeMillis() + 4_380_000L) + "\"")));
        shot(watch, "9-watch-no-picture-yet");
        assertTrue(screenText(watch).contains("Opening printer camera"));
        watch.showPicture(printBed(1280, 720));
        idle(50);
        shot(watch, "10-watch-printing");
        String all = screenText(watch);
        for (String s : new String[] {"Printing", "Desk_mount_PLA", "42 %", "Layer 51 of 120", "1 h 13 min left", "done about", "215 / 215 °C", "Live · local network", "Printer camera (1 of 2)", "◀ ▶"})
            assertTrue(s + " in " + all, all.contains(s));

        assertTrue("OK hides the status", key(watch, KeyEvent.KEYCODE_DPAD_CENTER)); idle(400);
        assertFalse(watch.statusShown());
        watch.eventArrived(new JSONObject("{\"type\":\"first_layer\",\"file\":\"Desk_mount_PLA\",\"layer\":1,\"total_layers\":120}"));
        idle(400);
        assertTrue(watch.bannerShown());
        shot(watch, "11-watch-hidden-with-event");
        key(watch, KeyEvent.KEYCODE_DPAD_CENTER); idle(400);
        assertTrue("OK shows it again", watch.statusShown());
        assertTrue("Right: the next camera", key(watch, KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals("added-ab12", watch.cameraShown());
        assertEquals("remembered", "added-ab12", prefs().getString("camera", null));
        watch.showPicture(printBed(1280, 720));   // the bench camera: turned a quarter and mirrored
        idle(9_000);
        shot(watch, "12-watch-second-camera-turned");
        key(watch, KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals("printer", watch.cameraShown());
    }
    private static String iso(long ms) { java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT); f.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); return f.format(new java.util.Date(ms)); }

    @Test public void watchingCalmlyWhenThingsAreAway() throws Exception {
        paired();
        WatchActivity watch = Robolectric.buildActivity(WatchActivity.class).setup().get();
        watch.linkChanged(PrinterLink.State.WAITING, System.currentTimeMillis() + 16_000, PrinterLink.UNREACHABLE, "");
        idle(50);
        shot(watch, "13-watch-phone-unreachable");
        assertTrue(screenText(watch).contains("Can't reach the phone")); assertTrue(screenText(watch).contains("Trying again in"));

        watch.camerasArrived(twoCameras());
        watch.linkChanged(PrinterLink.State.LIVE, 0, "", "");
        watch.statusArrived(new JSONObject("{\"printer\":{\"name\":\"Centauri Carbon 2\"},\"connection\":{\"route\":\"not connected\",\"fresh\":false,\"age_s\":420},\"state\":{\"phase\":\"offline\",\"words\":\"No status\",\"printing\":false}}"));
        watch.showPicture(printBed(1280, 720));
        watch.cameraChanged("printer", System.currentTimeMillis() + 20_000, "No picture from this camera yet.");
        idle(50);
        shot(watch, "14-watch-printer-offline");
        String all = screenText(watch);
        assertTrue(all.contains("The phone is not connected to the printer")); assertTrue(all.contains("Last status from the printer 7 min ago"));
        assertTrue("a still picture says so", all.contains("Picture paused"));
        assertFalse("no old numbers", all.contains("°C"));

        watch.statusArrived(new JSONObject(StatusTextTest.PRINTING.replace("\"faults\":[]", "\"faults\":[\"1201\"]").replace("\"phase\":\"printing\",\"words\":\"Printing\"", "\"phase\":\"paused\",\"words\":\"Paused\"")));
        watch.eventArrived(new JSONObject("{\"type\":\"guardian_alarm\",\"file\":\"Desk_mount_PLA\",\"layer\":51,\"total_layers\":120}"));
        watch.cameraChanged("printer", 0, "");
        watch.showPicture(printBed(1280, 720));
        idle(400);
        shot(watch, "15-watch-paused-fault-alarm");
        assertTrue(screenText(watch).contains("The printer reports fault 1201"));
        assertTrue(screenText(watch).contains("The print guardian sees a problem"));

        watch.linkChanged(PrinterLink.State.WAITING, System.currentTimeMillis() + 30_000, PrinterLink.UNREACHABLE, "");
        idle(5_000);
        shot(watch, "16-watch-phone-lost-later");
        assertTrue(screenText(watch).contains("Can't reach the phone right now"));
        assertTrue(screenText(watch).contains("No contact with the phone since"));
    }

    @Test public void theBanner() throws Exception {
        if (System.getProperty("screenshots", "").isEmpty()) return;
        Context c = RuntimeEnvironment.getApplication();
        for (String name : new String[] {"banner", "ic_launcher"}) {
            android.graphics.drawable.Drawable d = c.getDrawable(c.getResources().getIdentifier(name, "drawable", c.getPackageName()));
            int w = name.equals("banner") ? 640 : 192, h = name.equals("banner") ? 360 : 192;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); d.setBounds(0, 0, w, h); d.draw(new Canvas(b));
            File f = new File(System.getProperty("screenshots"), "0-" + name + ".png"); f.getParentFile().mkdirs();
            try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
        }
    }

    private static View find(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().equals(text)) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { View f = find(((ViewGroup) v).getChildAt(i), text); if (f != null) return f; }
        return null;
    }
    private static boolean hasDescription(View v, String said) {
        if (v.getContentDescription() != null && v.getContentDescription().toString().equals(said)) return true;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) if (hasDescription(((ViewGroup) v).getChildAt(i), said)) return true;
        return false;
    }
}
