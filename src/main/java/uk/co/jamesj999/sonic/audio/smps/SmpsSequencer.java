package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.AudioStream;
import uk.co.jamesj999.sonic.audio.synth.Synthesizer;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

public class SmpsSequencer implements AudioStream {
    private static final Logger LOGGER = Logger.getLogger(SmpsSequencer.class.getName());
    private final AbstractSmpsData smpsData;
    private AbstractSmpsData fallbackVoiceData;
    private final byte[] data;
    private final Synthesizer synth;
    private final SmpsSequencerConfig config;
    private final int tempoModBase;
    private final List<Track> tracks = new ArrayList<>();
    private final int z80Base;

    public enum Region {
        NTSC(60.0), PAL(50.0);

        public final double frameRate;

        Region(double frameRate) {
            this.frameRate = frameRate;
        }
    }

    private Region region = Region.NTSC;
    private boolean speedShoes = false;
    private boolean sfxMode = false;
    private final boolean isSfx;
    private int normalTempo;
    private int commData = 0; // Communication byte (E2)
    private boolean fm6DacOff = false;
    private int maxTicks = Integer.MAX_VALUE;
    private float pitch = 1.0f;
    private int sfxPriority = 0x70; // Default SFX priority (Z80 driver uses 0x70 as common)

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public void setSfxPriority(int priority) {
        this.sfxPriority = priority;
    }

    public int getSfxPriority() {
        return sfxPriority;
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

    private static final double SAMPLE_RATE = 44100.0;
    // Base tempo weight is game/driver-specific (configured externally).
    private double samplesPerFrame = 44100.0 / 60.0;
    private double sampleCounter = 0;
    private int tempoWeight;
    private int tempoAccumulator;
    private int dividingTiming = 1;
    private boolean primed;

    // Scratch buffer for read() to avoid per-sample allocations
    private final short[] scratchSample = new short[1];

    // Speed-up tempos and channel orders are game/driver-specific (configurable).

    // F-Num table for Octave 4
    // Calculated using Z80 formula: round(freq * 1024 * 1024 * 2 / FM_Sample_Rate)
    // where FM_Sample_Rate = 53267 Hz (NTSC: 7670454 / 144)
    private static final int[] FNUM_TABLE = {
            606, 644, 683, 723, 766, 813, 860, 911, 965, 1023, 1084, 1148
    };
    // SMPSPlay DEF_PSGFREQ_68K table (register values). Slice from DEF_PSGFREQ_PRE
    // starting at index 12 (count 70).
    private static final int[] PSG_FREQ_TABLE = {
            0x356, 0x326, 0x2F9, 0x2CE, 0x2A5, 0x280, 0x25C, 0x23A, 0x21A, 0x1FB, 0x1DF, 0x1C4,
            0x1AB, 0x193, 0x17D, 0x167, 0x153, 0x140, 0x12E, 0x11D, 0x10D, 0x0FE, 0x0EF, 0x0E2,
            0x0D6, 0x0C9, 0x0BE, 0x0B4, 0x0A9, 0x0A0, 0x097, 0x08F, 0x087, 0x07F, 0x078, 0x071,
            0x06B, 0x065, 0x05F, 0x05A, 0x055, 0x050, 0x04B, 0x047, 0x043, 0x040, 0x03C, 0x039,
            0x036, 0x033, 0x030, 0x02D, 0x02B, 0x028, 0x026, 0x024, 0x022, 0x020, 0x01F, 0x01D,
            0x01B, 0x01A, 0x018, 0x017, 0x016, 0x015, 0x013, 0x012, 0x011, 0x010
    };

    // Carrier bitmask per YM2612 algorithm in YM operator order (Op1, Op2, Op3,
    // Op4) mapped to bits 0-3.
    // Works with opMap {0,2,1,3} to reach SMPS TL order (Op1, Op3, Op2, Op4).
    private static final int[] ALGO_OUT_MASK = { 0x08, 0x08, 0x08, 0x08, 0x0A, 0x0E, 0x0E, 0x0F };

    public enum TrackType {
        FM, PSG, DAC
    }

    private class Track {
        int pos;
        TrackType type;
        int channelId;
        int duration;
        int note;
        boolean active = true;
        boolean overridden = false; // Set if SFX stole the channel
        int rawDuration;
        int scaledDuration;
        int fill; // note-off shortening in ticks
        int keyOffset; // signed semitone displacement (E9)
        int volumeOffset; // attenuation applied to TL (FM) or volume (PSG)
        boolean tieNext; // E7 prevents next attack
        int pan = 0xC0; // default L+R bits set for YM (E0)
        int ams = 0;
        int fms = 0;
        byte[] voiceData; // last loaded voice
        int voiceId;
        int baseFnum;
        int baseBlock;
        int[] loopCounters = new int[4];
        int loopTarget = -1;
        // Z80 driver: Stack shares space with loop counters, grows down from offset 0x2A.
        // No hard limit but collision possible after ~5 calls. Using 16 for safety margin.
        final int[] returnStack = new int[16];
        int returnSp = 0;
        int dividingTiming = 1;
        // Modulation (F0)
        int modDelay;
        int modDelayInit;
        int modRate;
        int modDelta;
        int modSteps;
        int modRateCounter;
        int modStepCounter;
        int modAccumulator;
        int modCurrentDelta;
        boolean modEnabled;
        int detune;
        int modEnvId;
        int instrumentId;
        boolean noiseMode;
        int psgNoiseParam;
        int decayOffset;
        int decayTimer;
        // PSG Volume Envelope
        byte[] envData;
        int envPos;
        int envValue;
        boolean envHold;
        boolean envAtRest;
        boolean forceRefresh;
        // DAC mute state for fade-in
        boolean dacMuted;

        Track(int pos, TrackType type, int channelId) {
            this.pos = pos;
            this.type = type;
            this.channelId = channelId;
        }
    }

    public SmpsSequencer(AbstractSmpsData smpsData, DacData dacData, SmpsSequencerConfig config) {
        this(smpsData, dacData, new VirtualSynthesizer(), config);
    }

    public SmpsSequencer(AbstractSmpsData smpsData, DacData dacData, Synthesizer synth,
            SmpsSequencerConfig config) {
        this.smpsData = smpsData;
        this.isSfx = smpsData instanceof SmpsSfxData;
        this.data = smpsData.getData();
        this.synth = synth;
        this.config = Objects.requireNonNull(config, "config");
        this.tempoModBase = this.config.getTempoModBase();
        this.z80Base = smpsData.getZ80StartAddress();
        this.synth.setDacData(dacData);

        // Enable DAC (YM2612 Reg 2B = 0x80)
        synth.writeFm(this, 0, 0x2B, 0x80);

        dividingTiming = smpsData.getDividingTiming();
        if (dividingTiming == 0) {
            dividingTiming = 1;
        }
        normalTempo = smpsData.getTempo();

        // Initialize Region and Tempo
        setRegion(Region.NTSC);

        int z80Start = smpsData.getZ80StartAddress();

        if (smpsData instanceof SmpsSfxData sfxData) {
            initSfxTracks(sfxData, z80Start);
            setSfxMode(true);
            return;
        }

        int[] fmPointers = smpsData.getFmPointers();
        int[] psgPointers = smpsData.getPsgPointers();

        // FM tracks mapping
        int[] fmOrder = config.getFmChannelOrder();
        int[] psgOrder = config.getPsgChannelOrder();

        for (int i = 0; i < fmPointers.length; i++) {
            int chnVal = (i < fmOrder.length) ? fmOrder[i] : -1;

            // 0x16 or 0x10 is DAC
            if (chnVal == 0x16 || chnVal == 0x10) {
                // DAC Track
                int ptr = relocate(fmPointers[i], z80Start);
                if (ptr >= 0 && ptr < data.length) {
                    Track t = new Track(ptr, TrackType.DAC, 5); // DAC uses channel 5 (FM6) slot
                    t.dividingTiming = dividingTiming;
                    tracks.add(t);
                }
                continue;
            }

            // FM Channel
            int linearCh = mapFmChannel(chnVal);
            if (linearCh >= 0) {
                int ptr = relocate(fmPointers[i], z80Start);
                if (ptr < 0 || ptr >= data.length) {
                    continue;
                }
                Track t = new Track(ptr, TrackType.FM, linearCh);
                int[] fmKeys = smpsData.getFmKeyOffsets();
                int[] fmVols = smpsData.getFmVolumeOffsets();
                if (i < fmKeys.length) {
                    t.keyOffset = (byte) fmKeys[i];
                }
                if (i < fmVols.length) {
                    t.volumeOffset = fmVols[i];
                }
                t.dividingTiming = dividingTiming;
                loadVoice(t, 0); // default instrument
                tracks.add(t);
            }
        }

        // PSG tracks mapping
        for (int i = 0; i < psgPointers.length; i++) {
            int ptr = relocate(psgPointers[i], z80Start);
            if (ptr < 0 || ptr >= data.length) {
                continue;
            }

            int chnVal = (i < psgOrder.length) ? psgOrder[i] : -1;
            int linearCh = mapPsgChannel(chnVal);
            if (linearCh < 0) {
                // Fallback for extra channels (like Noise if mapped linearly)
                linearCh = i;
            }

            Track t = new Track(ptr, TrackType.PSG, linearCh);
            int[] psgKeys = smpsData.getPsgKeyOffsets();
            int[] psgVols = smpsData.getPsgVolumeOffsets();
            int[] psgMods = smpsData.getPsgModEnvs();
            int[] psgInsts = smpsData.getPsgInstruments();
            if (i < psgKeys.length) {
                t.keyOffset = (byte) psgKeys[i];
            }
            if (i < psgVols.length) {
                t.volumeOffset = psgVols[i];
            }
            if (i < psgMods.length) {
                t.modEnvId = psgMods[i];
            }
            if (i < psgInsts.length) {
                t.instrumentId = psgInsts[i];
                loadPsgEnvelope(t, t.instrumentId);
            }
            t.dividingTiming = dividingTiming;
            tracks.add(t);
        }
    }

    public AbstractSmpsData getSmpsData() {
        return smpsData;
    }

    /**
     * Optional: provide another SMPS data set (usually the currently playing music)
     * to supply instrument voices if this sequence has no local voice table.
     */
    public void setFallbackVoiceData(AbstractSmpsData fallbackVoiceData) {
        this.fallbackVoiceData = fallbackVoiceData;
    }

    /**
     * Force-silence a hardware channel that was previously owned by this sequencer.
     * Used by the driver when releasing SFX locks so stray tones don't linger
     * if there is no music track to immediately rewrite the channel.
     */
    public void forceSilence(TrackType type, int channelId) {
        if (type == TrackType.FM) {
            int hwCh = channelId;
            int port = (hwCh < 3) ? 0 : 1;
            int ch = hwCh % 3;
            int chVal = (port == 0) ? ch : (ch + 4);
            synth.writeFm(this, 0, 0x28, 0x00 | chVal); // key off
            if (hwCh == 5) {
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
        this.samplesPerFrame = SAMPLE_RATE / region.frameRate;
        calculateTempo();
    }

    public void setSpeedShoes(boolean active) {
        this.speedShoes = active;
        calculateTempo();
    }

    public void setFm6DacOff(boolean active) {
        this.fm6DacOff = active;
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
        this.samplesPerFrame = SAMPLE_RATE / region.frameRate;
        calculateTempo();

        // Safety: cap SFX to a reasonable tick budget so bad data doesn't hang forever.
        if (active) {
            this.maxTicks = 2048;
        } else {
            this.maxTicks = Integer.MAX_VALUE;
        }
    }

    public void setChannelOverridden(TrackType type, int channelId, boolean overridden) {
        for (Track t : tracks) {
            if (t.type == type && t.channelId == channelId) {
                boolean wasOverridden = t.overridden;
                t.overridden = overridden;
                if (wasOverridden && !overridden) {
                    if (!t.active)
                        continue;

                    // Channel released from SFX, restore instrument and volume
                    refreshInstrument(t);
                    if (t.type == TrackType.PSG) {
                        refreshVolume(t);
                    }
                    if (t.type == TrackType.FM) {
                        applyFmPanAmsFms(t);
                    }
                    if (t.duration > 0) {
                        restoreFrequency(t);
                    }
                }
            }
        }
    }

    private void restoreFrequency(Track t) {
        if (t.type == TrackType.PSG) {
            boolean noiseUsesTone2 = t.noiseMode && t.channelId == 2 && (t.psgNoiseParam & 0x03) == 0x03;
            boolean writeToneFreq = t.channelId < 3 && (!t.noiseMode || noiseUsesTone2);

            if (writeToneFreq) {
                int reg = t.baseFnum + t.modAccumulator + t.detune;
                if (reg > 1023)
                    reg = 1023;
                if (reg < 1)
                    reg = 1;

                if (pitch != 1.0f) {
                    reg = (int) (reg / pitch);
                    if (reg > 1023)
                        reg = 1023;
                    if (reg < 1)
                        reg = 1;
                }

                int data = reg & 0xF;
                int type = 0;
                int ch = t.channelId;
                synth.writePsg(this, 0x80 | (ch << 5) | (type << 4) | data);
                synth.writePsg(this, (reg >> 4) & 0x3F);
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

        int block = (packed >> 11) & 7;
        int fnum = packed & 0x7FF;

        int hwCh = t.channelId;
        int port = (hwCh < 3) ? 0 : 1;
        int ch = (hwCh % 3);
        writeFmFreq(port, ch, fnum, block);
    }

    public List<Track> getTracks() {
        return tracks;
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

        double multiplier = 1.0;
        if (region == Region.PAL && !smpsData.isPalSpeedupDisabled()) {
            multiplier = 1.2; // Compensate 50Hz by speeding up music
        }

        int weighted = (int) (base * multiplier);
        if (weighted > 0xFF)
            weighted = 0xFF;

        this.tempoWeight = weighted;
    }

    private int mapFmChannel(int val) {
        switch (val) {
            case 0:
                return 0; // FM1
            case 1:
                return 1; // FM2
            case 2:
                return 2; // FM3
            case 4:
                return 3; // FM4
            case 5:
                return 4; // FM5
            case 6:
                return 5; // FM6
            default:
                return -1;
        }
    }

    private int mapPsgChannel(int val) {
        switch (val) {
            case 0x80:
                return 0;
            case 0xA0:
                return 1;
            case 0xC0:
                return 2;
            default:
                return -1;
        }
    }

    private void initSfxTracks(SmpsSfxData sfxData, int z80Start) {
        int tickMult = sfxData.getTickMultiplier();
        if (tickMult <= 0) {
            tickMult = 1;
        }
        updateDividingTiming(tickMult);

        for (SmpsSfxData.SmpsSfxTrack entry : sfxData.getTrackEntries()) {
            int ptr = relocate(entry.pointer(), z80Start);
            if (ptr < 0 || ptr >= data.length) {
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
                // SFX should not inherit music state; center pan/AMS/FMS and preload voice 0 if
                // available.
                t.pan = 0xC0;
                t.ams = 0;
                t.fms = 0;
                loadVoice(t, 0);
                applyFmPanAmsFms(t);
            }
            tracks.add(t);
        }
    }

    private int relocate(int ptr, int z80Start) {
        if (ptr == 0)
            return -1;
        // Many Sonic 2 SMPS blobs use file-relative offsets already.
        if (ptr >= 0 && ptr < data.length) {
            return ptr;
        }
        if (z80Start > 0) {
            int offset = ptr - z80Start;
            if (offset >= 0 && offset < data.length) {
                return offset;
            }
        }
        return -1;
    }

    @Override
    public int read(short[] buffer) {
        if (!primed) {
            if (tempoWeight != 0) {
                tick();
            }
            primed = true;
        }

        if (tempoWeight == 0) {
            return buffer.length;
        }

        for (int i = 0; i < buffer.length; i++) {
            advance(1.0);
            if (synth instanceof VirtualSynthesizer) {
                ((VirtualSynthesizer) synth).render(scratchSample);
            }
            buffer[i] = scratchSample[0];
        }
        return buffer.length;
    }

    public void advance(double samples) {
        sampleCounter += samples;
        while (sampleCounter >= samplesPerFrame) {
            sampleCounter -= samplesPerFrame;
            processTempoFrame();
        }
    }

    private void tick() {
        for (Track t : tracks) {
            if (!t.active)
                continue;
            // Note: In SMPS, overridden tracks continue to process (tick) in the
            // background,
            // but their output is blocked (or overwritten) by the SFX.
            // SmpsDriver blocks the writes if locked.

            boolean wasActive = t.active;

            if (t.duration > 0) {
                t.duration--;

                if (t.fill > 0 && (t.scaledDuration - t.duration) >= t.fill && !t.tieNext) {
                    stopNote(t);
                }

                if (t.duration > 0) {
                    // Z80 driver order: PSG envelope (zPSGUpdateVolFX) THEN modulation (zDoModulation)
                    if (t.type == TrackType.PSG) {
                        processPsgEnvelope(t);
                    }
                    if ((t.type == TrackType.FM || t.type == TrackType.PSG) && t.modEnabled) {
                        applyModulation(t);
                    }
                    continue;
                }
            }

            while (t.duration == 0 && t.active) {
                if (t.pos >= data.length) {
                    t.active = false;
                    break;
                }

                int cmd = data[t.pos++] & 0xFF;

                // 0x00 is not a valid SMPS command/note - likely reading garbage
                if (cmd == 0x00) {
                    t.active = false;
                    break;
                }

                if (cmd >= 0xE0) {
                    handleFlag(t, cmd);
                    // Re-check bounds after handleFlag as it may have modified t.pos
                    if (t.pos < 0 || t.pos >= data.length) {
                        if (t.active) { // Only stop if still supposedly active
                            t.active = false;
                        }
                        break;
                    }
                } else if (cmd >= 0x80) {
                    t.note = cmd;
                    if (t.pos < data.length) {
                        int next = data[t.pos] & 0xFF;
                        if (next < 0x80) {
                            setDuration(t, next);
                            t.pos++;
                        } else {
                            reuseDuration(t);
                        }
                    }
                    playNote(t);
                    // Z80 driver calls zDoModulation on the same frame as note start.
                    // This ensures the wait counter begins decrementing immediately.
                    if ((t.type == TrackType.FM || t.type == TrackType.PSG) && t.modEnabled) {
                        applyModulation(t);
                    }
                    break;
                } else {
                    setDuration(t, cmd);
                    playNote(t);
                    // Z80 driver calls zDoModulation on the same frame as note start.
                    if ((t.type == TrackType.FM || t.type == TrackType.PSG) && t.modEnabled) {
                        applyModulation(t);
                    }
                    break;
                }
            }

            if (!t.active && wasActive) {
                stopNote(t);
            }
        }
    }

    private void processTempoFrame() {
        if (tempoWeight == 0) {
            return;
        }
        tempoAccumulator += tempoWeight;
        if (tempoAccumulator >= tempoModBase) {
            tempoAccumulator -= tempoModBase;
            processFade();
            tick();
            if (sfxMode) {
                maxTicks--;
                if (maxTicks <= 0) {
                    for (Track t : tracks) {
                        t.active = false;
                        stopNote(t);
                    }
                }
            }
        }
    }

    private void processFade() {
        if (!fadeState.active || fadeState.steps == 0) {
            return;
        }

        if (fadeState.delayCounter > 0) {
            fadeState.delayCounter--;
            return;
        }

        fadeState.delayCounter = fadeState.delayInit;
        fadeState.steps--;

        if (fadeState.steps == 0) {
            if (fadeState.fadeOut) {
                // Stop all tracks
                for (Track t : tracks) {
                    t.active = false;
                    stopNote(t);
                }
            } else {
                // Fade In complete - unmute DAC tracks
                for (Track t : tracks) {
                    if (t.type == TrackType.DAC) {
                        t.dacMuted = false;
                    }
                }
            }
            fadeState.active = false;
            return;
        }

        int dir = fadeState.fadeOut ? 1 : -1;

        for (Track t : tracks) {
            if (!t.active)
                continue;
            // Skip DAC tracks - they don't have volume control
            if (t.type == TrackType.DAC)
                continue;

            int add = (t.type == TrackType.PSG) ? fadeState.addPsg : fadeState.addFm;
            int change = add * dir;

            t.volumeOffset += change;
            refreshVolume(t);
        }
    }

    // handleFlag and other private methods...
    private void handleFlag(Track t, int cmd) {
        switch (cmd) {
            case 0xF2: // Stop
                t.active = false;
                stopNote(t);
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
                t.modEnabled = true;
                break;
            case 0xE0: // Pan
                setPanAmsFms(t);
                break;
            case 0xE1: // Detune
                setDetune(t);
                break;
            case 0xE2: // Set Communication (E2 xx)
                if (t.pos < data.length) {
                    commData = data[t.pos++] & 0xFF;
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
                if (t.pos < data.length) {
                    int insId = data[t.pos++] & 0xFF;
                    t.instrumentId = insId;
                    loadPsgEnvelope(t, insId);
                }
                break;
            case 0xEF:
                // Set Voice
                if (t.pos < data.length) {
                    int voiceId = data[t.pos++] & 0xFF;
                    loadVoice(t, voiceId);
                }
                break;
            case 0xEA:
                // Set main tempo
                if (t.pos < data.length) {
                    int newTempo = data[t.pos++] & 0xFF;
                    normalTempo = newTempo;
                    calculateTempo();
                    // Parity: EA (Tempo Set) resets the tempo accumulator/counter to the new tempo
                    // value
                    tempoAccumulator = tempoWeight;
                }
                break;
            case 0xEB:
                // Set dividing timing
                if (t.pos < data.length) {
                    int newDividingTiming = data[t.pos++] & 0xFF;
                    updateDividingTiming(newDividingTiming);
                }
                break;
            default:
                int params = flagParamLength(cmd);
                int advance = Math.min(params, data.length - t.pos);
                t.pos += advance;
                break;
        }
    }

    private int flagParamLength(int cmd) {
        switch (cmd) {
            case 0xE0:
            case 0xE1:
            case 0xE2:
            case 0xE5:
            case 0xE6:
            case 0xE8:
            case 0xE9:
            case 0xEA:
            case 0xEB:
            case 0xEC:
            case 0xED:
            case 0xF3:
            case 0xF5:
            case 0xEF:
                return 1;
            case 0xF6:
            case 0xF8:
                return 2;
            case 0xF0:
            case 0xF7:
                return 4;
            case 0xFD:
                return 2;
            // E3, E4, E7, EE, F1, F2, F4, F9 are 0 parameters
            default:
                return 0;
        }
    }

    private void handleFadeOut(Track t) {
        if (t.pos + 2 <= data.length) {
            fadeState.steps = data[t.pos++] & 0xFF;
            fadeState.delayInit = data[t.pos++] & 0xFF;
            fadeState.addFm = 1;
            fadeState.addPsg = 1;
            fadeState.delayCounter = fadeState.delayInit;
            fadeState.active = true;
            fadeState.fadeOut = true;
        }
    }

    public int getCommData() {
        return commData;
    }

    private int readPointer(Track t) {
        if (t.pos + 2 > data.length)
            return 0;
        int ptr = smpsData.read16(t.pos);
        t.pos += 2;
        return ptr;
    }

    private void handleJump(Track t) {
        int ptr = readPointer(t);
        int newPos = relocate(ptr, smpsData.getZ80StartAddress());
        if (newPos != -1) {
            t.pos = newPos;
        } else {
            t.active = false;
        }
    }

    private void handleLoop(Track t) {
        if (t.pos + 2 <= data.length) {
            int index = data[t.pos++] & 0xFF;
            int count = data[t.pos++] & 0xFF;
            int ptr = readPointer(t);
            int newPos = relocate(ptr, smpsData.getZ80StartAddress());
            if (newPos == -1) {
                t.active = false;
                return;
            }
            if (count == 0) {
                t.pos = newPos;
                return;
            }
            if (index >= t.loopCounters.length) {
                int[] newCounters = new int[Math.max(t.loopCounters.length * 2, index + 1)];
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
        int ptr = readPointer(t);
        int newPos = relocate(ptr, smpsData.getZ80StartAddress());
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
        if (t.pos + 4 <= data.length) {
            t.modDelayInit = data[t.pos++] & 0xFF;
            t.modDelay = t.modDelayInit;
            int rate = data[t.pos++] & 0xFF;
            t.modRate = (rate == 0) ? 256 : rate;
            t.modDelta = data[t.pos++];
            t.modSteps = data[t.pos++] & 0xFF;

            t.modRateCounter = t.modRate;
            t.modStepCounter = t.modSteps / 2;
            t.modAccumulator = 0;
            t.modCurrentDelta = t.modDelta;
            t.modEnabled = true;
        }
    }

    private void clearModulation(Track t) {
        t.modEnabled = false;
        t.modAccumulator = 0;
    }

    private void setPanAmsFms(Track t) {
        if (t.pos < data.length) {
            int val = data[t.pos++] & 0xFF;
            t.pan = ((val & 0x80) != 0 ? 0x80 : 0) | ((val & 0x40) != 0 ? 0x40 : 0);
            t.ams = (val >> 4) & 0x3;
            t.fms = val & 0x7;
            applyFmPanAmsFms(t);
        }
    }

    private void setVolumeOffset(Track t) {
        if (t.pos < data.length) {
            t.volumeOffset += (byte) data[t.pos++];
            refreshVolume(t);
        }
    }

    private void setFill(Track t) {
        if (t.pos < data.length) {
            t.fill = data[t.pos++] & 0xFF;
        }
    }

    private void setKeyOffset(Track t) {
        if (t.pos < data.length) {
            t.keyOffset += (byte) data[t.pos++];
        }
    }

    private void setPsgNoise(Track t) {
        if (t.pos < data.length) {
            int val = data[t.pos++] & 0x0F;
            t.noiseMode = true;
            t.psgNoiseParam = val;
            synth.writePsg(this, 0xE0 | (val & 0x0F));
        }
    }

    private void setPsgVolume(Track t) {
        if (t.pos < data.length) {
            t.volumeOffset += (byte) data[t.pos++];
            refreshVolume(t);
        }
    }

    private void setTempoWeight(int newTempo) {
        normalTempo = newTempo & 0xFF;
        calculateTempo();
    }

    private void setTrackDividingTiming(Track t) {
        if (t.pos < data.length) {
            int newDiv = data[t.pos++] & 0xFF;
            t.dividingTiming = newDiv;
        }
    }

    private void updateDividingTiming(int newDividingTiming) {
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

    private void loadVoice(Track t, int voiceId) {
        byte[] voice = smpsData.getVoice(voiceId);
        if (voice == null && fallbackVoiceData != null) {
            voice = fallbackVoiceData.getVoice(voiceId);
        }
        if (voice != null) {
            t.voiceData = voice;
            t.voiceId = voiceId;
            refreshInstrument(t);
        }
    }

    private void playNote(Track t) {
        if (t.note == 0x80) {
            stopNote(t);
            return;
        }

        if (t.forceRefresh) {
            refreshInstrument(t);
            t.forceRefresh = false;
        }

        if (t.type == TrackType.DAC) {
            // Skip DAC playback if muted during fade-in
            if (!t.dacMuted) {
                synth.playDac(this, t.note);
            }
            return;
        }

        int baseNoteOffset = (t.type == TrackType.PSG) ? smpsData.getPsgBaseNoteOffset() : smpsData.getBaseNoteOffset();
        int n = t.note - 0x81 + t.keyOffset + baseNoteOffset;
        if (n < 0)
            return;

        int octave = n / 12;
        int noteIdx = n % 12;

        if (t.type == TrackType.FM) {
            int hwCh = t.channelId;
            int port = (hwCh < 3) ? 0 : 1;
            int ch = (hwCh % 3);

            int fnum = FNUM_TABLE[noteIdx];
            int block = octave;

            if (block > 7) {
                int shift = block - 7;
                block = 7;
                fnum <<= shift;
                if (fnum > 0x7FF) {
                    fnum = 0x7FF;
                }
            } else {
                block &= 7;
            }

            t.baseFnum = fnum;
            t.baseBlock = block;

            if (t.modEnabled) {
                t.modDelay = t.modDelayInit;
                t.modRateCounter = t.modRate;
                t.modStepCounter = t.modSteps / 2;
                t.modAccumulator = 0;
                t.modCurrentDelta = t.modDelta;
            }

            int packed = (block << 11) | fnum;
            packed += t.detune;

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

            block = (packed >> 11) & 7;
            fnum = packed & 0x7FF;

            int chVal = (port == 0) ? ch : (ch + 4); // YM2612 0x28: bit2 selects upper port

            // SMPSPlay DoNoteOn: skip KEY_OFF and KEY_ON when tieNext (HOLD) is set.
            // This allows smpsNoAttack (E7) to work correctly for both music and SFX.
            if (!t.tieNext) {
                // [not in driver] turn DAC off when playing a note on FM6
                if (fm6DacOff && hwCh == 5) {
                    synth.writeFm(this, 0, 0x2B, 0x00);
                }

                synth.writeFm(this, 0, 0x28, 0x00 | chVal); // Key Off before frequency change
            }

            writeFmFreq(port, ch, fnum, block);
            applyFmPanAmsFms(t);

            if (!t.tieNext) {
                synth.writeFm(this, 0, 0x28, 0xF0 | chVal); // Key On after latching frequency/pan
                LOGGER.fine("FM KEY ON: chVal=" + Integer.toHexString(chVal) + " port=" + port + " fnum="
                        + Integer.toHexString(fnum) + " block=" + block + " note=" + Integer.toHexString(t.note));
            }
            t.tieNext = false;

        } else {
            // Use SMPSPlay PSG register table (Def_68k) for accuracy against the Sonic 2
            // driver definition.
            int psgNote = octave * 12 + noteIdx;
            if (psgNote < 0)
                psgNote = 0;
            if (psgNote >= PSG_FREQ_TABLE.length)
                psgNote = PSG_FREQ_TABLE.length - 1;
            int reg = PSG_FREQ_TABLE[psgNote];

            reg += t.detune;
            if (reg > 1023)
                reg = 1023;
            if (reg < 1)
                reg = 1;

            if (pitch != 1.0f) {
                reg = (int) (reg / pitch);
                if (reg > 1023)
                    reg = 1023;
                if (reg < 1)
                    reg = 1;
            }

            boolean noiseUsesTone2 = t.noiseMode && t.channelId == 2 && (t.psgNoiseParam & 0x03) == 0x03;
            boolean writeToneFreq = t.channelId < 3 && (!t.noiseMode || noiseUsesTone2);

            if (writeToneFreq) {
                int data = reg & 0xF;
                int type = 0;
                int ch = t.channelId;
                synth.writePsg(this, 0x80 | (ch << 5) | (type << 4) | data);
                synth.writePsg(this, (reg >> 4) & 0x3F);
                // Initialize modulation state for PSG slides
                t.baseFnum = reg;
            }

            if (t.modEnabled) {
                t.modDelay = t.modDelayInit;
                t.modRateCounter = t.modRate;
                t.modStepCounter = t.modSteps / 2;
                t.modAccumulator = 0;
                t.modCurrentDelta = t.modDelta;
            }
        }

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

        if (t.type == TrackType.PSG) {
            refreshVolume(t); // Apply the first envelope step immediately on note start
        }
    }

    private int getPitchSlideFreq(int freq) {
        // The Z80 SMPS driver does NOT have any pitch slide wrapping logic.
        // It directly adds modulation/detune to the frequency and lets the
        // hardware handle any overflow. Previous wrapping code here was
        // causing incorrect octave jumps during modulation (e.g., Gloop SFX).
        return freq;
    }

    private void stopNote(Track t) {
        if (t.type == TrackType.FM) {
            int hwCh = t.channelId;
            int port = (hwCh < 3) ? 0 : 1;
            int ch = hwCh % 3;
            int chVal = (port == 0) ? ch : (ch + 4);
            synth.writeFm(this, 0, 0x28, 0x00 | chVal); // Key On/Off is always on Port 0
        } else if (t.type == TrackType.DAC) {
            synth.stopDac(this);
        } else {
            if (t.channelId <= 3) {
                if (t.noiseMode && t.channelId == 2) {
                    synth.writePsg(this, 0x80 | (3 << 5) | (1 << 4) | 0x0F);
                } else {
                    synth.writePsg(this, 0x80 | (t.channelId << 5) | (1 << 4) | 0x0F);
                }
            }
        }
    }

    @Override
    public boolean isComplete() {
        for (Track t : tracks) {
            if (t.active)
                return false;
        }
        return true;
    }

    private void refreshVolume(Track t) {
        if (t.type == TrackType.FM) {
            refreshInstrument(t);
        } else if (t.type == TrackType.PSG) {
            if (t.envAtRest) {
                return;
            }
            int vol = 0x0F;
            if (t.note != 0x80) {
                vol = Math.min(0x0F, Math.max(0, t.volumeOffset + t.envValue));
            }
            int ch = t.channelId;
            if (t.noiseMode && ch == 2) {
                ch = 3;
            }
            if (ch <= 3) {
                synth.writePsg(this, 0x80 | (ch << 5) | (1 << 4) | vol);
            }
        }
    }

    private void loadPsgEnvelope(Track t, int id) {
        byte[] env = smpsData.getPsgEnvelope(id);
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
                if (val == 0x80) {
                    // HOLD (Sonic 2 driver definition)
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
                        int target = t.envData[t.envPos] & 0xFF;
                        t.envPos = target;
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

    private void refreshInstrument(Track t) {
        if (t.type != TrackType.FM || t.voiceData == null) {
            return;
        }
        byte[] voice = new byte[t.voiceData.length];
        System.arraycopy(t.voiceData, 0, voice, 0, voice.length);
        boolean hasTl = voice.length >= 25;
        // SMPS (S2) stores TL at the end of the 25-byte blob (bytes 21-24).
        int tlBase = hasTl ? 21 : -1;
        if (tlBase >= 0) {
            int algo = voice[0] & 0x07;
            int mask = ALGO_OUT_MASK[algo];
            // Mask bits are in slot order: bit0=Op1, bit1=Op3, bit2=Op2, bit3=Op4.
            // Voice data is in Slot Order (Op1, Op3, Op2, Op4). Identity mapping.
            // Voice TL bytes are stored in SMPS slot order: Op1, Op3, Op2, Op4.
            // Mask bits are YM operator order (Op1, Op2, Op3, Op4); TL bytes are SMPS order
            // (Op1, Op3, Op2, Op4).
            // Map mask bit -> TL index offset.
            int[] opMap = { 0, 2, 1, 3 };

            for (int op = 0; op < 4; op++) {
                if ((mask & (1 << op)) != 0) {
                    int idx = tlBase + opMap[op];
                    int tl = (voice[idx] & 0x7F) + t.volumeOffset;
                    tl &= 0x7F; // wrap like the Z80 interpreter (7-bit)
                    voice[idx] = (byte) tl;
                }
            }
        }
        synth.setInstrument(this, t.channelId, voice);
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
        if (isSfx) {
            int chVal = (port == 0) ? ch : (ch + 4);
            synth.writeFm(this, 0, 0x28, 0xF0 | (chVal & 0x0F));
            LOGGER.fine("FM KEY ON (freq latch): chVal=" + Integer.toHexString(chVal) + " fnum="
                    + Integer.toHexString(fnum) + " block=" + block);
        }
    }

    private void applyModulation(Track t) {
        if (!t.modEnabled)
            return;

        boolean changed = false;

        if (t.modDelay > 0) {
            t.modDelay--;
            return;
        }

        if (t.modRateCounter > 0) {
            t.modRateCounter--;
        }

        if (t.modRateCounter == 0) {
            t.modRateCounter = t.modRate;
            changed = true;

            if (t.modStepCounter == 0) {
                t.modStepCounter = t.modSteps;
                t.modCurrentDelta = -t.modCurrentDelta;
            } else {
                t.modStepCounter--;
                t.modAccumulator += t.modCurrentDelta;
            }
        }

        if (changed) {
            if (t.type == TrackType.FM) {
                int packed = (t.baseBlock << 11) | t.baseFnum;
                packed += t.modAccumulator + t.detune;

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

                int block = (packed >> 11) & 7;
                int fnum = packed & 0x7FF;

                int hwCh = t.channelId;
                int port = (hwCh < 3) ? 0 : 1;
                int ch = (hwCh % 3);
                writeFmFreq(port, ch, fnum, block);
            } else if (t.type == TrackType.PSG && t.channelId < 3) {
                boolean noiseUsesTone2 = t.noiseMode && t.channelId == 2 && (t.psgNoiseParam & 0x03) == 0x03;
                if (!t.noiseMode || noiseUsesTone2) {
                    int reg = t.baseFnum + t.modAccumulator + t.detune;
                    if (reg < 1)
                        reg = 1;
                    if (reg > 0x3FF)
                        reg = 0x3FF;

                    if (pitch != 1.0f) {
                        reg = (int) (reg / pitch);
                        if (reg < 1)
                            reg = 1;
                        if (reg > 0x3FF)
                            reg = 0x3FF;
                    }

                    int data = reg & 0xF;
                    int ch = t.channelId;
                    synth.writePsg(this, 0x80 | (ch << 5) | data);
                    synth.writePsg(this, (reg >> 4) & 0x3F);
                }
            }
        }
    }

    private void setDetune(Track t) {
        if (t.pos < data.length) {
            t.detune = data[t.pos++];
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
        AudioManager.getInstance().getBackend().restoreMusic();
    }

    public void triggerFadeIn(int steps, int delay) {
        // Start a fade in from current volume (silence) to normal
        fadeState.steps = steps;
        fadeState.delayInit = delay;
        fadeState.addFm = 1;
        fadeState.addPsg = 1;
        fadeState.delayCounter = fadeState.delayInit;
        fadeState.active = true;
        fadeState.fadeOut = false; // Fade IN

        // Add steps to existing volumeOffset (attenuate by 'steps'), then fade
        // decreases it.
        for (Track track : tracks) {
            // For DAC, mute during fade-in (no volume control available)
            if (track.type == TrackType.DAC) {
                track.dacMuted = true;
                stopNote(track);
                continue;
            }
            track.volumeOffset += steps;
            refreshVolume(track);
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
        fadeState.steps = steps;
        fadeState.delayInit = delay;
        fadeState.addFm = 1;
        fadeState.addPsg = 1;
        fadeState.delayCounter = delay;
        fadeState.active = true;
        fadeState.fadeOut = true;

        // Stop DAC track immediately (can't fade it) - matches ROM zFadeOutMusic
        for (Track track : tracks) {
            if (track.type == TrackType.DAC) {
                track.active = false;
                stopNote(track);
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

    public Synthesizer getSynthesizer() {
        return synth;
    }

    public synchronized DebugState debugState() {
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
        public int modAccumulator;
        public int detune;
        public int decayOffset;
        public int loopCounter;
        public int position;
        public int fill;
    }
}
