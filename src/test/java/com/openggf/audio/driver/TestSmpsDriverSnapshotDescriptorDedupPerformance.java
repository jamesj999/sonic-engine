package com.openggf.audio.driver;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.presentation.AudioPresentationCommand;
import com.openggf.audio.presentation.AudioPresentationSourceFactory;
import com.openggf.audio.presentation.DecodedPcmCache;
import com.openggf.audio.presentation.ResolvedSmpsSfxSource;
import com.openggf.audio.presentation.SmpsAssetKey;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.audio.session.SmpsSessionTestSupport;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsDriverSnapshotDescriptorDedupPerformance {

    private static final int SFX_COUNT = 32;
    private static final int CAPTURES_PER_REPETITION = 20;
    private static final int ALLOCATION_CONTROL_REPETITIONS = 7;
    // Residual per-operation VM accounting noise after repeated warmed controls.
    private static final long VM_ALLOCATION_NOISE_MARGIN_BYTES = 256;
    private static volatile SmpsDriverSnapshot escapedSnapshot;
    private static volatile Object escapedInstantiation;

    @Test
    void allocationToleranceDoesNotHideStableControlsBehindAFixedFloor() {
        assertEquals(256, controlTolerance(1_000, 1_000));
    }

    @Test
    void registeredMusicAndSfxInstantiationAllocationDoesNotScaleWithProgramSize() {
        InstantiationFixture tiny = instantiationFixture("tiny", 4);
        InstantiationFixture large = instantiationFixture(
                "large", 1024 * 1024);

        assertInstantiationStructure(tiny);
        assertInstantiationStructure(large);

        ThreadMXBean bean = allocationBeanOrSkip();

        for (int index = 0; index < 40; index++) {
            escapedInstantiation = tiny.instantiateSfx();
            escapedInstantiation = large.instantiateSfx();
            escapedInstantiation = tiny.instantiateMusic();
            escapedInstantiation = large.instantiateMusic();
        }

        long[] tinySfxControls = repeatedAllocationSlopes(
                bean, ignored -> tiny.instantiateSfx());
        long[] largeSfxSamples = repeatedAllocationSlopes(
                bean, ignored -> large.instantiateSfx());
        long[] tinyMusicControls = repeatedAllocationSlopes(
                bean, ignored -> tiny.instantiateMusic());
        long[] largeMusicSamples = repeatedAllocationSlopes(
                bean, ignored -> large.instantiateMusic());
        long tinySfxSlope = median(tinySfxControls);
        long largeSfxSlope = median(largeSfxSamples);
        long tinyMusicSlope = median(tinyMusicControls);
        long largeMusicSlope = median(largeMusicSamples);
        long sfxTolerance = controlTolerance(tinySfxControls);
        long musicTolerance = controlTolerance(tinyMusicControls);

        System.out.printf("smps catalog instantiation slopes "
                        + "sfxControls=%s largeSfx=%s "
                        + "musicControls=%s largeMusic=%s "
                        + "medians=%d/%d/%d/%d tolerances=%d/%d bytes%n",
                Arrays.toString(tinySfxControls),
                Arrays.toString(largeSfxSamples),
                Arrays.toString(tinyMusicControls),
                Arrays.toString(largeMusicSamples),
                tinySfxSlope, largeSfxSlope,
                tinyMusicSlope, largeMusicSlope,
                sfxTolerance, musicTolerance);
        assertTrue(Math.abs(largeSfxSlope - tinySfxSlope) <= sfxTolerance,
                "registered SFX instantiation must not allocate by program size");
        assertTrue(Math.abs(largeMusicSlope - tinyMusicSlope)
                        <= musicTolerance,
                "registered music restore must not allocate by program size");
        assertEquals(0, tiny.sfxSource.dataReads());
        assertEquals(0, large.sfxSource.dataReads());
        assertEquals(0, tiny.musicSource.dataReads());
        assertEquals(0, large.musicSource.dataReads());

    }

    @Test
    void sharedLargeFallbackIsHashedOncePerOptimizedCapture() {
        ThreadMXBean bean = allocationBeanOrSkip();
        Fixture fixture = fixture();
        SmpsSourceDescriptor expectedFallback = SmpsSourceDescriptor.from(fixture.fallback);
        fixture.fallback.resetDataReads();

        for (int i = 0; i < 10; i++) {
            escapedSnapshot = frozenLegacyCapture(fixture);
            escapedSnapshot = fixture.driver.captureSnapshot();
        }

        long[] legacyBytes = new long[5];
        long[] optimizedBytes = new long[5];
        long[] legacyNanos = new long[5];
        long[] optimizedNanos = new long[5];
        for (int repetition = 0; repetition < legacyBytes.length; repetition++) {
            CaptureRun legacy;
            CaptureRun optimized;
            if ((repetition & 1) == 0) {
                legacy = measure(bean, fixture.fallback, () -> frozenLegacyCapture(fixture));
                validateRun(legacy, fixture, expectedFallback, SFX_COUNT, "legacy " + repetition);
                optimized = measure(bean, fixture.fallback, fixture.driver::captureSnapshot);
                validateRun(optimized, fixture, expectedFallback, 1, "optimized " + repetition);
            } else {
                optimized = measure(bean, fixture.fallback, fixture.driver::captureSnapshot);
                validateRun(optimized, fixture, expectedFallback, 1, "optimized " + repetition);
                legacy = measure(bean, fixture.fallback, () -> frozenLegacyCapture(fixture));
                validateRun(legacy, fixture, expectedFallback, SFX_COUNT, "legacy " + repetition);
            }
            legacyBytes[repetition] = legacy.bytesPerCapture;
            optimizedBytes[repetition] = optimized.bytesPerCapture;
            legacyNanos[repetition] = legacy.nanosPerCapture;
            optimizedNanos[repetition] = optimized.nanosPerCapture;
        }

        long legacyAllocationMedian = median(legacyBytes);
        long optimizedAllocationMedian = median(optimizedBytes);
        long legacyTimingMedian = median(legacyNanos);
        long optimizedTimingMedian = median(optimizedNanos);
        System.out.printf("smps fallback descriptor capture legacyBytes=%s optimizedBytes=%s "
                        + "legacyNanos=%s optimizedNanos=%s medians=%d/%d bytes %d/%d ns%n",
                Arrays.toString(legacyBytes), Arrays.toString(optimizedBytes),
                Arrays.toString(legacyNanos), Arrays.toString(optimizedNanos),
                legacyAllocationMedian, optimizedAllocationMedian,
                legacyTimingMedian, optimizedTimingMedian);
        assertTrue(optimizedAllocationMedian + 256 < legacyAllocationMedian,
                "capture-local descriptor dedup should remove repeated descriptor allocation");
        assertTrue(optimizedTimingMedian * 2 < legacyTimingMedian,
                "hashing one 256 KiB fallback should take less than half of hashing it 32 times");
    }

    private static CaptureRun measure(ThreadMXBean bean,
                                      CountingSmpsData fallback,
                                      Supplier<SmpsDriverSnapshot> capture) {
        fallback.resetDataReads();
        long threadId = Thread.currentThread().threadId();
        long allocatedBefore = bean.getThreadAllocatedBytes(threadId);
        long nanosBefore = System.nanoTime();
        long workCount = 0;
        for (int i = 0; i < CAPTURES_PER_REPETITION; i++) {
            SmpsDriverSnapshot snapshot = capture.get();
            workCount += snapshot.sequencers().size();
            escapedSnapshot = snapshot;
        }
        long elapsedNanos = System.nanoTime() - nanosBefore;
        long allocatedAfter = bean.getThreadAllocatedBytes(threadId);
        return new CaptureRun(
                (allocatedAfter - allocatedBefore) / CAPTURES_PER_REPETITION,
                elapsedNanos / CAPTURES_PER_REPETITION,
                fallback.dataReads(),
                workCount,
                escapedSnapshot);
    }

    private static long allocationSlope(
            ThreadMXBean bean, IntFunction<Object> operation) {
        long small = allocatedBytes(bean, 24, operation);
        long large = allocatedBytes(bean, 72, operation);
        return (large - small) / 48;
    }

    private static long[] repeatedAllocationSlopes(
            ThreadMXBean bean, IntFunction<Object> operation) {
        long[] slopes = new long[ALLOCATION_CONTROL_REPETITIONS];
        for (int repetition = 0; repetition < slopes.length; repetition++) {
            slopes[repetition] = allocationSlope(bean, operation);
        }
        return slopes;
    }

    private static long allocatedBytes(
            ThreadMXBean bean,
            int operations,
            IntFunction<Object> operation) {
        long threadId = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(threadId);
        for (int index = 0; index < operations; index++) {
            escapedInstantiation = operation.apply(index);
        }
        return bean.getThreadAllocatedBytes(threadId) - before;
    }

    private static long controlTolerance(long... controls) {
        if (controls.length == 0) {
            throw new IllegalArgumentException("controls must not be empty");
        }
        long minimum = controls[0];
        long maximum = controls[0];
        for (int index = 1; index < controls.length; index++) {
            minimum = Math.min(minimum, controls[index]);
            maximum = Math.max(maximum, controls[index]);
        }
        return maximum - minimum + VM_ALLOCATION_NOISE_MARGIN_BYTES;
    }

    private static void assertInstantiationStructure(
            InstantiationFixture fixture) {
        SmpsSequencer firstSfx = fixture.instantiateSfx();
        SmpsSequencer secondSfx = fixture.instantiateSfx();
        SmpsSequencer firstMusic = fixture.instantiateMusic();
        SmpsSequencer secondMusic = fixture.instantiateMusic();

        assertSame(fixture.sfxDescriptor, firstSfx.getSourceDescriptor());
        assertSame(fixture.sfxDescriptor, secondSfx.getSourceDescriptor());
        assertSame(fixture.musicDescriptor,
                firstMusic.getSourceDescriptor());
        assertSame(fixture.musicDescriptor,
                secondMusic.getSourceDescriptor());
        assertEquals(0, fixture.sfxSource.dataReads());
        assertEquals(0, fixture.musicSource.dataReads());
    }

    private static void validateRun(CaptureRun run,
                                    Fixture fixture,
                                    SmpsSourceDescriptor expectedFallback,
                                    int hashesPerCapture,
                                    String label) {
        assertEquals((long) hashesPerCapture * CAPTURES_PER_REPETITION,
                run.hashReads, label + " hash work");
        assertEquals(33L * CAPTURES_PER_REPETITION, run.workCount, label + " snapshot entry work");
        List<SmpsDriverSnapshot.SequencerEntry> entries = run.snapshot.sequencers();
        assertEquals(33, entries.size(), label + " entry count");
        assertNull(entries.getFirst().fallbackVoiceSource(), label + " music fallback");
        assertEquals(fixture.sequencers.getFirst().getSourceDescriptor(), entries.getFirst().source(),
                label + " music descriptor");
        SmpsSourceDescriptor firstFallback = entries.get(1).fallbackVoiceSource();
        assertEquals(expectedFallback, firstFallback, label + " fallback descriptor");
        for (int index = 1; index < entries.size(); index++) {
            assertEquals(fixture.sequencers.get(index).getSourceDescriptor(), entries.get(index).source(),
                    label + " source descriptor " + index);
            assertEquals(expectedFallback, entries.get(index).fallbackVoiceSource(),
                    label + " fallback descriptor " + index);
        }
        if (hashesPerCapture == 1) {
            for (int index = 2; index < entries.size(); index++) {
                assertSame(firstFallback, entries.get(index).fallbackVoiceSource(),
                        label + " should reuse one capture-local descriptor identity");
            }
        }
    }

    /** Exact pre-fix capture algorithm for this default, unlocked driver fixture. */
    private static SmpsDriverSnapshot frozenLegacyCapture(Fixture fixture) {
        IdentityHashMap<SmpsSequencer, Integer> sequencerIds = new IdentityHashMap<>();
        IdentityHashMap<AbstractSmpsData, SmpsSourceDescriptor> sourceDescriptors =
                new IdentityHashMap<>();
        for (SmpsSequencer sequencer : fixture.sequencers) {
            sourceDescriptors.put(sequencer.getSmpsData(), sequencer.getSourceDescriptor());
        }
        List<SmpsDriverSnapshot.SequencerEntry> entries = new ArrayList<>(fixture.sequencers.size());
        for (int index = 0; index < fixture.sequencers.size(); index++) {
            SmpsSequencer sequencer = fixture.sequencers.get(index);
            sequencerIds.put(sequencer, index);
            AbstractSmpsData fallback = sequencer.getFallbackVoiceData();
            SmpsSourceDescriptor fallbackDescriptor = fallback != null
                    ? sourceDescriptors.getOrDefault(fallback, SmpsSourceDescriptor.from(fallback))
                    : null;
            entries.add(new SmpsDriverSnapshot.SequencerEntry(
                    sequencer.isSfx(),
                    sequencer.getSourceDescriptor(),
                    fallbackDescriptor,
                    sequencer.getSmpsData(),
                    sequencer.getDacData(),
                    sequencer.getAudioManager(),
                    sequencer.getConfig(),
                    sequencer.captureSnapshot()));
        }
        int[] fmLocks = new int[6];
        int[] psgLocks = new int[4];
        Arrays.fill(fmLocks, -1);
        Arrays.fill(psgLocks, -1);
        // The production legacy path queried this capture-local identity table
        // for every unlocked FM/PSG slot even though all values remain -1.
        for (int index = 0; index < fmLocks.length + psgLocks.length; index++) {
            sequencerIds.get(null);
        }
        return new SmpsDriverSnapshot(
                SmpsSequencer.Region.NTSC,
                SmpsDriver.ReadMode.HYBRID,
                0,
                false,
                0,
                entries,
                fmLocks,
                psgLocks);
    }

    private static Fixture fixture() {
        SmpsDriver driver = new SmpsDriver();
        List<SmpsSequencer> sequencers = new ArrayList<>(SFX_COUNT + 1);
        SmpsSequencer music = sequencer(new CountingSmpsData(new byte[0], 0x81), driver);
        driver.addSequencer(music, false);
        sequencers.add(music);
        CountingSmpsData fallback = new CountingSmpsData(new byte[256 * 1024], 0x90);
        for (int index = 0; index < SFX_COUNT; index++) {
            SmpsSequencer sfx = sequencer(
                    new CountingSmpsData(new byte[0], 0xB0 + index), driver);
            sfx.setFallbackVoiceData(fallback);
            driver.addSequencer(sfx, true);
            sequencers.add(sfx);
        }
        fallback.resetDataReads();
        return new Fixture(driver, List.copyOf(sequencers), fallback);
    }

    private static InstantiationFixture instantiationFixture(
            String gameId, int programSize) {
        AudioPresentationSourceFactory factory =
                new AudioPresentationSourceFactory(
                        () -> true,
                        new SmpsCoordFlagHandlerOwner(
                                new SmpsCoordFlagRuntimeState()),
                        new AudioPresentationSourceFactory.Settings(
                                48_000, SmpsSequencer.Region.NTSC,
                                false, false, false, 1,
                                AudioManager.getInstance(),
                                new DecodedPcmCache(), ignored -> null),
                        SmpsSessionTestSupport.installed(48_000));
        DacData dac = new DacData(Map.of(), Map.of(), 288);
        SmpsSequencerConfig config = new SmpsSequencerConfig.Builder()
                .fmChannelOrder(new int[] {0})
                .psgChannelOrder(new int[0])
                .build();
        SizedSfxData sfx = new SizedSfxData(programSize, 0xA0);
        SmpsAssetKey sfxKey = new SmpsAssetKey(
                gameId, SmpsAssetKey.Route.BASE_ID, 0xA0, null);
        factory.registerSmpsSfxAsset(
                sfxKey, 0, sfx, dac, config, false);
        ResolvedSmpsSfxSource resolvedSfx = factory.resolveSmpsSfx(
                1, sfxKey, 1 << 16, 0x70, 0, 1, 32);
        SmpsDriver sfxOwner = new SmpsDriver();
        SmpsSequencer firstSfx = factory.instantiateCached(
                resolvedSfx, sfxOwner);
        SmpsSourceDescriptor sfxDescriptor =
                firstSfx.getSourceDescriptor();

        SizedMusicData music = new SizedMusicData(programSize, 0x81);
        AudioPresentationCommand.MusicVoiceEntry musicEntry =
                factory.musicSmps(
                        gameId, 0x81, 2, 1,
                        music, dac, config,
                        AudioSourceDescriptor.baseMusic(0x81), 32);
        AudioPresentationCommand.SmpsVoiceDescriptor musicBlueprint =
                (AudioPresentationCommand.SmpsVoiceDescriptor)
                        musicEntry.voiceDescriptor();
        SmpsSourceDescriptor musicDescriptor = musicBlueprint.activation()
                .incomingMusic().source();
        sfx.resetDataReads();
        music.resetDataReads();
        return new InstantiationFixture(
                factory, sfxOwner, resolvedSfx, musicBlueprint,
                sfx, music, sfxDescriptor, musicDescriptor);
    }

    private static SmpsSequencer sequencer(AbstractSmpsData data, SmpsDriver driver) {
        return new SmpsSequencer(
                data,
                AudioTestFixtures.EMPTY_DAC,
                driver,
                AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder().build());
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static ThreadMXBean allocationBeanOrSkip() {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(raw instanceof ThreadMXBean,
                "ThreadMXBean allocation accounting unavailable");
        ThreadMXBean bean = (ThreadMXBean) raw;
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported(),
                "thread allocation accounting unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        Assumptions.assumeTrue(bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) >= 0,
                "current-thread allocation reads unavailable");
        return bean;
    }

    private static final class CountingSmpsData extends AbstractSmpsData {
        private int dataReads;

        private CountingSmpsData(byte[] data, int id) {
            super(data, 0);
            setId(id);
        }

        @Override public byte[] getData() { dataReads++; return super.getData(); }
        int dataReads() { return dataReads; }
        void resetDataReads() { dataReads = 0; }
        @Override protected void parseHeader() { }
        @Override public byte[] getVoice(int voiceId) { return new byte[0]; }
        @Override public byte[] getPsgEnvelope(int id) { return new byte[0]; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private abstract static class SizedData extends AbstractSmpsData {
        private int dataReads;

        private SizedData(int size, int id) {
            super(program(size), 0);
            this.id = id;
            channels = 1;
            fmPointers = new int[] {1};
            fmKeyOffsets = new int[] {0};
            fmVolumeOffsets = new int[] {0};
        }

        @Override public byte[] getData() {
            dataReads++;
            return super.getData();
        }
        final int dataReads() { return dataReads; }
        final void resetDataReads() { dataReads = 0; }
        @Override protected void parseHeader() { }
        @Override public byte[] getVoice(int voiceId) {
            return voiceId == 0 ? new byte[25] : null;
        }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) {
            return ((data[offset] & 0xFF) << 8)
                    | (data[offset + 1] & 0xFF);
        }
        @Override public int getBaseNoteOffset() { return 0; }

        private static byte[] program(int size) {
            byte[] bytes = new byte[Math.max(4, size)];
            bytes[1] = (byte) 0xF2;
            return bytes;
        }
    }

    private static final class SizedMusicData extends SizedData {
        private SizedMusicData(int size, int id) {
            super(size, id);
        }
    }

    private static final class SizedSfxData extends SizedData
            implements SmpsSfxData {
        private SizedSfxData(int size, int id) {
            super(size, id);
        }
        @Override public int getTickMultiplier() { return 1; }
        @Override public List<? extends SmpsSfxTrack> getTrackEntries() {
            return List.of(new SizedSfxTrack(0, 1, 0, 0));
        }
    }

    private record SizedSfxTrack(
            int channelMask, int pointer, int transpose, int volume)
            implements SmpsSfxData.SmpsSfxTrack {
    }

    private record Fixture(
            SmpsDriver driver,
            List<SmpsSequencer> sequencers,
            CountingSmpsData fallback) {
    }

    private record InstantiationFixture(
            AudioPresentationSourceFactory factory,
            SmpsDriver sfxOwner,
            ResolvedSmpsSfxSource resolvedSfx,
            AudioPresentationCommand.SmpsVoiceDescriptor musicBlueprint,
            SizedSfxData sfxSource,
            SizedMusicData musicSource,
            SmpsSourceDescriptor sfxDescriptor,
            SmpsSourceDescriptor musicDescriptor) {
        private SmpsSequencer instantiateSfx() {
            return factory.instantiateCached(resolvedSfx, sfxOwner);
        }

        private SmpsSequencer instantiateMusic() {
            SmpsDriverSnapshot.SequencerEntry entry =
                    musicBlueprint.activation().incomingMusic();
            SmpsDriver owner = new SmpsDriver();
            SmpsSequencer sequencer = new SmpsSequencer(
                    entry.smpsData(), entry.dacData(), owner, owner,
                    entry.audioManager(), entry.config(), entry.source(),
                    entry.sourceDescriptorTrust());
            sequencer.restoreSnapshot(entry.snapshot());
            owner.addSequencer(sequencer, false);
            return sequencer;
        }
    }

    private record CaptureRun(
            long bytesPerCapture,
            long nanosPerCapture,
            long hashReads,
            long workCount,
            SmpsDriverSnapshot snapshot) {
    }
}
