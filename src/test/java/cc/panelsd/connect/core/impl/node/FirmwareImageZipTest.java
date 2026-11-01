package cc.panelsd.connect.core.impl.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirmwareImageZipTest {

    @TempDir
    Path temp;

    @Test
    void nanoAvrRequiresAnUploadedZip() throws Exception {
        Path shipped = temp.resolve("shipped");
        Path cache = temp.resolve("cache");
        Files.createDirectories(shipped);
        Files.createDirectories(cache);
        Path shippedHex = shipped.resolve("nano-avr.hex");
        Files.writeString(shippedHex, ":shipped\n", StandardCharsets.US_ASCII);
        Files.writeString(shipped.resolve("manifest.json"), "{\n"
                + "  \"images\": [{\n"
                + "    \"id\": \"nano-avr\", \"version\": 9, \"tool\": \"avrdude\",\n"
                + "    \"filename\": \"nano-avr.hex\", \"sha256\": \""
                + FirmwareImageCatalog.sha256(shippedHex) + "\"\n"
                + "  }]\n"
                + "}\n", StandardCharsets.UTF_8);
        FirmwareImageCatalog before = FirmwareImageCatalog.load(shipped, cache);
        assertFalse(before.select("nano-avr").isPresent());

        byte[] zip = zipOf(shippedHex, "nano-avr.hex", ":uploaded\n");
        FirmwareImageZip.Installed installed = FirmwareImageZip.install(before, zip);
        FirmwareImageCatalog.Image nano = installed.catalog.select("nano-avr").orElseThrow();
        assertEquals("cache", nano.source);
        assertEquals("1000", nano.version);
        assertEquals(1, installed.ids.size());
        assertTrue(Files.readString(cache.resolve("nano-avr.hex")).contains("uploaded"));
    }

    @Test
    void rejectsAZipThatSlipsOutOfTheCache() {
        assertNull(FirmwareImageZip.safeName("../nano-avr.hex"));
        assertNull(FirmwareImageZip.safeName("/tmp/nano-avr.hex"));
        assertEquals("nano-avr.hex", FirmwareImageZip.safeName("folder/nano-avr.hex"));
    }

    @Test
    void rejectsAZipWithoutAManifest() {
        FirmwareImageCatalog catalog = FirmwareImageCatalog.load(temp.resolve("shipped"), temp.resolve("cache"));
        assertThrows(java.io.IOException.class, () -> FirmwareImageZip.install(catalog, new byte[] {1, 2, 3}));
    }

    private static byte[] zipOf(Path shippedHex, String name, String body) throws Exception {
        String sha = shaOf(body);
        String manifest = "{\n"
                + "  \"images\": [{\n"
                + "    \"id\": \"nano-avr\", \"version\": 1000, \"tool\": \"avrdude\",\n"
                + "    \"filename\": \"" + name + "\", \"mcu\": \"atmega328p\",\n"
                + "    \"programmer\": \"arduino\", \"baud\": 57600, \"altBaud\": 115200,\n"
                + "    \"sha256\": \"" + sha + "\"\n"
                + "  }]\n"
                + "}\n";
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(body.getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return raw.toByteArray();
    }

    private static String shaOf(String body) throws Exception {
        Path file = Files.createTempFile("nano-sha-", ".hex");
        try {
            Files.writeString(file, body, StandardCharsets.US_ASCII);
            return FirmwareImageCatalog.sha256(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
