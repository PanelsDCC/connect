package cc.panelsd.connect.core.impl.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeSketchPrepTest {

    @TempDir
    Path temp;

    @Test
    void rewritesBoardPrefixInTempCopy() throws Exception {
        Path src = temp.resolve("src");
        Files.createDirectories(src);
        Path ino = src.resolve("nano-avr.ino");
        Files.writeString(ino,
                "const char* BOARD_PREFIX = \"A\";\nvoid setup() {}\n",
                StandardCharsets.UTF_8);
        Path work = temp.resolve("panelsdcc-node");
        Path out = NodeSketchPrep.prepare(src, work, "AB", BoardProfile.MEGA_AVR);
        assertEquals("panelsdcc-node.ino", out.getFileName().toString());
        assertEquals("panelsdcc-node", out.getParent().getFileName().toString());
        String content = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(content.contains("const char* BOARD_PREFIX = \"AB\";"));
        assertFalse(content.contains("BOARD_PREFIX = \"A\";"));
        assertTrue(content.contains("BOARD_TYPE = \"mega-avr\""));
    }

    @Test
    void copiesCommonFirmwareIntoWorkDir() throws Exception {
        Path firmware = temp.resolve("firmware");
        Path src = firmware.resolve("panelsdcc-node-mega");
        Path common = firmware.resolve("common");
        Files.createDirectories(src);
        Files.createDirectories(common);
        Files.writeString(src.resolve("mega-avr.ino"),
                "const char* BOARD_PREFIX = \"A\";\nconst char* BOARD_TYPE = \"mega-avr\";\n",
                StandardCharsets.UTF_8);
        Files.writeString(common.resolve("NodeEngine.h"), "#pragma once\n", StandardCharsets.UTF_8);
        Files.writeString(common.resolve("NodeEngine.cpp"), "// engine\n", StandardCharsets.UTF_8);
        Path work = temp.resolve("work");
        NodeSketchPrep.prepare(src, work, "C", BoardProfile.MEGA_AVR);
        assertTrue(Files.isRegularFile(work.resolve("NodeEngine.h")));
        assertTrue(Files.isRegularFile(work.resolve("NodeEngine.cpp")));
        assertTrue(Files.readString(work.resolve("mega-avr.ino"), StandardCharsets.UTF_8)
                .contains("BOARD_PREFIX = \"C\""));
    }
}
