package io.github.thelastfrogrammer.elink;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Maintenance reminders: each task's use since it was last done, marking it done, and changing or adding tasks. */
public final class MaintenanceActivity extends PlainScreen {
    private List<Maintenance.Task> tasks = new ArrayList<>();
    private List<PrintRecorder.Recording> recordings = new ArrayList<>();
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("workshop-settings", MODE_PRIVATE);
        load();
    }
    private void load() {
        new Thread(() -> {
            List<PrintRecorder.Recording> read = new PrintRecorder(RecordingsActivity.directory(this)).list();
            List<Maintenance.Task> list = Maintenance.load(prefs, System.currentTimeMillis());
            runOnUiThread(() -> { if (isDestroyed()) return; recordings = read; tasks = list; show(); });
        }, "maintenance").start();
    }

    private void show() {
        content.removeAllViews();
        label(content, "Maintenance", 22, ink, true);
        label(content, "Counted from the prints this app recorded, since each task was last marked done. Prints it did not watch are not counted. The starting tasks and intervals are common starting points; change them to suit your printer and how you use it.", 13, muted, false);
        long now = System.currentTimeMillis();
        for (Maintenance.Task t : tasks) {
            double used = Maintenance.used(t, recordings, now);
            boolean due = Maintenance.due(t, used), soon = Maintenance.soon(t, used);
            LinearLayout card = card();
            label(card, t.name, 15, ink, true);
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000); bar.setProgress((int) Math.min(1000, t.every <= 0 ? 0 : used / t.every * 1000));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(due ? warning : accent));
            card.addView(bar, new LinearLayout.LayoutParams(-1, dp(12)));
            TextView state = label(card, (due ? "Due · " : soon ? "Soon · " : "") + Maintenance.progress(t, used, Locale.getDefault()), 13, due ? warning : muted, due);
            state.setContentDescription(t.name + ": " + state.getText());
            LinearLayout row = row(card);
            button(row, "Done", () -> new AlertDialog.Builder(this).setTitle("Mark \u201c" + t.name + "\u201d done?").setMessage("Counting starts again from now.")
                .setNegativeButton("Cancel", null).setPositiveButton("Done", (d, w) -> { t.doneAt = System.currentTimeMillis(); Maintenance.save(prefs, tasks); show(); }).show());
            button(row, "Change…", () -> edit(t));
        }
        LinearLayout more = card();
        button(more, "Add a reminder…", () -> edit(null));
        CheckBox notify = new CheckBox(this); notify.setText("Notify me when one falls due (checked when a print ends)"); notify.setTextColor(ink);
        notify.setChecked(prefs.getBoolean(Maintenance.ENABLED, true));
        notify.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(Maintenance.ENABLED, on).apply());
        more.addView(notify);
        button(more, "Start again from the suggested list…", () -> new AlertDialog.Builder(this).setTitle("Replace your reminders with the suggested list?")
            .setMessage("Each one starts counting from now.").setNegativeButton("Cancel", null)
            .setPositiveButton("Replace", (d, w) -> { tasks = Maintenance.defaults(System.currentTimeMillis()); Maintenance.save(prefs, tasks); show(); }).show());
    }

    /** Change a task (name, interval and what it counts), remove it, or add one (`task` null). */
    private void edit(Maintenance.Task task) {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(24), dp(8), dp(24), 0);
        EditText name = new EditText(this); name.setHint("What to do"); name.setText(task == null ? "" : task.name); body.addView(name);
        EditText every = new EditText(this); every.setHint("Every"); every.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (task != null) every.setText(task.every == Math.rint(task.every) ? String.valueOf((long) task.every) : String.valueOf(task.every)); body.addView(every);
        RadioGroup unit = new RadioGroup(this); String[] units = {Maintenance.HOURS, Maintenance.KG, Maintenance.DAYS};
        for (int i = 0; i < units.length; i++) { RadioButton b = new RadioButton(this); b.setId(1000 + i); b.setText(Maintenance.unitWords(units[i])); b.setTextColor(ink); unit.addView(b); }
        String current = task == null ? Maintenance.HOURS : task.unit; for (int i = 0; i < units.length; i++) if (units[i].equals(current)) unit.check(1000 + i);
        body.addView(unit);
        // When it was last done: today for a new one; earlier when it was done before the app counted.
        long[] doneAt = {task == null ? System.currentTimeMillis() : task.doneAt};
        android.widget.Button when = new android.widget.Button(this); when.setAllCaps(false); body.addView(when);
        Runnable showWhen = () -> when.setText("Last done: " + new java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(new java.util.Date(doneAt[0])) + " · change");
        showWhen.run();
        when.setOnClickListener(v -> {
            java.util.Calendar c = java.util.Calendar.getInstance(); c.setTimeInMillis(doneAt[0]);
            android.app.DatePickerDialog pick = new android.app.DatePickerDialog(this, (picker, y, m, d) -> {
                java.util.Calendar chosen = java.util.Calendar.getInstance(); chosen.set(y, m, d, 12, 0, 0);
                doneAt[0] = Math.min(System.currentTimeMillis(), chosen.getTimeInMillis()); showWhen.run();
            }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH));
            pick.getDatePicker().setMaxDate(System.currentTimeMillis()); pick.show();
        });
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(task == null ? "New reminder" : "Change reminder").setView(body).setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (d, w) -> {
                String words = name.getText().toString().trim(); double n;
                try { n = Double.parseDouble(every.getText().toString().trim().replace(',', '.')); } catch (NumberFormatException bad) { n = -1; }
                if (words.isEmpty() || !(n > 0) || n > 100_000) { Toast.makeText(this, "Give it a name and an interval above 0.", Toast.LENGTH_LONG).show(); return; }
                String u = units[Math.max(0, unit.getCheckedRadioButtonId() - 1000)];
                if (task == null) tasks.add(new Maintenance.Task("custom-" + System.currentTimeMillis(), words, u, n, doneAt[0]));
                else { task.name = words; task.every = n; task.unit = u; task.doneAt = doneAt[0]; }
                Maintenance.save(prefs, tasks); show();
            });
        if (task != null) dialog.setNeutralButton("Remove", (d, w) -> { tasks.remove(task); Maintenance.save(prefs, tasks); show(); });
        dialog.show();
    }
}
