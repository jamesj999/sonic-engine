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

    enum TrackType { FM, PSG }

    private class Track {
        int pos;
        TrackType type;
        int channelId;
        int duration;
        int note;
        boolean active = true;
        int rawDuration;
        int scaledDuration;

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
        this.synth.setDacData(dacData);

        // Enable DAC (YM2612 Reg 2B = 0x80)
        synth.writeFm(0, 0x2B, 0x80);

        dividingTiming = smpsData.getDividingTiming();
        int tempo = smpsData.getTempo();
        tempoWeight = tempo & 0xFF;
        tempoAccumulator = 0;

        if (data.length > 6) {
            int fmCount = data[2] & 0xFF;
            int psgCount = data[3] & 0xFF;
            int offset = 6;

            for (int i = 0; i < fmCount; i++) {
                if (offset + 1 >= data.length) break;
                int ptr = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
                tracks.add(new Track(ptr, TrackType.FM, i));
                offset += 4;
            }
            for (int i = 0; i < psgCount; i++) {
                if (offset + 1 >= data.length) break;
                int ptr = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
                tracks.add(new Track(ptr, TrackType.PSG, i));
                offset += 6;
            }
        }
    }

    @Override
    public int read(short[] buffer) {
        if (!primed) {
            tick();
            primed = true;
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

            if (t.duration > 0) {
                t.duration--;
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
        }
    }

    private void processTempoFrame() {
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
            case 0xE1:
                // Freq displacement (1 byte param)
                t.pos++;
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
                break;
        }
    }

    private void setTempoWeight(int newTempo) {
        tempoWeight = newTempo & 0xFF;
    }

    private void updateDividingTiming(int newDividingTiming) {
        dividingTiming = newDividingTiming;
        for (Track track : tracks) {
            if (!track.active || track.scaledDuration == 0) {
                continue;
            }
            int elapsed = track.scaledDuration - track.duration;
            int newScaledDuration = scaleDuration(track.rawDuration);
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
        track.scaledDuration = scaleDuration(rawDuration);
        track.duration = track.scaledDuration;
    }

    private void reuseDuration(Track track) {
        if (track.rawDuration == 0) {
            track.rawDuration = 1;
        }
        setDuration(track, track.rawDuration);
    }

    private int scaleDuration(int rawDuration) {
        int factor = dividingTiming == 0 ? 256 : dividingTiming;
        int scaled = (rawDuration * factor) & DURATION_MAX;
        if (scaled == 0) {
            // 0 duration represents 256 frames in S3K when timing is 0
            return 256;
        }
        return scaled;
    }

    private void loadVoice(Track t, int voiceId) {
        if (t.type != TrackType.FM) return;

        int voicePtr = smpsData.getVoicePtr();
        // Voice is 25 bytes.
        int offset = voicePtr + (voiceId * 25);
        if (offset >= 0 && offset + 25 <= data.length) {
            byte[] voice = new byte[25];
            System.arraycopy(data, offset, voice, 0, 25);
            int hwCh = getHwChannel(t.channelId);
            synth.setInstrument(hwCh, voice);
        }
    }

    private void playNote(Track t) {
        if (t.note == 0x80) { // Rest
            stopNote(t);
            return;
        }

        // Check for DAC (Track 0)
        if (t.type == TrackType.FM && t.channelId == 0) {
            synth.playDac(t.note);
            return;
        }

        // Map note to freq
        // 81 = C.
        int n = t.note - 0x81;
        if (n < 0) return;

        int octave = n / 12;
        int noteIdx = n % 12;

        if (t.type == TrackType.FM) {
            // YM2612
            int hwCh = getHwChannel(t.channelId);
            int port = (hwCh < 3) ? 0 : 1;
            int ch = (hwCh % 3);

            // FNum = Table[noteIdx]
            // Block = octave
            int fnum = FNUM_TABLE[noteIdx];
            int block = octave & 7;

            // Write A4 (Block/FNumMSB) and A0 (FNumLSB)
            int valA4 = (block << 3) | ((fnum >> 8) & 0x7);
            int valA0 = fnum & 0xFF;

            synth.writeFm(port, 0xA4 + ch, valA4);
            synth.writeFm(port, 0xA0 + ch, valA0);

            // Key On
            int chVal = (hwCh >= 3) ? (hwCh + 1) : hwCh;
            synth.writeFm(0, 0x28, 0xF0 | chVal); // Key On all ops

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

                // Volume On (0)
                synth.writePsg(0x80 | (ch << 5) | (1 << 4) | 0x00);
            }
        }
    }

    private void stopNote(Track t) {
        if (t.type == TrackType.FM) {
            int hwCh = getHwChannel(t.channelId);
            int chVal = (hwCh >= 3) ? (hwCh + 1) : hwCh;
            synth.writeFm(0, 0x28, 0x00 | chVal); // Key Off
        } else {
            if (t.channelId < 3) {
                synth.writePsg(0x80 | (t.channelId << 5) | (1 << 4) | 0x0F); // Silence
            }
        }
    }

    private int getHwChannel(int trackId) {
        // Sonic 2 Mapping: Track 0->FM6, 1->FM1, 2->FM2, 3->FM3, 4->FM4, 5->FM5
        switch (trackId) {
            case 0: return 5;
            case 1: return 0;
            case 2: return 1;
            case 3: return 2;
            case 4: return 3;
            case 5: return 4;
            default: return 0;
        }
    }

    @Override
    public boolean isComplete() {
        for (Track t : tracks) {
            if (t.active) return false;
        }
        return true;
    }
}
