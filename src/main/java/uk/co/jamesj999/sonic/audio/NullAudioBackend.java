package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;

public class NullAudioBackend implements AudioBackend {
    @Override
    public void init() {}

    @Override
    public void playMusic(int musicId) {}

    @Override
    public void playSmps(AbstractSmpsData data, DacData dacData) {}

    @Override
    public void playSfxSmps(AbstractSmpsData data, DacData dacData) {}

    @Override
    public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch) {}

    @Override
    public void playSfx(String sfxName) {}

    @Override
    public void playSfx(String sfxName, float pitch) {}

    @Override
    public void stopPlayback() {}

    @Override
    public void toggleMute(ChannelType type, int channel) {}

    @Override
    public void toggleSolo(ChannelType type, int channel) {}

    @Override
    public boolean isMuted(ChannelType type, int channel) { return false; }

    @Override
    public boolean isSoloed(ChannelType type, int channel) { return false; }

    @Override
    public void setSpeedShoes(boolean enabled) {}

    @Override
    public void update() {}

    @Override
    public void destroy() {}
}
