package com.openggf.audio;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ROM-backed headless capture of a Sonic 2 music override and the restore that
 * follows it, driven by music requests alone.
 *
 * <p>The existing {@code S2OracleEngineCapture} drives {@link SmpsDriver}
 * directly, and the driver has no music-replacement entry point: the ROM's
 * single 1-up save slot lives one layer up, in
 * {@link AbstractSmpsAudioBackend}. So an override window has to be captured
 * through the backend, which is what this class does. It is a headless
 * {@link HeadlessSmpsAudioBackend}, so no audio device is involved and the
 * whole save-slot and restore path is the production one.
 *
 * <p>Requests are the only stimulus, and they are fed by id: the capture hands
 * the backend a song and the {@link Sonic2AudioProfile} classifies it, exactly
 * as the 68k hands the driver a request byte and {@code zPlayMusic} decides
 * whether it is the extra-life branch ({@code s2.sounddriver.asm:1675-1724}).
 * Nothing here reads the reference, and the end of the jingle is not scheduled:
 * the jingle's own {@code E4} coordination flag reaches
 * {@code SmpsSequencer.handleFadeIn}, which calls {@code restoreMusic}, and the
 * capture only services the restore the backend has already requested.
 */
public final class S2OverrideResumeEngineCapture {

    private S2OverrideResumeEngineCapture() {
    }

    /** One completed engine driver update: the music sequencer's state and its writes. */
    public record OverrideTick(int ordinal, SmpsSequencerSnapshot snapshot,
            int z80Start, boolean overrideActive, List<Write> writes) {
        public OverrideTick {
            writes = List.copyOf(writes);
        }
    }

    /** One ordered chip write, in the shape the S2 oracle comparators use. */
    public record Write(boolean ym, int port, int register, int value) {
    }

    /**
     * Plays {@code baseMusicId}, requests {@code overrideMusicId} on
     * {@code overrideTick}, and services {@code tickCount} driver updates.
     *
     * @param overrideTick the ordinal on which the override request is issued,
     *                     or a negative value for no override at all
     */
    public static List<OverrideTick> capture(Path romPath, int tickCount,
            int baseMusicId, int overrideMusicId, int overrideTick) {
        Objects.requireNonNull(romPath, "romPath");
        if (tickCount <= 0) {
            throw new IllegalArgumentException("tickCount must be positive");
        }
        Rom rom = new Rom();
        if (!rom.open(romPath.toString())) {
            throw new IllegalArgumentException("cannot open the S2 ROM");
        }
        try (rom) {
            Sonic2SmpsLoader loader = new Sonic2SmpsLoader(rom);
            AbstractSmpsData base = Objects.requireNonNull(
                    loader.loadMusic(baseMusicId),
                    "the base song is absent from the ROM");
            AbstractSmpsData override = overrideTick >= 0
                    ? Objects.requireNonNull(loader.loadMusic(overrideMusicId),
                            "the override song is absent from the ROM")
                    : null;
            DacData dacData = Objects.requireNonNull(loader.loadDacData(),
                    "S2 DAC data is absent from the ROM");

            HeadlessSmpsAudioBackend backend = new HeadlessSmpsAudioBackend(
                    SonicConfigurationService.getInstance(), null);
            try {
                backend.setAudioProfile(new Sonic2AudioProfile());
                backend.playSmps(base, dacData,
                        Sonic2SmpsSequencerConfig.CONFIG, false);

                List<OverrideTick> ticks = new ArrayList<>(tickCount);
                WriteRecorder recorder = new WriteRecorder();
                OwnedSmpsAudioStream observed = null;
                for (int ordinal = 0; ordinal < tickCount; ordinal++) {
                    if (ordinal == overrideTick) {
                        // The request byte is the only stimulus; the profile
                        // classifies it as the extra-life override, which is
                        // what pushes the ROM's single save slot.
                        backend.playSmps(override, dacData,
                                Sonic2SmpsSequencerConfig.CONFIG, false);
                    }
                    // The jingle's own E4 flag asks for the restore. Service it
                    // at the update boundary, never on a schedule of our own.
                    if (backend.pendingRestore) {
                        backend.pendingRestore = false;
                        backend.doRestoreMusic();
                    }
                    AbstractSmpsAudioBackend.StateForTesting state =
                            backend.stateForTesting();
                    if (state.currentStream() instanceof OwnedSmpsAudioStream owned
                            && owned != observed) {
                        owned.setChipWriteObserver(recorder);
                        observed = owned;
                    }
                    SmpsDriver driver = backend.musicDriverForTesting();
                    if (driver == null) {
                        break;
                    }
                    driver.serviceOuterFrame();
                    AbstractSmpsAudioBackend.StateForTesting after =
                            backend.stateForTesting();
                    SmpsSequencer music = after.currentSmps();
                    if (music == null) {
                        break;
                    }
                    ticks.add(new OverrideTick(ordinal, music.captureSnapshot(),
                            music.getSmpsData().getZ80StartAddress(),
                            !after.overrideStack().isEmpty(), recorder.drain()));
                }
                return ticks;
            } finally {
                backend.destroy();
            }
        }
    }

    private static final class WriteRecorder implements ChipWriteObserver {
        private final List<Write> writes = new ArrayList<>();

        @Override
        public void onYm2612Write(int port, int register, int value) {
            writes.add(new Write(true, port, register, value));
        }

        @Override
        public void onPsgWrite(int value) {
            writes.add(new Write(false, 0, 0, value));
        }

        private List<Write> drain() {
            List<Write> drained = List.copyOf(writes);
            writes.clear();
            return drained;
        }
    }
}
