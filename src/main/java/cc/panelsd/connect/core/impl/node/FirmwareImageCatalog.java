package cc.panelsd.connect.core.impl.node;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Shipped prebuilt node images, with an optional newer copy under
 * {@code ~/.dcc-io/firmware-images/}. Connect does not download images.
 * A cache entry may name a {@code config} sidecar for a later custom-Nano import;
 * this catalog only records the path.
 */
public final class FirmwareImageCatalog {

    public static final String DEFAULT_IMAGES_ROOT =
            "/usr/share/panelsdcc-connect/images";

    private final Path shippedDir;
    private final Path cacheDir;
    private final Map<String, Image> shipped;
    private final Map<String, Image> cache;

    public FirmwareImageCatalog(Path shippedDir, Path cacheDir, Map<String, Image> shipped, Map<String, Image> cache) {
        this.shippedDir = shippedDir;
        this.cacheDir = cacheDir;
        this.shipped = shipped;
        this.cache = cache;
    }

    public static FirmwareImageCatalog loadDefault() {
        return load(resolveShippedDir(), defaultCacheDir());
    }

    public static Path resolveShippedDir() {
        Path cwd = Paths.get("").toAbsolutePath();
        List<Path> candidates = new ArrayList<>();
        candidates.add(cwd.resolve("firmware").resolve("images"));
        candidates.add(cwd.resolve("connect").resolve("firmware").resolve("images"));
        candidates.add(Paths.get(DEFAULT_IMAGES_ROOT));
        for (Path p : candidates) {
            if (Files.isRegularFile(p.resolve("manifest.json"))) {
                return p;
            }
        }
        return Paths.get(DEFAULT_IMAGES_ROOT);
    }

    public static Path defaultCacheDir() {
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".dcc-io", "firmware-images");
    }

    public static FirmwareImageCatalog load(Path shippedDir, Path cacheDir) {
        Map<String, Image> shipped = readManifest(shippedDir, "shipped");
        Map<String, Image> cache = readManifest(cacheDir, "cache");
        return new FirmwareImageCatalog(shippedDir, cacheDir, shipped, cache);
    }

    public Path shippedDir() {
        return shippedDir;
    }

    public Path cacheDir() {
        return cacheDir;
    }

    /**
     * Shipped image, or the cache copy when its {@code version} is strictly newer
     * and its files are present. Nano AVR has no shipped image: only an uploaded zip.
     */
    public Optional<Image> select(String boardType) {
        String id = BoardProfile.normalize(boardType);
        Image cachedImage = cache.get(id);
        if (BoardProfile.NANO_AVR.equals(id)) {
            if (cachedImage != null && cachedImage.filesPresent()) {
                return Optional.of(cachedImage);
            }
            return Optional.empty();
        }
        Image shippedImage = shipped.get(id);
        if (cachedImage != null && cachedImage.filesPresent()) {
            String shippedVersion = shippedImage == null ? null : shippedImage.version;
            if (ImageVersions.isNewer(cachedImage.version, shippedVersion)) {
                return Optional.of(cachedImage);
            }
        }
        return Optional.ofNullable(shippedImage);
    }

    public List<Image> shippedImages() {
        return List.copyOf(shipped.values());
    }

    /** Shipped image for each id, or the newer cache copy when one is present. */
    public List<Image> selectable() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        ids.addAll(shipped.keySet());
        ids.addAll(cache.keySet());
        List<Image> out = new ArrayList<>();
        for (String id : ids) {
            select(id).ifPresent(out::add);
        }
        return out;
    }

    private static Map<String, Image> readManifest(Path dir, String source) {
        Map<String, Image> out = new LinkedHashMap<>();
        if (dir == null) {
            return out;
        }
        Path manifest = dir.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return out;
        }
        try {
            String text = Files.readString(manifest);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray images = root.has("images") && root.get("images").isJsonArray()
                    ? root.getAsJsonArray("images")
                    : new JsonArray();
            for (JsonElement el : images) {
                if (!el.isJsonObject()) {
                    continue;
                }
                Image image = Image.fromJson(dir, el.getAsJsonObject(), source);
                if (image != null && image.id != null) {
                    out.put(image.id, image);
                }
            }
        } catch (Exception ignore) {
            return out;
        }
        return out;
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        byte[] dig = md.digest();
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    public static final class Part {
        public final Path file;
        public final String offset;
        public final String sha256;

        public Part(Path file, String offset, String sha256) {
            this.file = file;
            this.offset = offset;
            this.sha256 = sha256;
        }
    }

    public static final class Image {
        public final String id;
        /** Build date {@code YYYY-MM-DD}, or legacy semver / integer as decimal text. */
        public final String version;
        public final String tool;
        public final String mcu;
        public final String programmer;
        public final String chip;
        public final int baud;
        /** Second avrdude baud (old vs new Nano bootloader). Null when unused. */
        public final Integer altBaud;
        public final List<String> flashArgs;
        public final List<Part> parts;
        /** Optional config sidecar in the same directory. Not applied by the flasher. */
        public final Path configFile;
        public final String source;

        public Image(
                String id,
                String version,
                String tool,
                String mcu,
                String programmer,
                String chip,
                int baud,
                Integer altBaud,
                List<String> flashArgs,
                List<Part> parts,
                Path configFile,
                String source) {
            this.id = id;
            this.version = version == null || version.isBlank() ? "0.0.0" : version.trim();
            this.tool = tool;
            this.mcu = mcu;
            this.programmer = programmer;
            this.chip = chip;
            this.baud = baud;
            this.altBaud = altBaud;
            this.flashArgs = flashArgs == null ? List.of() : List.copyOf(flashArgs);
            this.parts = parts == null ? List.of() : List.copyOf(parts);
            this.configFile = configFile;
            this.source = source;
        }

        /** @deprecated use {@link #Image(String, String, String, String, String, String, int, Integer, List, List, Path, String)} */
        @Deprecated
        public Image(
                String id,
                int version,
                String tool,
                String mcu,
                String programmer,
                String chip,
                int baud,
                Integer altBaud,
                List<String> flashArgs,
                List<Part> parts,
                Path configFile,
                String source) {
            this(id, String.valueOf(version), tool, mcu, programmer, chip, baud, altBaud, flashArgs, parts, configFile, source);
        }

        static Image fromJson(Path dir, JsonObject o, String source) {
            if (!o.has("id") || o.get("id").isJsonNull()) {
                return null;
            }
            String id = BoardProfile.normalize(o.get("id").getAsString());
            String version = ImageVersions.fromJsonValue(o.get("version"));
            String tool = text(o, "tool", "avrdude");
            String mcu = text(o, "mcu", null);
            String programmer = text(o, "programmer", null);
            String chip = text(o, "chip", null);
            int baud = o.has("baud") && o.get("baud").isJsonPrimitive() ? o.get("baud").getAsInt() : 115200;
            Integer altBaud = o.has("altBaud") && o.get("altBaud").isJsonPrimitive()
                    ? o.get("altBaud").getAsInt()
                    : null;
            List<String> flashArgs = new ArrayList<>();
            if (o.has("flashArgs") && o.get("flashArgs").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("flashArgs")) {
                    flashArgs.add(el.getAsString());
                }
            }
            List<Part> parts = new ArrayList<>();
            if (o.has("parts") && o.get("parts").isJsonArray()) {
                for (JsonElement el : o.getAsJsonArray("parts")) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject p = el.getAsJsonObject();
                    String filename = text(p, "filename", null);
                    if (filename == null) {
                        continue;
                    }
                    parts.add(new Part(dir.resolve(filename), text(p, "offset", null), text(p, "sha256", null)));
                }
            } else {
                String filename = text(o, "filename", null);
                if (filename != null) {
                    parts.add(new Part(dir.resolve(filename), null, text(o, "sha256", null)));
                }
            }
            Path config = null;
            String configName = text(o, "config", null);
            if (configName != null && !configName.isBlank()) {
                config = dir.resolve(configName);
            }
            return new Image(id, version, tool, mcu, programmer, chip, baud, altBaud, flashArgs, parts, config, source);
        }

        public boolean filesPresent() {
            if (parts.isEmpty()) {
                return false;
            }
            for (Part p : parts) {
                if (p.file == null || !Files.isRegularFile(p.file)) {
                    return false;
                }
            }
            return true;
        }

        public void verifyHashes() throws IOException {
            if (parts.isEmpty()) {
                throw new IOException("Firmware image " + id + " has no files");
            }
            for (Part p : parts) {
                if (!Files.isRegularFile(p.file)) {
                    throw new IOException("Firmware file missing: " + p.file);
                }
                if (p.sha256 == null || p.sha256.isBlank()) {
                    throw new IOException("Firmware file has no sha256: " + p.file.getFileName());
                }
                String actual = sha256(p.file);
                if (!actual.equalsIgnoreCase(p.sha256.trim())) {
                    throw new IOException("SHA-256 mismatch for " + p.file.getFileName());
                }
            }
        }

        /** Flash size the image was built for, such as {@code 16MB}. Null when the image says detect. */
        public String requiredFlashSize() {
            for (int i = 0; i + 1 < flashArgs.size(); i++) {
                String arg = flashArgs.get(i);
                if ("--flash_size".equals(arg) || "--flash-size".equals(arg)) {
                    return normalizeFlashSize(flashArgs.get(i + 1));
                }
            }
            return null;
        }

        public List<String> uploadCommand(String avrdude, String esptool, String devicePath) {
            return uploadCommand(avrdude, esptool, devicePath, baud);
        }

        public List<String> uploadCommand(String avrdude, String esptool, String devicePath, int baudRate) {
            return uploadCommand(avrdude, esptool, devicePath, baudRate, "default_reset", "hard_reset");
        }

        public List<String> uploadCommand(
                String avrdude, String esptool, String devicePath, int baudRate, String beforeReset) {
            return uploadCommand(avrdude, esptool, devicePath, baudRate, beforeReset, "hard_reset");
        }

        public List<String> uploadCommand(
                String avrdude,
                String esptool,
                String devicePath,
                int baudRate,
                String beforeReset,
                String afterReset) {
            if ("esptool".equals(tool)) {
                List<String> cmd = new ArrayList<>();
                cmd.add(esptool);
                cmd.add("--chip");
                cmd.add(chip == null ? "esp32" : chip);
                cmd.add("--port");
                cmd.add(devicePath);
                cmd.add("--baud");
                cmd.add(Integer.toString(baudRate));
                cmd.add("--before");
                cmd.add(beforeReset == null || beforeReset.isBlank() ? "default_reset" : beforeReset);
                cmd.add("--after");
                cmd.add(afterReset == null || afterReset.isBlank() ? "hard_reset" : afterReset);
                cmd.add("write_flash");
                cmd.addAll(flashArgs);
                for (Part p : parts) {
                    cmd.add(p.offset == null ? "0x0" : p.offset);
                    cmd.add(p.file.toAbsolutePath().toString());
                }
                return cmd;
            }
            if (parts.isEmpty()) {
                throw new IllegalStateException("No hex for " + id);
            }
            Path hex = parts.get(0).file;
            List<String> cmd = new ArrayList<>();
            cmd.add(avrdude);
            cmd.add("-p");
            cmd.add(mcu == null ? "atmega328p" : mcu);
            cmd.add("-c");
            cmd.add(programmer == null ? "arduino" : programmer);
            cmd.add("-P");
            cmd.add(devicePath);
            cmd.add("-b");
            cmd.add(Integer.toString(baudRate));
            cmd.add("-D");
            cmd.add("-U");
            cmd.add("flash:w:" + hex.toAbsolutePath() + ":i");
            return Collections.unmodifiableList(cmd);
        }

        static String normalizeFlashSize(String raw) {
            if (raw == null) {
                return null;
            }
            String size = raw.replace(" ", "").trim().toUpperCase(Locale.ROOT);
            if (size.isEmpty() || "DETECT".equals(size) || "KEEP".equals(size)) {
                return null;
            }
            return size;
        }

        private static int versionOf(JsonElement v) {
            // Kept for older call sites; prefer ImageVersions.fromJsonValue.
            String s = ImageVersions.fromJsonValue(v);
            try {
                if (s.matches("\\d+")) {
                    return Integer.parseInt(s);
                }
                int[] p = new int[] {0, 0, 0};
                String[] bits = s.split("\\.");
                for (int i = 0; i < Math.min(3, bits.length); i++) {
                    p[i] = Integer.parseInt(bits[i]);
                }
                return p[0] * 1_000_000 + p[1] * 1_000 + p[2];
            } catch (RuntimeException e) {
                return 0;
            }
        }

        private static String text(JsonObject o, String key, String fallback) {
            if (!o.has(key) || o.get(key).isJsonNull()) {
                return fallback;
            }
            String s = o.get(key).getAsString();
            return s == null || s.isBlank() ? fallback : s;
        }
    }
}
