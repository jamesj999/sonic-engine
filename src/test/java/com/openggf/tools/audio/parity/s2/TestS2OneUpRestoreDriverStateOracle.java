package com.openggf.tools.audio.parity.s2;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import org.junit.jupiter.api.Test;

import com.openggf.audio.S2OverrideResumeEngineCapture;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The committed S2 driver-state window that contains a 1-up jingle and the
 * restore that follows it, and the engine's fade-in-to-previous behaviour
 * measured against it.
 *
 * <p>The ROM routine under test is {@code cfFadeInToPrevious}
 * ({@code docs/s2disasm/s2.sounddriver.asm:3083-3163}), the "fade-in to
 * previous song" coordination flag the 1-up jingle's own track data runs when
 * it ends. It restores the whole {@code zAbsVar} + track region that
 * {@code zPlayMusic} backed up when the jingle started
 * ({@code s2.sounddriver.asm:1675-1724}), and then, for every music track that
 * is playing, marks the track at rest and re-arms it for the fade.
 *
 * <p>Comparison-only. Nothing here hydrates engine state; the engine produces
 * every value it is checked against, and the reference supplies only the ROM
 * expectation it is checked against.
 */
class TestS2OneUpRestoreDriverStateOracle {

    private static final String RESOURCE =
            "/audio/parity/s2/s2-driver-state-w20107-23600.reference-v2.jsonl.gz";
    private static final String GZIP_SHA256 =
            "1fa3d0bdb032b0f23d84d807833878b0ea6cd491830e408d28e0a8c8315bf786";
    private static final int FIRST_ROW = 20_107;
    private static final int EXCLUSIVE_END = 23_600;
    private static final int TICKS = 3_484;
    private static final int ZERO_SERVICE_FRAMES = 9;

    /** {@code MusID_ExtraLife} ({@code docs/s2disasm/s2.constants.asm:856}). */
    private static final int EXTRA_LIFE_ROM_MUSIC_ID = 0x98;
    /** The level music the window opens on; engine id 81h, Emerald Hill. */
    private static final int LEVEL_ROM_MUSIC_ID = 0x82;
    /** The same song on the engine side: driver 82h is engine 81h. */
    private static final int LEVEL_ENGINE_MUSIC_ID = 0x81;

    /** The service on which zPlayMusic takes its 1-up branch and backs up. */
    private static final int BACKUP_TICK = 2_875;
    /** The service on which cfFadeInToPrevious restores and starts the fade. */
    private static final int RESTORE_TICK = 3_085;

    /** {@code zTrack.PlaybackControl} bit 1: track is at rest (sd:84-90). */
    private static final int REST_BIT = 0x02;
    /** {@code zTrack.PlaybackControl} bit 2: SFX is overriding this track. */
    private static final int OVERRIDE_BIT = 0x04;
    /** The fade-in the restore arms: {@code 28h} steps (sd:3152-3153). */
    private static final int FADE_IN_STEPS = 0x28;

    private static final double SAMPLE_RATE = 44_100.0;

    // ------------------------------------------------------------------
    // The committed reference
    // ------------------------------------------------------------------

    @Test
    void committedReferenceMatchesItsPinnedDigestAndShape() throws Exception {
        assertEquals(GZIP_SHA256, S2DriverStateReference.gzipDigest(RESOURCE));
        S2DriverStateReference.Result reference = read();
        assertEquals(TICKS, reference.ticks().size());
        assertEquals(EXCLUSIVE_END - FIRST_ROW, reference.frames());
        assertEquals(ZERO_SERVICE_FRAMES, reference.zeroServiceFrames());
        assertEquals(0, reference.multiServiceFrames());
        assertEquals(reference.ticks().size() + reference.zeroServiceFrames(),
                reference.frames(),
                "every frame either completed a service or was run past by one");
    }

    /**
     * The window opens at a ROM epoch and not at a hand-chosen frame: the
     * service on which {@code zCurSong} becomes the level music id, with
     * {@code 1upPlaying} already cleared by {@code zPlayMusic}'s ordinary-load
     * branch ({@code s2.sounddriver.asm:1728-1731}).
     */
    @Test
    void theWindowOpensOnTheLevelMusicLoad() throws Exception {
        S2OracleDriverState epoch = stateAt(read(), 0);
        assertEquals(LEVEL_ROM_MUSIC_ID, epoch.globals().curSong(),
                "tick 0 must be the level music load");
        assertEquals(0, epoch.globals().oneUpPlaying(),
                "the ordinary music load clears 1upPlaying");
        assertEquals(0, epoch.globals().fadeInFlag(),
                "no fade is in flight at the epoch");
    }

    /**
     * The 1-up really is inside the window: {@code 1upPlaying} is set on
     * exactly one service and cleared on exactly one later service, and
     * nowhere else.
     */
    @Test
    void theWindowContainsOneCompleteOneUpJingleAndItsRestore() throws Exception {
        S2DriverStateReference.Result reference = read();
        List<Integer> setTicks = new ArrayList<>();
        List<Integer> clearTicks = new ArrayList<>();
        boolean previous = false;
        for (S2DriverStateReference.Tick tick : reference.ticks()) {
            boolean playing = stateAt(tick).globals().oneUpPlaying() != 0;
            if (playing && !previous) {
                setTicks.add(tick.index());
            } else if (!playing && previous) {
                clearTicks.add(tick.index());
            }
            previous = playing;
        }
        assertEquals(List.of(BACKUP_TICK), setTicks,
                "1upPlaying is set exactly once, by zPlayMusic's 1-up branch");
        assertEquals(List.of(RESTORE_TICK), clearTicks,
                "1upPlaying is cleared exactly once, by cfFadeInToPrevious");
        assertEquals(0x80, stateAt(reference, BACKUP_TICK).globals().oneUpPlaying(),
                "zPlayMusic stores 80h, not any non-zero value (sd:1711-1712)");
        assertEquals(EXTRA_LIFE_ROM_MUSIC_ID,
                stateAt(reference, BACKUP_TICK).globals().curSong(),
                "the jingle the window contains is MusID_ExtraLife");
    }

    /**
     * The ROM's restore contract, read off the recording rather than asserted
     * from the listing alone. On the restore service every playing FM and PSG
     * music track carries the rest bit ({@code sd:3107}, {@code sd:3131}), the
     * DAC carries the overriding bit the routine sets to keep it silent
     * through the fade ({@code sd:3094}), and the fade-in globals are
     * armed ({@code sd:3151-3155}).
     */
    @Test
    void theRomRestoreMarksEveryPlayingTrackAtRestAndArmsTheFade() throws Exception {
        S2OracleDriverState restore = stateAt(read(), RESTORE_TICK);

        assertEquals(0x80, restore.globals().fadeInFlag(), "FadeInFlag = 80h");
        assertEquals(FADE_IN_STEPS, restore.globals().fadeInCounter(),
                "FadeInCounter = 28h");
        assertEquals(0, restore.globals().oneUpPlaying(), "1upPlaying = 0");

        int playing = 0;
        for (S2OracleDriverState.TrackState track : restore.musicTracks()) {
            if (!track.playing()) {
                continue;
            }
            if ("DAC".equals(track.slot())) {
                assertNotEquals(0, track.playbackControl() & OVERRIDE_BIT,
                        "the DAC track is marked overridden through the fade");
                continue;
            }
            playing++;
            assertNotEquals(0, track.playbackControl() & REST_BIT,
                    track.slot() + " must be at rest after the restore");
        }
        assertTrue(playing >= 6,
                "the restored level music must have several playing tracks, saw " + playing);
    }

    /**
     * The service after the restore has already taken one fade step, so the
     * fade is armed to run immediately rather than after a delay.
     */
    @Test
    void theFadeStepsOnTheServiceAfterTheRestore() throws Exception {
        S2DriverStateReference.Result reference = read();
        S2OracleDriverState restore = stateAt(reference, RESTORE_TICK);
        S2OracleDriverState next = stateAt(reference, RESTORE_TICK + 1);
        assertEquals(FADE_IN_STEPS - 1, next.globals().fadeInCounter(),
                "the first fade step is immediate");
        for (int slot = 1; slot < restore.musicTracks().size(); slot++) {
            S2OracleDriverState.TrackState before = restore.musicTracks().get(slot);
            S2OracleDriverState.TrackState after = next.musicTracks().get(slot);
            if (!before.playing() || !after.playing()) {
                continue;
            }
            assertEquals(before.volume() - 1, after.volume(),
                    before.slot() + " attenuation decreases by one per fade step");
        }
    }

    /**
     * Control: the restore assertion is capable of failing. Clearing the rest
     * bit on one playing track in a copy of the payload must break it.
     */
    @Test
    void clearingOneRestBitBreaksTheRestoreAssertion() throws Exception {
        byte[] perturbed = payloadWithClearedRestBit();
        S2DriverStateReference.Result reference = S2DriverStateReference.read(
                new ByteArrayInputStream(perturbed), false);
        S2OracleDriverState restore = stateAt(reference, RESTORE_TICK);
        boolean anyPlayingFmOrPsgAtRest = false;
        for (S2OracleDriverState.TrackState track : restore.musicTracks()) {
            if (track.playing() && !"DAC".equals(track.slot())
                    && (track.playbackControl() & REST_BIT) == 0) {
                anyPlayingFmOrPsgAtRest = true;
            }
        }
        assertTrue(anyPlayingFmOrPsgAtRest,
                "the perturbation must produce a playing track that is not at rest");
        assertThrows(AssertionError.class, () -> {
            for (S2OracleDriverState.TrackState track : restore.musicTracks()) {
                if (track.playing() && !"DAC".equals(track.slot())) {
                    assertNotEquals(0, track.playbackControl() & REST_BIT,
                            track.slot() + " must be at rest after the restore");
                }
            }
        }, "the unperturbed assertion must reject the perturbed payload");
    }

    // ------------------------------------------------------------------
    // The engine, measured against it
    // ------------------------------------------------------------------

    /**
     * The whole window, engine against reference, driven by music requests
     * alone. The engine plays the level music, receives the extra-life request
     * at the service the recording issued it on, and the jingle's own E4 flag
     * drives the restore; nothing schedules the end of the jingle.
     *
     * <p>This does not use the BK2 replay bridge the other driver-state
     * oracles use. That bridge runs the whole S2 run chain, whose dynamic-art
     * gap axes are red for reasons unrelated to audio, and this window's replay
     * extent reaches boundaries the w10150-12400 window's does not.
     */
    @Test
    void driverStateComparesAcrossTheOneUpWindow() throws Exception {
        String romProperty = System.getProperty("sonic2.rom.path");
        assumeTrue(romProperty != null, "an explicit ROM path is required");

        S2DriverStateReference.Result reference = read();
        List<S2AudioOracleComparator.ReferenceTick> allTicks = new ArrayList<>();
        for (S2DriverStateReference.Tick tick : reference.ticks()) {
            allTicks.add(new S2AudioOracleComparator.ReferenceTick(tick.index(),
                    tick.frame(), S2DriverStateReference.rebase(tick.state()),
                    tick.writes()));
        }
        int referenceAnchor = 0;
        while (referenceAnchor < allTicks.size()
                && !hasPlayingMusicTrack(allTicks.get(referenceAnchor).state())) {
            referenceAnchor++;
        }
        assertTrue(referenceAnchor < allTicks.size(), "the window must have an anchor");
        // The request time is a stimulus read from the recording, exactly as
        // the CPZ oracle reads its SFX request ticks from a sidecar. No
        // compared value comes from the reference.
        int overrideOrdinal = BACKUP_TICK - referenceAnchor;
        int shared = allTicks.size() - referenceAnchor;

        List<S2OverrideResumeEngineCapture.OverrideTick> engineTicks =
                S2OverrideResumeEngineCapture.capture(Path.of(romProperty),
                        shared, LEVEL_ENGINE_MUSIC_ID, EXTRA_LIFE_ROM_MUSIC_ID,
                        overrideOrdinal);

        int paired = Math.min(shared, engineTicks.size());
        List<S2AudioOracleComparator.ReferenceTick> referenceSide =
                new ArrayList<>();
        List<S2OracleEngineCapture.EngineTick> engineSide = new ArrayList<>();
        List<S2AudioOracleComparator.ReferenceTick> referenceStateOnly =
                new ArrayList<>();
        List<S2OracleEngineCapture.EngineTick> engineStateOnly = new ArrayList<>();
        for (int index = 0; index < paired; index++) {
            S2AudioOracleComparator.ReferenceTick referenceTick =
                    allTicks.get(referenceAnchor + index);
            S2OverrideResumeEngineCapture.OverrideTick engineTick =
                    engineTicks.get(index);
            referenceSide.add(new S2AudioOracleComparator.ReferenceTick(index,
                    referenceTick.row(), referenceTick.state(),
                    referenceTick.writes()));
            referenceStateOnly.add(new S2AudioOracleComparator.ReferenceTick(
                    index, referenceTick.row(), referenceTick.state(), List.of()));
            engineSide.add(mapEngine(index, engineTick, true));
            engineStateOnly.add(mapEngine(index, engineTick, false));
        }

        System.out.println("MEASUREMENT_ONLY s2-driver-state-w20107-23600"
                + " reference ticks=" + reference.ticks().size()
                + " engine ticks=" + engineTicks.size()
                + " anchored at reference tick " + referenceAnchor
                + " comparing " + paired
                + " with the extra-life request at engine ordinal "
                + overrideOrdinal);
        S2AudioOracleComparator.Report withWrites =
                S2AudioOracleComparator.compareWithEngine(referenceSide, engineSide);
        System.out.println("MEASUREMENT_ONLY s2-driver-state-w20107-23600 "
                + "state and writes: " + withWrites.describe());
        S2AudioOracleComparator.Report stateOnly =
                S2AudioOracleComparator.compareWithEngine(
                        referenceStateOnly, engineStateOnly);
        System.out.println("MEASUREMENT_ONLY s2-driver-state-w20107-23600 "
                + "state only: " + stateOnly.describe());

        assertNotEquals(S2AudioOracleComparator.Kind.INVALID, stateOnly.kind(),
                stateOnly.describe());
        assertNotEquals(S2AudioOracleComparator.Kind.INVALID, withWrites.kind(),
                withWrites.describe());
        assertTrue(paired > 0, "the window must produce a paired span");
    }

    /** Maps one engine tick into the ROM slot vocabulary the comparator uses. */
    private static S2OracleEngineCapture.EngineTick mapEngine(int ordinal,
            S2OverrideResumeEngineCapture.OverrideTick tick, boolean withWrites) {
        S2OracleComparison.MappedTrack[] slots =
                new S2OracleComparison.MappedTrack[S2OracleDriverState.MUSIC_SLOTS.size()];
        for (com.openggf.audio.rewind.SmpsTrackSnapshot track
                : tick.snapshot().tracks()) {
            int slot = switch (track.type()) {
                case DAC -> 0;
                case FM -> 1 + track.channelId();
                case PSG -> 7 + track.channelId();
            };
            if (slot >= 0 && slot < slots.length && slots[slot] == null) {
                slots[slot] = S2OracleComparison.MappedTrack.fromEngine(
                        track, tick.z80Start());
            }
        }
        for (int index = 0; index < slots.length; index++) {
            if (slots[index] == null) {
                slots[index] = S2OracleComparison.MappedTrack.absent();
            }
        }
        List<S2OracleRawStream.ChipWrite> writes = new ArrayList<>();
        if (withWrites) {
            for (S2OverrideResumeEngineCapture.Write write : tick.writes()) {
                writes.add(new S2OracleRawStream.ChipWrite(write.ym(),
                        write.port(), write.register(), write.value(),
                        S2OracleRawStream.ChipWrite.SERVICE_UPDATE_MUSIC));
            }
        }
        return new S2OracleEngineCapture.EngineTick(ordinal,
                tick.snapshot().normalTempo() & 0xff,
                tick.snapshot().tempoAccumulator() & 0xff,
                List.of(slots), writes);
    }

    /** True when the reference shows at least one music track playing. */
    private static boolean hasPlayingMusicTrack(byte[] state) {
        for (S2OracleDriverState.TrackState track
                : S2OracleDriverState.decode(state).musicTracks()) {
            if (track.playing()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The engine's port of {@code cfFadeInToPrevious} is
     * {@code SmpsSequencer.triggerFadeIn}, whose only production caller is
     * {@code AbstractSmpsAudioBackend.doRestoreMusic}. This drives the real
     * ROM-backed level-music sequencer to a state where its tracks are
     * playing, runs the restore fade, and requires the engine's per-track rest
     * state to agree with what the recording shows the ROM leaving behind.
     */
    @Test
    void theEngineRestoreLeavesEveryPlayingTrackAtRestLikeTheRom() throws Exception {
        String romProperty = System.getProperty("sonic2.rom.path");
        assumeTrue(romProperty != null, "an explicit ROM path is required");

        S2OracleDriverState romRestore = stateAt(read(), RESTORE_TICK);

        Rom rom = new Rom();
        assumeTrue(rom.open(romProperty), "the verified S2 ROM must open");
        try (rom) {
            Sonic2SmpsLoader loader = new Sonic2SmpsLoader(rom);
            AbstractSmpsData song = Objects.requireNonNull(
                    loader.loadMusic(Sonic2Music.EMERALD_HILL.id),
                    "the S2 level music must load from the verified ROM");
            DacData dac = Objects.requireNonNull(loader.loadDacData(),
                    "S2 DAC data must load from the verified ROM");

            try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                    "s2-one-up-restore", 0,
                    new SmpsPhysicalDevice.Settings(SAMPLE_RATE, false),
                    LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE,
                    ChipWriteObserver.NONE)) {
                SmpsDriver driver = stream.logicalDriver();
                SmpsSequencer sequencer = new SmpsSequencer(song, dac, driver,
                        () -> { }, Sonic2SmpsSequencerConfig.CONFIG);
                sequencer.setSampleRate(SAMPLE_RATE);
                driver.addSequencer(sequencer, false);

                // Let the song reach a steady state with notes keyed on, the
                // state the 1-up jingle interrupts and the restore returns to.
                for (int update = 0; update < 240; update++) {
                    driver.serviceOuterFrame();
                }

                SmpsSequencerSnapshot before = sequencer.captureSnapshot();
                int playingBefore = 0;
                for (SmpsTrackSnapshot track : before.tracks()) {
                    if (track.active() && track.type() != SmpsSequencer.TrackType.DAC) {
                        playingBefore++;
                    }
                }
                assertTrue(playingBefore >= 6,
                        "the engine song must have several playing tracks before the"
                                + " restore, saw " + playingBefore);

                sequencer.triggerFadeIn(FADE_IN_STEPS,
                        Sonic2SmpsSequencerConfig.CONFIG.getFadeInDelay());

                SmpsSequencerSnapshot after = sequencer.captureSnapshot();
                List<String> failures = new ArrayList<>();
                for (SmpsTrackSnapshot track : after.tracks()) {
                    if (!track.active() || track.type() == SmpsSequencer.TrackType.DAC) {
                        continue;
                    }
                    String slot = track.type() == SmpsSequencer.TrackType.FM
                            ? "FM" + (track.channelId() + 1)
                            : "PSG" + (track.channelId() + 1);
                    S2OracleDriverState.TrackState romTrack = romSlot(romRestore, slot);
                    if (romTrack == null || !romTrack.playing()) {
                        continue;
                    }
                    boolean romAtRest = (romTrack.playbackControl() & REST_BIT) != 0;
                    if (track.resting() != romAtRest) {
                        failures.add(slot + ": engine resting=" + track.resting()
                                + " against the ROM's " + romAtRest);
                    }
                }
                assertEquals(List.of(), failures,
                        "cfFadeInToPrevious marks every playing FM and PSG track at rest"
                                + " (s2.sounddriver.asm:3107, :3131); the engine's restore"
                                + " must leave the same per-track rest state");
            }
        }
    }

    /**
     * The attenuation half of the same routine: the restore adds
     * {@code 28h - FadeInCounter} to every playing track's volume
     * ({@code sd:3098-3099, :3109, :3134}). With no fade in flight
     * that is the full {@code 28h}.
     */
    @Test
    void theEngineRestoreAttenuatesEveryPlayingTrackByTheFullFadeDepth() throws Exception {
        String romProperty = System.getProperty("sonic2.rom.path");
        assumeTrue(romProperty != null, "an explicit ROM path is required");

        Rom rom = new Rom();
        assumeTrue(rom.open(romProperty), "the verified S2 ROM must open");
        try (rom) {
            Sonic2SmpsLoader loader = new Sonic2SmpsLoader(rom);
            AbstractSmpsData song = Objects.requireNonNull(
                    loader.loadMusic(Sonic2Music.EMERALD_HILL.id));
            DacData dac = Objects.requireNonNull(loader.loadDacData());
            try (OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                    "s2-one-up-restore-volume", 0,
                    new SmpsPhysicalDevice.Settings(SAMPLE_RATE, false),
                    LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE,
                    ChipWriteObserver.NONE)) {
                SmpsDriver driver = stream.logicalDriver();
                SmpsSequencer sequencer = new SmpsSequencer(song, dac, driver,
                        () -> { }, Sonic2SmpsSequencerConfig.CONFIG);
                sequencer.setSampleRate(SAMPLE_RATE);
                driver.addSequencer(sequencer, false);
                for (int update = 0; update < 240; update++) {
                    driver.serviceOuterFrame();
                }
                SmpsSequencerSnapshot before = sequencer.captureSnapshot();
                sequencer.triggerFadeIn(FADE_IN_STEPS,
                        Sonic2SmpsSequencerConfig.CONFIG.getFadeInDelay());
                SmpsSequencerSnapshot after = sequencer.captureSnapshot();

                for (SmpsTrackSnapshot track : after.tracks()) {
                    if (track.type() == SmpsSequencer.TrackType.DAC) {
                        continue;
                    }
                    SmpsTrackSnapshot original = trackAt(before, track.type(),
                            track.channelId());
                    if (original == null) {
                        continue;
                    }
                    assertEquals(original.volumeOffset() + FADE_IN_STEPS,
                            track.volumeOffset(),
                            "the restore attenuates every non-DAC track by 28h");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static S2DriverStateReference.Result read() throws Exception {
        try (InputStream input = S2DriverStateReference.open(RESOURCE)) {
            return S2DriverStateReference.read(input, false);
        }
    }

    private static S2OracleDriverState stateAt(S2DriverStateReference.Result reference,
            int tick) {
        return stateAt(reference.ticks().get(tick));
    }

    private static S2OracleDriverState stateAt(S2DriverStateReference.Tick tick) {
        return S2OracleDriverState.decode(S2DriverStateReference.rebase(tick.state()));
    }

    private static S2OracleDriverState.TrackState romSlot(S2OracleDriverState state,
            String slot) {
        for (S2OracleDriverState.TrackState track : state.musicTracks()) {
            if (track.slot().equals(slot)) {
                return track;
            }
        }
        return null;
    }

    private static SmpsTrackSnapshot trackAt(SmpsSequencerSnapshot snapshot,
            SmpsSequencer.TrackType type, int channelId) {
        for (SmpsTrackSnapshot track : snapshot.tracks()) {
            if (track.type() == type && track.channelId() == channelId) {
                return track;
            }
        }
        return null;
    }

    /**
     * A copy of the committed payload with the rest bit cleared on one playing
     * FM track of the restore service. Only the perturbation control reads it.
     */
    private static byte[] payloadWithClearedRestBit() throws Exception {
        StringBuilder rebuilt = new StringBuilder();
        int index = -1;
        try (InputStream input = S2DriverStateReference.open(RESOURCE);
             java.io.BufferedReader reader = new java.io.BufferedReader(
                     new java.io.InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"row\":\"tick\"")) {
                    index++;
                }
                if (index == RESTORE_TICK && line.contains("\"ram\"")) {
                    line = clearRestBitInRam(line);
                }
                rebuilt.append(line).append('\n');
            }
        }
        // The reader takes an already-decompressed stream.
        return rebuilt.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Clears PlaybackControl bit 1 of the FM1 music slot in one ram field. */
    private static String clearRestBitInRam(String line) {
        int marker = line.indexOf("\"ram\":\"");
        int start = marker + "\"ram\":\"".length();
        int end = line.indexOf('"', start);
        String hex = line.substring(start, end);
        // FM1 music slot: 1B98h + 2Ah, PlaybackControl at offset 0, window base 12FEh.
        int byteIndex = (0x1b98 + 0x2a) - 0x12fe;
        int at = byteIndex * 2;
        int value = Integer.parseInt(hex.substring(at, at + 2), 16) & ~REST_BIT;
        String patched = hex.substring(0, at)
                + String.format("%02x", value) + hex.substring(at + 2);
        return line.substring(0, start) + patched + line.substring(end);
    }
}
