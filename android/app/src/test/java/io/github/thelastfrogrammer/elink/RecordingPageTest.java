package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * A print's recording page in parts (PageSection): the story order, what is open at first, heavy parts built only when
 * opened and remembered across prints, the tour opening everything, every action still there, and the list's way to a
 * file's page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h891dp-xxhdpi")
public class RecordingPageTest {
    @After public void off() { Showcase.stop(); }

    private static Context context() { return RuntimeEnvironment.getApplication(); }
    private static PrintRecorder recorder() { return new PrintRecorder(RecordingsActivity.directory(context())); }

    /** A print fed through the recorder as the service feeds it, one status a minute, with `pictures` camera pictures. */
    private static PrintRecorder.Recording record(String file, String start, int minutes, int pictures) throws Exception {
        PrintRecorder recorder = recorder(); long begin = Instant.parse(start).toEpochMilli();
        for (int m = 0; m <= minutes; m++) {
            JSONObject status = new JSONObject()
                .put("machine_status", new JSONObject().put("status", 2).put("sub_status", m == minutes ? 2077 : 2075).put("progress", m * 100 / minutes))
                .put("print_status", new JSONObject().put("filename", file).put("uuid", file + start).put("current_layer", m / 2).put("total_layer", minutes / 2).put("remaining_time_sec", (minutes - m) * 60))
                .put("extruder", new JSONObject().put("temperature", 220).put("target", 220)).put("heater_bed", new JSONObject().put("temperature", 60).put("target", 60))
                .put("fans", new JSONObject().put("fan", new JSONObject().put("speed", 255)));
            recorder.update(begin + m * 60_000L, status, "local", "Bedroom");
        }
        PrintRecorder.Recording made = null;
        for (PrintRecorder.Recording r : recorder.list()) if (r.start == begin) made = r;
        made.pictureFolder().mkdirs();
        for (int i = 0; i < pictures; i++) {
            android.graphics.Bitmap frame = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888);
            try (OutputStream out = new FileOutputStream(new File(made.pictureFolder(), String.format(Locale.ROOT, "%06d.jpg", 120 + i * 300)))) { frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out); }
        }
        return made;
    }
    private static RecordingsActivity open(PrintRecorder.Recording r, boolean journal) {
        Intent intent = new Intent(context(), RecordingsActivity.class).putExtra(RecordingsActivity.EXTRA_META, r.meta.getAbsolutePath());
        if (journal) intent.putExtra(RecordingsActivity.EXTRA_JOURNAL, true);
        return Robolectric.buildActivity(RecordingsActivity.class, intent).setup().get();
    }
    @SuppressWarnings("unchecked")
    private static List<PageSection> parts(RecordingsActivity page) throws Exception {
        java.lang.reflect.Field f = RecordingsActivity.class.getDeclaredField("sections"); f.setAccessible(true);
        return (List<PageSection>) f.get(page);
    }
    private static PageSection part(RecordingsActivity page, String name) throws Exception {
        for (PageSection p : parts(page)) if (p.name.equals(name)) return p;
        return null;
    }
    private static List<String> names(RecordingsActivity page) throws Exception { List<String> out = new ArrayList<>(); for (PageSection p : parts(page)) out.add(p.name); return out; }
    private static <T extends View> List<T> all(View view, Class<T> kind) {
        List<T> out = new ArrayList<>();
        if (kind.isInstance(view)) out.add(kind.cast(view));
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) out.addAll(all(((ViewGroup) view).getChildAt(i), kind));
        return out;
    }
    private static TextView text(View root, String exactly) { for (TextView t : all(root, TextView.class)) if (t.getText().toString().equals(exactly)) return t; return null; }
    private static View root(android.app.Activity a) { return a.findViewById(android.R.id.content); }
    private static void layOut(android.app.Activity a) {
        View decor = a.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, 1080, 2340);
    }

    @Test public void theStoryReadsFromTheTopWithTheHeavyPartsClosed() throws Exception {
        RecordingsActivity page = open(record("Benchy_PLA.gcode", "2026-10-06T14:05:00Z", 60, 4), false);
        assertEquals(Arrays.asList("At a glance", "What happened", "Pictures and video", "Charts", "Layers and G-code", "Tools"), names(page));
        for (String name : Arrays.asList("At a glance", "What happened", "Tools")) assertTrue(name + " opens at first", part(page, name).isOpen());
        for (String name : Arrays.asList("Pictures and video", "Charts", "Layers and G-code")) {
            PageSection p = part(page, name);
            assertFalse(name + " starts closed", p.isOpen());
            assertEquals("and is not built until opened", 0, p.body.getChildCount());
            assertFalse(name + " says what is inside", p.summary.getText().toString().isEmpty());
        }
        assertTrue("no chart is drawn while the charts are closed", all(root(page), ChartView.class).isEmpty());
        assertNotNull("the latest picture leads the page", text(root(page), "Latest picture · 0:17 into the print"));
        assertNotNull("the result is told at a glance", text(part(page, "At a glance").body, "Completed"));
        assertEquals("Progress, layer, temperatures and fans over time", part(page, "Charts").summary.getText().toString());
        assertEquals("4 pictures · a timelapse can be made", part(page, "Pictures and video").summary.getText().toString());
    }

    @Test public void eachHeaderIsATallHeadingThatSaysWhetherItIsOpen() throws Exception {
        RecordingsActivity page = open(record("Benchy_PLA.gcode", "2026-10-06T14:05:00Z", 60, 0), false);
        layOut(page);
        float density = page.getResources().getDisplayMetrics().density;
        for (PageSection p : parts(page)) {
            assertTrue(p.name + " is at least 48dp tall", p.header.getHeight() / density >= 48);
            AccessibilityNodeInfo info = p.header.createAccessibilityNodeInfo();
            assertTrue(p.name + " is a heading", info.isHeading());
            assertEquals(p.isOpen() ? "Expanded" : "Collapsed", String.valueOf(info.getStateDescription()));
            assertTrue("offers the matching action", info.getActionList().contains(p.isOpen() ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND));
            assertTrue("its name, and while closed its line, are spoken", info.getContentDescription().toString().startsWith(p.name));
            if (!p.isOpen()) assertTrue(info.getContentDescription().toString().endsWith(p.summary.getText().toString()));
            assertFalse("the arrow is not spoken", info.getContentDescription().toString().contains("▾"));
        }
    }

    @Test public void openingAPartBuildsItAndTheNextPrintRemembers() throws Exception {
        PrintRecorder.Recording first = record("Benchy_PLA.gcode", "2026-10-06T14:05:00Z", 60, 0), second = record("Clip.gcode", "2026-10-07T10:00:00Z", 30, 0);
        RecordingsActivity page = open(first, false);
        PageSection charts = part(page, "Charts");
        charts.header.performClick();
        assertTrue(charts.isOpen());
        assertEquals("the readings chart, one at a time behind chips", 1, all(charts.body, ChartView.class).stream().filter(c -> c.getVisibility() == View.VISIBLE).count());
        assertEquals("Expanded", String.valueOf(charts.header.createAccessibilityNodeInfo().getStateDescription()));
        part(page, "What happened").header.performClick();
        RecordingsActivity next = open(second, false);
        assertTrue("Charts stays open on the next print", part(next, "Charts").isOpen());
        assertFalse("What happened stays closed", part(next, "What happened").isOpen());
        assertEquals("a closed part shows its line", View.VISIBLE, part(next, "What happened").summary.getVisibility());
        assertFalse("its charts are there at once", all(part(next, "Charts").body, ChartView.class).isEmpty());
    }

    @Test public void theTourOpensEveryPartAndShowsEveryChart() throws Exception {
        PrintRecorder.Recording r = record("Benchy_PLA.gcode", "2026-10-06T14:05:00Z", 60, 2);
        context().getSharedPreferences("workshop-settings", 0).edit().putBoolean(PageSection.KEY + "recording.charts", false).putBoolean(PageSection.KEY + "recording.glance", false).commit();
        Showcase.stage = Showcase.RECORDING;
        RecordingsActivity page = open(r, false);
        for (PageSection p : parts(page)) assertTrue(p.name + " is open in the tour", p.isOpen());
        long shown = all(part(page, "Charts").body, ChartView.class).stream().filter(c -> c.getVisibility() == View.VISIBLE).count();
        assertEquals("progress, layer, temperatures and fans, all at once", 4, shown);
        assertNotNull("the tour finds the camera pictures", Showcase.find(root(page), "Camera pictures"));
        assertFalse("the tour did not change what the person chose", context().getSharedPreferences("workshop-settings", 0).getBoolean(PageSection.KEY + "recording.charts", true));
    }

    @Test public void theJournalLinkOpensAtAGlanceWithoutChangingTheChoice() throws Exception {
        context().getSharedPreferences("workshop-settings", 0).edit().putBoolean(PageSection.KEY + "recording.glance", false).commit();
        PrintRecorder.Recording r = record("Benchy_PLA.gcode", "2026-10-06T14:05:00Z", 60, 0);
        assertFalse(part(open(r, false), "At a glance").isOpen());
        RecordingsActivity rating = open(r, true);
        assertTrue("opened to rate the print, the journal is in view", part(rating, "At a glance").isOpen());
        assertNotNull(text(part(rating, "At a glance").body, "What went wrong…"));
        assertFalse(context().getSharedPreferences("workshop-settings", 0).getBoolean(PageSection.KEY + "recording.glance", true));
    }

    @Test public void everyActionIsStillThereAndDeletingStandsApart() throws Exception {
        RecordingsActivity page = open(record("Benchy_PLA.gcode", "2026-10-06T14:05:00Z", 60, 0), false);
        View tools = part(page, "Tools").body;
        List<String> actions = new ArrayList<>(); for (Button b : all(tools, Button.class)) actions.add(b.getText().toString());
        assertEquals(Arrays.asList("All readings…", "Export CSV…", "Compare with…", "Share a report…", "Change settings and slice again…", "Every print of this file…", "Delete this recording…"), actions);
        Button delete = all(tools, Button.class).get(6), readings = all(tools, Button.class).get(0);
        assertNotEquals("deleting is in the warning colour", readings.getCurrentTextColor(), delete.getCurrentTextColor());
        // Opening the other parts adds their cards and buttons: the G-code, timelapse and chart chips are all still reachable.
        part(page, "Layers and G-code").header.performClick();
        assertNotNull(text(part(page, "Layers and G-code").body, "The print's G-code"));
    }

    @Test public void listCardsOfAFilePrintedAgainLeadToItsPage() throws Exception {
        record("Benchy_PLA.gcode", "2026-10-06T14:05:00Z", 30, 0);
        record("Benchy_PLA.gcode", "2026-10-08T14:05:00Z", 30, 0);
        record("Clip.gcode", "2026-10-07T10:00:00Z", 30, 0);
        RecordingsActivity list = Robolectric.buildActivity(RecordingsActivity.class).setup().get();
        List<TextView> links = new ArrayList<>(); for (TextView t : all(root(list), TextView.class)) if (t.getText().toString().equals("All 2 prints of this file")) links.add(t);
        assertEquals("both prints of the file lead to it; the clip, printed once, does not", 2, links.size());
        layOut(list);
        assertTrue("a full touch target", links.get(0).getHeight() / list.getResources().getDisplayMetrics().density >= 48);
        links.get(0).performClick();
        Intent started = Shadows.shadowOf(list).getNextStartedActivity();
        assertEquals(FileMemoryActivity.class.getName(), started.getComponent().getClassName());
        assertEquals("Benchy_PLA.gcode", started.getStringExtra(FileMemoryActivity.EXTRA_FILE));
        assertEquals(java.util.Map.of("Benchy_PLA.gcode", 2, "Clip.gcode", 1), RecordingsActivity.printsPerFile(recorder().list()));
    }

    @Test public void closedPartsSayWhatIsInside() {
        assertEquals("Nothing stood out in the readings · 6 checks measured, no problems", RecordingsActivity.happenedLine(0, false, 6, 0));
        assertEquals("2 things to look at · a tuning try · 1 problem measured", RecordingsActivity.happenedLine(2, true, 6, 1));
        assertEquals("1 thing to look at", RecordingsActivity.happenedLine(1, false, 0, 0));
        assertEquals("No pictures yet", RecordingsActivity.picturesLine(0, 0, false, false));
        assertEquals("24 pictures · 24 more with the head far · a timelapse video · where the head was", RecordingsActivity.picturesLine(24, 24, true, true));
        assertEquals("Progress and temperatures over time · which tray fed it", RecordingsActivity.chartsLine(Arrays.asList("progress", "temperatures"), true));
        assertEquals("Which tray fed the print", RecordingsActivity.chartsLine(Collections.emptyList(), true));
        assertTrue(RecordingsActivity.layersLine(false).contains("not on this phone"));
    }

    @Test public void theEndIsAClockTimeOnTheSameDay() {
        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        long start = Instant.parse("2026-10-07T22:30:00Z").toEpochMilli();
        assertEquals("23:40", RecordingsActivity.endedWords(start, Instant.parse("2026-10-07T23:40:00Z").toEpochMilli(), Locale.UK, utc));
        assertEquals("Thu 8 Oct, 01:50", RecordingsActivity.endedWords(start, Instant.parse("2026-10-08T01:50:00Z").toEpochMilli(), Locale.UK, utc));
    }

    @Test public void atAGlanceNamesWhatStandsOutInShort() {
        List<RecordingAnalysis.Finding> found = Arrays.asList(new RecordingAnalysis.Finding(RecordingAnalysis.Finding.COOLING, 68, "long words"),
            new RecordingAnalysis.Finding(RecordingAnalysis.Finding.COOLING, 80, "long words"), new RecordingAnalysis.Finding(RecordingAnalysis.Finding.NOZZLE_DROP, 0, "long words"));
        assertEquals(Arrays.asList("The filament sensor reported no filament at 1:20 into the print", Tuning.what(RecordingAnalysis.Finding.COOLING) + " (layers 68, 80)",
            Tuning.what(RecordingAnalysis.Finding.NOZZLE_DROP)), RecordingsActivity.highlights(found, "The filament sensor reported no filament at 1:20 into the print."));
        assertTrue(RecordingsActivity.highlights(Collections.emptyList(), "").isEmpty());
        PrintRecorder.Recording r = new PrintRecorder.Recording(new File("x.csv"), new File("x.json"));
        r.outcome = "stopped"; r.start = 0; r.end = 3_600_000L; r.journal.stars = 4;
        assertTrue(RecordingsActivity.glanceLine(r).startsWith("Stopped · 1 h · ★★★★☆"));
    }
}
