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
    private static final int PICK_FILE = 1, PICK_SNAPSHOT = 3, CLOUD_LOGIN = 4, SAVE_GCODE = 5, SLICE = 6, SAVE_TIMELAPSE = 7, SHOWCASE = 8, SAVE_SHOWCASE = 9, GCODE_FOLDER = 10, BACKUP_SAVE = 11, BACKUP_OPEN = 12;
    private String pendingExportHash = "";
    private android.net.Uri pendingExportUri;
    private TextView inspection;
    private ImageView filePreview;
    private TextView previewInfo, previewWarning;
    private LinearLayout timelapseList;
    private String saveAfterDownload, pendingPrintSetup, previewAfterDownload; private long previewGiveUpAt;
    /** Intent extra: the tab to open (FeatureIndex.PRINT … SETTINGS), from another screen's hint. Settings opens on its connection section. */
    static final String EXTRA_PAGE = "openPage";
    private long printSetupUntil;
    private long saveGiveUpAt;
    private Button saveTimelapse;
    private JSONObject renderedHistory;
    private LinearLayout fileDetails, transferRow, trayList, pageRow;
    private LinearLayout printRow, lightRow, tilesRow, controlsCard, canvasCard, tuningCard, upkeepCard, upkeepButtons, moreOptions, cameraCloudCard, cameraLocalCard, localCameraBody;
    private Button controlFix, filesFix, moreToggle, localToggle;
    private TextView tuningHint, upkeepHint, cloudCameraHint, timelapseHint, fileHelp;
    private boolean moreOpen, localCameraOpen, cameraLocalFirst;
    private LinearLayout historyButtons, historyList, filamentButtons;
    private TextView filamentHint, printerStatus;
    private Button printerFix;
    /** Emergency stop sits on the Printer tab's front page and in each section that moves the printer. */
    private final List<Button> emergencyStops = new ArrayList<>();
    private JSONObject renderedHistoryList;
    private ControlState.Block block = ControlState.Block.DISCONNECTED;
    private String dismissedFeedback = "", renderedCanvas;
    private GcodeInspector.Report renderedReport;
    private Button materialDetails;
    private Button saveCopy, shareInspection, clearCopy, cancelDownload, layerCharts;
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
        boolean signedIn = cloudAccounts != null && cloudAccounts.present();
        return signedIn && host.getText().toString().trim().isEmpty();
    }
    private Button segment(LinearLayout row, String text, Runnable action) {
        Button button = rowButton(row, text, action, false); A11y.tab(button, false); return button;
    }
    private void styleSegment(Button button, boolean selected) {
        if (button == null) return;
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(24)); shape.setColor(selected ? TEAL : BUTTON);
        button.setBackground(shape); button.setTextColor(selected ? (dark ? 0xff00201c : Color.WHITE) : TEAL);
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        A11y.tab(button, selected);
    }
    private static final int PRINT = FeatureIndex.PRINT, PRINTER = FeatureIndex.PRINTER, FILES = FeatureIndex.FILES, HISTORY = FeatureIndex.HISTORY,
        SETTINGS = FeatureIndex.SETTINGS, TAB_COUNT = FeatureIndex.TABS.length;
    private final LinearLayout[] pages = new LinearLayout[TAB_COUNT];
    private final LinearLayout[] tabs = new LinearLayout[TAB_COUNT];
    private int page = SETTINGS;
    private SharedPreferences settings;
    private ProfileStore profiles;
    private EditText profileName, cameraAddress, pairingPin;
    private TextView pinProbeHelp, cloudStatus, cloudLiveLabel, lanHint;
    private Button cloudSignOut, cloudPrinters, probeButton;
    private TextView probeResult;
    private CompoundButton cloudBackground;
    private Button loadFilament, unloadFilament, trayFilament, homeAll, jog, autoLevel, vibration, selfCheck;
    private ImageView fileThumbnail;
    private Button graphs;
    private String fileThumbnailKey = "";
    private Button cloudCamera, cameraFix;
    /** First run only: the three ways in. Hidden for good once the phone has connected or a model has been sliced. */
    private LinearLayout getStarted, heroCard;
    /** Print tab quick actions: navigation only, never a printer command. quickRow holds Live toolpath or Slice, then Camera; recordingsRow holds Layer alert (printing) or Print again (idle) and Print recordings; printExtrasRow the running print's Pictures. */
    private LinearLayout quickRow, recordingsRow, printExtrasRow, rateRow; private Button rateButton;
    private Button quickSlice, quickCamera, quickAgain, picturesNow, printAutomations, printOptions;
    /** Maintenance starts collapsed; the Emergency stop button stays outside the collapsed part. */
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
    /** A camera the person added, playing (Print tab or Camera section), and which one. */
    private ExtraCameras.Feed extraFeed; private ExtraCameras.Camera extraShown;
    private Button monitorSourceButton; private LinearLayout extraList; private ImageView extraImage; private TextView extraInfo;
    private Bitmap lastFrame, pendingSnapshot;
    private String cameraHost = "";
    private String cameraReported = "";
    private boolean scanningNow;
    private EditText host, access, serial;
    private CompoundButton remember;
    private TextView connection, state, job, feedback, selected, faults, trays, identity, diagnostics;
    private Button connect, pause, stop, refresh, upload, pick, refill, check, forget, recentSlices;
    /** The Print tab while printing: the live camera on top (local stream, only while this tab is on screen), then the job with its thumbnail. */
    private LinearLayout monitorCamera, monitorBody, trayStrip;
    /** Show camera: the one button left when the Print tab's camera is hidden. */
    private Button monitorShow;
    /** The cloud video playing inside the Print tab's camera block (cloud mode), or null; and the frame that holds the picture. */
    private CloudVideo cloudVideo; private FrameLayout monitorFrame; private boolean pendingCloudSnapshot;
    /** Cloud picture visit: a short, unseen join of the printer's video channel that keeps one print picture when one is due. */
    /** Cloud picture visits (made in onCreate, once the settings are loaded). */
    /** The service's cloud picture visits (none before it is bound). */
    private boolean visitRunning() { return printer != null && printer.cloudPictureRunning(); }
    private void endVisit() { if (printer != null) printer.leaveCloudPictures(); else CloudFrameGrab.leave(); }
    private Button cameraCheckButton, cameraAskButton; private TextView cameraCheckText; private boolean cameraChecking;
    private ImageView monitorImage, jobThumb;
    private TextView monitorCameraInfo, percentText;
    private Button monitorCameraStart, trayDetails;
    private SegmentBar jobBar;
    /** Dashboard pieces of the hero: "Done at" beside the percentage, the Elapsed / Remaining / Layer tiles, Light beside Pause and Stop, the camera's badge. */
    /** Temperatures, fans & speed: what the heaters and fans are doing now, above the controls that change them. */
    private TextView tuneNozzle, tuneBed, tunePartFan, tuneAuxFan, tuneChamberFan; private LinearLayout tuneReadout;
    private TextView doneText, statElapsed, statRemaining, statLayer, monitorBadge, printNotes;
    private LinearLayout statsRow;
    private Button printLight;
    /** "This print so far": the running print's temperatures from its recording, redrawn at most once a minute. */
    private LinearLayout guardianCard, guardianButtons; private ImageView guardianImage; private TextView guardianText; private android.graphics.Bitmap guardianShown;
    private LinearLayout telemetryCard; private ChartView telemetryChart; private long telemetryAt; private String telemetryFor = "";
    private boolean monitorCameraTried, traysOpen;
    private String jobThumbAsked = "", jobThumbKey = "";
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
            takeSliced(); takeShared();
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
        Diagnostics.init(getFilesDir()); Crashes.install(this); MainThreadWatch.start();
        long createdAt = android.os.SystemClock.elapsedRealtime();
        // After a crash: say so once, and where the details are (the record holds no messages, addresses or codes).
        if (settings.getLong(Crashes.SEEN_KEY, 0) > 0) { settings.edit().remove(Crashes.SEEN_KEY).apply(); pendingFeedback = "Link Workshop closed unexpectedly last time. Settings › Share diagnostics… has the details, if you want to send them."; }
        credentials = new CredentialStore(this);
        profiles = new ProfileStore(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BACKGROUND);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BACKGROUND); mainScroll = scroll;
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(16), dp(2), dp(16), dp(24)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        // Header, fixed above the scrolling page: the printer's name (tap to switch), its connection chip and a search button on one
        // line; the model and what the connection can do on one line each beneath (tap them for the whole text).
        boolean largeText = getResources().getConfiguration().fontScale >= 1.3f;
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.VERTICAL); bar.setPadding(dp(16), dp(8), dp(6), dp(6)); bar.setBackgroundColor(BACKGROUND);
        root.addView(bar, 0, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout header = new LinearLayout(this); header.setOrientation(LinearLayout.HORIZONTAL); header.setGravity(Gravity.CENTER_VERTICAL); bar.addView(header);
        title = new TextView(this); title.setText("Link Workshop");
        title.setTextSize(20); title.setTextColor(INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(largeText ? 2 : 1); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setMinHeight(dp(48)); title.setGravity(Gravity.CENTER_VERTICAL);
        A11y.heading(title); header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        summary = new TextView(this); summary.setTextSize(12); summary.setTypeface(Typeface.DEFAULT, Typeface.BOLD); summary.setPadding(dp(10), dp(4), dp(10), dp(4)); summary.setMaxLines(1);
        LinearLayout.LayoutParams chipLayout = new LinearLayout.LayoutParams(-2, -2); chipLayout.leftMargin = dp(8); header.addView(summary, chipLayout);
        // "Find a feature": on every tab, jumps to any feature by name or everyday words.
        android.widget.ImageButton find = new android.widget.ImageButton(this); find.setImageResource(android.R.drawable.ic_menu_search); find.setImageTintList(ColorStateList.valueOf(INK));
        find.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), null, new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        find.setContentDescription("Find a feature"); find.setOnClickListener(v -> featureDialog());
        header.addView(find, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title.setOnClickListener(v -> switchPrinter()); A11y.clickLabel(title, "Switch printer");
        identity = label(bar, "Centauri Carbon 2", 12, MUTED, false); identity.setSingleLine(true); identity.setEllipsize(android.text.TextUtils.TruncateAt.END);
        reach = label(bar, "", 12, MUTED, false); reach.setVisibility(View.GONE); reach.setSingleLine(true); reach.setEllipsize(android.text.TextUtils.TruncateAt.END);
        for (TextView line : new TextView[] {identity, reach}) {
            line.setPadding(0, 0, dp(10), 0);
            line.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(title.getText().toString().replace(" ▾", ""))
                .setMessage(identity.getText() + (reach.getText().length() == 0 ? "" : "\n\n" + reach.getText())).setPositiveButton("Close", null).show());
            A11y.clickLabel(line, "Show the printer's details");
        }
        // Results of actions show here, on every tab, until tapped away or replaced.
        feedback = new TextView(this); feedback.setTextSize(14); feedback.setTextColor(INK); feedback.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable banner = new GradientDrawable(); banner.setColor(TILE); banner.setCornerRadius(dp(14)); banner.setStroke(dp(1), (TEAL & 0x00ffffff) | 0x55000000); feedback.setBackground(banner);
        A11y.polite(feedback); A11y.clickLabel(feedback, "Dismiss message"); feedback.setVisibility(View.GONE);
        feedback.setOnClickListener(v -> { dismissedFeedback = feedback.getText().toString(); feedback.setVisibility(View.GONE); });
        LinearLayout.LayoutParams bannerLayout = new LinearLayout.LayoutParams(-1, -2); bannerLayout.topMargin = dp(8); content.addView(feedback, bannerLayout);
        // Bottom navigation.
        LinearLayout navigation = new LinearLayout(this); navigation.setOrientation(LinearLayout.HORIZONTAL); navigation.setBackgroundColor(NAV); navigation.setElevation(dp(8));
        String[] titles = FeatureIndex.TABS;
        int[] icons = {R.drawable.ic_nav_monitor, R.drawable.ic_nav_printer, R.drawable.ic_nav_files, R.drawable.ic_nav_history, R.drawable.ic_nav_settings};
        for (int i = 0; i < TAB_COUNT; i++) {
            final int target = i; LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER); item.setPadding(0, dp(8), 0, dp(8));
            ImageView icon = new ImageView(this); icon.setImageResource(icons[i]); item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView name = new TextView(this); name.setText(titles[i]); name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 12 * getResources().getDisplayMetrics().density * Math.min(1.3f, getResources().getConfiguration().fontScale)); name.setGravity(Gravity.CENTER); name.setMaxLines(2); item.addView(name);
            item.setContentDescription(titles[i]); item.setOnClickListener(v -> selectPage(target));
            item.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x3363d5c7 : 0x22006b65), null, new android.graphics.drawable.ColorDrawable(Color.WHITE)));
            tabs[i] = item; navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        // The log notes how long the first screen took: the first frame, then the first frame with the Print tab's cards laid out.
        boolean[] noted = {false, false};
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() { public boolean onPreDraw() {
            long took = android.os.SystemClock.elapsedRealtime() - createdAt;
            if (!noted[0]) { noted[0] = true; Diagnostics.note(MainThreadWatch.AREA, "first frame after " + took + " ms"); }
            if (!noted[1] && heroCard != null && heroCard.getHeight() > 0) { noted[1] = true; Diagnostics.note(MainThreadWatch.AREA, "Print tab cards drawn after " + took + " ms"); }
            if (noted[0] && noted[1]) root.getViewTreeObserver().removeOnPreDrawListener(this);
            return true;
        } });
        for (int i = 0; i < TAB_COUNT; i++) buildHub(i);
        // Settings: a short front page of sections, the connection first.
        connectionSection = section(SETTINGS, "Printer connection", "");
        // App preferences, by what they are about: recording and alerts, automations and other apps, appearance, storage and backup.
        preferencesSection = section(SETTINGS, "Recording & alerts", "Print recordings, camera pictures, notifications, print watch and guardian");
        haSection = section(SETTINGS, "Automations & integrations", "Rules for print events, Home Assistant (read only)");
        appearanceSection = section(SETTINGS, "Appearance", "Theme: light, dark or follow the system");
        storageSection = section(SETTINGS, "Storage & backup", "Back up and restore settings, recordings and project files");
        helpSection = section(SETTINGS, "Help & diagnostics", "Connection help, Field check, printer probe and diagnostics");
        aboutSection = section(SETTINGS, "About", "Version " + appVersion() + ", licenses and planned features");
        currentSection = connectionSection.body;
        // Local or cloud: one connection card at a time, so the page stays short.
        LinearLayout modeRow = row(currentSection); ((LinearLayout.LayoutParams) modeRow.getLayoutParams()).topMargin = dp(12);
        modeLocal = segment(modeRow, "Local (Wi-Fi)", () -> setSettingsMode(false));
        modeCloud = segment(modeRow, "Elegoo cloud", () -> setSettingsMode(true));
        LinearLayout connectionCard = card("Printer connection");
        localSettings = connectionCard;
        label(connectionCard, "At home, on the printer's Wi-Fi, enter its IP address and access code (LAN Only). Away from home, use Elegoo cloud above.", 13, MUTED, false);
        connection = label(connectionCard, "Preparing connection service…", 14, TEAL, true);
        connection.setTextIsSelectable(true); connection.setMinHeight(dp(48)); // selectable text is a touch target too
        LinearLayout findRow = row(connectionCard);
        discover = rowButton(findRow, "Find on Wi-Fi", this::scanPrinters, false);
        chooseProfile = rowButton(findRow, "Saved printers…", this::choosePrinter, false);
        host = input(connectionCard, "Printer IP address", false); host.setInputType(InputType.TYPE_CLASS_PHONE);
        host.setText(credentials.host().isEmpty() ? getPreferences(MODE_PRIVATE).getString("host", "") : credentials.host());
        access = input(connectionCard, "Access code (LAN Only)", true); access.setTypeface(Typeface.DEFAULT); access.setSaveEnabled(false); access.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        lanHint = label(connectionCard, "Find the IP in printer Settings → Network. The access code appears after you turn on LAN Only.", 13, MUTED, false);
        remember = checkbox(connectionCard, "Remember access code securely on this phone", credentials.remembers());
        remember.setTextColor(INK); remember.setButtonTintList(tint(TEAL));
        LinearLayout connectRow = row(connectionCard);
        connect = rowButton(connectRow, "Connect", this::toggleConnection, true);
        check = rowButton(connectRow, "Check connection", this::checkConnection, false);
        diagnostics = label(connectionCard, "Check connection tests whether the printer is reachable and how it is set up.", 13, MUTED, false);
        diagnostics.setTextIsSelectable(true); diagnostics.setMinHeight(dp(48));
        // Advanced options (serial, route VPN, authentication, saved printers) are rarely changed, so they sit behind one toggle (open already when the route or PIN probe is in use).
        moreToggle = button(connectionCard, "", this::toggleMore);
        LinearLayout more = new LinearLayout(this); more.setOrientation(LinearLayout.VERTICAL); connectionCard.addView(more); moreOptions = more;
        label(more, "Leave the serial number blank to find it automatically. If that fails, enter it from printer Settings → Device.", 13, MUTED, false);
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
        pairingPin = input(more, "Pairing PIN shown on the printer (probe only)", true); pairingPin.setTypeface(Typeface.DEFAULT); pairingPin.setSaveEnabled(false); pairingPin.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        pinProbeHelp = label(more, "Experimental and read-only. Use the pairing PIN the printer shows now, not the access code. The PIN is never saved.", 13, MUTED, false);
        button(more, "Matrix coexistence test…", this::coexistenceHelp);
        subheading(more, "Saved printer");
        profileName = input(more, "Printer profile name", false);
        serial.setText(profiles.find(host.getText().toString()).optString("serial"));
        profileName.setText(profiles.find(host.getText().toString()).optString("name"));
        LinearLayout profileRow = row(more);
        saveProfile = rowButton(profileRow, "Save profile", this::savePrinter, false);
        removeProfile = rowButton(profileRow, "Remove…", this::removePrinter, false);
        forget = button(more, "Forget saved access code", () -> { credentials.forget(host.getText().toString().trim()); remember.setChecked(false); access.setText(""); pairingPin.setText(""); message("Saved access code removed; entered PIN cleared. An existing connection keeps its in-memory code until disconnected."); });
        setMore(settings.getBoolean("remoteVPN", false) || settings.getBoolean("pinProbe", false));
        currentSection = fronts[PRINT];
        // Get started: one line on what the app does, then the three ways in, each with one button.
        getStarted = card("Get started");
        label(getStarted, "Watch or control your Centauri Carbon 2, or slice a model on this phone. Pick one way to start.", 14, INK, false);
        label(getStarted, "1. At home, on Wi-Fi", 14, INK, true);
        label(getStarted, "Turn on the printer and join this phone to the same Wi-Fi. Then look for it.", 13, MUTED, false);
        rowButton(row(getStarted), "Find my printer on Wi-Fi", () -> { openSettings(false); scanPrinters(); }, true);
        label(getStarted, "2. Away from home", 14, INK, true);
        label(getStarted, "Sign in to your Elegoo account to watch and control the printer through the cloud.", 13, MUTED, false);
        rowButton(row(getStarted), "Sign in with Elegoo…", this::cloudSignIn, false);
        label(getStarted, "3. Just want to print a file", 14, INK, true);
        label(getStarted, "Slice a model on this phone, then send the file to the printer.", 13, MUTED, false);
        rowButton(row(getStarted), "Slice a model…", () -> startActivityForResult(new Intent(this, SliceActivity.class), SLICE), false);
        LinearLayout hero = card(null); heroCard = hero;
        // Live camera on top while printing: the printer's own stream on this network, started by the user (then remembered),
        // and stopped whenever this tab or the app is out of sight. Through the cloud the button opens the cloud camera.
        // Built here, placed under the progress bar and stat tiles below. Hide camera folds it to one Show camera button (remembered).
        monitorCamera = new LinearLayout(this); monitorCamera.setOrientation(LinearLayout.VERTICAL);
        monitorBody = new LinearLayout(this); monitorBody.setOrientation(LinearLayout.VERTICAL); monitorCamera.addView(monitorBody);
        // The picture sits in a frame with a small badge (where it comes from) and two overlay buttons; the frame shows and hides with it.
        monitorFrame = new FrameLayout(this); monitorBody.addView(monitorFrame, new LinearLayout.LayoutParams(-1, dp(200))); monitorFrame.setVisibility(View.GONE);
        monitorImage = new ImageView(this) { @Override public void setVisibility(int visibility) { super.setVisibility(visibility); monitorFrame.setVisibility(visibility); } };
        monitorImage.setContentDescription("Live printer camera"); monitorImage.setScaleType(ImageView.ScaleType.FIT_CENTER); monitorImage.setBackgroundColor(Color.BLACK);
        monitorFrame.addView(monitorImage, new FrameLayout.LayoutParams(-1, -1)); monitorImage.setVisibility(View.GONE);
        monitorBadge = overlayChip(monitorFrame, "● Live", Gravity.TOP | Gravity.START, null); monitorBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout overlayButtons = new LinearLayout(this); overlayButtons.setOrientation(LinearLayout.HORIZONTAL);
        monitorFrame.addView(overlayButtons, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END));
        snapshotChip = overlayChip(overlayButtons, "Snapshot", 0, this::saveSnapshot); snapshotChip.setContentDescription("Save a snapshot of the camera to the phone");
        fullChip = overlayChip(overlayButtons, "Full screen", 0, () -> { if (toolpathPreviewOn()) openLiveToolpath(); else cameraFullScreen(); }); fullChip.setContentDescription("Show the camera full screen");
        // Chosen in Print options: the running print's toolpath, drawn up to the nozzle, in place of the camera.
        toolpathPreview = new LiveToolpathPreview(this, monitorFrame, 1, dark ? 0xff0e1417 : 0xfff2f5f6, dark, this::openLiveToolpath, () -> main.post(this::render));
        monitorCameraInfo = label(monitorBody, "", 12, MUTED, false);
        LinearLayout cameraButtons = row(monitorBody);
        monitorCameraStart = rowButton(cameraButtons, "Watch camera", this::monitorCameraTap, false);
        monitorHide = rowButton(cameraButtons, "Hide camera", () -> hideMonitorCamera(true), false);
        monitorSourceButton = rowButton(row(monitorBody), "Camera: the printer's ▾", this::chooseMonitorSource, false); ((View) monitorSourceButton.getParent()).setVisibility(View.GONE);
        monitorShow = rowButton(row(monitorCamera), "Show camera", () -> hideMonitorCamera(false), false);
        monitorImage.setOnClickListener(v -> cameraFullScreen()); A11y.clickLabel(monitorImage, "Show the camera full screen");
        // A dark chamber: offer the printer's light (sent only on this tap, like Light on below).
        darkRow = new LinearLayout(this); darkRow.setOrientation(LinearLayout.VERTICAL); monitorBody.addView(darkRow); darkRow.setVisibility(View.GONE);
        label(darkRow, "The picture is dark. The printer's light may be off. The phone is brightening the picture for now.", 12, MUTED, false);
        LinearLayout darkButtons = row(darkRow);
        rowButton(darkButtons, "Turn the light on", () -> { darkDismissed = true; darkRow.setVisibility(View.GONE); light(true); }, false);
        rowButton(darkButtons, "Not now", () -> { darkDismissed = true; darkRow.setVisibility(View.GONE); }, false);
        LinearLayout heroRow = new LinearLayout(this); heroRow.setOrientation(LinearLayout.HORIZONTAL); heroRow.setGravity(Gravity.CENTER_VERTICAL); hero.addView(heroRow);
        // The file's own preview (from the printer), beside the job, as on the printer's screen.
        jobThumb = new ImageView(this); jobThumb.setScaleType(ImageView.ScaleType.FIT_CENTER); jobThumb.setContentDescription("Preview of the file being printed");
        GradientDrawable thumbShape = new GradientDrawable(); thumbShape.setColor(TILE); thumbShape.setCornerRadius(dp(12)); jobThumb.setBackground(thumbShape); jobThumb.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams thumbLayout = new LinearLayout.LayoutParams(dp(88), dp(88)); thumbLayout.rightMargin = dp(14); heroRow.addView(jobThumb, thumbLayout); jobThumb.setVisibility(View.GONE);
        LinearLayout heroText = new LinearLayout(this); heroText.setOrientation(LinearLayout.VERTICAL); heroRow.addView(heroText, new LinearLayout.LayoutParams(0, -2, 1));
        state = A11y.polite(label(heroText, "Waiting for printer", 20, INK, true));
        job = label(heroText, "", 14, INK, false); job.setMaxLines(2); job.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // The percentage large, with the finish time on the clock beside it.
        LinearLayout percentRow = new LinearLayout(this); percentRow.setOrientation(LinearLayout.HORIZONTAL); percentRow.setGravity(Gravity.BOTTOM); heroText.addView(percentRow);
        percentText = label(percentRow, "", 34, INK, true); percentText.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        doneText = label(percentRow, "", 14, MUTED, false); doneText.setLayoutParams(new LinearLayout.LayoutParams(-2, -2)); doneText.setGravity(Gravity.END); doneText.setPadding(dp(8), 0, 0, dp(12));
        percentRow.setVisibility(View.GONE);
        detail = label(heroText, "", 13, MUTED, false);
        jobBar = new SegmentBar(this, TEAL, TRACK); // the percentage above says it
        LinearLayout.LayoutParams barLayout = new LinearLayout.LayoutParams(-1, dp(10)); barLayout.topMargin = dp(12); hero.addView(jobBar, barLayout); jobBar.setVisibility(View.GONE);
        // Elapsed, remaining and layer as three small tiles under the bar.
        statsRow = new LinearLayout(this); statsRow.setOrientation(getResources().getConfiguration().fontScale >= 1.5f ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams statsLayout = new LinearLayout.LayoutParams(-1, -2); statsLayout.topMargin = dp(12); hero.addView(statsRow, statsLayout); statsRow.setVisibility(View.GONE);
        statElapsed = stat(statsRow, "Elapsed", 0); statRemaining = stat(statsRow, "Remaining", dp(8)); statLayer = stat(statsRow, "Layer", dp(8));
        // Filament fed so far, and the finish time by what past prints took against the printer's estimate.
        printNotes = label(hero, "", 13, MUTED, false); printNotes.setVisibility(View.GONE);
        // Which G-code this phone holds for the running print: the exact one chosen, one matching by name, or none.
        fileSource = label(hero, "", 12, MUTED, false); fileSource.setVisibility(View.GONE); fileSource.setMinHeight(dp(48)); fileSource.setGravity(Gravity.CENTER_VERTICAL);
        fileSource.setOnClickListener(v -> startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true)));
        A11y.clickLabel(fileSource, "Choose the file in Live toolpath");
        // The running print's newest pictures, newest first; tap one to see it, the last cell opens the recording.
        latestScroll = new android.widget.HorizontalScrollView(this); latestScroll.setHorizontalScrollBarEnabled(false); latestScroll.setVisibility(View.GONE);
        latestStrip = new LinearLayout(this); latestStrip.setOrientation(LinearLayout.HORIZONTAL); latestScroll.addView(latestStrip);
        LinearLayout.LayoutParams latestAt = new LinearLayout.LayoutParams(-1, -2); latestAt.topMargin = dp(8); hero.addView(latestScroll, latestAt);
        LinearLayout.LayoutParams cameraAt = new LinearLayout.LayoutParams(-1, -2); cameraAt.topMargin = dp(12); hero.addView(monitorCamera, cameraAt);
        // Pause / Resume and Stop sit with the job they act on; each still asks first.
        printRow = row(hero); ((LinearLayout.LayoutParams) printRow.getLayoutParams()).topMargin = dp(8);
        pause = rowButton(printRow, "Pause", () -> confirmCommand("Pause the current print?", Cc2Codec.PAUSE), true);
        resume = rowButton(printRow, "Resume", () -> confirmCommand("Resume the print? Check why the printer paused first.", Cc2Codec.RESUME), true);
        stop = rowButton(printRow, "Stop", () -> confirmCommand("Stop the current print? It cannot be resumed.", Cc2Codec.STOP), false); stop.setTextColor(ERROR);
        // The chamber light beside them while a print runs, when the printer reports it: one tap sends one command, as Light on/off below.
        printLight = rowButton(printRow, "Light on", () -> light(!lightIsOn), false); printLight.setVisibility(View.GONE);
        faults = A11y.assertive(label(hero, "", 14, ERROR, true));
        // Quick actions: they only navigate or open a screen, never send a printer command. render() shows the ones that fit the state.
        // Row 1 while printing: Live toolpath and Camera. Idle and connected: Slice a model and Camera. Row 2: Print again (idle, with history) and Print recordings.
        // These rows are holders now: render() keeps their buttons' state, and the Print tab's list rows below mirror them.
        LinearLayout quickHolder = new LinearLayout(this); quickHolder.setOrientation(LinearLayout.VERTICAL); quickHolder.setVisibility(View.GONE); hero.addView(quickHolder);
        quickRow = row(quickHolder);
        liveToolpath = rowButton(quickRow, "Live toolpath", () -> startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true)), true);
        quickSlice = rowButton(quickRow, "Slice a model…", () -> startActivityForResult(new Intent(this, SliceActivity.class), SLICE), true);
        quickCamera = rowButton(quickRow, "Camera", () -> openSection(cameraSection), false);
        // The running print's picture interval on its own row; then Layer alert (printing) or Print again (idle) beside Print recordings.
        // The running print's own settings (pictures, a layer alert, its automations) behind one Print options… button, so the job,
        // camera and pause come first. The option buttons stay as hidden holders: render() keeps their state and the menu lists them.
        printExtrasRow = row(quickHolder);
        printOptions = rowButton(printExtrasRow, "Print options…", this::printOptionsDialog, false);
        printOptions.setContentDescription("Print options: pictures, layer alert and automations for this print");
        LinearLayout optionHolder = new LinearLayout(this); optionHolder.setVisibility(View.GONE); hero.addView(optionHolder);
        picturesNow = rowButton(optionHolder, "Pictures…", () -> pictureDialog(false), false);
        printAutomations = rowButton(optionHolder, "Automations…", this::printAutomationsDialog, false);
        rateRow = row(hero);
        rateButton = rowButton(rateRow, "", () -> { if (rateMeta != null) startActivity(new Intent(this, RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, rateMeta.getAbsolutePath()).putExtra(RecordingsActivity.EXTRA_JOURNAL, true)); }, false);
        rateRow.setVisibility(View.GONE);
        recordingsRow = row(quickHolder);
        layerAlert = rowButton(optionHolder, "Layer alert…", this::layerAlertDialog, false);
        quickAgain = rowButton(recordingsRow, "Print again…", () -> { selectPage(HISTORY); openSection(printerHistorySection); }, false);
        quickAgain.setContentDescription("Print again: opens the History tab, where you can print the latest file again");
        graphs = rowButton(recordingsRow, "Print recordings", () -> {
            PrintRecorder.Recording current = printer == null || printer.recorder == null ? null : printer.recorder.current();
            Intent intent = new Intent(this, RecordingsActivity.class);
            if (current != null) intent.putExtra(RecordingsActivity.EXTRA_META, current.meta.getAbsolutePath());
            startActivity(intent);
        }, false);
        LinearLayout tiles = new LinearLayout(this); tiles.setOrientation(getResources().getConfiguration().fontScale >= 1.5f ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); tilesRow = tiles;
        LinearLayout.LayoutParams tilesLayout = new LinearLayout.LayoutParams(-1, -2); tilesLayout.topMargin = dp(12); currentSection.addView(tiles, tilesLayout);
        tileNozzle = tile(tiles, "Nozzle", 0); tileBed = tile(tiles, "Bed", dp(8)); tileChamber = tile(tiles, "Chamber", dp(8));
        // One quiet list for everything around the job, instead of rows of buttons: each row opens a screen or a menu, never
        // sends a printer command. The Camera section's row joins this list (it becomes the tab's section list).
        LinearLayout nav = new LinearLayout(this); nav.setOrientation(LinearLayout.VERTICAL); nav.setPadding(dp(16), dp(4), dp(16), dp(4));
        GradientDrawable navShape = new GradientDrawable(); navShape.setColor(SURFACE); navShape.setCornerRadius(dp(20)); nav.setBackground(navShape);
        LinearLayout.LayoutParams navLayout = new LinearLayout.LayoutParams(-1, -2); navLayout.topMargin = dp(12); currentSection.addView(nav, navLayout); sectionRows[PRINT] = nav;
        printNav = nav;
        navToolpath = navRow(nav, "Live toolpath", "", () -> liveToolpath.performClick());
        navSlice = navRow(nav, "Prepare a model", "Slice on this phone; no printer needed", () -> quickSlice.performClick());
        navOptions = navRow(nav, "Print options", "", () -> printOptions.performClick());
        navRecording = navRow(nav, "This print's recording", "Graphs, camera pictures and your notes", () -> graphs.performClick());
        navAgain = navRow(nav, "Print again", "", () -> quickAgain.performClick());
        navCanvas = navRow(nav, "CANVAS filament", "", () -> openSection(filamentSection));
        navRefresh = navRow(nav, "Refresh status", "", () -> refresh.performClick());
        // This print so far: its temperatures from the recording, as on the recording's own page (touch to read values).
        telemetryCard = card(null);
        cardHeader(telemetryCard, "This print so far", "All charts ›", () -> graphs.performClick());
        telemetryChart = new ChartView(this, INK, MUTED, TRACK, SURFACE); telemetryCard.addView(telemetryChart, new LinearLayout.LayoutParams(-1, -2));
        telemetryCard.setVisibility(View.GONE);
        // Print guardian (beta): the latest check of a picture against the toolpath. Its buttons only tell the guardian; nothing goes to the printer.
        guardianCard = card("Print guardian (beta)");
        guardianImage = new ImageView(this); guardianImage.setAdjustViewBounds(true); guardianImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        guardianImage.setContentDescription("The latest picture checked: where the print should be in teal, filament-coloured spots off the print in red");
        guardianCard.addView(guardianImage, new LinearLayout.LayoutParams(-1, -2));
        guardianText = A11y.polite(label(guardianCard, "", 13, MUTED, false));
        guardianButtons = row(guardianCard);
        rowButton(guardianButtons, "Looks fine", () -> guardianAnswer(true), false);
        rowButton(guardianButtons, "Real problem", () -> guardianAnswer(false), false);
        guardianCard.setVisibility(View.GONE);
        LinearLayout controls = card("Controls"); controlsCard = controls;
        controlSource = label(controls, "", 13, MUTED, false);
        controlFix = rowButton(row(controls), "", this::fixControl, true); ((View) controlFix.getParent()).setVisibility(View.GONE);
        lightRow = row(controls);
        lightOn = rowButton(lightRow, "Light on", () -> light(true), false);
        lightOff = rowButton(lightRow, "Light off", () -> light(false), false);
        refresh = button(quickHolder, "Refresh status", () -> { if (printer == null) return; if (printer.ready()) printer.refresh(); else printer.cloudVisible(true); });
        LinearLayout canvas = card(null); canvasCard = canvas;
        trayDetails = cardHeader(canvas, "CANVAS filament", "Details ›", () -> { traysOpen = !traysOpen; renderTrayDetails(); });
        // One colour bar per tray, the active one outlined; tapping it opens the full tray list.
        trayStrip = new LinearLayout(this); trayStrip.setOrientation(LinearLayout.VERTICAL); canvas.addView(trayStrip);
        trayList = new LinearLayout(this); trayList.setOrientation(LinearLayout.VERTICAL); canvas.addView(trayList); trayList.setVisibility(View.GONE);
        trays = label(canvas, "Connect to see the trays, materials, colours and the active tray.", 14, MUTED, false);
        refill = button(canvas, "Automatic refill", this::confirmRefill);
        // Printer: what can be done now and Emergency stop on its front page, then one section per kind of control.
        currentSection = fronts[PRINTER];
        LinearLayout status = card("Printer status");
        printerStatus = label(status, "", 13, MUTED, false);
        printerFix = rowButton(row(status), "", this::fixControl, true); ((View) printerFix.getParent()).setVisibility(View.GONE);
        printerAi = label(status, "", 13, MUTED, false); printerAi.setVisibility(View.GONE);
        emergencyButton(status);
        tuningSection = section(PRINTER, "Temperatures, fans & speed", "Heater targets, fans and print speed");
        filamentSection = section(PRINTER, "Filament", "Load or unload filament and CANVAS trays");
        motionSection = section(PRINTER, "Move & calibrate", "Home, move axes, level the bed, run a vibration test and a full self-check");
        reportsSection = section(PRINTER, "Everything the printer reports", "Every value in its latest status, live");
        hybridSection = section(PRINTER, "Local and cloud together", "Your Wi-Fi and the Elegoo cloud: camera, files, LAN Only switching and tests");
        currentSection = tuningSection.body;
        LinearLayout tuning = card(null); tuningCard = tuning;
        tuneReadout = new LinearLayout(this); tuneReadout.setOrientation(LinearLayout.VERTICAL); tuning.addView(tuneReadout);
        boolean stackTiles = getResources().getConfiguration().fontScale >= 1.5f;
        LinearLayout heatRow = new LinearLayout(this); heatRow.setOrientation(stackTiles ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL); tuneReadout.addView(heatRow);
        tuneNozzle = stat(heatRow, "Nozzle", 0); tuneBed = stat(heatRow, "Bed", dp(8));
        LinearLayout fanRow = new LinearLayout(this); fanRow.setOrientation(heatRow.getOrientation());
        LinearLayout.LayoutParams fanRowAt = new LinearLayout.LayoutParams(-1, -2); fanRowAt.topMargin = dp(8); tuneReadout.addView(fanRow, fanRowAt);
        tunePartFan = stat(fanRow, "Part fan", 0); tuneAuxFan = stat(fanRow, "Aux fan", dp(8)); tuneChamberFan = stat(fanRow, "Chamber fan", dp(8));
        tuningHint = label(tuning, "", 13, MUTED, false);
        heater = button(tuning, "Set heater temperatures now…", this::temperatureDialog);
        for (TextView t : new TextView[] {tileNozzle, tileBed}) { View tile = (View) t.getParent(); tile.setOnClickListener(v -> temperatureDialog()); tile.setClickable(false); A11y.clickLabel(tile, "Change heater targets"); }
        fan = button(tuning, "Fan setting…", this::fanDialog);
        speed = button(tuning, "Print speed mode…", this::speedDialog);
        currentSection = filamentSection.body;
        // The CANVAS trays live with the filament controls; the Print tab keeps a one-line summary that leads here.
        ((ViewGroup) canvasCard.getParent()).removeView(canvasCard);
        LinearLayout.LayoutParams canvasLayout = new LinearLayout.LayoutParams(-1, -2); canvasLayout.topMargin = dp(12); currentSection.addView(canvasCard, canvasLayout);
        LinearLayout filament = card(null);
        filamentHint = label(filament, "", 13, MUTED, false);
        LinearLayout filamentGroup = new LinearLayout(this); filamentGroup.setOrientation(LinearLayout.VERTICAL); filament.addView(filamentGroup); filamentButtons = filamentGroup;
        LinearLayout filamentRow = row(filamentGroup);
        loadFilament = rowButton(filamentRow, "Load filament", () -> maintenanceConfirm("Load filament?", "The printer heats the nozzle and feeds filament. This can take a few minutes.", Cc2Codec.FEED), false);
        unloadFilament = rowButton(filamentRow, "Unload filament", () -> maintenanceConfirm("Unload filament?", "The printer heats the nozzle and retracts the filament. This can take a few minutes.", Cc2Codec.RETREAT), false);
        trayFilament = button(filamentGroup, "Load or unload a CANVAS tray…", this::trayDialog);
        emergencyButton(filament);
        // Spools: filament left per tray, estimated on this phone.
        LinearLayout spoolCard = card("Spools");
        label(spoolCard, "About how much filament is left on each spool. Prints started from this app subtract their use when they finish. Tap a tray to set its spool.", 13, MUTED, false);
        spoolList = new LinearLayout(this); spoolList.setOrientation(LinearLayout.VERTICAL); spoolCard.addView(spoolList);
        currentSection = motionSection.body;
        LinearLayout upkeep = card(null); upkeepCard = upkeep;
        upkeepHint = label(upkeep, "", 13, MUTED, false);
        LinearLayout group = new LinearLayout(this); group.setOrientation(LinearLayout.VERTICAL); upkeep.addView(group); upkeepButtons = group;
        LinearLayout motionRow = row(group);
        homeAll = rowButton(motionRow, "Home all axes", () -> confirmRequest("Home all axes?", "The print head and bed move to their home positions. Keep the printer clear.", () -> Cc2Codec.homeRequest(0, "xyz")), false);
        jog = rowButton(motionRow, "Move axes…", this::jogDialog, false);
        LinearLayout calibrationRow = row(group);
        autoLevel = rowButton(calibrationRow, "Auto-level bed", () -> maintenanceConfirm("Level the bed automatically?", "The printer probes the bed. Remove any objects first.", Cc2Codec.AUTO_LEVEL), false);
        vibration = rowButton(calibrationRow, "Vibration test", () -> maintenanceConfirm("Run vibration test?", "The printer shakes the print head and bed to tune motion. Keep the printer clear.", Cc2Codec.VIBRATION), false);
        selfCheck = button(group, "Full self-check…", () -> confirmRequest("Run the full self-check?", "The printer runs a vibration test, a heater (PID) check and bed leveling, as the official app does. This takes several minutes. Keep the printer clear.", () -> Cc2Codec.selfCheckRequest(0)));
        emergencyButton(upkeep);
        buildHybrid(); buildReports();
        currentSection = fronts[FILES];
        // The Files tab by where things are: projects (plans and their files), this phone (models to slice, G-code kept here), then
        // the printer's own storage. What was printed is on History.
        // Its front page is three short rows; each location opens on its own page.
        phoneSection = section(FILES, "On this phone", "Slice a model, choose G-code, find models online");
        printerFilesSection = section(FILES, "On the printer", "The printer's files and storage");
        LinearLayout locations = sectionRows[FILES];
        listRow(locations, "Projects", "Plan builds: parts, files and the prints that made them", true, () -> startActivity(new Intent(this, ProjectsActivity.class)));
        View projectsRow = locations.getChildAt(locations.getChildCount() - 1), projectsHairline = locations.getChildAt(locations.getChildCount() - 2);
        locations.removeView(projectsRow); locations.removeView(projectsHairline); locations.addView(projectsRow, 0); locations.addView(projectsHairline, 1);
        projectsLine = (TextView) ((LinearLayout) ((LinearLayout) projectsRow).getChildAt(0)).getChildAt(1);
        A11y.heading(((LinearLayout) ((LinearLayout) projectsRow).getChildAt(0)).getChildAt(0));
        currentSection = phoneSection.body;
        LinearLayout files = card(null);
        selected = label(files, "Slice a model or pick a .gcode file on this phone. Slicing works without the printer; uploading needs a connection to the printer.", 14, MUTED, false);
        LinearLayout startRow = row(files);
        sliceModel = rowButton(startRow, "Slice a model…", () -> startActivityForResult(new Intent(this, SliceActivity.class), SLICE), true);
        pick = rowButton(startRow, "Choose G-code…", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.setType("*/*"); intent.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(intent, PICK_FILE);
        }, false);
        button(files, "Find models online…", () -> startActivityForResult(new Intent(this, SliceActivity.class).putExtra(SliceActivity.EXTRA_OPEN, "find"), SLICE));
        recentSlices = button(files, "All recent slices…", this::chooseRecentSlice);
        // The newest slices as picture cards (each file's own preview), one tap from choosing them.
        recentGrid = new LinearLayout(this); recentGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridAt = new LinearLayout.LayoutParams(-1, -2); gridAt.topMargin = dp(4); currentSection.addView(recentGrid, gridAt);
        // The chosen file: preview, actions and the offline report, shown once there is one.
        fileDetails = new LinearLayout(this); fileDetails.setOrientation(LinearLayout.VERTICAL); files.addView(fileDetails);
        filePreview = new ImageView(this); filePreview.setContentDescription("Slicer preview of the selected G-code file"); filePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams previewLayout = new LinearLayout.LayoutParams(-1, dp(180)); previewLayout.topMargin = dp(10); fileDetails.addView(filePreview, previewLayout); filePreview.setVisibility(View.GONE);
        previewInfo = label(fileDetails, "", 12, MUTED, false);
        previewWarning = A11y.polite(label(fileDetails, "", 14, ERROR, true)); previewWarning.setVisibility(View.GONE);
        addLoad = button(fileDetails, "Add the filament load…", this::addLoadDialog); addLoad.setVisibility(View.GONE);
        LinearLayout uploadRow = row(fileDetails);
        upload = rowButton(uploadRow, "Upload", () -> {
            if (printer == null || printer.selectedName == null) return;
            String warning = printer.selectedReport == null ? "" : printer.selectedReport.compatibilityWarning();
            new AlertDialog.Builder(this).setTitle("Upload " + printer.selectedName + "?")
                .setMessage((warning.isEmpty() ? "" : "Warning: " + warning + "\n\n") + "This only sends the file. " + (printer.nameOnPrinter(printer.selectedName) ? "A file with this name is already on the printer, so uploading replaces it. " : "Any file with the same name is replaced. ") + "Refresh the file list after uploading, then choose Print setup to start it.")
                .setNegativeButton("Cancel", null).setPositiveButton("Upload", (dialog, which) -> { if (printer != null) printer.upload(); }).show();
        }, true);
        LinearLayout viewRow = row(fileDetails);
        previewToolpath = rowButton(viewRow, "Preview toolpath", () -> {
            if (printer == null || printer.selectedFile == null) return;
            startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FILE, printer.selectedFile.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, printer.selectedName));
        }, false);
        layerCharts = rowButton(viewRow, "Layer charts", () -> {
            if (printer == null || printer.selectedFile == null) return;
            startActivity(new Intent(this, LayerChartsActivity.class).putExtra(LayerChartsActivity.EXTRA_FILE, printer.selectedFile.getAbsolutePath()).putExtra(LayerChartsActivity.EXTRA_NAME, printer.selectedName));
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
            label(body, "These numbers are positions in the file's comment list, not confirmed tool or CANVAS tray IDs. The app does not map them to trays.", 13, MUTED, false);
            ScrollView detail = new ScrollView(this); detail.addView(body); new AlertDialog.Builder(this).setTitle("Sliced material evidence").setView(detail).setPositiveButton("Close",null).show();
        }, false);
        inspection = label(fileDetails, "", 13, INK, false); inspection.setTextIsSelectable(true);
        LinearLayout keepRow = row(fileDetails);
        shareInspection = rowButton(keepRow, "Share report…", () -> {
            if (printer == null || printer.selectedReport == null) return;
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, StatusPresentation.clean(printer.selectedName) + "\n" + printer.selectedReport.text());
            startActivity(Intent.createChooser(send, "Share G-code report"));
        }, false);
        saveCopy = rowButton(keepRow, "Save G-code to phone…", this::saveSelected, false);
        clearCopy = rowButton(keepRow, "Remove from this phone", () -> { if (printer != null) printer.clearPhoneCopy(); }, false);
        transferRow = row(files);
        cancelUpload = rowButton(transferRow, "Cancel upload", () -> { if (printer != null) printer.cancelUpload(); }, false);
        cancelDownload = rowButton(transferRow, "Cancel download", () -> { if (printer != null) printer.cancelDownload(); }, false);
        label(fileDetails, "Uploads and downloads need the local connection (the printer's HTTP port 80). Downloads are kept in this app. Save G-code to phone… makes a copy you can keep. The report reads only the slicer's comments and does not simulate a print.", 12, MUTED, false);
        currentSection = printerFilesSection.body;
        buildFileBrowser();
        currentSection = fronts[HISTORY]; buildHistory();
        cameraSection = section(PRINT, "Camera tools", "Snapshots, other cameras, line-up in 3D");
        currentSection = cameraSection.body; buildCamera();
        currentSection = connectionSection.body;
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
        label(account, "Your password is sent only to Elegoo's own sign-in page. Without a local connection, the Print tab shows the cloud's last update.", 13, MUTED, false);
        renderCloudAccount();
        setSettingsMode(settingsModeAtStart());
        currentSection = appearanceSection.body;
        button(card(null), "Appearance: " + (settings.getInt("theme", 0) == 0 ? "Follow system" : dark ? "Dark" : "Light") + "…", this::appearanceDialog);
        currentSection = preferencesSection.body;
        LinearLayout preferences = card("Recordings and pictures");
        CompoundButton record = checkbox(preferences, "Make print recordings", settings.getBoolean("recordPrints", true));
        record.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("recordPrints", enabled).apply());
        Button pictures = button(preferences, "", () -> pictureDialog(true));
        pictureDefault = pictures; showPictureDefault();
        CompoundButton autoVideo = checkbox(preferences, "Make the timelapse video when a print ends", settings.getBoolean("autoTimelapse", true));
        autoVideo.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("autoTimelapse", enabled).apply());
        button(preferences, "Storage…", () -> startActivity(new Intent(this, StorageActivity.class)));
        CompoundButton videoWhere = checkbox(preferences, "Show the head's position on timelapse videos", settings.getBoolean("timelapsePosition", true));
        videoWhere.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("timelapsePosition", enabled).apply());
        CompoundButton videoPath = checkbox(preferences, "Draw the toolpath on timelapse videos", settings.getBoolean("timelapseToolpath", true));
        videoPath.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("timelapseToolpath", enabled).apply());
        label(preferences, "The toolpath is drawn when the print's G-code is kept with its recording and the printer's camera is lined up in Live toolpath (More…, Line up the camera by hand).", 12, MUTED, false);
        CompoundButton visits = checkbox(preferences, "Take print pictures through the cloud", settings.getBoolean(PictureVisits.PREF, true));
        visits.setOnCheckedChangeListener((view, enabled) -> { settings.edit().putBoolean(PictureVisits.PREF, enabled).apply(); if (!enabled) endVisit(); });
        label(preferences, "Through the cloud, each picture opens the printer's video for a few seconds: a little mobile data each time. They keep coming with the app closed while it watches through the cloud in the background (Settings › Printer connection).", 12, MUTED, false);
        LinearLayout notices = card("Notifications and watching");
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            CompoundButton live = checkbox(notices, "Live progress in the status bar while printing", settings.getBoolean("liveProgress", true));
            live.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("liveProgress", enabled).apply());
        }
        CompoundButton alerts = checkbox(notices, "Completion and new fault notifications", settings.getBoolean("alerts", true));
        alerts.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("alerts", enabled).apply());
        CompoundButton firstLayer = checkbox(notices, "Also when the first layer is done", settings.getBoolean("alertFirstLayer", false));
        firstLayer.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("alertFirstLayer", enabled).apply());
        CompoundButton watchBox = checkbox(notices, "Print watch: a print seems stuck or a heater drifts", settings.getBoolean("printWatch", true));
        watchBox.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("printWatch", enabled).apply());
        CompoundButton guardianBox = checkbox(notices, "Print guardian (beta): pictures don't match the toolpath", settings.getBoolean("guardian", true));
        guardianBox.setOnCheckedChangeListener((view, enabled) -> { settings.edit().putBoolean("guardian", enabled).apply(); Diagnostics.note(Diagnostics.GUARDIAN, enabled ? "turned on" : "turned off"); });
        CompoundButton alertPictures = checkbox(notices, "Add camera pictures to notifications", settings.getBoolean("alertPictures", true));
        alertPictures.setOnCheckedChangeListener((view, enabled) -> settings.edit().putBoolean("alertPictures", enabled).apply());
        label(notices, "Only notifications: nothing here pauses or stops a print.", 12, MUTED, false);
        listRow(notices, "How these work", "Permissions, the guardian's needs, layer alerts", true, () -> new AlertDialog.Builder(this).setTitle("Notifications and watching")
            .setMessage("Alerts need notification permission and the app watching the printer; they stop when you disconnect or Android stops the app. Tapping one only opens the app.\n\n"
                + "The guardian compares camera pictures with the print's toolpath. It needs the print's G-code on this phone and the print pictures, and works best with the camera lined up in Live toolpath. It never pauses or stops a print.\n\n"
                + "While a print runs, Print options on the Print tab set its own picture interval and a one-off layer alert.\n\n"
                + "In cloud mode, while the app is open, a due picture opens the printer's video for a few seconds, with the app open or closed (closed: while it watches in the background). An open camera view takes pictures too.")
            .setPositiveButton("Close", null).show());
        currentSection = storageSection.body;
        LinearLayout storage = card(null);
        button(storage, "Back up settings…", this::backupDialog);
        button(storage, "Restore from a backup…", () -> {
            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), BACKUP_OPEN); }
            catch (Exception none) { message("This phone has no file picker."); }
        });
        label(storage, "A backup holds your settings, saved printers, automation rules, added cameras and their line-ups, spools and projects, with the projects' files and recordings if you choose. Passwords, access codes and camera addresses go in only with a backup password. The Elegoo sign-in is never included.", 12, MUTED, false);
        currentSection = haSection.body;
        LinearLayout rules = card("Automations");
        button(rules, "Automation workshop…", () -> startActivity(new Intent(this, AutomationActivity.class)));
        label(rules, "Rules for print events: notifications, webhooks, Home Assistant, other apps, and the printer's light (for example, off after the last print of the day). Templates can be shared.", 12, MUTED, false);
        buildHomeAssistant();
        currentSection = helpSection.body;
        LinearLayout help = card(null);
        button(help, "Connection help…", this::connectionHelp);
        probeButton = button(help, "Probe printer (read-only)…", this::probeDialog);
        // The probe result scrolls inside a fixed-height area, so a long answer does not push the rest of the page down.
        probeResult = A11y.polite(new TextView(this)); probeResult.setTextSize(13); probeResult.setTextColor(MUTED); probeResult.setPadding(0, dp(5), 0, dp(7)); probeResult.setTextIsSelectable(true);
        ScrollView probeArea = new ScrollView(this); probeArea.addView(probeResult);
        help.addView(probeArea, new LinearLayout.LayoutParams(-1, dp(200))); probeArea.setVisibility(View.GONE);
        button(help, "Field check…", () -> startActivity(new Intent(this, FieldCheckActivity.class)));
        button(help, "Printer companion (not set up)…", this::companionDialog);
        button(help, "View logs…", () -> startActivity(new Intent(this, LogsActivity.class)));
        button(help, "Share diagnostics…", this::shareDiagnostics);
        currentSection = aboutSection.body;
        LinearLayout about = card("Link Workshop " + appVersion());
        label(about, "This is a development build. Printer behaviour still needs testing on real hardware. Field check (in Help & diagnostics) walks you through it. Planned next: painting tools in the slicer and more printer models.", 13, MUTED, false);
        button(about, "About & licenses", this::showLicenses);
        updateText = label(about, "", 13, MUTED, false); updateText.setVisibility(View.GONE);
        button(about, "Check for updates", () -> checkForUpdate(true));
        CompoundButton autoUpdate = checkbox(about, "Check for updates once a day (reads the project's public release list on GitHub; nothing about you or the printer is sent)", settings.getBoolean(UpdateCheck.PREF_AUTO, true));
        autoUpdate.setOnCheckedChangeListener((view, on) -> settings.edit().putBoolean(UpdateCheck.PREF_AUTO, on).apply());
        currentSection = null;
        if (saved != null) { host.setText(saved.getString("host", host.getText().toString())); serial.setText(saved.getString("serial", "")); diagnostics.setText(saved.getString("diagnostics", diagnostics.getText().toString())); profileName.setText(saved.getString("profileName", profileName.getText().toString())); }
        // The saved access code is decrypted off the main thread (the key store reads the disk) and filled in when ready,
        // unless the field was filled meanwhile (restored input, or typed).
        String savedFor = host.getText().toString().trim();
        remember.setChecked(saved == null ? credentials.remembers(savedFor) : saved.getBoolean("remember", false));
        checks.execute(() -> {
            String code = null; boolean unreadable = false;
            try { code = credentials.load(savedFor); } catch (Exception error) { unreadable = true; }
            String found = code; boolean failed = unreadable;
            main.post(() -> {
                if (isDestroyed() || !savedFor.equals(host.getText().toString().trim())) return;
                if (failed) { credentials.forget(savedFor); remember.setChecked(false); message("Saved code could not be decrypted. Enter it again before connecting."); return; }
                if (found != null && !found.isEmpty() && access.getText().length() == 0) access.setText(found);
            });
        });
        TransientInputs transientInputs = (TransientInputs) getLastNonConfigurationInstance();
        if (transientInputs != null && host.getText().toString().equals(transientInputs.host)) { access.setText(transientInputs.code); pendingSnapshot = transientInputs.snapshot; }
        selectPage(saved == null ? savedTab() : saved.getInt("tab", PRINT));
        if (saved == null) { receiveSliced(getIntent()); receiveShared(getIntent()); }
        openRequestedPage(getIntent());
        bound = bindService(new Intent(this, PrinterService.class), binding, BIND_AUTO_CREATE);
        render();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); receiveSliced(intent); receiveShared(intent); openRequestedPage(intent); }
    /** A .gcode file shared or opened from another app: taken in as if chosen on the Files tab, and kept for the live toolpath. */
    private android.net.Uri pendingShared;
    private void receiveShared(Intent intent) {
        if (intent == null) return;
        android.net.Uri uri = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) uri = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri.class) : intent.getParcelableExtra(Intent.EXTRA_STREAM);
        else if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        if (uri == null || !"content".equals(uri.getScheme())) return;
        intent.setAction(Intent.ACTION_MAIN);   // taken once, not again on recreation
        pendingShared = uri; selectPage(FILES); openSection(phoneSection); takeShared();
    }
    private void takeShared() {
        if (pendingShared == null || printer == null) return;
        android.net.Uri uri = pendingShared; pendingShared = null; printer.select(uri);
    }

    /** The tab open last time; from before the History and Printer tabs, the old tab's new place (Camera is on Print now). */
    private int savedTab() {
        if (settings.contains("tab")) return settings.getInt("tab", PRINT);
        int[] moved = {PRINT, FILES, PRINT, SETTINGS};
        int old = settings.getInt("page", 0); return old >= 0 && old < moved.length ? moved[old] : PRINT;
    }
    /** G-code from a Slice screen that another app opened ("Open with"), handed over like a slice started from Files. */
    private void receiveSliced(Intent intent) {
        if (intent == null) return;
        String setup = intent.getStringExtra(SliceActivity.RESULT_PRINT_SETUP);
        if (setup != null) { awaitPrintSetup(setup); intent.removeExtra(SliceActivity.RESULT_PRINT_SETUP); }
        String path = intent.getStringExtra(SliceActivity.RESULT_FILE), name = intent.getStringExtra(SliceActivity.RESULT_NAME);
        if (path == null || name == null) return;
        // Only the app's own sliced output is accepted: kept slices, or the cache when keeping one failed.
        if (!SliceStore.ownOutput(this, path, name)) {
            Diagnostics.note(Diagnostics.FILES, "a sliced file handed over was not taken: not one of this app's slices");
            android.widget.Toast.makeText(this, "That sliced file could not be taken in. Find it under Recent slices on the Files tab.", android.widget.Toast.LENGTH_LONG).show();
            intent.removeExtra(SliceActivity.RESULT_FILE); return;
        }
        pendingSlicedFile = path; pendingSlicedName = name; intent.removeExtra(SliceActivity.RESULT_FILE);
        settings.edit().putBoolean("everSliced", true).apply();
        selectPage(FILES); openSection(phoneSection); takeSliced();
    }
    /** After "Upload and print" on the Slice screen: open Print setup once the upload is in the printer's file list. */
    private static final long PRINT_SETUP_WAIT_MS = 5 * 60_000L;
    private long printSetupStarted;
    private void awaitPrintSetup(String name) {
        pendingPrintSetup = name; printSetupStarted = System.currentTimeMillis(); printSetupUntil = printSetupStarted + PRINT_SETUP_WAIT_MS; selectPage(FILES); openSection(printerFilesSection);
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
    private LinearLayout recentGrid; private String recentShown = "";
    /** Previews of slices, read once per file (path and time) off the main thread. */
    private final java.util.Map<String, android.graphics.Bitmap> slicePictures = new java.util.HashMap<>();
    /** Up to four newest slices as cards: preview, name and age; a tap chooses the file, as Recent slices does. */
    private void refreshRecentGrid() {
        if (recentGrid == null) return;
        List<File> recent = SliceStore.list(this);
        StringBuilder key = new StringBuilder(); for (int i = 0; i < Math.min(4, recent.size()); i++) key.append(recent.get(i).getPath()).append(recent.get(i).lastModified()).append(';');
        if (key.toString().equals(recentShown)) return;
        recentShown = key.toString(); recentGrid.removeAllViews();
        if (recent.isEmpty()) return;
        A11y.heading(label(recentGrid, "Recent slices", 17, INK, true)).setPadding(dp(4), dp(12), 0, 0);
        for (int i = 0; i < Math.min(4, recent.size()); i++) {
            File file = recent.get(i);
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable shape = new GradientDrawable(); shape.setColor(SURFACE); shape.setCornerRadius(dp(20));
            card.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null)); card.setClipToOutline(true);
            ImageView picture = new ImageView(this); picture.setScaleType(ImageView.ScaleType.FIT_CENTER); picture.setBackgroundColor(TILE); picture.setPadding(dp(8), dp(8), dp(8), dp(8));
            picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); card.addView(picture, new LinearLayout.LayoutParams(-1, dp(110)));
            String pictureKey = file.getPath() + file.lastModified();
            android.graphics.Bitmap known = slicePictures.get(pictureKey);
            if (known != null) picture.setImageBitmap(known);
            else checks.execute(() -> {
                EmbeddedThumbnail thumbnail = EmbeddedThumbnail.fromFile(file);
                android.graphics.Bitmap bitmap = thumbnail == null ? null : ThumbnailDecoder.decode(thumbnail);
                if (bitmap != null) main.post(() -> { slicePictures.put(pictureKey, bitmap); picture.setImageBitmap(bitmap); });
            });
            LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL); words.setPadding(dp(12), dp(6), dp(12), dp(10)); card.addView(words);
            TextView name = label(words, file.getName().replaceFirst("(?i)\\.gcode$", ""), 14, INK, true); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label(words, android.text.format.DateUtils.getRelativeTimeSpanString(file.lastModified()) + " · " + Math.max(1, file.length() / 1024) + " KB", 12, MUTED, false);
            card.setClickable(true); card.setFocusable(true); card.setContentDescription(name.getText() + ", sliced " + android.text.format.DateUtils.getRelativeTimeSpanString(file.lastModified()));
            A11y.clickLabel(card, "Choose this file");
            card.setOnClickListener(v -> { if (printer != null && !printer.fileBusy()) printer.selectSliced(file, file.getName()); mainScroll.smoothScrollTo(0, 0); });
            WorkshopUi.addToGrid(recentGrid, card, dp(12));
        }
    }
    private void chooseRecentSlice() {
        List<File> recent = SliceStore.list(this);
        if (printer == null || recent.isEmpty()) { message("No recent slices yet."); return; }
        String[] titles = new String[recent.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = recent.get(i).getName() + "\n" + (recent.get(i).length() / 1024) + " KB · " + android.text.format.DateUtils.getRelativeTimeSpanString(recent.get(i).lastModified());
        new AlertDialog.Builder(this).setTitle("Recent slices").setItems(titles, (d, which) -> { if (printer != null) printer.selectSliced(recent.get(which), recent.get(which).getName()); })
            .setNeutralButton("Add one to a project…", (d, w) -> {
                String[] names = new String[recent.size()]; for (int i = 0; i < names.length; i++) names[i] = recent.get(i).getName();
                new AlertDialog.Builder(this).setTitle("Add to a project").setItems(names, (d2, which) -> startActivity(new Intent(this, ProjectsActivity.class)
                    .putExtra(ProjectsActivity.EXTRA_ADD_PATH, recent.get(which).getAbsolutePath()).putExtra(ProjectsActivity.EXTRA_ADD_NAME, recent.get(which).getName()))).setNegativeButton("Cancel", null).show();
            })
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
        return "Last report " + CloudStatusActivity.age(System.currentTimeMillis() - at) + " ago";
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
            .setMessage("This changes the CANVAS automatic refill setting. The printer still controls tray selection and filament loading.")
            .setNegativeButton("Cancel", null).setPositiveButton(enabled ? "Enable" : "Disable", (dialog, which) -> { if (printer != null) printer.autoRefill(enabled); }).show();
    }
    /** Opens the tab another screen asked for (EXTRA_PAGE), once. */
    private void openRequestedPage(Intent intent) {
        if (intent != null && intent.hasExtra(EXTRA_PAGE)) {
            int tab = intent.getIntExtra(EXTRA_PAGE, PRINT); intent.removeExtra(EXTRA_PAGE);
            if (tab == SETTINGS) openSettings(null); else selectPage(tab);
        }
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
            case "projects": startActivity(new Intent(this, ProjectsActivity.class)); return;
            default: break;
        }
        selectPage(f.tab);
        prepareFeature(f);
        render();
        main.post(() -> {
            View target = reveal(f.tab, f.target);
            if (target == null || !target.isShown()) {
                // Hidden in this state (printer controls while it prints, or not connected): show the card that explains when.
                View card = f.fallback.isEmpty() ? null : reveal(f.tab, f.fallback);
                if (card != null && card.isShown()) {
                    main.post(() -> { scrollTo(card); highlight(card); });
                    message(f.title + " appears here when the printer is connected and idle.");
                } else message("toolpath".equals(f.id) ? "Live toolpath appears on the Print tab while a print is running."
                    : f.title + " needs a connected printer" + (f.tab == PRINTER ? " that is idle" : "") + ". Connect in Settings first.");
                return;
            }
            // Opening a section changes the layout: scroll once it is laid out.
            main.post(() -> {
                scrollTo(target); highlight(target);
                if (f.press && target.isEnabled() && target instanceof Button) target.performClick();
                else if (!f.press && target instanceof Button && !target.isEnabled()) message(f.title + " is here, but not available right now (it needs a connected, idle printer).");
            });
        });
    }
    /** The Settings mode or option a feature lives behind; its section is opened by reveal(). */
    private void prepareFeature(FeatureIndex.Feature f) {
        switch (f.prepare) {
            case SETTINGS_LOCAL: setSettingsMode(false); break;
            case SETTINGS_CLOUD: setSettingsMode(true); break;
            case ADVANCED_CONNECTION: setSettingsMode(false); setMore(true); break;
            default: break;
        }
    }
    /**
     * The visible view on a tab whose text starts with `text`, opening the section that holds it first (sections before the
     * front page, so a section's own heading wins over the tile that leads to it). Null when nothing visible matches.
     */
    private View reveal(int tab, String text) {
        for (Section section : sections) {
            if (section.tab != tab) continue;
            View found = null;
            for (int i = 0; i < section.body.getChildCount() && found == null; i++) found = findByText(section.body.getChildAt(i), text);
            if (found != null) { openSection(section); return found; }
        }
        closeSection(tab);
        return findByText(pages[tab], text);
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
    // ------------------------------------------------------------------ Tabs, their front pages and sections
    /**
     * A part of a tab that opens in place of the tab's front page, from a row on it: the tab stays short and says where things
     * are, and Back (the bar on top, the system back, or the tab again) returns to the front page.
     */
    private static final class Section {
        final int tab; final String title; final LinearLayout body; TextView detail;
        Section(int tab, String title, LinearLayout body) { this.tab = tab; this.title = title; this.body = body; }
    }
    private final List<Section> sections = new ArrayList<>();
    private final Section[] opened = new Section[TAB_COUNT];
    private final LinearLayout[] fronts = new LinearLayout[TAB_COUNT], sectionRows = new LinearLayout[TAB_COUNT];
    private final Button[] backBars = new Button[TAB_COUNT];
    private Section appearanceSection, storageSection, phoneSection, printerFilesSection, printerHistorySection, statsSection;
    private Section connectionSection, preferencesSection, helpSection, aboutSection, cameraSection, tuningSection, filamentSection, motionSection, hybridSection;
    private Object backCallback;
    /** A tab: the back bar (shown in a section), then the front page; sections are added after it. */
    private void buildHub(int tab) {
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); content.addView(page); pages[tab] = page;
        // In a section: an arrow and the section's title, as one bar (set when it opens); it goes back to the tab's front page.
        Button back = new Button(this); back.setText(FeatureIndex.TABS[tab]); back.setAllCaps(false); back.setTextSize(22); back.setTextColor(INK); back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setBackground(null); WorkshopUi.backArrow(back, INK); back.setStateListAnimator(null);
        back.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); back.setMinHeight(dp(56)); back.setMinimumHeight(dp(56)); back.setPadding(dp(4), 0, dp(12), 0);
        back.setContentDescription("Back to " + FeatureIndex.TABS[tab]); back.setOnClickListener(v -> closeSection(tab)); back.setVisibility(View.GONE);
        LinearLayout.LayoutParams backLayout = new LinearLayout.LayoutParams(-2, -2); backLayout.topMargin = dp(8); page.addView(back, backLayout); backBars[tab] = back;
        LinearLayout front = new LinearLayout(this); front.setOrientation(LinearLayout.VERTICAL); page.addView(front); fronts[tab] = front;
    }
    /**
     * A section of `tab` with its heading, and its row on the front page (in a list started where the first section is added,
     * so a tab can put cards above it). Build its content with currentSection = the section's body.
     */
    private Section section(int tab, String title, String detail) {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setVisibility(View.GONE); pages[tab].addView(body);
        TextView heading = label(body, title, 22, INK, true); A11y.heading(heading); heading.setPadding(dp(4), 0, 0, 0);
        heading.setVisibility(View.GONE);   // the back bar carries the title
        Section section = new Section(tab, title, body); sections.add(section);
        if (sectionRows[tab] == null) {
            LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(16), dp(4), dp(16), dp(4));
            GradientDrawable shape = new GradientDrawable(); shape.setColor(SURFACE); shape.setCornerRadius(dp(20)); list.setBackground(shape);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); fronts[tab].addView(list, layout); sectionRows[tab] = list;
        }
        LinearLayout list = sectionRows[tab];
        listRow(list, title, detail, list.getChildCount() > 0, () -> openSection(section));
        LinearLayout row = (LinearLayout) list.getChildAt(list.getChildCount() - 1);
        section.detail = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
        A11y.heading(((LinearLayout) row.getChildAt(0)).getChildAt(0));
        return section;
    }
    private void openSection(Section section) {
        if (section == null) return;
        int tab = section.tab; opened[tab] = section;
        if (section == phoneSection) refreshRecentGrid();
        fronts[tab].setVisibility(View.GONE); backBars[tab].setVisibility(View.VISIBLE); backBars[tab].setText(section.title); A11y.heading(backBars[tab]);
        for (Section other : sections) if (other.tab == tab) other.body.setVisibility(other == section ? View.VISIBLE : View.GONE);
        if (tab == page) { mainScroll.scrollTo(0, 0); main.post(() -> backBars[tab].sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)); }
        watchBack(); render();
    }
    private void closeSection(int tab) {
        Section was = opened[tab]; if (was == null) return;
        opened[tab] = null; was.body.setVisibility(View.GONE); backBars[tab].setVisibility(View.GONE); fronts[tab].setVisibility(View.VISIBLE);
        if (tab == page) mainScroll.scrollTo(0, 0);
        watchBack(); render();
    }
    /** Settings, on its connection section (local or cloud when given): where every "connect first" hint leads. */
    private void openSettings(Boolean cloud) { selectPage(SETTINGS); if (cloud != null) setSettingsMode(cloud); openSection(connectionSection); }
    /** System back closes an open section first (Android 13+ asks for this up front; older versions call onBackPressed). */
    private void watchBack() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean wanted = opened[page] != null;
        android.window.OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (wanted && backCallback == null) {
            android.window.OnBackInvokedCallback callback = () -> closeSection(page);
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback); backCallback = callback;
        } else if (!wanted && backCallback != null) { dispatcher.unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback); backCallback = null; }
    }
    /** Android 8–12 only: from 13 on, back (gestures included) goes to the callback watchBack() registers. */
    @SuppressWarnings("deprecation") @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { if (opened[page] != null) closeSection(page); else super.onBackPressed(); }
    private void selectPage(int selected) {
        int before = page; page = Math.max(0, Math.min(TAB_COUNT - 1, selected));
        // Leaving a tab returns it to its front page; tapping the tab you are on does the same.
        if (before != page && pages[before] != null) closeSection(before); else if (before == page) closeSection(page);
        // The Print tab's camera (on top, and in its Camera section) stops whenever the tab changes, and on any other tab.
        if (page != before || page != PRINT) stopCamera();
        if (page == PRINT && before != PRINT) monitorCameraTried = false;
        if (page == FILES) { filesAutoAt = 0; main.post(this::autoLoadFiles); refreshProjectsLine(); }
        if (page == PRINTER && printer != null) printer.readAiDetection();
        if (page == PRINT) { traysAskedAt = 0; main.post(this::autoLoadTrays); }
        if (page == HISTORY) { historyAutoAt = 0; main.post(this::autoLoadHistory); refreshStats(); }
        for (int i = 0; i < TAB_COUNT; i++) {
            pages[i].setVisibility(i == page ? View.VISIBLE : View.GONE);
            int color = i == page ? TEAL : MUTED;
            ((ImageView) tabs[i].getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
            TextView name = (TextView) tabs[i].getChildAt(1); name.setTextColor(color); name.setTypeface(Typeface.DEFAULT, i == page ? Typeface.BOLD : Typeface.NORMAL);
            A11y.tab(tabs[i], i == page);
        }
        watchBack();
        settings.edit().putInt("tab", page).apply();
        // A tab shown blank on a phone (even its fixed cards) means its views were never laid out: force a fresh layout now and
        // once more after this frame, and note what was stuck (sizes and flags only).
        View shown = pages[page]; int tab = page;
        String before_ = layoutState(shown); unstick(shown);
        main.post(() -> unstick(shown));
        main.postDelayed(() -> { if (page == tab) Diagnostics.note(Diagnostics.FILES, "tab " + tab + " shown: before " + before_ + "; now " + layoutState(shown)); }, 1200);
    }
    /** Marks a view, everything inside it and every parent as needing layout, then asks the window for a layout pass. */
    private void unstick(View view) {
        forceAll(view);
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) ((View) parent).forceLayout();
        view.getRootView().requestLayout(); view.invalidate();
    }
    private static void forceAll(View view) {
        view.forceLayout();
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) forceAll(((ViewGroup) view).getChildAt(i));
    }
    /** "1080x2400, 3 of 3 children sized, waiting: page,content" — sizes and which views still wait for layout. */
    private static String layoutState(View view) {
        int sized = 0, count = 0;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { View child = ((ViewGroup) view).getChildAt(i); if (child.getVisibility() == View.GONE) continue; count++; if (child.getHeight() > 0) sized++; }
        StringBuilder waiting = new StringBuilder(); if (view.isLayoutRequested()) waiting.append("page");
        int depth = 0;
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent(), depth++) if (((View) parent).isLayoutRequested()) waiting.append(waiting.length() > 0 ? "," : "").append("parent").append(depth + 1);
        return view.getWidth() + "x" + view.getHeight() + ", " + sized + " of " + count + " cards sized" + (waiting.length() > 0 ? ", waiting: " + waiting : "");
    }
    private void fixControl() {
        if (block == ControlState.Block.CLOUD_AGREEMENT) cloudGate(true, this::render); else if (block == ControlState.Block.DISCONNECTED) openSettings(null);
    }
    /** The Print tab's list: the rows that fit now, mirroring the buttons render() keeps (navigation only). */
    private void renderPrintNav() {
        showNav(navToolpath, liveToolpath.getVisibility() == View.VISIBLE, null);
        showNav(navSlice, quickSlice.getVisibility() == View.VISIBLE || heroCard.getVisibility() != View.VISIBLE, null);
        List<String> options = new ArrayList<>();
        if (picturesNow.getVisibility() == View.VISIBLE) options.add(picturesNow.getText().toString().replace("…", ""));
        if (layerAlert.getVisibility() == View.VISIBLE) options.add(layerAlert.getText().toString().replace("…", ""));
        if (printAutomations.getVisibility() == View.VISIBLE) options.add("automations");
        showNav(navOptions, printOptions.getVisibility() == View.VISIBLE, String.join(" · ", options));
        boolean printing = liveToolpath.getVisibility() == View.VISIBLE;
        String recordingTitle = printing ? "This print's recording" : "Print recordings";
        if (!recordingTitle.contentEquals(navRecording.title.getText())) navRecording.title.setText(recordingTitle);
        showNav(navRecording, true, "");
        showNav(navAgain, quickAgain.getVisibility() == View.VISIBLE, null);
        JSONObject canvasNow = printer == null ? null : printer.canvas;
        showNav(navCanvas, canvasCard.getVisibility() == View.VISIBLE && canvasNow != null, StatusPresentation.canvasLine(canvasNow));
        showNav(navRefresh, refresh.getVisibility() == View.VISIBLE, null);
        tidyNav(printNav);
    }
    /** A list row that can come and go: its title, a detail line, a chevron, and a hairline above it unless it is the first shown. */
    private static final class NavRow { View wrap, divider; TextView title, detail; }
    private NavRow navRow(LinearLayout list, String title, String detail, Runnable action) {
        NavRow r = new NavRow();
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL); list.addView(wrap, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(this); line.setBackgroundColor(TRACK); wrap.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        listRow(wrap, title, detail, false, action);
        LinearLayout row = (LinearLayout) wrap.getChildAt(1);
        r.wrap = wrap; r.divider = line; r.title = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(0); r.detail = (TextView) ((LinearLayout) row.getChildAt(0)).getChildAt(1);
        A11y.heading(r.title);
        return r;
    }
    private void showNav(NavRow r, boolean shown, String detail) {
        r.wrap.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (detail != null && !detail.contentEquals(r.detail.getText())) r.detail.setText(detail);
        r.detail.setVisibility(r.detail.getText().length() == 0 ? View.GONE : View.VISIBLE);
        ((View) r.title.getParent().getParent()).setContentDescription(r.title.getText() + (r.detail.getText().length() == 0 ? "" : ", " + r.detail.getText()));
    }
    /** Hairlines only between rows that show. */
    private void tidyNav(LinearLayout list) {
        boolean first = true;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child.getClass() == View.class) { child.setVisibility(first ? View.GONE : View.VISIBLE); continue; }   // a section row's own hairline
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof LinearLayout && ((LinearLayout) child).getChildCount() == 2 && ((LinearLayout) child).getChildAt(0).getClass() == View.class) ((LinearLayout) child).getChildAt(0).setVisibility(first ? View.GONE : View.VISIBLE);
            first = false;
        }
    }
    /** One compact tappable row for a printer file: name, size and layers beneath, a chevron, and a hairline above all but the first. */
    private void fileRow(String name, String detail, boolean divider, Runnable action) { listRow(fileRows, name, detail, divider, action); }
    /** A compact list row (name, muted detail line); tappable with a chevron when there is an action, plain otherwise. */
    private TextView listRow(LinearLayout list, String name, String detail, boolean divider, Runnable action) {
        if (divider) { View line = new View(this); line.setBackgroundColor(TRACK); list.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)))); }
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(10), 0, dp(10)); row.setMinimumHeight(dp(56));
        if (action != null) row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), null, new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        LinearLayout text = new LinearLayout(this); text.setOrientation(LinearLayout.VERTICAL); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView first = new TextView(this); first.setText(name); first.setTextSize(15); first.setTextColor(INK); // One line cut in the middle (the extension stays); with large text, two lines so short names still show whole.
        if (getResources().getConfiguration().fontScale >= 1.3f) { first.setMaxLines(2); first.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        else { first.setSingleLine(true); first.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); }
        text.addView(first);
        TextView second = new TextView(this); second.setText(detail); second.setTextSize(12); second.setTextColor(MUTED); text.addView(second);
        if (action != null) { TextView chevron = new TextView(this); chevron.setText("›"); chevron.setTextSize(24); chevron.setTextColor(MUTED); chevron.setPadding(dp(12), 0, 0, 0); row.addView(chevron); row.setOnClickListener(v -> action.run()); }
        row.setContentDescription(name + ", " + detail); list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return second;
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
            .setMessage("Printer IP: printer Settings → Network.\n\nAccess code: turn on LAN Only on the printer and use the code it shows (blank if code protection is off).\n\nSerial number: leave blank so the app finds it automatically; if that fails, enter it from printer Settings → Device.\n\nConnection route: Local Wi-Fi at home, or Remote through home VPN with your Pi gateway (see Remote access setup).\n\nCheck connection tests whether the printer can be reached (MQTT 1883, HTTP 80, camera 8080, UDP 52700). Uploads need HTTP port 80.\n\nWithout LAN Only, use Elegoo cloud above and sign in to monitor and control through the cloud instead.")
            .setPositiveButton("Close", null).show();
    }
    private void coexistenceHelp() {
        new AlertDialog.Builder(this).setTitle("Read-only Matrix coexistence test")
            .setMessage("1. Leave the printer in normal cloud mode (LAN Only off). Confirm Matrix shows live status and camera. Do not unbind or re-pair the printer.\n\n2. Use home Wi-Fi first. Select Cloud-mode PIN probe and enter the pairing PIN the printer shows, if it shows one. If UDP discovery cannot identify the printer, enter its exact serial.\n\n3. Tap Check connection, then Connect. This tries the SDK's local MQTT PIN path once. It does not sign in to your Elegoo account, bind devices, copy cloud client identities, guess credentials or use a PIN in HTTP requests.\n\n4. Compare live status here and in Matrix, switch between the apps, and check that Matrix stays connected. Registration alone does not prove coexistence. If the printer refuses or disconnects, the probe stops. Firmware may require the official cloud transport.\n\nPrinter-changing controls and uploads stay disabled in this probe. Camera and read queries depend on firmware. PINs are not saved, included in diagnostics or logged. If no monitoring session remains, reopening the app requires the PIN again.")
            .setPositiveButton("Close", null).show();
    }
    private void remoteHelp() {
        LinearLayout body = dialogBody();
        label(body, "Use your always-on Pi as a Tailscale subnet router. Install Tailscale on the Pi and phone, then sign in to your own tailnet. The Pi needs access to the printer on your home network.", 14, INK, false);
        label(body, "On the Pi: turn on IPv4 forwarding and advertise only the printer's IP as a /32 route. Approve that route in the Tailscale admin console. Limit the phone's access to printer TCP 1883 (monitor and control), 80 (uploads, if available), 8080 (camera) and, optionally, UDP 52700 (identity). Review any broader existing access rules too.", 14, INK, false);
        label(body, "Choose Remote through home VPN, and keep the printer's home IP (not the Pi's Tailscale IP). Authentication is separate: the access code uses LAN Only, and the experimental read-only PIN probe keeps cloud mode on to test Matrix coexistence. Set up the chosen authentication on the home network first. A manual serial is the fallback if UDP identity does not reply.", 14, INK, false);
        label(body, "The internet hop from phone to Pi is encrypted by the VPN. The Pi-to-printer hop keeps the printer's LAN protocol. Do not publicly forward printer ports. Turn on multi-factor sign-in (MFA) and keep the Pi updated. Android's always-on VPN and block-without-VPN settings enforce this more strictly against connection-loss races. A connected VPN alone does not prove the gateway, route or encryption setup.", 14, MUTED, false);
        label(body, "The app does not install or configure Tailscale, and it does not bypass printer authentication. HTTP unavailable at home stays unavailable remotely. Monitoring, control, upload and camera requests use the selected route. If the VPN drops, the session closes and needs a fresh connection. Commands are never replayed.", 14, MUTED, false);
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
        // Shown at once from whether a sign-in is stored; the account's name is decrypted off the main thread (the key store
        // reads the disk) and filled in after.
        boolean stored = cloudAccounts.present();
        cloudStatus.setText(stored ? "Signed in." : "Not signed in.");
        // Signed out, these have nothing to act on, so they are not shown as dead buttons.
        int signedIn = stored ? View.VISIBLE : View.GONE;
        cloudSignOut.setVisibility(signedIn); cloudPrinters.setVisibility(signedIn); cloudBackground.setVisibility(signedIn);
        if (printer != null) printer.cloudSettingsChanged();
        if (!stored) return;
        checks.execute(() -> {
            CloudLogin.Account account; boolean unreadable = false;
            try { account = cloudAccounts.load(); } catch (Exception error) { account = null; unreadable = true; }
            CloudLogin.Account shown = account; boolean failed = unreadable;
            main.post(() -> {
                if (isDestroyed()) return;
                if (failed) {
                    cloudAccounts.forget(); message("Saved Elegoo sign-in could not be decrypted. Sign in again.");
                    cloudStatus.setText("Not signed in."); cloudSignOut.setVisibility(View.GONE); cloudPrinters.setVisibility(View.GONE); cloudBackground.setVisibility(View.GONE);
                    if (printer != null) printer.cloudSettingsChanged();
                } else if (shown != null && cloudAccounts.present()) cloudStatus.setText("Signed in as " + shown.summary() + ".");
            });
        });
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
                if (result.isEmpty()) { message("No printers replied to the Wi-Fi search. You can still enter the printer IP and serial by hand; some networks block broadcasts."); return; }
                String[] names = new String[result.size()];
                for (int i = 0; i < names.length; i++) { Cc2Discovery.Found entry = result.get(i); names[i] = StatusPresentation.clean(entry.info.name.isEmpty() ? entry.info.model : entry.info.name) + " · " + entry.host + "\n" + entry.info.summary(); }
                new AlertDialog.Builder(this).setTitle("Printers found").setItems(names, (dialog, which) -> {
                    Cc2Discovery.Found entry = result.get(which); selectPrinter(entry.host, entry.info.serial, entry.info.name); message(entry.info.summary());
                }).setNegativeButton("Cancel", null).show();
            });
        });
    }
    private void buildFileBrowser() {
        LinearLayout browser = card(null);
        // Storage picker and Refresh each take a full row, so long storage names and large font sizes never squeeze them.
        storagePicker = spinner(browser, new String[] {"Internal storage", "USB drive"}); A11y.name(storagePicker, "Printer storage to list");
        storagePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { if (printer != null && printer.canQuery() && !printer.busy(Cc2Codec.FILES)) printer.browse(position == 0 ? "local" : "u-disk", 0); }
        });
        listFiles = button(browser, "Refresh", () -> { if (printer != null) printer.browse(storagePicker.getSelectedItemPosition() == 0 ? "local" : "u-disk", 0); });
        fileInfo = label(browser, "Connect, then refresh to see the printer's files.", 14, MUTED, false);
        filesFix = rowButton(row(browser), "", this::fixControl, true); ((View) filesFix.getParent()).setVisibility(View.GONE);
        fileRows = new LinearLayout(this); fileRows.setOrientation(LinearLayout.VERTICAL); browser.addView(fileRows);
        pageRow = row(browser);
        previousFiles = rowButton(pageRow, "Previous 50", () -> { if (printer != null) printer.browse(printer.storage, Math.max(0, printer.fileOffset - 50)); }, false);
        nextFiles = rowButton(pageRow, "Next 50", () -> { if (printer != null) printer.browse(printer.storage, printer.fileOffset + 50); }, false);
        fileHelp = label(browser, "Tap a file to print it, save it to your phone or delete it. Printing and deleting need an idle printer.", 13, MUTED, false);
        LinearLayout storage = card("Printer storage");
        diskInfo = label(storage, "Storage usage not loaded.", 14, MUTED, false);
        loadDisk = button(storage, "Refresh storage", () -> { if (printer != null) printer.loadDisk(); });
    }
    /** History: what was printed (with Print again and timelapses), the app's own recordings, then the printer's storage. */
    private void buildHistory() {
        // Recent prints first: each opens its page (journal, charts, pictures). Then the printer's own history, then statistics.
        LinearLayout recordings = card("Recent prints");
        recentPrints = new LinearLayout(this); recentPrints.setOrientation(LinearLayout.VERTICAL); recordings.addView(recentPrints);
        recentEmpty = label(recordings, "Prints the app watched appear here, with their graphs, camera pictures and your notes.", 13, MUTED, false);
        listRow(recordings, "All print recordings…", "Search, compare and rate every print the app watched", true, () -> startActivity(new Intent(this, RecordingsActivity.class)));
        maintenanceDetail = listRow(recordings, "Maintenance reminders…", "Nozzle, plate, belts and more, by print hours", true, () -> startActivity(new Intent(this, MaintenanceActivity.class)));
        // The printer's own records and the statistics each on their own page.
        printerHistorySection = section(HISTORY, "The printer's history", "What the printer itself lists: print again, timelapses");
        statsSection = section(HISTORY, "Statistics", "Prints, hours, filament and cost");
        LinearLayout outer = currentSection;
        currentSection = printerHistorySection.body;
        LinearLayout history = card(null);
        LinearLayout refreshRow = row(history); historyButtons = refreshRow;
        loadHistory = rowButton(refreshRow, "Refresh history", () -> { if (printer != null) printer.loadHistory(); }, false);
        historyInfo = label(history, "History not loaded.", 14, INK, false); historyInfo.setTextIsSelectable(true);
        historyList = new LinearLayout(this); historyList.setOrientation(LinearLayout.VERTICAL); history.addView(historyList);
        // Timelapse videos the printer made for recent prints: download over LAN, then save.
        timelapseList = new LinearLayout(this); timelapseList.setOrientation(LinearLayout.VERTICAL); history.addView(timelapseList);
        timelapseHint = label(history, "", 12, MUTED, false);
        saveTimelapse = button(history, "Save timelapse to phone…", () -> {
            if (printer == null || printer.timelapseFile == null) return;
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("video/mp4").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, printer.timelapseName), SAVE_TIMELAPSE);
        });
        saveTimelapse.setVisibility(View.GONE);
        currentSection = statsSection.body;
        LinearLayout stats = card(null);
        statsText = label(stats, "Counting…", 14, INK, false);
        button(stats, "Prices…", this::priceDialog);
        button(stats, "Make a showcase video…", this::showcaseIntro);
        currentSection = outer;
    }
    private Button layerAlert;
    /** The running print's key, as PrintAlerts uses it: its uuid, else its file name. */
    private static String printKey(JSONObject status) {
        JSONObject print = status == null ? null : status.optJSONObject("print_status"); if (print == null) return "";
        String key = print.optString("uuid", ""); return key.isEmpty() ? print.optString("filename", "") : key;
    }
    /** "Filament used so far 3.21 m (≈ 9.6 g) · By your past prints: done ≈ 14:42 (+12 min)", or the parts known. */
    private String printNotes(long remaining) {
        PrintRecorder.Recording now = printer == null || printer.recorder == null ? null : printer.recorder.current();
        if (now == null) return "";
        List<String> parts = new ArrayList<>();
        if (now.extrudedMm > 1) {
            JSONObject tray = PrintWatch.activeTray(printer.canvas);
            parts.add(String.format(Locale.getDefault(), "Filament used so far %.2f m (≈ %.0f g)", now.extrudedMm / 1000, PrintRecorder.grams(now.extrudedMm, tray == null ? "" : tray.optString("filament_type"))));
        }
        long learned = printer.recorder.learnedRemaining(android.os.SystemClock.elapsedRealtime());
        if (learned >= 0 && remaining >= 0 && Math.abs(learned - remaining) >= 180)
            parts.add("By your past prints: done ≈ " + StatusPresentation.doneAt(System.currentTimeMillis(), learned, Locale.getDefault(), TimeZone.getDefault())
                + " (" + (learned > remaining ? "+" : "−") + StatusPresentation.span(Math.abs(learned - remaining)) + ")");
        return String.join(" · ", parts);
    }
    /** The answer to a guardian alarm: tells the guardian, and keeps it in the print's journal. */
    private void guardianAnswer(boolean fine) {
        if (printer != null && printer.guardian != null) {
            String said = printer.guardian.verdict;
            printer.guardian.feedback(fine);
            if (printer.recorder != null) { PrintRecorder recorder = printer.recorder; checks.execute(() -> recorder.noteGuardian(fine, said, System.currentTimeMillis())); }
        }
        guardianButtons.setVisibility(View.GONE);
    }
    /** "How did … come out?": the print that ended last, until it is rated (for two days), read off the main thread. */
    private File rateMeta; private PrintRecorder.Recording rateRecording; private long rateCheckedAt;
    private void refreshRatePrompt() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (printer == null || printer.recorder == null || now - rateCheckedAt < 20_000) return;
        rateCheckedAt = now; PrintRecorder recorder = printer.recorder;
        checks.execute(() -> {
            File meta = recorder.lastFinished(); PrintRecorder.Recording read = null;
            try { if (meta != null) read = PrintRecorder.Recording.read(meta); } catch (Exception unreadable) { }
            PrintRecorder.Recording found = read;
            main.post(() -> { rateMeta = meta; rateRecording = found; render(); });
        });
    }
    /** The Projects card's line: how many projects and how far they are (read off the screen thread). */
    private void refreshProjectsLine() {
        checks.execute(() -> {
            Projects.Book book = Projects.load(new File(getFilesDir(), "projects"));
            int open = 0, ready = 0;
            for (Projects.Project p : book.projects) if (!p.archived) { open++; if (p.allAvailable()) ready++; }
            String text = open == 0 ? "Plan a build: its parts, how many of each, their files and the prints that made them."
                : open + (open == 1 ? " project" : " projects") + (ready > 0 ? " · " + ready + " with every part ready" : "");
            main.post(() -> { if (projectsLine != null) projectsLine.setText(text); });
        });
    }
    private android.widget.HorizontalScrollView latestScroll; private LinearLayout latestStrip; private String latestFor = "";
    static final int LATEST_PICTURES = 6;
    /** The running print's newest pictures in a strip under the job, read again only when a new one was kept. */
    private void renderLatestPictures(PrintRecorder.Recording recording) {
        if (recording == null || recording.pictures == 0) { latestScroll.setVisibility(View.GONE); latestFor = ""; return; }
        String key = recording.meta.getName() + ":" + recording.pictures;
        if (key.equals(latestFor)) return;
        latestFor = key; File meta = recording.meta; int width = dp(120);
        checks.execute(() -> {
            List<File> all = PrintRecorder.pictures(recording);
            List<File> newest = new ArrayList<>(all.subList(Math.max(0, all.size() - LATEST_PICTURES), all.size())); java.util.Collections.reverse(newest);
            List<android.graphics.Bitmap> thumbs = new ArrayList<>(); for (File f : newest) thumbs.add(RecordingsActivity.decode(f, width));
            main.post(() -> {
                if (isDestroyed() || !key.equals(latestFor)) return;
                latestStrip.removeAllViews();
                for (int i = 0; i < newest.size(); i++) {
                    File file = newest.get(i); String at = ChartView.elapsed(Double.parseDouble(file.getName().replace(".jpg", "")));
                    LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setPadding(0, 0, dp(8), 0);
                    ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setImageBitmap(thumbs.get(i));
                    image.setContentDescription("Picture at " + at + " into the print"); A11y.clickLabel(image, "See it larger");
                    int index = all.size() - 1 - i;
                    image.setOnClickListener(v -> PictureViewer.show(this, all, index, k -> ChartView.elapsed(Double.parseDouble(all.get(k).getName().replace(".jpg", ""))) + " into the print"));
                    cell.addView(image, new LinearLayout.LayoutParams(dp(120), dp(90)));
                    label(cell, i == 0 ? "Latest · " + at : at, 12, MUTED, false);
                    latestStrip.addView(cell);
                }
                TextView every = label(latestStrip, "All " + recording.pictures + " ›", 13, TEAL, true);
                every.setMinHeight(dp(90)); every.setGravity(Gravity.CENTER_VERTICAL); every.setPadding(dp(8), 0, dp(8), 0);
                every.setOnClickListener(v -> startActivity(new Intent(this, RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, meta.getAbsolutePath())));
                latestScroll.setVisibility(View.VISIBLE);
            });
        });
    }
    /** The File source line, worked out off the screen thread at most every 10 s (and at once for a new print). */
    private void renderFileSource(boolean printing, String name) {
        if (!printing || printer == null || printer.library == null || name.isEmpty()) { fileSource.setVisibility(View.GONE); fileSourceFor = ""; return; }
        long now = android.os.SystemClock.elapsedRealtime();
        if (name.equals(fileSourceFor) && now - fileSourceAt < 10_000) return;
        fileSourceFor = name; fileSourceAt = now;
        GcodeLibrary library = printer.library; GcodeJobTracker.Job jobNow = printer.fileJob();
        checks.execute(() -> {
            String text;
            try {
                PrintRecorder recorder = PrintRecorder.active; PrintRecorder.Recording recording = recorder == null ? null : recorder.current();
                if (recording != null && !java.util.Objects.equals(GcodeLibrary.baseName(recording.file), GcodeLibrary.baseName(name))) recording = null;
                text = recording != null ? GuardianRun.fileSourceLine(GuardianRun.source(library, jobNow, recording, getCacheDir())) : GuardianRun.fileSourceLine(GuardianRun.source(library, jobNow, name));
            } catch (Exception unreadable) { text = ""; }
            String shown = text;
            main.post(() -> { if (!name.equals(fileSourceFor)) return; fileSource.setText(shown); fileSource.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE); });
        });
    }
    /**
     * What a printer companion (a small helper reached through the printer's service console, with a Raspberry Pi) would add, and
     * that none is set up. Nothing here connects to anything: the app never installs on or changes the printer.
     */
    private void companionDialog() {
        LinearLayout body = dialogBody();
        label(body, "Not set up. The app works without one.", 15, INK, true);
        label(body, "A companion would read, never write, what the printer keeps to itself:", 14, INK, false);
        for (String line : new String[] {
                "• The exact G-code of a print started elsewhere (USB stick, the printer's screen), so Live toolpath and the guardian can use it.",
                "• The printer's own job journal, finished timelapses and thumbnails.",
                "• Why the camera is not sending: off in this mode, missing, or recovering.",
                "• A live status stream, as an alternative to the cloud."})
            label(body, line, 13, INK, false);
        label(body, "What it needs: owner access to the printer's service console (opening the printer and a USB-serial adapter) and a Raspberry Pi kept beside it. "
            + "This is research, not yet tried on a real printer. The printer must keep working normally without it, firmware stays untouched, and the app will never install it for you.", 13, MUTED, false);
        label(body, "Until then: the File source line on the Print tab says whether this phone holds the exact file of the running print; prints sent or sliced from this app always keep one.", 13, MUTED, false);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new AlertDialog.Builder(this).setTitle("Printer companion").setView(scroll).setPositiveButton("Close", null).show();
    }
    /** The running print's options: each opens its own dialog (nothing here is sent to the printer). */
    private void printOptionsDialog() {
        List<String> names = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        for (Button option : new Button[] {picturesNow, layerAlert, printAutomations})
            if (option.getVisibility() == View.VISIBLE) { names.add(option == printAutomations ? "Automations for this print…" : option.getText().toString()); actions.add(option::performClick); }
        // Through the cloud with background watching off, the recording stops when the app closes: offer to keep it going.
        if (printer != null && printer.usingCloud() && !printer.cloudBackground() && !cloudBackground.isChecked()) {
            names.add("Keep recording when the app is closed…");
            actions.add(() -> new AlertDialog.Builder(this).setTitle("Keep recording when the app is closed?")
                .setMessage("The app keeps watching through the Elegoo cloud in the background, with a notification, so this print's recording carries on. It checks about every 30 seconds while a print runs; the phone stays awake for it until the print ends. Camera pictures through the cloud keep coming too. Turn it off again in Settings › Printer connection.")
                .setNegativeButton("Not now", null).setPositiveButton("Keep recording", (d, w) -> cloudBackground.setChecked(true)).show());
        }
        JSONObject machineNow = snapshot.optJSONObject("machine_status");
        if (machineNow != null && machineNow.optInt("status", -1) == 2) {
            boolean toolpath = toolpathPreviewOn();
            names.add(toolpath ? "Preview: show the camera" : "Preview: show the live toolpath"); actions.add(() -> choosePreview(!toolpath));
        }
        names.add("Camera tools…"); actions.add(() -> openSection(cameraSection));
        new AlertDialog.Builder(this).setTitle("Print options").setItems(names.toArray(new String[0]), (d, which) -> actions.get(which).run()).setNegativeButton("Close", null).show();
    }
    /** This print's automation choices: the day's last print (for rules that wait for it) and rules to skip. Nothing is sent to the printer here. */
    private void printAutomationsDialog() {
        String key = printKey(snapshot); if (key.isEmpty()) return;
        JSONObject printing = snapshot.optJSONObject("print_status"); String file = printing == null ? "" : printing.optString("filename", "");
        Automation.ThisPrint mine = AutomationRun.thisPrint(this).forPrint(key, file);
        List<Automation.Rule> on = new ArrayList<>(); for (Automation.Rule r : AutomationRun.rules(this)) if (r.enabled) on.add(r);
        String[] items = new String[on.size() + 1]; boolean[] checked = new boolean[items.length];
        items[0] = "This is the last print of the day"; checked[0] = mine.lastOfDay;
        for (int i = 0; i < on.size(); i++) { items[i + 1] = "Run “" + on.get(i).name + "”" + (on.get(i).lastPrintOnly ? " (last print only)" : ""); checked[i + 1] = !mine.muted.contains(on.get(i).id); }
        new AlertDialog.Builder(this).setTitle("Automations for this print")
            .setMultiChoiceItems(items, checked, (d, which, value) -> checked[which] = value)
            .setNeutralButton("Workshop…", (d, w) -> startActivity(new Intent(this, AutomationActivity.class)))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (d, w) -> {
                mine.lastOfDay = checked[0]; mine.muted.clear(); mine.key = key; if (!file.isEmpty()) mine.file = file; mine.savedAt = System.currentTimeMillis();
                for (int i = 0; i < on.size(); i++) if (!checked[i + 1]) mine.muted.add(on.get(i).id);
                AutomationRun.saveThisPrint(this, mine);
                Diagnostics.note(Diagnostics.AUTOMATIONS, "this print: " + (mine.lastOfDay ? "last of the day" : "not the last of the day") + ", " + mine.muted.size() + " rules skipped");
            }).show();
    }
    private void layerAlertDialog() {
        JSONObject print = snapshot.optJSONObject("print_status"); if (print == null) return;
        int total = print.optInt("total_layer", 0), now = print.optInt("current_layer", 0); String key = printKey(snapshot);
        LinearLayout body = dialogBody();
        label(body, "Get a notification when this print reaches a layer, for example to check it or to swap filament by hand. Now at layer " + now + " of " + total + ".", 14, INK, false);
        EditText at = input(body, "Layer (" + (now + 1) + "–" + total + ")", false); at.setInputType(InputType.TYPE_CLASS_NUMBER);
        int saved = key.equals(settings.getString("alertLayerKey", "")) ? settings.getInt("alertLayer", 0) : 0; at.setText(String.valueOf(saved > 0 ? saved : Math.min(total, now + 10)));
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Layer alert").setView(body).setNegativeButton("Cancel", null).setPositiveButton("Set alert", (d, w) -> {
            try {
                int layer = Integer.parseInt(at.getText().toString());
                if (layer <= now || layer > total) { message("Choose a layer after " + now + ", up to " + total + "."); return; }
                settings.edit().putInt("alertLayer", layer).putString("alertLayerKey", key).apply(); requestNotifications(); render();
                message("You'll get a notification at layer " + layer + ", while the app keeps watching the printer.");
            } catch (NumberFormatException invalid) { message("Enter the layer as a number."); }
        });
        if (saved > 0) builder.setNeutralButton("Remove", (d, w) -> { settings.edit().remove("alertLayer").remove("alertLayerKey").apply(); render(); });
        builder.show();
    }
    // ------------------------------------------------------------------ Statistics (History)
    private TextView statsText, maintenanceDetail;
    private long statsAt;
    /** Counts from the recordings and spool use, read off the main thread (up to a hundred small files). */
    private void refreshStats() {
        if (statsText == null) return;
        statsAt = System.currentTimeMillis();
        File folder = RecordingsActivity.directory(this); SpoolStore store = spools(); double price = pricePerKg();
        checks.execute(() -> {
            List<PrintRecorder.Recording> all = new PrintRecorder(folder).list();
            List<String> lines = PrintStats.of(all, store.usage(), System.currentTimeMillis()).lines(price);
            List<PrintRecorder.Recording> recent = new ArrayList<>(all.subList(0, Math.min(5, all.size())));
            String upkeep = Maintenance.summary(Maintenance.load(settings, System.currentTimeMillis()), all, System.currentTimeMillis());
            main.post(() -> { if (isDestroyed()) return; statsText.setText(String.join("\n", lines)); showRecentPrints(recent); if (!lines.isEmpty()) statsSection.detail.setText(lines.get(0)); if (maintenanceDetail != null) maintenanceDetail.setText(upkeep); });
        });
    }
    /** The latest prints the app recorded, newest first; each row opens the print's own page. */
    private void showRecentPrints(List<PrintRecorder.Recording> recent) {
        recentPrints.removeAllViews();
        for (PrintRecorder.Recording r : recent) {
            String name = StatusPresentation.clean(r.file.isEmpty() ? "Print" : r.file.replaceFirst("(?i)\\.gcode$", ""));
            String detail = RecordingsActivity.rowSummary(r, Locale.getDefault(), TimeZone.getDefault()) + (r.journal.stars > 0 ? " · " + "★★★★★".substring(0, Math.min(5, r.journal.stars)) : r.journal.rated() ? "" : " · not rated yet");
            File meta = r.meta;
            listRow(recentPrints, name, detail, recentPrints.getChildCount() > 0, () -> startActivity(new Intent(this, RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, meta.getAbsolutePath())));
        }
        recentEmpty.setVisibility(recent.isEmpty() ? View.VISIBLE : View.GONE);
    }
    /** A field's number (empty is 0), at most `limit`; NumberFormatException when it is not a number in range. */
    private static double parsed(EditText field, double limit) {
        String text = field.getText().toString().trim().replace(',', '.');
        double value = text.isEmpty() ? 0 : Double.parseDouble(text); if (value < 0 || value > limit) throw new NumberFormatException(); return value;
    }
    private static double number(String text) { try { return Double.parseDouble(text); } catch (NumberFormatException none) { return 0; } }
    private double pricePerKg() { try { return Double.parseDouble(settings.getString("filamentPricePerKg", "0")); } catch (NumberFormatException none) { return 0; } }
    private void priceDialog() {
        LinearLayout body = dialogBody();
        label(body, "What a kilogram of filament costs you, in your currency. Used for the cost on History, after slicing and on each print's recording. Leave empty for none.", 14, INK, false);
        EditText price = input(body, "Price per kg", false); price.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double now = pricePerKg(); if (now > 0) price.setText(String.valueOf(now));
        label(body, "For the energy too (optional): the printer's average power while printing, in watts (a plug-in meter tells you; around 150 W is common for a printer of this size with a heated bed), and what a kWh costs you.", 13, MUTED, false);
        EditText watts = input(body, "Average power (W)", false); watts.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText kwh = input(body, "Price per kWh", false); kwh.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double w0 = number(settings.getString("printerWatts", "0")), k0 = number(settings.getString("electricityPerKwh", "0"));
        if (w0 > 0) watts.setText(String.valueOf(w0)); if (k0 > 0) kwh.setText(String.valueOf(k0));
        new AlertDialog.Builder(this).setTitle("Prices").setView(body).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            try {
                double value = parsed(price, 10_000), power = parsed(watts, 5_000), energy = parsed(kwh, 100);
                settings.edit().putString("filamentPricePerKg", String.valueOf(value)).putString("printerWatts", String.valueOf(power)).putString("electricityPerKwh", String.valueOf(energy)).apply(); refreshStats();
            }
            catch (NumberFormatException invalid) { message("Enter each as a number, for example 22.50."); }
        }).show();
    }
    private TextView printerAi;
    private boolean lightIsOn;
    /**
     * The printer's own AI camera checks as it reports them (read only: the app does not change them). Values the printer
     * gives as text are shown as they are.
     */
    static String aiText(JSONObject ai) {
        if (ai == null) return "";
        JSONObject spaghetti = ai.optJSONObject("spaghetti_detection"), foreign = ai.optJSONObject("foreign_object_detection");
        if (spaghetti == null && foreign == null) return "";
        StringBuilder text = new StringBuilder("The printer's own camera checks: ");
        if (spaghetti != null) {
            text.append("spaghetti detection ").append(spaghetti.optInt("status") != 0 ? "on" : "off");
            String often = StatusPresentation.clean(spaghetti.optString("frequency")).trim(), then = StatusPresentation.clean(spaghetti.optString("handing")).trim();
            if (spaghetti.optInt("status") != 0 && (!often.isEmpty() || !then.isEmpty()))
                text.append(" (").append(often.isEmpty() ? "" : "how often: " + often).append(!often.isEmpty() && !then.isEmpty() ? ", " : "").append(then.isEmpty() ? "" : "when found: " + then).append(")");
        }
        if (foreign != null) text.append(spaghetti != null ? "; " : "").append("objects left on the bed ").append(foreign.optInt("status") != 0 ? "on" : "off");
        return text.append(". Change them on the printer's screen.").toString();
    }
    /** The phone's copy of a file on this printer: the only one kept under that name for it (or for no printer); null when there are several or none. */
    private java.io.File printerCopy(String name) {
        GcodeLibrary library = new GcodeLibrary(new java.io.File(getFilesDir(), "gcode-library"));
        String serial = printer == null ? "" : printer.filePrinterId();
        if (serial.isEmpty()) return library.find(name);
        try { java.util.List<GcodeLibrary.Artifact> copies = library.candidates(serial, name); return copies.size() == 1 ? copies.get(0).file : null; }
        catch (java.io.IOException unreadable) { return null; }
    }
    private SpoolStore spools() { return SpoolStore.at(new File(getFilesDir(), "spools.json")); }
    /** Emergency stop: asks first, and is sent once per confirmation like every other printer command. */
    private void emergencyButton(LinearLayout parent) {
        Button stop = button(parent, "Emergency stop…", () -> confirmRequest("Emergency stop?", "Stops the printer right away, like the printer's own emergency stop. A running print cannot be resumed.", () -> Cc2Codec.maintenanceRequest(0, Cc2Codec.URGENT_STOP)));
        stop.setTextColor(ERROR); emergencyStops.add(stop);
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
    /** Saves the phone copy of the selected G-code where the user picks. */
    private void saveSelected() {
        if (printer == null || printer.selectedReport == null || printer.fileBusy()) return;
        pendingExportHash = printer.selectedReport.sha256;
        String title = printer.selectedName.substring(printer.selectedName.lastIndexOf('/') + 1);
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, title);
        startActivityForResult(save, SAVE_GCODE);
    }

    // ------------------------------------------------------------------ backup and restore
    private char[] backupPassword; private boolean backupRecordings, backupProjectFiles = true;
    private void backupDialog() {
        LinearLayout body = dialogBody();
        long size = Backup.recordingsSize(this);
        CompoundButton recordings = checkbox(body, "Include the print recordings and their pictures (" + Math.max(1, size / (1024 * 1024)) + " MB)", false);
        int projectCount = Backup.projectFiles(this).size(); long projectSize = Backup.projectFilesSize(this);
        CompoundButton projectFiles = checkbox(body, "Include the files kept for projects (" + projectCount + (projectCount == 1 ? " file, " : " files, ") + Math.max(1, projectSize / (1024 * 1024)) + " MB)", projectCount > 0);
        projectFiles.setEnabled(projectCount > 0);
        CompoundButton secrets = checkbox(body, "Include passwords, access codes and camera addresses, protected by a backup password", false);
        EditText first = input(body, "Backup password", true), again = input(body, "The same password again", true);
        Runnable showFields = () -> { int v = secrets.isChecked() ? View.VISIBLE : View.GONE; first.setVisibility(v); again.setVisibility(v); };
        secrets.setOnCheckedChangeListener((view, on) -> showFields.run()); showFields.run();
        label(body, "With a backup password, printers' access codes, added cameras, webhook addresses and tokens are encrypted with it. Without one they are left out: webhooks keep only their site and come back turned off, so enter them again after restoring. Keep the password safe: it cannot be recovered.", 12, MUTED, false);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Back up settings").setView(body).setNegativeButton("Cancel", null).setPositiveButton("Choose where to save…", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (secrets.isChecked()) {
                String a = first.getText().toString(), b = again.getText().toString();
                if (a.length() < 8) { message("Use a backup password of at least 8 characters."); return; }
                if (!a.equals(b)) { message("The two passwords are not the same."); return; }
                backupPassword = a.toCharArray();
            } else backupPassword = null;
            backupRecordings = recordings.isChecked(); backupProjectFiles = projectFiles.isChecked(); dialog.dismiss();
            String name = "link-workshop-backup-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date()) + ".zip";
            try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE, name), BACKUP_SAVE); }
            catch (Exception none) { message("This phone has no file picker."); }
        }));
        dialog.show();
    }
    private void writeBackup(android.net.Uri target, boolean recordings, boolean projectFiles, char[] password) {
        message("Writing the backup…");
        checks.execute(() -> {
            String text;
            try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                if (out == null) throw new IOException("no file");
                Backup.Summary s = Backup.export(this, new java.io.BufferedOutputStream(out), recordings, projectFiles, password);
                text = "Backup saved: " + s.describe() + ".";
                Diagnostics.note(Diagnostics.MESSAGES, "backup written: " + s.prefs + " settings files, " + s.files + " data files, " + s.recordings + " recording files, " + s.projectFiles + " project files" + (s.secrets ? ", secrets protected" : ""));
            } catch (Exception failed) { text = "The backup could not be written: " + StatusPresentation.clean(String.valueOf(failed.getMessage())); }
            finally { if (password != null) java.util.Arrays.fill(password, ' '); }
            String said = text; main.post(() -> message(said));
        });
    }
    private void readBackup(android.net.Uri source) {
        message("Reading the backup…");
        checks.execute(() -> {
            File copy = new File(getCacheDir(), "restore.zip");
            try (InputStream in = getContentResolver().openInputStream(source); OutputStream out = new FileOutputStream(copy)) {
                if (in == null) throw new IOException("no file");
                Backup.copy(in, out, Backup.MAX_TOTAL);
                Backup.Summary s = Backup.inspect(copy);
                main.post(() -> confirmRestore(copy, s));
            } catch (Exception failed) { copy.delete(); String said = "That backup could not be read: " + StatusPresentation.clean(String.valueOf(failed.getMessage())); main.post(() -> message(said)); }
        });
    }
    private void confirmRestore(File copy, Backup.Summary s) {
        LinearLayout body = dialogBody();
        label(body, "Made " + (s.created > 0 ? new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(new Date(s.created)) : "at an unknown time") + (s.app.isEmpty() ? "" : " by version " + s.app) + ".", 14, INK, false);
        label(body, "It holds " + s.describe() + ".", 14, INK, false);
        EditText password = input(body, "Backup password (for the passwords and addresses)", true); password.setVisibility(s.secrets ? View.VISIBLE : View.GONE);
        label(body, "Restoring replaces this app's settings, automation rules and spools with the backup's, and adds its recordings (none are deleted). The app restarts afterwards." + (s.secrets ? " Without the password, everything else is restored and the passwords are left out." : ""), 12, MUTED, false);
        new AlertDialog.Builder(this).setTitle("Restore this backup?").setView(body).setNegativeButton("Cancel", (d, w) -> copy.delete())
            .setPositiveButton("Restore", (d, w) -> {
                char[] secret = password.getText().toString().toCharArray();
                message("Restoring…");
                checks.execute(() -> {
                    String text; boolean done = false;
                    try { Backup.Summary restored = Backup.restore(this, copy, secret); done = true;
                        text = "Restored" + (s.secrets && !restored.secrets ? " (without the passwords)" : "") + ". Restarting…";
                        Diagnostics.note(Diagnostics.MESSAGES, "backup restored" + (restored.secrets ? " with secrets" : ""));
                    } catch (Exception failed) { text = StatusPresentation.clean(String.valueOf(failed.getMessage())); }
                    finally { java.util.Arrays.fill(secret, ' '); copy.delete(); }
                    String said = text; boolean restart = done;
                    main.post(() -> { message(said); if (restart) main.postDelayed(this::restartApp, 1200); });
                });
            }).show();
    }
    /** Starts the app afresh, so every part reads the restored settings. */
    private void restartApp() {
        stopService(new Intent(this, PrinterService.class));
        android.app.PendingIntent start = android.app.PendingIntent.getActivity(this, 42, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_CANCEL_CURRENT);
        android.app.AlarmManager alarms = getSystemService(android.app.AlarmManager.class);
        if (alarms != null) alarms.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 600, start);
        finishAffinity(); Runtime.getRuntime().exit(0);
    }
    /** "Preview toolpath" of a printer file the phone has no copy of: once its download is in, open it in the toolpath viewer. */
    private void continuePreviewAfterDownload() {
        if (previewAfterDownload == null || printer == null) return;
        if (printer.selectedFile != null && previewAfterDownload.equals(printer.selectedName) && !printer.importing) {
            previewAfterDownload = null; previewGiveUpAt = 0;
            startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FILE, printer.selectedFile.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, printer.selectedName));
        } else if (!printer.downloading() && !printer.importing) {
            long now = System.currentTimeMillis();
            if (previewGiveUpAt == 0) previewGiveUpAt = now + 3000; else if (now > previewGiveUpAt) { previewAfterDownload = null; previewGiveUpAt = 0; }
        } else previewGiveUpAt = 0;
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
        }; adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); view.setAdapter(adapter); view.setBackgroundTintList(tint(TEAL)); view.setMinimumHeight(dp(52)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); A11y.nameFromCaption(view, parent);
        WorkshopUi.chipsFor(view, TEAL, BUTTON, INK, dark);   // a few short options: chips in a row
        return view;
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
        if (printer != null && printer.pinProbe()) { label(body, "Read-only PIN probe: printing and deleting are turned off.", 14, MUTED, false); dialog.show(); return; }
        // Starting a print comes first; it and Delete need what PrinterService checks (fresh status, an idle printer, a fresh file list), so say when they are not available.
        boolean usable = printer != null && printer.liveFresh() && Cc2Codec.idle(printer.liveStatus()) && printer.filesFresh();
        Button setup = rowButton(row(body), "Print setup…", () -> { dialog.dismiss(); startDialog(file, storage); }, true); setup.setEnabled(usable);
        if (!usable) label(body, printer == null || !printer.liveFresh() ? "Printing and deleting need a fresh printer status." : !Cc2Codec.idle(printer.liveStatus()) ? "The printer is busy. Printing and deleting are available when it is idle." : "The file list is out of date. Refresh files to print or delete.", 13, MUTED, false);
        // The toolpath of this file: straight away from the phone's copy, else after downloading it (locally, or through the cloud when Elegoo keeps a copy).
        java.io.File phoneCopy = printerCopy(name);
        Button preview = button(body, phoneCopy != null ? "Preview toolpath" : "Preview toolpath (downloads it first)", () -> {
            dialog.dismiss();
            if (phoneCopy != null) startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FILE, phoneCopy.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, name));
            else if (printer != null) { previewAfterDownload = name; printer.download(storage, name); }
        });
        preview.setEnabled(phoneCopy != null || printer != null && !printer.fileBusy());
        if (phoneCopy != null) button(body, "Layer charts", () -> { dialog.dismiss(); startActivity(new Intent(this, LayerChartsActivity.class).putExtra(LayerChartsActivity.EXTRA_FILE, phoneCopy.getAbsolutePath()).putExtra(LayerChartsActivity.EXTRA_NAME, name)); });
        Button save = button(body, "Save to phone…", () -> { dialog.dismiss(); if (printer != null) { saveAfterDownload = name; printer.download(storage, name); } });
        Button download = button(body, "Keep a copy in this app", () -> { dialog.dismiss(); if (printer != null) printer.download(storage, name); });
        save.setEnabled(printer != null && !printer.fileBusy()); download.setEnabled(printer != null && !printer.fileBusy());
        Button delete = button(body, "Delete from the printer…", () -> { dialog.dismiss(); new AlertDialog.Builder(this).setTitle("Delete " + StatusPresentation.clean(name) + "?").setMessage("This permanently deletes this file from the printer. The printer must be idle.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (d, which) -> { if (printer != null) printer.delete(storage, name); }).show(); });
        delete.setEnabled(usable); delete.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {MUTED, ERROR}));
        dialog.show();
    }
    private void startDialog(JSONObject file, String storage) {
        if (printer != null && printer.pinProbe()) { message("Read-only PIN probe: printing is turned off."); return; }
        if (printer != null && printer.fileBusy()) { message("A file is being sent or received. Wait for it to finish before starting a print."); return; }
        if (printer == null || !printer.liveFresh() || !Cc2Codec.idle(printer.liveStatus()) || !printer.filesFresh()) { message("Refresh status and files, then wait for the printer to be idle."); return; }
        String name = file.optString("filename");
        LinearLayout body = dialogBody(); label(body, StatusPresentation.clean(name), 16, INK, true);
        label(body, "Check the build plate, material and sliced printer profile before starting.", 13, MUTED, false);
        subheading(body, "Checks");
        CompoundButton leveling = checkbox(body, "Run printer / bed check", true), force = checkbox(body, "Force bed leveling", false), timelapse = checkbox(body, "Record a timelapse on the printer", false);
        force.setOnCheckedChangeListener((view, checked) -> { if (checked) leveling.setChecked(true); });
        leveling.setOnCheckedChangeListener((view, checked) -> { if (!checked) force.setChecked(false); });
        subheading(body, "Build plate");
        Spinner plate = spinner(body, new String[] {"A · textured PEI side", "B · smooth side"});
        // The side the file was sliced for, when this phone has a copy: read off the main thread, then chosen here.
        TextView plateNote = label(body, "", 13, MUTED, false); plateNote.setVisibility(View.GONE);
        java.io.File copy = new GcodeLibrary(new java.io.File(getFilesDir(), "gcode-library")).find(name);
        if (copy != null) checks.execute(() -> {
            String side = GcodeInspector.plateSide(copy);
            main.post(() -> {
                if (side.isEmpty()) return;
                plate.setSelection(side.equals("A") ? 0 : 1);
                plateNote.setText("This file was sliced for side " + side + (side.equals("A") ? " (textured PEI)." : " (smooth)."));
                plateNote.setVisibility(View.VISIBLE);
            });
        });
        subheading(body, "Filament");
        label(body, "Filaments used by this file", 13, MUTED, false);
        Spinner toolCount = spinner(body, new String[] {"1 filament", "2 filaments", "3 filaments", "4 filaments", "5 filaments", "6 filaments", "7 filaments", "8 filaments"});
        // Loaded trays of connected CANVAS units (what print start accepts), each with its material and colour in words. The last
        // report is offered straight away; a fresh one is asked for now (a read) and replaces it, and starting with chosen trays waits for it.
        List<TrayPlan.Tray> trays = new ArrayList<>(printer.canvas != null ? TrayPlan.trays(printer.canvas, true) : new ArrayList<>());
        List<String> choices = new ArrayList<>(), dots = new ArrayList<>(); choices.add("Printer / G-code default"); dots.add(null);
        for (TrayPlan.Tray tray : trays) {
            choices.add(tray.material() + "\n" + tray.where()); dots.add(tray.colour);
        }
        if (!printer.canvasFresh()) printer.refresh();
        // What the file was sliced for, per tool: a plan saved when slicing on this phone, the printer's file details, or the
        // slicer notes of the same file inspected on this phone.
        TrayPlan plan = TrayPlan.parse(getSharedPreferences(SliceActivity.TRAY_PLANS, MODE_PRIVATE).getString(GcodeLibrary.safeName(name), null));
        boolean sameFile = printer.selectedReport != null && GcodeLibrary.safeName(printer.selectedName) != null && GcodeLibrary.safeName(printer.selectedName).equals(GcodeLibrary.safeName(name));
        FilamentMatch.Need[] needs = FilamentMatch.merge(TrayPlan.MAX_TOOLS, FilamentMatch.fromPlan(plan), FilamentMatch.fromColorMap(file.opt("color_map")),
            sameFile ? FilamentMatch.fromComments(printer.selectedReport.materials, TrayPlan.MAX_TOOLS) : new ArrayList<>());
        // Grams per filament (from slicing on this phone, or the slicer notes of the same file inspected here) for the spool check.
        double[] toolGrams = new double[8];
        for (int t = 0; t < 8; t++) {
            toolGrams[t] = plan == null ? 0 : plan.grams(t);
            if (toolGrams[t] <= 0 && sameFile) for (SlicedMaterials.Entry entry : printer.selectedReport.materials.entries) if (entry.index == t && entry.massG != null) toolGrams[t] = entry.massG;
        }
        SpoolStore spoolStore = spools();
        if (file.has("color_map")) Diagnostics.note(Diagnostics.TRAYS, "file details carry color_map (" + (file.opt("color_map") instanceof JSONArray ? "list" : file.opt("color_map") == null ? "null" : file.opt("color_map").getClass().getSimpleName()) + ")");
        Spinner[] maps = new Spinner[8]; LinearLayout[] rows = new LinearLayout[8]; TextView[] verdicts = new TextView[8];
        for (int t = 0; t < 8; t++) {
            rows[t] = new LinearLayout(this); rows[t].setOrientation(LinearLayout.VERTICAL); rows[t].setPadding(0, dp(10), 0, 0); body.addView(rows[t]);
            // A hairline between filaments; it hides with its row.
            if (t > 0) { View line = new View(this); line.setBackgroundColor(TRACK); rows[t].addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)))); }
            FilamentMatch.Need need = needs[t];
            TextView heading = label(rows[t], "Filament " + (t + 1) + " (T" + t + ")", 14, INK, true);
            String wants = need == null ? "" : need.describe();
            TextView wanted = label(rows[t], wants.isEmpty() ? "The file does not say which material." : "Sliced for " + wants + " (from " + need.source + ")", 13, MUTED, false);
            if (need != null && need.colour != null) { wanted.setCompoundDrawablesRelativeWithIntrinsicBounds(swatch(need.colour), null, null, null); wanted.setCompoundDrawablePadding(dp(8)); }
            label(rows[t], "Tray", 13, MUTED, false);
            maps[t] = spinner(rows[t], new String[] {""});
            maps[t].setAdapter(new WorkshopUi.DottedAdapter(this, INK, MUTED, choices, dots));
            A11y.name(maps[t], "Tray for filament " + (t + 1));
            verdicts[t] = A11y.polite(label(rows[t], "", 13, MUTED, false));
            heading.setContentDescription("Filament " + (t + 1) + ", tool T" + t);
        }
        Runnable judge = () -> {
            int count = toolCount.getSelectedItemPosition() + 1;
            for (int t = 0; t < 8; t++) {
                boolean shown = t < count;
                rows[t].setVisibility(shown ? View.VISIBLE : View.GONE);
                if (!shown) continue;
                int selected = maps[t].getSelectedItemPosition();
                TrayPlan.Tray tray = selected > 0 ? trays.get(selected - 1) : null;
                List<Integer> shared = new ArrayList<>();
                for (int o = 0; o < count; o++) if (o != t && selected > 0 && maps[o].getSelectedItemPosition() == selected) shared.add(o + 1);
                FilamentMatch.Verdict verdict = tray == null ? FilamentMatch.unset(needs[t], trays) : FilamentMatch.check(needs[t], tray, shared);
                verdicts[t].setText((verdict.level == FilamentMatch.Level.OK ? "✓ " : verdict.level == FilamentMatch.Level.WRONG ? "✕ " : "! ") + verdict.text);
                verdicts[t].setTextColor(verdict.level == FilamentMatch.Level.OK ? TEAL : verdict.level == FilamentMatch.Level.WRONG ? ERROR : AMBER);
                // Not enough left on the spool in that tray (when this phone knows the spool and the grams).
                String low = tray == null ? null : spoolStore.shortfall(SpoolStore.slot(tray), toolGrams[t]);
                if (low != null) { verdicts[t].append("\n! " + low); if (verdict.level == FilamentMatch.Level.OK) verdicts[t].setTextColor(AMBER); }
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
                : "Suggested " + filled + (filled == 1 ? " tray" : " trays") + " by material, then colour. Check each before starting.");
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
            else if (sameFile && !printer.selectedReport.tools.isEmpty()) { toolCount.setSelection(guess - 1); source.setText("Filament count taken from the tool changes in the file (" + guess + "). Check it against your slice."); }
            if (known > 0 && trays.size() > 0) main.post(suggestTrays);
        }
        LinearLayout tools = row(body);
        rowButton(tools, "Suggest trays", suggestTrays, false);
        rowButton(tools, "Clear trays", () -> { for (Spinner map : maps) map.setSelection(0); source.setText(""); judge.run(); }, false);
        rowButton(row(body), "Refresh trays", () -> { if (printer != null) printer.refresh(); }, false);
        TextView trayState = A11y.polite(label(body, "", 13, MUTED, false));
        // While the dialog is open: say how current the trays are, and take a fresh report in, keeping each choice on the same tray.
        Runnable[] watch = new Runnable[1]; long opened = System.currentTimeMillis(); boolean[] fresh = {printer.canvasFresh()};
        watch[0] = () -> {
            if (printer == null || isDestroyed()) return;
            boolean nowFresh = printer.canvasFresh();
            if (nowFresh && !fresh[0]) {
                TrayPlan.Tray[] picked = new TrayPlan.Tray[8];
                for (int t = 0; t < 8; t++) { int at = maps[t].getSelectedItemPosition(); picked[t] = at > 0 && at - 1 < trays.size() ? trays.get(at - 1) : null; }
                trays.clear(); trays.addAll(TrayPlan.trays(printer.canvas, true));
                choices.subList(1, choices.size()).clear(); dots.subList(1, dots.size()).clear();
                for (TrayPlan.Tray tray : trays) { choices.add(tray.material() + "\n" + tray.where()); dots.add(tray.colour); }
                for (int t = 0; t < 8; t++) {
                    ((ArrayAdapter<?>) maps[t].getAdapter()).notifyDataSetChanged();
                    int again = 0; if (picked[t] != null) for (int i = 0; i < trays.size(); i++) if (trays.get(i).same(picked[t].canvasId, picked[t].trayId)) again = i + 1;
                    maps[t].setSelection(again);
                }
                judge.run();
            }
            fresh[0] = nowFresh;
            boolean waited = System.currentTimeMillis() - opened > 20_000;
            trayState.setText(trays.isEmpty() && nowFresh ? "No loaded CANVAS trays reported, so the printer's own G-code mapping is used."
                : nowFresh ? "Trays are up to date. Leave every filament at Printer / G-code default, or choose a loaded tray for every filament."
                : waited ? "The printer has not reported its trays yet. You can choose from the last report, but starting with chosen trays needs a fresh one: tap Refresh trays."
                : "Checking the trays… You can choose from the last report meanwhile.");
            trayState.setTextColor(nowFresh ? MUTED : AMBER);
            main.postDelayed(watch[0], 1000);
        };
        label(body, "The printer records a timelapse. Download it later from the Files tab, over the local connection.", 12, MUTED, false);
        main.post(judge);
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        AlertDialog setup = new AlertDialog.Builder(this).setTitle("Print setup").setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Next: review…", null).create();
        main.post(watch[0]); setup.setOnDismissListener(d -> main.removeCallbacks(watch[0]));
        setup.setOnShowListener(d -> setup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            JSONArray mapping = new JSONArray(); int count = toolCount.getSelectedItemPosition() + 1, explicit = 0, wrong = 0, check = 0;
            List<ReviewRow> reviewRows = new ArrayList<>();
            try {
                for (int t = 0; t < count; t++) {
                    int selected = maps[t].getSelectedItemPosition();
                    if (selected > 0) { TrayPlan.Tray tray = trays.get(selected - 1); mapping.put(new JSONObject().put("t", t).put("canvas_id", tray.canvasId).put("tray_id", tray.trayId)); explicit++; }
                }
                if (explicit != 0 && explicit != count) { new AlertDialog.Builder(this).setTitle("Choose every filament").setMessage("Choose a tray for every filament, or set them all to Printer / G-code default.").setPositiveButton("OK", null).show(); return; }
                if (explicit > 0 && !printer.canvasFresh()) { printer.refresh(); new AlertDialog.Builder(this).setTitle("Trays not up to date").setMessage("The trays may have changed since the printer last reported them, so a fresh report was asked for. Try again once the setup says the trays are up to date.").setPositiveButton("OK", null).show(); return; }
                for (int t = 0; t < count && explicit > 0; t++) {
                    int selected = maps[t].getSelectedItemPosition(); TrayPlan.Tray tray = trays.get(selected - 1);
                    List<Integer> shared = new ArrayList<>();
                    for (int o = 0; o < count; o++) if (o != t && maps[o].getSelectedItemPosition() == selected) shared.add(o + 1);
                    FilamentMatch.Verdict verdict = FilamentMatch.check(needs[t], tray, shared);
                    if (verdict.level == FilamentMatch.Level.WRONG) wrong++; else if (verdict.level == FilamentMatch.Level.CHECK) check++;
                    ReviewRow row = new ReviewRow();
                    row.title = "Filament " + (t + 1); row.wants = needs[t] == null ? "" : needs[t].describe(); row.wantsColour = needs[t] == null ? null : needs[t].colour;
                    row.tray = tray.material() + " · " + tray.where(); row.trayColour = tray.colour; row.level = verdict.level;
                    row.problem = verdict.level == FilamentMatch.Level.OK ? "" : verdict.text; row.low = spoolStore.shortfall(SpoolStore.slot(tray), toolGrams[t]);
                    row.grams = toolGrams[t];
                    reviewRows.add(row);
                }
            } catch (Exception error) { message("Could not read the tray choices. Refresh trays and try again."); return; }
            setup.dismiss();
            List<String[]> settingsShown = new ArrayList<>();
            settingsShown.add(new String[] {"Build plate", plate.getSelectedItemPosition() == 0 ? "A" : "B"});
            settingsShown.add(new String[] {"Printer / bed check", leveling.isChecked() ? "On" : "Off"});
            settingsShown.add(new String[] {"Force bed leveling", force.isChecked() ? "On" : "Off"});
            settingsShown.add(new String[] {"Timelapse on the printer", timelapse.isChecked() ? "On" : "Off"});
            int wrongCount = wrong, checkCount = check;
            Runnable start = () -> {
                if (plan != null || mapping.length() > 0) Diagnostics.note(Diagnostics.TRAYS, StatusPresentation.clean(name) + ": start with " + count + " filament(s), mapping " + mapping
                    + (wrongCount + checkCount > 0 ? " · " + wrongCount + " material mismatch, " + checkCount + " to check" : ""));
                if (printer != null) {
                    String before = printer.feedback;
                    printer.start(storage, name, leveling.isChecked(), force.isChecked(), timelapse.isChecked(), plate.getSelectedItemPosition() == 0 ? "A" : "B", mapping);
                    // Refused before sending (the checks in start() say why); otherwise the steps page follows the printer's status.
                    String after = printer.feedback == null ? "" : printer.feedback;
                    boolean refused = !after.equals(before) && (after.startsWith("Refresh status, files and trays") || after.startsWith("A file is being sent") || after.startsWith("The printer reports fault"));
                    new PrintStartSteps(this, () -> printer, name, printer.ready() ? "On your network" : "Through the Elegoo cloud", refused, INK, MUTED, TEAL, BACKGROUND, SURFACE, dark).show();
                }
                // Remember the grams per tray, so they come off the spools when the print ends (only with chosen trays: the printer's own mapping is not known).
                Map<String, Double> uses = new HashMap<>();
                for (int t = 0; t < count && mapping.length() > 0; t++) { int at = maps[t].getSelectedItemPosition(); if (at > 0 && toolGrams[t] > 0) uses.merge(SpoolStore.slot(trays.get(at - 1)), toolGrams[t], Double::sum); }
                spoolStore.started(name, uses, System.currentTimeMillis());
            };
            printReview(file, storage, settingsShown, reviewRows, count, wrongCount, start, () -> { setup.show(); main.post(watch[0]); });
        })); setup.show();
    }
    /** One filament on the review page: what the file was sliced for, the tray chosen, and anything to check. */
    static final class ReviewRow { String title, wants, wantsColour, tray, trayColour, problem = "", low; FilamentMatch.Level level = FilamentMatch.Level.OK; double grams; }
    /**
     * The last step before a print starts: a full page with the file, the settings and each filament's tray side by side, the
     * problems in colour, and one Start button (sent once per tap). Change settings goes back to the setup as it was.
     */
    private void printReview(JSONObject file, String storage, List<String[]> settingsShown, List<ReviewRow> rows, int count, int wrong, Runnable start, Runnable back) {
        android.app.Dialog page = new android.app.Dialog(this, dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BACKGROUND); root.setFitsSystemWindows(true);
        Button change = new Button(this); change.setText("Change settings"); change.setAllCaps(false); change.setTextSize(16); change.setTextColor(INK); change.setBackground(null); WorkshopUi.backArrow(change, INK);
        change.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); change.setMinHeight(dp(48)); change.setMinimumHeight(dp(48)); change.setPadding(dp(16), 0, dp(16), 0);
        root.addView(change, new LinearLayout.LayoutParams(-2, -2));
        ScrollView scroll = new ScrollView(this); LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), 0, dp(16), dp(16)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        A11y.heading(label(body, wrong > 0 ? "Check the filaments" : "Ready to print?", 22, INK, true));
        // The file: its picture from the printer (when it sent one), name and what the printer reports about it.
        LinearLayout fileCard = reviewCard(body, null);
        LinearLayout fileRow = new LinearLayout(this); fileRow.setOrientation(LinearLayout.HORIZONTAL); fileRow.setGravity(Gravity.CENTER_VERTICAL); fileCard.addView(fileRow);
        Bitmap picture = printer == null ? null : decodeThumbnail(printer.thumbnails.get(storage + "/" + file.optString("filename")));
        if (picture != null) { ImageView thumb = new ImageView(this); thumb.setImageBitmap(picture); thumb.setScaleType(ImageView.ScaleType.FIT_CENTER); thumb.setContentDescription("Preview of the file");
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(88), dp(88)); size.rightMargin = dp(14); fileRow.addView(thumb, size); }
        LinearLayout fileText = new LinearLayout(this); fileText.setOrientation(LinearLayout.VERTICAL); fileRow.addView(fileText, new LinearLayout.LayoutParams(0, -2, 1));
        label(fileText, StatusPresentation.clean(file.optString("filename")), 16, INK, true);
        List<String> facts = new ArrayList<>();
        if (file.optLong("print_time", -1) > 0) facts.add("about " + FeatureData.duration(file.optLong("print_time")));
        if (file.optInt("layer", 0) > 0) facts.add(file.optInt("layer") + " layers");
        double grams = 0; for (ReviewRow row : rows) grams += row.grams; if (grams > 0) facts.add(String.format(Locale.getDefault(), "about %.0f g of filament", grams));
        facts.add(storage.equals("local") ? "on the printer" : "on the USB drive");
        label(fileText, String.join(" · ", facts), 13, MUTED, false);
        // The settings chosen.
        LinearLayout settingsCard = reviewCard(body, "Settings");
        for (String[] item : settingsShown) {
            LinearLayout line = new LinearLayout(this); line.setOrientation(LinearLayout.HORIZONTAL); line.setPadding(0, dp(4), 0, dp(4)); settingsCard.addView(line);
            TextView key = label(line, item[0], 14, MUTED, false); ((LinearLayout.LayoutParams) key.getLayoutParams()).width = 0; ((LinearLayout.LayoutParams) key.getLayoutParams()).weight = 1;
            TextView value = label(line, item[1], 14, INK, true); value.setGravity(Gravity.END); ((LinearLayout.LayoutParams) value.getLayoutParams()).width = -2;
            line.setContentDescription(item[0] + ": " + item[1]); line.setFocusable(true);
        }
        // Each filament: sliced for → tray, with what to check.
        LinearLayout filamentCard = reviewCard(body, count == 1 ? "Filament" : count + " filaments");
        if (rows.isEmpty()) label(filamentCard, "The printer uses the trays the file was sliced for. To choose trays yourself, use Change settings.", 14, INK, false);
        for (int i = 0; i < rows.size(); i++) {
            ReviewRow row = rows.get(i);
            if (i > 0) { View rule = new View(this); rule.setBackgroundColor(TRACK); LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))); l.topMargin = dp(8); l.bottomMargin = dp(8); filamentCard.addView(rule, l); }
            label(filamentCard, row.title, 14, INK, true);
            TextView from = label(filamentCard, "Sliced for: " + (row.wants.isEmpty() ? "not stated in the file" : row.wants), 13, MUTED, false);
            if (row.wantsColour != null) { from.setCompoundDrawablesRelativeWithIntrinsicBounds(swatch(row.wantsColour), null, null, null); from.setCompoundDrawablePadding(dp(8)); }
            TextView to = label(filamentCard, "Prints from: " + row.tray, 14, INK, false);
            if (row.trayColour != null) { to.setCompoundDrawablesRelativeWithIntrinsicBounds(swatch(row.trayColour), null, null, null); to.setCompoundDrawablePadding(dp(8)); }
            if (!row.problem.isEmpty()) label(filamentCard, (row.level == FilamentMatch.Level.WRONG ? "✕ " : "! ") + row.problem, 13, row.level == FilamentMatch.Level.WRONG ? ERROR : AMBER, false);
            if (row.low != null) label(filamentCard, "! " + row.low, 13, AMBER, false);
        }
        if (wrong > 0) label(body, wrong + (wrong == 1 ? " filament is" : " filaments are") + " a different material from what the file was sliced for. Printing with the wrong material can fail or clog the nozzle.", 14, ERROR, true);
        label(body, "Starting moves and heats the printer. Make sure the build plate is clear and the filament is right.", 13, MUTED, false);
        // Start and Change, always on screen below the page.
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setPadding(dp(16), dp(8), dp(16), dp(12)); bar.setBackgroundColor(SURFACE);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        Button back2 = rowButton(bar, "Change settings", () -> { }, false);
        Button go = rowButton(bar, wrong > 0 ? "Start anyway" : "Start print", () -> { }, true);
        if (wrong > 0) go.setBackgroundTintList(ColorStateList.valueOf(ERROR));
        boolean[] sent = {false};
        Runnable goBack = () -> { page.dismiss(); back.run(); };
        change.setOnClickListener(v -> goBack.run()); back2.setOnClickListener(v -> goBack.run());
        go.setOnClickListener(v -> { if (sent[0]) return; sent[0] = true; go.setEnabled(false); page.dismiss(); start.run(); });
        page.setOnCancelListener(d -> back.run());   // system back: to the setup, nothing sent
        page.setContentView(root); page.show();
    }
    private LinearLayout reviewCard(LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(SURFACE); shape.setCornerRadius(dp(20)); card.setBackground(shape);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(12); parent.addView(card, layout);
        if (title != null) A11y.heading(label(card, title, 16, INK, true));
        return card;
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
        if (!Cc2Codec.heatersAdjustable(snapshot) || block != ControlState.Block.NONE) return;
        boolean during = !Cc2Codec.idle(snapshot);
        LinearLayout body = dialogBody(); label(body, (during ? "This changes the heaters of the running print right away. A wrong temperature can spoil the print or weaken how it sticks to the bed. " : "")
            + "Nozzle 0–300°C, bed 0–100°C. Enter 0 to turn that heater off. This does not change the sliced file; set that in the filament's Settings… on the Slice screen. "
            + "The chamber has only a temperature sensor, so it has no target to set. Set the chamber fan under Fan setting….", 14, INK, false);
        EditText nozzle = input(body, "Nozzle target °C", false), bed = input(body, "Bed target °C", false); nozzle.setInputType(InputType.TYPE_CLASS_NUMBER); bed.setInputType(InputType.TYPE_CLASS_NUMBER);
        JSONObject n = snapshot.optJSONObject("extruder"), b = snapshot.optJSONObject("heater_bed"); nozzle.setText(String.valueOf(n == null ? 0 : n.optInt("target"))); bed.setText(String.valueOf(b == null ? 0 : b.optInt("target")));
        // Presets from the loaded CANVAS materials (and common ones): a tap fills both fields; nothing is sent until Set heaters.
        int presetsAt = body.indexOfChild(nozzle) - 1;   // above the nozzle field's caption
        TextView presetsLabel = label(body, "Tap a preset to fill in both fields:", 13, MUTED, false); body.removeView(presetsLabel); body.addView(presetsLabel, presetsAt);
        HorizontalScrollView presetScroll = new HorizontalScrollView(this); presetScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presets = new LinearLayout(this); presets.setOrientation(LinearLayout.HORIZONTAL); presetScroll.addView(presets);
        for (TemperaturePresets.Preset preset : TemperaturePresets.from(printer == null ? null : printer.canvas)) {
            Button chip = new Button(this); chip.setText(preset.text()); chip.setAllCaps(false); chip.setTextSize(13); styleButton(chip);
            chip.setMinHeight(dp(48)); chip.setMinimumHeight(dp(48)); chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setContentDescription("Fill in " + preset.text() + (preset.fromTray ? ", from the loaded tray" : ""));
            chip.setOnClickListener(v -> { nozzle.setText(String.valueOf(preset.nozzle)); bed.setText(String.valueOf(preset.bed)); });
            LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.rightMargin = dp(8); presets.addView(chip, gap);
        }
        body.addView(presetScroll, presetsAt + 1);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(during ? "Change heaters during the print?" : "Heater temperatures now").setView(body).setNegativeButton("Cancel", null).setPositiveButton("Set heaters", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { try { int nt = Integer.parseInt(nozzle.getText().toString()), bt = Integer.parseInt(bed.getText().toString()); Cc2Codec.temperatureRequest(0, nt, bt); if (printer != null) printer.temperatures(nt, bt); dialog.dismiss(); } catch (Exception error) { nozzle.setError("Nozzle 0–300°C; bed 0–100°C"); } })); dialog.show();
    }
    private void fanDialog() {
        LinearLayout body = dialogBody(); Spinner kind = spinner(body, new String[] {"Part cooling", "Auxiliary", "Chamber"}); A11y.name(kind, "Fan");
        TextView[] shown = new TextView[1]; SeekBar amount = WorkshopUi.slider(body, "Fan speed", shown, TEAL, TRACK, INK, MUTED); amount.setMax(100);
        TextView value = shown[0]; value.setText("0 %");
        amount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar bar, int progress, boolean user) { value.setText(progress + " %"); } public void onStartTrackingTouch(SeekBar bar) { } public void onStopTrackingTouch(SeekBar bar) { } });
        // The slider starts at the chosen fan's current speed (as reported), so Set fan without moving it changes nothing.
        String[] keys = {"fan", "aux_fan", "box_fan"};
        kind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { amount.setProgress(Math.max(0, fanPercent(keys[position]))); value.setText(amount.getProgress() + " %"); }
            public void onNothingSelected(AdapterView<?> parent) { } });
        amount.setProgress(Math.max(0, fanPercent(keys[0])));
        new AlertDialog.Builder(this).setTitle("Fan setting").setView(body).setNegativeButton("Cancel", null).setPositiveButton("Set fan", (dialog, which) -> { if (printer != null) printer.fan(new String[] {"fan", "aux_fan", "box_fan"}[kind.getSelectedItemPosition()], amount.getProgress()); }).show();
    }
    private void speedDialog() { new AlertDialog.Builder(this).setTitle("Print speed mode").setItems(new String[] {"Silent", "Balanced", "Sport", "Ludicrous"}, (dialog, which) -> {
        new AlertDialog.Builder(this).setTitle("Change print speed?").setMessage("This changes the speed mode of the current print. On some firmware, a filament change can reset it.").setNegativeButton("Cancel", null).setPositiveButton("Apply", (d, w) -> { if (printer != null) printer.speed(which); }).show();
    }).setNegativeButton("Cancel", null).show(); }
    private void buildCamera() {
        // Order: the cloud card and the local card trade places in renderCamera (local first when the printer is on this network).
        LinearLayout cloudCard = card("Cloud camera"); cameraCloudCard = cloudCard;
        cloudCameraHint = label(cloudCard, "", 13, MUTED, false);
        cameraFix = button(cloudCard, "Open Settings to sign in", () -> openSettings(true)); cameraFix.setVisibility(View.GONE);
        // Opening it asks for the one-time cloud-control agreement first, like every other cloud action.
        cloudCamera = button(cloudCard, "Watch through the Elegoo cloud", () -> {
            if (printer == null || printer.cloudSerial.isEmpty()) return;
            String serialNumber = printer.cloudSerial, printerName = printer.cloudName;
            cloudGate(true, () -> {
                // The Print tab's video leaves the channel first (one join per account), then the full screen joins.
                boolean leaving = cloudVideo != null || visitRunning(); stopCamera(); endVisit();
                Runnable open = () -> startActivity(new Intent(this, CloudCameraActivity.class).putExtra(CloudCameraActivity.EXTRA_SERIAL, serialNumber).putExtra(CloudCameraActivity.EXTRA_NAME, printerName));
                if (leaving) main.postDelayed(open, 1_500); else open.run();
            });
        });
        cameraLocalCard = card("Local camera");
        localToggle = button(cameraLocalCard, "", () -> { localCameraOpen = !localCameraOpen; render(); });
        localCameraBody = new LinearLayout(this); localCameraBody.setOrientation(LinearLayout.VERTICAL); cameraLocalCard.addView(localCameraBody);
        LinearLayout card = localCameraBody;
        cameraInfo = label(card, "Watch the printer's camera over local Wi-Fi, or through your home VPN.", 14, MUTED, false);
        cameraStart = button(card, "Start camera", this::toggleCamera);
        cameraImage = new ImageView(this); cameraImage.setContentDescription("Live printer camera"); cameraImage.setScaleType(ImageView.ScaleType.FIT_CENTER); cameraImage.setBackgroundColor(Color.BLACK); card.addView(cameraImage, new LinearLayout.LayoutParams(-1, dp(240)));
        // The picture's size toggles between the normal and the large height; the button names the size it switches to.
        LinearLayout pictureRow = row(card);
        rowButton(pictureRow, "Full screen", this::cameraFullScreen, false).setContentDescription("Full screen: zoom and brighten");
        cameraImage.setOnClickListener(v -> cameraFullScreen()); A11y.clickLabel(cameraImage, "Show the camera full screen");
        cameraSnapshot = rowButton(pictureRow, "Snapshot…", this::saveSnapshot, false); cameraSnapshot.setContentDescription("Save a snapshot to the phone");
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
        cameraAddress = input(settings, "Camera address on this printer", false); cameraAddress.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        cameraQuery = button(settings, "Get camera address from printer", () -> { if (printer != null) printer.camera(); });
        label(settings, "The camera stops when you leave this tab or the app goes to the background. Monitoring keeps going. Redirects and camera addresses on other devices are blocked.", 13, MUTED, false);
        LinearLayout added = card("Added cameras");
        label(added, "A webcam, ESP32-CAM, IP camera or phone beside the printer (an MJPEG stream or JPEG snapshot). One can take the print pictures, in cloud mode too.", 13, MUTED, false);
        extraInfo = A11y.polite(label(added, "", 13, INK, false)); extraInfo.setVisibility(View.GONE);
        extraImage = new ImageView(this); extraImage.setContentDescription("Picture from an added camera"); extraImage.setScaleType(ImageView.ScaleType.FIT_CENTER); extraImage.setBackgroundColor(Color.BLACK);
        added.addView(extraImage, new LinearLayout.LayoutParams(-1, dp(220))); extraImage.setVisibility(View.GONE);
        extraImage.setOnClickListener(v -> cameraFullScreen()); A11y.clickLabel(extraImage, "Show the camera full screen");
        extraList = new LinearLayout(this); extraList.setOrientation(LinearLayout.VERTICAL); added.addView(extraList);
        button(added, "Add a camera…", () -> editExtraCamera(null));
        renderExtraCameras();
        // Lining up and checking, as quiet rows: the check is diagnostics, kept last.
        LinearLayout tools = card(null);
        listRow(tools, "Line up the camera in 3D…", "Once, against the real bed, with or without a print", false, () -> {
            if (printer == null) return;
            Runnable open = () -> { stopCamera(); startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true).putExtra(GcodeViewerActivity.EXTRA_ALIGN, true).putExtra(GcodeViewerActivity.EXTRA_CAMERA, "")); };
            cloudGate(!printer.ready() && printer.usingCloud(), open);
        });
        listRow(tools, "Camera check", "Tries each camera once; sends nothing to the printer", true, () -> cameraCheckButton.performClick());
        listRow(tools, "Camera log…", "What each camera did, with times", true, () -> startActivity(new Intent(this, LogsActivity.class).putExtra(LogsActivity.EXTRA_AREA, Diagnostics.CAMERA)));
        // Holders: render() keeps their state; the check's result shows under the rows.
        LinearLayout checkHolder = new LinearLayout(this); checkHolder.setVisibility(View.GONE); tools.addView(checkHolder);
        cameraCheckButton = button(checkHolder, "Run camera check", this::cameraCheck);
        // Cloud mode only: the printer has not joined its video channel on its own; Elegoo's camera question may be what starts it.
        cameraAskButton = button(tools, "Try: ask the printer for its camera…", this::askCloudCamera);
        cameraCheckText = label(tools, "", 12, INK, false); cameraCheckText.setTextIsSelectable(true); cameraCheckText.setTypeface(Typeface.MONOSPACE); cameraCheckText.setVisibility(View.GONE);
    }
    private void toggleCamera() {
        if (cameraPlayer != null) { stopCamera(); return; }
        try {
            boolean alongsideCloud = printer != null && !printer.ready() && printer.localCamera();
            String address = host.getText().toString().trim(), url = alongsideCloud ? printer.localCameraUrl() : FeatureData.cameraUrl(address, cameraAddress.getText().toString().trim());
            NetworkRoute route = alongsideCloud ? NetworkRoute.local(this) : NetworkRoute.select(this, remoteMode()); cameraInfo.setText("Opening camera…"); cameraRoute = route;
            cameraRouteWatch = route.watch(() -> main.post(() -> { if (cameraRoute == route && cameraPlayer != null) { stopCamera(); cameraInfo.setText("Home VPN route changed. Enable the VPN and restart the camera."); } }));
            long openedAt = android.os.SystemClock.elapsedRealtime(); boolean[] first = {true};
            Diagnostics.note(Diagnostics.CAMERA, "camera stream opening " + (alongsideCloud ? "on Wi-Fi alongside the cloud" : remoteMode() ? "through the home VPN" : "on the local network"));
            cameraPlayer = new MjpegPlayer(url, route.http(), new MjpegPlayer.Listener() {
                public void frame(Bitmap image) { if (isDestroyed()) return; lastFrame = image;
                    if (first[0]) { first[0] = false; Diagnostics.note(Diagnostics.CAMERA, "camera stream: first picture after " + (android.os.SystemClock.elapsedRealtime() - openedAt) + " ms, " + image.getWidth() + "x" + image.getHeight()); } cameraImage.setVisibility(View.VISIBLE); cameraImage.setImageBitmap(image); cameraInfo.setText("Live · up to 5 pictures a second"); cameraSnapshot.setEnabled(true);
                    monitorImage.setVisibility(View.VISIBLE); monitorImage.setImageBitmap(image); monitorCameraInfo.setText(""); monitorCameraInfo.setVisibility(View.GONE);
                    if (fullView != null) fullView.setImageBitmap(image);
                    tone(image);
                    // While this stream is open the printer may not serve another: the print's pictures come from it.
                    if (printer != null) printer.offerFrame(image); }
                public void error(String text) { Diagnostics.note(Diagnostics.CAMERA, "camera stream failed after " + (android.os.SystemClock.elapsedRealtime() - openedAt) + " ms" + (first[0] ? ", before any picture" : "") + ": " + Diagnostics.scrub(text)); stopCamera(); cameraInfo.setText(text); monitorCameraInfo.setText(text); monitorCameraInfo.setVisibility(View.VISIBLE); }
            }); cameraStart.setText("Stop camera"); monitorCameraStart.setText("Stop camera");
        } catch (Exception error) { stopCamera(); cameraInfo.setText(remoteMode() ? "Enable your home VPN, then use the camera address for the home printer. The VPN route must allow the camera port." : "Enter the camera address for the selected printer, and connect the phone to the same Wi-Fi network as the printer."); monitorCameraInfo.setText(cameraInfo.getText() + " (Camera tab, then Camera settings.)"); monitorCameraInfo.setVisibility(View.VISIBLE); }
    }
    // ------------------------------------------------------------------ Camera: tone, full screen
    private LinearLayout darkRow;
    private boolean darkDismissed;
    private ZoomImageView fullView;
    private android.app.Dialog fullDialog;
    private float lastGain = 1;
    /** Brightens a dark picture on this phone (when chosen), and offers the light when the chamber is dark. */
    private void tone(Bitmap image) {
        double luminance;
        try { Bitmap small = Bitmap.createScaledBitmap(image, 32, 24, true); int[] pixels = new int[32 * 24]; small.getPixels(pixels, 0, 32, 0, 0, 32, 24); luminance = CameraTone.luminance(pixels); }
        catch (RuntimeException unreadable) { return; }
        float gain = settings.getBoolean("cameraBrighten", true) ? CameraTone.gain(luminance) : 1;
        if (Math.abs(gain - lastGain) > 0.05f) {
            lastGain = gain;
            android.graphics.ColorFilter filter = gain <= 1.01f ? null : new android.graphics.ColorMatrixColorFilter(CameraTone.matrix(gain));
            for (ImageView view : new ImageView[] {monitorImage, cameraImage, fullView}) if (view != null) view.setColorFilter(filter);
        }
        boolean dark = luminance < CameraTone.DARK && !darkDismissed && lightOn.isEnabled() && !lightIsOn;
        if (darkRow != null) darkRow.setVisibility(dark ? View.VISIBLE : View.GONE);
    }
    /** The camera full screen: pinch or double tap to zoom, drag to move, and buttons for the same and for brightening. */
    private void cameraFullScreen() {
        if (cloudVideo != null) { cloudCamera.performClick(); return; }   // the cloud camera's own full screen (this one stops first)
        if (lastFrame == null) return;
        android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this); stage.setBackgroundColor(Color.BLACK);
        ZoomImageView view = new ZoomImageView(this); view.setContentDescription("Live printer camera, full screen"); view.setImageBitmap(lastFrame);
        stage.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setPadding(dp(8), dp(8), dp(8), dp(8)); bar.setBackgroundColor(0x99000000);
        Button out = fullButton(bar, "−", () -> view.zoomBy(1 / 1.5f)); out.setContentDescription("Zoom out");
        Button in = fullButton(bar, "+", () -> view.zoomBy(1.5f)); in.setContentDescription("Zoom in");
        Button brighten = fullButton(bar, "", () -> { });
        Runnable showBrighten = () -> { boolean on = settings.getBoolean("cameraBrighten", true); brighten.setText(on ? "Brighten: on" : "Brighten: off"); A11y.state(brighten, on ? "On" : "Off"); };
        brighten.setOnClickListener(v -> { settings.edit().putBoolean("cameraBrighten", !settings.getBoolean("cameraBrighten", true)).apply(); lastGain = -1; showBrighten.run(); if (lastFrame != null) tone(lastFrame); });
        showBrighten.run();
        fullButton(bar, "Close", dialog::dismiss);
        stage.addView(bar, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        dialog.setContentView(stage);
        dialog.setOnDismissListener(d -> { if (fullDialog == dialog) { fullDialog = null; fullView = null; } });
        fullView = view; fullDialog = dialog; lastGain = -1; tone(lastFrame);
        dialog.show();
    }
    private Button fullButton(LinearLayout bar, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setBackground(null);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setMinWidth(dp(56)); b.setMinimumWidth(dp(56)); b.setOnClickListener(v -> action.run());
        bar.addView(b, new LinearLayout.LayoutParams(0, -2, 1)); return b;
    }
    private void stopCamera() {
        android.app.Dialog full = fullDialog; fullDialog = null; fullView = null; if (full != null && full.isShowing()) full.dismiss(); AutoCloseable watcher = cameraRouteWatch; cameraRouteWatch = null; cameraRoute = null; if (watcher != null) try { watcher.close(); } catch (Exception ignored) { } MjpegPlayer player = cameraPlayer; cameraPlayer = null; if (player != null) player.close(); ExtraCameras.Feed added = extraFeed; extraFeed = null; extraShown = null; if (added != null) added.close(); if (cameraStart != null) cameraStart.setText("Start camera"); if (monitorCameraStart != null) monitorCameraStart.setText("Watch camera"); stopCloudVideo(); }
    /**
     * Camera check: the facts on this phone, then one camera-access request to Elegoo (signed in) and one picture from the
     * printer's camera stream (local connection only; in cloud mode the printer's camera port is off and left alone).
     * Nothing is sent to the printer. The result is shown and noted in the diagnostics report, without codes or addresses.
     */
    private void cameraCheck() {
        if (cameraChecking || printer == null) return;
        cameraChecking = true; cameraCheckButton.setEnabled(false); cameraCheckText.setVisibility(View.VISIBLE); cameraCheckText.setText("Checking…");
        List<String> out = new ArrayList<>();
        boolean ready = printer.ready(), cloud = !ready && printer.usingCloud();
        String version = ""; try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        out.add("App " + version + " · Android " + android.os.Build.VERSION.RELEASE);
        out.add("Connection: " + (ready ? "local network" : cloud ? "Elegoo cloud" : printer.connecting() ? "connecting" : "not connected"));
        android.content.pm.PackageInfo webView = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this);
        out.add("WebView: " + (webView == null ? "not found" : webView.versionName) + (CloudVideo.supported() ? "" : " (too old for the cloud camera)"));
        out.add("Print tab camera: " + (settings.getBoolean("printCameraHidden", false) ? "hidden" : "shown") + "; starts by itself: local " + (settings.getBoolean("monitorCamera", false) ? "yes" : "no")
            + ", cloud " + (settings.getBoolean("monitorCloudCamera", false) ? "yes" : "no") + "; playing now: "
            + (cameraPlayer != null ? "local stream" : cloudVideo != null ? (cloudVideo.playing() ? "cloud video" : "cloud video, connecting") : "nothing"));
        PrintRecorder.Recording recording = printer.recorder == null ? null : printer.recorder.current();
        long mono = android.os.SystemClock.elapsedRealtime();
        if (recording == null) out.add("Pictures: no print is being recorded" + (settings.getBoolean("recordPrints", true) ? "" : " (recording prints is off in Settings)"));
        else { long since = printer.recorder.secondsSincePicture(mono);
            out.add("Pictures: " + (recording.pictureLayers > 0 ? recording.pictureWords().toLowerCase(Locale.ROOT) : recording.pictureMinutes == 0 ? "off for this print" : "every " + recording.pictureMinutes + " min") + "; " + recording.pictures + " so far; last "
                + (since < 0 ? "none this app run" : since / 60 + " min ago") + (printer.recorder.pictureDue(mono) ? "; one is due now" : "")); }
        out.add("Picture source now: " + (ready ? "the printer's camera on this network" : cloud && printer.localCamera() ? "the printer's camera alongside the cloud"
            : cloud ? (cloudVideo != null && cloudVideo.playing() ? "the Print tab's cloud video" : "none: in cloud mode only an open cloud video gives pictures") : "none: not connected"));
        boolean signedIn = printer.cloudSignedIn, chosen = !printer.cloudSerial.isEmpty();
        if (CloudVideo.emptyStreak() > 0) out.add("Cloud video: the printer did not join its channel " + CloudVideo.emptyStreak() + " time(s) in a row" + (CloudVideo.emptyStreak() >= 2 ? "; restart the printer" : ""));
        out.add("Cloud picture visits: " + (settings.getBoolean(PictureVisits.PREF, true) ? "on" : "off") + (visitRunning() ? ", one running now" : "") + "; in the background: "
            + (CloudFrameGrab.broken() != null ? "cannot run on this phone (" + CloudFrameGrab.broken() + ")" : printer != null && printer.nativePictures() ? "on" : "waiting after two empty tries"));
        out.add("Elegoo cloud: " + (signedIn ? "signed in" : "not signed in") + ", printer " + (chosen ? "chosen" : "not chosen") + ", "
            + (printer.cloudOnline == 1 ? "online" : printer.cloudOnline == 0 ? "offline" : "online state unknown") + ", cloud agreement " + (settings.getBoolean("cloudControlUnderstood", false) ? "accepted" : "not accepted yet"));
        boolean streamOpen = cameraPlayer != null;
        String url = null; NetworkRoute route = null; String routeProblem = "";
        if (ready) try { url = FeatureData.cameraUrl(printer.host(), cameraAddress.getText().toString().trim()); route = NetworkRoute.select(this, remoteMode()); }
            catch (Exception invalid) { routeProblem = Diagnostics.scrub(invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage()); url = null; }
        String cameraUrl = url, problem = routeProblem; NetworkRoute checkRoute = route;
        boolean alongside = printer.localCamera();
        checks.execute(() -> {
            out.add("Camera access from Elegoo: " + (signedIn ? CameraCheck.cloudAccess(this) : "skipped (not signed in)"));
            if (ready && streamOpen) out.add("Printer's camera on this network: a stream is already open on this phone, so the test is skipped (the printer serves one stream at a time)");
            else if (ready && cameraUrl == null) out.add("Printer's camera on this network: the camera address or route is not usable (" + problem + "); check Camera settings");
            else if (ready) out.add("Printer's camera on this network: " + CameraCheck.localStream(cameraUrl, checkRoute));
            else if (cloud) out.add("Printer's camera on this network: skipped; in cloud mode the printer turns its camera port off" + (alongside ? " (the local camera alongside the cloud is on and was tried once this app run)" : ""));
            else out.add("Printer's camera on this network: skipped (not connected locally)");
            main.post(() -> {
                cameraChecking = false; if (isDestroyed()) return;
                Diagnostics.note(Diagnostics.CAMERA, "camera check: " + String.join(" | ", out));
                StringBuilder text = new StringBuilder(String.join("\n", out)).append("\n\nRecent camera events:");
                List<String> recent = Diagnostics.recent(Diagnostics.CAMERA, 12);
                for (String line : recent) if (!line.contains("camera check:")) text.append("\n").append(line);
                text.append("\n\nThe full camera log: Camera log below, or Settings › Help & diagnostics › View logs.");
                cameraCheckText.setText(text); cameraCheckButton.setEnabled(true);
            });
        });
    }
    /** Sends Elegoo's read-only camera question (1042) once through the cloud, shows the answer, then offers the cloud camera. */
    private void askCloudCamera() {
        if (printer == null) return;
        new AlertDialog.Builder(this).setTitle("Ask the printer for its camera?")
            .setMessage("Sends the printer one question through the Elegoo cloud: \u201cwhere is your camera video?\u201d (the same read-only question Elegoo's printer page asks on Wi-Fi). It changes no setting. "
                + "The printer has not joined its video channel on its own; this checks whether the question makes it start. It is sent once and never repeated by itself.")
            .setNegativeButton("Cancel", null).setPositiveButton("Ask", (d, w) -> cloudGate(true, () -> {
                cameraAskButton.setEnabled(false); cameraCheckText.setVisibility(View.VISIBLE); cameraCheckText.setText("Asking the printer through the Elegoo cloud…");
                boolean sent = printer.askCloudCamera(answer -> {
                    if (isDestroyed()) return;
                    cameraAskButton.setEnabled(true);
                    cameraCheckText.setText("Camera question: " + answer + "\n\nNext: open the cloud camera now. The camera log then shows whether the printer joined its video channel (\u201cother members\u201d).");
                    new AlertDialog.Builder(this).setTitle("The printer answered").setMessage(answer + "\n\nOpen the cloud camera to see whether the printer joined its video channel, or try the printer's own camera on this Wi-Fi now (one try per app run; asking first may have started it).")
                        .setNegativeButton("Not now", null).setPositiveButton("Open camera", (d2, w2) -> cloudCamera.performClick())
                        .setNeutralButton("Try its Wi-Fi camera", (d2, w2) -> {
                            printer.tryLocalCamera(); cameraCheckText.setText("Trying the printer's own camera on this Wi-Fi…");
                            main.postDelayed(() -> { if (!isDestroyed() && printer != null && printer.localCameraText != null) cameraCheckText.setText("Printer's own camera on Wi-Fi: " + printer.localCameraText); }, 14_000);
                        }).show();
                });
                if (!sent) { cameraAskButton.setEnabled(true); cameraCheckText.setText("Not sent: this needs the printer watched through the Elegoo cloud with fresh status, and no other cloud command in progress."); }
            })).show();
    }
    private Button addLoad;
    /** Settings › Home Assistant: the user's broker, the printer's status published read only with discovery. */
    private Section haSection; private LinearLayout recentPrints; private TextView recentEmpty;
    private TextView haStatus, projectsLine, reach, fileSource;
    private TextView snapshotChip, fullChip; private LiveToolpathPreview toolpathPreview;
    /** The Print tab's preview shows the live toolpath instead of the camera (Print options). */
    private boolean toolpathPreviewOn() { return "toolpath".equals(settings.getString("printPreview", "camera")); }
    private void openLiveToolpath() { startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true)); }
    private void choosePreview(boolean toolpath) {
        settings.edit().putString("printPreview", toolpath ? "toolpath" : "camera").putBoolean("printCameraHidden", false).apply();
        if (toolpath && (cameraPlayer != null || cloudVideo != null || extraFeed != null) && opened[PRINT] != cameraSection) stopCamera();
        if (!toolpath) { toolpathPreview.release(); monitorCameraTried = false; }
        render();
    }
    private Button monitorHide; private LinearLayout printNav; private NavRow navToolpath, navSlice, navOptions, navRecording, navAgain, navCanvas, navRefresh; private long fileSourceAt; private String fileSourceFor = ""; private String feedbackTarget = "";
    private void buildHomeAssistant() {
        currentSection = haSection.body;
        LinearLayout card = card("Home Assistant");
        label(card, "Progress, temperatures, state and the latest picture to your MQTT broker; the sensors appear in Home Assistant by themselves. Read only: Home Assistant cannot control the printer through this.", 13, MUTED, false);
        CompoundButton on = checkbox(card, "Send to Home Assistant", settings.getBoolean("haEnabled", false));
        // The broker's details only while it is on (or being set up), so the page stays short.
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); card.addView(form);
        LinearLayout outerCard = card; card = form;
        EditText host = input(card, "Broker address (for example homeassistant.local or 192.168.1.10)", false); host.setText(settings.getString("haHost", ""));
        EditText port = input(card, "Port", false); port.setInputType(InputType.TYPE_CLASS_NUMBER); port.setText(String.valueOf(settings.getInt("haPort", 1883)));
        CompoundButton tls = checkbox(card, "Use TLS (usually port 8883)", settings.getBoolean("haTls", false));
        EditText user = input(card, "User name (optional)", false); user.setText(settings.getString("haUser", ""));
        EditText password = input(card, "Password (kept encrypted on this phone; leave empty to keep the saved one)", true);
        card = outerCard;
        form.setVisibility(on.isChecked() || !settings.getString("haHost", "").isEmpty() ? View.VISIBLE : View.GONE);
        on.setOnCheckedChangeListener((view, checked) -> { if (checked) form.setVisibility(View.VISIBLE); });
        haStatus = label(card, "", 13, MUTED, false);
        button(card, "Save", () -> {
            String address = host.getText().toString().trim(); int number;
            try { number = Integer.parseInt(port.getText().toString().trim()); } catch (NumberFormatException bad) { number = 0; }
            if (on.isChecked() && (address.isEmpty() || number <= 0 || number > 65535)) { message("Enter the broker's address and a port from 1 to 65535."); return; }
            settings.edit().putBoolean("haEnabled", on.isChecked()).putString("haHost", address).putInt("haPort", number <= 0 ? 1883 : number)
                .putBoolean("haTls", tls.isChecked()).putString("haUser", user.getText().toString().trim()).apply();
            String secret = password.getText().toString();
            if (!secret.isEmpty() && !address.isEmpty()) try { new CredentialStore(this).saveSecret("homeassistant:" + address, secret); } catch (Exception failed) { message("The password could not be saved securely on this phone."); return; }
            password.setText("");
            Diagnostics.note(Diagnostics.HOME_ASSISTANT, on.isChecked() ? "turned on" + (tls.isChecked() ? " (TLS)" : "") : "turned off");
            message(on.isChecked() ? "Saved. The next status update goes to Home Assistant." : "Saved. Home Assistant updates are off.");
        });
    }
    /** "Everything the printer reports": the latest status, attributes and trays as sorted "path = value" lines, live while open. */
    private Section reportsSection; private TextView reportsText, reportsCount; private String reportsShown = ""; private long reportsAt;
    private void buildReports() {
        currentSection = reportsSection.body;
        LinearLayout card = card(null);
        label(card, "What the printer sends: status, its attributes and the CANVAS trays. Serial numbers, addresses, names and keys are hidden. It updates while this page is open.", 13, MUTED, false);
        reportsCount = label(card, "", 12, MUTED, false);
        rowButton(row(card), "Copy", () -> {
            android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Printer values", reportsShown));
            message("Copied the printer's values.");
        }, false);
        reportsText = label(card, "", 12, INK, false); reportsText.setTypeface(Typeface.MONOSPACE); reportsText.setTextIsSelectable(true);
    }
    private void renderReports() {
        if (reportsSection == null || opened[PRINTER] != reportsSection || printer == null) return;
        long now = android.os.SystemClock.elapsedRealtime(); if (now - reportsAt < 2_000) return; reportsAt = now;
        List<String> lines = new ArrayList<>();
        boolean ready = printer.ready();
        if (snapshot.length() > 0) { lines.add("# status (" + (ready ? "local network" : "Elegoo cloud") + ")"); lines.addAll(StatusPresentation.flatten(snapshot)); }
        if (ready && printer.attributes != null && printer.attributes.length() > 0) { lines.add(""); lines.add("# attributes (1001)"); lines.addAll(StatusPresentation.flatten(printer.attributes)); }
        if (printer.canvas != null && printer.canvas.length() > 0) { lines.add(""); lines.add("# CANVAS trays"); lines.addAll(StatusPresentation.flatten(printer.canvas)); }
        String text = lines.isEmpty() ? "No status yet. Connect, or watch through the Elegoo cloud, then open this again." : String.join("\n", lines);
        if (text.equals(reportsShown)) return;
        reportsShown = text; reportsText.setText(text);
        long values = lines.stream().filter(l -> l.contains(" = ")).count();
        reportsCount.setText(values + " values · updated " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
    }
    /** Picks the CANVAS tray to load from (with its reported filament when known), then makes the repaired copy. */
    private void addLoadDialog() {
        if (printer == null || printer.selectedFile == null) return;
        String[] trays = new String[4];
        for (int i = 0; i < 4; i++) trays[i] = "Tray " + (i + 1) + TrayPlan.trayWords(printer.canvas, i);
        new AlertDialog.Builder(this).setTitle("Load filament from which tray?")
            .setItems(trays, (d, which) -> new AlertDialog.Builder(this).setTitle("Add the filament load?")
                .setMessage("Makes a new copy of this file with the Centauri Carbon 2's filament load added before its purge line: heat the nozzle, load from tray " + (which + 1)
                    + " (M6211) and select it (T" + which + "), as the printer's own profile does. The original file is not changed. Re-slicing with a Centauri Carbon 2 profile is still the better fix.")
                .setNegativeButton("Cancel", null).setPositiveButton("Add", (d2, w2) -> printer.addFilamentLoad(which)).show())
            .setNegativeButton("Cancel", null).show();
    }
    private void stopCloudVideo() {
        CloudVideo video = cloudVideo; cloudVideo = null;
        if (video == null) return;
        // Which part of the screen stopped it (method and line), for a video that stops right after it started.
        StackTraceElement[] at = new Throwable().getStackTrace(); List<String> callers = new ArrayList<>();
        for (int i = 1; i < Math.min(at.length, 4); i++) callers.add(at[i].getMethodName() + ":" + at[i].getLineNumber());
        Diagnostics.note(Diagnostics.CAMERA, "Print tab cloud video: stop asked by " + String.join(" < ", callers));
        video.stop();
    }
    /** Cloud mode: plays the printer's camera through the Elegoo cloud right here under the progress bar (after the one-time cloud agreement). */
    private void startCloudVideo() {
        if (printer == null || printer.cloudSerial.isEmpty() || cloudVideo != null) return;
        // A picture visit holds the channel (one join per account): end it, then start once it has left.
        if (visitRunning()) { endVisit(); main.postDelayed(this::startCloudVideo, 1_500); return; }
        // The cloud camera screen may still hold the channel (one join per account); start once it has let go.
        if (CloudCameraActivity.holdsChannel()) { main.postDelayed(() -> { if (active && page == PRINT && cloudVideo == null) startCloudVideo(); }, 3_000); return; }
        if (!CloudVideo.supported()) { monitorCameraInfo.setText("The camera needs a newer Android System WebView. Update it from the Play Store."); monitorCameraInfo.setVisibility(View.VISIBLE); return; }
        String serialNumber = printer.cloudSerial;
        cloudGate(true, () -> {
            stopCamera();
            CloudVideo[] self = new CloudVideo[1];
            self[0] = new CloudVideo(this, serialNumber, () -> printer, new CloudVideo.Listener() {
                public void status(String text, boolean problem) {
                    if (cloudVideo != self[0] || isDestroyed()) return;
                    monitorCameraInfo.setText(text); monitorCameraInfo.setTextColor(problem ? ERROR : MUTED); monitorCameraInfo.setVisibility(View.VISIBLE);
                    if (problem) { stopCloudVideo(); render(); }
                }
                public void playing() { if (cloudVideo != self[0] || isDestroyed()) return; monitorCameraInfo.setText(""); monitorCameraInfo.setVisibility(View.GONE); render(); }
                public void snapshot(byte[] jpeg) {
                    Bitmap picture = jpeg == null ? null : android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (picture == null) { pendingCloudSnapshot = false; message("No camera picture yet. Wait for the video to appear, then try again."); return; }
                    lastFrame = picture; saveSnapshot();
                }
            });
            cloudVideo = self[0];
            monitorFrame.addView(cloudVideo.view(), 1, new FrameLayout.LayoutParams(-1, -1));   // over the last picture, under the badge and buttons
            cloudVideo.start(); render();
        });
    }
    /** Recording pictures: the default interval (Settings) or the running print's own (Print tab). 0 = none. */
    private Button pictureDefault;
    /** The choices: minutes (by time) then layers (at layer changes, for smooth timelapses). {minutes, layers}. */
    private void showPictureDefault() {
        if (pictureDefault == null) return;
        String id = PictureSettings.camera(this);
        pictureDefault.setText("Recording pictures: " + PictureSettings.words(PictureSettings.minutes(this, id), PictureSettings.layers(this, id), PictureSettings.far(this, id)).toLowerCase(Locale.ROOT) + "…");
    }
    /**
     * Recording pictures, all in one place: which camera takes them, when (off, every N minutes, or every N layers), and for
     * layers the moment (any, or with the head far from the camera). For new prints (kept per camera) or for the running print.
     */
    private void pictureDialog(boolean forDefault) {
        PrintRecorder.Recording now = printer == null || printer.recorder == null ? null : printer.recorder.current();
        if (!forDefault && now == null) return;
        LinearLayout body = dialogBody();
        // The camera: the printer's own, or an added one (the choice applies from the next picture on).
        List<ExtraCameras.Camera> added = ExtraCameras.list(this);
        Spinner camera = null;
        if (!added.isEmpty()) {
            label(body, "Camera", 13, MUTED, true);
            String[] names = new String[added.size() + 1]; names[0] = "The printer's"; int chosenCamera = 0;
            for (int i = 0; i < added.size(); i++) { names[i + 1] = added.get(i).name; if (added.get(i).pictures) chosenCamera = i + 1; }
            camera = spinner(body, names); camera.setSelection(chosenCamera);
        }
        label(body, "When", 13, MUTED, true);
        Spinner when = spinner(body, new String[] {"Off", "By time", "By layers"});
        LinearLayout byTime = new LinearLayout(this); byTime.setOrientation(LinearLayout.VERTICAL); body.addView(byTime);
        String[] minuteNames = new String[PictureSettings.MINUTES.length]; for (int i = 0; i < minuteNames.length; i++) minuteNames[i] = PictureSettings.words(PictureSettings.MINUTES[i], 0, false);
        Spinner minutes = spinner(byTime, minuteNames);
        LinearLayout byLayers = new LinearLayout(this); byLayers.setOrientation(LinearLayout.VERTICAL); body.addView(byLayers);
        String[] layerNames = new String[PictureSettings.LAYERS.length]; for (int i = 0; i < layerNames.length; i++) layerNames[i] = PictureSettings.words(0, PictureSettings.LAYERS[i], false);
        Spinner layers = spinner(byLayers, layerNames);
        label(byLayers, "Moment in the layer", 13, MUTED, true);
        Spinner moment = spinner(byLayers, new String[] {"Any moment", "Head far from the camera"});
        TextView about = label(byLayers, "", 12, MUTED, false);
        // Where the chosen camera is: picked in a list over this dialog, which stays open (the spot is kept at once).
        Spinner[] cameraPick = new Spinner[1];
        java.util.function.Supplier<ExtraCameras.Camera> pickedCamera = () -> {
            if (cameraPick[0] == null) return ExtraCameras.forPictures(this);
            int at = cameraPick[0].getSelectedItemPosition(); return at > 0 && at <= added.size() ? added.get(at - 1) : null;
        };
        Button[] position = new Button[1];
        Runnable showPosition = () -> position[0].setText("Camera position: " + cameraSpotName(pickedCamera.get()) + "…");
        position[0] = button(byLayers, "", () -> pictureCameraPosition(pickedCamera.get(), showPosition));
        showPosition.run();
        label(body, "Prints sliced here with Smooth timelapse take each layer's picture with the head parked, whatever is chosen here.", 12, MUTED, false);
        Runnable fill = () -> {
            String id = PictureSettings.camera(this);
            int m = forDefault ? PictureSettings.minutes(this, id) : now.pictureMinutes, l = forDefault ? PictureSettings.layers(this, id) : now.pictureLayers;
            boolean f = forDefault ? PictureSettings.far(this, id) : now.pictureFar;
            when.setSelection(l > 0 ? 2 : m > 0 ? 1 : 0);
            int mi = 2; for (int i = 0; i < PictureSettings.MINUTES.length; i++) if (PictureSettings.MINUTES[i] == m) mi = i; minutes.setSelection(mi);
            int li = 0; for (int i = 0; i < PictureSettings.LAYERS.length; i++) if (PictureSettings.LAYERS[i] == l) li = i; layers.setSelection(li);
            moment.setSelection(f ? 1 : 0);
        };
        fill.run();
        Runnable show = () -> {
            int w = when.getSelectedItemPosition();
            byTime.setVisibility(w == 1 ? View.VISIBLE : View.GONE); byLayers.setVisibility(w == 2 ? View.VISIBLE : View.GONE);
            about.setText(moment.getSelectedItemPosition() == 1
                ? "The head's distance from the camera is checked every second (on your network). With the print's G-code on this phone each layer's far side comes from the file; otherwise from the layer before. Set where the camera is with Camera position."
                : "Taken as soon as the layer begins.");
        };
        android.widget.AdapterView.OnItemSelectedListener changed = new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { show.run(); }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        };
        when.setOnItemSelectedListener(changed); moment.setOnItemSelectedListener(changed); show.run();
        Spinner cameraChoice = camera; cameraPick[0] = camera;
        if (camera != null) camera.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { showPosition.run(); }
            public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        new AlertDialog.Builder(this).setTitle(forDefault ? "Recording pictures" : "Pictures for this print").setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", (d, w) -> {
                if (cameraChoice != null) {
                    int pick = cameraChoice.getSelectedItemPosition();
                    for (int i = 0; i < added.size(); i++) added.get(i).pictures = i + 1 == pick;
                    ExtraCameras.save(this, added);
                }
                int choice = when.getSelectedItemPosition();
                int m = choice == 1 ? PictureSettings.MINUTES[minutes.getSelectedItemPosition()] : 0, l = choice == 2 ? PictureSettings.LAYERS[layers.getSelectedItemPosition()] : 0;
                boolean f = choice == 2 && moment.getSelectedItemPosition() == 1;
                if (forDefault) PictureSettings.save(this, PictureSettings.camera(this), m, l, f);
                else if (printer != null && printer.recorder != null) { if (l > 0) printer.recorder.setPictureLayers(l, f); else printer.recorder.setPictureMinutes(m); }
                showPictureDefault(); render();
            }).show();
    }
    /**
     * Where the picture camera is, for "head far from the camera": the same setting as Live toolpath's camera position (a
     * preset spot, or lined up there by hand), kept for each camera.
     */
    private String cameraSpotName(ExtraCameras.Camera added) {
        String id = added == null ? "" : added.id;
        int spot = getSharedPreferences("viewer-hints", MODE_PRIVATE).getInt(GcodeViewerActivity.camKey("cameraSpot", id), GcodeViewerActivity.defaultSpot(id));
        return GcodeViewerActivity.CAMERA_SPOTS[Math.max(0, Math.min(spot, GcodeViewerActivity.CAMERA_SPOTS.length - 1))];
    }
    private void pictureCameraPosition(ExtraCameras.Camera added, Runnable saved) {
        String id = added == null ? "" : added.id, key = GcodeViewerActivity.camKey("cameraSpot", id);
        android.content.SharedPreferences viewer = getSharedPreferences("viewer-hints", MODE_PRIVATE);
        int current = viewer.getInt(key, GcodeViewerActivity.defaultSpot(id));
        boolean lined = GcodeViewerActivity.linedUp(viewer, id);
        List<String> spots = new ArrayList<>(java.util.Arrays.asList(GcodeViewerActivity.CAMERA_SPOTS).subList(0, GcodeViewerActivity.CAMERA_SPOTS.length - 1));
        if (lined) spots.add(GcodeViewerActivity.CAMERA_SPOTS[GcodeViewerActivity.CAMERA_SPOTS.length - 1]);
        int chosen = Math.min(current, spots.size() - 1);
        new AlertDialog.Builder(this).setTitle("Where is " + (added == null ? "the printer's camera" : "“" + added.name + "”") + "?")
            .setSingleChoiceItems(spots.toArray(new String[0]), chosen, (d, which) -> { viewer.edit().putInt(key, which).apply(); d.dismiss(); saved.run(); })
            .setNegativeButton("Cancel", null).show();
    }
    /** On the Print tab, ask for the CANVAS trays when they are missing or out of date: a read, at most every 30 seconds. */
    private long traysAskedAt;
    private void autoLoadTrays() {
        if (page != PRINT || printer == null || printer.canvasFresh() || !(printer.ready() || printer.usingCloud() && settings.getBoolean("cloudControlUnderstood", false))) return;
        long now = System.currentTimeMillis(); if (now - traysAskedAt < 30_000) return;
        traysAskedAt = now; printer.refreshTrays();
    }
    /** On the History tab, ask for the print history and storage once when there are none: reads, at most once a minute. */
    private long historyAutoAt;
    private void autoLoadHistory() {
        if (page != HISTORY || printer == null || !printer.canQuery() || !(printer.ready() || settings.getBoolean("cloudControlUnderstood", false))) return;
        long now = System.currentTimeMillis(); if (now - historyAutoAt < 60_000) return;
        historyAutoAt = now;
        if (printer.history.length() == 0) printer.loadHistory();
        if (printer.disk.length() == 0) printer.loadDisk();
    }
    /** On the Files tab, ask for the printer's file list when there is none (or it is out of date): a read, at most once a minute. */
    private long filesAutoAt;
    private void autoLoadFiles() {
        if (page != FILES || printer == null || !printer.canQuery() || printer.busy(Cc2Codec.FILES) || printer.filesFresh()) return;
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
            : block == ControlState.Block.CONNECTING ? "Connecting on your local network…" : "Files are unavailable until the printer is reachable (see Printer status on the Printer tab).";
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
            // A list received while the tab was drawn empty has shown no rows on a phone: ask for a fresh layout, and record
            // what is really on screen a moment later (counts and sizes only).
            if (count > 0) {
                unstick(fileRows); main.post(() -> unstick(fileRows));
                int expected = count;
                main.postDelayed(() -> {
                    View card = (View) fileRows.getParent();
                    Diagnostics.note(Diagnostics.FILES, "file rows on screen: " + expected + " listed, " + fileRows.getChildCount() + " views, list " + fileRows.getHeight() + " px high, card "
                        + card.getHeight() + " px, shown " + fileRows.isShown() + ", help " + (fileHelp.getVisibility() == View.VISIBLE ? fileHelp.getHeight() + " px" : "hidden") + ", tab " + page);
                }, 1500);
            }
        }
        diskInfo.setText(printer == null || printer.disk.length() == 0 ? "Storage usage not loaded." : FeatureData.disk(printer.disk));
        String printerFilesLine = StatusPresentation.joinParts(fileInfo.getText().toString().split("\n")[0], printer == null || printer.disk.length() == 0 ? "" : FeatureData.diskShort(printer.disk));
        if (!printerFilesLine.contentEquals(printerFilesSection.detail.getText())) printerFilesSection.detail.setText(printerFilesLine.isEmpty() ? "The printer's files and storage" : printerFilesLine);
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
        historyButtons.setVisibility(query ? View.VISIBLE : View.GONE); loadDisk.setVisibility(historyButtons.getVisibility()); if (noHistory) historyInfo.setVisibility(View.GONE);
        renderTimelapses();
        continueSaveAfterDownload(); continuePreviewAfterDownload();
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
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(24));
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
        cameraAskButton.setVisibility(cloud && signedIn ? View.VISIBLE : View.GONE);
        if (ready != cameraLocalFirst) { cameraLocalFirst = ready; cameraSection.body.removeView(cameraLocalCard); cameraSection.body.addView(cameraLocalCard, ready ? 1 : 2); }
    }
    /** render() runs on every status change and every second; a slow one is noted (at most every 30 s) with its time. */
    private long slowRenderNotedAt;
    private void render() {
        if (connect == null || isDestroyed()) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { main.post(this::render); return; }   // views change on the screen thread only
        long renderStart = android.os.SystemClock.elapsedRealtime();
        try { renderNow(); } finally {
            long took = android.os.SystemClock.elapsedRealtime() - renderStart;
            if (took > 300 && renderStart - slowRenderNotedAt > 30_000) { slowRenderNotedAt = renderStart; Diagnostics.note(MainThreadWatch.AREA, "updating the screen took " + took + " ms (tab " + page + ")"); }
        }
    }
    private void renderNow() {
        renderHybrid(); renderReports();
        if (haStatus != null && printer != null) { String text = settings.getBoolean("haEnabled", false) ? printer.ha.lastResult : "Off."; if (!text.contentEquals(haStatus.getText())) haStatus.setText(text); }
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
        // When the printer reports its light (led.status), only the button that changes it shows.
        JSONObject led = snapshot.optJSONObject("led"); boolean lightKnown = led != null && led.has("status"); lightIsOn = lightKnown && led.optInt("status") != 0;
        lightOn.setVisibility(!lightKnown || !lightIsOn ? View.VISIBLE : View.GONE); lightOff.setVisibility(!lightKnown || lightIsOn ? View.VISIBLE : View.GONE);
        lightOn.setContentDescription(lightKnown ? "Light on. The light is off now" : null); lightOff.setContentDescription(lightKnown ? "Light off. The light is on now" : null);
        spaceActions(lightRow);
        printerAi.setText(aiText(ready && printer != null ? printer.aiDetection : null)); printerAi.setVisibility(printerAi.getText().length() == 0 ? View.GONE : View.VISIBLE);
        discover.setEnabled(!remoteMode() && !connecting && !scanningNow); discover.setText(scanningNow ? "Scanning…" : "Find on Wi-Fi");
        saveProfile.setEnabled(!connecting); chooseProfile.setEnabled(!connecting); removeProfile.setEnabled(!connecting); profileName.setEnabled(!connecting);
        // Only what can work right now is shown; the line above each group says what is missing. Nothing here is enabled in a state PrinterService refuses.
        boolean idleNow = Cc2Codec.idle(snapshot), pausable = Cc2Codec.canPause(snapshot);
        boolean heatersOk = Cc2Codec.heatersAdjustable(snapshot);
        heater.setEnabled(canControl && heatersOk); fan.setEnabled(canControl); speed.setEnabled(canControl && pausable);
        heater.setVisibility(heatersOk ? View.VISIBLE : View.GONE);
        // The temperature tiles open the same dialog when the heaters can be changed (as on the printer's own screen).
        for (TextView t : new TextView[] {tileNozzle, tileBed}) ((View) t.getParent()).setClickable(canControl && heatersOk); speed.setVisibility(pausable ? View.VISIBLE : View.GONE);
        // The printer's own speed-mode number, until its names are confirmed (set a mode on the printer and compare).
        JSONObject moveInfo = snapshot.optJSONObject("gcode_move_inf");
        tuningHint.setText(ControlState.tuningNote(snapshot) + (moveInfo != null && moveInfo.has("speed_mode") ? " The printer reports speed mode number " + moveInfo.optInt("speed_mode") + "." : ""));
        setTile(tuneNozzle, temperature("extruder")); setTile(tuneBed, temperature("heater_bed"));
        setTile(tunePartFan, fanText("fan")); setTile(tuneAuxFan, fanText("aux_fan")); setTile(tuneChamberFan, fanText("box_fan"));
        tuneReadout.setVisibility(snapshot.length() == 0 ? View.GONE : View.VISIBLE);
        String upkeepReason = ControlState.upkeepReason(snapshot);
        boolean upkeepOk = canControl && upkeepReason.isEmpty();
        for (Button button : new Button[] {loadFilament, unloadFilament, homeAll, jog, autoLevel, vibration, selfCheck}) button.setEnabled(upkeepOk);
        trayFilament.setEnabled(upkeepOk && printer.canvas != null);
        // Emergency stop stays while another command is still busy: it goes ahead of anything not yet sent.
        boolean canStop = canControl || block == ControlState.Block.CLOUD_BUSY;
        for (Button stop : emergencyStops) { stop.setEnabled(canStop); stop.setVisibility(canStop ? View.VISIBLE : View.GONE); }
        upkeepButtons.setVisibility(upkeepOk ? View.VISIBLE : View.GONE); filamentButtons.setVisibility(upkeepButtons.getVisibility());
        upkeepHint.setText(upkeepOk ? "Each tap sends one command. Nothing here is repeated automatically." : upkeepReason + " Emergency stop stays available.");
        // On a new install the Get started card already offers Find, Sign in and Slice, so the empty Controls card waits.
        controlsCard.setVisibility(firstRun && block == ControlState.Block.DISCONNECTED ? View.GONE : View.VISIBLE);
        // On first run the Get started card already says how to connect; the "Not connected" summary would repeat it.
        heroCard.setVisibility(firstRun && block == ControlState.Block.DISCONNECTED ? View.GONE : View.VISIBLE);
        canvasCard.setVisibility(noData && !(connecting && renderedCanvas != null) ? View.GONE : View.VISIBLE); tilesRow.setVisibility(noData ? View.GONE : View.VISIBLE);
        printRow.setVisibility(inJob ? View.VISIBLE : View.GONE); lightRow.setVisibility(noData || !canControl ? View.GONE : View.VISIBLE);
        // While a print runs and the printer reports its light, the switch sits with Pause and Stop instead of under Controls.
        boolean lightInRow = inJob && canControl && lightKnown;
        // Stop always stays in the row. When the light button comes, goes or changes its words, the row is measured again so
        // each button's text is centred for its new width (a phone kept the old centring after the light was switched).
        String lightText = lightIsOn ? "Light off" : "Light on"; int lightShown = lightInRow ? View.VISIBLE : View.GONE;
        boolean rowChanged = printLight.getVisibility() != lightShown || !lightText.contentEquals(printLight.getText()) || stop.getVisibility() != View.VISIBLE;
        stop.setVisibility(View.VISIBLE); printLight.setVisibility(lightShown); printLight.setEnabled(lightInRow);
        if (!lightText.contentEquals(printLight.getText())) printLight.setText(lightText);
        printLight.setContentDescription(lightIsOn ? "Light off. The light is on now" : "Light on. The light is off now");
        if (lightInRow) lightRow.setVisibility(View.GONE);
        spaceActions(printRow);
        if (rowChanged) { for (int i = 0; i < printRow.getChildCount(); i++) printRow.getChildAt(i).requestLayout(); printRow.requestLayout(); printRow.invalidate(); }
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
        layerCharts.setEnabled(printer != null && printer.selectedFile != null && !fileBusy);
        saveCopy.setEnabled(hasReport && !fileBusy); shareInspection.setEnabled(hasReport && !fileBusy); clearCopy.setEnabled(printer != null && printer.selectedFile != null && !fileBusy);
        GcodeInspector.Report report = hasReport ? printer.selectedReport : null;
        if (renderedReport != report || !hasReport) { renderedReport = report; inspection.setText(hasReport ? report.text() : ""); }
        fileDetails.setVisibility(printer != null && (printer.selectedFile != null || printer.importing) ? View.VISIBLE : View.GONE);
        if (!hasReport && printer != null && printer.importing) inspection.setText("Importing and inspecting G-code…");
        filePreview.setImageBitmap(hasReport ? printer.selectedThumbnail : null); filePreview.setVisibility(hasReport && printer.selectedThumbnail != null ? View.VISIBLE : View.GONE);
        String fileWarning = hasReport ? report.compatibilityWarning() : "";
        previewWarning.setText(fileWarning); previewWarning.setVisibility(fileWarning.isEmpty() ? View.GONE : View.VISIBLE);
        addLoad.setVisibility(hasReport && report.needsFilamentLoad() ? View.VISIBLE : View.GONE); addLoad.setEnabled(!fileBusy);
        previewInfo.setText(!hasReport ? "" : report.thumbnail == null ? report.thumbnailNote : printer.selectedThumbnail == null ? "Embedded image found but Android could not decode it. File inspection remains available." : report.thumbnailNote + " · slicer image, not a live camera or motion simulation");
        materialDetails.setEnabled(hasReport && !report.materials.entries.isEmpty());
        previewToolpath.setEnabled(printer != null && printer.selectedFile != null && !printer.importing);
        boolean chosen = printer != null && printer.selectedFile != null;
        selected.setText(chosen ? printer.selectedName + " · " + printer.selectedFile.length() / 1024 + " KiB" : "Slice a model or choose a .gcode file on this phone. Slicing needs no printer connection; upload does.");
        String phoneLine = chosen ? "Chosen: " + printer.selectedName : printer != null && printer.importing ? "Importing a file…" : "Slice a model, choose G-code, find models online";
        if (!phoneLine.contentEquals(phoneSection.detail.getText())) phoneSection.detail.setText(phoneLine);
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
        // The name doubles as the printer switcher once a printer is saved.
        boolean canSwitch = profiles.all().length() > 0;
        // What is plugged in, as the printer reports it: a USB stick, and its camera when it says the camera is missing.
        JSONObject devices = snapshot.optJSONObject("external_device");
        if (devices != null) model = StatusPresentation.joinParts(model, devices.optBoolean("u_disk") ? "USB stick in" : "", devices.has("camera") && !devices.optBoolean("camera") ? "camera not connected" : "");
        if (Showcase.running()) {   // a video to share: no address, serial or the printer's own name
            name = "Centauri Carbon 2"; model = ready ? StatusPresentation.clean(printer.attributes.optString("machine_model", "Centauri Carbon 2")) : "Centauri Carbon 2";
        }
        title.setText(canSwitch ? name + " ▾" : name); title.setClickable(canSwitch);
        title.setContentDescription(canSwitch ? name + ", switch printer" : name); identity.setText(model);
        String reachText = Showcase.running() ? "" : StatusPresentation.reach(ready, ready && printer.pinProbe(), cloud, cloud && printer.cloudOnline != 0, cloudOk,
            ready ? printer.statusAt : cloud ? printer.cloudCheckedAt : 0, System.currentTimeMillis());
        if (!reachText.contentEquals(reach.getText())) reach.setText(reachText);
        reach.setVisibility(reachText.isEmpty() ? View.GONE : View.VISIBLE); reach.setTextColor(live || reachText.isEmpty() ? MUTED : AMBER);
        // Hero: state and job.
        JSONObject machine = snapshot.optJSONObject("machine_status"), print = snapshot.optJSONObject("print_status");
        boolean printing = machine != null && machine.optInt("status", -1) == 2;
        int percent = machine == null ? 0 : Math.max(0, Math.min(100, machine.optInt("progress", 0)));
        String stateText = snapshot.length() == 0 ? (printer == null ? "Starting…" : connecting ? "Connecting…" : cloud ? "Waiting for printer" : "Not connected") : StatusPresentation.state(snapshot);
        if (snapshot.length() > 0 && !live) stateText += " · out of date";
        if (!stateText.contentEquals(state.getText())) state.setText(stateText);
        // Job: the file's preview, a large percentage, layer and time left, and a bar; while idle the title stands alone.
        boolean inJobNow = machine != null && machine.optInt("status", -1) == 2;
        String printFile = print == null ? "" : print.optString("filename", "");
        percentText.setText(inJobNow ? percent + "%" : ""); percentText.setContentDescription(percent + " percent complete");
        ((View) percentText.getParent()).setVisibility(inJobNow ? View.VISIBLE : View.GONE);
        jobBar.setProgress(percent); jobBar.setVisibility(inJobNow ? View.VISIBLE : View.GONE);
        statsRow.setVisibility(inJobNow && print != null ? View.VISIBLE : View.GONE);
        // The preview is a read, asked once per file; the printer keeps it under the file's name on its own storage.
        if (inJobNow && !printFile.isEmpty() && !printFile.equals(jobThumbAsked) && printer.canQuery() && !printer.busy(Cc2Codec.THUMBNAIL)) { jobThumbAsked = printFile; printer.thumbnail("local", printFile); }
        String thumbData = inJobNow && printer != null ? printer.thumbnails.get("local/" + printFile) : null;
        String thumbKey = thumbData == null ? "" : printFile + "#" + thumbData.length();
        if (!thumbKey.equals(jobThumbKey)) { jobThumbKey = thumbKey; Bitmap picture = thumbData == null ? null : decodeThumbnail(thumbData); jobThumb.setImageBitmap(picture); jobThumb.setTag(picture == null ? null : "shown"); }
        jobThumb.setVisibility(inJobNow && jobThumb.getTag() != null ? View.VISIBLE : View.GONE);
        if (printing && print != null) {
            long remaining = print.optLong("remaining_time_sec", -1);
            int layers = print.optInt("total_layer", 0);
            job.setText(StatusPresentation.clean(print.optString("filename", "Current print")).replaceFirst("(?i)\\.gcode$", ""));
            // Layer and times in the tiles under the bar; the finish time on the clock beside the percentage.
            long elapsed = printer == null || printer.recorder == null ? -1 : printer.recorder.elapsedSeconds(android.os.SystemClock.elapsedRealtime());
            // Elapsed comes from this phone's recording of the print; without one the tile steps aside rather than show a dash.
            setTile(statElapsed, StatusPresentation.span(elapsed)); ((View) statElapsed.getParent()).setVisibility(elapsed < 0 ? View.GONE : View.VISIBLE);
            View remainingTile = (View) statRemaining.getParent(); ((LinearLayout.LayoutParams) remainingTile.getLayoutParams()).leftMargin = elapsed < 0 || statsRow.getOrientation() == LinearLayout.VERTICAL ? 0 : dp(8);
            setTile(statRemaining, StatusPresentation.span(remaining));
            setTile(statLayer, layers > 0 ? print.optInt("current_layer", 0) + " / " + layers : "—");
            String done = remaining < 0 ? "" : "Done at " + StatusPresentation.doneAt(System.currentTimeMillis(), remaining, Locale.getDefault(), TimeZone.getDefault());
            doneText.setText(done); doneText.setContentDescription(done.isEmpty() ? null : done + (live ? "" : ", out of date"));
            String notes = printNotes(remaining);
            printNotes.setText(notes); printNotes.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
            detail.setText(live ? "" : "Out of date: the printer has not reported for a while.");
        } else {
            job.setText(snapshot.length() == 0 ? (cloud || ready ? "" : "Connect in Settings, or sign in to Elegoo to watch through the cloud.") : "No active print.");
            doneText.setText("");
            detail.setText("");
            printNotes.setVisibility(View.GONE);
        }
        // Camera on top while printing. It starts by itself only after the user started it here once, and only while this tab is on screen.
        boolean cloudCameraOk = !ready && printer != null && printer.cloudSignedIn && !printer.cloudSerial.isEmpty() && printer.cloudOnline == 1;
        ExtraCameras.Camera addedSource = monitorExtra();
        boolean toolpathShown = inJobNow && printer != null && toolpathPreviewOn();
        boolean showCamera = !toolpathShown && inJobNow && (ready || cloudCameraOk || addedSource != null);
        ((View) monitorSourceButton.getParent()).setVisibility(extraCount > 0 && !toolpathShown ? View.VISIBLE : View.GONE);
        monitorSourceButton.setText("Camera: " + (addedSource == null ? "the printer's" : addedSource.name) + " ▾");
        monitorCamera.setVisibility(showCamera || toolpathShown ? View.VISIBLE : View.GONE);
        boolean cameraSectionOpen = opened[PRINT] == cameraSection;   // its own Start camera keeps the stream when nothing prints
        if (page == PRINT && (cameraPlayer != null || cloudVideo != null || extraFeed != null) && !showCamera && !cameraSectionOpen) stopCamera();
        boolean ownCamera = ready || printer != null && printer.localCamera();   // the printer's own stream, locally or alongside the cloud
        boolean cameraHidden = settings.getBoolean("printCameraHidden", false);
        monitorBody.setVisibility(cameraHidden ? View.GONE : View.VISIBLE); ((View) monitorShow.getParent()).setVisibility(cameraHidden ? View.VISIBLE : View.GONE);
        if (page == PRINT && active && showCamera && addedSource == null && ownCamera && !cameraHidden && cameraPlayer == null && !monitorCameraTried && settings.getBoolean("monitorCamera", false)) { monitorCameraTried = true; toggleCamera(); }
        if (page == PRINT && active && showCamera && addedSource == null && !ownCamera && !cameraHidden && cloudVideo == null && !monitorCameraTried && cloudOk && settings.getBoolean("monitorCloudCamera", false)) { monitorCameraTried = true; startCloudVideo(); }
        monitorImage.setVisibility(showCamera && (addedSource != null ? extraFeed != null : ownCamera ? cameraPlayer != null || lastFrame != null : cloudVideo != null) ? View.VISIBLE : View.GONE);
        monitorCameraStart.setText(cameraPlayer != null || cloudVideo != null || extraFeed != null ? "Stop camera" : "Watch camera");
        monitorBadge.setText(extraFeed != null && extraShown != null ? "● Live · " + extraShown.name : cloudVideo != null ? (cloudVideo.playing() ? "● Live · cloud" : "Connecting…") : cameraPlayer != null ? "● Live" : "Last picture");
        // The toolpath instead of the camera: drawn only while this tab is on screen, freed otherwise.
        if (toolpathShown && page == PRINT && active && !cameraHidden) {
            monitorImage.setVisibility(View.GONE); monitorFrame.setVisibility(View.VISIBLE);
            toolpathPreview.update(printer);
            monitorBadge.setText(toolpathPreview.drawing() ? "● Live toolpath" : "Toolpath");
            monitorCameraInfo.setText(toolpathPreview.note()); monitorCameraInfo.setTextColor(MUTED);
        } else if (toolpathPreview != null) {
            toolpathPreview.release();
            if (monitorCameraInfo.getText().toString().equals(toolpathPreview.note())) monitorCameraInfo.setText("");
        }
        snapshotChip.setVisibility(toolpathShown ? View.GONE : View.VISIBLE);
        fullChip.setContentDescription(toolpathShown ? "Open Live toolpath" : "Show the camera full screen");
        monitorCameraStart.setText(toolpathShown ? "Show the camera" : monitorCameraStart.getText());
        monitorHide.setText(toolpathShown ? "Hide preview" : "Hide camera"); monitorShow.setText(toolpathShown ? "Show preview" : "Show camera");
        // Two empty channels in a row: the printer's camera service is probably stuck; say so before the next 20 s wait.
        if (!toolpathShown && !ready && cloudVideo == null && CloudVideo.emptyStreak() >= 2) {
            monitorCameraInfo.setText("The printer's camera has not answered " + CloudVideo.emptyStreak() + " times in a row. Its camera service is probably stuck: restart the printer, then tap Watch camera.");
            monitorCameraInfo.setTextColor(ERROR); monitorCameraInfo.setVisibility(View.VISIBLE);
        } else if (CloudVideo.emptyStreak() < 2 && monitorCameraInfo.getText().toString().startsWith("The printer's camera has not answered")) monitorCameraInfo.setText("");
        if (monitorCameraInfo.getText().length() == 0) monitorCameraInfo.setVisibility(View.GONE);
        // Hide camera only while there is a picture to hide; otherwise Watch camera stands alone.
        monitorHide.setVisibility(monitorFrame.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        spaceActions((LinearLayout) monitorCameraStart.getParent());
        graphs.setText("Print recordings");
        PrintRecorder.Recording recordingNow = printer == null || printer.recorder == null ? null : printer.recorder.current();
        picturesNow.setVisibility(inJobNow && recordingNow != null ? View.VISIBLE : View.GONE);
        renderTelemetry(inJobNow ? recordingNow : null);
        renderFileSource(inJobNow, printFile);
        renderLatestPictures(inJobNow ? recordingNow : null);
        GuardianRun guard = printer == null ? null : printer.guardian;
        boolean guardShown = inJobNow && guard != null && settings.getBoolean("guardian", true) && !guard.verdict.isEmpty();
        guardianCard.setVisibility(guardShown ? View.VISIBLE : View.GONE);
        if (guardShown) {
            android.graphics.Bitmap picture = guard.overlay;
            if (picture != guardianShown) { guardianShown = picture; guardianImage.setImageBitmap(picture); }
            guardianImage.setVisibility(picture == null ? View.GONE : View.VISIBLE);
            guardianText.setText(guard.verdict); guardianText.setTextColor(guard.alarmed ? ERROR : MUTED);
            guardianButtons.setVisibility(guard.alarmed ? View.VISIBLE : View.GONE);
        }
        // One alert at a chosen layer of this print (navigation-only notification; the printer is never told anything).
        int total = snapshot.optJSONObject("print_status") == null ? 0 : snapshot.optJSONObject("print_status").optInt("total_layer", 0);
        layerAlert.setVisibility(inJobNow && total > 1 ? View.VISIBLE : View.GONE);
        int alertAt = settings.getInt("alertLayer", 0); boolean thisPrint = alertAt > 0 && printKey(snapshot).equals(settings.getString("alertLayerKey", ""));
        layerAlert.setText(thisPrint ? "Layer alert at " + alertAt + "…" : "Layer alert…");
        if (recordingNow != null) {
            picturesNow.setText(recordingNow.pictureLayers > 0 ? "Pictures: " + (recordingNow.pictureLayers == 1 ? "every layer" : "every " + recordingNow.pictureLayers + " layers")
                : recordingNow.pictureMinutes == 0 ? "Pictures: off" : "Pictures: " + recordingNow.pictureMinutes + " min");
            picturesNow.setContentDescription("Camera pictures for this print: " + recordingNow.pictureWords() + ". Change"); }
        // Quick actions by state. Printing: Live toolpath and Camera. Connected and idle: Slice a model, Camera and, with history, Print again.
        // Not connected: only Print recordings, as before (Get started and the fix guidance cover the rest). None of these sends a printer command.
        boolean idleOnline = live && !inJobNow, hasHistory = printer != null && !FeatureData.historyRows(printer.history).isEmpty();
        liveToolpath.setVisibility(inJobNow ? View.VISIBLE : View.GONE);
        quickSlice.setVisibility(idleOnline ? View.VISIBLE : View.GONE);
        // While printing with the camera on top, this leads to the Camera section's other tools (snapshot, line-up, cloud camera).
        quickCamera.setVisibility(inJobNow || idleOnline ? View.VISIBLE : View.GONE); quickCamera.setText(showCamera ? "Camera tools…" : "Camera");
        quickRow.setVisibility(liveToolpath.getVisibility() == View.VISIBLE || quickSlice.getVisibility() == View.VISIBLE || quickCamera.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        quickAgain.setVisibility(idleOnline && hasHistory ? View.VISIBLE : View.GONE);
        spaceActions(quickRow); spaceActions(recordingsRow); spaceActions(printExtrasRow);
        printAutomations.setVisibility(inJobNow && !printKey(snapshot).isEmpty() ? View.VISIBLE : View.GONE);
        refreshRatePrompt();
        boolean rate = !inJobNow && rateRecording != null && !rateRecording.journal.rated() && System.currentTimeMillis() - Math.max(rateRecording.end, rateRecording.lastSample) < 48 * 3_600_000L;
        rateRow.setVisibility(rate ? View.VISIBLE : View.GONE);
        if (rate) rateButton.setText("How did " + StatusPresentation.clean(rateRecording.file.replaceFirst("(?i)\\.gcode$", "")) + " come out? Rate it…");
        printOptions.setVisibility(picturesNow.getVisibility() == View.VISIBLE || printAutomations.getVisibility() == View.VISIBLE || layerAlert.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        printExtrasRow.setVisibility(printOptions.getVisibility());
        job.setVisibility(job.getText().length() == 0 ? View.GONE : View.VISIBLE); detail.setVisibility(detail.getText().length() == 0 ? View.GONE : View.VISIBLE);
        String codes = StatusPresentation.faultCodes(snapshot);
        String faultText = codes.isEmpty() ? "" : "Printer reports fault code(s): " + codes + ". Check the printer screen."; if (!faultText.contentEquals(faults.getText())) faults.setText(faultText);
        faults.setVisibility(codes.isEmpty() ? View.GONE : View.VISIBLE);
        setTile(tileNozzle, temperature("extruder")); setTile(tileBed, temperature("heater_bed")); setTile(tileChamber, chamberTile());
        // Where controls go.
        switch (block) {
            case NONE: controlSource.setText(ready ? "Commands go over your local network." : "Commands go through the Elegoo cloud. " + lastReport() + (printer.cloudLiveOn ? " · live" : "") + "."); break;
            case PIN_PROBE: controlSource.setText("Read-only PIN probe: controls are disabled."); break;
            case STALE: controlSource.setText("Waiting for printer status. Controls unlock when it arrives."); break;
            case CONNECTING: controlSource.setText("Connecting on your local network…"); break;
            case CLOUD_AGREEMENT: controlSource.setText("You are watching through the Elegoo cloud. Controls stay off until you agree to cloud control."); break;
            case CLOUD_BUSY: controlSource.setText("Sending through the Elegoo cloud…"); break;
            case CLOUD_OFFLINE: controlSource.setText("Printer offline. The Elegoo cloud reports it offline."); break;
            case CLOUD_WAITING: controlSource.setText(!printer.cloudMessage.isEmpty() ? printer.cloudMessage : printer.cloudReportedAt > 0 && !printer.cloudReportRecent() ? "The printer has not reported for a while. " + lastReport() + ". Controls stay off until it reports again." : "Waiting for printer through the Elegoo cloud…"); break;
            default: controlSource.setText("Not connected. Connect on your network, or sign in with Elegoo to control through the cloud."); break;
        }
        boolean fix = block == ControlState.Block.CLOUD_AGREEMENT || block == ControlState.Block.DISCONNECTED;
        controlFix.setText(block == ControlState.Block.CLOUD_AGREEMENT ? "Turn on cloud control…" : "Open Settings"); ((View) controlFix.getParent()).setVisibility(fix ? View.VISIBLE : View.GONE);
        // The Controls card only when it has something to say: why controls are off (with the way to fix it), or the light while idle.
        boolean lightIdle = lightRow.getVisibility() == View.VISIBLE;
        controlSource.setVisibility(block == ControlState.Block.NONE ? View.GONE : View.VISIBLE);
        controlsCard.setVisibility(!(firstRun && block == ControlState.Block.DISCONNECTED) && (block != ControlState.Block.NONE || lightIdle) ? View.VISIBLE : View.GONE);
        renderPrintNav();
        // The Printer tab says the same, and its sections say why their buttons are off.
        printerStatus.setText(controlSource.getText()); printerFix.setText(controlFix.getText()); ((View) printerFix.getParent()).setVisibility(fix ? View.VISIBLE : View.GONE);
        if (!canControl) { tuningHint.setText(controlSource.getText()); upkeepHint.setText(controlSource.getText()); }
        filamentHint.setText(upkeepHint.getText());
        // The connection row says plainly what the chip in the header means.
        String chipNow = summary.getText().toString();
        connectionSection.detail.setText(ready ? (printer.pinProbe() ? "Connected on your network, read-only" : "Connected on your network")
            : cloudFresh ? "Watching through the Elegoo cloud" : connecting ? "Connecting…" : chipNow);
        main.post(this::autoLoadTrays);
        // The last trays stay on screen while the connection changes over (a reconnect, local to cloud); they go only when nothing is connected.
        JSONObject canvasNow = (ready || cloud || connecting) && printer.canvas != null ? printer.canvas : null;
        renderTrays(canvasNow);
        renderSpools(canvasNow);
        if (canvasNow != null) {
            String refillText = "Automatic refill: " + (canvasNow.has("auto_refill") ? (canvasNow.optBoolean("auto_refill") ? "On" : "Off") : "Not reported");
            trays.setText(trayList.getChildCount() == 0 ? StatusPresentation.canvas(canvasNow) : refillText + (!printer.canvasFresh() ? "\nTray status is stale; refresh before changing refill." : ""));
        } else trays.setText(cloud ? "Tap Refresh status to load trays through the cloud." : ready ? StatusPresentation.canvas(null) : "Connect for CANVAS tray status.");
        // A result belongs to the printer it came from: on a switch to another printer, the old one's message goes away.
        String target = ready ? "local:" + printer.host() : cloud ? "cloud:" + printer.cloudSerial : "";
        if (!target.isEmpty()) {
            if (!feedbackTarget.isEmpty() && !target.equals(feedbackTarget) && printer != null) { printer.feedback = ""; feedback.setVisibility(View.GONE); }
            feedbackTarget = target;
        }
        showFeedback(printer == null ? null : printer.feedback);
        cloudLiveLabel.setText(printer == null ? "" : printer.cloudLiveState); cloudLiveLabel.setVisibility(cloudLiveLabel.getText().length() == 0 ? View.GONE : View.VISIBLE);
        showThumbnail();
    }
    /** One row per reported tray: colour swatch, material and the active-tray marker. Rebuilt only when the trays change. */
    // ------------------------------------------------------------------ Spools
    private LinearLayout spoolList;
    private String renderedSpools;
    /** One row per loaded tray with its spool's filament left, then the shelf; rebuilt only when something shown changed. */
    private void renderSpools(JSONObject canvas) {
        if (spoolList == null) return;
        List<TrayPlan.Tray> loaded = canvas == null ? new ArrayList<>() : TrayPlan.trays(canvas);
        SpoolStore store = spools(); List<SpoolStore.Spool> all = store.all();
        StringBuilder key = new StringBuilder(); for (TrayPlan.Tray t : loaded) key.append(t.label()).append(';');
        for (SpoolStore.Spool s : all) key.append(s.id).append(s.slot).append(Math.round(s.left)).append(';');
        if (key.toString().equals(renderedSpools)) return;
        renderedSpools = key.toString(); spoolList.removeAllViews();
        if (loaded.isEmpty()) label(spoolList, "No loaded CANVAS trays reported. Refresh status on the Print tab to see them.", 13, MUTED, false);
        boolean first = true;
        for (TrayPlan.Tray tray : loaded) {
            SpoolStore.Spool spool = store.inSlot(SpoolStore.slot(tray));
            String detail = spool == null ? "Spool not set: tap to say how much is on it" : String.format(Locale.getDefault(), "About %.0f g left of %.0f g", spool.left, spool.size)
                + (spool.left < 100 ? " · running low" : "");
            listRow(spoolList, "Tray " + tray.trayId + (tray.canvasId > 0 ? " (CANVAS " + tray.canvasId + ")" : "") + " · " + tray.material(), detail, !first, () -> spoolDialog(tray));
            first = false;
        }
        List<SpoolStore.Spool> shelf = store.shelf();
        if (!shelf.isEmpty()) {
            TextView heading = label(spoolList, "On the shelf", 14, INK, true); heading.setPadding(0, dp(10), 0, 0); A11y.heading(heading);
            first = true;
            for (SpoolStore.Spool spool : shelf) {
                listRow(spoolList, spool.material(), String.format(Locale.getDefault(), "About %.0f g left of %.0f g", spool.left, spool.size), !first, () -> shelfDialog(spool));
                first = false;
            }
        }
    }
    /** What to do with a tray's spool. Nothing here talks to the printer. */
    private void spoolDialog(TrayPlan.Tray tray) {
        SpoolStore store = spools(); SpoolStore.Spool spool = store.inSlot(SpoolStore.slot(tray));
        List<String> names = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        if (spool != null) { names.add("Set filament left…"); actions.add(() -> amountDialog("Filament left on this spool", spool.size, spool.left, false, (size, left) -> { store.setLeft(spool.id, left, System.currentTimeMillis()); render(); })); }
        names.add("New full spool (" + Math.round(SpoolStore.DEFAULT_SIZE) + " g)"); actions.add(() -> { store.put(tray, SpoolStore.DEFAULT_SIZE, SpoolStore.DEFAULT_SIZE, System.currentTimeMillis()); render(); });
        names.add("New spool of another size, or part used…"); actions.add(() -> amountDialog("Spool in tray " + tray.trayId, SpoolStore.DEFAULT_SIZE, SpoolStore.DEFAULT_SIZE, true, (size, left) -> { store.put(tray, size, left, System.currentTimeMillis()); render(); }));
        for (SpoolStore.Spool back : store.candidates(tray)) {
            names.add(String.format(Locale.getDefault(), "Put back the %s spool from the shelf (%.0f g left)", back.material(), back.left));
            actions.add(() -> { store.fromShelf(back.id, tray, System.currentTimeMillis()); render(); });
        }
        new AlertDialog.Builder(this).setTitle("Tray " + tray.trayId + " · " + tray.material()).setItems(names.toArray(new String[0]), (d, which) -> actions.get(which).run())
            .setNegativeButton("Cancel", null).show();
    }
    private void shelfDialog(SpoolStore.Spool spool) {
        SpoolStore store = spools();
        new AlertDialog.Builder(this).setTitle(spool.material()).setItems(new String[] {"Set filament left…", "Remove (spool used up or given away)"}, (d, which) -> {
            if (which == 0) amountDialog("Filament left on this spool", spool.size, spool.left, false, (size, left) -> { store.setLeft(spool.id, left, System.currentTimeMillis()); render(); });
            else { store.remove(spool.id); render(); }
        }).setNegativeButton("Cancel", null).show();
    }
    private interface Amount { void set(double size, double left); }
    /** Grams on a spool (and its full size, for a new one). Weighing the spool and taking off the empty spool's weight is the most accurate. */
    private void amountDialog(String title, double size, double left, boolean askSize, Amount done) {
        LinearLayout body = dialogBody();
        label(body, "Estimates are fine. To be exact, weigh the spool and take off the empty spool's weight (often about 200 g for plastic, 140 g for cardboard).", 13, MUTED, false);
        EditText total = askSize ? input(body, "Full spool, grams of filament", false) : null;
        EditText now = input(body, "Filament left, grams", false);
        if (total != null) { total.setInputType(InputType.TYPE_CLASS_NUMBER); total.setText(String.valueOf(Math.round(size))); }
        now.setInputType(InputType.TYPE_CLASS_NUMBER); now.setText(String.valueOf(Math.round(left)));
        new AlertDialog.Builder(this).setTitle(title).setView(body).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            try {
                double full = total == null ? size : Double.parseDouble(total.getText().toString()), remaining = Double.parseDouble(now.getText().toString());
                done.set(full, Math.min(remaining, full));
            } catch (NumberFormatException invalid) { message("Enter the grams as a number."); }
        }).show();
    }
    private void renderTrays(JSONObject canvas) {
        // Rebuilt only when something shown changes: the printer's report also carries values that are not shown here (and
        // reorders its fields), and rebuilding on each of those made the strip flicker and the page jump.
        String key = trayKey(canvas);
        if (java.util.Objects.equals(key, renderedCanvas)) return;
        renderedCanvas = key; trayList.removeAllViews(); trayStrip.removeAllViews();
        try { buildTrays(canvas); } finally { renderTrayDetails(); }
    }
    /** What the tray strip and list show, as one string: units, their trays (material, name, colour, range) and the active tray. */
    static String trayKey(JSONObject canvas) {
        if (canvas == null) return null;
        StringBuilder key = new StringBuilder().append(canvas.optInt("active_canvas_id", -1)).append('/').append(canvas.optInt("active_tray_id", -1));
        JSONArray units = canvas.optJSONArray("canvas_list");
        for (int u = 0; units != null && u < units.length(); u++) {
            JSONObject unit = units.optJSONObject(u); if (unit == null) continue;
            key.append('|').append(unit.optInt("canvas_id", -1)).append(':').append(unit.opt("connected"));
            JSONArray list = unit.optJSONArray("tray_list");
            for (int t = 0; list != null && t < list.length(); t++) {
                JSONObject tray = list.optJSONObject(t); if (tray == null) continue;
                key.append(';').append(tray.optInt("tray_id", -1)).append(',').append(tray.optString("filament_type")).append(',').append(tray.optString("filament_name"))
                    .append(',').append(TrayPlan.colour(tray.optString("filament_color"))).append(',').append(tray.optInt("min_nozzle_temp")).append('-').append(tray.optInt("max_nozzle_temp"));
            }
        }
        return key.toString();
    }
    private void buildTrays(JSONObject canvas) {
        JSONArray units = canvas == null ? null : canvas.optJSONArray("canvas_list");
        if (units == null) return;
        for (int u = 0; u < Math.min(units.length(), 8); u++) {
            JSONObject unit = units.optJSONObject(u); if (unit == null) continue;
            JSONArray list = unit.optJSONArray("tray_list"); if (list == null) continue;
            int id = unit.optInt("canvas_id", -1);
            Object connected = unit.opt("connected");
            boolean online = connected == null || Boolean.TRUE.equals(connected) || unit.optInt("connected", 0) == 1;
            if (units.length() > 1 || !online) label(trayList, "CANVAS " + id + (online ? "" : " · Not connected"), 12, MUTED, true);
            if (units.length() > 1 || !online) label(trayStrip, "CANVAS " + id + (online ? "" : " · Not connected"), 12, MUTED, true);
            LinearLayout strip = new LinearLayout(this); strip.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams stripLayout = new LinearLayout.LayoutParams(-1, -2); stripLayout.topMargin = dp(8); trayStrip.addView(strip, stripLayout);
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
                strip.addView(trayCell(trayId, type, colour, active, row.getContentDescription()), stripCell(strip.getChildCount()));
            }
        }
    }
    private LinearLayout.LayoutParams stripCell(int index) { LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1); if (index > 0) layout.leftMargin = dp(8); return layout; }
    /** One tray in the strip: a colour bar (outlined when active, hollow when empty), its number and material. Tapping it opens the details. */
    private View trayCell(int trayId, String type, String colour, boolean active, CharSequence description) {
        LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER_HORIZONTAL); cell.setPadding(0, dp(2), 0, dp(2));
        View bar = new View(this); GradientDrawable fill = new GradientDrawable(); fill.setCornerRadius(dp(8));
        fill.setColor(colour == null ? Color.TRANSPARENT : Color.parseColor(colour));
        fill.setStroke(active ? dp(3) : dp(1), active ? TEAL : (MUTED & 0x00ffffff) | 0x80000000); bar.setBackground(fill);
        cell.addView(bar, new LinearLayout.LayoutParams(-1, dp(36)));
        TextView name = new TextView(this); name.setText(trayId + " · " + (type.isEmpty() ? "Empty" : type)); name.setTextSize(12); name.setGravity(Gravity.CENTER);
        name.setTextColor(active ? INK : MUTED); name.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(-1, -2); nameLayout.topMargin = dp(4); cell.addView(name, nameLayout);
        cell.setMinimumHeight(dp(48)); cell.setContentDescription(description); A11y.clickLabel(cell, "Show or hide tray details");
        cell.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), null, new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        cell.setOnClickListener(v -> { traysOpen = !traysOpen; renderTrayDetails(); });
        return cell;
    }
    private void setTile(TextView view, String text) {
        // The tile speaks as one item: "Nozzle 219 degrees, heating to 220 degrees".
        View tile = (View) view.getParent(); String caption = ((TextView) ((ViewGroup) tile).getChildAt(0)).getText().toString();
        tile.setContentDescription(caption + " " + (text.equals("—") ? "not reported" : text.replace("\n", ", ").replace("↑ ", "").replace("↓ ", "").replace("°", " degrees")));
        int split = text.indexOf('\n');
        if (split < 0) { view.setText(text); return; }
        android.text.SpannableString styled = new android.text.SpannableString(text);
        styled.setSpan(new android.text.style.RelativeSizeSpan(0.62f), split, text.length(), 0);
        styled.setSpan(new android.text.style.ForegroundColorSpan(MUTED), split, text.length(), 0);
        view.setText(styled);
    }
    /** A fan's speed as the printer reports it (0-255), in percent: "68 %", "off" or "—" when not reported. */
    private String fanText(String key) { int percent = fanPercent(key); return percent < 0 ? "—" : percent == 0 ? "off" : percent + "\u00a0%"; }
    private int fanPercent(String key) {
        JSONObject fans = snapshot.optJSONObject("fans"), fan = fans == null ? null : fans.optJSONObject(key);
        return fan == null || !fan.has("speed") ? -1 : (int) Math.round(fan.optDouble("speed", 0) * 100 / 255);
    }
    /** "219°" over "↑ heating to 220°", "↓ cooling to 60°", "at 220°" or "off". */
    /** The chamber now, then its countdown to the loaded filament's goal (ABS, ASA, PC, nylon), its rate, or its peak. */
    private String chamberTile() {
        JSONObject chamber = snapshot.optJSONObject("ztemperature_sensor"); if (chamber == null || !chamber.has("temperature")) return "—";
        double now = chamber.optDouble("temperature", 0);
        String text = String.format(Locale.ROOT, "%.0f°", now);
        if (printer == null) return text;
        JSONObject tray = PrintWatch.activeTray(printer.canvas);
        String line = printer.chamberTrend.line(now, ChamberTrend.goal(tray == null ? "" : tray.optString("filament_type")), chamber.optDouble("measured_max_temperature", Double.NaN));
        return line.isEmpty() ? text : text + "\n" + line;
    }
    private String temperature(String key) {
        JSONObject value = snapshot.optJSONObject(key); if (value == null || !value.has("temperature")) return "—";
        double now = value.optDouble("temperature", 0), target = value.optDouble("target", 0);
        String text = String.format(Locale.ROOT, "%.0f°", now);
        if (!value.has("target")) return text;
        if (target <= 0) return text + "\noff";
        String goal = String.format(Locale.ROOT, "%.0f°", target);
        return text + (now < target - 3 ? "\n↑ heating to " + goal : now > target + 3 ? "\n↓ cooling to " + goal : "\nat " + goal);
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
        if (request == BACKUP_SAVE) { char[] password = backupPassword; backupPassword = null; boolean recordings = backupRecordings;
            if (result == RESULT_OK && data != null && data.getData() != null) writeBackup(data.getData(), recordings, backupProjectFiles, password); else if (password != null) java.util.Arrays.fill(password, ' ');
            return; }
        if (request == BACKUP_OPEN && result == RESULT_OK && data != null && data.getData() != null) { readBackup(data.getData()); return; }
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
        if (request == SHOWCASE) {
            if (result != RESULT_OK || data == null) { message("The showcase was not recorded: Android's screen recording was not allowed."); return; }
            ShowcaseService.listener = showcaseListener; Showcase.stage = Showcase.MAIN; Showcase.video = null;
            try { startForegroundService(new Intent(this, ShowcaseService.class).putExtra(ShowcaseService.EXTRA_RESULT, result).putExtra(ShowcaseService.EXTRA_DATA, data)); }
            catch (Exception failed) { Showcase.stop(); message("The showcase could not start recording: " + failed.getMessage()); }
        }
        if (request == SAVE_SHOWCASE && result == RESULT_OK && data != null && data.getData() != null && Showcase.video != null) {
            File source = Showcase.video; android.net.Uri target = data.getData();
            checks.execute(() -> {
                String text;
                try (InputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new IOException();
                    byte[] buffer = new byte[1 << 16]; int count; while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    text = "Showcase video saved.";
                } catch (IOException error) { text = "The showcase video could not be saved there."; }
                String done = text; main.post(() -> { if (!isDestroyed()) message(done); });
            });
        }
        if (request == GCODE_FOLDER && result == RESULT_OK && data != null && data.getData() != null && printer != null) {
            try { getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION); printer.setGcodeFolder(data.getData()); }
            catch (SecurityException refused) { message("Android did not let the app keep reading that folder. Choose it again."); }
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
        if (request == CLOUD_LOGIN) { renderCloudAccount(); if (result == RESULT_OK) { message("Signed in with Elegoo. The Print tab now shows your printer through the cloud when there is no local connection."); selectPage(0); } }
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
    @Override protected void onStart() { rateCheckedAt = 0;
        super.onStart(); active = true; monitorCameraTried = false;
        if (!"robolectric".equals(android.os.Build.FINGERPRINT) && !Showcase.running()) checkForUpdate(false);   // at most once a day, if allowed
        // Background cloud watching runs as a started foreground service; start it while the app is visible.
        if (settings.getBoolean("cloudBackground", false) && cloudBackground != null && cloudBackground.isEnabled()) try { startForegroundService(new Intent(this, PrinterService.class)); } catch (Exception ignored) { }
        if (printer != null) { printer.observe(this::render); printer.cloudVisible(true); } main.post(clock);
        if (Showcase.stage == Showcase.END) main.postDelayed(this::showcaseEnd, 400);
    }
    @Override protected void onStop() {
        if (printer != null && printer.usingCloud() && !printer.cloudBackground() && printer.recorder != null && printer.recorder.current() != null)
            Diagnostics.note(Diagnostics.STATUS, "the print's recording pauses while the app is closed: watching through the cloud in the background is off (Print options or Settings turn it on)");
        active = false; stopCamera(); if (toolpathPreview != null) toolpathPreview.release(); main.removeCallbacks(clock); if (printer != null) { printer.observe(null); printer.cloudVisible(false); } super.onStop(); }
    @Override protected void onDestroy() { if (showcaseScript != null) showcaseScript.cancel(); if (ShowcaseService.listener == showcaseListener && !isChangingConfigurations()) ShowcaseService.listener = null; if (scanning != null) scanning.close(); stopCamera(); if (toolpathPreview != null) toolpathPreview.shutdown(); if (printer != null) printer.observe(null); if (bound) unbindService(binding); checks.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("exportHash", pendingExportHash); out.putString("exportUri", pendingExportUri == null ? "" : pendingExportUri.toString()); out.putString("host", host.getText().toString()); out.putString("serial", serial.getText().toString()); out.putString("diagnostics", diagnostics.getText().toString()); out.putString("profileName", profileName.getText().toString()); out.putBoolean("remember", remember.isChecked()); out.putInt("tab", page); }
    private static final class TransientInputs { String host, code; Bitmap snapshot; }
    @Override public Object onRetainNonConfigurationInstance() { TransientInputs state = new TransientInputs(); state.host = host.getText().toString(); state.code = access.getText().toString(); state.snapshot = pendingSnapshot; return state; }
    // ------------------------------------------------------------------ local and cloud together

    private CompoundButton followBox, localCameraBox;
    private TextView followLabel, cameraTryLabel, folderLabel, listenLabel, otherAppLabel;
    private Button cameraTry, folderButton, folderStop, listenButton;
    /** The Printer tab's card for using this Wi-Fi alongside the cloud. Everything here only reads from the printer. */
    private void buildHybrid() {
        currentSection = hybridSection.body;
        LinearLayout follow = card("Follow the printer's mode");
        label(follow, "Turn this on to use your Wi-Fi when the printer is in LAN Only mode, and go back to the cloud when it returns to cloud mode. The app checks once a minute and connects with your saved access code. It leaves a connection you made yourself alone.", 13, MUTED, false);
        followBox = checkbox(follow, "Switch with the printer's mode", true);
        followBox.setOnCheckedChangeListener((view, on) -> { if (printer != null && on != printer.followMode()) printer.setFollowMode(on); });
        followLabel = A11y.polite(label(follow, "", 13, INK, false));
        label(follow, "In cloud mode the printer closes its file server, so files come down only in LAN Only mode.", 13, MUTED, false);

        LinearLayout camera = card("The printer's own camera");
        label(camera, "Try the printer's own camera on this Wi-Fi. If it works, the app can show it instead of the cloud video: sharper, less delay, no relay. Away from home the cloud video is still used.", 13, MUTED, false);
        cameraTry = button(camera, "Try the printer's own camera", () -> { if (printer != null) printer.tryLocalCamera(); });
        cameraTryLabel = A11y.polite(label(camera, "", 13, INK, false));
        localCameraBox = checkbox(camera, "Use it on this Wi-Fi while watching through the cloud", false);
        localCameraBox.setOnCheckedChangeListener((view, on) -> { if (printer != null) printer.useLocalCamera(on); });
        label(camera, "The app tries once each time it starts. In cloud mode the camera port can stop answering after a few tries until the printer restarts. Other projects report that stock firmware pauses this stream in cloud mode.", 13, MUTED, false);

        LinearLayout folder = card("Your G-code folder");
        label(folder, "Choose the folder your slicer exports to, synced to this phone (for example with Google Drive or Syncthing). When a print starts whose file is not on this phone, the app looks there by name. It only reads the folder, and uses a file only if its size matches the printer's.", 13, MUTED, false);
        LinearLayout folderRow = row(folder);
        folderButton = rowButton(folderRow, "Choose folder…", () -> {
            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), GCODE_FOLDER); }
            catch (Exception none) { message("This phone has no folder picker."); }
        }, true);
        folderStop = rowButton(folderRow, "Stop using it", () -> { if (printer != null) printer.setGcodeFolder(null); }, false);
        folderLabel = A11y.polite(label(folder, "", 13, INK, false));
        label(folder, "Or share a .gcode file to Link Workshop. Either way, the live toolpath needs no download from the printer.", 13, MUTED, false);

        LinearLayout tests = card("Experiment");
        label(tests, "Check whether a printer in cloud mode still reports its status on your Wi-Fi. Enter the pairing PIN from the printer's screen. The app listens to the printer's local broker for 30 seconds and sends nothing: no requests, no registration. The PIN is used once and not kept. Another project saw nothing here; this checks your firmware.", 13, MUTED, false);
        listenButton = button(tests, "Try listening for local status…", this::listenDialog);
        listenLabel = A11y.polite(label(tests, "", 13, INK, false));
        label(tests, "When Elegoo's phone app starts a print through the cloud, the printer fetches the file from Elegoo's storage. This listens on your account's cloud channel for 15 minutes while you start a print there, and sends nothing. If the printer names the file it fetches, the live toolpath can download it the same way. The log keeps the shape of what the printer sent, without values.", 13, MUTED, false);
        button(tests, "Listen for a print sent from Elegoo's app…", () -> { if (printer != null) cloudGate(true, printer::listenForOtherApps); });
        otherAppLabel = A11y.polite(label(tests, "", 13, INK, false));
        currentSection = null;
    }
    private void listenDialog() {
        if (printer == null) return;
        EditText pin = new EditText(this); pin.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pin.setHint("Pairing PIN"); pin.setSaveEnabled(false); pin.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout box = new LinearLayout(this); box.setPadding(dp(20), dp(8), dp(20), 0); box.addView(pin, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Listen for local status")
            .setMessage("Type the pairing PIN exactly as the printer shows it: letters and numbers, capitals matter. The app listens for 30 seconds and sends nothing to the printer.")
            .setView(box)
            .setPositiveButton("Listen", (d, w) -> printer.tryListen(pin.getText().toString()))
            .setNegativeButton("Cancel", null).show();
    }
    private void renderHybrid() {
        if (followBox == null) return;
        boolean bound = printer != null;
        if (bound && followBox.isChecked() != printer.followMode()) followBox.setChecked(printer.followMode());
        followLabel.setText(bound && printer.followText != null ? StatusPresentation.clean(printer.followText) : bound && printer.cloudSignedIn ? "Checking for LAN Only mode every minute." : "Sign in with Elegoo in Settings to watch through the cloud. Follow mode switches between it and your Wi-Fi.");
        cameraTry.setEnabled(bound && !printer.localCameraTrying && !printer.localCameraTried());
        cameraTryLabel.setText(bound && printer.localCameraText != null ? StatusPresentation.clean(printer.localCameraText) : "");
        cameraTryLabel.setVisibility(cameraTryLabel.getText().length() == 0 ? View.GONE : View.VISIBLE);
        boolean useLocal = getSharedPreferences("workshop-settings", MODE_PRIVATE).getBoolean("localCameraInCloud", false);
        if (localCameraBox.isChecked() != useLocal) localCameraBox.setChecked(useLocal);
        boolean hasFolder = bound && printer.hasGcodeFolder();
        folderButton.setText(hasFolder ? "Choose another…" : "Choose folder…"); folderStop.setEnabled(hasFolder);
        folderLabel.setText(bound && printer.folderText != null ? StatusPresentation.clean(printer.folderText) : hasFolder ? "Folder chosen." : "No folder chosen.");
        listenButton.setEnabled(bound && !printer.listening);
        listenLabel.setText(bound && printer.listenText != null ? StatusPresentation.clean(printer.listenText) : "");
        listenLabel.setVisibility(listenLabel.getText().length() == 0 ? View.GONE : View.VISIBLE);
        otherAppLabel.setText(bound ? printer.otherAppListen : ""); otherAppLabel.setVisibility(otherAppLabel.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ showcase video

    /** What the showcase does, before Android asks to allow the screen recording. */
    private void showcaseIntro() {
        new AlertDialog.Builder(this).setTitle("Showcase video")
            .setMessage("The app tours itself for about two minutes while it records the screen: the Print, Printer, Files and History tabs, "
                + "the live toolpath with its options changing, and a print's graphs, with captions.\n\n"
                + "It only moves between screens and changes how things are shown; nothing is sent to the printer. The printer's address, "
                + "serial and name are left out. No sound is recorded. Best during a print, with its file on this phone.\n\n"
                + "Android will ask to allow the recording: choose this app (or the entire screen). Please don't touch the screen until it ends.")
            .setPositiveButton("Start", (d, w) -> {
                android.media.projection.MediaProjectionManager manager = getSystemService(android.media.projection.MediaProjectionManager.class);
                try { startActivityForResult(manager.createScreenCaptureIntent(), SHOWCASE); } catch (Exception none) { message("This phone cannot record its screen for apps."); }
            })
            .setNegativeButton("Cancel", null).show();
    }
    private final ShowcaseService.Listener showcaseListener = new ShowcaseService.Listener() {
        @Override public void started(boolean ok, String problem) {
            if (!ok) { Showcase.stop(); message("The showcase could not start recording: " + problem); return; }
            if (!isDestroyed()) main.postDelayed(MainActivity.this::showcaseMain, 600);
        }
        @Override public void finished(File video) {
            Showcase.stop(); Showcase.video = video;
            if (isDestroyed()) return;
            Showcase.clear(MainActivity.this); render();
            if (video == null) { message("The showcase recording was too short to keep."); return; }
            new AlertDialog.Builder(MainActivity.this).setTitle("Showcase recorded")
                .setMessage(String.format(Locale.getDefault(), "The video is ready (%.1f MB). Save it where you like, then share it from there.", video.length() / 1e6))
                .setPositiveButton("Save…", (d, w) -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("video/mp4").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "link-workshop-showcase.mp4"), SAVE_SHOWCASE))
                .setNegativeButton("Discard", (d, w) -> video.delete()).show();
        }
    };
    private Showcase.Script showcaseScript;
    /** The main tabs' part of the tour. Only shows things: the camera stream it may open is read-only. */
    private void showcaseMain() {
        if (Showcase.stage != Showcase.MAIN) return;
        render();
        JSONObject snapshot = printer == null ? null : printer.liveStatus();
        JSONObject machineState = snapshot == null ? null : snapshot.optJSONObject("machine_status");
        boolean printing = machineState != null && machineState.optInt("status", -1) == 2;
        boolean[] cameraByTour = {false};
        Showcase.Script script = new Showcase.Script(this); showcaseScript = script;
        script.then(0, () -> { selectPage(PRINT); mainScroll.scrollTo(0, 0); Showcase.card(this, "Link Workshop", printing ? "A tour on a real print" : "A tour of the app", 2600); })
            .then(3300, () -> Showcase.caption(this, printing ? "Live status: progress, layer, temperatures and time left" : "The printer at a glance"))
            .then(3800, () -> {
                if (printer != null && printer.ready() && cameraPlayer == null) { cameraByTour[0] = true; toggleCamera(); Showcase.caption(this, "The printer's camera, live on the phone"); }
            })
            .then(cameraByTour[0] || (printer != null && printer.ready()) ? 4200 : 0, () -> { Showcase.caption(this, "Everything for the print on one page"); Showcase.glide(mainScroll, (int) (pages[PRINT].getHeight() * 0.45f), 3200); })
            .then(4200, () -> Showcase.glide(mainScroll, 0, 1400))
            .then(1700, () -> Showcase.fade(mainScroll, () -> { if (cameraByTour[0]) stopCamera(); selectPage(PRINTER); mainScroll.scrollTo(0, 0); Showcase.caption(this, "Printer: temperatures, fans, light and spools"); }))
            .then(3600, () -> Showcase.glide(mainScroll, (int) (pages[PRINTER].getHeight() * 0.4f), 3000))
            .then(3600, () -> Showcase.fade(mainScroll, () -> { selectPage(FILES); mainScroll.scrollTo(0, 0); Showcase.caption(this, "Files on the printer and on this phone, sliced right here"); }))
            .then(3800, () -> Showcase.fade(mainScroll, () -> { selectPage(HISTORY); mainScroll.scrollTo(0, 0); Showcase.caption(this, "Your printing: prints, hours, filament and its cost"); }))
            .then(4000, () -> Showcase.caption(this, null))
            .then(400, this::showcaseNext);
        script.run(null);
    }
    /** From the tabs to the live toolpath (or a file's toolpath), or on to a recording when there is no toolpath to show. */
    private void showcaseNext() {
        if (Showcase.stage != Showcase.MAIN) return;
        JSONObject snapshot = printer == null ? null : printer.liveStatus();
        JSONObject machineState = snapshot == null ? null : snapshot.optJSONObject("machine_status");
        boolean printing = machineState != null && machineState.optInt("status", -1) == 2;
        Intent viewer = null;
        if (printing && printer != null && (printer.ready() || printer.usingCloud())) viewer = new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true);
        else if (printer != null && printer.selectedFile != null) viewer = new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FILE, printer.selectedFile.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, printer.selectedName);
        if (viewer != null) { Showcase.stage = Showcase.VIEWER; startActivity(viewer.putExtra(Showcase.EXTRA, true)); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); return; }
        String meta = Showcase.recordingMeta(this, printer);
        if (meta != null) { Showcase.stage = Showcase.RECORDING; startActivity(new Intent(this, RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, meta).putExtra(Showcase.EXTRA, true)); overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out); return; }
        Showcase.stage = Showcase.END; showcaseEnd();
    }
    /** The closing card, then the recording stops (and the save offer follows). */
    private void showcaseEnd() {
        if (Showcase.stage != Showcase.END || isDestroyed()) return;
        selectPage(PRINT); mainScroll.scrollTo(0, 0);
        Showcase.card(this, "Link Workshop", "Recorded in the app, on a real printer", 0);
        main.postDelayed(() -> { if (ShowcaseService.recording) startService(new Intent(this, ShowcaseService.class).setAction(ShowcaseService.ACTION_STOP)); else { Showcase.stop(); Showcase.clear(this); } }, 3800);
    }

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
    private TextView updateText;
    /**
     * Looks for a newer signed release: asked (says what it found) or by itself at most once a day (says something only for
     * a newer version, once per version). The download opens in the browser; nothing installs by itself.
     */
    private void checkForUpdate(boolean asked) {
        long now = System.currentTimeMillis();
        if (!asked && (!settings.getBoolean(UpdateCheck.PREF_AUTO, true) || now - settings.getLong(UpdateCheck.PREF_LAST, 0) < UpdateCheck.DAY_MS)) return;
        settings.edit().putLong(UpdateCheck.PREF_LAST, now).apply();
        if (asked) { updateText.setText("Checking…"); updateText.setVisibility(View.VISIBLE); }
        String current = appVersion();
        checks.execute(() -> {
            UpdateCheck.Release found; String problem = null;
            try { found = UpdateCheck.fetch(); } catch (Exception failed) { found = null; problem = "Could not reach GitHub: " + Diagnostics.scrub(failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage()); }
            UpdateCheck.Release release = found; String why = problem;
            main.post(() -> {
                if (isDestroyed()) return;
                boolean newer = release != null && UpdateCheck.compare(release.version, current) > 0;
                if (asked || newer) {
                    updateText.setText(why != null ? why : release == null ? "No signed release is published yet." : newer ? "Version " + release.version + " is available (this is " + current + ")." : "This is the newest version (" + current + ").");
                    updateText.setVisibility(View.VISIBLE);
                }
                if (!newer || !asked && release.version.equals(settings.getString("updateOffered", ""))) return;
                settings.edit().putString("updateOffered", release.version).apply();
                boolean development = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                new AlertDialog.Builder(this).setTitle("Link Workshop " + release.version + " is available")
                    .setMessage((release.notes.isEmpty() ? "" : release.notes + "\n\n") + "The download opens in your browser; open it to install. Android checks that it is signed like this app."
                        + (development ? "\n\nThis phone has a development build, signed with a different key: Android will refuse to install over it. Make a backup first (Settings › Backup), uninstall, install the release, then restore." : ""))
                    .setNegativeButton("Later", null)
                    .setNeutralButton(release.page.isEmpty() ? null : "Release page", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(release.page))))
                    .setPositiveButton("Download", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(release.apk)))).show();
            });
        });
    }
    private String appVersion() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception error) { return "dev"; } }
    /** A card heading with a "More ›" style link on the right that opens the card's details. Returns the link. */
    private Button cardHeader(LinearLayout card, String heading, String link, Runnable action) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        TextView name = new TextView(this); name.setText(heading); name.setTextSize(17); name.setTextColor(INK); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD); A11y.heading(name);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Button button = new A11y.DimButton(this); button.setText(link); button.setAllCaps(false); button.setTextSize(14); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setTextColor(tint(TEAL));
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setMinWidth(dp(48)); button.setMinimumWidth(dp(48)); button.setPadding(dp(12), 0, dp(4), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), null, new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        button.setOnClickListener(view -> action.run()); row.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return button;
    }
    /** Hide camera folds the Print tab's camera away (and stops its stream unless the Camera section uses it); Show camera brings it back. */
    private void hideMonitorCamera(boolean hide) {
        settings.edit().putBoolean("printCameraHidden", hide).apply();
        if (hide && (cameraPlayer != null || cloudVideo != null) && opened[PRINT] != cameraSection) stopCamera();
        if (!hide) monitorCameraTried = false;   // a stream the user had on starts again
        render();
    }
    /** The Print tab camera button: the local stream here (remembered for next time), or the cloud camera screen when only the cloud reaches the printer. */
    // ------------------------------------------------------------------ cameras the person added
    private int extraCount = -1;
    private ExtraCameras.Camera monitorExtra() {
        String id = settings.getString("monitorSource", "");
        return id.isEmpty() ? null : ExtraCameras.find(this, id);
    }
    private void chooseMonitorSource() {
        List<ExtraCameras.Camera> cameras = ExtraCameras.list(this);
        String[] names = new String[cameras.size() + 1]; names[0] = "The printer's camera";
        for (int i = 0; i < cameras.size(); i++) names[i + 1] = cameras.get(i).name;
        new AlertDialog.Builder(this).setTitle("Camera on the Print tab").setItems(names, (d, which) -> {
            stopCamera();
            settings.edit().putString("monitorSource", which == 0 ? "" : cameras.get(which - 1).id).apply();
            if (which > 0) watchExtra(cameras.get(which - 1));
            render();
        }).setNegativeButton("Cancel", null).show();
    }
    private void watchExtra(ExtraCameras.Camera camera) {
        stopCamera();
        extraShown = camera; long openedAt = android.os.SystemClock.elapsedRealtime(); boolean[] first = {true};
        Diagnostics.note(Diagnostics.CAMERA, "added camera “" + camera.name + "” opening (" + camera.kind + ")");
        String source = "the added camera “" + camera.name + "”";
        extraFeed = ExtraCameras.open(this, camera, new MjpegPlayer.Listener() {
            public void frame(Bitmap image) {
                if (isDestroyed() || extraShown != camera) return;
                lastFrame = image;
                if (first[0]) { first[0] = false; Diagnostics.note(Diagnostics.CAMERA, "added camera “" + camera.name + "”: first picture after " + (android.os.SystemClock.elapsedRealtime() - openedAt) + " ms, " + image.getWidth() + "x" + image.getHeight()); }
                monitorImage.setImageBitmap(image); extraImage.setImageBitmap(image); extraImage.setVisibility(View.VISIBLE);
                extraInfo.setText("Live · " + camera.name); extraInfo.setVisibility(View.VISIBLE);
                monitorCameraInfo.setVisibility(View.GONE);
                if (fullView != null) fullView.setImageBitmap(image);
                tone(image);
                if (printer != null) printer.offerFrame(image, "added:" + camera.id, source);
            }
            public void error(String text) {
                Diagnostics.note(Diagnostics.CAMERA, "added camera “" + camera.name + "” failed" + (first[0] ? " before any picture" : "") + ": " + Diagnostics.scrub(text));
                stopCamera(); String said = camera.name + ": " + text;
                extraInfo.setText(said); extraInfo.setVisibility(View.VISIBLE); monitorCameraInfo.setText(said); monitorCameraInfo.setVisibility(View.VISIBLE); render();
            }
        });
        if (monitorCameraStart != null) monitorCameraStart.setText("Stop camera");
        renderExtraCameras();
    }
    private void renderExtraCameras() {
        if (extraList == null) return;
        extraList.removeAllViews();
        List<ExtraCameras.Camera> cameras = ExtraCameras.list(this); extraCount = cameras.size();
        for (ExtraCameras.Camera c : cameras) {
            label(extraList, c.name + " · " + (ExtraCameras.SNAPSHOT.equals(c.kind) ? "snapshots" : "stream") + (c.rotation != 0 ? " · turned " + c.rotation + "°" : "") + (c.pictures ? " · takes the print pictures" : ""), 14, INK, true);
            LinearLayout line = row(extraList);
            boolean playing = extraFeed != null && extraShown != null && extraShown.id.equals(c.id);
            rowButton(line, playing ? "Stop" : "Watch", () -> { if (playing) stopCamera(); else watchExtra(c); renderExtraCameras(); render(); }, false);
            rowButton(line, "Edit…", () -> editExtraCamera(c), false);
            rowButton(line, "Line up in 3D…", () -> { if (printer == null) return;
                Runnable open = () -> { stopCamera(); startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FOLLOW, true).putExtra(GcodeViewerActivity.EXTRA_ALIGN, true).putExtra(GcodeViewerActivity.EXTRA_CAMERA, c.id)); };
                cloudGate(!printer.ready() && printer.usingCloud(), open); }, false);
        }
        if (extraImage != null && extraFeed == null) extraImage.setVisibility(View.GONE);
    }
    /** Adds (camera null) or edits a camera: name, address, sign-in, turn; Test shows what the address serves. */
    private void editExtraCamera(ExtraCameras.Camera existing) {
        ExtraCameras.Camera camera = existing != null ? existing : new ExtraCameras.Camera();
        LinearLayout body = dialogBody();
        EditText name = input(body, "Name", false); name.setText(existing == null ? "" : camera.name); name.setHint("Side camera");
        EditText address = input(body, "Address", false); address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(existing == null ? "" : ExtraCameras.url(this, camera)); address.setHint("http://192.168.1.50:8080/?action=stream");
        label(body, "For example: mjpg-streamer or a Raspberry Pi webcam http://ADDRESS:8080/?action=stream (or ?action=snapshot); go2rtc http://ADDRESS:1984/api/stream.mjpeg?src=NAME; ESP32-CAM http://ADDRESS:81/stream; an IP-webcam phone app http://ADDRESS:8080/video; an IP camera's snapshot address. RTSP is not played directly: use the camera's snapshot address or a bridge such as go2rtc.", 12, MUTED, false);
        EditText user = input(body, "User name (if the camera asks)", false); user.setText(camera.user);
        EditText password = input(body, existing != null && !ExtraCameras.password(this, camera).isEmpty() ? "Password (kept; blank keeps it)" : "Password (if the camera asks)", true);
        subheading(body, "Picture");
        Spinner turn = spinner(body, new String[] {"As it comes", "Turned 90° right", "Turned upside down", "Turned 90° left"});
        turn.setSelection(camera.rotation / 90);
        CompoundButton mirror = checkbox(body, "Mirror (left to right)", camera.mirror);
        CompoundButton pictures = checkbox(body, "Takes the print pictures instead of the printer's camera", camera.pictures);
        label(body, "Print pictures go into recordings, timelapses, notifications, Home Assistant and automations. The print guardian checks them once this camera is lined up in 3D (Line up in 3D…).", 12, MUTED, false);
        TextView result = A11y.polite(label(body, "", 13, INK, false));
        ImageView preview = new ImageView(this); preview.setAdjustViewBounds(true); preview.setVisibility(View.GONE); body.addView(preview, new LinearLayout.LayoutParams(-1, dp(160)));
        String[] kind = {camera.kind}; boolean[] tested = {existing != null};
        button(body, "Test", () -> {
            String url = address.getText().toString().trim(), problem = ExtraCameras.problem(url);
            if (!problem.isEmpty()) { result.setText(problem); result.setTextColor(ERROR); return; }
            String pass = password.getText().toString().isEmpty() && existing != null ? ExtraCameras.password(this, camera) : password.getText().toString();
            result.setText("Asking the camera…"); result.setTextColor(MUTED);
            String who = user.getText().toString().trim();
            checks.execute(() -> {
                Bitmap[] picture = new Bitmap[1]; String found, error = null;
                try { found = ExtraCameras.probe(url, ExtraCameras.connections(who, pass), picture); } catch (Exception failed) { found = null; error = ExtraCameras.describe(failed); }
                String foundKind = found, why = error;
                main.post(() -> {
                    if (isDestroyed()) return;
                    if (foundKind == null) { result.setText("Not working: " + why + "."); result.setTextColor(ERROR); preview.setVisibility(View.GONE); return; }
                    kind[0] = foundKind; tested[0] = true;
                    Bitmap shown = ExtraCameras.turn(picture[0], turn.getSelectedItemPosition() * 90, mirror.isChecked());
                    result.setText("Works: " + (ExtraCameras.STREAM.equals(foundKind) ? "a live MJPEG stream" : "a snapshot picture (the app asks for a new one every second)") + ", " + picture[0].getWidth() + "×" + picture[0].getHeight() + ".");
                    result.setTextColor(TEAL); preview.setImageBitmap(shown); preview.setVisibility(View.VISIBLE);
                });
            });
        });
        ScrollView scroll = new ScrollView(this); scroll.addView(body);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(existing == null ? "Add a camera" : "Edit " + camera.name).setView(scroll)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null);
        if (existing != null) builder.setNeutralButton("Remove", (d, w) -> new AlertDialog.Builder(this).setTitle("Remove " + camera.name + "?")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove", (d2, w2) -> {
                if (extraShown != null && extraShown.id.equals(camera.id)) stopCamera();
                List<ExtraCameras.Camera> all = ExtraCameras.list(this); all.removeIf(c -> c.id.equals(camera.id)); ExtraCameras.save(this, all); ExtraCameras.forget(this, camera);
                if (camera.id.equals(settings.getString("monitorSource", ""))) settings.edit().putString("monitorSource", "").apply();
                Diagnostics.note(Diagnostics.CAMERA, "added camera “" + camera.name + "” removed"); renderExtraCameras(); render();
            }).show());
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = address.getText().toString().trim(), problem = ExtraCameras.problem(url);
            if (!problem.isEmpty()) { result.setText(problem); result.setTextColor(ERROR); return; }
            if (!tested[0]) {   // not tested: guess from the address (a snapshot address usually says so)
                String lower = url.toLowerCase(Locale.ROOT);
                kind[0] = lower.contains("snapshot") || lower.contains("snap") || lower.contains("frame.jp") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? ExtraCameras.SNAPSHOT : ExtraCameras.STREAM;
            }
            camera.name = Automation.cap(name.getText().toString().trim().isEmpty() ? "Camera" : name.getText().toString().trim(), 40);
            camera.user = user.getText().toString().trim(); camera.kind = kind[0]; camera.rotation = turn.getSelectedItemPosition() * 90; camera.mirror = mirror.isChecked();
            camera.pictures = pictures.isChecked();
            try { ExtraCameras.setSecrets(this, camera, url, password.getText().toString().isEmpty() && existing != null ? null : password.getText().toString()); }
            catch (Exception failed) { result.setText("Could not keep the address safely on this phone."); result.setTextColor(ERROR); return; }
            List<ExtraCameras.Camera> all = ExtraCameras.list(this);
            if (camera.pictures) for (ExtraCameras.Camera c : all) c.pictures = false;   // one camera takes the pictures
            boolean replaced = false;
            for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(camera.id)) { all.set(i, camera); replaced = true; }
            if (!replaced) {
                if (all.size() >= 8) { result.setText("At most 8 added cameras."); result.setTextColor(ERROR); return; }
                all.add(camera);
            }
            ExtraCameras.save(this, all);
            Diagnostics.note(Diagnostics.CAMERA, "added camera “" + camera.name + "” " + (existing == null ? "added" : "changed") + " (" + camera.kind + (camera.pictures ? ", takes the print pictures" : "") + ")");
            dialog.dismiss(); renderExtraCameras(); render();
        }));
        dialog.show();
    }
    private void monitorCameraTap() {
        if (toolpathPreviewOn()) { choosePreview(false); return; }
        ExtraCameras.Camera chosen = monitorExtra();
        if (chosen != null) { if (extraFeed != null) stopCamera(); else watchExtra(chosen); render(); return; }
        if (printer != null && (printer.ready() || printer.localCamera())) {
            boolean on = cameraPlayer == null;
            settings.edit().putBoolean("monitorCamera", on).apply(); monitorCameraTried = true;
            if (on) toggleCamera(); else stopCamera();
            render();
        } else {
            // Through the cloud the video plays here too; remembered like the local one, so it comes back when this tab shows a print.
            boolean on = cloudVideo == null;
            settings.edit().putBoolean("monitorCloudCamera", on).apply(); monitorCameraTried = true;
            if (on) startCloudVideo(); else { stopCamera(); render(); }
        }
    }
    /** Header printer name: pick another saved printer. Connecting is a read; nothing is sent to either printer, and a running print carries on. */
    private void switchPrinter() {
        JSONArray rows = profiles.all();
        boolean connected = printer != null && printer.connecting();
        String current = connected ? printer.host() : host.getText().toString().trim();
        List<String> names = new ArrayList<>(); List<JSONObject> picks = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null || row.optString("host").isEmpty()) continue;
            String name = StatusPresentation.clean(row.optString("name")).trim(); if (name.isEmpty()) name = "Printer at " + row.optString("host");
            names.add(name + (connected && row.optString("host").equals(current) ? " · connected" : "")); picks.add(row);
        }
        names.add("Connection settings…");
        new AlertDialog.Builder(this).setTitle(picks.isEmpty() ? "No saved printers yet" : "Switch printer").setItems(names.toArray(new String[0]), (dialog, which) -> {
            if (which >= picks.size()) { openSettings(false); return; }
            JSONObject row = picks.get(which); String address = row.optString("host");
            String name = StatusPresentation.clean(row.optString("name")).trim(); String shown = name.isEmpty() ? address : name;
            if (connected && address.equals(current)) return;
            Runnable go = () -> {
                if (printer != null && printer.connecting()) printer.disconnect();
                selectPrinter(address, row.optString("serial"), row.optString("name"));
                routePicker.setSelection(row.optBoolean("remote_vpn", false) ? 1 : 0); authPicker.setSelection(row.optBoolean("pin_probe", false) ? 1 : 0);
                if (!row.optBoolean("pin_probe", false) && !access.getText().toString().isEmpty()) toggleConnection();
                else { openSettings(false); message("Enter the " + (row.optBoolean("pin_probe", false) ? "pairing PIN" : "access code") + " for " + shown + ", then Connect."); }
            };
            if (connected) new AlertDialog.Builder(this).setTitle("Switch to " + shown + "?").setMessage("This disconnects from the current printer. Nothing is sent to either printer; a running print carries on.")
                .setNegativeButton("Cancel", null).setPositiveButton("Switch", (d, w) -> go.run()).show();
            else go.run();
        }).setNegativeButton("Cancel", null).show();
    }
    /** The tray list under the colour strip opens and closes with Details. */
    private void renderTrayDetails() {
        boolean any = trayList.getChildCount() > 0;
        trayList.setVisibility(traysOpen && any ? View.VISIBLE : View.GONE);
        trayDetails.setVisibility(any ? View.VISIBLE : View.GONE);
        trayDetails.setText(traysOpen ? "Less ▴" : "Details ›");
        trayDetails.setContentDescription(traysOpen ? "Hide tray details" : "Show tray details"); A11y.expandable(trayDetails, traysOpen);
    }
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
            GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(24));
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
    /** The running print's temperatures, read from its recording off the main thread, at most once a minute (or when the print changes). */
    private void renderTelemetry(PrintRecorder.Recording recording) {
        if (recording == null) { telemetryCard.setVisibility(View.GONE); telemetryFor = ""; return; }
        long now = android.os.SystemClock.elapsedRealtime();
        if (recording.id.equals(telemetryFor) && now - telemetryAt < 60_000) return;
        telemetryFor = recording.id; telemetryAt = now;
        boolean darkChart = dark;
        checks.execute(() -> {
            List<double[]> rows; try { rows = PrintRecorder.samples(recording); } catch (Exception unreadable) { rows = java.util.Collections.emptyList(); }
            List<double[]> read = rows;
            main.post(() -> {
                if (isDestroyed() || !recording.id.equals(telemetryFor)) return;
                if (read.size() < 2) { telemetryCard.setVisibility(View.GONE); return; }
                // The recording page's palette (validated for both themes): nozzle blue, bed orange, chamber aqua; dashed lines are targets.
                int blue = darkChart ? 0xff3987e5 : 0xff2a78d6, orange = darkChart ? 0xffd95926 : 0xffeb6834, aqua = darkChart ? 0xff199e70 : 0xff149a69;
                double[] t = RecordingsActivity.column(read, "elapsed_s");
                telemetryChart.set("Temperatures", " °C", t, java.util.Arrays.asList(
                    new ChartView.Series("Nozzle", blue, RecordingsActivity.column(read, "nozzle_c"), false), new ChartView.Series("Nozzle target", blue, RecordingsActivity.column(read, "nozzle_target_c"), true),
                    new ChartView.Series("Bed", orange, RecordingsActivity.column(read, "bed_c"), false), new ChartView.Series("Bed target", orange, RecordingsActivity.column(read, "bed_target_c"), true),
                    new ChartView.Series("Chamber", aqua, RecordingsActivity.column(read, "chamber_c"), false)), Double.NaN, Double.NaN);
                if (recording.start > 0) telemetryChart.clock(recording.start);
                telemetryCard.setVisibility(View.VISIBLE);
            });
        });
    }
    /** Asks where to save the camera's latest picture, then saves it (onActivityResult). */
    private void saveSnapshot() {
        if (cloudVideo != null && pendingCloudSnapshot) { pendingCloudSnapshot = false; }
        else if (cloudVideo != null) { pendingCloudSnapshot = true; cloudVideo.snapshot(); return; }
        if (lastFrame == null) { message("No camera picture yet. Start the camera first."); return; }
        pendingSnapshot = lastFrame.copy(Bitmap.Config.ARGB_8888, false);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("image/jpeg").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "CC2-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".jpg"); startActivityForResult(intent, PICK_SNAPSHOT);
    }
    /** A small white label on a dark plate over the camera picture; tappable (at least 48dp) when it has an action. */
    private TextView overlayChip(ViewGroup parent, String text, int gravity, Runnable action) {
        TextView chip = new TextView(this); chip.setText(text); chip.setTextSize(12); chip.setTextColor(Color.WHITE); chip.setTypeface(Typeface.DEFAULT_BOLD); chip.setGravity(Gravity.CENTER);
        GradientDrawable plate = new GradientDrawable(); plate.setColor(0xb3000000); plate.setCornerRadius(dp(14));
        if (action != null) { chip.setMinHeight(dp(48)); chip.setMinWidth(dp(48)); chip.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55ffffff), new android.graphics.drawable.InsetDrawable(plate, 0, dp(8), 0, dp(8)), null)); chip.setOnClickListener(v -> action.run()); }   // a slim pill on a full-size touch target
        else chip.setBackground(plate);
        chip.setPadding(dp(12), dp(4), dp(12), dp(4));   // after the background, which brings its own padding
        if (parent instanceof FrameLayout) { FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(-2, -2, gravity); at.setMargins(dp(8), dp(8), dp(8), dp(8)); parent.addView(chip, at); }
        else { LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2); at.setMargins(0, dp(8), dp(8), dp(8)); parent.addView(chip, at); }
        return chip;
    }
    /** A small stat tile in the hero (Elapsed, Remaining, Layer): the temperature tile, smaller, on the tile colour. */
    private TextView stat(LinearLayout row, String caption, int gap) {
        TextView value = tile(row, caption, gap); value.setTextSize(17);
        GradientDrawable background = new GradientDrawable(); background.setColor(TILE); background.setCornerRadius(dp(12)); ((View) value.getParent()).setBackground(background);
        return value;
    }
    /** Temperature tile: small caption, large current value, target on a second line. */
    private TextView tile(LinearLayout row, String caption, int gap) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable(); background.setColor(SURFACE); background.setCornerRadius(dp(16)); tile.setBackground(background);
        TextView name = new TextView(this); name.setText(caption); name.setTextSize(12); name.setTextColor(MUTED); tile.addView(name);
        TextView value = new TextView(this); value.setText("—"); value.setTextSize(20); value.setTextColor(INK); value.setTypeface(Typeface.DEFAULT, Typeface.BOLD); tile.addView(value);
        tile.setFocusable(true); name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        boolean stacked = row.getOrientation() == LinearLayout.VERTICAL; // at large font sizes the tiles stack instead of breaking words
        LinearLayout.LayoutParams layout = stacked ? new LinearLayout.LayoutParams(-1, -2) : new LinearLayout.LayoutParams(0, -1, 1);   // side by side: all as tall as the tallest
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
    /** A text field with a small caption above it that names it for screen readers too (a hint alone disappears once text is typed). */
    private EditText input(LinearLayout parent, String hint, boolean password) {
        TextView caption = label(parent, hint, 12, MUTED, false); caption.setPadding(0, dp(6), 0, 0);
        // The caption shows and hides with its field.
        EditText input = new EditText(this) { @Override public void setVisibility(int visibility) { super.setVisibility(visibility); caption.setVisibility(visibility); } };
        input.setId(View.generateViewId()); caption.setLabelFor(input.getId()); input.setHintTextColor(MUTED); input.setBackgroundTintList(tint(TEAL)); input.setSingleLine(true); input.setTextColor(tint(INK)); if (password) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setMinHeight(dp(56)); parent.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new A11y.DimButton(this); button.setText(text); styleButton(button);
        button.setOnClickListener(view -> action.run()); LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(6); parent.addView(button, layout); return button;
    }
    private ColorStateList tint(int color) { return new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}}, new int[] {MUTED, color}); }
    private void styleButton(Button button) {
        button.setAllCaps(false); button.setTextColor(tint(TEAL)); button.setMinHeight(dp(48));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(BUTTON); shape.setCornerRadius(dp(24));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x4463d5c7 : 0x33006b65), shape, null));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.topMargin = dp(6); button.setLayoutParams(layout); button.setPadding(dp(10), dp(8), dp(10), dp(8));
    }
    /** A setting that is on or off: its words, with a switch at the end of the row. */
    private CompoundButton checkbox(LinearLayout parent, String text, boolean checked) {
        android.widget.Switch view = new android.widget.Switch(this); view.setMinHeight(dp(52)); view.setText(text); view.setTextColor(INK); view.setTextSize(15);
        view.setSwitchPadding(dp(16)); view.setPadding(0, dp(4), 0, dp(4)); WorkshopUi.switchColours(view, TEAL, TRACK, dark); view.setChecked(checked);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private static String colourWords(String hex) { String name = WorkshopUi.colourName(hex); return name == null ? "colour not reported" : name; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
