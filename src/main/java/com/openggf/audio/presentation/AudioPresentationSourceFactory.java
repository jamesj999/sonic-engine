package com.openggf.audio.presentation;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioAdmissionObserver;
import com.openggf.audio.AudioAdmissionObserver.AudioAdmissionDecision;
import com.openggf.audio.AudioDiagnosticObserverException;
import com.openggf.audio.SmpsSfxPlaybackPolicy;
import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.driver.SfxContentionObserver;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.AdmissionResult;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.SmpsAdmissionContext;
import com.openggf.audio.presentation.AudioPresentationCommand.MusicVoiceEntry;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.session.PreparedSmpsMusicActivation;
import com.openggf.audio.session.PreparedSmpsSfxProgram;
import com.openggf.audio.session.SmpsDacSelection;
import com.openggf.audio.session.SmpsLogicalTransitionPolicies;
import com.openggf.audio.session.SmpsMusicActivation;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLogicalWriteTarget;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.LoadedSmpsMusic;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSequencerHost;
import com.openggf.audio.synth.ChipWriteObserver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Builds presentation-owned audio sources before rendering begins.
 *
 * <p>SMPS SFX resolution is deliberately split in two. Command submission
 * snapshots loader-owned assets into this factory, while ordered registry
 * apply performs the first sequencer construction and driver attachment.
 */
public final class AudioPresentationSourceFactory
        implements SmpsSfxInstantiation, AudioPresentationDependencyResolver {

    private static final SmpsLogicalWriteTarget DETACHED_WRITE_TARGET =
            new SmpsLogicalWriteTarget() {
                @Override public void writeFm(
                        Object source, int port, int reg, int val) {
                }
                @Override public void writePsg(Object source, int val) {
                }
                @Override public void setInstrument(
                        Object source, int channelId, byte[] voice) {
                }
                @Override public void playDac(Object source, int note) {
                }
                @Override public void stopDac(Object source) {
                }
                @Override public void setDacData(DacData data) {
                }
                @Override public void selectDac(
                        SmpsSourceDescriptor source, DacData data) {
                }
                @Override public void setFmMute(
                        int channel, boolean mute) {
                }
                @Override public void setPsgMute(
                        int channel, boolean mute) {
                }
                @Override public void setDacInterpolate(
                        boolean interpolate) {
                }
                @Override public void silenceAll() {
                }
            };

    @FunctionalInterface
    public interface WavAssets {
        InputStream open(String assetId);
    }

    public record Settings(
            double outputSampleRate,
            SmpsSequencer.Region region,
            boolean dacInterpolate,
            boolean fm6DacOff,
            boolean speedShoesEnabled,
            int speedMultiplier,
            MusicRestoreSink audioManager,
            DecodedPcmCache pcmCache,
            WavAssets wavAssets) {
        public Settings {
            if (!Double.isFinite(outputSampleRate)
                    || outputSampleRate <= 0) {
                throw new IllegalArgumentException(
                        "outputSampleRate must be positive");
            }
            Objects.requireNonNull(region, "region");
            Objects.requireNonNull(audioManager, "audioManager");
            Objects.requireNonNull(pcmCache, "pcmCache");
            Objects.requireNonNull(wavAssets, "wavAssets");
        }

        public static Settings defaults() {
            ClassLoader loader =
                    AudioPresentationSourceFactory.class.getClassLoader();
            return new Settings(
                    48_000,
                    SmpsSequencer.Region.NTSC,
                    false,
                    false,
                    false,
                    1,
                    AudioManager.presentationOwner(),
                    new DecodedPcmCache(),
                    loader::getResourceAsStream);
        }
    }

    /** Immutable catalog-owned values used only by the dormant live backend. */
    public record RegisteredSmpsPlayback(
            AbstractSmpsData program,
            DacData dac,
            SmpsSequencerConfig config,
            SmpsSfxPlaybackPolicy policy) {
        public RegisteredSmpsPlayback {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(dac, "dac");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(policy, "policy");
        }

        public RegisteredSmpsPlayback(
                AbstractSmpsData program,
                DacData dac,
                SmpsSequencerConfig config) {
            this(program, dac, config,
                    SmpsSfxPlaybackPolicy.defaults(false));
        }
    }

    private record LegacySmpsSource(
            String gameId,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig staticConfig,
            boolean coordFlagHandlerRequired,
            boolean specialSfx) {
    }

    private interface DiagnosticDispatcher {
        DiagnosticDispatcher IMMEDIATE = Runnable::run;

        void emit(Runnable callback);
    }

    private final class DeferredDiagnosticRoot
            implements DiagnosticDispatcher {
        private enum State {
            PREPARING,
            DEFERRED,
            COMMITTED,
            DISCARDED
        }

        private final List<Runnable> callbacks = new ArrayList<>();
        private State state = State.PREPARING;
        private long provisionalNextDriverOrdinal =
                nextDriverInstanceOrdinal;

        @Override
        public void emit(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            switch (state) {
                case PREPARING, DEFERRED -> callbacks.add(callback);
                case COMMITTED -> publishDiagnostic(callback);
                case DISCARDED -> {
                    // Provisional voices remain bound here while being stopped.
                }
            }
        }

        private long allocateDriverOrdinal() {
            return provisionalNextDriverOrdinal++;
        }

        private void commitRoot() {
            state = State.COMMITTED;
            nextDriverInstanceOrdinal = provisionalNextDriverOrdinal;
            List<Runnable> deferred = List.copyOf(callbacks);
            callbacks.clear();
            for (Runnable callback : deferred) {
                publishDiagnostic(callback);
            }
        }

        private void publishDiagnostic(Runnable callback) {
            try {
                callback.run();
            } catch (RuntimeException failure) {
                try {
                    diagnosticErrorSink.accept(failure);
                } catch (RuntimeException ignored) {
                    // Diagnostics cannot influence committed audio state.
                }
            }
        }

        private void discardRoot() {
            state = State.DISCARDED;
            callbacks.clear();
        }

        private void rollbackTo(int callbackCount, long driverOrdinal) {
            callbacks.subList(callbackCount, callbacks.size()).clear();
            provisionalNextDriverOrdinal = driverOrdinal;
        }
    }

    private final class DeferredDiagnosticTransaction
            implements DiagnosticTransaction {
        private enum State {
            PREPARING,
            DEFERRED,
            COMMITTED,
            DISCARDED
        }

        private final DeferredDiagnosticRoot root;
        private final boolean rootOwner;
        private final int callbackStart;
        private final long ordinalStart;
        private State state = State.PREPARING;

        private DeferredDiagnosticTransaction(
                DeferredDiagnosticRoot root, boolean rootOwner) {
            this.root = root;
            this.rootOwner = rootOwner;
            callbackStart = root.callbacks.size();
            ordinalStart = root.provisionalNextDriverOrdinal;
        }

        @Override
        public void endPreparation() {
            if (state != State.PREPARING
                    || activeDiagnosticTransactions.isEmpty()
                    || activeDiagnosticTransactions.getLast() != this) {
                throw new IllegalStateException(
                        "diagnostic transaction is not preparing");
            }
            activeDiagnosticTransactions.removeLast();
            state = State.DEFERRED;
            if (activeDiagnosticTransactions.isEmpty()) {
                activeDiagnosticRoot = null;
                root.state = DeferredDiagnosticRoot.State.DEFERRED;
            }
        }

        @Override
        public void commit() {
            if (state != State.DEFERRED) {
                throw new IllegalStateException(
                        "diagnostic transaction cannot be committed");
            }
            state = State.COMMITTED;
            if (rootOwner) {
                root.commitRoot();
            }
        }

        @Override
        public void discard() {
            if (state == State.COMMITTED) {
                throw new IllegalStateException(
                        "committed diagnostic transaction cannot be discarded");
            }
            if (state == State.PREPARING) {
                if (activeDiagnosticTransactions.isEmpty()
                        || activeDiagnosticTransactions.getLast() != this) {
                    throw new IllegalStateException(
                            "diagnostic transaction is not current");
                }
                activeDiagnosticTransactions.removeLast();
            }
            state = State.DISCARDED;
            if (rootOwner) {
                activeDiagnosticTransactions.clear();
                activeDiagnosticRoot = null;
                root.discardRoot();
            } else {
                root.rollbackTo(callbackStart, ordinalStart);
            }
        }
    }

    private record CompatibilityDependencies(
            DacData dac, SmpsSequencerConfig config) {
    }

    private final BooleanSupplier ownerThreadBoundary;
    private final SmpsCoordFlagHandlerOwner coordFlagHandlers;
    private final Settings settings;
    private final SmpsDriverSession smpsSession;
    private final SmpsAssetCatalog assetCatalog;
    private final Map<SmpsSourceDescriptor, SmpsAssetCatalog.ProgramEntry>
            sourcesByDescriptor =
            new HashMap<>();
    private final Map<SmpsAssetCatalog.DependencyKey,
            CompatibilityDependencies> compatibilityDependencies =
            new HashMap<>();
    private final AtomicInteger cacheLookupCount = new AtomicInteger();
    private AudioAdmissionObserver admissionObserver =
            AudioAdmissionObserver.NONE;
    private SmpsDriverServiceObserver driverServiceObserver =
            SmpsDriverServiceObserver.NONE;
    private SmpsRequestAdmissionPolicy sfxAdmissionPolicy =
            SmpsRequestAdmissionPolicy.PERMISSIVE;
    private ChipWriteObserver chipWriteObserver = ChipWriteObserver.NONE;
    private SfxContentionObserver sfxContentionObserver =
            SfxContentionObserver.NONE;
    private Consumer<RuntimeException> diagnosticErrorSink = ignored -> { };
    private long nextServiceOrdinal;
    private long nextDriverInstanceOrdinal;
    private DeferredDiagnosticRoot activeDiagnosticRoot;
    private final List<DeferredDiagnosticTransaction>
            activeDiagnosticTransactions = new ArrayList<>();

    final class ResolutionMutation {
        private final SmpsAssetCatalog.Snapshot catalogBefore =
                assetCatalog.snapshot();
        private final Map<SmpsAssetCatalog.DependencyKey,
                CompatibilityDependencies> compatibilityBefore =
                Map.copyOf(compatibilityDependencies);
        private final Map<SmpsSourceDescriptor,
                SmpsAssetCatalog.ProgramEntry> sourcesBefore =
                Map.copyOf(sourcesByDescriptor);
        private final DiagnosticTransaction diagnostics =
                beginDiagnosticTransaction();
        private boolean prepared;
        private boolean closed;
        private boolean diagnosticsPublished;

        void prepareCommit() {
            requireOpen();
            diagnostics.endPreparation();
            prepared = true;
        }

        void commit() {
            requireOpen();
            if (!prepared) {
                throw new IllegalStateException(
                        "resolution mutation is not prepared");
            }
            closed = true;
        }

        void publishDiagnostics() {
            if (!closed || !prepared) {
                throw new IllegalStateException(
                        "resolution mutation is not committed");
            }
            if (diagnosticsPublished) {
                return;
            }
            diagnosticsPublished = true;
            diagnostics.commit();
        }

        void rollback() {
            requireOpen();
            assetCatalog.restore(catalogBefore);
            compatibilityDependencies.clear();
            compatibilityDependencies.putAll(compatibilityBefore);
            sourcesByDescriptor.clear();
            sourcesByDescriptor.putAll(sourcesBefore);
            diagnostics.discard();
            closed = true;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "resolution mutation is closed");
            }
        }
    }

    public AudioPresentationSourceFactory(
            BooleanSupplier ownerThreadBoundary,
            SmpsCoordFlagHandlerOwner coordFlagHandlers) {
        this(ownerThreadBoundary, coordFlagHandlers, Settings.defaults(),
                null);
    }

    public AudioPresentationSourceFactory(
            BooleanSupplier ownerThreadBoundary,
            SmpsCoordFlagHandlerOwner coordFlagHandlers,
            Settings settings) {
        this(ownerThreadBoundary, coordFlagHandlers, settings, null);
    }

    public AudioPresentationSourceFactory(
            BooleanSupplier ownerThreadBoundary,
            SmpsCoordFlagHandlerOwner coordFlagHandlers,
            Settings settings,
            SmpsDriverSession smpsSession) {
        this.ownerThreadBoundary =
                Objects.requireNonNull(ownerThreadBoundary,
                        "ownerThreadBoundary");
        this.coordFlagHandlers =
                Objects.requireNonNull(coordFlagHandlers, "coordFlagHandlers");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.smpsSession = smpsSession;
        assetCatalog = new SmpsAssetCatalog(coordFlagHandlers);
    }

    public void setAdmissionObserver(AudioAdmissionObserver observer) {
        admissionObserver = Objects.requireNonNull(observer, "observer");
    }

    public void setDriverServiceObserver(
            SmpsDriverServiceObserver observer) {
        driverServiceObserver = Objects.requireNonNull(observer, "observer");
        if (smpsSession != null) {
            smpsSession.setDriverServiceObserver(observer);
        }
    }

    public void setSfxAdmissionPolicy(
            SmpsRequestAdmissionPolicy policy) {
        sfxAdmissionPolicy = Objects.requireNonNull(policy, "policy");
    }

    public void setChipWriteObserver(ChipWriteObserver observer) {
        chipWriteObserver = Objects.requireNonNull(observer, "observer");
        if (smpsSession != null) {
            smpsSession.setChipWriteObserver(observer);
        }
    }

    public void setSfxContentionObserver(
            SfxContentionObserver observer) {
        sfxContentionObserver = Objects.requireNonNull(observer, "observer");
        if (smpsSession != null) {
            smpsSession.setSfxContentionObserver(observer);
        }
    }

    public void setDiagnosticErrorSink(
            Consumer<RuntimeException> errorSink) {
        diagnosticErrorSink = Objects.requireNonNull(errorSink, "errorSink");
        if (smpsSession != null) {
            smpsSession.setDiagnosticErrorSink(errorSink);
        }
    }

    @Override
    public DiagnosticTransaction beginDiagnosticTransaction() {
        assertOwnerBoundary();
        boolean rootOwner = activeDiagnosticRoot == null;
        if (rootOwner) {
            activeDiagnosticRoot = new DeferredDiagnosticRoot();
        }
        DeferredDiagnosticTransaction transaction =
                new DeferredDiagnosticTransaction(
                        activeDiagnosticRoot, rootOwner);
        activeDiagnosticTransactions.add(transaction);
        return transaction;
    }

    ResolutionMutation beginResolutionMutation() {
        return new ResolutionMutation();
    }

    public MusicVoiceEntry musicSmps(
            String gameId,
            int musicId,
            long voiceId,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            AudioSourceDescriptor descriptor,
            int maxStereoFrames) {
        return musicSmpsInternal(gameId, musicId, voiceId, 0,
                data, dac, config, descriptor, maxStereoFrames, true);
    }

    public MusicVoiceEntry musicSmps(
            String gameId,
            int musicId,
            long voiceId,
            long dependencyGeneration,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            AudioSourceDescriptor descriptor,
            int maxStereoFrames) {
        return musicSmpsInternal(gameId, musicId, voiceId,
                dependencyGeneration, data, dac, config, descriptor,
                maxStereoFrames, false);
    }

    private MusicVoiceEntry musicSmpsInternal(
            String gameId,
            int musicId,
            long voiceId,
            long dependencyGeneration,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            AudioSourceDescriptor descriptor,
            int maxStereoFrames,
            boolean compatibilityGenerationZero) {
        String resolvedGameId = requireGameId(gameId);
        Objects.requireNonNull(descriptor, "descriptor");
        SmpsAssetKey key = musicAssetKey(
                resolvedGameId, musicId, descriptor);
        CompatibilityDependencies dependencies = compatibilityGenerationZero
                ? compatibilityDependencies(key, dac, config)
                : new CompatibilityDependencies(dac, config);
        SmpsAssetCatalog.ProgramEntry source = compatibilityGenerationZero
                ? registerCompatibilitySmpsMusicAsset(
                key, data, dependencies.dac(), dependencies.config())
                : registerSmpsMusicAsset(
                key, dependencyGeneration, data,
                dependencies.dac(), dependencies.config());
        return musicSmpsFromRegistered(
                resolvedGameId, musicId, voiceId, descriptor,
                maxStereoFrames, source);
    }

    MusicVoiceEntry musicSmpsFromRegistered(
            String gameId,
            int musicId,
            long voiceId,
            AudioSourceDescriptor descriptor,
            int maxStereoFrames,
            SmpsAssetCatalog.ProgramEntry source) {
        String resolvedGameId = requireGameId(gameId);
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(source, "source");
        if (smpsSession == null) {
            throw new IllegalStateException(
                    "SMPS presentation requires a session owner");
        }
        PreparedSmpsMusicActivation activation =
                prepareMusicActivation(source);
        return new MusicVoiceEntry(
                musicId, descriptor,
                new AudioPresentationCommand.SmpsVoiceDescriptor(
                        voiceId, 0, musicId, descriptor,
                        maxStereoFrames, activation));
    }

    /**
     * Copies a legacy sequencer profile while replacing its mutable
     * coordination handler with this backend's private owner.
     */
    public SmpsSequencerConfig legacySequencerConfig(
            String gameId, SmpsSequencerConfig config) {
        return copyPresentationConfig(
                requireGameId(gameId),
                Objects.requireNonNull(config, "config"),
                config.getCoordFlagHandler() != null);
    }

    public SmpsAssetCatalog.ProgramEntry registerSmpsSfxAsset(
            SmpsAssetKey key,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config) {
        return registerCompatibilitySmpsSfxAsset(
                key, data, dac, config, false);
    }

    public SmpsAssetCatalog.ProgramEntry registerSmpsSfxAsset(
            SmpsAssetKey key,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            boolean specialSfx) {
        return registerCompatibilitySmpsSfxAsset(
                key, data, dac, config, specialSfx);
    }

    public SmpsAssetCatalog.ProgramEntry registerSmpsSfxAsset(
            SmpsAssetKey key,
            long dependencyGeneration,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            boolean specialSfx) {
        SmpsAssetKey resolvedKey = Objects.requireNonNull(key, "key");
        validateSfxKey(resolvedKey);
        return register(new SmpsAssetCatalog.ProgramKey(
                resolvedKey, dependencyGeneration), data, dac, config,
                SmpsSfxPlaybackPolicy.defaults(specialSfx));
    }

    public SmpsAssetCatalog.ProgramEntry registerSmpsSfxAsset(
            SmpsAssetKey key,
            long dependencyGeneration,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            SmpsSfxPlaybackPolicy policy) {
        SmpsAssetKey resolvedKey = Objects.requireNonNull(key, "key");
        validateSfxKey(resolvedKey);
        return register(new SmpsAssetCatalog.ProgramKey(
                resolvedKey, dependencyGeneration), data, dac, config,
                policy);
    }

    public SmpsAssetCatalog.ProgramEntry findRegisteredSmpsSfxAsset(
            SmpsAssetKey key, long dependencyGeneration) {
        SmpsAssetKey resolvedKey = Objects.requireNonNull(key, "key");
        validateSfxKey(resolvedKey);
        return assetCatalog.find(new SmpsAssetCatalog.ProgramKey(
                resolvedKey, dependencyGeneration));
    }

    public RegisteredSmpsPlayback requireRegisteredSmpsSfxPlayback(
            SmpsAssetKey key, long dependencyGeneration) {
        SmpsAssetCatalog.ProgramEntry entry = findRegisteredSmpsSfxAsset(
                key, dependencyGeneration);
        if (entry == null) {
            throw new IllegalStateException(
                    "no registered SMPS SFX for " + key);
        }
        return registeredPlayback(entry);
    }

    public SmpsAssetCatalog.ProgramEntry registerSmpsMusicAsset(
            SmpsAssetKey key,
            long dependencyGeneration,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config) {
        SmpsAssetKey resolvedKey = Objects.requireNonNull(key, "key");
        validateMusicKey(resolvedKey);
        return register(new SmpsAssetCatalog.ProgramKey(
                resolvedKey, dependencyGeneration), data, dac, config,
                SmpsSfxPlaybackPolicy.defaults(false));
    }

    public SmpsAssetCatalog.ProgramEntry registerSmpsMusicAsset(
            SmpsAssetKey key,
            long dependencyGeneration,
            LoadedSmpsMusic loaded,
            DacData dac,
            SmpsSequencerConfig config) {
        SmpsAssetKey resolvedKey = Objects.requireNonNull(key, "key");
        validateMusicKey(resolvedKey);
        Objects.requireNonNull(loaded, "loaded");
        SmpsAssetCatalog.ProgramEntry entry = assetCatalog.register(
                new SmpsAssetCatalog.ProgramKey(
                        resolvedKey, dependencyGeneration),
                loaded.data(), dac, config,
                SmpsSfxPlaybackPolicy.defaults(false), loaded.readiness());
        SmpsAssetCatalog.ProgramEntry previous = sourcesByDescriptor.putIfAbsent(
                entry.sourceDescriptor(), entry);
        if (previous != null && previous != entry) {
            throw new IllegalStateException("SMPS source descriptor collision for "
                    + entry.sourceDescriptor());
        }
        return entry;
    }

    public SmpsAssetCatalog.ProgramEntry findRegisteredSmpsMusicAsset(
            SmpsAssetKey key, long dependencyGeneration) {
        SmpsAssetKey resolvedKey = Objects.requireNonNull(key, "key");
        validateMusicKey(resolvedKey);
        return assetCatalog.find(new SmpsAssetCatalog.ProgramKey(
                resolvedKey, dependencyGeneration));
    }

    public RegisteredSmpsPlayback requireRegisteredSmpsMusicPlayback(
            SmpsAssetKey key, long dependencyGeneration) {
        SmpsAssetCatalog.ProgramEntry entry = findRegisteredSmpsMusicAsset(
                key, dependencyGeneration);
        if (entry == null) {
            throw new IllegalStateException(
                    "no registered SMPS music for " + key);
        }
        return registeredPlayback(entry);
    }

    private static RegisteredSmpsPlayback registeredPlayback(
            SmpsAssetCatalog.ProgramEntry entry) {
        return new RegisteredSmpsPlayback(
                entry.program(), entry.dac(), entry.staticConfig(),
                entry.sfxPolicy());
    }

    private SmpsAssetCatalog.ProgramEntry register(
            SmpsAssetCatalog.ProgramKey key,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            SmpsSfxPlaybackPolicy policy) {
        SmpsAssetCatalog.ProgramEntry entry = assetCatalog.register(
                key, data, dac, config, policy);
        SmpsAssetCatalog.ProgramEntry previous = sourcesByDescriptor.putIfAbsent(
                entry.sourceDescriptor(), entry);
        if (previous != null && previous != entry) {
            throw new IllegalStateException(
                    "SMPS source descriptor collision for "
                            + entry.sourceDescriptor());
        }
        return entry;
    }

    private SmpsAssetCatalog.ProgramEntry registerCompatibilitySmpsSfxAsset(
            SmpsAssetKey key,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            boolean specialSfx) {
        SmpsAssetKey resolvedKey = Objects.requireNonNull(key, "key");
        CompatibilityDependencies dependencies = compatibilityDependencies(
                resolvedKey, dac, config);
        return register(new SmpsAssetCatalog.ProgramKey(resolvedKey, 0),
                data, dependencies.dac(), dependencies.config(),
                SmpsSfxPlaybackPolicy.defaults(specialSfx));
    }

    private SmpsAssetCatalog.ProgramEntry registerCompatibilitySmpsMusicAsset(
            SmpsAssetKey key,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config) {
        return register(new SmpsAssetCatalog.ProgramKey(key, 0),
                data, dac, config, SmpsSfxPlaybackPolicy.defaults(false));
    }

    private CompatibilityDependencies compatibilityDependencies(
            SmpsAssetKey key,
            DacData dac,
            SmpsSequencerConfig config) {
        SmpsAssetCatalog.DependencyKey dependencyKey =
                new SmpsAssetCatalog.ProgramKey(key, 0).dependencyKey();
        return compatibilityDependencies.computeIfAbsent(
                dependencyKey,
                ignored -> new CompatibilityDependencies(
                        Objects.requireNonNull(dac, "dac"),
                        Objects.requireNonNull(config, "config")));
    }

    public ResolvedSmpsSfxSource resolveSmpsSfx(
            long standaloneVoiceId,
            SmpsAssetKey assetKey,
            int pitchQ16,
            int priority,
            int continuousSfxId,
            int trackCount,
            int maxStereoFrames) {
        return resolveSmpsSfx(standaloneVoiceId, assetKey, 0,
                pitchQ16, priority, continuousSfxId, trackCount,
                maxStereoFrames);
    }

    public ResolvedSmpsSfxSource resolveSmpsSfx(
            long standaloneVoiceId,
            SmpsAssetKey assetKey,
            long dependencyGeneration,
            int pitchQ16,
            int priority,
            int continuousSfxId,
            int trackCount,
            int maxStereoFrames) {
        if (pitchQ16 <= 0) {
            throw new IllegalArgumentException("pitchQ16 must be positive");
        }
        validateSfxKey(Objects.requireNonNull(assetKey, "assetKey"));
        SmpsAssetCatalog.ProgramEntry cached =
                findRegisteredSmpsSfxAsset(
                        assetKey, dependencyGeneration);
        return new ResolvedSmpsSfxSource(
                standaloneVoiceId, assetKey, dependencyGeneration,
                pitchQ16, priority,
                continuousSfxId, trackCount, maxStereoFrames,
                cached != null ? cached.assetId() : assetKey.sfxId(),
                cached != null && cached.specialSfx());
    }

    @Override
    public SmpsSequencer instantiateCached(
            ResolvedSmpsSfxSource source,
            SmpsDriver currentOwner) {
        assertOwnerBoundary();
        cacheLookupCount.incrementAndGet();
        SmpsAssetCatalog.ProgramEntry cached = requireCached(source);
        SmpsDriver owner = Objects.requireNonNull(
                currentOwner, "currentOwner");
        SmpsSequencer sequencer = newSequencer(cached, owner);
        sequencer.setSfxMode(true);
        sequencer.setPitch(source.pitchQ16() / 65_536.0f);
        sequencer.setSfxPriority(source.priority());
        sequencer.setSpecialSfx(cached.specialSfx());
        SmpsSequencer music = owner.firstMusicSequencer();
        if (music != null) {
            sequencer.setFallbackVoiceData(music.getSmpsData());
        }
        return sequencer;
    }

    @Override
    public PreparedSmpsSfxProgram prepareCached(
            ResolvedSmpsSfxSource source) {
        assertOwnerBoundary();
        cacheLookupCount.incrementAndGet();
        SmpsAssetCatalog.ProgramEntry cached = requireCached(source);
        SmpsSequencer sequencer = detachedSequencer(cached);
        sequencer.setSfxMode(true);
        sequencer.setPitch(source.pitchQ16() / 65_536.0f);
        sequencer.setSfxPriority(source.priority());
        sequencer.setSpecialSfx(cached.specialSfx());
        return new PreparedSmpsSfxProgram(
                sequencerEntry(sequencer, true, cached, null),
                source.continuousSfxId(), source.trackCount());
    }

    @Override
    public Admission evaluateAdmission(
            ResolvedSmpsSfxSource source, SmpsDriver currentOwner) {
        Objects.requireNonNull(source, "source");
        int requestedId = source.assetKey().sfxId();
        SmpsAdmissionContext context = new SmpsAdmissionContext(
                requestedId, source.resolvedSoundId(), source.priority(),
                SmpsRequestAdmissionPolicy.NO_PRIORITY,
                source.specialSfx(), false);
        AdmissionResult result = Objects.requireNonNull(
                sfxAdmissionPolicy.evaluate(context),
                "SFX admission policy returned no result");
        return new Admission(context, result);
    }

    @Override
    public Admission rejectedAdmission(
            ResolvedSmpsSfxSource source,
            SmpsRequestAdmissionPolicy.RejectionReason reason) {
        Objects.requireNonNull(source, "source");
        SmpsAdmissionContext context = new SmpsAdmissionContext(
                source.assetKey().sfxId(), source.resolvedSoundId(),
                source.priority(), SmpsRequestAdmissionPolicy.NO_PRIORITY,
                source.specialSfx(), false);
        return new Admission(context, new AdmissionResult(false, reason,
                context.priorityBefore(), context.priorityBefore(),
                context.resolvedSoundId()));
    }

    @Override
    public void observeAdmission(Admission admission) {
        if (admissionObserver == AudioAdmissionObserver.NONE) {
            return;
        }
        DiagnosticDispatcher diagnostics = diagnosticDispatcher();
        diagnostics.emit(() ->
                AudioDiagnosticObserverException.invoke(() ->
                        admissionObserver.onDecision(
                                new AudioAdmissionDecision(
                                        admission.context(),
                                        admission.result()))));
    }

    @Override
    public void observeLifecycle(
            SmpsDriverServiceObserver.LifecycleEvent event) {
        if (driverServiceObserver == SmpsDriverServiceObserver.NONE) {
            return;
        }
        DiagnosticDispatcher diagnostics = diagnosticDispatcher();
        diagnostics.emit(() ->
                AudioDiagnosticObserverException.invoke(() ->
                        driverServiceObserver.onLifecycle(event)));
    }

    @Override
    public boolean hasPotentiallyThrowingObserver() {
        return admissionObserver != AudioAdmissionObserver.NONE
                || driverServiceObserver != SmpsDriverServiceObserver.NONE
                || chipWriteObserver != ChipWriteObserver.NONE
                || sfxContentionObserver != SfxContentionObserver.NONE;
    }

    public MusicVoiceEntry fallbackMusic(
            long voiceId,
            int musicId,
            AudioSourceDescriptor descriptor) throws IOException {
        Objects.requireNonNull(descriptor, "descriptor");
        String assetId = "music/"
                + Integer.toHexString(musicId).toUpperCase() + ".wav";
        DecodedPcm pcm = decode(assetId);
        SampleBackedVoice voice = SampleBackedVoice.loopingMusic(
                voiceId, pcm, roundedOutputSampleRate(), 1.0f);
        return MusicVoiceEntry.fromVoice(
                musicId, descriptor, voice);
    }

    public SampleBackedVoice fallbackSfx(
            long voiceId,
            String name,
            int priority,
            float pitch) throws IOException {
        DecodedPcm hostPcm = HostUiSfx.forCue(name);
        DecodedPcm pcm = hostPcm != null
                ? hostPcm
                : decode(fallbackSfxAsset(name));
        return SampleBackedVoice.oneShot(
                voiceId,
                priority,
                pcm,
                roundedOutputSampleRate(),
                pitch,
                1.0f);
    }

    public SampleBackedVoice segaPcm(
            long voiceId,
            DecodedPcm registeredPcm) {
        return SampleBackedVoice.rawSegaPcm(
                voiceId, 0,
                Objects.requireNonNull(registeredPcm, "registeredPcm"),
                roundedOutputSampleRate());
    }

    @Override
    public DecodedPcm resolvePcm(String assetId) {
        DecodedPcm pcm = settings.pcmCache().get(assetId);
        if (pcm == null) {
            pcm = HostUiSfx.forAsset(assetId);
        }
        if (pcm == null) {
            throw new IllegalStateException(
                    "no cached PCM for " + assetId);
        }
        return pcm;
    }

    @Override
    public DacData resolveDac(SmpsSourceDescriptor source) {
        SmpsAssetCatalog.ProgramEntry cached =
                sourcesByDescriptor.get(Objects.requireNonNull(
                        source, "source"));
        if (cached == null) {
            throw new IllegalStateException(
                    "no cached DAC dependency for " + source);
        }
        return cached.dac();
    }

    @Override
    public com.openggf.audio.smps.SmpsLoadReadiness
            resolveSmpsLoadReadiness(SmpsSourceDescriptor source) {
        SmpsAssetCatalog.ProgramEntry cached = sourcesByDescriptor.get(
                Objects.requireNonNull(source, "source"));
        if (cached == null) {
            throw new IllegalStateException(
                    "no cached SMPS load readiness for " + source);
        }
        return cached.readiness();
    }

    public DecodedPcm registerUnsigned8Mono(
            String assetId, byte[] pcm, int sourceRate) {
        return settings.pcmCache().registerUnsigned8Mono(
                assetId, pcm, sourceRate);
    }

    AtomicInteger cacheLookupCountForTesting() {
        return cacheLookupCount;
    }

    private SmpsDriverSnapshot.DependencyResolver wrappingDependencies(
            SmpsDriverSnapshot.DependencyResolver delegate,
            String voiceGameId) {
        return new SmpsDriverSnapshot.DependencyResolver() {
            @Override
            public AbstractSmpsData resolveSmpsData(
                    SmpsDriverSnapshot.SequencerEntry entry) {
                SmpsAssetCatalog.ProgramEntry cached =
                        sourcesByDescriptor.get(entry.source());
                return cached != null
                        ? cached.program()
                        : copySmpsData(delegate.resolveSmpsData(entry));
            }

            @Override
            public DacData resolveDacData(
                    SmpsDriverSnapshot.SequencerEntry entry) {
                SmpsAssetCatalog.ProgramEntry cached =
                        sourcesByDescriptor.get(entry.source());
                DacData dac = cached != null
                        ? cached.dac()
                        : delegate.resolveDacData(entry);
                return Objects.requireNonNull(dac, "dac");
            }

            @Override
            public MusicRestoreSink resolveAudioManager(
                    SmpsDriverSnapshot.SequencerEntry entry) {
                MusicRestoreSink manager = delegate.resolveAudioManager(entry);
                return manager != null ? manager : settings.audioManager();
            }

            @Override
            public SmpsSequencerConfig resolveConfig(
                    SmpsDriverSnapshot.SequencerEntry entry) {
                SmpsAssetCatalog.ProgramEntry cached =
                        sourcesByDescriptor.get(entry.source());
                SmpsSequencerConfig sourceConfig = cached != null
                        ? cached.staticConfig()
                        : delegate.resolveConfig(entry);
                if (cached != null) {
                    return sourceConfig;
                }
                return copyPresentationConfig(
                        gameIdFor(entry.source(), voiceGameId),
                        sourceConfig,
                        sourceConfig.getCoordFlagHandler() != null);
            }
        };
    }

    private SmpsAssetCatalog.ProgramEntry requireCached(
            ResolvedSmpsSfxSource source) {
        Objects.requireNonNull(source, "source");
        SmpsAssetCatalog.ProgramEntry cached =
                findRegisteredSmpsSfxAsset(
                        source.assetKey(), source.dependencyGeneration());
        if (cached == null) {
            throw new SmpsSfxInstantiation.CacheMissException(
                    source.assetKey());
        }
        return cached;
    }

    private SmpsSequencer newSequencer(
            SmpsAssetCatalog.ProgramEntry source, SmpsDriver driver) {
        SmpsSequencer sequencer = new SmpsSequencer(
                source.program(), source.dac(), driver,
                settings.audioManager(),
                source.staticConfig(), source.sourceDescriptor(),
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE);
        sequencer.setSampleRate(settings.outputSampleRate());
        sequencer.setFm6DacOff(settings.fm6DacOff());
        return sequencer;
    }

    private PreparedSmpsMusicActivation prepareMusicActivation(
            SmpsAssetCatalog.ProgramEntry source) {
        SmpsSequencer sequencer = detachedSequencer(source);
        sequencer.setSpeedShoes(settings.speedShoesEnabled());
        sequencer.setSpeedMultiplier(settings.speedMultiplier());
        sequencer.setFallbackVoiceData(source.program());
        SmpsDriverSnapshot.SequencerEntry entry = sequencerEntry(
                sequencer, false, source, source.sourceDescriptor());
        int fmDacTrackCount = 0;
        int psgTrackCount = 0;
        for (int index = 0; index < sequencer.trackCount(); index++) {
            SmpsSequencer.TrackType type = sequencer.trackAt(index).type;
            if (type == SmpsSequencer.TrackType.FM
                    || type == SmpsSequencer.TrackType.DAC) {
                fmDacTrackCount++;
            } else if (type == SmpsSequencer.TrackType.PSG) {
                psgTrackCount++;
            }
        }
        return new PreparedSmpsMusicActivation(
                new SmpsMusicActivation(
                        source.sourceDescriptor(), fmDacTrackCount,
                        psgTrackCount),
                entry,
                SmpsLogicalTransitionPolicies.forConfig(
                        source.staticConfig()),
                new SmpsDacSelection(
                        source.sourceDescriptor(), source.dac()),
                source.readiness());
    }

    private SmpsSequencer detachedSequencer(
            SmpsAssetCatalog.ProgramEntry source) {
        SmpsSequencer sequencer = new SmpsSequencer(
                source.program(), source.dac(),
                DETACHED_WRITE_TARGET, SmpsSequencerHost.NONE,
                settings.audioManager(), source.staticConfig(),
                source.sourceDescriptor(),
                SmpsSequencer.SourceDescriptorTrust
                        .PRECOMPUTED_IMMUTABLE);
        sequencer.setSampleRate(settings.outputSampleRate());
        sequencer.setFm6DacOff(settings.fm6DacOff());
        sequencer.setRegion(settings.region());
        return sequencer;
    }

    private static SmpsDriverSnapshot.SequencerEntry sequencerEntry(
            SmpsSequencer sequencer,
            boolean sfx,
            SmpsAssetCatalog.ProgramEntry source,
            SmpsSourceDescriptor fallbackVoiceSource) {
        return new SmpsDriverSnapshot.SequencerEntry(
                sfx,
                source.sourceDescriptor(),
                SmpsSequencer.SourceDescriptorTrust
                        .PRECOMPUTED_IMMUTABLE,
                fallbackVoiceSource,
                source.program(), source.dac(),
                sequencer.getAudioManager(), source.staticConfig(),
                sequencer.captureSnapshot());
    }

    private SmpsSequencer newLegacySequencer(
            LegacySmpsSource source,
            SmpsDriver driver,
            SmpsSourceDescriptor descriptor) {
        SmpsSequencer sequencer = new SmpsSequencer(
                source.data(), source.dac(), driver, driver,
                settings.audioManager(),
                copyPresentationConfig(
                        source.gameId(), source.staticConfig(),
                        source.coordFlagHandlerRequired()),
                descriptor,
                SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE);
        sequencer.setSampleRate(settings.outputSampleRate());
        sequencer.setFm6DacOff(settings.fm6DacOff());
        return sequencer;
    }

    private DiagnosticDispatcher diagnosticDispatcher() {
        return activeDiagnosticRoot == null
                ? DiagnosticDispatcher.IMMEDIATE
                : activeDiagnosticRoot;
    }

    private long allocateDriverOrdinal() {
        return activeDiagnosticRoot == null
                ? nextDriverInstanceOrdinal++
                : activeDiagnosticRoot.allocateDriverOrdinal();
    }

    private void installDiagnosticObservers(
            SmpsDriver driver, DiagnosticDispatcher diagnostics) {
        if (sfxContentionObserver != SfxContentionObserver.NONE) {
            SfxContentionObserver observer = sfxContentionObserver;
            driver.setSfxContentionObserver(new SfxContentionObserver() {
                @Override
                public void onSfxAdmitted(Admission admission) {
                    diagnostics.emit(() ->
                            AudioDiagnosticObserverException.invoke(() ->
                                    observer.onSfxAdmitted(admission)));
                }

                @Override
                public void onRoleArbitrated(Arbitration arbitration) {
                    diagnostics.emit(() ->
                            AudioDiagnosticObserverException.invoke(() ->
                                    observer.onRoleArbitrated(arbitration)));
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
                diagnostics.emit(() ->
                        AudioDiagnosticObserverException.invoke(() ->
                                observer.onServiceBegin(emitted)));
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
                diagnostics.emit(() ->
                        AudioDiagnosticObserverException.invoke(() ->
                                observer.onServiceEnd(completed, snapshot)));
            }

            @Override
            public void onLifecycle(LifecycleEvent event) {
                diagnostics.emit(() ->
                        AudioDiagnosticObserverException.invoke(() ->
                                observer.onLifecycle(event)));
            }
        });
    }

    private static SmpsDriverServiceObserver.DriverAdmissionOrigin musicOrigin(
            long voiceId, int musicId) {
        return new SmpsDriverServiceObserver.DriverAdmissionOrigin(
                SmpsDriverServiceObserver.DriverOriginKind.MUSIC,
                voiceId, musicId);
    }

    private static SmpsDriverServiceObserver.DriverAdmissionOrigin sfxOrigin(
            long voiceId, int sfxId) {
        return new SmpsDriverServiceObserver.DriverAdmissionOrigin(
                SmpsDriverServiceObserver.DriverOriginKind.SFX,
                voiceId, sfxId);
    }

    private ChipWriteObserver diagnosticChipWriteObserver(
            DiagnosticDispatcher diagnostics) {
        if (chipWriteObserver == ChipWriteObserver.NONE) {
            return ChipWriteObserver.NONE;
        }
        ChipWriteObserver observer = chipWriteObserver;
        return new ChipWriteObserver() {
            @Override
            public void onYm2612Write(
                    int port, int register, int value) {
                diagnostics.emit(() ->
                        AudioDiagnosticObserverException.invoke(() ->
                                observer.onYm2612Write(
                                        port, register, value)));
            }

            @Override
            public void onPsgWrite(int value) {
                diagnostics.emit(() ->
                        AudioDiagnosticObserverException.invoke(() ->
                                observer.onPsgWrite(value)));
            }

            @Override
            public boolean observesPhysicalWrites() {
                return observer.observesPhysicalWrites();
            }

            @Override
            public void onYm2612BusWrite(long cycle, int busPort, int value,
                    ChipWriteObserver.PhysicalWriteOrigin origin) {
                diagnostics.emit(() -> AudioDiagnosticObserverException.invoke(
                        () -> observer.onYm2612BusWrite(
                                cycle, busPort, value, origin)));
            }

            @Override
            public void onPsgBusWrite(long tick, int value) {
                diagnostics.emit(() -> AudioDiagnosticObserverException.invoke(
                        () -> observer.onPsgBusWrite(tick, value)));
            }

            @Override
            public void onPhysicalTimelineBoundary(
                    ChipWriteObserver.ChipClockDomain domain, long clock,
                    ChipWriteObserver.PhysicalTimelineBoundary boundary) {
                diagnostics.emit(() -> AudioDiagnosticObserverException.invoke(
                        () -> observer.onPhysicalTimelineBoundary(
                                domain, clock, boundary)));
            }
        };
    }

    private SmpsSequencerConfig copyPresentationConfig(
            String gameId,
            SmpsSequencerConfig sourceConfig,
            boolean coordFlagHandlerRequired) {
        return SmpsAssetCatalog.bindLegacyConfig(
                requireGameId(gameId), sourceConfig,
                coordFlagHandlerRequired, coordFlagHandlers);
    }

    private DecodedPcm decode(String assetId) throws IOException {
        return settings.pcmCache().getOrDecode(
                assetId, () -> settings.wavAssets().open(assetId));
    }

    private int roundedOutputSampleRate() {
        long rounded = Math.round(settings.outputSampleRate());
        if (rounded <= 0 || rounded > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "output sample rate cannot be represented as an integer");
        }
        return (int) rounded;
    }

    private static String fallbackSfxAsset(String name) {
        String value = Objects.requireNonNull(name, "name");
        return switch (value) {
            case "JUMP" -> "sfx/jump.wav";
            case "RING", "RING_LEFT", "RING_RIGHT" -> "sfx/ring.wav";
            case "SPINDASH", "SPINDASH_CHARGE" -> "sfx/spindash.wav";
            case "SKID" -> "sfx/skid.wav";
            default -> "sfx/" + value.toLowerCase() + ".wav";
        };
    }

    private static String gameIdFor(
            SmpsSourceDescriptor source, String fallback) {
        if (source.donorGameId() != null) {
            return source.donorGameId();
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalStateException(
                "no game id cached for SMPS source " + source);
    }

    private static SmpsSourceDescriptor describeLegacyMusic(
            AudioSourceDescriptor descriptor, AbstractSmpsData data) {
        return switch (descriptor.route()) {
            case BASE_MUSIC_ID -> SmpsSourceDescriptor.baseMusic(data);
            case DONOR_MUSIC_ID -> SmpsSourceDescriptor.donorMusic(
                    descriptor.donorGameId(), data);
            default -> SmpsSourceDescriptor.from(data);
        };
    }

    private static SmpsAssetKey musicAssetKey(
            String gameId,
            int musicId,
            AudioSourceDescriptor descriptor) {
        SmpsAssetKey.Route route = switch (descriptor.route()) {
            case BASE_MUSIC_ID -> SmpsAssetKey.Route.BASE_MUSIC;
            case DONOR_MUSIC_ID -> SmpsAssetKey.Route.DONOR_MUSIC;
            default -> throw new IllegalArgumentException(
                    "SMPS music requires a base or donor descriptor");
        };
        return new SmpsAssetKey(gameId, route, musicId, null);
    }

    private static void validateSfxKey(SmpsAssetKey key) {
        switch (key.route()) {
            case BASE_ID, DONOR_ID -> {
                if (key.assetId() < 0 || key.assetName() != null) {
                    throw new IllegalArgumentException(
                            "id SMPS route requires only a non-negative id");
                }
            }
            case BASE_NAME, FALLBACK_NAME -> {
                if (key.assetName() == null
                        || key.assetName().isBlank()) {
                    throw new IllegalArgumentException(
                            "named SMPS route requires a name");
                }
            }
            case BASE_MUSIC, DONOR_MUSIC ->
                    throw new IllegalArgumentException(
                            "music route cannot register an SFX asset");
        }
    }

    private static void validateMusicKey(SmpsAssetKey key) {
        switch (key.route()) {
            case BASE_MUSIC, DONOR_MUSIC -> {
                if (key.assetId() < 0 || key.assetName() != null) {
                    throw new IllegalArgumentException(
                            "music route requires only a non-negative id");
                }
            }
            case BASE_ID, BASE_NAME, DONOR_ID, FALLBACK_NAME ->
                    throw new IllegalArgumentException(
                            "SFX route cannot register a music asset");
        }
    }

    private void assertOwnerBoundary() {
        if (!ownerThreadBoundary.getAsBoolean()) {
            throw new IllegalStateException(
                    "SMPS source instantiation requires the owner boundary");
        }
    }

    private static String requireGameId(String gameId) {
        String value = Objects.requireNonNull(gameId, "gameId");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "gameId must not be blank");
        }
        return value;
    }

    private static AbstractSmpsData copySmpsData(
            AbstractSmpsData source) {
        return SmpsAssetCatalog.freezeStandalone(
                Objects.requireNonNull(source, "source"));
    }
}
