package cc.panelsd.connect.core.impl.node;

/**
 * Catalog / board image versions. Preferred form is a build date {@code YYYY-MM-DD}.
 * Legacy integer ({@code 4}) and semver ({@code 1.0.4}) entries sort older than any date.
 */
public final class ImageVersions {

    private ImageVersions() {
    }

    /** Display form: dates stay {@code 2026-10-27}; legacy ints become {@code v4.0.0}. */
    public static String display(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = stripV(raw.trim());
        if (isDate(t)) {
            return t;
        }
        int[] p = semverParts(t);
        if (p != null) {
            return "v" + p[0] + "." + p[1] + "." + p[2];
        }
        return raw.trim();
    }

    /** {@code > 0} if a is newer than b. */
    public static int compare(String a, String b) {
        if (a == null || a.isBlank()) {
            return (b == null || b.isBlank()) ? 0 : -1;
        }
        if (b == null || b.isBlank()) {
            return 1;
        }
        String aa = stripV(a.trim());
        String bb = stripV(b.trim());
        boolean aDate = isDate(aa);
        boolean bDate = isDate(bb);
        if (aDate && bDate) {
            return aa.compareTo(bb);
        }
        if (aDate && !bDate) {
            return 1;
        }
        if (!aDate && bDate) {
            return -1;
        }
        // Both legacy (int or semver): compare as semver-ish triples.
        int[] pa = semverParts(aa);
        int[] pb = semverParts(bb);
        if (pa != null && pb != null) {
            for (int i = 0; i < 3; i++) {
                if (pa[i] != pb[i]) {
                    return Integer.compare(pa[i], pb[i]);
                }
            }
            return 0;
        }
        return aa.compareToIgnoreCase(bb);
    }

    public static boolean isNewer(String candidate, String baseline) {
        return compare(candidate, baseline) > 0;
    }

    public static boolean isDate(String raw) {
        return raw != null && raw.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "1970-01-01";
        }
        String t = stripV(raw.trim());
        if (isDate(t)) {
            return t;
        }
        int[] p = semverParts(t);
        if (p != null) {
            return p[0] + "." + p[1] + "." + p[2];
        }
        return t;
    }

    private static String stripV(String raw) {
        String t = raw.trim();
        if (t.regionMatches(true, 0, "v", 0, 1) && t.length() > 1 && Character.isDigit(t.charAt(1))) {
            return t.substring(1).trim();
        }
        return t;
    }

    private static int[] semverParts(String raw) {
        if (raw == null) {
            return null;
        }
        String t = stripV(raw);
        if (isDate(t)) {
            return null;
        }
        String[] bits = t.split("\\.");
        if (bits.length == 0 || bits.length > 3) {
            return null;
        }
        if (bits.length == 1 && !bits[0].matches("\\d+")) {
            return null;
        }
        int[] out = new int[] {0, 0, 0};
        try {
            for (int i = 0; i < bits.length; i++) {
                if (!bits[i].matches("\\d+")) {
                    return null;
                }
                out[i] = Integer.parseInt(bits[i]);
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accept JSON number or string ({@code YYYY-MM-DD}, semver, or legacy int). */
    public static String fromJsonValue(com.google.gson.JsonElement v) {
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) {
            return "1970-01-01";
        }
        try {
            if (v.getAsJsonPrimitive().isNumber()) {
                return String.valueOf(v.getAsInt());
            }
            String s = v.getAsString().trim();
            if (s.isEmpty()) {
                return "1970-01-01";
            }
            if (s.regionMatches(true, 0, "v", 0, 1) && s.length() > 1 && Character.isDigit(s.charAt(1))) {
                s = s.substring(1).trim();
            }
            return s;
        } catch (RuntimeException e) {
            return "1970-01-01";
        }
    }
}
