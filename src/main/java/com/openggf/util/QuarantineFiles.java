package com.openggf.util;

import java.nio.file.Files;
import java.nio.file.Path;

public final class QuarantineFiles {
    private QuarantineFiles() {
    }

    public static Path uniqueCorruptSibling(Path file) {
        Path base = file.resolveSibling(file.getFileName() + ".corrupt");
        if (!Files.exists(base)) {
            return base;
        }
        for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
            Path candidate = file.resolveSibling(file.getFileName() + ".corrupt." + suffix);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No available quarantine filename for " + file);
    }
}
