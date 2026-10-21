package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

/**
 * Layer charts for one G-code file before it is printed (from Files, the printer's file list or a slice result): its
 * cross section by line type and the speed, flow and acceleration of each layer, as a recording shows them. Reads the
 * file on the phone only; nothing is sent to the printer.
 */
public final class LayerChartsActivity extends Activity {
    static final String EXTRA_FILE = "file", EXTRA_NAME = "name";

    @Override protected void onCreate(Bundle saved) {
        WorkshopUi ui = new WorkshopUi(this);
        super.onCreate(saved);
        Diagnostics.init(getFilesDir()); Crashes.install(this);
        String path = getIntent().getStringExtra(EXTRA_FILE), name = getIntent().getStringExtra(EXTRA_NAME);
        File file = path == null ? null : new File(path);
        LinearLayout page = ui.page("Layer charts", name == null ? "" : StatusPresentation.clean(name.replaceFirst("(?i)\\.gcode$", "")).replace('_', ' '));
        LinearLayout card = ui.card(page, "Each layer, from the G-code");
        TextView note = ui.label(card, file == null || !file.isFile() ? "The file is not on this phone any more." : "Reading the file…", 13, ui.muted, false);
        if (file == null || !file.isFile()) return;
        int grid = ui.dark ? 0xff2a3a40 : 0xffe1e6e6;
        new Thread(() -> {
            LayerProfile read; try { read = LayerProfile.read(file); } catch (Exception | OutOfMemoryError failed) { read = null; }
            LayerProfile p = read;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (p == null || p.layers == 0) { note.setText("The file could not be read, or has no layers."); return; }
                note.setText(LayerCharts.note(p));
                LayerCharts.fill(this, card, p, ui.ink, ui.muted, grid, ui.surface, ui.dark, getSharedPreferences("workshop-settings", MODE_PRIVATE), (row, text, action) -> {
                    Button chip = ui.rowButton(row, text, action, false); chip.setPadding(0, ui.dp(8), 0, ui.dp(8)); return chip;
                });
            });
        }, "layer-charts").start();
    }
}
