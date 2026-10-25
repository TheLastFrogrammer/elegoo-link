package io.github.thelastfrogrammer.elink.tv;

import static org.junit.Assert.*;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONObject;
import org.junit.Test;

/** What the TV shows for a status and an event: only what was reported, in the TV's clock. */
public class StatusTextTest {
    /** The example in INTEGRATIONS.md, at 01:34:05 UTC. */
    static final String PRINTING = "{\"schema\":\"linkworkshop.status/1\",\"updated_at\":\"2026-10-25T01:34:05Z\","
        + "\"printer\":{\"name\":\"Centauri Carbon 2\",\"model\":\"Centauri Carbon 2\"},"
        + "\"connection\":{\"route\":\"local network\",\"fresh\":true,\"status_at\":\"2026-10-25T01:34:04Z\",\"age_s\":1},"
        + "\"state\":{\"phase\":\"printing\",\"words\":\"Printing\",\"printing\":true,\"machine_status\":2,\"sub_status\":2075,\"faults\":[]},"
        + "\"print\":{\"file\":\"Desk_mount_PLA\",\"progress\":42,\"layer\":51,\"total_layers\":120,\"remaining_s\":4380,\"done_at\":\"2026-10-25T02:47:05Z\",\"started_at\":\"2026-10-25T00:44:05Z\",\"elapsed_s\":3000},"
        + "\"temperatures\":{\"nozzle\":{\"now\":214.8,\"target\":215.0},\"bed\":{\"now\":60.1,\"target\":60.0},\"chamber\":{\"now\":31.4}},"
        + "\"fans\":{\"part\":70,\"aux\":35,\"chamber\":0},\"light\":true}";
    static final long NOW = StatusText.isoMs("2026-10-25T01:34:05Z");
    static final TimeZone UTC = TimeZone.getTimeZone("UTC"), BERLIN = TimeZone.getTimeZone("Europe/Berlin");

    private static StatusText.Shown show(String json, TimeZone zone, String pattern) throws Exception {
        return StatusText.of(new JSONObject(json), NOW, NOW, new SimpleDateFormat(pattern, Locale.US), zone);
    }

    @Test public void aRunningPrint() throws Exception {
        StatusText.Shown s = show(PRINTING, UTC, "HH:mm");
        assertEquals("Centauri Carbon 2", s.printer);
        assertEquals("Printing", s.headline);
        assertEquals("Desk_mount_PLA", s.file);
        assertEquals(42, s.progress); assertEquals("42 %", s.progressText);
        assertEquals("Layer 51 of 120", s.layer);
        assertEquals("1 h 13 min left", s.timeLeft);
        assertEquals("done about 02:47", s.doneAt);
        assertEquals("Layer 51 of 120 · 1 h 13 min left · done about 02:47", StatusText.printLine(s).replace(' ', ' '));
        assertEquals("it wraps only between parts", 4, StatusText.printLine(s).split(" ").length - 1);
        assertTrue(s.active); assertTrue(s.fresh); assertFalse(s.attention);
        assertEquals(4, s.numbers.size());
        assertEquals("215 / 215 °C", s.numbers.get(0).value); assertEquals("Nozzle", s.numbers.get(0).label);
        assertEquals("60 / 60 °C", s.numbers.get(1).value);
        assertEquals("a reading without a target", "31 °C", s.numbers.get(2).value);
        assertEquals("70 %", s.numbers.get(3).value);
        assertEquals("Live · local network", s.freshness);
        assertEquals("", s.faults);
        assertTrue(s.spoken, s.spoken.startsWith("Printing, Desk_mount_PLA, 42 percent, layer 51 of 120, 1 hour 13 minutes left, done about 02:47."));
        assertTrue(s.spoken.contains("Nozzle 215 degrees, heating to 215"));
    }
    @Test public void theTvsOwnClockAndFormat() throws Exception {
        assertEquals("the TV's time zone", "done about 03:47", show(PRINTING, BERLIN, "HH:mm").doneAt);
        assertEquals("a 12-hour clock", "done about 3:47 AM", show(PRINTING, BERLIN, "h:mm a").doneAt);
        long late = StatusText.isoMs("2026-10-25T22:30:00Z");
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        assertEquals("tomorrow 01:30", StatusText.clock(late + 3 * 3600_000L, late, hm, UTC));
        assertEquals("over a year's end", "tomorrow 00:30", StatusText.clock(StatusText.isoMs("2027-01-01T00:30:00Z"), StatusText.isoMs("2026-12-31T23:00:00Z"), hm, UTC));
        assertEquals("02:00", StatusText.clock(StatusText.isoMs("2026-10-25T02:00:00Z"), StatusText.isoMs("2026-10-25T01:00:00Z"), hm, UTC));
    }
    @Test public void pausedHasNoDoneAtAndAsksForALook() throws Exception {
        StatusText.Shown s = show(PRINTING.replace("\"phase\":\"printing\",\"words\":\"Printing\"", "\"phase\":\"paused\",\"words\":\"Paused\""), UTC, "HH:mm");
        assertEquals("Paused", s.headline); assertTrue(s.attention); assertTrue(s.active);
        assertEquals("the time left stays", "1 h 13 min left", s.timeLeft);
        assertEquals("the end time stands still while paused, so none", "", s.doneAt);
    }
    @Test public void faultsAreSaid() throws Exception {
        StatusText.Shown s = show(PRINTING.replace("\"faults\":[]", "\"faults\":[\"1201\"]"), UTC, "HH:mm");
        assertEquals("The printer reports fault 1201", s.faults); assertTrue(s.attention);
        assertEquals("The printer reports faults 1201, 1305", show(PRINTING.replace("\"faults\":[]", "\"faults\":[\"1201\",\"1305\"]"), UTC, "HH:mm").faults);
    }
    @Test public void offlineShowsNoOldNumbers() throws Exception {
        String off = "{\"schema\":\"linkworkshop.status/1\",\"printer\":{\"name\":\"Centauri Carbon 2\"},\"connection\":{\"route\":\"not connected\",\"fresh\":false,\"status_at\":\"2026-10-25T01:29:05Z\",\"age_s\":300},"
            + "\"state\":{\"phase\":\"offline\",\"words\":\"No status\",\"printing\":false}}";
        StatusText.Shown s = StatusText.of(new JSONObject(off), NOW, NOW + 60_000, new SimpleDateFormat("HH:mm", Locale.US), UTC);
        assertEquals("The phone is not connected to the printer", s.headline);
        assertFalse(s.detail.isEmpty()); assertFalse(s.fresh); assertFalse(s.active); assertTrue(s.attention);
        assertEquals(-1, s.progress); assertTrue(s.numbers.isEmpty());
        assertEquals("its age counts on from when it arrived", "Last status from the printer 6 min ago", s.freshness);
        String waiting = off.replace("\"route\":\"not connected\"", "\"route\":\"Elegoo cloud\"");
        assertEquals("Waiting for the printer", show(waiting, UTC, "HH:mm").headline);
        assertEquals("Waiting for the phone", StatusText.of(null, 0, NOW, null, UTC).headline);
    }
    @Test public void missingFieldsShowNothing() throws Exception {
        StatusText.Shown s = show("{\"connection\":{\"fresh\":true,\"route\":\"local network\"},\"state\":{\"phase\":\"idle\",\"words\":\"Idle\",\"printing\":false},\"print\":{\"progress\":0}}", UTC, "HH:mm");
        assertEquals("Idle", s.headline); assertFalse(s.active);
        assertEquals("", s.file); assertEquals("", s.layer); assertEquals("", s.timeLeft); assertEquals("", s.doneAt);
        assertTrue(s.numbers.isEmpty()); assertEquals("", s.printer);
        assertEquals("the phase when words are missing", "Getting ready to print", show("{\"connection\":{\"fresh\":true},\"state\":{\"phase\":\"preparing\"}}", UTC, "HH:mm").headline);
        assertEquals("a heater that is off", "24 °C", show("{\"connection\":{\"fresh\":true},\"state\":{\"phase\":\"idle\"},\"temperatures\":{\"bed\":{\"now\":24.4,\"target\":0}}}", UTC, "HH:mm").numbers.get(0).value);
    }
    @Test public void durations() {
        assertEquals("0 min", StatusText.duration(59)); assertEquals("45 min", StatusText.duration(45 * 60 + 59));
        assertEquals("1 h", StatusText.duration(3600)); assertEquals("2 h 5 min", StatusText.duration(2 * 3600 + 300));
        assertEquals("26 h", StatusText.duration(26 * 3600 + 120));
        assertEquals("1 hour 1 minute", StatusText.spokenDuration(3660)); assertEquals("5 minutes", StatusText.spokenDuration(300));
        assertEquals("just now", StatusText.ago(20)); assertEquals("3 min ago", StatusText.ago(200));
    }
    @Test public void eventBanners() throws Exception {
        StatusText.Banner done = StatusText.banner(new JSONObject("{\"schema\":\"linkworkshop.event/1\",\"type\":\"print_finished\",\"words\":\"A print finishes\",\"file\":\"Desk_mount_PLA\",\"layer\":120,\"total_layers\":120,\"progress\":100}"));
        assertEquals("Print finished", done.title); assertEquals("Desk_mount_PLA", done.detail); assertTrue(done.good); assertFalse(done.alert);
        StatusText.Banner alarm = StatusText.banner(new JSONObject("{\"type\":\"guardian_alarm\",\"file\":\"Vase\",\"layer\":12,\"total_layers\":300}"));
        assertEquals("The print guardian sees a problem", alarm.title); assertEquals("Vase · layer 12 of 300", alarm.detail); assertTrue(alarm.alert);
        assertTrue(alarm.showMs() > done.showMs());
        for (String t : new String[] {"print_started", "first_layer", "paused", "resumed", "print_stopped", "fault", "print_watch", "contact_lost", "bed_levelled", "maintenance_finished"})
            assertFalse(t, StatusText.banner(new JSONObject().put("type", t)).title.isEmpty());
        assertEquals("a new type uses the phone's words", "Something new", StatusText.banner(new JSONObject("{\"type\":\"later_type\",\"words\":\"Something new\"}")).title);
        assertNull(StatusText.banner(new JSONObject("{\"type\":\"later_type\"}")));
    }
}
