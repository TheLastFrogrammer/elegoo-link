package io.github.thelastfrogrammer.elink;

import org.json.JSONObject;
import java.util.*;

/** Alerts require observed state; disconnect/idle never implies successful completion. */
public final class PrintAlerts {
    private String observedJob = "";
    private String observedName = "";
    private boolean armed;
    private Set<String> faults = new HashSet<>();
    /** Also alert when the first layer is done, and when the print (`layerKey`) reaches `layer` (0 = no layer alert). */
    public boolean firstLayer; public int layer; public String layerKey = "";
    private int lastLayer = -1; private boolean firstSent, layerSent;
    /**
     * The alert for this status, or "": a new fault, then a completion, then a layer alert. Layer alerts need the layer seen
     * changing (an app that connects at layer 50 does not report the first layer as just done).
     */
    public String update(JSONObject status) {
        String codes = StatusPresentation.faultCodes(status);
        Set<String> now = codes.isEmpty() ? new HashSet<>() : new HashSet<>(Arrays.asList(codes.split(", ")));
        Set<String> added = new HashSet<>(now); added.removeAll(faults); faults = now;
        JSONObject machine = status.optJSONObject("machine_status"), print = status.optJSONObject("print_status");
        String name = print == null ? "" : print.optString("filename"), key = print == null ? "" : print.optString("uuid", "");
        if (key.isEmpty()) key = name;
        boolean completed = false; String reached = "";
        if (machine != null && machine.optInt("status", -1) == 2) {
            int sub = machine.optInt("sub_status", -1);
            if (sub == 2077) { completed = armed && (key.isEmpty() || observedJob.equals(key)); armed = false; }
            else if (sub == 2503 || sub == 2504) armed = false;
            else if (!key.isEmpty()) {
                if (!key.equals(observedJob)) { lastLayer = -1; firstSent = false; layerSent = false; }
                observedJob = key; observedName = name; armed = true;
                int current = print.optInt("current_layer", -1); String shown = StatusPresentation.clean(name.isEmpty() ? observedName : name);
                if (firstLayer && !firstSent && lastLayer >= 0 && lastLayer <= 1 && current >= 2) { firstSent = true; reached = "First layer done: " + shown; }
                if (layer > 0 && key.equals(layerKey) && !layerSent && lastLayer > 0 && lastLayer < layer && current >= layer) { layerSent = true; reached = "Layer " + layer + " reached: " + shown; }
                if (current >= 0) lastLayer = current;
            }
        } else if (machine != null && machine.optInt("status", -1) == 1) armed = false;
        if (!added.isEmpty()) return "Printer fault: " + codes;
        return completed ? "Print complete: " + StatusPresentation.clean(name.isEmpty() ? observedName : name) : reached;
    }
    public void disconnected() { armed = false; }
}
