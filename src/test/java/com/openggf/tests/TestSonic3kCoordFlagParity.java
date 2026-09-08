package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.audio.presentation.AudioPresentationCommand;
import com.openggf.audio.presentation.AudioPresentationMixer;
import com.openggf.audio.presentation.AudioPresentationSessionCommandApplier;
import com.openggf.audio.presentation.AudioPresentationSourceFactory;
import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.SmpsAssetKey;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.session.SmpsSessionTestSupport;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.audio.smps.Sonic3kCoordFlagHandler;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSfxData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic3kCoordFlagParity {

    private static final DacData EMPTY_DAC = new DacData(new HashMap<>(), new HashMap<>(), 297);

    private static class FmWrite {
        final int port;
        final int reg;
        final int val;

        FmWrite(int port, int reg, int val) {
            this.port = port;
            this.reg = reg;
            this.val = val;
        }
    }

    private static class CaptureSynth extends VirtualSynthesizer {
        final List<FmWrite> fmWrites = new ArrayList<>();
        final List<Integer> psgWrites = new ArrayList<>();

        @Override
        public void writeFm(Object source, int port, int reg, int val) {
            fmWrites.add(new FmWrite(port, reg & 0xFF, val & 0xFF));
            super.writeFm(source, port, reg, val);
        }

        @Override
        public void writePsg(Object source, int val) {
            psgWrites.add(val & 0xFF);
            super.writePsg(source, val);
        }
    }

    @Test
    public void e5UsesSecondParamAsFmVolumeDeltaAndDoesNotLoadVoice() {
        byte[] fmTrack = {
                (byte) 0xEF, 0x00,       // Load voice 0
                (byte) 0xE5, 0x7F, 0x01, // E5: first byte ignored, second byte = +1 volume
                (byte) 0xF2              // Stop
        };
        Sonic3kSmpsData smps = createMusicData(2, 0, fmTrack, null, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, new CaptureSynth(), Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(20000);

        SmpsSequencer.Track fm = findTrack(seq, SmpsSequencer.TrackType.FM);
        assertEquals(0, fm.voiceId, "E5 must not switch instrument on S3K");
        assertEquals(1, fm.volumeOffset, "E5 must apply second byte as FM volume delta");
    }

    @Test
    public void edSetsTransposeToParamMinus40() {
        byte[] fmTrack = {
                (byte) 0xED, 0x41, // key offset = +1
                (byte) 0xF2
        };
        Sonic3kSmpsData smps = createMusicData(2, 0, fmTrack, null, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, new CaptureSynth(), Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(20000);

        SmpsSequencer.Track fm = findTrack(seq, SmpsSequencer.TrackType.FM);
        assertEquals(1, fm.keyOffset);
    }

    @Test
    public void e4AppliesS3kAbsoluteVolumeScalingForFmAndPsg() {
        byte[] fmTrack = {
                (byte) 0xE4, 0x00, // FM -> 0x7F attenuation
                (byte) 0xF2
        };
        byte[] psgTrack = {
                (byte) 0xE4, 0x78, // PSG -> 0x00 attenuation
                (byte) 0xF2
        };
        Sonic3kSmpsData smps = createMusicData(2, 1, fmTrack, psgTrack, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, new CaptureSynth(), Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(20000);

        SmpsSequencer.Track fm = findTrack(seq, SmpsSequencer.TrackType.FM);
        SmpsSequencer.Track psg = findTrack(seq, SmpsSequencer.TrackType.PSG);
        assertEquals(0x7F, fm.volumeOffset);
        assertEquals(0x00, psg.volumeOffset);
    }

    @Test
    public void ecUsesUnsignedClipBehaviorForPsgVolume() {
        byte[] psgTrack = {
                (byte) 0xEC, (byte) 0xFF, // 0 + (-1) -> 0xFF -> clipped to 0x0F
                (byte) 0xF2
        };
        Sonic3kSmpsData smps = createMusicData(2, 1, new byte[] { (byte) 0xF2 }, psgTrack, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, new CaptureSynth(), Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(20000);

        SmpsSequencer.Track psg = findTrack(seq, SmpsSequencer.TrackType.PSG);
        assertEquals(0x0F, psg.volumeOffset);
    }

    @Test
    public void f3SetResetTogglesNoiseMode() {
        byte[] psgTrack = {
                (byte) 0xF3, (byte) 0xE7, // set noise
                (byte) 0xF3, 0x00,        // reset noise
                (byte) 0xF2
        };
        CaptureSynth synth = new CaptureSynth();
        Sonic3kSmpsData smps = createMusicData(2, 1, new byte[] { (byte) 0xF2 }, psgTrack, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, synth, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(20000);

        SmpsSequencer.Track psg = findTrack(seq, SmpsSequencer.TrackType.PSG);
        assertEquals(0, psg.psgNoiseParam);
        assertTrue(synth.psgWrites.contains(0xFF), "Expected PSG noise reset write");
    }

    @Test
    public void musicF2StopsWithOnlyItsDriverSilenceTransaction() {
        CaptureSynth toneSynth = new CaptureSynth();
        SmpsSequencer tone = new SmpsSequencer(createMusicData(2, 1,
                new byte[] {(byte) 0xF2}, new byte[] {(byte) 0xF2}, null),
                EMPTY_DAC, toneSynth, Sonic3kSmpsSequencerConfig.CONFIG);
        toneSynth.psgWrites.clear();
        tone.advanceSamples(20000);
        assertEquals(List.of(0x9F, 0xFF), toneSynth.psgWrites);
        assertFalse(findTrack(tone, SmpsSequencer.TrackType.PSG).active);

        CaptureSynth noiseSynth = new CaptureSynth();
        SmpsSequencer noise = new SmpsSequencer(createMusicData(2, 1,
                new byte[] {(byte) 0xF2},
                new byte[] {(byte) 0xF3, (byte) 0xE7, (byte) 0xF2}, null),
                EMPTY_DAC, noiseSynth, Sonic3kSmpsSequencerConfig.CONFIG);
        noiseSynth.psgWrites.clear();
        noise.advanceSamples(20000);
        assertEquals(List.of(0xDF, 0xE7, 0x9F, 0xFF, 0xFF), noiseSynth.psgWrites);
        SmpsSequencer.Track stoppedNoise = findTrack(
                noise, SmpsSequencer.TrackType.PSG);
        assertFalse(stoppedNoise.active);
        assertFalse(stoppedNoise.resting,
                "music F2 has no SFX release callback that normalizes rest");
    }

    @Test
    public void fdRawFreqReadsWordAndAppliesRawFrequency() {
        byte[] fmTrack = {
                (byte) 0xFD, 0x01, // raw frequency mode on
                (byte) 0x84, 0x02, // packed frequency = 0x0284
                0x01,              // duration
                (byte) 0xF2
        };
        CaptureSynth synth = new CaptureSynth();
        Sonic3kSmpsData smps = createMusicData(2, 0, fmTrack, null, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, synth, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(25000);

        boolean wroteA4 = false;
        boolean wroteA0 = false;
        for (FmWrite w : synth.fmWrites) {
            if (w.reg == 0xA4 && w.val == 0x02) {
                wroteA4 = true;
            }
            if (w.reg == 0xA0 && w.val == 0x84) {
                wroteA0 = true;
            }
        }
        assertTrue(wroteA4, "Raw frequency mode should write A4 from raw word");
        assertTrue(wroteA0, "Raw frequency mode should write A0 from raw word");
    }

    @Test
    public void s3kUsesDefZ80Type2PsgTable() {
        byte[] psgTrack = {
                (byte) 0x81, 0x01, // first note
                (byte) 0xF2
        };
        CaptureSynth synth = new CaptureSynth();
        Sonic3kSmpsData smps = createMusicData(1, 1, null, psgTrack, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, synth, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(25000);

        // DEF_Z80_T2 first entry is 0x3FF, so channel 0 writes should include 0x8F then 0x3F.
        assertTrue(synth.psgWrites.contains(0x8F));
        assertTrue(synth.psgWrites.contains(0x3F));
    }

    @Test
    public void psgNegativeTransposeClampsToLowestNoteInsteadOfSkipping() {
        byte[] psgTrack = {
                (byte) 0xFB, (byte) 0xFF, // transpose -1
                (byte) 0x81, 0x01,        // first note should clamp to lowest table entry
                (byte) 0xF2
        };
        CaptureSynth synth = new CaptureSynth();
        Sonic3kSmpsData smps = createMusicData(1, 1, null, psgTrack, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, synth, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(25000);

        assertTrue(synth.psgWrites.contains(0x8F), "PSG note should still write frequency after negative transpose");
        assertTrue(synth.psgWrites.contains(0x3F), "Lowest S3K PSG entry should write high byte 0x3F");
    }

    /**
     * The S3K driver keeps the PSG frequency in a 16-bit register and never
     * masks it. {@code zUpdateFreq} adds the sign-extended detune to the stored
     * word (skdisasm Sound/Z80 Sound Driver.asm:3080-3101), so period zero minus
     * one is 0FFFFh, not 03FFh. {@code zUpdatePSGTrack} then latches
     * {@code l & 0Fh} and sends the nibble swap of {@code (l & 0F0h) | h}, with
     * no six-bit mask (:4085-4095), giving 08Fh followed by 0FFh. S2 masks that
     * byte (s2.sounddriver.asm:2835-2842); S3K does not.
     */
    @Test
    public void psgDetuneUnderflowKeepsTheWholeSixteenBitPeriod() {
        byte[] psgTrack = {
                (byte) 0xE1, (byte) 0xFF, // detune -1
                (byte) 0xD4, 0x01,        // high note -> base period 0x000 in DEF_Z80_T2 tail
                (byte) 0xF2
        };
        CaptureSynth synth = new CaptureSynth();
        Sonic3kSmpsData smps = createMusicData(1, 1, null, psgTrack, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, synth, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(25000);

        assertTrue(synth.psgWrites.contains(0x8F), "Underflow latches the low nibble of 0FFFFh");
        assertTrue(synth.psgWrites.contains(0xFF), "Underflow sends the unmasked second byte 0FFh");
    }

    @Test
    public void fbTransposeAddWrapsAsSignedByte() {
        byte[] fmTrack = {
                (byte) 0xFB, (byte) 0xF0,
                (byte) 0xFB, (byte) 0xF0,
                (byte) 0xFB, (byte) 0xF0,
                (byte) 0xFB, (byte) 0xF0,
                (byte) 0xFB, (byte) 0xF0,
                (byte) 0xFB, (byte) 0xF0,
                (byte) 0xFB, (byte) 0xF0,
                (byte) 0xFB, (byte) 0xF0,
                (byte) 0xFB, (byte) 0xF0, // 9 * (-16) = -144 -> wraps to +112
                (byte) 0xF2
        };
        Sonic3kSmpsData smps = createMusicData(2, 0, fmTrack, null, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, new CaptureSynth(), Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(20000);

        SmpsSequencer.Track fm = findTrack(seq, SmpsSequencer.TrackType.FM);
        assertEquals(112, fm.keyOffset, "S3K transpose add must wrap as signed byte");
    }

    @Test
    public void edTransposeSetWrapsAsSignedByte() {
        byte[] fmTrack = {
                (byte) 0xED, (byte) 0xFF, // 0xFF - 0x40 = 0xBF -> signed -65
                (byte) 0xF2
        };
        Sonic3kSmpsData smps = createMusicData(2, 0, fmTrack, null, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, new CaptureSynth(), Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(20000);

        SmpsSequencer.Track fm = findTrack(seq, SmpsSequencer.TrackType.FM);
        assertEquals(-65, fm.keyOffset, "S3K transpose set must wrap as signed byte");
    }

    @Test
    public void spindashRevAddsPersistentRevCounterToTranspose() {
        byte[] fmTrack = {
                (byte) 0xFF, 0x07, // Reset shared spindash rev counter
                (byte) 0xE9,       // key offset += 0, rev becomes 1
                (byte) 0xE9,       // key offset += 1, rev becomes 2
                (byte) 0xE9,       // key offset += 2
                (byte) 0xF2
        };
        Sonic3kSmpsData smps = createMusicData(2, 0, fmTrack, null, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, new CaptureSynth(), Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(20000);

        SmpsSequencer.Track fm = findTrack(seq, SmpsSequencer.TrackType.FM);
        assertEquals(3, fm.keyOffset, "E9 should add the persistent spindash rev value to track transpose");
    }

    @Test
    public void normalSfxStartResetsPersistentS3kSpindashRevCounter() {
        PresentationFixture fixture = presentationFixture();
        fixture.addSfx(createSfxData(
                Sonic3kSfx.SPINDASH.id,
                new byte[] {(byte) 0xE9, (byte) 0xE9, (byte) 0xF2}),
                0);
        fixture.mix();
        // zUpdateSFXTracks runs before zUpdateMusic's zFillSoundQueue admits
        // the request (skdisasm Sound/Z80 Sound Driver.asm:650-701), so the
        // service that admits an SFX gives it no walk; its flags run on the
        // next one. Two mixes is the same amount of driver work this test
        // always meant, expressed at the ROM's cadence.
        fixture.mix();
        assertEquals(2, fixture.state.spindashRevCounter());

        fixture.addSfx(createSfxData(
                Sonic3kSfx.DASH.id, new byte[] {(byte) 0xF2}), 0);
        assertEquals(0, fixture.state.spindashRevCounter(),
                "Ordered apply of a normal SFX resets the session counter");
        fixture.addSfx(createSfxData(
                Sonic3kSfx.SPINDASH.id,
                new byte[] {(byte) 0xE9, (byte) 0xF2}), 0);
        fixture.mix();
        fixture.mix();

        assertEquals(1, fixture.state.spindashRevCounter(),
                "The next presentation-owned spindash starts from reset");
    }

    @Test
    public void continuousSfxStartDoesNotResetPersistentS3kSpindashRevCounter() {
        PresentationFixture fixture = presentationFixture();
        fixture.state.setSpindashRevCounter(2);
        Sonic3kSfxData continuous = createSfxData(
                Sonic3kSfx.SLIDE_SKID_LOUD.id,
                new byte[] {
                        (byte) 0xFC, 0x40, 0x00,
                        (byte) 0xF2
                });

        fixture.addSfx(continuous, Sonic3kSfx.SLIDE_SKID_LOUD.id);
        fixture.addSfx(continuous, Sonic3kSfx.SLIDE_SKID_LOUD.id);

        assertEquals(1,
                fixture.session.captureLogicalSnapshot().sequencers().size());
        assertTrue(fixture.session.captureLogicalSnapshot()
                .continuousSfxFlag());
        assertEquals(2, fixture.state.spindashRevCounter(),
                "Continuous retrigger does not construct a resetting handler");
    }

    @Test
    public void musicMutationAndResetThenSfxSharePresentationCounter() {
        PresentationFixture fixture = presentationFixture();
        fixture.replaceMusic(createMusicData(
                2, 0,
                new byte[] {
                        (byte) 0xE9, (byte) 0xE9, (byte) 0xF2
                },
                null, null), 0x81);
        fixture.mix();
        assertEquals(2, fixture.state.spindashRevCounter());

        fixture.addSfx(createSfxData(
                Sonic3kSfx.DASH.id, new byte[] {(byte) 0xF2}), 0);
        assertEquals(0, fixture.state.spindashRevCounter());
        fixture.addSfx(createSfxData(
                Sonic3kSfx.SPINDASH.id,
                new byte[] {(byte) 0xE9, (byte) 0xF2}), 0);
        fixture.mix();
        fixture.mix();

        assertEquals(1, fixture.state.spindashRevCounter());
    }

    @Test
    public void sfxThenMusicAndOverrideSharePresentationCounter() {
        PresentationFixture fixture = presentationFixture();
        fixture.addSfx(createSfxData(
                Sonic3kSfx.SPINDASH.id,
                new byte[] {(byte) 0xE9, (byte) 0xE9, (byte) 0xF2}), 0);
        fixture.mix();
        // zUpdateSFXTracks runs before zUpdateMusic's zFillSoundQueue admits
        // the request (skdisasm Sound/Z80 Sound Driver.asm:650-701), so the
        // service that admits an SFX gives it no walk; its flags run on the
        // next one. Two mixes is the same amount of driver work this test
        // always meant, expressed at the ROM's cadence.
        fixture.mix();
        assertEquals(2, fixture.state.spindashRevCounter());

        fixture.replaceMusic(createMusicData(
                2, 0,
                new byte[] {(byte) 0xE9, (byte) 0xF2},
                null, null), 0x81);
        fixture.mix();
        assertEquals(3, fixture.state.spindashRevCounter());

        fixture.pushMusic(createMusicData(
                2, 0,
                new byte[] {(byte) 0xE9, (byte) 0xF2},
                null, null), 0x91);
        fixture.mix();
        assertEquals(4, fixture.state.spindashRevCounter());
    }

    @Test
    public void s3kTempoZeroStillReportsTickBoundariesForHybridDriver() {
        byte[] fmTrack = {
                (byte) 0xF0, 0x2A, 0x01, 0x29, 0x00,
                (byte) 0xAD, 0x3C,
                (byte) 0xF2
        };
        Sonic3kSmpsData smps = createMusicData(2, 0, fmTrack, null, null, null, 0);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, new CaptureSynth(), Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(2);

        assertTrue(seq.getSamplesUntilNextTempoFrame() < Integer.MAX_VALUE,
                "S3K tempo 0 still ticks every video frame and must not batch indefinitely");
        assertTrue(seq.getSamplesUntilNextObservableEvent() < Integer.MAX_VALUE,
                "Hybrid rendering must see pending per-frame modulation updates in tempo-0 songs");
    }

    @Test
    public void tiedS3kModulationChangeDoesNotResetLiveAccumulator() {
        byte[] fmTrack = {
                (byte) 0xF0, 0x01, 0x01, 0x1A, 0x01,
                (byte) 0xBD, 0x18, // nC5
                (byte) 0xE7,       // smpsNoAttack
                (byte) 0xF0, 0x00, 0x00, 0x00, 0x00,
                0x02,              // tied duration continuation
                (byte) 0xF2
        };
        CaptureSynth synth = new CaptureSynth();
        Sonic3kSmpsData smps = createMusicData(2, 0, fmTrack, null, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, synth, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(40000);

        int baseC5A4 = (5 << 3) | ((644 >> 8) & 0x07);
        int lastA4 = -1;
        for (FmWrite write : synth.fmWrites) {
            if (write.reg == 0xA4) {
                lastA4 = write.val;
            }
        }

        assertTrue(lastA4 > baseC5A4,
                "F0 00 00 00 00 before a tied continuation must not drop the live S3K pitch ramp to base");
    }

    @Test
    public void tiedS3kContinuationKeepsFrozenModulatedFrequency() {
        byte[] fmTrack = {
                (byte) 0xF0, 0x01, 0x01, 0x1A, 0x01,
                (byte) 0xBD, 0x18, // nC5
                (byte) 0xE7,       // smpsNoAttack
                (byte) 0xF0, 0x00, 0x00, 0x00, 0x00,
                0x02,              // tied duration continuation; freezes modulation source
                (byte) 0xE7,
                0x02,              // another tied continuation after the accumulator is frozen
                (byte) 0xF2
        };

        int finalPacked = finalFmPackedFrequency(fmTrack);

        assertTrue(finalPacked > 0x2A84,
                "A tied continuation must rewrite the existing modulated frequency even when the accumulator is frozen");
    }

    @Test
    public void tiedS3kModulationChangeUsesNewSourceForReloadsOnly() {
        byte[] unchangedRamp = {
                (byte) 0xF0, 0x01, 0x01, 0x1A, 0x01,
                (byte) 0xBD, 0x18, // nC5
                (byte) 0xE7,       // smpsNoAttack
                0x10,              // tied duration continuation
                (byte) 0xF2
        };
        byte[] deferredRampChange = {
                (byte) 0xF0, 0x01, 0x01, 0x1A, 0x01,
                (byte) 0xBD, 0x18, // nC5
                (byte) 0xE7,       // smpsNoAttack
                (byte) 0xF0, 0x00, 0x00, 0x00, 0x00,
                0x10,              // tied duration continuation
                (byte) 0xF2
        };

        int unchangedFinalPacked = finalFmPackedFrequency(unchangedRamp);
        int deferredFinalPacked = finalFmPackedFrequency(deferredRampChange);

        assertTrue(deferredFinalPacked < unchangedFinalPacked,
                "S3K F0 before a tied continuation must update source bytes used by live modulation reloads");
    }

    @Test
    public void z80ModulationStepCounterDecrementsOnSustainTicks() {
        byte[] fmTrack = {
                (byte) 0xF0, 0x01, 0x02, 0x10, 0x04,
                (byte) 0xBD, 0x06, // nC5
                (byte) 0xF2
        };

        int finalPacked = finalFmPackedFrequency(fmTrack);

        // The expected value is an end-state snapshot of the engine after
        // 60,000 samples, not a ROM-derived constant: it pins that the named
        // mechanism keeps working, and it shifts whenever the run's start
        // phase does. It moved when the load service stopped accumulating
        // tempo for the song it loads, which the driver oracle's reference
        // settles (zUpdateEverything runs TempoWait ahead of zUpdateMusic's
        // zFillSoundQueue, skdisasm Sound/Z80 Sound Driver.asm:653-701,
        // :2607-2621). The mechanism this test names is untouched.
        assertEquals(0x2A84, finalPacked,
                "S3K zDoModulation decrements ModulationSteps on every full"
                        + " track walk, including tempo-delay services");
    }

    @Test
    public void z80ModulationWaitZeroAppliesOnSameTick() {
        byte[] fmTrack = {
                (byte) 0xF0, 0x01, 0x01, 0x29, 0x00,
                (byte) 0xBD, 0x01, // nC5
                (byte) 0xF2
        };

        int finalPacked = finalFmPackedFrequency(fmTrack);

        // The expected value is an end-state snapshot of the engine after
        // 60,000 samples, not a ROM-derived constant: it pins that the named
        // mechanism keeps working, and it shifts whenever the run's start
        // phase does. It moved when the load service stopped accumulating
        // tempo for the song it loads, which the driver oracle's reference
        // settles (zUpdateEverything runs TempoWait ahead of zUpdateMusic's
        // zFillSoundQueue, skdisasm Sound/Z80 Sound Driver.asm:653-701,
        // :2607-2621). The mechanism this test names is untouched.
        assertEquals(0x2AD6, finalPacked,
                "S3K zDoModulation applies the first delta on the tick that ModulationWait decrements to zero");
    }

    @Test
    public void globalVoicePointerUsesGlobalVoiceTableInZ80AddressedSongs() {
        byte[] songData = new byte[0x1900];
        setLe16(songData, 0x00, 0x17D8); // Global voice table pointer in S3K Z80 RAM
        // If incorrectly treated as local, this marker would be returned.
        songData[0x17D8] = 0x11;

        Sonic3kSmpsData smps = new Sonic3kSmpsData(songData, 0x8000);
        byte[] globalVoices = new byte[25 * 2];
        globalVoices[0] = 0x55;
        smps.setGlobalVoiceData(globalVoices);

        byte[] voice = smps.getVoice(0);
        assertNotNull(voice);
        assertEquals(0x55, voice[0] & 0xFF, "Expected global voice data, not in-song blob data");
    }

    @Test
    public void localVoicePointerStillWorksForOffsetBasedData() {
        byte[] songData = new byte[0x200];
        setLe16(songData, 0x00, 0x0100);
        songData[0x0100] = 0x33;

        Sonic3kSmpsData smps = new Sonic3kSmpsData(songData, 0);
        byte[] globalVoices = new byte[25];
        globalVoices[0] = 0x77;
        smps.setGlobalVoiceData(globalVoices);

        byte[] voice = smps.getVoice(0);
        assertNotNull(voice);
        assertEquals(0x33, voice[0] & 0xFF, "Offset-based data should keep using local voices");
    }

    @Test
    public void ff06LoadsFmVolumeEnvelopeState() {
        byte[] fmTrack = {
                (byte) 0xFF, 0x06, 0x01, 0x01, // env id 1, op mask 1
                (byte) 0x81, 0x02,
                (byte) 0xF2
        };
        Map<Integer, byte[]> psgEnvs = new HashMap<>();
        psgEnvs.put(1, new byte[] { 0x03, (byte) 0x81 });
        Sonic3kSmpsData smps = createMusicData(2, 0, fmTrack, null, psgEnvs);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, new CaptureSynth(), Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(25000);

        SmpsSequencer.Track fm = findTrack(seq, SmpsSequencer.TrackType.FM);
        assertTrue(fm.fmVolEnvData != null && fm.fmVolEnvData.length > 0, "FM vol env should be loaded by FF 06");
        assertEquals(0x01, fm.fmVolEnvOpMask);
    }

    @Test
    public void f4ModEnvelopeAppliesPitchDeltaOnPsg() {
        byte[] psgTrack = {
                (byte) 0xF4, 0x01, // load modulation envelope 1
                (byte) 0x90, 0x04,
                (byte) 0xF2
        };
        Map<Integer, byte[]> modEnvs = new HashMap<>();
        modEnvs.put(1, new byte[] { 0x01, (byte) 0x81 }); // +1 then HOLD

        CaptureSynth synth = new CaptureSynth();
        Sonic3kSmpsData smps = createMusicData(1, 1, null, psgTrack, null, modEnvs);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, synth, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(25000);

        SmpsSequencer.Track psg = findTrack(seq, SmpsSequencer.TrackType.PSG);
        assertEquals(1, psg.modEnvCache, "Mod envelope delta should be cached");
        assertTrue(synth.psgWrites.contains(0x8F), "PSG pitch write should include modulation-adjusted low nibble");
    }

    @Test
    public void psgDetuneIsAppliedOnceWhenModEnvelopeUpdatesPitch() {
        byte[] psgTrack = {
                (byte) 0xE1, 0x01, // detune +1
                (byte) 0xF4, 0x01, // load modulation envelope 1
                (byte) 0x92, 0x04, // base period 0x280 in DEF_Z80_T2
                (byte) 0xF2
        };
        Map<Integer, byte[]> modEnvs = new HashMap<>();
        modEnvs.put(1, new byte[] { 0x01, (byte) 0x81 }); // +1 then HOLD

        CaptureSynth synth = new CaptureSynth();
        Sonic3kSmpsData smps = createMusicData(1, 1, null, psgTrack, null, modEnvs);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, synth, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(25000);

        assertTrue(synth.psgWrites.contains(0x82), "Expected modulation write at 0x282 (+1 detune, +1 env)");
        assertFalse(synth.psgWrites.contains(0x83), "Detune must not be applied twice (would write 0x283)");
    }

    @Test
    public void modEnvelopeChangeMultiplierUsesZ80Formula() {
        byte[] psgTrack = {
                (byte) 0xF4, 0x01,
                (byte) 0x92, 0x04, // base period 0x280 in DEF_Z80_T2
                (byte) 0xF2
        };
        Map<Integer, byte[]> modEnvs = new HashMap<>();
        // 84 02 => mult = 2, Z80 formula uses (mult + 1), then value 01 => delta 3.
        modEnvs.put(1, new byte[] { (byte) 0x84, 0x02, 0x01, (byte) 0x81 });

        CaptureSynth synth = new CaptureSynth();
        Sonic3kSmpsData smps = createMusicData(1, 1, null, psgTrack, null, modEnvs);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, synth, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(25000);

        SmpsSequencer.Track psg = findTrack(seq, SmpsSequencer.TrackType.PSG);
        assertEquals(3, psg.modEnvCache, "CHG_MULT should affect modulation delta via Z80 (mult+1)");
        assertTrue(synth.psgWrites.contains(0x83), "PSG frequency should reflect +3 modulation delta");
    }

    private static Sonic3kSmpsData createMusicData(int channels, int psgChannels, byte[] fmTrack, byte[] psgTrack,
            Map<Integer, byte[]> psgEnvelopes) {
        return createMusicData(channels, psgChannels, fmTrack, psgTrack, psgEnvelopes, null);
    }

    private static Sonic3kSmpsData createMusicData(int channels, int psgChannels, byte[] fmTrack, byte[] psgTrack,
            Map<Integer, byte[]> psgEnvelopes, Map<Integer, byte[]> modEnvelopes) {
        return createMusicData(channels, psgChannels, fmTrack, psgTrack, psgEnvelopes, modEnvelopes, 0x80);
    }

    private static Sonic3kSmpsData createMusicData(int channels, int psgChannels, byte[] fmTrack, byte[] psgTrack,
            Map<Integer, byte[]> psgEnvelopes, Map<Integer, byte[]> modEnvelopes, int tempo) {
        byte[] data = new byte[0x240];
        setLe16(data, 0x00, 0x100); // voice table pointer
        data[0x02] = (byte) channels;
        data[0x03] = (byte) psgChannels;
        data[0x04] = 0x01; // dividing timing
        data[0x05] = (byte) tempo;

        // FM/DAC entries (4 bytes each): ptr, transpose, volume
        for (int i = 0; i < channels; i++) {
            int off = 0x06 + (i * 4);
            if (i == 0) {
                setLe16(data, off, 0x80); // DAC track
                data[0x80] = (byte) 0xF2;
            } else if (i == 1 && fmTrack != null) {
                setLe16(data, off, 0x90);
                System.arraycopy(fmTrack, 0, data, 0x90, fmTrack.length);
            } else {
                setLe16(data, off, 0);
            }
        }

        int psgBase = 0x06 + (channels * 4);
        for (int i = 0; i < psgChannels; i++) {
            int off = psgBase + (i * 6);
            if (i == 0 && psgTrack != null) {
                setLe16(data, off, 0xA0);
                System.arraycopy(psgTrack, 0, data, 0xA0, psgTrack.length);
            } else {
                setLe16(data, off, 0);
            }
            data[off + 2] = 0;
            data[off + 3] = 0;
            data[off + 4] = 0;
            data[off + 5] = 0;
        }

        // Two local voices so EF/E5 bugs are observable.
        for (int i = 0; i < 25; i++) {
            data[0x100 + i] = 0x00;
            data[0x100 + 25 + i] = 0x20;
        }
        data[0x100] = 0x00;
        data[0x100 + 25] = 0x00;

        Sonic3kSmpsData smps = new Sonic3kSmpsData(data, 0);
        if (psgEnvelopes != null) {
            smps.setPsgEnvelopes(psgEnvelopes);
        }
        if (modEnvelopes != null) {
            smps.setModEnvelopes(modEnvelopes);
        }
        return smps;
    }

    private static PresentationFixture presentationFixture() {
        SmpsCoordFlagRuntimeState state =
                new SmpsCoordFlagRuntimeState();
        SmpsCoordFlagHandlerOwner handlers =
                new SmpsCoordFlagHandlerOwner(state);
        handlers.register("s3k", Sonic3kCoordFlagHandler::new);
        SmpsDriverSession session =
                SmpsSessionTestSupport.installed(48_000);
        AudioPresentationSourceFactory factory =
                new AudioPresentationSourceFactory(
                        () -> true, handlers,
                        AudioPresentationSourceFactory.Settings.defaults(),
                        session);
        AudioVoiceRegistry registry = new AudioVoiceRegistry(
                factory, factory, handlers, ignored -> {
                }, session);
        return new PresentationFixture(
                state, handlers, factory, registry, session);
    }

    private static final class PresentationFixture {
        private static final int MAX_STEREO_FRAMES = 10_000;

        final SmpsCoordFlagRuntimeState state;
        final SmpsCoordFlagHandlerOwner handlers;
        final AudioPresentationSourceFactory factory;
        final AudioVoiceRegistry registry;
        final SmpsDriverSession session;
        private final Map<Sonic3kSfxData, SmpsAssetKey> sfxAssetKeys =
                new IdentityHashMap<>();
        private long nextVoiceId = 1;
        private int nextSfxAssetId = 1;

        PresentationFixture(
                SmpsCoordFlagRuntimeState state,
                SmpsCoordFlagHandlerOwner handlers,
                AudioPresentationSourceFactory factory,
                AudioVoiceRegistry registry,
                SmpsDriverSession session) {
            this.state = state;
            this.handlers = handlers;
            this.factory = factory;
            this.registry = registry;
            this.session = session;
        }

        void replaceMusic(Sonic3kSmpsData data, int musicId) {
            AudioPresentationCommand.MusicVoiceEntry music =
                    factory.musicSmps(
                            "s3k", musicId, nextVoiceId++,
                            data, EMPTY_DAC,
                            Sonic3kSmpsSequencerConfig.CONFIG,
                            AudioSourceDescriptor.baseMusic(musicId),
                            MAX_STEREO_FRAMES);
            AudioPresentationSessionCommandApplier.apply(
                    session, registry,
                    new AudioPresentationCommand.ReplaceMusic(music));
        }

        void pushMusic(Sonic3kSmpsData data, int musicId) {
            AudioPresentationCommand.MusicVoiceEntry music =
                    factory.musicSmps(
                            "s3k", musicId, nextVoiceId++,
                            data, EMPTY_DAC,
                            Sonic3kSmpsSequencerConfig.CONFIG,
                            AudioSourceDescriptor.baseMusic(musicId),
                            MAX_STEREO_FRAMES);
            AudioPresentationSessionCommandApplier.apply(
                    session, registry,
                    new AudioPresentationCommand.PushMusicOverride(music));
        }

        void addSfx(Sonic3kSfxData data, int continuousSfxId) {
            SmpsAssetKey key = sfxAssetKeys.computeIfAbsent(data,
                    ignored -> new SmpsAssetKey(
                            "s3k", SmpsAssetKey.Route.BASE_NAME, -1,
                            "coord-fixture-" + nextSfxAssetId++));
            factory.registerSmpsSfxAsset(
                    key, 0, data, EMPTY_DAC,
                    Sonic3kSmpsSequencerConfig.CONFIG, false);
            AudioPresentationSessionCommandApplier.apply(
                    session, registry,
                    new AudioPresentationCommand.AddSmpsSfx(
                            factory.resolveSmpsSfx(
                            nextVoiceId++, key, 1 << 16, 0x70,
                            continuousSfxId,
                            data.getTrackEntries().size(),
                            MAX_STEREO_FRAMES)));
        }

        void mix() {
            registry.beginRendering();
            try {
                registry.serviceOuterFrame();
                session.serviceForward();
                short[] smpsPcm = new short[MAX_STEREO_FRAMES * 2];
                session.renderFrames(smpsPcm, 0, MAX_STEREO_FRAMES);
                new AudioPresentationMixer(MAX_STEREO_FRAMES).mixPcmVoices(
                        registry, MAX_STEREO_FRAMES, smpsPcm, 0);
            } finally {
                registry.endRendering();
            }
        }
    }

    private static Sonic3kSfxData createSfxData(int id, byte[] fmTrack) {
        byte[] data = new byte[0x200];
        data[0x00] = 0x00; // global voice table
        data[0x01] = 0x00;
        data[0x02] = 0x01; // tick multiplier
        data[0x03] = 0x01; // one track
        data[0x04] = (byte) 0x80; // playback flags
        data[0x05] = 0x02; // FM1
        setLe16(data, 0x06, 0x40);
        data[0x08] = 0x00; // transpose
        data[0x09] = 0x00; // volume
        System.arraycopy(fmTrack, 0, data, 0x40, fmTrack.length);

        Sonic3kSfxData smps = new Sonic3kSfxData(data, 0, 0, 0);
        smps.setId(id);
        smps.setGlobalVoiceData(new byte[25]);
        return smps;
    }

    private static SmpsSequencer.Track findTrack(SmpsSequencer seq, SmpsSequencer.TrackType type) {
        for (SmpsSequencer.Track t : seq.getTracks()) {
            if (t.type == type) {
                return t;
            }
        }
        throw new AssertionError("Missing track type: " + type);
    }

    private static int finalFmPackedFrequency(byte[] fmTrack) {
        CaptureSynth synth = new CaptureSynth();
        Sonic3kSmpsData smps = createMusicData(2, 0, fmTrack, null, null);
        SmpsSequencer seq = new SmpsSequencer(smps, EMPTY_DAC, synth, Sonic3kSmpsSequencerConfig.CONFIG);
        seq.advanceSamples(60000);

        int high = -1;
        int low = -1;
        for (FmWrite write : synth.fmWrites) {
            if (write.reg == 0xA4) {
                high = write.val;
            } else if (write.reg == 0xA0) {
                low = write.val;
            }
        }
        if (high < 0 || low < 0) {
            throw new AssertionError("No final FM frequency write captured");
        }
        return (high << 8) | low;
    }

    private static void setLe16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
