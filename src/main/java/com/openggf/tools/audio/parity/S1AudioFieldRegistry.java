package com.openggf.tools.audio.parity;

import java.util.List;

/** Executable inventory of the S1 snapshot fields considered by the parity contract. */
public final class S1AudioFieldRegistry {
    public enum Signedness { BOOLEAN, UNSIGNED_BYTE, SIGNED_BYTE, UNSIGNED_WORD, RELATIVE_POSITION, STRUCTURAL }
    public enum Applicability { GLOBAL, ALL_ROLES, ACTIVE_ROLES, FM_DAC_ONLY, FM_PSG_ONLY, PSG_ONLY }
    public enum Comparison { GATE, DIAGNOSTIC }

    public record Field(String name, String source, Signedness signedness,
            Applicability applicability, Comparison comparison) {
        public Field {
            if (name == null || name.isBlank() || source == null || source.isBlank()) {
                throw new IllegalArgumentException("field name and source are required");
            }
        }
    }

    private static final List<Field> FIELDS = List.of(
            field("tempoReload", "SmpsSequencerSnapshot.tempoWeight (S1 NTSC TIMEOUT phase)",
                    Signedness.UNSIGNED_BYTE, Applicability.GLOBAL, Comparison.GATE),
            field("tempoTimeout", "SmpsSequencerSnapshot.tempoAccumulator",
                    Signedness.UNSIGNED_BYTE, Applicability.GLOBAL, Comparison.GATE),
            field("speedUp", "SmpsSequencerSnapshot.speedShoes", Signedness.BOOLEAN,
                    Applicability.GLOBAL, Comparison.GATE),
            field("fadeState", "SmpsSequencerSnapshot.fade", Signedness.STRUCTURAL,
                    Applicability.GLOBAL, Comparison.GATE),
            field("speedUpReload", "Sonic1SmpsSequencerConfig speed-up tempo",
                    Signedness.UNSIGNED_BYTE, Applicability.GLOBAL, Comparison.DIAGNOSTIC),
            field("active", "SmpsTrackSnapshot.active", Signedness.BOOLEAN,
                    Applicability.ALL_ROLES, Comparison.GATE),
            field("role", "SmpsTrackSnapshot.type and channelId", Signedness.STRUCTURAL,
                    Applicability.ALL_ROLES, Comparison.GATE),
            field("hardware", "SmpsTrackSnapshot.type and channelId", Signedness.STRUCTURAL,
                    Applicability.ALL_ROLES, Comparison.GATE),
            field("overridden", "SmpsTrackSnapshot.overridden", Signedness.BOOLEAN,
                    Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("doNotAttack", "SmpsTrackSnapshot.tieNext", Signedness.BOOLEAN,
                    Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("modulationEnabled", "SmpsTrackSnapshot.modEnabled", Signedness.BOOLEAN,
                    Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("sequencePosition", "SmpsTrackSnapshot.pos in the GHZ asset coordinate",
                    Signedness.RELATIVE_POSITION, Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("transpose", "SmpsTrackSnapshot.keyOffset", Signedness.SIGNED_BYTE,
                    Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("volume", "SmpsTrackSnapshot.volumeOffset", Signedness.SIGNED_BYTE,
                    Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("pan", "SmpsTrackSnapshot.pan", Signedness.UNSIGNED_BYTE,
                    Applicability.FM_DAC_ONLY, Comparison.GATE),
            field("ams", "SmpsTrackSnapshot.ams", Signedness.UNSIGNED_BYTE,
                    Applicability.FM_DAC_ONLY, Comparison.GATE),
            field("fms", "SmpsTrackSnapshot.fms", Signedness.UNSIGNED_BYTE,
                    Applicability.FM_DAC_ONLY, Comparison.GATE),
            field("voiceOrEnvelope", "SmpsTrackSnapshot.voiceId (FM/DAC) or instrumentId (PSG)",
                    Signedness.UNSIGNED_BYTE, Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("envelopeCursor", "SmpsTrackSnapshot.envPos", Signedness.UNSIGNED_BYTE,
                    Applicability.PSG_ONLY, Comparison.GATE),
            field("duration", "SmpsTrackSnapshot.duration", Signedness.UNSIGNED_BYTE,
                    Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("durationReload", "SmpsTrackSnapshot.scaledDuration", Signedness.UNSIGNED_BYTE,
                    Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("baseFrequency", "SmpsTrackSnapshot.baseBlock/baseFnum", Signedness.UNSIGNED_WORD,
                    Applicability.FM_PSG_ONLY, Comparison.GATE),
            field("detune", "SmpsTrackSnapshot.detune", Signedness.SIGNED_BYTE,
                    Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("loopCounters", "SmpsTrackSnapshot.loopCounters at parsed GHZ $F7 indices",
                    Signedness.UNSIGNED_BYTE, Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("returnStack", "SmpsTrackSnapshot.returnStack[0..returnSp) in call order",
                    Signedness.RELATIVE_POSITION, Applicability.ACTIVE_ROLES, Comparison.GATE),
            field("resting", "SmpsTrackSnapshot.note / envelope rest state",
                    Signedness.BOOLEAN, Applicability.ACTIVE_ROLES, Comparison.DIAGNOSTIC),
            field("rawDuration", "SmpsTrackSnapshot.rawDuration", Signedness.UNSIGNED_BYTE,
                    Applicability.ACTIVE_ROLES, Comparison.DIAGNOSTIC),
            field("noteFillPhase", "SmpsTrackSnapshot.fill/duration/scaledDuration",
                    Signedness.STRUCTURAL, Applicability.ACTIVE_ROLES, Comparison.DIAGNOSTIC),
            field("modulationPhase", "SmpsTrackSnapshot modulation counters/delta/accumulator",
                    Signedness.STRUCTURAL, Applicability.ACTIVE_ROLES, Comparison.DIAGNOSTIC));

    private S1AudioFieldRegistry() {
    }

    public static List<Field> fields() {
        return FIELDS;
    }

    private static Field field(String name, String source, Signedness signedness,
            Applicability applicability, Comparison comparison) {
        return new Field(name, source, signedness, applicability, comparison);
    }
}
