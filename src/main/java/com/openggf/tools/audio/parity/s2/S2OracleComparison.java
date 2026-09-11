package com.openggf.tools.audio.parity.s2;

import com.openggf.audio.rewind.SmpsTrackSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * The S2 oracle's comparison vocabulary: the subset of the ROM zTrack and
 * driver globals this oracle compares per invocation, with the engine mapping
 * for each (behaviour spec section 2.3; engine map sections 2.2-2.4). Fields
 * outside {@link #COMPARED_TRACK_FIELDS} are deliberately not compared in this
 * first oracle tier; each exclusion carries its reason so the registry is an
 * honest classification, not silent scope loss.
 */
public final class S2OracleComparison {

    /** Track fields this tier compares, in report order. */
    public static final List<String> COMPARED_TRACK_FIELDS = List.of(
            "active", "dataPointer", "durationTimeout", "savedDuration", "transpose",
            "volume", "voiceIndex", "tempoDivider", "detune", "controlBits", "freq",
            "volFlutter");

    /** Global fields this tier compares, in report order. */
    public static final List<String> COMPARED_GLOBAL_FIELDS = List.of(
            "currentTempo", "tempoTimeout");

    /** ROM fields deliberately not compared in this tier, with reasons. */
    public static final List<String> NOT_COMPARED = List.of(
            "PlaybackControl bit 1 (resting): engine maintains it only on the direct-68k path (EM 2.3)",
            "PlaybackControl bit 2 (overridden): this tier injects no SFX requests yet",
            "AMSFMSPan: engine recomposes pan/ams/fms at write time; covered by the write stream",
            "StackPointer/GoSubStack/LoopCounters: ROM overlaps them in one region the engine splits",
            "NoteFillTimeout/Master: engine derives the Z80-path value (EM 2.3)",
            "Modulation fields: engine splits the ROM's decrementing bytes into init+counter pairs",
            "VolTLMask/TLPtr/VoicePtr: recomputed or materialised engine-side",
            "SFXPriorityVal/Queue0-2/QueueToPlay/StopMusic/zPaused: driver globals the engine lacks (GA 1.2 #4/#5/#14)",
            "SFX track slots: this tier drives music plus the speed-up command only",
            "DAC sample stream (2Ah data writes, DpcmIteration/DacDispatch/SegaPcm services): PCM tier, not sequencer writes");

    private S2OracleComparison() {
    }

    /**
     * One ROM music slot as this tier sees it, from either side. Values are in
     * ROM vocabulary; -1 marks a value the mapping cannot produce.
     */
    public record MappedTrack(boolean present, boolean active, int dataPointer,
            int durationTimeout, int savedDuration, int transpose, int volume,
            int voiceIndex, int tempoDivider, int detune, int controlBits, int freq,
            int volFlutter) {

        /** Control-bit mask compared by this tier: playing, do-not-attack, modulation. */
        public static final int CONTROL_MASK = 0x98;

        public static MappedTrack absent() {
            return new MappedTrack(false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public static MappedTrack fromEngine(SmpsTrackSnapshot track, int z80Start) {
            boolean psg = track.type() == com.openggf.audio.smps.SmpsSequencer.TrackType.PSG;
            boolean dac = track.type() == com.openggf.audio.smps.SmpsSequencer.TrackType.DAC;
            int control = (track.active() ? 0x80 : 0)
                    | (track.tieNext() ? 0x10 : 0)
                    | (track.modEnabled() ? 0x08 : 0);
            int freq;
            if (dac) {
                freq = -1;
            } else if (psg) {
                freq = track.baseFnum() & 0xffff;
            } else {
                freq = ((track.baseBlock() & 0x07) << 11) | (track.baseFnum() & 0x7ff);
            }
            return new MappedTrack(true, track.active(),
                    track.pos() + z80Start,
                    track.duration() & 0xff,
                    track.scaledDuration() & 0xff,
                    track.keyOffset() & 0xff,
                    track.volumeOffset() & 0xff,
                    (psg ? track.instrumentId() : track.voiceId()) & 0xff,
                    track.dividingTiming() & 0xff,
                    track.detune() & 0xff,
                    control,
                    freq,
                    psg ? track.envPos() & 0xff : -1);
        }

        public static MappedTrack fromReference(S2OracleDriverState.TrackState track,
                boolean psg, boolean dac) {
            return new MappedTrack(true, track.playing(),
                    track.dataPointer(),
                    track.durationTimeout(),
                    track.savedDuration(),
                    track.transpose(),
                    track.volume(),
                    track.voiceIndex(),
                    track.tempoDivider(),
                    track.detune(),
                    track.playbackControl() & CONTROL_MASK & ~0x80
                            | (track.playing() ? 0x80 : 0),
                    dac ? -1 : track.freq(),
                    psg ? track.volFlutter() : -1);
        }

        /**
         * Field-by-field difference against another mapped slot, honouring the
         * ROM rule that an inactive slot's remaining bytes are stale storage:
         * when both sides agree the slot is not playing, only {@code active}
         * is compared.
         */
        public List<String> differences(MappedTrack other) {
            List<String> failures = new ArrayList<>();
            if (active != other.active) {
                failures.add(difference("active", active, other.active));
                return failures;
            }
            if (!active) {
                return failures;
            }
            compare(failures, "dataPointer", dataPointer, other.dataPointer);
            compare(failures, "durationTimeout", durationTimeout, other.durationTimeout);
            compare(failures, "savedDuration", savedDuration, other.savedDuration);
            compare(failures, "transpose", transpose, other.transpose);
            compare(failures, "volume", volume, other.volume);
            compare(failures, "voiceIndex", voiceIndex, other.voiceIndex);
            compare(failures, "tempoDivider", tempoDivider, other.tempoDivider);
            compare(failures, "detune", detune, other.detune);
            compare(failures, "controlBits", controlBits, other.controlBits);
            if (freq >= 0 && other.freq >= 0) {
                compare(failures, "freq", freq, other.freq);
            }
            if (volFlutter >= 0 && other.volFlutter >= 0) {
                compare(failures, "volFlutter", volFlutter, other.volFlutter);
            }
            return failures;
        }

        private static void compare(List<String> failures, String field, int expected,
                int actual) {
            if (expected != actual) {
                failures.add(difference(field, expected, actual));
            }
        }

        private static String difference(String field, Object expected, Object actual) {
            return field + " expected=" + hex(expected) + " actual=" + hex(actual);
        }

        private static String hex(Object value) {
            if (value instanceof Integer number) {
                return "0x" + Integer.toHexString(number);
            }
            return String.valueOf(value);
        }
    }
}
