package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.Sonic2SmpsLoader;
import uk.co.jamesj999.sonic.data.Rom;

import java.util.logging.Logger;

public class AudioManager {
    private static final Logger LOGGER = Logger.getLogger(AudioManager.class.getName());
    private static AudioManager instance;
    private AudioBackend backend;
    private Sonic2SmpsLoader smpsLoader;
    private DacData dacData;

    private AudioManager() {
        // Default to NullBackend
        backend = new NullAudioBackend();
    }

    public static synchronized AudioManager getInstance() {
        if (instance == null) {
            instance = new AudioManager();
        }
        return instance;
    }

    public void setBackend(AudioBackend backend) {
        if (this.backend != null) {
            this.backend.destroy();
        }
        this.backend = backend;
        try {
            this.backend.init();
            LOGGER.info("AudioBackend initialized: " + backend.getClass().getSimpleName());
        } catch (Exception e) {
            LOGGER.severe("Failed to initialize AudioBackend: " + e.getMessage());
            e.printStackTrace();
            this.backend = new NullAudioBackend();
        }
    }

    public void setRom(Rom rom) {
        this.smpsLoader = new Sonic2SmpsLoader(rom);
        this.dacData = smpsLoader.loadDacData();
    }

    public void playMusic(int musicId) {
        if (smpsLoader != null) {
            AbstractSmpsData data = smpsLoader.loadMusic(musicId);
            if (data != null) {
                backend.playSmps(data, dacData);
                return;
            }
        }
        backend.playMusic(musicId);
    }

    public void playSfx(String sfxName) {
        if (smpsLoader != null) {
            AbstractSmpsData sfx = smpsLoader.loadSfx(sfxName);
            if (sfx != null) {
                backend.playSfxSmps(sfx, dacData);
                return;
            }
        }
        backend.playSfx(sfxName);
    }

    public void update() {
        backend.update();
    }

    public void destroy() {
        if (backend != null) {
            backend.destroy();
        }
    }
}
