package io.github.thelastfrogrammer.elink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Projects (see Projects): a list of projects, and for one project its overview, planned parts and files. Parts can be
 * planned before any model exists; files are kept as a copy or linked where they are, and one file can belong to several
 * projects and parts without a second copy. Nothing here talks to the printer.
 */
public final class ProjectsActivity extends Activity {
    static final String EXTRA_PROJECT = "project", EXTRA_ADD_PATH = "addPath", EXTRA_ADD_NAME = "addName";
    private static final int PICK_COPY = 1, PICK_LINK = 2, PICK_RELINK = 3, EXPORT = 4, SAVE_ARCHIVE = 5, IMPORT = 6, SAVE_COVER_PNG = 7, SAVE_COVER_PDF = 8;
    private static final String HOME = "home", OVERVIEW = "overview", PARTS = "parts", FILES = "files", PART = "part", UNASSIGNED = "unassigned", ARCHIVED = "archived", COVER = "cover", PRINTS = "prints";

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Reads, copies, hashes and saves, one at a time and in order. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private WorkshopUi ui;
    private LinearLayout content;
    private File dir;
    private Projects.Book book;
    private String screen = HOME, projectId = "", partId = "", roleFilter = "";
    /** Where a picked file goes (project and part), and which file a relink replaces. */
    private String pickProject = "", pickPart = "", relinkAsset = "", exportAsset = "";
    /** A file handed over from another screen (a recent slice), waiting for a project to go into. */
    private String handedPath, handedName;
    /** A project file from another app, waiting for the projects to load. */
    private Uri incoming;
    /** Linked files' state, checked in the background: available, permission needed, missing, changed. */
    private final Map<String, String> availability = new HashMap<>();

    @Override protected void onCreate(Bundle saved) {
        ui = new WorkshopUi(this);
        super.onCreate(saved);
        dir = new File(getFilesDir(), "projects");
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(ui.background); scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24));
        scroll.addView(content); setContentView(scroll);
        if (saved != null) {
            screen = saved.getString("screen", HOME); projectId = saved.getString("project", ""); partId = saved.getString("part", ""); roleFilter = saved.getString("role", "");
            pickProject = saved.getString("pickProject", ""); pickPart = saved.getString("pickPart", ""); relinkAsset = saved.getString("relink", ""); exportAsset = saved.getString("export", "");
        } else {
            String wanted = getIntent().getStringExtra(EXTRA_PROJECT); if (wanted != null) { projectId = wanted; screen = OVERVIEW; }
            handedPath = getIntent().getStringExtra(EXTRA_ADD_PATH); handedName = getIntent().getStringExtra(EXTRA_ADD_NAME);
            // A project file opened or shared from another app: read, then added only when the person says so.
            Intent in = getIntent(); Uri shared = Intent.ACTION_VIEW.equals(in.getAction()) ? in.getData() : Intent.ACTION_SEND.equals(in.getAction()) ? in.getParcelableExtra(Intent.EXTRA_STREAM) : null;
            if (shared != null) incoming = shared;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        ui.label(content, "Loading projects…", 14, ui.muted, false);
        worker.execute(() -> { Projects.Book loaded = Projects.load(dir); main.post(() -> { if (isDestroyed()) return; book = loaded; render(); if (handedPath != null) offerHanded(); if (incoming != null) { Uri u = incoming; incoming = null; importFrom(u); } }); });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("screen", screen); state.putString("project", projectId); state.putString("part", partId); state.putString("role", roleFilter);
        state.putString("pickProject", pickProject); state.putString("pickPart", pickPart); state.putString("relink", relinkAsset); state.putString("export", exportAsset);
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
    /** Back goes up one screen (part to parts, project to the list) before it leaves. */
    private void back() {
        if (screen.equals(PART)) { screen = PARTS; render(); }
        else if (screen.equals(COVER)) { screen = OVERVIEW; render(); }
        else if (!screen.equals(HOME)) { screen = HOME; render(); }
        else finish();
    }
    @SuppressWarnings("deprecation") @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { if (android.os.Build.VERSION.SDK_INT >= 33) super.onBackPressed(); else back(); }

    /** Saves in the background, in order; a failure is said, and the screen shows what is in memory. */
    private void save() {
        String text; try { text = book.toJson(); } catch (Exception impossible) { return; }
        Projects.Book snapshot; try { snapshot = Projects.Book.fromJson(text); } catch (Exception impossible) { return; }
        worker.execute(() -> {
            try { Projects.save(dir, snapshot); }
            catch (Exception failed) { main.post(() -> toast("Could not save the projects: " + failed.getMessage())); }
        });
    }
    private long now() { return System.currentTimeMillis(); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    Projects.Book bookForTest() { return book; }
    private Projects.Project project() { return book == null ? null : book.project(projectId); }

    // ------------------------------------------------------------------ screens

    private void render() {
        if (book == null) return;
        content.removeAllViews();
        Projects.Project project = project();
        if (!screen.equals(HOME) && !screen.equals(UNASSIGNED) && !screen.equals(ARCHIVED) && project == null) screen = HOME;
        switch (screen) {
            case UNASSIGNED: unassigned(); break;
            case ARCHIVED: home(true); break;
            case OVERVIEW: case PARTS: case FILES: case PRINTS: projectScreen(project); break;
            case COVER: coverScreen(project); break;
            case PART: { Projects.Part part = project.part(partId); if (part == null) { screen = PARTS; projectScreen(project); } else partScreen(project, part); break; }
            default: home(false);
        }
    }

    private void home(boolean archived) {
        A11y.heading(ui.label(content, archived ? "Archived projects" : "Projects", 22, ui.ink, true));
        ui.label(content, archived ? "Projects put away. Open one to bring it back." : "Plan what you are making: its parts (even before they have a model), how many of each, and the files and links that belong to it.", 13, ui.muted, false);
        if (!archived) { LinearLayout row = ui.row(content); ui.rowButton(row, "New project", this::newProject, true); ui.rowButton(row, "Import a project…", this::importPick, false); }
        int shown = 0;
        for (Projects.Project p : book.projects) {
            if (p.archived != archived) continue;
            shown++;
            LinearLayout card = ui.card(content, p.title);
            int[] done = p.printedProgress();
            ui.label(card, status(p) + (done[1] > 0 ? " · " + done[0] + " / " + done[1] + " printed parts accepted" : "") + "\n" + summary(p), 13, ui.muted, false);
            if (!p.description.isEmpty()) { TextView d = ui.label(card, p.description, 13, ui.ink, false); d.setMaxLines(2); d.setEllipsize(android.text.TextUtils.TruncateAt.END); }
            String id = p.id;
            card.setOnClickListener(v -> { projectId = id; screen = OVERVIEW; render(); });
            ui.button(card, "Open", () -> { projectId = id; screen = OVERVIEW; render(); }, false).setContentDescription("Open " + p.title);
        }
        if (shown == 0) ui.label(content, archived ? "No archived projects." : "No projects yet. Start one with New project, then add the parts it needs.", 14, ui.muted, false);
        if (!archived) {
            int loose = book.unassigned().size(), away = 0; for (Projects.Project p : book.projects) if (p.archived) away++;
            if (loose > 0) ui.button(content, "Files in no project (" + loose + ")", () -> { screen = UNASSIGNED; render(); }, false);
            if (away > 0) ui.button(content, "Archived projects (" + away + ")", () -> { screen = ARCHIVED; render(); }, false);
        } else ui.button(content, "Back to projects", () -> { screen = HOME; render(); }, false);
    }

    /** "3 parts · 4 pieces to print · 1 of 2 bought · 0 of 1 task done · 5 files". */
    static String summary(Projects.Project p) {
        int printed = 0, pieces = 0, bought = 0, needed = 0, tasks = 0, done = 0, noFile = 0;
        for (Projects.Part part : p.parts) {
            if (part.kind.equals(Projects.PRINTED)) { printed++; pieces += part.quantity; if (p.assetsOn(part.id).isEmpty()) noFile++; }
            else if (part.kind.equals(Projects.PURCHASED)) { needed += part.quantity; bought += Math.min(part.have, part.quantity); }
            else if (part.kind.equals(Projects.TASK)) { tasks++; if (part.done) done++; }
        }
        List<String> out = new ArrayList<>();
        out.add(p.parts.size() == 1 ? "1 part" : p.parts.size() + " parts");
        if (printed > 0) out.add(pieces + (pieces == 1 ? " piece" : " pieces") + " to print" + (noFile > 0 ? " (" + noFile + " without a file yet)" : ""));
        if (needed > 0) out.add(bought + " of " + needed + " bought");
        if (tasks > 0) out.add(done + " of " + tasks + (tasks == 1 ? " task" : " tasks") + " done");
        int files = p.allAssets().size(); out.add(files == 1 ? "1 file" : files + " files");
        return String.join(" · ", out);
    }

    private void newProject() {
        LinearLayout body = form();
        EditText title = field(body, "Name", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText about = field(body, "What it is for (optional)", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(this).setTitle("New project").setView(scroll(body)).setNegativeButton("Cancel", null).setPositiveButton("Create", (d, w) -> {
            Projects.Project p = book.create(title.getText().toString(), now()); p.description = Automation.cap(about.getText().toString().trim(), 4000);
            save(); projectId = p.id; screen = PARTS; render();
            if (handedPath != null) offerHanded();
        }).show();
    }

    private void tabs(Projects.Project p) {
        LinearLayout row = ui.row(content);
        String[][] tabs = {{OVERVIEW, "Overview"}, {PARTS, "Parts"}, {FILES, "Files"}, {PRINTS, "Prints"}};
        for (String[] t : tabs) {
            Button b = ui.rowButton(row, t[1], () -> { screen = t[0]; render(); }, screen.equals(t[0]));
            b.setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8)); b.setSelected(screen.equals(t[0]));
            int count = t[0].equals(PARTS) ? p.parts.size() : t[0].equals(FILES) ? p.allAssets().size() : t[0].equals(PRINTS) ? p.attempts.size() : -1;
            b.setContentDescription(t[1] + (count >= 0 ? ", " + count : "") + (screen.equals(t[0]) ? ", shown" : ""));
        }
    }

    /** The project's header: where it stands (a small overline), its name and purpose, then its views. */
    private void projectScreen(Projects.Project p) {
        ui.button(content, "‹ All projects", () -> { screen = HOME; render(); }, false);
        overline(content, "PROJECT / " + (p.archived ? "ARCHIVED" : p.assembled ? "ASSEMBLED" : "ACTIVE"));
        A11y.heading(ui.label(content, p.title, 24, ui.ink, true));
        if (!p.description.isEmpty()) { TextView d = ui.label(content, p.description, 13, ui.muted, false); d.setMaxLines(3); d.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        tabs(p);
        switch (screen) {
            case OVERVIEW: overview(p); break;
            case PARTS: parts(p); break;
            case PRINTS: prints(p); break;
            default: files(p, null);
        }
    }
    private TextView overline(LinearLayout parent, String text) {
        TextView t = ui.label(parent, text, 11, ui.muted, true); t.setLetterSpacing(0.12f); t.setPadding(0, ui.dp(10), 0, 0); return t;
    }

    // ------------------------------------------------------------------ overview: progress, the next step, a glance at parts and files

    private void overview(Projects.Project p) {
        progressCard(p);
        List<Step> steps = steps(book, p);
        if (!steps.isEmpty()) nextBanner(p, steps.get(0));
        if (steps.size() > 1) {
            sectionHead(content, "Then work on", "Full outline", () -> { screen = PARTS; render(); });
            for (int i = 1; i < Math.min(4, steps.size()); i++) { Step step = steps.get(i); listRow(content, step.title, step.detail, null, null, () -> follow(p, step)); }
        }
        sectionHead(content, "Parts outline", p.parts.isEmpty() ? null : "View all", () -> { screen = PARTS; render(); });
        if (p.parts.isEmpty()) ui.label(content, "Start the outline: add the parts this needs, even before any of them has a model.", 14, ui.ink, false);
        for (Projects.Part part : attention(p, 3)) partRow(content, p, part, null);
        LinearLayout add = ui.row(content);
        ui.rowButton(add, "Add planned part", () -> editPart(p, null), p.parts.isEmpty());
        ui.rowButton(add, "Add files", () -> addFiles(p, ""), false);
        List<String> files = new ArrayList<>(p.allAssets());
        if (!files.isEmpty()) {
            sectionHead(content, "Project files", "See files", () -> { screen = FILES; render(); });
            for (int i = 0; i < Math.min(2, files.size()); i++) { Projects.Asset a = book.asset(files.get(i)); if (a != null) listRow(content, a.name, holdingShort(a), null, null, () -> { screen = FILES; render(); }); }
        }
        LinearLayout share = ui.card(content, "Share");
        ui.label(share, "Send the project as one file another phone imports, or as a plain outline; and make a cover page.", 13, ui.muted, false);
        LinearLayout shareRow = ui.row(share);
        ui.rowButton(shareRow, "Share or export…", () -> exportDialog(p), false);
        ui.rowButton(shareRow, "Cover page…", () -> { screen = COVER; render(); }, false);
        LinearLayout more = ui.row(content);
        ui.rowButton(more, "Rename or describe…", () -> editProject(p), false);
        ui.rowButton(more, p.archived ? "Bring back" : "Archive", () -> { p.archived = !p.archived; p.updated = now(); save(); render(); }, false);
        ui.button(content, "Delete this project…", () -> deleteProject(p), false);
    }

    private void editProject(Projects.Project p) {
        LinearLayout body = form();
        EditText title = field(body, "Name", p.title, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText about = field(body, "What it is for", p.description, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(this).setTitle("Project").setView(scroll(body)).setNegativeButton("Cancel", null).setPositiveButton("Save", (d, w) -> {
            p.title = Projects.title(title.getText().toString()); p.description = Automation.cap(about.getText().toString().trim(), 4000); p.updated = now(); save(); render();
        }).show();
    }
    private void deleteProject(Projects.Project p) {
        new AlertDialog.Builder(this).setTitle("Delete “" + p.title + "”?")
            .setMessage("Its planned parts, notes and print records are deleted. Its files are not: ones no other project uses move to Files in no project, where they can be deleted on their own. Recordings stay in Print recordings.")
            .setNegativeButton("Keep it", null).setPositiveButton("Delete", (d, w) -> { book.projects.remove(p); save(); screen = HOME; render(); }).show();
    }

    /** "3 / 8 printed parts accepted", a bar, and what is still open: kept apart from "assembled and checked". */
    private void progressCard(Projects.Project p) {
        LinearLayout card = ui.card(content, null);
        int[] done = p.printedProgress(); int waiting = 0; for (Projects.Part part : p.parts) if (part.kind.equals(Projects.PRINTED)) waiting += p.waiting(part);
        String state = status(p);
        LinearLayout top = ui.row(card); top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView big = new TextView(this); big.setTextColor(ui.ink); big.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        android.text.SpannableString number = new android.text.SpannableString(done[0] + " / " + done[1]);
        number.setSpan(new android.text.style.RelativeSizeSpan(0.6f), String.valueOf(done[0]).length(), number.length(), 0);
        number.setSpan(new android.text.style.ForegroundColorSpan(ui.muted), String.valueOf(done[0]).length(), number.length(), 0);
        big.setText(number); big.setTextSize(30); top.addView(big, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chip = new TextView(this); chip.setText(state); chip.setTextSize(12); chip.setTextColor(ui.teal); chip.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
        android.graphics.drawable.GradientDrawable chipShape = new android.graphics.drawable.GradientDrawable(); chipShape.setColor(ui.button); chipShape.setCornerRadius(ui.dp(8)); chip.setBackground(chipShape);
        top.addView(chip);
        ui.label(card, done[1] == 0 ? "No printed parts planned yet" : "printed parts accepted", 13, ui.muted, false);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(Math.max(1, done[1])); bar.setProgress(done[0]);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.teal)); bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(ui.button));
        bar.setContentDescription(done[0] + " of " + done[1] + " printed parts accepted"); card.addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(10)));
        List<String> open = new ArrayList<>();
        if (done[1] > done[0]) open.add((done[1] - done[0]) + " still needed");
        if (waiting > 0) open.add(waiting + " awaiting inspection");
        open.add(p.assembled ? "assembled and checked" : "assembly not checked");
        ui.label(card, String.join(" · ", open), 13, ui.muted, false);
        if (p.allAvailable() || p.assembled) {
            CheckBox assembled = check(card, "Assembled and checked (your call: printing alone does not say it fits)", p.assembled);
            assembled.setOnCheckedChangeListener((v, on) -> { p.assembled = on; p.updated = now(); save(); render(); });
        }
    }
    static String status(Projects.Project p) {
        if (p.assembled) return "Assembled";
        if (p.allAvailable()) return "All parts available";
        int[] done = p.printedProgress(); boolean printing = done[0] > 0 || !p.attempts.isEmpty();
        return printing ? "In progress" : p.parts.isEmpty() ? "Starting" : "Planning";
    }

    /** One step to take, worked out from the project: what to inspect, design, revise, print, buy or do. */
    static final class Step {
        final String title, detail, action, partId;
        Step(String title, String detail, String action, String partId) { this.title = title; this.detail = detail; this.action = action; this.partId = partId; }
    }
    /** The steps in the order they matter: judge printed pieces first, then fix what failed, design what is missing, print, buy, do. */
    static List<Step> steps(Projects.Book book, Projects.Project p) {
        List<Step> out = new ArrayList<>();
        for (Projects.Part part : p.parts) {
            int waiting = part.kind.equals(Projects.PRINTED) ? p.waiting(part) : 0;
            if (waiting > 0) out.add(new Step("Review " + waiting + " printed " + (waiting == 1 ? part.name.toLowerCase(Locale.getDefault()) : part.name.toLowerCase(Locale.getDefault()) + " pieces"),
                "Confirm fit before adding to your count", "inspect", part.id));
        }
        for (Projects.Part part : p.parts) if (part.kind.equals(Projects.PRINTED) && part.design.equals("revise"))
            out.add(new Step("Revise the " + part.name.toLowerCase(Locale.getDefault()), part.notes.isEmpty() ? "Marked as needing a revision" : firstLine(part.notes), "part", part.id));
        for (Projects.Part part : p.parts) if (part.kind.equals(Projects.PRINTED) && !hasModel(book, p, part) && !part.design.equals("revise")) {
            boolean sketch = false; for (String id : p.assetsOn(part.id)) { Projects.Asset a = book.asset(id); sketch |= a != null && (a.role.equals("image") || a.role.equals("source")); }
            out.add(new Step("Design the " + part.name.toLowerCase(Locale.getDefault()), "Planned part · " + (sketch ? "sketch attached" : "no model yet"), "part", part.id));
        }
        for (Projects.Part part : p.parts) {
            if (!part.kind.equals(Projects.PRINTED) || !hasModel(book, p, part) || part.design.equals("revise")) continue;
            int missing = part.quantity - p.accepted(part) - p.waiting(part);
            if (missing > 0) out.add(new Step("Print " + missing + " more " + part.name.toLowerCase(Locale.getDefault()), (part.revision.isEmpty() ? "" : "Revision " + part.revision + " · ") + p.accepted(part) + " of " + part.quantity + " accepted", "prepare", part.id));
        }
        for (Projects.Part part : p.parts) if (part.kind.equals(Projects.PURCHASED) && part.have < part.quantity)
            out.add(new Step("Get " + (part.quantity - part.have) + " " + part.name, part.have + " of " + part.quantity + " at hand", "part", part.id));
        for (Projects.Part part : p.parts) if (part.kind.equals(Projects.TASK) && !part.done) out.add(new Step(part.name, "Task", "part", part.id));
        if (p.allAvailable() && !p.assembled) out.add(new Step("Assemble and check it", "Every part is available. Mark it assembled once it fits together.", "assemble", ""));
        return out;
    }
    private static String firstLine(String text) { String t = text.trim(); int n = t.indexOf('\n'); return n < 0 ? t : t.substring(0, n); }
    static boolean hasModel(Projects.Book book, Projects.Project p, Projects.Part part) {
        for (String id : p.assetsOn(part.id)) { Projects.Asset a = book.asset(id); if (a != null && (a.role.equals("model") || a.role.equals("gcode"))) return true; }
        return false;
    }
    /** The parts most worth a look: ones with something open first, then the rest, at most `n`. */
    private static List<Projects.Part> attention(Projects.Project p, int n) {
        List<Projects.Part> open = new ArrayList<>(), rest = new ArrayList<>();
        for (Projects.Part part : p.parts) {
            boolean needs = part.kind.equals(Projects.PRINTED) ? p.accepted(part) < part.quantity : part.kind.equals(Projects.PURCHASED) ? part.have < part.quantity : part.kind.equals(Projects.TASK) && !part.done;
            (needs ? open : rest).add(part);
        }
        open.addAll(rest); return open.subList(0, Math.min(n, open.size()));
    }
    private void nextBanner(Projects.Project p, Step step) {
        LinearLayout banner = new LinearLayout(this); banner.setOrientation(LinearLayout.HORIZONTAL); banner.setGravity(android.view.Gravity.CENTER_VERTICAL);
        banner.setPadding(ui.dp(16), ui.dp(14), ui.dp(12), ui.dp(14));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable(); shape.setColor(ui.button); shape.setCornerRadius(ui.dp(18)); banner.setBackground(shape);
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        overline(words, "NEXT").setPadding(0, 0, 0, 0);
        ui.label(words, step.title, 16, ui.ink, true).setPadding(0, ui.dp(2), 0, 0);
        ui.label(words, step.detail, 13, ui.muted, false).setPadding(0, 0, 0, 0);
        banner.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = new TextView(this); chevron.setText("›"); chevron.setTextSize(26); chevron.setTextColor(ui.teal); banner.addView(chevron);
        banner.setClickable(true); banner.setFocusable(true); banner.setMinimumHeight(ui.dp(48));
        banner.setOnClickListener(v -> follow(p, step)); banner.setContentDescription("Next: " + step.title + ". " + step.detail);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = ui.dp(12); content.addView(banner, lp);
    }
    /** Does what a step asks: opens the inspection, the part, the Slice screen with its model, or asks about assembly. */
    private void follow(Projects.Project p, Step step) {
        Projects.Part part = p.part(step.partId);
        switch (step.action) {
            case "inspect": { Projects.Attempt waiting = null; for (Projects.Attempt a : p.attempts) if (part != null && a.waiting(part.id) > 0) { waiting = a; break; } if (waiting != null) inspect(p, waiting); break; }
            case "prepare": if (part != null) prepare(p, java.util.Collections.singletonList(part)); break;
            case "assemble": new AlertDialog.Builder(this).setTitle("Assembled and checked?").setMessage("Mark " + p.title + " as put together and checked. This is your call; the app only knows the parts are available.")
                .setNegativeButton("Not yet", null).setPositiveButton("Yes, it is", (d, w) -> { p.assembled = true; p.updated = now(); save(); render(); }).show(); break;
            default: if (part != null) { partId = part.id; screen = PART; render(); }
        }
    }
    private void sectionHead(LinearLayout parent, String title, String action, Runnable run) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this); t.setText(title); t.setTextSize(16); t.setTextColor(ui.ink); t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); A11y.heading(t);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) { Button b = new Button(this); b.setText(action); b.setAllCaps(false); b.setTextColor(ui.teal); b.setBackgroundColor(android.graphics.Color.TRANSPARENT); b.setMinHeight(ui.dp(48)); b.setOnClickListener(v -> run.run()); row.addView(b); }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = ui.dp(16); parent.addView(row, lp);
    }
    /** A list row: a title and a line under it, an optional count on the right ("2 / 4" over "ACCEPTED"), tappable. */
    private LinearLayout listRow(LinearLayout parent, String title, String line, String count, String countLabel, Runnable open) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10)); row.setMinimumHeight(ui.dp(56));
        LinearLayout words = new LinearLayout(this); words.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this); t.setText(title); t.setTextSize(15); t.setTextColor(ui.ink); t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); words.addView(t);
        if (line != null && !line.isEmpty()) { TextView l = new TextView(this); l.setText(line); l.setTextSize(13); l.setTextColor(ui.muted); words.addView(l); }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (count != null) {
            LinearLayout right = new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL); right.setGravity(android.view.Gravity.END);
            TextView c = new TextView(this); c.setText(count); c.setTextSize(15); c.setTextColor(ui.ink); c.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); c.setGravity(android.view.Gravity.END); right.addView(c);
            TextView k = new TextView(this); k.setText(countLabel); k.setTextSize(10); k.setLetterSpacing(0.1f); k.setTextColor(ui.muted); k.setGravity(android.view.Gravity.END); right.addView(k);
            row.addView(right);
        }
        if (open != null) {
            row.setClickable(true); row.setFocusable(true); row.setOnClickListener(v -> open.run());
            android.util.TypedValue ripple = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true); row.setBackgroundResource(ripple.resourceId);
        }
        row.setContentDescription(title + (line == null || line.isEmpty() ? "" : ". " + line) + (count == null ? "" : ". " + count.replace(" / ", " of ") + " " + countLabel.toLowerCase(Locale.getDefault())));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View rule = new View(this); rule.setBackgroundColor(ui.button); parent.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1))));
        return row;
    }
    /** A part as a row: name, its state, and good pieces against what is needed (or at hand, for purchased parts). */
    private LinearLayout partRow(LinearLayout parent, Projects.Project p, Projects.Part part, CheckBox pick) {
        String count = null, label = null;
        if (part.kind.equals(Projects.PRINTED)) { count = Math.min(p.accepted(part), Projects.MAX_QUANTITY) + " / " + part.quantity; label = "ACCEPTED"; }
        else if (part.kind.equals(Projects.PURCHASED)) { count = Math.min(part.have, part.quantity) + " / " + part.quantity; label = part.have >= part.quantity ? "ACQUIRED" : "AT HAND"; }
        else if (part.kind.equals(Projects.TASK)) { count = part.done ? "✓" : "–"; label = part.done ? "DONE" : "TO DO"; }
        String id = part.id;
        LinearLayout row = listRow(parent, part.name, partLine(book, p, part), count, label, () -> { partId = id; screen = PART; render(); });
        if (pick != null) row.addView(pick, 0);
        return row;
    }
    /** What a part's row says under its name: revision and design, or what is open ("No model yet", "1 awaiting inspection"). */
    static String partLine(Projects.Book book, Projects.Project p, Projects.Part part) {
        List<String> bits = new ArrayList<>();
        switch (part.kind) {
            case Projects.PRINTED: {
                int waiting = p.waiting(part);
                if (!part.revision.isEmpty()) bits.add("Revision " + part.revision);
                if (waiting > 0) bits.add(waiting + " awaiting inspection");
                else if (part.design.equals("revise")) bits.add("Revise fit");
                else if (!hasModel(book, p, part)) bits.add(part.design.equals("modeling") ? "Being designed" : "No model yet");
                else if (p.accepted(part) >= part.quantity) bits.add("Done");
                else bits.add("Ready");
                break;
            }
            case Projects.PURCHASED: bits.add(part.have >= part.quantity ? "Acquired" : (part.quantity - part.have) + " to get"); break;
            case Projects.TASK: bits.add(part.done ? "Done" : "Task"); break;
            default: bits.add("Reference");
        }
        if (!part.notes.isEmpty() && part.kind.equals(Projects.PURCHASED)) bits.add(firstLine(part.notes).toLowerCase(Locale.getDefault()));
        return String.join(" · ", bits);
    }
    private String holdingShort(Projects.Asset a) {
        if (a.storage.equals(Projects.WEB)) return "Web link";
        String size = a.size >= 0 ? " · " + size(a.size) : "";
        return (a.storage.equals(Projects.MANAGED) ? "Kept in the app" : "Linked file") + size;
    }

    // ------------------------------------------------------------------ parts bench: groups, pick parts, prepare them together

    private final Set<String> collapsed = new HashSet<>(), picked = new HashSet<>();
    private void parts(Projects.Project p) {
        if (p.parts.isEmpty()) {
            ui.label(content, "No parts yet. Add each part the project needs, with how many, even before it has a model. Purchased parts and tasks can go here too.", 14, ui.muted, false);
            ui.button(content, "Add planned part", () -> editPart(p, null), true); return;
        }
        int[] done = p.printedProgress();
        ui.label(content, done[0] + " / " + done[1] + " printed pieces accepted · tick printed parts with a model to prepare them together", 13, ui.muted, false);
        // Groups in the order they first appear; parts without one come last under "Other parts" (or with no heading when no part has a group).
        java.util.LinkedHashMap<String, List<Projects.Part>> groups = new java.util.LinkedHashMap<>();
        for (Projects.Part part : p.parts) groups.computeIfAbsent(part.group, g -> new ArrayList<>()).add(part);
        List<String> order = new ArrayList<>(groups.keySet()); if (order.remove("")) order.add("");
        boolean headings = order.size() > 1 || !order.get(0).isEmpty();
        picked.removeIf(id -> p.part(id) == null || !preparable(p, p.part(id)));
        for (String group : order) {
            List<Projects.Part> members = groups.get(group);
            if (headings) {
                String name = group.isEmpty() ? "Other parts" : group; boolean shut = collapsed.contains(group);
                Button head = ui.button(content, name + " · " + members.size() + (shut ? "   ▸" : "   ▾"), () -> { if (!collapsed.remove(group)) collapsed.add(group); render(); }, false);
                head.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL); head.setTextColor(ui.ink);
                head.setContentDescription(name + ", " + members.size() + (members.size() == 1 ? " part" : " parts") + (shut ? ", collapsed" : ", expanded"));
                if (shut) continue;
            }
            for (Projects.Part part : members) {
                CheckBox pick = null;
                if (preparable(p, part)) {
                    pick = new CheckBox(this); pick.setChecked(picked.contains(part.id)); pick.setContentDescription("Choose " + part.name + " to prepare");
                    String id = part.id; pick.setOnCheckedChangeListener((v, on) -> { if (on) picked.add(id); else picked.remove(id); render(); });
                } else if (part.kind.equals(Projects.PRINTED)) { pick = new CheckBox(this); pick.setEnabled(false); pick.setContentDescription(part.name + ": no model to prepare yet"); }
                partRow(content, p, part, pick);
            }
        }
        ui.button(content, "Add planned part", () -> editPart(p, null), false);
        List<Projects.Part> chosen = new ArrayList<>(); for (Projects.Part part : p.parts) if (picked.contains(part.id)) chosen.add(part);
        Button prepare = ui.button(content, "Prepare selected (" + chosen.size() + ")", () -> prepare(p, chosen), true);
        prepare.setEnabled(!chosen.isEmpty());
    }
    /** A printed part whose model is kept in the app, so the Slice screen can open it. */
    private boolean preparable(Projects.Project p, Projects.Part part) {
        if (!part.kind.equals(Projects.PRINTED)) return false;
        for (String id : p.assetsOn(part.id)) { Projects.Asset a = book.asset(id); if (a != null && a.sliceable()) return true; }
        return false;
    }
    /** Opens the Slice screen with the chosen parts' kept models (one file each: the newest added). */
    private void prepare(Projects.Project p, List<Projects.Part> parts) {
        List<String> paths = new ArrayList<>(); List<String> without = new ArrayList<>();
        for (Projects.Part part : parts) {
            File newest = null;
            for (String id : p.assetsOn(part.id)) { Projects.Asset a = book.asset(id); File f = a != null && a.sliceable() ? Projects.managedFile(dir, a) : null; if (f != null && f.isFile()) newest = f; }
            if (newest == null) without.add(part.name); else paths.add(newest.getAbsolutePath());
        }
        if (paths.isEmpty()) { toast(without.isEmpty() ? "Choose parts first." : "No kept model for " + String.join(", ", without) + ". Add one as a kept copy (linked files cannot be prepared together)."); return; }
        if (!without.isEmpty()) toast("Left out (no kept model): " + String.join(", ", without));
        startActivity(new Intent(this, SliceActivity.class).putExtra(SliceActivity.EXTRA_MODEL_PATHS, paths.toArray(new String[0])));
    }

    /** Adds (part null) or edits a planned part. */
    private void editPart(Projects.Project p, Projects.Part part) {
        LinearLayout body = form();
        EditText name = field(body, "Name", part == null ? "" : part.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        ui.label(body, "Kind", 13, ui.ink, false);
        List<String> kinds = new ArrayList<>(); int chosen = 0;
        for (int i = 0; i < Projects.KINDS.length; i++) { kinds.add(Projects.KINDS[i][1]); if (part != null && Projects.KINDS[i][0].equals(part.kind)) chosen = i; }
        Spinner kind = ui.spinner(body, kinds, chosen);
        EditText quantity = field(body, "How many the project needs", String.valueOf(part == null ? 1 : part.quantity), InputType.TYPE_CLASS_NUMBER);
        Set<String> known = new java.util.LinkedHashSet<>(); for (Projects.Part q : p.parts) if (!q.group.isEmpty()) known.add(q.group);
        EditText group = field(body, "Group (optional)" + (known.isEmpty() ? ", such as Housing or Mounting" : ": " + String.join(", ", known)), part == null ? "" : part.group, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText revision = field(body, "Design revision (optional), such as B", part == null ? "" : part.revision, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        ui.label(body, "Design (printed parts)", 13, ui.ink, false);
        List<String> designs = new ArrayList<>(); int design = 0;
        for (int i = 0; i < Projects.DESIGN.length; i++) { designs.add(Projects.DESIGN[i][1]); if (part != null && Projects.DESIGN[i][0].equals(part.design)) design = i; }
        Spinner designSpinner = ui.spinner(body, designs, design);
        EditText have = field(body, "How many you have (purchased parts)", String.valueOf(part == null ? 0 : part.have), InputType.TYPE_CLASS_NUMBER);
        CheckBox done = new CheckBox(this); done.setText("Done (tasks)"); done.setTextColor(ui.ink); done.setMinHeight(ui.dp(48)); done.setChecked(part != null && part.done); body.addView(done);
        EditText notes = field(body, "Notes: sizes to check, what blocks it…", part == null ? "" : part.notes, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        ui.label(body, "Planning only: the number needed does not count anything as printed. Printed pieces count once you accept them after a print.", 12, ui.muted, false);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(part == null ? "Planned part" : "Edit part").setView(scroll(body)).setNegativeButton("Cancel", null).setPositiveButton(part == null ? "Add" : "Save", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String kindId = Projects.KINDS[Math.max(0, kind.getSelectedItemPosition())][0];
            int count = number(quantity), at = number(have);
            boolean counted = kindId.equals(Projects.PRINTED) || kindId.equals(Projects.PURCHASED);
            if (counted && (count < 1 || count > Projects.MAX_QUANTITY)) { quantity.setError("1 to " + Projects.MAX_QUANTITY); return; }
            if (kindId.equals(Projects.PURCHASED) && (at < 0 || at > Projects.MAX_QUANTITY)) { have.setError("0 to " + Projects.MAX_QUANTITY); return; }
            Projects.Part target = part != null ? part : book.addPart(p, name.getText().toString(), kindId, counted ? count : 1, now());
            String typed = name.getText().toString().trim(); if (!typed.isEmpty()) target.name = Automation.cap(typed, 120);
            target.kind = kindId; target.quantity = counted ? count : 1; target.have = kindId.equals(Projects.PURCHASED) ? at : 0; target.done = kindId.equals(Projects.TASK) && done.isChecked();
            target.group = Automation.cap(group.getText().toString().trim(), 40); target.revision = Automation.cap(revision.getText().toString().trim(), 20);
            target.design = kindId.equals(Projects.PRINTED) ? Projects.DESIGN[Math.max(0, designSpinner.getSelectedItemPosition())][0] : "";
            target.notes = Automation.cap(notes.getText().toString().trim(), 4000); p.updated = now();
            save(); dialog.dismiss(); render();
        });
    }
    private static int number(EditText field) { try { return Integer.parseInt(field.getText().toString().trim()); } catch (NumberFormatException e) { return -1; } }

    private void partScreen(Projects.Project p, Projects.Part part) {
        ui.button(content, "‹ " + p.title, () -> { screen = PARTS; render(); }, false);
        overline(content, (part.group.isEmpty() ? "" : part.group.toUpperCase(Locale.getDefault()) + " / ") + Projects.label(Projects.KINDS, part.kind).toUpperCase(Locale.getDefault()));
        A11y.heading(ui.label(content, part.name + (part.counted() ? " × " + part.quantity : ""), 22, ui.ink, true));
        ui.label(content, partLine(book, p, part) + (part.design.isEmpty() || part.design.equals("revise") ? "" : " · " + Projects.label(Projects.DESIGN, part.design)), 13, ui.muted, false);
        if (!part.notes.isEmpty()) ui.label(content, part.notes, 14, ui.ink, false);
        if (part.kind.equals(Projects.PRINTED)) {
            LinearLayout card = ui.card(content, "Pieces");
            int good = p.accepted(part), waiting = p.waiting(part), bad = p.rejected(part);
            ui.label(card, good + " of " + part.quantity + " accepted" + (waiting > 0 ? " · " + waiting + " awaiting inspection" : "") + (bad > 0 ? " · " + bad + " rejected" : "")
                + (part.acceptedElsewhere > 0 ? " (" + part.acceptedElsewhere + " made elsewhere)" : ""), 14, ui.ink, false);
            ui.label(card, "Pieces count once you accept them after a print (Prints tab). A finished print alone does not count them.", 12, ui.muted, false);
            LinearLayout row = ui.row(card);
            if (waiting > 0) ui.rowButton(row, "Inspect…", () -> follow(p, new Step("", "", "inspect", part.id)), true);
            if (preparable(p, part)) ui.rowButton(row, "Prepare", () -> prepare(p, java.util.Collections.singletonList(part)), waiting == 0);
            ui.button(card, "Good pieces made elsewhere…", () -> elsewhere(p, part), false);
        } else if (part.kind.equals(Projects.PURCHASED)) {
            LinearLayout card = ui.card(content, "At hand");
            ui.label(card, part.have + " of " + part.quantity, 18, ui.ink, true);
            LinearLayout row = ui.row(card);
            ui.rowButton(row, "− 1", () -> { part.have = Math.max(0, part.have - 1); p.updated = now(); save(); render(); }, false).setContentDescription("One fewer at hand");
            ui.rowButton(row, "+ 1", () -> { part.have = Math.min(Projects.MAX_QUANTITY, part.have + 1); p.updated = now(); save(); render(); }, false).setContentDescription("One more at hand");
        } else if (part.kind.equals(Projects.TASK)) {
            CheckBox done = check(content, "Done", part.done); done.setOnCheckedChangeListener((v, on) -> { part.done = on; p.updated = now(); save(); render(); });
        }
        LinearLayout row = ui.row(content); int at = p.parts.indexOf(part);
        ui.rowButton(row, "Edit…", () -> editPart(p, part), false);
        Button up = ui.rowButton(row, "Move up", () -> { book.movePart(p, part.id, -1); save(); render(); }, false); up.setEnabled(at > 0);
        Button down = ui.rowButton(row, "Move down", () -> { book.movePart(p, part.id, 1); save(); render(); }, false); down.setEnabled(at < p.parts.size() - 1);
        ui.button(content, "Remove part…", () -> new AlertDialog.Builder(this).setTitle("Remove “" + part.name + "”?")
            .setMessage("The part comes off the plan. Its files stay with the project, and are not deleted; prints keep their other parts.").setNegativeButton("Keep", null)
            .setPositiveButton("Remove", (d, w) -> { book.removePart(p, part.id); p.updated = now(); save(); screen = PARTS; render(); }).show(), false);
        files(p, part);
    }
    private void elsewhere(Projects.Project p, Projects.Part part) {
        LinearLayout body = form();
        EditText count = field(body, "Good pieces made outside the prints listed here", String.valueOf(part.acceptedElsewhere), InputType.TYPE_CLASS_NUMBER);
        ui.label(body, "For pieces printed before this project was set up, or on another printer. Sets the number (it does not add to it).", 12, ui.muted, false);
        new AlertDialog.Builder(this).setTitle(part.name).setView(scroll(body)).setNegativeButton("Cancel", null).setPositiveButton("Set", (d, w) -> {
            int n = number(count); if (n < 0) return; part.acceptedElsewhere = Math.min(Projects.MAX_QUANTITY, n); p.updated = now(); save(); render();
        }).show();
    }

    // ------------------------------------------------------------------ prints: attempts, what they made, and inspection

    private final Map<String, String> recordingLines = new HashMap<>();
    private void prints(Projects.Project p) {
        LinearLayout row = ui.row(content);
        ui.rowButton(row, "Add a recorded print…", () -> pickRecording(p), true);
        ui.rowButton(row, "Add by hand…", () -> editAttempt(p, null, "", ""), false);
        if (p.attempts.isEmpty()) {
            ui.label(content, "No prints yet. Add a print the app recorded (or one made elsewhere), say which parts it made and how many, then inspect them: only accepted pieces count toward the project.", 14, ui.muted, false);
            return;
        }
        List<String> missing = new ArrayList<>();
        for (int i = p.attempts.size() - 1; i >= 0; i--) {
            Projects.Attempt a = p.attempts.get(i);
            LinearLayout card = ui.card(content, "#" + String.format(Locale.ROOT, "%03d", i + 1) + " · " + (a.title.isEmpty() ? "Print" : a.title));
            List<String> made = new ArrayList<>(); int waiting = 0, good = 0, total = 0;
            for (Map.Entry<String, int[]> e : a.pieces.entrySet()) {
                Projects.Part part = p.part(e.getKey()); if (part == null) continue;
                made.add(part.name + ": " + e.getValue()[1] + " accepted, " + e.getValue()[2] + " rejected of " + e.getValue()[0] + (part.revision.isEmpty() ? "" : " (rev " + part.revision + ")"));
                waiting += a.waiting(part.id); good += e.getValue()[1]; total += e.getValue()[0];
            }
            String verdict = total == 0 ? "No parts counted yet" : waiting > 0 ? waiting + " awaiting inspection" : good == total ? "All accepted" : good == 0 ? "None accepted" : "Partly accepted";
            ui.label(card, verdict + (a.when > 0 ? " · " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(a.when)) : ""), 13, waiting > 0 ? ui.teal : ui.muted, true);
            for (String line : made) ui.label(card, line, 13, ui.ink, false);
            if (!a.recording.isEmpty()) {
                String line = recordingLines.get(a.recording);
                if (line == null) missing.add(a.recording); else if (!line.isEmpty()) ui.label(card, line, 12, ui.muted, false);
            }
            if (!a.note.isEmpty()) ui.label(card, a.note, 13, ui.ink, false);
            LinearLayout actions = ui.row(card);
            ui.rowButton(actions, "Inspect…", () -> inspect(p, a), waiting > 0).setEnabled(total > 0);
            ui.rowButton(actions, "Edit…", () -> editAttempt(p, a, a.recording, a.title), false);
            if (!a.recording.isEmpty()) ui.rowButton(actions, "Recording", () -> {
                File meta = new File(RecordingsActivity.directory(this), a.recording);
                if (meta.isFile()) startActivity(new Intent(this, RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, meta.getAbsolutePath())); else toast("That recording is no longer on this phone.");
            }, false);
        }
        if (!missing.isEmpty()) worker.execute(() -> {
            Map<String, String> found = new HashMap<>();
            for (String rel : missing) {
                File meta = new File(RecordingsActivity.directory(this), rel);
                try { PrintRecorder.Recording r = PrintRecorder.Recording.read(meta); String j = r.journal == null ? "" : r.journal.summary(); found.put(rel, "Recorded print" + (r.outcome == null || r.outcome.isEmpty() ? "" : " · " + r.outcome) + (j.isEmpty() ? "" : " · " + j)); }
                catch (Exception gone) { found.put(rel, "The recording is no longer on this phone."); }
            }
            main.post(() -> { if (!isDestroyed()) { recordingLines.putAll(found); render(); } });
        });
    }
    /** The prints the app recorded, newest first, to link one to the project. */
    private void pickRecording(Projects.Project p) {
        worker.execute(() -> {
            List<File> metas = new ArrayList<>(); List<String> names = new ArrayList<>();
            File root = RecordingsActivity.directory(this); File[] all = root.listFiles();
            List<PrintRecorder.Recording> list = new ArrayList<>();
            if (all != null) for (File f : all) { File meta = f.isDirectory() ? new File(f, "meta.json") : f.getName().endsWith(".json") ? f : null; if (meta != null && meta.isFile()) try { list.add(PrintRecorder.Recording.read(meta)); } catch (Exception skip) { } }
            list.sort((x, y) -> Long.compare(y.start, x.start));
            for (PrintRecorder.Recording r : list) { metas.add(r.meta); names.add((r.file == null || r.file.isEmpty() ? "Print" : r.file) + " · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(new java.util.Date(r.start))); }
            main.post(() -> {
                if (isDestroyed()) return;
                if (metas.isEmpty()) { toast("No recorded prints on this phone yet. Add the print by hand instead."); return; }
                new AlertDialog.Builder(this).setTitle("Which print?").setItems(names.subList(0, Math.min(50, names.size())).toArray(new String[0]), (d, w) -> {
                    String rel = root.toURI().relativize(metas.get(w).toURI()).getPath();
                    editAttempt(p, null, rel, names.get(w).replaceFirst(" · .*$", ""));
                }).setNegativeButton("Cancel", null).show();
            });
        });
    }
    /** Adds or edits a print: its name, a note, and how many pieces of each printed part it made. */
    private void editAttempt(Projects.Project p, Projects.Attempt existing, String recording, String title) {
        List<Projects.Part> printed = new ArrayList<>(); for (Projects.Part part : p.parts) if (part.kind.equals(Projects.PRINTED)) printed.add(part);
        if (printed.isEmpty()) { toast("Plan a printed part first."); return; }
        LinearLayout body = form();
        EditText name = field(body, "Name", existing == null ? title : existing.title, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        ui.label(body, "Pieces this print made, per part (0 for none):", 13, ui.ink, true);
        Map<String, EditText> made = new HashMap<>();
        for (Projects.Part part : printed) {
            int[] c = existing == null ? null : existing.pieces.get(part.id);
            made.put(part.id, field(body, part.name + (part.revision.isEmpty() ? "" : " (revision " + part.revision + ")"), String.valueOf(c == null ? 0 : c[0]), InputType.TYPE_CLASS_NUMBER));
        }
        EditText note = field(body, "Note (settings, material, what happened)", existing == null ? "" : existing.note, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        ui.label(body, "New pieces wait for inspection; nothing counts until you accept it.", 12, ui.muted, false);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(existing == null ? "Add a print" : "Edit print").setView(scroll(body)).setNegativeButton("Cancel", null)
            .setPositiveButton(existing == null ? "Add" : "Save", (d, w) -> {
                Projects.Attempt a = existing != null ? existing : new Projects.Attempt();
                if (existing == null) { a.recording = recording; a.when = now(); p.attempts.add(a); }
                a.title = Automation.cap(name.getText().toString().trim(), 200); a.note = Automation.cap(note.getText().toString().trim(), 2000);
                for (Projects.Part part : printed) {
                    int[] old = a.pieces.get(part.id); int n = Math.max(0, number(made.get(part.id)));
                    a.set(part.id, n, old == null ? 0 : old[1], old == null ? 0 : old[2]);
                }
                p.updated = now(); save(); screen = PRINTS; render();
            });
        if (existing != null) b.setNeutralButton("Remove…", (d, w) -> new AlertDialog.Builder(this).setTitle("Remove this print?").setMessage("Its pieces stop counting toward the project. The recording itself is kept.")
            .setNegativeButton("Keep", null).setPositiveButton("Remove", (d2, w2) -> { p.attempts.remove(existing); p.updated = now(); save(); render(); }).show());
        b.show();
    }
    /** Inspection: for each part the print made, how many are good and how many are not. Sets the numbers; never adds twice. */
    private void inspect(Projects.Project p, Projects.Attempt a) {
        LinearLayout body = form();
        ui.label(body, "Judge each piece: does it fit, is it clean? Accepted pieces count toward the project; the rest wait until you decide.", 13, ui.muted, false);
        Map<String, EditText[]> fields = new HashMap<>();
        for (Map.Entry<String, int[]> e : a.pieces.entrySet()) {
            Projects.Part part = p.part(e.getKey()); if (part == null) continue;
            ui.label(body, part.name + ": " + e.getValue()[0] + " made", 14, ui.ink, true);
            LinearLayout row = ui.row(body);
            EditText good = inlineNumber(row, "Accepted", e.getValue()[1]), bad = inlineNumber(row, "Rejected", e.getValue()[2]);
            fields.put(part.id, new EditText[] {good, bad});
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Inspect " + (a.title.isEmpty() ? "the print" : a.title)).setView(scroll(body)).setNegativeButton("Cancel", null).setPositiveButton("Save", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            for (Map.Entry<String, EditText[]> e : fields.entrySet()) {
                int[] c = a.pieces.get(e.getKey()); int good = number(e.getValue()[0]), bad = number(e.getValue()[1]);
                if (good < 0 || bad < 0 || good + bad > c[0]) { e.getValue()[0].setError("Accepted and rejected together: at most " + c[0]); return; }
            }
            for (Map.Entry<String, EditText[]> e : fields.entrySet()) { int[] c = a.pieces.get(e.getKey()); a.set(e.getKey(), c[0], number(e.getValue()[0]), number(e.getValue()[1])); }
            p.updated = now(); save(); dialog.dismiss(); render();
        });
    }
    private EditText inlineNumber(LinearLayout row, String label, int value) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this); l.setText(label); l.setTextColor(ui.muted); l.setTextSize(12); box.addView(l);
        EditText input = new EditText(this); input.setText(String.valueOf(value)); input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setSingleLine(true); input.setMinHeight(ui.dp(48));
        input.setTextColor(ui.ink); input.setContentDescription(label); box.addView(input);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); if (row.getChildCount() > 0) lp.leftMargin = ui.dp(12); row.addView(box, lp);
        return input;
    }
    // ------------------------------------------------------------------ files

    /** The files of a project, or of one part, with what can be done with each. */
    private void files(Projects.Project p, Projects.Part part) {
        String where = part == null ? "" : part.id;
        LinearLayout actions = ui.card(content, part == null ? "Files and links" : "Files for this part");
        ui.label(actions, "Keep a copy in the app (stays even if the original goes), or link a file where it is (no second copy; it must stay there). One file can be in several projects.", 13, ui.muted, false);
        ui.button(actions, "Add files…", () -> addFiles(p, where), true);
        List<String> ids = part == null ? new ArrayList<>(p.allAssets()) : p.assetsOn(where);
        if (part == null && !ids.isEmpty()) {
            List<String> roles = new ArrayList<>(Collections.singletonList("All kinds")); int chosen = 0;
            for (int i = 0; i < Projects.ROLES.length; i++) { roles.add(Projects.ROLES[i][1]); if (Projects.ROLES[i][0].equals(roleFilter)) chosen = i + 1; }
            Spinner filter = ui.spinner(content, roles, chosen);
            filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> a, View v, int position, long id) { String next = position == 0 ? "" : Projects.ROLES[position - 1][0]; if (!next.equals(roleFilter)) { roleFilter = next; render(); } }
                public void onNothingSelected(AdapterView<?> a) { }
            });
        }
        int shown = 0;
        for (String id : ids) {
            Projects.Asset asset = book.asset(id); if (asset == null) continue;
            if (part == null && !roleFilter.isEmpty() && !asset.role.equals(roleFilter)) continue;
            shown++;
            assetCard(asset, p, where, part == null);
        }
        if (shown == 0) ui.label(content, ids.isEmpty() ? (part == null ? "No files yet." : "No files on this part yet. Add its model, a sketch or a supplier's page.") : "No files of that kind.", 14, ui.muted, false);
        checkLinks(ids);
    }

    private void assetCard(Projects.Asset asset, Projects.Project p, String partId, boolean showPart) {
        LinearLayout card = ui.card(content, asset.name);
        List<String> line = new ArrayList<>(); line.add(Projects.label(Projects.ROLES, asset.role)); line.add(holding(asset));
        if (asset.size >= 0 && !asset.storage.equals(Projects.WEB)) line.add(size(asset.size));
        ui.label(card, String.join(" · ", line), 13, stateBad(asset) ? ui.error : ui.muted, false);
        List<String> used = book.usedIn(asset.id);
        if (showPart || used.size() > 1) ui.label(card, "In: " + String.join("; ", used), 12, ui.muted, false);
        LinearLayout row = ui.row(card);
        if (asset.sliceable() || asset.storage.equals(Projects.EXTERNAL) && ModelSites.MODEL_TYPES.contains(ModelSites.extension(asset.name))) ui.rowButton(row, "Slice", () -> slice(asset), true);
        if (asset.gcode()) ui.rowButton(row, "Preview", () -> preview(asset), true);
        ui.rowButton(row, asset.storage.equals(Projects.WEB) ? "Open link" : "Open", () -> open(asset), false);
        ui.rowButton(row, "More…", () -> assetMenu(asset, p, partId), false).setContentDescription("More for " + asset.name);
    }
    private boolean stateBad(Projects.Asset a) { String s = availability.get(a.id); return s != null && !s.equals("available"); }
    private String holding(Projects.Asset a) {
        if (a.storage.equals(Projects.WEB)) return "web link (opens in the browser; nothing is downloaded)";
        if (a.storage.equals(Projects.MANAGED)) return "copy kept in the app";
        String state = availability.get(a.id);
        if (state == null) return "linked file (checking…)";
        switch (state) {
            case "available": return "linked file, available";
            case "changed": return "linked file, changed since it was linked";
            case "permission": return "linked file, permission needed: relink it";
            default: return "linked file, not found: relink it";
        }
    }
    static String size(long bytes) { return bytes < 1024 ? bytes + " B" : bytes < 1024 * 1024 ? (bytes / 1024) + " KB" : String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024)); }

    private void assetMenu(Projects.Asset asset, Projects.Project p, String partId) {
        List<String> names = new ArrayList<>(); List<Runnable> actions = new ArrayList<>();
        names.add("Put it on a part…"); actions.add(() -> attachToPart(asset, p));
        if (!partId.isEmpty()) { names.add("Take it off this part"); actions.add(() -> {
            // Off the part, still in the project (under its files) unless another part has it.
            book.detach(p, asset.id, partId); if (!attachedAnywhere(p, asset.id)) book.attach(p, asset.id, ""); p.updated = now(); save(); render(); }); }
        names.add("Also add it to another project…"); actions.add(() -> chooseProject(asset));
        names.add("Remove it from this project"); actions.add(() -> { p.attachments.removeIf(a -> a.assetId.equals(asset.id)); p.updated = now(); save(); render();
            toast(book.usedIn(asset.id).isEmpty() ? "Removed. The file is kept under Files in no project." : "Removed from this project. Other projects keep it."); });
        if (asset.storage.equals(Projects.EXTERNAL)) { names.add("Relink to the file…"); actions.add(() -> { relinkAsset = asset.id; pick(PICK_RELINK, false); }); }
        if (asset.storage.equals(Projects.MANAGED)) { names.add("Save a copy elsewhere…"); actions.add(() -> exportCopy(asset)); }
        names.add("What it is…"); actions.add(() -> chooseRole(asset));
        new AlertDialog.Builder(this).setTitle(asset.name).setItems(names.toArray(new String[0]), (d, w) -> actions.get(w).run()).setNegativeButton("Close", null).show();
    }
    private boolean attachedAnywhere(Projects.Project p, String assetId) { for (Projects.Attachment a : p.attachments) if (a.assetId.equals(assetId)) return true; return false; }

    private void attachToPart(Projects.Asset asset, Projects.Project p) {
        if (p.parts.isEmpty()) { toast("Add a planned part first."); return; }
        String[] names = new String[p.parts.size()]; for (int i = 0; i < names.length; i++) names[i] = p.parts.get(i).name;
        new AlertDialog.Builder(this).setTitle("Put “" + asset.name + "” on").setItems(names, (d, w) -> {
            book.attach(p, asset.id, p.parts.get(w).id); book.detach(p, asset.id, ""); p.updated = now(); save(); render();
        }).setNegativeButton("Cancel", null).show();
    }
    private void chooseProject(Projects.Asset asset) {
        List<Projects.Project> others = new ArrayList<>(); for (Projects.Project q : book.projects) if (!q.archived) others.add(q);
        if (others.isEmpty()) { toast("There is no other project yet."); return; }
        String[] names = new String[others.size()]; for (int i = 0; i < names.length; i++) names[i] = others.get(i).title;
        new AlertDialog.Builder(this).setTitle("Add “" + asset.name + "” to").setItems(names, (d, w) -> {
            Projects.Project q = others.get(w); book.attach(q, asset.id, ""); q.updated = now(); save(); render();
            toast("Added to " + q.title + ". It is the same file: no second copy.");
        }).setNegativeButton("Cancel", null).show();
    }
    private void chooseRole(Projects.Asset asset) {
        String[] names = new String[Projects.ROLES.length]; int at = 0;
        for (int i = 0; i < names.length; i++) { names[i] = Projects.ROLES[i][1]; if (Projects.ROLES[i][0].equals(asset.role)) at = i; }
        new AlertDialog.Builder(this).setTitle("What is “" + asset.name + "”?").setSingleChoiceItems(names, at, (d, w) -> { asset.role = Projects.ROLES[w][0]; save(); d.dismiss(); render(); }).show();
    }

    /** The ways to add: a kept copy, a link, a web link, a file already in projects, a recent slice. */
    private void addFiles(Projects.Project p, String partId) {
        String[] names = {"Keep a copy in the app…", "Link a file where it is…", "Add a web link…", "A file already in a project…", "A recent slice…"};
        new AlertDialog.Builder(this).setTitle("Add to " + (partId.isEmpty() ? p.title : p.part(partId) == null ? p.title : p.part(partId).name)).setItems(names, (d, w) -> {
            pickProject = p.id; pickPart = partId;
            switch (w) {
                case 0: pick(PICK_COPY, true); break;
                case 1: pick(PICK_LINK, true); break;
                case 2: webLink(p, partId); break;
                case 3: existing(p, partId); break;
                default: recentSlice(p, partId);
            }
        }).setNegativeButton("Cancel", null).show();
    }
    private void pick(int request, boolean many) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, many);
        if (request != PICK_COPY) intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(intent, request); } catch (android.content.ActivityNotFoundException none) { toast("No file picker is available on this phone."); }
    }
    private void webLink(Projects.Project p, String partId) {
        LinearLayout body = form();
        EditText title = field(body, "Title", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText url = field(body, "Address (https://…)", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ui.label(body, "Opening it shows the page in the browser; nothing is downloaded or checked.", 12, ui.muted, false);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Web link").setView(scroll(body)).setNegativeButton("Cancel", null).setPositiveButton("Add", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = url.getText().toString().trim();
            if (!Projects.webOk(address)) { url.setError("Starts with https:// or http://"); return; }
            Projects.Asset a = new Projects.Asset(); a.storage = Projects.WEB; a.role = "link"; a.location = Automation.cap(address, 2000); a.added = now();
            String typed = title.getText().toString().trim(); a.name = typed.isEmpty() ? Automation.shortUrl(address) : Automation.cap(typed, 200);
            book.assets.add(a); book.attach(p, a.id, partId); p.updated = now(); save(); dialog.dismiss(); render();
        });
    }
    private void existing(Projects.Project p, String partId) {
        List<Projects.Asset> choices = new ArrayList<>(); for (Projects.Asset a : book.assets) if (!book.has(p, a.id, partId)) choices.add(a);
        if (choices.isEmpty()) { toast("Every file is already here."); return; }
        String[] names = new String[choices.size()];
        for (int i = 0; i < names.length; i++) { List<String> used = book.usedIn(choices.get(i).id); names[i] = choices.get(i).name + (used.isEmpty() ? " (in no project)" : " (in " + String.join("; ", used) + ")"); }
        new AlertDialog.Builder(this).setTitle("Add a file already in a project").setItems(names, (d, w) -> {
            book.attach(p, choices.get(w).id, partId); p.updated = now(); save(); render();
        }).setNegativeButton("Cancel", null).show();
    }
    private void recentSlice(Projects.Project p, String partId) {
        List<File> recent = SliceStore.list(this);
        if (recent.isEmpty()) { toast("No recent slices."); return; }
        String[] names = new String[Math.min(20, recent.size())]; for (int i = 0; i < names.length; i++) names[i] = recent.get(i).getName();
        new AlertDialog.Builder(this).setTitle("Add a recent slice").setItems(names, (d, w) -> keepFile(recent.get(w), recent.get(w).getName(), p.id, partId)).setNegativeButton("Cancel", null).show();
    }
    /** A file of the app's own (a recent slice), kept as a project copy; the same bytes already kept are reused. */
    private void keepFile(File file, String name, String project, String part) {
        worker.execute(() -> {
            String problem = null; Projects.Asset fresh = null;
            try (InputStream in = new java.io.FileInputStream(file)) { fresh = Projects.copyIn(dir, in, name, null, now()); }
            catch (Exception failed) { problem = failed.getMessage(); }
            String said = problem; Projects.Asset copied = fresh;
            main.post(() -> {
                if (isDestroyed()) return;
                if (copied == null) { toast("Could not keep " + name + ": " + said); return; }
                Projects.Asset kept = adopt(copied);
                Projects.Project p = book.project(project); if (p != null) { book.attach(p, kept.id, part); p.updated = now(); }
                save(); render();
            });
        });
    }
    /** A recent slice handed over from the Files tab: asks which project it goes into. */
    private void offerHanded() {
        String path = handedPath, name = handedName == null ? new File(handedPath).getName() : handedName; handedPath = null;
        if (!SliceStore.ownOutput(this, path, name)) { toast("That file is not one of this app's slices."); return; }
        List<Projects.Project> open = new ArrayList<>(); for (Projects.Project q : book.projects) if (!q.archived) open.add(q);
        if (open.isEmpty()) { toast("Make a project first, then add the slice from its Files."); return; }
        String[] names = new String[open.size()]; for (int i = 0; i < names.length; i++) names[i] = open.get(i).title;
        new AlertDialog.Builder(this).setTitle("Add " + name + " to").setItems(names, (d, w) -> { projectId = open.get(w).id; screen = FILES; keepFile(new File(path), name, projectId, ""); })
            .setNegativeButton("Cancel", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || book == null) return;
        if (request == EXPORT) { exportTo(data.getData()); return; }
        if (request == SAVE_ARCHIVE) { writeArchive(data.getData()); return; }
        if (request == IMPORT) { if (data.getData() != null) importFrom(data.getData()); return; }
        if (request == SAVE_COVER_PNG || request == SAVE_COVER_PDF) { saveCover(data.getData(), request == SAVE_COVER_PDF); return; }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        if (request == PICK_RELINK) { relink(uris.get(0)); return; }
        boolean link = request == PICK_LINK; String project = pickProject, part = pickPart;
        if (link) for (Uri uri : uris) try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException notOffered) { }
        toast(link ? "Linking " + uris.size() + " file(s)…" : "Keeping a copy of " + uris.size() + " file(s)…");
        worker.execute(() -> {
            List<Projects.Asset> added = new ArrayList<>(); List<String> problems = new ArrayList<>();
            for (Uri uri : uris) {
                String name = nameOf(uri), mime = null; try { mime = getContentResolver().getType(uri); } catch (RuntimeException ignored) { }
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("could not be opened");
                    if (!link) { added.add(Projects.copyIn(dir, in, name, mime, now())); continue; }
                    Projects.Asset a = new Projects.Asset(); a.storage = Projects.EXTERNAL; a.location = uri.toString(); a.name = Projects.displayName(name);
                    a.role = Projects.roleFor(a.name, mime); a.mime = mime == null ? "" : mime; a.added = now(); a.size = sizeOf(uri); a.sha256 = Projects.hash(in);
                    added.add(a);
                } catch (Exception failed) { problems.add(name + " (" + failed.getMessage() + ")"); }
            }
            main.post(() -> {
                if (isDestroyed()) return;
                Projects.Project p = book.project(project);
                for (Projects.Asset fresh : added) { Projects.Asset a = adopt(fresh); if (p != null) book.attach(p, a.id, part); }
                if (p != null) p.updated = now();
                save(); render();
                if (!problems.isEmpty()) toast("Not added: " + String.join(", ", problems));
            });
        });
    }
    /** Points a linked file at a document picked again; different bytes are shown as a change, the earlier hash kept. */
    private void relink(Uri uri) {
        Projects.Asset asset = book.asset(relinkAsset); if (asset == null) return;
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException notOffered) { }
        worker.execute(() -> {
            String hash = null; try (InputStream in = getContentResolver().openInputStream(uri)) { if (in != null) hash = Projects.hash(in); } catch (Exception unreadable) { }
            String read = hash; String name = nameOf(uri); long size = sizeOf(uri);
            main.post(() -> {
                if (read == null) { toast("That file could not be read."); return; }
                asset.location = uri.toString(); asset.size = size;
                boolean changed = Projects.recordHash(asset, read);
                if (!name.equals(asset.name)) asset.name = Projects.displayName(name);
                availability.put(asset.id, "available"); save(); render();
                toast(changed ? "Relinked. The file's contents differ from before: it is treated as a new version." : "Relinked to the same file.");
            });
        });
    }
    /** Checks linked files in the background (can they be opened; have their bytes changed), then shows the result. */
    private void checkLinks(List<String> ids) {
        List<Projects.Asset> linked = new ArrayList<>();
        for (String id : ids) { Projects.Asset a = book.asset(id); if (a != null && a.storage.equals(Projects.EXTERNAL) && !availability.containsKey(a.id)) linked.add(a); }
        if (linked.isEmpty()) return;
        worker.execute(() -> {
            Map<String, String> found = new HashMap<>();
            for (Projects.Asset a : linked) {
                String state;
                try (InputStream in = getContentResolver().openInputStream(Uri.parse(a.location))) {
                    if (in == null) state = "missing";
                    else { String hash = a.size >= 0 && a.size < 64L * 1024 * 1024 ? Projects.hash(in) : ""; state = !hash.isEmpty() && !a.sha256.isEmpty() && !hash.equals(a.sha256) ? "changed" : "available"; }
                } catch (SecurityException denied) { state = "permission"; }
                catch (Exception gone) { state = "missing"; }
                found.put(a.id, state);
            }
            main.post(() -> { if (isDestroyed()) return; availability.putAll(found); render(); });
        });
    }

    private void unassigned() {
        ui.button(content, "‹ All projects", () -> { screen = HOME; render(); }, false);
        A11y.heading(ui.label(content, "Files in no project", 22, ui.ink, true));
        ui.label(content, "Files that were taken off every project. Add one to a project, or delete it.", 13, ui.muted, false);
        List<Projects.Asset> loose = book.unassigned();
        if (loose.isEmpty()) ui.label(content, "None.", 14, ui.muted, false);
        for (Projects.Asset a : loose) {
            LinearLayout card = ui.card(content, a.name);
            ui.label(card, Projects.label(Projects.ROLES, a.role) + " · " + holding(a), 13, ui.muted, false);
            LinearLayout row = ui.row(card);
            ui.rowButton(row, "Add to a project…", () -> chooseProject(a), true);
            ui.rowButton(row, "Delete…", () -> new AlertDialog.Builder(this).setTitle("Delete “" + a.name + "”?")
                .setMessage(a.storage.equals(Projects.MANAGED) ? "The copy kept in the app is deleted." : "The link is forgotten. The file itself stays where it is.")
                .setNegativeButton("Keep", null).setPositiveButton("Delete", (d, w) -> {
                    if (book.forget(a.id)) { save(); if (a.storage.equals(Projects.MANAGED)) worker.execute(() -> Projects.deleteBytes(dir, a)); }
                    if (a.storage.equals(Projects.EXTERNAL)) try { getContentResolver().releasePersistableUriPermission(Uri.parse(a.location), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                    render();
                }).show(), false);
        }
    }

    // ------------------------------------------------------------------ opening

    private void slice(Projects.Asset asset) {
        if (asset.storage.equals(Projects.MANAGED)) {
            File f = Projects.managedFile(dir, asset); if (f == null || !f.isFile()) { toast("The kept copy is missing."); return; }
            startActivity(new Intent(this, SliceActivity.class).putExtra(SliceActivity.EXTRA_MODEL_PATHS, new String[] {f.getAbsolutePath()}));
        } else {
            Intent intent = new Intent(this, SliceActivity.class).setAction(Intent.ACTION_VIEW).setData(Uri.parse(asset.location)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        }
    }
    private void preview(Projects.Asset asset) {
        File f = Projects.managedFile(dir, asset); if (f == null || !f.isFile()) { toast("The kept copy is missing."); return; }
        startActivity(new Intent(this, GcodeViewerActivity.class).putExtra(GcodeViewerActivity.EXTRA_FILE, f.getAbsolutePath()).putExtra(GcodeViewerActivity.EXTRA_NAME, asset.name));
    }
    private void open(Projects.Asset asset) {
        try {
            if (asset.storage.equals(Projects.WEB)) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(asset.location))); return; }
            if (asset.storage.equals(Projects.EXTERNAL)) { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(asset.location), asset.mime.isEmpty() ? "*/*" : asset.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); return; }
            File f = Projects.managedFile(dir, asset); if (f == null || !f.isFile()) { toast("The kept copy is missing."); return; }
            if (asset.role.equals("image")) {
                android.graphics.Bitmap picture = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
                if (picture == null) { toast("The picture could not be read."); return; }
                ImageView view = new ImageView(this); view.setImageBitmap(picture); view.setAdjustViewBounds(true); view.setContentDescription(asset.name);
                new AlertDialog.Builder(this).setTitle(asset.name).setView(view).setPositiveButton("Close", null).show(); return;
            }
            if (asset.sliceable()) { slice(asset); return; }
            if (asset.gcode()) { preview(asset); return; }
            exportCopy(asset);
        } catch (android.content.ActivityNotFoundException none) { toast("No app on this phone opens that."); }
    }
    private void exportCopy(Projects.Asset asset) {
        exportAsset = asset.id;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(asset.mime.isEmpty() ? "application/octet-stream" : asset.mime).putExtra(Intent.EXTRA_TITLE, asset.name);
        try { startActivityForResult(intent, EXPORT); } catch (android.content.ActivityNotFoundException none) { toast("No file saver is available on this phone."); }
    }
    private void exportTo(Uri target) {
        Projects.Asset asset = book.asset(exportAsset); if (asset == null || target == null) return;
        File f = Projects.managedFile(dir, asset);
        worker.execute(() -> {
            String said;
            try (InputStream in = new java.io.FileInputStream(f); java.io.OutputStream out = getContentResolver().openOutputStream(target)) {
                if (out == null) throw new java.io.IOException("could not be written");
                byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n); said = "Saved a copy of " + asset.name + ".";
            } catch (Exception failed) { said = "Could not save the copy: " + failed.getMessage(); }
            String text = said; main.post(() -> toast(text));
        });
    }

    // ------------------------------------------------------------------ share, export and import

    private ProjectArchive.Options exportOptions = new ProjectArchive.Options();
    private boolean exportCover = true;
    private void exportDialog(Projects.Project p) {
        LinearLayout body = form();
        long kept = 0; int linked = 0, web = 0, keptCount = 0;
        for (String id : p.allAssets()) { Projects.Asset a = book.asset(id); if (a == null) continue;
            if (a.storage.equals(Projects.MANAGED)) { kept += Math.max(0, a.size); keptCount++; } else if (a.storage.equals(Projects.EXTERNAL)) linked++; else web++; }
        ui.label(body, "One file (" + ProjectArchive.EXTENSION + ") with the parts, notes and files, which Link Workshop on another phone imports as a new project.", 13, ui.muted, false);
        CheckBox files = check(body, "Include the files kept in the app (" + keptCount + ", " + size(kept) + ")", exportOptions.keptFiles); files.setEnabled(keptCount > 0);
        CheckBox links = check(body, "Include web links (" + web + ")", exportOptions.webLinks); links.setEnabled(web > 0);
        CheckBox notes = check(body, "Include notes and the description", exportOptions.notes);
        CheckBox cover = check(body, "Include the cover page", exportCover);
        if (linked > 0) ui.label(body, linked + (linked == 1 ? " linked file stays" : " linked files stay") + " out: they are on this phone only. The project lists them so they can be added again.", 12, ui.muted, false);
        ui.label(body, "Web links go in as they are: leave them out if one holds a private key.", 12, ui.muted, false);
        Runnable take = () -> { exportOptions.keptFiles = files.isChecked(); exportOptions.webLinks = links.isChecked(); exportOptions.notes = notes.isChecked(); exportCover = cover.isChecked(); };
        AlertDialog[] dialog = new AlertDialog[1];
        ui.button(body, "Share the project file…", () -> { take.run(); dialog[0].dismiss(); shareArchive(p); }, true);
        ui.button(body, "Save the project file…", () -> { take.run(); dialog[0].dismiss(); pickProject = p.id;
            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(ProjectArchive.MIME).putExtra(Intent.EXTRA_TITLE, fileName(p.title) + ProjectArchive.EXTENSION);
            try { startActivityForResult(save, SAVE_ARCHIVE); } catch (android.content.ActivityNotFoundException none) { toast("No file saver is available on this phone."); } }, false);
        ui.button(body, "Share as text (the outline)", () -> { take.run(); dialog[0].dismiss();
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, p.title)
                .putExtra(Intent.EXTRA_TEXT, ProjectArchive.outline(book, p, exportOptions.notes)), "Share " + p.title)); }, false);
        dialog[0] = new AlertDialog.Builder(this).setTitle("Share " + p.title).setView(scroll(body)).setNegativeButton("Close", null).show();
    }
    private CheckBox check(LinearLayout parent, String text, boolean on) {
        CheckBox box = new CheckBox(this); box.setText(text); box.setTextColor(ui.ink); box.setMinHeight(ui.dp(48)); box.setChecked(on); parent.addView(box); return box;
    }
    static String fileName(String title) { String t = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim(); return t.isEmpty() ? "project" : Automation.cap(t, 80); }
    private String appVersion() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; } }
    /** Writes the archive (with the cover page if wanted) to `out`, in the background, then runs `done` with the report or the error. */
    private void writeArchiveTo(Projects.Project p, java.util.concurrent.Callable<java.io.OutputStream> target, java.util.function.BiConsumer<ProjectArchive.Report, String> done) {
        String text; try { text = book.toJson(); } catch (Exception impossible) { return; }
        ProjectArchive.Options options = new ProjectArchive.Options(); options.keptFiles = exportOptions.keptFiles; options.webLinks = exportOptions.webLinks; options.notes = exportOptions.notes;
        boolean withCover = exportCover; String id = p.id, app = appVersion();
        toast("Preparing " + p.title + "…");
        worker.execute(() -> {
            ProjectArchive.Report report = null; String problem = null;
            try {
                Projects.Book copy = Projects.Book.fromJson(text); Projects.Project project = copy.project(id);
                if (withCover) options.coverPng = CoverPage.png(CoverPage.content(copy, project, CoverPage.Options.fromJson(project.cover), now()), CoverPage.Options.fromJson(project.cover), coverPicture(copy, project));
                try (java.io.OutputStream out = target.call()) { report = ProjectArchive.export(dir, copy, project, options, out, app, now()); }
            } catch (Exception failed) { problem = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage(); }
            ProjectArchive.Report r = report; String why = problem;
            main.post(() -> { if (!isDestroyed()) done.accept(r, why); });
        });
    }
    private void shareArchive(Projects.Project p) {
        File out = ShareProvider.fresh(this, fileName(p.title) + ProjectArchive.EXTENSION);
        writeArchiveTo(p, () -> new java.io.FileOutputStream(out), (report, problem) -> {
            if (report == null) { toast("Could not make the project file: " + problem); return; }
            Uri uri = ShareProvider.uriFor(this, out);
            Intent send = new Intent(Intent.ACTION_SEND).setType(ProjectArchive.MIME).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, p.title)
                .putExtra(Intent.EXTRA_TEXT, p.title + ": a Link Workshop project (" + report.describe() + ")").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri(p.title, uri));
            startActivity(Intent.createChooser(send, "Share " + p.title));
        });
    }
    private void writeArchive(Uri target) {
        Projects.Project p = book.project(pickProject); if (p == null || target == null) return;
        writeArchiveTo(p, () -> getContentResolver().openOutputStream(target), (report, problem) -> toast(report == null ? "Could not save the project file: " + problem : "Saved: " + report.describe()));
    }
    private void importPick() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {ProjectArchive.MIME, "application/zip", "application/octet-stream"});
        try { startActivityForResult(pick, IMPORT); } catch (android.content.ActivityNotFoundException none) { toast("No file picker is available on this phone."); }
    }
    /** Reads a project file (copied to the cache first), shows what it holds, and adds it only when the person says so. */
    private void importFrom(Uri uri) {
        File copy = new File(getCacheDir(), "import" + ProjectArchive.EXTENSION);
        toast("Reading the project file…");
        worker.execute(() -> {
            ProjectArchive.Imported read = null; String problem = null;
            try (InputStream in = getContentResolver().openInputStream(uri); java.io.OutputStream out = new java.io.FileOutputStream(copy)) {
                if (in == null) throw new java.io.IOException("it could not be opened");
                byte[] buffer = new byte[65536]; long total = 0; int n;
                while ((n = in.read(buffer)) > 0) { total += n; if (total > ProjectArchive.MAX_TOTAL + 64L * 1024 * 1024) throw new java.io.IOException("it is too large"); out.write(buffer, 0, n); }
                out.close();
                read = ProjectArchive.read(copy, dir, now());
            } catch (Exception failed) { problem = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage(); }
            finally { copy.delete(); }
            ProjectArchive.Imported got = read; String why = problem;
            main.post(() -> {
                if (isDestroyed()) return;
                if (got == null) { new AlertDialog.Builder(this).setTitle("Not imported").setMessage("The project file could not be read: " + why).setPositiveButton("OK", null).show(); return; }
                new AlertDialog.Builder(this).setTitle("Import “" + got.title + "”?").setMessage(got.report.describe() + "\n\nIt is added as a new project; nothing here is replaced.")
                    .setNegativeButton("Cancel", (d, w) -> worker.execute(() -> { for (Projects.Asset a : got.assets) if (a.storage.equals(Projects.MANAGED)) Projects.deleteBytes(dir, a); }))
                    .setPositiveButton("Import", (d, w) -> {
                        List<Projects.Asset> duplicates = ProjectArchive.adopt(book, got);
                        worker.execute(() -> { for (Projects.Asset a : duplicates) Projects.deleteBytes(dir, a); });
                        save(); projectId = got.project.id; screen = OVERVIEW; render();
                    }).setCancelable(false).show();
            });
        });
    }

    // ------------------------------------------------------------------ cover page

    private ImageView coverPreview; private TextView coverStatus; private int coverDrawn;
    private void coverScreen(Projects.Project p) {
        ui.button(content, "‹ " + p.title, () -> { screen = OVERVIEW; render(); }, false);
        A11y.heading(ui.label(content, "Cover page", 22, ui.ink, true));
        CoverPage.Options o = CoverPage.Options.fromJson(p.cover);
        coverPreview = new ImageView(this); coverPreview.setAdjustViewBounds(true); coverPreview.setBackgroundColor(ui.surface);
        content.addView(coverPreview, new LinearLayout.LayoutParams(-1, -2));
        coverStatus = A11y.polite(ui.label(content, "Drawing…", 12, ui.muted, false));
        LinearLayout row = ui.row(content);
        ui.rowButton(row, "Share…", () -> shareCover(p), true);
        ui.rowButton(row, "Save PNG…", () -> saveCoverPick(p, false), false);
        ui.rowButton(row, "Save PDF…", () -> saveCoverPick(p, true), false);

        LinearLayout look = ui.card(content, "Look");
        ui.label(look, "Style", 13, ui.ink, false);
        List<String> styles = new ArrayList<>(); int style = 0; for (int i = 0; i < CoverPage.STYLES.length; i++) { styles.add(CoverPage.STYLES[i][1]); if (CoverPage.STYLES[i][0].equals(o.style)) style = i; }
        Spinner styleSpinner = ui.spinner(look, styles, style);
        ui.label(look, "Colour", 13, ui.ink, false);
        LinearLayout swatches = new LinearLayout(this); swatches.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView swatchScroll = new HorizontalScrollView(this); swatchScroll.addView(swatches); look.addView(swatchScroll);
        for (String[] accent : CoverPage.ACCENTS) {
            Button b = new Button(this); b.setText(accent[1]); b.setAllCaps(false); b.setTextColor(android.graphics.Color.WHITE); b.setMinHeight(ui.dp(48));
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable(); shape.setCornerRadius(ui.dp(12)); shape.setColor(android.graphics.Color.parseColor(accent[0]));
            if (accent[0].equals(o.accent)) shape.setStroke(ui.dp(4), ui.ink);
            b.setBackground(shape); b.setContentDescription(accent[1] + (accent[0].equals(o.accent) ? ", chosen" : ""));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = ui.dp(8); swatches.addView(b, lp);
            b.setOnClickListener(v -> { o.accent = accent[0]; keepCover(p, o); render(); });
        }
        ui.label(look, "Page", 13, ui.ink, false);
        List<String> pages = new ArrayList<>(); int page = 0; for (int i = 0; i < CoverPage.PAGES.length; i++) { pages.add((String) CoverPage.PAGES[i][1]); if (CoverPage.PAGES[i][0].equals(o.page)) page = i; }
        Spinner pageSpinner = ui.spinner(look, pages, page);
        ui.label(look, "Picture", 13, ui.ink, false);
        List<Projects.Asset> images = new ArrayList<>(); for (String id : p.allAssets()) { Projects.Asset a = book.asset(id); if (a != null && a.role.equals("image") && a.storage.equals(Projects.MANAGED)) images.add(a); }
        List<String> pictureNames = new ArrayList<>(Collections.singletonList(images.isEmpty() ? "None (add an image to the project's files)" : "None")); int pic = 0;
        for (int i = 0; i < images.size(); i++) { pictureNames.add(images.get(i).name); if (images.get(i).id.equals(o.picture)) pic = i + 1; }
        Spinner pictureSpinner = ui.spinner(look, pictureNames, pic);
        CheckBox large = check(look, "Large title", o.largeTitle);

        LinearLayout words = ui.card(content, "Words");
        EditText subtitle = field(words, "Subtitle (empty: the project's description)", o.subtitle, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText maker = field(words, "Made by (optional)", o.maker, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText footer = field(words, "Footer (optional): a link, a licence, a note", o.footer, InputType.TYPE_CLASS_TEXT);
        ui.button(words, "Use these words", () -> { o.subtitle = Automation.cap(subtitle.getText().toString().trim(), 200); o.maker = Automation.cap(maker.getText().toString().trim(), 80);
            o.footer = Automation.cap(footer.getText().toString().trim(), 200); keepCover(p, o); drawCover(p, o); }, false);

        LinearLayout show = ui.card(content, "What it shows");
        for (String[] section : CoverPage.SECTIONS) {
            CheckBox box = check(show, section[1], o.sections.contains(section[0]));
            box.setOnCheckedChangeListener((v, on) -> { if (on) o.sections.add(section[0]); else o.sections.remove(section[0]); keepCover(p, o); drawCover(p, o); });
        }
        AdapterView.OnItemSelectedListener changed = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int position, long id) {
                String nextStyle = CoverPage.STYLES[Math.max(0, styleSpinner.getSelectedItemPosition())][0], nextPage = (String) CoverPage.PAGES[Math.max(0, pageSpinner.getSelectedItemPosition())][0];
                int pi = pictureSpinner.getSelectedItemPosition(); String nextPicture = pi <= 0 || pi > images.size() ? "" : images.get(pi - 1).id;
                if (nextStyle.equals(o.style) && nextPage.equals(o.page) && nextPicture.equals(o.picture)) return;
                o.style = nextStyle; o.page = nextPage; o.picture = nextPicture; keepCover(p, o); drawCover(p, o);
            }
            public void onNothingSelected(AdapterView<?> a) { }
        };
        styleSpinner.setOnItemSelectedListener(changed); pageSpinner.setOnItemSelectedListener(changed); pictureSpinner.setOnItemSelectedListener(changed);
        large.setOnCheckedChangeListener((v, on) -> { o.largeTitle = on; keepCover(p, o); drawCover(p, o); });
        drawCover(p, o);
    }
    private void keepCover(Projects.Project p, CoverPage.Options o) { try { p.cover = o.toJson(); save(); } catch (Exception impossible) { } }
    /** The cover's picture: a kept image of the project, read at most about 1600 pixels across. */
    private Bitmap coverPicture(Projects.Book from, Projects.Project p) {
        CoverPage.Options o = CoverPage.Options.fromJson(p.cover);
        Projects.Asset a = o.picture.isEmpty() ? null : from.asset(o.picture); File f = a == null ? null : Projects.managedFile(dir, a);
        if (f == null || !f.isFile()) return null;
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeFile(f.getPath(), bounds);
        android.graphics.BitmapFactory.Options read = new android.graphics.BitmapFactory.Options(); read.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 1600)));
        return android.graphics.BitmapFactory.decodeFile(f.getPath(), read);
    }
    /** Draws the cover in the background and shows it; only the latest drawing is shown. */
    private void drawCover(Projects.Project p, CoverPage.Options o) {
        int ticket = ++coverDrawn; CoverPage.Content c = CoverPage.content(book, p, o, now());
        String picture = o.picture; Projects.Asset a = picture.isEmpty() ? null : book.asset(picture);
        CoverPage.Options copy = CoverPage.Options.fromJson(safeJson(o));
        worker.execute(() -> {
            Bitmap image = null;
            if (a != null) { Projects.Book one = new Projects.Book(); one.assets.add(a); Projects.Project q = new Projects.Project(); q.cover = safeJson(copy); image = coverPicture(one, q); }
            Bitmap page = CoverPage.render(c, copy, image);
            int w = Math.min(1080, page.getWidth()); Bitmap shown = Bitmap.createScaledBitmap(page, w, Math.round(page.getHeight() * (w / (float) page.getWidth())), true);
            if (shown != page) page.recycle();
            main.post(() -> {
                if (isDestroyed() || ticket != coverDrawn || coverPreview == null) return;
                coverPreview.setImageBitmap(shown);
                coverPreview.setContentDescription("Cover page preview: " + c.title + (c.subtitle.isEmpty() ? "" : ", " + c.subtitle) + ". " + (c.parts.size() + c.purchased.size() + c.tasks.size()) + " items listed.");
                coverStatus.setText(copy.page()[1] + " · " + copy.width() + " × " + copy.height() + " px");
            });
        });
    }
    private static org.json.JSONObject safeJson(CoverPage.Options o) { try { return o.toJson(); } catch (Exception e) { return new org.json.JSONObject(); } }
    /** Renders the full-size cover in the background and hands over PNG bytes or writes a PDF. */
    private void coverBytes(Projects.Project p, boolean pdf, java.util.concurrent.Callable<java.io.OutputStream> target, Runnable after) {
        CoverPage.Options o = CoverPage.Options.fromJson(p.cover); CoverPage.Content c = CoverPage.content(book, p, o, now());
        String text; try { text = book.toJson(); } catch (Exception impossible) { return; }
        String id = p.id;
        worker.execute(() -> {
            String said = null;
            try (java.io.OutputStream out = target.call()) {
                Projects.Book copy = Projects.Book.fromJson(text); Bitmap image = coverPicture(copy, copy.project(id));
                if (pdf) CoverPage.pdf(c, o, image, out); else out.write(CoverPage.png(c, o, image));
            } catch (Exception failed) { said = "Could not make the cover page: " + failed.getMessage(); }
            String problem = said; main.post(() -> { if (problem != null) toast(problem); else after.run(); });
        });
    }
    private void shareCover(Projects.Project p) {
        File out = ShareProvider.fresh(this, fileName(p.title) + " cover.png");
        coverBytes(p, false, () -> new java.io.FileOutputStream(out), () -> {
            Uri uri = ShareProvider.uriFor(this, out);
            Intent send = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri(p.title, uri));
            startActivity(Intent.createChooser(send, "Share the cover page"));
        });
    }
    private void saveCoverPick(Projects.Project p, boolean pdf) {
        pickProject = p.id;
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(pdf ? "application/pdf" : "image/png").putExtra(Intent.EXTRA_TITLE, fileName(p.title) + " cover" + (pdf ? ".pdf" : ".png"));
        try { startActivityForResult(save, pdf ? SAVE_COVER_PDF : SAVE_COVER_PNG); } catch (android.content.ActivityNotFoundException none) { toast("No file saver is available on this phone."); }
    }
    private void saveCover(Uri target, boolean pdf) {
        Projects.Project p = book.project(pickProject); if (p == null || target == null) return;
        coverBytes(p, pdf, () -> getContentResolver().openOutputStream(target), () -> toast("Saved the cover page."));
    }

    /** A fresh file into the book (main thread); a copy whose bytes are already kept is dropped and the kept one used. */
    private Projects.Asset adopt(Projects.Asset fresh) {
        Projects.Asset kept = book.adopt(fresh);
        if (kept != fresh) { worker.execute(() -> Projects.deleteBytes(dir, fresh)); toast(fresh.name + " is already kept in the app: the same copy is used, not a second one."); }
        return kept;
    }

    // ------------------------------------------------------------------ small helpers

    private String nameOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) { }
        String last = uri.getLastPathSegment(); return last == null ? "file" : last;
    }
    private long sizeOf(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) { }
        return -1;
    }
    private LinearLayout form() { LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0); return body; }
    private ScrollView scroll(View body) { ScrollView s = new ScrollView(this); s.addView(body); return s; }
    private EditText field(LinearLayout parent, String label, String value, int type) {
        ui.label(parent, label, 13, ui.ink, false);
        EditText input = new EditText(this); input.setText(value); input.setInputType(type); input.setTextColor(ui.ink); input.setMinHeight(ui.dp(48)); input.setContentDescription(label);
        if ((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) input.setSingleLine(true);
        parent.addView(input); return input;
    }
}
