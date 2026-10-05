package io.github.thelastfrogrammer.elink;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int PICK_FILE = 1, INK = 0xff142c3b, MUTED = 0xff536976, TEAL = 0xff006b65;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService fileWorker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private EditText host, access;
    private TextView connection, state, temperatures, job, feedback, selected;
    private Button connect, pause, stop, refresh, upload, pick;
    private ProgressBar progress;
    private Cc2Session session;
    private JSONObject snapshot = new JSONObject();
    private File selectedFile;
    private String selectedName;
    private boolean active, importing;
    private long generation;
    private final Runnable clock = new Runnable() {
        public void run() { updateButtons(); if (active) main.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setBackgroundColor(0xffedf3f4);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        label(content, "LINK WORKSHOP", 12, TEAL, true);
        label(content, "Your printer, on your phone", 28, INK, true);
        label(content, "Centauri Carbon 2 · local Wi-Fi · development v0.1", 14, MUTED, false);
        LinearLayout connectionCard = card("Connection");
        host = input(connectionCard, "Printer IP address", false);
        host.setInputType(InputType.TYPE_CLASS_PHONE);
        host.setText(getPreferences(MODE_PRIVATE).getString("host", ""));
        access = input(connectionCard, "LAN access code", true);
        access.setSaveEnabled(false); access.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        label(connectionCard, "Use the address and access code from the printer's network settings. Phone and printer must share a local network. A blank code uses the SDK default.", 13, MUTED, false);
        connection = label(connectionCard, "Disconnected", 14, TEAL, true);
        connect = button(connectionCard, "Connect", this::toggleConnection);
        LinearLayout monitor = card("Live monitor");
        state = label(monitor, "Waiting for printer", 22, INK, true);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); monitor.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        temperatures = label(monitor, "Nozzle —    Bed —    Chamber —", 16, INK, false);
        job = label(monitor, "Connect to see the current print, layers and remaining time.", 14, MUTED, false);
        refresh = button(monitor, "Refresh status", () -> { if (session != null) session.refresh(); });
        LinearLayout controls = card("Print controls");
        pause = button(controls, "Pause print", () -> confirmCommand("Pause the current print?", Cc2Codec.PAUSE));
        stop = button(controls, "Stop print…", () -> confirmCommand("Stop the current print? It cannot be resumed through this app.", Cc2Codec.STOP));
        label(controls, "Controls require fresh printer status. Resume is pending protocol verification.", 13, MUTED, false);
        LinearLayout files = card("Send a sliced file");
        selected = label(files, "Choose a .gcode file sliced for this printer.", 14, MUTED, false);
        pick = button(files, "Choose G-code", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent, PICK_FILE);
        });
        upload = button(files, "Upload to printer", () -> new AlertDialog.Builder(this)
            .setTitle("Upload " + selectedName + "?")
            .setMessage("This sends the file only. A printer file with the same name may be replaced. Start the job from the printer screen after checking the plate and filament.")
            .setNegativeButton("Cancel", null).setPositiveButton("Upload", (dialog, which) -> {
                if (session != null && selectedFile != null) { session.upload(selectedFile, selectedName); updateButtons(); }
            }).show());
        label(files, "The file picker disconnects the foreground session. Reconnect after selecting a file. Uploads stop if you leave the app.", 13, MUTED, false);
        feedback = label(content, "Development build: printer behavior still needs hardware testing.", 14, MUTED, false);
        LinearLayout coming = card("Next in the workshop");
        label(coming, "Camera & timelapse · printer file browser · CANVAS trays · multiple printers · completion alerts · remote access", 15, INK, false);
        label(coming, "These features are planned and unavailable in this build.", 13, MUTED, false);
        button(coming, "About & licenses", this::showLicenses);
        if (saved != null) {
            host.setText(saved.getString("host", host.getText().toString()));
            String path = saved.getString("file");
            if (path != null) { File candidate = new File(path); if (getCacheDir().equals(candidate.getParentFile()) && candidate.isFile()) { selectedFile = candidate; selectedName = saved.getString("filename"); selected.setText(selectedName); } }
        }
        updateButtons();
    }
    private void toggleConnection() {
        if (session != null) { disconnect(); return; }
        try {
            long connectionGeneration = ++generation;
            Cc2Session created = new Cc2Session(host.getText().toString().trim(), access.getText().toString(), new Cc2Session.Listener() {
                private void deliver(Runnable action) { ui(() -> { if (generation == connectionGeneration) action.run(); }); }
                public void connection(String message, boolean registered) { deliver(() -> { connection.setText(message); if (!registered && session != null && !session.ready() && !message.endsWith("…")) { session = null; connect.setText("Connect"); } updateButtons(); }); }
                public void status(JSONObject status) { deliver(() -> { snapshot = status; renderStatus(); }); }
                public void result(String message) { deliver(() -> { feedback.setText(message); updateButtons(); }); }
                public void uploadProgress(int percent) { deliver(() -> feedback.setText("Uploading " + selectedName + ": " + percent + "%")); }
            });
            session = created; connect.setText("Disconnect");
            host.setEnabled(false); access.setEnabled(false);
            getPreferences(MODE_PRIVATE).edit().putString("host", host.getText().toString().trim()).apply();
            created.connect();
        } catch (Exception exception) { feedback.setText(exception.getMessage()); }
        updateButtons();
    }
    private void confirmCommand(String title, int method) {
        new AlertDialog.Builder(this).setTitle(title).setNegativeButton("Cancel", null)
            .setPositiveButton(method == Cc2Codec.PAUSE ? "Pause" : "Stop", (dialog, which) -> { if (session != null) session.command(method); }).show();
    }
    private void renderStatus() {
        JSONObject machine = snapshot.optJSONObject("machine_status"), print = snapshot.optJSONObject("print_status");
        int status = machine == null ? -1 : machine.optInt("status", -1), sub = machine == null ? -1 : machine.optInt("sub_status", -1);
        String description = status == 1 ? "Idle" : status == 2 ? "Printing" : status == 0 ? "Initializing" : "Machine state " + status;
        if (status == 2) {
            if (sub == 2501) description = "Pausing";
            else if (sub == 2502 || sub == 2505) description = "Paused";
            else if (sub == 2503) description = "Stopping";
            else if (sub == 2504) description = "Stopped";
            else if (sub == 2077) description = "Print complete";
            else if (sub != 2075) description = "Print preparation · " + sub;
        }
        int percent = machine == null ? 0 : machine.optInt("progress", 0);
        state.setText(description + (status == 2 ? " · " + percent + "%" : ""));
        progress.setProgress(Math.max(0, Math.min(100, percent)));
        temperatures.setText("Nozzle " + temperature("extruder") + "\nBed " + temperature("heater_bed") + "\nChamber " + temperature("ztemperature_sensor"));
        if (status == 2 && print != null) {
            long remaining = print.optLong("remaining_time_sec", -1);
            job.setText(print.optString("filename", "Current print") + "\nLayer " + print.optInt("current_layer", 0) + " / " + print.optInt("total_layer", 0)
                + " · " + (remaining < 0 ? "Time unavailable" : remaining / 3600 + "h " + (remaining % 3600) / 60 + "m remaining"));
        } else job.setText("No active print reported.");
        updateButtons();
    }
    private String temperature(String key) {
        JSONObject value = snapshot.optJSONObject(key);
        if (value == null || !value.has("temperature")) return "—";
        String text = String.format(Locale.ROOT, "%.1f°C", value.optDouble("temperature", 0));
        return value.has("target") ? text + String.format(Locale.ROOT, " / %.0f°C", value.optDouble("target", 0)) : text;
    }
    private void updateButtons() {
        if (connect == null) return;
        boolean ready = session != null && session.ready(), fresh = ready && session.fresh(), busy = session != null && session.uploading();
        host.setEnabled(session == null); access.setEnabled(session == null);
        refresh.setEnabled(ready); pause.setEnabled(fresh && Cc2Codec.canPause(snapshot)); stop.setEnabled(fresh && Cc2Codec.canStop(snapshot));
        upload.setEnabled(ready && selectedFile != null && !busy && !importing); pick.setEnabled(!busy && !importing);
        if (ready && !fresh && snapshot.length() > 0) state.setText("Status stale · refresh to enable controls");
    }
    private void disconnect() {
        generation++;
        Cc2Session current = session; session = null; if (current != null) current.close();
        connection.setText("Disconnected"); connect.setText("Connect"); snapshot = new JSONObject();
        state.setText("Waiting for printer"); progress.setProgress(0);
        temperatures.setText("Nozzle —    Bed —    Chamber —"); job.setText("Reconnect for live status."); updateButtons();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FILE || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData(); importing = true; updateButtons();
        fileWorker.execute(() -> {
            File local = null;
            try {
                String name = null;
                try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                }
                if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".gcode") || !name.matches("[A-Za-z0-9 _.-]+") || name.length() > 120)
                    throw new IOException("Choose a .gcode file with a simple filename.");
                local = File.createTempFile("upload-", ".gcode", getCacheDir());
                try (InputStream input = getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(local)) {
                    if (input == null) throw new IOException("Cannot open selected file");
                    byte[] bytes = new byte[65536]; int count; long copied = 0;
                    while ((count = input.read(bytes)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("File import cancelled");
                        copied += count; if (copied > 512L * 1024 * 1024) throw new IOException("Development build supports files up to 512 MiB");
                        output.write(bytes, 0, count);
                    }
                }
                if (local.length() == 0) throw new IOException("File is empty");
                File readyFile = local; String readyName = name;
                main.post(() -> {
                    if (isDestroyed()) { readyFile.delete(); return; }
                    if (selectedFile != null) selectedFile.delete(); selectedFile = readyFile; selectedName = readyName;
                    importing = false; selected.setText(readyName + " · " + readyFile.length() / 1024 + " KiB"); updateButtons();
                });
            } catch (Exception exception) {
                if (local != null) local.delete();
                main.post(() -> { if (!isDestroyed()) { importing = false; feedback.setText(exception.getMessage()); updateButtons(); } });
            }
        });
    }
    private void ui(Runnable action) { main.post(() -> { if (active && !isDestroyed()) action.run(); }); }
    private void showLicenses() {
        try {
            StringBuilder text = new StringBuilder("Link Workshop v0.1.0\nIndependent Android app derived from Elegoo Link.\n\n");
            for (String name : new String[] {"THIRD_PARTY_NOTICES.md", "Apache-2.0.txt", "Paho-NOTICE.txt", "Paho-EDL-1.0.txt", "Paho-EPL-2.0.txt"}) {
                try (InputStream input = getAssets().open("licenses/" + name); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    text.append(bytes.toString("UTF-8")).append("\n\n");
                }
            }
            ScrollView scroll = new ScrollView(this); TextView view = new TextView(this); view.setText(text.toString()); view.setTextSize(13); view.setPadding(dp(20), dp(12), dp(20), dp(12)); scroll.addView(view);
            new AlertDialog.Builder(this).setTitle("About & licenses").setView(scroll).setPositiveButton("Close", null).show();
        } catch (IOException exception) { feedback.setText("License information could not be opened."); }
    }
    @Override protected void onStart() { super.onStart(); active = true; main.post(clock); }
    @Override protected void onStop() { active = false; main.removeCallbacks(clock); disconnect(); super.onStop(); }
    @Override protected void onDestroy() { fileWorker.shutdownNow(); if (!isChangingConfigurations() && selectedFile != null) selectedFile.delete(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("host", host.getText().toString());
        if (selectedFile != null) { out.putString("file", selectedFile.getAbsolutePath()); out.putString("filename", selectedName); }
    }
    private LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(16)); card.setBackground(background);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(18); content.addView(card, layout);
        label(card, title, 18, INK, true); return card;
    }
    private TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(7)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private EditText input(LinearLayout parent, String hint, boolean password) {
        EditText input = new EditText(this); input.setHint(hint); input.setSingleLine(true); input.setTextColor(INK);
        if (password) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(input, new LinearLayout.LayoutParams(-1, dp(56))); return input;
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextColor(TEAL); button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run()); parent.addView(button, new LinearLayout.LayoutParams(-1, -2)); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
