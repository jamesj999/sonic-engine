package com.openggf.tools;

import com.openggf.bench.BenchmarkComparison;
import com.openggf.bench.BenchmarkReport;
import com.openggf.bench.BenchmarkReportIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads benchmark report JSON files produced by {@link TraceBenchmarkTool} and
 * renders a Markdown comparison.
 *
 * <p>The first file listed is the baseline every percentage is quoted against,
 * so put the runtime you currently ship on first.
 *
 * <pre>
 *   mvn exec:java "-Dexec.mainClass=com.openggf.tools.BenchmarkCompareTool" \
 *       "-Dexec.args=--out docs/architecture/audits/2026-07-27-jvm-benchmark.md \
 *                    target/bench/temurin21-g1.json target/bench/graal21.json"
 * </pre>
 *
 * <p>Runs this tool separately from the benchmark itself: comparison is pure
 * post-processing over the JSON, so it can run under any JVM without affecting
 * what is being compared.
 */
public final class BenchmarkCompareTool {

    private BenchmarkCompareTool() {
    }

    public static void main(String[] argv) {
        Path out = null;
        List<Path> inputs = new ArrayList<>();
        try {
            for (int i = 0; i < argv.length; i++) {
                if ("--out".equals(argv[i])) {
                    if (++i >= argv.length) {
                        throw new IllegalArgumentException("Missing value for --out");
                    }
                    out = Paths.get(argv[i]);
                } else if (argv[i].startsWith("--")) {
                    throw new IllegalArgumentException("Unknown argument: " + argv[i]);
                } else {
                    inputs.add(Paths.get(argv[i]));
                }
            }
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException(
                        "At least one report JSON is required; the first is the baseline");
            }

            List<BenchmarkReport> reports = new ArrayList<>(inputs.size());
            for (Path input : inputs) {
                reports.add(BenchmarkReportIo.read(input));
            }

            String markdown = BenchmarkComparison.render(reports);
            if (out != null) {
                writeMarkdownText(markdown, out);
                System.out.println("Wrote comparison -> " + out.toAbsolutePath());
            } else {
                System.out.print(markdown);
            }
        } catch (Exception e) {
            System.err.println("Benchmark comparison failed: " + e.getMessage());
            System.exit(1);
        }
    }

    /** Renders and writes a comparison for the given reports. */
    static void writeMarkdown(List<BenchmarkReport> reports, Path destination) throws IOException {
        writeMarkdownText(BenchmarkComparison.render(reports), destination);
    }

    private static void writeMarkdownText(String markdown, Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(destination, markdown, StandardCharsets.UTF_8);
    }
}
