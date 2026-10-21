package io.github.thelastfrogrammer.elink;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class PrintAlertsTest {
    private static JSONObject state(int machine,int sub,String job) throws Exception { return new JSONObject().put("machine_status",new JSONObject().put("status",machine).put("sub_status",sub)).put("print_status",new JSONObject().put("uuid",job).put("filename",job.isEmpty()?"":job+".gcode")); }
    @Test public void completionRequiresObservedJobAndOnlyAlertsOnce() throws Exception {
        PrintAlerts alerts=new PrintAlerts(); assertEquals("",alerts.update(state(2,2077,"a"))); assertEquals("",alerts.update(state(2,2075,"a")));
        assertEquals("Print complete: a.gcode",alerts.update(state(2,2077,"a"))); assertEquals("",alerts.update(state(2,2077,"a")));
    }
    @Test public void idleCancellationAndDisconnectedSessionsDoNotImplyCompletion() throws Exception {
        for (int end : new int[] {2503,2504,-1}) { PrintAlerts a=new PrintAlerts(); a.update(state(2,2075,"a")); a.update(state(end==-1?1:2,end,"a")); assertEquals("",a.update(state(2,2077,"a"))); }
        PrintAlerts a=new PrintAlerts(); a.update(state(2,2075,"a")); a.disconnected(); assertEquals("",a.update(state(2,2077,"a")));
    }
    @Test public void clearedCompletionFilenameUsesObservedNameButOtherJobIsRejected() throws Exception {
        PrintAlerts a=new PrintAlerts(); a.update(state(2,2075,"a")); assertEquals("Print complete: a.gcode",a.update(state(2,2077,"")));
        a.update(state(2,2075,"a")); assertEquals("",a.update(state(2,2077,"b")));
    }
    @Test public void unchangedFaultDoesNotRepeatButNewOrReappearingFaultDoes() throws Exception {
        PrintAlerts a=new PrintAlerts(); JSONObject s=state(1,0,""); s.put("exception",new JSONObject("{\"exception_code\":{\"803\":{}}}"));
        assertEquals("Printer fault: 803",a.update(s)); assertEquals("",a.update(s)); a.disconnected(); assertEquals("",a.update(s));
        s.getJSONObject("exception").getJSONObject("exception_code").put("1101",new JSONObject()); assertTrue(a.update(s).contains("1101"));
        s.getJSONObject("exception").put("exception_code",new JSONObject()); assertEquals("",a.update(s)); s.getJSONObject("exception").put("exception_code",new JSONObject("{\"803\":{}}")); assertEquals("Printer fault: 803",a.update(s));
    }
    /** A fault says what the printer was doing: here, as in the user's log, it went idle in the same status the fault came in. */
    @Test public void aFaultSaysWhatThePrinterWasDoing() throws Exception {
        PrintAlerts a = new PrintAlerts(); JSONObject levelling = state(2, 1066, "a");
        String doing = StatusPresentation.state(levelling);
        assertEquals("", a.update(levelling));
        JSONObject idle = state(1, 0, ""); idle.put("exception", new JSONObject("{\"exception_code\":{\"704\":{}}}"));
        assertEquals("Printer fault: 704 · while " + doing + " · the printer is idle now", a.update(idle));
        JSONObject printing = layer("b", 135); printing.put("exception", new JSONObject("{\"exception_code\":{\"1243\":{}}}"));
        assertEquals("a fault while still printing names the state it came in", "Printer fault: 1243 · while " + StatusPresentation.state(printing), a.update(printing));
    }
    private static JSONObject layer(String job, int layer) throws Exception { JSONObject s = state(2, 2075, job); s.getJSONObject("print_status").put("current_layer", layer); return s; }
    @Test public void firstLayerAndChosenLayerAlertOnceAndOnlyWhenSeenChanging() throws Exception {
        PrintAlerts a = new PrintAlerts(); a.firstLayer = true; a.layer = 3; a.layerKey = "a";
        assertEquals("", a.update(layer("a", 1)));
        assertEquals("First layer done: a.gcode", a.update(layer("a", 2)));
        assertEquals("", a.update(layer("a", 2)));
        assertEquals("Layer 3 reached: a.gcode", a.update(layer("a", 4)));
        assertEquals("", a.update(layer("a", 5)));
        PrintAlerts late = new PrintAlerts(); late.firstLayer = true; late.layer = 3; late.layerKey = "b";
        assertEquals("joined at layer 50: nothing to report", "", late.update(layer("b", 50))); assertEquals("", late.update(layer("b", 51)));
        PrintAlerts other = new PrintAlerts(); other.layer = 3; other.layerKey = "a";
        other.update(layer("b", 2)); assertEquals("the layer alert belongs to its own print", "", other.update(layer("b", 3)));
    }
}
