package com.openggf.audio.smps;

import com.openggf.audio.AudioManager;
import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.game.GameServices;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

public class SmpsSequencer implements CoordFlagContext {
    private static final Logger LOGGER = Logger.getLogger(SmpsSequencer.class.getName());
    private static final byte[] ZERO_FM_VOICE = new byte[25];
    private static final SmpsLogicalWriteTarget DETACHED_WRITE_TARGET =
            new SmpsLogicalWriteTarget() {
                @Override public void writeFm(Object source, int port,
                        int register, int value) { }
                @Override public void writePsg(Object source, int value) { }
                @Override public void setInstrument(Object source, int channel,
                        byte[] voice) { }
                @Override public void playDac(Object source, int note) { }
                @Override public void stopDac(Object source) { }
                @Override public void setDacData(DacData data) { }
                @Override public void setFmMute(int channel, boolean mute) { }
                @Override public void setPsgMute(int channel, boolean mute) { }
                @Override public void setDacInterpolate(boolean interpolate) { }
                @Override public void silenceAll() { }
                @Override public void selectDac(
                        SmpsSourceDescriptor source, DacData data) { }
            };
    private final AbstractSmpsData smpsData;
    private final MusicRestoreSink audioManager;
    private AbstractSmpsData fallbackVoiceData;
    private SmpsProgramView fallbackVoiceView;
    private SmpsSourceDescriptor sourceDescriptor;
    private SourceDescriptorTrust sourceDescriptorTrust;
    private final SmpsProgramView programView;
    private final SmpsLogicalWriteTarget synth;
    private final SmpsSequencerHost host;
    private final SmpsSequencerConfig config;
    private final DacData dacData;
    private final int tempoModBase;
    private final List<Track> tracks = new ArrayList<>();
    /** Immutable ROM-header-order projection into {@link #tracks}. */
    private int[] sfxHeaderOrderIndices = new int[0];

    public enum Region {
        NTSC(60.0), PAL(50.0);

        public final double frameRate;

        Region(double frameRate) {
            this.frameRate = frameRate;
        }
    }

    public enum SourceDescriptorTrust {
        LEGACY_RECOMPUTE,
        PRECOMPUTED_IMMUTABLE
    }

    private Region region = Region.NTSC;
    private boolean speedShoes = false;
    private boolean sfxMode = false;
    private int normalTempo;
    private int commData = 0; // Communication byte (E2)
    private boolean fm6DacOff = false;
    private int maxTicks = Integer.MAX_VALUE;
    private float pitch = 1.0f;
    private int sfxPriority = 0x70; // Default SFX priority (Z80 driver uses 0x70 as common)
    private boolean specialSfx = false; // Driver-specific "special SFX" class (e.g. S1 0xD0+)
    private boolean isSfx = false; // Cached SFX status for performance (set by SmpsDriver.addSequencer)
    private int psgLatchChannel = -1; // Cached PSG latch channel for performance (set by SmpsDriver.writePsg)
    private int speedMultiplier = 1; // S3K: zTempoSpeedup value (0/1=off, 8=speed shoes)
    private int speedupTimeout = 0;  // S3K: zSpeedupTimeout countdown for double-update

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public void setSampleRate(double sampleRate) {
        if (sampleRate > 0.0) {
            this.sampleRate = sampleRate;
            this.samplesPerFrame = sampleRate / region.frameRate;
        }
    }

    public void setSfxPriority(int priority) {
        this.sfxPriority = priority;
    }

    public int getSfxPriority() {
        return sfxPriority;
    }

    public void setSpecialSfx(boolean specialSfx) {
        this.specialSfx = specialSfx;
    }

    public boolean isSpecialSfx() {
        return specialSfx;
    }

    /**
     * Mark this sequencer as SFX. Called by SmpsDriver when adding the sequencer.
     * This cached flag eliminates HashSet lookups in the hot path.
     */
    public void setIsSfx(boolean isSfx) {
        this.isSfx = isSfx;
    }

    /**
     * Returns true if this sequencer is playing SFX (not music).
     * Uses a cached field for O(1) lookup instead of HashSet.contains().
     */
    public boolean isSfx() {
        return isSfx;
    }

    /**
     * Set the cached PSG latch channel. Called by SmpsDriver.writePsg().
     * This eliminates HashMap lookups in the hot path.
     */
    public void setPsgLatchChannel(int channel) {
        this.psgLatchChannel = channel;
    }

    /**
     * Get the cached PSG latch channel.
     * @return the latch channel (0-3), or -1 if not latched
     */
    public int getPsgLatchChannel() {
        return psgLatchChannel;
    }

    /**
     * Set a callback to be invoked when a fade-in completes.
     * Used by JOALAudioBackend to clear sfxBlocked flag after override music restoration.
     */
    public void setOnFadeComplete(Runnable callback) {
        this.onFadeComplete = callback;
    }

    public boolean hasFadeCompleteCallback() {
        return onFadeComplete != null;
    }

    /**
     * Identity-preserving state used only to roll back a failed live command.
     * Rewind snapshots deliberately remain callback-free.
     */
    public static final class LiveCommandMutationToken {
        private final SmpsSequencer owner;
        /** The live {@link Track} objects at capture, restored in place on rollback. */
        private Track[] identities = new Track[10];
        /** Pooled storage tracks holding each identity's captured state. */
        private Track[] storage = new Track[10];
        private int trackCount;
        private boolean inUse;
        private Region region;
        private boolean speedShoes;
        private boolean sfxMode;
        private int normalTempo;
        private int commData;
        private boolean fm6DacOff;
        private int maxTicks;
        private float pitch;
        private int sfxPriority;
        private boolean specialSfx;
        private boolean isSfx;
        private int psgLatchChannel;
        private int speedMultiplier;
        private int speedupTimeout;
        private int fadeSteps;
        private int fadeDelayInit;
        private int fadeDelayCounter;
        private int fadeAddFm;
        private int fadeAddPsg;
        private boolean fadeActive;
        private boolean fadeOut;
        private double sampleRate;
        private double samplesPerFrame;
        private double sampleCounter;
        private int tempoWeight;
        private int tempoAccumulator;
        private int dividingTiming;
        private boolean primed;
        private AbstractSmpsData fallbackVoiceData;
        private SmpsSourceDescriptor sourceDescriptor;
        private SourceDescriptorTrust sourceDescriptorTrust;
        private Runnable onFadeComplete;

        private LiveCommandMutationToken(SmpsSequencer owner) {
            this.owner = owner;
        }

        private void ensureCapacity(int count) {
            if (identities.length < count) {
                int grown = Math.max(count, identities.length * 2);
                identities = Arrays.copyOf(identities, grown);
                storage = Arrays.copyOf(storage, grown);
            }
        }
    }

    /**
     * Free tokens for {@link #captureLiveCommandMutation()}. A token returns
     * here from {@link #rollbackLiveCommandMutation} or
     * {@link #releaseLiveCommandMutation}; one that is dropped without either
     * is simply not reused, so pooling never affects correctness, only
     * allocation. Callers capture one token per outstanding mutation, so a
     * token is never shared between two live mutations.
     */
    private final ArrayDeque<LiveCommandMutationToken> liveTokenPool = new ArrayDeque<>();

    /**
     * Captures the state {@link #rollbackLiveCommandMutation} restores. The
     * token and its per-track storage are pooled and refilled in place: the
     * session captures one per frame, and a fresh immutable snapshot per
     * capture was the largest steady-state garbage source on the audio path.
     */
    public LiveCommandMutationToken captureLiveCommandMutation() {
        LiveCommandMutationToken token = liveTokenPool.pollFirst();
        if (token == null) {
            token = new LiveCommandMutationToken(this);
        }
        token.inUse = true;
        token.region = region;
        token.speedShoes = speedShoes;
        token.sfxMode = sfxMode;
        token.normalTempo = normalTempo;
        token.commData = commData;
        token.fm6DacOff = fm6DacOff;
        token.maxTicks = maxTicks;
        token.pitch = pitch;
        token.sfxPriority = sfxPriority;
        token.specialSfx = specialSfx;
        token.isSfx = isSfx;
        token.psgLatchChannel = psgLatchChannel;
        token.speedMultiplier = speedMultiplier;
        token.speedupTimeout = speedupTimeout;
        token.fadeSteps = fadeState.steps;
        token.fadeDelayInit = fadeState.delayInit;
        token.fadeDelayCounter = fadeState.delayCounter;
        token.fadeAddFm = fadeState.addFm;
        token.fadeAddPsg = fadeState.addPsg;
        token.fadeActive = fadeState.active;
        token.fadeOut = fadeState.fadeOut;
        token.sampleRate = sampleRate;
        token.samplesPerFrame = samplesPerFrame;
        token.sampleCounter = sampleCounter;
        token.tempoWeight = tempoWeight;
        token.tempoAccumulator = tempoAccumulator;
        token.dividingTiming = dividingTiming;
        token.primed = primed;
        token.fallbackVoiceData = fallbackVoiceData;
        token.sourceDescriptor = sourceDescriptor;
        token.sourceDescriptorTrust = sourceDescriptorTrust;
        token.onFadeComplete = onFadeComplete;
        int count = tracks.size();
        token.ensureCapacity(count);
        for (int index = 0; index < count; index++) {
            Track track = tracks.get(index);
            Track backup = token.storage[index];
            if (backup == null) {
                backup = new Track(0, track.type, 0);
                token.storage[index] = backup;
            }
            copyTrack(track, backup);
            token.identities[index] = track;
        }
        token.trackCount = count;
        return token;
    }

    public void rollbackLiveCommandMutation(
            LiveCommandMutationToken token) {
        Objects.requireNonNull(token, "token");
        if (token.owner != this) {
            throw new IllegalArgumentException(
                    "live command token belongs to another sequencer");
        }
        if (!token.inUse) {
            throw new IllegalStateException(
                    "live command token was already rolled back or released");
        }
        region = token.region;
        speedShoes = token.speedShoes;
        sfxMode = token.sfxMode;
        normalTempo = token.normalTempo;
        commData = token.commData;
        fm6DacOff = token.fm6DacOff;
        maxTicks = token.maxTicks;
        pitch = token.pitch;
        sfxPriority = token.sfxPriority;
        specialSfx = token.specialSfx;
        isSfx = token.isSfx;
        psgLatchChannel = token.psgLatchChannel;
        speedMultiplier = token.speedMultiplier;
        speedupTimeout = token.speedupTimeout;
        fadeState.steps = token.fadeSteps;
        fadeState.delayInit = token.fadeDelayInit;
        fadeState.delayCounter = token.fadeDelayCounter;
        fadeState.addFm = token.fadeAddFm;
        fadeState.addPsg = token.fadeAddPsg;
        fadeState.active = token.fadeActive;
        fadeState.fadeOut = token.fadeOut;
        sampleRate = token.sampleRate;
        samplesPerFrame = token.samplesPerFrame;
        sampleCounter = token.sampleCounter;
        tempoWeight = token.tempoWeight;
        tempoAccumulator = token.tempoAccumulator;
        dividingTiming = token.dividingTiming;
        primed = token.primed;
        tracks.clear();
        for (int index = 0; index < token.trackCount; index++) {
            Track track = token.identities[index];
            copyTrack(token.storage[index], track);
            tracks.add(track);
        }
        fallbackVoiceData = token.fallbackVoiceData;
        fallbackVoiceView = token.fallbackVoiceData;
        sourceDescriptor = token.sourceDescriptor;
        sourceDescriptorTrust = token.sourceDescriptorTrust;
        onFadeComplete = token.onFadeComplete;
        releaseLiveCommandMutation(token);
    }

    /** Returns a committed token to the pool; no-op for one already rolled back or released. */
    public void releaseLiveCommandMutation(LiveCommandMutationToken token) {
        Objects.requireNonNull(token, "token");
        if (token.owner != this) {
            throw new IllegalArgumentException(
                    "live command token belongs to another sequencer");
        }
        if (!token.inUse) {
            return;
        }
        token.inUse = false;
        Arrays.fill(token.identities, 0, token.trackCount, null);
        token.fallbackVoiceData = null;
        token.sourceDescriptor = null;
        token.onFadeComplete = null;
        token.trackCount = 0;
        liveTokenPool.addFirst(token);
    }

    /**
     * Copies the state {@link SmpsTrackSnapshot} carries from one track to
     * another. Program data references ({@code voiceData}, {@code envData},
     * {@code modEnvData}, {@code fmVolEnvData}) are shared: they are replaced
     * as whole arrays and never written in place, so a live rollback restores
     * the same content by restoring the reference. The scratch, stack and
     * counter arrays are copied by content.
     */
    private static void copyTrack(Track from, Track to) {
        to.pos = from.pos;
        to.type = from.type;
        to.channelId = from.channelId;
        to.duration = from.duration;
        to.note = from.note;
        to.active = from.active;
        to.overridden = from.overridden;
        to.rawDuration = from.rawDuration;
        to.scaledDuration = from.scaledDuration;
        to.fill = from.fill;
        to.fillCounter = from.fillCounter;
        to.resting = from.resting;
        to.keyOffset = from.keyOffset;
        to.volumeOffset = from.volumeOffset;
        to.tieNext = from.tieNext;
        to.pan = from.pan;
        to.ams = from.ams;
        to.fms = from.fms;
        to.voiceData = from.voiceData;
        System.arraycopy(from.voiceScratch, 0, to.voiceScratch, 0, to.voiceScratch.length);
        to.voiceId = from.voiceId;
        to.baseFnum = from.baseFnum;
        to.baseBlock = from.baseBlock;
        if (to.loopCounters.length != from.loopCounters.length) {
            to.loopCounters = new int[from.loopCounters.length];
        }
        System.arraycopy(from.loopCounters, 0, to.loopCounters, 0, from.loopCounters.length);
        to.loopTarget = from.loopTarget;
        System.arraycopy(from.returnStack, 0, to.returnStack, 0, to.returnStack.length);
        to.returnSp = from.returnSp;
        to.dividingTiming = from.dividingTiming;
        to.modDelay = from.modDelay;
        to.modDelayInit = from.modDelayInit;
        to.modRate = from.modRate;
        to.modDelta = from.modDelta;
        to.modSteps = from.modSteps;
        to.modStepsFull = from.modStepsFull;
        to.modPendingDelayInit = from.modPendingDelayInit;
        to.modPendingRate = from.modPendingRate;
        to.modPendingDelta = from.modPendingDelta;
        to.modPendingSteps = from.modPendingSteps;
        to.modPendingStepsFull = from.modPendingStepsFull;
        to.modRateCounter = from.modRateCounter;
        to.modStepCounter = from.modStepCounter;
        to.modAccumulator = from.modAccumulator;
        to.modCurrentDelta = from.modCurrentDelta;
        to.modEnabled = from.modEnabled;
        to.customModEnabled = from.customModEnabled;
        to.detune = from.detune;
        to.modEnvId = from.modEnvId;
        to.modEnvData = from.modEnvData;
        to.modEnvPos = from.modEnvPos;
        to.modEnvMult = from.modEnvMult;
        to.modEnvCache = from.modEnvCache;
        to.modEnvHold = from.modEnvHold;
        to.rawFreqMode = from.rawFreqMode;
        to.rawFrequency = from.rawFrequency;
        to.instrumentId = from.instrumentId;
        to.noiseMode = from.noiseMode;
        to.psgNoiseParam = from.psgNoiseParam;
        to.decayOffset = from.decayOffset;
        to.decayTimer = from.decayTimer;
        to.envData = from.envData;
        to.envPos = from.envPos;
        to.envValue = from.envValue;
        to.envHold = from.envHold;
        to.envAtRest = from.envAtRest;
        to.fmVolEnvData = from.fmVolEnvData;
        to.fmVolEnvPos = from.fmVolEnvPos;
        to.fmVolEnvValue = from.fmVolEnvValue;
        to.fmVolEnvHold = from.fmVolEnvHold;
        to.fmVolEnvOpMask = from.fmVolEnvOpMask;
        to.forceRefresh = from.forceRefresh;
        System.arraycopy(from.ssgEg, 0, to.ssgEg, 0, to.ssgEg.length);
        to.dacMuted = from.dacMuted;
        to.modStepInEffect = from.modStepInEffect;
        to.modStepChanged = from.modStepChanged;
        to.modStepDelta = from.modStepDelta;
        to.modEnvStepInEffect = from.modEnvStepInEffect;
        to.modEnvStepChanged = from.modEnvStepChanged;
        to.modEnvStepDelta = from.modEnvStepDelta;
        to.fm3SpecialMode = from.fm3SpecialMode;
        to.customSsgEgPresent = from.customSsgEgPresent;
        System.arraycopy(from.customSsgEgPayload, 0, to.customSsgEgPayload, 0, to.customSsgEgPayload.length);
        to.customSsgEgPayloadKnown = from.customSsgEgPayloadKnown;
        to.rawPsgNoise = from.rawPsgNoise;
        to.rawPsgNoiseKnown = from.rawPsgNoiseKnown;
    }

    private static class FadeState {
        int steps;
        int delayInit;
        int delayCounter;
        int addFm;
        int addPsg;
        boolean active;
        boolean fadeOut; // true = Fade Out, false = Fade In
    }

    private final FadeState fadeState = new FadeState();
    private Runnable onFadeComplete;

    private double sampleRate = 44100.0;
    // Base tempo weight is game/driver-specific (configured externally).
    private double samplesPerFrame = 44100.0 / 60.0;
    private double sampleCounter = 0;
    private int tempoWeight;
    private int tempoAccumulator;
    private int dividingTiming = 1;
    private boolean primed;

    // Scratch buffer for read() to avoid per-sample allocations

    // Speed-up tempos and channel orders are game/driver-specific (configurable).

    // DEF_FMFREQ_68K - S1/S2 68K driver (FMBaseNote = B, FMBaseOctave = -1)
    private static final int[] FNUM_TABLE_68K = {
            606, 644, 683, 723, 766, 813, 860, 911, 965, 1023, 1084, 1148
    };
    // DEF_FMFREQ_Z80 - S3K Z80 driver (FMBaseNote = C, FMBaseOctave = 0)
    private static final int[] FNUM_TABLE_Z80 = {
            644, 683, 723, 766, 813, 860, 911, 965, 1023, 1084, 1148, 1216
    };
    // SMPSPlay DEF_PSGFREQ_68K table (register values). Slice from DEF_PSGFREQ_PRE
    // starting at index 12 (count 70).
    private static final int[] PSG_FREQ_TABLE_68K = {
            0x356, 0x326, 0x2F9, 0x2CE, 0x2A5, 0x280, 0x25C, 0x23A, 0x21A, 0x1FB, 0x1DF, 0x1C4,
            0x1AB, 0x193, 0x17D, 0x167, 0x153, 0x140, 0x12E, 0x11D, 0x10D, 0x0FE, 0x0EF, 0x0E2,
            0x0D6, 0x0C9, 0x0BE, 0x0B4, 0x0A9, 0x0A0, 0x097, 0x08F, 0x087, 0x07F, 0x078, 0x071,
            0x06B, 0x065, 0x05F, 0x05A, 0x055, 0x050, 0x04B, 0x047, 0x043, 0x040, 0x03C, 0x039,
            0x036, 0x033, 0x030, 0x02D, 0x02B, 0x028, 0x026, 0x024, 0x022, 0x020, 0x01F, 0x01D,
            0x01B, 0x01A, 0x018, 0x017, 0x016, 0x015, 0x013, 0x012, 0x011, 0x000
    };
    // SMPSPlay DEF_PSGFREQ_Z80_T2 table used by S3K (DefDrv: PSGFreqs=DEF_Z80_T2).
    private static final int[] PSG_FREQ_TABLE_Z80_T2 = {
            0x3FF, 0x3FF, 0x3FF, 0x3FF, 0x3FF, 0x3FF, 0x3FF, 0x3FF, 0x3FF, 0x3F7, 0x3BE, 0x388,
            0x356, 0x326, 0x2F9, 0x2CE, 0x2A5, 0x280, 0x25C, 0x23A, 0x21A, 0x1FB, 0x1DF, 0x1C4,
            0x1AB, 0x193, 0x17D, 0x167, 0x153, 0x140, 0x12E, 0x11D, 0x10D, 0x0FE, 0x0EF, 0x0E2,
            0x0D6, 0x0C9, 0x0BE, 0x0B4, 0x0A9, 0x0A0, 0x097, 0x08F, 0x087, 0x07F, 0x078, 0x071,
            0x06B, 0x065, 0x05F, 0x05A, 0x055, 0x050, 0x04B, 0x047, 0x043, 0x040, 0x03C, 0x039,
            0x036, 0x033, 0x030, 0x02D, 0x02B, 0x028, 0x026, 0x024, 0x022, 0x020, 0x01F, 0x01D,
            0x01B, 0x01A, 0x018, 0x017, 0x016, 0x015, 0x013, 0x012, 0x011, 0x010, 0x000, 0x000
    };

    // Carrier bitmask per YM2612 algorithm in register traversal order.
    // S1's normalized 68k voice needs the converted form; S2 consumes the raw table.
    private static final int[] ALGO_OUT_MASK_SLOT = { 0x08, 0x08, 0x08, 0x08, 0x0C, 0x0E, 0x0E, 0x0F };
    private static final int[] ALGO_OUT_MASK = toSmpsOrderMask(ALGO_OUT_MASK_SLOT);
    private static final int[] S2_OPERATOR_REGISTER_OFFSETS = { 0, 4, 8, 12 };
    private static final int[] S3K_OPERATOR_REGISTER_OFFSETS = { 0, 8, 4, 12 };
    private static final int[] FM_PARAMETER_REGISTERS = { 0x30, 0x50, 0x60, 0x70, 0x80 };

    private static int[] toSmpsOrderMask(int[] slotMasks) {
        int[] out = new int[slotMasks.length];
        for (int i = 0; i < slotMasks.length; i++) {
            int mask = slotMasks[i];
            int smps = 0;
            if ((mask & 0x01) != 0) smps |= 1; // slot1 -> op1
            if ((mask & 0x04) != 0) smps |= 1 << 1; // slot3 -> op3
            if ((mask & 0x02) != 0) smps |= 1 << 2; // slot2 -> op2
            if ((mask & 0x08) != 0) smps |= 1 << 3; // slot4 -> op4
            out[i] = smps;
        }
        return out;
    }

    public enum TrackType {
        FM, PSG, DAC
    }

    public static class Track {
        public int pos;
        public TrackType type;
        public int channelId;
        public int duration;
        public int note;
        public boolean active = true;
        public boolean overridden = false; // Set if SFX stole the channel
        public int rawDuration;
        public int scaledDuration;
        public int fill; // note-off shortening in ticks
        public int fillCounter; // live S1/S2 NoteTimeout countdown
        public boolean resting; // S1/S2 PlaybackControl bit 1
        /**
         * Withholds the PSG attenuation byte while the envelope is stepped.
         * S1 splits the work in two: PSGDoVolFX advances the envelope and
         * computes the level, then falls into SetPSGVolume, which decides
         * whether the byte reaches the chip (s1.sounddriver.asm:1938-1969).
         * The engine's envelope step sends as it goes, so stepping it without
         * a send needs this.
         */
        public boolean suppressPsgVolumeWrite;
        public int keyOffset; // signed semitone displacement (E9)
        public int volumeOffset; // attenuation applied to TL (FM) or volume (PSG)
        public boolean tieNext; // E7 prevents next attack
        public int pan = 0xC0; // default L+R bits set for YM (E0)
        public int ams = 0;
        public int fms = 0;
        public byte[] voiceData; // last loaded voice
        // Scratch buffer for voice data modification (avoids allocation in refreshInstrument)
        public final byte[] voiceScratch = new byte[25];
        public int voiceId;
        public int baseFnum;
        public int baseBlock;
        public int[] loopCounters = new int[8]; // Increased from 4 to reduce runtime reallocation
        public int loopTarget = -1;
        // Z80 driver: Stack shares space with loop counters, grows down from offset 0x2A.
        // No hard limit but collision possible after ~5 calls. Using 16 for safety margin.
        public final int[] returnStack = new int[16];
        public int returnSp = 0;
        public int dividingTiming = 1;
        // Modulation (F0)
        public int modDelay;
        public int modDelayInit;
        public int modRate;
        public int modDelta;
        public int modSteps;
        public int modStepsFull;
        public int modPendingDelayInit;
        public int modPendingRate;
        public int modPendingDelta;
        public int modPendingSteps;
        public int modPendingStepsFull;
        public int modRateCounter;
        public int modStepCounter;
        public short modAccumulator;
        public int modCurrentDelta;
        public boolean modEnabled;
        public boolean customModEnabled;
        public int detune;
        public int modEnvId;
        public byte[] modEnvData;
        public int modEnvPos;
        public int modEnvMult;
        public int modEnvCache;
        public boolean modEnvHold;
        public boolean rawFreqMode;
        public int rawFrequency;
        public int instrumentId;
        public boolean noiseMode;
        /** S3K FM3 PlaybackControl bit 0; distinct from PSG noiseMode. */
        public boolean fm3SpecialMode;
        public int psgNoiseParam;
        /** Exact unsigned S3K cfSetPSGNoise operand retained for zStopPSGTrack. */
        public int rawPsgNoise;
        /** Whether {@link #rawPsgNoise} came from an executed PSG F3 command. */
        public boolean rawPsgNoiseKnown;
        public int decayOffset;
        public int decayTimer;
        // PSG Volume Envelope
        public byte[] envData;
        public int envPos;
        public int envValue;
        public boolean envHold;
        public boolean envAtRest;
        // S3K FF 06: FM volume envelope (envelope ID + operator mask).
        public byte[] fmVolEnvData;
        public int fmVolEnvPos;
        public int fmVolEnvValue;
        public boolean fmVolEnvHold;
        public int fmVolEnvOpMask;
        public boolean forceRefresh;
        // SSG-EG per-operator state (S3K FF 05), preserved across track restoration.
        public final int[] ssgEg = new int[4];
        /** S3K HaveSSGEGFlag: FF05 occurred, including the all-zero payload. */
        public boolean customSsgEgPresent;
        /** Exact unsigned FF05 operands for S3K's E4 custom SSG-EG restore. */
        public final int[] customSsgEgPayload = new int[4];
        /** Whether {@link #customSsgEgPayload} still represents the aliased Z80 pointer. */
        public boolean customSsgEgPayloadKnown;
        // DAC mute state for fade-in
        public boolean dacMuted;

        // Mutable result fields for stepCustomModulation() – avoids per-tick allocation
        boolean modStepInEffect;
        boolean modStepChanged;
        int modStepDelta;
        boolean forceModulationWrite;

        // Mutable result fields for stepModEnvelope() – avoids per-tick allocation
        boolean modEnvStepInEffect;
        boolean modEnvStepChanged;
        int modEnvStepDelta;

        Track(int pos, TrackType type, int channelId) {
            this.pos = pos;
            this.type = type;
            this.channelId = channelId;
            // Every driver seeds the first duration timeout with 1, so the
            // track's first walk decrements it to zero and reads its opening
            // stream unit on that same walk. S1 loads d5 = 1 for both music
            // loops (s1.sounddriver.asm:823, :836, used at :847 and :897) and
            // writes 1 directly for SFX (:1062, :1171); S2 stores 1 with the
            // comment "should expire next update, play first note, etc."
            // (s2.sounddriver.asm:1857); S3K's zZeroFillTrackRAM seeds it in
            // the track-RAM fill (skdisasm Sound/Z80 Sound
            // Driver.asm:2168-2184).
            this.duration = 1;
        }
    }

    public SmpsSequencer(AbstractSmpsData smpsData, DacData dacData, SmpsSequencerConfig config) {
        this(smpsData, dacData, DETACHED_WRITE_TARGET,
                SmpsSequencerHost.NONE, GameServices.audio(), config, null,
                SourceDescriptorTrust.LEGACY_RECOMPUTE);
    }

    public SmpsSequencer(AbstractSmpsData smpsData, DacData dacData, MusicRestoreSink audioManager,
            SmpsSequencerConfig config) {
        this(smpsData, dacData, DETACHED_WRITE_TARGET,
                SmpsSequencerHost.NONE, audioManager, config, null,
                SourceDescriptorTrust.LEGACY_RECOMPUTE);
    }

    public SmpsSequencer(AbstractSmpsData smpsData, DacData dacData,
            SmpsLogicalWriteTarget synth,
            SmpsSequencerConfig config) {
        this(smpsData, dacData, synth, GameServices.audio(), config);
    }

    public SmpsSequencer(AbstractSmpsData smpsData, DacData dacData,
            SmpsLogicalWriteTarget synth,
            MusicRestoreSink audioManager, SmpsSequencerConfig config) {
        this(smpsData, dacData, synth, audioManager, config, null,
                SourceDescriptorTrust.LEGACY_RECOMPUTE);
    }

    public SmpsSequencer(
            AbstractSmpsData smpsData,
            DacData dacData,
            SmpsLogicalWriteTarget synth,
            MusicRestoreSink audioManager,
            SmpsSequencerConfig config,
            SmpsSourceDescriptor sourceDescriptor) {
        this(smpsData, dacData, synth, audioManager, config,
                sourceDescriptor, SourceDescriptorTrust.LEGACY_RECOMPUTE);
    }

    public SmpsSequencer(
            AbstractSmpsData smpsData,
            DacData dacData,
            SmpsLogicalWriteTarget synth,
            MusicRestoreSink audioManager,
            SmpsSequencerConfig config,
            SmpsSourceDescriptor sourceDescriptor,
            SourceDescriptorTrust sourceDescriptorTrust) {
        this(smpsData, dacData, Objects.requireNonNull(synth, "synth"),
                inferredHost(synth), audioManager, config, sourceDescriptor,
                sourceDescriptorTrust);
    }

    public SmpsSequencer(
            AbstractSmpsData smpsData,
            DacData dacData,
            SmpsLogicalWriteTarget synth,
            SmpsSequencerHost host,
            MusicRestoreSink audioManager,
            SmpsSequencerConfig config,
            SmpsSourceDescriptor sourceDescriptor,
            SourceDescriptorTrust sourceDescriptorTrust) {
        this.smpsData = Objects.requireNonNull(smpsData, "smpsData");
        this.programView = smpsData;
        this.sourceDescriptorTrust = Objects.requireNonNull(
                sourceDescriptorTrust, "sourceDescriptorTrust");
        if (sourceDescriptor == null
                && sourceDescriptorTrust == SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE) {
            throw new IllegalArgumentException(
                    "precomputed source descriptor is required for immutable trust");
        }
        this.sourceDescriptor = sourceDescriptor != null
                ? sourceDescriptor : SmpsSourceDescriptor.from(smpsData);
        this.audioManager = Objects.requireNonNull(audioManager, "audioManager");
        this.synth = Objects.requireNonNull(synth, "synth");
        this.host = Objects.requireNonNull(host, "host");
        this.config = Objects.requireNonNull(config, "config");
        this.tempoModBase = this.config.getTempoModBase();
        this.dacData = dacData;
        dividingTiming = smpsData.getDividingTiming();
        if (dividingTiming == 0) {
            dividingTiming = 1;
        }
        normalTempo = smpsData.getTempo();

        // Initialize Region and Tempo
        setRegion(Region.NTSC);

        // Z80 drivers seed the tempo accumulator with the header tempo at song
        // load (S2 sd:1820-1822: TempoTimeout = CurrentTempo = tempo; S3K
        // D:1829-1831: zTempoAccumulator = zCurrentTempo = tempo), so the first
        // TempoWait adds the tempo to an already-seeded value. TIMEOUT (S1)
        // seeds its countdown in calculateTempo. SFX programs have no music
        // tempo accumulator (their pass is not tempo-gated).
        if (config.getTempoMode() != SmpsSequencerConfig.TempoMode.TIMEOUT
                && !(smpsData instanceof SmpsSfxData)) {
            tempoAccumulator = tempoWeight;
        }

        int z80Start = smpsData.getZ80StartAddress();

        if (smpsData instanceof SmpsSfxData sfxData) {
            initSfxTracks(sfxData, z80Start);
            List<Track> headerOrder = List.copyOf(tracks);
            if (config.getSfxTrackWalkMode()
                    == SmpsSequencerConfig.SfxTrackWalkMode.CHANNEL_RAM_ORDER) {
                tracks.sort(Comparator.comparingInt(SmpsSequencer::sfxTrackRamOrder));
            }
            sfxHeaderOrderIndices = headerOrder.stream()
                    .mapToInt(track -> tracks.indexOf(track)).toArray();
            setSfxMode(true);
            return;
        }

        // FM tracks mapping
        for (int i = 0; i < programView.fmPointerCount(); i++) {
            int chnVal = (i < config.fmChannelCount())
                    ? config.fmChannelAt(i) : -1;

            // 0x16 or 0x10 is DAC
            if (chnVal == 0x16 || chnVal == 0x10) {
                // DAC Track
                int ptr = relocate(programView.fmPointerAt(i), z80Start);
                if (ptr >= 0 && ptr < programView.dataLength()) {
                    Track t = new Track(ptr, TrackType.DAC, 5); // DAC uses channel 5 (FM6) slot
                    t.dividingTiming = dividingTiming;
                    tracks.add(t);
                }
                continue;
            }

            // FM Channel
            int linearCh = mapFmChannel(chnVal);
            if (linearCh >= 0) {
                int ptr = relocate(programView.fmPointerAt(i), z80Start);
                if (ptr < 0 || ptr >= programView.dataLength()) {
                    continue;
                }
                Track t = new Track(ptr, TrackType.FM, linearCh);
                t.keyOffset = (byte) programView.fmKeyOffsetAt(i);
                t.volumeOffset = programView.fmVolumeOffsetAt(i);
                t.dividingTiming = dividingTiming;
                // No driver uploads an instrument when a song loads: S3K
                // zBGMLoad's FM/DAC loop only calls zInitFMDACTrack, which
                // writes track RAM and zeroes the FM instrument index
                // (skdisasm Sound/Z80 Sound Driver.asm:1837-1856, 2171-2199),
                // and its single chip write between the bank switch and the
                // track loops is 0B6h (:1811-1816). S2 zBGMLoad defers to
                // zInitMusicPlayback (s2.sounddriver.asm:1738-1739) and S1 uses
                // InitMusicPlayback (s1.sounddriver.asm:1486-1545); neither
                // sends a voice either. The voice reaches the YM2612 only from
                // the track's own SetVoice, so select it without refreshing.
                selectVoice(t, 0); // default instrument, track RAM only
                tracks.add(t);
            }
        }

        // PSG tracks mapping
        for (int i = 0; i < programView.psgPointerCount(); i++) {
            int ptr = relocate(programView.psgPointerAt(i), z80Start);
            if (ptr < 0 || ptr >= programView.dataLength()) {
                continue;
            }

            int chnVal = (i < config.psgChannelCount())
                    ? config.psgChannelAt(i) : -1;
            int linearCh = mapPsgChannel(chnVal);
            if (linearCh < 0) {
                // Fallback for extra channels (like Noise if mapped linearly)
                linearCh = i;
            }

            Track t = new Track(ptr, TrackType.PSG, linearCh);
            t.keyOffset = (byte) programView.psgKeyOffsetAt(i);
            t.volumeOffset = programView.psgVolumeOffsetAt(i);
            t.modEnvId = programView.psgModEnvelopeAt(i);
            if (t.modEnvId != 0) {
                t.modEnvData = copyModEnvelope(programView, t.modEnvId);
                t.modEnabled = t.modEnvData != null;
            }
            t.instrumentId = programView.psgInstrumentAt(i);
            if (i < programView.psgInstrumentCount()) {
                loadPsgEnvelope(t, t.instrumentId);
            }
            t.dividingTiming = dividingTiming;
            tracks.add(t);
        }
    }

    private static SmpsSequencerHost inferredHost(
            SmpsLogicalWriteTarget synth) {
        return synth instanceof SmpsSequencerHost sequencerHost
                ? sequencerHost : SmpsSequencerHost.NONE;
    }

    private static int sfxTrackRamOrder(Track track) {
        return switch (track.type) {
            case DAC -> track.channelId;
            case FM -> 8 + track.channelId;
            case PSG -> 16 + track.channelId;
        };
    }

    @Override
    public AbstractSmpsData getSmpsData() {
        return smpsData;
    }

    public SmpsSourceDescriptor getSourceDescriptor() {
        return sourceDescriptor;
    }

    public SourceDescriptorTrust getSourceDescriptorTrust() {
        return sourceDescriptorTrust;
    }

    public void setSourceDescriptor(SmpsSourceDescriptor sourceDescriptor) {
        this.sourceDescriptor = Objects.requireNonNull(sourceDescriptor, "sourceDescriptor");
        sourceDescriptorTrust = SourceDescriptorTrust.LEGACY_RECOMPUTE;
    }

    public DacData getDacData() {
        return dacData;
    }

    @Override
    public void restorePreviousMusic() {
        audioManager.restoreMusic();
    }

    public MusicRestoreSink getAudioManager() {
        return audioManager;
    }

    /**
     * Optional: provide another SMPS data set (usually the currently playing music)
     * to supply instrument voices if this sequence has no local voice table.
     */
    public void setFallbackVoiceData(AbstractSmpsData fallbackVoiceData) {
        this.fallbackVoiceData = fallbackVoiceData;
        this.fallbackVoiceView = fallbackVoiceData;
    }

    public AbstractSmpsData getFallbackVoiceData() {
        return fallbackVoiceData;
    }

    /**
     * Force-silence a hardware channel that was previously owned by this sequencer.
     * Used by the driver when releasing SFX locks so stray tones don't linger
     * if there is no music track to immediately rewrite the channel.
     * <p>
     * For FM channels, this matches ROM behavior (zSetMaxRelRate + zFMSilenceChannel):
     * - Set D1L/RR to 0xFF for all operators (fastest release)
     * - Set TL to 0x7F for all operators (max attenuation)
     * - Key off
     * This ensures the envelope is fully silenced before music refreshes the channel,
     * preventing corrupted first samples when music resumes.
     */
    public void forceSilence(TrackType type, int channelId) {
        if (type == TrackType.FM) {
            int port = (channelId < 3) ? 0 : 1;
            int ch = channelId % 3;
            int chVal = (port == 0) ? ch : (ch + 4);

            // ROM: zSetMaxRelRate - Set D1L/RR to 0xFF (fastest release) for all operators
            // Register 0x80-0x8F: D1L/RR (Sustain Level / Release Rate)
            // Operator register offsets: 0x00 (Op1), 0x08 (Op3), 0x04 (Op2), 0x0C (Op4)
            int[] opOffsets = {0x00, 0x08, 0x04, 0x0C};
            for (int opOffset : opOffsets) {
                synth.writeFm(this, port, 0x80 + opOffset + ch, 0xFF);
            }

            // ROM: zFMSilenceChannel - Set TL to 0x7F (max attenuation) for all operators
            // Register 0x40-0x4F: TL (Total Level)
            for (int opOffset : opOffsets) {
                synth.writeFm(this, port, 0x40 + opOffset + ch, 0x7F);
            }

            // Key off
            synth.writeFm(this, 0, 0x28, chVal);

            if (channelId == 5) {
                // If this was DAC (FM6), stop DAC playback too.
                synth.stopDac(this);
            }
        } else if (type == TrackType.PSG) {
            int ch = Math.max(0, Math.min(3, channelId));
            synth.writePsg(this, 0x80 | (ch << 5) | (1 << 4) | 0x0F); // volume -> silence
        } else if (type == TrackType.DAC) {
            synth.stopDac(this);
        }
    }

    public void setRegion(Region region) {
        this.region = region;
        this.samplesPerFrame = sampleRate / region.frameRate;
        calculateTempo();
    }

    public void setSpeedShoes(boolean active) {
        this.speedShoes = active;
        calculateTempo();
    }

    public void setFm6DacOff(boolean active) {
        this.fm6DacOff = active;
    }

    /**
     * Records that this sequencer was created by the request its own service
     * consumed, so the walk it would otherwise skip has already gone by.
     *
     * <p>{@code zUpdateSFXTracks} runs before {@code zFillSoundQueue} admits a
     * sound (skdisasm Sound/Z80 Sound Driver.asm:653-701), so an SFX gets no
     * update in its admitting service and its first one is the next service.
     * {@code primeFirstService} models that by skipping a walk, which is right
     * when the sound is admitted outside a service, as live playback admits it.
     * When the driver consumes the request at the ROM's point instead, the
     * admitting service's SFX walk has genuinely already run, and skipping
     * again would cost the sound a second service.
     */
    public void markAdmittingServiceWalkMissed() {
        if (sfxMode && config.isSfxWalkPrecedesRequest()) {
            primed = true;
        }
    }

    public void setSfxMode(boolean active) {
        this.sfxMode = active;
        int div = smpsData.getDividingTiming();
        if (smpsData instanceof SmpsSfxData sfxData) {
            div = sfxData.getTickMultiplier();
        }
        if (div == 0) {
            div = 1;
        }
        if (active) {
            updateDividingTiming(div);
        } else {
            updateDividingTiming(smpsData.getDividingTiming());
        }
        // SFX tick every tempo frame; keep frame pacing tied to region to avoid
        // double-speed playback.
        this.samplesPerFrame = sampleRate / region.frameRate;
        calculateTempo();

        // Safety: cap SFX to a reasonable tick budget so bad data doesn't hang forever.
        if (active) {
            this.maxTicks = 2048;
        } else {
            this.maxTicks = Integer.MAX_VALUE;
        }
    }

    public void setChannelOverridden(TrackType type, int channelId, boolean overridden) {
        setChannelOverridden(type, channelId, overridden, false);
    }

    /** Restore cause owned only by S3K cfStopTrack's immediate handoff. */
    public void setChannelOverriddenAfterSfxTrackStop(
            TrackType type, int channelId) {
        setChannelOverridden(type, channelId, false, true);
    }

    private void setChannelOverridden(TrackType type, int channelId,
            boolean overridden, boolean afterSfxTrackStop) {
        for (Track t : tracks) {
            if (t.type == type && t.channelId == channelId) {
                boolean wasOverridden = t.overridden;
                t.overridden = overridden;
                if (wasOverridden && !overridden) {
                    if (afterSfxTrackStop && t.type == TrackType.PSG
                            && config.getPsgSfxReleaseMode()
                            == SmpsSequencerConfig.PsgSfxReleaseMode
                                    .ROM_NOISE_RESTORE_PRESERVE_REST) {
                        // zStopPSGTrack has no playing-bit guard. It preserves
                        // rest and re-sends only a negative raw PSGNoise byte
                        // (skdisasm Sound/Z80 Sound Driver.asm:3521-3533).
                        if (t.noiseMode && t.rawPsgNoiseKnown
                                && (t.rawPsgNoise & 0x80) != 0) {
                            synth.writePsg(this, t.rawPsgNoise);
                        }
                        continue;
                    }
                    if (!t.active)
                        continue;

                    if (t.type == TrackType.FM
                            && config.getFmSfxReleaseMode()
                            != SmpsSequencerConfig.FmSfxReleaseMode.LEGACY_FULL_RESTORE) {
                        // The SFX's note-off stands: restore voice/pan without
                        // resending frequency or key-on. The profile owns whether
                        // this handoff also sets the music track's rest bit.
                        if (config.getFmSfxReleaseMode()
                                == SmpsSequencerConfig.FmSfxReleaseMode.ROM_VOICE_RESTORE) {
                            t.resting = true;
                        }
                        if (t.channelId == 2
                                && config.getFmSfxReleaseMode()
                                == SmpsSequencerConfig.FmSfxReleaseMode
                                        .ROM_VOICE_RESTORE_PRESERVE_REST) {
                            // S3K cfStopTrack restores FM3 settings before the
                            // covered music voice (:3472-3499). Shipped
                            // fix_sndbugs=0 also saves this byte in
                            // zFM3Settings; the fixed branch omits that RAM
                            // store, but both branches write 27h physically.
                            synth.writeFm(this, 0, 0x27,
                                    t.fm3SpecialMode ? 0x4F : 0x0F);
                        }
                        refreshInstrument(t);
                        continue;
                    }
                    if (t.type == TrackType.PSG
                            && config.getPsgSfxReleaseMode()
                            == SmpsSequencerConfig.PsgSfxReleaseMode.ROM_REST_RESTORE) {
                        // S1 cfStopTrack clears the override at rest. Only a
                        // restored noise track re-latches PSGNoise; ordinary
                        // PSG volume/frequency wait for the next note
                        // (SD:2538-2563).
                        t.resting = true;
                        if (t.noiseMode) {
                            synth.writePsg(this, 0xe0 | (t.psgNoiseParam & 0x0f));
                        }
                        continue;
                    }

                    // Channel released from SFX, restore instrument and volume
                    refreshInstrument(t);
                    if (t.type == TrackType.PSG) {
                        refreshVolume(t);
                    }
                    if (t.type == TrackType.FM) {
                        applyFmPanAmsFms(t);
                        // Ensure channel is keyed-off after restore to prevent clicks/pops.
                        // The music track's note was interrupted by SFX; it should remain
                        // silent until the next note event naturally keys-on.
                        int hwCh = t.channelId;
                        int port = (hwCh < 3) ? 0 : 1;
                        int ch = hwCh % 3;
                        int chVal = (port == 0) ? ch : (ch + 4);
                        synth.writeFm(this, 0, 0x28, chVal);
                    }
                    if (t.duration > 0) {
                        restoreFrequency(t);
                    }
                }
            }
        }
    }

    /**
     * Updates the driver-RAM override bit without performing release writes.
     * Admission-time channel-mask changes are pure ownership mutations in the
     * shipped driver; the next ordinary music service owns any audible write.
     */
    public void setChannelOverriddenWithoutRestore(
            TrackType type, int channelId, boolean overridden) {
        for (Track track : tracks) {
            if (track.type == type && track.channelId == channelId) {
                track.overridden = overridden;
            }
        }
    }

    private void restoreFrequency(Track t) {
        if (t.type == TrackType.PSG) {
            boolean noiseUsesTone2 = t.noiseMode && t.channelId == 2 && (t.psgNoiseParam & 0x03) == 0x03;
            boolean writeToneFreq = t.channelId < 3 && (!t.noiseMode || noiseUsesTone2);

            if (writeToneFreq) {
                int reg = t.baseFnum + t.modAccumulator + t.detune;
                if (pitch != 1.0f) {
                    reg = (int) (reg / pitch);
                }
                reg = normalizePsgPeriod(reg);

                int data = reg & 0xF;
                int ch = t.channelId;
                synth.writePsgFrequencyPair(this,
                        0x80 | (ch << 5) | data,
                        psgFrequencyHighByte(reg));
            }

            if (t.noiseMode) {
                synth.writePsg(this, 0xE0 | (t.psgNoiseParam & 0x0F));
            }
            return;
        }

        if (t.type != TrackType.FM)
            return;

        int packed = (t.baseBlock << 11) | t.baseFnum;
        packed += t.modAccumulator + t.detune;

        if (pitch != 1.0f) {
            int b = (packed >> 11) & 7;
            int f = packed & 0x7FF;
            f = (int) (f * pitch);
            while (f > 0x7FF && b < 7) {
                f >>= 1;
                b++;
            }
            packed = (b << 11) | (f & 0x7FF);
        }

        int block = (packed >> 11) & 7;
        int fnum = packed & 0x7FF;

        int hwCh = t.channelId;
        int port = (hwCh < 3) ? 0 : 1;
        int ch = (hwCh % 3);
        writeFmFreq(port, ch, fnum, block);
    }

    public List<Track> getTracks() {
        return Collections.unmodifiableList(tracks);
    }

    /** SFX header order before any fixed channel-RAM service ordering. */
    public List<Track> getSfxHeaderOrderTracks() {
        if (sfxHeaderOrderIndices.length == 0) {
            return getTracks();
        }
        List<Track> ordered = new ArrayList<>(sfxHeaderOrderIndices.length);
        for (int index : sfxHeaderOrderIndices) {
            ordered.add(tracks.get(index));
        }
        for (int index = sfxHeaderOrderIndices.length;
                index < tracks.size(); index++) {
            ordered.add(tracks.get(index));
        }
        return Collections.unmodifiableList(ordered);
    }

    public int trackCount() {
        return tracks.size();
    }

    public Track trackAt(int index) {
        return tracks.get(index);
    }

    /** Returns whether this sequencer writes to the supplied synthesizer. */
    public boolean isBoundTo(SmpsLogicalWriteTarget candidate) {
        return synth == candidate;
    }

    /**
     * Publishes the coordination start for a prepared new SFX admission.
     * Continuous extensions deliberately do not call this method.
     */
    public void beginSfxAdmission() {
        CoordFlagHandler handler = config.getCoordFlagHandler();
        if (handler != null) {
            handler.onSfxStart(smpsData.getId());
        }
    }

    /** Publishes the SFX DAC source only after admission has been validated. */
    public void commitSfxAdmissionInitialization() {
        if (!(smpsData instanceof SmpsSfxData)) {
            return;
        }
        synth.selectDac(sourceDescriptor, dacData);
    }

    /** Validates the raw SFX entries retained by the immutable program. */
    public void validateSfxAdmissionMetadata() {
        if (!(smpsData instanceof SmpsSfxData sfxData)) {
            return;
        }
        List<? extends SmpsSfxData.SmpsSfxTrack> entries =
                sfxData.getTrackEntries();
        if (entries.size() != tracks.size()) {
            throw new IllegalArgumentException(
                    "SFX contains an invalid channel or pointer");
        }
        int z80Start = smpsData.getZ80StartAddress();
        for (int index = 0; index < entries.size(); index++) {
            SmpsSfxData.SmpsSfxTrack entry = entries.get(index);
            int pointer = relocate(entry.pointer(), z80Start);
            if (pointer < 0 || pointer >= programView.dataLength()) {
                throw new IllegalArgumentException(
                        "SFX track pointer is outside the program");
            }
            int channel = entry.channelMask();
            boolean valid = channel == 0x16 || channel == 0x10
                    || mapFmChannel(channel) >= 0
                    || mapPsgChannel(channel) >= 0;
            if (!valid) {
                throw new IllegalArgumentException(
                        "SFX track has an invalid channel mask");
            }
        }
    }

    /** Adds a track to this sequencer's track list. */
    public void addTrack(Track track) {
        tracks.add(track);
    }

    private void calculateTempo() {
        if (sfxMode) {
            this.tempoWeight = config.getTempoModBase(); // 0x100: Tick every frame
            return;
        }

        int base = normalTempo;

        if (speedShoes) {
            base = config.getSpeedUpTempos().getOrDefault(smpsData.getId(), base);
        }

        // PAL compensation belongs to the ROM driver's update counter, not to
        // CurrentTempo (S2 sd:441-452; S3K D:482-499; S1 has no PAL branch).
        int weighted = base;
        if (weighted > 0xFF)
            weighted = 0xFF;

        this.tempoWeight = weighted;

        // For TIMEOUT mode, initialize the countdown accumulator to the tempo value
        if (config.getTempoMode() == SmpsSequencerConfig.TempoMode.TIMEOUT && tempoAccumulator == 0) {
            tempoAccumulator = tempoWeight;
        }
    }

    private int mapFmChannel(int val) {
        return switch (val) {
            case 0 -> 0; // FM1
            case 1 -> 1; // FM2
            case 2 -> 2; // FM3
            case 4 -> 3; // FM4
            case 5 -> 4; // FM5
            case 6 -> 5; // FM6
            default -> -1;
        };
    }

    private int mapPsgChannel(int val) {
        return switch (val) {
            case 0x80 -> 0;
            case 0xA0 -> 1;
            case 0xC0 -> 2;
            default -> -1;
        };
    }

    private void initSfxTracks(SmpsSfxData sfxData, int z80Start) {
        int tickMult = sfxData.getTickMultiplier();
        if (tickMult <= 0) {
            tickMult = 1;
        }
        updateDividingTiming(tickMult);

        for (SmpsSfxData.SmpsSfxTrack entry : sfxData.getTrackEntries()) {
            int ptr = relocate(entry.pointer(), z80Start);
            if (ptr < 0 || ptr >= programView.dataLength()) {
                continue;
            }

            int chnVal = entry.channelMask();
            TrackType type;
            int linearCh;

            if (chnVal == 0x16 || chnVal == 0x10) {
                type = TrackType.DAC;
                linearCh = 5;
            } else {
                int fmCh = mapFmChannel(chnVal);
                if (fmCh >= 0) {
                    type = TrackType.FM;
                    linearCh = fmCh;
                } else {
                    int psgCh = mapPsgChannel(chnVal);
                    if (psgCh < 0) {
                        continue;
                    }
                    type = TrackType.PSG;
                    linearCh = psgCh;
                }
            }

            Track t = new Track(ptr, type, linearCh);
            t.keyOffset = (byte) entry.transpose();
            t.volumeOffset = entry.volume();
            t.dividingTiming = tickMult;
            if (type == TrackType.FM) {
                // SFX should not inherit logical music state. Select voice 0 without
                // refreshing shared hardware; bytecode SetVoice owns the first upload.
                t.pan = 0xC0;
                t.ams = 0;
                t.fms = 0;
                primeVoice(t, 0);
            }
            tracks.add(t);
        }
    }

    private void primeVoice(Track track, int voiceId) {
        byte[] voice = copyVoice(programView, voiceId);
        if (voice != null) {
            track.voiceData = voice;
            track.voiceId = voiceId;
            Arrays.fill(track.ssgEg, 0);
        }
    }

    private int relocate(int ptr, int z80Start) {
        if (ptr == 0)
            return -1;
        // Many Sonic 2 SMPS blobs use file-relative offsets already.
        if (ptr >= 0 && ptr < programView.dataLength()) {
            return ptr;
        }
        if (z80Start > 0) {
            int offset = ptr - z80Start;
            if (offset >= 0 && offset < programView.dataLength()) {
                return offset;
            }
        }
        return -1;
    }

    /** Advances logical sample-time without owning or rendering a physical device. */
    public void advanceSamples(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
        if (!primed) {
            primeFirstService();
        }

        if (tempoWeight == 0 && config.getTempoMode() == SmpsSequencerConfig.TempoMode.OVERFLOW2) {
            return;
        }

        for (int i = 0; i < length; i++) {
            advance(1.0);
        }
    }

    /**
     * Runs the sequencer service owned by one outer presentation frame.
     * The ROM drivers enter from V-blank; PCM packet size must not decide when
     * this state transition occurs (S1 SD:147, S2 sd:399, S3K D:470-481).
     */
    // Driver-coordinated SFX slot walk (SfxTrackWalkMode.CHANNEL_RAM_ORDER).
    private boolean slotWalkPassOpen;
    private boolean slotWalkTracksPending;
    private boolean slotWalkFinishSfxTempoFrame;
    private SmpsDriverServiceObserver.ServiceEvent slotWalkService;

    public void serviceOuterFrame() {
        if (!primed) {
            primeFirstService();
            return;
        }
        processTempoFrame();
    }

    private void primeFirstService() {
        if (sfxMode && config.isSfxWalkPrecedesRequest()) {
            // zUpdateSFXTracks has already walked the SFX tracks by the time
            // zUpdateMusic's zFillSoundQueue admits this one (Sound/Z80 Sound
            // Driver.asm:650-701), so the admitting service gives it no update
            // at all and its first one is the next service.
            primed = true;
            return;
        }
        if (config.isTempoWaitPrecedesRequest()) {
            // The load service's TempoWait already ran, with the previous
            // tempo, before zUpdateMusic reached zFillSoundQueue
            // (Sound/Z80 Sound Driver.asm:653-701, :2607-2621), so this song's
            // first accumulation belongs to the next service. zBGMLoad's seed
            // of zTempoAccumulator (:1829-1831) is what the load service ends
            // on. The track walk still runs here.
            tick();
            primed = true;
            return;
        }
        if (config.isTempoOnFirstTick()) {
            if (tempoWeight != 0) {
                processTempoFrame();
            } else {
                // Tempo-0 songs (e.g. S3K Title Screen) need an unconditional
                // first tick so FF 00 can install the real tempo.
                tick();
            }
        } else if (tempoWeight != 0) {
            tick();
        }
        primed = true;
    }

    public void advance(double samples) {
        sampleCounter += samples;
        while (sampleCounter >= samplesPerFrame) {
            sampleCounter -= samplesPerFrame;
            processTempoFrame();
        }
    }

    /**
     * Advance by multiple samples at once. More efficient than calling advance(1) repeatedly.
     * Processes tempo frames as needed.
     *
     * @param samples Number of samples to advance
     */
    public void advanceBatch(int samples) {
        sampleCounter += samples;
        while (sampleCounter >= samplesPerFrame) {
            sampleCounter -= samplesPerFrame;
            processTempoFrame();
        }
    }

    /**
     * Calculate the number of samples until the next tempo frame boundary.
     * This determines the maximum safe batch size that won't cross a tempo event.
     *
     * @return Number of samples until next tempo frame, or Integer.MAX_VALUE if no tempo
     */
    public int getSamplesUntilNextTempoFrame() {
        if ((tempoWeight == 0 && !ticksEveryFrameWithZeroTempo()) || samplesPerFrame <= 0) {
            return Integer.MAX_VALUE;
        }
        double remaining = samplesPerFrame - sampleCounter;
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.ceil(remaining);
    }

    /**
     * Return the next observable boundary caused by driver state changes that are
     * scheduled from tempo ticks.
     *
     * Tick-scoped chip writes (PSG envelope, FM volume envelope, modulation,
     * track commands, DAC rate changes) all happen inside {@link #tick()}, and
     * the driver's {@link #getSamplesUntilNextTempoFrame()} - 1 cap handles those.
     */
    public int getSamplesUntilNextObservableEvent() {
        int nextEvent = Integer.MAX_VALUE;

        if (fadeState.active) {
            nextEvent = Math.min(nextEvent, getSamplesUntilNextTempoFrame());
        }

        if (sfxMode && maxTicks <= 1) {
            nextEvent = Math.min(nextEvent, getSamplesUntilNextTempoFrame());
        }

        for (Track t : tracks) {
            if (!t.active) {
                continue;
            }

            if (t.duration <= 0) {
                return 0;
            }

            nextEvent = Math.min(nextEvent, samplesUntilTempoTicks(t.duration));

            boolean fillPending = config.isDirect68kDriver() ? t.fillCounter > 0 : t.fill > 0;
            if (fillPending && !t.tieNext && t.type != TrackType.DAC) {
                int fillTicks = config.isDirect68kDriver()
                        ? t.fillCounter : Math.max(0, t.fill + t.duration - t.scaledDuration);
                nextEvent = Math.min(nextEvent, samplesUntilTempoTicks(fillTicks));
            }
        }

        return nextEvent;
    }

    public boolean requiresSampleAccurateFallback() {
        // Fades do not force per-sample rendering: fade volume steps are applied only
        // inside processTempoFrame() (processFade), hybrid chunks never cross a
        // tempo-frame boundary, and getSamplesUntilNextObservableEvent() clamps to
        // the next tempo frame while a fade is active. Proven PCM-identical by
        // TestSmpsFadeHybridParity (fade-out, fade-in, and fade-with-SFX windows).
        return speedMultiplier > 1;
    }

    // Package-private for TestSmpsSequencerTempoMath equivalence proofs.
    int samplesUntilTempoTicks(int ticks) {
        if (ticks <= 0) {
            return 0;
        }
        if ((tempoWeight == 0 && !ticksEveryFrameWithZeroTempo()) || samplesPerFrame <= 0) {
            return Integer.MAX_VALUE;
        }

        double firstRemaining = samplesPerFrame - sampleCounter;
        // Closed form: after the first frame boundary every later frame costs exactly
        // samplesPerFrame, so the total is firstRemaining + (ticks - 1) * samplesPerFrame.
        // That matches the reference loop bit-for-bit only when both doubles are exact
        // integers (then every sum/product below 2^53 is exact regardless of evaluation
        // order, and totals beyond int range saturate identically through the cast).
        // Production rates (e.g. 44100/48000 Hz over 60/50 fps) hit this path; the
        // fractional internal-rate output keeps the loop to preserve exact behaviour,
        // as does a counter left above the frame size by a PAL->NTSC region switch.
        if (firstRemaining > 0.0
                && samplesPerFrame == Math.floor(samplesPerFrame)
                && sampleCounter == Math.floor(sampleCounter)) {
            return (int) Math.ceil(firstRemaining + (ticks - 1) * samplesPerFrame);
        }

        double counter = sampleCounter;
        double total = 0.0;
        for (int i = 0; i < ticks; i++) {
            double remaining = samplesPerFrame - counter;
            if (remaining <= 0.0) {
                remaining = samplesPerFrame;
            }
            total += remaining;
            counter += remaining;
            while (counter >= samplesPerFrame) {
                counter -= samplesPerFrame;
            }
        }
        return (int) Math.ceil(total);
    }

    private boolean ticksEveryFrameWithZeroTempo() {
        return config.getTempoMode() == SmpsSequencerConfig.TempoMode.OVERFLOW;
    }

    private void tick() {
        tick(false);
    }

    private void tick(boolean finishSfxTempoFrame) {
        SmpsDriverServiceObserver.ServiceEvent service =
                host.beginSequencerService(this,
                        SmpsDriverServiceObserver.ServiceKind.SEQUENCER_TICK);
        if (slotWalkPassOpen) {
            // The driver is walking the fixed SFX track slots across every live
            // SFX program; this sequencer's own tracks are run from there, in
            // slot order, and this pass's tail is deferred to
            // finishSfxSlotWalkPass().
            slotWalkService = service;
            slotWalkFinishSfxTempoFrame = finishSfxTempoFrame;
            slotWalkTracksPending = true;
            return;
        }
        tickTracks();
        if (finishSfxTempoFrame) {
            finishSfxTempoFrame();
        }
        // A completed track returns its channel during this driver service,
        // before later fixed-RAM slots run next frame. A wholly completed
        // sequencer stays owned until completion cleanup.
        host.reconcileInactiveSfxTracks(this);
        host.endSequencerService(service);
    }

    /**
     * Opens a driver-coordinated SFX slot-walk pass and runs this sequencer's
     * per-frame bookkeeping without walking its tracks.
     *
     * <p>S1 {@code UpdateMusic} has no per-sound SFX service: it walks a fixed
     * array of SFX track slots -- FM3..FM5 (s1.sounddriver.asm:222-231) then
     * PSG1..PSG3 (:233-241) -- so when two sounds are live their tracks
     * interleave by channel, not by which sound started first. The driver
     * therefore drives the walk itself; see {@link
     * com.openggf.audio.smps.SmpsSequencerConfig.SfxTrackWalkMode}.
     *
     * @return the tracks this sequencer contributes to the walk, in slot order,
     *         or an empty list when this frame runs no track walk
     */
    public List<Track> beginSfxSlotWalkPass() {
        slotWalkPassOpen = true;
        slotWalkTracksPending = false;
        slotWalkService = null;
        serviceOuterFrame();
        return slotWalkTracksPending ? tracks : List.of();
    }

    /** Runs one track of a driver-coordinated SFX slot-walk pass. */
    public void tickSfxSlotWalkTrack(Track track) {
        if (!slotWalkTracksPending) {
            return;
        }
        tickTrack(track);
        // cfStopTrack restores the music voice from inside the finishing
        // track's own slot service (s1.sounddriver.asm:2489-2563), so the
        // channel returns to music before the walk reaches a later slot --
        // not after the whole pass, and not deferred to completion cleanup
        // when the finishing track was the sound's last one.
        host.reconcileFinishedSfxSlot(this);
    }

    /** Closes a driver-coordinated SFX slot-walk pass and runs its tail. */
    public void finishSfxSlotWalkPass() {
        slotWalkPassOpen = false;
        if (!slotWalkTracksPending) {
            return;
        }
        slotWalkTracksPending = false;
        if (slotWalkFinishSfxTempoFrame) {
            finishSfxTempoFrame();
        }
        host.reconcileInactiveSfxTracks(this);
        host.endSequencerService(slotWalkService);
        slotWalkService = null;
    }

    /**
     * The fixed SFX RAM slot this track occupies, for the driver's walk.
     *
     * <p>Special-SFX tracks live in their own RAM block straight after the SFX
     * block (`v_spcsfx_track_ram`, s1.sounddriver.ram.asm:98-105), and
     * {@code UpdateMusic} walks them after every normal SFX slot: SFX FM3..FM5
     * (s1.sounddriver.asm:243-250) then SFX PSG1..PSG3 (:252-256), then the two
     * special slots, FM4 followed by PSG3 (:258-268). So a normal SFX admitted
     * later is still serviced before a special SFX already playing, whatever
     * channels they hold.
     */
    public static int sfxSlotWalkOrder(Track track, boolean specialSfx) {
        return sfxTrackRamOrder(track)
                + (specialSfx ? SPECIAL_SFX_SLOT_WALK_BASE : 0);
    }

    /** Slot-walk offset of the special-SFX RAM block past every SFX slot. */
    private static final int SPECIAL_SFX_SLOT_WALK_BASE = 32;

    private void finishSfxTempoFrame() {
        maxTicks--;
        if (maxTicks <= 0) {
            for (Track track : tracks) {
                track.active = false;
                stopNote(track);
            }
        }
    }

    private void tickTracks() {
        for (Track t : tracks) {
            tickTrack(t);
        }
    }

    private void tickTrack(Track t) {
        {
            if (!t.active)
                return;
            // Note: In SMPS, overridden tracks continue to process (tick) in the
            // background,
            // but their output is blocked (or overwritten) by the SFX.
            // SmpsDriver blocks the writes if locked.

            if (t.duration > 0) {
                t.duration--;

                if (t.duration > 0 && t.type == TrackType.FM && t.resting
                        && config.isFmNoteGoingReturnsAtRest()) {
                    // zUpdateFMorPSGTrack's .note_going opens with
                    // bit 4,(ix+zTrack.PlaybackControl) / ret nz
                    // (Sound/Z80 Sound Driver.asm:781-783). That branch is
                    // only reached when the duration timer did NOT expire; an
                    // expired timer goes to zGetNextNote instead, which is why
                    // this is gated on the post-decrement duration. So a
                    // resting FM track whose note is still running advances
                    // nothing else: no volume envelope, no note fill, no
                    // frequency update and no modulation step.
                    // zUpdatePSGTrack has no such test at its matching entry
                    // (:4066-4076).
                    return;
                }


                if (t.duration > 0) {
                    // The note fill belongs to the continuing-note branch,
                    // not to the pass whose duration timer expired. All
                    // three drivers call it from there: S3K from
                    // zUpdateFMorPSGTrack's .note_going (Sound/Z80 Sound
                    // Driver.asm:781-790), S2 from zFMUpdateTrack's
                    // .notegoing (s2.sounddriver.asm:832-834) and S1 from
                    // FMUpdateTrack's .notegoing (s1.sounddriver.asm:358-361).
                    // An expired timer goes to the next stream unit instead.
                    if (!t.tieNext && t.type != TrackType.DAC) {
                        if (config.isDirect68kDriver()) {
                            if (t.fillCounter > 0 && --t.fillCounter == 0) {
                                // S1/S2 NoteTimeoutUpdate tampers with the return address:
                                // after note-off, the rest of this track update is skipped.
                                stopNote(t);
                                t.resting = true;
                                return;
                            }
                        } else if (config.getNoteFillTail()
                                == SmpsSequencerConfig.NoteFillTail.S3K_SPLIT) {
                            if (t.fillCounter > 0 && --t.fillCounter == 0) {
                            // S3K's note fill is the same per-pass countdown,
                            // armed unscaled from NoteFillMaster at note start
                            // (Sound/Z80 Sound Driver.asm:1067-1068), but its two
                            // tails differ by track type and both are tail jumps,
                            // so the rest of the pass is skipped either way.
                            // zUpdatePSGTrack's `jp z, zRestTrack` only sets the
                            // rest bit and writes nothing (:4070-4074,
                            // :2160-2164), while zUpdateFMorPSGTrack's
                            // `jp z, zKeyOffIfActive` keys the channel off
                            // (:786-790, :2148-2152).
                                if (t.type == TrackType.PSG) {
                                    restTrack(t);
                                } else {
                                    stopNote(t);
                                }
                                return;
                            }
                        } else if (t.fill > 0 && (t.scaledDuration - t.duration) >= t.fill) {
                            // S2's zNoteFillUpdate is a per-pass countdown that
                            // rests the track and sends a note off
                            // (s2.sounddriver.asm:1153-1163). The engine models it
                            // as an elapsed comparison instead. That difference is
                            // unverified against the S2 oracle and is left alone.
                            stopNote(t);
                        }
                    }
                    // S1/S2 run the PSG volume effects before modulation and
                    // the frequency (s1.sounddriver.asm:1822-1827,
                    // s2.sounddriver.asm:1134-1138). S3K's zUpdatePSGTrack
                    // latches the frequency first and reads the volume
                    // envelope afterwards (skdisasm Sound/Z80 Sound
                    // Driver.asm:4077-4110). The FM side is volume-envelope
                    // first in all three (S3K :781-790).
                    boolean psgFrequencyFirst = t.type == TrackType.PSG
                            && config.getPsgNoteGoingOrder()
                                    == SmpsSequencerConfig.PsgNoteGoingOrder.FREQUENCY_THEN_VOLUME;
                    if (t.type == TrackType.PSG) {
                        if (!psgFrequencyFirst) {
                            processPsgEnvelope(t);
                        }
                    } else if (t.type == TrackType.FM) {
                        processFmVolEnvelope(t);
                    }
                    if (t.type == TrackType.FM || t.type == TrackType.PSG) {
                        // S3K's zDoModulation only computes the frequency; the
                        // fall-through to zFMSendFreq (Sound/Z80 Sound
                        // Driver.asm:791-799) and the PSG latch (:4077-4090)
                        // put it on the bus on every pass of a sounding track.
                        // S1/S2's DoModulation discards the return address, so
                        // there the send happens only when modulation ran and
                        // moved the frequency.
                        boolean resendEveryPass = config.getNoteGoingFreqSend()
                                == SmpsSequencerConfig.NoteGoingFreqSend.EVERY_PASS;
                        int freqDelta = t.modEnabled
                                ? applyModulation(t, !resendEveryPass)
                                : 0;
                        // S3K's FM .note_going returns on the rest bit before
                        // it reaches the send (Sound/Z80 Sound
                        // Driver.asm:781-783), but zUpdatePSGTrack's rest check
                        // sits after the frequency latch and gates only the
                        // volume (:4085-4090, :4098-4101), so a resting PSG
                        // track still puts its frequency on the bus.
                        if (resendEveryPass && (t.type == TrackType.PSG || !t.resting)) {
                            writeTrackFrequency(t, freqDelta, false);
                        }
                    }
                    if (psgFrequencyFirst) {
                        if (t.type == TrackType.PSG && t.overridden) {
                            // zUpdatePSGTrack tests the SFX-overriding bit
                            // right after zDoModulation and returns on it,
                            // before both the frequency latch and zDoVolEnv
                            // (Sound/Z80 Sound Driver.asm:4079-4083). So a PSG
                            // track an SFX has taken stops advancing its
                            // volume envelope entirely, rather than running on
                            // underneath and coming back somewhere else in it.
                            return;
                        }
                        processPsgEnvelope(t);
                    }
                    return;
                }
            }

            if (t.duration == 0 && t.type != TrackType.DAC) {
                // Every driver clears its do-not-attack bit before it reads the
                // next stream unit, so a prevent-attack coordination flag read
                // during that same unit survives into the note it guards and
                // stays set for the note's whole duration. S1/S2 clear
                // PlaybackControl bit 4 when the duration expires
                // (s2.sounddriver.asm:775-819), and their DAC path has no
                // matching res 4 (:821-835, :1123-1138). S3K clears its bit 1
                // at the top of zGetNextNote, ahead of the coordination-flag
                // loop (skdisasm Sound/Z80 Sound Driver.asm:905-915), and
                // cfPreventAttack (0E7h) sets it from inside that loop
                // (:3212-3221).
                t.tieNext = false;
            }
            while (t.duration == 0 && t.active) {
                if (t.pos >= programView.dataLength()) {
                    // Running off the end of the data has no ROM counterpart,
                    // since every stream ends with a track-end flag. Keep the
                    // engine's safety stop here rather than after the loop, so
                    // it cannot double up with the stop a track-end flag has
                    // already performed.
                    t.active = false;
                    stopNote(t);
                    break;
                }

                int cmd = programView.dataByteAt(t.pos) & 0xFF;

                if (cmd >= 0xE0) {
                    t.pos++;
                    handleFlag(t, cmd);
                    // Re-check bounds after handleFlag as it may have modified t.pos
                    if (t.pos < 0 || t.pos >= programView.dataLength()) {
                        if (t.active) { // Only stop if still supposedly active
                            t.active = false;
                        }
                        break;
                    }
                } else if (t.rawFreqMode) {
                    if (t.pos + 1 >= programView.dataLength()) {
                        t.active = false;
                        break;
                    }
                    int freq = (programView.dataByteAt(t.pos) & 0xFF)
                            | ((programView.dataByteAt(t.pos + 1) & 0xFF) << 8);
                    t.pos += 2;
                    if (freq != 0) {
                        freq = (freq + t.keyOffset) & 0xFFFF;
                    }
                    t.rawFrequency = freq;
                    t.note = (freq == 0) ? 0x80 : 0x81;
                    if (t.pos < programView.dataLength()) {
                        int next = programView.dataByteAt(t.pos) & 0xFF;
                        if (next < 0x80) {
                            setDuration(t, next);
                            t.pos++;
                        } else {
                            reuseDuration(t);
                        }
                    } else {
                        reuseDuration(t);
                    }
                    playRawFrequency(t);
                    break;
                } else if (cmd >= 0x80) {
                    t.pos++;
                    t.note = cmd;
                    if (t.pos < programView.dataLength()) {
                        int next = programView.dataByteAt(t.pos) & 0xFF;
                        if (next < 0x80) {
                            setDuration(t, next);
                            t.pos++;
                        } else {
                            reuseDuration(t);
                        }
                    }
                    playNote(t);
                    break;
                } else {
                    t.pos++;
                    // 0x00 is not a valid SMPS command/note in standard mode.
                    if (cmd == 0x00) {
                        t.active = false;
                        break;
                    }
                    setDuration(t, cmd);
                    playNote(t, false);
                    break;
                }
            }

            if (t.active && t.type == TrackType.PSG && !t.resting && t.tieNext
                    && config.getPsgEnvRestCmd()
                            == SmpsSequencerConfig.PsgEnvRestCmd.Z80_81_AND_83) {
                // zUpdatePSGTrack's note-start entry falls through to the same
                // .skip_fill block the note-going entry uses, so a new note's
                // own pass reads the volume envelope too; only a rest note
                // returns first, on the bit 4 test after zGetNextNote
                // (Sound/Z80 Sound Driver.asm:4059-4090). This matters when
                // zFinishTrackUpdate left VolEnv alone, which it does exactly
                // when the do-not-attack bit is set (:1061-1068): the envelope
                // is still parked on whatever command it stopped at, and a
                // parked 81h or 83h re-rests the track on the same pass that
                // zGetNextNote cleared bit 4. When the bit is clear the ROM
                // resets VolEnv to 0 and reads the first byte, which is the
                // step playNote already applies.
                processPsgEnvelope(t);
            }

            if (!t.active && !config.isTrackEndFlagOwnsTheStop()) {
                // S1/S2 keep this blanket stop: their handlers do not all stop
                // the note themselves and removing it costs the S2
                // driver-state oracle a write at tick 207. S3K's cfStopTrack
                // keys off exactly once on its own (Sound/Z80 Sound
                // Driver.asm:3040-3046), so a second stop here would put a
                // duplicate key-off on the bus.
                stopNote(t);
            }
        }
    }

    /**
     * Runs at the ROM's dispatch point: after the fade step and before the
     * track walk. S1's {@code UpdateMusic} steps the fade
     * (s1.sounddriver.asm:179-186) and only then cycles the sound queue and
     * calls {@code PlaySoundID} (:197-202), before walking any track (:205
     * onward). A request that arms a fade in one invocation is therefore not
     * stepped until the next, because that invocation's fade step has already
     * gone by. Set by the parity host so a recorded driver command lands where
     * the ROM issues it.
     */
    private Runnable dispatchPoint;

    public void setDispatchPointListener(Runnable listener) {
        this.dispatchPoint = listener;
    }

    private void runDispatchPoint() {
        if (dispatchPoint != null) {
            dispatchPoint.run();
        }
    }

    private void processTempoFrame() {
        if (config.getTempoMode() == SmpsSequencerConfig.TempoMode.OVERFLOW && !sfxMode) {
            // S3K music frame: fades advance inside each zUpdateMusic
            // (zDoMusicFadeOut runs per music update, D:2331-2385), so the fade
            // step lives inside musicUpdateOverflow(), not at the frame boundary.
            musicUpdateOverflow();
            return;
        }
        processObservedFadeStep();

        if (tempoWeight == 0 && config.getTempoMode() == SmpsSequencerConfig.TempoMode.OVERFLOW2) {
            return;
        }
        if (config.getTempoMode() == SmpsSequencerConfig.TempoMode.TIMEOUT) {
            // S1 UpdateMusic SD:174-176: every pass decrements v_main_tempo_timeout;
            // on zero, TempoWait (SD:1549-1561) reloads it from v_main_tempo and adds
            // 1 to the DurationTimeout of all ten music slots WITHOUT testing the
            // playing bit (the loop at SD:1551-1558 walks every slot). The track walk
            // always runs; S1 has no skip frame. SFX and special tracks are never
            // held (they live in their own sequencers, sfxMode == true there).
            if (!sfxMode) {
                tempoAccumulator--;
                if (tempoAccumulator <= 0) {
                    tempoAccumulator = tempoWeight;
                    for (Track t : tracks) {
                        t.duration++;
                    }
                }
                // The ROM's dispatch point: past the fade step, before any
                // track is walked (SD:179-202).
                runDispatchPoint();
            }
            tick(sfxMode);
        } else if (config.getTempoMode() == SmpsSequencerConfig.TempoMode.OVERFLOW2) {
            if (sfxMode) {
                // S2 SFX durations decrement every frame via the SFX pass
                // (sd:821-835); TempoWait touches only the ten music slots.
                tick(true);
            } else {
                musicUpdateOverflow2();
            }
        } else {
            // OVERFLOW (S3K) SFX pass: zUpdateSFXTracks services SFX every frame
            // regardless of music tempo (D:727).
            tick(true);
        }
    }

    /**
     * Runs the shared tail after an S3K SFX or music pass (D:743-758).
     * Expiry reloads {@code zTempoSpeedup}, runs one extra music update, then
     * consumes that extra update's own tail decrement.
     */
    public void serviceS3kSpeedupTail() {
        if (speedMultiplier <= 1) {
            return;
        }
        if (speedupTimeout <= 0) {
            speedupTimeout = speedMultiplier;
            processTempoFrame();
        }
        speedupTimeout--;
    }

    /**
     * One S2 {@code zUpdateMusic} (sd:545-551, FixDriverBugs off): {@code TempoWait}
     * (sd:596-619) runs first — {@code TempoTimeout += CurrentTempo}; on carry the
     * frame is normal; on NO carry every music slot's {@code DurationTimeout} is
     * incremented (sd:609-616, no playing-bit test) — and the track walk then always
     * runs, so envelopes, modulation, note fill and the per-frame frequency/volume
     * writes all continue on a delay frame; only note expiry is pushed out.
     */
    private void musicUpdateOverflow2() {
        tempoAccumulator += tempoWeight;
        if (tempoAccumulator >= tempoModBase) {
            tempoAccumulator -= tempoModBase; // carry → normal frame
        } else {
            // No carry → delay: the pre-increment cancels this update's decrement.
            for (Track t : tracks) {
                t.duration++;
            }
        }
        tick();
    }

    /**
     * One S3K {@code zUpdateMusic}: fades advance once per music update
     * (zDoMusicFadeOut, D:2331-2385), then {@code TempoWait} (D:2607-2621) —
     * {@code zTempoAccumulator += zCurrentTempo}; on 8-bit carry every music slot's
     * {@code DurationTimeout} byte is incremented (no playing-bit test) — then the
     * track walk always runs; envelopes, note fill, modulation and the per-frame
     * frequency writes continue on a delay frame; only note expiry is pushed out.
     */
    private void musicUpdateOverflow() {
        processObservedFadeStep();
        tempoAccumulator += tempoWeight;
        if (tempoAccumulator >= tempoModBase) {
            tempoAccumulator -= tempoModBase;
            // Carry → delay: the pre-increment cancels this update's decrement.
            for (Track t : tracks) {
                t.duration++;
            }
        }
        tick();
    }

    /** Set when the driver ran this service's fade step ahead of the queue. */
    private boolean fadeStepConsumedThisService;

    /**
     * Runs this service's fade step before the sound queue is consumed.
     *
     * <p>{@code zUpdateMusic} calls {@code TempoWait}, {@code zDoMusicFadeOut}
     * and {@code zDoMusicFadeIn} and only then reaches {@code zFillSoundQueue}
     * (skdisasm Sound/Z80 Sound Driver.asm:659-701), so the song playing when a
     * request arrives gets its fade step first, and a music change consumed by
     * that service replaces the song afterwards. Running the step inside the
     * song's own walk instead loses it whenever the request swaps the song out.
     */
    public void serviceFadeStepAheadOfRequest() {
        if (!config.isDriverOwnedFadeDelay()) {
            return;
        }
        // Only a step that actually ran claims this service's step. A fade
        // armed by the request that follows must still be left alone by its
        // own arming service, which processFade owns through
        // fadeArmedOutsideService.
        if (!fadeState.active) {
            return;
        }
        processObservedFadeStep();
        fadeStepConsumedThisService = true;
    }

    private void processObservedFadeStep() {
        if (fadeStepConsumedThisService) {
            fadeStepConsumedThisService = false;
            return;
        }
        if (!fadeState.active) {
            return;
        }
        SmpsDriverServiceObserver.ServiceEvent service =
                host.beginSequencerService(this,
                        SmpsDriverServiceObserver.ServiceKind.FADE_STEP);
        processFade();
        host.endSequencerService(service);
    }

    /**
     * Set when a fade is armed from outside a service, which is where a
     * request-driven fade is armed. In the ROM the request never reaches the
     * driver until after that service's fade handler has already run:
     * zUpdateEverything calls zDoMusicFadeOut before zUpdateMusic loads
     * zMusicNumber for zFillSoundQueue (Sound/Z80 Sound Driver.asm:653-701,
     * :2628-2643). So the arming service does not advance the fade it armed.
     * A fade armed by a coordination flag needs no such flag, because that
     * happens inside the track walk, which already runs after the fade step.
     */
    private boolean fadeArmedOutsideService;

    /**
     * The running fade delay. S3K keeps it on the driver, so a fade armed with
     * no song loaded still records it; S1 and S2 keep it on the song
     * (skdisasm Sound/Z80 Sound Driver.asm:2306-2312 against
     * s1.sounddriver.asm:1363 and s2.sounddriver.asm:2425-2429).
     */
    /**
     * The fade's remaining steps. S3K keeps this on the driver as
     * {@code zFadeOutTimeout} / {@code zFadeInTimeout}, which is what its
     * steppers test to decide a fade is running at all (Sound/Z80 Sound
     * Driver.asm:2331-2335, :2395-2396); S1 and S2 keep it with the song.
     */
    private int fadeSteps() {
        return config.isDriverOwnedFadeDelay()
                ? host.fadeStepCounter(fadeState.fadeOut) : fadeState.steps;
    }

    private void setFadeSteps(int value) {
        if (config.isDriverOwnedFadeDelay()) {
            host.setFadeStepCounter(fadeState.fadeOut, value);
        }
        fadeState.steps = value;
    }

    private int fadeDelayCounter() {
        if (!config.isDriverOwnedFadeDelay()) {
            return fadeState.delayCounter;
        }
        return fadeState.fadeOut ? host.fadeDelayTimeout() : host.fadeDelay();
    }

    private void setFadeDelayCounter(int value) {
        if (config.isDriverOwnedFadeDelay()) {
            if (fadeState.fadeOut) {
                host.setFadeDelayTimeout(value);
            } else {
                host.setFadeDelay(value);
            }
        }
        fadeState.delayCounter = value;
    }

    /**
     * The reload source for the counter above.
     *
     * <p>The two steppers use the driver's pair in opposite roles, which is
     * why these are direction-aware rather than fixed. {@code zDoMusicFadeOut}
     * decrements {@code zFadeDelayTimeout} and reloads it from
     * {@code zFadeDelay} (Sound/Z80 Sound Driver.asm:2337-2346);
     * {@code zDoMusicFadeIn} decrements {@code zFadeDelay} and reloads it from
     * {@code zFadeDelayTimeout} (:2405-2414). Both arming routines write the
     * same value to both halves, so the swap only shows once a fade runs.
     */
    private int fadeDelayReload() {
        if (!config.isDriverOwnedFadeDelay()) {
            return fadeState.delayInit;
        }
        return fadeState.fadeOut ? host.fadeDelay() : host.fadeDelayTimeout();
    }

    private void setFadeDelayReload(int value) {
        if (config.isDriverOwnedFadeDelay()) {
            if (fadeState.fadeOut) {
                host.setFadeDelay(value);
            } else {
                host.setFadeDelayTimeout(value);
            }
        }
        fadeState.delayInit = value;
    }

    private void processFade() {
        if (fadeArmedOutsideService) {
            fadeArmedOutsideService = false;
            return;
        }
        // ROM: Check if fade counter is already 0 BEFORE processing
        // This happens after all steps have been applied
        if (fadeSteps() == 0) {
            if (fadeState.fadeOut) {
                // Stop all tracks
                for (Track t : tracks) {
                    t.active = false;
                    stopNote(t);
                }
            } else {
                boolean releasePsg = config.getFadeInRestore()
                        == SmpsSequencerConfig.FadeInRestore.OVERRIDE_PSG;
                // Fade In complete - unmute DAC tracks
                for (Track t : tracks) {
                    if (t.type == TrackType.DAC) {
                        t.dacMuted = false;
                    }
                    if (releasePsg
                            && (t.type == TrackType.PSG
                                    || t.type == TrackType.DAC)) {
                        // zDoMusicFadeIn's tail clears the SFX-overriding bit
                        // on every PSG track and on FM6/DAC once the timeout
                        // reaches zero (Sound/Z80 Sound Driver.asm:2440-2452).
                        // That bit is how the restore muted them, so without
                        // this they never come back and the song plays on with
                        // no PSG at all.
                        t.overridden = false;
                    }
                }
                // Notify listener that fade-in is complete (e.g., to unblock SFX)
                Runnable callback = onFadeComplete;
                if (callback != null) {
                    callback.run();
                }
            }
            fadeState.active = false;
            return;
        }

        // ROM: Check delay counter, decrement and return if not yet 0
        if (config.getFadeDelayCadence()
                == SmpsSequencerConfig.FadeDelayCadence.DECREMENT_THEN_TEST) {
            // zDoMusicFadeOut: dec a / jr z, .timer_expired, so an armed delay
            // of six steps on the sixth service, not the seventh
            // (Sound/Z80 Sound Driver.asm:2337-2343).
            setFadeDelayCounter(fadeDelayCounter() - 1);
            if (fadeDelayCounter() != 0) {
                return;
            }
        } else if (fadeDelayCounter() > 0) {
            // zUpdateFadeout reads the delay first and steps only when it is
            // already zero (s2.sounddriver.asm:1686-1697).
            setFadeDelayCounter(fadeDelayCounter() - 1);
            return;
        }

        // ROM: Decrement fade counter and apply volume change
        setFadeSteps(fadeSteps() - 1);
        setFadeDelayCounter(fadeDelayReload());

        if (fadeState.fadeOut && config.isDriverOwnedFadeDelay() && fadeSteps() == 0
                && host.fadeOutCompletesWithGlobalStop()) {
            // zDoMusicFadeOut jumps to zStopAllSound before the terminal TL
            // loop (Z80 driver:2347-2362). The host applies that shared stop
            // after this step; do not emit another volume or note update here.
            fadeState.active = false;
            for (Track track : tracks) {
                track.active = false;
            }
            return;
        }

        int dir = fadeState.fadeOut ? 1 : -1;

        for (Track t : tracks) {
            if (!t.active)
                continue;
            // Skip DAC tracks - they don't have volume control
            if (t.type == TrackType.DAC)
                continue;

            if (t.type == TrackType.PSG && config.getFadeInRestore()
                    == SmpsSequencerConfig.FadeInRestore.OVERRIDE_PSG) {
                // Neither S3K stepper walks the PSG tracks. zDoMusicFadeOut
                // loops the tracks before PSG1 and zDoMusicFadeIn loops FM1
                // onwards (Sound/Z80 Sound Driver.asm:2364, :2416-2430), so a
                // PSG track's volume never moves with the fade in this driver;
                // the overriding bit is what silences it.
                continue;
            }
            int add = (t.type == TrackType.PSG) ? fadeState.addPsg : fadeState.addFm;
            int change = add * dir;

            int prevOffset = t.volumeOffset;
            t.volumeOffset += change;
            if (fadeState.fadeOut) {
                // SMPSPlay smps.c:3467-3476 - clamp on signed overflow (bit-7 toggle)
                if ((t.volumeOffset & 0x80) != 0 && (prevOffset & 0x80) == 0) {
                    t.volumeOffset = 0x7F;
                }
            }
            refreshVolume(t);
        }
    }

    // handleFlag and other private methods...
    private void handleFlag(Track t, int cmd) {
        // Delegate to game-specific handler first (e.g., S3K coord flags)
        CoordFlagHandler handler = config.getCoordFlagHandler();
        if (handler != null && handler.handleFlag(this, t, cmd)) {
            return;
        }

        switch (cmd) {
            case 0xF2: // Stop
                t.active = false;
                // tickTrack() owns the one terminal note-off after command
                // parsing. Calling it here too emitted a duplicate write.
                break;
            case 0xE3: // Return
                handleReturn(t);
                break;
            case 0xF6: // Jump
                handleJump(t);
                break;
            case 0xF7: // Loop
                handleLoop(t);
                break;
            case 0xF8: // Call
                handleCall(t);
                break;
            case 0xF9: // SND_OFF
                handleSndOff(t);
                break;
            case 0xF0: // Modulation
                handleModulation(t);
                break;
            case 0xF1: // Modulation on
                t.customModEnabled = true;
                t.modEnabled = true;
                break;
            case 0xE0: // Pan
                setPanAmsFms(t);
                break;
            case 0xE1: // Detune
                setDetune(t);
                break;
            case 0xE2: // Set Communication (E2 xx)
                if (t.pos < programView.dataLength()) {
                    commData = programView.dataByteAt(t.pos++) & 0xFF;
                }
                break;
            case 0xE4: // Fade in (Stop Track / Fade In)
                handleFadeIn(t);
                break;
            case 0xFD: // Custom Fade Out command for testing/internal use
                handleFadeOut(t);
                break;
            case 0xE5: // Tick multiplier
                setTrackDividingTiming(t);
                break;
            case 0xE6: // Volume
                setVolumeOffset(t);
                break;
            case 0xE7: // Tie next
                t.tieNext = true;
                break;
            case 0xE8: // Note fill
                setFill(t);
                break;
            case 0xE9: // Key displacement
                setKeyOffset(t);
                break;
            case 0xEC: // PSG volume
                setPsgVolume(t);
                break;
            case 0xF3: // PSG Noise
                setPsgNoise(t);
                break;
            case 0xF4: // Modulation off
                clearModulation(t);
                break;
            case 0xF5: // PSG instrument
                if (t.pos < programView.dataLength()) {
                    int insId = programView.dataByteAt(t.pos++) & 0xFF;
                    t.instrumentId = insId;
                    loadPsgEnvelope(t, insId);
                }
                break;
            case 0xEF:
                // Set Voice
                if (t.pos < programView.dataLength()) {
                    int voiceId = programView.dataByteAt(t.pos++) & 0xFF;
                    loadVoice(t, voiceId);
                }
                break;
            case 0xEA:
                // Set main tempo
                if (t.pos < programView.dataLength()) {
                    normalTempo = programView.dataByteAt(t.pos++) & 0xFF;
                    calculateTempo();
                    // S1 cfSetTempo (SD:2256-2258) writes v_main_tempo AND resets
                    // the countdown; the Z80 drivers' cfSetTempo replaces
                    // CurrentTempo only — S2 sd:3207-3209 and S3K D:3861-3863 do
                    // not touch the accumulator (CD CAD-03), so the accumulated
                    // phase is kept and the new rate takes effect at the next
                    // TempoWait.
                    if (config.getTempoMode() == SmpsSequencerConfig.TempoMode.TIMEOUT) {
                        tempoAccumulator = tempoWeight;
                    }
                }
                break;
            case 0xEB:
                // Set dividing timing
                if (t.pos < programView.dataLength()) {
                    int newDividingTiming = programView.dataByteAt(t.pos++) & 0xFF;
                    updateDividingTiming(newDividingTiming);
                }
                break;
            default:
                // Check for game-specific TRK_END flags (e.g., S1 0xEE = stop track)
                if (!config.getExtraTrkEndFlags().isEmpty() && config.getExtraTrkEndFlags().contains(cmd)) {
                    t.active = false;
                    break;
                }
                int params = flagParamLength(cmd);
                int advance = Math.min(
                        params, programView.dataLength() - t.pos);
                t.pos += advance;
                break;
        }
    }

    // Pre-computed flag parameter length table for SMPS commands 0xE0-0xFF
    // Lookup table is faster than switch statement in hot path
    // Index = cmd - 0xE0 (range 0-31)
    private static final byte[] FLAG_PARAM_LENGTH = new byte[32];
    static {
        // 1-parameter commands
        FLAG_PARAM_LENGTH[0xE0 - 0xE0] = 1; // Pan
        FLAG_PARAM_LENGTH[0xE1 - 0xE0] = 1; // Detune
        FLAG_PARAM_LENGTH[0xE2 - 0xE0] = 1; // Set Communication
        FLAG_PARAM_LENGTH[0xE5 - 0xE0] = 1; // Tick multiplier
        FLAG_PARAM_LENGTH[0xE6 - 0xE0] = 1; // Volume
        FLAG_PARAM_LENGTH[0xE8 - 0xE0] = 1; // Note fill
        FLAG_PARAM_LENGTH[0xE9 - 0xE0] = 1; // Key displacement
        FLAG_PARAM_LENGTH[0xEA - 0xE0] = 1; // Set main tempo
        FLAG_PARAM_LENGTH[0xEB - 0xE0] = 1; // Set dividing timing
        FLAG_PARAM_LENGTH[0xEC - 0xE0] = 1; // PSG volume
        FLAG_PARAM_LENGTH[0xED - 0xE0] = 1; // (unused but keep for safety)
        FLAG_PARAM_LENGTH[0xEF - 0xE0] = 1; // Set Voice
        FLAG_PARAM_LENGTH[0xF3 - 0xE0] = 1; // PSG Noise
        FLAG_PARAM_LENGTH[0xF5 - 0xE0] = 1; // PSG instrument

        // 2-parameter commands
        FLAG_PARAM_LENGTH[0xF6 - 0xE0] = 2; // Jump
        FLAG_PARAM_LENGTH[0xF8 - 0xE0] = 2; // Call
        FLAG_PARAM_LENGTH[0xFD - 0xE0] = 2; // Custom Fade Out

        // 4-parameter commands
        FLAG_PARAM_LENGTH[0xF0 - 0xE0] = 4; // Modulation
        FLAG_PARAM_LENGTH[0xF7 - 0xE0] = 4; // Loop

        // 0-parameter commands (E3, E4, E7, EE, F1, F2, F4, F9) are already 0 from array initialization
    }

    private int flagParamLength(int cmd) {
        if (cmd >= 0xE0 && cmd <= 0xFF) {
            // Delegate to game-specific handler first
            CoordFlagHandler handler = config.getCoordFlagHandler();
            if (handler != null) {
                int len = handler.flagParamLength(cmd);
                if (len >= 0) {
                    return len;
                }
            }
            // Check for game-specific overrides first (e.g., S1 ED/EE differ from S2)
            Map<Integer, Integer> overrides = config.getCoordFlagParamOverrides();
            if (!overrides.isEmpty()) {
                Integer override = overrides.get(cmd);
                if (override != null) {
                    return override;
                }
            }
            return FLAG_PARAM_LENGTH[cmd - 0xE0];
        }
        return 0;
    }

    private void handleFadeOut(Track t) {
        if (t.pos + 2 <= programView.dataLength()) {
            fadeState.steps = programView.dataByteAt(t.pos++) & 0xFF;
            fadeState.delayInit = programView.dataByteAt(t.pos++) & 0xFF;
            fadeState.addFm = 1;
            fadeState.addPsg = 1;
            fadeState.delayCounter = fadeState.delayInit;
            fadeState.active = true;
            fadeState.fadeOut = true;
        }
    }

    @Override
    public int getCommData() {
        return commData;
    }

    private int readPointer(Track t) {
        if (t.pos + 2 > programView.dataLength())
            return 0;
        int ptr = smpsData.read16(t.pos);
        t.pos += 2;
        return ptr;
    }

    /**
     * Read a jump/loop/call pointer from the track data, handling both S1 (PC-relative)
     * and S2 (absolute Z80) addressing modes.
     *
     * <p>S1 68k: In-stream pointers (F6/F7/F8) use {@code dc.w loc-*-1}, meaning the
     * raw 16-bit value is a signed offset from (ptrWordOffset + 1).
     *
     * <p>S2 Z80: Pointers are absolute Z80 addresses, resolved via {@link #relocate}.
     */
    @Override
    public int readJumpPointer(Track t) {
        if (config.isRelativePointers()) {
            // S1 68k: PC-relative from (ptrAddr + 1)
            int ptrOffset = t.pos;
            int raw = smpsData.read16(t.pos);
            t.pos += 2;
            // Interpret as signed 16-bit
            int target = ptrOffset + 1 + (short) raw;
            return (target >= 0 && target < programView.dataLength())
                    ? target : -1;
        } else {
            // S2 Z80: absolute address, needs relocate
            int ptr = readPointer(t);
            return relocate(ptr, smpsData.getZ80StartAddress());
        }
    }

    private void handleJump(Track t) {
        int newPos = readJumpPointer(t);
        if (newPos != -1) {
            t.pos = newPos;
        } else {
            t.active = false;
        }
    }

    private void handleLoop(Track t) {
        if (t.pos + 2 <= programView.dataLength()) {
            int index = programView.dataByteAt(t.pos++) & 0xFF;
            int count = programView.dataByteAt(t.pos++) & 0xFF;
            int newPos = readJumpPointer(t);
            if (newPos == -1) {
                t.active = false;
                return;
            }
            if (count == 0) {
                t.pos = newPos;
                return;
            }
            if (index >= t.loopCounters.length) {
                // Cap at 256 entries (max possible index from a single byte)
                int newSize = Math.min(256, Math.max(t.loopCounters.length * 2, index + 1));
                if (index >= newSize) {
                    // Index exceeds maximum SMPS loop nesting; skip this loop command
                    return;
                }
                int[] newCounters = new int[newSize];
                System.arraycopy(t.loopCounters, 0, newCounters, 0, t.loopCounters.length);
                t.loopCounters = newCounters;
            }

            if (t.loopCounters[index] == 0) {
                t.loopCounters[index] = count;
            }
            if (t.loopCounters[index] > 0) {
                t.loopCounters[index]--;
                if (t.loopCounters[index] > 0) {
                    t.pos = newPos;
                }
            }
        }
    }

    private void handleCall(Track t) {
        int newPos = readJumpPointer(t);
        if (newPos == -1 || t.returnSp >= t.returnStack.length) {
            t.active = false;
            return;
        }
        t.returnStack[t.returnSp++] = t.pos;
        t.pos = newPos;
    }

    private void handleReturn(Track t) {
        if (t.returnSp > 0) {
            t.pos = t.returnStack[--t.returnSp];
        } else {
            t.active = false;
        }
    }

    private void handleSndOff(Track t) {
        // SMPSPlay CF_SND_OFF: Only writes to specific operators' release rates (Op 2
        // and 4).
        // Confirmed via SMPSPlay src/Engine/smps_commands.c that it does NOT write
        // Total Level (TL).
        // It does NOT stop the track (active=false) or explicitly stop the note.

        if (t.type == TrackType.FM) {
            int hwCh = t.channelId;
            int port = (hwCh < 3) ? 0 : 1;
            int ch = (hwCh % 3);

            // Write 0x0F to 0x88 + ch (Op 2) and 0x8C + ch (Op 4)
            // 0x80 register: SL/RR. 0x0F means SL=0, RR=15 (Max Release).
            synth.writeFm(this, port, 0x88 + ch, 0x0F);
            synth.writeFm(this, port, 0x8C + ch, 0x0F);

            // Mark track for instrument refresh on next note to undo SL/RR changes
            t.forceRefresh = true;
        }
    }

    private void handleModulation(Track t) {
        if (t.pos + 4 <= programView.dataLength()) {
            t.modPendingDelayInit = programView.dataByteAt(t.pos++) & 0xFF;
            int rate = programView.dataByteAt(t.pos++) & 0xFF;
            t.modPendingRate = (rate == 0) ? 256 : rate;
            t.modPendingDelta = programView.dataByteAt(t.pos++);
            int steps = programView.dataByteAt(t.pos++) & 0xFF;
            t.modPendingStepsFull = steps;
            // Driver profiles select whether the raw modulation step byte is halved.
            t.modPendingSteps = config.isHalveModSteps() ? steps / 2 : steps;

            t.customModEnabled = true;
            prepareCustomModulation(t);
            t.modEnvId = 0;
            t.modEnvData = null;
            t.modEnvPos = 0;
            t.modEnvMult = 0;
            t.modEnvCache = 0;
            t.modEnvHold = false;
            t.modEnabled = true;
        }
    }

    @Override
    public void clearModulation(Track t) {
        t.customModEnabled = false;
        t.modEnabled = false;
        t.modEnvId = 0;
        t.modEnvData = null;
        t.modEnvPos = 0;
        t.modEnvMult = 0;
        t.modEnvCache = 0;
        t.modEnvHold = false;
        t.modAccumulator = 0;
    }

    private void prepareCustomModulation(Track t) {
        t.modDelayInit = t.modPendingDelayInit;
        t.modRate = t.modPendingRate;
        t.modDelta = t.modPendingDelta;
        t.modSteps = t.modPendingSteps;
        t.modStepsFull = t.modPendingStepsFull;
        t.modDelay = t.modDelayInit;
        t.modRateCounter = t.modRate;
        t.modStepCounter = t.modSteps;
        t.modAccumulator = 0;
        t.modCurrentDelta = t.modDelta;
    }

    private void resetModEnvelopeState(Track t) {
        if (t.modEnvId == 0) {
            return;
        }
        if (t.modEnvData == null) {
            t.modEnvData = copyModEnvelope(programView, t.modEnvId);
        }
        t.modEnvPos = 0;
        t.modEnvMult = 0;
        t.modEnvCache = 0;
        t.modEnvHold = false;
        t.modEnabled = t.customModEnabled || t.modEnvData != null;
    }

    private void setPanAmsFms(Track t) {
        if (t.pos < programView.dataLength()) {
            int val = programView.dataByteAt(t.pos++) & 0xFF;
            t.pan = ((val & 0x80) != 0 ? 0x80 : 0) | ((val & 0x40) != 0 ? 0x40 : 0);
            t.ams = (val >> 4) & 0x3;
            t.fms = val & 0x7;
            applyFmPanAmsFms(t);
        }
    }

    private void setVolumeOffset(Track t) {
        if (t.pos < programView.dataLength()) {
            t.volumeOffset += programView.dataByteAt(t.pos++);
            refreshVolume(t);
        }
    }

    private void setFill(Track t) {
        if (t.pos < programView.dataLength()) {
            t.fill = programView.dataByteAt(t.pos++) & 0xFF;
        }
    }

    private void setKeyOffset(Track t) {
        if (t.pos < programView.dataLength()) {
            t.keyOffset = wrapSignedByte(t.keyOffset
                    + programView.dataByteAt(t.pos++));
        }
    }

    private static int wrapSignedByte(int value) {
        return (byte) value;
    }

    private void setPsgNoise(Track t) {
        if (t.pos < programView.dataLength()) {
            int val = programView.dataByteAt(t.pos++) & 0x0F;
            t.noiseMode = true;
            t.psgNoiseParam = val;
            synth.writePsg(this, 0xE0 | (val & 0x0F));
        }
    }

    private void setPsgVolume(Track t) {
        if (t.pos < programView.dataLength()) {
            t.volumeOffset += programView.dataByteAt(t.pos++);
            // S1 cfChangePSGVolume only mutates track RAM. The subsequent
            // PSGDoVolFX in the same track update owns the one visible write
            // (SD:2276-2279, 1813-1821).
            if (!config.isDirect68kDriver()) {
                refreshVolume(t);
            }
        }
    }

    private void setTempoWeight(int newTempo) {
        normalTempo = newTempo & 0xFF;
        calculateTempo();
    }

    private void setTrackDividingTiming(Track t) {
        if (t.pos < programView.dataLength()) {
            t.dividingTiming = programView.dataByteAt(t.pos++) & 0xFF;
        }
    }

    @Override
    public void updateDividingTiming(int newDividingTiming) {
        dividingTiming = newDividingTiming;
        for (Track track : tracks) {
            track.dividingTiming = newDividingTiming;
        }
    }

    private void setDuration(Track track, int rawDuration) {
        track.rawDuration = rawDuration;
        int scaled = scaleDuration(track, rawDuration);
        track.scaledDuration = scaled;
        track.duration = scaled;
    }

    private void reuseDuration(Track track) {
        if (track.rawDuration == 0) {
            track.rawDuration = 1;
        }
        setDuration(track, track.rawDuration);
    }

    private int scaleDuration(Track track, int rawDuration) {
        int factor = track.dividingTiming;
        int scaled = rawDuration * factor;
        if (scaled == 0) {
            return 65536; // Emulate SMPS wrap-around behavior (0 ticks -> 65536 ticks)
        }
        return scaled;
    }

    private int[] getFmFreqTable() {
        if (config.getVolMode() == SmpsSequencerConfig.VolMode.BIT7) {
            return FNUM_TABLE_Z80;
        }
        return FNUM_TABLE_68K;
    }

    private int[] getPsgFreqTable() {
        if (config.getVolMode() == SmpsSequencerConfig.VolMode.BIT7) {
            return PSG_FREQ_TABLE_Z80_T2;
        }
        return PSG_FREQ_TABLE_68K;
    }

    /**
     * YM2612 register 28h channel-select value for a driver channel slot, the
     * low three bits of the track's VoiceControl byte (bit 2 selects part II).
     */
    private static int keyOnOffChannelSelect(int channelId) {
        return channelId < 3 ? channelId : (channelId % 3) + 4;
    }

    /**
     * The PSG frequency's second byte. S2 {@code zPSGUpdateFreq} shifts the
     * 16-bit frequency right by four and masks to six bits
     * (s2.sounddriver.asm:2827-2843), but S3K {@code zUpdatePSGTrack} ORs the
     * low byte's high nibble with the whole high byte and rotates the result
     * right by four, with no mask, so a frequency above 03FFh keeps bit 6
     * (skdisasm Sound/Z80 Sound Driver.asm:4085-4095).
     */
    private int psgFrequencyHighByte(int reg) {
        if (!config.isPsgFrequencyHighByteNibbleSwap()) {
            return (reg >> 4) & 0x3F;
        }
        int combined = ((reg & 0xF0) | ((reg >> 8) & 0xFF)) & 0xFF;
        return ((combined >> 4) | (combined << 4)) & 0xFF;
    }

    private boolean shouldPreventNoteAttack(Track t) {
        // We model the driver's note-on-prevent bit with tieNext.
        // On SMPS Z80 this is the HOLD bit, and on SMPS 68k this maps to AT-REST.
        return t.tieNext;
    }

    private void resetTrackedFrequency(Track t) {
        if (t.type == TrackType.FM) {
            t.baseFnum = 0;
            t.baseBlock = 0;
        } else if (t.type == TrackType.PSG) {
            // S1 PSGSetFreq and S2 zPSGSetFreq store $FFFF as the invalid
            // frequency sentinel for a rest. The resting status bit prevents
            // this value from reaching the PSG period write path.
            t.baseFnum = 0xFFFF;
        }
    }

    @Override
    public void loadVoice(Track t, int voiceId) {
        if (selectVoice(t, voiceId) && !t.tieNext) {
            // Continuous SFX (cfx_*) loop back through smpsSetvoice while tied.
            // Preserve the existing Z80 HOLD behavior rather than refreshing the
            // live channel mid-sustain.
            refreshInstrument(t);
        }
    }

    private boolean selectVoice(Track t, int voiceId) {
        byte[] voice = copyVoice(programView, voiceId);
        if (voice == null && fallbackVoiceView != null) {
            voice = copyVoice(fallbackVoiceView, voiceId);
        }
        if (voice != null) {
            t.voiceData = voice;
            t.voiceId = voiceId;
            // Clear SSG-EG state: new voice may not use SSG-EG, and the song's
            // coordination flags (FF 05) will re-set it if needed.
            Arrays.fill(t.ssgEg, 0);
            return true;
        }
        return false;
    }

    private static byte[] copyVoice(
            SmpsProgramView source, int voiceId) {
        if (source instanceof AbstractSmpsData data) {
            return data.materializeVoiceForSequencer(voiceId);
        }
        int length = source.voiceLength(voiceId);
        if (length == 0) {
            return null;
        }
        byte[] copy = new byte[length];
        for (int index = 0; index < length; index++) {
            copy[index] = source.voiceByteAt(voiceId, index);
        }
        return copy;
    }

    private static byte[] copyPsgEnvelope(
            SmpsProgramView source, int envelopeId) {
        if (source instanceof AbstractSmpsData data) {
            return data.materializePsgEnvelopeForSequencer(envelopeId);
        }
        int length = source.psgEnvelopeLength(envelopeId);
        if (length == 0) {
            return null;
        }
        byte[] copy = new byte[length];
        for (int index = 0; index < length; index++) {
            copy[index] = source.psgEnvelopeByteAt(envelopeId, index);
        }
        return copy;
    }

    private static byte[] copyModEnvelope(
            SmpsProgramView source, int envelopeId) {
        if (source instanceof AbstractSmpsData data) {
            return data.materializeModEnvelopeForSequencer(envelopeId);
        }
        int length = source.modEnvelopeLength(envelopeId);
        if (length == 0) {
            return null;
        }
        byte[] copy = new byte[length];
        for (int index = 0; index < length; index++) {
            copy[index] = source.modEnvelopeByteAt(envelopeId, index);
        }
        return copy;
    }

    private void playNote(Track t) {
        playNote(t, true);
    }

    /**
     * @param noteByteRead whether this stream unit actually carried a note
     *     byte. A duration-only unit does not: {@code zGetNextNote} cleared
     *     the rest bit on entry (Sound/Z80 Sound Driver.asm:910-911) and a
     *     positive byte goes straight to {@code zStoreDuration} (:917-919),
     *     which touches neither the rest bit nor the saved note. Only a note
     *     byte of 80h reaches {@code zRestTrack}. Re-deriving the rest bit
     *     from the previous unit's note would keep a track resting through a
     *     duration that follows a rest.
     */
    private void playNote(Track t, boolean noteByteRead) {
        boolean preventAttack = shouldPreventNoteAttack(t);
        if (!preventAttack) {
            t.fillCounter = t.fill;
            // Every driver resets the volume envelope index beside the note
            // fill, in the same routine and under the same do-not-attack
            // guard: S3K zFinishTrackUpdate (Sound/Z80 Sound
            // Driver.asm:1055-1069), S2 (s2.sounddriver.asm:948-957) and S1
            // FinishTrackUpdate (s1.sounddriver.asm:436-442), whose own
            // comment notes it happens even on FM tracks. Without it a PSG
            // envelope runs on past the note that should have restarted it,
            // reaches its terminator early, and then parks there: an 83h
            // terminator re-silences the channel on every later pass.
            t.envPos = 0;
            if (config.isNoteResetAliasesModulationState()) {
                // zFinishTrackUpdate clears ModEnvIndex and ModEnvSens for
                // every stream unit it reads, rest or note, whenever the
                // do-not-attack bit is clear (Sound/Z80 Sound
                // Driver.asm:1055-1069). Those are the same bytes as
                // ModulationSpeed (offset 25h) and ModulationValLow (22h)
                // (:76-92), so a track running normal modulation has its
                // speed counter and the low byte of its accumulator zeroed by
                // an unrelated routine. The engine's resetModEnvelopeState
                // below clears them only when a modulation envelope is in
                // use, which is the mod-envelope reading of the same bytes.
                t.modRateCounter = 0;
                t.modAccumulator = (short) (t.modAccumulator & 0xFF00);
            }
            if (t.note != 0x80 || config.isDirect68kDriver()) {
                if (t.customModEnabled) {
                    prepareCustomModulation(t);
                }
                resetModEnvelopeState(t);
            }
        }
        // No driver's DAC path touches the track's rest bit. S3K's
        // zUpdateDACTrack jumps a rest straight to
        // zUpdateDACTrack_GetDuration without setting PlaybackControl bit 4
        // (skdisasm Sound/Z80 Sound Driver.asm:2890-2896), and S1's
        // DACUpdateTrack (s1.sounddriver.asm:277-307) and S2's
        // zDACUpdateTrack (s2.sounddriver.asm:759-790) have no rest test at
        // all. Only FM and PSG tracks rest.
        if (noteByteRead) {
            t.resting = t.type != TrackType.DAC && t.note == 0x80;
        } else {
            t.resting = false;
        }

        // The whole rest branch belongs to a note byte of 80h, never to a
        // duration-only unit that leaves the previous unit's note in place.
        // zGetNextNote sends a positive byte to zStoreDuration (Sound/Z80
        // Sound Driver.asm:917-919), which silences nothing; only a real rest
        // byte reaches zRestTrack.
        if (noteByteRead && t.note == 0x80) {
            if (t.type != TrackType.DAC) {
                stopNote(t);
            }
            if (config.getDelayFreq() == SmpsSequencerConfig.DelayFreq.RESET) {
                resetTrackedFrequency(t);
            }
            if (config.isAdvancePsgEnvelopeOnRest()
                    && t.type == TrackType.PSG && !preventAttack) {
                // S1 PSGUpdateTrack and S2 zPSGUpdateTrack still run their
                // volume-envelope step after parsing a rest. The cursor
                // advances, but the resting bit suppresses the chip write
                // (S2 sd:1123-1131, 1276-1312).
                primePsgRestEnvelope(t);
            }
            return;
        }

        if (t.forceRefresh) {
            refreshInstrument(t);
            t.forceRefresh = false;
        }

        if (t.type == TrackType.DAC) {
            if (config.isDacNoteKeysOffFm6AndRestoresFm3()) {
                // The S3K Z80 driver's DAC track shares FM6, so starting a
                // sample first kills the FM note on that channel and puts FM3
                // back into normal mode: zUpdateDACTrack calls zKeyOffIfActive
                // then zFM3NormalMode before queuing the sample
                // (skdisasm Sound/Z80 Sound Driver.asm:2897-2898).
                // zKeyOffIfActive writes nothing when PlaybackControl bit 1
                // ("do not attack next note") or bit 2 ("SFX overriding this
                // track") is set (:3338-3341); otherwise zKeyOff sends 28h with
                // the track's VoiceControl byte, which zFMDACInitBytes gives as
                // 6 for the DAC/FM6 track (:3348-3358, :1897).
                // zFM3NormalMode is called unconditionally and writes 27h = 0
                // (:2511-2521). S1 DACUpdateTrack
                // (s1.sounddriver.asm:277-331) and S2 zDACUpdateTrack
                // (s2.sounddriver.asm:759-816) make neither call, so this stays
                // off for those games.
                if (!preventAttack && !t.overridden) {
                    synth.writeFm(this, 0, 0x28, keyOnOffChannelSelect(t.channelId));
                }
                synth.writeFm(this, 0, 0x27, 0x00);
            }
            // Skip DAC playback if muted during fade-in
            if (!t.dacMuted) {
                synth.playDac(this, t.note);
            }
            return;
        }

        int baseNoteOffset = (t.type == TrackType.PSG) ? smpsData.getPsgBaseNoteOffset() : smpsData.getBaseNoteOffset();
        int n = t.note - 0x81 + t.keyOffset + baseNoteOffset;
        boolean psgVolumeWrittenByFrequencyTail = false;

        if (t.type == TrackType.FM) {
            // Match SMPSPlay/GetNote FM note indexing behavior.
            int fmNote = n & 0xFF;
            if (baseNoteOffset == 1) {
                fmNote &= 0x7F;
            }
            int octave = fmNote / 12;
            int noteIdx = fmNote % 12;

            int hwCh = t.channelId;
            int port = (hwCh < 3) ? 0 : 1;
            int ch = (hwCh % 3);

            int fnum = getFmFreqTable()[noteIdx];
            int block = octave;

            block &= 7;

            if (noteByteRead) {
                t.baseFnum = fnum;
            } else {
                // zStoreDuration stores no frequency (Sound/Z80 Sound
                // Driver.asm:917-919), so a duration-only unit re-attacks the
                // frequency the track already holds.
                fnum = t.baseFnum;
                block = t.baseBlock;
            }
            t.baseBlock = block;

            int packed = (block << 11) | fnum;
            packed += t.detune;

            if (pitch != 1.0f) {
                int b = (packed >> 11) & 7;
                int f = packed & 0x7FF;
                f = (int) (f * pitch);
                while (f > 0x7FF && b < 7) {
                    f >>= 1;
                    b++;
                }
                packed = (b << 11) | (f & 0x7FF);
            }

            block = (packed >> 11) & 7;
            fnum = packed & 0x7FF;

            int chVal = (port == 0) ? ch : (ch + 4); // YM2612 0x28: bit2 selects upper port

            // SMPSPlay DoNoteOn: skip KEY_OFF and KEY_ON when tieNext (HOLD) is set.
            // This allows smpsNoAttack (E7) to work correctly for both music and SFX.
            if (!preventAttack) {
                // [not in driver] turn DAC off when playing a note on FM6
                if (fm6DacOff && hwCh == 5) {
                    synth.writeFm(this, 0, 0x2B, 0x00);
                }

                synth.writeFm(this, 0, 0x28, chVal); // Key Off before frequency change
            }

            // The S3K HOLD-family path applies its note-start modulation before
            // key-on. S2 calls zFMNoteOn first and zDoModulation afterwards
            // (sd:821-835); S1 does not apply modulation at note start.
            boolean modulationSendsNoteFrequency = t.modEnabled
                    && config.isApplyModOnNote()
                    && config.getNoteOnPrevent()
                            == SmpsSequencerConfig.NoteOnPrevent.HOLD;
            if (!modulationSendsNoteFrequency) {
                // zUpdateFreq and zDoModulation only compute the frequency; the
                // single zFMSendFreq that follows them puts it on the bus
                // (skdisasm Sound/Z80 Sound Driver.asm:776-782, :815-871), so a
                // modulated note must not also send the unmodulated value.
                writeFmFreq(port, ch, fnum, block);
            }
            // S1/S2 FMPrepareNote writes only A4/A0; pan is written by SetVoice
            // or its coordination flag (S2 sd:821-835, 2797-2807).
            if (config.isWriteFmPanOnNote()) {
                applyFmPanAmsFms(t);
            }
            if (modulationSendsNoteFrequency) {
                t.forceModulationWrite = true;
                applyModulation(t);
            }

            // S1/S2 smpsNoAttack suppresses FMNoteOff and the per-note state
            // reset, but FMNoteOn tests only rest/override bits and still keys
            // on after the tied frequency is prepared (S2 sd:2797-2825).
            // Preserve the S3K HOLD behavior, which suppresses its key-on.
            if (config.getNoteOnPrevent() == SmpsSequencerConfig.NoteOnPrevent.REST
                    || !preventAttack) {
                synth.writeFm(this, 0, 0x28, 0xF0 | chVal); // Key On after latching frequency/pan
                LOGGER.fine("FM KEY ON: chVal=" + Integer.toHexString(chVal) + " port=" + port + " fnum="
                        + Integer.toHexString(fnum) + " block=" + block + " note=" + Integer.toHexString(t.note));
            }
            if (t.modEnabled && config.isApplyModOnNote()
                    && config.getNoteOnPrevent()
                            == SmpsSequencerConfig.NoteOnPrevent.REST) {
                applyModulation(t);
            }

        } else {
            // Table choice comes from driver config: S1/S2 use DEF_68K, S3K uses DEF_Z80_T2.
            int[] psgFreqTable = getPsgFreqTable();
            int psgNote = n;
            if (psgNote < 0)
                psgNote = 0;
            if (psgNote >= psgFreqTable.length)
                psgNote = psgFreqTable.length - 1;
            int reg;
            if (noteByteRead) {
                reg = psgFreqTable[psgNote];
                t.baseFnum = reg;
            } else {
                // zStoreDuration stores no frequency (Sound/Z80 Sound
                // Driver.asm:917-919); the track keeps its Freq word.
                reg = t.baseFnum;
            }

            reg += t.detune;

            if (pitch != 1.0f) {
                reg = (int) (reg / pitch);
            }
            reg = normalizePsgPeriod(reg);

            boolean noiseUsesTone2 = t.noiseMode && t.channelId == 2 && (t.psgNoiseParam & 0x03) == 0x03;
            boolean writeToneFreq = t.channelId < 3 && (!t.noiseMode || noiseUsesTone2);

            // S3K zUpdatePSGTrack calls zUpdateFreq and zDoModulation, which
            // only compute, and then sends the frequency once
            // (skdisasm Sound/Z80 Sound Driver.asm:4077-4095).
            //
            // S2 sends it once here too. zPSGDoNoteOn is not a separate write:
            // it loads the frequency into de and falls straight through into
            // zPSGUpdateFreq, which is the send (s2.sounddriver.asm:1202-1209).
            // The second send at the tail of zPSGUpdateTrack's note-on branch
            // (:1127-1131) is reached only when zDoModulation recomputes the
            // frequency, because zDoModulation pops its caller's return address
            // on entry (:986-987) and every early return therefore lands past
            // zPSGUpdateTrack. Only the .calcfreq path returns normally, with
            // an explicit `jp (hl)` the listing annotates "WILL return to
            // zUpdateTrack" (:1046-1049).
            boolean modulationSendsNoteFrequency = t.modEnabled
                    && config.isApplyModOnNote()
                    && config.getNoteOnPrevent()
                            == SmpsSequencerConfig.NoteOnPrevent.HOLD;
            if (writeToneFreq && !modulationSendsNoteFrequency) {
                int data = reg & 0xF;
                int ch = t.channelId;
                synth.writePsgFrequencyPair(this,
                        0x80 | (ch << 5) | data,
                        psgFrequencyHighByte(reg));
                // baseFnum stores detune-free period; modulation applies detune dynamically.
            }

            // S2 (ModAlgo 68k_a) applies modulation before PSG volume write; S1 (ModAlgo 68k) does not.
            if (t.modEnabled && config.isApplyModOnNote()) {
                // Whether that modulation pass also puts the frequency back on
                // the bus is the same ROM property NoteGoingFreqSend names. A
                // driver whose modulation discards the caller's return address
                // sends again only when it actually stepped the frequency;
                // S3K's ordinary subroutine returns into the send every time.
                t.forceModulationWrite = config.getNoteGoingFreqSend()
                        == SmpsSequencerConfig.NoteGoingFreqSend.EVERY_PASS;
                applyModulation(t);
                // Claim the shared ROM tail only when this pass necessarily
                // emitted it: S3K forces every-pass sends, and only a live,
                // unowned tone channel reaches writeTrackFrequency's PSG pair.
                psgVolumeWrittenByFrequencyTail =
                        config.getNoteGoingFreqSend()
                                == SmpsSequencerConfig.NoteGoingFreqSend.EVERY_PASS
                        && config.getPsgVolumeTail()
                                == SmpsSequencerConfig.PsgVolumeTail.EVERY_NOTE_GOING_PASS
                        && t.instrumentId == 0
                        && (t.modStepInEffect || t.modEnvStepInEffect)
                        && t.channelId >= 0
                        && t.channelId < 3
                        && !t.resting
                        && !t.overridden;
            }

        }

        if (!preventAttack) {
            boolean deferPsgEnvelopeToOverrideGate =
                    t.type == TrackType.PSG
                    && t.overridden
                    && config.getPsgNoteGoingOrder()
                            == SmpsSequencerConfig.PsgNoteGoingOrder
                                    .FREQUENCY_THEN_VOLUME;
            t.decayOffset = 0;
            t.decayTimer = 0;
            t.envPos = 0;
            t.envHold = false;
            t.envAtRest = false;
            if (t.envData != null && t.envData.length > 0) {
                if (deferPsgEnvelopeToOverrideGate) {
                    // zFinishTrackUpdate resets VolEnv, but the later S3K
                    // override gate returns before zDoVolEnv can consume byte
                    // zero (Sound/Z80 Sound Driver.asm:1055-1069, :4079-4106).
                    t.envValue = 0;
                } else {
                    int val = t.envData[0] & 0xFF;
                    if (val < 0x80) {
                        t.envValue = val;
                        t.envPos = 1;
                    }
                }
            } else {
                t.envData = null;
                t.envValue = 0;
            }

            if (t.type == TrackType.PSG
                    && !deferPsgEnvelopeToOverrideGate
                    && !psgVolumeWrittenByFrequencyTail) {
                // S3K's modulation-driven frequency send already falls through
                // zUpdatePSGTrack's one volume tail. Do not append a second
                // attacked-note write when that tail had no envelope to step
                // (Sound/Z80 Sound Driver.asm:4059-4135).
                refreshVolume(t); // Apply the first envelope step immediately on note start
            }
            if (t.type == TrackType.FM && t.fmVolEnvData != null) {
                t.fmVolEnvPos = 0;
                t.fmVolEnvValue = 0;
                t.fmVolEnvHold = false;
                refreshVolume(t);
            }
        } else if (t.type == TrackType.PSG && config.isDirect68kDriver()) {
            // S1 PSGUpdateTrack always falls through PSGDoVolFX after a new
            // stream unit. A tied/no-attack note preserves envelope state but
            // still resends its current volume (SD:1813-1821, 1926-1987).
            //
            // PSGDoVolFX advances the envelope as well as sending the volume,
            // and the new-note path reaches it unconditionally: the branch is
            // `bra.w PSGDoVolFX` (SD:1819), taken whatever the do-not-attack
            // bit says. Only the *reset* is conditional, and it lives in
            // FinishTrackUpdate, which skips `clr.b VolEnvIndex` when bit 4 is
            // set (SD:438-442). So a tied note keeps its cursor and then steps
            // it once, where an attacked note clears the cursor and then steps
            // it once. Stepping here rather than only resending is what makes
            // the two cases differ by their reset alone, as the ROM does.
            // S2 reaches its vol-FX the same unconditional way, by `call
            // zPSGDoVolFX` on the note-on path (s2.sounddriver.asm:1129).
            //
            // The ROM splits this in two and so does the engine here: the
            // step advances the cursor and computes the level, then exactly
            // one send follows, because PSGDoVolFX falls into SetPSGVolume
            // (SD:1960-1969). Stepping with the send suppressed and sending
            // once afterwards keeps a pass to a single write whether or not
            // the step itself would have emitted one.
            t.suppressPsgVolumeWrite = true;
            try {
                processPsgEnvelope(t);
            } finally {
                t.suppressPsgVolumeWrite = false;
            }
            refreshVolume(t);
        }
    }

    private void playRawFrequency(Track t) {
        boolean preventAttack = shouldPreventNoteAttack(t);
        int freq = t.rawFrequency & 0xFFFF;

        if (freq == 0) {
            stopNote(t);
            if (config.getDelayFreq() == SmpsSequencerConfig.DelayFreq.RESET) {
                resetTrackedFrequency(t);
            }
            t.tieNext = false;
            return;
        }

        if (!preventAttack) {
            resetModEnvelopeState(t);
        }

        if (t.type == TrackType.FM) {
            int packed = freq + t.detune;
            int block = (packed >> 11) & 0x7;
            int fnum = packed & 0x7FF;
            t.baseFnum = fnum;
            t.baseBlock = block;

            if (t.customModEnabled && !preventAttack) {
                prepareCustomModulation(t);
            }

            int hwCh = t.channelId;
            int port = (hwCh < 3) ? 0 : 1;
            int ch = (hwCh % 3);
            int chVal = (port == 0) ? ch : (ch + 4);
            if (!preventAttack) {
                synth.writeFm(this, 0, 0x28, chVal);
            }
            writeFmFreq(port, ch, fnum, block);
            applyFmPanAmsFms(t);
            if (t.modEnabled && config.isApplyModOnNote()) {
                t.forceModulationWrite = true;
                applyModulation(t);
            }
            if (!preventAttack) {
                synth.writeFm(this, 0, 0x28, 0xF0 | chVal);
            }
            if (!preventAttack && t.fmVolEnvData != null) {
                t.fmVolEnvPos = 0;
                t.fmVolEnvValue = 0;
                t.fmVolEnvHold = false;
                refreshVolume(t);
            }
        } else if (t.type == TrackType.PSG) {
            t.baseFnum = freq;

            int reg = freq + t.detune;
            reg = normalizePsgPeriod(reg);

            boolean noiseUsesTone2 = t.noiseMode && t.channelId == 2 && (t.psgNoiseParam & 0x03) == 0x03;
            boolean writeToneFreq = t.channelId < 3 && (!t.noiseMode || noiseUsesTone2);
            if (writeToneFreq) {
                int ch = t.channelId;
                synth.writePsgFrequencyPair(this,
                        0x80 | (ch << 5) | (reg & 0x0F),
                        psgFrequencyHighByte(reg));
            }

            if (t.customModEnabled && !preventAttack) {
                prepareCustomModulation(t);
            }
            if (t.modEnabled && config.isApplyModOnNote()) {
                t.forceModulationWrite = true;
                applyModulation(t);
            }

            if (!preventAttack) {
                t.envPos = 0;
                t.envHold = false;
                t.envAtRest = false;
                t.decayOffset = 0;
                t.decayTimer = 0;
                refreshVolume(t);
            }
        }

        if (config.getNoteOnPrevent() == SmpsSequencerConfig.NoteOnPrevent.REST) {
            // S1/S2 keep the engine's existing post-note clear. S3K must not
            // clear here: zGetNextNote clears the bit before the
            // coordination-flag loop, so a cfPreventAttack read in that same
            // unit stays set for the guarded note's whole duration
            // (Sound/Z80 Sound Driver.asm:905-915, :3212-3221).
            t.tieNext = false;
        }
    }

    private int getPitchSlideFreq(int freq) {
        // The Z80 SMPS driver does NOT have any pitch slide wrapping logic.
        // It directly adds modulation/detune to the frequency and lets the
        // hardware handle any overflow. Previous wrapping code here was
        // causing incorrect octave jumps during modulation (e.g., Gloop SFX).
        return freq;
    }

    /**
     * ROM {@code zRestTrack} (Sound/Z80 Sound Driver.asm:4220-4224): set the
     * rest bit, return if an SFX is overriding the track, and otherwise fall
     * through into {@code zSilencePSGChannel}. The routine has no terminating
     * {@code ret} of its own, so the fall-through is unconditional for a
     * track the driver still owns, and the silence therefore repeats on every
     * pass that reaches it.
     */
    private void restTrack(Track t) {
        t.resting = true;
        if (t.overridden) {
            return;
        }
        stopNote(t);
    }

    @Override
    public void releaseChannelToMusic(Track endingTrack) {
        host.releaseChannelToMusic(this, endingTrack);
    }

    @Override
    public void stopNote(Track t) {
        if (t.type == TrackType.FM) {
            int hwCh = t.channelId;
            int port = (hwCh < 3) ? 0 : 1;
            int ch = hwCh % 3;
            int chVal = (port == 0) ? ch : (ch + 4);
            synth.writeFm(this, 0, 0x28, chVal); // Key On/Off is always on Port 0
        } else if (t.type == TrackType.DAC) {
            synth.stopDac(this);
        } else {
            if (t.channelId <= 3) {
                if (config.getPsgSilenceShape()
                        == SmpsSequencerConfig.PsgSilenceShape.TONE_THEN_NOISE) {
                    // zSilencePSGChannel silences 1Fh + VoiceControl, which is
                    // the track's own tone channel, and only then adds 0FFh
                    // for the noise channel, gated on PlaybackControl bit 0
                    // (Sound/Z80 Sound Driver.asm:4226-4245).
                    synth.writePsg(this, 0x80 | (t.channelId << 5) | (1 << 4) | 0x0F);
                    if (t.noiseMode) {
                        synth.writePsg(this, 0xFF);
                    }
                } else if (t.noiseMode && t.channelId == 2) {
                    synth.writePsg(this, 0x80 | (3 << 5) | (1 << 4) | 0x0F);
                } else {
                    synth.writePsg(this, 0x80 | (t.channelId << 5) | (1 << 4) | 0x0F);
                }
            }
        }
    }

    @Override
    public void stopPsgNoteWithDriverSilence(Track t) {
        if (t.type != TrackType.PSG) {
            throw new IllegalArgumentException("driver PSG silence requires a PSG track");
        }
        synth.writePsgDriverSilence(this, t.channelId, t.noiseMode);
    }

    public boolean isComplete() {
        for (Track t : tracks) {
            if (t.active)
                return false;
        }
        return true;
    }

    @Override
    public void refreshVolume(Track t) {
        if (t.type == TrackType.FM) {
            updateFmTotalLevel(t);
        } else if (t.type == TrackType.PSG) {
            if (t.suppressPsgVolumeWrite) {
                return;
            }
            if (t.envAtRest || t.resting) {
                return;
            }
            // Every SMPS driver withholds the PSG attenuation byte while an SFX
            // owns the track, testing the same playback-control bit 2 the flutter
            // index keeps advancing behind: S2 zPSGUpdateVol does `and 6` over the
            // rest and override bits and returns (s2.sounddriver.asm:1305-1308),
            // S1 SetPSGVolume tests the two bits separately
            // (s1.sounddriver.asm:1965-1969), and S3K reaches the same outcome one
            // level up, where zUpdatePSGTrack returns on bit 2 before either the
            // frequency pair or the volume tail (skdisasm Sound/Z80 Sound
            // Driver.asm:4079-4081).
            if (t.overridden) {
                return;
            }
            // The rest test is the track's rest BIT, not the last note byte
            // read. zUpdatePSGTrack's volume tail adds Volume to the envelope
            // value and only forces 0Fh when that addition sets bit 4
            // (Sound/Z80 Sound Driver.asm:4098-4112); the rest bit gates
            // whether the write happens at all, one line above. Keying on the
            // note byte instead silenced a track whose last byte was a rest
            // but which a later duration-only unit had already brought back.
            int vol = t.resting ? 0x0f
                    : Math.min(0x0F, Math.max(0, t.volumeOffset + t.envValue));
            int ch = t.channelId;
            if (t.noiseMode && ch == 2) {
                ch = 3;
            }
            if (ch <= 3) {
                synth.writePsg(this, 0x80 | (ch << 5) | (1 << 4) | vol);
            }
        }
    }

    private void updateFmTotalLevel(Track t) {
        if (t.voiceData == null) {
            return;
        }
        boolean hasTl = t.voiceData.length >= 25;
        if (!hasTl) {
            return;
        }
        if (config.isDirect68kDriver()) {
            byte[] volumeVoice = t.voiceData;
            if (isSfx && !specialSfx
                    && config.getFmVolumeVoiceBankMode()
                    == SmpsSequencerConfig.FmVolumeVoiceBankMode.S1_SPECIAL_POINTER_BUG) {
                // S1 FixBugs=0 SendVoiceTL reads VoicePtr(a6), aliasing the
                // global special-SFX pointer instead of this track's VoicePtr
                // (SD:2391-2398). A cleared pointer reads zero TL bytes from
                // the ROM vector area in the shipped image.
                byte[] specialVoice = host.s1SpecialSfxVoiceForBug(t.voiceId);
                byte[] zeroAddressVoice = smpsData
                        instanceof ZeroAddressFmVoiceProvider provider
                        ? provider.getZeroAddressFmVoice(t.voiceId) : null;
                volumeVoice = specialVoice != null ? specialVoice
                        : zeroAddressVoice != null ? zeroAddressVoice : ZERO_FM_VOICE;
            }
            int port = t.channelId < 3 ? 0 : 1;
            int channel = t.channelId % 3;
            int[] operatorOffsets = { 0, 8, 4, 12 };
            int[] normalizedVoiceIndices = { 0, 2, 1, 3 };
            int mask = ALGO_OUT_MASK[t.voiceData[0] & 7];
            for (int operator = 0; operator < 4; operator++) {
                if ((mask & (1 << operator)) == 0) {
                    continue;
                }
                int rawTl = volumeVoice[21 + normalizedVoiceIndices[operator]] & 0xff;
                int adjusted = rawTl + (t.volumeOffset & 0xff);
                // S1/S2 SendVoiceTL skips the write when the byte addition carries.
                if (adjusted > 0xff) {
                    continue;
                }
                synth.writeFm(this, port, 0x40 + operatorOffsets[operator] + channel, adjusted);
            }
            return;
        }
        int hwCh = t.channelId;
        int port = (hwCh < 3) ? 0 : 1;
        int ch = hwCh % 3;

        int mask = config.getVolMode() == SmpsSequencerConfig.VolMode.BIT7
                ? bit7CarrierMask(t.voiceData, 21)
                : ALGO_OUT_MASK_SLOT[t.voiceData[0] & 0x07];
        int[] registerOffsets = operatorRegisterOffsets();
        // Both Z80 drivers write all four operators every time and use the
        // carrier mask only to decide whether the track volume is added.
        // S2's zSetFMTLs rewrites non-carriers unchanged (sd:3385-3424,
        // 3438-3457) and FixDriverBugs=0 keeps its 8-bit carrier addition.
        // S3K's zSendTL loops over the whole TL table, branching past the
        // volume add on a positive byte but writing every entry either way,
        // and its fix_sndbugs=0 path strips the sign bit from what it sends
        // (skdisasm Sound/Z80 Sound Driver.asm:3149-3178).
        SmpsSequencerConfig.FmVoiceWriteProfile profile =
                config.getFmVoiceWriteProfile();
        boolean writesEveryOperator =
                profile == SmpsSequencerConfig.FmVoiceWriteProfile.S2_Z80
                        || profile == SmpsSequencerConfig.FmVoiceWriteProfile.S3K_Z80;
        for (int storedOperator = 0; storedOperator < 4; storedOperator++) {
            int idx = 21 + storedOperator;
            if (idx >= t.voiceData.length) {
                continue;
            }
            boolean carrier = (mask & (1 << storedOperator)) != 0;
            if (!carrier && !writesEveryOperator) {
                continue;
            }
            int rawTl = t.voiceData[idx] & 0xff;
            int tl;
            if (!carrier) {
                tl = profile == SmpsSequencerConfig.FmVoiceWriteProfile.S2_Z80
                        ? rawTl : rawTl & 0x7f;
            } else {
                tl = profile == SmpsSequencerConfig.FmVoiceWriteProfile.S2_Z80
                        ? computeS2TotalLevel(t, rawTl, storedOperator)
                        : computeFmTotalLevel(t, rawTl & 0x7f, storedOperator);
            }
            synth.writeFm(this, port, 0x40 + registerOffsets[storedOperator] + ch, tl);
        }
    }

    private static int bit7CarrierMask(byte[] voice, int tlBase) {
        int mask = 0;
        for (int storedOperator = 0; storedOperator < 4; storedOperator++) {
            int idx = tlBase + storedOperator;
            if (idx < voice.length && (voice[idx] & 0x80) != 0) {
                mask |= 1 << storedOperator;
            }
        }
        return mask;
    }

    private int[] operatorRegisterOffsets() {
        return config.getFmVoiceWriteProfile() == SmpsSequencerConfig.FmVoiceWriteProfile.S3K_Z80
                ? S3K_OPERATOR_REGISTER_OFFSETS
                : S2_OPERATOR_REGISTER_OFFSETS;
    }

    private int computeFmTotalLevel(Track t, int baseTl, int op) {
        int tl = baseTl + t.volumeOffset;
        if (t.fmVolEnvData != null && (t.fmVolEnvOpMask & (1 << op)) != 0) {
            tl += t.fmVolEnvValue;
        }
        return tl & 0x7F; // wrap like the Z80 interpreter (7-bit)
    }

    private int computeS2TotalLevel(Track t, int baseTl, int storedOperator) {
        int tl = baseTl + t.volumeOffset;
        if (t.fmVolEnvData != null && (t.fmVolEnvOpMask & (1 << storedOperator)) != 0) {
            tl += t.fmVolEnvValue;
        }
        // Shipped S2 uses FixDriverBugs=0: zSetFMTLs performs an 8-bit add
        // without forcing bit 7 or clamping attenuation overflow.
        return tl & 0xFF;
    }

    @Override
    public void loadPsgEnvelope(Track t, int id) {
        byte[] env = copyPsgEnvelope(programView, id);
        if (env != null) {
            t.envData = env;
            t.envPos = 0;
            t.envHold = false;
            t.envAtRest = false;
            t.envValue = 0;
        } else {
            t.envData = null;
            t.envValue = 0;
        }
    }

    private void primePsgRestEnvelope(Track t) {
        t.decayOffset = 0;
        t.decayTimer = 0;
        t.envPos = 0;
        t.envHold = false;
        t.envAtRest = false;
        if (t.envData != null && t.envData.length > 0) {
            int val = t.envData[0] & 0xFF;
            if (val < 0x80) {
                t.envValue = val;
                t.envPos = 1;
            }
        } else {
            t.envData = null;
            t.envValue = 0;
        }
    }

    private void processPsgEnvelope(Track t) {
        if (t.envData == null || t.envHold)
            return;

        // Loop to handle envelope commands that may require immediate progression
        while (true) {
            if (t.envPos >= t.envData.length) {
                t.envHold = true;
                t.envAtRest = true;
                return;
            }
            int val = t.envData[t.envPos] & 0xFF;
            t.envPos++;

            if (val < 0x80) {
                t.envValue = val;
                refreshVolume(t);
                return;
            } else {
                if (config.getPsgEnvRestCmd()
                        == SmpsSequencerConfig.PsgEnvRestCmd.Z80_81_AND_83
                        && (val == 0x81 || val == 0x83)) {
                    // zDoVolEnvRest (81h) and zDoVolEnvFullRest (83h) both pop
                    // the caller's return address and end the track's pass
                    // (Sound/Z80 Sound Driver.asm:4169-4175, :4187-4194,
                    // :4204-4208), and neither advances VolEnv, so the
                    // envelope stays parked and re-reads the command on every
                    // later pass. That re-read matters: zGetNextNote clears
                    // bit 4 when a new note is read (:905-915) and the parked
                    // command puts it straight back on the same pass, so the
                    // envelope must not be held here.
                    //
                    // The two differ in one way. 81h sets the bit itself and
                    // returns, writing nothing, which the ROM notes at :4208.
                    // 83h jumps to zRestTrack, which has no terminating ret
                    // and falls straight through into zSilencePSGChannel
                    // (:4220-4245), so it silences the channel every pass it
                    // runs.
                    t.envPos--;
                    if (val == 0x83) {
                        restTrack(t);
                    } else {
                        t.resting = true;
                    }
                    return;
                }
                if (val == 0x80) {
                    if (config.getPsgEnvCmd80() == SmpsSequencerConfig.PsgEnvCmd80.RESET) {
                        // S3K: reset envelope to start (loop from beginning)
                        t.envPos = 0;
                        continue;
                    }
                    // S1/S2: HOLD (Sonic 2 driver definition)
                    // S1 VolEnvHold decrements the already-advanced index so it
                    // remains on the terminator byte.
                    t.envPos--;
                    t.envHold = true;
                    t.envAtRest = true;
                    return;
                } else if (val == 0x81) {
                    // HOLD
                    t.envHold = true;
                    t.envAtRest = true;
                    return;
                } else if (val == 0x82) {
                    // LOOP xx - next byte is target index
                    if (t.envPos < t.envData.length) {
                        t.envPos = t.envData[t.envPos] & 0xFF;
                        continue;
                    } else {
                        t.envHold = true;
                        t.envAtRest = true;
                        return;
                    }
                } else if (val == 0x84) {
                    // CHGMULT xx - not modeled; consume parameter to stay in sync
                    if (t.envPos < t.envData.length) {
                        t.envPos++; // skip multiplier byte
                        continue;
                    } else {
                        t.envHold = true;
                        t.envAtRest = true;
                        return;
                    }
                } else if (val == 0x83) {
                    // STOP
                    t.envHold = true;
                    t.envValue = 0x0F; // Silence
                    t.envAtRest = true;
                    refreshVolume(t);
                    stopNote(t);
                    return;
                } else {
                    // Unknown/Other: Treat as HOLD
                    t.envHold = true;
                    t.envAtRest = true;
                    return;
                }
            }
        }
    }

    private void processFmVolEnvelope(Track t) {
        if (t.fmVolEnvData == null || t.fmVolEnvHold) {
            return;
        }

        while (true) {
            if (t.fmVolEnvPos >= t.fmVolEnvData.length) {
                t.fmVolEnvHold = true;
                return;
            }
            int val = t.fmVolEnvData[t.fmVolEnvPos] & 0xFF;
            t.fmVolEnvPos++;

            if (val < 0x80) {
                t.fmVolEnvValue = val;
                refreshVolume(t);
                return;
            }

            if (val == 0x80) {
                t.fmVolEnvPos = 0;
                continue;
            }
            if (val == 0x81) {
                t.fmVolEnvHold = true;
                return;
            }
            if (val == 0x82) {
                if (t.fmVolEnvPos < t.fmVolEnvData.length) {
                    t.fmVolEnvPos = t.fmVolEnvData[t.fmVolEnvPos] & 0xFF;
                    continue;
                }
                t.fmVolEnvHold = true;
                return;
            }
            if (val == 0x84) {
                if (t.fmVolEnvPos < t.fmVolEnvData.length) {
                    t.fmVolEnvPos++;
                    continue;
                }
                t.fmVolEnvHold = true;
                return;
            }

            // STOP/unknown: hold at max attenuation.
            t.fmVolEnvHold = true;
            t.fmVolEnvValue = 0x7F;
            refreshVolume(t);
            return;
        }
    }

    @Override
    public void refreshInstrument(Track t) {
        if (t.type != TrackType.FM || t.voiceData == null) {
            return;
        }
        // Use scratch buffer instead of allocating new array each call
        int copyLen = Math.min(t.voiceData.length, t.voiceScratch.length);
        System.arraycopy(t.voiceData, 0, t.voiceScratch, 0, copyLen);
        byte[] voice = t.voiceScratch;
        boolean hasTl = t.voiceData.length >= 25;
        // SMPS (S2) stores TL at the end of the 25-byte blob (bytes 21-24).
        int tlBase = hasTl ? 21 : -1;
        if (tlBase >= 0 && !config.isDirect68kDriver()) {
            int mask = config.getVolMode() == SmpsSequencerConfig.VolMode.BIT7
                    ? bit7CarrierMask(voice, tlBase)
                    : ALGO_OUT_MASK_SLOT[voice[0] & 0x07];
            for (int storedOperator = 0; storedOperator < 4; storedOperator++) {
                if ((mask & (1 << storedOperator)) == 0) {
                    continue;
                }
                int idx = tlBase + storedOperator;
                if (config.getFmVoiceWriteProfile() == SmpsSequencerConfig.FmVoiceWriteProfile.S2_Z80) {
                    voice[idx] = (byte) computeS2TotalLevel(t, voice[idx] & 0xFF, storedOperator);
                } else {
                    int tl = computeFmTotalLevel(t, voice[idx] & 0x7F, storedOperator);
                    voice[idx] = (byte) (tl | (voice[idx] & 0x80));
                }
            }
        } else if (tlBase >= 0) {
            int algo = voice[0] & 0x07;
            int[] opMap = { 0, 2, 1, 3 };
            int mask = ALGO_OUT_MASK[algo];
            for (int op = 0; op < 4; op++) {
                if ((mask & (1 << op)) != 0) {
                    int idx = tlBase + opMap[op];
                    // S1 SetVoice performs an 8-bit add on the stored TL byte.
                    // S1's DEFAULT voice format deliberately leaves bit 7 set on
                    // carriers, so it is visible on the bus even though YM2612 uses
                    // only the low seven TL bits.
                    int tl = (voice[idx] & 0xff) + t.volumeOffset;
                    if (t.fmVolEnvData != null && (t.fmVolEnvOpMask & (1 << op)) != 0) {
                        tl += t.fmVolEnvValue;
                    }
                    voice[idx] = (byte) tl;
                }
            }
        }
        switch (config.getFmVoiceWriteProfile()) {
            case S1_68K -> write68kInstrument(t, voice);
            case S2_Z80 -> writeS2Instrument(t, voice, hasTl);
            case S3K_Z80 -> writeS3kInstrument(t, voice, hasTl);
        }

        // S3K's coordination flag owns SSG-EG independently of its voice table.
        // Re-emit active values when restoring a track after shared-channel use.
        boolean hasSsgEg = false;
        for (int v : t.ssgEg) {
            if (v != 0) { hasSsgEg = true; break; }
        }
        if (hasSsgEg) {
            int port = (t.channelId < 3) ? 0 : 1;
            int ch = t.channelId % 3;
            int[] registerOffsets = operatorRegisterOffsets();
            for (int storedOperator = 0; storedOperator < 4; storedOperator++) {
                if (t.ssgEg[storedOperator] != 0) {
                    synth.writeFm(this, port, 0x90 + registerOffsets[storedOperator] + ch,
                            t.ssgEg[storedOperator]);
                }
            }
        }
    }

    private void write68kInstrument(Track t, byte[] voice) {
        int port = t.channelId < 3 ? 0 : 1;
        int channel = t.channelId % 3;
        int[] operatorOffsets = { 0, 8, 4, 12 };
        int[] normalizedVoiceIndices = { 0, 2, 1, 3 };
        int[] parameterRegisters = { 0x30, 0x50, 0x60, 0x70, 0x80 };

        // S1 SetVoice writes by parameter group in stored operator order
        // (1,3,2,4), sends adjusted TL last, then restores AMS/FMS/panning.
        // The shipped driver does not inject a key-off or clear SSG-EG here.
        synth.writeFm(this, port, 0xb0 + channel, voice[0] & 0xff);
        for (int group = 0; group < 5; group++) {
            for (int operator = 0; operator < 4; operator++) {
                synth.writeFm(this, port,
                        parameterRegisters[group] + operatorOffsets[operator] + channel,
                        voice[1 + group * 4 + normalizedVoiceIndices[operator]] & 0xff);
            }
        }
        for (int operator = 0; operator < 4; operator++) {
            synth.writeFm(this, port, 0x40 + operatorOffsets[operator] + channel,
                    voice[21 + normalizedVoiceIndices[operator]] & 0xff);
        }
        applyFmPanAmsFms(t);
    }

    private void writeS2Instrument(Track t, byte[] voice, boolean hasTl) {
        int port = t.channelId < 3 ? 0 : 1;
        int channel = t.channelId % 3;
        synth.writeFm(this, port, 0xB0 + channel, voice[0] & 0xFF);
        writeGroupedOperatorParameters(port, channel, voice, S2_OPERATOR_REGISTER_OFFSETS);
        applyFmPanAmsFms(t);
        if (hasTl) {
            writeTotalLevels(port, channel, voice, S2_OPERATOR_REGISTER_OFFSETS, false);
        }
    }

    private void writeS3kInstrument(Track t, byte[] voice, boolean hasTl) {
        int port = t.channelId < 3 ? 0 : 1;
        int channel = t.channelId % 3;
        applyFmPanAmsFms(t);
        synth.writeFm(this, port, 0xB0 + channel, voice[0] & 0xFF);
        writeGroupedOperatorParameters(port, channel, voice, S3K_OPERATOR_REGISTER_OFFSETS);
        if (hasTl) {
            writeTotalLevels(port, channel, voice, S3K_OPERATOR_REGISTER_OFFSETS, true);
        }
    }

    private void writeGroupedOperatorParameters(int port, int channel, byte[] voice, int[] registerOffsets) {
        for (int group = 0; group < FM_PARAMETER_REGISTERS.length; group++) {
            for (int storedOperator = 0; storedOperator < 4; storedOperator++) {
                synth.writeFm(this, port,
                        FM_PARAMETER_REGISTERS[group] + registerOffsets[storedOperator] + channel,
                        voice[1 + group * 4 + storedOperator] & 0xFF);
            }
        }
    }

    private void writeTotalLevels(int port, int channel, byte[] voice, int[] registerOffsets,
            boolean maskToSevenBits) {
        for (int storedOperator = 0; storedOperator < 4; storedOperator++) {
            int value = voice[21 + storedOperator] & 0xFF;
            if (maskToSevenBits) {
                // S3K fix_sndbugs=0 zSendTL strips bit 7 before every YM write.
                value &= 0x7F;
            }
            synth.writeFm(this, port, 0x40 + registerOffsets[storedOperator] + channel,
                    value);
        }
    }

    private void applyFmPanAmsFms(Track t) {
        if (t.type != TrackType.FM)
            return;
        int hwCh = t.channelId;
        int port = (hwCh < 3) ? 0 : 1;
        int ch = (hwCh % 3);
        int reg = 0xB4 + ch;
        int val = (t.pan & 0xC0) | ((t.ams & 0x3) << 4) | (t.fms & 0x7);
        synth.writeFm(this, port, reg, val);
    }

    private void writeFmFreq(int port, int ch, int fnum, int block) {
        int valA4 = (block << 3) | ((fnum >> 8) & 0x7);
        int valA0 = fnum & 0xFF;
        synth.writeFm(this, port, 0xA4 + ch, valA4);
        synth.writeFm(this, port, 0xA0 + ch, valA0);
        // Note: Key On is handled by playNote(), NOT here.
        // The original Z80 driver's zFMUpdateFreq only writes frequency registers.
        // Adding Key On here caused re-keying during rests, breaking SFX like 0xAD.
    }

    private void applyModulation(Track t) {
        applyModulation(t, true);
    }

    /**
     * Steps modulation for {@code t} and, when {@code write} is set, sends the
     * resulting frequency. Returns the frequency displacement in effect after
     * stepping, which is zero whenever modulation is not running, so a caller
     * that owns the send itself can pass {@code false} and use the returned
     * displacement.
     */
    private int applyModulation(Track t, boolean write) {
        if (!t.modEnabled)
            return 0;
        // Only S2's zDoModulation tests the rest bit on entry
        // (s2.sounddriver.asm:988-990). S1's DoModulation
        // (s1.sounddriver.asm:483-490) and S3K's zDoModulation
        // (skdisasm Sound/Z80 Sound Driver.asm:1277-1283) test only whether
        // modulation is active, so both keep advancing the phase at rest.
        if (!config.isStepModulationAtRest() && t.resting)
            return 0;

        stepCustomModulation(t);
        stepModEnvelope(t);
        if (!t.modStepInEffect && !t.modEnvStepInEffect) {
            t.modEnabled = false;
            return 0;
        }

        int freqDelta = t.modStepDelta + t.modEnvStepDelta;
        boolean changed = t.modStepChanged || t.modEnvStepChanged || t.forceModulationWrite;
        t.forceModulationWrite = false;
        if (!write || !changed) {
            return freqDelta;
        }
        writeTrackFrequency(t, freqDelta, true);
        return freqDelta;
    }

    /**
     * Puts the track's frequency plus {@code freqDelta} on the bus.
     *
     * @param suppressPsgAtRest whether a resting PSG track is skipped. True on
     *     the modulation-driven send, whose S1/S2 callers have already returned
     *     at rest; false on S3K's unconditional per-pass send, whose ROM rest
     *     check comes after the frequency latch.
     */
    private void writeTrackFrequency(Track t, int freqDelta, boolean suppressPsgAtRest) {
        if (t.type == TrackType.FM) {
            int packed = getPacked(t, freqDelta);

            int hwCh = t.channelId;
            int port = (hwCh < 3) ? 0 : 1;
            int ch = (hwCh % 3);
            if (config.isDirect68kDriver()) {
                // S1 FMUpdateFreq moves the high and low bytes of d6 directly;
                // do not reinterpret a negative rest-frequency modulation as
                // a masked YM block/f-number pair.
                synth.writeFm(this, port, 0xA4 + ch, (packed >>> 8) & 0xff);
                synth.writeFm(this, port, 0xA0 + ch, packed & 0xff);
            } else {
                int block = (packed >> 11) & 7;
                int fnum = packed & 0x7FF;
                writeFmFreq(port, ch, fnum, block);
            }
        } else if (t.type == TrackType.PSG && t.channelId < 3
                && !(suppressPsgAtRest && t.resting)) {
            boolean noiseUsesTone2 = t.noiseMode && t.channelId == 2 && (t.psgNoiseParam & 0x03) == 0x03;
            if (!t.noiseMode || noiseUsesTone2) {
                int reg = t.baseFnum + freqDelta + t.detune;
                if (pitch != 1.0f) {
                    reg = (int) (reg / pitch);
                }
                reg = normalizePsgPeriod(reg);

                int data = reg & 0xF;
                int ch = t.channelId;
                synth.writePsgFrequencyPair(this,
                        0x80 | (ch << 5) | data,
                        psgFrequencyHighByte(reg));
            }
            if (config.getPsgVolumeTail()
                    == SmpsSequencerConfig.PsgVolumeTail.EVERY_NOTE_GOING_PASS
                    && t.instrumentId == 0) {
                // zUpdatePSGTrack's .note_going path sends the frequency pair
                // and then falls straight into the volume tail on every pass
                // of a sounding note. The only gates on that tail are
                // PlaybackControl bit 2 (SFX overriding) and bit 4 (track at
                // rest), both of which refreshVolume already applies; there is
                // no attack test in it at all
                // (Sound/Z80 Sound Driver.asm:4079-4135).
                //
                // The gate is the ROM's own: zUpdatePSGTrack reads VoiceIndex
                // and takes .no_volenv when it is zero, skipping zDoVolEnv with
                // c = 0 and falling through to the same volume write rather
                // than returning (:4103-4112). A track that does carry a PSG
                // volume envelope already reaches refreshVolume through its
                // envelope step each pass, so writing here as well would double
                // the write. Testing the voice index rather than whether
                // envelope data happens to be loaded matters: a track that
                // takes its channel from music can be holding the displaced
                // track's envelope data while its own voice index is still
                // zero, and that is exactly the collapse-over-music case.
                //
                // Without the tail, a volume the track changed while a note was
                // already sounding never reached the chip. sfx_Collapse's tail
                // is exactly that: six passes of "nB3, $18, smpsNoAttack" each
                // followed by smpsPSGAlterVol $03
                // (Sound/SFX/59 - Collapse.asm:31-36), whose $00-to-$0F decay
                // ramp is what makes the effect ring out rather than stop dead.
                refreshVolume(t);
            }
        }
    }

    private void stepCustomModulation(Track t) {
        if (!t.customModEnabled) {
            t.modStepInEffect = false;
            t.modStepChanged = false;
            t.modStepDelta = 0;
            return;
        }

        if (config.getModAlgo() == SmpsSequencerConfig.ModAlgo.MOD_Z80) {
            t.modDelay = (t.modDelay - 1) & 0xFF;
            if (t.modDelay != 0) {
                t.modStepInEffect = true;
                t.modStepChanged = false;
                t.modStepDelta = t.modAccumulator;
                return;
            }
            t.modDelay = 1;

            boolean accumulatorChanged = false;
            // dec (ix+ModulationSpeed) / jr nz, .mod_sustain: an 8-bit
            // decrement, so zero wraps to 0FFh and the accumulator then holds
            // for 255 passes rather than advancing every pass
            // (Sound/Z80 Sound Driver.asm:1296-1301).
            t.modRateCounter = (t.modRateCounter - 1) & 0xFF;
            if (t.modRateCounter == 0) {
                t.modRateCounter = t.modPendingRate;
                t.modAccumulator += t.modCurrentDelta;
                t.modAccumulator = (short) t.modAccumulator; // 16-bit signed wrap
                accumulatorChanged = true;
            }

            // Z80 zDoModulation decrements ModulationSteps on every sustain tick,
            // after applying the current accumulated delta to the note frequency.
            t.modStepCounter = (t.modStepCounter - 1) & 0xFF;
            if (t.modStepCounter == 0) {
                t.modStepCounter = t.modPendingStepsFull; // reload from current ModData[0x03]
                t.modCurrentDelta = -t.modCurrentDelta;
            }

            t.modStepInEffect = true;
            t.modStepChanged = accumulatorChanged;
            t.modStepDelta = t.modAccumulator;
            return;
        }

        if (t.modDelay > 0) {
            t.modDelay--;
            t.modStepInEffect = true;
            t.modStepChanged = false;
            t.modStepDelta = t.modAccumulator;
            return;
        }

        if (t.modRateCounter > 0) {
            t.modRateCounter--;
        }

        if (t.modRateCounter == 0) {
            t.modRateCounter = t.modPendingRate;

            // S1/S2 (MODALGO_68K): pre-check, then decrement.
            // ld a,(ix+zModStepCount) ; or a ; jr nz,.calcfreq
            if (t.modStepCounter == 0) {
                t.modStepCounter = t.modPendingStepsFull; // reload from current ModData[0x03]
                t.modCurrentDelta = -t.modCurrentDelta;
                t.modStepInEffect = true;
                t.modStepChanged = false;
                t.modStepDelta = t.modAccumulator;
                return;
            }
            t.modStepCounter--;

            t.modAccumulator += t.modCurrentDelta;
            t.modAccumulator = (short) t.modAccumulator; // 16-bit signed wrap
            t.modStepInEffect = true;
            t.modStepChanged = true;
            t.modStepDelta = t.modAccumulator;
            return;
        }

        t.modStepInEffect = true;
        t.modStepChanged = false;
        t.modStepDelta = t.modAccumulator;
    }

    private void stepModEnvelope(Track t) {
        if (t.modEnvId == 0 || t.modEnvData == null || t.modEnvData.length == 0) {
            t.modEnvStepInEffect = false;
            t.modEnvStepChanged = false;
            t.modEnvStepDelta = 0;
            return;
        }

        if (t.modEnvHold) {
            t.modEnvStepInEffect = true;
            t.modEnvStepChanged = false;
            t.modEnvStepDelta = t.modEnvCache;
            return;
        }

        int safety = 0;
        while (safety++ < 512) {
            if (t.modEnvPos >= t.modEnvData.length) {
                t.modEnvPos = 0;
            }

            int value = t.modEnvData[t.modEnvPos] & 0xFF;
            t.modEnvPos++;

            if (value < 0x80) {
                int envVal = (byte) value;
                int multiplier = t.modEnvMult + 1; // S3K DefDrv: EnvMult = Z80
                t.modEnvCache = (short) (envVal * multiplier);
                t.modEnvStepInEffect = true;
                t.modEnvStepChanged = true;
                t.modEnvStepDelta = t.modEnvCache;
                return;
            }

            switch (value) {
                case 0x80: // RESET
                    t.modEnvPos = 0;
                    continue;
                case 0x81: // HOLD
                case 0x83: // VOLSTOP_MODHOLD => HOLD for modulation envelopes
                    t.modEnvPos--;
                    t.modEnvHold = true;
                    t.modEnvStepInEffect = true;
                    t.modEnvStepChanged = false;
                    t.modEnvStepDelta = t.modEnvCache;
                    return;
                case 0x82: // LOOP xx
                    if (t.modEnvPos < t.modEnvData.length) {
                        t.modEnvPos = t.modEnvData[t.modEnvPos] & 0xFF;
                        continue;
                    }
                    t.modEnvHold = true;
                    t.modEnvStepInEffect = true;
                    t.modEnvStepChanged = false;
                    t.modEnvStepDelta = t.modEnvCache;
                    return;
                case 0x84: // CHG_MULT xx
                    if (t.modEnvPos < t.modEnvData.length) {
                        t.modEnvMult = (t.modEnvMult + (t.modEnvData[t.modEnvPos] & 0xFF)) & 0xFF;
                        t.modEnvPos++;
                        continue;
                    }
                    t.modEnvHold = true;
                    t.modEnvStepInEffect = true;
                    t.modEnvStepChanged = false;
                    t.modEnvStepDelta = t.modEnvCache;
                    return;
                default:
                    t.modEnvHold = true;
                    t.modEnvStepInEffect = true;
                    t.modEnvStepChanged = false;
                    t.modEnvStepDelta = t.modEnvCache;
                    return;
            }
        }

        t.modEnvHold = true;
        t.modEnvStepInEffect = true;
        t.modEnvStepChanged = false;
        t.modEnvStepDelta = t.modEnvCache;
    }

    private int getPacked(Track t, int modulationDelta) {
        int packed = (t.baseBlock << 11) | t.baseFnum;
        packed += modulationDelta + t.detune;

        packed = getPitchSlideFreq(packed);

        if (pitch != 1.0f) {
            int b = (packed >> 11) & 7;
            int f = packed & 0x7FF;
            f = (int) (f * pitch);
            while (f > 0x7FF && b < 7) {
                f >>= 1;
                b++;
            }
            packed = (b << 11) | (f & 0x7FF);
        }
        return packed;
    }

    private int normalizePsgPeriod(int reg) {
        // S1/S2 deliberately use period zero for nMaxPSG noise notes. Preserve the
        // register value here; PsgChip owns the integrated/discrete zero-period rule.
        if (config.isPsgFrequencyHighByteNibbleSwap()) {
            // S3K keeps the frequency in a 16-bit register and never masks it:
            // zUpdateFreq adds the sign-extended detune to the stored word and
            // zUpdatePSGTrack sends the result straight out
            // (skdisasm Sound/Z80 Sound Driver.asm:3080-3101, 4077-4095). A word
            // above 03FFh therefore survives into the second PSG byte. Masking
            // is invisible on S1/S2 because both mask that byte to six bits
            // anyway (s2.sounddriver.asm:2835-2842).
            return reg & 0xFFFF;
        }
        return reg & 0x3FF;
    }

    private void setDetune(Track t) {
        if (t.pos < programView.dataLength()) {
            t.detune = programView.dataByteAt(t.pos++);
        }
    }

    private void handleFadeIn(Track t) {
        // E4 is "Fade in to previous song" in Sonic 2.
        // It's used at the end of the 1-up jingle.
        // This command should stop ALL tracks in this sequence and restore the previous
        // music.
        for (Track track : tracks) {
            track.active = false;
            stopNote(track);
        }
        audioManager.restoreMusic();
    }

    /**
     * Models {@code StopAllSound}'s effect on this sequencer's own track RAM
     * (s1.sounddriver.asm:1461-1482). The ROM writes the DAC-enable and
     * FM3/FM6-mode registers, clears the driver's variable and track RAM, sets
     * {@code v_sound_id} to {@code $80}, and silences FM and PSG. What that
     * leaves behind for the music tracks is every playback-control byte zero,
     * so no track is playing, while the driver itself keeps running: the next
     * {@code UpdateMusic} still decrements the tempo timeout, now from a
     * cleared value.
     *
     * <p>Distinct from the {@code E4} coordination flag in track data
     * ({@link #handleFadeIn}), which ends a 1-up jingle and restores the
     * interrupted song. This is the {@code $E4} sound id, reached through
     * {@code PlaySoundID}'s {@code Sound_E0toE4} branch (:715, :726).
     *
     * <p>Deliberately does not touch the sequencer's membership of the driver.
     * The ROM's driver has no notion of going away, and this engine retains a
     * finished direct-68k music sequencer for the same reason.
     */
    public void applyStopAllSound() {
        for (Track track : tracks) {
            track.active = false;
            stopNote(track);
        }
        fadeState.active = false;
        fadeState.steps = 0;
        fadeState.delayCounter = 0;
        // The clear covers the driver's variables too, so the master tempo and
        // its timeout are both zeroed alongside the track RAM.
        normalTempo = 0;
        tempoWeight = 0;
        tempoAccumulator = 0;
        speedShoes = false;
    }

    public void triggerFadeIn(int steps, int delay) {
        // Start a fade in from current volume (silence) to normal
        fadeState.addFm = 1;
        fadeState.addPsg = 1;
        fadeState.fadeOut = false;
        setFadeSteps(steps);
        setFadeDelayReload(delay);
        // S1 and S2 leave the fade-in delay uninitialised, so their first step
        // happens at once. S3K's zFadeInToPrevious writes the same value to
        // both halves of its driver pair, exactly as zFadeOutMusic does
        // (Sound/Z80 Sound Driver.asm:2784-2789 against :2306-2312).
        setFadeDelayCounter(config.isDriverOwnedFadeDelay() ? delay : 0);
        fadeState.active = true;
        fadeState.fadeOut = false; // Fade IN

        // Add steps to existing volumeOffset (attenuate by 'steps'), then fade
        // decreases it.
        for (Track track : tracks) {
            // For DAC, mute during fade-in (no volume control available). The
            // ROM expresses the same thing by setting the DAC track's
            // "SFX is overriding" bit so nothing drives it through the fade
            // (s2.sounddriver.asm:3094, s1.sounddriver.asm:2183).
            if (track.type == TrackType.DAC) {
                track.dacMuted = true;
                stopNote(track);
                continue;
            }
            boolean restTracks = config.getFadeInRestore()
                    == SmpsSequencerConfig.FadeInRestore.REST_TRACKS;
            // S1 and S2 attenuate every playing FM and PSG track by the fade
            // depth (s2.sounddriver.asm:3098-3099, :3109, :3134;
            // s1.sounddriver.asm:2194, :2213). S3K attenuates only the FM
            // tracks, by 40h, and leaves the PSG volumes alone
            // (Sound/Z80 Sound Driver.asm:2767-2770).
            if (restTracks || track.type == TrackType.FM) {
                track.volumeOffset += steps;
                refreshVolume(track);
            }
            if (!track.active) {
                continue;
            }
            if (restTracks) {
                // S1/S2: mark the track at rest so the resumed song stays
                // silent until each track reads its own next note, rather
                // than holding the note that was sounding when the jingle
                // interrupted it (s2.sounddriver.asm:3107, :3131;
                // s1.sounddriver.asm:2193, :2211).
                track.resting = true;
                if (track.type == TrackType.PSG) {
                    // zPSGNoteOff / PSGNoteOff on each rested PSG track
                    // (s2.sounddriver.asm:3132, s1.sounddriver.asm:2212).
                    // The FM tracks get no key-off: the ROM re-sends their
                    // voice instead.
                    stopNote(track);
                }
            } else {
                // S3K silences by a different bit. zFadeInToPrevious ORs 84h
                // over every track and then clears bit 2 again on the FM ones
                // (Sound/Z80 Sound Driver.asm:2761-2770). Bit 2 is "SFX is
                // overriding this track" and bit 4 is "track is resting" in
                // this driver (Driver.asm:25, :27; zRestTrack at :4220-4223
                // sets bit 4 then tests bit 2), so 84h is bits 7 and 2,
                // playing plus overriding -- the routine's inline comment
                // naming it "playing and resting" is a mislabel. The PSG
                // tracks keep the overriding bit, which is what mutes them
                // through the fade; the FM tracks have it cleared whatever it
                // was before, and no track is rested.
                track.overridden = track.type == TrackType.PSG;
                if (track.type == TrackType.FM) {
                    // The ROM's per-track order is: clear the overriding bit,
                    // add 40h to the volume, then fetch and send the FM
                    // instrument (:2766-2772). The resend comes after the
                    // attenuation, so the voice reaches the chip already
                    // carrying the fade's starting level rather than the
                    // level the song had before the jingle interrupted it.
                    refreshInstrument(track);
                }
            }
        }
    }

    /**
     * Trigger a music fade-out. ROM equivalent: zFadeOutMusic.
     * Gradually increases volume attenuation over 'steps' frames with 'delay' frames between each step.
     * DAC track is stopped immediately (no volume control available).
     *
     * @param steps total number of volume steps (ROM default: 0x28 = 40)
     * @param delay frames between each volume step (ROM default: 3)
     */
    public void triggerFadeOut(int steps, int delay) {
        if (steps <= 0) {
            return;
        }
        fadeState.addFm = 1;
        fadeState.addPsg = 1;
        fadeState.fadeOut = true;
        setFadeSteps(steps);
        setFadeDelayReload(delay);
        setFadeDelayCounter(delay);
        fadeState.active = true;

        // Stop DAC track immediately (can't fade it) - matches ROM zFadeOutMusic
        for (Track track : tracks) {
            if (track.type == TrackType.DAC) {
                track.active = false;
                stopNote(track);
            }
        }
        if (config.getFadeDelayCadence()
                == SmpsSequencerConfig.FadeDelayCadence.DECREMENT_THEN_TEST) {
            // See fadeArmedOutsideService. Scoped to the driver whose ordering
            // has been checked against its listing; S1 and S2 keep the
            // engine's existing behaviour.
            fadeArmedOutsideService = true;
        }
        if (config.getFadeOutHalt() == SmpsSequencerConfig.FadeOutHalt.DAC_AND_PSG) {
            // zFadeOutMusic falls through into zHaltDACPSG, which zeroes the
            // playback control of PSG3, PSG1 and PSG2 alongside FM6/DAC and
            // then jumps to zPSGSilenceAll (Sound/Z80 Sound
            // Driver.asm:2307-2325). The halt writes nothing itself; the
            // silence comes from zPSGSilenceAll, so no note is stopped here.
            for (Track track : tracks) {
                if (track.type == TrackType.PSG) {
                    track.active = false;
                }
            }
        }
    }

    /**
     * Refresh all FM voice settings after being paused/restored.
     * This reloads instruments and pan/ams/fms settings to the hardware.
     */
    public void refreshAllVoices() {
        for (Track t : tracks) {
            if (!t.active)
                continue;
            if (t.type == TrackType.FM) {
                refreshInstrument(t);
                applyFmPanAmsFms(t);
            } else if (t.type == TrackType.PSG) {
                refreshVolume(t);
            }
        }
    }

    // -----------------------------------------------------------------------
    // CoordFlagContext implementation (remaining methods)
    // -----------------------------------------------------------------------

    @Override
    public SmpsProgramView programView() {
        return programView;
    }

    @Override
    public SmpsSequencerConfig getConfig() {
        return config;
    }

    @Override
    public void setNormalTempo(int tempo) {
        this.normalTempo = tempo;
    }

    @Override
    public int getNormalTempo() {
        return normalTempo;
    }

    @Override
    public void recalculateTempo() {
        calculateTempo();
    }

    @Override
    public void triggerFadeIn() {
        // Parameterless version: use config defaults
        triggerFadeIn(config.getFadeInSteps(), config.getFadeInDelay());
    }

    @Override
    public void setCommData(int value) {
        this.commData = value;
    }

    @Override
    public void writeFm(int port, int reg, int value) {
        synth.writeFm(this, port, reg, value);
    }

    @Override
    public void writePsg(int value) {
        synth.writePsg(this, value);
    }

    @Override
    public void playDac(int noteId) {
        synth.playDac(this, noteId);
    }

    @Override
    public void stopDac() {
        synth.stopDac(this);
    }

    // --- Continuous SFX (delegate to SmpsDriver if available) ---

    @Override
    public boolean isContinuousSfxFlagSet() {
        return host.isContinuousSfxFlagSet();
    }

    @Override
    public void clearContinuousSfxId() {
        host.clearContinuousSfxId();
    }

    @Override
    public void clearContinuousSfxFlag() {
        host.clearContinuousSfxFlag();
    }

    @Override
    public boolean decrementContSfxLoopCnt() {
        return host.decrementContSfxLoopCnt();
    }

    // -----------------------------------------------------------------------
    // Speed multiplier (S3K speed shoes)
    // -----------------------------------------------------------------------

    public void setSpeedMultiplier(int multiplier) {
        this.speedMultiplier = Math.max(1, multiplier);
        if (this.speedMultiplier <= 1) {
            this.speedupTimeout = 0;
        }
    }

    public int getSpeedMultiplier() {
        return speedMultiplier;
    }

    public DebugState debugState() {
        DebugState state = new DebugState();
        state.tempoWeight = tempoWeight;
        state.dividingTiming = dividingTiming;
        for (Track t : tracks) {
            DebugTrack dt = new DebugTrack();
            dt.type = t.type;
            dt.channelId = t.channelId;
            dt.active = t.active;
            dt.duration = t.duration;
            dt.rawDuration = t.rawDuration;
            dt.note = t.note;
            dt.voiceId = t.voiceId;
            dt.volumeOffset = t.volumeOffset;
            dt.keyOffset = t.keyOffset;
            dt.pan = t.pan;
            dt.ams = t.ams;
            dt.fms = t.fms;
            dt.envValue = t.envValue;
            dt.tieNext = t.tieNext;
            dt.modEnabled = t.modEnabled;
            dt.modAccumulator = t.modAccumulator;
            dt.detune = t.detune;
            dt.decayOffset = t.decayOffset;
            dt.loopCounter = (t.loopCounters != null && t.loopCounters.length > 0) ? t.loopCounters[0] : 0;
            dt.position = t.pos;
            dt.fill = t.fill;
            state.tracks.add(dt);
        }
        return state;
    }

    public SmpsSequencerSnapshot captureSnapshot() {
        List<SmpsTrackSnapshot> trackSnapshots = new ArrayList<>(tracks.size());
        for (Track track : tracks) {
            trackSnapshots.add(captureTrack(track));
        }
        return new SmpsSequencerSnapshot(
                region,
                speedShoes,
                sfxMode,
                normalTempo,
                commData,
                fm6DacOff,
                maxTicks,
                pitch,
                sfxPriority,
                specialSfx,
                isSfx,
                psgLatchChannel,
                speedMultiplier,
                speedupTimeout,
                new SmpsSequencerSnapshot.FadeSnapshot(
                        fadeState.steps,
                        fadeState.delayInit,
                        fadeState.delayCounter,
                        fadeState.addFm,
                        fadeState.addPsg,
                        fadeState.active,
                        fadeState.fadeOut),
                sampleRate,
                samplesPerFrame,
                sampleCounter,
                tempoWeight,
                tempoAccumulator,
                dividingTiming,
                primed,
                trackSnapshots);
    }

    /**
     * Restores backend-agnostic SMPS sequencing state only. This does not replay chip writes
     * or rebuild presentation state for an already-started audio backend.
     */
    public void restoreSnapshot(SmpsSequencerSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        region = snapshot.region();
        speedShoes = snapshot.speedShoes();
        sfxMode = snapshot.sfxMode();
        normalTempo = snapshot.normalTempo();
        commData = snapshot.commData();
        fm6DacOff = snapshot.fm6DacOff();
        maxTicks = snapshot.maxTicks();
        pitch = snapshot.pitch();
        sfxPriority = snapshot.sfxPriority();
        specialSfx = snapshot.specialSfx();
        isSfx = snapshot.sfx();
        psgLatchChannel = snapshot.psgLatchChannel();
        speedMultiplier = snapshot.speedMultiplier();
        speedupTimeout = snapshot.speedupTimeout();
        fadeState.steps = snapshot.fade().steps();
        fadeState.delayInit = snapshot.fade().delayInit();
        fadeState.delayCounter = snapshot.fade().delayCounter();
        fadeState.addFm = snapshot.fade().addFm();
        fadeState.addPsg = snapshot.fade().addPsg();
        fadeState.active = snapshot.fade().active();
        fadeState.fadeOut = snapshot.fade().fadeOut();
        sampleRate = snapshot.sampleRate();
        samplesPerFrame = snapshot.samplesPerFrame();
        sampleCounter = snapshot.sampleCounter();
        tempoWeight = snapshot.tempoWeight();
        tempoAccumulator = snapshot.tempoAccumulator();
        dividingTiming = snapshot.dividingTiming();
        primed = snapshot.primed();

        tracks.clear();
        for (SmpsTrackSnapshot trackSnapshot : snapshot.tracks()) {
            tracks.add(restoreTrack(trackSnapshot));
        }
    }

    private static SmpsTrackSnapshot captureTrack(Track track) {
        return new SmpsTrackSnapshot(
                track.pos,
                track.type,
                track.channelId,
                track.duration,
                track.note,
                track.active,
                track.overridden,
                track.rawDuration,
                track.scaledDuration,
                track.fill,
                track.fillCounter,
                track.resting,
                track.keyOffset,
                track.volumeOffset,
                track.tieNext,
                track.pan,
                track.ams,
                track.fms,
                track.voiceData,
                track.voiceScratch,
                track.voiceId,
                track.baseFnum,
                track.baseBlock,
                track.loopCounters,
                track.loopTarget,
                track.returnStack,
                track.returnSp,
                track.dividingTiming,
                track.modDelay,
                track.modDelayInit,
                track.modRate,
                track.modDelta,
                track.modSteps,
                track.modStepsFull,
                track.modPendingDelayInit,
                track.modPendingRate,
                track.modPendingDelta,
                track.modPendingSteps,
                track.modPendingStepsFull,
                track.modRateCounter,
                track.modStepCounter,
                track.modAccumulator,
                track.modCurrentDelta,
                track.modEnabled,
                track.customModEnabled,
                track.detune,
                track.modEnvId,
                track.modEnvData,
                track.modEnvPos,
                track.modEnvMult,
                track.modEnvCache,
                track.modEnvHold,
                track.rawFreqMode,
                track.rawFrequency,
                track.instrumentId,
                track.noiseMode,
                track.psgNoiseParam,
                track.decayOffset,
                track.decayTimer,
                track.envData,
                track.envPos,
                track.envValue,
                track.envHold,
                track.envAtRest,
                track.fmVolEnvData,
                track.fmVolEnvPos,
                track.fmVolEnvValue,
                track.fmVolEnvHold,
                track.fmVolEnvOpMask,
                track.forceRefresh,
                track.ssgEg,
                track.dacMuted,
                track.modStepInEffect,
                track.modStepChanged,
                track.modStepDelta,
                track.modEnvStepInEffect,
                track.modEnvStepChanged,
                track.modEnvStepDelta,
                track.fm3SpecialMode,
                track.customSsgEgPresent,
                track.customSsgEgPayload,
                track.customSsgEgPayloadKnown,
                track.rawPsgNoise,
                track.rawPsgNoiseKnown);
    }

    private static Track restoreTrack(SmpsTrackSnapshot snapshot) {
        Track track = new Track(snapshot.pos(), snapshot.type(), snapshot.channelId());
        restoreTrack(track, snapshot);
        return track;
    }

    private static void restoreTrack(
            Track track, SmpsTrackSnapshot snapshot) {
        track.pos = snapshot.pos();
        track.type = snapshot.type();
        track.channelId = snapshot.channelId();
        track.duration = snapshot.duration();
        track.note = snapshot.note();
        track.active = snapshot.active();
        track.overridden = snapshot.overridden();
        track.rawDuration = snapshot.rawDuration();
        track.scaledDuration = snapshot.scaledDuration();
        track.fill = snapshot.fill();
        track.fillCounter = snapshot.fillCounter();
        track.resting = snapshot.resting();
        track.keyOffset = snapshot.keyOffset();
        track.volumeOffset = snapshot.volumeOffset();
        track.tieNext = snapshot.tieNext();
        track.pan = snapshot.pan();
        track.ams = snapshot.ams();
        track.fms = snapshot.fms();
        track.voiceData = copy(snapshot.voiceData());
        copyInto(snapshot.voiceScratch(), track.voiceScratch);
        track.voiceId = snapshot.voiceId();
        track.baseFnum = snapshot.baseFnum();
        track.baseBlock = snapshot.baseBlock();
        track.loopCounters = copy(snapshot.loopCounters());
        track.loopTarget = snapshot.loopTarget();
        copyInto(snapshot.returnStack(), track.returnStack);
        track.returnSp = snapshot.returnSp();
        track.dividingTiming = snapshot.dividingTiming();
        track.modDelay = snapshot.modDelay();
        track.modDelayInit = snapshot.modDelayInit();
        track.modRate = snapshot.modRate();
        track.modDelta = snapshot.modDelta();
        track.modSteps = snapshot.modSteps();
        track.modStepsFull = snapshot.modStepsFull();
        track.modPendingDelayInit = snapshot.modPendingDelayInit();
        track.modPendingRate = snapshot.modPendingRate();
        track.modPendingDelta = snapshot.modPendingDelta();
        track.modPendingSteps = snapshot.modPendingSteps();
        track.modPendingStepsFull = snapshot.modPendingStepsFull();
        track.modRateCounter = snapshot.modRateCounter();
        track.modStepCounter = snapshot.modStepCounter();
        track.modAccumulator = snapshot.modAccumulator();
        track.modCurrentDelta = snapshot.modCurrentDelta();
        track.modEnabled = snapshot.modEnabled();
        track.customModEnabled = snapshot.customModEnabled();
        track.detune = snapshot.detune();
        track.modEnvId = snapshot.modEnvId();
        track.modEnvData = copy(snapshot.modEnvData());
        track.modEnvPos = snapshot.modEnvPos();
        track.modEnvMult = snapshot.modEnvMult();
        track.modEnvCache = snapshot.modEnvCache();
        track.modEnvHold = snapshot.modEnvHold();
        track.rawFreqMode = snapshot.rawFreqMode();
        track.rawFrequency = snapshot.rawFrequency();
        track.instrumentId = snapshot.instrumentId();
        track.noiseMode = snapshot.noiseMode();
        track.psgNoiseParam = snapshot.psgNoiseParam();
        track.decayOffset = snapshot.decayOffset();
        track.decayTimer = snapshot.decayTimer();
        track.envData = copy(snapshot.envData());
        track.envPos = snapshot.envPos();
        track.envValue = snapshot.envValue();
        track.envHold = snapshot.envHold();
        track.envAtRest = snapshot.envAtRest();
        track.fmVolEnvData = copy(snapshot.fmVolEnvData());
        track.fmVolEnvPos = snapshot.fmVolEnvPos();
        track.fmVolEnvValue = snapshot.fmVolEnvValue();
        track.fmVolEnvHold = snapshot.fmVolEnvHold();
        track.fmVolEnvOpMask = snapshot.fmVolEnvOpMask();
        track.forceRefresh = snapshot.forceRefresh();
        copyInto(snapshot.ssgEg(), track.ssgEg);
        track.dacMuted = snapshot.dacMuted();
        track.modStepInEffect = snapshot.modStepInEffect();
        track.modStepChanged = snapshot.modStepChanged();
        track.modStepDelta = snapshot.modStepDelta();
        track.modEnvStepInEffect = snapshot.modEnvStepInEffect();
        track.modEnvStepChanged = snapshot.modEnvStepChanged();
        track.modEnvStepDelta = snapshot.modEnvStepDelta();
        track.fm3SpecialMode = snapshot.fm3SpecialMode();
        track.customSsgEgPresent = snapshot.customSsgEgPresent();
        copyInto(snapshot.customSsgEgPayload(), track.customSsgEgPayload);
        track.customSsgEgPayloadKnown = snapshot.customSsgEgPayloadKnown();
        track.rawPsgNoise = snapshot.rawPsgNoise();
        track.rawPsgNoiseKnown = snapshot.rawPsgNoiseKnown();
    }

    private static byte[] copy(byte[] values) {
        return values == null ? null : Arrays.copyOf(values, values.length);
    }

    private static int[] copy(int[] values) {
        return values == null ? null : Arrays.copyOf(values, values.length);
    }

    private static void copyInto(byte[] source, byte[] target) {
        Arrays.fill(target, (byte) 0);
        if (source != null) {
            System.arraycopy(source, 0, target, 0, Math.min(source.length, target.length));
        }
    }

    private static void copyInto(int[] source, int[] target) {
        Arrays.fill(target, 0);
        if (source != null) {
            System.arraycopy(source, 0, target, 0, Math.min(source.length, target.length));
        }
    }

    public static class DebugState {
        public int tempoWeight;
        public int dividingTiming;
        public final List<DebugTrack> tracks = new ArrayList<>();
    }

    public static class DebugTrack {
        public TrackType type;
        public int channelId;
        public boolean active;
        public boolean overridden;
        public int duration;
        public int rawDuration;
        public int note;
        public int voiceId;
        public int volumeOffset;
        public int envValue;
        public int keyOffset;
        public int pan;
        public int ams;
        public int fms;
        public boolean tieNext;
        public boolean modEnabled;
        public short modAccumulator;
        public int detune;
        public int decayOffset;
        public int loopCounter;
        public int position;
        public int fill;
    }
}
