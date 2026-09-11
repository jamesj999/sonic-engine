package com.openggf.tools.audio.parity;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.session.SmpsDriverSessionConfiguration;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.data.Rom;
import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic1.audio.smps.Sonic1SmpsLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * ROM-backed test host that replays the S1 sound-test SFX reference's request
 * sequence through the real {@link SmpsDriver}: GHZ music exactly as
 * {@link S1OpenGgfAudioCapture}, plus one SFX sequencer admission per recorded
 * dispatch, applied on the recorded invocation ordinal before that tick's
 * driver service — the ROM dispatches a queued sound (PlaySoundID) before the
 * track walk of the same UpdateMusic invocation.
 */
public final class S1OpenGgfSfxAudioCapture {
    private static final double SAMPLE_RATE = 44_100.0;

    private S1OpenGgfSfxAudioCapture() {
    }

    public record CaptureResult(int recordCount, int dispatchCount) {
    }

    public static CaptureResult capture(Path reference, Path romPath, Path output) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(romPath, "romPath");
        Objects.requireNonNull(output, "output");
        AudioParityMetadata referenceMetadata = S1OpenGgfAudioCapture.readReferenceMetadata(reference);
        boolean gameplay = AudioParitySchema.GAMEPLAY_REFERENCE_CAPTURE.equals(referenceMetadata.capture());
        boolean runWindow =
                AudioParitySchema.RUN_WINDOW_REFERENCE_CAPTURE.equals(referenceMetadata.capture());
        if (!gameplay && !runWindow
                && !AudioParitySchema.SFX_REFERENCE_CAPTURE.equals(referenceMetadata.capture())) {
            throw new IllegalArgumentException(
                    "reference stream is not a BizHawk S1 SFX, gameplay or run-window capture");
        }
        // The sound-test and gameplay kinds are GHZ by construction: their
        // epoch is the GHZ1 level-start request. A per-song run window names
        // its own epoch song, and the host loads that one.
        int musicId = runWindow
                ? referenceMetadata.details().get("music_id").intValue()
                : Sonic1Music.GHZ.id;

        Map<Integer, List<Integer>> dispatchesByOrdinal = new HashMap<>();
        AudioParityJsonl.read(reference, tick -> {
            List<Integer> dispatches = tick.dispatches();
            if (dispatches != null && !dispatches.isEmpty()) {
                dispatchesByOrdinal.put(tick.ordinal(), dispatches);
            }
        });

        S1OpenGgfAudioCapture.verifyRomIdentity(romPath);
        try (Rom rom = S1OpenGgfAudioCapture.openRom(romPath)) {
            Sonic1SmpsLoader loader = new Sonic1SmpsLoader(rom);
            AbstractSmpsData song = Objects.requireNonNull(loader.loadMusic(musicId),
                    "S1 music 0x" + Integer.toHexString(musicId)
                            + " is absent from the verified ROM");
            DacData dacData = Objects.requireNonNull(loader.loadDacData(),
                    "S1 DAC data is absent from the verified ROM");
            S1OpenGgfAudioCapture.SongContract contract =
                    S1OpenGgfAudioCapture.inspectSong(rom, song, musicId);

            AudioParityMetadata outputMetadata;
            if (runWindow) {
                outputMetadata = AudioParityMetadata.openGgfRunWindow(
                        referenceMetadata.terminalRecordCount(), referenceMetadata.romSha1(),
                        referenceMetadata.romCrc32());
            } else if (gameplay) {
                outputMetadata = AudioParityMetadata.openGgfGameplay(
                        referenceMetadata.terminalRecordCount(), referenceMetadata.romSha1(),
                        referenceMetadata.romCrc32());
            } else {
                outputMetadata = AudioParityMetadata.openGgfSfx(
                        referenceMetadata.terminalRecordCount(), referenceMetadata.romSha1(),
                        referenceMetadata.romCrc32());
            }
            try (CaptureIterator ticks = new CaptureIterator(
                    loader, song, dacData, contract,
                    referenceMetadata.terminalRecordCount(),
                    dispatchesByOrdinal)) {
                AudioParityJsonl.writeNew(output, outputMetadata, ticks);
                return new CaptureResult(
                        referenceMetadata.terminalRecordCount(),
                        ticks.dispatchCount);
            }
        }
    }

    private static final class CaptureIterator
            implements Iterator<AudioParityTick>, ChipWriteObserver,
            AutoCloseable {
        private final OwnedSmpsAudioStream stream;
        private final Sonic1SmpsLoader loader;
        private final AbstractSmpsData song;
        private final DacData dacData;
        private final SmpsDriver driver;
        private final SmpsSequencer musicSequencer;
        private final S1OpenGgfAudioCapture.SongContract contract;
        private final Sonic1AudioProfile profile = new Sonic1AudioProfile();
        private final int terminalCount;
        private final Map<Integer, List<Integer>> dispatchesByOrdinal;
        private final List<AudioParityChipWrite> writes = new ArrayList<>();
        private int ordinal;
        private int dispatchCount;
        private int ringSpeaker;

        private CaptureIterator(Sonic1SmpsLoader loader, AbstractSmpsData song, DacData dacData,
                S1OpenGgfAudioCapture.SongContract contract, int terminalCount,
                Map<Integer, List<Integer>> dispatchesByOrdinal) {
            this.loader = loader;
            this.song = song;
            this.dacData = dacData;
            this.contract = contract;
            this.terminalCount = terminalCount;
            this.dispatchesByOrdinal = dispatchesByOrdinal;
            stream = new OwnedSmpsAudioStream(
                    "s1-sfx-parity", 0,
                    new SmpsPhysicalDevice.Settings(
                            SAMPLE_RATE, false),
                    LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE,
                    ChipWriteObserver.NONE,
                    new SmpsDriverSessionConfiguration(profile.smpsStatefulCommandPolicy()));
            driver = stream.logicalDriver();
            musicSequencer = new SmpsSequencer(song, dacData, driver, () -> { },
                    Sonic1SmpsSequencerConfig.CONFIG);
            musicSequencer.setSampleRate(SAMPLE_RATE);
            driver.addSequencer(musicSequencer, false);
            stream.setChipWriteObserver(this);
            S1OpenGgfAudioCapture.CaptureIterator.initializeS1MusicPlayback(driver, song);
        }

        @Override
        public void close() {
            stream.close();
        }

        /**
         * S1 {@code FadeOutMusic} (s1.sounddriver.asm:1360-1367): stop the SFX
         * and special-SFX tracks, arm a fade of {@code $28} steps three frames
         * apart, stop the DAC track, and clear the speed-shoes tempo flag.
         * {@code triggerFadeOut} performs the step/delay arming and the DAC
         * stop, so only the two stops and the flag clear are done here.
         */
        private static final int S1_FADE_OUT_STEPS = 0x28;
        private static final int S1_FADE_OUT_DELAY = 3;

        /**
         * The parts of {@code FadeOutMusic} that must not run inside the
         * driver's own service. {@code StopSFX} removes sequencers, and the
         * driver iterates its sequencer list by index over a size captured
         * before the pass, so removing from inside the dispatch point --
         * which runs within the music sequencer's service -- walks off the end
         * of the shortened list. The ROM stops the SFX tracks before it walks
         * any track (s1.sounddriver.asm:1361-1362), and so does this: nothing
         * between here and the dispatch point reads SFX state, since the fade
         * step neither reads nor writes it, so the two positions are
         * observationally the same and this one is safe.
         */
        private void submitFlagCommandStops(int soundId) {
            if (soundId == 0xE4) {
                // StopAllSound (s1.sounddriver.asm:1461-1482) clears the
                // driver's variable and track RAM and silences both chips. Like
                // the fade's stops this runs before the service rather than at
                // the dispatch point, because it releases sequencers and the
                // driver iterates its list by index over a size captured before
                // the pass; the ROM likewise clears before it walks any track.
                // StopAllSound's own order (s1.sounddriver.asm:1461-1482):
                // enable the DAC, put FM3/FM6 back in normal mode with the
                // timers off, clear the driver's variable and track RAM, then
                // FMSilenceAll and PSGSilenceAll.
                driver.writeFm(musicSequencer, 0, 0x2B, 0x80);
                driver.writeFm(musicSequencer, 0, 0x27, 0x00);
                driver.stopAllSfx();
                musicSequencer.applyStopAllSound();
                driver.silenceAll();
                return;
            }
            if (soundId != 0xE0) {
                return;
            }
            stream.prepareFadeOut();
        }

        private void submitFlagCommand(int soundId) {
            if (soundId == 0xE4) {
                // Wholly handled before the service; nothing is armed here.
                return;
            }
            if (soundId != 0xE0) {
                // $E1 PlaySegaSound, $E2 SpeedUpMusic, $E3 SlowDownMusic and
                // $E4 StopAllSound reach the driver through the same
                // Sound_E0toE4 branch (s1.sounddriver.asm:715) but are not
                // modelled here yet. Failing loudly keeps a window that
                // contains one from being compared as though the request never
                // happened.
                throw new IllegalStateException(
                        "S1 driver flag command 0x" + Integer.toHexString(soundId)
                                + " is not modelled by the parity host yet");
            }
            // The stops already ran before the service; only the arming, which
            // is what the fade step's position actually decides, belongs here.
            stream.armFadeOut(S1_FADE_OUT_STEPS, S1_FADE_OUT_DELAY);
        }

        /**
         * Driver commands deferred to the ROM's dispatch point. S1 steps the
         * fade before it cycles the sound queue (s1.sounddriver.asm:179-202),
         * so a fade armed by a request is not stepped until the next
         * invocation. Submitting these before the whole frame service would
         * let the engine step the fade in the invocation that armed it.
         *
         * <p>SFX admission stays at the pre-service point: nothing between
         * there and the dispatch point touches it, since the fade step does not
         * read or write SFX admission, so the two positions are observationally
         * the same for an SFX and the pre-service one keeps a newly admitted
         * SFX in the same frame's walk as the ROM does.
         */
        private final List<Integer> deferredFlagCommands = new ArrayList<>();

        private void submitDispatches(List<Integer> dispatches) {
            for (int requestedSoundId : dispatches) {
                int soundId = requestedSoundId;
                if (soundId >= 0xE0) {
                    submitFlagCommandStops(soundId);
                    deferredFlagCommands.add(soundId);
                    continue;
                }
                if (soundId == 0xB5) {
                    // S1 Sound_PlaySFX substitutes the left-speaker program CE
                    // while v_ring_speaker is zero, then toggles the source-owned
                    // bit for the next ring request (SD:984-991).
                    if (ringSpeaker == 0) {
                        soundId = 0xCE;
                    }
                    ringSpeaker ^= 1;
                }
                AbstractSmpsData sfx = Objects.requireNonNull(loader.loadSfx(soundId),
                        "recorded SFX id is absent from the verified ROM: 0x"
                                + Integer.toHexString(soundId));
                SmpsSequencer sequencer = new SmpsSequencer(sfx, dacData, driver, () -> { },
                        Sonic1SmpsSequencerConfig.CONFIG);
                sequencer.setSampleRate(SAMPLE_RATE);
                sequencer.setSfxMode(true);
                sequencer.setSfxPriority(profile.getSfxPriority(soundId));
                // A recorded id >= NORMAL_ID_MAX+1 came through the probe's
                // Sound_PlaySpecial hook (s1_gameplay_driver_parity_probe.lua),
                // not Sound_PlaySFX -- the ROM dispatches these through a
                // separate routine (s1.sounddriver.asm:1117) with its own
                // admission gates (1-up / fadeout / fadein checks). The id
                // alone disambiguates the two dispatch entries.
                sequencer.setSpecialSfx(profile.isSpecialSfx(soundId));
                sequencer.setFallbackVoiceData(song);
                driver.addSequencer(sequencer, true);
                dispatchCount++;
            }
        }

        @Override
        public boolean hasNext() {
            return ordinal < terminalCount;
        }

        @Override
        public AudioParityTick next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            List<Integer> dispatches = dispatchesByOrdinal.getOrDefault(ordinal, List.of());
            submitDispatches(dispatches);
            musicSequencer.setDispatchPointListener(() -> {
                for (int soundId : deferredFlagCommands) {
                    submitFlagCommand(soundId);
                }
                deferredFlagCommands.clear();
            });
            if (ordinal == 0) {
                musicSequencer.advanceSamples(0);
            } else {
                // ROM walk order inside one UpdateMusic invocation: music tracks
                // first, SFX tracks after — the driver's sequencer list preserves
                // that order (music added first, SFX in admission order).
                driver.serviceOuterFrame();
            }
            driver.reapCompletedSequencers();
            SmpsSequencerSnapshot snapshot = musicSequencer.captureSnapshot();
            S1AudioStateNormalizer.NormalizedState state = S1AudioStateNormalizer.normalize(
                    snapshot, contract.assetRange(), contract.f7LoopIndices());
            AudioParityTick tick = new AudioParityTick(ordinal, state.global(), state.tracks(),
                    List.copyOf(writes), dispatches);
            writes.clear();
            ordinal++;
            return tick;
        }

        @Override
        public void onYm2612Write(int port, int register, int value) {
            writes.add(AudioParityChipWrite.ym2612(port, register, value));
        }

        @Override
        public void onPsgWrite(int value) {
            writes.add(AudioParityChipWrite.psg(value));
        }
    }
}
