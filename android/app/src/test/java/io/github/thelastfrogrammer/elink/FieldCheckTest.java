package io.github.thelastfrogrammer.elink;

import static org.junit.Assert.*;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The field checklist: results and notes survive, the report reads plainly, and Go there only opens screens. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FieldCheckTest {
    @Test public void resultsAndNotesSurviveAndReportByArea() {
        Map<String, FieldCheck.Entry> entries = new HashMap<>();
        entries.put("files", new FieldCheck.Entry(FieldCheck.Result.FAILS, "List stays blank\nafter refresh", 1_700_000_000_000L));
        entries.put("cloud", new FieldCheck.Entry(FieldCheck.Result.WORKS, "", 1_700_000_000_000L));
        entries.put("alerts", new FieldCheck.Entry(FieldCheck.Result.SKIPPED, "", 1_700_000_000_000L));
        Map<String, FieldCheck.Entry> back = FieldCheck.parse(FieldCheck.toJson(entries));
        assertEquals(FieldCheck.Result.FAILS, back.get("files").result); assertEquals("List stays blank\nafter refresh", back.get("files").note);
        assertEquals("3 of " + FieldCheck.CHECKS.size() + " checked · 1 problem", FieldCheck.summary(back));
        String report = FieldCheck.report(back, "App 1.0", TimeZone.getTimeZone("UTC"));
        assertTrue(report, report.contains("## Files\n"));
        assertTrue(report, report.contains("✕ Files tab lists the printer's files · 2023-11-14 22:13\n    List stays blank\n    after refresh\n"));
        assertTrue(report, report.contains("✓ Cloud monitoring · "));
        assertTrue(report, report.contains("– Alerts in the background (skipped)"));
        assertTrue(report, report.contains("· Local Wi-Fi connection (not checked)"));
        assertTrue("unreadable saves are ignored", FieldCheck.parse("{not json").isEmpty());
    }

    @Test public void everyCheckHasAPlaceAndUniqueId() {
        Set<String> ids = new HashSet<>();
        for (FieldCheck.Check c : FieldCheck.CHECKS) {
            assertTrue(c.id, ids.add(c.id));
            assertTrue(c.id, c.open == FieldCheck.NOWHERE || c.open >= 0 && c.open <= FieldCheck.SLICE);
            assertFalse(c.how.isEmpty());
        }
    }

    @Test public void markingSavesAndGoThereOnlyNavigates() {
        RuntimeEnvironment.getApplication().getSharedPreferences(FieldCheckActivity.PREFS, 0).edit().clear().commit();
        FieldCheckActivity activity = Robolectric.buildActivity(FieldCheckActivity.class).setup().get();
        List<Button> buttons = new ArrayList<>(); collect(activity.getWindow().getDecorView(), buttons);
        Button works = null, goThere = null;
        for (Button b : buttons) { if (works == null && "Works".contentEquals(b.getText())) works = b; if (goThere == null && "Go there".contentEquals(b.getText())) goThere = b; }
        assertNotNull(works); assertNotNull(goThere);
        works.performClick();   // the first check: Local Wi-Fi connection
        Map<String, FieldCheck.Entry> saved = FieldCheck.parse(activity.getSharedPreferences(FieldCheckActivity.PREFS, 0).getString(FieldCheckActivity.KEY, null));
        assertEquals(FieldCheck.Result.WORKS, saved.get("local").result);
        works.performClick();   // tapping it again clears it
        saved = FieldCheck.parse(activity.getSharedPreferences(FieldCheckActivity.PREFS, 0).getString(FieldCheckActivity.KEY, null));
        assertEquals(FieldCheck.Result.NONE, saved.get("local").result);
        goThere.performClick();
        Intent next = ((org.robolectric.shadows.ShadowActivity) org.robolectric.shadow.api.Shadow.extract(activity)).getNextStartedActivity();
        assertEquals(MainActivity.class.getName(), next.getComponent().getClassName());
        assertEquals("Go there shows where the connection is set up, pressing nothing", "connect", next.getStringExtra(MainActivity.EXTRA_FEATURE));
        assertFalse(FeatureIndex.byId("connect").press);
    }

    private static void collect(View view, List<Button> out) {
        if (view instanceof Button) out.add((Button) view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), out);
    }
}
