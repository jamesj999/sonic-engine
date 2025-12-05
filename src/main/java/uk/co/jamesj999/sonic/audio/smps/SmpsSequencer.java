package uk.co.jamesj999.sonic.audio.smps;

import uk.co.jamesj999.sonic.audio.AudioStream;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.ArrayList;
import java.util.List;

public class SmpsSequencer implements AudioStream {
    private final SmpsData smpsData;
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

    // F-Num table for Octave 4
    private static final int[] FNUM_TABLE = {
        617, 653, 692, 733, 777, 823, 872, 924, 979, 1037, 1099, 1164
    };

    enum TrackType { FM, PSG, DAC }

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
        // Simple modulation (F0) approximation
        int modDelay;
        int modStep;
        int modDepth;
        int modTimer;
        int modPos;
        boolean modEnabled;
        // PSG overrides
        int psgVolumeOverride = -1;

        Track(int pos, TrackType type, int channelId) {
            this.pos = pos;
            this.type = type;
            this.channelId = channelId;
        }
    }

    public SmpsSequencer(SmpsData smpsData, DacData dacData) {
        this(smpsData, dacData, new VirtualSynthesizer());
    }

    public SmpsSequencer(SmpsData smpsData, DacData dacData, VirtualSynthesizer synth) {
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

        // DAC track (channel 5) if present
        int dacPtr = relocate(smpsData.getDacPointer(), z80Start);
        if (dacPtr >= 0 && dacPtr < data.length) {
            Track t = new Track(dacPtr, TrackType.DAC, 5);
            t.dividingTiming = dividingTiming;
            tracks.add(t);
        }

        // FM tracks
        for (int i = 0; i < fmPointers.length; i++) {
            int ptr = relocate(fmPointers[i], z80Start);
            if (ptr < 0 || ptr >= data.length) {
                continue;
            }
            Track t = new Track(ptr, TrackType.FM, i);
            int[] fmKeys = smpsData.getFmKeyOffsets();
            int[] fmVols = smpsData.getFmVolumeOffsets();
            if (i < fmKeys.length) {
                t.keyOffset = (byte) fmKeys[i];
            }
            if (i < fmVols.length) {
                t.volumeOffset = fmVols[i];
            }
            t.dividingTiming = dividingTiming;
            loadVoice(t, 0); // default instrument to avoid silence before EF
            tracks.add(t);
        }

        for (int i = 0; i < psgPointers.length; i++) {
            int ptr = relocate(psgPointers[i], z80Start);
            if (ptr < 0 || ptr >= data.length) {
                continue;
            }
            Track t = new Track(ptr, TrackType.PSG, i);
            int[] psgKeys = smpsData.getPsgKeyOffsets();
            int[] psgVols = smpsData.getPsgVolumeOffsets();
            if (i < psgKeys.length) {
                t.keyOffset = (byte) psgKeys[i];
            }
            if (i < psgVols.length) {
                t.volumeOffset = psgVols[i];
            }
            t.dividingTiming = dividingTiming;
            tracks.add(t);
        }

        // Some manually constructed SMPS blobs only provide a single pointer in the "DAC" slot.
        // If no FM tracks were created but we have a valid pointer and at least one FM channel
        // declared, repurpose that pointer as an FM track on hardware channel 5 (DAC slot).
        boolean hasFmTrack = tracks.stream().anyMatch(t -> t.type == TrackType.FM);
        if (!hasFmTrack && smpsData.getChannels() > 0 && dacPtr > 0 && dacPtr < data.length) {
            Track dacTrack = tracks.stream()
                    .filter(t -> t.type == TrackType.DAC && t.pos == dacPtr)
                    .findFirst()
                    .orElse(null);
            if (dacTrack != null) {
                dacTrack.type = TrackType.FM;
                dacTrack.channelId = 5;
            } else {
                tracks.add(new Track(dacPtr, TrackType.FM, 5));
            }
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
            // Tempo zero stalls progression; render still runs synth mix (silence) without ticking.
            return buffer.length;
        }

        for (int i = 0; i < buffer.length; i++) {
            sampleCounter++;
            if (sampleCounter >= samplesPerFrame) {
                sampleCounter -= samplesPerFrame;
                processTempoFrame();
            }
            // Generate 1 sample
            short[] single = new short[1]; // Inefficient but simple for now
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
                if (t.duration == 0 && !t.tieNext) {
                    stopNote(t);
                }
                // Apply modulation during sustain for FM channels
                if (t.type == TrackType.FM && t.modEnabled) {
                    applyModulation(t);
                }
                continue;
            }

            // Read next command
            while (t.duration == 0 && t.active) {
                if (t.pos >= data.length) {
                    t.active = false;
                    break;
                }

                int cmd = data[t.pos++] & 0xFF;

                if (cmd >= 0xE0) {
                    // Flags
                    handleFlag(t, cmd);
                } else if (cmd >= 0x80) {
                    // Note
                    t.note = cmd;
                    // Check next byte for duration
                    if (t.pos < data.length) {
                        int next = data[t.pos] & 0xFF;
                        if (next < 0x80) {
                            setDuration(t, next);
                            t.pos++;
                        } else {
                            // Reuse previous duration if not specified.
                            reuseDuration(t);
                        }
                    }
                    playNote(t);
                    break; // Consumed time
                } else {
                    // Duration only
                    setDuration(t, cmd);
                    playNote(t);
                    break;
                }
            }

            // If the track became inactive during this tick (e.g., ran off the end without an explicit stop),
            // make sure we silence the channel. Otherwise the last PSG tone can linger.
            if (!t.active && wasActive) {
                stopNote(t);
            }
        }
    }

    private void processTempoFrame() {
        if (tempoWeight == 0) {
            return; // tempo zero halts progression
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
            case 0xE3: // Return (alt opcode)
                handleReturn(t);
                break;
            case 0xF6: // Jump (2 byte param)
                handleJump(t);
                break;
            case 0xF7: // Loop (4 byte param)
                handleLoop(t);
                break;
            case 0xF8: // Call (2 byte param)
                handleCall(t);
                break;
            case 0xF9: // Return from subroutine (S2: paired with F8 call)
                handleReturn(t);
                break;
            case 0xF0: // Modulation (4 byte param)
                handleModulation(t);
                break;
            case 0xF1: // Modulation on (resume)
                t.modEnabled = true;
                break;
            case 0xE0: // Pan
                setPanAmsFms(t);
                break;
            case 0xE1: // Detune (unused placeholder)
            case 0xE2: // Detune variant / comms
                if (t.pos < data.length) t.pos++;
                break;
            case 0xE5: // Tick multiplier (track-local)
                setTrackDividingTiming(t);
                break;
            case 0xE6: // Channel volume attenuation
                setVolumeOffset(t);
                break;
            case 0xE7: // Tie next note (prevent attack)
                t.tieNext = true;
                break;
            case 0xE8: // Note fill (release early)
                setFill(t);
                break;
            case 0xE9: // Key displacement
                setKeyOffset(t);
                break;
            case 0xEC: // PSG volume override (S2 uses EC)
                setPsgVolume(t);
                break;
            case 0xF3: // PSG Noise
                setPsgNoise(t);
                break;
            case 0xF4: // Modulation off
                clearModulation(t);
                break;
            case 0xF5: // PSG instrument (not emulated) - consume param
                if (t.pos < data.length) {
                    t.pos++;
                }
                break;
            case 0xEF:
                // Set Voice (1 byte param)
                if (t.pos < data.length) {
                    int voiceId = data[t.pos++] & 0xFF;
                    loadVoice(t, voiceId);
                }
                break;
            case 0xEA:
                // Set main tempo (immediate effect)
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
                // Unknown flag: consume known parameter length to keep parser in sync
                int params = flagParamLength(cmd);
                int advance = Math.min(params, data.length - t.pos);
                t.pos += advance;
                break;
        }
    }

    /**
     * Parameter lengths for SMPS (Sonic 2 Z80) coordination flags.
     * Counts bytes after the flag byte. Unknowns return 0.
     */
    private int flagParamLength(int cmd) {
        return switch (cmd) {
            // 1-byte params
            case 0xE0, // Pan/AMS/FMS
                 0xE1, // Detune
                 0xE2, // Set comm / detune variant
                 0xE5, // Tick multiplier (current track)
                 0xE6, // Channel volume offset
                 0xE8, // Note fill
                 0xE9, // Key displacement
                 0xEA, // Tempo weight
                 0xEB, // Dividing timing (all tracks)
                 0xEC, // PSG volume
                 0xED, // Ignore (consume param)
                 0xF3, // PSG noise
                 0xF5, // PSG instrument placeholder
                 0xEF  // Set voice
                    -> 1;
            // 2-byte params
            case 0xF6, // Jump
                 0xF8  // Call
                    -> 2;
            // 3-byte params

            // 4-byte params
            case 0xF0, // Modulation setup
                 0xF7  // Loop (index + count + ptr)
                    -> 4;
            // 0-byte params
            case 0xE3, // Return
                 0xE4, // Fade in
                 0xE7, // Hold
                 0xF1, // Mod on
                 0xF2, // Stop
                 0xF4, // Mod off
                 0xF9, 0xFA, 0xFB, 0xFC, 0xFD, 0xFE, 0xFF // End / no-op
                    -> 0;
            default -> 0;
        };
    }

    private void handleJump(Track t) {
        if (t.pos + 2 <= data.length) {
            int p1 = data[t.pos++] & 0xFF;
            int p2 = data[t.pos++] & 0xFF;
            int ptr = p1 | (p2 << 8); // Little Endian
            int newPos = relocate(ptr, smpsData.getZ80StartAddress());
            if (newPos != -1) {
                t.pos = newPos;
            } else {
                // Invalid jump, stop track
                t.active = false;
            }
        }
    }

    private void handleLoop(Track t) {
        if (t.pos + 4 <= data.length) {
            int index = data[t.pos++] & 0xFF;
            int count = data[t.pos++] & 0xFF;
            int p1 = data[t.pos++] & 0xFF;
            int p2 = data[t.pos++] & 0xFF;
            int ptr = p1 | (p2 << 8);
            int newPos = relocate(ptr, smpsData.getZ80StartAddress());
            if (newPos == -1) {
                t.active = false;
                return;
            }
            if (count == 0) {
                t.pos = newPos; // infinite loop
                return;
            }
            // Ensure capacity
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
        if (t.pos + 2 <= data.length) {
            int p1 = data[t.pos++] & 0xFF;
            int p2 = data[t.pos++] & 0xFF;
            int ptr = p1 | (p2 << 8);
            int newPos = relocate(ptr, smpsData.getZ80StartAddress());
            if (newPos == -1 || t.returnSp >= t.returnStack.length) {
                t.active = false;
                return;
            }
            t.returnStack[t.returnSp++] = t.pos;
            t.pos = newPos;
        }
    }

    private void handleReturn(Track t) {
        if (t.returnSp > 0) {
            t.pos = t.returnStack[--t.returnSp];
        } else {
            t.active = false;
        }
    }

    private void handleModulation(Track t) {
        if (t.pos + 4 <= data.length) {
            t.modDelay = data[t.pos++] & 0xFF;
            t.modStep = data[t.pos++] & 0xFF;
            t.modDepth = data[t.pos++] & 0xFF;
            t.modTimer = data[t.pos++] & 0xFF;
            t.modPos = 0;
            t.modEnabled = true;
        }
    }

    private void clearModulation(Track t) {
        t.modEnabled = false;
        t.modPos = 0;
        t.modTimer = 0;
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
            refreshInstrument(t);
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
            synth.writePsg(0xE0 | (val & 0x0F));
        }
    }

    private void setPsgVolume(Track t) {
        if (t.pos < data.length) {
            t.psgVolumeOverride = data[t.pos++] & 0x0F;
        }
    }

    private void setTempoWeight(int newTempo) {
        tempoWeight = newTempo & 0xFF;
        tempoAccumulator = 0;
    }

    private void setTrackDividingTiming(Track t) {
        if (t.pos < data.length) {
            int newDiv = data[t.pos++] & 0xFF;
            if (newDiv == 0) newDiv = 256; // Treat 0 as 256 ticks (driver behaviour)
            int elapsed = t.scaledDuration - t.duration;
            t.dividingTiming = newDiv;
            int newScaled = scaleDuration(t, t.rawDuration);
            int newRemaining = newScaled - elapsed;
            if (newRemaining < 0) newRemaining = 0;
            t.scaledDuration = newScaled;
            t.duration = newRemaining;
        }
    }

    private void updateDividingTiming(int newDividingTiming) {
        dividingTiming = newDividingTiming;
        for (Track track : tracks) {
            if (!track.active || track.scaledDuration == 0) {
                continue;
            }
            track.dividingTiming = newDividingTiming;
            int elapsed = track.scaledDuration - track.duration;
            int newScaledDuration = scaleDuration(track, track.rawDuration);
            int newRemaining = newScaledDuration - elapsed;
            if (newRemaining < 0) {
                newRemaining = 0;
            }
            track.scaledDuration = newScaledDuration;
            track.duration = newRemaining;
        }
    }

    private void setDuration(Track track, int rawDuration) {
        track.rawDuration = rawDuration;
        int scaled = scaleDuration(track, rawDuration);
        if (track.fill > 0) {
            scaled = Math.max(1, scaled - track.fill);
        }
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
        int scaled = (rawDuration * factor) & DURATION_MAX;
        if (scaled == 0) {
            // 0 duration represents 256 frames in S3K when timing is 0
            return 256;
        }
        return scaled;
    }

    private void loadVoice(Track t, int voiceId) {
        int voicePtr = smpsData.getVoicePtr();
        voicePtr = relocate(voicePtr, z80Base);
        if (voicePtr < 0 || voicePtr >= data.length) {
            return;
        }

        // Determine voice stride and length based on SMPS format (S1 vs S2)
        // Both now return 25 as standard stride.
        int voiceLen = smpsData.getFmVoiceLength();
        int offset = voicePtr + (voiceId * voiceLen);

        if (offset >= 0 && offset + voiceLen <= data.length) {
            byte[] voice = new byte[voiceLen];
            System.arraycopy(data, offset, voice, 0, voiceLen);

            // For Sonic 2 (Little Endian), the voice data is typically 21 bytes (Header + Regs)
            // but stored with 25-byte stride (padding at end).
            // However, it DOES NOT contain Total Level (TL) bytes at index 5.
            // Ym2612Chip.setInstrument() detects TL presence by checking if length >= 25.
            // Since we read 25 bytes, we must truncate it to 21 bytes for S2 to force the "No TL" mapping.
            if (smpsData.isLittleEndian()) {
                byte[] s2Voice = new byte[21];
                System.arraycopy(voice, 0, s2Voice, 0, 21);
                voice = s2Voice;
            }

            t.voiceData = voice;
            t.voiceId = voiceId;
            refreshInstrument(t);
        }
    }

    private void playNote(Track t) {
        if (t.note == 0x80) { // Rest
            stopNote(t);
            return;
        }

        if (t.type == TrackType.DAC) {
            synth.playDac(t.note);
            return;
        }

        // Map note to freq
        // 81 = C.
        int n = t.note - 0x81 + t.keyOffset;
        if (n < 0) return;

        int octave = n / 12;
        int noteIdx = n % 12;

        if (t.type == TrackType.FM) {
            // YM2612
            int hwCh = t.channelId;
            int port = (hwCh < 3) ? 0 : 1;
            int ch = (hwCh % 3);

            // FNum = Table[noteIdx]
            // Block = octave
            int fnum = FNUM_TABLE[noteIdx];
            int block = octave;

            // Handle high octaves by using Block 7 and higher F-Num (if possible)
            if (block > 7) {
                int shift = block - 7;
                block = 7;
                fnum <<= shift;
                // Clamp F-Num to max 11-bit value to prevent wrapping
                if (fnum > 0x7FF) {
                    fnum = 0x7FF;
                }
            } else {
                block &= 7;
            }

            t.baseFnum = fnum;
            t.baseBlock = block;

            // Write A4 (Block/FNumMSB) and A0 (FNumLSB)
            writeFmFreq(port, ch, fnum, block);

            // Apply pan/AMS/FMS control
            applyFmPanAmsFms(t);

            // Key On unless tied
            if (!t.tieNext) {
                int chVal = (hwCh >= 3) ? (hwCh + 1) : hwCh;
                synth.writeFm(0, 0x28, 0xF0 | chVal); // Key On all ops
            }
            t.tieNext = false;

        } else {
            // PSG
            // Freq in Hz
            // Table values are F-Num. Need Hz.
            // Hz = (FNum * Clock) / (72 * 2^(20-Block))
            // Clock 7670453.
            // For PSG, Block shifts.
            // Let's approximate.
            double freq = (FNUM_TABLE[noteIdx] * 7670453.0) / (72.0 * (1 << (20 - octave)));

            // PSG Register = 3579545 / (32 * freq)
            int reg = (int) (3579545.0 / (32.0 * freq));
            if (reg > 1023) reg = 1023;
            if (reg < 1) reg = 1;

            // Write Tone
            // Channel 0,1,2.
            if (t.channelId < 3) {
                // Latch Tone: 1cc0dddd
                int data = reg & 0xF;
                int type = 0;
                int ch = t.channelId;
                synth.writePsg(0x80 | (ch << 5) | (type << 4) | data);
                // Data: 00DDDDDD
                synth.writePsg((reg >> 4) & 0x3F);

                // Volume (respect override/atten)
                int vol = t.psgVolumeOverride >= 0 ? t.psgVolumeOverride : 0;
                vol = Math.min(0x0F, Math.max(0, vol + t.volumeOffset));
                synth.writePsg(0x80 | (ch << 5) | (1 << 4) | vol);
            }
        }
    }

    private void stopNote(Track t) {
        if (t.type == TrackType.FM) {
            int hwCh = t.channelId;
            int chVal = (hwCh >= 3) ? (hwCh + 1) : hwCh;
            synth.writeFm(0, 0x28, 0x00 | chVal); // Key Off
        } else if (t.type == TrackType.DAC) {
            synth.stopDac();
        } else {
            if (t.channelId < 3) {
                synth.writePsg(0x80 | (t.channelId << 5) | (1 << 4) | 0x0F); // Silence
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

    private void refreshInstrument(Track t) {
        if (t.type != TrackType.FM || t.voiceData == null) {
            return;
        }
        byte[] voice = new byte[t.voiceData.length];
        System.arraycopy(t.voiceData, 0, voice, 0, voice.length);
        // Apply volume offset to TL bytes if present (19-byte voices omit TL -> use 0)
        boolean hasTl = voice.length >= 25;
        int tlBase = hasTl ? 5 : -1;
        if (tlBase >= 0) {
            for (int op = 0; op < 4; op++) {
                int idx = tlBase + op;
                int tl = (voice[idx] & 0x7F) + t.volumeOffset;
                if (tl > 0x7F) tl = 0x7F;
                voice[idx] = (byte) tl;
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
        if (t.modDelay > 0) {
            t.modDelay--;
            return;
        }
        if (t.modTimer > 0) {
            t.modTimer--;
            return;
        }
        t.modTimer = t.modStep;
        t.modPos = (t.modPos + 1) & 0xFF;
        int signedDepth = (t.modPos & 0x80) != 0 ? -t.modDepth : t.modDepth;
        int fnum = t.baseFnum + signedDepth;
        if (fnum < 0) fnum = 0;
        if (fnum > 0x7FF) fnum = 0x7FF;
        int hwCh = t.channelId;
        int port = (hwCh < 3) ? 0 : 1;
        int ch = (hwCh % 3);
        writeFmFreq(port, ch, fnum, t.baseBlock);
    }

    /**
     * Debug snapshot of current sequencer state for overlays/diagnostics.
     */
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
            dt.modDepth = t.modDepth;
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
        public int modDepth;
        public int loopCounter;
        public int position;
    }
}
