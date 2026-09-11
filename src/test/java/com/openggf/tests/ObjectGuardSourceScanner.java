package com.openggf.tests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class ObjectGuardSourceScanner {
    public static final String[] GAME_OBJECT_PACKAGE_PATHS = {
            "com/openggf/game/sonic1/objects",
            "com/openggf/game/sonic2/objects",
            "com/openggf/game/sonic3k/objects",
    };

    public static final String[] OBJECT_PACKAGE_PATHS = {
            "com/openggf/game/sonic1/objects",
            "com/openggf/game/sonic2/objects",
            "com/openggf/game/sonic3k/objects",
            "com/openggf/level/objects",
    };

    private ObjectGuardSourceScanner() {
    }

    public static Path findSourceRoot() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path srcMain = cwd.resolve("src/main/java");
        if (Files.isDirectory(srcMain)) {
            return srcMain;
        }
        Path parent = cwd.getParent();
        if (parent == null) {
            return null;
        }
        srcMain = parent.resolve("src/main/java");
        return Files.isDirectory(srcMain) ? srcMain : null;
    }

    public static List<Path> javaFilesUnderPackages(Path srcMain, String[] packages) throws IOException {
        List<Path> result = new ArrayList<>();
        for (String pkg : packages) {
            Path pkgDir = srcMain.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(pkgDir)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(result::add);
            }
        }
        return result;
    }

    public static String className(Path srcMain, Path sourceFile) {
        return srcMain.relativize(sourceFile).toString()
                .replace('\\', '/').replace(".java", "").replace('/', '.');
    }

    public static SourceText sourceWithoutCommentOnlyLines(List<String> lines) {
        StringBuilder source = new StringBuilder();
        List<Integer> lineByOffset = new ArrayList<>();
        List<String> sourceLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            String scannedLine = (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
                    ? ""
                    : line;
            sourceLines.add(scannedLine);
            source.append(scannedLine);
            for (int j = 0; j < scannedLine.length(); j++) {
                lineByOffset.add(i + 1);
            }
            source.append('\n');
            lineByOffset.add(i + 1);
        }
        return new SourceText(source.toString(), lineByOffset, sourceLines);
    }

    public record SourceText(String text, List<Integer> lineByOffset, List<String> lines) {
        public int lineAt(int offset) {
            if (lineByOffset.isEmpty()) {
                return 1;
            }
            return lineByOffset.get(Math.min(offset, lineByOffset.size() - 1));
        }

        public String lineTextAt(int offset) {
            int lineNumber = lineAt(offset);
            if (lineNumber < 1 || lineNumber > lines.size()) {
                return "";
            }
            return lines.get(lineNumber - 1);
        }
    }
}
