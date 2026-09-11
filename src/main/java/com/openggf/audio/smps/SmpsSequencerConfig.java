package com.openggf.audio.smps;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SmpsSequencerConfig {

    public enum TempoMode {
        /**
         * S3K (TempoWait D:2607-2621): accumulator carry → delay frame — every
         * music slot's DurationTimeout is pre-incremented and the track walk
         * still runs. Higher tempo = more delays = slower.
         */
        OVERFLOW,
        /**
         * S2 (TempoWait sd:596-619): accumulator NO-carry → delay frame — every
         * music slot's DurationTimeout is pre-incremented and the track walk
         * still runs. Higher tempo = fewer delays = faster.
         */
        OVERFLOW2,
        /**
         * S1 (TempoWait SD:1549-1561): countdown from tempo; on expiry every
         * music slot's DurationTimeout is extended by 1. Always tick.
         */
        TIMEOUT
    }

    /** ROM PAL compensation performed at the driver invocation boundary. */
    public enum PalUpdateMode {
        /** S1: no PAL cadence branch. */
        NONE,
        /** S2 sd:441-452: repeat music only when the five-count expires. */
        EXTRA_MUSIC,
        /** S3K D:482-499: repeat the complete SFX-then-music update. */
        EXTRA_FULL
    }

    /** How carrier operators are determined for volume scaling. */
    public enum VolMode {
        /** S1/S2: carrier mask derived from algorithm number via ALGO_OUT_MASK table. */
        ALGO,
        /** S3K: carrier operators identified by bit 7 set in the TL byte of the voice data. */
        BIT7
    }

    /** Behavior of PSG envelope command byte 0x80. */
    public enum PsgEnvCmd80 {
        /** S1/S2: hold the envelope at current level (stop advancing). */
        HOLD,
        /** S3K: reset the envelope index to 0 (loop from start). */
        RESET
    }

    /** How note-on is prevented during ties/holds. */
    public enum NoteOnPrevent {
        /** S1/S2: prevented when note is REST (0x80). */
        REST,
        /** S3K: prevented when HOLD flag is set. */
        HOLD
    }

    /** What happens to frequency during rests/delays. */
    public enum DelayFreq {
        /** S1/S2: frequency is reset on rest. */
        RESET,
        /** S3K: frequency persists through rests. */
        KEEP
    }

    /**
     * Whether a track whose note has not expired re-sends its frequency on
     * every driver pass, or only when modulation actually moved it.
     */
    public enum NoteGoingFreqSend {
        /**
         * S1/S2: DoModulation discards its caller's return address on entry
         * ({@code addq.w #4,sp} at s1.sounddriver.asm:483-486, {@code pop de}
         * at s2.sounddriver.asm:986-987), so an inactive modulation, a track at
         * rest, or an unexpired modulation wait returns past the caller and the
         * frequency send is skipped entirely (s1.sounddriver.asm:358-361,
         * s2.sounddriver.asm:832-834).
         */
        MODULATION_ONLY,
        /**
         * S3K: zDoModulation is an ordinary subroutine, so each of its returns
         * lands on the fall-through to zFMSendFreq for FM
         * (skdisasm Sound/Z80 Sound Driver.asm:791-799, :1277-1283) and on the
         * unconditional PSG frequency latch for PSG (:4077-4090). The
         * frequency therefore goes out on every pass of a sounding track,
         * whether or not it changed.
         */
        EVERY_PASS
    }

    /** What a track's note-fill countdown does when it reaches zero. */
    public enum NoteFillTail {
        /**
         * The engine's existing behaviour for S1 and S2. S1's
         * {@code NoteTimeoutUpdate} and S2's {@code zNoteFillUpdate} both set
         * the rest bit, discard the caller's return address and send a note
         * off for either track type (s2.sounddriver.asm:1153-1163). S2 does
         * not currently take that path in the engine, which models its fill
         * as an elapsed comparison rather than the ROM's per-pass countdown;
         * that difference is unverified and deliberately untouched here.
         */
        LEGACY,
        /**
         * S3K splits the tail by track type, and both halves are tail jumps
         * so the rest of the pass is skipped either way.
         * {@code zUpdatePSGTrack}'s {@code jp z, zRestTrack} sets the rest bit
         * and writes nothing (skdisasm Sound/Z80 Sound
         * Driver.asm:4070-4074, :4220-4224), while
         * {@code zUpdateFMorPSGTrack}'s {@code jp z, zKeyOffIfActive} keys the
         * channel off and never rests it (:786-790, :2148-2152).
         */
        S3K_SPLIT
    }

    /**
     * Whether the PSG volume envelope carries commands that put the track at
     * rest.
     */
    public enum PsgEnvRestCmd {
        /**
         * S1/S2 keep the engine's existing generic handling. S2's flutter
         * list defines only {@code 80h} as a terminator and applies every
         * other negative byte as a value (s2.sounddriver.asm:1298-1301), so
         * what the engine does with {@code 81h}, {@code 82h}, {@code 83h} and
         * {@code 84h} on those drivers is unverified and deliberately left
         * alone here.
         */
        NONE,
        /**
         * S3K's {@code zDoVolEnv} dispatches {@code 83h} to
         * {@code zDoVolEnvFullRest} and {@code 81h} to
         * {@code zDoVolEnvRest} (skdisasm Sound/Z80 Sound
         * Driver.asm:4169-4175). Both pop the caller's return address, set
         * {@code PlaybackControl} bit 4 and end the track's pass; neither
         * advances the envelope index and neither silences the channel, which
         * the ROM says outright at :4206-4208. {@code 82h} and {@code 84h}
         * are not commands there at all.
         */
        Z80_81_AND_83
    }

    /**
     * Order in which a PSG track whose note has not expired writes its
     * frequency and its volume.
     */
    public enum PsgNoteGoingOrder {
        /**
         * S1/S2: the volume effects run first and the frequency last -
         * {@code PSGUpdateVolFX}, {@code DoModulation}, {@code PSGUpdateFreq}
         * (s1.sounddriver.asm:1822-1827) and {@code zPSGUpdateVolFX},
         * {@code zDoModulation}, {@code zPSGUpdateFreq}
         * (s2.sounddriver.asm:1134-1138).
         */
        VOLUME_THEN_FREQUENCY,
        /**
         * S3K: {@code zUpdatePSGTrack}'s {@code .skip_fill} latches the
         * frequency to the PSG immediately after {@code zDoModulation}, and
         * only then reads the volume envelope and writes the volume
         * (skdisasm Sound/Z80 Sound Driver.asm:4077-4110).
         */
        FREQUENCY_THEN_VOLUME
    }

    /** Modulation stepping algorithm. */
    public enum ModAlgo {
        /** S1/S2 (MODALGO_68K): pre-check step counter, then decrement. Reload from raw data. */
        MOD_68K,
        /** S3K (MODALGO_Z80): post-decrement with 8-bit wrap, then check. Reload from raw data. */
        MOD_Z80
    }

    /** How an FM channel is prepared when an SFX first takes it from music. */
    public enum FmSfxTakeoverMode {
        /** Legacy engine behavior: clear internal chip state and inject a key-off. */
        FORCE_RESET,
        /** Shipped-driver behavior: let the SFX bytecode perform all visible writes. */
        REGISTER_SEQUENCE
    }

    /** How a PSG channel is prepared when an SFX first takes it from music. */
    public enum PsgSfxTakeoverMode {
        /** Legacy engine behavior: inject a maximum-attenuation latch. */
        FORCE_SILENCE,
        /** Shipped-driver behavior: let the SFX bytecode own visible writes. */
        REGISTER_SEQUENCE,
        /** S1: bytecode owns PSG1/2; PSG3 admission explicitly writes DF, FF. */
        S1_PSG3_SILENCE_PAIR
    }

    /**
     * Visible PSG writes S1's special-SFX loader emits at the tail of
     * {@code Sound_PlaySpecial}.
     */
    public enum SpecialSfxPsg3SilenceMode {
        /** Admitting a special SFX changes no PSG register state. */
        NONE,
        /**
         * S1: {@code Sound_PlaySpecial}'s {@code .doneoverride} tail
         * (docs/s1disasm/s1.sounddriver.asm:1183-1191) writes {@code d4|$1F}
         * and then that value with bit 5 flipped, where {@code d4} still holds
         * the voice control bits of the LAST track its own load loop read
         * (:1141). The intended value was the PSG3 channel byte {@code $C0},
         * which would give the {@code $DF, $FF} silence pair; with an FM-only
         * special SFX such as {@code SndD0 - Waterfall} ({@code cFM4} =
         * {@code $04}) it instead emits {@code $1F, $3F}, two SN76489 DATA
         * bytes that land on whichever register was latched last. This is a
         * shipped-ROM defect under {@code FixBugs = 0} and is modelled as
         * emitted, not as the value the comment intends.
         */
        S1_STALE_VOICE_CONTROL_PAIR
    }

    /** Visible PSG writes emitted while admitting a declared PSG3 SFX track. */
    public enum Psg3SfxAdmissionWriteMode {
        /** Admission changes no PSG register state. */
        NONE,
        /** Silence tone 3 and noise with the PSG writes {@code DF, FF}. */
        SILENCE_TONE_AND_NOISE
    }

    /** When an accepted SFX program takes ownership of its declared channels. */
    public enum SfxChannelOwnershipMode {
        /** Compatibility behavior: the program's first chip write takes ownership. */
        FIRST_WRITE,
        /** Driver-RAM behavior: header admission takes ownership before service. */
        ADMISSION
    }

    /** How an FM channel returns to music after its SFX track stops. */
    public enum FmSfxReleaseMode {
        /** Legacy engine behavior: silence, restore all state, then resend frequency. */
        LEGACY_FULL_RESTORE,
        /** Shipped S1 behavior: the SFX note-off stands; restore voice/pan at rest. */
        ROM_VOICE_RESTORE,
        /** Restore voice/pan without changing the covered music track's rest state. */
        ROM_VOICE_RESTORE_PRESERVE_REST
    }

    /** How a PSG channel returns to music after its SFX track stops. */
    public enum PsgSfxReleaseMode {
        /** Legacy engine behavior: silence, restore volume, then resend frequency. */
        LEGACY_FULL_RESTORE,
        /** Shipped S1 behavior: the SFX note-off stands; restore at rest/noise only. */
        ROM_REST_RESTORE,
        /** S3K: preserve music state and restore only a signed raw noise byte. */
        ROM_NOISE_RESTORE_PRESERVE_REST
    }

    /** How SFX track RAM is walked after header initialization. */
    public enum SfxTrackWalkMode {
        /** Preserve the SFX header entry order. */
        HEADER_ORDER,
        /** Walk the fixed driver RAM slots: DAC/FM channels, then PSG channels. */
        CHANNEL_RAM_ORDER
    }

    /** Voice bank used by an in-stream FM volume change. */
    public enum FmVolumeVoiceBankMode {
        /** Read total levels from the current track's voice bank. */
        TRACK_VOICE_BANK,
        /** Shipped S1 FixBugs=0 path: ordinary SFX read the special-SFX bank. */
        S1_SPECIAL_POINTER_BUG
    }

    /** Exact shipped-driver sequence used to upload a 25-byte FM voice. */
    public enum FmVoiceWriteProfile {
        /** Sonic 1's 68k SetVoice routine. */
        S1_68K,
        /** Sonic 2's Z80 zSetVoice routine. */
        S2_Z80,
        /** Sonic 3 & Knuckles' Z80 zSendFMInstrument routine. */
        S3K_Z80
    }

    // -----------------------------------------------------------------------
    // Default constants shared across all three games (S1, S2, S3K)
    // -----------------------------------------------------------------------

    /** Default tempo modulation base (0x100). Same for S1, S2, and S3K. */
    public static final int DEFAULT_TEMPO_MOD_BASE = 0x100;

    /** Default FM channel order: DAC(0x16), FM1-FM6. Same for S1, S2, and S3K. */
    public static final int[] DEFAULT_FM_CHANNEL_ORDER = { 0x16, 0, 1, 2, 4, 5, 6 };

    /** Default PSG channel order: PSG1(0x80), PSG2(0xA0), PSG3(0xC0). Same for S1, S2, and S3K. */
    public static final int[] DEFAULT_PSG_CHANNEL_ORDER = { 0x80, 0xA0, 0xC0 };

    private final Map<Integer, Integer> speedUpTempos;
    private final int tempoModBase;
    private final int[] fmChannelOrder;
    private final int[] psgChannelOrder;
    private final TempoMode tempoMode;
    private final Map<Integer, Integer> coordFlagParamOverrides;
    private final boolean applyModOnNote;
    private final boolean halveModSteps;
    private final Set<Integer> extraTrkEndFlags;
    private final PalUpdateMode palUpdateMode;
    private final boolean relativePointers; // S1: true (68k PC-relative), S2: false (Z80 absolute)
    private final boolean tempoOnFirstTick; // S1: true (DOTEMPO), S2: false (PlayMusic)
    private final boolean resetTempoOnMusicLoad;
    private final boolean direct68kDriver;
    private final boolean advancePsgEnvelopeOnRest;
    private final boolean writeFmPanOnNote;
    private final boolean dacNoteKeysOffFm6AndRestoresFm3;
    private final boolean enableDacOnSequencerStart;
    private final boolean psgFrequencyHighByteNibbleSwap;
    private final FmSfxTakeoverMode fmSfxTakeoverMode;
    private final PsgSfxTakeoverMode psgSfxTakeoverMode;
    private final Psg3SfxAdmissionWriteMode psg3SfxAdmissionWriteMode;
    private final SpecialSfxPsg3SilenceMode specialSfxPsg3SilenceMode;
    private final SfxChannelOwnershipMode sfxChannelOwnershipMode;
    private final FmSfxReleaseMode fmSfxReleaseMode;
    private final PsgSfxReleaseMode psgSfxReleaseMode;
    private final SfxTrackWalkMode sfxTrackWalkMode;
    private final FmVolumeVoiceBankMode fmVolumeVoiceBankMode;
    private final FmVoiceWriteProfile fmVoiceWriteProfile;

    // --- S3K-specific config fields ---
    private final VolMode volMode;
    private final PsgEnvCmd80 psgEnvCmd80;
    private final NoteOnPrevent noteOnPrevent;
    private final DelayFreq delayFreq;
    private final CoordFlagHandler coordFlagHandler;
    private final ModAlgo modAlgo;
    private final NoteGoingFreqSend noteGoingFreqSend;
    private final PsgNoteGoingOrder psgNoteGoingOrder;
    private final PsgEnvRestCmd psgEnvRestCmd;
    private final boolean stepModulationAtRest;
    private final boolean noteResetAliasesModulationState;
    private final boolean fmNoteGoingReturnsAtRest;
    private final FadeOutHalt fadeOutHalt;
    private final FadeInRestore fadeInRestore;
    private final boolean driverOwnedFadeDelay;
    private final FadeDelayCadence fadeDelayCadence;
    private final boolean tempoWaitPrecedesRequest;
    private final PsgSilenceShape psgSilenceShape;
    private final PsgVolumeTail psgVolumeTail;
    private final boolean sfxWalkPrecedesRequest;
    private final boolean sfxAdmissionKeyOffAndClearsSsgEg;
    private final boolean psgSfxAdmissionSilencesNoise;
    private final boolean trackEndFlagOwnsTheStop;
    private final NoteFillTail noteFillTail;
    private final int fadeOutDelay;
    private final int fadeOutSteps;
    private final int fadeInSteps;
    private final int fadeInDelay;

    /**
     * Private constructor used by the Builder. All fields are set here.
     */
    private SmpsSequencerConfig(Builder b) {
        this.speedUpTempos = Collections.unmodifiableMap(new HashMap<>(b.speedUpTempos));
        this.tempoModBase = b.tempoModBase;
        this.fmChannelOrder = Arrays.copyOf(b.fmChannelOrder, b.fmChannelOrder.length);
        this.psgChannelOrder = Arrays.copyOf(b.psgChannelOrder, b.psgChannelOrder.length);
        this.tempoMode = b.tempoMode;
        this.coordFlagParamOverrides = (b.coordFlagParamOverrides != null)
                ? Collections.unmodifiableMap(new HashMap<>(b.coordFlagParamOverrides))
                : Collections.emptyMap();
        this.applyModOnNote = b.applyModOnNote;
        this.halveModSteps = b.halveModSteps;
        this.extraTrkEndFlags = (b.extraTrkEndFlags != null)
                ? Collections.unmodifiableSet(b.extraTrkEndFlags)
                : Collections.emptySet();
        this.palUpdateMode = b.palUpdateMode;
        this.relativePointers = b.relativePointers;
        this.tempoOnFirstTick = b.tempoOnFirstTick;
        this.resetTempoOnMusicLoad = b.resetTempoOnMusicLoad;
        this.direct68kDriver = b.direct68kDriver;
        this.advancePsgEnvelopeOnRest = b.advancePsgEnvelopeOnRest;
        this.writeFmPanOnNote = b.writeFmPanOnNote;
        this.dacNoteKeysOffFm6AndRestoresFm3 = b.dacNoteKeysOffFm6AndRestoresFm3;
        this.enableDacOnSequencerStart = b.enableDacOnSequencerStart;
        this.psgFrequencyHighByteNibbleSwap = b.psgFrequencyHighByteNibbleSwap;
        this.fmSfxTakeoverMode = b.fmSfxTakeoverMode;
        this.psgSfxTakeoverMode = b.psgSfxTakeoverMode;
        this.psg3SfxAdmissionWriteMode = b.psg3SfxAdmissionWriteMode;
        this.specialSfxPsg3SilenceMode = b.specialSfxPsg3SilenceMode;
        this.sfxChannelOwnershipMode = b.sfxChannelOwnershipMode;
        this.fmSfxReleaseMode = b.fmSfxReleaseMode;
        this.psgSfxReleaseMode = b.psgSfxReleaseMode;
        this.sfxTrackWalkMode = b.sfxTrackWalkMode;
        this.fmVolumeVoiceBankMode = b.fmVolumeVoiceBankMode;
        this.fmVoiceWriteProfile = b.fmVoiceWriteProfile;
        this.volMode = b.volMode;
        this.psgEnvCmd80 = b.psgEnvCmd80;
        this.noteOnPrevent = b.noteOnPrevent;
        this.delayFreq = b.delayFreq;
        this.coordFlagHandler = b.coordFlagHandler;
        this.modAlgo = b.modAlgo;
        this.noteGoingFreqSend = b.noteGoingFreqSend;
        this.psgNoteGoingOrder = b.psgNoteGoingOrder;
        this.psgEnvRestCmd = b.psgEnvRestCmd;
        this.stepModulationAtRest = b.stepModulationAtRest;
        this.noteResetAliasesModulationState = b.noteResetAliasesModulationState;
        this.fmNoteGoingReturnsAtRest = b.fmNoteGoingReturnsAtRest;
        this.fadeOutHalt = b.fadeOutHalt;
        this.fadeInRestore = b.fadeInRestore;
        this.driverOwnedFadeDelay = b.driverOwnedFadeDelay;
        this.fadeDelayCadence = b.fadeDelayCadence;
        this.tempoWaitPrecedesRequest = b.tempoWaitPrecedesRequest;
        this.psgSilenceShape = b.psgSilenceShape;
        this.psgVolumeTail = b.psgVolumeTail;
        this.sfxWalkPrecedesRequest = b.sfxWalkPrecedesRequest;
        this.sfxAdmissionKeyOffAndClearsSsgEg = b.sfxAdmissionKeyOffAndClearsSsgEg;
        this.psgSfxAdmissionSilencesNoise = b.psgSfxAdmissionSilencesNoise;
        this.trackEndFlagOwnsTheStop = b.trackEndFlagOwnsTheStop;
        this.noteFillTail = b.noteFillTail;
        this.fadeOutDelay = b.fadeOutDelay;
        this.fadeOutSteps = b.fadeOutSteps;
        this.fadeInSteps = b.fadeInSteps;
        this.fadeInDelay = b.fadeInDelay;
    }

    public Map<Integer, Integer> getSpeedUpTempos() {
        return speedUpTempos;
    }

    public int getTempoModBase() {
        return tempoModBase;
    }

    public int[] getFmChannelOrder() {
        return Arrays.copyOf(fmChannelOrder, fmChannelOrder.length);
    }

    public int[] getPsgChannelOrder() {
        return Arrays.copyOf(psgChannelOrder, psgChannelOrder.length);
    }

    int fmChannelCount() {
        return fmChannelOrder.length;
    }

    int fmChannelAt(int index) {
        return fmChannelOrder[index];
    }

    int psgChannelCount() {
        return psgChannelOrder.length;
    }

    int psgChannelAt(int index) {
        return psgChannelOrder[index];
    }

    public TempoMode getTempoMode() {
        return tempoMode;
    }

    /**
     * Returns overrides for coordination flag parameter lengths.
     * Keys are flag commands (0xE0-0xFF), values are the param length for that flag.
     * Only flags that differ from the default S2 table need to be present.
     */
    public Map<Integer, Integer> getCoordFlagParamOverrides() {
        return coordFlagParamOverrides;
    }

    /**
     * Whether to apply modulation during note start (playNote).
     * S2 (ModAlgo 68k_a): true. S1 (ModAlgo 68k): false.
     */
    public boolean isApplyModOnNote() {
        return applyModOnNote;
    }

    /**
     * Whether to halve the modulation step count on load.
     * Both the S1 68k and S2 Z80 drivers shift the raw count once.
     */
    public boolean isHalveModSteps() {
        return halveModSteps;
    }

    /**
     * Returns coordination flag commands that should stop the track (TRK_END).
     * S1: includes 0xEE. S2: empty (0xEE is IGNORE/no-op).
     */
    public Set<Integer> getExtraTrkEndFlags() {
        return extraTrkEndFlags;
    }

    public PalUpdateMode getPalUpdateMode() {
        return palUpdateMode;
    }

    /**
     * Whether in-stream pointers (F6 Jump, F7 Loop, F8 Call) use PC-relative addressing.
     * S1 (68k): true — pointer value is signed offset from (ptrAddr + 1).
     * S2 (Z80): false — pointer value is absolute Z80 address, resolved via relocate().
     */
    public boolean isRelativePointers() {
        return relativePointers;
    }

    /** Whether playback follows the direct 68k chip-write/update contract. */
    public boolean isDirect68kDriver() {
        return direct68kDriver;
    }

    /** Whether a newly parsed PSG rest still consumes the first envelope byte. */
    public boolean isAdvancePsgEnvelopeOnRest() {
        return advancePsgEnvelopeOnRest;
    }

    /** Whether FM note preparation repeats the track's current pan register. */
    public boolean isWriteFmPanOnNote() {
        return writeFmPanOnNote;
    }

    /**
     * Whether starting a DAC sample also keys off the shared FM6 channel and
     * restores FM3 to normal mode, as the S3K Z80 driver's DAC track does.
     */
    public boolean isDacNoteKeysOffFm6AndRestoresFm3() {
        return dacNoteKeysOffFm6AndRestoresFm3;
    }

    /**
     * Whether admitting a sequencer also enables the YM2612 DAC (2Bh = 80h).
     * The Z80 drivers do not: their DAC transport owns that register.
     */
    public boolean isEnableDacOnSequencerStart() {
        return enableDacOnSequencerStart;
    }

    /**
     * Whether the PSG frequency's second byte is the S3K driver's nibble swap
     * of {@code (low & 0F0h) | high} rather than a six-bit-masked shift.
     */
    public boolean isPsgFrequencyHighByteNibbleSwap() {
        return psgFrequencyHighByteNibbleSwap;
    }

    public FmSfxTakeoverMode getFmSfxTakeoverMode() {
        return fmSfxTakeoverMode;
    }

    public PsgSfxTakeoverMode getPsgSfxTakeoverMode() {
        return psgSfxTakeoverMode;
    }

    public SpecialSfxPsg3SilenceMode getSpecialSfxPsg3SilenceMode() {
        return specialSfxPsg3SilenceMode;
    }

    public Psg3SfxAdmissionWriteMode getPsg3SfxAdmissionWriteMode() {
        return psg3SfxAdmissionWriteMode;
    }

    public SfxChannelOwnershipMode getSfxChannelOwnershipMode() {
        return sfxChannelOwnershipMode;
    }

    public FmSfxReleaseMode getFmSfxReleaseMode() {
        return fmSfxReleaseMode;
    }

    public PsgSfxReleaseMode getPsgSfxReleaseMode() {
        return psgSfxReleaseMode;
    }

    public SfxTrackWalkMode getSfxTrackWalkMode() {
        return sfxTrackWalkMode;
    }

    public FmVolumeVoiceBankMode getFmVolumeVoiceBankMode() {
        return fmVolumeVoiceBankMode;
    }

    public FmVoiceWriteProfile getFmVoiceWriteProfile() {
        return fmVoiceWriteProfile;
    }

    /**
     * Whether to process tempo on the very first frame.
     * S1 (DOTEMPO): true — first frame goes through processTempoFrame().
     * S2 (PlayMusic): false — first frame calls tick() directly, bypassing tempo.
     */
    public boolean isTempoOnFirstTick() {
        return tempoOnFirstTick;
    }

    public boolean isResetTempoOnMusicLoad() {
        return resetTempoOnMusicLoad;
    }

    /** Volume mode: ALGO (S1/S2) or BIT7 (S3K). */
    public VolMode getVolMode() {
        return volMode;
    }

    /** PSG envelope 0x80 command behavior: HOLD (S1/S2) or RESET (S3K). */
    public PsgEnvCmd80 getPsgEnvCmd80() {
        return psgEnvCmd80;
    }

    /** Note-on prevention mode: REST (S1/S2) or HOLD (S3K). */
    public NoteOnPrevent getNoteOnPrevent() {
        return noteOnPrevent;
    }

    /** Delay frequency behavior: RESET (S1/S2) or KEEP (S3K). */
    public DelayFreq getDelayFreq() {
        return delayFreq;
    }

    /** Game-specific coordination flag handler, or null for default S2 handling. */
    public CoordFlagHandler getCoordFlagHandler() {
        return coordFlagHandler;
    }

    /** Modulation stepping algorithm: MOD_68K (S1/S2) or MOD_Z80 (S3K). */
    public ModAlgo getModAlgo() {
        return modAlgo;
    }

    /**
     * Note-going frequency send: MODULATION_ONLY (S1/S2) or EVERY_PASS (S3K).
     */
    public NoteGoingFreqSend getNoteGoingFreqSend() {
        return noteGoingFreqSend;
    }

    /**
     * PSG note-going write order: VOLUME_THEN_FREQUENCY (S1/S2) or
     * FREQUENCY_THEN_VOLUME (S3K).
     */
    public PsgNoteGoingOrder getPsgNoteGoingOrder() {
        return psgNoteGoingOrder;
    }

    /** PSG envelope rest commands: NONE (S1/S2) or Z80_81_AND_83 (S3K). */
    public PsgEnvRestCmd getPsgEnvRestCmd() {
        return psgEnvRestCmd;
    }

    /**
     * Whether a track at rest still advances its modulation phase. Only S2
     * checks the rest bit on entry to its modulation routine
     * ({@code bit 1,(ix+zTrack.PlaybackControl) / ret nz},
     * s2.sounddriver.asm:988-990). S1's {@code DoModulation}
     * (s1.sounddriver.asm:483-490) and S3K's {@code zDoModulation}
     * (skdisasm Sound/Z80 Sound Driver.asm:1277-1283) test only whether
     * modulation is active, so both keep stepping while the track rests.
     */
    public boolean isStepModulationAtRest() {
        return stepModulationAtRest;
    }

    /**
     * Whether reading a stream unit zeroes two bytes that the S3K track
     * layout shares between the modulation envelope and normal modulation.
     * {@code zFinishTrackUpdate} clears {@code ModEnvIndex} and
     * {@code ModEnvSens} whenever the do-not-attack bit is clear
     * (skdisasm Sound/Z80 Sound Driver.asm:1055-1069), and those are the same
     * bytes as {@code ModulationSpeed} at offset 25h and
     * {@code ModulationValLow} at 22h (:76-92). So on a track using normal
     * modulation, every note read zeroes the speed counter and the low byte
     * of the accumulator. It is an aliasing quirk of the shipped driver, not
     * an intended effect, and S1 and S2 have different track layouts.
     */
    public boolean isNoteResetAliasesModulationState() {
        return noteResetAliasesModulationState;
    }

    /**
     * Whether a resting FM track's continuing-note pass returns immediately.
     * {@code zUpdateFMorPSGTrack}'s {@code .note_going} opens with
     * {@code bit 4,(ix+zTrack.PlaybackControl) / ret nz}
     * (skdisasm Sound/Z80 Sound Driver.asm:781-783), so a resting S3K FM
     * track runs no volume envelope, no note fill, no frequency update and no
     * modulation at all. {@code zUpdatePSGTrack} has no such test at its
     * matching entry (:4066-4076), which is why a resting PSG track keeps
     * sending its frequency and stepping its modulation.
     */
    public boolean isFmNoteGoingReturnsAtRest() {
        return fmNoteGoingReturnsAtRest;
    }

    /**
     * Whether {@code TempoWait} runs before the driver reads its request
     * mailbox. S3K's is in {@code zUpdateEverything}, ahead of
     * {@code zUpdateMusic} and its {@code zFillSoundQueue}
     * (skdisasm Sound/Z80 Sound Driver.asm:653-701, :2607-2621), so the
     * service that loads a song has already accumulated with the previous
     * tempo and {@code zBGMLoad}'s seed of the accumulator (:1829-1831) is
     * the value that service ends on. The newly loaded song's own first
     * accumulation is the next service; its track walk still runs in the load
     * service. S1 and S2 run their tempo step inside the music update, after
     * the queue is filled, and do accumulate on the load service.
     */
    public boolean isTempoWaitPrecedesRequest() {
        return tempoWaitPrecedesRequest;
    }

    /** How the fade's inter-step delay counter is tested. */
    public enum FadeDelayCadence {
        /**
         * S1/S2: {@code zUpdateFadeout} reads the delay, steps when it is
         * already zero, and otherwise decrements and returns
         * (s2.sounddriver.asm:1686-1697). A delay of 3 therefore steps on the
         * fourth service.
         */
        TEST_THEN_DECREMENT,
        /**
         * S3K: {@code zDoMusicFadeOut} decrements first and steps when the
         * result is zero (skdisasm Sound/Z80 Sound Driver.asm:2337-2343). A
         * delay of 6 therefore steps on the sixth service.
         */
        DECREMENT_THEN_TEST
    }

    /** Fade delay cadence: TEST_THEN_DECREMENT (S1/S2) or DECREMENT_THEN_TEST (S3K). */
    public FadeDelayCadence getFadeDelayCadence() {
        return fadeDelayCadence;
    }

    /**
     * Whether the SFX track walk runs before the driver reads its request
     * mailbox. {@code zUpdateEverything} calls {@code zUpdateSFXTracks} and
     * only then falls into {@code zUpdateMusic}, whose {@code zFillSoundQueue}
     * consumes the queue (skdisasm Sound/Z80 Sound Driver.asm:650-701). So an
     * SFX admitted during a service has already missed that service's walk,
     * and its first update belongs to the next one.
     */
    public boolean isSfxWalkPrecedesRequest() {
        return sfxWalkPrecedesRequest;
    }

    /**
     * Whether an admitted SFX keys each of its channels off and clears their
     * SSG-EG operators. {@code zSFXTrackInitLoop} calls
     * {@code zKeyOffIfActive} and then {@code zFMClearSSGEGOps} for every SFX
     * track it initialises (skdisasm Sound/Z80 Sound Driver.asm:2092-2103,
     * :2528-2536), writing 90h, 94h, 98h and 9Ch of the track's channel with
     * zero. On the shipped {@code fix_sndbugs = 0} branch the clear is also
     * called for PSG tracks, but {@code zWriteFMIorII} returns on bit 7 of
     * {@code VoiceControl} before writing anything (:2549-2551), so no PSG
     * track's SSG-EG clear puts a byte on the bus; the fixed branch merely
     * skips the call.
     */
    public boolean isSfxAdmissionKeyOffAndClearsSsgEg() {
        return sfxAdmissionKeyOffAndClearsSsgEg;
    }

    /** Whether each declared PSG SFX header unconditionally silences noise at admission. */
    public boolean isPsgSfxAdmissionSilencesNoise() {
        return psgSfxAdmissionSilencesNoise;
    }

    /**
     * Whether the track-end coordination flag is the only thing that stops
     * the note. S3K's {@code cfStopTrack} calls {@code zKeyOffIfActive}
     * exactly once as it clears the playing bit (skdisasm Sound/Z80 Sound
     * Driver.asm:3040-3046), so a second stop after the stream read puts a
     * duplicate key-off on the bus. S1 and S2 keep the engine's blanket stop:
     * their handlers do not all stop the note themselves, and removing it
     * takes the S2 driver-state oracle from MATCH to a missing write at tick
     * 207, so their track-end paths are unaudited rather than known-equal.
     */
    public boolean isTrackEndFlagOwnsTheStop() {
        return trackEndFlagOwnsTheStop;
    }

    /** How a single PSG track's silence is written. */
    public enum PsgSilenceShape {
        /** The engine's existing S1/S2 behaviour: one byte for the channel
         * the track is sounding on, which for a noise track is the noise
         * channel. Neither driver has a per-track PSG silence routine to cite
         * against; both silence all four channels at once
         * (s2.sounddriver.asm:1412-1418), so this is unaudited rather than
         * established. */
        SOUNDING_CHANNEL_ONLY,
        /**
         * S3K's {@code zSilencePSGChannel} writes {@code 1Fh + VoiceControl}
         * first, which is the track's own tone channel, and only then adds
         * {@code 0FFh} for the noise channel, and only when
         * {@code PlaybackControl} bit 0 is set (skdisasm Sound/Z80 Sound
         * Driver.asm:4226-4245). Under {@code fix_sndbugs = 0} that bit is
         * usually clear when the routine runs, which the listing itself calls
         * out, so most calls emit the tone byte alone.
         */
        TONE_THEN_NOISE
    }

    /** PSG silence shape: SOUNDING_CHANNEL_ONLY (S1/S2) or TONE_THEN_NOISE (S3K). */
    public PsgSilenceShape getPsgSilenceShape() {
        return psgSilenceShape;
    }

    /** When a sounding PSG track resends its attenuation byte. */
    public enum PsgVolumeTail {
        /**
         * The engine's existing S1/S2 behaviour: the attenuation goes out when
         * a note starts or an envelope step changes it, and not otherwise.
         * Both 68K-era drivers reach their volume write through
         * {@code PSGDoVolFX} off the note path, and their oracles are pinned to
         * this shape, so it is left alone rather than re-derived here.
         */
        NOTE_AND_ENVELOPE_ONLY,
        /**
         * S3K: {@code zUpdatePSGTrack}'s {@code .note_going} path sends the
         * frequency pair and then falls straight into the volume tail on every
         * pass of a sounding note, gated only on {@code PlaybackControl} bit 2
         * (SFX overriding) and bit 4 (track at rest)
         * (skdisasm Sound/Z80 Sound Driver.asm:4079-4135). This is what
         * carries a mid-note volume change, such as the
         * {@code smpsPSGAlterVol} ramp that gives {@code sfx_Collapse} its
         * decaying tail, out to the chip.
         */
        EVERY_NOTE_GOING_PASS
    }

    /** PSG volume tail: NOTE_AND_ENVELOPE_ONLY (S1/S2) or EVERY_NOTE_GOING_PASS (S3K). */
    public PsgVolumeTail getPsgVolumeTail() {
        return psgVolumeTail;
    }

    /** Which tracks a music fade-out request halts outright. */
    public enum FadeOutHalt {
        /**
         * S1/S2: {@code zFadeOutMusic} zeroes only the DAC track's playback
         * control, with the comment "can't fade it"
         * (s2.sounddriver.asm:1668-1681).
         */
        DAC_ONLY,
        /**
         * S3K: {@code zFadeOutMusic} falls through into {@code zHaltDACPSG},
         * which zeroes FM6/DAC, PSG3, PSG1 and PSG2 and then jumps to
         * {@code zPSGSilenceAll} (skdisasm Sound/Z80 Sound
         * Driver.asm:2307-2325). The halt itself writes nothing to the chip;
         * only {@code zPSGSilenceAll} does.
         */
        DAC_AND_PSG
    }

    /** Fade-out halt scope: DAC_ONLY (S1/S2) or DAC_AND_PSG (S3K). */
    public FadeOutHalt getFadeOutHalt() {
        return fadeOutHalt;
    }

    /**
     * What the restore-to-previous fade does to each music track it brings
     * back. The two shapes silence the resumed song by different bits, and
     * the drivers do not agree on which bit means what.
     */
    public enum FadeInRestore {
        /**
         * S1/S2: {@code cfFadeInToPrevious} sets {@code PlaybackControl} bit 1,
         * "track at rest" in these drivers, on every playing FM and PSG track
         * and calls {@code PSGNoteOff} on the PSG ones
         * (s2.sounddriver.asm:3107, :3131-3132; s1.sounddriver.asm:2193,
         * :2211-2212). The FM tracks get no key-off; their voice is re-sent
         * instead. Each track leaves rest when it reads its own next note.
         */
        REST_TRACKS,
        /**
         * S3K: {@code zFadeInToPrevious} ORs {@code 84h} over every track and
         * then clears bit 2 again on the FM ones
         * (skdisasm Sound/Z80 Sound Driver.asm:2761-2770). In this driver's
         * layout bit 2 is "SFX is overriding this track" and bit 4 is "track
         * is resting" (Driver.asm:25, :27, and {@code zRestTrack} at :4220-4223
         * sets bit 4 then tests bit 2). {@code 84h} is therefore bits 7 and 2,
         * playing and overriding -- the routine's own inline comment calling
         * it "playing and resting" is a mislabel. So the PSG tracks are left
         * marked overridden, which is what silences them through the fade,
         * the FM tracks are released, re-voiced and attenuated by 40h
         * (:2767-2770), and no track is rested. The PSG tracks get no
         * attenuation at all.
         */
        OVERRIDE_PSG
    }

    /**
     * Whether the fade delay pair lives on the driver rather than the song.
     *
     * <p>S3K keeps {@code zFadeDelay} and {@code zFadeDelayTimeout} in the
     * driver's own variable region, and both {@code zFadeOutMusic} and
     * {@code zFadeInToPrevious} write them whether or not a song is loaded
     * (skdisasm Sound/Z80 Sound Driver.asm:2306-2312, :2784-2789). S1 and S2
     * instead keep a single delay byte per direction and reload it from an
     * immediate rather than a stored timeout (s1.sounddriver.asm:1363,
     * :1381; s2.sounddriver.asm:2425-2429), so they keep the song-owned
     * shape.
     */
    public boolean isDriverOwnedFadeDelay() {
        return driverOwnedFadeDelay;
    }

    /** Restore-fade track handling: REST_TRACKS (S1/S2) or OVERRIDE_PSG (S3K). */
    public FadeInRestore getFadeInRestore() {
        return fadeInRestore;
    }

    /** Note-fill expiry tail: LEGACY (S1/S2) or S3K_SPLIT (S3K). */
    public NoteFillTail getNoteFillTail() {
        return noteFillTail;
    }

    /** Fade-out inter-step delay in frames. S1/S2: 3, S3K: 6. */
    public int getFadeOutDelay() {
        return fadeOutDelay;
    }

    /** Fade-out total step count. S1/S2: 0x28, S3K: 0x28. */
    public int getFadeOutSteps() {
        return fadeOutSteps;
    }

    /** Fade-in total step count. S1/S2: 0x28, S3K: 0x40. */
    public int getFadeInSteps() {
        return fadeInSteps;
    }

    /** Fade-in inter-step delay in frames. S1/S2: 2, S3K: 2. */
    public int getFadeInDelay() {
        return fadeInDelay;
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    /**
     * Builder for SmpsSequencerConfig with S2-compatible defaults.
     * Use this for S3K and other configs that need the new fields.
     */
    public static final class Builder {
        // Required (defaults reference shared constants)
        private Map<Integer, Integer> speedUpTempos = Collections.emptyMap();
        private int tempoModBase = DEFAULT_TEMPO_MOD_BASE;
        private int[] fmChannelOrder = DEFAULT_FM_CHANNEL_ORDER;
        private int[] psgChannelOrder = DEFAULT_PSG_CHANNEL_ORDER;

        // S2-compatible defaults
        private TempoMode tempoMode = TempoMode.OVERFLOW2;
        private Map<Integer, Integer> coordFlagParamOverrides = null;
        private boolean applyModOnNote = true;
        private boolean halveModSteps = true;
        private Set<Integer> extraTrkEndFlags = null;
        private PalUpdateMode palUpdateMode = PalUpdateMode.NONE;
        private boolean relativePointers = false;
        private boolean tempoOnFirstTick = false;
        private boolean resetTempoOnMusicLoad;
        private boolean direct68kDriver = false;
        private boolean advancePsgEnvelopeOnRest = true;
        private boolean writeFmPanOnNote = false;
        private boolean dacNoteKeysOffFm6AndRestoresFm3 = false;
        private boolean enableDacOnSequencerStart = true;
        private boolean psgFrequencyHighByteNibbleSwap = false;
        private FmSfxTakeoverMode fmSfxTakeoverMode = FmSfxTakeoverMode.FORCE_RESET;
        private PsgSfxTakeoverMode psgSfxTakeoverMode = PsgSfxTakeoverMode.FORCE_SILENCE;
        private Psg3SfxAdmissionWriteMode psg3SfxAdmissionWriteMode =
                Psg3SfxAdmissionWriteMode.NONE;
        private SpecialSfxPsg3SilenceMode specialSfxPsg3SilenceMode =
                SpecialSfxPsg3SilenceMode.NONE;
        private SfxChannelOwnershipMode sfxChannelOwnershipMode =
                SfxChannelOwnershipMode.FIRST_WRITE;
        private FmSfxReleaseMode fmSfxReleaseMode = FmSfxReleaseMode.LEGACY_FULL_RESTORE;
        private PsgSfxReleaseMode psgSfxReleaseMode = PsgSfxReleaseMode.LEGACY_FULL_RESTORE;
        private SfxTrackWalkMode sfxTrackWalkMode = SfxTrackWalkMode.HEADER_ORDER;
        private FmVolumeVoiceBankMode fmVolumeVoiceBankMode =
                FmVolumeVoiceBankMode.TRACK_VOICE_BANK;
        private FmVoiceWriteProfile fmVoiceWriteProfile = FmVoiceWriteProfile.S2_Z80;

        // S3K-specific defaults (S2 compatible)
        private VolMode volMode = VolMode.ALGO;
        private PsgEnvCmd80 psgEnvCmd80 = PsgEnvCmd80.HOLD;
        private NoteOnPrevent noteOnPrevent = NoteOnPrevent.REST;
        private DelayFreq delayFreq = DelayFreq.RESET;
        private CoordFlagHandler coordFlagHandler = null;
        private ModAlgo modAlgo = ModAlgo.MOD_68K;
        private NoteGoingFreqSend noteGoingFreqSend = NoteGoingFreqSend.MODULATION_ONLY;
        private PsgNoteGoingOrder psgNoteGoingOrder = PsgNoteGoingOrder.VOLUME_THEN_FREQUENCY;
        private PsgEnvRestCmd psgEnvRestCmd = PsgEnvRestCmd.NONE;
        private boolean stepModulationAtRest = false;
        private boolean noteResetAliasesModulationState = false;
        private boolean fmNoteGoingReturnsAtRest = false;
        private FadeOutHalt fadeOutHalt = FadeOutHalt.DAC_ONLY;
        private FadeInRestore fadeInRestore = FadeInRestore.REST_TRACKS;
        private boolean driverOwnedFadeDelay = false;
        private FadeDelayCadence fadeDelayCadence = FadeDelayCadence.TEST_THEN_DECREMENT;
        private boolean tempoWaitPrecedesRequest = false;
        private PsgSilenceShape psgSilenceShape = PsgSilenceShape.SOUNDING_CHANNEL_ONLY;
        private PsgVolumeTail psgVolumeTail = PsgVolumeTail.NOTE_AND_ENVELOPE_ONLY;
        private boolean sfxWalkPrecedesRequest = false;
        private boolean sfxAdmissionKeyOffAndClearsSsgEg = false;
        private boolean psgSfxAdmissionSilencesNoise = false;
        private boolean trackEndFlagOwnsTheStop = false;
        private NoteFillTail noteFillTail = NoteFillTail.LEGACY;
        private int fadeOutDelay = 3;
        private int fadeOutSteps = 0x28;
        private int fadeInSteps = 0x28;
        private int fadeInDelay = 2;

        public Builder speedUpTempos(Map<Integer, Integer> val) { speedUpTempos = val; return this; }
        public Builder tempoModBase(int val) { tempoModBase = val; return this; }
        public Builder fmChannelOrder(int[] val) { fmChannelOrder = val; return this; }
        public Builder psgChannelOrder(int[] val) { psgChannelOrder = val; return this; }
        public Builder tempoMode(TempoMode val) { tempoMode = val; return this; }
        public Builder coordFlagParamOverrides(Map<Integer, Integer> val) { coordFlagParamOverrides = val; return this; }
        public Builder applyModOnNote(boolean val) { applyModOnNote = val; return this; }
        public Builder halveModSteps(boolean val) { halveModSteps = val; return this; }
        public Builder extraTrkEndFlags(Set<Integer> val) { extraTrkEndFlags = val; return this; }
        public Builder palUpdateMode(PalUpdateMode val) { palUpdateMode = val; return this; }
        public Builder relativePointers(boolean val) { relativePointers = val; return this; }
        public Builder tempoOnFirstTick(boolean val) { tempoOnFirstTick = val; return this; }
        public Builder resetTempoOnMusicLoad(boolean val) { resetTempoOnMusicLoad = val; return this; }
        public Builder direct68kDriver(boolean val) { direct68kDriver = val; return this; }
        public Builder advancePsgEnvelopeOnRest(boolean val) { advancePsgEnvelopeOnRest = val; return this; }
        public Builder writeFmPanOnNote(boolean val) { writeFmPanOnNote = val; return this; }
        public Builder dacNoteKeysOffFm6AndRestoresFm3(boolean val) { dacNoteKeysOffFm6AndRestoresFm3 = val; return this; }
        public Builder enableDacOnSequencerStart(boolean val) { enableDacOnSequencerStart = val; return this; }
        public Builder psgFrequencyHighByteNibbleSwap(boolean val) { psgFrequencyHighByteNibbleSwap = val; return this; }
        public Builder fmSfxTakeoverMode(FmSfxTakeoverMode val) { fmSfxTakeoverMode = val; return this; }
        public Builder psgSfxTakeoverMode(PsgSfxTakeoverMode val) { psgSfxTakeoverMode = val; return this; }
        public Builder psg3SfxAdmissionWriteMode(Psg3SfxAdmissionWriteMode val) { psg3SfxAdmissionWriteMode = val; return this; }
        public Builder specialSfxPsg3SilenceMode(SpecialSfxPsg3SilenceMode val) { specialSfxPsg3SilenceMode = val; return this; }
        public Builder sfxChannelOwnershipMode(SfxChannelOwnershipMode val) { sfxChannelOwnershipMode = val; return this; }
        public Builder fmSfxReleaseMode(FmSfxReleaseMode val) { fmSfxReleaseMode = val; return this; }
        public Builder psgSfxReleaseMode(PsgSfxReleaseMode val) { psgSfxReleaseMode = val; return this; }
        public Builder sfxTrackWalkMode(SfxTrackWalkMode val) { sfxTrackWalkMode = val; return this; }
        public Builder fmVolumeVoiceBankMode(FmVolumeVoiceBankMode val) { fmVolumeVoiceBankMode = val; return this; }
        public Builder fmVoiceWriteProfile(FmVoiceWriteProfile val) { fmVoiceWriteProfile = val; return this; }
        public Builder volMode(VolMode val) { volMode = val; return this; }
        public Builder psgEnvCmd80(PsgEnvCmd80 val) { psgEnvCmd80 = val; return this; }
        public Builder noteOnPrevent(NoteOnPrevent val) { noteOnPrevent = val; return this; }
        public Builder delayFreq(DelayFreq val) { delayFreq = val; return this; }
        public Builder coordFlagHandler(CoordFlagHandler val) { coordFlagHandler = val; return this; }
        public Builder modAlgo(ModAlgo val) { modAlgo = val; return this; }
        public Builder noteGoingFreqSend(NoteGoingFreqSend val) { noteGoingFreqSend = val; return this; }
        public Builder psgNoteGoingOrder(PsgNoteGoingOrder val) { psgNoteGoingOrder = val; return this; }
        public Builder psgEnvRestCmd(PsgEnvRestCmd val) { psgEnvRestCmd = val; return this; }
        public Builder stepModulationAtRest(boolean val) { stepModulationAtRest = val; return this; }
        public Builder noteResetAliasesModulationState(boolean val) { noteResetAliasesModulationState = val; return this; }
        public Builder fmNoteGoingReturnsAtRest(boolean val) { fmNoteGoingReturnsAtRest = val; return this; }
        public Builder fadeOutHalt(FadeOutHalt val) { fadeOutHalt = val; return this; }
        public Builder fadeInRestore(FadeInRestore val) { fadeInRestore = val; return this; }
        public Builder driverOwnedFadeDelay(boolean val) { driverOwnedFadeDelay = val; return this; }
        public Builder fadeDelayCadence(FadeDelayCadence val) { fadeDelayCadence = val; return this; }
        public Builder tempoWaitPrecedesRequest(boolean val) { tempoWaitPrecedesRequest = val; return this; }
        public Builder psgSilenceShape(PsgSilenceShape val) { psgSilenceShape = val; return this; }
        public Builder psgVolumeTail(PsgVolumeTail val) { psgVolumeTail = val; return this; }
        public Builder sfxWalkPrecedesRequest(boolean val) { sfxWalkPrecedesRequest = val; return this; }
        public Builder sfxAdmissionKeyOffAndClearsSsgEg(boolean val) { sfxAdmissionKeyOffAndClearsSsgEg = val; return this; }
        public Builder psgSfxAdmissionSilencesNoise(boolean val) { psgSfxAdmissionSilencesNoise = val; return this; }
        public Builder trackEndFlagOwnsTheStop(boolean val) { trackEndFlagOwnsTheStop = val; return this; }
        public Builder noteFillTail(NoteFillTail val) { noteFillTail = val; return this; }
        public Builder fadeOutDelay(int val) { fadeOutDelay = val; return this; }
        public Builder fadeOutSteps(int val) { fadeOutSteps = val; return this; }
        public Builder fadeInSteps(int val) { fadeInSteps = val; return this; }
        public Builder fadeInDelay(int val) { fadeInDelay = val; return this; }

        public SmpsSequencerConfig build() {
            Objects.requireNonNull(speedUpTempos, "speedUpTempos");
            Objects.requireNonNull(fmChannelOrder, "fmChannelOrder");
            Objects.requireNonNull(psgChannelOrder, "psgChannelOrder");
            Objects.requireNonNull(tempoMode, "tempoMode");
            Objects.requireNonNull(palUpdateMode, "palUpdateMode");
            Objects.requireNonNull(fmSfxTakeoverMode, "fmSfxTakeoverMode");
            Objects.requireNonNull(psgSfxTakeoverMode, "psgSfxTakeoverMode");
            Objects.requireNonNull(psg3SfxAdmissionWriteMode, "psg3SfxAdmissionWriteMode");
            Objects.requireNonNull(sfxChannelOwnershipMode, "sfxChannelOwnershipMode");
            Objects.requireNonNull(fmSfxReleaseMode, "fmSfxReleaseMode");
            Objects.requireNonNull(psgSfxReleaseMode, "psgSfxReleaseMode");
            Objects.requireNonNull(sfxTrackWalkMode, "sfxTrackWalkMode");
            Objects.requireNonNull(fmVolumeVoiceBankMode, "fmVolumeVoiceBankMode");
            Objects.requireNonNull(fmVoiceWriteProfile, "fmVoiceWriteProfile");
            return new SmpsSequencerConfig(this);
        }
    }
}
