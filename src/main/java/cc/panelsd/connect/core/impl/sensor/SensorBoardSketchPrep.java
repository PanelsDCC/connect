package cc.panelsd.connect.core.impl.sensor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Prepares a temp copy of the bundled sensor-board sketch with a chosen BOARD_PREFIX.
 */
public final class SensorBoardSketchPrep {

    private static final Pattern BOARD_PREFIX_LINE =
            Pattern.compile("^(\\s*const\\s+char\\s*\\*\\s*BOARD_PREFIX\\s*=\\s*\")([^\"]*)(\"\\s*;.*)$");

    private SensorBoardSketchPrep() {
    }

    /**
     * Copy sketch directory to {@code workDir} and rewrite {@code BOARD_PREFIX}.
     *
     * @return path to the .ino file in the work directory
     */
    public static Path prepare(Path sourceDir, Path workDir, String prefix) throws IOException {
        if (!SensorBoardSupport.isValidPrefix(prefix)) {
            throw new IllegalArgumentException("Invalid board prefix: " + prefix);
        }
        if (!Files.isDirectory(sourceDir)) {
            throw new IOException("Sketch source not found: " + sourceDir);
        }
        Files.createDirectories(workDir);
        Path ino = null;
        try (Stream<Path> stream = Files.list(sourceDir)) {
            for (Path p : stream.collect(Collectors.toList())) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Path dest = workDir.resolve(p.getFileName().toString());
                Files.copy(p, dest);
                if (p.getFileName().toString().endsWith(".ino")) {
                    ino = dest;
                }
            }
        }
        if (ino == null) {
            throw new IOException("No .ino file in " + sourceDir);
        }
        rewriteBoardPrefix(ino, prefix);
        return ino;
    }

    static void rewriteBoardPrefix(Path inoFile, String prefix) throws IOException {
        String content = Files.readString(inoFile, StandardCharsets.UTF_8);
        String[] lines = content.split("\\R", -1);
        boolean found = false;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = BOARD_PREFIX_LINE.matcher(line);
            if (m.matches()) {
                out.append(m.group(1)).append(prefix).append(m.group(3));
                found = true;
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        if (!found) {
            throw new IOException("BOARD_PREFIX declaration not found in " + inoFile);
        }
        Files.writeString(inoFile, out.toString(), StandardCharsets.UTF_8);
    }
}
