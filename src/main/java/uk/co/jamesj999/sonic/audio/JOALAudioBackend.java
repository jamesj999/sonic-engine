package uk.co.jamesj999.sonic.audio;

import com.jogamp.openal.AL;
import com.jogamp.openal.ALC;
import com.jogamp.openal.ALFactory;
import com.jogamp.openal.ALCcontext;
import com.jogamp.openal.ALCdevice;
import com.jogamp.common.nio.Buffers;

import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;

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
    private static final int STREAM_BUFFER_SIZE = 4096;
    private SmpsSequencer currentSmps;

    // Fallback mappings
    private final Map<Integer, String> musicFallback = new HashMap<>();
    private final Map<String, String> sfxFallback = new HashMap<>();

    // Mute/Solo State
    private final boolean[] fmUserMutes = new boolean[6];
    private final boolean[] fmUserSolos = new boolean[6];
    private final boolean[] psgUserMutes = new boolean[4];
    private final boolean[] psgUserSolos = new boolean[4];

    public JOALAudioBackend() {
        // Initialize fallback mappings
        // SFX
        sfxFallback.put("JUMP", "sfx/jump.wav");
        sfxFallback.put("RING", "sfx/ring.wav");
        sfxFallback.put("SPINDASH", "sfx/spindash.wav");
        sfxFallback.put("SKID", "sfx/skid.wav");
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

            LOGGER.info("OpenAL Initialized.");

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

        // Try fallback map first
        String filename = musicFallback.get(musicId);
        if (filename == null) {
            // Default naming convention
            filename = "music/" + Integer.toHexString(musicId).toUpperCase() + ".wav";
        }

        playWav(filename, musicSource, true);
    }

    @Override
    public void playSmps(SmpsData data, DacData dacData) {
        stopStream();
        // Stop music source if playing wav
        al.alSourceStop(musicSource);

        currentSmps = new SmpsSequencer(data, dacData);
        updateSynthesizerConfig();
        currentStream = currentSmps;
        startStream();
    }

    @Override
    public void playSfxSmps(SmpsData data, DacData dacData) {
        this.sfxStream = new SmpsSequencer(data, dacData);
        // We probably don't want to mute SFX channels based on music muting preferences,
        // but if we did, we'd apply it here. For now, we leave SFX unmuted.
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
        }
    }

    private void updateStream() {
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

    private void fillBuffer(int bufferId) {
        short[] data = new short[STREAM_BUFFER_SIZE];
        int read = (currentStream != null) ? currentStream.read(data) : 0;
        // If music stream ended or not present, buffer is 0.

        if (sfxStream != null) {
            short[] sfxData = new short[STREAM_BUFFER_SIZE];
            sfxStream.read(sfxData);

            for(int i=0; i<data.length; i++) {
                int mixed = data[i] + sfxData[i];
                if (mixed > 32000) mixed = 32000;
                if (mixed < -32000) mixed = -32000;
                data[i] = (short) mixed;
            }

            if (sfxStream.isComplete()) {
                sfxStream = null;
            }
        }

        ShortBuffer sBuffer = Buffers.newDirectShortBuffer(data);
        al.alBufferData(bufferId, AL.AL_FORMAT_MONO16, sBuffer, data.length * 2, 44100);
    }

    /**
     * Returns a debug snapshot of the current SMPS sequencer if one is playing.
     */
    public SmpsSequencer.DebugState getDebugState() {
        return currentSmps != null ? currentSmps.debugState() : null;
    }

    @Override
    public void playSfx(String sfxName) {
        String filename = sfxFallback.get(sfxName);
        if (filename != null) {
            int source = getAvailableSource();
            if (source != -1) {
                sfxSources.add(source);
                playWav(filename, source, false);
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

    private void updateSynthesizerConfig() {
        if (currentSmps == null || currentSmps.getSynthesizer() == null) return;
        var synth = currentSmps.getSynthesizer();

        boolean anyFmSolo = false;
        for (boolean s : fmUserSolos) if (s) anyFmSolo = true;

        boolean anyPsgSolo = false;
        for (boolean s : psgUserSolos) if (s) anyPsgSolo = true;

        boolean anySolo = anyFmSolo || anyPsgSolo;

        for (int i = 0; i < 6; i++) {
            boolean soloed = fmUserSolos[i];
            boolean muted = fmUserMutes[i];
            if (soloed) muted = false;
            else if (anySolo) muted = true;
            synth.setFmMute(i, muted);
        }

        for (int i = 0; i < 4; i++) {
            boolean soloed = psgUserSolos[i];
            boolean muted = psgUserMutes[i];
            if (soloed) muted = false;
            else if (anySolo) muted = true;
            synth.setPsgMute(i, muted);
        }
    }

    private int getAvailableSource() {
        int[] src = new int[1];
        al.alGenSources(1, src, 0);
        return src[0];
    }

    private void playWav(String resourcePath, int source, boolean loop) {
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
        while(it.hasNext()) {
            int src = it.next();
            al.alGetSourcei(src, AL.AL_SOURCE_STATE, state, 0);
            if (state[0] == AL.AL_STOPPED) {
                al.alDeleteSources(1, new int[]{src}, 0);
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
