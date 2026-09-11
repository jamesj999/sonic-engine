package com.openggf.game.sonic3k.audio.smps;

import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FixBugs=0 semantic state tests for S3K's shared PlaybackControl bit zero. */
class TestSonic3kFm3SpecialMode {
    private static final DacData EMPTY_DAC = new DacData(
            new HashMap<>(), new HashMap<>(), 297);

    @Test
    void fm3ReleaseRestoresNormalModeBeforeTheMusicVoice() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = fmSequencerWithVoice(2,
                new byte[] {(byte) 0xF2}, synth);

        sequencer.setChannelOverridden(SmpsSequencer.TrackType.FM, 2, true);
        synth.fmWrites.clear();
        synth.fmSources.clear();
        sequencer.setChannelOverridden(SmpsSequencer.TrackType.FM, 2, false);

        assertEquals(List.of("27:0F", "B6:C0"),
                synth.fmWrites.subList(0, 2),
                "cfStopTrack restores FM3 settings before its music voice");
        assertEquals(sequencer, synth.fmSources.getFirst(),
                "the restored music sequencer owns the global mode write source");
    }

    @Test
    void fm3ReleaseRetainsSpecialModeBeforeTheMusicVoice() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = fmSequencerWithVoice(2,
                new byte[] {(byte) 0xF2}, synth);
        onlyTrack(sequencer).fm3SpecialMode = true;

        sequencer.setChannelOverridden(SmpsSequencer.TrackType.FM, 2, true);
        synth.fmWrites.clear();
        sequencer.setChannelOverridden(SmpsSequencer.TrackType.FM, 2, false);

        assertEquals(List.of("27:4F", "B6:C0"),
                synth.fmWrites.subList(0, 2));
    }

    @Test
    void nonFm3AndEarlierDriversDoNotUseTheS3kModeRestorePrelude() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = fmSequencerWithVoice(1,
                new byte[] {(byte) 0xF2}, synth);

        sequencer.setChannelOverridden(SmpsSequencer.TrackType.FM, 1, true);
        synth.fmWrites.clear();
        sequencer.setChannelOverridden(SmpsSequencer.TrackType.FM, 1, false);

        assertFalse(synth.fmWrites.stream().anyMatch(write -> write.startsWith("27:")));
        assertNotEquals(SmpsSequencerConfig.FmSfxReleaseMode
                        .ROM_VOICE_RESTORE_PRESERVE_REST,
                Sonic1SmpsSequencerConfig.CONFIG.getFmSfxReleaseMode());
        assertNotEquals(SmpsSequencerConfig.FmSfxReleaseMode
                        .ROM_VOICE_RESTORE_PRESERVE_REST,
                Sonic2SmpsSequencerConfig.CONFIG.getFmSfxReleaseMode());
    }

    @Test
    void fm3F3SetsSpecialModeWithoutPsgWritesAndLeavesRawFrequencyIndependent() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = fmSequencer(2,
                new byte[] {(byte) 0xF3, (byte) 0xE7,
                        (byte) 0xFD, 0x01, (byte) 0xF2}, synth);

        sequencer.advanceSamples(20_000);

        SmpsSequencer.Track fm3 = onlyTrack(sequencer);
        assertTrue(fm3.fm3SpecialMode);
        assertTrue(fm3.rawFreqMode);
        assertTrue(synth.psgWrites == 0,
                "FM3 F3 must not take the PSG-noise write path");
        assertEquals(0x45, fm3.pos,
                "F3 and FD must each consume exactly one operand before F2");
    }

    @Test
    void fm3F3ZeroClearsOnlyTheRetainedFm3State() {
        SmpsSequencer sequencer = fmSequencer(2,
                new byte[] {(byte) 0xF3, 0x00, (byte) 0xF2},
                new CaptureSynth());
        SmpsSequencer.Track fm3 = onlyTrack(sequencer);
        fm3.fm3SpecialMode = true;
        fm3.rawFreqMode = true;

        sequencer.advanceSamples(20_000);

        assertFalse(fm3.fm3SpecialMode);
        assertTrue(fm3.rawFreqMode,
                "F3 must not overload the independent FD raw-frequency state");
        assertEquals(0x43, fm3.pos);
    }

    @Test
    void fm3FeConsumesAllFourOperandsRetainsBitAndEmitsNoSpecialModeWrite() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = fmSequencer(2,
                new byte[] {(byte) 0xFE, 0x01, 0x02, 0x03, 0x04,
                        (byte) 0xF2}, synth);

        sequencer.advanceSamples(20_000);

        SmpsSequencer.Track fm3 = onlyTrack(sequencer);
        assertTrue(fm3.fm3SpecialMode);
        assertEquals(0x46, fm3.pos,
                "FE must consume its four operands on the FixBugs=0 path");
        assertEquals(0, synth.fm3ModeWrites,
                "this tranche retains state only; it must not emit YM $27 writes");
    }

    @Test
    void nonFm3FeStillConsumesAllFourOperandsWithoutRetainingTheBit() {
        SmpsSequencer sequencer = fmSequencer(1,
                new byte[] {(byte) 0xFE, 0x01, 0x02, 0x03, 0x04,
                        (byte) 0xF2}, new CaptureSynth());

        sequencer.advanceSamples(20_000);

        SmpsSequencer.Track fm2 = onlyTrack(sequencer);
        assertFalse(fm2.fm3SpecialMode);
        assertEquals(0x46, fm2.pos);
    }

    @Test
    void nonFm3F3StillConsumesItsOperandWithoutChangingFm3State() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = fmSequencer(3,
                new byte[] {(byte) 0xF3, (byte) 0xE7, (byte) 0xF2}, synth);

        sequencer.advanceSamples(20_000);

        SmpsSequencer.Track fm4 = onlyTrack(sequencer);
        assertFalse(fm4.fm3SpecialMode);
        assertEquals(0x43, fm4.pos);
        assertEquals(0, synth.psgWrites);
    }

    @Test
    void replacementTrackStartsWithSpecialModeClear() {
        SmpsSequencer original = fmSequencer(2,
                new byte[] {(byte) 0xFE, 1, 2, 3, 4, (byte) 0xF2},
                new CaptureSynth());
        original.advanceSamples(20_000);
        SmpsSequencer.Track originalFm3 = onlyTrack(original);
        originalFm3.customSsgEgPresent = true;
        originalFm3.customSsgEgPayload[0] = 0x11;
        originalFm3.customSsgEgPayload[1] = 0x22;
        originalFm3.customSsgEgPayload[2] = 0x33;
        originalFm3.customSsgEgPayload[3] = 0x44;
        originalFm3.customSsgEgPayloadKnown = true;

        SmpsSequencer replacement = fmSequencer(2,
                new byte[] {(byte) 0xF2}, new CaptureSynth());

        assertTrue(originalFm3.fm3SpecialMode);
        assertTrue(originalFm3.customSsgEgPresent);
        assertTrue(originalFm3.customSsgEgPayloadKnown);
        assertEquals(List.of(0x11, 0x22, 0x33, 0x44),
                java.util.Arrays.stream(originalFm3.customSsgEgPayload).boxed().toList());
        assertFalse(onlyTrack(replacement).fm3SpecialMode,
                "reinitialization must use Track's default state rather than leak FM3 mode");
        assertFalse(onlyTrack(replacement).customSsgEgPresent);
        assertFalse(onlyTrack(replacement).customSsgEgPayloadKnown);
        assertTrue(java.util.Arrays.equals(new int[4],
                onlyTrack(replacement).customSsgEgPayload));
    }

    @Test
    void psgF3MustRetainTheExactRawOperandSeparatelyFromItsNormalizedRegister() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = psgSequencer(2,
                new byte[] {(byte) 0xF3, 0x15, (byte) 0xF2}, synth);

        sequencer.advanceSamples(20_000);

        SmpsSequencer.Track psg3 = onlyTrack(sequencer);
        assertEquals(0x15, psg3.rawPsgNoise,
                "the exact source operand must survive independently of the normalized PSG register");
        assertTrue(psg3.rawPsgNoiseKnown);
        assertEquals(0x05, psg3.psgNoiseParam,
                "normalised PSG behaviour remains independent of the retained byte");
        // Retail fix_sndbugs=0 cfStopTrack calls zGetSFXChannelPointers:
        // zSilencePSGChannel emits DF FF for this noise track, then the
        // pointer helper emits its unconditional FF. The fixed branch omits
        // that extra write (Sound/Z80 Sound Driver.asm:2115-2142, 3443-3469).
        assertEquals(List.of(0xDF, 0xE5, 0xDF, 0xFF, 0xFF), synth.psgWriteValues,
                "F3 preserves its operand independently of the retail F2 transaction");
        assertEquals(0x43, psg3.pos);
    }

    @Test
    void psgF3ZeroRetainsTheExplicitResetOperand() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = psgSequencer(2,
                new byte[] {(byte) 0xF3, 0x00, (byte) 0xF2}, synth);

        sequencer.advanceSamples(20_000);

        SmpsSequencer.Track psg3 = onlyTrack(sequencer);
        assertFalse(psg3.noiseMode);
        assertEquals(0, psg3.psgNoiseParam);
        assertEquals(0, psg3.rawPsgNoise);
        assertTrue(psg3.rawPsgNoiseKnown,
                "zero is an executed reset byte, not an absent raw value");
        // Reset clears noise mode, so F2 emits the tone mute and only the
        // pointer helper's unconditional FF (retail fix_sndbugs=0).
        assertEquals(List.of(0xDF, 0xFF, 0xDF, 0xFF), synth.psgWriteValues,
                "F3 reset retains the tone-mode retail F2 transaction");
        assertEquals(0x43, psg3.pos);
    }

    @Test
    void allZeroFf05MustStillBeDistinguishableFromNoCustomSsgEgCommand() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = fmSequencer(2,
                new byte[] {(byte) 0xFF, 0x05, 0x00, 0x00, 0x00, 0x00,
                        (byte) 0xF2}, synth);

        sequencer.advanceSamples(20_000);

        SmpsSequencer.Track fm3 = onlyTrack(sequencer);
        assertTrue(fm3.customSsgEgPresent,
                "all-zero FF05 must remain distinguishable from no FF05 command");
        assertTrue(java.util.Arrays.equals(new int[4], fm3.ssgEg));
        assertTrue(fm3.customSsgEgPayloadKnown,
                "all-zero FF05 has an exact source payload, rather than an unknown one");
        assertTrue(java.util.Arrays.equals(new int[4], fm3.customSsgEgPayload));
        assertEquals(0x47, fm3.pos);
        assertEquals(4, synth.ssgEgWrites);

        var snapshot = sequencer.captureSnapshot();
        fm3.customSsgEgPayload[0] = 0x7F;
        fm3.customSsgEgPayloadKnown = false;
        sequencer.restoreSnapshot(snapshot);
        SmpsSequencer.Track restored = onlyTrack(sequencer);
        assertTrue(restored.customSsgEgPresent);
        assertTrue(restored.customSsgEgPayloadKnown);
        assertTrue(java.util.Arrays.equals(new int[4], restored.customSsgEgPayload));
    }

    @Test
    void ff05ThenVoiceSelectionMustNotLoseItsRestorePayload() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = fmSequencerWithVoice(2,
                new byte[] {(byte) 0xFF, 0x05, 0x11, 0x22, 0x33, 0x44,
                        (byte) 0xEF, 0x00, (byte) 0xF2}, synth);

        sequencer.advanceSamples(20_000);

        SmpsSequencer.Track fm3 = onlyTrack(sequencer);
        assertEquals(List.of(0x11, 0x22, 0x33, 0x44),
                java.util.Arrays.stream(fm3.customSsgEgPayload).boxed().toList(),
                "the custom restore bytes need their own state beyond live SSG-EG behavior");
        assertTrue(java.util.Arrays.equals(new int[4], fm3.ssgEg),
                "EF keeps its existing live SSG-EG clear behavior");
        assertTrue(fm3.customSsgEgPayloadKnown);
        assertTrue(fm3.customSsgEgPresent);
        assertEquals(4, synth.ssgEgWrites,
                "the retained restore state must not add a second SSG-EG write pass");

        var snapshot = sequencer.captureSnapshot();
        fm3.customSsgEgPayload[0] = 0;
        sequencer.restoreSnapshot(snapshot);
        assertEquals(List.of(0x11, 0x22, 0x33, 0x44), java.util.Arrays.stream(
                onlyTrack(sequencer).customSsgEgPayload).boxed().toList());
    }

    @Test
    void ordinaryFf06ClearsTheAliasedCustomSsgEgRestorePresence() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = fmSequencer(2,
                new byte[] {(byte) 0xFF, 0x05, 0x11, 0x22, 0x33, 0x44,
                        (byte) 0xFF, 0x06, 0x01, 0x0F, (byte) 0xF2},
                synth);

        sequencer.advanceSamples(20_000);

        assertFalse(onlyTrack(sequencer).customSsgEgPresent,
                "positive FMVolEnv overwrites HaveSSGEGFlag and cannot restore FF05 data");
        assertFalse(onlyTrack(sequencer).customSsgEgPayloadKnown);
        assertEquals(4, synth.ssgEgWrites,
                "FF06 must retain the existing FF05 hardware writes without adding SSG-EG output");
    }

    @Test
    void highBitFf06RetainsAliasedPresenceButInvalidatesTheFf05Payload() {
        CaptureSynth synth = new CaptureSynth();
        SmpsSequencer sequencer = fmSequencer(2,
                new byte[] {(byte) 0xFF, 0x05, 0x11, 0x22, 0x33, 0x44,
                        (byte) 0xFF, 0x06, (byte) 0x81, 0x0F, (byte) 0xF2},
                synth);

        sequencer.advanceSamples(20_000);

        SmpsSequencer.Track fm3 = onlyTrack(sequencer);
        assertTrue(fm3.customSsgEgPresent);
        assertFalse(fm3.customSsgEgPayloadKnown,
                "high-bit FMVolEnv retains the sign flag but overwrites FF05 pointer data");
        assertTrue(java.util.Arrays.equals(new int[4], fm3.customSsgEgPayload));
        assertEquals(4, synth.ssgEgWrites,
                "FF06 must not add a custom SSG-EG write pass");
    }

    private static SmpsSequencer fmSequencer(int channel, byte[] stream,
            CaptureSynth synth) {
        return fmSequencer(channel, stream, synth, false);
    }

    private static SmpsSequencer fmSequencerWithVoice(int channel, byte[] stream,
            CaptureSynth synth) {
        return fmSequencer(channel, stream, synth, true);
    }

    private static SmpsSequencer fmSequencer(int channel, byte[] stream,
            CaptureSynth synth, boolean withVoice) {
        byte[] data = new byte[0x100];
        int headerIndex = channel + 1; // index 0 is DAC in the S3K FM table
        if (withVoice) {
            setLe16(data, 0, 0x80);
            for (int index = 0; index < 25; index++) {
                data[0x80 + index] = (byte) (index + 1);
            }
        }
        data[2] = (byte) (headerIndex + 1);
        data[4] = 1;
        data[5] = (byte) 0x80;
        setLe16(data, 0x06 + headerIndex * 4, 0x40);
        System.arraycopy(stream, 0, data, 0x40, stream.length);
        return new SmpsSequencer(new Sonic3kSmpsData(data, 0), EMPTY_DAC,
                synth, Sonic3kSmpsSequencerConfig.CONFIG);
    }

    private static SmpsSequencer psgSequencer(int channel, byte[] stream,
            CaptureSynth synth) {
        byte[] data = new byte[0x100];
        data[2] = 0; // DAC/FM channel count
        data[3] = (byte) (channel + 1);
        data[4] = 1;
        data[5] = (byte) 0x80;
        setLe16(data, 0x06 + channel * 6, 0x40);
        System.arraycopy(stream, 0, data, 0x40, stream.length);
        return new SmpsSequencer(new Sonic3kSmpsData(data, 0), EMPTY_DAC,
                synth, Sonic3kSmpsSequencerConfig.CONFIG);
    }

    private static SmpsSequencer.Track onlyTrack(SmpsSequencer sequencer) {
        assertEquals(1, sequencer.getTracks().size());
        return sequencer.getTracks().getFirst();
    }

    private static void setLe16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private static final class CaptureSynth extends VirtualSynthesizer {
        private int psgWrites;
        private int fm3ModeWrites;
        private int ssgEgWrites;
        private final java.util.ArrayList<Integer> psgWriteValues = new java.util.ArrayList<>();
        private final java.util.ArrayList<String> fmWrites = new java.util.ArrayList<>();
        private final java.util.ArrayList<Object> fmSources = new java.util.ArrayList<>();

        @Override
        public void writePsg(Object source, int value) {
            psgWrites++;
            psgWriteValues.add(value & 0xFF);
            super.writePsg(source, value);
        }

        @Override
        public void writeFm(Object source, int port, int register, int value) {
            fmWrites.add("%02X:%02X".formatted(register & 0xFF, value & 0xFF));
            fmSources.add(source);
            if ((register & 0xFF) == 0x27) {
                fm3ModeWrites++;
            }
            if ((register & 0xFF) >= 0x90 && (register & 0xFF) < 0xA0) {
                ssgEgWrites++;
            }
            super.writeFm(source, port, register, value);
        }
    }
}
