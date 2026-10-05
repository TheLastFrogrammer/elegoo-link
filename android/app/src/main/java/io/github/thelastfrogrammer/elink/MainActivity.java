package io.github.thelastfrogrammer.elink;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
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
    private final ExecutorService checks = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private EditText host, access, serial;
    private CheckBox remember;
    private TextView connection, state, temperatures, job, feedback, selected, faults, trays, identity, diagnostics;
    private Button connect, pause, stop, refresh, upload, pick, refill, check, forget;
    private ProgressBar progress;
    private CredentialStore credentials;
    private PrinterService printer;
    private JSONObject snapshot = new JSONObject();
    private boolean active, bound, checking;
    private String pendingFeedback;
    private final ServiceConnection binding = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            printer = ((PrinterService.LocalBinder) binder).service();
            if (pendingFeedback != null) { printer.feedback = pendingFeedback; pendingFeedback = null; }
            if (printer.connecting()) { access.setText(printer.accessCode()); serial.setText(printer.serial()); }
            if (active) printer.observe(MainActivity.this::render);
            render();
        }
        public void onServiceDisconnected(ComponentName name) { printer = null; render(); }
    };
    private final Runnable clock = new Runnable() {
        public void run() { if (active) { render(); main.postDelayed(this, 1000); } }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        credentials = new CredentialStore(this);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xffedf3f4);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(20), dp(24), dp(20), dp(24)); scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        label(content, "LINK WORKSHOP", 12, TEAL, true); label(content, "Your printer, on your phone", 28, INK, true);
        label(content, "Centauri Carbon 2 · local Wi-Fi · v0.2.1", 14, MUTED, false);
        LinearLayout connectionCard = card("Connection");
        host = input(connectionCard, "Printer IP address", false); host.setInputType(InputType.TYPE_CLASS_PHONE);
        host.setText(credentials.host().isEmpty() ? getPreferences(MODE_PRIVATE).getString("host", "") : credentials.host());
        access = input(connectionCard, "LAN access code", true); access.setSaveEnabled(false); access.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        serial = input(connectionCard, "Serial number (optional)", false);
        serial.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        label(connectionCard, "Leave serial blank for automatic identity discovery. If discovery fails, enter Serial Number from Settings → Device to connect directly over MQTT.", 13, MUTED, false);
        remember = new CheckBox(this); remember.setText("Remember access code securely on this phone"); remember.setChecked(credentials.remembers()); connectionCard.addView(remember);
        label(connectionCard, "IP: printer Settings → Network. Access code: Settings → LAN Only. Enable LAN Only and use the same local Wi-Fi. Leave the code blank if code protection is off.", 13, MUTED, false);
        connection = label(connectionCard, "Preparing connection service…", 14, TEAL, true);
        connect = button(connectionCard, "Connect", this::toggleConnection);
        check = button(connectionCard, "Check connection", this::checkConnection);
        diagnostics = label(connectionCard, "Check connection tests HTTP, MQTT and UDP identity discovery. HTTP is optional for monitoring; file uploads still require it. No access code is included in the report.", 13, MUTED, false);
        diagnostics.setTextIsSelectable(true);
        forget = button(connectionCard, "Forget saved access code", () -> { credentials.forget(); remember.setChecked(false); access.setText(""); message("Saved access code removed. An existing connection keeps its in-memory code until disconnected."); });
        LinearLayout monitor = card("Live monitor");
        identity = label(monitor, "Connect to identify the printer and firmware.", 13, MUTED, false);
        state = label(monitor, "Waiting for printer", 22, INK, true);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); monitor.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        temperatures = label(monitor, "Nozzle —    Bed —    Chamber —", 16, INK, false);
        job = label(monitor, "Connect to see the current print, layers and remaining time.", 14, MUTED, false);
        faults = label(monitor, "Printer faults will appear here after status is received.", 14, MUTED, false);
        refresh = button(monitor, "Refresh status & trays", () -> { if (printer != null) printer.refresh(); });
        LinearLayout canvas = card("CANVAS filament trays");
        trays = label(canvas, "Connect to see reported trays, materials, colors and the active tray.", 15, INK, false);
        label(canvas, "Tray IDs and state codes are shown as reported by the printer. Unknown values are not guessed.", 12, MUTED, false);
        refill = button(canvas, "Automatic refill", this::confirmRefill);
        LinearLayout controls = card("Print controls");
        pause = button(controls, "Pause print", () -> confirmCommand("Pause the current print?", Cc2Codec.PAUSE));
        stop = button(controls, "Stop print…", () -> confirmCommand("Stop the current print? It cannot be resumed through this app.", Cc2Codec.STOP));
        label(controls, "Controls require fresh printer status. Resume is pending protocol verification.", 13, MUTED, false);
        LinearLayout files = card("Send a sliced file");
        selected = label(files, "Choose a .gcode file sliced for this printer.", 14, MUTED, false);
        pick = button(files, "Choose G-code", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent, PICK_FILE);
        });
        upload = button(files, "Upload to printer", () -> {
            if (printer == null || printer.selectedName == null) return;
            new AlertDialog.Builder(this).setTitle("Upload " + printer.selectedName + "?")
                .setMessage("This sends the file only. A file with the same name may be replaced. Start the job from the printer screen after checking the plate and filament.")
                .setNegativeButton("Cancel", null).setPositiveButton("Upload", (dialog, which) -> { if (printer != null) printer.upload(); }).show();
        });
        label(files, "Connection and uploads continue while choosing a file, rotating, or switching apps. Use Disconnect in the app or notification to stop the session. Android may still stop the app under memory or battery restrictions.", 13, MUTED, false);
        label(files, "File uploads require HTTP port 80. If HTTP is unavailable, you can still monitor and use supported MQTT controls.", 13, MUTED, false);
        feedback = label(content, "Development build: printer behavior still needs hardware testing.", 14, MUTED, false);
        LinearLayout coming = card("Next in the workshop");
        label(coming, "Start & resume · camera & timelapse · printer file browser · full discovery list · completion alerts · remote access", 15, INK, false);
        label(coming, "These features are unavailable in this build. The ongoing notification shows connection and print state; separate completion alerts are still pending.", 13, MUTED, false);
        button(coming, "About & licenses", this::showLicenses);
        try { access.setText(credentials.load()); }
        catch (Exception error) { credentials.forget(); remember.setChecked(false); message("Saved code could not be decrypted. Enter it again before connecting."); }
        if (saved != null) { host.setText(saved.getString("host", host.getText().toString())); serial.setText(saved.getString("serial", "")); diagnostics.setText(saved.getString("diagnostics", diagnostics.getText().toString())); }
        bound = bindService(new Intent(this, PrinterService.class), binding, BIND_AUTO_CREATE);
        render();
    }
    private void toggleConnection() {
        if (printer == null) return;
        if (printer.connecting()) { printer.disconnect(); return; }
        String address = host.getText().toString().trim(), code = access.getText().toString();
        String serialNumber = serial.getText().toString().trim();
        if (!serialNumber.isEmpty() && !Cc2Discovery.validSerial(serialNumber)) { message("Enter the exact printer serial, using letters, numbers, hyphens or underscores, without spaces."); return; }
        try { new PrinterHttp(address, code); }
        catch (Exception error) { message("Enter a valid private IPv4 address and LAN access code."); return; }
        try { credentials.save(address, code, remember.isChecked()); }
        catch (Exception error) { message("Could not save the code securely. Uncheck Remember to connect without saving."); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !getPreferences(MODE_PRIVATE).getBoolean("notificationsAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationsAsked", true).apply();
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        try {
            startForegroundService(new Intent(this, PrinterService.class));
            printer.connect(address, code, serialNumber); render();
        } catch (Exception error) { printer.disconnect(); message("Android could not start printer monitoring. Keep the app open and check its permissions."); }
    }
    private void checkConnection() {
        if (checking) return;
        String address = host.getText().toString().trim(), code = access.getText().toString();
        String serialNumber = serial.getText().toString().trim();
        if (!serialNumber.isEmpty() && !Cc2Discovery.validSerial(serialNumber)) { diagnostics.setText("Enter a valid serial number or leave it blank for discovery."); return; }
        try { new PrinterHttp(address, code); } catch (Exception error) { diagnostics.setText("Enter a valid private IPv4 address before checking."); return; }
        checking = true; diagnostics.setText("Checking Wi-Fi, HTTP 80, MQTT 1883, and UDP discovery 52700…"); render();
        Context context = getApplicationContext();
        checks.execute(() -> {
            String report;
            try { report = NetworkRoute.local(context).check(address, code, serialNumber); }
            catch (IOException error) { report = "No local Wi-Fi network is available. Connect the phone to the printer's Wi-Fi and check again."; }
            catch (Exception error) { report = "Connection check failed. Confirm the current printer IP and the phone's Wi-Fi."; }
            String result = report;
            main.post(() -> { if (!isDestroyed()) { checking = false; diagnostics.setText(result); render(); } });
        });
    }
    private void confirmCommand(String title, int method) {
        new AlertDialog.Builder(this).setTitle(title).setNegativeButton("Cancel", null)
            .setPositiveButton(method == Cc2Codec.PAUSE ? "Pause" : "Stop", (dialog, which) -> { if (printer != null) printer.command(method); }).show();
    }
    private void confirmRefill() {
        if (printer == null || !printer.canvasFresh() || !printer.canvas.has("auto_refill")) return;
        boolean enabled = !printer.canvas.optBoolean("auto_refill");
        new AlertDialog.Builder(this).setTitle((enabled ? "Enable" : "Disable") + " automatic refill?")
            .setMessage("This changes the printer's CANVAS automatic-refill setting. Tray selection and filament loading remain controlled by the printer.")
            .setNegativeButton("Cancel", null).setPositiveButton(enabled ? "Enable" : "Disable", (dialog, which) -> { if (printer != null) printer.autoRefill(enabled); }).show();
    }
    private void render() {
        if (connect == null || isDestroyed()) return;
        boolean ready = printer != null && printer.ready(), fresh = ready && printer.fresh(), busy = printer != null && printer.uploading(), connecting = printer != null && printer.connecting();
        snapshot = printer == null ? new JSONObject() : printer.status;
        connection.setText(printer == null ? "Preparing connection service…" : printer.connection);
        if (connecting && !printer.host().equals(host.getText().toString())) { host.setText(printer.host()); access.setText(""); }
        host.setEnabled(!connecting); access.setEnabled(!connecting); serial.setEnabled(!connecting); remember.setEnabled(!connecting);
        connect.setEnabled(printer != null); connect.setText(connecting ? "Disconnect" : "Connect"); check.setEnabled(!checking);
        refresh.setEnabled(ready); pause.setEnabled(fresh && Cc2Codec.canPause(snapshot)); stop.setEnabled(fresh && Cc2Codec.canStop(snapshot));
        refill.setEnabled(printer != null && printer.canvasFresh() && printer.canvas.has("auto_refill"));
        if (printer != null && printer.canvas != null && printer.canvas.has("auto_refill")) refill.setText(printer.canvas.optBoolean("auto_refill") ? "Disable automatic refill…" : "Enable automatic refill…");
        else refill.setText("Automatic refill unavailable");
        upload.setEnabled(ready && printer.selectedFile != null && !busy && !printer.importing); pick.setEnabled(printer != null && !busy && !printer.importing);
        if (printer != null && printer.selectedFile != null) selected.setText(printer.selectedName + " · " + printer.selectedFile.length() / 1024 + " KiB");
        state.setText(!fresh && snapshot.length() > 0 ? "Status stale · controls disabled" : StatusPresentation.state(snapshot));
        JSONObject machine = snapshot.optJSONObject("machine_status"), print = snapshot.optJSONObject("print_status");
        int percent = machine == null ? 0 : machine.optInt("progress", 0); progress.setProgress(Math.max(0, Math.min(100, percent)));
        if (fresh && machine != null && machine.optInt("status", -1) == 2) state.append(" · " + percent + "%");
        temperatures.setText("Nozzle " + temperature("extruder") + "\nBed " + temperature("heater_bed") + "\nChamber " + temperature("ztemperature_sensor"));
        if (print != null && machine != null && machine.optInt("status", -1) == 2) {
            long remaining = print.optLong("remaining_time_sec", -1);
            job.setText(StatusPresentation.clean(print.optString("filename", "Current print")) + "\nLayer " + print.optInt("current_layer", 0) + " / " + print.optInt("total_layer", 0)
                + " · " + (remaining < 0 ? "Time unavailable" : remaining / 3600 + "h " + (remaining % 3600) / 60 + "m remaining"));
        } else job.setText(ready ? "No active print reported." : "Connect for live print status.");
        String codes = StatusPresentation.faultCodes(snapshot);
        faults.setText(snapshot.length() == 0 ? "Awaiting printer fault status." : codes.isEmpty() ? "No active fault codes reported." : "Printer reports fault code(s): " + codes + ". Check the printer screen for instructions.");
        faults.setTextColor(codes.isEmpty() ? MUTED : 0xffa32929);
        trays.setText(printer == null ? "Connect for CANVAS tray status." : StatusPresentation.canvas(printer.canvas) + (printer.canvas != null && !printer.canvasFresh() ? "\nTray status is stale; refresh before changing refill." : ""));
        if (printer != null) {
            JSONObject version = printer.attributes.optJSONObject("software_version");
            identity.setText(StatusPresentation.clean(printer.attributes.optString("hostname", "CC2")) + " · " + StatusPresentation.clean(printer.attributes.optString("machine_model", "Centauri Carbon 2"))
                + (version == null ? "" : " · " + StatusPresentation.clean(version.optString("ota_version"))));
            feedback.setText(printer.feedback);
        }
    }
    private String temperature(String key) {
        JSONObject value = snapshot.optJSONObject(key); if (value == null || !value.has("temperature")) return "—";
        String text = String.format(Locale.ROOT, "%.1f°C", value.optDouble("temperature", 0));
        return value.has("target") ? text + String.format(Locale.ROOT, " / %.0f°C", value.optDouble("target", 0)) : text;
    }
    private void message(String text) { if (printer != null) printer.feedback = text; else pendingFeedback = text; if (feedback != null) feedback.setText(text); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_FILE && result == RESULT_OK && data != null && data.getData() != null && printer != null) printer.select(data.getData());
    }
    @Override protected void onStart() { super.onStart(); active = true; if (printer != null) printer.observe(this::render); main.post(clock); }
    @Override protected void onStop() { active = false; main.removeCallbacks(clock); if (printer != null) printer.observe(null); super.onStop(); }
    @Override protected void onDestroy() { if (printer != null) printer.observe(null); if (bound) unbindService(binding); checks.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("host", host.getText().toString()); out.putString("serial", serial.getText().toString()); out.putString("diagnostics", diagnostics.getText().toString()); }
    private void showLicenses() {
        try {
            StringBuilder text = new StringBuilder("Link Workshop v0.2.0\nIndependent Android app derived from Elegoo Link.\n\n");
            for (String name : new String[] {"THIRD_PARTY_NOTICES.md", "Apache-2.0.txt", "Paho-NOTICE.txt", "Paho-EDL-1.0.txt", "Paho-EPL-2.0.txt"}) {
                try (InputStream input = getAssets().open("licenses/" + name); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count); text.append(bytes.toString("UTF-8")).append("\n\n");
                }
            }
            ScrollView scroll = new ScrollView(this); TextView view = new TextView(this); view.setText(text.toString()); view.setTextSize(13); view.setPadding(dp(20), dp(12), dp(20), dp(12)); scroll.addView(view);
            new AlertDialog.Builder(this).setTitle("About & licenses").setView(scroll).setPositiveButton("Close", null).show();
        } catch (IOException error) { feedback.setText("License information could not be opened."); }
    }
    private LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(16)); card.setBackground(background);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(18); content.addView(card, layout); label(card, title, 18, INK, true); return card;
    }
    private TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(7)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private EditText input(LinearLayout parent, String hint, boolean password) {
        EditText input = new EditText(this); input.setHint(hint); input.setSingleLine(true); input.setTextColor(INK); if (password) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(input, new LinearLayout.LayoutParams(-1, dp(56))); return input;
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextColor(TEAL); button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run()); parent.addView(button, new LinearLayout.LayoutParams(-1, -2)); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
