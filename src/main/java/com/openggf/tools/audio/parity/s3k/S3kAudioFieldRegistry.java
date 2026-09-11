package com.openggf.tools.audio.parity.s3k;

import java.util.List;

/**
 * Executable inventory of the S3K oracle comparison vocabulary.
 *
 * <p>Sources: skdisasm {@code Sound/Z80 Sound Driver.asm} via
 * {@code docs/architecture/research/audio/2026-08-30-s3k-sound-driver-routine-map.md}
 * (§1.3 variables, §2 zTrack) and the engine's
 * {@code SmpsSequencerSnapshot}/{@code SmpsTrackSnapshot}.
 *
 * <p>GATE fields are compared tick-by-tick; DIAGNOSTIC fields are decoded from
 * the reference but not compared, either because the engine does not model the
 * byte (load-time cursors, write-only bytes) or because the engine-side
 * mapping is not yet pinned by a design (DataPointer's asset coordinate,
 * modulation phase bytes, zDACIndex).
 */
public final class S3kAudioFieldRegistry {
    public enum Scope { GLOBAL, TRACK }
    public enum Comparison { GATE, DIAGNOSTIC }

    public record Field(String name, Scope scope, String romSource, String engineSource,
            Comparison comparison) {
        public Field {
            if (name == null || name.isBlank() || romSource == null || romSource.isBlank()) {
                throw new IllegalArgumentException("field name and ROM source are required");
            }
        }
    }

    private static final List<Field> FIELDS = List.of(
            field("currentTempo", Scope.GLOBAL, "zCurrentTempo 1C24 (map §3.4)",
                    "SmpsSequencerSnapshot.tempoWeight", Comparison.GATE),
            field("tempoAccumulator", Scope.GLOBAL, "zTempoAccumulator 1C13 (map §3.4)",
                    "SmpsSequencerSnapshot.tempoAccumulator", Comparison.GATE),
            field("tempoSpeedup", Scope.GLOBAL, "zTempoSpeedup 1C08 (map §3.5)",
                    "speedShoes ? profile multiplier : 0", Comparison.GATE),
            field("speedupTimeout", Scope.GLOBAL, "zSpeedupTimeout 1C2F (map §3.5)",
                    "SmpsSequencerSnapshot.speedupTimeout", Comparison.GATE),
            field("dacIndex", Scope.GLOBAL, "zDACIndex 1C30 (map §9.1)",
                    "no direct engine byte; DAC state lives in the DAC track", Comparison.DIAGNOSTIC),
            field("fadeOutTimeout", Scope.GLOBAL, "zFadeOutTimeout 1C0D (map §5.1)",
                    "SmpsDriverSnapshot.fadeOutTimeout", Comparison.GATE),
            field("fadeDelay", Scope.GLOBAL, "zFadeDelay 1C0E",
                    "SmpsDriverSnapshot.fadeDelay", Comparison.GATE),
            field("fadeDelayTimeout", Scope.GLOBAL, "zFadeDelayTimeout 1C0F",
                    "SmpsDriverSnapshot.fadeDelayTimeout", Comparison.GATE),
            field("fadeInTimeout", Scope.GLOBAL, "zFadeInTimeout 1C29 (map §5.2)",
                    "SmpsDriverSnapshot.fadeInTimeout", Comparison.GATE),
            field("pauseFlag", Scope.GLOBAL, "zPauseFlag 1C10 (map §3.6)",
                    "presentation-side SILENT mode, not a driver flag", Comparison.DIAGNOSTIC),
            field("soundQueue", Scope.GLOBAL, "zSoundQueue0..2 1C05-07 (map §4.2)",
                    "no driver-side queue bytes (gap #4)", Comparison.DIAGNOSTIC),
            field("nextSound", Scope.GLOBAL, "zNextSound 1C09 (map §4.2)", "none", Comparison.DIAGNOSTIC),
            field("palDoubleUpdateCounter", Scope.GLOBAL,
                    "zPalDblUpdCounter 1C04 (map §1.3)",
                    "SmpsDriverSnapshot.palUpdateCounter", Comparison.GATE),

            field("playing", Scope.TRACK, "PlaybackControl bit 7 (map §2)",
                    "SmpsTrackSnapshot.active", Comparison.GATE),
            field("overridden", Scope.TRACK, "PlaybackControl bit 2",
                    "SmpsTrackSnapshot.overridden", Comparison.GATE),
            field("doNotAttack", Scope.TRACK, "PlaybackControl bit 1",
                    "SmpsTrackSnapshot.tieNext", Comparison.GATE),
            field("resting", Scope.TRACK, "PlaybackControl bit 4",
                    "SmpsTrackSnapshot.resting", Comparison.GATE),
            field("voiceControl", Scope.TRACK, "VoiceControl offset 01",
                    "derived from type/channelId in the normalizer", Comparison.GATE),
            field("tempoDivider", Scope.TRACK, "TempoDivider offset 02",
                    "SmpsTrackSnapshot.dividingTiming", Comparison.GATE),
            field("dataPointer", Scope.TRACK, "DataPointer offsets 03-04 (Z80 address)",
                    "SmpsTrackSnapshot.pos is asset-relative; mapping not yet pinned",
                    Comparison.DIAGNOSTIC),
            field("transpose", Scope.TRACK, "Transpose offset 05",
                    "SmpsTrackSnapshot.keyOffset", Comparison.GATE),
            field("volume", Scope.TRACK, "Volume offset 06",
                    "SmpsTrackSnapshot.volumeOffset", Comparison.GATE),
            field("modulationCtrl", Scope.TRACK, "ModulationCtrl offset 07",
                    "modEnabled/modEnvId composite", Comparison.GATE),
            field("voiceIndex", Scope.TRACK, "VoiceIndex offset 08 (1-based, 0 none)",
                    "voiceId (FM) / instrumentId (PSG); base offset verified red by the oracle",
                    Comparison.GATE),
            field("amsFmsPan", Scope.TRACK, "AMSFMSPan offset 0A (raw B4 byte)",
                    "pan | ams<<4 | fms", Comparison.GATE),
            field("durationTimeout", Scope.TRACK, "DurationTimeout offset 0B",
                    "SmpsTrackSnapshot.duration", Comparison.GATE),
            field("savedDuration", Scope.TRACK, "SavedDuration offset 0C",
                    "SmpsTrackSnapshot.scaledDuration", Comparison.GATE),
            field("frequency", Scope.TRACK, "FreqLow/FreqHigh offsets 0D-0E",
                    "FM: ((block<<3)|(fnum>>8))<<8 | fnum&FF; PSG: baseFnum", Comparison.GATE),
            field("detune", Scope.TRACK, "Detune offset 10",
                    "SmpsTrackSnapshot.detune", Comparison.GATE),
            field("volEnv", Scope.TRACK, "VolEnv offset 17",
                    "SmpsTrackSnapshot.envPos, the envelope position", Comparison.GATE),
            field("noteFillTimeout", Scope.TRACK, "NoteFillTimeout offset 1E",
                    "SmpsTrackSnapshot.fillCounter", Comparison.GATE),
            field("noteFillMaster", Scope.TRACK, "NoteFillMaster offset 1F",
                    "SmpsTrackSnapshot.fill", Comparison.GATE),
            field("modulationPtr", Scope.TRACK, "ModulationPtr offsets 20-21",
                    "Z80 address; engine has no equivalent", Comparison.DIAGNOSTIC),
            field("modulationVal", Scope.TRACK, "ModulationVal offsets 22-23",
                    "SmpsTrackSnapshot.modAccumulator", Comparison.GATE),
            field("modulationWait", Scope.TRACK, "ModulationWait offset 24",
                    "SmpsTrackSnapshot.modDelay", Comparison.GATE),
            field("modulationSpeed", Scope.TRACK, "ModulationSpeed offset 25",
                    "SmpsTrackSnapshot.modRateCounter", Comparison.GATE),
            field("modulationDelta", Scope.TRACK, "ModulationDelta offset 26",
                    "SmpsTrackSnapshot.modCurrentDelta", Comparison.GATE),
            field("modulationSteps", Scope.TRACK, "ModulationSteps offset 27",
                    "SmpsTrackSnapshot.modStepCounter", Comparison.GATE));

    private S3kAudioFieldRegistry() {
    }

    public static List<Field> fields() {
        return FIELDS;
    }

    public static List<Field> gates(Scope scope) {
        return FIELDS.stream()
                .filter(field -> field.scope() == scope && field.comparison() == Comparison.GATE)
                .toList();
    }

    private static Field field(String name, Scope scope, String romSource, String engineSource,
            Comparison comparison) {
        return new Field(name, scope, romSource, engineSource, comparison);
    }
}
