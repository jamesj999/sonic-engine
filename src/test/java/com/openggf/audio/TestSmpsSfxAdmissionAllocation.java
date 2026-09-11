package com.openggf.audio;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.presentation.AudioPresentationCommandQueue;
import com.openggf.audio.presentation.AudioPresentationCommandResolver;
import com.openggf.audio.presentation.AudioPresentationSessionCommandApplier;
import com.openggf.audio.presentation.AudioPresentationSourceFactory;
import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.DecodedPcmCache;
import com.openggf.audio.presentation.SmpsAssetKey;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.session.SmpsSessionTestSupport;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Allocation ownership proof for the complete warmed SMPS SFX command path.
 *
 * <p>Every measured operation starts at the logical command resolver, performs
 * the versioned catalog lookup, creates the one mutable sequencer/track, then
 * prepares and commits replacement through the live music driver. The one live
 * music voice and one live SFX remain constant throughout. Asset freezing and
 * the first catalog registration are deliberately completed before the warmup
 * and measured regions.</p>
 */
class TestSmpsSfxAdmissionAllocation {
    private static final int MUSIC_ID = 0x81;
    private static final int SFX_ID = 0xA0;
    private static final int TINY_PROGRAM_BYTES = 64;
    private static final int LARGE_PROGRAM_BYTES = 1024 * 1024;
    private static final int TINY_DAC_BYTES = 64;
    private static final int LARGE_DAC_BYTES = 4 * 1024 * 1024;
    private static final int SIMPLE_MUSIC_TRACKS = 1;
    private static final int MAX_MUSIC_TRACKS = 10;
    private static final int WARM_TRIGGERS = 10_000;
    private static final int[] MEASURED_COUNTS = {64, 128, 256};
    private static final int REPETITIONS = 7;
    /** Residual HotSpot accounting noise after repeated warmed controls. */
    private static final long VM_NOISE_MARGIN_BYTES_PER_TRIGGER = 128;
    /**
     * One sequencer, one track, candidate-sized admission arrays, and bounded
     * command metadata. This is intentionally a generous object-shape ceiling,
     * not a fitted value from this fixture.
     */
    private static final long MAX_BOUNDED_TRIGGER_BYTES = 32 * 1024;
    @Test
    void warmedAdmissionAllocationIsIndependentOfAssetAndUnrelatedMusicSize() {
        Fixture programTiny = fixture(
                TINY_PROGRAM_BYTES, TINY_DAC_BYTES, SIMPLE_MUSIC_TRACKS);
        Fixture programLarge = fixture(
                LARGE_PROGRAM_BYTES, TINY_DAC_BYTES, SIMPLE_MUSIC_TRACKS);
        Fixture dacTiny = fixture(
                TINY_PROGRAM_BYTES, TINY_DAC_BYTES, SIMPLE_MUSIC_TRACKS);
        Fixture dacLarge = fixture(
                TINY_PROGRAM_BYTES, LARGE_DAC_BYTES, SIMPLE_MUSIC_TRACKS);
        Fixture musicSimple = fixture(
                TINY_PROGRAM_BYTES, TINY_DAC_BYTES, SIMPLE_MUSIC_TRACKS);
        Fixture musicComplex = fixture(
                TINY_PROGRAM_BYTES, TINY_DAC_BYTES, MAX_MUSIC_TRACKS);
        List<Fixture> fixtures = List.of(
                programTiny, programLarge,
                dacTiny, dacLarge,
                musicSimple, musicComplex);

        // Structural and semantic identity evidence always runs, including on
        // VMs that cannot expose per-thread allocated bytes.
        fixtures.forEach(TestSmpsSfxAdmissionAllocation::assertWarmedIdentity);
        assertPairTopology(programTiny, programLarge,
                "program-size control");
        assertPairTopology(dacTiny, dacLarge, "DAC-size control");
        assertPairTopology(musicSimple, musicComplex,
                "unrelated-music control", false);
        ThreadMXBean bean = allocationBeanOrNull();
        if (bean == null) {
            for (Fixture fixture : fixtures) {
                warmTriggers(fixture, WARM_TRIGGERS);
                fixture.assertOneMaterializationPerKey();
                fixture.assertFixedLiveTopology();
            }
            Assumptions.assumeTrue(false,
                    "ThreadMXBean allocation accounting unavailable");
            return;
        }
        for (Fixture fixture : fixtures) {
            warmAllocatedBytesCallSite(bean, fixture);
            fixture.assertOneMaterializationPerKey();
            fixture.assertFixedLiveTopology();
        }

        PairMeasurement program = measureAlternating(
                bean, "program", programTiny, programLarge);
        PairMeasurement dac = measureAlternating(
                bean, "dac", dacTiny, dacLarge);
        PairMeasurement music = measureAlternating(
                bean, "unrelated-music", musicSimple, musicComplex);

        assertDimensionIndependent(program);
        assertDimensionIndependent(dac);
        assertDimensionIndependent(music);
        for (PairMeasurement measurement : List.of(program, dac, music)) {
            assertTrue(measurement.leftMedian() <= MAX_BOUNDED_TRIGGER_BYTES,
                    measurement.label() + " tiny/control trigger slope "
                            + measurement.leftMedian()
                            + " exceeds the bounded mutable-voice budget");
            assertTrue(measurement.rightMedian() <= MAX_BOUNDED_TRIGGER_BYTES,
                    measurement.label() + " large trigger slope "
                            + measurement.rightMedian()
                            + " exceeds the bounded mutable-voice budget");
        }
    }

    private static Fixture fixture(
            int sfxProgramBytes, int dacBytes, int musicTracks) {
        MaterializationCounter musicCounter = new MaterializationCounter();
        MaterializationCounter sfxCounter = new MaterializationCounter();
        CountingLoader loader = new CountingLoader(
                musicTracks, sfxProgramBytes, musicCounter, sfxCounter);
        byte[] sample = new byte[dacBytes];
        sample[sample.length - 1] = 1;
        DacData dac = new DacData(
                Map.of(1, sample),
                Map.of(0x81, new DacData.DacEntry(1, 4)), 288);
        SmpsSequencerConfig config =
                new SmpsSequencerConfig.Builder().build();
        SmpsCoordFlagHandlerOwner handlers =
                new SmpsCoordFlagHandlerOwner(
                        new SmpsCoordFlagRuntimeState());
        SmpsDriverSession session =
                SmpsSessionTestSupport.installed(48_000);
        AudioPresentationSourceFactory factory =
                new AudioPresentationSourceFactory(
                        () -> true, handlers,
                        new AudioPresentationSourceFactory.Settings(
                                48_000, SmpsSequencer.Region.NTSC,
                                false, false, false, 1,
                                AudioManager.getInstance(),
                                new DecodedPcmCache(), ignored -> null),
                        session);
        AudioPresentationCommandQueue queue =
                new AudioPresentationCommandQueue();
        AudioVoiceRegistry registry = new AudioVoiceRegistry(
                factory, factory, handlers,
                warning -> {
                    throw new AssertionError(warning);
                }, session);
        AudioPresentationCommandResolver.Sources sources =
                new AudioPresentationCommandResolver.Sources() {
                    @Override
                    public AudioPresentationCommandResolver.SourceAccess
                            sourceFor(
                                    SmpsAssetKey.Route route,
                                    String donorGameId) {
                        return new AudioPresentationCommandResolver.SourceAccess(
                                "task9", 1, loader, dac, config,
                                new FixedSfxPolicy());
                    }

                    @Override
                    public int maxStereoFrames() {
                        return 800;
                    }
                };
        AudioPresentationCommandResolver resolver =
                new AudioPresentationCommandResolver(
                        queue, factory, sources,
                        warning -> {
                            throw new AssertionError(warning);
                        }, () -> true, registry::apply);
        Fixture fixture = new Fixture(
                resolver, queue, registry, session, loader,
                musicCounter, sfxCounter,
                sfxProgramBytes, dacBytes, musicTracks);
        fixture.submitMusic();
        assertEquals(1, loader.musicLoads,
                "music registration loads exactly once");
        assertEquals(1, musicCounter.count,
                "music registration materializes the program exactly once");
        return fixture;
    }

    private static void assertWarmedIdentity(Fixture fixture) {
        fixture.trigger();
        SmpsSequencer first = fixture.liveSfx();
        AbstractSmpsData program = first.getSmpsData();
        DacData dac = first.getDacData();
        Object descriptor = first.getSourceDescriptor();
        fixture.trigger();
        SmpsSequencer second = fixture.liveSfx();

        assertNotSame(first, second,
                "same-ID retrigger must create fresh mutable playback state");
        assertSame(program, second.getSmpsData(),
                "retrigger must reuse the one frozen program");
        assertSame(dac, second.getDacData(),
                "retrigger must reuse the one immutable DAC dependency");
        assertSame(descriptor, second.getSourceDescriptor(),
                "retrigger must reuse the registration-time descriptor");
        fixture.assertOneMaterializationPerKey();
        fixture.assertFixedLiveTopology();
    }

    private static void assertPairTopology(
            Fixture left, Fixture right, String label) {
        assertPairTopology(left, right, label, true);
    }

    private static void assertPairTopology(
            Fixture left, Fixture right, String label,
            boolean requireEqualMusicTracks) {
        assertEquals(left.liveSfx().trackCount(), right.liveSfx().trackCount(),
                label + " SFX track count");
        assertEquals(left.liveSfx().captureSnapshot().pitch(),
                right.liveSfx().captureSnapshot().pitch(), label + " pitch");
        assertEquals(left.liveSfx().getSfxPriority(),
                right.liveSfx().getSfxPriority(), label + " priority");
        assertEquals(left.registry.orderedVoiceCount(),
                right.registry.orderedVoiceCount(), label + " live voices");
        assertEquals(left.driver().sequencersForTesting().size(),
                right.driver().sequencersForTesting().size(),
                label + " driver topology");
        if (requireEqualMusicTracks) {
            assertEquals(left.musicTracks, right.musicTracks,
                    label + " music tracks");
        }
    }

    private static void warmTriggers(Fixture fixture, int count) {
        for (int index = 0; index < count; index++) {
            fixture.trigger();
        }
    }

    private static void warmAllocatedBytesCallSite(
            ThreadMXBean bean, Fixture fixture) {
        int remaining = WARM_TRIGGERS;
        while (remaining > 0) {
            int operations = Math.min(
                    MEASURED_COUNTS[MEASURED_COUNTS.length - 1],
                    remaining);
            allocatedBytes(bean, fixture, operations);
            remaining -= operations;
        }
    }

    private static PairMeasurement measureAlternating(
            ThreadMXBean bean, String label, Fixture left, Fixture right) {
        long[] leftSlopes = new long[REPETITIONS];
        long[] rightSlopes = new long[REPETITIONS];
        long[][] leftRaw = new long[REPETITIONS][];
        long[][] rightRaw = new long[REPETITIONS][];
        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            if ((repetition & 1) == 0) {
                leftRaw[repetition] = measureCounts(bean, left, repetition);
                rightRaw[repetition] = measureCounts(bean, right, repetition);
            } else {
                rightRaw[repetition] = measureCounts(bean, right, repetition);
                leftRaw[repetition] = measureCounts(bean, left, repetition);
            }
            leftSlopes[repetition] = endpointSlope(leftRaw[repetition]);
            rightSlopes[repetition] = endpointSlope(rightRaw[repetition]);
            left.assertOneMaterializationPerKey();
            right.assertOneMaterializationPerKey();
            left.assertFixedLiveTopology();
            right.assertFixedLiveTopology();
        }
        PairMeasurement result = new PairMeasurement(
                label, leftRaw, rightRaw, leftSlopes, rightSlopes,
                median(leftSlopes), median(rightSlopes),
                spread(leftSlopes) + VM_NOISE_MARGIN_BYTES_PER_TRIGGER);
        System.out.printf(
                "SMPS_SFX_ALLOCATION label=%s counts=%s leftRaw=%s "
                        + "rightRaw=%s leftSlopes=%s rightSlopes=%s "
                        + "medians=%d/%d controlSpread=%d vmMargin=%d "
                        + "tolerance=%d%n",
                label, Arrays.toString(MEASURED_COUNTS),
                Arrays.deepToString(leftRaw), Arrays.deepToString(rightRaw),
                Arrays.toString(leftSlopes), Arrays.toString(rightSlopes),
                result.leftMedian(), result.rightMedian(),
                spread(leftSlopes), VM_NOISE_MARGIN_BYTES_PER_TRIGGER,
                result.tolerance());
        return result;
    }

    private static long[] measureCounts(
            ThreadMXBean bean, Fixture fixture, int repetition) {
        long[] bytes = new long[MEASURED_COUNTS.length];
        if ((repetition & 1) == 0) {
            for (int index = 0; index < MEASURED_COUNTS.length; index++) {
                bytes[index] = allocatedBytes(
                        bean, fixture, MEASURED_COUNTS[index]);
            }
        } else {
            for (int index = MEASURED_COUNTS.length - 1; index >= 0; index--) {
                bytes[index] = allocatedBytes(
                        bean, fixture, MEASURED_COUNTS[index]);
            }
        }
        return bytes;
    }

    private static long allocatedBytes(
            ThreadMXBean bean, Fixture fixture, int operations) {
        int topologyInspectionsBefore = fixture.topologyInspections;
        long threadId = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < operations; index++) {
            fixture.trigger();
        }
        long allocated = bean.getThreadAllocatedBytes(threadId) - before;
        assertEquals(topologyInspectionsBefore, fixture.topologyInspections,
                "measured trigger must not inspect live topology");
        return allocated;
    }

    private static long endpointSlope(long[] allocatedAtCounts) {
        return (allocatedAtCounts[2] - allocatedAtCounts[0])
                / (MEASURED_COUNTS[2] - MEASURED_COUNTS[0]);
    }

    private static void assertDimensionIndependent(PairMeasurement result) {
        long dimensionSlope = result.rightMedian() - result.leftMedian();
        assertTrue(Math.abs(dimensionSlope) <= result.tolerance(),
                result.label() + " changed allocation by " + dimensionSlope
                        + " bytes/trigger; controls="
                        + Arrays.toString(result.leftSlopes())
                        + ", samples="
                        + Arrays.toString(result.rightSlopes())
                        + ", tolerance=" + result.tolerance());
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long spread(long[] values) {
        long minimum = Arrays.stream(values).min().orElseThrow();
        long maximum = Arrays.stream(values).max().orElseThrow();
        return maximum - minimum;
    }

    private static ThreadMXBean allocationBeanOrNull() {
        java.lang.management.ThreadMXBean raw =
                ManagementFactory.getThreadMXBean();
        if (!(raw instanceof ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            return null;
        }
        try {
            if (!bean.isThreadAllocatedMemoryEnabled()) {
                bean.setThreadAllocatedMemoryEnabled(true);
            }
        } catch (SecurityException | UnsupportedOperationException failure) {
            return null;
        }
        return bean.isThreadAllocatedMemoryEnabled()
                && bean.getThreadAllocatedBytes(
                Thread.currentThread().threadId()) >= 0 ? bean : null;
    }

    private static final class Fixture {
        private final AudioPresentationCommandResolver resolver;
        private final AudioPresentationCommandQueue queue;
        private final AudioVoiceRegistry registry;
        private final SmpsDriverSession session;
        private final CountingLoader loader;
        private final MaterializationCounter musicCounter;
        private final MaterializationCounter sfxCounter;
        private final int sfxProgramBytes;
        private final int dacBytes;
        private final int musicTracks;
        private int topologyInspections;

        private Fixture(
                AudioPresentationCommandResolver resolver,
                AudioPresentationCommandQueue queue,
                AudioVoiceRegistry registry,
                SmpsDriverSession session,
                CountingLoader loader,
                MaterializationCounter musicCounter,
                MaterializationCounter sfxCounter,
                int sfxProgramBytes,
                int dacBytes,
                int musicTracks) {
            this.resolver = resolver;
            this.queue = queue;
            this.registry = registry;
            this.session = session;
            this.loader = loader;
            this.musicCounter = musicCounter;
            this.sfxCounter = sfxCounter;
            this.sfxProgramBytes = sfxProgramBytes;
            this.dacBytes = dacBytes;
            this.musicTracks = musicTracks;
        }

        private void submitMusic() {
            resolver.submit(new AudioCommand.PlayMusic(
                    MUSIC_ID, AudioCommand.MusicRoute.BASE_SMPS,
                    false, null));
            queue.applyPending(command ->
                    AudioPresentationSessionCommandApplier.apply(
                            session, registry, command));
        }

        private void trigger() {
            resolver.submit(new AudioCommand.PlaySfx(
                    SFX_ID, null, AudioCommand.SfxRoute.BASE_SMPS_ID,
                    1.0f, null));
            queue.applyPending(command ->
                    AudioPresentationSessionCommandApplier.apply(
                            session, registry, command));
        }

        private SmpsDriver driver() {
            return SmpsSessionTestSupport.logicalDriver(session);
        }

        private SmpsSequencer liveSfx() {
            topologyInspections++;
            return driver().sequencersForTesting().stream()
                    .filter(SmpsSequencer::isSfx)
                    .findFirst()
                    .orElseThrow();
        }

        private void assertOneMaterializationPerKey() {
            assertEquals(1, loader.musicLoads,
                    "music loader calls for " + description());
            assertEquals(1, loader.sfxLoads,
                    "SFX loader calls for " + description());
            assertEquals(1, musicCounter.count,
                    "music materializations for " + description());
            assertEquals(1, sfxCounter.count,
                    "SFX materializations for " + description());
        }

        private void assertFixedLiveTopology() {
            assertEquals(0, registry.orderedVoiceCount(),
                    "session SMPS must not occupy an ordered PCM slot for "
                            + description());
            assertEquals(2, driver().sequencersForTesting().size(),
                    "one music plus one replacement SFX for "
                            + description());
            assertEquals(1, liveSfx().trackCount(),
                    "fixed one-track SFX for " + description());
            assertEquals(musicTracks,
                    driver().firstMusicSequencer().trackCount(),
                    "unrelated music topology for " + description());
        }

        private String description() {
            return "program=" + sfxProgramBytes + ",dac=" + dacBytes
                    + ",musicTracks=" + musicTracks;
        }
    }

    private static final class FixedSfxPolicy
            implements AudioPresentationCommandResolver.SfxPolicy {
        @Override public int priority(int sfxId) { return 0x70; }
        @Override public boolean special(int sfxId) { return false; }
        @Override public boolean continuous(int sfxId) { return false; }
    }

    private static final class CountingLoader implements SmpsLoader {
        private final int musicTracks;
        private final int sfxProgramBytes;
        private final MaterializationCounter musicCounter;
        private final MaterializationCounter sfxCounter;
        private int musicLoads;
        private int sfxLoads;

        private CountingLoader(
                int musicTracks,
                int sfxProgramBytes,
                MaterializationCounter musicCounter,
                MaterializationCounter sfxCounter) {
            this.musicTracks = musicTracks;
            this.sfxProgramBytes = sfxProgramBytes;
            this.musicCounter = musicCounter;
            this.sfxCounter = sfxCounter;
        }

        @Override
        public AbstractSmpsData loadMusic(int musicId) {
            musicLoads++;
            return new InstrumentedMusicData(
                    TINY_PROGRAM_BYTES, musicId, musicTracks, musicCounter);
        }

        @Override
        public AbstractSmpsData loadSfx(int sfxId) {
            sfxLoads++;
            return new InstrumentedSfxData(
                    sfxProgramBytes, sfxId, sfxCounter);
        }

        @Override public AbstractSmpsData loadSfx(String name) { return null; }
        @Override public DacData loadDacData() { return null; }
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
            byte[] program = new byte[Math.max(2, size)];
            program[1] = (byte) 0xF2;
            return program;
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
        private int count;
    }

    private record PairMeasurement(
            String label,
            long[][] leftRaw,
            long[][] rightRaw,
            long[] leftSlopes,
            long[] rightSlopes,
            long leftMedian,
            long rightMedian,
            long tolerance) {
    }
}
