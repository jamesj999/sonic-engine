package uk.co.jamesj999.sonic.tests;

import uk.co.jamesj999.sonic.tools.SaxmanDecompressor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility runner to validate Saxman-decompressed output against the bundled SMPS rips.
 * Not wired into the normal test suite; run manually via:
 *   mvn -q -Dexec.mainClass=uk.co.jamesj999.sonic.tests.SaxmanFixtureValidator -Dexec.classpathScope=test exec:java
 * or run directly from your IDE.
 */
public final class SaxmanFixtureValidator {
    private final SaxmanDecompressor decompressor = new SaxmanDecompressor();

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("docs", "SMPS-rips", "Sonic The Hedgehog 2");
        Path compressedDir = root.resolve("Compressed_Data");

        List<Path> targets;
        if (args.length > 0) {
            targets = new ArrayList<>();
            for (String arg : args) {
                Path p = Paths.get(arg);
                if (Files.isDirectory(p)) {
                    targets.addAll(listSaxFiles(p));
                } else {
                    targets.add(p);
                }
            }
        } else {
            targets = listSaxFiles(compressedDir);
        }

        SaxmanFixtureValidator validator = new SaxmanFixtureValidator();
        List<String> failures = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Path sax : targets) {
            String base = stripExtension(sax.getFileName().toString());
            Path expected = resolveExpected(root, base);
            if (!Files.exists(expected)) {
                missing.add(base);
                continue;
            }

            try {
                ValidationResult result = validator.compare(sax, expected, base);
                if (!result.matches) {
                    failures.add(result.describe(base));
                }
            } catch (IOException e) {
                failures.add(base + " error: " + e.getMessage());
            }
        }

        if (!missing.isEmpty()) {
            System.out.println("Missing .sm2 for: " + String.join(", ", missing));
        }
        if (failures.isEmpty()) {
            System.out.println("All Saxman fixtures matched their .sm2 counterparts (" + targets.size() + ").");
        } else {
            System.out.println("Mismatches (" + failures.size() + "):");
            failures.forEach(s -> System.out.println(" - " + s));
            System.exit(1);
        }
    }

    /**
     * Some fixtures decompress to .bin instead of .sm2 (e.g. Z80 driver).
     */
    private static Path resolveExpected(Path root, String baseName) {
        Path sm2 = root.resolve(baseName + ".sm2");
        if (Files.exists(sm2)) {
            return sm2;
        }
        Path bin = root.resolve(baseName + ".bin");
        if (Files.exists(bin)) {
            return bin;
        }
        return sm2; // default (non-existent) to trigger "missing" reporting
    }

    private ValidationResult compare(Path sax, Path expected, String baseName) throws IOException {
        byte[] compressed = Files.readAllBytes(sax);
        byte[] reference = Files.readAllBytes(expected);
        byte[] actual;

        // The Z80 driver dump lacks the 2-byte size header; synthesize it.
        if ("Z80Drv_0925_Final".equals(baseName)) {
            actual = decompressor.decompressRaw(compressed, compressed.length);
        } else {
            actual = decompressor.decompress(compressed);
        }

        if (reference.length != actual.length) {
            return new ValidationResult(false, "length " + actual.length + " vs " + reference.length);
        }

        for (int i = 0; i < reference.length; i++) {
            if (reference[i] != actual[i]) {
                return new ValidationResult(false, "byte mismatch at " + i + " (ref=" + toHex(reference[i]) + ", got=" + toHex(actual[i]) + ")");
            }
        }
        return ValidationResult.OK;
    }

    private static List<Path> listSaxFiles(Path dir) throws IOException {
        return Files.list(dir)
                .filter(p -> p.getFileName().toString().endsWith(".sax"))
                .sorted(Comparator.comparing(Path::toString))
                .collect(Collectors.toList());
    }

    private static String stripExtension(String name) {
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(0, idx) : name;
    }

    private static String toHex(byte b) {
        return String.format("%02X", b);
    }

    private static final class ValidationResult {
        static final ValidationResult OK = new ValidationResult(true, "ok");
        final boolean matches;
        final String detail;

        ValidationResult(boolean matches, String detail) {
            this.matches = matches;
            this.detail = detail;
        }

        String describe(String name) {
            return name + ": " + detail;
        }
    }
}
