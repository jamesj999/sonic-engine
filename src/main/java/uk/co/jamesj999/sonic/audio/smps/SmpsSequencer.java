package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.audio.AudioStream;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.ArrayList;
import java.util.List;

public class SmpsSequencer implements AudioStream {
    private final AbstractSmpsData smpsData;
    private final byte[] data;
    private final VirtualSynthesizer synth;
    private final List<Track> tracks = new ArrayList<>();
    private final int z80Base;
    private static final double DEFAULT_FRAME_RATE = 60.0; // Approx 60Hz VBlank
    private static final int TEMPO_MOD_BASE = 256; // Sonic 2 main tempo duty cycle
    private static final int DURATION_MAX = 0xFF;
    private double samplesPerFrame = 44100.0 / DEFAULT_FRAME_RATE;
    private int tempoWeight = TEMPO_MOD_BASE;
    private int tempoAccumulator = TEMPO_MOD_BASE;
    private int dividingTiming = 1;
    private double sampleCounter = 0;
    private boolean primed;

    // Default Sonic 2 Channel Order (from SMPSPlay loader_def.c)
    private static final int[] FM_CHN_ORDER = {0x16, 0, 1, 2, 4, 5, 6};
    private static final int[] PSG_CHN_ORDER = {0x80, 0xA0, 0xC0};

    // F-Num table for Octave 4
    private static final int[] FNUM_TABLE = {
        617, 653, 692, 733, 777, 823, 872, 924, 979, 1037, 1099, 1164
    };

    private static final int[] ALGO_OUT_MASK = {0x08, 0x08, 0x08, 0x08, 0x0C, 0x0E, 0x0E, 0x0F};

    public enum TrackType { FM, PSG, DAC }

    private class Track {
        int pos;
        TrackType type;
        int channelId;
        int duration;
        int note;
        boolean active = true;
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
        final int[] returnStack = new int[4];
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
        int decayOffset;
        int decayTimer;
        // PSG Volume Envelope
        byte[] envData;
        int envPos;
        int envValue;
        boolean envHold;

        Track(int pos, TrackType type, int channelId) {
            this.pos = pos;
            this.type = type;
            this.channelId = channelId;
        }
    }

    public SmpsSequencer(AbstractSmpsData smpsData, DacData dacData) {
        this(smpsData, dacData, new VirtualSynthesizer());
    }

    public SmpsSequencer(AbstractSmpsData smpsData, DacData dacData, VirtualSynthesizer synth) {
        this.smpsData = smpsData;
        this.data = smpsData.getData();
        this.synth = synth;
        this.z80Base = smpsData.getZ80StartAddress();
        this.synth.setDacData(dacData);

        // Enable DAC (YM2612 Reg 2B = 0x80)
        synth.writeFm(0, 0x2B, 0x80);

        dividingTiming = smpsData.getDividingTiming();
        int tempo = smpsData.getTempo();
        tempoWeight = tempo & 0xFF;
        tempoAccumulator = 0;

        int z80Start = smpsData.getZ80StartAddress();
        int[] fmPointers = smpsData.getFmPointers();
        int[] psgPointers = smpsData.getPsgPointers();

        // FM tracks mapping
        for (int i = 0; i < fmPointers.length; i++) {
            int chnVal = (i < FM_CHN_ORDER.length) ? FM_CHN_ORDER[i] : -1;

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

            int chnVal = (i < PSG_CHN_ORDER.length) ? PSG_CHN_ORDER[i] : -1;
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

    private int mapFmChannel(int val) {
        switch (val) {
            case 0: return 0; // FM1
            case 1: return 1; // FM2
            case 2: return 2; // FM3
            case 4: return 3; // FM4
            case 5: return 4; // FM5
            case 6: return 5; // FM6
            default: return -1;
        }
    }

    private int mapPsgChannel(int val) {
        switch (val) {
            case 0x80: return 0;
            case 0xA0: return 1;
            case 0xC0: return 2;
            default: return -1;
        }
    }

    private int relocate(int ptr, int z80Start) {
        if (ptr == 0) return -1;
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
            sampleCounter++;
            if (sampleCounter >= samplesPerFrame) {
                sampleCounter -= samplesPerFrame;
                processTempoFrame();
            }
            short[] single = new short[1];
            synth.render(single);
            buffer[i] = single[0];
        }
        return buffer.length;
    }

    private void tick() {
        for (Track t : tracks) {
            if (!t.active) continue;
            boolean wasActive = t.active;

            if (t.duration > 0) {
                t.duration--;

                if (t.fill > 0 && t.duration <= t.fill && !t.tieNext) {
                    stopNote(t);
                }

                if (t.duration > 0) {
                    if (t.type == TrackType.FM && t.modEnabled) {
                        applyModulation(t);
                    }
                    if (t.type == TrackType.PSG) {
                        processPsgEnvelope(t);
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

                if (cmd >= 0xE0) {
                    handleFlag(t, cmd);
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
                    break;
                } else {
                    setDuration(t, cmd);
                    playNote(t);
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
        while (tempoAccumulator >= TEMPO_MOD_BASE) {
            tempoAccumulator -= TEMPO_MOD_BASE;
            tick();
        }
    }

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
            case 0xF9: // SND_OFF (was Return in some versions, but Sonic 2 is SND_OFF)
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
            case 0xE2: // Detune variant / comms
                if (t.pos < data.length) t.pos++;
                break;
            case 0xE4: // Fade in (stop track placeholder)
                handleFadeIn(t);
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
                    setTempoWeight(newTempo);
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
            case 0xE0: case 0xE1: case 0xE2: case 0xE5: case 0xE6:
            case 0xE8: case 0xE9: case 0xEA: case 0xEB: case 0xEC:
            case 0xED: case 0xF3: case 0xF5: case 0xEF:
                return 1;
            case 0xF6: case 0xF8:
                return 2;
            case 0xF0: case 0xF7:
                return 4;
            default:
                return 0;
        }
    }

    private int readPointer(Track t) {
        if (t.pos + 2 > data.length) return 0;
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
        if (t.type != TrackType.FM) return;
        int hwCh = t.channelId;
        int port = (hwCh < 3) ? 0 : 1;
        int ch = (hwCh % 3);
        // Write 0x0F to RR of Ops 3 (Slot 1) and 4 (Slot 3)
        // 0x88 + ch = Op 3
        // 0x8C + ch = Op 4
        synth.writeFm(port, 0x88 + ch, 0x0F);
        synth.writeFm(port, 0x8C + ch, 0x0F);
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
            int val = data[t.pos++] & 0xFF;
            t.noiseMode = true;
            synth.writePsg(0xE0 | (val & 0x0F));
        }
    }

    private void setPsgVolume(Track t) {
        if (t.pos < data.length) {
            t.volumeOffset += (byte) data[t.pos++];
            refreshVolume(t);
        }
    }

    private void setTempoWeight(int newTempo) {
        tempoWeight = newTempo & 0xFF;
        tempoAccumulator = 0;
    }

    private void setTrackDividingTiming(Track t) {
        if (t.pos < data.length) {
            int newDiv = data[t.pos++] & 0xFF;
            if (newDiv == 0) newDiv = 256;
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
        int factor = track.dividingTiming == 0 ? 256 : track.dividingTiming;
        int scaled = rawDuration * factor;
        if (scaled == 0) {
            return 256;
        }
        return scaled;
    }

    private void loadVoice(Track t, int voiceId) {
        byte[] voice = smpsData.getVoice(voiceId);
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

        if (t.type == TrackType.DAC) {
            synth.playDac(t.note);
            return;
        }

        // Adjust for Base Note.
        // Sonic 2 (Little Endian) uses Base Note B (+1), meaning Note 0x81 maps to index 1.
        int baseNoteOffset = smpsData.getBaseNoteOffset();
        int n = t.note - 0x81 + t.keyOffset + baseNoteOffset;
        if (n < 0) return;

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

            // Pitch Slide / Wrapping logic
            int packed = (block << 11) | fnum;
            packed += t.detune;

            packed = getPitchSlideFreq(packed);

            block = (packed >> 11) & 7;
            fnum = packed & 0x7FF;

            // Re-trigger / Key Off if not legato
            if (!t.tieNext) {
                int chVal = (hwCh >= 3) ? (hwCh + 1) : hwCh;
                synth.writeFm(0, 0x28, 0x00 | chVal);
            }

            writeFmFreq(port, ch, fnum, block);
            applyFmPanAmsFms(t);

            if (!t.tieNext) {
                int chVal = (hwCh >= 3) ? (hwCh + 1) : hwCh;
                synth.writeFm(0, 0x28, 0xF0 | chVal);
            }
            t.tieNext = false;

        } else {
            // PSG
            // Octave fixed: +1
            double freq = (FNUM_TABLE[noteIdx] * 7670453.0) / (72.0 * (1 << (20 - (octave + 1))));

            int reg = (int) (3579545.0 / (32.0 * freq));
            if (reg > 1023) reg = 1023;
            if (reg < 1) reg = 1;

            reg += t.detune;
            if (reg > 1023) reg = 1023;
            if (reg < 1) reg = 1;

            if (t.channelId < 3 && !t.noiseMode) {
                int data = reg & 0xF;
                int type = 0;
                int ch = t.channelId;
                synth.writePsg(0x80 | (ch << 5) | (type << 4) | data);
                synth.writePsg((reg >> 4) & 0x3F);

                int vol = Math.min(0x0F, Math.max(0, t.volumeOffset));
                synth.writePsg(0x80 | (ch << 5) | (1 << 4) | vol);
            } else if (t.channelId == 2 && t.noiseMode) {
                // Channel 2 (Tone 2) used as Noise (Control Ch3)
                // We typically only update volume for noise channel here
                // unless frequency control is needed (handled by F3 or Freq/Ch2)
                int vol = Math.min(0x0F, Math.max(0, t.volumeOffset));
                synth.writePsg(0x80 | (3 << 5) | (1 << 4) | vol);
            } else if (t.channelId == 3) {
                // Direct Channel 3 access?
                int vol = Math.min(0x0F, Math.max(0, t.volumeOffset + t.envValue));
                synth.writePsg(0x80 | (3 << 5) | (1 << 4) | vol);
            }
        }

        t.decayOffset = 0;
        t.decayTimer = 0;
        // Reset Envelope
        t.envPos = 0;
        t.envHold = false;
        // Process first tick of envelope immediately?
        // SMPS typically updates envelope on next tick, but we should init value.
        // Let's execute one step of envelope logic if possible to set initial volume
        if (t.envData != null && t.envData.length > 0) {
             int val = t.envData[0] & 0xFF;
             if (val < 0x80) {
                 t.envValue = val;
                 t.envPos = 1;
             }
        } else {
            t.envValue = 0;
        }
    }

    private int getPitchSlideFreq(int freq) {
        // Based on SMPSPlay DoPitchSlide logic
        // Base FNum for low Octave is approx 0x269 (617)
        int baseFreq = 0x269;
        int lowFreq = baseFreq;
        int highFreq = baseFreq * 2;
        int freqFixDown = 0x7FF - baseFreq;
        int freqFixUp = 0x800 - baseFreq;

        int octFreq = freq & 0x7FF;
        if (octFreq < lowFreq) {
             freq -= freqFixDown;
        } else if (octFreq > highFreq) {
             freq += freqFixUp;
        }
        return freq;
    }

    private void stopNote(Track t) {
        if (t.type == TrackType.FM) {
            int hwCh = t.channelId;
            int chVal = (hwCh >= 3) ? (hwCh + 1) : hwCh;
            synth.writeFm(0, 0x28, 0x00 | chVal);
        } else if (t.type == TrackType.DAC) {
            synth.stopDac();
        } else {
            if (t.channelId <= 3) {
                // If noise mode, silence noise channel?
                // Or just silence the channel ID.
                if (t.noiseMode && t.channelId == 2) {
                     synth.writePsg(0x80 | (3 << 5) | (1 << 4) | 0x0F);
                } else {
                     synth.writePsg(0x80 | (t.channelId << 5) | (1 << 4) | 0x0F);
                }
            }
        }
    }

    @Override
    public boolean isComplete() {
        for (Track t : tracks) {
            if (t.active) return false;
        }
        return true;
    }

    private void refreshVolume(Track t) {
        if (t.type == TrackType.FM) {
            refreshInstrument(t);
        } else if (t.type == TrackType.PSG) {
            int vol = Math.min(0x0F, Math.max(0, t.volumeOffset + t.envValue));
            int ch = t.channelId;
            if (t.noiseMode && ch == 2) {
                ch = 3;
            }
            if (ch <= 3) {
                synth.writePsg(0x80 | (ch << 5) | (1 << 4) | vol);
            }
        }
    }

    private void loadPsgEnvelope(Track t, int id) {
        byte[] env = smpsData.getPsgEnvelope(id);
        if (env != null) {
            t.envData = env;
            t.envPos = 0;
            t.envHold = false;
            t.envValue = 0; // Reset value or keep? Reset seems safer.
        } else {
            t.envData = null;
            t.envValue = 0;
        }
    }

    private void processPsgEnvelope(Track t) {
        if (t.envData == null || t.envHold) return;

        if (t.envPos < t.envData.length) {
            int val = t.envData[t.envPos] & 0xFF;
            if (val < 0x80) {
                t.envValue = val; // Absolute or Relative? SMPS Z80 uses relative to volume, but the value itself is absolute attenuation from envelope
                // wait, "EnvVol = Trk->VolEnvCache". "FinalVol = Trk->Volume + EnvVol".
                // So the envelope value is added to the track volume.
                // The byte in the envelope IS the value.
                t.envPos++;
                refreshVolume(t);
            } else {
                // Command
                if (val == 0x80) { // Hold
                    t.envHold = true;
                } else {
                    // Treat others as hold for now
                    t.envHold = true;
                }
            }
        } else {
            t.envHold = true; // End of data
        }
    }

    private void refreshInstrument(Track t) {
        if (t.type != TrackType.FM || t.voiceData == null) {
            return;
        }
        byte[] voice = new byte[t.voiceData.length];
        System.arraycopy(t.voiceData, 0, voice, 0, voice.length);
        boolean hasTl = voice.length >= 25;
        int tlBase = hasTl ? 5 : -1;
        if (tlBase >= 0) {
            int algo = voice[0] & 0x07;
            int mask = ALGO_OUT_MASK[algo];
            // Mask bits use Slot Order (Bit 0=Slot 0/Op1, Bit 1=Slot 1/Op3, Bit 2=Slot 2/Op2, Bit 3=Slot 3/Op4).
            // Voice array uses Logical Operator Order (1, 2, 3, 4).
            // Mapping:
            // Bit 0 (Op1) -> Idx 0
            // Bit 1 (Op3) -> Idx 2
            // Bit 2 (Op2) -> Idx 1
            // Bit 3 (Op4) -> Idx 3
            int[] opMap = {0, 2, 1, 3};

            for (int op = 0; op < 4; op++) {
                if ((mask & (1 << op)) != 0) {
                    int idx = tlBase + opMap[op];
                    // Correct wrapping for TL volume (adding volume offset = more attenuation)
                    int tl = (voice[idx] & 0x7F) + t.volumeOffset;
                    // Clamp to 0-127 instead of wrapping, as negative volumeOffset (louder) + 0 should be 0 (max loud), not 127-ish.
                    if (tl < 0) tl = 0;
                    if (tl > 127) tl = 127;
                    voice[idx] = (byte) tl;
                }
            }
        }
        synth.setInstrument(t.channelId, voice);
    }

    private void applyFmPanAmsFms(Track t) {
        if (t.type != TrackType.FM) return;
        int hwCh = t.channelId;
        int port = (hwCh < 3) ? 0 : 1;
        int ch = (hwCh % 3);
        int reg = 0xB4 + ch;
        int val = (t.pan & 0xC0) | ((t.ams & 0x3) << 4) | (t.fms & 0x7);
        synth.writeFm(port, reg, val);
    }

    private void writeFmFreq(int port, int ch, int fnum, int block) {
        int valA4 = (block << 3) | ((fnum >> 8) & 0x7);
        int valA0 = fnum & 0xFF;
        synth.writeFm(port, 0xA4 + ch, valA4);
        synth.writeFm(port, 0xA0 + ch, valA0);
    }

    private void applyModulation(Track t) {
        if (!t.modEnabled) return;

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
                // Hold one step at peak
                // We do not add to accumulator this time
            } else {
                t.modStepCounter--;
                t.modAccumulator += t.modCurrentDelta;
            }
        }

        if (changed) {
            // Calculate packed frequency (Block|FNum) to support octave wrapping
            int packed = (t.baseBlock << 11) | t.baseFnum;
            packed += t.modAccumulator + t.detune;

            packed = getPitchSlideFreq(packed);

            int block = (packed >> 11) & 7;
            int fnum = packed & 0x7FF;

            int hwCh = t.channelId;
            int port = (hwCh < 3) ? 0 : 1;
            int ch = (hwCh % 3);
            writeFmFreq(port, ch, fnum, block);
        }
    }

    private void setDetune(Track t) {
        if (t.pos < data.length) {
            t.detune = data[t.pos++];
        }
    }

    private void handleFadeIn(Track t) {
        t.active = false;
        stopNote(t);
    }

    public VirtualSynthesizer getSynthesizer() {
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
            dt.tieNext = t.tieNext;
            dt.modEnabled = t.modEnabled;
            dt.modAccumulator = t.modAccumulator;
            dt.detune = t.detune;
            dt.decayOffset = t.decayOffset;
            dt.loopCounter = (t.loopCounters != null && t.loopCounters.length > 0) ? t.loopCounters[0] : 0;
            dt.position = t.pos;
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
        public int duration;
        public int rawDuration;
        public int note;
        public int voiceId;
        public int volumeOffset;
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
    }
}
