package io.github.thelastfrogrammer.elink;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.RemoteViews;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONObject;

/**
 * Home-screen widget: the print's latest picture, name, state, progress, time left and temperatures, from the status the
 * app last had (local or through the cloud). Tapping it opens the app. It only shows; it never sends anything.
 */
public final class PrintWidget extends AppWidgetProvider {
    /** What the widget shows, worked out from a status (pure, for tests). */
    static final class Shown {
        String name = "", percent = "", state = "", time = "", temps = ""; int progress = -1;
    }

    static Shown shown(JSONObject status, String printer, long nowMs, Locale locale, TimeZone zone) {
        Shown s = new Shown();
        if (status == null || status.length() == 0) { s.state = "No printer status yet"; s.name = printer; return s; }
        JSONObject machine = status.optJSONObject("machine_status"), print = status.optJSONObject("print_status");
        boolean printing = machine != null && machine.optInt("status", -1) == 2;
        s.state = StatusPresentation.state(status);
        String file = print == null ? "" : StatusPresentation.clean(print.optString("filename", "")).replaceFirst("(?i)\\.gcode$", "");
        s.name = printing && !file.isEmpty() ? file : printer;
        if (printing) {
            int percent = Math.max(0, Math.min(100, machine.optInt("progress", 0)));
            s.progress = percent; s.percent = percent + "%";
            long remaining = print == null ? -1 : print.optLong("remaining_time_sec", -1);
            int layer = print == null ? 0 : print.optInt("current_layer", 0), layers = print == null ? 0 : print.optInt("total_layer", 0);
            String left = remaining < 0 ? "" : StatusPresentation.span(remaining) + " left · done " + StatusPresentation.doneAt(nowMs, remaining, locale, zone);
            s.time = StatusPresentation.joinParts(left, layers > 0 ? "layer " + layer + "/" + layers : "");
        }
        s.temps = StatusPresentation.joinParts(temp("Nozzle", status.optJSONObject("extruder")), temp("Bed", status.optJSONObject("heater_bed")), temp("Chamber", status.optJSONObject("ztemperature_sensor")));
        return s;
    }
    private static String temp(String name, JSONObject value) {
        if (value == null || !value.has("temperature")) return "";
        return name + " " + Math.round(value.optDouble("temperature")) + "°";
    }

    private static long updatedAt; private static String lastKey = "";
    /** Updates every placed widget: at most every 15 s, sooner when the percentage or state changes. */
    static void update(Context context, JSONObject status, String printer, Bitmap picture) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, PrintWidget.class));
        if (ids == null || ids.length == 0) return;
        Shown s = shown(status, printer, System.currentTimeMillis(), Locale.getDefault(), TimeZone.getDefault());
        long now = SystemClock.elapsedRealtime(); String key = s.percent + "|" + s.state;
        if (key.equals(lastKey) && now - updatedAt < 15_000) return;
        lastKey = key; updatedAt = now;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_print);
        views.setTextViewText(R.id.widget_name, s.name);
        views.setTextViewText(R.id.widget_percent, s.percent);
        views.setTextViewText(R.id.widget_state, s.state);
        views.setTextViewText(R.id.widget_time, s.time.isEmpty() ? "Updated " + new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date()) : s.time);
        views.setTextViewText(R.id.widget_temps, s.temps);
        views.setViewVisibility(R.id.widget_progress, s.progress < 0 ? android.view.View.GONE : android.view.View.VISIBLE);
        views.setProgressBar(R.id.widget_progress, 100, Math.max(0, s.progress), false);
        if (picture != null) views.setImageViewBitmap(R.id.widget_picture, PrinterService.scaled(picture, 240));
        else views.setImageViewResource(R.id.widget_picture, R.drawable.ic_launcher);
        PendingIntent open = PendingIntent.getActivity(context, 7, new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, open);
        manager.updateAppWidget(ids, views);
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        // Placed or the launcher asks: show the last known state until the app has a fresh one.
        lastKey = ""; update(context, null, "Link Workshop", null);
    }
}
