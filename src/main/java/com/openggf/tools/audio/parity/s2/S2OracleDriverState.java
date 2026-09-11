package com.openggf.tools.audio.parity.s2;

import java.util.List;

/**
 * Raw ROM-vocabulary view of one S2 driver invocation's Z80 RAM image.
 * Field names, offsets and semantics follow the shipped REV01 driver
 * (s2.sounddriver.asm; layout at sd:84-166 and sd:4087-4096, mirrored by
 * the S2 routine map and behaviour spec section 2). This decoder reads
 * bytes only — no asset validation, no engine semantics — so it accepts
 * every transient state a real invocation can leave behind.
 */
public record S2OracleDriverState(Globals globals, List<TrackState> musicTracks,
        List<TrackState> sfxTracks) {

    private static final int VAR = 0x1b80;
    private static final int MUSIC_TRACKS = 0x1b98;
    private static final int SFX_TRACKS = 0x1d3c;
    private static final int TRACK_BYTES = 0x2a;

    /** The ten music slots in RAM order (sd:183-200). */
    public static final List<String> MUSIC_SLOTS = List.of(
            "DAC", "FM1", "FM2", "FM3", "FM4", "FM5", "FM6", "PSG1", "PSG2", "PSG3");
    /** The six SFX slots in RAM order (sd:202-213). */
    public static final List<String> SFX_SLOTS = List.of(
            "SFX_FM3", "SFX_FM4", "SFX_FM5", "SFX_PSG1", "SFX_PSG2", "SFX_PSG3");

    public S2OracleDriverState {
        musicTracks = List.copyOf(musicTracks);
        sfxTracks = List.copyOf(sfxTracks);
    }

    /** zVar (sd:142-166) plus the driver globals outside it (sd:4087-4096). */
    public record Globals(int sfxPriority, int tempoTimeout, int currentTempo, int stopMusic,
            int fadeOutCounter, int fadeOutDelay, int communication, int dacUpdating,
            int queueToPlay, int queue0, int queue1, int queue2, int voiceTblPtr,
            int fadeInFlag, int fadeInDelay, int fadeInCounter, int oneUpPlaying,
            int tempoMod, int tempoTurbo, int speedUpFlag, int dacEnabled,
            int musicBankNumber, int isPalFlag, int palUpdTick, int curDac, int curSong,
            int doSfxFlag, int ringSpeaker, int gloopFlag, int spindashPlayingCounter,
            int spindashExtraFrequencyIndex, int spindashActiveFlag, int paused) {
    }

    /** One zTrack (sd:84-140), raw bytes widened to ints. */
    public record TrackState(String slot, int playbackControl, int voiceControl,
            int tempoDivider, int dataPointer, int transpose, int volume, int amsFmsPan,
            int voiceIndex, int volFlutter, int stackPointer, int durationTimeout,
            int savedDuration, int freq, int noteFillTimeout, int noteFillMaster,
            int modulationPtr, int modulationWait, int modulationSpeed, int modulationDelta,
            int modulationSteps, int modulationVal, int detune, int volTlMask, int psgNoise,
            int voicePtr, int tlPtr, List<Integer> loopCounters) {

        public TrackState {
            loopCounters = List.copyOf(loopCounters);
        }

        public boolean playing() {
            return (playbackControl & 0x80) != 0;
        }
    }

    public static S2OracleDriverState decode(byte[] state) {
        if (state == null || state.length != S2OracleSchema.STATE_BYTES) {
            throw new IllegalArgumentException("S2 driver state must be exactly 8192 bytes");
        }
        Globals globals = new Globals(
                u8(state, VAR), u8(state, VAR + 1), u8(state, VAR + 2), u8(state, VAR + 3),
                u8(state, VAR + 4), u8(state, VAR + 5), u8(state, VAR + 6), u8(state, VAR + 7),
                u8(state, VAR + 8), u8(state, VAR + 9), u8(state, VAR + 0x0a), u8(state, VAR + 0x0b),
                word(state, VAR + 0x0c), u8(state, VAR + 0x0e), u8(state, VAR + 0x0f),
                u8(state, VAR + 0x10), u8(state, VAR + 0x11), u8(state, VAR + 0x12),
                u8(state, VAR + 0x13), u8(state, VAR + 0x14), u8(state, VAR + 0x15),
                u8(state, VAR + 0x16), u8(state, VAR + 0x17),
                u8(state, 0x12fe), u8(state, 0x12ff), u8(state, 0x1300), u8(state, 0x1301),
                u8(state, 0x1302), u8(state, 0x1303), u8(state, 0x1304), u8(state, 0x1305),
                u8(state, 0x1306), u8(state, 0x1307));
        TrackState[] music = new TrackState[MUSIC_SLOTS.size()];
        for (int index = 0; index < music.length; index++) {
            music[index] = track(state, MUSIC_SLOTS.get(index),
                    MUSIC_TRACKS + index * TRACK_BYTES);
        }
        TrackState[] sfx = new TrackState[SFX_SLOTS.size()];
        for (int index = 0; index < sfx.length; index++) {
            sfx[index] = track(state, SFX_SLOTS.get(index), SFX_TRACKS + index * TRACK_BYTES);
        }
        return new S2OracleDriverState(globals, List.of(music), List.of(sfx));
    }

    private static TrackState track(byte[] state, String slot, int base) {
        Integer[] loops = new Integer[10];
        for (int index = 0; index < loops.length; index++) {
            loops[index] = u8(state, base + 0x20 + index);
        }
        return new TrackState(slot,
                u8(state, base), u8(state, base + 1), u8(state, base + 2),
                word(state, base + 3), u8(state, base + 5), u8(state, base + 6),
                u8(state, base + 7), u8(state, base + 8), u8(state, base + 9),
                u8(state, base + 0x0a), u8(state, base + 0x0b), u8(state, base + 0x0c),
                word(state, base + 0x0d), u8(state, base + 0x0f), u8(state, base + 0x10),
                word(state, base + 0x11), u8(state, base + 0x13), u8(state, base + 0x14),
                u8(state, base + 0x15), u8(state, base + 0x16), word(state, base + 0x17),
                u8(state, base + 0x19), u8(state, base + 0x1a), u8(state, base + 0x1b),
                word(state, base + 0x1c), word(state, base + 0x1e), List.of(loops));
    }

    private static int u8(byte[] state, int offset) {
        return state[offset] & 0xff;
    }

    private static int word(byte[] state, int offset) {
        return u8(state, offset) | u8(state, offset + 1) << 8;
    }
}
