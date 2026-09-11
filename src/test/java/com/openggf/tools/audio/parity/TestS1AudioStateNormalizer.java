package com.openggf.tools.audio.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencer.Region;
import com.openggf.audio.smps.SmpsSequencer.TrackType;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.game.sonic1.audio.Sonic1SmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1AudioStateNormalizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final S1AudioStateNormalizer.GhzAssetRange GHZ =
            new S1AudioStateNormalizer.GhzAssetRange(476636, 478532);

    @Test
    void registryMakesEverySourceAndComparisonDecisionExecutable() {
        Map<String, S1AudioFieldRegistry.Field> fields = new HashMap<>();
        S1AudioFieldRegistry.fields().forEach(field -> fields.put(field.name(), field));

        assertEquals(S1AudioFieldRegistry.Comparison.GATE, fields.get("tempoReload").comparison());
        assertEquals(S1AudioFieldRegistry.Signedness.SIGNED_BYTE, fields.get("volume").signedness());
        assertEquals(S1AudioFieldRegistry.Applicability.PSG_ONLY,
                fields.get("envelopeCursor").applicability());
        assertEquals("FM_PSG_ONLY", fields.get("baseFrequency").applicability().name(),
                "S1 DAC T+$10 is SavedDAC, not the FM/PSG frequency word");
        assertEquals("SmpsTrackSnapshot.voiceId (FM/DAC) or instrumentId (PSG)",
                fields.get("voiceOrEnvelope").source());
        assertEquals(S1AudioFieldRegistry.Comparison.DIAGNOSTIC, fields.get("resting").comparison());
        assertEquals(S1AudioFieldRegistry.Comparison.DIAGNOSTIC, fields.get("noteFillPhase").comparison());
        assertEquals(S1AudioFieldRegistry.Comparison.DIAGNOSTIC,
                fields.get("modulationPhase").comparison());
        assertTrue(fields.values().stream().allMatch(field -> !field.source().isBlank()));
    }

    @Test
    void normalizesFixedRolesSignedFieldsChannelsAndLiveContainers() {
        SmpsTrackSnapshot dac = track(40, TrackType.DAC, 5, true, 6, 8,
                0, 0, 0, 0x321, 4, 0xC0, 0, 0, 0x80, 0,
                new int[] {1, 2, 3}, new int[0], 0,
                false, false, false, 0x80, 0, 0, 0, 0);
        SmpsTrackSnapshot fm1 = track(52, TrackType.FM, 0, true, 12, 16,
                0xFE, 0xFF, 0xFD, 0x123, 4, 0xC0, 1, 2, 7, 99,
                new int[] {4, 88, 2, 77}, new int[] {38, 1112, 1792, 9999}, 2,
                true, true, true, 0, 0, 0, 0, 0);
        SmpsTrackSnapshot psg3 = track(100, TrackType.PSG, 2, true, 3, 5,
                0, 0, 0, 0x2AB, 0, 0, 0, 0, 44, 6,
                new int[] {9, 8, 7}, new int[] {12}, 1,
                false, false, false, 0, 0, 0, 0, 0);
        // Break caught: stale fields on an inactive track leak into the gating state.
        SmpsTrackSnapshot inactiveFm6 = track(1800, TrackType.FM, 5, false, 255, 255,
                127, 127, 127, 0x7FF, 7, 0xC0, 3, 7, 255, 255,
                new int[] {255}, new int[] {1234}, 1,
                true, true, true, 255, 255, 127, 255, 32767);

        S1AudioStateNormalizer.NormalizedState normalized = S1AudioStateNormalizer.normalize(
                sequencer(List.of(psg3, inactiveFm6, fm1, dac), 21, 3), GHZ, Set.of(0, 2));

        assertEquals(AudioParitySchema.ROLES, normalized.tracks().stream().map(AudioParityTrackState::role).toList());
        AudioParityTrackState normalizedDac = normalized.tracks().get(0);
        assertTrue(normalizedDac.active());
        assertEquals(null, normalizedDac.baseFrequency(),
                "DAC SavedDAC must not be compared as an FM/PSG frequency");
        AudioParityTrackState fm = normalized.tracks().get(1);
        assertEquals(-2, fm.transpose());
        assertEquals(-1, fm.volume());
        assertEquals(-3, fm.detune());
        assertEquals(52, fm.sequencePosition());
        assertEquals(0x2123, fm.baseFrequency());
        assertEquals(0xC0, fm.pan());
        assertEquals(1, fm.ams());
        assertEquals(2, fm.fms());
        assertEquals(7, fm.voiceOrEnvelope());
        assertEquals(List.of(4, 2), fm.loopCounters());
        assertEquals(List.of(38L, 1112L), fm.returnStack());
        assertFalse(normalized.tracks().get(6).active());
        AudioParityTrackState psg = normalized.tracks().get(9);
        assertEquals("PSG3", psg.hardware());
        assertEquals(0x2AB, psg.baseFrequency());
        assertEquals(6, psg.voiceOrEnvelope());
        assertEquals(44, psg.envelopeCursor());
        assertEquals(List.of(9, 7), psg.loopCounters());
    }

    @Test
    void validatesUniqueHardwareRolesAndGhzRelativeCoordinates() {
        SmpsTrackSnapshot fm1 = track(0, TrackType.FM, 0, true, 1, 1,
                0, 0, 0, 0, 0, 0xC0, 0, 0, 0, 0, new int[0], new int[0], 0,
                false, false, false, 0, 0, 0, 0, 0);
        SmpsTrackSnapshot duplicate = track(1, TrackType.FM, 0, true, 1, 1,
                0, 0, 0, 0, 0, 0xC0, 0, 0, 0, 0, new int[0], new int[0], 0,
                false, false, false, 0, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> S1AudioStateNormalizer.normalize(
                sequencer(List.of(fm1, duplicate), 21, 3), GHZ, Set.of()));

        SmpsTrackSnapshot pastEnd = track(GHZ.length(), TrackType.FM, 0, true, 1, 1,
                0, 0, 0, 0, 0, 0xC0, 0, 0, 0, 0, new int[0], new int[0], 0,
                false, false, false, 0, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> S1AudioStateNormalizer.normalize(
                sequencer(List.of(pastEnd), 21, 3), GHZ, Set.of()));

        SmpsTrackSnapshot badReturn = track(0, TrackType.FM, 0, true, 1, 1,
                0, 0, 0, 0, 0, 0xC0, 0, 0, 0, 0, new int[0], new int[] {GHZ.length() + 1}, 1,
                false, false, false, 0, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> S1AudioStateNormalizer.normalize(
                sequencer(List.of(badReturn), 21, 3), GHZ, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> S1AudioStateNormalizer.normalize(
                sequencer(List.of(fm1), 21, 3), GHZ, Set.of(256)));

        SmpsTrackSnapshot malformedPan = track(0, TrackType.FM, 0, true, 1, 1,
                0, 0, 0, 0, 0, 0xC1, 0, 0, 0, 0, new int[0], new int[0], 0,
                false, false, false, 0, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> S1AudioStateNormalizer.normalize(
                sequencer(List.of(malformedPan), 21, 3), GHZ, Set.of()));
    }

    @Test
    void promotesOnlyProvenS1TempoPhaseAndExcludesUnprovenDerivedPhases() {
        SmpsTrackSnapshot first = track(12, TrackType.FM, 0, true, 4, 8,
                0, 0, 0, 0x100, 2, 0xC0, 0, 0, 1, 0, new int[0], new int[0], 0,
                false, false, false, 0x80, 3, 5, 7, 11);
        SmpsTrackSnapshot differentDiagnostics = track(12, TrackType.FM, 0, true, 4, 8,
                0, 0, 0, 0x100, 2, 0xC0, 0, 0, 1, 0, new int[0], new int[0], 0,
                false, false, false, 0x40, 99, -5, 33, -200);

        S1AudioStateNormalizer.NormalizedState a = S1AudioStateNormalizer.normalize(
                sequencer(List.of(first), 21, 3), GHZ, Set.of());
        S1AudioStateNormalizer.NormalizedState b = S1AudioStateNormalizer.normalize(
                sequencer(List.of(differentDiagnostics), 22, 4), GHZ, Set.of());

        assertEquals(a.tracks(), b.tracks(), "rest/note-fill/modulation phase are diagnostic only");
        assertEquals(21, a.global().tempoReload());
        assertEquals(3, a.global().tempoTimeout());
        assertEquals(22, b.global().tempoReload());
        assertEquals(4, b.global().tempoTimeout());
    }

    @Test
    void productionS1TimeoutTransitionUsesTheMappedTempoFieldsDirectly() {
        SmpsSequencer sequencer = new SmpsSequencer(new AudioTestFixtures.StubSmpsData("tempo"),
                AudioTestFixtures.EMPTY_DAC, () -> {}, Sonic1SmpsSequencerConfig.CONFIG);
        sequencer.setNormalTempo(21);
        sequencer.recalculateTempo();

        S1AudioStateNormalizer.NormalizedState before = S1AudioStateNormalizer.normalize(
                sequencer.captureSnapshot(), GHZ, Set.of());
        sequencer.advanceSamples(0);
        S1AudioStateNormalizer.NormalizedState after = S1AudioStateNormalizer.normalize(
                sequencer.captureSnapshot(), GHZ, Set.of());

        assertEquals(21, before.global().tempoReload());
        assertEquals(21, before.global().tempoTimeout());
        assertEquals(21, after.global().tempoReload());
        assertEquals(20, after.global().tempoTimeout());
    }

    @Test
    void productionS1PsgRestRetainsTheRomInvalidFrequencySentinel() {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        SmpsSequencer sequencer = new SmpsSequencer(new RestingPsgData(),
                AudioTestFixtures.EMPTY_DAC, synth, () -> {}, Sonic1SmpsSequencerConfig.CONFIG);
        sequencer.setSampleRate(44_100);
        assertEquals(1, sequencer.getTracks().size());
        List<Integer> psgWrites = new ArrayList<>();
        synth.setChipWriteObserver(new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) { }
            @Override public void onPsgWrite(int value) { psgWrites.add(value); }
        });

        sequencer.advanceSamples(0);
        assertTrue(sequencer.getTracks().get(0).active);

        AudioParityTrackState psg1 = S1AudioStateNormalizer.normalize(
                sequencer.captureSnapshot(), GHZ, Set.of()).tracks().get(7);
        assertEquals(0xFFFF, psg1.baseFrequency(),
                "S1 PSGSetFreq stores -1 in Freq for a rest");
        assertEquals(1, psg1.envelopeCursor(),
                "S1 PSGDoVolFX consumes one envelope byte even while SetPSGVolume suppresses a rest write");
        assertEquals(List.of(0x9F), psgWrites,
                "advancing the rest envelope must not add or reorder PSG writes");

        psgWrites.clear();
        sequencer.advanceBatch(735);
        AudioParityTrackState nextPsg1 = S1AudioStateNormalizer.normalize(
                sequencer.captureSnapshot(), GHZ, Set.of()).tracks().get(7);
        assertEquals(2, nextPsg1.envelopeCursor());
        assertEquals(List.of(), psgWrites,
                "later PSG envelope steps also remain silent while the track rests");

        sequencer.advanceBatch(735);
        sequencer.advanceBatch(735);
        AudioParityTrackState heldPsg1 = S1AudioStateNormalizer.normalize(
                sequencer.captureSnapshot(), GHZ, Set.of()).tracks().get(7);
        assertEquals(3, heldPsg1.envelopeCursor(),
                "S1 VolEnvHold backs the cursor onto the 0x80 terminator");
    }

    @Test
    void productionS1MaximumPsgNoteUsesTheShippedZeroPeriodBytes() {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        SmpsSequencer sequencer = new SmpsSequencer(new MaximumPsgData(),
                AudioTestFixtures.EMPTY_DAC, synth, () -> {}, Sonic1SmpsSequencerConfig.CONFIG);
        List<Integer> psgWrites = new ArrayList<>();
        synth.setChipWriteObserver(new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) { }
            @Override public void onPsgWrite(int value) { psgWrites.add(value); }
        });

        sequencer.advanceSamples(0);

        AudioParityTrackState psg1 = S1AudioStateNormalizer.normalize(
                sequencer.captureSnapshot(), GHZ, Set.of()).tracks().get(7);
        assertEquals(0, psg1.baseFrequency(),
                "S1 PSGFrequencies ends nMaxPSG with the ROM's zero period");
        assertEquals(List.of(0x80, 0x00, 0x90), psgWrites,
                "the zero-period note must reach the chip in the ROM's latch/data/volume order");
    }

    @Test
    void productionS1NoteFillExpirySkipsTheRemainingPsgUpdate() {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        SmpsSequencer sequencer = new SmpsSequencer(new NoteFillPsgData(),
                AudioTestFixtures.EMPTY_DAC, synth, () -> {}, Sonic1SmpsSequencerConfig.CONFIG);
        sequencer.setSampleRate(44_100);
        List<Integer> psgWrites = new ArrayList<>();
        synth.setChipWriteObserver(new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) { }
            @Override public void onPsgWrite(int value) { psgWrites.add(value); }
        });

        sequencer.advanceSamples(0);
        psgWrites.clear();
        sequencer.advanceBatch(735);
        psgWrites.clear();
        sequencer.advanceBatch(735);

        AudioParityTrackState psg1 = S1AudioStateNormalizer.normalize(
                sequencer.captureSnapshot(), GHZ, Set.of()).tracks().get(7);
        assertEquals(2, psg1.envelopeCursor(),
                "S1 NoteTimeoutUpdate exits PSGUpdateTrack before its envelope step");
        assertEquals(List.of(0x9f), psgWrites,
                "note-fill expiry emits only the PSG note-off");

        psgWrites.clear();
        sequencer.advanceBatch(735);
        AudioParityTrackState afterExpiry = S1AudioStateNormalizer.normalize(
                sequencer.captureSnapshot(), GHZ, Set.of()).tracks().get(7);
        assertEquals(3, afterExpiry.envelopeCursor(),
                "the envelope cursor keeps advancing on the resting track");
        assertEquals(List.of(), psgWrites,
                "the resting bit suppresses later envelope writes after note-fill expiry");
    }

    @Test
    void productionS1VoiceUploadUsesTheShippedRegisterOrderWithoutAnInjectedKeyOff() {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        SmpsSequencer sequencer = new SmpsSequencer(new OneFmVoiceData(),
                AudioTestFixtures.EMPTY_DAC, synth, () -> {}, Sonic1SmpsSequencerConfig.CONFIG);
        List<AudioParityChipWrite> writes = new ArrayList<>();
        synth.setChipWriteObserver(new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                writes.add(AudioParityChipWrite.ym2612(port, register, value));
            }
            @Override public void onPsgWrite(int value) { }
        });

        sequencer.advanceSamples(0);

        List<AudioParityChipWrite> expected = new ArrayList<>();
        expected.add(AudioParityChipWrite.ym2612(0, 0xb0, 0x34));
        int[] operatorOffsets = {0, 8, 4, 12};
        int[] normalizedVoiceIndices = {0, 2, 1, 3};
        int[] parameterRegisters = {0x30, 0x50, 0x60, 0x70, 0x80};
        for (int group = 0; group < 5; group++) {
            for (int operator = 0; operator < 4; operator++) {
                expected.add(AudioParityChipWrite.ym2612(0,
                        parameterRegisters[group] + operatorOffsets[operator],
                        1 + group * 4 + normalizedVoiceIndices[operator]));
            }
        }
        int[] expectedTl = {0x18, 0x92, 0x80, 0x92};
        for (int operator = 0; operator < 4; operator++) {
            expected.add(AudioParityChipWrite.ym2612(0, 0x40 + operatorOffsets[operator],
                    expectedTl[operator]));
        }
        expected.add(AudioParityChipWrite.ym2612(0, 0xb4, 0xc0));
        expected.add(AudioParityChipWrite.ym2612(0, 0x28, 0));
        expected.add(AudioParityChipWrite.ym2612(0, 0xa4, 2));
        expected.add(AudioParityChipWrite.ym2612(0, 0xa0, 0x5e));
        expected.add(AudioParityChipWrite.ym2612(0, 0x28, 0xf0));
        assertEquals(expected, writes,
                "S1 SetVoice and note start preserve the shipped order without injected writes");

        writes.clear();
        sequencer.refreshVolume(sequencer.getTracks().get(0));
        assertEquals(List.of(
                AudioParityChipWrite.ym2612(0, 0x48, 0x92),
                AudioParityChipWrite.ym2612(0, 0x4c, 0x92)), writes,
                "S1 SendVoiceTL writes only carriers in 1,3,2,4 table order");
    }

    @Test
    void productionS1NoAttackBitRemainsLatchedForTheTiedNote() {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        SmpsSequencer sequencer = new SmpsSequencer(new NoAttackFmData(),
                AudioTestFixtures.EMPTY_DAC, synth, () -> {}, Sonic1SmpsSequencerConfig.CONFIG);
        sequencer.setSampleRate(44_100);
        List<AudioParityChipWrite> writes = new ArrayList<>();
        synth.setChipWriteObserver(new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                writes.add(AudioParityChipWrite.ym2612(port, register, value));
            }
            @Override public void onPsgWrite(int value) { }
        });

        sequencer.advanceSamples(0);
        writes.clear();
        sequencer.advanceBatch(735);

        assertTrue(sequencer.getTracks().get(0).tieNext,
                "S1 leaves PlaybackControl bit 4 set throughout the tied note");
        assertEquals(List.of(
                AudioParityChipWrite.ym2612(0, 0xa4, 2),
                AudioParityChipWrite.ym2612(0, 0xa0, 0x84),
                AudioParityChipWrite.ym2612(0, 0x28, 0xf0)), writes,
                "the tied note suppresses key-off but still performs S1 FMNoteOn");
    }

    @Test
    void productionS1ModulationHalvesTheConfiguredStepCount() {
        SmpsSequencer sequencer = new SmpsSequencer(new ModulationStepFmData(),
                AudioTestFixtures.EMPTY_DAC, new VirtualSynthesizer(), () -> {},
                Sonic1SmpsSequencerConfig.CONFIG);

        sequencer.advanceSamples(0);

        assertEquals(1, sequencer.getTracks().get(0).modStepCounter,
                "S1 cfModulation and FinishTrackUpdate shift the raw step byte right once");
    }

    @Test
    void productionS1FmModulationContinuesWhileTheTrackIsResting() {
        VirtualSynthesizer synth = new VirtualSynthesizer();
        SmpsSequencer sequencer = new SmpsSequencer(new RestingModulationFmData(),
                AudioTestFixtures.EMPTY_DAC, synth, () -> {}, Sonic1SmpsSequencerConfig.CONFIG);
        sequencer.setSampleRate(44_100);
        List<AudioParityChipWrite> writes = new ArrayList<>();
        synth.setChipWriteObserver(new ChipWriteObserver() {
            @Override public void onYm2612Write(int port, int register, int value) {
                writes.add(AudioParityChipWrite.ym2612(port, register, value));
            }
            @Override public void onPsgWrite(int value) { }
        });

        sequencer.advanceSamples(0);
        writes.clear();
        sequencer.advanceBatch(735);

        assertEquals(List.of(
                AudioParityChipWrite.ym2612(0, 0xa4, 0xff),
                AudioParityChipWrite.ym2612(0, 0xa0, 0xff)), writes,
                "S1 DoModulation falls through to FMUpdateFreq even for a resting track");
    }

    @Test
    void productionS1RestTransitionReloadsModulationPhase() {
        SmpsSequencer sequencer = new SmpsSequencer(new RestResetsModulationFmData(),
                AudioTestFixtures.EMPTY_DAC, new VirtualSynthesizer(), () -> {},
                Sonic1SmpsSequencerConfig.CONFIG);
        sequencer.setSampleRate(44_100);

        sequencer.advanceSamples(0);
        sequencer.advanceBatch(735);
        sequencer.advanceBatch(735);

        SmpsSequencer.Track track = sequencer.getTracks().get(0);
        assertTrue(track.resting);
        assertEquals(2, track.modDelay,
                "S1 FinishTrackUpdate reloads modulation delay on a non-tied rest");
        assertEquals(2, track.modStepCounter);
        assertEquals(0, track.modAccumulator);
    }

    @Test
    void openGgfGoldenInputProducesTheExistingCanonicalContract() throws IOException {
        JsonNode golden = JSON.readTree(Files.readString(Path.of(
                "src/test/resources/audio/parity/s1/normalization-contract-v1.json")));
        JsonNode input = golden.path("openGgf");
        Set<Integer> loopIndices = Set.copyOf(toInts(golden.path("activeLoopIndices")));
        List<SmpsTrackSnapshot> tracks = new ArrayList<>();
        for (JsonNode node : input.path("tracks")) {
            if (!node.path("active").asBoolean()) {
                continue;
            }
            String role = node.path("role").asText();
            TrackType type = role.equals("DAC") ? TrackType.DAC : role.startsWith("PSG") ? TrackType.PSG : TrackType.FM;
            int channel = role.equals("DAC") ? 5 : role.startsWith("PSG")
                    ? Integer.parseInt(role.substring(3)) - 1 : Integer.parseInt(role.substring(2)) - 1;
            tracks.add(track(node.path("position").asInt(), type, channel, true,
                    node.path("duration").asInt(), node.path("scaledDuration").asInt(),
                    node.path("transpose").asInt(), node.path("volume").asInt(), node.path("detune").asInt(),
                    type == TrackType.FM ? node.path("baseFrequency").asInt() & 0x7FF : node.path("baseFrequency").asInt(),
                    type == TrackType.FM ? node.path("baseFrequency").asInt() >>> 11 : 0,
                    node.path("pan").asInt(), node.path("ams").asInt(), node.path("fms").asInt(),
                    type == TrackType.PSG ? node.path("envPos").asInt() : node.path("voiceId").asInt(),
                    type == TrackType.PSG ? node.path("instrumentId").asInt() : 0,
                    toIntArray(node.path("loopCounters")),
                    toIntArray(node.path("returnStack")), node.path("returnSp").asInt(),
                    node.path("tieNext").asBoolean(), node.path("overridden").asBoolean(),
                    node.path("modEnabled").asBoolean(), 0, 0, 0, 0, 0));
        }
        JsonNode global = input.path("global");
        SmpsSequencerSnapshot snapshot = sequencer(tracks, global.path("tempoReload").asInt(),
                global.path("tempoTimeout").asInt());
        S1AudioStateNormalizer.NormalizedState state = S1AudioStateNormalizer.normalize(snapshot, GHZ, loopIndices);
        AudioParityTick tick = new AudioParityTick(0, state.global(), state.tracks(), List.of(
                new AudioParityChipWrite("ym2612", 0, 34, 17),
                new AudioParityChipWrite("ym2612", 1, 42, 128),
                new AudioParityChipWrite("psg", null, null, 159)));

        assertEquals(golden.path("expectedCanonicalJson").asText(), AudioParityJsonl.canonicalGatingJson(tick));
    }

    private static SmpsSequencerSnapshot sequencer(List<SmpsTrackSnapshot> tracks, int tempoWeight,
            int tempoAccumulator) {
        return new SmpsSequencerSnapshot(Region.NTSC, false, false, tempoWeight, 0, false,
                Integer.MAX_VALUE, 1.0f, 0, false, false, 0, 1, 0,
                new SmpsSequencerSnapshot.FadeSnapshot(0, 0, 0, 0, 0, false, false),
                44100, 735, 0, tempoWeight, tempoAccumulator, 1, true, tracks);
    }

    private static final class RestingPsgData extends AbstractSmpsData {
        private RestingPsgData() {
            super(new byte[] {0, (byte) 0x80, 0x40}, 0);
        }

        @Override
        protected void parseHeader() {
            psgPointers = new int[] {1};
            psgKeyOffsets = new int[] {0};
            psgVolumeOffsets = new int[] {0};
            psgModEnvs = new int[] {0};
            psgInstruments = new int[] {0};
            tempo = 3;
        }

        @Override public byte[] getVoice(int voiceId) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return new byte[] {0, 0, 0, (byte) 0x80}; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static final class MaximumPsgData extends AbstractSmpsData {
        private MaximumPsgData() {
            super(new byte[] {0, (byte) 0xC6, 0x10}, 0);
        }

        @Override
        protected void parseHeader() {
            psgPointers = new int[] {1};
            psgKeyOffsets = new int[] {0};
            psgVolumeOffsets = new int[] {0};
            psgModEnvs = new int[] {0};
            psgInstruments = new int[] {0};
            tempo = 3;
        }

        @Override public byte[] getVoice(int voiceId) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return new byte[] {0}; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static final class OneFmVoiceData extends AbstractSmpsData {
        private static final byte[] VOICE = {
                0x34, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18, 19, 20, 0x18, (byte) 0x80, (byte) 0x80, (byte) 0x80
        };

        private OneFmVoiceData() {
            super(new byte[] {0, (byte) 0xef, 0, (byte) 0x81, 4}, 0);
        }

        @Override
        protected void parseHeader() {
            fmPointers = new int[] {99, 1};
            fmKeyOffsets = new int[] {0, 0};
            fmVolumeOffsets = new int[] {0, 18};
            tempo = 3;
        }

        @Override public byte[] getVoice(int voiceId) { return VOICE.clone(); }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static final class NoteFillPsgData extends AbstractSmpsData {
        private NoteFillPsgData() {
            super(new byte[] {0, (byte) 0xe8, 2, (byte) 0xf5, 1, (byte) 0x81, 8}, 0);
        }

        @Override
        protected void parseHeader() {
            psgPointers = new int[] {1};
            psgKeyOffsets = new int[] {0};
            psgVolumeOffsets = new int[] {0};
            psgModEnvs = new int[] {0};
            psgInstruments = new int[] {0};
            // A tempo of 1 is degenerate against the ROM's own seed. S1's
            // UpdateMusic adds 1 to every slot's DurationTimeout whenever the
            // tempo timeout expires (s1.sounddriver.asm:1549-1561), which at
            // tempo 1 is every frame, exactly cancelling the per-frame
            // decrement; a track seeded with the ROM's DurationTimeout of 1
            // (:823, :836) would then never reach zero and never read its
            // stream at all. Tempo 2 lets the note play, which is what this
            // fixture is for.
            tempo = 2;
        }

        @Override public byte[] getVoice(int voiceId) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return new byte[] {0, 0, 0, 0}; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static final class NoAttackFmData extends AbstractSmpsData {
        private NoAttackFmData() {
            super(new byte[] {0, (byte) 0x81, 1, (byte) 0xe7, (byte) 0x82, 3}, 0);
        }

        @Override
        protected void parseHeader() {
            fmPointers = new int[] {99, 1};
            fmKeyOffsets = new int[] {0, 0};
            fmVolumeOffsets = new int[] {0, 0};
            tempo = 3;
        }

        @Override public byte[] getVoice(int voiceId) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static final class ModulationStepFmData extends AbstractSmpsData {
        private ModulationStepFmData() {
            super(new byte[] {0, (byte) 0xf0, 0, 1, 1, 3, (byte) 0x81, 8}, 0);
        }

        @Override
        protected void parseHeader() {
            fmPointers = new int[] {99, 1};
            fmKeyOffsets = new int[] {0, 0};
            fmVolumeOffsets = new int[] {0, 0};
            tempo = 3;
        }

        @Override public byte[] getVoice(int voiceId) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static final class RestingModulationFmData extends AbstractSmpsData {
        private RestingModulationFmData() {
            super(new byte[] {0, (byte) 0xf0, 0, 1, (byte) 0xff, 4, (byte) 0x80, 3}, 0);
        }

        @Override
        protected void parseHeader() {
            fmPointers = new int[] {99, 1};
            fmKeyOffsets = new int[] {0, 0};
            fmVolumeOffsets = new int[] {0, 0};
            tempo = 3;
        }

        @Override public byte[] getVoice(int voiceId) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static final class RestResetsModulationFmData extends AbstractSmpsData {
        private RestResetsModulationFmData() {
            super(new byte[] {0, (byte) 0xf0, 2, 1, 1, 4,
                    (byte) 0x81, 2, (byte) 0x80, 3}, 0);
        }

        @Override
        protected void parseHeader() {
            fmPointers = new int[] {99, 1};
            fmKeyOffsets = new int[] {0, 0};
            fmVolumeOffsets = new int[] {0, 0};
            tempo = 4;
        }

        @Override public byte[] getVoice(int voiceId) { return null; }
        @Override public byte[] getPsgEnvelope(int id) { return null; }
        @Override public int read16(int offset) { return 0; }
        @Override public int getBaseNoteOffset() { return 0; }
    }

    private static SmpsTrackSnapshot track(int pos, TrackType type, int channelId, boolean active,
            int duration, int scaledDuration, int transpose, int volume, int detune,
            int baseFnum, int baseBlock, int pan, int ams, int fms, int voiceId, int instrumentOrEnv,
            int[] loops, int[] stack, int returnSp, boolean tie, boolean overridden, boolean modEnabled,
            int note, int fill, int modRateCounter, int modCurrentDelta, int modAccumulator) {
        int instrumentId = type == TrackType.PSG ? instrumentOrEnv : 0;
        int envPos = type == TrackType.PSG ? voiceId : 0;
        int actualVoiceId = type == TrackType.PSG ? 0 : voiceId;
        return new SmpsTrackSnapshot(pos, type, channelId, duration, note, active, overridden,
                scaledDuration, scaledDuration, fill, 0, false, transpose, volume, tie, pan, ams, fms,
                null, null, actualVoiceId, baseFnum, baseBlock, loops, -1, stack, returnSp, 1,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, modRateCounter, 0, (short) modAccumulator,
                modCurrentDelta, modEnabled, modEnabled, detune, 0, null, 0, 0, 0, false,
                false, 0, instrumentId, false, 0, 0, 0, null, envPos, 0, false, false,
                null, 0, 0, false, 0, false, new int[0], false,
                false, false, 0, false, false, 0, false,
                false, new int[0], false, 0, false);
    }

    private static List<Integer> toInts(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asInt()));
        return values;
    }

    private static int[] toIntArray(JsonNode array) {
        return toInts(array).stream().mapToInt(Integer::intValue).toArray();
    }
}
