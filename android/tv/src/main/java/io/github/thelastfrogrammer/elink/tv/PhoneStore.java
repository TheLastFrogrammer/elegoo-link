package io.github.thelastfrogrammer.elink.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

/**
 * What the TV remembers, in its private preferences (never backed up, never logged): the last phone's address, the token
 * the phone gave when it approved this TV, the camera last shown and whether the status was hidden.
 */
final class PhoneStore {
    private final SharedPreferences prefs;

    PhoneStore(Context c) { prefs = c.getApplicationContext().getSharedPreferences("phone", Context.MODE_PRIVATE); }

    String base() { return prefs.getString("base", null); }
    String token() { return prefs.getString("token", null); }
    boolean paired() { return base() != null && token() != null; }
    String camera() { return prefs.getString("camera", null); }
    boolean statusHidden() { return prefs.getBoolean("status_hidden", false); }

    /** The phone to watch. The token stays: the same phone often comes back at a new address, and a wrong one answers 401. */
    void phone(String base) { prefs.edit().putString("base", base).apply(); }
    void token(String token) { prefs.edit().putString("token", token).apply(); }
    void forgetToken() { prefs.edit().remove("token").apply(); }
    void camera(String id) { prefs.edit().putString("camera", id).apply(); }
    void statusHidden(boolean hidden) { prefs.edit().putBoolean("status_hidden", hidden).apply(); }

    /** The name the phone shows when this TV asks to pair: the TV's own name ("Living room Fire TV"), when it has one. */
    static String deviceName(Context c) {
        String name = null;
        try { name = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME); } catch (RuntimeException unavailable) { /* below */ }
        if (name != null && !name.trim().isEmpty()) return name.trim();
        return "Amazon".equalsIgnoreCase(Build.MANUFACTURER) ? "Fire TV" : "TV";
    }
}
