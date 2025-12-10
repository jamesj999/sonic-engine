package uk.co.jamesj999.sonic.audio.driver;

import uk.co.jamesj999.sonic.audio.AudioStream;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SmpsDriver extends VirtualSynthesizer implements AudioStream {
    private final List<SmpsSequencer> sequencers = new ArrayList<>();
    private final Set<SmpsSequencer> sfxSequencers = new HashSet<>();
    private final SmpsSequencer[] fmLocks = new SmpsSequencer[6];
    private final SmpsSequencer[] psgLocks = new SmpsSequencer[4];
    private final Map<Object, Integer> psgLatches = new HashMap<>();
    private SmpsSequencer.Region region = SmpsSequencer.Region.NTSC;

    public void setRegion(SmpsSequencer.Region region) {
        this.region = region;
        for (SmpsSequencer seq : sequencers) {
            seq.setRegion(region);
        }
    }

    public void addSequencer(SmpsSequencer seq, boolean isSfx) {
        seq.setRegion(region);
        sequencers.add(seq);
        if (isSfx) {
            sfxSequencers.add(seq);
        }
    }

    public void stopAll() {
        sequencers.clear();
        sfxSequencers.clear();
        for (int i=0; i<6; i++) fmLocks[i] = null;
        for (int i=0; i<4; i++) psgLocks[i] = null;
        psgLatches.clear();
    }

    @Override
    public int read(short[] buffer) {
        for (int i = 0; i < buffer.length; i++) {
            // Tick all sequencers
            for (int s = 0; s < sequencers.size(); s++) {
                SmpsSequencer seq = sequencers.get(s);
                seq.advance(1.0);
                if (seq.isComplete()) {
                    sequencers.remove(s);
                    s--;
                    releaseLocks(seq);
                    if (isSfx(seq)) sfxSequencers.remove(seq);
                }
            }
            
            // Render Synth
            short[] single = new short[1];
            // Since this class IS the synthesizer, we call the render logic.
            // But we extend VirtualSynthesizer which has render().
            super.render(single);
            buffer[i] = single[0];
        }
        return buffer.length;
    }
    
    @Override
    public boolean isComplete() {
        return sequencers.isEmpty();
    }
    
    private boolean isSfx(Object source) {
        return sfxSequencers.contains(source);
    }

    private void releaseLocks(SmpsSequencer seq) {
        for (int i = 0; i < 6; i++) {
            if (fmLocks[i] == seq) {
                fmLocks[i] = null;
                updateOverrides(SmpsSequencer.TrackType.FM, i, false);
            }
        }
        for (int i = 0; i < 4; i++) {
            if (psgLocks[i] == seq) {
                psgLocks[i] = null;
                updateOverrides(SmpsSequencer.TrackType.PSG, i, false);
            }
        }
        psgLatches.remove(seq);
    }

    private void updateOverrides(SmpsSequencer.TrackType type, int ch, boolean overridden) {
        for (SmpsSequencer s : sequencers) {
            if (!isSfx(s)) {
                s.setChannelOverridden(type, ch, overridden);
            }
        }
    }

    @Override
    public void writeFm(Object source, int port, int reg, int val) {
        int ch = -1;
        int rawReg = reg & 0xFF;
        
        // Map Register to Channel
        if (rawReg >= 0x30 && rawReg <= 0x9E) {
            ch = (rawReg & 0x03) + (port * 3);
        } else if (rawReg >= 0xA0 && rawReg <= 0xA2) {
            ch = (rawReg - 0xA0) + (port * 3);
        } else if (rawReg >= 0xA4 && rawReg <= 0xA6) {
            ch = (rawReg - 0xA4) + (port * 3);
        } else if (rawReg >= 0xB0 && rawReg <= 0xB2) {
            ch = (rawReg - 0xB0) + (port * 3);
        } else if (rawReg >= 0xB4 && rawReg <= 0xB6) {
            ch = (rawReg - 0xB4) + (port * 3);
        } else if (rawReg == 0x28) {
            // Key On/Off: 0x28 is Port 0 only.
            // Val: d7-d4 (slot mask), d2-d0 (channel). d2 (bit 4 of ch?) No.
            // Channel is 0-2 (0,1,2) or 4-6 (4,5,6).
            // Ym2612Chip: "if (chIdx >= 4) chIdx -= 1;" -> Maps 4,5,6 to 3,4,5.
            // So Ch 0,1,2 -> 0,1,2. Ch 4,5,6 -> 3,4,5.
            // We need linear channel 0-5.
            int c = val & 0x07;
            if (c >= 4) c -= 1;
            ch = c;
        }
        
        if (ch >= 0 && ch < 6) {
            if (isSfx(source)) {
                if (fmLocks[ch] != source) {
                    fmLocks[ch] = (SmpsSequencer) source;
                    updateOverrides(SmpsSequencer.TrackType.FM, ch, true);
                }
                super.writeFm(source, port, reg, val);
            } else {
                if (fmLocks[ch] == null) {
                    super.writeFm(source, port, reg, val);
                }
            }
        } else {
            // Global or unmapped
            super.writeFm(source, port, reg, val);
        }
    }

    @Override
    public void writePsg(Object source, int val) {
        if ((val & 0x80) != 0) {
            // Latch
            int ch = (val >> 5) & 0x03;
            psgLatches.put(source, ch);
            
            if (isSfx(source)) {
                if (psgLocks[ch] != source) {
                    psgLocks[ch] = (SmpsSequencer) source;
                    updateOverrides(SmpsSequencer.TrackType.PSG, ch, true);
                }
                super.writePsg(source, val);
            } else {
                if (psgLocks[ch] == null) {
                    super.writePsg(source, val);
                }
            }
        } else {
            // Data
            Integer ch = psgLatches.get(source);
            if (ch != null) {
                if (isSfx(source)) {
                    // Update lock just in case? Already locked by Latch.
                    if (psgLocks[ch] == (SmpsSequencer) source) {
                        super.writePsg(source, val);
                    }
                } else {
                    if (psgLocks[ch] == null) {
                        super.writePsg(source, val);
                    }
                }
            } else {
                // Unknown channel (no previous latch from this source), drop or pass?
                // Pass for safety/compatibility
                super.writePsg(source, val);
            }
        }
    }
    
    // Override other methods if needed (setInstrument calls writeFm, so it's covered)
    @Override
    public void setInstrument(Object source, int channelId, byte[] voice) {
        // Channel ID is passed explicitly.
        if (channelId >= 0 && channelId < 6) {
            if (isSfx(source)) {
                fmLocks[channelId] = (SmpsSequencer) source;
                super.setInstrument(source, channelId, voice);
            } else {
                if (fmLocks[channelId] == null) {
                    super.setInstrument(source, channelId, voice);
                }
            }
        }
    }
    
    @Override
    public void playDac(Object source, int note) {
        // DAC is on Channel 5 (FM6)
        int ch = 5;
        if (isSfx(source)) {
            if (fmLocks[ch] != source) {
                fmLocks[ch] = (SmpsSequencer) source;
                updateOverrides(SmpsSequencer.TrackType.FM, ch, true);
            }
            super.playDac(source, note);
        } else {
            if (fmLocks[ch] == null) {
                super.playDac(source, note);
            }
        }
    }
    
    @Override
    public void stopDac(Object source) {
        int ch = 5;
        if (isSfx(source)) {
            // Don't release lock here, just stop sound.
            // Lock is released when track ends or channel unused?
            // Actually, stopDac is just stopping sound.
            super.stopDac(source);
        } else {
            if (fmLocks[ch] == null) {
                super.stopDac(source);
            }
        }
    }
}
