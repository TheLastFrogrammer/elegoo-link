package io.github.thelastfrogrammer.elink;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Bitmap;
import android.content.res.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.util.Locale;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int PICK_FILE = 1, PICK_SNAPSHOT = 3, CLOUD_LOGIN = 4, SAVE_GCODE = 5, SLICE = 6, SAVE_TIMELAPSE = 7;
    private String pendingExportHash = "";
    private android.net.Uri pendingExportUri;
    private TextView inspection;
    private ImageView filePreview;
    private TextView previewInfo;
    private LinearLayout timelapseList;
    private String saveAfterDownload, pendingPrintSetup;
    /** Intent extra: the tab to open (0 Monitor, 1 Files, 2 Camera, 3 Settings), from another screen's hint. */
    static final String EXTRA_PAGE = "openPage";
    private long printSetupUntil;
    private long saveGiveUpAt;
    private Button saveTimelapse;
    private JSONObject renderedHistory;
    private LinearLayout fileDetails, transferRow, trayList, pageRow;
    private LinearLayout printRow, lightRow, tilesRow, controlsCard, canvasCard, tuningCard, upkeepCard, upkeepButtons, moreOptions, cameraCloudCard, cameraLocalCard, localCameraBody;
    private Button controlFix, filesFix, moreToggle, localToggle;
    private TextView tuningHint, upkeepHint, cloudCameraHint, timelapseHint, fileHelp;
    private boolean moreOpen, localCameraOpen, cameraLocalFirst, historyOpen;
    private LinearLayout historyButtons, historyList, historyBody;
    private Button historyToggle;
    private JSONObject renderedHistoryList;
    private ControlState.Block block = ControlState.Block.DISCONNECTED;
    private String dismissedFeedback = "", renderedCanvas;
    private GcodeInspector.Report renderedReport;
    private Button materialDetails;
    private Button saveCopy, shareInspection, clearCopy, cancelDownload;
    private int INK = 0xff17252c, MUTED = 0xff5a6d76, TEAL = 0xff00796b, BACKGROUND = 0xfff2f5f6, SURFACE = Color.WHITE, BUTTON = 0xffe2efed,
        TILE = 0xfff4f8f8, TRACK = 0xffdbe6e6, ERROR = 0xffb3261e, AMBER = 0xff855700, NAV = Color.WHITE;
    private boolean dark;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService checks = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private LinearLayout currentSection, fileRows;
    private LinearLayout localSettings, cloudSettings;
    private Button modeLocal, modeCloud;
    /** Settings shows the local connection or the Elegoo cloud account, not both; the choice is remembered. */
    private void setSettingsMode(boolean cloud) {
        if (localSettings == null || cloudSettings == null) return;
        localSettings.setVisibility(cloud ? View.GONE : View.VISIBLE);
        cloudSettings.setVisibility(cloud ? View.VISIBLE : View.GONE);
        styleSegment(modeLocal, !cloud); styleSegment(modeCloud, cloud);
        settings.edit().putBoolean("settingsCloud", cloud).apply();
    }
    /** The remembered choice; the first time, cloud for someone signed in to Elegoo without a saved printer address. */
    private boolean settingsModeAtStart() {
        if (settings.contains("settingsCloud")) return settings.getBoolean("settingsCloud", false);
        boolean signedIn; try { signedIn = cloudAccounts != null && cloudAccounts.load() != null; } catch (Exception unreadable) { signedIn = false; }
        return signedIn && host.getText().toString().trim().isEmpty();
    }
    private Button segment(LinearLayout row, String text, Runnable action) {
        Button button = rowButton(row, text, action, false); A11y.tab(button, false); return button;
    }
    private void styleSegment(Button button, boolean selected) {
        if (button == null) return;
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(12)); shape.setColor(selected ? TEAL : BUTTON);
        button.setBackground(shape); button.setTextColor(selected ? (dark ? 0xff00201c : Color.WHITE) : TEAL);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        A11y.tab(button, selected);
    }
    private final LinearLayout[] pages = new LinearLayout[4];
    private final LinearLayout[] tabs = new LinearLayout[4];
    private int page = 3;
    private SharedPreferences settings;
    private ProfileStore profiles;
    private EditText profileName, cameraAddress, pairingPin;
    private TextView pinProbeHelp, cloudStatus, cloudLiveLabel, lanHint;
    private Button cloudSignOut, cloudPrinters, probeButton;
    private TextView probeResult;
    private CheckBox cloudBackground;
    private Button loadFilament, unloadFilament, trayFilament, homeAll, jog, autoLevel, vibration, selfCheck, urgentStop;
    private ImageView fileThumbnail;
    private Button graphs;
    private String fileThumbnailKey = "";
    private Button cloudCamera, cameraFix;
    /** First run only: the three ways in. Hidden for good once the phone has connected or a model has been sliced. */
    private LinearLayout getStarted, heroCard;
    /** Monitor quick actions: navigation only, never a printer command. quickRow holds Live toolpath or Slice, then Camera; recordingsRow holds Print again and Print recordings. */
    private LinearLayout quickRow, recordingsRow;
    private Button quickSlice, quickCamera, quickAgain;
    /** Maintenance starts collapsed; the Emergency stop button stays outside the collapsed part. */
    private Button maintenanceToggle;
    private LinearLayout maintenanceBody;
    private boolean maintenanceOpen;
    private CloudAccountStore cloudAccounts;
    private TextView summary, fileInfo, historyInfo, diskInfo, cameraInfo;
    private Spinner storagePicker, routePicker, authPicker;
    private NetworkRoute cameraRoute;
    private AutoCloseable cameraRouteWatch;
    private Button sliceModel, liveToolpath, previewToolpath;
    private Button resume, discover, saveProfile, chooseProfile, removeProfile, cancelUpload, listFiles, previousFiles, nextFiles, loadHistory, loadDisk,
        cameraStart, cameraQuery, cameraSnapshot, heater, fan, speed, lightOn, lightOff;
    private ImageView cameraImage;
    private JSONObject renderedFiles;
    private Cc2Discovery scanning;
    private MjpegPlayer cameraPlayer;
    private Bitmap lastFrame, pendingSnapshot;
    private String cameraHost = "";
    private String cameraReported = "";
    private boolean scanningNow;
    private EditText host, access, serial;
    private CheckBox remember;
    private TextView connection, state, job, feedback, selected, faults, trays, identity, diagnostics;
    private Button connect, pause, stop, refresh, upload, pick, refill, check, forget, recentSlices;
    private ProgressRing ring;
    private TextView title, detail, controlSource, tileNozzle, tileBed, tileChamber;
    private CredentialStore credentials;
    private PrinterService printer;
    private JSONObject snapshot = new JSONObject();
    private boolean active, bound, checking, cloudAsked;
    private String pendingFeedback, pendingSlicedFile, pendingSlicedName;
    private final ServiceConnection binding = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            printer = ((PrinterService.LocalBinder) binder).service();
            if (pendingFeedback != null) { printer.feedback = pendingFeedback; pendingFeedback = null; }
            finishPendingExport();
            takeSliced();
            if (printer.connecting()) { routePicker.setSelection(printer.remote() ? 1 : 0); authPicker.setSelection(printer.pinProbe() ? 1 : 0); host.setText(printer.host()); if (printer.pinProbe()) pairingPin.setText(printer.accessCode()); else access.setText(printer.accessCode()); serial.setText(printer.serial()); }
            if (active) { printer.observe(MainActivity.this::render); printer.cloudVisible(true); }
            render();
        }
        public void onServiceDisconnected(ComponentName name) { printer = null; render(); }
    };
    private final Runnable clock = new Runnable() {
        public void run() { if (active) { render(); main.postDelayed(this, 1000); } }
    };
    @Override public void onCreate(Bundle saved) {
        settings = getSharedPreferences("workshop-settings", MODE_PRIVATE);
        int appearance = settings.getInt("theme", 0);
        dark = appearance == 2 || appearance == 0 && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (dark) { INK = 0xffe6eef1; MUTED = 0xff9fb3bb; TEAL = 0xff5fd4c4; BACKGROUND = 0xff0e1417; SURFACE = 0xff182227; BUTTON = 0xff21343a;
            TILE = 0xff1e2a30; TRACK = 0xff2a3a40; ERROR = 0xffffb4ab; AMBER = 0xffe8c06a; NAV = 0xff141c20; }
        setTheme(dark ? R.style.WorkshopDark : R.style.WorkshopLight);
        super.onCreate(saved);
        if (saved != null) { pendingExportHash = saved.getString("exportHash", ""); String uri = saved.getString("exportUri", ""); if (!uri.isEmpty()) pendingExportUri = android.net.Uri.parse(uri); }
        Diagnostics.init(getFilesDir());
        credentials = new CredentialStore(this);
        profiles = new ProfileStore(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BACKGROUND);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BACKGROUND); mainScroll = scroll;
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(16), dp(16), dp(24)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        // Header: printer name, connection chip, model/firmware line.
        // With large text the chip goes under the title, so the printer name gets the full width.
        boolean largeText = getResources().getConfiguration().fontScale >= 1.3f;
        LinearLayout header = new LinearLayout(this); header.setOrientation(largeText ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        header.setGravity(largeText ? Gravity.START : Gravity.CENTER_VERTICAL); content.addView(header);
        title = new TextView(this); title.setText("Link Workshop");
        title.setTextSize(24); title.setTextColor(INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END); A11y.heading(title); header.addView(title, largeText ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1));
        summary = new TextView(this); summary.setTextSize(12); summary.setTypeface(Typeface.DEFAULT, Typeface.BOLD); summary.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams chipLayout = new LinearLayout.LayoutParams(-2, -2); if (largeText) chipLayout.topMargin = dp(4); header.addView(summary, chipLayout);
        identity = label(content, "Centauri Carbon 2", 13, MUTED, false);
        // "Find a feature": on every tab, a search-style bar that jumps to any feature by name or everyday words.
        Button find = new Button(this); find.setText("Find a feature…"); find.setAllCaps(false); find.setTextSize(14); find.setTextColor(MUTED);
        find.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); find.setMinHeight(dp(48)); find.setMinimumHeight(dp(48)); find.setPadding(dp(14), 0, dp(14), 0);
        android.graphics.drawable.Drawable lens = getDrawable(android.R.drawable.ic_menu_search);
        if (lens != null) { lens = lens.mutate(); lens.setTint(MUTED); lens.setBounds(0, 0, dp(22), dp(22)); find.setCompoundDrawables(lens, null, null, null); find.setCompoundDrawablePadding(dp(8)); }
        GradientDrawable findShape = new GradientDrawable(); findShape.setColor(SURFACE); findShape.setCornerRadius(dp(24)); findShape.setStroke(dp(1), (MUTED & 0x00ffffff) | 0x40000000); find.setBackground(findShape);
        find.setContentDescription("Find a feature"); find.setOnClickListener(v -> featureDialog());
        LinearLayout.LayoutParams findLayout = new LinearLayout.LayoutParams(-1, -2); findLayout.topMargin = dp(8); content.addView(find, findLayout);
        // Results of actions show here, on every tab, until tapped away or replaced.
        feedback = new TextView(this); feedback.setTextSize(14); feedback.setTextColor(INK); feedback.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable banner = new GradientDrawable(); banner.setColor(TILE); banner.setCornerRadius(dp(14)); banner.setStroke(dp(1), (TEAL & 0x00ffffff) | 0x55000000); feedback.setBackground(banner);
        A11y.polite(feedback); A11y.clickLabel(feedback, "Dismiss message"); feedback.setVisibility(View.GONE);
        feedback.setOnClickListener(v -> { dismissedFeedback = feedback.getText().toString(); feedback.setVisibility(View.GONE); });
        LinearLayout.LayoutParams bannerLayout = new LinearLayout.LayoutParams(-1, -2); bannerLayout.topMargin = dp(8); content.addView(feedback, bannerLayout);
        // Bottom navigation.
        LinearLayout navigation = new LinearLayout(this); navigation.setOrientation(LinearLayout.HORIZONTAL); navigation.setBackgroundColor(NAV); navigation.setElevation(dp(8));
        String[] titles = {"Monitor", "Files", "Camera", "Settings"};
        int[] icons = {R.drawable.ic_nav_monitor, R.drawable.ic_nav_files, R.drawable.ic_nav_camera, R.drawable.ic_nav_settings};
        for (int i = 0; i < 4; i++) {
            final int target = i; LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER); item.setPadding(0, dp(8), 0, dp(8));
            ImageView icon = new ImageView(this); icon.setImageResource(icons[i]); item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView name = new TextView(this); name.setText(titles[i]); name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 12 * getResources().getDisplayMetrics().density * Math.min(1.3f, getResources().getConfiguration().fontScale)); name.setGravity(Gravity.CENTER); name.setMaxLines(2); item.addView(name);
            item.setContentDescription(titles[i]); item.setOnClickListener(v -> selectPage(target));
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x3363d5c7 : 0x22006b65), null, new android.graphics.drawable.ColorDrawable(Color.WHITE)));
            tabs[i] = item; navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        for (int i = 0; i < 4; i++) { pages[i] = new LinearLayout(this); pages[i].setOrientation(LinearLayout.VERTICAL); content.addView(pages[i]); }
        currentSection = pages[3];
        // Local or cloud: one connection card at a time, so the page stays short.
        LinearLayout modeRow = row(pages[3]); ((LinearLayout.LayoutParams) modeRow.getLayoutParams()).topMargin = dp(12);
        modeLocal = segment(modeRow, "Local (Wi-Fi)", () -> setSettingsMode(false));
        modeCloud = segment(modeRow, "Elegoo cloud", () -> setSettingsMode(true));
        LinearLayout connectionCard = card("Printer connection");
        localSettings = connectionCard;
        label(connectionCard, "At home, on the printer's Wi-Fi: its IP address and access code (LAN Only). Away from home, use Elegoo cloud above.", 13, MUTED, false);
        connection = label(connectionCard, "Preparing connection service…", 14, TEAL, true);
        connection.setTextIsSelectable(true);
        LinearLayout findRow = row(connectionCard);
        discover = rowButton(findRow, "Find on Wi-Fi", this::scanPrinters, false);
        chooseProfile = rowButton(findRow, "Saved printers…", this::choosePrinter, false);
        host = input(connectionCard, "Printer IP address", false); host.setInputType(InputType.TYPE_CLASS_PHONE);
        host.setText(credentials.host().isEmpty() ? getPreferences(MODE_PRIVATE).getString("host", "") : credentials.host());
        access = input(connectionCard, "Access code (LAN Only)", true); access.setTypeface(Typeface.DEFAULT); access.setSaveEnabled(false); access.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        lanHint = label(connectionCard, "Printer IP: Settings → Network. Access code: shown once LAN Only is on.", 13, MUTED, false);
        remember = new CheckBox(this); remember.setMinHeight(dp(48)); remember.setText("Remember access code securely on this phone"); remember.setChecked(credentials.remembers()); connectionCard.addView(remember);
        remember.setTextColor(INK); remember.setButtonTintList(tint(TEAL));
        LinearLayout connectRow = row(connectionCard);
        connect = rowButton(connectRow, "Connect", this::toggleConnection, true);
        check = rowButton(connectRow, "Check connection", this::checkConnection, false);
        diagnostics = label(connectionCard, "Check connection tests whether the printer is reachable and how it is set up.", 13, MUTED, false);
        diagnostics.setTextIsSelectable(true);
        // Advanced options (serial, route VPN, authentication, saved printers) are rarely changed, so they sit behind one toggle (open already when the route or PIN probe is in use).
        moreToggle = button(connectionCard, "", this::toggleMore);
        LinearLayout more = new LinearLayout(this); more.setOrientation(LinearLayout.VERTICAL); connectionCard.addView(more); moreOptions = more;
        label(more, "Serial number: leave blank to find it automatically. If that fails, enter it from printer Settings → Device.", 13, MUTED, false);
        serial = input(more, "Serial number (optional)", false);
        serial.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        subheading(more, "Connection route");
        routePicker = spinner(more, new String[] {"Local Wi-Fi / Ethernet", "Remote through home VPN"});
        routePicker.setSelection(settings.getBoolean("remoteVPN", false) ? 1 : 0);
        routePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { settings.edit().putBoolean("remoteVPN", position == 1).apply(); stopCamera(); if (connect != null) { diagnostics.setText("Connection route changed. Run Check connection before reconnecting."); render(); } }
        });
        button(more, "Remote access setup…", this::remoteHelp);
        subheading(more, "Printer authentication");
        authPicker = spinner(more, new String[] {"Access code (LAN Only)", "Cloud-mode PIN probe (read-only)"});
        authPicker.setSelection(settings.getBoolean("pinProbe", false) ? 1 : 0);
        authPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { settings.edit().putBoolean("pinProbe", position == 1).apply(); if (connect != null) { diagnostics.setText("Authentication mode changed. Run Check connection; PIN probe preserves the printer's cloud setting."); render(); } }
        });
        pairingPin = input(more, "Current printer pairing PIN (probe only)", true); pairingPin.setTypeface(Typeface.DEFAULT); pairingPin.setSaveEnabled(false); pairingPin.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        pinProbeHelp = label(more, "Experimental and read-only: use the pairing PIN the printer currently shows, not the access code. The PIN is never saved.", 13, MUTED, false);
        button(more, "Matrix coexistence test…", this::coexistenceHelp);
        subheading(more, "Saved printer");
        profileName = input(more, "Printer profile name", false);
        serial.setText(profiles.find(host.getText().toString()).optString("serial"));
        profileName.setText(profiles.find(host.getText().toString()).optString("name"));
        LinearLayout profileRow = row(more);
        saveProfile = rowButton(profileRow, "Save", this::savePrinter, false);
        removeProfile = rowButton(profileRow, "Remove…", this::removePrinter, false);
        forget = button(more, "Forget saved access code", () -> { credentials.forget(host.getText().toString().trim()); remember.setChecked(false); access.setText(""); pairingPin.setText(""); message("Saved access code removed; entered PIN cleared. An existing connection keeps its in-memory code until disconnected."); });
        setMore(settings.getBoolean("remoteVPN", false) || settings.getBoolean("pinProbe", false));
        currentSection = pages[0];
        // Get started: one line on what the app does, then the three ways in, each with one button.
        getStarted = card("Get started");
        label(getStarted, "Watch and control your Centauri Carbon 2, or slice models on this phone. Pick one way to start.", 14, INK, false);
        label(getStarted, "1. At home, on Wi-Fi", 14, INK, true);
        label(getStarted, "Turn the printer on and join this phone's Wi-Fi, then look for it.", 13, MUTED, false);
        rowButton(row(getStarted), "Find my printer on Wi-Fi", () -> { setSettingsMode(false); selectPage(3); scanPrinters(); }, true);
        label(getStarted, "2. Away from home", 14, INK, true);
        label(getStarted, "Use your Elegoo account to watch and control the printer through the cloud.", 13, MUTED, false);
        rowButton(row(getStarted), "Sign in with Elegoo…", this::cloudSignIn, false);
        label(getStarted, "3. Just want a print", 14, INK, true);
        label(getStarted, "Slice a model on this phone, then send the file to the printer.", 13, MUTED, false);
        rowButton(row(getStarted), "Slice a model…", () -> startActivityForResult(new Intent(this, SliceActivity.class), SLICE), false);
        LinearLayout hero = card(null); heroCard = hero;
        LinearLayout heroRow = new LinearLayout(this); heroRow.setOrientation(LinearLayout.HORIZONTAL); heroRow.setGravity(Gravity.CENTER_VERTICAL); hero.addView(heroRow);
        ring = new ProgressRing(this, TRACK, TEAL, INK, MUTED); heroRow.addView(ring, new LinearLayout.LayoutParams(dp(116), dp(116)));
        LinearLayout heroText = new LinearLayout(this); heroText.setOrientation(LinearLayout.VERTICAL); heroText.setPadding(dp(16), 0, 0, 0); heroRow.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1));
        state = A11y.polite(label(heroText, "Waiting for printer", 20, INK, true));
        job = label(heroText, "", 14, INK, false); job.setMaxLines(2); job.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail = label(heroText, "", 13, MUTED, false);
        faults = A11y.assertive(label(hero, "", 14, ERROR, true));
        // Quick actions: they only navigate or open a screen, never send a printer command. render() shows the ones that fit the state.
        // Row 1 while printing: Live toolpath and Camera. Idle and connected: Slice a model and Camera. Row 2: Print again (idle, with history) and Print recordings.
        quickRow = row(hero);
        liveToolpath = rowButton(quickRow, "Live toolpath", () -> startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true)), true);
        quickSlice = rowButton(quickRow, "Slice a model…", () -> startActivityForResult(new Intent(this, SliceActivity.class), SLICE), true);
        quickCamera = rowButton(quickRow, "Camera", () -> selectPage(2), false);
        recordingsRow = row(hero);
        quickAgain = rowButton(recordingsRow, "Print again…", () -> { setHistoryOpen(true); selectPage(1); }, false);
        quickAgain.setContentDescription("Print again: opens print history on the Files tab, where the latest print can be printed again");
        graphs = rowButton(recordingsRow, "Print recordings", () -> {
            PrintRecorder.Recording current = printer == null || printer.recorder == null ? null : printer.recorder.current();
            Intent intent = new Intent(this, RecordingsActivity.class);
            if (current != null) intent.putExtra(RecordingsActivity.EXTRA_META, current.meta.getAbsolutePath());
            startActivity(intent);
        }, false);
        LinearLayout tiles = new LinearLayout(this); tiles.setOrientation(getResources().getConfiguration().fontScale >= 1.5f ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); tilesRow = tiles;
        LinearLayout.LayoutParams tilesLayout = new LinearLayout.LayoutParams(-1, -2); tilesLayout.topMargin = dp(12); currentSection.addView(tiles, tilesLayout);
        tileNozzle = tile(tiles, "Nozzle", 0); tileBed = tile(tiles, "Bed", dp(8)); tileChamber = tile(tiles, "Chamber", dp(8));
        LinearLayout controls = card("Controls"); controlsCard = controls;
        controlSource = label(controls, "", 13, MUTED, false);
        controlFix = rowButton(row(controls), "", this::fixControl, true); ((View) controlFix.getParent()).setVisibility(View.GONE);
        printRow = row(controls);
        pause = rowButton(printRow, "Pause", () -> confirmCommand("Pause the current print?", Cc2Codec.PAUSE), true);
        resume = rowButton(printRow, "Resume", () -> confirmCommand("Resume after checking why the printer paused?", Cc2Codec.RESUME), true);
        stop = rowButton(printRow, "Stop", () -> confirmCommand("Stop the current print? It cannot be resumed.", Cc2Codec.STOP), false); stop.setTextColor(ERROR);
        lightRow = row(controls);
        lightOn = rowButton(lightRow, "Light on", () -> light(true), false);
        lightOff = rowButton(lightRow, "Light off", () -> light(false), false);
        refresh = button(controls, "Refresh status", () -> { if (printer == null) return; if (printer.ready()) printer.refresh(); else printer.cloudVisible(true); });
        LinearLayout canvas = card("CANVAS filament trays"); canvasCard = canvas;
        trayList = new LinearLayout(this); trayList.setOrientation(LinearLayout.VERTICAL); canvas.addView(trayList);
        trays = label(canvas, "Connect to see reported trays, materials, colors and the active tray.", 14, MUTED, false);
        refill = button(canvas, "Automatic refill", this::confirmRefill);
        LinearLayout tuning = card("Printer controls"); tuningCard = tuning;
        tuningHint = label(tuning, "", 13, MUTED, false);
        heater = button(tuning, "Set heater temperatures now…", this::temperatureDialog);
        fan = button(tuning, "Fan setting…", this::fanDialog);
        speed = button(tuning, "Print speed mode…", this::speedDialog);
        LinearLayout upkeep = card("Maintenance"); upkeepCard = upkeep;
        upkeepHint = label(upkeep, "", 13, MUTED, false);
        maintenanceToggle = button(upkeep, "", this::toggleMaintenance);
        LinearLayout collapsible = new LinearLayout(this); collapsible.setOrientation(LinearLayout.VERTICAL); upkeep.addView(collapsible); maintenanceBody = collapsible;
        LinearLayout group = new LinearLayout(this); group.setOrientation(LinearLayout.VERTICAL); collapsible.addView(group); upkeepButtons = group;
        LinearLayout filamentRow = row(group);
        loadFilament = rowButton(filamentRow, "Load filament", () -> maintenanceConfirm("Load filament?", "The printer heats the nozzle and feeds filament. This can take a few minutes.", Cc2Codec.FEED), false);
        unloadFilament = rowButton(filamentRow, "Unload filament", () -> maintenanceConfirm("Unload filament?", "The printer heats the nozzle and retracts the filament. This can take a few minutes.", Cc2Codec.RETREAT), false);
        trayFilament = button(group, "Load or unload a CANVAS tray…", this::trayDialog);
        LinearLayout motionRow = row(group);
        homeAll = rowButton(motionRow, "Home all axes", () -> confirmRequest("Home all axes?", "The print head and bed move to their home positions. Keep the printer clear.", () -> Cc2Codec.homeRequest(0, "xyz")), false);
        jog = rowButton(motionRow, "Move axes…", this::jogDialog, false);
        LinearLayout calibrationRow = row(group);
        autoLevel = rowButton(calibrationRow, "Auto-level bed", () -> maintenanceConfirm("Run auto bed leveling?", "The printer probes the bed. Remove any objects first.", Cc2Codec.AUTO_LEVEL), false);
        vibration = rowButton(calibrationRow, "Vibration test", () -> maintenanceConfirm("Run vibration optimization?", "The printer shakes the print head and bed to tune motion. Keep the printer clear.", Cc2Codec.VIBRATION), false);
        selfCheck = button(group, "Full self-check…", () -> confirmRequest("Run the full self-check?", "Vibration optimization, heater (PID) check and bed leveling, as in Elegoo's app. This takes several minutes; keep the printer clear.", () -> Cc2Codec.selfCheckRequest(0)));
        urgentStop = button(upkeep, "Emergency stop…", () -> confirmRequest("Emergency stop?", "Halts the printer immediately, like the printer's emergency stop. A running print cannot be resumed.", () -> Cc2Codec.maintenanceRequest(0, Cc2Codec.URGENT_STOP)));
        urgentStop.setTextColor(ERROR);
        setMaintenanceOpen(false);
        currentSection = pages[1];
        // Order on the Files tab: what is on the printer, then sending a new file, then the rarely needed storage and history.
        buildFileBrowser();
        LinearLayout files = card("Send a new file to the printer");
        selected = label(files, "Slice a model or choose a .gcode file on this phone. Slicing needs no printer connection; upload does.", 14, MUTED, false);
        LinearLayout startRow = row(files);
        sliceModel = rowButton(startRow, "Slice a model…", () -> startActivityForResult(new Intent(this, SliceActivity.class), SLICE), true);
        pick = rowButton(startRow, "Choose G-code…", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent, PICK_FILE);
        }, false);
        button(files, "Find models online…", () -> startActivityForResult(new Intent(this, SliceActivity.class).putExtra(SliceActivity.EXTRA_OPEN, "find"), SLICE));
        recentSlices = button(files, "Recent slices…", this::chooseRecentSlice);
        // The chosen file: preview, actions and the offline report, shown once there is one.
        fileDetails = new LinearLayout(this); fileDetails.setOrientation(LinearLayout.VERTICAL); files.addView(fileDetails);
        filePreview = new ImageView(this); filePreview.setContentDescription("Embedded slicer preview of selected G-code"); filePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(-1, dp(180)); previewLayout.topMargin = dp(10); fileDetails.addView(filePreview, previewLayout); filePreview.setVisibility(View.GONE);
        previewInfo = label(fileDetails, "", 12, MUTED, false);
        LinearLayout uploadRow = row(fileDetails);
        upload = rowButton(uploadRow, "Upload", () -> {
            if (printer == null || printer.selectedName == null) return;
            new AlertDialog.Builder(this).setTitle("Upload " + printer.selectedName + "?")
                .setMessage("This sends the file only. " + (printer.nameOnPrinter(printer.selectedName) ? "A file with this name is already on the printer: uploading will replace the file of the same name. " : "A file with the same name would be replaced. ") + "Refresh Files after upload and choose Print setup to start it.")
                .setNegativeButton("Cancel", null).setPositiveButton("Upload", (dialog, which) -> { if (printer != null) printer.upload(); }).show();
        }, true);
        LinearLayout viewRow = row(fileDetails);
        previewToolpath = rowButton(viewRow, "Preview toolpath", () -> {
            if (printer == null || printer.selectedFile == null) return;
            startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FILE, printer.selectedFile.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, printer.selectedName));
        }, false);
        materialDetails = rowButton(viewRow, "Materials…", () -> {
            if (printer == null || printer.selectedReport == null) return;
            SlicedMaterials materials = printer.selectedReport.materials; LinearLayout body = dialogBody();
            for (SlicedMaterials.Entry entry : materials.entries) {
                LinearLayout row = new LinearLayout(this); body.addView(row);
                if (entry.color != null) { View swatch = new View(this); swatch.setContentDescription("Configured filament colour: " + colourWords(entry.color)); swatch.setBackgroundColor(Color.parseColor(entry.color)); LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(28),dp(28)); size.setMargins(0,dp(8),dp(12),0); row.addView(swatch,size); }
                label(row, entry.text(), 14, INK, false);
            }
            for (String warning : materials.warnings) label(body, warning, 13, MUTED, false);
            label(body, "Comment indices are source-array positions, not confirmed tool or CANVAS tray IDs. No mapping is applied.", 13, MUTED, false);
            ScrollView detail = new ScrollView(this); detail.addView(body); new AlertDialog.Builder(this).setTitle("Sliced material evidence").setView(detail).setPositiveButton("Close",null).show();
        }, false);
        inspection = label(fileDetails, "", 13, INK, false); inspection.setTextIsSelectable(true);
        LinearLayout keepRow = row(fileDetails);
        shareInspection = rowButton(keepRow, "Share report…", () -> {
            if (printer == null || printer.selectedReport == null) return;
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, StatusPresentation.clean(printer.selectedName) + "\n" + printer.selectedReport.text());
            startActivity(Intent.createChooser(send, "Share G-code inspection"));
        }, false);
        saveCopy = rowButton(keepRow, "Save G-code to phone…", this::saveSelected, false);
        clearCopy = rowButton(keepRow, "Remove", () -> { if (printer != null) printer.clearPhoneCopy(); }, false);
        transferRow = row(files);
        cancelUpload = rowButton(transferRow, "Cancel upload", () -> { if (printer != null) printer.cancelUpload(); }, false);
        cancelDownload = rowButton(transferRow, "Cancel download", () -> { if (printer != null) printer.cancelDownload(); }, false);
        label(fileDetails, "Uploads and downloads need the local connection (the printer's HTTP port 80). Downloads are kept in this app; Save to phone… exports a copy. The report reads slicer comments only; it does not simulate a print.", 12, MUTED, false);
        currentSection = pages[2]; buildCamera();
        currentSection = pages[3];
        cloudAccounts = new CloudAccountStore(this);
        LinearLayout account = card("Elegoo cloud (experimental)");
        cloudSettings = account;
        label(account, "Watch and control the printer away from home, through Elegoo's cloud.", 13, MUTED, false);
        cloudStatus = label(account, "", 14, INK, false);
        cloudLiveLabel = label(account, "", 13, MUTED, false);
        button(account, "Sign in with Elegoo…", this::cloudSignIn);
        // Cloud details and sign-out only appear while signed in (renderCloudAccount).
        cloudPrinters = button(account, "Cloud details…", () -> startActivity(new Intent(this, CloudStatusActivity.class)));
        cloudSignOut = button(account, "Sign out on this phone", this::cloudSignOut);
        cloudBackground = checkbox(account, "Keep watching through the cloud in the background", settings.getBoolean("cloudBackground", false));
        cloudBackground.setOnCheckedChangeListener((view, enabled) -> {
            settings.edit().putBoolean("cloudBackground", enabled).apply();
            if (enabled) { requestNotifications(); try { startForegroundService(new Intent(this, PrinterService.class)); } catch (Exception ignored) { } }
            if (printer != null) printer.cloudSettingsChanged();
        });
        label(account, "Your password goes only to Elegoo's own sign-in page. Without a local connection, Monitor shows the cloud's last update.", 13, MUTED, false);
        renderCloudAccount();
        setSettingsMode(settingsModeAtStart());
        LinearLayout preferences = card("App preferences");
        button(preferences, "Appearance: " + (settings.getInt("theme", 0) == 0 ? "System" : dark ? "Dark" : "Light"), this::appearanceDialog);
        CheckBox record = checkbox(preferences, "Make print recordings", settings.getBoolean("recordPrints", true));
        record.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("recordPrints", enabled).apply());
        CheckBox alerts = checkbox(preferences, "Completion and new fault notifications", settings.getBoolean("alerts", true));
        alerts.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("alerts", enabled).apply());
        label(preferences, "Alerts need notification permission and an active monitoring session. They stop after you disconnect or Android stops the process.", 13, MUTED, false);
        LinearLayout help = card("Help & diagnostics");
        button(help, "Connection help…", this::connectionHelp);
        probeButton = button(help, "Probe printer (read-only)…", this::probeDialog);
        // The probe result scrolls inside a fixed-height area, so a long answer does not push the rest of the page down.
        probeResult = A11y.polite(new TextView(this)); probeResult.setTextSize(13); probeResult.setTextColor(MUTED); probeResult.setPadding(0, dp(5), 0, dp(7)); probeResult.setTextIsSelectable(true);
        ScrollView probeArea = new ScrollView(this); probeArea.addView(probeResult);
        help.addView(probeArea, new LinearLayout.LayoutParams(-1, dp(200))); probeArea.setVisibility(View.GONE);
        button(help, "Share diagnostics…", this::shareDiagnostics);
        LinearLayout about = card("About Link Workshop " + appVersion());
        label(about, "Development build: printer behavior still needs hardware testing. Planned next: painting tools in the slicer, slicing all plates of a project at once, and other printer models.", 13, MUTED, false);
        button(about, "About & licenses", this::showLicenses);
        currentSection = null;
        if (saved != null) { host.setText(saved.getString("host", host.getText().toString())); serial.setText(saved.getString("serial", "")); diagnostics.setText(saved.getString("diagnostics", diagnostics.getText().toString())); profileName.setText(saved.getString("profileName", profileName.getText().toString())); }
        try { access.setText(credentials.load(host.getText().toString().trim())); remember.setChecked(saved == null ? credentials.remembers(host.getText().toString().trim()) : saved.getBoolean("remember", false)); }
        catch (Exception error) { credentials.forget(host.getText().toString().trim()); remember.setChecked(false); message("Saved code could not be decrypted. Enter it again before connecting."); }
        TransientInputs transientInputs = (TransientInputs) getLastNonConfigurationInstance();
        if (transientInputs != null && host.getText().toString().equals(transientInputs.host)) { access.setText(transientInputs.code); pendingSnapshot = transientInputs.snapshot; }
        selectPage(saved == null ? settings.getInt("page", 0) : saved.getInt("page", 0));
        if (saved == null) receiveSliced(getIntent());
        openRequestedPage(getIntent());
        bound = bindService(new Intent(this, PrinterService.class), binding, BIND_AUTO_CREATE);
        render();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); receiveSliced(intent); openRequestedPage(intent); }

    /** G-code from a Slice screen that another app opened ("Open with"), handed over like a slice started from Files. */
    private void receiveSliced(Intent intent) {
        if (intent == null) return;
        String setup = intent.getStringExtra(SliceActivity.RESULT_PRINT_SETUP);
        if (setup != null) { awaitPrintSetup(setup); intent.removeExtra(SliceActivity.RESULT_PRINT_SETUP); }
        String path = intent.getStringExtra(SliceActivity.RESULT_FILE), name = intent.getStringExtra(SliceActivity.RESULT_NAME);
        if (path == null || name == null) return;
        // Only the app's own sliced output is accepted.
        File file = new File(path), sliced = new File(getCacheDir(), "sliced");
        try { if (!file.getCanonicalFile().getParentFile().equals(sliced.getCanonicalFile()) || !file.isFile()) return; } catch (IOException error) { return; }
        pendingSlicedFile = path; pendingSlicedName = name; intent.removeExtra(SliceActivity.RESULT_FILE);
        settings.edit().putBoolean("everSliced", true).apply();
        selectPage(1); takeSliced();
    }
    /** After "Upload and print" on the Slice screen: open Print setup once the upload is in the printer's file list. */
    private static final long PRINT_SETUP_WAIT_MS = 5 * 60_000L;
    private long printSetupStarted;
    private void awaitPrintSetup(String name) {
        pendingPrintSetup = name; printSetupStarted = System.currentTimeMillis(); printSetupUntil = printSetupStarted + PRINT_SETUP_WAIT_MS; selectPage(1);
    }
    private void continuePrintSetup() {
        if (pendingPrintSetup == null || printer == null) return;
        long now = System.currentTimeMillis();
        boolean uploading = printer.uploading() || printer.uploadsPending();
        // A big upload can take far longer than the usual wait: keep waiting while it runs, for up to an hour.
        if (uploading && now - printSetupStarted < 60 * 60_000L) printSetupUntil = Math.max(printSetupUntil, now + PRINT_SETUP_WAIT_MS);
        if (now > printSetupUntil) {
            pendingPrintSetup = null;
            message(uploading ? "The upload is still running; open Print setup from Files when it finishes." : "Print setup did not open by itself. Refresh Files and choose Print setup on the uploaded file.");
            return;
        }
        if (printer.uploadsPending() || !printer.filesFresh() || !"local".equals(printer.storage)) return;
        org.json.JSONArray list = printer.filePage.optJSONArray("file_list");
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject file = list.optJSONObject(i);
            if (file != null && pendingPrintSetup.equals(file.optString("filename"))) { pendingPrintSetup = null; startDialog(file, "local"); return; }
        }
    }
    // ---- Print again: a history entry whose file the printer still holds opens the normal Print setup ----
    private final List<Object[]> againRows = new ArrayList<>(); // {Button, TextView reason, JSONObject history row}
    private PrintAgain againCheck(JSONObject row) {
        return PrintAgain.check(row.optString("task_name"), printer.filePage, printer.storage, printer.fileOffset, printer.filesFresh());
    }
    private void addAgain(JSONObject row) {
        Button again = button(historyList, "Print again", () -> printAgain(row));
        TextView why = label(historyList, "", 12, MUTED, false);
        againRows.add(new Object[] {again, why, row});
    }
    private void updateAgain(boolean query) {
        for (Object[] item : againRows) {
            Button again = (Button) item[0]; TextView why = (TextView) item[1];
            if (printer == null || !query) { again.setEnabled(false); why.setText("Connect, or watch through the cloud, to print again."); why.setVisibility(View.VISIBLE); continue; }
            PrintAgain check = againCheck((JSONObject) item[2]);
            again.setText(check.state == PrintAgain.State.REFRESH ? "Refresh files first" : "Print again");
            again.setEnabled(check.state == PrintAgain.State.READY || check.state == PrintAgain.State.REFRESH || check.state == PrintAgain.State.UNKNOWN);
            if (check.state == PrintAgain.State.UNKNOWN) again.setText("Check the file list");
            why.setText(check.reason); why.setVisibility(check.reason.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }
    /** Opens Print setup (all its checks and the final confirmation still apply), or refreshes the file list first. */
    private void printAgain(JSONObject row) {
        if (printer == null) return;
        PrintAgain check = againCheck(row);
        switch (check.state) {
            case READY: startDialog(check.file, "local"); break;
            case REFRESH: case UNKNOWN:
                if (!printer.canQuery()) { message("Refresh status and files, then try again: the printer's data is out of date."); break; }
                message("Refreshing the file list…"); printer.browse("local", 0); break;
            default: message(check.reason); break;
        }
    }
    private Bitmap decodeThumbnail(String data) {
        if (data == null || data.isEmpty()) return null;
        try {
            byte[] bytes = android.util.Base64.decode(data.contains(",") ? data.substring(data.indexOf(',') + 1) : data, android.util.Base64.DEFAULT);
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (IllegalArgumentException invalid) { return null; }
    }
    /** What the printer reports about one past print: its own record, a preview if the file is still there, and the detail query's answer. */
    private void historyDetail(JSONObject row) {
        if (printer == null) return;
        String name = row.optString("task_name"), taskId = row.optString("task_id");
        LinearLayout body = dialogBody(); ScrollView scroll = new ScrollView(this); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(StatusPresentation.clean(name).replaceFirst("(?i)\\.gcode$", "")).setView(scroll).setPositiveButton("Close", null).create();
        PrintAgain first = againCheck(row);
        if (first.file != null) printer.thumbnail("local", name);
        printer.historyDetail(taskId);
        Runnable[] refresh = new Runnable[1]; String[] shown = {null};
        refresh[0] = () -> {
            if (!dialog.isShowing() || printer == null) return;
            PrintAgain check = againCheck(row);
            Bitmap picture = decodeThumbnail(printer.thumbnails.get("local/" + name));
            JSONObject detail = printer.historyDetails.get(taskId); String missing = printer.historyDetailMessages.get(taskId);
            String signature = check.state + "|" + (picture != null) + "|" + (detail != null) + "|" + missing + "|" + printer.busy(Cc2Codec.HISTORY_DETAIL);
            if (!signature.equals(shown[0])) {
                shown[0] = signature; body.removeAllViews();
                if (picture != null) {
                    ImageView image = new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setImageBitmap(picture);
                    image.setContentDescription("Preview image of this print file from the printer"); body.addView(image, new LinearLayout.LayoutParams(-1, dp(180)));
                }
                for (String[] line : FeatureData.historyDetail(row, detail, Locale.getDefault(), TimeZone.getDefault())) {
                    LinearLayout pair = new LinearLayout(this); pair.setOrientation(LinearLayout.VERTICAL); pair.setPadding(0, dp(6), 0, dp(2)); body.addView(pair);
                    label(pair, line[0], 12, MUTED, false); label(pair, line[1], 15, INK, false);
                }
                if (detail == null) label(body, missing != null ? missing : printer.busy(Cc2Codec.HISTORY_DETAIL) ? "Asking the printer for more details…" : "The printer reports only what is shown above.", 12, MUTED, false);
                label(body, check.state == PrintAgain.State.READY || check.state == PrintAgain.State.REFRESH ? "The file is on the printer." : check.reason, 13, check.state == PrintAgain.State.MISSING ? MUTED : INK, false);
                Button again = button(body, check.state == PrintAgain.State.REFRESH ? "Refresh files first" : check.state == PrintAgain.State.UNKNOWN ? "Check the file list" : "Print again", () -> { dialog.dismiss(); printAgain(row); });
                again.setEnabled(check.state != PrintAgain.State.MISSING);
            }
            main.postDelayed(refresh[0], 700);
        };
        dialog.show(); refresh[0].run();
    }
    /** Slices made on this phone are kept (SliceStore): choose one to upload or save, e.g. after a failed upload or a killed app. */
    private void chooseRecentSlice() {
        List<File> recent = SliceStore.list(this);
        if (printer == null || recent.isEmpty()) { message("No recent slices yet."); return; }
        String[] titles = new String[recent.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = recent.get(i).getName() + "\n" + (recent.get(i).length() / 1024) + " KB · " + android.text.format.DateUtils.getRelativeTimeSpanString(recent.get(i).lastModified());
        new AlertDialog.Builder(this).setTitle("Recent slices").setItems(titles, (d, which) -> { if (printer != null) printer.selectSliced(recent.get(which), recent.get(which).getName()); })
            .setNegativeButton("Close", null).show();
    }
    private void takeSliced() {
        if (printer == null || pendingSlicedFile == null) return;
        printer.selectSliced(new File(pendingSlicedFile), pendingSlicedName); pendingSlicedFile = null; pendingSlicedName = null;
    }

    private void toggleConnection() {
        if (printer == null) return;
        if (printer.connecting()) { printer.disconnect(); return; }
        String address = host.getText().toString().trim(), code = pinProbe() ? pairingPin.getText().toString() : access.getText().toString();
        String serialNumber = serial.getText().toString().trim();
        if (!serialNumber.isEmpty() && !Cc2Discovery.validSerial(serialNumber)) { message("Enter the exact printer serial, using letters, numbers, hyphens or underscores, without spaces."); return; }
        try { new PrinterAuthentication(pinProbe(), code); new PrinterHttp(address, pinProbe() ? "" : code); }
        catch (Exception error) { message(pinProbe() ? "Enter a valid private printer IP and its current displayed pairing PIN." : "Enter a valid private IPv4 address and access code."); return; }
        try { if (!pinProbe()) credentials.save(address, code, remember.isChecked()); }
        catch (Exception error) { message("Could not save the code securely. Uncheck Remember to connect without saving."); return; }
        requestNotifications();
        try {
            startForegroundService(new Intent(this, PrinterService.class));
            printer.connect(address, code, serialNumber, remoteMode(), pinProbe()); render();
        } catch (Exception error) { printer.disconnect(); message("Android could not start printer monitoring. Keep the app open and check its permissions."); }
    }
    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !getPreferences(MODE_PRIVATE).getBoolean("notificationsAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationsAsked", true).apply();
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }
    private void checkConnection() {
        if (checking) return;
        String address = host.getText().toString().trim(), code = pinProbe() ? "" : access.getText().toString();
        String serialNumber = serial.getText().toString().trim();
        if (!serialNumber.isEmpty() && !Cc2Discovery.validSerial(serialNumber)) { diagnostics.setText("Enter a valid serial number or leave it blank for discovery."); return; }
        try { new PrinterHttp(address, code); } catch (Exception error) { diagnostics.setText("Enter a valid private IPv4 address before checking."); return; }
        checking = true; diagnostics.setText(remoteMode() ? "Checking VPN, HTTP 80, MQTT 1883, camera 8080, and UDP identity 52700…" : "Checking Wi-Fi, HTTP 80, MQTT 1883, and UDP discovery 52700…"); render();
        Context context = getApplicationContext(); boolean remote = remoteMode(), probe = pinProbe();
        checks.execute(() -> {
            String report;
            try { report = NetworkRoute.select(context, remote).check(address, code, serialNumber, probe); }
            catch (IOException error) { report = remote ? "Home VPN is unavailable or changed. Enable Tailscale / your home VPN, verify this app uses it, and check again." : "No local Wi-Fi network is available. Connect the phone to the printer's Wi-Fi and check again."; }
            catch (Exception error) { report = "Connection check failed. Confirm the current printer IP and the phone's Wi-Fi."; }
            String result = report;
            main.post(() -> { if (!isDestroyed()) { checking = false; diagnostics.setText(result); render(); } });
        });
    }
    /** "Last report 3 min ago" from the printer's own report time (the poll time only when the cloud gave none). */
    private String lastReport() {
        long at = printer.cloudReportedAt > 0 ? printer.cloudReportedAt : printer.cloudCheckedAt;
        return "Last report " + CloudStatusActivity.age(System.currentTimeMillis() - at) + " ago" + (printer.cloudLiveOn ? " · live" : "") + ".";
    }
    private boolean viaCloud() { return printer != null && !printer.ready() && printer.usingCloud(); }
    private void confirmCommand(String title, int method) {
        boolean cloud = viaCloud();
        Runnable send = () -> {
            if (printer == null) return;
            if (!cloud) printer.command(method);
            else if (method == Cc2Codec.PAUSE) printer.cloudPause(); else if (method == Cc2Codec.RESUME) printer.cloudResume(); else if (method == Cc2Codec.STOP) printer.cloudStop();
        };
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(title).setNegativeButton("Cancel", null)
            .setPositiveButton(method == Cc2Codec.PAUSE ? "Pause" : method == Cc2Codec.RESUME ? "Resume" : "Stop", (d, which) -> cloudGate(cloud, send));
        if (cloud) dialog.setMessage("Sent through the Elegoo cloud. It is sent once and never repeated automatically.");
        dialog.show();
    }
    private interface Built { JSONObject build() throws Exception; }
    private void confirmRequest(String title, String text, Built builder) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(text + (viaCloud() ? "\n\nSent through the Elegoo cloud." : "")).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", (d, which) -> {
                if (printer == null) return;
                try { JSONObject request = builder.build(); cloudGate(viaCloud(), () -> printer.maintenance(request)); }
                catch (Exception error) { message("Could not prepare the command."); }
            }).show();
    }
    private void maintenanceConfirm(String title, String text, int method) { confirmRequest(title, text, () -> Cc2Codec.maintenanceRequest(0, method)); }
    private void trayDialog() {
        if (printer == null || printer.canvas == null) { message("Refresh status to load CANVAS trays first."); return; }
        List<int[]> slots = new ArrayList<>(); List<String> names = new ArrayList<>();
        JSONArray units = printer.canvas.optJSONArray("canvas_list");
        if (units != null) for (int u = 0; u < units.length(); u++) {
            JSONObject unit = units.optJSONObject(u); if (unit == null) continue; JSONArray trays = unit.optJSONArray("tray_list"); if (trays == null) continue;
            for (int t = 0; t < trays.length(); t++) {
                JSONObject tray = trays.optJSONObject(t); if (tray == null) continue;
                slots.add(new int[] {unit.optInt("canvas_id"), tray.optInt("tray_id")});
                names.add("CANVAS " + unit.optInt("canvas_id") + " · tray " + tray.optInt("tray_id") + " · " + StatusPresentation.clean(tray.optString("filament_type", "empty")) + " " + StatusPresentation.clean(tray.optString("filament_color")));
            }
        }
        if (slots.isEmpty()) { message("No CANVAS trays reported."); return; }
        new AlertDialog.Builder(this).setTitle("Choose a tray").setItems(names.toArray(new String[0]), (dialog, which) -> {
            int[] slot = slots.get(which);
            new AlertDialog.Builder(this).setTitle(names.get(which)).setNegativeButton("Cancel", null)
                .setNeutralButton("Unload", (d, w) -> confirmRequest("Unload this tray?", "The printer cuts and retracts the filament for this tray.", () -> Cc2Codec.canvasFilamentRequest(0, false, slot[0], slot[1])))
                .setPositiveButton("Load", (d, w) -> confirmRequest("Load this tray?", "The printer heats the nozzle and loads filament from this tray.", () -> Cc2Codec.canvasFilamentRequest(0, true, slot[0], slot[1]))).show();
        }).setNegativeButton("Cancel", null).show();
    }
    /** Jog one homed axis by a chosen step, as Elegoo's page allows; each tap is one confirmed-once-per-dialog move. */
    private void jogDialog() {
        if (printer == null) return;
        JSONObject current = printer.liveStatus();
        LinearLayout body = dialogBody();
        JSONObject position = Cc2Codec.position(current), head = current.optJSONObject("tool_head") != null ? current.optJSONObject("tool_head") : current.optJSONObject("toolhead");
        label(body, "Homed: " + (head == null ? "unknown" : head.optString("homed_axes", "none").toUpperCase(Locale.ROOT))
            + (position == null ? "" : String.format(Locale.ROOT, "\nPosition X %.1f · Y %.1f · Z %.1f", position.optDouble("x", 0), position.optDouble("y", 0), position.optDouble("z", 0)))
            + "\nAn axis must be homed before it can move. Each tap sends one move.", 14, INK, false);
        Spinner axis = spinner(body, new String[] {"X axis", "Y axis", "Z axis"}); A11y.name(axis, "Axis to move");
        Spinner step = spinner(body, new String[] {"0.1 mm", "1 mm", "10 mm"}); A11y.name(step, "Step size");
        double[] steps = {0.1, 1, 10}; String[] axes = {"x", "y", "z"};
        LinearLayout moves = row(body);
        rowButton(moves, "− Move", () -> jog(axes[axis.getSelectedItemPosition()], -steps[step.getSelectedItemPosition()]), false);
        rowButton(moves, "+ Move", () -> jog(axes[axis.getSelectedItemPosition()], steps[step.getSelectedItemPosition()]), false);
        rowButton(row(body), "Home this axis", () -> { if (printer != null) try { printer.maintenance(Cc2Codec.homeRequest(0, axes[axis.getSelectedItemPosition()])); } catch (Exception ignored) { } }, false);
        new AlertDialog.Builder(this).setTitle("Move axes").setView(body).setPositiveButton("Done", null).show();
    }
    private void jog(String axis, double distance) {
        if (printer == null) return;
        try { JSONObject request = Cc2Codec.moveRequest(0, axis, distance); cloudGate(viaCloud(), () -> printer.maintenance(request)); }
        catch (Exception error) { message("Invalid move."); }
    }
    private void light(boolean on) {
        if (printer == null) return;
        if (viaCloud()) cloudGate(true, () -> printer.cloudLight(on)); else printer.light(on);
    }
    /** First cloud command: explain that it shares ElegooSlicer's cloud control identity. */
    /** Explains the read-only probe before it runs; through the cloud it needs the cloud-control agreement like any request. */
    private void probeDialog() {
        if (printer == null) return;
        if (printer.probing) { printer.cancelProbe(); return; }
        boolean cloud = !printer.ready() && printer.usingCloud() && printer.cloudFresh();
        new AlertDialog.Builder(this).setTitle("Probe the printer?")
            .setMessage("Checks which network ports the printer accepts on this Wi-Fi (and what its web server says, if it has one), then asks it each read-only question "
                + "Elegoo's own printer page knows: system info, status, fans, homing, files, history, storage, camera, filament, AI detection and CANVAS. "
                + "Each is asked once, one at a time. Nothing that moves, heats, prints, deletes or updates the printer is ever sent, and unknown method numbers are never tried.\n\n"
                + (printer.ready() ? "Questions go over the local connection." : cloud ? "Questions go through the Elegoo cloud; cloud controls wait while it runs (about a minute)." : "There is no local connection or fresh cloud status, so only the network ports are checked.")
                + "\n\nThe result lists what answered. Share diagnostics… adds the field names of each answer, never their values, addresses or serial numbers.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Probe", (d, which) -> cloudGate(cloud, () -> { if (printer != null) printer.probePrinter(); })).show();
    }
    private void cloudGate(boolean cloud, Runnable action) {
        if (!cloud || settings.getBoolean("cloudControlUnderstood", false)) { action.run(); return; }
        new AlertDialog.Builder(this).setTitle("Turn on cloud control?")
            .setMessage("Without a local connection, controls, files, history and settings go through Elegoo's cloud, using the same cloud sign-in as ElegooSlicer on a computer. If ElegooSlicer is open with this account, one of them may be signed out of cloud control. The Matrix app is expected to keep working, but this is untested.\n\nThe app connects only while you use these, and disconnects after two idle minutes. Monitoring works either way.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Turn on", (d, which) -> { settings.edit().putBoolean("cloudControlUnderstood", true).apply(); action.run(); }).show();
    }
    private void confirmRefill() {
        if (printer == null || !printer.canvasFresh() || !printer.canvas.has("auto_refill")) return;
        boolean enabled = !printer.canvas.optBoolean("auto_refill");
        new AlertDialog.Builder(this).setTitle((enabled ? "Enable" : "Disable") + " automatic refill?")
            .setMessage("This changes the printer's CANVAS automatic-refill setting. Tray selection and filament loading remain controlled by the printer.")
            .setNegativeButton("Cancel", null).setPositiveButton(enabled ? "Enable" : "Disable", (dialog, which) -> { if (printer != null) printer.autoRefill(enabled); }).show();
    }
    /** Opens the tab another screen asked for (EXTRA_PAGE), once. */
    private void openRequestedPage(Intent intent) {
        if (intent != null && intent.hasExtra(EXTRA_PAGE)) { selectPage(intent.getIntExtra(EXTRA_PAGE, 0)); intent.removeExtra(EXTRA_PAGE); }
        // Launcher shortcuts name a feature; go there as "Find a feature" does, once the screen is laid out.
        String feature = intent == null ? null : intent.getStringExtra(EXTRA_FEATURE);
        if (feature != null) { intent.removeExtra(EXTRA_FEATURE); FeatureIndex.Feature f = FeatureIndex.byId(feature); if (f != null) main.post(() -> goToFeature(f)); }
    }

    // ------------------------------------------------------------------ Find a feature
    static final String EXTRA_FEATURE = "feature";
    private ScrollView mainScroll;
    private List<String> recentFeatures() {
        List<String> ids = new ArrayList<>();
        for (String id : settings.getString("recentFeatures", "").split(",")) if (!id.isEmpty()) ids.add(id);
        return ids;
    }
    private void featureDialog() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), 0);
        EditText query = new EditText(this); query.setHint("What are you looking for? e.g. level, timelapse"); query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT); query.setTextColor(INK); query.setHintTextColor(MUTED);
        body.addView(query, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView hint = label(body, "", 12, MUTED, false);
        ListView list = new ListView(this); body.addView(list, new LinearLayout.LayoutParams(-1, dp(360)));
        List<FeatureIndex.Feature> shown = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Find a feature").setView(body).setNegativeButton("Close", null).create();
        Runnable refresh = () -> {
            shown.clear(); shown.addAll(FeatureIndex.search(query.getText().toString(), recentFeatures()));
            adapter.clear(); for (FeatureIndex.Feature f : shown) adapter.add(f.title + "  ·  " + f.where());
            boolean typed = query.length() > 0;
            hint.setText(shown.isEmpty() ? "Nothing matches. Try another word, such as camera, filament or history."
                : typed ? "" : recentFeatures().isEmpty() ? "Everything the app can do, by where it lives." : "Recently used first.");
        };
        query.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { refresh.run(); }
            public void afterTextChanged(android.text.Editable s) { }
        });
        list.setOnItemClickListener((parent, view, position, id) -> { dialog.dismiss(); goToFeature(shown.get(position)); });
        refresh.run();
        dialog.show();
    }
    /**
     * Goes to a feature: its tab, any collapsed section or Settings mode it lives in, then brings its button into view and
     * marks it. Only features that open a screen or a dialog are pressed; printer-changing buttons are only shown.
     */
    private void goToFeature(FeatureIndex.Feature f) {
        settings.edit().putString("recentFeatures", String.join(",", FeatureIndex.used(recentFeatures(), f.id))).apply();
        switch (f.id) {   // tools that live on the Slice screen
            case "slice": startActivityForResult(new Intent(this, SliceActivity.class), SLICE); return;
            case "find-models": startActivityForResult(new Intent(this, SliceActivity.class).putExtra(SliceActivity.EXTRA_OPEN, "find"), SLICE); return;
            case "calibration": startActivityForResult(new Intent(this, SliceActivity.class).putExtra(SliceActivity.EXTRA_OPEN, "calibration"), SLICE); return;
            default: break;
        }
        switch (f.prepare) {
            case HISTORY_OPEN: setHistoryOpen(true); break;
            case MAINTENANCE_OPEN: setMaintenanceOpen(true); break;
            case SETTINGS_LOCAL: setSettingsMode(false); break;
            case SETTINGS_CLOUD: setSettingsMode(true); break;
            case ADVANCED_CONNECTION: setSettingsMode(false); setMore(true); break;
            default: break;
        }
        selectPage(f.tab);
        render();
        main.post(() -> {
            View target = findByText(pages[f.tab], f.target);
            if (target == null || !target.isShown()) {
                // Hidden in this state (printer controls while it prints, or not connected): show the card that explains when.
                View card = f.fallback.isEmpty() ? null : findByText(pages[f.tab], f.fallback);
                if (card != null && card.isShown()) {
                    scrollTo(card); highlight(card);
                    message(f.title + " appears here when the printer is connected and idle.");
                } else message("toolpath".equals(f.id) ? "Live toolpath appears on Monitor while a print is running."
                    : f.title + " needs a connected printer" + (f.tab == FeatureIndex.MONITOR ? " that is idle" : "") + ". Connect in Settings first.");
                return;
            }
            scrollTo(target); highlight(target);
            if (f.press && target.isEnabled() && target instanceof Button) target.performClick();
            else if (!f.press && target instanceof Button && !target.isEnabled()) message(f.title + " is here, but not available right now (it needs a connected, idle printer).");
        });
    }
    /** The first visible button, check box or heading in `root` whose text starts with `text`. */
    static View findByText(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().startsWith(text) && root.getVisibility() == View.VISIBLE) return root;
        if (root instanceof ViewGroup && root.getVisibility() == View.VISIBLE)
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = findByText(((ViewGroup) root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private void scrollTo(View target) {
        int[] at = new int[2], top = new int[2]; target.getLocationInWindow(at); mainScroll.getLocationInWindow(top);
        mainScroll.smoothScrollTo(0, Math.max(0, mainScroll.getScrollY() + at[1] - top[1] - dp(96)));
    }
    /** A brief outline around what was found, and focus for TalkBack. */
    private void highlight(View target) {
        GradientDrawable ring = new GradientDrawable(); ring.setCornerRadius(dp(12)); ring.setStroke(dp(3), TEAL); ring.setColor(Color.TRANSPARENT);
        target.setForeground(ring);
        target.postDelayed(() -> target.setForeground(null), 2500);
        target.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }
    private void selectPage(int selected) {
        page = Math.max(0, Math.min(3, selected));
        if (page != 2) stopCamera();
        if (page == 1) { filesAutoAt = 0; main.post(this::autoLoadFiles); }
        for (int i = 0; i < 4; i++) {
            pages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
            int color = i == page ? TEAL : MUTED;
            ((ImageView) tabs[i].getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
            TextView name = (TextView) tabs[i].getChildAt(1); name.setTextColor(color); name.setTypeface(Typeface.DEFAULT, i == page ? Typeface.BOLD : Typeface.NORMAL);
            A11y.tab(tabs[i], i == page);
        }
        settings.edit().putInt("page", page).apply();
    }
    private void fixControl() {
        if (block == ControlState.Block.CLOUD_AGREEMENT) cloudGate(true, this::render); else if (block == ControlState.Block.DISCONNECTED) selectPage(3);
    }
    /** One compact tappable row for a printer file: name, size and layers beneath, a chevron, and a hairline above all but the first. */
    private void fileRow(String name, String detail, boolean divider, Runnable action) { listRow(fileRows, name, detail, divider, action); }
    /** A compact list row (name, muted detail line); tappable with a chevron when there is an action, plain otherwise. */
    private void listRow(LinearLayout list, String name, String detail, boolean divider, Runnable action) {
        if (divider) { View line = new View(this); line.setBackgroundColor(TRACK); list.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)))); }
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(10), 0, dp(10)); row.setMinimumHeight(dp(56));
        if (action != null) row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), null, new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView first = new TextView(this); first.setText(name); first.setTextSize(15); first.setTextColor(INK); first.setSingleLine(true); first.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); text.addView(first);
        TextView second = new TextView(this); second.setText(detail); second.setTextSize(12); second.setTextColor(MUTED); text.addView(second);
        if (action != null) { TextView chevron = new TextView(this); chevron.setText("›"); chevron.setTextSize(24); chevron.setTextColor(MUTED); chevron.setPadding(dp(12), 0, 0, 0); row.addView(chevron); row.setOnClickListener(v -> action.run()); }
        row.setContentDescription(name + ", " + detail); list.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    private void toggleMore() { setMore(!moreOpen); }
    private void setMore(boolean open) {
        moreOpen = open; moreOptions.setVisibility(open ? View.VISIBLE : View.GONE);
        moreToggle.setText(open ? "Hide advanced options ▴" : "Advanced connection options ▾");
        moreToggle.setContentDescription(open ? "Hide advanced connection options" : "Advanced connection options"); A11y.expandable(moreToggle, open);
    }
    private boolean pinProbe() { return authPicker != null && authPicker.getSelectedItemPosition() == 1; }
    private boolean remoteMode() { return routePicker != null && routePicker.getSelectedItemPosition() == 1; }
    private void connectionHelp() {
        new AlertDialog.Builder(this).setTitle("Connecting locally")
            .setMessage("Printer IP: printer Settings → Network.\n\nAccess code: turn on LAN Only on the printer and use the code it shows (blank if code protection is off).\n\nSerial number: leave blank so the app finds it automatically; if that fails, enter it from printer Settings → Device.\n\nConnection route: Local Wi-Fi at home, or Remote through home VPN with your Pi gateway (see Remote access setup).\n\nCheck connection tests reachability (MQTT 1883, HTTP 80, camera 8080, UDP 52700). Uploads need HTTP port 80.\n\nWithout LAN Only, sign in with Elegoo below to monitor and control through the cloud instead.")
            .setPositiveButton("Close", null).show();
    }
    private void coexistenceHelp() {
        new AlertDialog.Builder(this).setTitle("Read-only Matrix coexistence test")
            .setMessage("1. Leave the printer in normal cloud mode (LAN Only off). Confirm Matrix shows live status/camera. Do not unbind or re-pair the printer.\n\n2. Use home Wi-Fi first. Select Cloud-mode PIN probe and enter the current printer-displayed pairing PIN if available. Supply the exact serial if UDP discovery cannot identify it.\n\n3. Check connection, then Connect. This tries the SDK's local MQTT PIN path once. It does not sign into your Elegoo account, bind devices, copy cloud client identities, guess credentials or use a PIN in HTTP.\n\n4. Compare live status here and in Matrix, switch between apps, and confirm Matrix remains connected. Registration alone does not prove coexistence. If refused or disconnected, the probe stops; firmware may require the official cloud transport.\n\nPrinter-changing controls and uploads stay disabled in this probe. Camera/read queries depend on firmware. PINs are not saved, included in diagnostics or logged. If no monitoring session remains, reopening the app requires the PIN again.")
            .setPositiveButton("Close", null).show();
    }
    private void remoteHelp() {
        LinearLayout body = dialogBody();
        label(body, "Use your always-on Pi as a Tailscale subnet router. Install Tailscale on the Pi and phone, then sign in to your own tailnet. The Pi needs access to the printer on your home network.", 14, INK, false);
        label(body, "On the Pi: enable IPv4 forwarding and advertise only the printer's IP as a /32 route. Approve that route in the Tailscale admin console. Restrict the phone's access to printer TCP 1883 (monitor/control), 80 (uploads if available), 8080 (camera) and optionally UDP 52700 (identity). Broader existing access rules must also be reviewed.", 14, INK, false);
        label(body, "Choose Remote through home VPN and keep the printer's home IP (not the Pi's Tailscale IP). Authentication is separate: the access code uses LAN Only; the experimental read-only PIN probe keeps cloud mode enabled to test Matrix coexistence. Establish the selected authentication locally first. A manual serial provides fallback if UDP identity does not reply.", 14, INK, false);
        label(body, "The internet hop from phone to Pi is encrypted by the VPN. The Pi-to-printer hop retains the printer's LAN protocol. Do not publicly forward printer ports. Secure your account with MFA and keep the Pi updated. Android's always-on VPN/block-without-VPN setting provides stronger enforcement against connection-loss races. VPN presence alone does not prove the gateway/route or encryption configuration.", 14, MUTED, false);
        label(body, "The app does not install or configure Tailscale, and it does not bypass printer authentication. HTTP unavailable at home stays unavailable remotely. Monitoring/control/upload/camera requests use the selected route; VPN loss closes the session and requires a fresh connection without command replay.", 14, MUTED, false);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Away-from-home access").setView(scroll).setPositiveButton("Close", null).show();
    }
    private void cloudSignIn() {
        new AlertDialog.Builder(this).setTitle("Elegoo account region").setSingleChoiceItems(new String[] {"Global (account.elegoo.com)", "China mainland (account.elegoo.com.cn)"}, cloudAccounts.china() ? 1 : 0, (dialog, which) -> {
            dialog.dismiss(); cloudAccounts.china(which == 1);
            startActivityForResult(new Intent(this, CloudLoginActivity.class).putExtra(CloudLoginActivity.EXTRA_CHINA, which == 1), CLOUD_LOGIN);
        }).setNegativeButton("Cancel", null).show();
    }
    private void cloudSignOut() {
        cloudAccounts.forget();
        android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.WebStorage.getInstance().deleteAllData();
        settings.edit().putBoolean("cloudBackground", false).apply(); cloudBackground.setChecked(false);
        renderCloudAccount(); message("Signed out of Elegoo on this phone. Other apps signed in to the same account are not affected.");
    }
    private void renderCloudAccount() {
        CloudLogin.Account account;
        try { account = cloudAccounts.load(); }
        catch (Exception error) { cloudAccounts.forget(); account = null; message("Saved Elegoo sign-in could not be decrypted. Sign in again."); }
        cloudStatus.setText(account == null ? "Not signed in." : "Signed in as " + account.summary() + ".");
        // Signed out, these have nothing to act on, so they are not shown as dead buttons.
        int signedIn = account != null ? View.VISIBLE : View.GONE;
        cloudSignOut.setVisibility(signedIn); cloudPrinters.setVisibility(signedIn); cloudBackground.setVisibility(signedIn);
        if (printer != null) printer.cloudSettingsChanged();
    }
    private void appearanceDialog() {
        new AlertDialog.Builder(this).setTitle("Appearance").setSingleChoiceItems(new String[] {"Follow system", "Light", "Dark"}, settings.getInt("theme", 0), (dialog, which) -> {
            settings.edit().putInt("theme", which).apply(); dialog.dismiss(); recreate();
        }).setNegativeButton("Cancel", null).show();
    }
    private void savePrinter() {
        String address = host.getText().toString().trim(), code = access.getText().toString();
        try {
            new PrinterHttp(address, pinProbe() ? "" : code);
            profiles.save(profileName.getText().toString().trim(), address, serial.getText().toString().trim(), remoteMode(), pinProbe());
            if (!pinProbe()) credentials.save(address, code, remember.isChecked()); message(pinProbe() ? "Profile saved with PIN-probe mode. The PIN is not saved." : "Printer profile saved. Access-code storage follows the Remember setting.");
        } catch (Exception error) { message("Profile could not be saved. Check the IP and serial, or uncheck Remember if credential storage is unavailable."); }
    }
    private void choosePrinter() {
        JSONArray rows = profiles.all(); if (rows.length() == 0) { message("No saved printers. Enter an IP or use Find printers, then Save printer profile."); return; }
        String[] names = new String[rows.length()];
        for (int i = 0; i < names.length; i++) { JSONObject row = rows.optJSONObject(i); names[i] = row == null ? "Invalid profile" : row.optString("name") + " · " + row.optString("host"); }
        new AlertDialog.Builder(this).setTitle("Saved printers").setItems(names, (dialog, which) -> {
            JSONObject row = rows.optJSONObject(which); if (row != null) { selectPrinter(row.optString("host"), row.optString("serial"), row.optString("name")); routePicker.setSelection(row.optBoolean("remote_vpn", false) ? 1 : 0); authPicker.setSelection(row.optBoolean("pin_probe", false) ? 1 : 0); }
        }).setNegativeButton("Cancel", null).show();
    }
    private void selectPrinter(String address, String number, String name) {
        if (printer != null && printer.connecting()) { message("Disconnect before selecting another printer."); return; }
        stopCamera(); pairingPin.setText(""); host.setText(address); serial.setText(number); profileName.setText(name); access.setText(""); remember.setChecked(credentials.remembers(address));
        try { access.setText(credentials.load(address)); } catch (Exception error) { credentials.forget(address); remember.setChecked(false); message("Saved code could not be decrypted. Enter it again."); }
        cameraHost = ""; diagnostics.setText("Run Check connection to inspect this printer."); render();
    }
    private void removePrinter() {
        String address = host.getText().toString().trim();
        new AlertDialog.Builder(this).setTitle("Remove saved profile?").setMessage("This removes the profile and its saved access code from this phone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove", (dialog, which) -> { profiles.remove(address); credentials.forget(address); access.setText(""); remember.setChecked(false); profileName.setText(""); message("Saved profile removed."); }).show();
    }
    private void scanPrinters() {
        if (remoteMode()) { message("Wi-Fi broadcast scanning is unavailable over a routed VPN. Enter the home printer IP and serial, or use a saved profile."); return; }
        if (scanningNow) return;
        scanningNow = true; render();
        checks.execute(() -> {
            List<Cc2Discovery.Found> found = Collections.emptyList(); String failure = "";
            try (Cc2Discovery scanner = NetworkRoute.local(getApplicationContext()).discovery()) { scanning = scanner; found = scanner.scan(); }
            catch (Exception error) { failure = "Could not scan local Wi-Fi. Confirm the phone is connected to the printer's network."; }
            finally { scanning = null; }
            List<Cc2Discovery.Found> result = found; String error = failure;
            main.post(() -> {
                if (isDestroyed()) return; scanningNow = false; render();
                if (!error.isEmpty()) { message(error); return; }
                if (result.isEmpty()) { message("No CC2 discovery replies received. You can still enter the printer IP and serial manually; some networks block broadcasts."); return; }
                String[] names = new String[result.size()];
                for (int i = 0; i < names.length; i++) { Cc2Discovery.Found entry = result.get(i); names[i] = StatusPresentation.clean(entry.info.name.isEmpty() ? entry.info.model : entry.info.name) + " · " + entry.host + "\n" + entry.info.summary(); }
                new AlertDialog.Builder(this).setTitle("Printers found").setItems(names, (dialog, which) -> {
                    Cc2Discovery.Found entry = result.get(which); selectPrinter(entry.host, entry.info.serial, entry.info.name); message(entry.info.summary());
                }).setNegativeButton("Cancel", null).show();
            });
        });
    }
    private void buildFileBrowser() {
        LinearLayout browser = card("Printer files");
        // Storage picker and Refresh each take a full row, so long storage names and large font sizes never squeeze them.
        storagePicker = spinner(browser, new String[] {"Internal storage", "USB drive"}); A11y.name(storagePicker, "Printer storage to list");
        storagePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { if (printer != null && printer.canQuery() && !printer.busy(Cc2Codec.FILES)) printer.browse(position == 0 ? "local" : "u-disk", 0); }
        });
        listFiles = button(browser, "Refresh", () -> { if (printer != null) printer.browse(storagePicker.getSelectedItemPosition() == 0 ? "local" : "u-disk", 0); });
        fileInfo = label(browser, "Connect, then refresh to browse printer files.", 14, MUTED, false);
        filesFix = rowButton(row(browser), "", this::fixControl, true); ((View) filesFix.getParent()).setVisibility(View.GONE);
        fileRows = new LinearLayout(this); fileRows.setOrientation(LinearLayout.VERTICAL); browser.addView(fileRows);
        pageRow = row(browser);
        previousFiles = rowButton(pageRow, "Previous 50", () -> { if (printer != null) printer.browse(printer.storage, Math.max(0, printer.fileOffset - 50)); }, false);
        nextFiles = rowButton(pageRow, "Next 50", () -> { if (printer != null) printer.browse(printer.storage, printer.fileOffset + 50); }, false);
        fileHelp = label(browser, "Tap a file to start a print, download it or delete it. Printing and deleting need an idle printer.", 13, MUTED, false);
        // Storage, print history, timelapses and recordings are rarely needed, so they sit behind one toggle that starts collapsed.
        LinearLayout storage = card("Print history & storage");
        historyToggle = button(storage, "", () -> setHistoryOpen(!historyOpen));
        historyBody = new LinearLayout(this); historyBody.setOrientation(LinearLayout.VERTICAL); storage.addView(historyBody);
        setHistoryOpen(false);
        diskInfo = label(historyBody, "Storage usage not loaded.", 14, MUTED, false);
        LinearLayout refreshRow = row(historyBody); historyButtons = refreshRow;
        loadDisk = rowButton(refreshRow, "Refresh storage", () -> { if (printer != null) printer.loadDisk(); }, false);
        loadHistory = rowButton(refreshRow, "Refresh history", () -> { if (printer != null) printer.loadHistory(); }, false);
        historyInfo = label(historyBody, "History not loaded.", 14, INK, false); historyInfo.setTextIsSelectable(true);
        historyList = new LinearLayout(this); historyList.setOrientation(LinearLayout.VERTICAL); historyBody.addView(historyList);
        // Timelapse videos the printer made for recent prints: download over LAN, then save.
        timelapseList = new LinearLayout(this); timelapseList.setOrientation(LinearLayout.VERTICAL); historyBody.addView(timelapseList);
        timelapseHint = label(historyBody, "", 12, MUTED, false);
        saveTimelapse = button(historyBody, "Save timelapse to phone…", () -> {
            if (printer == null || printer.timelapseFile == null) return;
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("video/mp4").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, printer.timelapseName), SAVE_TIMELAPSE);
        });
        saveTimelapse.setVisibility(View.GONE);
        label(historyBody, "Graphs of progress, layers, temperatures and fans for each print this app has watched.", 13, MUTED, false);
        button(historyBody, "Print recordings…", () -> startActivity(new Intent(this, RecordingsActivity.class)));
    }
    /** Maintenance is collapsed by default; its header says what a tap does and speaks its state. Emergency stop is never inside the body. */
    private void toggleMaintenance() { setMaintenanceOpen(!maintenanceOpen); }
    public void setMaintenanceOpen(boolean open) {
        maintenanceOpen = open;
        maintenanceBody.setVisibility(open && maintenanceToggle.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        maintenanceToggle.setText(open ? "Hide maintenance ▴" : "Show maintenance ▾");
        maintenanceToggle.setContentDescription(open ? "Hide maintenance options" : "Show maintenance options");
        A11y.expandable(maintenanceToggle, open);
    }
    /** Spaces the visible buttons of a quick-action row: a gap only between buttons that sit side by side. */
    private void spaceActions(LinearLayout row) {
        boolean beside = row.getOrientation() == LinearLayout.HORIZONTAL, first = true;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i); if (child.getVisibility() != View.VISIBLE) continue;
            ((LinearLayout.LayoutParams) child.getLayoutParams()).leftMargin = beside && !first ? dp(8) : 0;
            first = false;
        }
    }
    /** Expands or collapses the storage, history and recordings section; the header says what a tap does and speaks its state. */
    private void setHistoryOpen(boolean open) {
        historyOpen = open; historyBody.setVisibility(open ? View.VISIBLE : View.GONE);
        // The card's title already names what is inside; the state is spoken by A11y.expandable.
        historyToggle.setText(open ? "Hide ▴" : "Show history, storage and recordings ▾");
        historyToggle.setContentDescription("Print history, storage and recordings");
        A11y.expandable(historyToggle, open);
    }

    /** Saves the phone copy of the selected G-code where the user picks. */
    private void saveSelected() {
        if (printer == null || printer.selectedReport == null || printer.fileBusy()) return;
        pendingExportHash = printer.selectedReport.sha256;
        String title = printer.selectedName.substring(printer.selectedName.lastIndexOf('/') + 1);
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, title);
        startActivityForResult(save, SAVE_GCODE);
    }

    /** "Download and save": once the download is in, open the save picker; give up when the download ends otherwise. */
    private void continueSaveAfterDownload() {
        if (saveAfterDownload == null || printer == null) return;
        if (printer.selectedFile != null && saveAfterDownload.equals(printer.selectedName) && !printer.importing && printer.selectedReport != null) {
            saveAfterDownload = null; saveGiveUpAt = 0; saveSelected();
        } else if (!printer.downloading() && !printer.importing) {
            // The finished download is handed over a moment after the transfer ends; give up only once that has passed.
            long now = System.currentTimeMillis();
            if (saveGiveUpAt == 0) saveGiveUpAt = now + 3000; else if (now > saveGiveUpAt) { saveAfterDownload = null; saveGiveUpAt = 0; }
        } else saveGiveUpAt = 0;
    }

    /** One download button per history entry whose timelapse video is ready, newest first. Rebuilt when history changes. */
    private void renderTimelapses() {
        JSONObject history = printer == null ? null : printer.history;
        if (history == renderedHistory) return;
        renderedHistory = history; timelapseList.removeAllViews();
        for (JSONObject row : FeatureData.timelapses(history)) {
            String name = StatusPresentation.clean(row.optString("task_name", "Print")).replaceFirst("(?i)\\.gcode$", "");
            String url = row.optString("time_lapse_video_url");
            button(timelapseList, "Download timelapse: " + name + FeatureData.videoSize(row), () -> { if (printer != null) printer.downloadTimelapse(url, row.optString("task_name")); });
        }
    }
    private Spinner spinner(LinearLayout parent, String[] items) {
        Spinner view = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int position, View convert, android.view.ViewGroup group) { TextView text = (TextView) super.getView(position, convert, group); text.setSingleLine(false); text.setMaxLines(Integer.MAX_VALUE); return text; }
            @Override public View getDropDownView(int position, View convert, android.view.ViewGroup group) { TextView text = (TextView) super.getDropDownView(position, convert, group); text.setSingleLine(false); text.setMaxLines(Integer.MAX_VALUE); return text; }
        }; adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); view.setAdapter(adapter); view.setBackgroundTintList(tint(TEAL)); view.setMinimumHeight(dp(52)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); A11y.nameFromCaption(view, parent); return view;
    }
    private LinearLayout dialogBody() { LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(8), dp(20), dp(8)); return body; }
    private void fileActions(JSONObject file, String storage) {
        String name = file.optString("filename");
        try { Cc2Codec.filename(name); } catch (Exception error) { message("Only .gcode files can be started or deleted here."); return; }
        LinearLayout body = dialogBody();
        fileThumbnail = new ImageView(this); fileThumbnail.setAdjustViewBounds(true); fileThumbnail.setContentDescription("Preview image of this print file from the printer"); fileThumbnail.setVisibility(View.GONE); body.addView(fileThumbnail, new LinearLayout.LayoutParams(-1, dp(180)));
        fileThumbnailKey = storage + "/" + name; if (printer != null) printer.thumbnail(storage, name); showThumbnail();
        label(body, FeatureData.file(file), 14, INK, false);
        ScrollView detailScroll = new ScrollView(this); detailScroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Printer file").setView(detailScroll).setNegativeButton("Close", null).create();
        if (printer != null && printer.pinProbe()) { label(body, "Read-only PIN probe: print start and deletion are disabled.", 14, MUTED, false); dialog.show(); return; }
        // Starting a print comes first; it and Delete need what PrinterService checks (fresh status, an idle printer, a fresh file list), so say when they are not available.
        boolean usable = printer != null && printer.liveFresh() && Cc2Codec.idle(printer.liveStatus()) && printer.filesFresh();
        Button setup = rowButton(row(body), "Print setup…", () -> { dialog.dismiss(); startDialog(file, storage); }, true); setup.setEnabled(usable);
        if (!usable) label(body, printer == null || !printer.liveFresh() ? "Printing and deleting need a fresh printer status." : !Cc2Codec.idle(printer.liveStatus()) ? "The printer is busy. Printing and deleting are available when it is idle." : "The file list is out of date. Refresh files to print or delete.", 13, MUTED, false);
        Button save = button(body, "Save to phone…", () -> { dialog.dismiss(); if (printer != null) { saveAfterDownload = name; printer.download(storage, name); } });
        Button download = button(body, "Keep in this app", () -> { dialog.dismiss(); if (printer != null) printer.download(storage, name); });
        save.setEnabled(printer != null && !printer.fileBusy()); download.setEnabled(printer != null && !printer.fileBusy());
        Button delete = button(body, "Delete file…", () -> { dialog.dismiss(); new AlertDialog.Builder(this).setTitle("Delete " + StatusPresentation.clean(name) + "?").setMessage("This permanently removes the selected file from the printer. The printer must be idle.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, which) -> { if (printer != null) printer.delete(storage, name); }).show(); });
        delete.setEnabled(usable); delete.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {MUTED, ERROR}));
        dialog.show();
    }
    private void startDialog(JSONObject file, String storage) {
        if (printer != null && printer.pinProbe()) { message("Read-only PIN probe: print start is disabled."); return; }
        if (printer != null && printer.fileBusy()) { message("A file is being sent or received. Wait for it to finish before starting a print."); return; }
        if (printer == null || !printer.liveFresh() || !Cc2Codec.idle(printer.liveStatus()) || !printer.filesFresh()) { message("Refresh status and files, then wait for the printer to be idle."); return; }
        String name = file.optString("filename");
        LinearLayout body = dialogBody(); label(body, StatusPresentation.clean(name), 16, INK, true);
        label(body, "Check the build plate, material and sliced printer profile before starting.", 13, MUTED, false);
        subheading(body, "1 · Checks");
        CheckBox leveling = checkbox(body, "Run printer / bed check", true), force = checkbox(body, "Force bed leveling", false), timelapse = checkbox(body, "Record a timelapse on the printer", false);
        force.setOnCheckedChangeListener((view, checked) -> { if (checked) leveling.setChecked(true); });
        leveling.setOnCheckedChangeListener((view, checked) -> { if (!checked) force.setChecked(false); });
        subheading(body, "2 · Build plate");
        Spinner plate = spinner(body, new String[] {"Build plate A", "Build plate B"});
        subheading(body, "3 · Filament");
        label(body, "Filaments used by this file", 13, MUTED, false);
        Spinner toolCount = spinner(body, new String[] {"1 filament", "2 filaments", "3 filaments", "4 filaments", "5 filaments", "6 filaments", "7 filaments", "8 filaments"});
        // Loaded trays of connected CANVAS units (what print start accepts), each with its material and colour in words.
        List<TrayPlan.Tray> trays = printer.canvasFresh() ? TrayPlan.trays(printer.canvas, true) : new ArrayList<>();
        List<String> choices = new ArrayList<>(), dots = new ArrayList<>(); choices.add("Printer / G-code default"); dots.add(null);
        for (TrayPlan.Tray tray : trays) {
            choices.add(tray.material() + "\n" + tray.where()); dots.add(tray.colour);
        }
        // What the file was sliced for, per tool: a plan saved when slicing on this phone, the printer's file details, or the
        // slicer notes of the same file inspected on this phone.
        TrayPlan plan = TrayPlan.parse(getSharedPreferences(SliceActivity.TRAY_PLANS, MODE_PRIVATE).getString(GcodeLibrary.safeName(name), null));
        boolean sameFile = printer.selectedReport != null && GcodeLibrary.safeName(printer.selectedName) != null && GcodeLibrary.safeName(printer.selectedName).equals(GcodeLibrary.safeName(name));
        FilamentMatch.Need[] needs = FilamentMatch.merge(TrayPlan.MAX_TOOLS, FilamentMatch.fromPlan(plan), FilamentMatch.fromColorMap(file.opt("color_map")),
            sameFile ? FilamentMatch.fromComments(printer.selectedReport.materials, TrayPlan.MAX_TOOLS) : new ArrayList<>());
        if (file.has("color_map")) Diagnostics.note(Diagnostics.TRAYS, "file details carry color_map (" + (file.opt("color_map") instanceof JSONArray ? "list" : file.opt("color_map") == null ? "null" : file.opt("color_map").getClass().getSimpleName()) + ")");
        Spinner[] maps = new Spinner[8]; LinearLayout[] rows = new LinearLayout[8]; TextView[] verdicts = new TextView[8];
        for (int t = 0; t < 8; t++) {
            rows[t] = new LinearLayout(this); rows[t].setOrientation(LinearLayout.VERTICAL); rows[t].setPadding(0, dp(10), 0, 0); body.addView(rows[t]);
            FilamentMatch.Need need = needs[t];
            TextView heading = label(rows[t], "Filament " + (t + 1) + " (T" + t + ")", 14, INK, true);
            String wants = need == null ? "" : need.describe();
            TextView wanted = label(rows[t], wants.isEmpty() ? "The file does not say which material." : "Sliced for " + wants + " (from " + need.source + ")", 13, MUTED, false);
            if (need != null && need.colour != null) { wanted.setCompoundDrawablesRelativeWithIntrinsicBounds(swatch(need.colour), null, null, null); wanted.setCompoundDrawablePadding(dp(8)); }
            maps[t] = spinner(rows[t], new String[] {""});
            maps[t].setAdapter(new WorkshopUi.DottedAdapter(this, INK, MUTED, choices, dots));
            A11y.name(maps[t], "Tray for filament " + (t + 1));
            verdicts[t] = A11y.polite(label(rows[t], "", 13, MUTED, false));
            heading.setContentDescription("Filament " + (t + 1) + ", tool T" + t);
        }
        Runnable judge = () -> {
            int count = toolCount.getSelectedItemPosition() + 1;
            for (int t = 0; t < 8; t++) {
                boolean shown = t < count && choices.size() > 1;
                rows[t].setVisibility(shown ? View.VISIBLE : View.GONE);
                if (!shown) continue;
                int selected = maps[t].getSelectedItemPosition();
                TrayPlan.Tray tray = selected > 0 ? trays.get(selected - 1) : null;
                List<Integer> shared = new ArrayList<>();
                for (int o = 0; o < count; o++) if (o != t && selected > 0 && maps[o].getSelectedItemPosition() == selected) shared.add(o + 1);
                FilamentMatch.Verdict verdict = tray == null ? FilamentMatch.unset(needs[t], trays) : FilamentMatch.check(needs[t], tray, shared);
                verdicts[t].setText((verdict.level == FilamentMatch.Level.OK ? "✓ " : verdict.level == FilamentMatch.Level.WRONG ? "✕ " : "! ") + verdict.text);
                verdicts[t].setTextColor(verdict.level == FilamentMatch.Level.OK ? TEAL : verdict.level == FilamentMatch.Level.WRONG ? ERROR : AMBER);
            }
        };
        AdapterView.OnItemSelectedListener rejudge = new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { judge.run(); }
        };
        toolCount.setOnItemSelectedListener(rejudge);
        for (Spinner map : maps) map.setOnItemSelectedListener(rejudge);
        TextView source = label(body, "", 13, TEAL, false);
        Runnable suggestTrays = () -> {
            int[] picks = FilamentMatch.suggest(java.util.Arrays.copyOf(needs, toolCount.getSelectedItemPosition() + 1), trays);
            int filled = 0;
            for (int t = 0; t < picks.length; t++) if (picks[t] >= 0) { maps[t].setSelection(picks[t] + 1); filled++; }
            source.setText(filled == 0 ? "No loaded tray matches the materials the file was sliced for. Choose the trays yourself."
                : "Suggested " + filled + " tray(s) by material, then colour. Check each before starting.");
            judge.run();
        };
        if (plan != null) {
            toolCount.setSelection(plan.count - 1);
            int matched = 0;
            for (TrayPlan.Tool tool : plan.tools)
                for (int i = 0; i < trays.size(); i++) if (trays.get(i).same(tool.canvasId, tool.trayId)) { maps[tool.t].setSelection(i + 1); matched++; }
            StringBuilder reported = new StringBuilder();
            for (TrayPlan.Tray tray : trays) reported.append(reported.length() > 0 ? ", " : "").append(tray.canvasId).append("/").append(tray.trayId);
            Diagnostics.note(Diagnostics.TRAYS, StatusPresentation.clean(name) + ": plan " + plan.toJson() + " · loaded trays (canvas/tray) [" + reported + "]"
                + (printer.canvasFresh() ? "" : " · tray status not fresh") + " · prefilled " + matched + " of " + plan.tools.size());
            source.setText(plan.tools.isEmpty() ? "Filament count from slicing this file on the phone (" + plan.count + ")."
                : matched == plan.tools.size() ? "Filament count and trays from slicing this file on the phone."
                : "Filament count from slicing this file on the phone; " + (plan.tools.size() - matched) + " planned tray(s) are not loaded now. Choose them, or use Suggest trays.");
            if (plan.tools.isEmpty() && trays.size() > 0) main.post(suggestTrays);
        } else {
            // No stored plan: the file's own T selections (when this is the file inspected on the phone) are a starting point, not proof.
            int guess = TrayPlan.defaultToolCount(null, sameFile ? printer.selectedReport.tools : null);
            int known = 0; for (FilamentMatch.Need need : needs) if (need != null) known = Math.max(known, need.t + 1);
            if (known > 0) { toolCount.setSelection(Math.max(known, guess) - 1); }
            else if (sameFile && !printer.selectedReport.tools.isEmpty()) { toolCount.setSelection(guess - 1); source.setText("Filament count taken from the file's own T commands (" + guess + "). Check it against your slice."); }
            if (known > 0 && trays.size() > 0) main.post(suggestTrays);
        }
        if (choices.size() > 1) {
            LinearLayout tools = row(body);
            rowButton(tools, "Suggest trays", suggestTrays, false);
            rowButton(tools, "Clear trays", () -> { for (Spinner map : maps) map.setSelection(0); source.setText(""); judge.run(); }, false);
        }
        label(body, choices.size() > 1 ? "Leave every filament at Printer / G-code default, or choose a loaded tray for every filament." : printer.canvasFresh()
            ? "No loaded CANVAS trays reported: the printer's own G-code mapping is used." : "Tray status is not fresh: refresh the printer to choose trays. Until then the printer's own mapping is used.", 13, MUTED, false);
        label(body, "A timelapse is recorded on the printer; download it later from the Files tab (local connection).", 12, MUTED, false);
        main.post(judge);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        AlertDialog setup = new AlertDialog.Builder(this).setTitle("Print setup").setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Next: review…", null).create();
        setup.setOnShowListener(d -> setup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JSONArray mapping = new JSONArray(); int count = toolCount.getSelectedItemPosition() + 1, explicit = 0, wrong = 0, check = 0;
            StringBuilder lines = new StringBuilder();
            try {
                for (int t = 0; t < count; t++) {
                    int selected = maps[t].getSelectedItemPosition();
                    if (selected > 0) { TrayPlan.Tray tray = trays.get(selected - 1); mapping.put(new JSONObject().put("t", t).put("canvas_id", tray.canvasId).put("tray_id", tray.trayId)); explicit++; }
                }
                if (explicit != 0 && explicit != count) { new AlertDialog.Builder(this).setTitle("Choose every filament").setMessage("Choose a tray for every filament, or set them all to Printer / G-code default.").setPositiveButton("OK", null).show(); return; }
                for (int t = 0; t < count && explicit > 0; t++) {
                    int selected = maps[t].getSelectedItemPosition(); TrayPlan.Tray tray = trays.get(selected - 1);
                    List<Integer> shared = new ArrayList<>();
                    for (int o = 0; o < count; o++) if (o != t && maps[o].getSelectedItemPosition() == selected) shared.add(o + 1);
                    FilamentMatch.Verdict verdict = FilamentMatch.check(needs[t], tray, shared);
                    if (verdict.level == FilamentMatch.Level.WRONG) wrong++; else if (verdict.level == FilamentMatch.Level.CHECK) check++;
                    String wants = needs[t] == null ? "" : needs[t].describe();
                    lines.append("\nFilament ").append(t + 1).append(wants.isEmpty() ? "" : " (" + wants + ")").append(" → ").append(choices.get(selected).replace("\n", ", "))
                        .append(verdict.level == FilamentMatch.Level.OK ? "" : (verdict.level == FilamentMatch.Level.WRONG ? "\n   ✕ " : "\n   ! ") + verdict.text);
                }
            } catch (Exception error) { message("Could not prepare tool mappings. Refresh trays."); return; }
            setup.dismiss();
            String text = StatusPresentation.clean(name) + "\nStorage: " + (storage.equals("local") ? "Internal" : "USB") + "\nBuild plate " + (plate.getSelectedItemPosition() == 0 ? "A" : "B")
                + " · Run printer / bed check " + (leveling.isChecked() ? "on" : "off") + " · Force bed leveling " + (force.isChecked() ? "on" : "off") + "\nTimelapse " + (timelapse.isChecked() ? "on" : "off")
                + "\n\n" + count + " filament(s): " + (mapping.length() == 0 ? "the printer's own mapping" : "from these trays:" + lines)
                + (wrong > 0 ? "\n\n" + wrong + " filament(s) are a different material from what the file was sliced for. Printing with the wrong material can fail or clog the nozzle." : "")
                + "\n\nStarting moves and heats the printer. Confirm the plate is clear and the filament is correct.";
            int wrongCount = wrong, checkCount = check;
            new AlertDialog.Builder(this).setTitle(wrong > 0 ? "Materials don't match" : "Start this print?").setMessage(text).setNegativeButton(wrong > 0 ? "Go back" : "Cancel", (b2, w2) -> { if (wrongCount > 0) setup.show(); })
                .setPositiveButton(wrong > 0 ? "Start anyway" : "Start print", (confirm, which) -> {
                    if (plan != null || mapping.length() > 0) Diagnostics.note(Diagnostics.TRAYS, StatusPresentation.clean(name) + ": start with " + count + " filament(s), mapping " + mapping
                        + (wrongCount + checkCount > 0 ? " · " + wrongCount + " material mismatch, " + checkCount + " to check" : ""));
                    if (printer != null) printer.start(storage, name, leveling.isChecked(), force.isChecked(), timelapse.isChecked(), plate.getSelectedItemPosition() == 0 ? "A" : "B", mapping);
                }).show();
        })); setup.show();
    }
    /** A small round colour swatch for a "sliced for" line. */
    private android.graphics.drawable.Drawable swatch(String colour) {
        GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL); dot.setColor(Color.parseColor(colour)); dot.setStroke(Math.max(1, dp(1)), MUTED); dot.setSize(dp(14), dp(14));
        return dot;
    }
    /** Shows the open file dialog's thumbnail once the printer has sent it (base64 PNG, with or without a data: prefix). */
    private void showThumbnail() {
        if (fileThumbnail == null || printer == null || fileThumbnail.getVisibility() == View.VISIBLE) return;
        String data = printer.thumbnails.get(fileThumbnailKey); if (data == null || data.isEmpty()) return;
        try {
            byte[] bytes = android.util.Base64.decode(data.contains(",") ? data.substring(data.indexOf(',') + 1) : data, android.util.Base64.DEFAULT);
            Bitmap image = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (image != null) { fileThumbnail.setImageBitmap(image); fileThumbnail.setVisibility(View.VISIBLE); }
        } catch (IllegalArgumentException ignored) { }
    }
    private void temperatureDialog() {
        LinearLayout body = dialogBody(); label(body, "While idle: nozzle 0–300°C, bed 0–100°C. Zero turns that heater off. Use the correct targets for your filament and build plate. This changes the heaters now. It does not change sliced files: set those in the filament's Settings… on the Slice screen.", 14, INK, false);
        EditText nozzle = input(body, "Nozzle target °C", false), bed = input(body, "Bed target °C", false); nozzle.setInputType(InputType.TYPE_CLASS_NUMBER); bed.setInputType(InputType.TYPE_CLASS_NUMBER);
        JSONObject n = snapshot.optJSONObject("extruder"), b = snapshot.optJSONObject("heater_bed"); nozzle.setText(String.valueOf(n == null ? 0 : n.optInt("target"))); bed.setText(String.valueOf(b == null ? 0 : b.optInt("target")));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Heater temperatures now").setView(body).setNegativeButton("Cancel", null).setPositiveButton("Set heaters", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { try { int nt = Integer.parseInt(nozzle.getText().toString()), bt = Integer.parseInt(bed.getText().toString()); Cc2Codec.temperatureRequest(0, nt, bt); if (printer != null) printer.temperatures(nt, bt); dialog.dismiss(); } catch (Exception error) { nozzle.setError("Nozzle 0–300°C; bed 0–100°C"); } })); dialog.show();
    }
    private void fanDialog() {
        LinearLayout body = dialogBody(); Spinner kind = spinner(body, new String[] {"Part cooling", "Auxiliary", "Chamber"}); A11y.name(kind, "Fan");
        TextView value = label(body, "Fan: 0%", 15, INK, false); SeekBar amount = new SeekBar(this); amount.setMax(100); body.addView(amount); A11y.labelFor(value, amount);
        amount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar bar, int progress, boolean user) { value.setText("Fan: " + progress + "%"); } public void onStartTrackingTouch(SeekBar bar) { } public void onStopTrackingTouch(SeekBar bar) { } });
        new AlertDialog.Builder(this).setTitle("Fan setting").setView(body).setNegativeButton("Cancel", null).setPositiveButton("Set fan", (dialog, which) -> { if (printer != null) printer.fan(new String[] {"fan", "aux_fan", "box_fan"}[kind.getSelectedItemPosition()], amount.getProgress()); }).show();
    }
    private void speedDialog() { new AlertDialog.Builder(this).setTitle("Print speed mode").setItems(new String[] {"Silent", "Balanced", "Sport", "Ludicrous"}, (dialog, which) -> {
        new AlertDialog.Builder(this).setTitle("Change print speed?").setMessage("This changes the current print's speed mode. Filament changes may reset the mode on some firmware.").setNegativeButton("Cancel", null).setPositiveButton("Apply", (d, w) -> { if (printer != null) printer.speed(which); }).show();
    }).setNegativeButton("Cancel", null).show(); }
    private void buildCamera() {
        // Order: the cloud card and the local card trade places in renderCamera (local first when the printer is on this network).
        LinearLayout cloudCard = card("Cloud camera"); cameraCloudCard = cloudCard;
        cloudCameraHint = label(cloudCard, "", 13, MUTED, false);
        cameraFix = button(cloudCard, "Open Settings to sign in", () -> { setSettingsMode(true); selectPage(3); }); cameraFix.setVisibility(View.GONE);
        // Opening it asks for the one-time cloud-control agreement first, like every other cloud action.
        cloudCamera = button(cloudCard, "Watch through the Elegoo cloud", () -> {
            if (printer == null || printer.cloudSerial.isEmpty()) return;
            String serialNumber = printer.cloudSerial, printerName = printer.cloudName;
            cloudGate(true, () -> { stopCamera(); startActivity(new Intent(this, CloudCameraActivity.class).putExtra(CloudCameraActivity.EXTRA_SERIAL, serialNumber).putExtra(CloudCameraActivity.EXTRA_NAME, printerName)); });
        });
        cameraLocalCard = card("Local camera");
        localToggle = button(cameraLocalCard, "", () -> { localCameraOpen = !localCameraOpen; render(); });
        localCameraBody = new LinearLayout(this); localCameraBody.setOrientation(LinearLayout.VERTICAL); cameraLocalCard.addView(localCameraBody);
        LinearLayout card = localCameraBody;
        cameraInfo = label(card, "The printer's stream on port 8080, or through your home VPN.", 14, MUTED, false);
        cameraStart = button(card, "Start camera", this::toggleCamera);
        cameraImage = new ImageView(this); cameraImage.setContentDescription("Live printer camera"); cameraImage.setScaleType(ImageView.ScaleType.FIT_CENTER); cameraImage.setBackgroundColor(Color.BLACK); card.addView(cameraImage, new LinearLayout.LayoutParams(-1, dp(240)));
        cameraSnapshot = button(card, "Save snapshot to phone…", () -> {
            if (lastFrame == null) return; pendingSnapshot = lastFrame.copy(Bitmap.Config.ARGB_8888, false);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/jpeg").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "CC2-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".jpg"); startActivityForResult(intent, PICK_SNAPSHOT);
        });
        button(card, "Larger camera view", () -> { cameraImage.getLayoutParams().height = cameraImage.getLayoutParams().height == dp(240) ? dp(400) : dp(240); cameraImage.requestLayout(); });
        // Setup details sit behind one collapsed header; the header speaks its state.
        Button settingsToggle = button(card, "", () -> { });
        LinearLayout settings = new LinearLayout(this); settings.setOrientation(LinearLayout.VERTICAL); settings.setVisibility(View.GONE);
        settingsToggle.setContentDescription("Camera settings");
        settingsToggle.setOnClickListener(view -> {
            boolean open = settings.getVisibility() != View.VISIBLE;
            settings.setVisibility(open ? View.VISIBLE : View.GONE);
            settingsToggle.setText(open ? "Hide camera settings ▴" : "Camera settings ▾"); A11y.expandable(settingsToggle, open);
        });
        settingsToggle.setText("Camera settings ▾"); A11y.expandable(settingsToggle, false);
        card.addView(settings);
        cameraAddress = input(settings, "Camera URL on this printer", false); cameraAddress.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        cameraQuery = button(settings, "Get camera address from printer", () -> { if (printer != null) printer.camera(); });
        label(settings, "Camera stops when you leave this tab or the app goes to the background. Monitoring continues. Redirects and camera addresses on other devices are blocked.", 13, MUTED, false);
        LinearLayout lineUp = card("Camera in the 3D view");
        label(lineUp, "Shows the camera picture in the 3D toolpath. Line it up once against the real bed, print or no print.", 13, MUTED, false);
        button(lineUp, "Line up the camera in 3D…", () -> {
            if (printer == null) return;
            Runnable open = () -> { stopCamera(); startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true).putExtra(GcodeViewerActivity.EXTRA_ALIGN, true)); };
            cloudGate(!printer.ready() && printer.usingCloud(), open);
        });
    }
    private void toggleCamera() {
        if (cameraPlayer != null) { stopCamera(); return; }
        try {
            String address = host.getText().toString().trim(), url = FeatureData.cameraUrl(address, cameraAddress.getText().toString().trim());
            NetworkRoute route = NetworkRoute.select(this, remoteMode()); cameraInfo.setText("Opening camera…"); cameraRoute = route;
            cameraRouteWatch = route.watch(() -> main.post(() -> { if (cameraRoute == route && cameraPlayer != null) { stopCamera(); cameraInfo.setText("Home VPN route changed. Enable the VPN and restart the camera."); } }));
            cameraPlayer = new MjpegPlayer(url, route.http(), new MjpegPlayer.Listener() {
                public void frame(Bitmap image) { if (isDestroyed()) return; lastFrame = image; cameraImage.setVisibility(View.VISIBLE); cameraImage.setImageBitmap(image); cameraInfo.setText("Live camera · up to 5 frames/second"); cameraSnapshot.setEnabled(true); }
                public void error(String text) { stopCamera(); cameraInfo.setText(text); }
            }); cameraStart.setText("Stop camera");
        } catch (Exception error) { stopCamera(); cameraInfo.setText(remoteMode() ? "Enable your home VPN and use the camera URL on the home printer IP. The Pi/subnet route must allow its camera port." : "Enter a camera URL on the selected printer's IP and connect the phone to local Wi-Fi."); }
    }
    private void stopCamera() { AutoCloseable watcher = cameraRouteWatch; cameraRouteWatch = null; cameraRoute = null; if (watcher != null) try { watcher.close(); } catch (Exception ignored) { } MjpegPlayer player = cameraPlayer; cameraPlayer = null; if (player != null) player.close(); if (cameraStart != null) cameraStart.setText("Start camera"); }
    /** On the Files tab, ask for the printer's file list when there is none (or it is out of date): a read, at most once a minute. */
    private long filesAutoAt;
    private void autoLoadFiles() {
        if (page != 1 || printer == null || !printer.canQuery() || printer.busy(Cc2Codec.FILES) || printer.filesFresh()) return;
        long now = System.currentTimeMillis(); if (now - filesAutoAt < 60_000) return;
        filesAutoAt = now; printer.browse(storagePicker.getSelectedItemPosition() == 0 ? "local" : "u-disk", 0);
    }
    private void renderFeatures(boolean query, boolean ready) {
        if (query) main.post(this::autoLoadFiles);
        listFiles.setEnabled(query && !printer.busy(Cc2Codec.FILES)); storagePicker.setEnabled(!query || !printer.busy(Cc2Codec.FILES));
        loadHistory.setEnabled(query && !printer.busy(Cc2Codec.HISTORY)); loadDisk.setEnabled(query && !printer.busy(Cc2Codec.DISK)); cameraQuery.setEnabled(ready && !printer.busy(Cc2Codec.CAMERA));
        JSONObject files = printer == null ? new JSONObject() : printer.filePage;
        JSONArray rows = files.optJSONArray("file_list"); int count = rows == null ? 0 : rows.length(), offset = printer == null ? 0 : printer.fileOffset;
        previousFiles.setEnabled(query && !printer.busy(Cc2Codec.FILES) && offset > 0);
        nextFiles.setEnabled(query && !printer.busy(Cc2Codec.FILES) && count >= 50 && (files.optInt("total", -1) < 0 || offset + count < files.optInt("total")));
        boolean paged = offset > 0 || count >= 50 && (files.optInt("total", -1) < 0 || offset + count < files.optInt("total"));
        pageRow.setVisibility(paged ? View.VISIBLE : View.GONE); // only when there is another page
        String filesWhy = query ? null : printer == null ? "Starting…" : block == ControlState.Block.CLOUD_AGREEMENT ? "Browsing printer files through the Elegoo cloud needs cloud control turned on."
            : block == ControlState.Block.DISCONNECTED ? "Not connected. Connect on your network, or sign in with Elegoo, to browse the printer's files."
            : block == ControlState.Block.CONNECTING ? "Connecting on your local network…" : "Files are unavailable until the printer is reachable (see Controls on the Monitor tab).";
        fileInfo.setText(filesWhy != null ? filesWhy : FeatureData.fileSummary(printer.fileMessage, printer.storage, rows != null, count, offset, printer.filesFresh(), printer.busy(Cc2Codec.FILES)));
        boolean filesFix1 = !query && (block == ControlState.Block.CLOUD_AGREEMENT || block == ControlState.Block.DISCONNECTED);
        filesFix.setText(block == ControlState.Block.CLOUD_AGREEMENT ? "Turn on cloud control…" : "Open Settings"); ((View) filesFix.getParent()).setVisibility(filesFix1 ? View.VISIBLE : View.GONE);
        fileHelp.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        listFiles.setVisibility(query || filesWhy == null ? View.VISIBLE : View.GONE);
        if (files != renderedFiles) {
            renderedFiles = files; fileRows.removeAllViews();
            if (rows != null) for (int i = 0; i < Math.min(rows.length(), 50); i++) {
                JSONObject row = rows.optJSONObject(i); if (row == null) continue; String storage = printer.storage;
                fileRow(StatusPresentation.clean(row.optString("filename", "Unnamed entry")), FeatureData.size(row.optLong("size", -1)) + (row.has("layer") ? " · " + row.optInt("layer") + " layers" : ""), i > 0, () -> fileActions(row, storage));
            }
        }
        diskInfo.setText(printer == null || printer.disk.length() == 0 ? "Storage usage not loaded." : FeatureData.disk(printer.disk));
        JSONObject historyNow = printer == null ? null : printer.history;
        java.util.List<String[]> entries = historyNow == null ? new ArrayList<>() : FeatureData.historyEntries(historyNow);
        historyInfo.setText(printer == null ? "History not loaded." : historyNow.length() == 0 ? printer.historyMessage : entries.isEmpty() ? FeatureData.history(historyNow) : "");
        historyInfo.setVisibility(historyInfo.getText().length() == 0 ? View.GONE : View.VISIBLE);
        if (historyNow != renderedHistoryList) {
            renderedHistoryList = historyNow; historyList.removeAllViews(); againRows.clear();
            java.util.List<JSONObject> historyRows = historyNow == null ? new ArrayList<>() : FeatureData.historyRows(historyNow);
            for (int i = 0; i < entries.size() && i < historyRows.size(); i++) {
                JSONObject historyRow = historyRows.get(i);
                listRow(historyList, entries.get(i)[0], entries.get(i)[1], i > 0, () -> historyDetail(historyRow));
                addAgain(historyRow);
            }
        }
        updateAgain(query);
        boolean noStorage = !query && (printer == null || printer.disk.length() == 0), noHistory = !query && (printer == null || printer.history.length() == 0);
        if (noStorage) diskInfo.setText("Storage and print history load once the printer is reachable.");
        historyButtons.setVisibility(query ? View.VISIBLE : View.GONE); if (noHistory) historyInfo.setVisibility(View.GONE);
        renderTimelapses();
        continueSaveAfterDownload();
        continuePrintSetup();
        for (int i = 0; i < timelapseList.getChildCount(); i++) timelapseList.getChildAt(i).setEnabled(ready && !printer.pinProbe() && !printer.fileBusy());
        timelapseHint.setText(!ready ? "Timelapse downloads need the local connection (the printer's HTTP port 80)." : printer.pinProbe() ? "Timelapse downloads are disabled in read-only PIN probe mode." : "");
        timelapseHint.setVisibility(timelapseList.getChildCount() > 0 && timelapseHint.getText().length() > 0 ? View.VISIBLE : View.GONE);
        saveTimelapse.setVisibility(printer != null && printer.timelapseFile != null ? View.VISIBLE : View.GONE);
        String selectedHost = host.getText().toString().trim();
        if (!cameraHost.equals(selectedHost)) { stopCamera(); cameraHost = selectedHost; cameraReported = ""; cameraAddress.setText(selectedHost.isEmpty() ? "" : "http://" + selectedHost + ":8080/?action=stream"); lastFrame = null; cameraImage.setImageDrawable(null); }
        if (ready && !printer.cameraUrl.isEmpty() && cameraPlayer == null && !printer.cameraUrl.equals(cameraReported)) { cameraAddress.setText(printer.cameraUrl); cameraReported = printer.cameraUrl; }
        cameraSnapshot.setEnabled(lastFrame != null);
    }
    /** Fills a watch button with the accent colour, or returns it to the tonal style. */
    private void emphasise(Button button, boolean on) {
        if (!on) { styleButton(button); return; }
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(12));
        shape.setColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {BUTTON, TEAL}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape, null));
        button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {MUTED, dark ? 0xff00201c : Color.WHITE}));
    }
    /** Camera tab: the one way that works now is filled and first; the local card leads when the printer is on this network. */
    private void renderCamera(boolean ready, boolean cloud) {
        // The picture area shows only once there is something to show: no empty black box before the camera starts.
        cameraImage.setVisibility(cameraPlayer != null || lastFrame != null ? View.VISIBLE : View.GONE);
        boolean cloudOk = settings.getBoolean("cloudControlUnderstood", false), signedIn = printer != null && printer.cloudSignedIn && !printer.cloudSerial.isEmpty();
        boolean online = signedIn && printer.cloudOnline == 1, local = ready || cameraPlayer != null;
        emphasise(cameraStart, local);
        emphasise(cloudCamera, !local);
        cloudCamera.setEnabled(online);
        cloudCameraHint.setText(!signedIn ? "Sign in with Elegoo in Settings. Works when LAN Only is off on the printer."
            : !online ? "The Elegoo cloud shows the printer offline right now."
            : "Works from anywhere." + (cloudOk ? "" : " The first use asks you to turn on cloud control."));
        localCameraBody.setVisibility(local || localCameraOpen ? View.VISIBLE : View.GONE);
        localToggle.setVisibility(local ? View.GONE : View.VISIBLE);
        localToggle.setText(localCameraOpen ? "Hide local camera" : "Use the printer's own camera on this network…");
        cameraFix.setVisibility(!ready && !signedIn ? View.VISIBLE : View.GONE);
        if (ready != cameraLocalFirst) { cameraLocalFirst = ready; pages[2].removeView(cameraLocalCard); pages[2].addView(cameraLocalCard, ready ? 0 : 1); }
    }
    private void render() {
        if (connect == null || isDestroyed()) return;
        if (probeButton != null) {
            boolean probing = printer != null && printer.probing;
            probeButton.setText(probing ? "Stop probe" : "Probe printer (read-only)…");
            String probeText = printer == null ? "" : printer.probeText;
            if (!probeText.contentEquals(probeResult.getText())) probeResult.setText(probeText);
            ((View) probeResult.getParent()).setVisibility(probeText.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (cameraPlayer != null && cameraRoute != null && !cameraRoute.available()) { stopCamera(); cameraInfo.setText("Home VPN is unavailable. Enable it and restart the camera."); }
        boolean ready = printer != null && printer.ready(), fresh = ready && printer.fresh(), writable = fresh && !printer.pinProbe(), busy = printer != null && printer.uploading(), connecting = printer != null && printer.connecting();
        // Local session first; otherwise what the Elegoo cloud last received.
        boolean cloud = !ready && !connecting && printer != null && printer.usingCloud(), cloudFresh = cloud && printer.cloudFresh();
        // Cloud actions share ElegooSlicer's cloud control identity, so they wait for a one-time agreement; monitoring does not.
        boolean cloudOk = settings.getBoolean("cloudControlUnderstood", false);
        if (cloudFresh && !cloudOk && !cloudAsked) { cloudAsked = true; cloudGate(true, this::render); }
        boolean live = fresh || cloudFresh;
        // "Seen" flags for the first-run card: once the phone has connected (locally or through the cloud), the card stays hidden.
        if ((ready || cloudFresh) && !settings.getBoolean("everConnected", false)) settings.edit().putBoolean("everConnected", true).apply();
        boolean firstRun = !settings.getBoolean("everConnected", false) && !settings.getBoolean("everSliced", false);
        getStarted.setVisibility(firstRun ? View.VISIBLE : View.GONE);
        block = ControlState.block(ready, fresh, ready && printer.pinProbe(), connecting, cloud, cloudFresh, cloud && printer.cloudOnline == 0, cloudOk, cloud && printer.cloudCommandBusy());
        boolean canControl = block == ControlState.Block.NONE;
        snapshot = printer == null ? new JSONObject() : ready ? printer.status : cloud ? printer.cloudStatus : new JSONObject();
        connection.setText(printer == null ? "Preparing connection service…" : printer.connection);
        if (ready) chip(summary, printer.pinProbe() ? "Local · read-only" : "Local", TEAL);
        else if (connecting) chip(summary, "Connecting…", AMBER);
        else if (cloudFresh) chip(summary, "Cloud", TEAL);
        else if (cloud && printer.cloudOnline == 0) chip(summary, "Printer offline", MUTED);
        else chip(summary, cloud ? "Waiting for printer" : "Not connected", MUTED);
        if (connecting && !printer.host().equals(host.getText().toString())) { host.setText(printer.host()); access.setText(""); }
        routePicker.setEnabled(!connecting); authPicker.setEnabled(!connecting); host.setEnabled(!connecting); access.setEnabled(!connecting); pairingPin.setEnabled(!connecting); serial.setEnabled(!connecting); remember.setEnabled(!connecting && !pinProbe());
        access.setVisibility(pinProbe() ? View.GONE : View.VISIBLE); lanHint.setVisibility(access.getVisibility()); pairingPin.setVisibility(pinProbe() ? View.VISIBLE : View.GONE); pinProbeHelp.setVisibility(pinProbe() ? View.VISIBLE : View.GONE); remember.setVisibility(pinProbe() ? View.GONE : View.VISIBLE);
        connect.setEnabled(printer != null); connect.setText(connecting ? "Disconnect" : "Connect"); check.setEnabled(!checking);
        boolean resumable = Cc2Codec.canResume(snapshot), noData = snapshot.length() == 0, inJob = Cc2Codec.canStop(snapshot) || Cc2Codec.canResume(snapshot) || Cc2Codec.canPause(snapshot);
        refresh.setEnabled(ready || cloud); refresh.setVisibility(ready || cloud ? View.VISIBLE : View.GONE);
        pause.setEnabled(canControl && Cc2Codec.canPause(snapshot)); resume.setEnabled(canControl && resumable); stop.setEnabled(canControl && Cc2Codec.canStop(snapshot));
        pause.setVisibility(resumable ? View.GONE : View.VISIBLE); resume.setVisibility(resumable ? View.VISIBLE : View.GONE);
        lightOn.setEnabled(canControl); lightOff.setEnabled(canControl);
        discover.setEnabled(!remoteMode() && !connecting && !scanningNow); discover.setText(scanningNow ? "Scanning…" : "Find on Wi-Fi");
        saveProfile.setEnabled(!connecting); chooseProfile.setEnabled(!connecting); removeProfile.setEnabled(!connecting); profileName.setEnabled(!connecting);
        // Only what can work right now is shown; the line above each group says what is missing. Nothing here is enabled in a state PrinterService refuses.
        boolean idleNow = Cc2Codec.idle(snapshot), pausable = Cc2Codec.canPause(snapshot);
        heater.setEnabled(canControl && idleNow); fan.setEnabled(canControl); speed.setEnabled(canControl && pausable);
        heater.setVisibility(idleNow ? View.VISIBLE : View.GONE); speed.setVisibility(pausable ? View.VISIBLE : View.GONE);
        tuningHint.setText(ControlState.tuningNote(snapshot));
        String upkeepReason = ControlState.upkeepReason(snapshot);
        boolean upkeepOk = canControl && upkeepReason.isEmpty();
        for (Button button : new Button[] {loadFilament, unloadFilament, homeAll, jog, autoLevel, vibration, selfCheck}) button.setEnabled(upkeepOk);
        trayFilament.setEnabled(upkeepOk && printer.canvas != null); urgentStop.setEnabled(canControl);
        upkeepButtons.setVisibility(upkeepOk ? View.VISIBLE : View.GONE);
        // The toggle and its body appear only when maintenance can run; the reason and Emergency stop stay visible otherwise.
        maintenanceToggle.setVisibility(upkeepOk ? View.VISIBLE : View.GONE);
        maintenanceBody.setVisibility(upkeepOk && maintenanceOpen ? View.VISIBLE : View.GONE);
        upkeepHint.setText(upkeepOk ? "Follows Elegoo's own printer page. Never repeated automatically." : upkeepReason + " Emergency stop stays available.");
        tuningCard.setVisibility(canControl ? View.VISIBLE : View.GONE); upkeepCard.setVisibility(canControl ? View.VISIBLE : View.GONE);
        // On a new install the Get started card already offers Find, Sign in and Slice, so the empty Controls card waits.
        controlsCard.setVisibility(firstRun && block == ControlState.Block.DISCONNECTED ? View.GONE : View.VISIBLE);
        // On first run the Get started card already says how to connect; the "Not connected" summary would repeat it.
        heroCard.setVisibility(firstRun && block == ControlState.Block.DISCONNECTED ? View.GONE : View.VISIBLE);
        canvasCard.setVisibility(noData ? View.GONE : View.VISIBLE); tilesRow.setVisibility(noData ? View.GONE : View.VISIBLE);
        printRow.setVisibility(inJob ? View.VISIBLE : View.GONE); lightRow.setVisibility(noData || !canControl ? View.GONE : View.VISIBLE);
        cancelUpload.setVisibility(busy ? View.VISIBLE : View.GONE); cancelDownload.setVisibility(printer != null && printer.downloading() ? View.VISIBLE : View.GONE);
        transferRow.setVisibility(cancelUpload.getVisibility() == View.VISIBLE || cancelDownload.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        renderFeatures(printer != null && printer.canQuery() && (ready || cloudOk), ready);
        renderCamera(ready, cloud);
        boolean refillOk = canControl && printer.canvasFresh() && printer.canvas.has("auto_refill");
        refill.setEnabled(refillOk); refill.setVisibility(refillOk ? View.VISIBLE : View.GONE);
        if ((ready || cloud) && printer.canvas != null && printer.canvas.has("auto_refill")) refill.setText(printer.canvas.optBoolean("auto_refill") ? "Disable automatic refill…" : "Enable automatic refill…");
        else refill.setText(ready ? "Automatic refill unavailable" : "Automatic refill (refresh trays first)");
        boolean fileBusy = printer != null && printer.fileBusy();
        upload.setEnabled((ready && !printer.pinProbe() || cloudFresh && cloudOk && printer.cloudUploadReady() && !printer.cloudCommandBusy()) && printer.selectedFile != null && !fileBusy && !printer.replacesActivePrint(printer.selectedName));
        upload.setText(printer != null && printer.replacesActivePrint(printer.selectedName) ? "Being printed: cannot replace" : ready || !cloud ? "Upload to printer" : "Upload through the cloud"); pick.setEnabled(printer != null && !fileBusy); recentSlices.setEnabled(printer != null && !fileBusy && !SliceStore.list(this).isEmpty());
        boolean hasReport = printer != null && printer.selectedFile != null && printer.selectedReport != null;
        saveCopy.setEnabled(hasReport && !fileBusy); shareInspection.setEnabled(hasReport && !fileBusy); clearCopy.setEnabled(printer != null && printer.selectedFile != null && !fileBusy);
        GcodeInspector.Report report = hasReport ? printer.selectedReport : null;
        if (renderedReport != report || !hasReport) { renderedReport = report; inspection.setText(hasReport ? report.text() : ""); }
        fileDetails.setVisibility(printer != null && (printer.selectedFile != null || printer.importing) ? View.VISIBLE : View.GONE);
        if (!hasReport && printer != null && printer.importing) inspection.setText("Importing and inspecting G-code…");
        filePreview.setImageBitmap(hasReport ? printer.selectedThumbnail : null); filePreview.setVisibility(hasReport && printer.selectedThumbnail != null ? View.VISIBLE : View.GONE);
        previewInfo.setText(!hasReport ? "" : report.thumbnail == null ? report.thumbnailNote : printer.selectedThumbnail == null ? "Embedded image found but Android could not decode it. File inspection remains available." : report.thumbnailNote + " · slicer image, not a live camera or motion simulation");
        materialDetails.setEnabled(hasReport && !report.materials.entries.isEmpty());
        previewToolpath.setEnabled(printer != null && printer.selectedFile != null && !printer.importing);
        boolean chosen = printer != null && printer.selectedFile != null;
        selected.setText(chosen ? printer.selectedName + " · " + printer.selectedFile.length() / 1024 + " KiB" : "Slice a model or choose a .gcode file on this phone. Slicing needs no printer connection; upload does.");
        selected.setTextColor(chosen ? INK : MUTED); selected.setTypeface(Typeface.DEFAULT, chosen ? Typeface.BOLD : Typeface.NORMAL);
        // Header.
        String name = "Link Workshop", model = "Centauri Carbon 2";
        if (ready) {
            name = StatusPresentation.clean(printer.attributes.optString("hostname", profileName.getText().toString().isEmpty() ? "Centauri Carbon 2" : profileName.getText().toString()));
            JSONObject version = printer.attributes.optJSONObject("software_version");
            String firmware = version == null ? "" : StatusPresentation.clean(version.optString("ota_version")).trim();
            model = StatusPresentation.joinParts(StatusPresentation.clean(printer.attributes.optString("machine_model", "Centauri Carbon 2")), firmware.isEmpty() ? "" : "firmware " + firmware, printer.host());
        } else if (cloud) {
            name = printer.cloudName.isEmpty() ? "Cloud printer" : StatusPresentation.clean(printer.cloudName);
            String sn = printer.cloudSerial; model = StatusPresentation.clean(printer.cloudModel.isEmpty() ? "Centauri Carbon 2" : printer.cloudModel) + " · SN …" + (sn.length() > 4 ? sn.substring(sn.length() - 4) : sn);
        }
        title.setText(name); identity.setText(model);
        // Hero: state, progress ring, job.
        JSONObject machine = snapshot.optJSONObject("machine_status"), print = snapshot.optJSONObject("print_status");
        boolean printing = machine != null && machine.optInt("status", -1) == 2;
        int percent = machine == null ? 0 : Math.max(0, Math.min(100, machine.optInt("progress", 0)));
        String stateText = snapshot.length() == 0 ? (printer == null ? "Starting…" : connecting ? "Connecting…" : cloud ? "Waiting for printer" : "Not connected") : StatusPresentation.state(snapshot);
        if (snapshot.length() > 0 && !live) stateText += " · stale";
        if (!stateText.contentEquals(state.getText())) state.setText(stateText);
        // The title beside the ring already names the state, so the ring holds only progress (and stays empty while idle).
        boolean inJobNow = machine != null && machine.optInt("status", -1) == 2;
        ring.set(inJobNow ? percent : -1, inJobNow ? percent + "%" : "", inJobNow && print != null && print.optInt("total_layer", 0) > 0 ? "layer " + print.optInt("current_layer", 0) + "/" + print.optInt("total_layer", 0) : "");
        if (!inJobNow) ring.setContentDescription(stateText);
        // Without a print there is no progress to draw, so the ring gives its space to the title.
        ring.setVisibility(inJobNow ? View.VISIBLE : View.GONE); ((View) state.getParent()).setPadding(inJobNow ? dp(16) : 0, 0, 0, 0);
        if (printing && print != null) {
            long remaining = print.optLong("remaining_time_sec", -1);
            job.setText(StatusPresentation.clean(print.optString("filename", "Current print")).replaceFirst("(?i)\\.gcode$", ""));
            detail.setText(remaining < 0 ? "Time remaining unavailable" : remaining / 3600 + "h " + (remaining % 3600) / 60 + "m left · done ≈ "
                + StatusPresentation.doneAt(System.currentTimeMillis(), remaining, Locale.getDefault(), TimeZone.getDefault()) + (live ? "" : " · stale"));
        } else {
            job.setText(snapshot.length() == 0 ? (cloud || ready ? "" : "Connect in Settings, or sign in with Elegoo to watch through the cloud.") : "No active print.");
            detail.setText("");
        }
        graphs.setText("Print recordings");
        // Quick actions by state. Printing: Live toolpath and Camera. Connected and idle: Slice a model, Camera and, with history, Print again.
        // Not connected: only Print recordings, as before (Get started and the fix guidance cover the rest). None of these sends a printer command.
        boolean idleOnline = live && !inJobNow, hasHistory = printer != null && !FeatureData.historyRows(printer.history).isEmpty();
        liveToolpath.setVisibility(inJobNow ? View.VISIBLE : View.GONE);
        quickSlice.setVisibility(idleOnline ? View.VISIBLE : View.GONE);
        quickCamera.setVisibility(inJobNow || idleOnline ? View.VISIBLE : View.GONE);
        quickRow.setVisibility(inJobNow || idleOnline ? View.VISIBLE : View.GONE);
        quickAgain.setVisibility(idleOnline && hasHistory ? View.VISIBLE : View.GONE);
        spaceActions(quickRow); spaceActions(recordingsRow);
        job.setVisibility(job.getText().length() == 0 ? View.GONE : View.VISIBLE); detail.setVisibility(detail.getText().length() == 0 ? View.GONE : View.VISIBLE);
        String codes = StatusPresentation.faultCodes(snapshot);
        String faultText = codes.isEmpty() ? "" : "Printer reports fault code(s): " + codes + ". Check the printer screen."; if (!faultText.contentEquals(faults.getText())) faults.setText(faultText);
        faults.setVisibility(codes.isEmpty() ? View.GONE : View.VISIBLE);
        setTile(tileNozzle, temperature("extruder")); setTile(tileBed, temperature("heater_bed")); setTile(tileChamber, temperature("ztemperature_sensor"));
        // Where controls go.
        switch (block) {
            case NONE: controlSource.setText(ready ? "Commands go over your local network." : "Commands go through the Elegoo cloud. " + lastReport() + (printer.cloudLiveOn ? " · live" : "") + "."); break;
            case PIN_PROBE: controlSource.setText("Read-only PIN probe: controls are disabled."); break;
            case STALE: controlSource.setText("Waiting for printer status. Controls unlock when it arrives."); break;
            case CONNECTING: controlSource.setText("Connecting on your local network…"); break;
            case CLOUD_AGREEMENT: controlSource.setText("You are watching through the Elegoo cloud. Controls stay off until you agree to cloud control."); break;
            case CLOUD_BUSY: controlSource.setText("Sending through the Elegoo cloud…"); break;
            case CLOUD_OFFLINE: controlSource.setText("Printer offline. The Elegoo cloud reports it offline."); break;
            case CLOUD_WAITING: controlSource.setText(!printer.cloudMessage.isEmpty() ? printer.cloudMessage : printer.cloudReportedAt > 0 && !printer.cloudReportRecent() ? "The printer has not reported for a while. " + lastReport() + " Controls stay off until it reports again." : "Waiting for printer through the Elegoo cloud…"); break;
            default: controlSource.setText("Not connected. Connect on your network, or sign in with Elegoo to control through the cloud."); break;
        }
        boolean fix = block == ControlState.Block.CLOUD_AGREEMENT || block == ControlState.Block.DISCONNECTED;
        controlFix.setText(block == ControlState.Block.CLOUD_AGREEMENT ? "Turn on cloud control…" : "Open Settings"); ((View) controlFix.getParent()).setVisibility(fix ? View.VISIBLE : View.GONE);
        JSONObject canvasNow = (ready || cloud) && printer.canvas != null ? printer.canvas : null;
        renderTrays(canvasNow);
        if (canvasNow != null) {
            String refillText = "Automatic refill: " + (canvasNow.has("auto_refill") ? (canvasNow.optBoolean("auto_refill") ? "On" : "Off") : "Not reported");
            trays.setText(trayList.getChildCount() == 0 ? StatusPresentation.canvas(canvasNow) : refillText + (!printer.canvasFresh() ? "\nTray status is stale; refresh before changing refill." : ""));
        } else trays.setText(cloud ? "Tap Refresh status to load trays through the cloud." : ready ? StatusPresentation.canvas(null) : "Connect for CANVAS tray status.");
        showFeedback(printer == null ? null : printer.feedback);
        cloudLiveLabel.setText(printer == null ? "" : printer.cloudLiveState); cloudLiveLabel.setVisibility(cloudLiveLabel.getText().length() == 0 ? View.GONE : View.VISIBLE);
        showThumbnail();
    }
    /** One row per reported tray: colour swatch, material and the active-tray marker. Rebuilt only when the trays change. */
    private void renderTrays(JSONObject canvas) {
        String key = canvas == null ? null : canvas.toString();
        if (java.util.Objects.equals(key, renderedCanvas)) return;
        renderedCanvas = key; trayList.removeAllViews();
        JSONArray units = canvas == null ? null : canvas.optJSONArray("canvas_list");
        if (units == null) return;
        for (int u = 0; u < Math.min(units.length(), 8); u++) {
            JSONObject unit = units.optJSONObject(u); if (unit == null) continue;
            JSONArray list = unit.optJSONArray("tray_list"); if (list == null) continue;
            int id = unit.optInt("canvas_id", -1);
            Object connected = unit.opt("connected");
            boolean online = connected == null || Boolean.TRUE.equals(connected) || unit.optInt("connected", 0) == 1;
            if (units.length() > 1 || !online) label(trayList, "CANVAS " + id + (online ? "" : " · Not connected"), 12, MUTED, true);
            for (int t = 0; t < Math.min(list.length(), 16); t++) {
                JSONObject tray = list.optJSONObject(t); if (tray == null) continue;
                int trayId = tray.optInt("tray_id", -1);
                boolean active = canvas.has("active_canvas_id") && canvas.has("active_tray_id") && id == canvas.optInt("active_canvas_id") && trayId == canvas.optInt("active_tray_id");
                String type = StatusPresentation.clean(tray.optString("filament_type")).trim(), name = StatusPresentation.clean(tray.optString("filament_name")).trim();
                String colour = TrayPlan.colour(tray.optString("filament_color"));
                LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(6), 0, dp(6));
                View swatch = new View(this); GradientDrawable dot = new GradientDrawable(); dot.setShape(GradientDrawable.OVAL);
                if (colour != null) dot.setColor(Color.parseColor(colour)); else dot.setColor(Color.TRANSPARENT);
                dot.setStroke(dp(colour == null ? 2 : 1), MUTED); swatch.setBackground(dot);
                LinearLayout.LayoutParams swatchSize = new LinearLayout.LayoutParams(dp(28), dp(28)); swatchSize.rightMargin = dp(12); row.addView(swatch, swatchSize);
                LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
                TextView first = new TextView(this); first.setTextColor(INK); first.setTextSize(15); first.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
                first.setText("Tray " + trayId + " · " + (type.isEmpty() ? "Empty" : name.isEmpty() || name.equalsIgnoreCase(type) ? type : name)); text.addView(first);
                String second = type; // the dot shows the colour; its plain name is written beside the material and spoken instead of the hex
                if (colour != null) second += (second.isEmpty() ? "" : " · ") + colourWords(colour);
                if (tray.has("min_nozzle_temp") && tray.has("max_nozzle_temp")) second += (second.isEmpty() ? "" : " · ") + tray.optInt("min_nozzle_temp") + "–" + tray.optInt("max_nozzle_temp") + "°C";
                if (!second.isEmpty()) { TextView detailLine = new TextView(this); detailLine.setText(second); detailLine.setTextColor(MUTED); detailLine.setTextSize(12); text.addView(detailLine); }
                if (active) { TextView chip = new TextView(this); chip.setTextSize(12); chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD); chip.setPadding(dp(10), dp(4), dp(10), dp(4)); chip(chip, "Active", TEAL); row.addView(chip); }
                row.setContentDescription("Tray " + trayId + ", " + (type.isEmpty() ? "empty" : type + " " + name) + (colour == null ? "" : ", " + colourWords(colour)) + (active ? ", active" : ""));
                trayList.addView(row);
            }
        }
    }
    private void setTile(TextView view, String text) {
        int split = text.indexOf('\n');
        if (split < 0) { view.setText(text); return; }
        android.text.SpannableString styled = new android.text.SpannableString(text);
        styled.setSpan(new android.text.style.RelativeSizeSpan(0.62f), split, text.length(), 0);
        styled.setSpan(new android.text.style.ForegroundColorSpan(MUTED), split, text.length(), 0);
        view.setText(styled);
    }
    private String temperature(String key) {
        JSONObject value = snapshot.optJSONObject(key); if (value == null || !value.has("temperature")) return "—";
        String text = String.format(Locale.ROOT, "%.0f°", value.optDouble("temperature", 0));
        double target = value.optDouble("target", 0);
        return text + (value.has("target") ? (target > 0 ? String.format(Locale.ROOT, "\n→ %.0f°", target) : "\noff") : "");
    }
    private void message(String text) { if (printer != null) printer.feedback = text; else pendingFeedback = text; showFeedback(text); }
    private void showFeedback(String text) {
        if (feedback == null) return;
        boolean show = text != null && !text.isEmpty() && !text.equals(dismissedFeedback);
        if (show) { feedback.setText(text); dismissedFeedback = ""; }
        feedback.setVisibility(show ? View.VISIBLE : View.GONE);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE_TIMELAPSE && result == RESULT_OK && data != null && data.getData() != null && printer != null && printer.timelapseFile != null) {
            File source = printer.timelapseFile; android.net.Uri target = data.getData();
            checks.execute(() -> {
                String text;
                try (InputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new IOException();
                    byte[] buffer = new byte[1 << 16]; int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    text = "Timelapse saved.";
                } catch (IOException error) { text = "The timelapse could not be saved there."; }
                String done = text; main.post(() -> { if (!isDestroyed()) message(done); });
            });
        }
        if (request == SAVE_GCODE) {
            if (result == RESULT_OK && data != null && data.getData() != null) { pendingExportUri = data.getData(); finishPendingExport(); }
            else { pendingExportHash = ""; pendingExportUri = null; }
        }
        if (request == PICK_FILE && result == RESULT_OK && data != null && data.getData() != null && printer != null) printer.select(data.getData());
        if (request == SLICE && result == RESULT_OK && data != null && data.getStringExtra(SliceActivity.RESULT_FILE) != null) settings.edit().putBoolean("everSliced", true).apply();
        if (request == SLICE && result == RESULT_OK && data != null && data.getStringExtra(SliceActivity.RESULT_PRINT_SETUP) != null) awaitPrintSetup(data.getStringExtra(SliceActivity.RESULT_PRINT_SETUP));
        if (request == SLICE && result == RESULT_OK && data != null && printer != null) {
            String path = data.getStringExtra(SliceActivity.RESULT_FILE), name = data.getStringExtra(SliceActivity.RESULT_NAME);
            if (path != null && name != null) printer.selectSliced(new File(path), name);
            openRequestedPage(data);
        }
        if (request == CLOUD_LOGIN) { renderCloudAccount(); if (result == RESULT_OK) { message("Signed in with Elegoo. Monitor now shows your printer through the cloud when there is no local connection."); selectPage(0); } }
        if (request == PICK_SNAPSHOT && result != RESULT_OK) pendingSnapshot = null;
        if (request == PICK_SNAPSHOT && result == RESULT_OK && data != null && data.getData() != null && pendingSnapshot != null) {
            Bitmap image = pendingSnapshot; pendingSnapshot = null; android.net.Uri uri = data.getData();
            checks.execute(() -> { String text; try (OutputStream output = getContentResolver().openOutputStream(uri)) { if (output == null || !image.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw new IOException(); text = "Camera snapshot saved."; } catch (Exception error) { text = "Snapshot could not be saved. Capture it again."; } String done = text; main.post(() -> { if (!isDestroyed()) message(done); }); });
        }
    }
    private void finishPendingExport() {
        if (printer == null || pendingExportUri == null) return;
        printer.exportPhoneCopy(pendingExportUri, pendingExportHash); pendingExportUri = null; pendingExportHash = "";
    }
    @Override protected void onStart() {
        super.onStart(); active = true;
        // Background cloud watching runs as a started foreground service; start it while the app is visible.
        if (settings.getBoolean("cloudBackground", false) && cloudBackground != null && cloudBackground.isEnabled()) try { startForegroundService(new Intent(this, PrinterService.class)); } catch (Exception ignored) { }
        if (printer != null) { printer.observe(this::render); printer.cloudVisible(true); } main.post(clock);
    }
    @Override protected void onStop() { active = false; stopCamera(); main.removeCallbacks(clock); if (printer != null) { printer.observe(null); printer.cloudVisible(false); } super.onStop(); }
    @Override protected void onDestroy() { if (scanning != null) scanning.close(); stopCamera(); if (printer != null) printer.observe(null); if (bound) unbindService(binding); checks.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("exportHash", pendingExportHash); out.putString("exportUri", pendingExportUri == null ? "" : pendingExportUri.toString()); out.putString("host", host.getText().toString()); out.putString("serial", serial.getText().toString()); out.putString("diagnostics", diagnostics.getText().toString()); out.putString("profileName", profileName.getText().toString()); out.putBoolean("remember", remember.isChecked()); out.putInt("page", page); }
    private static final class TransientInputs { String host, code; Bitmap snapshot; }
    @Override public Object onRetainNonConfigurationInstance() { TransientInputs state = new TransientInputs(); state.host = host.getText().toString(); state.code = access.getText().toString(); state.snapshot = pendingSnapshot; return state; }
    private void showLicenses() {
        try {
            StringBuilder text = new StringBuilder("Link Workshop v" + appVersion() + "\nIndependent Android app derived from Elegoo Link. Cloud commands use Agora RTM, Elegoo's cloud command service.\n\n");
            for (String name : new String[] {"THIRD_PARTY_NOTICES.md", "Apache-2.0.txt", "Paho-NOTICE.txt", "Paho-EDL-1.0.txt", "Paho-EPL-2.0.txt"}) {
                try (InputStream input = getAssets().open("licenses/" + name); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count); text.append(bytes.toString("UTF-8")).append("\n\n");
                }
            }
            // Builds with the slicer carry its AGPL-3.0 license (slicer/scripts/install-into-app.sh).
            try (InputStream input = getAssets().open("slicer/LICENSE-AGPL-3.0.txt"); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count; while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                text.append("Slicer (ElegooSlicer engine), AGPL-3.0. Source: https://github.com/TheLastFrogrammer/elegoo-link/tree/main/slicer\n\n").append(bytes.toString("UTF-8")).append("\n\n");
            } catch (IOException notIncluded) { }
            ScrollView scroll = new ScrollView(this); TextView view = new TextView(this); view.setText(text.toString()); view.setTextColor(INK); view.setTextSize(13); view.setPadding(dp(20), dp(12), dp(20), dp(12)); scroll.addView(view);
            new AlertDialog.Builder(this).setTitle("About & licenses").setView(scroll).setPositiveButton("Close", null).show();
        } catch (IOException error) { message("License information could not be opened."); }
    }
    /** The field-test log (slicing, Live toolpath, tray plans) with app and device details, through the share sheet. */
    private void shareDiagnostics() {
        android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
        ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        String slicer;
        try (InputStream in = getAssets().open("slicer/VERSION"); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256]; int count; while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            slicer = bytes.toString("UTF-8").trim();
        }
        catch (IOException notIncluded) { slicer = "not included"; }
        String header = "App " + appVersion() + " · slicer " + slicer + "\nDevice " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
            + String.join("/", Build.SUPPORTED_ABIS) + " · " + memory.totalMem / (1024 * 1024) + " MB RAM, " + memory.availMem / (1024 * 1024) + " MB free";
        String report = Diagnostics.report(header);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Link Workshop diagnostics").putExtra(Intent.EXTRA_TEXT, report);
        new AlertDialog.Builder(this).setTitle("Diagnostics").setMessage(report.length() > 4000 ? report.substring(0, 4000) + "\n…" : report)
            .setNegativeButton("Clear log", (d, w) -> { Diagnostics.clear(); message("Diagnostics log cleared."); })
            .setNeutralButton("Close", null)
            .setPositiveButton("Share…", (d, w) -> startActivity(Intent.createChooser(send, "Share diagnostics"))).show();
    }
    private String appVersion() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception error) { return "dev"; } }
    private LinearLayout card(String title) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable background = new GradientDrawable(); background.setColor(SURFACE); background.setCornerRadius(dp(20)); card.setBackground(background);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); (currentSection == null ? content : currentSection).addView(card, layout);
        if (title != null) A11y.heading(label(card, title, 17, INK, true));
        return card;
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }
    /** Equal-width button in a row; primary buttons are filled with the accent color. */
    private Button rowButton(LinearLayout row, String text, Runnable action, boolean primary) {
        Button button = new A11y.DimButton(this); button.setText(text); styleButton(button);
        if (primary) {
            // Disabled primary buttons fall back to the tonal colors so they do not look tappable.
            GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(12));
            shape.setColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {BUTTON, TEAL}));
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape, null));
            button.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {MUTED, dark ? 0xff00201c : Color.WHITE}));
        }
        button.setOnClickListener(view -> action.run());
        // With large text, a row of buttons stacks so no label breaks mid-word ("Disconnec / t").
        boolean onlyButtons = true; for (int i = 0; i < row.getChildCount(); i++) onlyButtons &= row.getChildAt(i) instanceof Button;
        if (getResources().getConfiguration().fontScale >= 1.3f && onlyButtons) {
            row.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < row.getChildCount(); i++) { LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams(); p.width = -1; p.weight = 0; p.leftMargin = 0; }
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(6);
            row.addView(button, layout); return button;
        }
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1); layout.topMargin = dp(6);
        if (row.getChildCount() > 0) layout.leftMargin = dp(8);
        row.addView(button, layout); return button;
    }
    /** Temperature tile: small caption, large current value, target on a second line. */
    private TextView tile(LinearLayout row, String caption, int gap) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable(); background.setColor(SURFACE); background.setCornerRadius(dp(16)); tile.setBackground(background);
        TextView name = new TextView(this); name.setText(caption); name.setTextSize(12); name.setTextColor(MUTED); tile.addView(name);
        TextView value = new TextView(this); value.setText("—"); value.setTextSize(20); value.setTextColor(INK); value.setTypeface(Typeface.DEFAULT, Typeface.BOLD); tile.addView(value);
        boolean stacked = row.getOrientation() == LinearLayout.VERTICAL; // at large font sizes the tiles stack instead of breaking words
        LinearLayout.LayoutParams layout = stacked ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -2, 1);
        if (stacked) layout.topMargin = gap == 0 ? 0 : dp(8); else layout.leftMargin = gap;
        row.addView(tile, layout);
        return value;
    }
    private static int darker(int color) { return 0xff000000 | (int) (((color >> 16) & 255) * 0.8f) << 16 | (int) (((color >> 8) & 255) * 0.8f) << 8 | (int) ((color & 255) * 0.8f); }
    private void chip(TextView view, String text, int color) {
        // Light theme: the text is a darker shade of the colour on a fainter tint, so it stays above 4.5:1 on the page and card backgrounds.
        int text_ = dark ? color : darker(color);
        view.setText(text); view.setTextColor(text_);
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(12)); shape.setColor((color & 0x00ffffff) | (dark ? 0x26000000 : 0x14000000)); view.setBackground(shape);
    }
    /** A group heading inside a card, set apart from the controls above it. */
    private void subheading(LinearLayout parent, String text) {
        TextView view = A11y.heading(label(parent, text, 14, INK, true));
        ((LinearLayout.LayoutParams) view.getLayoutParams()).topMargin = dp(14);
    }
    private TextView label(LinearLayout parent, String text, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(7)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private EditText input(LinearLayout parent, String hint, boolean password) {
        EditText input = new EditText(this); input.setHint(hint); input.setHintTextColor(MUTED); input.setBackgroundTintList(tint(TEAL)); input.setSingleLine(true); input.setTextColor(tint(INK)); if (password) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setMinHeight(dp(56)); parent.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new A11y.DimButton(this); button.setText(text); styleButton(button);
        button.setOnClickListener(view -> action.run()); LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(6); parent.addView(button, layout); return button;
    }
    private ColorStateList tint(int color) { return new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {MUTED, color}); }
    private void styleButton(Button button) {
        button.setAllCaps(false); button.setTextColor(tint(TEAL)); button.setMinHeight(dp(48));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(BUTTON); shape.setCornerRadius(dp(12));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(6); button.setLayoutParams(layout); button.setPadding(dp(10), dp(8), dp(10), dp(8));
    }
    private CheckBox checkbox(LinearLayout parent, String text, boolean checked) { CheckBox view = new CheckBox(this); view.setMinHeight(dp(48)); view.setText(text); view.setTextColor(INK); view.setButtonTintList(tint(TEAL)); view.setChecked(checked); parent.addView(view); return view; }
    private static String colourWords(String hex) { String name = WorkshopUi.colourName(hex); return name == null ? "colour not reported" : name; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
