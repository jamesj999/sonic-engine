package com.openggf.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executable acceptance gate for the paired Task 9 historical benchmark. */
public final class SmpsRepeatedPlaybackBenchmarkComparator {
    private static final String HEADER_PREFIX = "SMPS_BENCHMARK_HEADER ";
    private static final String SAMPLE_PREFIX = "SMPS_BENCHMARK_SAMPLE ";
    private static final String SUMMARY_PREFIX = "SMPS_BENCHMARK_SUMMARY ";
    private static final Pattern FIELD = Pattern.compile(
            "([A-Za-z][A-Za-z0-9]*)=(.*?)(?= [A-Za-z][A-Za-z0-9]*=|$)");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final List<String> MANIFEST_FILES = List.of(
            "src/test/java/com/openggf/audio/"
                    + "TestSmpsRepeatedPlaybackBenchmark.java");
    private static final List<String> SCENARIOS = List.of(
            "music-repeat",
            "sfx-program-tiny", "sfx-program-large",
            "sfx-dac-tiny", "sfx-dac-large",
            "sfx-unrelated-music-min", "sfx-unrelated-music-max");
    private static final int[] COUNTS = {64, 128, 256};

    private SmpsRepeatedPlaybackBenchmarkComparator() {
    }

    public static void main(String[] arguments) {
        if (arguments.length == 2
                && arguments[0].equals("--hash-manifest")) {
            try {
                System.out.println(manifestHash(Path.of(arguments[1])));
            } catch (IOException | RuntimeException failure) {
                System.err.println("SMPS_COMPARATOR_RESULT FAIL reason="
                        + stable(failure.getMessage()));
                System.exit(1);
            }
            return;
        }
        if (arguments.length != 4) {
            System.err.println("usage: <baseline-raw> <feature-raw> "
                    + "<baseline-manifest-root> "
                    + "<feature-manifest-root>");
            System.exit(2);
        }
        try {
            ComparisonResult result = compare(
                    Path.of(arguments[0]), Path.of(arguments[1]),
                    Path.of(arguments[2]), Path.of(arguments[3]));
            System.out.print(result.render());
        } catch (IOException | RuntimeException failure) {
            System.err.println("SMPS_COMPARATOR_RESULT FAIL reason="
                    + stable(failure.getMessage()));
            System.exit(1);
        }
    }

    public static ComparisonResult compare(
            Path baselineRaw,
            Path featureRaw,
            Path baselineManifestRoot,
            Path featureManifestRoot) throws IOException {
        String baselineManifestHash = manifestHash(baselineManifestRoot);
        String featureManifestHash = manifestHash(featureManifestRoot);
        require(baselineManifestHash.equals(featureManifestHash),
                "benchmark manifest hashes differ");

        RunData baseline = parse(baselineRaw);
        RunData feature = parse(featureRaw);
        validateRun("baseline", baseline, false);
        validateRun("feature", feature, true);
        require(baselineManifestHash.equals(
                        value(baseline.header(), "manifestSha256")),
                "baseline raw is not bound to its archived manifest");
        require(featureManifestHash.equals(
                        value(feature.header(), "manifestSha256")),
                "feature raw is not bound to its archived manifest");
        require(comparableEnvironment(baseline.header()).equals(
                        comparableEnvironment(feature.header())),
                "benchmark environment/header identity differs");

        long vmMargin = number(baseline.header(), "vmNoiseMargin");
        StringBuilder output = new StringBuilder();
        output.append("SMPS_COMPARATOR_MANIFEST sha256=")
                .append(baselineManifestHash)
                .append(" identity=PASS\n");
        output.append("SMPS_COMPARATOR_ENV identity=PASS header=")
                .append(stable(baseline.header().toString()))
                .append('\n');

        for (String scenario : SCENARIOS) {
            for (int operations : COUNTS) {
                Summary before = baseline.summary(scenario, operations);
                Summary after = feature.summary(scenario, operations);
                long tolerance = Math.max(
                        before.controlSpread(), after.controlSpread())
                        + vmMargin;
                require(after.medianBytesPerOp()
                                <= before.medianBytesPerOp() + tolerance,
                        scenario + "/" + operations
                                + " feature median allocation regressed");
                output.append("SMPS_COMPARATOR_FIXTURE scenario=")
                        .append(scenario)
                        .append(" operations=").append(operations)
                        .append(" baselineMedian=")
                        .append(before.medianBytesPerOp())
                        .append(" featureMedian=")
                        .append(after.medianBytesPerOp())
                        .append(" allocationDeltaPercent=")
                        .append(percentDelta(before.medianBytesPerOp(),
                                after.medianBytesPerOp()))
                        .append(" tolerance=").append(tolerance)
                        .append(" result=PASS\n");
            }
        }

        compareControl(output, baseline, feature, vmMargin,
                "program", "sfx-program-tiny", "sfx-program-large");
        compareControl(output, baseline, feature, vmMargin,
                "dac", "sfx-dac-tiny", "sfx-dac-large");
        compareControl(output, baseline, feature, vmMargin,
                "unrelated-music", "sfx-unrelated-music-min",
                "sfx-unrelated-music-max");
        output.append("SMPS_COMPARATOR_COUNTERS featureLoaderCalls=1 ")
                .append("featureProgramMaterializations=1 result=PASS\n");
        output.append("SMPS_COMPARATOR_RESULT PASS\n");
        return new ComparisonResult(output.toString());
    }

    private static void compareControl(
            StringBuilder output,
            RunData baseline,
            RunData feature,
            long vmMargin,
            String label,
            String smallScenario,
            String largeScenario) {
        Summary baselineSmall = baseline.summary(smallScenario, 256);
        Summary baselineLarge = baseline.summary(largeScenario, 256);
        Summary featureSmall = feature.summary(smallScenario, 256);
        Summary featureLarge = feature.summary(largeScenario, 256);
        long tolerance = Math.max(
                Math.max(baselineSmall.controlSpread(),
                        baselineLarge.controlSpread()),
                Math.max(featureSmall.controlSpread(),
                        featureLarge.controlSpread())) + vmMargin;
        long baselineSlope = baselineLarge.medianBytesPerOp()
                - baselineSmall.medianBytesPerOp();
        long featureSlope = featureLarge.medianBytesPerOp()
                - featureSmall.medianBytesPerOp();
        require(Math.abs(featureSlope) <= tolerance,
                label + " feature slope exceeds zero/control tolerance");
        require(baselineSlope - Math.abs(featureSlope) > tolerance,
                label + " feature slope is not materially below baseline");
        long largeImprovement = baselineLarge.medianBytesPerOp()
                - featureLarge.medianBytesPerOp();
        require(largeImprovement > tolerance,
                label + " large-case improvement is not material");
        output.append("SMPS_COMPARATOR_CONTROL control=")
                .append(label)
                .append(" baselineSlope=").append(baselineSlope)
                .append(" featureSlope=").append(featureSlope)
                .append(" largeImprovement=").append(largeImprovement)
                .append(" largeAllocationDeltaPercent=")
                .append(percentDelta(baselineLarge.medianBytesPerOp(),
                        featureLarge.medianBytesPerOp()))
                .append(" tolerance=").append(tolerance)
                .append(" result=PASS\n");
    }

    private static RunData parse(Path path) throws IOException {
        Map<String, String> header = null;
        Map<SampleKey, Sample> samples = new LinkedHashMap<>();
        Map<SummaryKey, Summary> summaries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            if (line.startsWith(HEADER_PREFIX)) {
                require(header == null, "duplicate benchmark header");
                header = fields(line.substring(HEADER_PREFIX.length()));
            } else if (line.startsWith(SAMPLE_PREFIX)) {
                Map<String, String> values = fields(
                        line.substring(SAMPLE_PREFIX.length()));
                Sample sample = new Sample(
                        value(values, "scenario"),
                        integer(values, "repetition"),
                        integer(values, "operations"),
                        number(values, "allocatedBytes"),
                        number(values, "elapsedNanos"),
                        number(values, "loaderCalls"),
                        number(values, "programMaterializations"),
                        number(values, "gcCountDelta"),
                        number(values, "gcTimeMillisDelta"),
                        integer(values, "liveVoices"),
                        integer(values, "driverSequencers"));
                SampleKey key = new SampleKey(
                        sample.scenario(), sample.repetition(),
                        sample.operations());
                require(samples.putIfAbsent(key, sample) == null,
                        "duplicate sample " + key);
            } else if (line.startsWith(SUMMARY_PREFIX)) {
                Map<String, String> values = fields(
                        line.substring(SUMMARY_PREFIX.length()));
                Summary summary = new Summary(
                        value(values, "scenario"),
                        integer(values, "operations"),
                        numbers(values, "bytesPerOp"),
                        number(values, "medianBytesPerOp"),
                        number(values, "controlSpread"),
                        numbers(values, "nanosPerOp"),
                        number(values, "medianNanosPerOp"),
                        numbers(values, "loaderCalls"),
                        numbers(values, "programMaterializations"));
                SummaryKey key = new SummaryKey(
                        summary.scenario(), summary.operations());
                require(summaries.putIfAbsent(key, summary) == null,
                        "duplicate summary " + key);
            }
        }
        require(header != null, "missing benchmark header");
        return new RunData(Map.copyOf(header), Map.copyOf(samples),
                Map.copyOf(summaries));
    }

    private static void validateRun(
            String label, RunData run, boolean feature) {
        require("3".equals(value(run.header(), "schema")),
                label + " benchmark schema is not 3");
        require(SHA_256.matcher(value(
                        run.header(), "manifestSha256")).matches(),
                label + " manifest SHA-256 header is invalid");
        require(value(run.header(), "java").startsWith("21."),
                label + " did not run on JDK 21");
        require("true".equals(value(
                        run.header(), "allocationSupported")),
                label + " allocation accounting unsupported");
        require("true".equals(value(
                        run.header(), "allocationEnabled")),
                label + " allocation accounting disabled");
        require(Arrays.equals(COUNTS,
                        ints(value(run.header(), "counts"))),
                label + " operation counts differ from manifest");
        int repetitions = Math.toIntExact(number(
                run.header(), "repetitions"));
        require(repetitions >= 5,
                label + " has fewer than five repetitions");
        require(number(run.header(), "wrapperWarmup") > 0,
                label + " did not warm the measurement wrapper");
        require(number(run.header(), "discardedScenarioRepetitions") > 0,
                label + " did not discard a full scenario repetition");
        validateVmArguments(label, run.header());
        require(run.samples().size()
                        == SCENARIOS.size() * COUNTS.length * repetitions,
                label + " sample cardinality mismatch");
        require(run.summaries().size()
                        == SCENARIOS.size() * COUNTS.length,
                label + " summary cardinality mismatch");

        for (String scenario : SCENARIOS) {
            int expectedSequencers = scenario.equals("music-repeat") ? 1 : 2;
            for (int operations : COUNTS) {
                long[] bytesPerOp = new long[repetitions];
                long[] nanosPerOp = new long[repetitions];
                long[] loaderCalls = new long[repetitions];
                long[] materializations = new long[repetitions];
                for (int repetition = 0;
                        repetition < repetitions; repetition++) {
                    Sample sample = run.samples().get(new SampleKey(
                            scenario, repetition, operations));
                    require(sample != null,
                            label + " missing sample " + scenario + "/"
                                    + repetition + "/" + operations);
                    require(sample.operations() == operations,
                            label + " sample operation mismatch");
                    require(sample.liveVoices() == 1,
                            label + " live voice topology changed");
                    require(sample.driverSequencers() == expectedSequencers,
                            label + " driver topology changed");
                    require(sample.gcCountDelta() >= 0
                                    && sample.gcTimeMillisDelta() >= 0,
                            label + " has negative GC delta");
                    if (feature) {
                        require(sample.loaderCalls() == 1,
                                "feature loader count is not exactly one");
                        require(sample.programMaterializations() == 1,
                                "feature materialization count is not exactly one");
                    }
                    bytesPerOp[repetition] =
                            sample.allocatedBytes() / operations;
                    nanosPerOp[repetition] =
                            sample.elapsedNanos() / operations;
                    loaderCalls[repetition] = sample.loaderCalls();
                    materializations[repetition] =
                            sample.programMaterializations();
                }
                Summary summary = run.summary(scenario, operations);
                require(Arrays.equals(bytesPerOp, summary.bytesPerOp()),
                        label + " printed byte samples do not match raw samples");
                require(median(bytesPerOp) == summary.medianBytesPerOp(),
                        label + " printed byte median is incorrect");
                require(spread(bytesPerOp) == summary.controlSpread(),
                        label + " printed control spread is incorrect");
                require(Arrays.equals(nanosPerOp, summary.nanosPerOp()),
                        label + " printed timing samples do not match raw samples");
                require(median(nanosPerOp) == summary.medianNanosPerOp(),
                        label + " printed timing median is incorrect");
                require(Arrays.equals(loaderCalls, summary.loaderCalls()),
                        label + " printed loader counts do not match raw samples");
                require(Arrays.equals(materializations,
                                summary.programMaterializations()),
                        label + " printed materializations do not match raw samples");
            }
        }
    }

    private static Map<String, String> comparableEnvironment(
            Map<String, String> header) {
        Map<String, String> comparable = new LinkedHashMap<>(header);
        comparable.remove("vmArgsRaw");
        return Map.copyOf(comparable);
    }

    static String manifestHash(Path manifestRoot) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        for (String relative : MANIFEST_FILES) {
            Path file = manifestRoot.resolve(relative);
            if (!Files.isRegularFile(file)) {
                throw new IOException("missing manifest file " + relative);
            }
            digest.update(Files.readAllBytes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String percentDelta(long baseline, long feature) {
        if (baseline == 0) {
            return feature == 0 ? "0.000%" : "unbounded";
        }
        return String.format(Locale.ROOT, "%.3f%%",
                100.0 * (feature - baseline) / baseline);
    }

    private static void validateVmArguments(
            String label, Map<String, String> header) {
        String raw = value(header, "vmArgsRaw");
        String normalized = raw.replaceAll(
                "-Djava\\.io\\.tmpdir=[^,]+/target/test-tmp",
                "-Djava.io.tmpdir=<WORKTREE>/target/test-tmp");
        require(normalized.equals(value(header, "vmArgs")),
                label + " normalized JVM arguments do not match raw input arguments");
    }

    private static Map<String, String> fields(String value) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = FIELD.matcher(value);
        int consumed = 0;
        while (matcher.find()) {
            require(matcher.start() == consumed,
                    "malformed benchmark field near "
                            + value.substring(consumed));
            require(result.putIfAbsent(
                    matcher.group(1), matcher.group(2)) == null,
                    "duplicate benchmark field " + matcher.group(1));
            consumed = matcher.end();
            if (consumed < value.length() && value.charAt(consumed) == ' ') {
                consumed++;
            }
        }
        require(consumed >= value.length(),
                "malformed benchmark fields " + value);
        return result;
    }

    private static String value(Map<String, String> values, String key) {
        String result = values.get(key);
        require(result != null, "missing benchmark field " + key);
        return result;
    }

    private static long number(Map<String, String> values, String key) {
        try {
            return Long.parseLong(value(values, key));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "invalid numeric benchmark field " + key, failure);
        }
    }

    private static int integer(Map<String, String> values, String key) {
        return Math.toIntExact(number(values, key));
    }

    private static long[] numbers(Map<String, String> values, String key) {
        String raw = value(values, key);
        require(raw.startsWith("[") && raw.endsWith("]"),
                "invalid list benchmark field " + key);
        String body = raw.substring(1, raw.length() - 1);
        if (body.isBlank()) {
            return new long[0];
        }
        String[] parts = body.split(", ", -1);
        long[] result = new long[parts.length];
        for (int index = 0; index < parts.length; index++) {
            try {
                result[index] = Long.parseLong(parts[index]);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        "invalid list benchmark field " + key, failure);
            }
        }
        return result;
    }

    private static int[] ints(String raw) {
        long[] values = numbers(Map.of("value", raw), "value");
        int[] result = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = Math.toIntExact(values[index]);
        }
        return result;
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long spread(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length - 1] - sorted[0];
    }

    private static String stable(String value) {
        return value == null ? "unknown"
                : value.replace(' ', '_').replace('\n', '_');
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public record ComparisonResult(String render) {
        public ComparisonResult {
            if (render == null || render.isBlank()) {
                throw new IllegalArgumentException(
                        "comparison output must not be blank");
            }
        }
    }

    private record SampleKey(
            String scenario, int repetition, int operations) {
    }

    private record SummaryKey(String scenario, int operations) {
    }

    private record Sample(
            String scenario,
            int repetition,
            int operations,
            long allocatedBytes,
            long elapsedNanos,
            long loaderCalls,
            long programMaterializations,
            long gcCountDelta,
            long gcTimeMillisDelta,
            int liveVoices,
            int driverSequencers) {
    }

    private record Summary(
            String scenario,
            int operations,
            long[] bytesPerOp,
            long medianBytesPerOp,
            long controlSpread,
            long[] nanosPerOp,
            long medianNanosPerOp,
            long[] loaderCalls,
            long[] programMaterializations) {
        private Summary {
            bytesPerOp = bytesPerOp.clone();
            nanosPerOp = nanosPerOp.clone();
            loaderCalls = loaderCalls.clone();
            programMaterializations = programMaterializations.clone();
        }

        @Override public long[] bytesPerOp() { return bytesPerOp.clone(); }
        @Override public long[] nanosPerOp() { return nanosPerOp.clone(); }
        @Override public long[] loaderCalls() { return loaderCalls.clone(); }
        @Override public long[] programMaterializations() {
            return programMaterializations.clone();
        }
    }

    private record RunData(
            Map<String, String> header,
            Map<SampleKey, Sample> samples,
            Map<SummaryKey, Summary> summaries) {
        private Summary summary(String scenario, int operations) {
            Summary result = summaries.get(
                    new SummaryKey(scenario, operations));
            require(result != null,
                    "missing summary " + scenario + "/" + operations);
            return result;
        }
    }
}
