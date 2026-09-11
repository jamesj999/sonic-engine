package com.openggf.audio;

import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.data.Rom;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in, byte-identical historical benchmark manifest for repeated public
 * music and SFX playback.
 *
 * <p>The measured caller uses only {@link AudioManager#playMusic(int)},
 * {@link AudioManager#playSfx(int)}, and
 * {@link AudioManager#presentFrame(PresentationMode)}, all of which exist on
 * the binding updated-develop baseline and the feature commit.
 * Every loader call returns a fresh instrumented program. Its primitive
 * materialization counter advances once on the first program-data access, so
 * loader work and actual defensive-copy/hash work remain distinct without a
 * feature-only API.</p>
 *
 * <p>Enable with {@code -Dopenggf.audio.repeatedPlaybackBenchmark=true}. An
 * enabled run is evidence, not an ordinary budget test: JDK 21 and enabled
 * {@link ThreadMXBean} allocation accounting are mandatory. Timing is emitted
 * for description only; paired allocation acceptance is evaluated from the
 * archived raw lines after both commits run this exact manifest.</p>
 */
@EnabledIfSystemProperty(
        named = "openggf.audio.repeatedPlaybackBenchmark", matches = "true")
class TestSmpsRepeatedPlaybackBenchmark {
    private static final String MANIFEST_RELATIVE =
            "src/test/java/com/openggf/audio/"
                    + "TestSmpsRepeatedPlaybackBenchmark.java";
    private static final int MUSIC_ID = 0x81;
    private static final int SFX_ID = 0xA0;
    private static final int TINY_PROGRAM_BYTES = 64;
    private static final int LARGE_PROGRAM_BYTES = 1024 * 1024;
    private static final int TINY_DAC_BYTES = 64;
    private static final int LARGE_DAC_BYTES = 4 * 1024 * 1024;
    private static final int SIMPLE_MUSIC_TRACKS = 1;
    private static final int MAX_MUSIC_TRACKS = 10;
    private static final int WARMUP_OPERATIONS = 64;
    private static final int WRAPPER_WARMUP_INVOCATIONS = 10_000;
    private static final int DISCARDED_SCENARIO_REPETITIONS = 1;
    private static final int[] OPERATION_COUNTS = {64, 128, 256};
    private static final int REPETITIONS = 5;
    /** Printed fixed term in the paired control-spread acceptance rule. */
    private static final long VM_NOISE_MARGIN_BYTES_PER_OPERATION = 128;
    private static volatile Object workloadEscape;

    @AfterEach
    void resetAudioManager() {
        AudioManager.getInstance().resetState();
    }

    @Test
    void repeatedPublicMusicAndSfxPlaybackEmitsStableRawSamples() {
        assertEquals("21", System.getProperty("java.specification.version"),
                "historical allocation comparison requires JDK 21");
        ThreadMXBean allocation = requireAllocationBean();
        Scenario[] scenarios = Scenario.values();
        Sample[][][] samples =
                new Sample[scenarios.length][REPETITIONS][];

        System.out.printf(
                "SMPS_BENCHMARK_HEADER schema=3 manifestSha256=%s "
                        + "java=%s vm=%s "
                        + "vmVendor=%s vmVersion=%s os=%s arch=%s "
                        + "vmArgs=%s vmArgsRaw=%s "
                        + "forkCount=%s forkNumber=%s "
                        + "reuseForks=%s "
                        + "allocationSupported=true allocationEnabled=true "
                        + "warmup=%d wrapperWarmup=%d "
                        + "discardedScenarioRepetitions=%d "
                        + "counts=%s repetitions=%d "
                        + "tinyProgram=%d largeProgram=%d tinyDac=%d "
                        + "largeDac=%d simpleMusicTracks=%d "
                        + "maxMusicTracks=%d vmNoiseMargin=%d%n",
                executedManifestHash(),
                System.getProperty("java.runtime.version"),
                stableToken(System.getProperty("java.vm.name")),
                stableToken(System.getProperty("java.vm.vendor")),
                stableToken(System.getProperty("java.vm.version")),
                stableToken(System.getProperty("os.name")),
                stableToken(System.getProperty("os.arch")),
                stableToken(normalizedVmArguments()),
                stableToken(rawVmArguments()),
                stableToken(System.getProperty(
                        "surefire.forkCount", "unset")),
                stableToken(System.getProperty(
                        "surefire.forkNumber", "unset")),
                stableToken(System.getProperty(
                        "openggf.audio.benchmark.reuseForks", "unset")),
                WARMUP_OPERATIONS, WRAPPER_WARMUP_INVOCATIONS,
                DISCARDED_SCENARIO_REPETITIONS,
                Arrays.toString(OPERATION_COUNTS),
                REPETITIONS, TINY_PROGRAM_BYTES, LARGE_PROGRAM_BYTES,
                TINY_DAC_BYTES, LARGE_DAC_BYTES,
                SIMPLE_MUSIC_TRACKS, MAX_MUSIC_TRACKS,
                VM_NOISE_MARGIN_BYTES_PER_OPERATION);

        warmMeasurementWrapper(allocation, scenarios);
        warmRecordedScenarioPaths(allocation, scenarios);

        // Repetition is outermost and scenario order alternates to distribute
        // host/JIT drift across controls. Operation-count order alternates too.
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            for (int step = 0; step < scenarios.length; step++) {
                int scenarioIndex = (repetition & 1) == 0
                        ? step : scenarios.length - 1 - step;
                Scenario scenario = scenarios[scenarioIndex];
                BenchmarkFixture fixture = new BenchmarkFixture(scenario);
                runOperations(fixture.audio, scenario.route,
                        WARMUP_OPERATIONS);
                fixture.assertFixedTopology();
                samples[scenarioIndex][repetition] =
                        measureCounts(allocation, fixture, repetition, true);
                fixture.assertFixedTopology();
            }
        }

        for (int scenarioIndex = 0;
                scenarioIndex < scenarios.length; scenarioIndex++) {
            emitSummary(scenarios[scenarioIndex], samples[scenarioIndex]);
        }
    }

    static String executedManifestHash() {
        return manifestHashForCodeSource(
                TestSmpsRepeatedPlaybackBenchmark.class);
    }

    static String manifestHashForCodeSource(Class<?> anchor) {
        try {
            Path testClasses = Path.of(anchor.getProtectionDomain()
                    .getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            if (!testClasses.endsWith(Path.of("target", "test-classes"))) {
                throw new IllegalStateException(
                        "benchmark did not execute from target/test-classes");
            }
            Path worktree = testClasses.getParent().getParent();
            Path manifest = worktree.resolve(MANIFEST_RELATIVE);
            if (!Files.isRegularFile(manifest)) {
                throw new IllegalStateException(
                        "executed benchmark manifest is missing");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(Files.readAllBytes(manifest)));
        } catch (IOException | NoSuchAlgorithmException
                | URISyntaxException failure) {
            throw new IllegalStateException(
                    "cannot hash executed benchmark manifest", failure);
        }
    }

    private static Sample[] measureCounts(
            ThreadMXBean allocation,
            BenchmarkFixture fixture,
            int repetition,
            boolean emit) {
        Sample[] samples = new Sample[OPERATION_COUNTS.length];
        if ((repetition & 1) == 0) {
            for (int index = 0; index < OPERATION_COUNTS.length; index++) {
                samples[index] = measure(
                        allocation, fixture, repetition,
                        OPERATION_COUNTS[index], emit);
            }
        } else {
            for (int index = OPERATION_COUNTS.length - 1; index >= 0;
                    index--) {
                samples[index] = measure(
                        allocation, fixture, repetition,
                        OPERATION_COUNTS[index], emit);
            }
        }
        return samples;
    }

    private static Sample measure(
            ThreadMXBean allocation,
            BenchmarkFixture fixture,
            int repetition,
            int operations,
            boolean emit) {
        long threadId = Thread.currentThread().threadId();
        long gcCountBefore = totalGcCount();
        long gcTimeBefore = totalGcTimeMillis();
        long allocatedBefore = allocation.getThreadAllocatedBytes(threadId);
        long nanosBefore = System.nanoTime();
        runOperations(fixture.audio, fixture.scenario.route, operations);
        long elapsedNanos = System.nanoTime() - nanosBefore;
        long allocatedBytes = allocation.getThreadAllocatedBytes(threadId)
                - allocatedBefore;
        long gcCountDelta = Math.max(0, totalGcCount() - gcCountBefore);
        long gcTimeDelta = Math.max(0, totalGcTimeMillis() - gcTimeBefore);
        Topology topology = fixture.topology();
        Sample sample = new Sample(
                allocatedBytes, elapsedNanos, operations,
                fixture.loaderCalls(), fixture.programMaterializations(),
                gcCountDelta, gcTimeDelta,
                topology.liveVoices(), topology.driverSequencers());
        if (emit) {
            System.out.printf(
                    "SMPS_BENCHMARK_SAMPLE scenario=%s repetition=%d "
                            + "operations=%d allocatedBytes=%d elapsedNanos=%d "
                            + "loaderCalls=%d programMaterializations=%d "
                            + "gcCountDelta=%d gcTimeMillisDelta=%d "
                            + "liveVoices=%d driverSequencers=%d%n",
                    fixture.scenario.label, repetition, operations,
                    sample.allocatedBytes(), sample.elapsedNanos(),
                    sample.loaderCalls(), sample.programMaterializations(),
                    sample.gcCountDelta(), sample.gcTimeMillisDelta(),
                    sample.liveVoices(), sample.driverSequencers());
        }
        return sample;
    }

    private static void warmMeasurementWrapper(
            ThreadMXBean allocation, Scenario[] scenarios) {
        BenchmarkFixture fixture = new BenchmarkFixture(
                Scenario.SFX_PROGRAM_TINY);
        runOperations(fixture.audio, fixture.scenario.route,
                WARMUP_OPERATIONS);
        fixture.assertFixedTopology();
        for (int invocation = 0;
                invocation < WRAPPER_WARMUP_INVOCATIONS; invocation++) {
            measure(allocation, fixture, -1, 1, false);
        }
        fixture.assertFixedTopology();
    }

    private static void warmRecordedScenarioPaths(
            ThreadMXBean allocation, Scenario[] scenarios) {
        for (int repetition = 0;
                repetition < DISCARDED_SCENARIO_REPETITIONS; repetition++) {
            for (Scenario scenario : scenarios) {
                BenchmarkFixture fixture = new BenchmarkFixture(scenario);
                runOperations(fixture.audio, scenario.route,
                        WARMUP_OPERATIONS);
                fixture.assertFixedTopology();
                measureCounts(allocation, fixture, repetition, false);
                fixture.assertFixedTopology();
            }
        }
    }

    private static String rawVmArguments() {
        return String.join(",",
                ManagementFactory.getRuntimeMXBean().getInputArguments());
    }

    private static String normalizedVmArguments() {
        return rawVmArguments().replace(
                "-Djava.io.tmpdir="
                        + System.getProperty("java.io.tmpdir"),
                "-Djava.io.tmpdir=<WORKTREE>/target/test-tmp");
    }

    /** The complete measured caller; identical on baseline and feature. */
    private static void runOperations(
            AudioManager audio, Route route, int operations) {
        for (int index = 0; index < operations; index++) {
            if (route == Route.MUSIC) {
                audio.playMusic(MUSIC_ID);
            } else {
                assertTrue(audio.playSfx(SFX_ID));
            }
            // SILENT still applies the complete pending command while avoiding
            // mixer cost that is unrelated to admission. It also leaves every
            // persistent fixture track live for the next replacement.
            audio.presentFrame(PresentationMode.SILENT);
        }
        workloadEscape = audio;
    }

    private static void emitSummary(
            Scenario scenario, Sample[][] repetitions) {
        for (int countIndex = 0;
                countIndex < OPERATION_COUNTS.length; countIndex++) {
            long[] bytesPerOperation = new long[REPETITIONS];
            long[] nanosPerOperation = new long[REPETITIONS];
            long[] loaderCalls = new long[REPETITIONS];
            long[] materializations = new long[REPETITIONS];
            for (int repetition = 0; repetition < REPETITIONS; repetition++) {
                Sample sample = repetitions[repetition][countIndex];
                bytesPerOperation[repetition] =
                        sample.allocatedBytes() / sample.operations();
                nanosPerOperation[repetition] =
                        sample.elapsedNanos() / sample.operations();
                loaderCalls[repetition] = sample.loaderCalls();
                materializations[repetition] =
                        sample.programMaterializations();
            }
            System.out.printf(
                    "SMPS_BENCHMARK_SUMMARY scenario=%s operations=%d "
                            + "bytesPerOp=%s medianBytesPerOp=%d "
                            + "controlSpread=%d nanosPerOp=%s "
                            + "medianNanosPerOp=%d loaderCalls=%s "
                            + "programMaterializations=%s%n",
                    scenario.label, OPERATION_COUNTS[countIndex],
                    Arrays.toString(bytesPerOperation),
                    median(bytesPerOperation), spread(bytesPerOperation),
                    Arrays.toString(nanosPerOperation),
                    median(nanosPerOperation), Arrays.toString(loaderCalls),
                    Arrays.toString(materializations));
        }
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long spread(long[] values) {
        return Arrays.stream(values).max().orElseThrow()
                - Arrays.stream(values).min().orElseThrow();
    }

    private static ThreadMXBean requireAllocationBean() {
        java.lang.management.ThreadMXBean raw =
                ManagementFactory.getThreadMXBean();
        assertTrue(raw instanceof ThreadMXBean,
                "com.sun.management.ThreadMXBean is required");
        ThreadMXBean bean = (ThreadMXBean) raw;
        assertTrue(bean.isThreadAllocatedMemorySupported(),
                "thread allocation accounting is required");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        assertTrue(bean.isThreadAllocatedMemoryEnabled(),
                "thread allocation accounting must be enabled");
        assertTrue(bean.getThreadAllocatedBytes(
                        Thread.currentThread().threadId()) >= 0,
                "current-thread allocation bytes are required");
        return bean;
    }

    private static long totalGcCount() {
        long total = 0;
        for (GarbageCollectorMXBean bean
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count >= 0) {
                total += count;
            }
        }
        return total;
    }

    private static long totalGcTimeMillis() {
        long total = 0;
        for (GarbageCollectorMXBean bean
                : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = bean.getCollectionTime();
            if (time >= 0) {
                total += time;
            }
        }
        return total;
    }

    private static String stableToken(String value) {
        return value == null ? "unknown" : value.replace(' ', '_');
    }

    private enum Route { MUSIC, SFX }

    private enum Scenario {
        MUSIC_REPEAT("music-repeat", Route.MUSIC,
                TINY_PROGRAM_BYTES, TINY_PROGRAM_BYTES,
                TINY_DAC_BYTES, SIMPLE_MUSIC_TRACKS),
        SFX_PROGRAM_TINY("sfx-program-tiny", Route.SFX,
                TINY_PROGRAM_BYTES, TINY_PROGRAM_BYTES,
                TINY_DAC_BYTES, SIMPLE_MUSIC_TRACKS),
        SFX_PROGRAM_LARGE("sfx-program-large", Route.SFX,
                TINY_PROGRAM_BYTES, LARGE_PROGRAM_BYTES,
                TINY_DAC_BYTES, SIMPLE_MUSIC_TRACKS),
        SFX_DAC_TINY("sfx-dac-tiny", Route.SFX,
                TINY_PROGRAM_BYTES, TINY_PROGRAM_BYTES,
                TINY_DAC_BYTES, SIMPLE_MUSIC_TRACKS),
        SFX_DAC_LARGE("sfx-dac-large", Route.SFX,
                TINY_PROGRAM_BYTES, TINY_PROGRAM_BYTES,
                LARGE_DAC_BYTES, SIMPLE_MUSIC_TRACKS),
        SFX_UNRELATED_MUSIC_MIN("sfx-unrelated-music-min", Route.SFX,
                TINY_PROGRAM_BYTES, TINY_PROGRAM_BYTES,
                TINY_DAC_BYTES, SIMPLE_MUSIC_TRACKS),
        SFX_UNRELATED_MUSIC_MAX("sfx-unrelated-music-max", Route.SFX,
                TINY_PROGRAM_BYTES, TINY_PROGRAM_BYTES,
                TINY_DAC_BYTES, MAX_MUSIC_TRACKS);

        private final String label;
        private final Route route;
        private final int musicProgramBytes;
        private final int sfxProgramBytes;
        private final int dacBytes;
        private final int musicTracks;

        Scenario(
                String label, Route route,
                int musicProgramBytes, int sfxProgramBytes,
                int dacBytes, int musicTracks) {
            this.label = label;
            this.route = route;
            this.musicProgramBytes = musicProgramBytes;
            this.sfxProgramBytes = sfxProgramBytes;
            this.dacBytes = dacBytes;
            this.musicTracks = musicTracks;
        }
    }

    private static final class BenchmarkFixture {
        private final Scenario scenario;
        private final AudioManager audio;
        private final InstrumentedLoader loader;

        private BenchmarkFixture(Scenario scenario) {
            this.scenario = scenario;
            MaterializationCounter music = new MaterializationCounter();
            MaterializationCounter sfx = new MaterializationCounter();
            loader = new InstrumentedLoader(
                    scenario.musicProgramBytes,
                    scenario.sfxProgramBytes,
                    scenario.musicTracks, scenario.dacBytes,
                    music, sfx);
            audio = AudioManager.getInstance();
            audio.resetState();
            audio.setBackend(new NullAudioBackend());
            audio.setAudioProfile(new BenchmarkProfile(loader));
            audio.setRom(new Rom());
            if (scenario.route == Route.SFX) {
                audio.playMusic(MUSIC_ID);
                audio.presentFrame(PresentationMode.SILENT);
                assertEquals(1, topology().liveVoices(),
                        "SFX control must establish one unrelated music voice");
            }
        }

        private long loaderCalls() {
            return scenario.route == Route.MUSIC
                    ? loader.musicLoads : loader.sfxLoads;
        }

        private long programMaterializations() {
            return scenario.route == Route.MUSIC
                    ? loader.musicMaterializations.count
                    : loader.sfxMaterializations.count;
        }

        private Topology topology() {
            AudioVoiceRegistry registry =
                    (AudioVoiceRegistry) field(audio, "shadowRegistry");
            int liveVoices = registry.orderedVoiceCount();
            SmpsDriverSession session = (SmpsDriverSession)
                    field(audio, "shadowSmpsSession");
            com.openggf.audio.driver.SmpsDriver driver =
                    (com.openggf.audio.driver.SmpsDriver)
                            field(session, "driver");
            int sequencers = driver.sequencersForTesting().size();
            return new Topology(liveVoices, sequencers);
        }

        private void assertFixedTopology() {
            Topology topology = topology();
            assertEquals(1, topology.liveVoices(),
                    scenario.label + " live voice count");
            assertEquals(scenario.route == Route.MUSIC ? 1 : 2,
                    topology.driverSequencers(),
                    scenario.label + " music/SFX sequencer topology");
        }
    }

    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError("no field " + name + " on "
                + target.getClass());
    }

    private static final class BenchmarkProfile implements GameAudioProfile {
        private final SmpsLoader loader;

        private BenchmarkProfile(SmpsLoader loader) {
            this.loader = loader;
        }

        @Override public String presentationGameId() { return "task9"; }
        @Override public SmpsLoader createSmpsLoader(Rom rom) { return loader; }
        @Override public SmpsSequencerConfig getSequencerConfig() {
            return new SmpsSequencerConfig.Builder().build();
        }
        @Override public int getSpeedShoesOnCommandId() { return -1; }
        @Override public int getSpeedShoesOffCommandId() { return -1; }
        @Override public int getInvincibilityMusicId() { return -1; }
        @Override public int getExtraLifeMusicId() { return -1; }
        @Override public int getDrowningMusicId() { return -1; }
        @Override public Map<GameSound, Integer> getSoundMap() {
            return Map.of();
        }
    }

    private static final class InstrumentedLoader implements SmpsLoader {
        private final int musicProgramBytes;
        private final int sfxProgramBytes;
        private final int musicTracks;
        private final DacData dac;
        private final MaterializationCounter musicMaterializations;
        private final MaterializationCounter sfxMaterializations;
        private long musicLoads;
        private long sfxLoads;

        private InstrumentedLoader(
                int musicProgramBytes, int sfxProgramBytes,
                int musicTracks, int dacBytes,
                MaterializationCounter musicMaterializations,
                MaterializationCounter sfxMaterializations) {
            this.musicProgramBytes = musicProgramBytes;
            this.sfxProgramBytes = sfxProgramBytes;
            this.musicTracks = musicTracks;
            this.musicMaterializations = musicMaterializations;
            this.sfxMaterializations = sfxMaterializations;
            byte[] sample = new byte[dacBytes];
            sample[sample.length - 1] = 1;
            dac = new DacData(
                    Map.of(1, sample),
                    Map.of(0x81, new DacData.DacEntry(1, 4)), 288);
        }

        @Override
        public AbstractSmpsData loadMusic(int musicId) {
            musicLoads++;
            return new InstrumentedMusicData(
                    musicProgramBytes, musicId, musicTracks,
                    musicMaterializations);
        }

        @Override
        public AbstractSmpsData loadSfx(int sfxId) {
            sfxLoads++;
            return new InstrumentedSfxData(
                    sfxProgramBytes, sfxId, sfxMaterializations);
        }

        @Override public AbstractSmpsData loadSfx(String name) { return null; }
        @Override public DacData loadDacData() { return dac; }
    }

    private abstract static class InstrumentedData extends AbstractSmpsData {
        private final MaterializationCounter counter;
        private boolean materialized;

        private InstrumentedData(
                int bytes, int id, int fmTracks, int psgTracks,
                MaterializationCounter counter) {
            super(program(bytes), 0);
            this.counter = counter;
            this.id = id;
            channels = fmTracks;
            psgChannels = psgTracks;
            fmPointers = pointers(fmTracks);
            fmKeyOffsets = new int[fmTracks];
            fmVolumeOffsets = new int[fmTracks];
            psgPointers = pointers(psgTracks);
            psgKeyOffsets = new int[psgTracks];
            psgVolumeOffsets = new int[psgTracks];
            psgModEnvs = new int[psgTracks];
            psgInstruments = new int[psgTracks];
        }

        @Override
        public byte[] getData() {
            if (!materialized) {
                materialized = true;
                counter.count++;
            }
            return super.getData();
        }

        @Override protected void parseHeader() { }
        @Override public byte[] getVoice(int voiceId) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) {
            return ((data[offset] & 0xFF) << 8)
                    | (data[offset + 1] & 0xFF);
        }
        @Override public int getBaseNoteOffset() { return 0; }

        private static byte[] program(int size) {
            byte[] bytes = new byte[Math.max(64, size)];
            // Persistent rest + duration + absolute jump to offset 1. The
            // tracks remain live across every warm/measured update, keeping
            // unrelated state and replacement topology constant.
            bytes[1] = (byte) 0x80;
            bytes[2] = 0x7F;
            bytes[3] = (byte) 0xF6;
            bytes[4] = 0;
            bytes[5] = 1;
            return bytes;
        }

        private static int[] pointers(int count) {
            int[] pointers = new int[count];
            Arrays.fill(pointers, 1);
            return pointers;
        }
    }

    private static final class InstrumentedMusicData
            extends InstrumentedData {
        private InstrumentedMusicData(
                int bytes, int id, int tracks,
                MaterializationCounter counter) {
            super(bytes, id, Math.min(6, tracks),
                    Math.max(0, tracks - 6), counter);
        }
    }

    private static final class InstrumentedSfxData
            extends InstrumentedData implements SmpsSfxData {
        private static final List<SmpsSfxTrack> TRACKS =
                List.of(new Track(0, 1, 0, 0));

        private InstrumentedSfxData(
                int bytes, int id, MaterializationCounter counter) {
            super(bytes, id, 1, 0, counter);
        }

        @Override public int getTickMultiplier() { return 1; }
        @Override public List<? extends SmpsSfxTrack> getTrackEntries() {
            return TRACKS;
        }
    }

    private record Track(
            int channelMask, int pointer, int transpose, int volume)
            implements SmpsSfxData.SmpsSfxTrack {
    }

    private static final class MaterializationCounter {
        private long count;
    }

    private record Topology(int liveVoices, int driverSequencers) {
    }

    private record Sample(
            long allocatedBytes,
            long elapsedNanos,
            int operations,
            long loaderCalls,
            long programMaterializations,
            long gcCountDelta,
            long gcTimeMillisDelta,
            int liveVoices,
            int driverSequencers) {
    }
}
