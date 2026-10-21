package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import java.util.*;

/**
 * Field check: the hardware checklist on the phone. Each check says what to try; the user marks it works, doesn't work or
 * skipped, with an optional note, and shares one report with the diagnostics. Marking a result never touches the printer;
 * "Go there" only opens the screen where the check is done.
 */
public final class FieldCheckActivity extends Activity {
    static final String PREFS = "field-check", KEY = "results";
    private WorkshopUi ui;
    private Map<String, FieldCheck.Entry> entries;
    private TextView summary;

    @Override protected void onCreate(Bundle saved) {
        ui = new WorkshopUi(this);
        super.onCreate(saved);
        Diagnostics.init(getFilesDir()); Crashes.install(this);
        entries = FieldCheck.parse(prefs().getString(KEY, null));
        LinearLayout page = ui.page("Field check", "Try each item on your phone and printer, mark what you saw, then share the report. "
            + "Marking a result never sends anything to the printer.");
        summary = A11y.polite(ui.label(page, FieldCheck.summary(entries), 14, ui.ink, true));
        LinearLayout top = ui.row(page);
        ui.rowButton(top, "Share report…", this::share, true);
        ui.rowButton(top, "Clear results…", this::clearAll, false);
        ui.label(page, "Notes go into the report as you type them; leave out access codes, PINs and addresses.", 12, ui.muted, false);
        // The self-test print: one short print that gives every measured check its evidence. Only opens the Slice screen, set up.
        LinearLayout selfCard = ui.card(page, "Self-test print");
        ui.label(selfCard, SelfTest.ABOUT, 13, ui.muted, false);
        TextView lastSelf = ui.label(selfCard, "", 13, ui.ink, false); lastSelf.setVisibility(View.GONE);
        LinearLayout selfRow = ui.row(selfCard);
        ui.rowButton(selfRow, "Set up the self-test print…", () -> startActivity(new Intent(this, SliceActivity.class).putExtra(SliceActivity.EXTRA_OPEN, "selftest")), true);
        // Measured: filled in by prints the app watches, with nothing to tap (read on a worker: it opens every recording).
        LinearLayout measuredCard = ui.card(page, "Measured during prints");
        TextView measuredText = ui.label(measuredCard, "Reading the recordings…", 13, ui.muted, false);
        new Thread(() -> {
            List<PrintRecorder.Recording> all = new PrintRecorder(RecordingsActivity.directory(this)).list();
            List<Object[]> found = PrintChecks.latest(all);
            PrintRecorder.Recording self = null; for (PrintRecorder.Recording r : all) if (self == null && SelfTest.is(r.file)) self = r;
            PrintRecorder.Recording lastTest = self;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                measured = found; showMeasured(measuredCard, measuredText);
                if (lastTest != null) {
                    java.text.DateFormat when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
                    lastSelf.setText("Last run " + when.format(new Date(lastTest.start)) + " · " + ("printing".equals(lastTest.outcome) ? "still running" : lastTest.outcome)
                        + " · " + PrintChecks.of(lastTest).size() + " checks answered");
                    lastSelf.setVisibility(View.VISIBLE);
                }
            });
        }, "field-measured").start();
        String area = null; LinearLayout card = null;
        for (FieldCheck.Check check : FieldCheck.CHECKS) {
            if (!check.area.equals(area)) { area = check.area; card = ui.card(page, area); }
            addCheck(card, check);
        }
    }

    /** The newest measured answer to each question, with its recording ({finding, recording}). */
    private List<Object[]> measured = new ArrayList<>();
    private void showMeasured(LinearLayout card, TextView intro) {
        intro.setText(measured.isEmpty()
            ? "Nothing measured yet. Leave the app connected (on your network) during any print: it measures how often status arrives, whether layers and head positions match the print's G-code (when the phone has a copy), and, for prints sliced with Smooth timelapse, whether the head parks and comes back. Results appear here and in the report, with nothing to tap."
            : "From prints the app watched; nothing was sent to the printer for these. Each shows the newest print that measured it.");
        java.text.DateFormat when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        for (Object[] m : measured) {
            PrintChecks.Finding f = (PrintChecks.Finding) m[0]; PrintRecorder.Recording r = (PrintRecorder.Recording) m[1];
            TextView title = ui.label(card, PrintChecks.mark(f.verdict) + "  " + f.title, 15, f.verdict == PrintChecks.Verdict.PROBLEM ? ui.error : ui.ink, true);
            title.setContentDescription((f.verdict == PrintChecks.Verdict.WORKS ? "Works: " : f.verdict == PrintChecks.Verdict.PROBLEM ? "Problem: " : "Unsure: ") + f.title);
            ui.label(card, f.evidence + "\n" + (SelfTest.is(r.file) ? "Self-test print" : StatusPresentation.clean(r.file)) + " · " + when.format(new Date(r.start)), 13, ui.muted, false);
            if (f.id.equals("cameraDelay") && r.checks.cameraDelayMs >= 0) {
                // Live toolpath's delay for the printer's camera, in tenths of a second (its slider), set only on this tap.
                int tenths = (int) Math.max(0, Math.min(100, Math.round(r.checks.cameraDelayMs / 100.0)));
                SharedPreferences viewer = getSharedPreferences("viewer-hints", MODE_PRIVATE);
                String key = GcodeViewerActivity.camKey("liveDelay", "");
                LinearLayout row = ui.row(card);
                Button use = ui.rowButton(row, String.format(Locale.getDefault(), "Use %.1f s in Live toolpath", tenths / 10.0), () -> { }, false);
                Runnable show = () -> {
                    boolean set = viewer.getInt(key, 0) == tenths;
                    use.setEnabled(!set); use.setText(set ? String.format(Locale.getDefault(), "Live toolpath waits %.1f s", tenths / 10.0) : String.format(Locale.getDefault(), "Use %.1f s in Live toolpath (now %.1f s)", tenths / 10.0, viewer.getInt(key, 0) / 10.0));
                };
                use.setOnClickListener(v -> { viewer.edit().putInt(key, tenths).apply(); show.run(); });
                show.run();
            }
        }
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private void save() { prefs().edit().putString(KEY, FieldCheck.toJson(entries)).apply(); summary.setText(FieldCheck.summary(entries)); }

    /** One check: its title and what to try, the three results, a note once a result is chosen, and Go there. */
    private void addCheck(LinearLayout card, FieldCheck.Check check) {
        View line = new View(this); line.setBackgroundColor((ui.muted & 0x00ffffff) | 0x33000000);
        LinearLayout.LayoutParams lineLayout = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1))); lineLayout.topMargin = ui.dp(10);
        if (card.getChildCount() > 1) card.addView(line, lineLayout);
        ui.label(card, check.title, 15, ui.ink, true);
        ui.label(card, check.how, 14, ui.muted, false).setPadding(0, 0, 0, ui.dp(4));
        if (check.open != FieldCheck.NOWHERE) {   // secondary: a text-style button, compact, above the results so the layout does not move
            Button go = ui.styled("Go there", () -> open(check), false);
            go.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ui.dark ? 0x4463d5c7 : 0x33006b65), null, null));
            go.setPadding(0, ui.dp(8), ui.dp(12), ui.dp(8)); go.setContentDescription("Go to " + check.title);
            card.addView(go, new LinearLayout.LayoutParams(-2, -2));
        }
        LinearLayout choices = ui.row(card);
        FieldCheck.Result[] results = {FieldCheck.Result.WORKS, FieldCheck.Result.FAILS, FieldCheck.Result.SKIPPED};
        String[] names = {"Works", "Problem", "Skip"};
        Button[] buttons = new Button[3];
        // The note, with a caption that names it (and hides with it).
        LinearLayout noteBox = new LinearLayout(this); noteBox.setOrientation(LinearLayout.VERTICAL);
        TextView caption = ui.label(noteBox, "Note (optional): what you saw", 12, ui.muted, false);
        EditText note = new EditText(this); note.setTextColor(ui.ink); note.setTextSize(14); note.setMinHeight(ui.dp(48));
        note.setId(View.generateViewId()); caption.setLabelFor(note.getId()); note.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ui.teal));
        FieldCheck.Entry saved = entries.get(check.id);
        note.setText(saved == null ? "" : saved.note);
        Runnable restyle = () -> {
            FieldCheck.Entry e = entries.get(check.id); FieldCheck.Result chosen = e == null ? FieldCheck.Result.NONE : e.result;
            for (int i = 0; i < 3; i++) style(buttons[i], results[i] == chosen, results[i] == FieldCheck.Result.FAILS);
            noteBox.setVisibility(chosen != FieldCheck.Result.NONE || note.getText().length() > 0 ? View.VISIBLE : View.GONE);
        };
        for (int i = 0; i < 3; i++) {
            FieldCheck.Result result = results[i];
            buttons[i] = ui.rowButton(choices, names[i], () -> {
                FieldCheck.Entry e = entries.get(check.id);
                boolean again = e != null && e.result == result;   // tapping the chosen one again clears it
                entries.put(check.id, new FieldCheck.Entry(again ? FieldCheck.Result.NONE : result, note.getText().toString(), again ? 0 : System.currentTimeMillis()));
                save(); restyle.run();
            }, false);
            buttons[i].setContentDescription(check.title + ": " + names[i]);
        }
        noteBox.addView(note, new LinearLayout.LayoutParams(-1, -2)); card.addView(noteBox, new LinearLayout.LayoutParams(-1, -2));
        note.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                FieldCheck.Entry e = entries.get(check.id);
                entries.put(check.id, new FieldCheck.Entry(e == null ? FieldCheck.Result.NONE : e.result, s.toString(), e == null ? 0 : e.at)); save();
            }
        });
        restyle.run();
    }

    /** The chosen result is filled; "Problem" in the error colour so it stands out in the list. */
    private void style(Button button, boolean chosen, boolean bad) {
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(ui.dp(24));
        int fill = bad ? ui.error : ui.teal;
        shape.setColor(chosen ? fill : ui.button);
        button.setBackground(shape); button.setTextColor(chosen ? (ui.dark ? 0xff00201c : 0xffffffff) : bad ? ui.error : ui.teal);
        A11y.state(button, chosen ? "Selected" : "Not selected");
    }

    /** Navigation only: a main tab, Live toolpath or the Slice screen. */
    private void open(FieldCheck.Check check) {
        int where = check.open; String feature = FieldCheck.feature(check.id);
        Intent intent = where == FieldCheck.LIVE ? new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true)
            : where == FieldCheck.SLICE ? new Intent(this, SliceActivity.class)
            : feature != null ? new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_FEATURE, feature).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            : new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PAGE, where).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void share() {
        String header = "App " + appVersion() + "\nDevice " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE;
        StringBuilder measuredText = new StringBuilder("\n## Measured during prints\n");
        if (measured.isEmpty()) measuredText.append("Nothing measured yet.\n");
        for (Object[] m : measured) { PrintChecks.Finding f = (PrintChecks.Finding) m[0]; measuredText.append(PrintChecks.mark(f.verdict)).append(' ').append(f.title).append(SelfTest.is(((PrintRecorder.Recording) m[1]).file) ? " (self-test)" : "").append("\n    ").append(f.evidence).append("\n"); }
        String text = FieldCheck.report(entries, header, TimeZone.getDefault()) + measuredText + "\n\n" + Diagnostics.report("Diagnostics (no codes, PINs, addresses or serial numbers)");
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Link Workshop field check").putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Share field check"));
    }

    private void clearAll() {
        new AlertDialog.Builder(this).setTitle("Clear all results?").setMessage("This clears every result and note on this page. The diagnostics log is kept.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> { entries.clear(); prefs().edit().remove(KEY).apply(); recreate(); }).show();
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }
}
