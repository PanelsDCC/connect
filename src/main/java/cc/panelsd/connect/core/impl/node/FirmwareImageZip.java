package cc.panelsd.connect.core.impl.node;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs a Nano builder zip (manifest plus image files) into the firmware cache.
 */
public final class FirmwareImageZip {

    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Installed {
        public final FirmwareImageCatalog catalog;
        public final List<String> ids;

        Installed(FirmwareImageCatalog catalog, List<String> ids) {
            this.catalog = catalog;
            this.ids = ids;
        }
    }

    private FirmwareImageZip() {
    }

    public static Installed install(FirmwareImageCatalog current, byte[] zipBytes) throws IOException {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IOException("Upload is empty");
        }
        if (zipBytes.length > MAX_BYTES) {
            throw new IOException("Upload is larger than 8MB");
        }
        Map<String, byte[]> files = unzip(zipBytes);
        byte[] manifestBytes = files.get("manifest.json");
        if (manifestBytes == null) {
            throw new IOException("Zip has no manifest.json");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Zip manifest is not JSON");
        }
        if (!root.has("images") || !root.get("images").isJsonArray() || root.getAsJsonArray("images").size() == 0) {
            throw new IOException("Zip manifest has no images");
        }
        Path cache = current.cacheDir();
        Files.createDirectories(cache);
        List<String> ids = new ArrayList<>();
        JsonArray incoming = root.getAsJsonArray("images");
        for (JsonElement el : incoming) {
            if (!el.isJsonObject()) {
                throw new IOException("Zip manifest image is not an object");
            }
            JsonObject image = el.getAsJsonObject();
            if (!image.has("id") || image.get("id").isJsonNull()) {
                throw new IOException("Zip manifest image has no id");
            }
            String id = image.get("id").getAsString();
            ids.add(id);
            for (String filename : referencedFiles(image)) {
                byte[] data = files.get(filename);
                if (data == null) {
                    throw new IOException("Zip is missing " + filename);
                }
                String expect = shaOf(image, filename);
                if (expect != null) {
                    Path temp = Files.createTempFile(cache, "sha-", ".bin");
                    try {
                        Files.write(temp, data);
                        String actual = FirmwareImageCatalog.sha256(temp);
                        if (!actual.equalsIgnoreCase(expect)) {
                            throw new IOException("SHA-256 mismatch for " + filename);
                        }
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                }
                Files.write(cache.resolve(filename), data);
            }
        }
        mergeManifest(cache, incoming);
        return new Installed(FirmwareImageCatalog.load(current.shippedDir(), cache), ids);
    }

    private static Map<String, byte[]> unzip(byte[] zipBytes) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        int total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = safeName(entry.getName());
                if (name == null) {
                    throw new IOException("Zip entry name is not allowed");
                }
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IOException("Zip expands past 8MB");
                    }
                    buf.write(chunk, 0, n);
                }
                files.put(name, buf.toByteArray());
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Zip is not readable");
        }
        return files;
    }

    static String safeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.replace('\\', '/');
        if (name.startsWith("/") || name.contains("..")) {
            return null;
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (!name.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        return name;
    }

    private static List<String> referencedFiles(JsonObject image) throws IOException {
        List<String> names = new ArrayList<>();
        if (image.has("parts") && image.get("parts").isJsonArray()) {
            for (JsonElement el : image.getAsJsonArray("parts")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                String filename = text(el.getAsJsonObject(), "filename");
                if (filename == null) {
                    throw new IOException("Image part has no filename");
                }
                names.add(filename);
            }
        } else {
            String filename = text(image, "filename");
            if (filename == null) {
                throw new IOException("Image has no filename");
            }
            names.add(filename);
        }
        String config = text(image, "config");
        if (config != null) {
            names.add(config);
        }
        for (String name : names) {
            if (safeName(name) == null || !name.equals(safeName(name))) {
                throw new IOException("Image filename is not allowed");
            }
        }
        return names;
    }

    private static String shaOf(JsonObject image, String filename) {
        if (image.has("parts") && image.get("parts").isJsonArray()) {
            for (JsonElement el : image.getAsJsonArray("parts")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject part = el.getAsJsonObject();
                if (filename.equals(text(part, "filename"))) {
                    return text(part, "sha256");
                }
            }
            return null;
        }
        if (filename.equals(text(image, "filename"))) {
            return text(image, "sha256");
        }
        return null;
    }

    private static void mergeManifest(Path cache, JsonArray incoming) throws IOException {
        Path manifest = cache.resolve("manifest.json");
        JsonArray images = new JsonArray();
        if (Files.isRegularFile(manifest)) {
            try {
                JsonObject existing = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
                if (existing.has("images") && existing.get("images").isJsonArray()) {
                    images = existing.getAsJsonArray("images");
                }
            } catch (RuntimeException ignored) {
                images = new JsonArray();
            }
        }
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonElement el : images) {
            if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                byId.put(el.getAsJsonObject().get("id").getAsString().toLowerCase(Locale.ROOT), el.getAsJsonObject());
            }
        }
        for (JsonElement el : incoming) {
            JsonObject image = el.getAsJsonObject();
            byId.put(image.get("id").getAsString().toLowerCase(Locale.ROOT), image);
        }
        JsonArray merged = new JsonArray();
        for (JsonObject image : byId.values()) {
            merged.add(image);
        }
        JsonObject root = new JsonObject();
        root.add("images", merged);
        Files.writeString(manifest, GSON.toJson(root) + "\n");
    }

    private static String text(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) {
            return null;
        }
        String value = obj.get(key).getAsString();
        return value == null || value.isBlank() ? null : value;
    }
}
