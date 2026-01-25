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

    private final List<SmpsSequencer> pendingRemovals = new ArrayList<>();

    // Scratch buffer for read() to avoid per-frame allocations
    private final short[] scratchFrameBuf = new short[2];

    public SmpsDriver() {
        super();
    }

    public SmpsDriver(double outputSampleRate) {
        super(outputSampleRate);
    }

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
        for (int i = 0; i < 6; i++)
            fmLocks[i] = null;
        for (int i = 0; i < 4; i++)
            psgLocks[i] = null;
        psgLatches.clear();
        // Silence hardware (ROM: zFMSilenceAll + zPSGSilenceAll)
        silenceAll();
    }

    /**
     * Stop all SFX sequencers, releasing their channel locks and silencing them.
     * Used when starting override music to prevent partial SFX playback on restore.
     */
    public void stopAllSfx() {
        List<SmpsSequencer> sfxToRemove = new ArrayList<>(sfxSequencers);
        for (SmpsSequencer sfx : sfxToRemove) {
            sequencers.remove(sfx);
            releaseLocks(sfx);
            sfxSequencers.remove(sfx);
        }
    }

    @Override
    public int read(short[] buffer) {
        int frames = buffer.length / 2;

        for (int i = 0; i < frames; i++) {
            int size = sequencers.size();
            for (int j = 0; j < size; j++) {
                SmpsSequencer seq = sequencers.get(j);
                seq.advance(1.0);
                if (seq.isComplete()) {
                    pendingRemovals.add(seq);
                }
            }

            if (!pendingRemovals.isEmpty()) {
                for (int j = 0; j < pendingRemovals.size(); j++) {
                    SmpsSequencer seq = pendingRemovals.get(j);
                    sequencers.remove(seq);
                    releaseLocks(seq);
                    sfxSequencers.remove(seq);
                }
                pendingRemovals.clear();
            }

            super.render(scratchFrameBuf);
            buffer[i * 2] = scratchFrameBuf[0];
            buffer[i * 2 + 1] = scratchFrameBuf[1];
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
        boolean isSfx = isSfx(seq);
        for (int i = 0; i < 6; i++) {
            if (fmLocks[i] == seq) {
                // If this was an SFX, ensure the channel is silenced before handing it back.
                if (isSfx) {
                    seq.forceSilence(SmpsSequencer.TrackType.FM, i);
                }
                fmLocks[i] = null;
                updateOverrides(SmpsSequencer.TrackType.FM, i, false);
            }
        }
        for (int i = 0; i < 4; i++) {
            if (psgLocks[i] == seq) {
                if (isSfx) {
                    seq.forceSilence(SmpsSequencer.TrackType.PSG, i);
                }
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
            if (c >= 4)
                c -= 1;
            ch = c;
        }

        if (ch >= 0 && ch < 6) {
            if (isSfx(source)) {
                if (shouldStealLock(fmLocks[ch], (SmpsSequencer) source)) {
                    // Silence channel if stealing from music (not from another SFX or self)
                    if (fmLocks[ch] != source && !isSfx(fmLocks[ch])) {
                        silenceFmChannel(ch);
                    }
                    fmLocks[ch] = (SmpsSequencer) source;
                    updateOverrides(SmpsSequencer.TrackType.FM, ch, true);
                }

                if (fmLocks[ch] == source) {
                    super.writeFm(source, port, reg, val);
                }
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
                if (shouldStealLock(psgLocks[ch], (SmpsSequencer) source)) {
                    // Silence channel if stealing from music (not from another SFX or self)
                    if (psgLocks[ch] != source && !isSfx(psgLocks[ch])) {
                        silencePsgChannel(ch);
                    }
                    psgLocks[ch] = (SmpsSequencer) source;
                    updateOverrides(SmpsSequencer.TrackType.PSG, ch, true);
                }

                if (psgLocks[ch] == source) {
                    super.writePsg(source, val);
                }
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
                    if (shouldStealLock(psgLocks[ch], (SmpsSequencer) source)) {
                        // Silence channel if stealing from music (not from another SFX or self)
                        if (psgLocks[ch] != source && !isSfx(psgLocks[ch])) {
                            silencePsgChannel(ch);
                        }
                        psgLocks[ch] = (SmpsSequencer) source;
                        updateOverrides(SmpsSequencer.TrackType.PSG, ch, true);
                    }

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

    // Override other methods if needed (setInstrument calls writeFm, so it's
    // covered)
    @Override
    public void setInstrument(Object source, int channelId, byte[] voice) {
        // Channel ID is passed explicitly.
        if (channelId >= 0 && channelId < 6) {
            if (isSfx(source)) {
                if (shouldStealLock(fmLocks[channelId], (SmpsSequencer) source)) {
                    // Silence channel if stealing from music (not from another SFX or self)
                    if (fmLocks[channelId] != source && !isSfx(fmLocks[channelId])) {
                        silenceFmChannel(channelId);
                    }
                    fmLocks[channelId] = (SmpsSequencer) source;
                    updateOverrides(SmpsSequencer.TrackType.FM, channelId, true);
                }

                if (fmLocks[channelId] == source) {
                    super.setInstrument(source, channelId, voice);
                }
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
            if (shouldStealLock(fmLocks[ch], (SmpsSequencer) source)) {
                // Silence channel if stealing from music (not from another SFX or self)
                if (fmLocks[ch] != source && !isSfx(fmLocks[ch])) {
                    silenceFmChannel(5);
                    super.stopDac(null);
                }
                fmLocks[ch] = (SmpsSequencer) source;
                updateOverrides(SmpsSequencer.TrackType.FM, ch, true);
            }

            if (fmLocks[ch] == source) {
                super.playDac(source, note);
            }
        } else {
            if (fmLocks[ch] == null) {
                super.playDac(source, note);
            }
        }
    }

    private boolean shouldStealLock(SmpsSequencer currentLock, SmpsSequencer challenger) {
        if (currentLock == null)
            return true;
        if (currentLock == challenger)
            return true;
        if (!isSfx(currentLock))
            return true; // Challenger is SFX, current is Music -> Steal

        // Both are SFX. Use Z80 driver priority system:
        // Higher priority always wins. For equal priority, newer SFX (added later) wins.
        int currentPriority = currentLock.getSfxPriority();
        int challengerPriority = challenger.getSfxPriority();

        if (challengerPriority > currentPriority) {
            return true; // Higher priority always steals
        } else if (challengerPriority == currentPriority) {
            // Equal priority: newer SFX wins (prevents old SFX from stealing back)
            int currentIdx = sequencers.indexOf(currentLock);
            int challengerIdx = sequencers.indexOf(challenger);
            return challengerIdx > currentIdx;
        }
        return false; // Lower priority cannot steal
    }

    /**
     * Silence an FM channel before SFX takes it over from music.
     * This directly resets envelope state to prevent the "chirp" artifact
     * that occurs when SFX first samples inherit envelope state from the
     * previous music note.
     *
     * Unlike register writes (which would be overwritten by the subsequent
     * voice load), this directly resets the envelope counters to fully
     * silent state, ensuring the next Key On starts from a clean slate.
     */
    private void silenceFmChannel(int ch) {
        // Directly reset envelope state - this takes effect immediately
        // without needing audio samples to be rendered
        super.forceSilenceChannel(ch);

        // Also send Key Off via registers for completeness
        int port = (ch < 3) ? 0 : 1;
        int hwCh = ch % 3;
        int chVal = (port == 0) ? hwCh : (hwCh + 4);
        super.writeFm(null, 0, 0x28, 0x00 | chVal);
    }

    /**
     * Silence a PSG channel before SFX takes it over from music.
     * Sets volume to 0xF (silence).
     */
    private void silencePsgChannel(int ch) {
        if (ch >= 0 && ch <= 3) {
            super.writePsg(null, 0x80 | (ch << 5) | (1 << 4) | 0x0F);
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
