package cc.panelsd.connect.core.impl.sensor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensorBoardSketchPrepTest {

    @TempDir
    Path temp;

    @Test
    void rewritesBoardPrefixInTempCopy() throws Exception {
        Path src = temp.resolve("src");
        Files.createDirectories(src);
        Path ino = src.resolve("panelsdcc-sensor-board.ino");
        Files.writeString(ino,
                "const char* BOARD_PREFIX = \"A\";\nvoid setup() {}\n",
                StandardCharsets.UTF_8);
        Path work = temp.resolve("panelsdcc-sensor-board");
        Path out = SensorBoardSketchPrep.prepare(src, work, "AB");
        assertEquals("panelsdcc-sensor-board.ino", out.getFileName().toString());
        assertEquals("panelsdcc-sensor-board", out.getParent().getFileName().toString());
        String content = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(content.contains("const char* BOARD_PREFIX = \"AB\";"));
        assertFalse(content.contains("BOARD_PREFIX = \"A\";"));
    }
}
