package io.github.thelastfrogrammer.elink;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * History › Printer health: how fast the bed and the nozzle heat up print after print, how the bed's tilt and flatness move
 * between levellings, and how well the probe repeats. All from what the app already keeps; nothing is asked of the printer.
 */
public final class PrinterHealthActivity extends PlainScreen {
    /** The newest recordings read for the heating trends. */
    static final int READ = 60;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        A11y.heading(label(content, "Printer health", 22, ink, true));
        label(content, "How the printer is wearing, from what the app already keeps: the recordings of your prints and the bed's shape from each levelling. "
            + "Each compares the first few with the last few, so one odd print does not count.", 14, muted, false);
        TextView waiting = label(content, "Reading the recordings…", 14, muted, false);
        java.io.File recordings = RecordingsActivity.directory(this), meshes = BedMesh.directory(getFilesDir());
        new Thread(() -> {
            List<PrinterHealth.Point> bed = new ArrayList<>(), nozzle = new ArrayList<>();
            List<PrintRecorder.Recording> all = new PrintRecorder(recordings).list();
            all.sort((a, b) -> Long.compare(b.start, a.start));
            for (PrintRecorder.Recording r : all.subList(0, Math.min(READ, all.size()))) {
                try {
                    List<double[]> rows = PrintRecorder.samples(r);
                    double[] t = RecordingsActivity.column(rows, "elapsed_s");
                    double b = PrinterHealth.heatRate(t, RecordingsActivity.column(rows, "bed_c"), RecordingsActivity.column(rows, "bed_target_c"), PrinterHealth.BED_FROM, PrinterHealth.BED_TO);
                    double n = PrinterHealth.heatRate(t, RecordingsActivity.column(rows, "nozzle_c"), RecordingsActivity.column(rows, "nozzle_target_c"), PrinterHealth.NOZZLE_FROM, PrinterHealth.NOZZLE_TO);
                    if (!Double.isNaN(b)) bed.add(new PrinterHealth.Point(r.start, b));
                    if (!Double.isNaN(n)) nozzle.add(new PrinterHealth.Point(r.start, n));
                } catch (java.io.IOException | RuntimeException unreadable) { }
            }
            List<BedMesh.Mesh> kept = BedMesh.list(meshes);
            runOnUiThread(() -> { if (!isDestroyed()) { content.removeView(waiting); show(bed, nozzle, kept); } });
        }, "printer-health").start();
    }

    private void show(List<PrinterHealth.Point> bed, List<PrinterHealth.Point> nozzle, List<BedMesh.Mesh> meshes) {
        int teal = dark ? 0xff5fd4c4 : 0xff00796b, orange = dark ? 0xffd95926 : 0xffeb6834, blue = dark ? 0xff3987e5 : 0xff2a78d6;
        LinearLayout heating = card();
        A11y.heading(label(heating, "Heating", 15, ink, true));
        heater(heating, "bed", bed, orange, String.format(java.util.Locale.ROOT, "%.0f to %.0f °C", PrinterHealth.BED_FROM, PrinterHealth.BED_TO));
        heater(heating, "nozzle", nozzle, blue, String.format(java.util.Locale.ROOT, "%.0f to %.0f °C", PrinterHealth.NOZZLE_FROM, PrinterHealth.NOZZLE_TO));
        label(heating, "Measured while each print heated up from cold, between the temperatures shown; prints that started warm are left out.", 12, muted, false);

        LinearLayout surface = card();
        A11y.heading(label(surface, "The bed over time", 15, ink, true));
        List<BedMesh.Mesh> same = PrinterHealth.sameTemperature(meshes);
        List<PrinterHealth.Point> tilt = new ArrayList<>(), flat = new ArrayList<>();
        for (BedMesh.Mesh m : same) { double[] tf = PrinterHealth.tiltAndFlatness(m); if (tf != null) { tilt.add(new PrinterHealth.Point(m.at, tf[0])); flat.add(new PrinterHealth.Point(m.at, tf[1])); } }
        List<String> words = PrinterHealth.bedWords(meshes);
        if (tilt.size() >= 2) {
            label(surface, "Tilt (mm)", 13, muted, false);
            TrendView t = new TrendView(this, ink, muted, grid, teal); t.set(tilt, " mm", 2, "Bed tilt over time. " + words.get(0)); surface.addView(t);
            label(surface, "Flatness without the tilt (mm)", 13, muted, false);
            TrendView f = new TrendView(this, ink, muted, grid, orange); f.set(flat, " mm", 2, "Bed flatness over time. " + (words.size() > 1 ? words.get(1) : "")); surface.addView(f);
        }
        for (String w : words) label(surface, w, 14, ink, false);
        label(surface, "Only levellings at the same bed temperature are compared: a warm bed changes shape a little.", 12, muted, false);
        button(surface, "Bed mesh…", () -> startActivity(new android.content.Intent(this, BedMeshActivity.class)));

        LinearLayout probe = card();
        A11y.heading(label(probe, "The probe", 15, ink, true));
        label(probe, PrinterHealth.repeatWords(meshes), 14, ink, false);
    }

    private void heater(LinearLayout card, String name, List<PrinterHealth.Point> points, int colour, String range) {
        label(card, (name.equals("bed") ? "Bed" : "Nozzle") + ", " + range + " (°C a minute)", 13, muted, false);
        String words = PrinterHealth.heatingWords(name, PrinterHealth.trend(points), 2 * PrinterHealth.ENDS);
        if (points.size() >= 2) { TrendView v = new TrendView(this, ink, muted, grid, colour); v.set(points, "", 1, (name.equals("bed") ? "Bed" : "Nozzle") + " heating over time. " + words); card.addView(v); }
        label(card, words, 14, ink, false);
    }
}
