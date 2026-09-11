package com.openggf.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TestSmpsRepeatedPlaybackBenchmarkComparator {
    private static final String MANIFEST_RELATIVE =
            "src/test/java/com/openggf/audio/"
                    + "TestSmpsRepeatedPlaybackBenchmark.java";
    private static final String[] SCENARIOS = {
            "music-repeat",
            "sfx-program-tiny", "sfx-program-large",
            "sfx-dac-tiny", "sfx-dac-large",
            "sfx-unrelated-music-min", "sfx-unrelated-music-max"
    };
    private static final int[] COUNTS = {64, 128, 256};
    private static final int REPETITIONS = 5;

    @TempDir
    Path temp;
    private Path baselineManifest;
    private Path featureManifest;

    @BeforeEach
    void createIdenticalManifestArchives() throws IOException {
        baselineManifest = manifest("baseline-manifest", "manifest-v1");
        featureManifest = manifest("feature-manifest", "manifest-v1");
    }

    @Test
    void acceptsOnlyACompletePassingPairedComparison() throws IOException {
        Path baseline = write("baseline.txt", run(false, medians(
                1000, 100, 1000, 100, 1100, 100, 400)));
        Path feature = write("feature.txt", run(true, medians(
                900, 80, 80, 80, 80, 80, 80)));

        var result = SmpsRepeatedPlaybackBenchmarkComparator.compare(
                baseline, feature, baselineManifest, featureManifest);

        assertTrue(result.render().contains("SMPS_COMPARATOR_RESULT PASS"));
        assertTrue(result.render().contains("control=program"));
        assertTrue(result.render().contains("control=dac"));
        assertTrue(result.render().contains("control=unrelated-music"));
    }

    @Test
    void rejectsEnvironmentIdentityMismatch() throws IOException {
        Path baseline = passingBaseline();
        String mismatched = passingFeatureText().replace(
                "vmArgs=-Dpair=stable", "vmArgs=-Dpair=different")
                .replace("vmArgsRaw=-Dpair=stable",
                        "vmArgsRaw=-Dpair=different");
        Path feature = write("feature.txt", mismatched);

        assertThrows(IllegalArgumentException.class,
                () -> SmpsRepeatedPlaybackBenchmarkComparator.compare(
                        baseline, feature, baselineManifest, featureManifest));
    }

    @Test
    void acceptsDifferentRawCheckoutPathsWithIdenticalEffectiveVmArgs()
            throws IOException {
        Path baseline = passingBaseline();
        Path feature = write("feature.txt", passingFeatureText().replace(
                "/baseline/target/test-tmp",
                "/feature/target/test-tmp"));

        var result = SmpsRepeatedPlaybackBenchmarkComparator.compare(
                baseline, feature, baselineManifest, featureManifest);

        assertTrue(result.render().contains("SMPS_COMPARATOR_ENV identity=PASS"));
    }

    @Test
    void rejectsRawVmArgsThatDoNotProduceTheDeclaredNormalizedArgs()
            throws IOException {
        Path baseline = passingBaseline();
        Path feature = write("feature.txt", passingFeatureText().replace(
                "vmArgsRaw=-Dpair=stable,",
                "vmArgsRaw=-Dpair=different,"));

        assertThrows(IllegalArgumentException.class,
                () -> SmpsRepeatedPlaybackBenchmarkComparator.compare(
                        baseline, feature, baselineManifest, featureManifest));
    }

    @Test
    void rejectsMissingOperationCountSample() throws IOException {
        Path baseline = passingBaseline();
        String missing = passingFeatureText().replaceFirst(
                "(?m)^SMPS_BENCHMARK_SAMPLE scenario=music-repeat "
                        + "repetition=0 operations=128.*\\R", "");
        Path feature = write("feature.txt", missing);

        assertThrows(IllegalArgumentException.class,
                () -> SmpsRepeatedPlaybackBenchmarkComparator.compare(
                        baseline, feature, baselineManifest, featureManifest));
    }

    @Test
    void rejectsFeatureLoaderOrMaterializationAboveOne() throws IOException {
        Path baseline = passingBaseline();
        String repeatedLoad = passingFeatureText().replaceFirst(
                "loaderCalls=1 programMaterializations=1",
                "loaderCalls=2 programMaterializations=1");
        Path feature = write("feature.txt", repeatedLoad);

        assertThrows(IllegalArgumentException.class,
                () -> SmpsRepeatedPlaybackBenchmarkComparator.compare(
                        baseline, feature, baselineManifest, featureManifest));
    }

    @Test
    void rejectsAnyFixtureMedianRegressionBeyondPairedTolerance()
            throws IOException {
        Path baseline = passingBaseline();
        Map<String, Long> regressed = medians(
                1200, 80, 80, 80, 80, 80, 80);
        Path feature = write("feature.txt", run(true, regressed));

        assertThrows(IllegalArgumentException.class,
                () -> SmpsRepeatedPlaybackBenchmarkComparator.compare(
                        baseline, feature, baselineManifest, featureManifest));
    }

    @Test
    void rejectsNonzeroTargetedFeatureSlope() throws IOException {
        Path baseline = passingBaseline();
        Map<String, Long> sloped = medians(
                900, 80, 300, 80, 80, 80, 80);
        Path feature = write("feature.txt", run(true, sloped));

        assertThrows(IllegalArgumentException.class,
                () -> SmpsRepeatedPlaybackBenchmarkComparator.compare(
                        baseline, feature, baselineManifest, featureManifest));
    }

    @Test
    void rejectsOneByteManifestMutation() throws IOException {
        featureManifest = manifest("feature-mutated", "manifest-v2");
        assertThrows(IllegalArgumentException.class,
                () -> SmpsRepeatedPlaybackBenchmarkComparator.compare(
                        passingBaseline(),
                        write("feature.txt", passingFeatureText()),
                        baselineManifest, featureManifest));
    }

    @Test
    void rejectsArbitraryEqualRawHeaderHashes() throws IOException {
        String arbitrary = "a".repeat(64);
        Path baseline = write("baseline.txt",
                passingBaselineText(arbitrary));
        Path feature = write("feature.txt",
                passingFeatureText(arbitrary));

        assertThrows(IllegalArgumentException.class,
                () -> SmpsRepeatedPlaybackBenchmarkComparator.compare(
                        baseline, feature,
                        baselineManifest, featureManifest));
    }

    @Test
    void rejectsMissingManifestFile() throws IOException {
        Files.delete(featureManifest.resolve(MANIFEST_RELATIVE));
        assertThrows(IOException.class,
                () -> SmpsRepeatedPlaybackBenchmarkComparator.compare(
                        passingBaseline(),
                        write("feature.txt", passingFeatureText()),
                        baselineManifest, featureManifest));
    }

    @Test
    void executedBenchmarkIgnoresCallerManifestHashSpoof() throws IOException {
        String spoof = "f".repeat(64);
        String previous = System.setProperty(
                "openggf.audio.benchmark.manifestSha256", spoof);
        try {
            String computed = TestSmpsRepeatedPlaybackBenchmark
                    .executedManifestHash();
            assertNotEquals(spoof, computed);
            assertEquals(SmpsRepeatedPlaybackBenchmarkComparator.manifestHash(
                    Path.of(System.getProperty("project.basedir"))), computed);
        } finally {
            if (previous == null) {
                System.clearProperty(
                        "openggf.audio.benchmark.manifestSha256");
            } else {
                System.setProperty(
                        "openggf.audio.benchmark.manifestSha256", previous);
            }
        }
    }

    @Test
    void executedSourceOneByteMutationChangesComputedDigest() throws Exception {
        Path root = temp.resolve("executed-worktree");
        Path classes = root.resolve("target/test-classes");
        Path source = root.resolve(MANIFEST_RELATIVE);
        Path anchorSource = temp.resolve("Anchor.java");
        Files.createDirectories(classes);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "a");
        Files.writeString(anchorSource, "package probe; public class Anchor {}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(),
                anchorSource.toString()));

        try (URLClassLoader loader = new URLClassLoader(
                new java.net.URL[]{classes.toUri().toURL()}, null)) {
            Class<?> anchor = loader.loadClass("probe.Anchor");
            String before = TestSmpsRepeatedPlaybackBenchmark
                    .manifestHashForCodeSource(anchor);
            Files.writeString(source, "b");
            String after = TestSmpsRepeatedPlaybackBenchmark
                    .manifestHashForCodeSource(anchor);
            assertNotEquals(before, after);
        }
    }

    private Path passingBaseline() throws IOException {
        return write("baseline.txt", passingBaselineText(manifestHash()));
    }

    private String passingBaselineText(String manifestHash) {
        return run(false, medians(
                1000, 100, 1000, 100, 1100, 100, 400), manifestHash);
    }

    private String passingFeatureText() {
        return passingFeatureText(manifestHash());
    }

    private String passingFeatureText(String manifestHash) {
        return run(true, medians(900, 80, 80, 80, 80, 80, 80),
                manifestHash);
    }

    private String manifestHash() {
        try {
            return SmpsRepeatedPlaybackBenchmarkComparator.manifestHash(
                    baselineManifest);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private Path manifest(String directory, String contents)
            throws IOException {
        Path root = temp.resolve(directory);
        Path file = root.resolve(MANIFEST_RELATIVE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return root;
    }

    private Path write(String name, String value) throws IOException {
        Path path = temp.resolve(name);
        Files.writeString(path, value);
        return path;
    }

    private static Map<String, Long> medians(long... values) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (int index = 0; index < SCENARIOS.length; index++) {
            result.put(SCENARIOS[index], values[index]);
        }
        return result;
    }

    private String run(
            boolean feature, Map<String, Long> medians) {
        return run(feature, medians, manifestHash());
    }

    private static String run(
            boolean feature, Map<String, Long> medians,
            String manifestHash) {
        StringBuilder raw = new StringBuilder();
        raw.append("SMPS_BENCHMARK_HEADER schema=3 manifestSha256=")
                .append(manifestHash).append(" java=21.0.11+10 ")
                .append("vm=OpenJDK_64-Bit_Server_VM vmVendor=Debian ")
                .append("vmVersion=21.0.11+10 os=Linux arch=amd64 ")
                .append("vmArgs=-Dpair=stable,"
                        + "-Djava.io.tmpdir=<WORKTREE>/target/test-tmp ")
                .append("vmArgsRaw=-Dpair=stable,"
                        + "-Djava.io.tmpdir=/baseline/target/test-tmp ")
                .append("forkCount=1 forkNumber=1 ")
                .append("reuseForks=true allocationSupported=true ")
                .append("allocationEnabled=true warmup=64 ")
                .append("wrapperWarmup=10000 ")
                .append("discardedScenarioRepetitions=1 ")
                .append("counts=[64, 128, 256] ")
                .append("repetitions=5 tinyProgram=64 largeProgram=1048576 ")
                .append("tinyDac=64 largeDac=4194304 ")
                .append("simpleMusicTracks=1 maxMusicTracks=10 ")
                .append("vmNoiseMargin=128\n");
        for (String scenario : SCENARIOS) {
            long bytesPerOperation = medians.get(scenario);
            int sequencers = scenario.equals("music-repeat") ? 1 : 2;
            for (int repetition = 0;
                    repetition < REPETITIONS; repetition++) {
                for (int operations : COUNTS) {
                    raw.append("SMPS_BENCHMARK_SAMPLE scenario=")
                            .append(scenario)
                            .append(" repetition=").append(repetition)
                            .append(" operations=").append(operations)
                            .append(" allocatedBytes=")
                            .append(bytesPerOperation * operations)
                            .append(" elapsedNanos=").append(operations * 10L)
                            .append(" loaderCalls=")
                            .append(feature ? 1 : operations * 4L)
                            .append(" programMaterializations=")
                            .append(feature ? 1 : operations * 2L)
                            .append(" gcCountDelta=0 gcTimeMillisDelta=0 ")
                            .append("liveVoices=1 driverSequencers=")
                            .append(sequencers).append('\n');
                }
            }
            for (int operations : COUNTS) {
                raw.append("SMPS_BENCHMARK_SUMMARY scenario=")
                        .append(scenario)
                        .append(" operations=").append(operations)
                        .append(" bytesPerOp=[")
                        .append(bytesPerOperation).append(", ")
                        .append(bytesPerOperation).append(", ")
                        .append(bytesPerOperation).append(", ")
                        .append(bytesPerOperation).append(", ")
                        .append(bytesPerOperation).append(']')
                        .append(" medianBytesPerOp=")
                        .append(bytesPerOperation)
                        .append(" controlSpread=0 nanosPerOp=[10, 10, 10, 10, 10]")
                        .append(" medianNanosPerOp=10 loaderCalls=[")
                        .append(feature ? "1, 1, 1, 1, 1"
                                : (operations * 4L + ", ").repeat(4)
                                + operations * 4L)
                        .append("] programMaterializations=[")
                        .append(feature ? "1, 1, 1, 1, 1"
                                : (operations * 2L + ", ").repeat(4)
                                + operations * 2L)
                        .append("]\n");
            }
        }
        return raw.toString();
    }
}
