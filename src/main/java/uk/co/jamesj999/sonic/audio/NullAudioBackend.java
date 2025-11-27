package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsData;

public class NullAudioBackend implements AudioBackend {
    @Override
    public void init() {}

    @Override
    public void playMusic(int musicId) {}

    @Override
    public void playSmps(SmpsData data, DacData dacData) {}

    @Override
    public void playSfxSmps(SmpsData data, DacData dacData) {}

    @Override
    public void playSfx(String sfxName) {}

    @Override
    public void update() {}

    @Override
    public void destroy() {}
}
