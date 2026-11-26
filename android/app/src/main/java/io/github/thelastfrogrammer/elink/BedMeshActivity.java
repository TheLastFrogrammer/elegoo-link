package io.github.thelastfrogrammer.elink;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The bed's shape each time the printer levelled it (BedMesh): the chosen capture as a map with its range, tilt and
 * shape without the tilt, a comparison with an earlier one as a difference map, and every capture kept. Only reads what
 * the app noted while it listened; nothing here talks to the printer.
 */
public final class BedMeshActivity extends PlainScreen {
    private List<BedMesh.Mesh> all = new ArrayList<>();
    private int shown, against = -1;
    /** Heights × this in the 3D view and the model (0: chosen for the mesh shown). */
    private int exaggeration;
    private PrinterService printer;
    private TextView cloudCapture;
    private int meshRevision = -1;
    private boolean bound;
    private final PrinterService.Observer watch = this::updateCapture;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!(binder instanceof PrinterService.LocalBinder)) return;   // not the app's own service (or none): no live status
            printer = ((PrinterService.LocalBinder) binder).service(); printer.watch(watch);
        }
        @Override public void onServiceDisconnected(ComponentName name) { printer = null; updateCapture(); }
    };

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PrinterService.class), connection, BIND_AUTO_CREATE);
    }
    @Override protected void onStop() {
        if (printer != null) printer.unwatch(watch);
        printer = null; meshRevision = -1;
        if (bound) { unbindService(connection); bound = false; }
        super.onStop();
    }
    private void updateCapture() {
        if (printer != null && printer.meshRevision() != meshRevision) {
            meshRevision = printer.meshRevision();
            all = BedMesh.list(BedMesh.directory(getFilesDir()));
            render(); return;
        }
        showCaptureStatus();
    }
    private void showCaptureStatus() {
        if (cloudCapture == null) return;
        boolean cloud = printer != null && printer.usingCloud();
        cloudCapture.setVisibility(cloud ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!cloud) return;
        String result = printer.cloudMeshProgress();
        cloudCapture.setText(result.isEmpty() ? (printer.cloudLiveOn
            ? "Cloud live updates connected. Leave this screen open and start Auto-level on the printer."
            : "Waiting for cloud live updates. Periodic status checks cannot collect probe points.") : result);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) { shown = saved.getInt("shown", 0); against = saved.getInt("against", -1); exaggeration = saved.getInt("exaggeration", 0); }
        all = BedMesh.list(BedMesh.directory(getFilesDir()));
        render();
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putInt("shown", shown); state.putInt("against", against); state.putInt("exaggeration", exaggeration); }

    private static String when(long at) { return new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(new Date(at)); }
    private static String context(BedMesh.Mesh m) {
        List<String> parts = new ArrayList<>();
        if (!m.file.isEmpty()) parts.add("before printing " + m.file.replaceFirst("(?i)\\.gcode$", ""));
        if (!Double.isNaN(m.bed)) parts.add(String.format(Locale.getDefault(), "bed %.0f °C", m.bed));
        if (m.source.contains("cloud")) parts.add("seen through the cloud");
        return String.join(" · ", parts);
    }

    private void render() {
        content.removeAllViews();
        A11y.heading(label(content, "Bed mesh", 22, ink, true));
        label(content, "The bed's shape each time the printer levels it: before a print (when levelling is on) or from Auto-level bed. The app only listens "
            + "to what the printer reports while it probes; nothing is sent to the printer for this.", 14, muted, false);
        cloudCapture = label(content, "", 13, muted, false);
        showCaptureStatus();
        if (all.isEmpty()) {
            LinearLayout empty = card();
            label(empty, "No bed mesh yet", 15, ink, true);
            label(empty, "On LAN, use Printer › Move & calibrate › "
                + "Auto-level bed. In cloud mode (experimental), wait for live updates here, then start leveling on the printer touchscreen. The map appears if enough live points arrive.", 13, muted, false);
            label(empty, "This works from the head positions the printer reports while probing. It was worked out on firmware 02.01.01.13 by others; "
                + "other firmware may report differently, and the app says so in Diagnostics if a levelling gives no map.", 12, muted, false);
            return;
        }
        shown = Math.max(0, Math.min(shown, all.size() - 1));
        BedMesh.Mesh m = all.get(shown);

        LinearLayout card = card();
        A11y.heading(label(card, shown == 0 ? "Latest: " + when(m.at) : when(m.at), 15, ink, true));
        String ctx = context(m); if (!ctx.isEmpty()) label(card, ctx, 13, muted, false);
        List<String> words = BedMesh.describe(m);   // always from the probe points themselves, never the filled-in surface
        between(card);
        BedMeshView view = new BedMeshView(this, dark, ink, muted);
        view.set(filled(m, 8), false, "Bed mesh map, " + betweenWords() + ". " + String.join(" ", words));
        card.addView(view, new LinearLayout.LayoutParams(-1, -2));
        TextView readout = label(card, "Tap a point to read it.", 13, muted, false); A11y.polite(readout);
        view.onReadout(readout::setText);
        for (String w : words) label(card, w, 14, ink, false);
        label(card, "Rebuilt from the positions the printer reported while probing, not read from the mesh it stores: a close picture, not a calibration certificate.", 12, muted, false);
        LinearLayout actions = row(card);
        button(actions, "Share as JSON", () -> share(m));
        button(actions, "Delete…", () -> delete(m));

        // The same shape in 3D, heights exaggerated so tenths of a millimetre show; and as a 3D model file.
        LinearLayout solid = card();
        A11y.heading(label(solid, "In 3D", 15, ink, true));
        if (exaggeration <= 0) exaggeration = BedMesh.autoExaggeration(m);
        BedMesh3DView model = new BedMesh3DView(this, dark, ink, muted);
        model.set(filled(m, 4), exaggeration);   // at most 4 steps here, so turning it stays smooth
        solid.addView(model, new LinearLayout.LayoutParams(-1, -2));
        label(solid, "Drag to turn it, pinch to zoom, double tap to start again. Heights are shown " + exaggeration + " times taller than they are.", 12, muted, false);
        LinearLayout heights = row(solid);
        button(heights, "Flatter", () -> { exaggeration = step(exaggeration, -1); render(); }).setContentDescription("Show the heights less exaggerated");
        button(heights, "Taller", () -> { exaggeration = step(exaggeration, 1); render(); }).setContentDescription("Show the heights more exaggerated");
        button(solid, "Save as a 3D model (STL)…", () -> shareModel(filled(m, 8)));

        // Comparing with an earlier capture on the same grid.
        List<Integer> comparable = new ArrayList<>();
        for (int k = 0; k < all.size(); k++) if (k != shown && all.get(k).sameGrid(m)) comparable.add(k);
        if (!comparable.isEmpty()) {
            LinearLayout compare = card();
            A11y.heading(label(compare, "Compared with another levelling", 15, ink, true));
            if (against < 0 || !comparable.contains(against)) against = comparable.get(0);
            BedMesh.Mesh other = all.get(against);
            BedMesh.Mesh older = other.at <= m.at ? other : m, newer = other.at <= m.at ? m : other;
            BedMesh.Mesh diff = BedMesh.difference(older, newer);
            String change = BedMesh.changeWords(older, diff);
            label(compare, when(older.at) + " → " + when(newer.at), 13, muted, false);
            BedMeshView map = new BedMeshView(this, dark, ink, muted);
            map.set(filled(diff, 8), true, "Difference map, " + betweenWords() + ". " + change);
            compare.addView(map, new LinearLayout.LayoutParams(-1, -2));
            TextView point = label(compare, "Tap a point to read its change.", 13, muted, false); A11y.polite(point);
            map.onReadout(point::setText);
            label(compare, change, 14, ink, false);
            if (!Double.isNaN(older.bed) && !Double.isNaN(newer.bed) && Math.abs(older.bed - newer.bed) >= 10)
                label(compare, String.format(Locale.getDefault(), "The bed was at %.0f °C, then %.0f °C: a warm bed changes shape a little, so compare levellings at the same temperature.", older.bed, newer.bed), 13, warning, false);
            button(compare, "Compare with another…", () -> {
                String[] names = new String[comparable.size()];
                for (int k = 0; k < names.length; k++) { BedMesh.Mesh c = all.get(comparable.get(k)); names[k] = when(c.at) + String.format(Locale.getDefault(), " · range %.2f mm", c.range()); }
                new AlertDialog.Builder(this).setTitle("Compare with").setItems(names, (d, w) -> { against = comparable.get(w); render(); }).setNegativeButton("Cancel", null).show();
            });
        }

        // Every capture kept.
        LinearLayout history = card();
        A11y.heading(label(history, "Every levelling kept (" + all.size() + ")", 15, ink, true));
        for (int k = 0; k < all.size(); k++) {
            BedMesh.Mesh c = all.get(k); int index = k;
            TextView rowText = label(history, (k == shown ? "▸ " : "") + when(c.at) + String.format(Locale.getDefault(), " · range %.2f mm", c.range())
                + (context(c).isEmpty() ? "" : " · " + context(c)), 14, k == shown ? accent : ink, k == shown);
            rowText.setMinHeight(dp(48)); rowText.setGravity(android.view.Gravity.CENTER_VERTICAL);
            rowText.setOnClickListener(v -> { shown = index; against = -1; exaggeration = 0; render(); });
            rowText.setContentDescription("Show the levelling of " + when(c.at));
        }
    }

    // ------------------------------------------------------------------ between the probe points

    static final String BETWEEN = "bedMeshBetween", DETAIL = "bedMeshDetail";
    private android.content.SharedPreferences prefs() { return getSharedPreferences("workshop-settings", MODE_PRIVATE); }
    private String how() { String h = prefs().getString(BETWEEN, BedMesh.PROBED); return BedMesh.LINEAR.equals(h) || BedMesh.SMOOTH.equals(h) ? h : BedMesh.PROBED; }
    private int detail() { int d = prefs().getInt(DETAIL, 4); return d == 2 || d == 8 ? d : 4; }
    /** The mesh as chosen between its points, with at most `cap` steps between two of them. */
    private BedMesh.Mesh filled(BedMesh.Mesh m, int cap) { return BedMesh.interpolate(m, how(), Math.min(cap, detail())); }
    private String betweenWords() { return BedMesh.PROBED.equals(how()) ? "as probed" : (BedMesh.LINEAR.equals(how()) ? "filled in in straight lines" : "filled in smoothly") + ", " + detail() + " steps between points"; }
    /** The choice of how to fill in between probe points, and how finely. */
    private void between(LinearLayout card) {
        label(card, "Between the probe points", 13, muted, false);
        LinearLayout ways = row(card);
        String[][] choices = {{BedMesh.PROBED, "As probed"}, {BedMesh.LINEAR, "Linear"}, {BedMesh.SMOOTH, "Smooth"}};
        for (String[] c : choices) {
            boolean on = c[0].equals(how());
            android.widget.Button b = button(ways, (on ? "✓ " : "") + c[1], () -> { prefs().edit().putString(BETWEEN, c[0]).apply(); render(); });
            b.setContentDescription(c[1] + (on ? ", selected" : ""));
        }
        if (!BedMesh.PROBED.equals(how())) {
            LinearLayout steps = row(card);
            for (int d : BedMesh.DETAILS) {
                boolean on = d == detail();
                button(steps, (on ? "✓ " : "") + d + " steps", () -> { prefs().edit().putInt(DETAIL, d).apply(); render(); }).setContentDescription(d + " steps between probe points" + (on ? ", selected" : ""));
            }
        }
        label(card, BedMesh.PROBED.equals(how()) ? "Each tile is one probe point, as measured."
            : BedMesh.LINEAR.equals(how()) ? "Straight lines between neighbouring points: it never goes past the measured heights. Dots mark the probe points."
            : "A smooth surface through every point (bicubic, as Klipper offers): between points it can dip or bulge a little past the measured heights. Dots mark the probe points.",
            12, muted, false);
    }

    /** The next exaggeration up or down the 1-2-5 steps. */
    static int step(int now, int direction) {
        int[] all = BedMesh.EXAGGERATIONS; int at = 0;
        for (int k = 0; k < all.length; k++) if (all[k] <= now) at = k;
        return all[Math.max(0, Math.min(all.length - 1, at + direction))];
    }
    /** The surface as a solid (STL) at the bed's real size, heights × the exaggeration shown, on a 2 mm base. */
    private void shareModel(BedMesh.Mesh m) {
        String name = (m.name.isEmpty() ? "bed-mesh" : m.name.replaceFirst("\\.json$", "")) + "-heights-x" + exaggeration + ".stl";
        File out = ShareProvider.fresh(this, name);
        try (OutputStream o = new FileOutputStream(out)) { o.write(BedMesh.stl(m, exaggeration, 2)); }
        catch (IOException failed) { Toast.makeText(this, "The model could not be made.", Toast.LENGTH_LONG).show(); return; }
        Uri uri = ShareProvider.uriFor(this, out);
        Intent send = new Intent(Intent.ACTION_SEND).setType("model/stl").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "Bed mesh " + when(m.at) + " (heights × " + exaggeration + ")")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(android.content.ClipData.newRawUri("Bed mesh model", uri));
        startActivity(Intent.createChooser(send, "Save or share the 3D model"));
    }
    private void share(BedMesh.Mesh m) {
        File out = ShareProvider.fresh(this, m.name.isEmpty() ? "bed-mesh.json" : m.name);
        try (OutputStream o = new FileOutputStream(out)) { o.write(BedMesh.json(m).toString(1).getBytes(StandardCharsets.UTF_8)); }
        catch (IOException | org.json.JSONException failed) { Toast.makeText(this, "The mesh could not be shared.", Toast.LENGTH_LONG).show(); return; }
        Uri uri = ShareProvider.uriFor(this, out);
        Intent send = new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "Bed mesh " + when(m.at))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(android.content.ClipData.newRawUri("Bed mesh", uri));
        startActivity(Intent.createChooser(send, "Share the bed mesh"));
    }
    private void delete(BedMesh.Mesh m) {
        new AlertDialog.Builder(this).setTitle("Delete this bed mesh?").setMessage("The levelling of " + when(m.at) + " is removed from this phone. The printer is not affected.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, w) -> {
                new File(BedMesh.directory(getFilesDir()), m.name).delete();
                all = BedMesh.list(BedMesh.directory(getFilesDir())); shown = 0; against = -1; render();
            }).show();
    }
}
