package cc.panelsd.connect.core.impl.node;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON body of a {@code CFG?} reply. A baked Nano prints this from program storage.
 */
public final class NodeCfgDocument {

    private NodeCfgDocument() {
    }

    /** Accepts {@code CFG {...}} or a bare object. Empty and {@code {}} are absent. */
    public static JsonObject parse(String line) {
        if (line == null) {
            return null;
        }
        String body = line.trim();
        if (body.length() >= 4 && body.regionMatches(true, 0, "CFG ", 0, 4)) {
            body = body.substring(4).trim();
        }
        if (body.isEmpty() || "{}".equals(body)) {
            return null;
        }
        try {
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) {
                return null;
            }
            JsonObject obj = el.getAsJsonObject();
            if (!hasItems(obj, "pins") && !hasItems(obj, "outputs") && !hasItems(obj, "opticalDistricts")) {
                return null;
            }
            return obj;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static void copyOnto(NodeInventory.NodeRecord rec, JsonObject cfg) {
        rec.config = cfg.deepCopy();
        rec.pins = array(cfg, "pins");
        rec.outputs = array(cfg, "outputs");
        rec.opticalDistricts = array(cfg, "opticalDistricts");
        if (cfg.has("boardType") && cfg.get("boardType").isJsonPrimitive()) {
            rec.boardType = BoardProfile.normalize(cfg.get("boardType").getAsString());
        }
    }

    private static boolean hasItems(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonArray() && obj.getAsJsonArray(key).size() > 0;
    }

    private static JsonArray array(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            return obj.getAsJsonArray(key).deepCopy();
        }
        return new JsonArray();
    }
}
