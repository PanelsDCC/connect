package cc.panelsd.connect.updater;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone updater for panelsDCC-connect Debian packages.
 * <p>
 * Checks GitHub {@code PanelsDCC/connect} for the latest release, compares with the
 * installed {@code panelsdcc-connect} package version, and optionally downloads
 * and installs the release {@code .deb}.
 * <p>
 * Persists progress to {@value #STATE_FILE}.
 * <p>
 * Usage:
 * <pre>
 *   java -cp ... cc.panelsd.connect.updater.ConnectUpdater check
 *   java -cp ... cc.panelsd.connect.updater.ConnectUpdater install
 *   java -cp ... cc.panelsd.connect.updater.ConnectUpdater test
 * </pre>
 */
public final class ConnectUpdater {

    private static final String STATE_DIR = "/var/lib/panelsdcc-connect";
    private static final String STATE_FILE = STATE_DIR + "/update-status.json";
    private static final String TMP_DIR = "/var/tmp/panelsdcc-connect";

    private static final String REPO_OWNER = "PanelsDCC";
    private static final String REPO_NAME = "connect";
    private static final String DEB_PACKAGE = "panelsdcc-connect";

    private static final String USER_AGENT = "panelsdcc-connect-updater";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);

    private static final Pattern SEMVER = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$",
            Pattern.CASE_INSENSITIVE);

    private ConnectUpdater() {
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "check";
        int code;
        try {
            switch (mode) {
                case "install":
                    runInstall();
                    break;
                case "test":
                    runTest();
                    break;
                default:
                    runCheck();
                    break;
            }
            code = 0;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
            try {
                JsonObject prev = readStateJson();
                boolean alreadyErr = prev.has("state") && !prev.get("state").isJsonNull()
                        && "error".equals(prev.get("state").getAsString());
                if (!alreadyErr) {
                    writeState(mapOf(
                            "state", "error",
                            "lastError", msg,
                            "message", "Update failed."));
                }
            } catch (Exception ignored) {
                // ignore
            }
            System.err.println("panelsdcc-connect-updater: " + msg);
            if (e.getCause() != null) {
                e.printStackTrace(System.err);
            }
            code = 1;
        }
        System.exit(code);
    }

    /**
     * JSON snapshot of {@value #STATE_FILE} for REST/UI, merged with the authoritative
     * installed package version from {@link #getCurrentVersion()} (dpkg or classpath).
     * <p>
     * After a manual {@code dpkg -i} upgrade, the file on disk may still list an older
     * {@code currentVersion}; this method always injects the live installed version and
     * recomputes {@code hasUpdate} / {@code state} when {@code latestVersion} is present
     * (unless a check/download/install is in progress).
     */
    public static String getStatusJsonString() {
        JsonObject o = readStateJson();
        Gson gson = new Gson();
        String installed = getCurrentVersion();

        if (o.size() == 0) {
            JsonObject d = new JsonObject();
            d.addProperty("state", "unknown");
            d.addProperty("message", "No update check has been run yet.");
            d.addProperty("currentVersion", installed);
            return gson.toJson(d);
        }

        o.addProperty("currentVersion", installed);

        String state = "";
        if (o.has("state") && !o.get("state").isJsonNull()) {
            state = o.get("state").getAsString();
        }
        boolean busy = "checking".equals(state) || "downloading".equals(state) || "installing".equals(state);

        if (!busy && o.has("latestVersion") && !o.get("latestVersion").isJsonNull()) {
            String latest = o.get("latestVersion").getAsString();
            boolean hasUpdate = isNewerVersion(latest, installed);
            o.addProperty("hasUpdate", hasUpdate);
            if (!hasUpdate) {
                o.addProperty("state", "up-to-date");
                o.addProperty("message", "Already running the latest version.");
                o.add("targetVersion", JsonNull.INSTANCE);
            } else {
                if ("up-to-date".equals(state)) {
                    o.addProperty("state", "update-available");
                }
                String msg = o.has("message") && !o.get("message").isJsonNull()
                        ? o.get("message").getAsString() : "";
                if (msg.isEmpty() || msg.contains("Already running the latest")) {
                    o.addProperty("message", "Update available: " + latest);
                }
            }
        }

        return gson.toJson(o);
    }

    /** Same as CLI {@code check}: query GitHub and write {@value #STATE_FILE}. */
    public static void performCheck() throws IOException, InterruptedException {
        runCheck();
    }

    /** Same as CLI {@code install}: download .deb and run dpkg (requires root). */
    public static void performInstall() throws IOException, InterruptedException {
        runInstall();
    }

    /** Installed semver (dpkg or classpath). */
    public static String getInstalledVersion() {
        return getCurrentVersion();
    }

    private static void ensureDir(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    private static JsonObject readStateJson() {
        Path p = Path.of(STATE_FILE);
        if (!Files.isRegularFile(p)) {
            return new JsonObject();
        }
        try {
            String raw = Files.readString(p, StandardCharsets.UTF_8);
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    /** Key/value pairs; values may be null (unlike {@link Map#of}). */
    private static Map<String, Object> mapOf(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("mapOf requires an even number of arguments");
        }
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static void writeState(Map<String, Object> partial) throws IOException {
        ensureDir(Path.of(STATE_DIR));
        JsonObject merged = readStateJson();
        for (Map.Entry<String, Object> e : partial.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null) {
                merged.add(k, JsonNull.INSTANCE);
            } else if (v instanceof Boolean) {
                merged.addProperty(k, (Boolean) v);
            } else if (v instanceof Number) {
                merged.addProperty(k, (Number) v);
            } else {
                merged.addProperty(k, String.valueOf(v));
            }
        }
        merged.addProperty("updatedAt", Instant.now().toString());
        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(merged);
        Files.writeString(Path.of(STATE_FILE), json, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /**
     * Current application semver: dpkg version for {@value #DEB_PACKAGE}, or embedded Maven version.
     */
    static String getCurrentVersion() {
        String fromDpkg = dpkgVersion();
        if (fromDpkg != null && !fromDpkg.isBlank()) {
            return normalizeDebianVersionToSemver(fromDpkg);
        }
        String fromResource = readVersionFromClasspath();
        if (fromResource != null && !fromResource.isBlank()) {
            return normalizeDebianVersionToSemver(fromResource);
        }
        return "0.0.0";
    }

    /**
     * Debian {@code Version:} is often {@code 0.3.0-1}; compare as {@code 0.3.0}.
     */
    static String normalizeDebianVersionToSemver(String v) {
        String s = v.trim();
        // strip epoch "1:"
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        // last segment if hyphen-separated and not part of semver pre-release: 0.3.0-1 -> 0.3.0
        if (s.matches("^[0-9]+\\.[0-9]+\\.[0-9]+-[0-9]+$")) {
            return s.substring(0, s.lastIndexOf('-'));
        }
        return s.replaceFirst("^v", "");
    }

    private static String dpkgVersion() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "dpkg-query", "-W", "-f=${Version}", DEB_PACKAGE);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (InputStream is = p.getInputStream()) {
                out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            int ex = p.waitFor();
            if (ex == 0 && !out.isEmpty()) {
                return out;
            }
        } catch (Exception ignored) {
            // not installed or dpkg missing
        }
        return null;
    }

    private static String readVersionFromClasspath() {
        try (InputStream is = ConnectUpdater.class.getResourceAsStream(
                "/META-INF/maven/cc.panelsd.connect/panelsDCC-connect/pom.properties")) {
            if (is == null) {
                return null;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("version=")) {
                        return line.substring("version=".length()).trim();
                    }
                }
            }
        } catch (IOException ignored) {
            // ignore
        }
        return null;
    }

    static int[] parseSemver(String v) {
        if (v == null) {
            return null;
        }
        String s = v.trim().replaceFirst("^v", "");
        Matcher m = SEMVER.matcher(s);
        if (!m.matches()) {
            return null;
        }
        return new int[]{
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))
        };
    }

    static boolean isNewerVersion(String a, String b) {
        int[] va = parseSemver(a);
        int[] vb = parseSemver(b);
        if (va == null || vb == null) {
            return !Objects.equals(normalizeTag(a), normalizeTag(b));
        }
        if (va[0] != vb[0]) {
            return va[0] > vb[0];
        }
        if (va[1] != vb[1]) {
            return va[1] > vb[1];
        }
        return va[2] > vb[2];
    }

    private static String normalizeTag(String v) {
        return v == null ? "" : v.trim().replaceFirst("^v", "");
    }

    /**
     * GitHub {@code browser_download_url} responses are often {@code 302} to another host
     * (e.g. {@code objects.githubusercontent.com}). {@link HttpClient.Redirect#NORMAL} does not
     * follow cross-origin redirects, which breaks downloads with "status 302".
     */
    private static HttpClient httpClient(Duration timeout) {
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();
    }

    private static JsonObject fetchLatestRelease() throws IOException, InterruptedException {
        String url = String.format(Locale.ROOT,
                "https://api.github.com/repos/%s/%s/releases/latest", REPO_OWNER, REPO_NAME);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> res = httpClient(HTTP_TIMEOUT).send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("GitHub API error " + res.statusCode());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static ReleaseInfo parseRelease(JsonObject data) {
        String tag = "";
        if (data.has("tag_name") && !data.get("tag_name").isJsonNull()) {
            tag = data.get("tag_name").getAsString();
        } else if (data.has("name") && !data.get("name").isJsonNull()) {
            tag = data.get("name").getAsString();
        }
        String latestVersion = normalizeTag(tag);
        if (latestVersion.isEmpty()) {
            throw new IllegalStateException("Latest release has no version tag/name");
        }
        JsonArray assets = data.has("assets") && data.get("assets").isJsonArray()
                ? data.getAsJsonArray("assets") : new JsonArray();
        JsonObject debAsset = null;
        for (int i = 0; i < assets.size(); i++) {
            JsonObject a = assets.get(i).getAsJsonObject();
            String name = a.has("name") && !a.get("name").isJsonNull() ? a.get("name").getAsString() : "";
            if (name.endsWith(".deb") && name.toLowerCase(Locale.ROOT).contains("panelsdcc")) {
                debAsset = a;
                break;
            }
        }
        if (debAsset == null) {
            for (int i = 0; i < assets.size(); i++) {
                JsonObject a = assets.get(i).getAsJsonObject();
                String name = a.has("name") && !a.get("name").isJsonNull() ? a.get("name").getAsString() : "";
                if (name.endsWith(".deb")) {
                    debAsset = a;
                    break;
                }
            }
        }
        if (debAsset == null || !debAsset.has("browser_download_url")) {
            throw new IllegalStateException("Latest release has no .deb asset");
        }
        String assetName = debAsset.get("name").getAsString();
        String downloadUrl = debAsset.get("browser_download_url").getAsString();
        return new ReleaseInfo(latestVersion, assetName, downloadUrl);
    }

    private static final class ReleaseInfo {
        final String latestVersion;
        final String assetName;
        final String downloadUrl;

        ReleaseInfo(String latestVersion, String assetName, String downloadUrl) {
            this.latestVersion = latestVersion;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
        }
    }

    private static void downloadFile(String url, Path dest) throws IOException, InterruptedException {
        ensureDir(dest.getParent());
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> res = httpClient(DOWNLOAD_TIMEOUT).send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("Download failed with status " + res.statusCode());
        }
        Path tmp = dest.resolveSibling(dest.getFileName().toString() + ".part");
        try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            if (in == null) {
                throw new IOException("Empty response body");
            }
            in.transferTo(out);
        }
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void runCommand(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process p = pb.start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("Command failed: " + String.join(" ", cmd) + " (exit " + code + ")");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void runTest() throws IOException {
        String currentVersion = getCurrentVersion();
        String fakeLatest = bumpPatch(currentVersion);

        writeState(mapOf(
                "state", "checking",
                "currentVersion", currentVersion,
                "lastError", null,
                "message", "Checking GitHub for latest release..."));
        sleep(800);

        writeState(mapOf(
                "state", "update-available",
                "latestVersion", fakeLatest,
                "targetVersion", fakeLatest,
                "hasUpdate", true,
                "message", "Update available: " + fakeLatest + " (test)"));
        sleep(800);

        writeState(mapOf(
                "state", "downloading",
                "latestVersion", fakeLatest,
                "targetVersion", fakeLatest,
                "hasUpdate", true,
                "message", "Downloading (test run, no real download)..."));
        sleep(2000);

        writeState(mapOf(
                "state", "installing",
                "message", "Installing (test run, no dpkg)..."));
        sleep(1000);

        writeState(mapOf(
                "state", "success",
                "message", "Test run: no install performed. UI flow OK.",
                "currentVersion", currentVersion,
                "latestVersion", fakeLatest,
                "targetVersion", null,
                "hasUpdate", false));
    }

    private static String bumpPatch(String v) {
        int[] p = parseSemver(normalizeDebianVersionToSemver(v));
        if (p == null) {
            return "0.0.1";
        }
        return p[0] + "." + p[1] + "." + (p[2] + 1);
    }

    private static void runCheck() throws IOException, InterruptedException {
        String currentVersion = getCurrentVersion();
        writeState(mapOf(
                "state", "checking",
                "currentVersion", currentVersion,
                "lastError", null,
                "message", "Checking GitHub for latest release..."));

        JsonObject data = fetchLatestRelease();
        ReleaseInfo info = parseRelease(data);
        boolean hasUpdate = isNewerVersion(info.latestVersion, currentVersion);
        writeState(mapOf(
                "state", hasUpdate ? "update-available" : "up-to-date",
                "latestVersion", info.latestVersion,
                "targetVersion", hasUpdate ? info.latestVersion : null,
                "hasUpdate", hasUpdate,
                "message", hasUpdate
                        ? "Update available: " + info.latestVersion
                        : "Already running the latest version."));
    }

    private static void runInstall() throws IOException, InterruptedException {
        String currentVersion = getCurrentVersion();
        writeState(mapOf(
                "state", "checking",
                "currentVersion", currentVersion,
                "lastError", null,
                "message", "Checking for update before install..."));

        JsonObject data = fetchLatestRelease();
        ReleaseInfo info = parseRelease(data);
        boolean hasUpdate = isNewerVersion(info.latestVersion, currentVersion);

        if (!hasUpdate) {
            writeState(mapOf(
                    "state", "up-to-date",
                    "latestVersion", info.latestVersion,
                    "targetVersion", null,
                    "hasUpdate", false,
                    "message", "Already running the latest version."));
            return;
        }

        Path debPath = Path.of(TMP_DIR, info.assetName);
        ensureDir(Path.of(TMP_DIR));
        writeState(mapOf(
                "state", "downloading",
                "latestVersion", info.latestVersion,
                "targetVersion", info.latestVersion,
                "hasUpdate", true,
                "message", "Downloading " + info.assetName + "..."));

        try {
            downloadFile(info.downloadUrl, debPath);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
            writeState(mapOf(
                    "state", "error",
                    "lastError", msg,
                    "message", "Download failed."));
            throw e;
        }

        writeState(mapOf(
                "state", "installing",
                "message", "Installing " + info.assetName + "..."));

        try {
            runCommand("dpkg", "-i", debPath.toAbsolutePath().toString());
        } catch (Exception e) {
            try {
                runCommand("apt-get", "install", "-f", "-y");
                runCommand("dpkg", "-i", debPath.toAbsolutePath().toString());
            } catch (Exception e2) {
                String msg = e2.getMessage() != null ? e2.getMessage() : String.valueOf(e2);
                writeState(mapOf(
                        "state", "error",
                        "lastError", msg,
                        "message", "Failed to install update via dpkg/apt."));
                throw e2;
            }
        }

        writeState(mapOf(
                "state", "success",
                "message", "Updated to " + info.latestVersion + ".",
                "currentVersion", info.latestVersion,
                "latestVersion", info.latestVersion,
                "targetVersion", null,
                "hasUpdate", false));
    }
}
