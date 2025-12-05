package uk.co.jamesj999.sonic.audio.synth;

import uk.co.jamesj999.sonic.audio.ChannelType;
import uk.co.jamesj999.sonic.audio.smps.DacData;

import java.util.HashSet;
import java.util.Set;

public class VirtualSynthesizer {
    private final PsgChip psg = new PsgChip();
    private final Ym2612Chip ym = new Ym2612Chip();
    private final Set<String> mutedChannels = new HashSet<>();

    public void setDacData(DacData data) {
        ym.setDacData(data);
    }

    public void playDac(int note) {
        if (!isMuted(ChannelType.DAC, 0)) {
            ym.playDac(note);
        }
    }

    public void stopDac() {
        ym.stopDac();
    }

    public void render(short[] buffer) {
        psg.render(buffer);
        ym.render(buffer);
    }

    public void writeFm(int port, int reg, int val) {
        ym.write(port, reg, val);
    }

    public void writePsg(int val) {
        psg.write(val);
    }

    public void setInstrument(int channelId, byte[] voice) {
        ym.setInstrument(channelId, voice);
    }

    public void setMute(ChannelType type, int channelId, boolean mute) {
        String key = type.name() + "-" + channelId;
        if (mute) {
            mutedChannels.add(key);
        } else {
            mutedChannels.remove(key);
        }

        // Propagate to chips
        if (type == ChannelType.FM) {
            ym.setChannelMute(channelId, mute);
        } else if (type == ChannelType.PSG) {
            psg.setChannelMute(channelId, mute);
        } else if (type == ChannelType.DAC) {
            ym.setDacMute(mute);
            if (mute) {
                stopDac();
            }
        }
    }

    private boolean isMuted(ChannelType type, int channelId) {
        return mutedChannels.contains(type.name() + "-" + channelId);
    }
}
