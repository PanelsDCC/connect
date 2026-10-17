package cc.panelsd.connect.core.impl.sensor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Prepares a temp copy of a bundled node sketch with BOARD_PREFIX / BOARD_TYPE.
 */
public final class SensorBoardSketchPrep {

    private static final Pattern BOARD_PREFIX_LINE =
            Pattern.compile("^(\\s*const\\s+char\\s*\\*\\s*BOARD_PREFIX\\s*=\\s*\")([^\"]*)(\"\\s*;.*)$");
    private static final Pattern BOARD_TYPE_LINE =
            Pattern.compile("^(\\s*const\\s+char\\s*\\*\\s*BOARD_TYPE\\s*=\\s*\")([^\"]*)(\"\\s*;.*)$");

    private SensorBoardSketchPrep() {
    }

    public static Path prepare(Path sourceDir, Path workDir, String prefix) throws IOException {
        return prepare(sourceDir, workDir, prefix, BoardProfile.NANO_AVR);
    }

    /**
     * Copy sketch directory to {@code workDir} and rewrite identity constants.
     *
     * @return path to the .ino file in the work directory
     */
    public static Path prepare(Path sourceDir, Path workDir, String prefix, String boardType) throws IOException {
        if (!SensorBoardSupport.isValidPrefix(prefix)) {
            throw new IllegalArgumentException("Invalid board prefix: " + prefix);
        }
        String type = BoardProfile.normalize(boardType);
        if (!Files.isDirectory(sourceDir)) {
            throw new IOException("Sketch source not found: " + sourceDir);
        }
        Files.createDirectories(workDir);
        Path ino = copyRegularFiles(sourceDir, workDir);
        // Shared CFG/engines live in firmware/common next to board sketch dirs
        Path common = sourceDir.getParent() != null ? sourceDir.getParent().resolve("common") : null;
        if (common != null && Files.isDirectory(common)) {
            copyRegularFiles(common, workDir);
        }
        if (ino == null) {
            throw new IOException("No .ino file in " + sourceDir);
        }
        rewriteIdentity(ino, prefix, type);
        return ino;
    }

    /** Copy regular files from {@code fromDir} into {@code workDir}; returns .ino if found. */
    private static Path copyRegularFiles(Path fromDir, Path workDir) throws IOException {
        Path ino = null;
        try (Stream<Path> stream = Files.list(fromDir)) {
            for (Path p : stream.collect(Collectors.toList())) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                Path dest = workDir.resolve(p.getFileName().toString());
                Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                if (p.getFileName().toString().endsWith(".ino")) {
                    ino = dest;
                }
            }
        }
        return ino;
    }

    static void rewriteBoardPrefix(Path inoFile, String prefix) throws IOException {
        rewriteIdentity(inoFile, prefix, null);
    }

    static void rewriteIdentity(Path inoFile, String prefix, String boardType) throws IOException {
        String content = Files.readString(inoFile, StandardCharsets.UTF_8);
        String[] lines = content.split("\\R", -1);
        boolean foundPrefix = false;
        boolean foundType = boardType == null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m = BOARD_PREFIX_LINE.matcher(line);
            if (m.matches()) {
                out.append(m.group(1)).append(prefix).append(m.group(3));
                foundPrefix = true;
            } else if (boardType != null) {
                Matcher t = BOARD_TYPE_LINE.matcher(line);
                if (t.matches()) {
                    out.append(t.group(1)).append(boardType).append(t.group(3));
                    foundType = true;
                } else {
                    out.append(line);
                }
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        if (!foundPrefix) {
            throw new IOException("BOARD_PREFIX declaration not found in " + inoFile);
        }
        if (boardType != null && !foundType) {
            // Optional on older Nano sketches — inject after BOARD_PREFIX
            String injected = content.contains("BOARD_TYPE")
                    ? out.toString()
                    : injectBoardType(out.toString(), boardType);
            Files.writeString(inoFile, injected, StandardCharsets.UTF_8);
            return;
        }
        Files.writeString(inoFile, out.toString(), StandardCharsets.UTF_8);
    }

    private static String injectBoardType(String content, String boardType) {
        return content.replaceFirst(
                "(const\\s+char\\s*\\*\\s*BOARD_PREFIX\\s*=\\s*\"[^\"]*\"\\s*;)",
                "$1\nconst char* BOARD_TYPE = \"" + boardType + "\";");
    }
}
