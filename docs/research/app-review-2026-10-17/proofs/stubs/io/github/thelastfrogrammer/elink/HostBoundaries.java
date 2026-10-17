package io.github.thelastfrogrammer.elink;

import java.util.*;
import org.json.JSONObject;

/** Host compilation boundaries. None of the tested identity/count/retention/storage code is replaced. */
final class Automation {
    // Same cap implementation as the reviewed source.
    static String cap(String text, int length) { return text == null ? "" : text.length() <= length ? text : text.substring(0, length); }
    static boolean webhookUrlOk(String url) { throw new UnsupportedOperationException("Not exercised"); }
}
final class ModelSites {
    static final Set<String> MODEL_TYPES = new HashSet<>(Arrays.asList("stl", "3mf", "obj", "step", "stp", "amf"));
    static String extension(String name) { throw new UnsupportedOperationException("Not exercised"); }
}
final class ProjectsActivity {
    static String size(long bytes) { return bytes + " B"; }
}
final class Cc2Codec {
    // Same position implementation as the reviewed source; fixtures have no extruder position.
    public static JSONObject position(JSONObject status) {
        JSONObject move = status.optJSONObject("gcode_move"); return move != null ? move : status.optJSONObject("gcode_move_inf");
    }
}
