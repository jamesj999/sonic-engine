package uk.co.jamesj999.sonic.audio;

import uk.co.jamesj999.sonic.audio.smps.SmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsLoader;
import uk.co.jamesj999.sonic.data.Rom;

import java.util.logging.Logger;

public class AudioManager {
    private static final Logger LOGGER = Logger.getLogger(AudioManager.class.getName());
    private static AudioManager instance;
    private AudioBackend backend;
    private SmpsLoader smpsLoader;

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
        this.smpsLoader = new SmpsLoader(rom);
    }

    public void playMusic(int musicId) {
        if (smpsLoader != null) {
            SmpsData data = smpsLoader.loadMusic(musicId);
            if (data != null) {
                backend.playSmps(data);
                return;
            }
        }
        backend.playMusic(musicId);
    }

    public void playSfx(String sfxName) {
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
