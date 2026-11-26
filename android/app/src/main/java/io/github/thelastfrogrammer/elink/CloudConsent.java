package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;

/** The one-time cloud-control agreement every cloud action waits for (monitoring does not). */
final class CloudConsent {
    static final String KEY = "cloudControlUnderstood";

    static boolean given(SharedPreferences settings) { return settings.getBoolean(KEY, false); }

    /** Runs `action` now when it is not a cloud action or the agreement was given; otherwise asks once first. */
    static void gate(Activity screen, SharedPreferences settings, boolean cloud, Runnable action) {
        if (!cloud || given(settings)) { action.run(); return; }
        new AlertDialog.Builder(screen).setTitle("Turn on cloud control?")
            .setMessage("Without a local connection, controls, files, history and settings go through Elegoo's cloud, using the same cloud sign-in as ElegooSlicer on a computer. If ElegooSlicer is open with this account, one of them may be signed out of cloud control. The Matrix app is expected to keep working, but this is untested.\n\nThe app connects only while you use these, and disconnects after two idle minutes. Monitoring works either way.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Turn on", (d, which) -> { settings.edit().putBoolean(KEY, true).apply(); action.run(); }).show();
    }

    private CloudConsent() { }
}
