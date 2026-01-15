package uk.co.jamesj999.sonic.audio;

import com.jogamp.openal.AL;
import com.jogamp.openal.ALC;
import com.jogamp.openal.ALFactory;
import com.jogamp.openal.ALCcontext;
import com.jogamp.openal.ALCdevice;
import com.jogamp.common.nio.Buffers;

import uk.co.jamesj999.sonic.audio.driver.SmpsDriver;
import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencerConfig;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JOALAudioBackend implements AudioBackend {
    private static final Logger LOGGER = Logger.getLogger(JOALAudioBackend.class.getName());

    private AL al;
    private ALC alc;
    private ALCdevice device;
    private ALCcontext context;

    private final Map<String, Integer> buffers = new HashMap<>();
    private final List<Integer> sfxSources = new ArrayList<>();
    private int musicSource = -1;

    private AudioStream currentStream;
    private AudioStream sfxStream;
    private int[] streamBuffers;
    private static final int STREAM_BUFFER_COUNT = 3;
    private static final int STREAM_BUFFER_SIZE = 1024;
    private SmpsSequencer currentSmps;
    private SmpsDriver smpsDriver;

    private static class MusicState {
        final AudioStream stream;
        final SmpsSequencer smps;
        final SmpsDriver driver;
        final int musicId;

        MusicState(AudioStream stream, SmpsSequencer smps, SmpsDriver driver, int musicId) {
            this.stream = stream;
            this.smps = smps;
            this.driver = driver;
            this.musicId = musicId;
        }
    }

    private final Deque<MusicState> musicStack = new ArrayDeque<>();
    private int currentMusicId = -1;
    private volatile boolean pendingRestore = false;

    // Fallback mappings
    private final Map<Integer, String> musicFallback = new HashMap<>();
    private final Map<String, String> sfxFallback = new HashMap<>();

    // Mute/Solo State
    private final boolean[] fmUserMutes = new boolean[6];
    private final boolean[] fmUserSolos = new boolean[6];
    private final boolean[] psgUserMutes = new boolean[4];
    private final boolean[] psgUserSolos = new boolean[4];

    private boolean speedShoesEnabled = false;
    private GameAudioProfile audioProfile;
    private SmpsSequencerConfig smpsConfig;

    public JOALAudioBackend() {
        // Initialize fallback mappings
        // SFX
        sfxFallback.put("JUMP", "sfx/jump.wav");
        sfxFallback.put("RING", "sfx/ring.wav");
        sfxFallback.put("SPINDASH", "sfx/spindash.wav");
        sfxFallback.put("SKID", "sfx/skid.wav");
    }

    @Override
    public void setAudioProfile(GameAudioProfile profile) {
        this.audioProfile = profile;
        this.smpsConfig = profile != null ? profile.getSequencerConfig() : null;
    }

    @Override
    public void init() {
        try {
            al = ALFactory.getAL();
            alc = ALFactory.getALC();

            String deviceSpec = null;
            device = alc.alcOpenDevice(deviceSpec);
            if (device == null) {
                throw new RuntimeException("Could not open ALC device");
            }

            context = alc.alcCreateContext(device, null);
            if (context == null) {
                throw new RuntimeException("Could not create ALC context");
            }

            alc.alcMakeContextCurrent(context);

            if (al.alGetError() != AL.AL_NO_ERROR) {
                throw new RuntimeException("AL Error during init");
            }

            LOGGER.info("OpenAL Initialized. Buffer Size: " + STREAM_BUFFER_SIZE);

            // Preload SFX
            for (String sfxPath : sfxFallback.values()) {
                loadWav(sfxPath);
            }

            // Generate music source
            int[] src = new int[1];
            al.alGenSources(1, src, 0);
            musicSource = src[0];

        } catch (Throwable t) {
            LOGGER.log(Level.SEVERE, "JOAL Init failed", t);
            throw new RuntimeException(t);
        }
    }

    @Override
    public void playMusic(int musicId) {
        LOGGER.info("Requesting Music ID: " + Integer.toHexString(musicId));
        stopStream(); // Stop any running stream
        clearMusicStack();
        currentMusicId = -1;

        // Try fallback map first
        String filename = musicFallback.get(musicId);
        if (filename == null) {
            // Default naming convention
            filename = "music/" + Integer.toHexString(musicId).toUpperCase() + ".wav";
        }

        playWav(filename, musicSource, true);
    }

    @Override
    public void playSmps(AbstractSmpsData data, DacData dacData) {
        int musicId = data.getId();
        boolean isOverride = audioProfile != null && audioProfile.isMusicOverride(musicId);
        if (isOverride) {
            pushCurrentState();

            // Just disconnect the current driver from the source without stopping/clearing
            // it.
            al.alSourceStop(musicSource);
            al.alSourcei(musicSource, AL.AL_BUFFER, 0);
            currentStream = null;
            currentSmps = null;
            smpsDriver = null;
        } else {
            stopStream();
            // Stop music source if playing wav
            al.alSourceStop(musicSource);
            clearMusicStack();
        }

        smpsDriver = new SmpsDriver();

        // Configure Region
        String regionStr = SonicConfigurationService.getInstance().getString(SonicConfiguration.REGION);
        if ("PAL".equalsIgnoreCase(regionStr)) {
            smpsDriver.setRegion(SmpsSequencer.Region.PAL);
        } else {
            smpsDriver.setRegion(SmpsSequencer.Region.NTSC);
        }

        boolean dacInterpolate = SonicConfigurationService.getInstance().getBoolean(SonicConfiguration.DAC_INTERPOLATE);
        smpsDriver.setDacInterpolate(dacInterpolate);

        boolean fm6DacOff = SonicConfigurationService.getInstance().getBoolean(SonicConfiguration.FM6_DAC_OFF);

        SmpsSequencer seq = new SmpsSequencer(data, dacData, smpsDriver, requireSmpsConfig());
        seq.setSpeedShoes(speedShoesEnabled);
        seq.setFm6DacOff(fm6DacOff);
        // Music is the primary voice source for SFX fallback
        seq.setFallbackVoiceData(data);
        smpsDriver.addSequencer(seq, false);
        currentSmps = seq;
        currentMusicId = musicId;

        updateSynthesizerConfig();
        currentStream = smpsDriver;
        startStream();
    }

    @Override
    public void playSfxSmps(AbstractSmpsData data, DacData dacData) {
        playSfxSmps(data, dacData, 1.0f);
    }

    @Override
    public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch) {
        boolean dacInterpolate = SonicConfigurationService.getInstance().getBoolean(SonicConfiguration.DAC_INTERPOLATE);
        boolean fm6DacOff = SonicConfigurationService.getInstance().getBoolean(SonicConfiguration.FM6_DAC_OFF);

        if (smpsDriver != null && currentStream == smpsDriver) {
            // Mix into current driver
            // Note: DAC interpolation is global on the driver/synth.
            // FM6 DAC Off is per-sequencer.
            SmpsSequencer seq = new SmpsSequencer(data, dacData, smpsDriver, requireSmpsConfig());
            seq.setFm6DacOff(fm6DacOff);
            seq.setSfxMode(true);
            seq.setPitch(pitch);
            if (currentSmps != null) {
                seq.setFallbackVoiceData(currentSmps.getSmpsData());
            }
            smpsDriver.addSequencer(seq, true);
        } else {
            // Standalone SFX driver
            SmpsDriver sfxDriver;
            if (sfxStream instanceof SmpsDriver) {
                sfxDriver = (SmpsDriver) sfxStream;
            } else {
                sfxDriver = new SmpsDriver();
                sfxDriver.setDacInterpolate(dacInterpolate);
                sfxStream = sfxDriver;
            }
            SmpsSequencer seq = new SmpsSequencer(data, dacData, sfxDriver, requireSmpsConfig());
            seq.setFm6DacOff(fm6DacOff);
            seq.setSfxMode(true);
            seq.setPitch(pitch);
            if (currentSmps != null) {
                seq.setFallbackVoiceData(currentSmps.getSmpsData());
            }
            sfxDriver.addSequencer(seq, true);
        }

        // Ensure stream is running
        int[] queued = new int[1];
        al.alGetSourcei(musicSource, AL.AL_BUFFERS_QUEUED, queued, 0);
        if (queued[0] == 0) {
            al.alSourceStop(musicSource);
            al.alSourcei(musicSource, AL.AL_BUFFER, 0);
            startStream();
        }
    }

    private void startStream() {
        if (streamBuffers == null) {
            streamBuffers = new int[STREAM_BUFFER_COUNT];
            al.alGenBuffers(STREAM_BUFFER_COUNT, streamBuffers, 0);
        }

        for (int i = 0; i < STREAM_BUFFER_COUNT; i++) {
            fillBuffer(streamBuffers[i]);
        }

        al.alSourceQueueBuffers(musicSource, STREAM_BUFFER_COUNT, streamBuffers, 0);
        al.alSourcePlay(musicSource);
    }

    private void stopStream() {
        if (currentStream != null) {
            al.alSourceStop(musicSource);
            int[] processed = new int[1];
            al.alGetSourcei(musicSource, AL.AL_BUFFERS_PROCESSED, processed, 0);
            while (processed[0] > 0) {
                int[] buffers = new int[1];
                al.alSourceUnqueueBuffers(musicSource, 1, buffers, 0);
                processed[0]--;
            }
            currentStream = null;
            currentSmps = null;
            if (smpsDriver != null) {
                smpsDriver.stopAll();
                smpsDriver = null;
            }
            currentMusicId = -1;
        }
    }

    private void updateStream() {
        // Check for pending music restoration (deferred from E4 handler)
        if (pendingRestore) {
            pendingRestore = false;
            doRestoreMusic();
            return;
        }

        if (currentStream != null || sfxStream != null) {
            // Only update if playing via SMPS. If playing WAV, musicSource handles it.
            // But musicSource is used for streaming.
            // If we are playing WAV, currentStream is null.
            // If currentStream is null but sfxStream is NOT null, we should still stream?
            // Yes.

            int[] state = new int[1];
            al.alGetSourcei(musicSource, AL.AL_SOURCE_STATE, state, 0);

            int[] processed = new int[1];
            al.alGetSourcei(musicSource, AL.AL_BUFFERS_PROCESSED, processed, 0);

            while (processed[0] > 0) {
                int[] buffers = new int[1];
                al.alSourceUnqueueBuffers(musicSource, 1, buffers, 0);
                fillBuffer(buffers[0]);
                al.alSourceQueueBuffers(musicSource, 1, buffers, 0);
                processed[0]--;
            }

            // Check state again?
            al.alGetSourcei(musicSource, AL.AL_SOURCE_STATE, state, 0);
            if (state[0] != AL.AL_PLAYING) {
                al.alSourcePlay(musicSource);
            }
        }
    }

    @Override
    public void restoreMusic() {
        // Defer actual restoration to next updateStream cycle to avoid
        // modifying buffers while they're being rendered
        if (!musicStack.isEmpty()) {
            pendingRestore = true;
        }
    }

    private void doRestoreMusic() {
        MusicState savedState = musicStack.pollFirst();
        if (savedState == null || savedState.stream == null || savedState.smps == null
                || savedState.driver == null) {
            return;
        }

        // Stop the current (invincibility/extra-life) music stream
        al.alSourceStop(musicSource);

        // Unqueue ALL buffers (both processed and queued) to avoid OpenAL errors
        int[] queued = new int[1];
        al.alGetSourcei(musicSource, AL.AL_BUFFERS_QUEUED, queued, 0);
        for (int i = 0; i < queued[0]; i++) {
            int[] buffers = new int[1];
            al.alSourceUnqueueBuffers(musicSource, 1, buffers, 0);
        }

        // Stop the current (non-saved) smps driver
        if (smpsDriver != null && smpsDriver != savedState.driver) {
            smpsDriver.stopAll();
        }

        // Restore saved state
        currentStream = savedState.stream;
        currentSmps = savedState.smps;
        smpsDriver = savedState.driver;
        currentMusicId = savedState.musicId;

        if (currentSmps != null) {
            // Restore speed shoes state to the saved sequencer
            currentSmps.setSpeedShoes(speedShoesEnabled);
            currentSmps.refreshAllVoices();
            currentSmps.triggerFadeIn(0x28, 2);
        }

        startStream();
    }

    private void fillBuffer(int bufferId) {
        // Stereo buffer: 2 channels * STREAM_BUFFER_SIZE
        short[] data = new short[STREAM_BUFFER_SIZE * 2];
        if (currentStream != null) {
            currentStream.read(data);
        }
        // If music stream ended or not present, buffer is 0.

        if (sfxStream != null) {
            short[] sfxData = new short[STREAM_BUFFER_SIZE * 2];
            sfxStream.read(sfxData);

            for (int i = 0; i < data.length; i++) {
                int mixed = data[i] + sfxData[i];
                if (mixed > 32000)
                    mixed = 32000;
                if (mixed < -32000)
                    mixed = -32000;
                data[i] = (short) mixed;
            }

            if (sfxStream.isComplete()) {
                sfxStream = null;
            }
        }

        ShortBuffer sBuffer = Buffers.newDirectShortBuffer(data);
        al.alBufferData(bufferId, AL.AL_FORMAT_STEREO16, sBuffer, data.length * 2, 44100);
    }

    /**
     * Returns a debug snapshot of the current SMPS sequencer if one is playing.
     */
    public SmpsSequencer.DebugState getDebugState() {
        return currentSmps != null ? currentSmps.debugState() : null;
    }

    @Override
    public void playSfx(String sfxName) {
        playSfx(sfxName, 1.0f);
    }

    @Override
    public void playSfx(String sfxName, float pitch) {
        String filename = sfxFallback.get(sfxName);
        if (filename != null) {
            int source = getAvailableSource();
            if (source != -1) {
                sfxSources.add(source);
                playWav(filename, source, false, pitch);
            }
        } else {
            LOGGER.fine("SFX not found in fallback map: " + sfxName);
        }
    }

    @Override
    public void stopPlayback() {
        stopStream();
        al.alSourceStop(musicSource);
        al.alSourcei(musicSource, AL.AL_BUFFER, 0);
        currentStream = null;
        currentSmps = null;
        currentMusicId = -1;
        clearMusicStack();
        // Also stop any playing SFX to prevent them persisting across level transitions
        if (sfxStream instanceof SmpsDriver sfxDriver) {
            sfxDriver.stopAll();
        }
        sfxStream = null;
        // Stop and cleanup WAV-based SFX sources
        for (int source : sfxSources) {
            al.alSourceStop(source);
            al.alDeleteSources(1, new int[] { source }, 0);
        }
        sfxSources.clear();
    }

    @Override
    public void endMusicOverride(int musicId) {
        if (currentSmps != null && currentMusicId == musicId) {
            restoreMusic();
            return;
        }
        removeSavedOverride(musicId);
    }

    @Override
    public void toggleMute(ChannelType type, int channel) {
        switch (type) {
            case FM:
            case DAC:
                if (channel >= 0 && channel < 6) {
                    fmUserMutes[channel] = !fmUserMutes[channel];
                }
                break;
            case PSG:
                if (channel >= 0 && channel < 4) {
                    psgUserMutes[channel] = !psgUserMutes[channel];
                }
                break;
        }
        updateSynthesizerConfig();
    }

    @Override
    public void toggleSolo(ChannelType type, int channel) {
        switch (type) {
            case FM:
            case DAC:
                if (channel >= 0 && channel < 6) {
                    fmUserSolos[channel] = !fmUserSolos[channel];
                }
                break;
            case PSG:
                if (channel >= 0 && channel < 4) {
                    psgUserSolos[channel] = !psgUserSolos[channel];
                }
                break;
        }
        updateSynthesizerConfig();
    }

    @Override
    public boolean isMuted(ChannelType type, int channel) {
        return switch (type) {
            case FM, DAC -> (channel >= 0 && channel < 6) && fmUserMutes[channel];
            case PSG -> (channel >= 0 && channel < 4) && psgUserMutes[channel];
        };
    }

    @Override
    public boolean isSoloed(ChannelType type, int channel) {
        return switch (type) {
            case FM, DAC -> (channel >= 0 && channel < 6) && fmUserSolos[channel];
            case PSG -> (channel >= 0 && channel < 4) && psgUserSolos[channel];
        };
    }

    @Override
    public void setSpeedShoes(boolean enabled) {
        this.speedShoesEnabled = enabled;
        if (currentSmps != null) {
            currentSmps.setSpeedShoes(enabled);
        }
    }

    private void updateSynthesizerConfig() {
        if (currentSmps == null || currentSmps.getSynthesizer() == null)
            return;
        var synth = currentSmps.getSynthesizer();

        boolean anyFmSolo = false;
        for (boolean s : fmUserSolos)
            if (s)
                anyFmSolo = true;

        boolean anyPsgSolo = false;
        for (boolean s : psgUserSolos)
            if (s)
                anyPsgSolo = true;

        boolean anySolo = anyFmSolo || anyPsgSolo;

        for (int i = 0; i < 6; i++) {
            boolean soloed = fmUserSolos[i];
            boolean muted = fmUserMutes[i];
            if (soloed)
                muted = false;
            else if (anySolo)
                muted = true;
            synth.setFmMute(i, muted);
        }

        for (int i = 0; i < 4; i++) {
            boolean soloed = psgUserSolos[i];
            boolean muted = psgUserMutes[i];
            if (soloed)
                muted = false;
            else if (anySolo)
                muted = true;
            synth.setPsgMute(i, muted);
        }
    }

    private SmpsSequencerConfig requireSmpsConfig() {
        if (smpsConfig == null) {
            throw new IllegalStateException("SMPS sequencer config not set");
        }
        return smpsConfig;
    }

    private int getAvailableSource() {
        int[] src = new int[1];
        al.alGenSources(1, src, 0);
        return src[0];
    }

    private void pushCurrentState() {
        if (currentStream == null || currentSmps == null || smpsDriver == null) {
            return;
        }
        musicStack.push(new MusicState(currentStream, currentSmps, smpsDriver, currentMusicId));
    }

    private void clearMusicStack() {
        musicStack.clear();
        pendingRestore = false;
    }

    private boolean removeSavedOverride(int musicId) {
        if (musicStack.isEmpty()) {
            return false;
        }
        for (Iterator<MusicState> iterator = musicStack.iterator(); iterator.hasNext();) {
            MusicState state = iterator.next();
            if (state.musicId == musicId) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void playWav(String resourcePath, int source, boolean loop) {
        playWav(resourcePath, source, loop, 1.0f);
    }

    private void playWav(String resourcePath, int source, boolean loop, float pitch) {
        try {
            // Check if buffer exists
            if (!buffers.containsKey(resourcePath)) {
                loadWav(resourcePath);
            }

            Integer buffer = buffers.get(resourcePath);
            if (buffer != null) {
                // Check if source is playing something else?
                al.alSourceStop(source);
                al.alSourcei(source, AL.AL_BUFFER, buffer);
                al.alSourcei(source, AL.AL_LOOPING, loop ? AL.AL_TRUE : AL.AL_FALSE);
                al.alSourcef(source, AL.AL_PITCH, pitch);
                al.alSourcePlay(source);
            } else {
                LOGGER.fine("Could not load buffer for: " + resourcePath);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to play WAV: " + resourcePath + " - " + e.getMessage());
        }
    }

    private void loadWav(String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                // Don't log severe if file is missing, just fine.
                LOGGER.fine("Audio resource not found: " + resourcePath);
                return;
            }
            try (BufferedInputStream bis = new BufferedInputStream(is);
                    AudioInputStream ais = AudioSystem.getAudioInputStream(bis)) {

                AudioFormat format = ais.getFormat();
                int channels = format.getChannels();
                int sampleSize = format.getSampleSizeInBits();
                float sampleRate = format.getSampleRate();

                int alFormat;
                if (channels == 1) {
                    alFormat = (sampleSize == 8) ? AL.AL_FORMAT_MONO8 : AL.AL_FORMAT_MONO16;
                } else {
                    alFormat = (sampleSize == 8) ? AL.AL_FORMAT_STEREO8 : AL.AL_FORMAT_STEREO16;
                }

                // Read data
                byte[] data = ais.readAllBytes();
                ByteBuffer bufferData = Buffers.newDirectByteBuffer(data);

                int[] buf = new int[1];
                al.alGenBuffers(1, buf, 0);
                al.alBufferData(buf[0], alFormat, bufferData, data.length, (int) sampleRate);

                buffers.put(resourcePath, buf[0]);
            }
        } catch (Exception e) {
            LOGGER.warning("Error loading WAV " + resourcePath + ": " + e.getMessage());
        }
    }

    @Override
    public void update() {
        updateStream();

        // Cleanup stopped sources
        Iterator<Integer> it = sfxSources.iterator();
        int[] state = new int[1];
        while (it.hasNext()) {
            int src = it.next();
            al.alGetSourcei(src, AL.AL_SOURCE_STATE, state, 0);
            if (state[0] == AL.AL_STOPPED) {
                al.alDeleteSources(1, new int[] { src }, 0);
                it.remove();
            }
        }
    }

    @Override
    public void destroy() {
        if (context != null) {
            alc.alcDestroyContext(context);
        }
        if (device != null) {
            alc.alcCloseDevice(device);
        }
    }
}
