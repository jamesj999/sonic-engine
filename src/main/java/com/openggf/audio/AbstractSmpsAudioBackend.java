package com.openggf.audio;

import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.session.LegacyCompatibilitySmpsPhysicalPolicy;
import com.openggf.audio.session.OwnedSmpsAudioStream;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.session.SmpsPhysicalPolicy;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SfxContentionObserver;
import com.openggf.audio.driver.SfxContentionObserver.Admission;
import com.openggf.audio.driver.SfxContentionObserver.Arbitration;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.AdmissionResult;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.RejectionReason;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.SmpsAdmissionContext;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.audio.synth.Ym2612Chip;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.PerformanceProfiler;

import java.util.*;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Device-agnostic base for the SMPS source-construction backend.
 *
 * <p>This class owns SMPS sequencer/driver construction, the music-stack and
 * SFX lifecycle, speed/tempo state, and logical music-source descriptors. It
 * owns <strong>no</strong> presentation state: the presentation clock, final
 * PCM, rewind history, reverse cursor, and capture leases all belong to
 * {@code AudioPresentationProducer}, and the only writer of a real audio
 * device is {@code OpenAlPcmSink}. It contains <strong>zero</strong> OpenAL
 * calls and no {@code org.lwjgl.openal} imports.
 *
 * <p>The remaining device lifecycle hooks are {@code protected abstract} and
 * implemented as no-ops by both concrete subclasses
 * ({@link LWJGLAudioBackend}, {@link HeadlessSmpsAudioBackend}); the headless
 * backend additionally fixes the device-facing fallback rate to 48000.
 */
public abstract class AbstractSmpsAudioBackend implements AudioBackend {
    private static final Logger LOGGER = Logger.getLogger(AbstractSmpsAudioBackend.class.getName());

    protected final Object streamLock = new Object();
    protected final SonicConfigurationService configService;
    /**
     * Currently unread: the {@code audio.music_stream} / {@code audio.sfx_stream}
     * / {@code audio.upload} profile sections this fed disappeared with the
     * backend's stream fill, and audio timing is now profiled on the
     * presentation producer instead. The field and its constructor argument are
     * deliberately retained so this deletion task does not churn every
     * backend construction site; nothing in the backend may start profiling
     * again without owning a section on the producer side.
     */
    protected final PerformanceProfiler profiler;

    protected static final int STREAM_BUFFER_SIZE = 1024;

    protected AudioStream currentStream;
    protected AudioStream sfxStream;
    private SmpsSequencer currentSmps;
    private SmpsDriver smpsDriver;
    private OwnedSmpsAudioStream smpsStream;

    private static class MusicState {
        final AudioStream stream;
        final SmpsSequencer smps;
        final SmpsDriver driver;
        final int musicId;
        final AudioSourceDescriptor descriptor;

        MusicState(AudioStream stream, SmpsSequencer smps, SmpsDriver driver, int musicId,
                   AudioSourceDescriptor descriptor) {
            this.stream = stream;
            this.smps = smps;
            this.driver = driver;
            this.musicId = musicId;
            this.descriptor = descriptor;
        }
    }

    private record OwnedMusic(
            OwnedSmpsAudioStream stream,
            SmpsDriver driver,
            SmpsSequencer sequencer) {
    }

    private final Deque<MusicState> musicStack = new ArrayDeque<>();

    private int currentMusicId = -1;
    private AudioSourceDescriptor currentMusicDescriptor;
    private AudioSourceDescriptor pendingMusicDescriptor;
    protected volatile boolean pendingRestore = false;
    protected volatile boolean sfxBlocked = false;  // Block SFX during override jingle/fade-in (ROM: 1upPlaying, FadeInFlag)

    // Fallback mappings
    protected final Map<Integer, String> musicFallback = new HashMap<>();
    protected final Map<String, String> sfxFallback = new HashMap<>();

    // Mute/Solo State
    private final boolean[] fmUserMutes = new boolean[6];
    private final boolean[] fmUserSolos = new boolean[6];
    private final boolean[] psgUserMutes = new boolean[4];
    private final boolean[] psgUserSolos = new boolean[4];

    private boolean speedShoesEnabled = false;
    private int speedMultiplier = 1;
    private GameAudioProfile audioProfile;
    private SmpsSequencerConfig smpsConfig;
    private AudioAdmissionObserver admissionObserver =
            AudioAdmissionObserver.NONE;
    private SmpsDriverServiceObserver driverServiceObserver =
            SmpsDriverServiceObserver.NONE;
    private ChipWriteObserver chipWriteObserver = ChipWriteObserver.NONE;
    private SfxContentionObserver sfxContentionObserver =
            SfxContentionObserver.NONE;
    private long nextServiceOrdinal;
    private long nextDriverInstanceOrdinal;
    private long nextDriverAdmissionOrdinal;
    protected AbstractSmpsAudioBackend(SonicConfigurationService configService, PerformanceProfiler profiler) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.profiler = profiler;
        // Initialize fallback mappings
        // SFX
        sfxFallback.put("JUMP", "sfx/jump.wav");
        sfxFallback.put("RING", "sfx/ring.wav");
        sfxFallback.put("SPINDASH", "sfx/spindash.wav");
        sfxFallback.put("SKID", "sfx/skid.wav");
    }

    // ------------------------------------------------------------------
    // Device-output hooks. Implemented with verbatim OpenAL code in
    // LWJGLAudioBackend; no-ops (plus sample-rate init) in headless.
    // ------------------------------------------------------------------

    /**
     * Opens/initialises the output device (or, headless, fixes the synthesis
     * sample rate). Must establish {@link #getDeviceSampleRate()}.
     */
    protected abstract void hookInitDevice();

    /** Tears the output device down (frees buffers/sources/context/device). */
    protected abstract void hookDestroyDevice();

    /**
     * Begins a source lifecycle transition into "playing". No presentation
     * output is attached to the backend; both subclasses are no-ops.
     */
    protected abstract void hookStartStream();

    /**
     * Ends a source lifecycle transition out of "playing" for the music slot.
     * Both subclasses are no-ops.
     */
    protected abstract void hookStopStreamSource();

    /**
     * Per-{@link #update()} device pump. No presentation output is attached to
     * the backend; both subclasses are no-ops.
     */
    protected abstract void hookUpdateStream();

    /**
     * Detaches the music slot without ending queued output. Used by the
     * override-swap paths in {@code playSmps}. Both subclasses are no-ops.
     */
    protected abstract void hookStopAndClearMusicSource();

    /**
     * Ends all queued music output. Used by {@code doRestoreMusic}. Both
     * subclasses are no-ops.
     */
    protected abstract void hookStopAndUnqueueAllMusicBuffers();

    /**
     * Ends all queued music output and detaches the music slot. Used by
     * {@code stopPlayback}. Both subclasses are no-ops.
     */
    protected abstract void hookStopAndClearAllMusicBuffers();

    /**
     * Restarts a dry music slot: the {@code playSfxSmps} "ensure stream is
     * running" tail. Both subclasses are no-ops.
     */
    protected abstract void hookRestartStreamIfDry();

    /** Stops and deletes any WAV-based SFX sources. Both subclasses: no-op. */
    protected abstract void hookStopAndDeleteWavSfxSources();

    /** Plays a named WAV SFX through the device. Headless: no-op. */
    protected abstract void hookPlayWavSfx(String sfxName, float pitch);

    /** Cleans up finished WAV SFX sources (called from {@link #update()}). Headless: no-op. */
    protected abstract void hookCleanupStoppedWavSfx();

    /** Pauses device playback. Headless: no-op. */
    protected abstract void hookPause();

    /** Resumes device playback. Headless: no-op. */
    protected abstract void hookResume();

    /**
     * Returns the negotiated device sample rate used to drive synthesis output.
     * LWJGL returns the OpenAL-negotiated rate; headless returns 48000.
     */
    protected abstract int getDeviceSampleRate();

    // ------------------------------------------------------------------
    // Shared backend logic.
    // ------------------------------------------------------------------

    @Override
    public void setAudioProfile(GameAudioProfile profile) {
        this.audioProfile = profile;
        this.smpsConfig = profile != null ? profile.getSequencerConfig() : null;
    }

    @Override
    public void setAdmissionObserver(AudioAdmissionObserver observer) {
        admissionObserver = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void setDriverServiceObserver(
            SmpsDriverServiceObserver observer) {
        driverServiceObserver = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void setChipWriteObserver(ChipWriteObserver observer) {
        chipWriteObserver = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void setSfxContentionObserver(
            SfxContentionObserver observer) {
        sfxContentionObserver = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void registerAudioProfileCoordHandlers(GameAudioProfile profile) {
        // Presentation owns the sole session coordination state after cutover.
    }

    @Override
    public AudioPresentationTuning presentationTuning() {
        SmpsSequencer.Region region =
                "PAL".equalsIgnoreCase(
                        configService.getString(SonicConfiguration.REGION))
                        ? SmpsSequencer.Region.PAL
                        : SmpsSequencer.Region.NTSC;
        return new AudioPresentationTuning(
                region,
                configService.getBoolean(SonicConfiguration.DAC_INTERPOLATE),
                configService.getBoolean(SonicConfiguration.FM6_DAC_OFF));
    }

    @Override
    public void init() {
        hookInitDevice();
    }

    @Override
    public void playMusic(int musicId) {
        if (LOGGER.isLoggable(Level.INFO)) {
            LOGGER.info("Requesting Music ID: " + Integer.toHexString(musicId));
        }
        stopStream(); // Stop any running stream
        clearMusicStack();
        currentMusicId = -1;

        // Try fallback map first
        String filename = musicFallback.get(musicId);
        if (filename == null) {
            // Default naming convention
            filename = "music/" + Integer.toHexString(musicId).toUpperCase() + ".wav";
        }

        playWavMusic(filename, musicId);
        currentMusicId = musicId;
        currentMusicDescriptor = consumePendingMusicDescriptor(musicId);
    }

    /**
     * Plays a WAV-backed music file on the device music source (loop). The base
     * has no device source, so this routes through the device hook. Default
     * fallback never loads a WAV in headless mode.
     */
    protected void playWavMusic(String filename, int musicId) {
        // Device subclasses override with the real WAV path; headless ignores.
    }

    @Override
    public void playSmps(AbstractSmpsData data, DacData dacData) {
        int musicId = data.getId();
        boolean isOverride = audioProfile != null && audioProfile.isMusicOverride(musicId);
        if (isOverride) {
            // ROM behavior: only 1-up jingle (isSfxBlockingMusic) kills active SFX.
            // Non-blocking overrides (invincibility, Super Sonic) let SFX continue.
            if (audioProfile.isSfxBlockingMusic(musicId)) {
                synchronized (streamLock) {
                    if (smpsDriver != null) {
                        smpsDriver.stopAllSfx();
                    }
                    closeStandaloneSfxStream();
                }
                sfxBlocked = true;
            }
            // Push current state unless re-triggering the same override (e.g.
            // collecting invincibility while already invincible).  When a
            // *different* override starts (e.g. 1-up during invincibility), the
            // active override must be saved so it resumes when the new one ends.
            boolean currentIsOverride = audioProfile != null && audioProfile.isMusicOverride(currentMusicId);
            if (!currentIsOverride || currentMusicId != musicId) {
                pushCurrentState();
            }

            // Just disconnect the current driver from the source without stopping/clearing it.
            hookStopAndClearMusicSource();
            currentStream = null;
            currentSmps = null;
            smpsDriver = null;
            smpsStream = null;
        } else {
            stopStream();
            // Stop music source if playing wav
            hookStopAndClearMusicSource();
            clearMusicStack();
            // Clean up standalone SFX stream - stopStream() only handles currentStream/smpsDriver,
            // but SFX played before any music was active use a separate sfxStream SmpsDriver.
            // Without this, the sfxStream persists indefinitely.
            synchronized (streamLock) {
                closeStandaloneSfxStream();
            }
        }

        AudioSourceDescriptor musicDescriptor = consumePendingMusicDescriptor(musicId);
        OwnedMusic legacyMusic = createLegacyMusic(
                data, dacData, requireSmpsConfig(), musicDescriptor);
        smpsDriver = legacyMusic.driver();
        smpsStream = legacyMusic.stream();
        SmpsSequencer seq = legacyMusic.sequencer();
        currentSmps = seq;
        currentMusicId = musicId;
        currentMusicDescriptor = musicDescriptor;

        updateSynthesizerConfig();
        synchronized (streamLock) {
            currentStream = smpsStream;
        }
        startStream();
    }

    @Override
    public void playSmps(AbstractSmpsData data, DacData dacData,
                         SmpsSequencerConfig config, boolean forceOverride) {
        SmpsSequencerConfig effectiveConfig = legacySequencerConfig(
                (config != null) ? config : requireSmpsConfig());

        int musicId = data.getId();
        boolean isOverride = forceOverride
                || (audioProfile != null && audioProfile.isMusicOverride(musicId));
        if (isOverride) {
            boolean sfxBlocking = audioProfile != null && audioProfile.isSfxBlockingMusic(musicId);
            // ROM: only the 1-up jingle (isSfxBlockingMusic) kills active SFX.
            // Non-blocking overrides (invincibility, Super Sonic) let SFX continue.
            if (sfxBlocking) {
                synchronized (streamLock) {
                    if (smpsDriver != null) {
                        smpsDriver.stopAllSfx();
                    }
                    closeStandaloneSfxStream();
                }
                sfxBlocked = true;
            }
            // Push current state unless re-triggering the same override.
            boolean currentIsOverride = audioProfile != null && audioProfile.isMusicOverride(currentMusicId);
            if (!currentIsOverride || currentMusicId != musicId) {
                pushCurrentState();
            }
            hookStopAndClearMusicSource();
            currentStream = null;
            currentSmps = null;
            smpsDriver = null;
            smpsStream = null;
        } else {
            stopStream();
            hookStopAndClearMusicSource();
            clearMusicStack();
            synchronized (streamLock) {
                closeStandaloneSfxStream();
            }
        }

        AudioSourceDescriptor musicDescriptor = consumePendingMusicDescriptor(musicId);
        OwnedMusic legacyMusic = createLegacyMusic(
                data, dacData, effectiveConfig, musicDescriptor);
        smpsDriver = legacyMusic.driver();
        smpsStream = legacyMusic.stream();
        SmpsSequencer seq = legacyMusic.sequencer();
        currentSmps = seq;
        currentMusicId = musicId;
        currentMusicDescriptor = musicDescriptor;

        updateSynthesizerConfig();
        synchronized (streamLock) {
            currentStream = smpsStream;
        }
        startStream();
    }

    @Override
    public void playSfxSmps(AbstractSmpsData data, DacData dacData) {
        playSfxSmps(data, dacData, 1.0f);
    }

    @Override
    public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch) {
        playSfxSmps(data, dacData, pitch, null);
    }

    @Override
    public void playSfxSmps(AbstractSmpsData data, DacData dacData, float pitch,
                             SmpsSequencerConfig config) {
        SmpsSfxPlaybackPolicy policy = new SmpsSfxPlaybackPolicy(
                (audioProfile != null)
                        ? audioProfile.getSfxPriority(data.getId()) : 0x70,
                audioProfile != null
                        && audioProfile.isSpecialSfx(data.getId()),
                audioProfile != null
                        && audioProfile.isContinuousSfx(data.getId()));
        playSfxSmps(data, dacData, pitch, config, policy);
    }

    @Override
    public void playSfxSmps(
            AbstractSmpsData data,
            DacData dacData,
            float pitch,
            SmpsSequencerConfig config,
            SmpsSfxPlaybackPolicy playbackPolicy) {
        Objects.requireNonNull(playbackPolicy, "playbackPolicy");
        int sfxPriority = playbackPolicy.priority();
        boolean specialSfx = playbackPolicy.special();

        // --- Continuous SFX detection (Z80: zPlaySound_Bankswitch lines 1937-1965) ---
        // If this SFX is continuous (S3K >= 0xBC) and the same one is already playing,
        // extend playback (set the flag) instead of restarting from scratch.
        boolean isContinuous = playbackPolicy.continuous();
        SmpsAdmissionContext admissionContext = new SmpsAdmissionContext(
                data.getId(), data.getId(), sfxPriority,
                SmpsRequestAdmissionPolicy.NO_PRIORITY,
                specialSfx, false);
        SmpsRequestAdmissionPolicy admissionPolicy = audioProfile != null
                ? audioProfile.getSfxAdmissionPolicy()
                : SmpsRequestAdmissionPolicy.PERMISSIVE;
        AdmissionResult admission = Objects.requireNonNull(
                admissionPolicy.evaluate(admissionContext),
                "SFX admission policy returned no result");
        if (!admission.accepted()) {
            observeAdmission(new AudioAdmissionObserver.AudioAdmissionDecision(
                    admissionContext, admission));
            return;
        }
        // ROM behavior: completely block SFX during override jingle and fade-in period.
        // The whole resolved request has already crossed the game policy exactly once.
        if (sfxBlocked) {
            observeAdmission(new AudioAdmissionObserver.AudioAdmissionDecision(
                    admissionContext,
                    new AdmissionResult(false, RejectionReason.BLOCKED,
                            admission.priorityBefore(),
                            admission.priorityBefore(),
                            admission.resolvedSoundId())));
            return;
        }
        SmpsSequencerConfig effectiveConfig = legacySequencerConfig(
                (config != null) ? config : requireSmpsConfig());

        boolean dacInterpolate = configService.getBoolean(SonicConfiguration.DAC_INTERPOLATE);
        boolean fm6DacOff = configService.getBoolean(SonicConfiguration.FM6_DAC_OFF);
        int contTrackCount = data.getChannels() + data.getPsgChannels();
        if (isContinuous) {
            SmpsDriver targetDriver = null;
            if (smpsDriver != null && currentStream == smpsStream) {
                targetDriver = smpsDriver;
            } else {
                synchronized (streamLock) {
                    if (sfxStream instanceof OwnedSmpsAudioStream owned) {
                        targetDriver = owned.logicalDriver();
                    }
                }
            }
            if (targetDriver != null && targetDriver.extendContinuousSfx(data.getId(), contTrackCount)) {
                observeAdmission(new AudioAdmissionObserver.AudioAdmissionDecision(
                        admissionContext, admission));
                return; // Extended existing playback — no new sequencer needed
            }
        }

        if (smpsDriver != null && currentStream == smpsStream) {
            // Mix into current driver
            if (isContinuous) {
                smpsDriver.startContinuousSfx(data.getId(), contTrackCount);
            }
            SmpsSequencer seq = new SmpsSequencer(data, dacData, smpsDriver, effectiveConfig);
            seq.setSourceDescriptor(describeSmpsSource(null, data, true));
            seq.setSampleRate(smpsStream.outputSampleRate());
            seq.setFm6DacOff(fm6DacOff);
            seq.setSfxMode(true);
            seq.setPitch(pitch);
            seq.setSfxPriority(sfxPriority);
            seq.setSpecialSfx(specialSfx);
            if (currentSmps != null) {
                seq.setFallbackVoiceData(currentSmps.getSmpsData());
            }
            smpsDriver.addSequencer(seq, true);
        } else {
            // Standalone SFX driver
            synchronized (streamLock) {
                SmpsDriver sfxDriver;
                OwnedSmpsAudioStream ownedSfx;
                if (sfxStream instanceof OwnedSmpsAudioStream existing) {
                    ownedSfx = existing;
                    sfxDriver = existing.logicalDriver();
                } else {
                    ownedSfx = newConfiguredSmpsStream(
                            driverOrigin(
                                    SmpsDriverServiceObserver.DriverOriginKind.SFX,
                                    data.getId()));
                    sfxDriver = ownedSfx.logicalDriver();
                    sfxStream = ownedSfx;
                    applyUserMasks(ownedSfx, hasAnyUserSolo());
                }
                if (isContinuous) {
                    sfxDriver.startContinuousSfx(data.getId(), contTrackCount);
                }
                SmpsSequencer seq = new SmpsSequencer(data, dacData, sfxDriver, effectiveConfig);
                seq.setSourceDescriptor(describeSmpsSource(null, data, true));
                seq.setSampleRate(ownedSfx.outputSampleRate());
                seq.setFm6DacOff(fm6DacOff);
                seq.setSfxMode(true);
                seq.setPitch(pitch);
                seq.setSfxPriority(sfxPriority);
                seq.setSpecialSfx(specialSfx);
                if (currentSmps != null) {
                    seq.setFallbackVoiceData(currentSmps.getSmpsData());
                }
                sfxDriver.addSequencer(seq, true);
            }
        }

        // Ensure stream is running
        hookRestartStreamIfDry();
        observeAdmission(new AudioAdmissionObserver.AudioAdmissionDecision(
                admissionContext, admission));
    }

    protected void startStream() {
        hookStartStream();
    }

    protected void stopStream() {
        hookStopStreamSource();

        currentStream = null;
        currentSmps = null;
        if (smpsDriver != null) {
            smpsDriver.stopAll();
            smpsDriver = null;
        }
        if (smpsStream != null) {
            smpsStream.close();
            smpsStream = null;
        }
        currentMusicId = -1;
        currentMusicDescriptor = null;
    }

    @Override
    public void restoreMusic() {
        // Defer actual restoration to next updateStream cycle to avoid
        // modifying buffers while they're being rendered
        if (!musicStack.isEmpty()) {
            pendingRestore = true;
        }
    }

    protected void doRestoreMusic() {
        MusicState savedState = musicStack.pollFirst();
        if (savedState == null || savedState.stream == null || savedState.smps == null
                || savedState.driver == null) {
            return;
        }

        // Release any queued output for the current (invincibility/extra-life)
        // music slot before the saved state is reinstated.
        hookStopAndUnqueueAllMusicBuffers();

        // Stop the current (non-saved) smps driver
        if (smpsDriver != null && smpsDriver != savedState.driver) {
            smpsDriver.stopAll();
        }

        // Restore saved state
        synchronized (streamLock) {
            currentStream = savedState.stream;
            currentSmps = savedState.smps;
            smpsDriver = savedState.driver;
            smpsStream = (OwnedSmpsAudioStream) savedState.stream;
            currentMusicId = savedState.musicId;
            currentMusicDescriptor = savedState.descriptor;
            updateSynthesizerConfig();
        }

        if (currentSmps != null) {
            // Restore speed shoes state to the saved sequencer
            currentSmps.setSpeedShoes(speedShoesEnabled);
            currentSmps.refreshAllVoices();
            // ROM: only the 1-up jingle fades in on restore. S1/S2 keep SFX
            // blocked through FadeInFlag; S3K clears zFadeToPrevFlag when
            // zFadeInToPrevious starts and allows new SFX on the next driver cycle.
            if (sfxBlocked) {
                if (audioProfile == null || audioProfile.blocksSfxDuringMusicRestoreFadeIn()) {
                    currentSmps.setOnFadeComplete(() -> sfxBlocked = false);
                } else {
                    sfxBlocked = false;
                }
                currentSmps.triggerFadeIn();
            }
        }

        startStream();
        AudioDiagnosticObserverException.invoke(() ->
                driverServiceObserver.onLifecycle(
                        SmpsDriverServiceObserver.LifecycleEvent.registry(
                                SmpsDriverServiceObserver.LifecycleKind.RESTORE,
                                SmpsDriverServiceObserver.LifecycleSource.MUSIC_OVERRIDE)));
    }

    protected double getSmpsOutputRate() {
        boolean internalRate = configService.getBoolean(SonicConfiguration.AUDIO_INTERNAL_RATE_OUTPUT);
        // Use device's native sample rate to avoid OpenAL resampling - our BlipResampler handles it
        return internalRate ? Ym2612Chip.getInternalRate() : getDeviceSampleRate();
    }

    /**
     * Returns a debug snapshot of the current SMPS sequencer if one is playing.
     */
    public SmpsSequencer.DebugState getDebugState() {
        synchronized (streamLock) {
            return currentSmps != null ? currentSmps.debugState() : null;
        }
    }

    SmpsDriver musicDriverForTesting() {
        synchronized (streamLock) {
            return smpsDriver;
        }
    }

    StateForTesting stateForTesting() {
        synchronized (streamLock) {
            List<OverrideStateForTesting> overrides =
                    new ArrayList<>(musicStack.size());
            for (MusicState state : musicStack) {
                overrides.add(new OverrideStateForTesting(
                        state.stream, state.smps, state.driver,
                        state.musicId, state.descriptor,
                        state.driver != null
                                ? state.driver.captureSnapshot() : null,
                        state.driver != null
                                ? state.driver
                                        .sequencersForTesting()
                                : List.of()));
            }
            return new StateForTesting(
                    currentStream, sfxStream, currentSmps, smpsDriver,
                    currentMusicDescriptor, currentMusicId,
                    pendingMusicDescriptor, sfxBlocked, pendingRestore,
                    speedShoesEnabled, speedMultiplier, overrides,
                    smpsDriver != null ? smpsDriver.captureSnapshot() : null,
                    smpsDriver != null
                            ? smpsDriver.sequencersForTesting()
                            : List.of(),
                    sfxStream instanceof OwnedSmpsAudioStream owned
                            ? owned.logicalDriver().captureSnapshot() : null,
                    sfxStream instanceof OwnedSmpsAudioStream owned
                            ? owned.logicalDriver().sequencersForTesting()
                            : List.of(),
                    maskOf(fmUserMutes), maskOf(fmUserSolos),
                    maskOf(psgUserMutes), maskOf(psgUserSolos));
        }
    }

    record OverrideStateForTesting(
            AudioStream stream,
            SmpsSequencer sequencer,
            SmpsDriver driver,
            int musicId,
            AudioSourceDescriptor descriptor,
            SmpsDriverSnapshot driverSnapshot,
            List<SmpsSequencer> sequencers) {
        OverrideStateForTesting {
            sequencers = List.copyOf(sequencers);
        }
    }

    record StateForTesting(
            AudioStream currentStream,
            AudioStream sfxStream,
            SmpsSequencer currentSmps,
            SmpsDriver musicDriver,
            AudioSourceDescriptor currentMusic,
            int currentMusicId,
            AudioSourceDescriptor pendingMusic,
            boolean sfxBlocked,
            boolean pendingRestore,
            boolean speedShoesEnabled,
            int speedMultiplier,
            List<OverrideStateForTesting> overrideStack,
            SmpsDriverSnapshot musicDriverSnapshot,
            List<SmpsSequencer> musicSequencers,
            SmpsDriverSnapshot standaloneSfxDriverSnapshot,
            List<SmpsSequencer> standaloneSfxSequencers,
            int fmUserMuteMask,
            int fmUserSoloMask,
            int psgUserMuteMask,
            int psgUserSoloMask) {
        StateForTesting {
            overrideStack = List.copyOf(overrideStack);
            musicSequencers = List.copyOf(musicSequencers);
            standaloneSfxSequencers =
                    List.copyOf(standaloneSfxSequencers);
        }
    }

    private OwnedSmpsAudioStream newConfiguredSmpsStream(
            SmpsDriverServiceObserver.DriverAdmissionOrigin origin) {
        double sampleRate = getSmpsOutputRate();
        SmpsPhysicalPolicy physicalPolicy = audioProfile != null
                ? audioProfile.smpsPhysicalPolicy()
                : LegacyCompatibilitySmpsPhysicalPolicy.INSTANCE;
        OwnedSmpsAudioStream stream = new OwnedSmpsAudioStream(
                audioProfile != null
                        ? audioProfile.presentationGameId() : "base",
                0,
                new SmpsPhysicalDevice.Settings(
                        sampleRate,
                        configService.getBoolean(
                                SonicConfiguration.DAC_INTERPOLATE),
                        com.openggf.audio.synth.FmCoreSelection.fromConfig(
                                configService.getString(SonicConfiguration.AUDIO_FM_CORE))),
                physicalPolicy,
                diagnosticChipWriteObserver(),
                new com.openggf.audio.session.SmpsDriverSessionConfiguration(
                        audioProfile != null
                                ? audioProfile.smpsStatefulCommandPolicy()
                                : com.openggf.audio.session
                                        .SmpsStatefulCommandPolicy.NONE));
        SmpsDriver driver = stream.logicalDriver();
        driver.setDiagnosticIdentity(
                new SmpsDriverServiceObserver.DriverIdentity(
                        nextDriverInstanceOrdinal++, origin));
        installDiagnosticObservers(driver);
        driver.observeLifecycle(
                SmpsDriverServiceObserver.LifecycleKind.DRIVER_CREATED);
        return stream;
    }

    private void installDiagnosticObservers(SmpsDriver driver) {
        if (sfxContentionObserver != SfxContentionObserver.NONE) {
            SfxContentionObserver observer = sfxContentionObserver;
            driver.setSfxContentionObserver(new SfxContentionObserver() {
                @Override
                public void onSfxAdmitted(Admission admission) {
                    AudioDiagnosticObserverException.invoke(() ->
                            observer.onSfxAdmitted(admission));
                }

                @Override
                public void onRoleArbitrated(Arbitration arbitration) {
                    AudioDiagnosticObserverException.invoke(() ->
                            observer.onRoleArbitrated(arbitration));
                }
            });
        }
        if (driverServiceObserver == SmpsDriverServiceObserver.NONE) {
            return;
        }
        SmpsDriverServiceObserver observer = driverServiceObserver;
        driver.setServiceObserver(new SmpsDriverServiceObserver() {
            private ServiceEvent activeEvent;

            @Override
            public void onServiceBegin(ServiceEvent event) {
                if (activeEvent != null) {
                    throw new IllegalStateException(
                            "SMPS driver service observer was re-entered");
                }
                activeEvent = new ServiceEvent(nextServiceOrdinal++,
                        event.driver(), event.sequencer(), event.kind());
                ServiceEvent emitted = activeEvent;
                AudioDiagnosticObserverException.invoke(() ->
                        observer.onServiceBegin(emitted));
            }

            @Override
            public void onServiceEnd(
                    ServiceEvent event,
                    SmpsDriverSnapshot snapshot) {
                ServiceEvent completed = activeEvent;
                if (completed == null) {
                    throw new IllegalStateException(
                            "SMPS driver service ended without a begin");
                }
                activeEvent = null;
                AudioDiagnosticObserverException.invoke(() ->
                        observer.onServiceEnd(completed, snapshot));
            }

            @Override
            public void onLifecycle(LifecycleEvent event) {
                AudioDiagnosticObserverException.invoke(() ->
                        observer.onLifecycle(event));
            }
        });
    }

    private SmpsDriverServiceObserver.DriverAdmissionOrigin driverOrigin(
            SmpsDriverServiceObserver.DriverOriginKind kind, int soundId) {
        return new SmpsDriverServiceObserver.DriverAdmissionOrigin(
                kind, nextDriverAdmissionOrdinal++, soundId);
    }

    private ChipWriteObserver diagnosticChipWriteObserver() {
        if (chipWriteObserver == ChipWriteObserver.NONE) {
            return ChipWriteObserver.NONE;
        }
        return new ChipWriteObserver() {
            @Override
            public void onYm2612Write(
                    int port, int register, int value) {
                AudioDiagnosticObserverException.invoke(() ->
                        chipWriteObserver.onYm2612Write(
                                port, register, value));
            }

            @Override
            public void onPsgWrite(int value) {
                AudioDiagnosticObserverException.invoke(() ->
                        chipWriteObserver.onPsgWrite(value));
            }

            @Override
            public boolean observesPhysicalWrites() {
                return chipWriteObserver.observesPhysicalWrites();
            }

            @Override
            public void onYm2612BusWrite(long cycle, int busPort, int value,
                    ChipWriteObserver.PhysicalWriteOrigin origin) {
                AudioDiagnosticObserverException.invoke(() ->
                        chipWriteObserver.onYm2612BusWrite(
                                cycle, busPort, value, origin));
            }

            @Override
            public void onPsgBusWrite(long tick, int value) {
                AudioDiagnosticObserverException.invoke(() ->
                        chipWriteObserver.onPsgBusWrite(tick, value));
            }

            @Override
            public void onPhysicalTimelineBoundary(
                    ChipWriteObserver.ChipClockDomain domain, long clock,
                    ChipWriteObserver.PhysicalTimelineBoundary boundary) {
                AudioDiagnosticObserverException.invoke(() ->
                        chipWriteObserver.onPhysicalTimelineBoundary(
                                domain, clock, boundary));
            }
        };
    }

    private void observeAdmission(
            AudioAdmissionObserver.AudioAdmissionDecision decision) {
        AudioDiagnosticObserverException.invoke(() ->
                admissionObserver.onDecision(decision));
    }

    private OwnedMusic createLegacyMusic(
            AbstractSmpsData data,
            DacData dacData,
            SmpsSequencerConfig sequencerConfig,
            AudioSourceDescriptor descriptor) {
        OwnedSmpsAudioStream stream = newConfiguredSmpsStream(
                driverOrigin(
                        SmpsDriverServiceObserver.DriverOriginKind.MUSIC,
                        data.getId()));
        SmpsDriver driver = stream.logicalDriver();
        driver.setRegion("PAL".equalsIgnoreCase(
                configService.getString(SonicConfiguration.REGION))
                ? SmpsSequencer.Region.PAL
                : SmpsSequencer.Region.NTSC);
        SmpsSequencer sequencer = new SmpsSequencer(
                data, dacData, driver, sequencerConfig);
        sequencer.setSourceDescriptor(
                describeSmpsSource(descriptor, data, false));
        sequencer.setSampleRate(stream.outputSampleRate());
        sequencer.setSpeedShoes(speedShoesEnabled);
        sequencer.setSpeedMultiplier(speedMultiplier);
        sequencer.setFm6DacOff(configService.getBoolean(
                SonicConfiguration.FM6_DAC_OFF));
        sequencer.setFallbackVoiceData(data);
        driver.addSequencer(sequencer, false);
        return new OwnedMusic(stream, driver, sequencer);
    }

    private SmpsSequencerConfig legacySequencerConfig(
            SmpsSequencerConfig config) {
        return AudioManager.presentationOwner()
                .bindLegacyConfigToPresentationOwner(config);
    }

    @Override
    public void prepareLogicalMusicSource(AudioSourceDescriptor descriptor) {
        pendingMusicDescriptor = descriptor;
    }

    @Override
    public int outputSampleRate() {
        return (int) Math.round(getSmpsOutputRate());
    }

    @Override
    public void playSfx(String sfxName) {
        playSfx(sfxName, 1.0f);
    }

    @Override
    public void playSfx(String sfxName, float pitch) {
        String filename = sfxFallback.get(sfxName);
        if (filename != null) {
            hookPlayWavSfx(sfxName, pitch);
        } else {
            LOGGER.fine("SFX not found in fallback map: " + sfxName);
        }
    }

    @Override
    public void stopPlayback() {
        stopStream();
        hookStopAndClearMusicSource();
        synchronized (streamLock) {
            currentStream = null;
            currentSmps = null;
            currentMusicId = -1;
            currentMusicDescriptor = null;
            clearMusicStack();
            // Also stop any playing SFX to prevent them persisting across level transitions
            closeStandaloneSfxStream();
        }
        // Stop and cleanup WAV-based SFX sources
        hookStopAndDeleteWavSfxSources();
    }

    @Override
    public void fadeOutMusic(int steps, int delay) {
        synchronized (streamLock) {
            if (smpsStream != null) {
                smpsStream.fadeOutMusic(steps, delay);
            }
        }
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
        synchronized (streamLock) {
            if (currentSmps != null) {
                currentSmps.setSpeedShoes(enabled);
            }
        }
    }

    @Override
    public void setSpeedMultiplier(int multiplier) {
        this.speedMultiplier = multiplier;
        synchronized (streamLock) {
            if (currentSmps != null) {
                currentSmps.setSpeedMultiplier(multiplier);
            }
        }
    }

    @Override
    public void changeMusicTempo(int newDividingTiming) {
        synchronized (streamLock) {
            if (currentSmps != null) {
                currentSmps.updateDividingTiming(newDividingTiming);
            }
        }
    }

    private void updateSynthesizerConfig() {
        boolean anySolo = hasAnyUserSolo();

        if (smpsStream != null) {
            applyUserMasks(smpsStream, anySolo);
        }
        if (sfxStream instanceof OwnedSmpsAudioStream ownedSfx
                && ownedSfx != smpsStream) {
            applyUserMasks(ownedSfx, anySolo);
        }
    }

    private boolean hasAnyUserSolo() {
        for (boolean solo : fmUserSolos) {
            if (solo) {
                return true;
            }
        }
        for (boolean solo : psgUserSolos) {
            if (solo) {
                return true;
            }
        }
        return false;
    }

    private void applyUserMasks(
            OwnedSmpsAudioStream stream, boolean anySolo) {
        int fmMask = 0;
        for (int channel = 0; channel < fmUserMutes.length; channel++) {
            boolean muted = fmUserMutes[channel];
            if (fmUserSolos[channel]) {
                muted = false;
            } else if (anySolo) {
                muted = true;
            }
            if (muted) {
                fmMask |= 1 << channel;
            }
        }
        int psgMask = 0;
        for (int channel = 0; channel < psgUserMutes.length; channel++) {
            boolean muted = psgUserMutes[channel];
            if (psgUserSolos[channel]) {
                muted = false;
            } else if (anySolo) {
                muted = true;
            }
            if (muted) {
                psgMask |= 1 << channel;
            }
        }
        stream.applyChannelMasks(fmMask, psgMask);
    }

    private static int maskOf(boolean[] values) {
        int mask = 0;
        for (int index = 0; index < values.length; index++) {
            if (values[index]) {
                mask |= 1 << index;
            }
        }
        return mask;
    }

    private void restoreUserMasks(
            int fmMuteMask,
            int fmSoloMask,
            int psgMuteMask,
            int psgSoloMask) {
        restoreMask(fmUserMutes, fmMuteMask);
        restoreMask(fmUserSolos, fmSoloMask);
        restoreMask(psgUserMutes, psgMuteMask);
        restoreMask(psgUserSolos, psgSoloMask);
    }

    private static void restoreMask(boolean[] target, int mask) {
        for (int index = 0; index < target.length; index++) {
            target[index] = (mask & (1 << index)) != 0;
        }
    }

    private SmpsSequencerConfig requireSmpsConfig() {
        if (smpsConfig == null) {
            throw new IllegalStateException("SMPS sequencer config not set");
        }
        return smpsConfig;
    }

    private void pushCurrentState() {
        if (currentStream == null || currentSmps == null || smpsDriver == null) {
            return;
        }
        musicStack.push(new MusicState(currentStream, currentSmps, smpsDriver, currentMusicId,
                currentMusicDescriptor));
        AudioDiagnosticObserverException.invoke(() ->
                driverServiceObserver.onLifecycle(
                        SmpsDriverServiceObserver.LifecycleEvent.registry(
                                SmpsDriverServiceObserver.LifecycleKind.SAVE,
                                SmpsDriverServiceObserver.LifecycleSource.MUSIC_OVERRIDE)));
    }

    private void clearMusicStack() {
        for (MusicState state : musicStack) {
            state.driver.stopAll();
            ((OwnedSmpsAudioStream) state.stream).close();
        }
        musicStack.clear();
        pendingRestore = false;
        sfxBlocked = false;  // Unblock SFX when stack is cleared (e.g., level transition)
    }

    private boolean removeSavedOverride(int musicId) {
        if (musicStack.isEmpty()) {
            return false;
        }
        for (Iterator<MusicState> iterator = musicStack.iterator(); iterator.hasNext();) {
            MusicState state = iterator.next();
            if (state.musicId == musicId) {
                state.driver.stopAll();
                ((OwnedSmpsAudioStream) state.stream).close();
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private static AudioSourceDescriptor describeMusic(int musicId) {
        return musicId >= 0 ? AudioSourceDescriptor.baseMusic(musicId) : null;
    }

    private AudioSourceDescriptor consumePendingMusicDescriptor(int musicId) {
        AudioSourceDescriptor descriptor = pendingMusicDescriptor != null
                ? pendingMusicDescriptor
                : describeMusic(musicId);
        pendingMusicDescriptor = null;
        return descriptor;
    }

    private static SmpsSourceDescriptor describeSmpsSource(
            AudioSourceDescriptor descriptor,
            AbstractSmpsData data,
            boolean sfx) {
        if (descriptor == null) {
            return sfx ? SmpsSourceDescriptor.baseSfx(data) : SmpsSourceDescriptor.baseMusic(data);
        }
        return switch (descriptor.route()) {
            case BASE_MUSIC_ID, FALLBACK_MUSIC_ID -> SmpsSourceDescriptor.baseMusic(data);
            case BASE_SFX_ID -> SmpsSourceDescriptor.baseSfx(data);
            case BASE_SFX_NAME, FALLBACK_SFX_NAME -> SmpsSourceDescriptor.baseNamedSfx(descriptor.name(), data);
            case DONOR_MUSIC_ID -> SmpsSourceDescriptor.donorMusic(descriptor.donorGameId(), data);
            case DONOR_SFX_ID -> SmpsSourceDescriptor.donorSfx(descriptor.donorGameId(), data);
            case SYSTEM_COMMAND -> SmpsSourceDescriptor.from(data);
        };
    }

    @Override
    public void update() {
        hookUpdateStream();
        hookCleanupStoppedWavSfx();
    }

    @Override
    public void destroy() {
        stopPlayback();
        hookDestroyDevice();
    }

    @Override
    public void stopAllSfx() {
        // Stop SFX sequencers in the active music driver (mixed into currentStream)
        if (smpsDriver != null) {
            smpsDriver.stopAllSfx();
        }
        // Stop standalone SFX stream (used when SFX played before any music started)
        synchronized (streamLock) {
            closeStandaloneSfxStream();
        }
    }

    private void closeStandaloneSfxStream() {
        if (sfxStream instanceof OwnedSmpsAudioStream owned) {
            owned.logicalDriver().stopAll();
            owned.close();
        }
        sfxStream = null;
    }

    @Override
    public void pause() {
        hookPause();
        AudioDiagnosticObserverException.invoke(() ->
                driverServiceObserver.onLifecycle(
                        SmpsDriverServiceObserver.LifecycleEvent.session(
                                SmpsDriverServiceObserver.LifecycleKind.PAUSE)));
    }

    @Override
    public void resume() {
        hookResume();
        AudioDiagnosticObserverException.invoke(() ->
                driverServiceObserver.onLifecycle(
                        SmpsDriverServiceObserver.LifecycleEvent.session(
                                SmpsDriverServiceObserver.LifecycleKind.RESUME)));
    }
}
