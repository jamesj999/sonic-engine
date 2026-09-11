package com.openggf.audio.presentation;

import com.openggf.audio.AudioDiagnosticObserverException;
import com.openggf.audio.GameSound;
import com.openggf.audio.SmpsSfxPlaybackPolicy;
import com.openggf.audio.presentation.AudioPresentationCommand.AddSmpsSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.ChangeMusicTempo;
import com.openggf.audio.presentation.AudioPresentationCommand.EndMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.FadeMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.PushMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.ResetRingAlternation;
import com.openggf.audio.presentation.AudioPresentationCommand.RestoreMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.SetSpeedMultiplier;
import com.openggf.audio.presentation.AudioPresentationCommand.SetSpeedShoes;
import com.openggf.audio.presentation.AudioPresentationCommand.StartSampleSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.StopAllSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.StopMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.StopRawPcm;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.LoadedSmpsMusic;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLoader;
import com.openggf.audio.smps.SmpsSequencerConfig;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Resolves logical audio commands into immutable presentation mutations.
 *
 * <p>This class has no registry or backend reference. Loading, WAV decoding,
 * and catalog registration happen synchronously during submission; queued
 * SMPS SFX contain only an asset key and primitive metadata.
 */
public final class AudioPresentationCommandResolver {

    /**
     * Applies one resolved command on behalf of the production presentation
     * owner. The owner supplies the implementation, so the resolver publishes
     * outcomes without holding a reference to the producer itself and the
     * AudioManager-only producer entry-point boundary stays intact.
     */
    @FunctionalInterface
    public interface ResolvedCommandApplier {
        void apply(AudioCommand request,
                   AudioPresentationCommand resolvedCommand);
    }

    public sealed interface ResolutionResult
            permits CompleteSuccess, Failure {
        AudioCommand request();
    }

    public static final class CompleteSuccess implements ResolutionResult {
        private final AudioCommand request;
        private final List<AudioPresentationCommand> commands;
        private final List<String> warnings;
        private final OutcomeReservation reservation;

        private CompleteSuccess(AudioCommand request,
                List<AudioPresentationCommand> commands,
                List<String> warnings, OutcomeReservation reservation) {
            this.request = request;
            this.commands = List.copyOf(commands);
            this.warnings = List.copyOf(warnings);
            this.reservation = reservation;
        }

        @Override public AudioCommand request() { return request; }
    }

    public record Failure(AudioCommand request) implements ResolutionResult {
        public Failure {
            Objects.requireNonNull(request, "request");
        }
    }

    public static final class OutcomeReservation {
        private final Object resolverIdentity;
        private final Object batchIdentity;
        private final long ordinal;

        private OutcomeReservation(Object resolverIdentity,
                Object batchIdentity, long ordinal) {
            this.resolverIdentity = resolverIdentity;
            this.batchIdentity = batchIdentity;
            this.ordinal = ordinal;
        }
    }

    public static final class AppliedOutcome {
        private final AudioCommand request;
        private final List<AudioPresentationCommand> commands;
        private final OutcomeReservation reservation;

        private AppliedOutcome(
                AudioCommand request, List<AudioPresentationCommand> commands,
                OutcomeReservation reservation) {
            this.request = Objects.requireNonNull(request, "request");
            this.commands = List.copyOf(commands);
            this.reservation = reservation;
        }

        public AudioCommand request() {
            return request;
        }

        public List<AudioPresentationCommand> commands() {
            return commands;
        }

        public boolean belongsTo(OutcomeReservation expected) {
            return reservation == expected
                    && reservation.resolverIdentity == expected.resolverIdentity
                    && reservation.batchIdentity == expected.batchIdentity
                    && reservation.ordinal == expected.ordinal;
        }

        public OutcomeSeal seal(OutcomeReservation expected) {
            if (!belongsTo(expected)) {
                throw new IllegalArgumentException(
                        "reservation does not own this outcome");
            }
            return new OutcomeSeal(this, expected);
        }
    }

    public static final class OutcomeSeal {
        private final AppliedOutcome outcome;
        private final OutcomeReservation reservation;

        private OutcomeSeal(AppliedOutcome outcome,
                OutcomeReservation reservation) {
            this.outcome = outcome;
            this.reservation = reservation;
        }
    }

    public interface Sources {
        SourceAccess sourceFor(
                SmpsAssetKey.Route route, String donorGameId);

        int maxStereoFrames();
    }

    /** Immutable policy captured with one loader/dependency publication. */
    public interface SfxPolicy {
        int priority(int sfxId);

        boolean special(int sfxId);

        boolean continuous(int sfxId);
    }

    /**
     * One immutable route source handle. Resolution performs every loader,
     * dependency, and policy lookup through this captured value so a reentrant
     * source replacement cannot compose two published generations.
     */
    public record SourceAccess(
            String gameId,
            long dependencyGeneration,
            SmpsLoader loader,
            DacData dac,
            SmpsSequencerConfig config,
            SfxPolicy sfxPolicy) {
        public SourceAccess {
            requireGameId(gameId);
            if (dependencyGeneration < 0) {
                throw new IllegalArgumentException(
                        "dependencyGeneration must be non-negative");
            }
            Objects.requireNonNull(sfxPolicy, "sfxPolicy");
        }
    }

    private final AudioPresentationCommandQueue queue;
    private final AudioPresentationSourceFactory factory;
    private final Sources sources;
    private final Consumer<String> warningConsumer;
    private final BooleanSupplier ownerThreadBoundary;
    private final Consumer<AudioPresentationCommand> synchronousApply;
    private final Runnable synchronousDrain;
    private long nextVoiceId = 1;
    private long nextBatchOrdinal = 1;
    private final Object resolverIdentity = new Object();
    private ResolvedCommandApplier forwardExecutor;
    private ResolutionBatch activeResolutionBatch;

    final class ResolutionBatch {
        private final long voiceCursorBefore = nextVoiceId;
        private final long batchOrdinalBefore = nextBatchOrdinal;
        private final AudioPresentationSourceFactory.ResolutionMutation
                factoryMutation = factory.beginResolutionMutation();
        private final AudioPresentationCommandQueue privateCommands =
                new AudioPresentationCommandQueue();
        private final List<String> privateWarnings = new java.util.ArrayList<>();
        private final OutcomeReservation reservation =
                new OutcomeReservation(resolverIdentity, new Object(),
                        nextBatchOrdinal++);
        private AudioCommand request;
        private ResolutionResult resolution;
        private AppliedOutcome appliedOutcome;
        private boolean prepared;
        private boolean closed;

        private ResolutionBatch() {
        }

        ResolutionResult resolve(AudioCommand command) {
            requireOpen();
            if (request != null) {
                throw new IllegalStateException(
                        "a request batch resolves exactly one consequence");
            }
            request = Objects.requireNonNull(command, "command");
            submit(command);
            List<AudioPresentationCommand> prepared =
                    privateCommands.snapshotCommands();
            List<AudioPresentationCommand> complete = completeCommands(
                    command, prepared);
            if (complete == null) {
                privateWarnings.clear();
                resolution = new Failure(command);
            } else {
                resolution = new CompleteSuccess(command, complete,
                        privateWarnings, reservation);
            }
            return resolution;
        }

        OutcomeReservation reservation() {
            requireOpen();
            return reservation;
        }

        AppliedOutcome apply() {
            requireOpen();
            if (request == null || resolution == null
                    || appliedOutcome != null) {
                throw new IllegalStateException(
                        "resolution batch is not ready to apply");
            }
            if (!(resolution instanceof CompleteSuccess success)) {
                throw new IllegalStateException(
                        "failed resolution cannot be applied");
            }
            ResolvedCommandApplier executor = forwardExecutor;
            if (executor == null) {
                throw new IllegalStateException(
                        "resolution batch has no production executor");
            }
            for (AudioPresentationCommand command : success.commands) {
                executor.apply(request, command);
            }
            appliedOutcome = new AppliedOutcome(request, success.commands,
                    reservation);
            return appliedOutcome;
        }

        void prepareCommit() {
            requireOpen();
            if (appliedOutcome == null) {
                throw new IllegalStateException(
                        "resolution batch has not been applied");
            }
            factoryMutation.prepareCommit();
            prepared = true;
        }

        void commit() {
            requireOpen();
            if (!prepared) {
                throw new IllegalStateException(
                        "resolution batch is not prepared");
            }
            factoryMutation.commit();
            closed = true;
            activeResolutionBatch = null;
        }

        void publishDiagnostics(
                AudioPresentationForwardService.CommittedReceipt receipt) {
            if (!closed || appliedOutcome == null
                    || !(resolution instanceof CompleteSuccess success)) {
                throw new IllegalStateException(
                        "resolution batch is not committed");
            }
            OutcomeSeal seal = receipt == null ? null
                    : receipt.sealFor(appliedOutcome);
            if (seal == null || seal.outcome != appliedOutcome
                    || seal.reservation != reservation) {
                throw new IllegalArgumentException(
                        "receipt does not seal this resolution outcome");
            }
            factoryMutation.publishDiagnostics();
            for (String warning : success.warnings) {
                warningConsumer.accept(warning);
            }
        }

        void rollback() {
            requireOpen();
            factoryMutation.rollback();
            nextVoiceId = voiceCursorBefore;
            nextBatchOrdinal = batchOrdinalBefore;
            closed = true;
            activeResolutionBatch = null;
        }

        private List<AudioPresentationCommand> completeCommands(
                AudioCommand command,
                List<AudioPresentationCommand> prepared) {
            if (command instanceof AudioCommand.PlayMusic) {
                if (prepared.size() != 1
                        || (!(prepared.getFirst() instanceof ReplaceMusic)
                        && !(prepared.getFirst() instanceof PushMusicOverride))) {
                    return null;
                }
                return List.of(new StopAllSfx(), prepared.getFirst());
            }
            if (command instanceof AudioCommand.PlaySfx sfx
                    && sfx.route() == AudioCommand.SfxRoute.RING_RESOLVED) {
                return prepared.size() == 2
                        && prepared.get(0) instanceof ResetRingAlternation
                        && prepared.get(1) instanceof StartSampleSfx
                        ? prepared : null;
            }
            return prepared.size() == 1 ? prepared : null;
        }

        private void enqueuePrivate(AudioPresentationCommand command) {
            privateCommands.submit(command, () -> true,
                    () -> { throw new IllegalStateException(
                            "private resolution batch exceeded capacity"); });
        }

        private void requireOpen() {
            if (closed || activeResolutionBatch != this) {
                throw new IllegalStateException(
                        "resolution batch is closed");
            }
        }
    }

    ResolutionBatch beginResolutionBatch() {
        if (activeResolutionBatch != null) {
            throw new IllegalStateException(
                    "a resolution batch is already active");
        }
        ResolutionBatch batch = new ResolutionBatch();
        activeResolutionBatch = batch;
        return batch;
    }

    void bindForwardExecutor(ResolvedCommandApplier executor) {
        Objects.requireNonNull(executor, "executor");
        if (forwardExecutor != null && forwardExecutor != executor) {
            throw new IllegalStateException(
                    "resolver already has a production executor");
        }
        forwardExecutor = executor;
    }

    public AudioPresentationCommandResolver(
            AudioPresentationCommandQueue queue,
            AudioPresentationSourceFactory factory,
            Sources sources,
            Consumer<String> warningConsumer,
            BooleanSupplier ownerThreadBoundary,
            Consumer<AudioPresentationCommand> synchronousApply) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.warningConsumer =
                Objects.requireNonNull(warningConsumer, "warningConsumer");
        this.ownerThreadBoundary = Objects.requireNonNull(
                ownerThreadBoundary, "ownerThreadBoundary");
        this.synchronousApply = Objects.requireNonNull(
                synchronousApply, "synchronousApply");
        synchronousDrain = null;
    }

    public AudioPresentationCommandResolver(
            AudioPresentationCommandQueue queue,
            AudioPresentationSourceFactory factory,
            Sources sources,
            Consumer<String> warningConsumer,
            BooleanSupplier ownerThreadBoundary,
            Runnable synchronousDrain) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.warningConsumer =
                Objects.requireNonNull(warningConsumer, "warningConsumer");
        this.ownerThreadBoundary = Objects.requireNonNull(
                ownerThreadBoundary, "ownerThreadBoundary");
        synchronousApply = null;
        this.synchronousDrain = Objects.requireNonNull(
                synchronousDrain, "synchronousDrain");
    }

    public static AudioPresentationCommandResolver controlsOnly(
            AudioPresentationCommandQueue queue,
            AudioPresentationSourceFactory factory,
            BooleanSupplier ownerThreadBoundary,
            Consumer<AudioPresentationCommand> synchronousApply) {
        return new AudioPresentationCommandResolver(
                queue, factory, new EmptySources(), ignored -> {
                }, ownerThreadBoundary, synchronousApply);
    }

    public void submit(AudioCommand command) {
        Objects.requireNonNull(command, "command");
        switch (command) {
            case AudioCommand.PlayMusic music -> submitMusic(music);
            case AudioCommand.PlaySfx sfx -> submitSfx(sfx);
            case AudioCommand.FadeOutMusic fade ->
                    enqueue(new FadeMusic(fade.steps(), fade.delay()));
            case AudioCommand.StopMusic ignored ->
                    enqueue(new StopMusic());
            case AudioCommand.StopAllSfx ignored ->
                    enqueue(new StopAllSfx());
            case AudioCommand.StopSmpsSfx stop ->
                    enqueue(new AudioPresentationCommand.StopSmpsSfx(
                            stop.sourceCommandId()));
            case AudioCommand.SilencePsg silence ->
                    enqueue(new AudioPresentationCommand.SilencePsg(
                            silence.sourceCommandId()));
            case AudioCommand.RetainGlobalStop stop ->
                    enqueue(new AudioPresentationCommand.RetainGlobalStop(
                            stop.sourceCommandId()));
            case AudioCommand.PlaySegaPcm sega ->
                    submitRawPcm(sega.pcm(), sega.sourceRate());
            case AudioCommand.StopRawPcm ignored ->
                    enqueue(new StopRawPcm());
            case AudioCommand.StopSegaPcmAndRetainGlobalStop stop ->
                    enqueue(new AudioPresentationCommand
                            .StopRawPcmAndRetainGlobalStop(
                            stop.sourceCommandId()));
            case AudioCommand.ReferenceLimitation limitation ->
                    enqueue(new AudioPresentationCommand.ReferenceLimitation(
                            limitation.sourceCommandId(),
                            limitation.reason()));
            case AudioCommand.EndMusicOverride end ->
                    enqueue(new EndMusicOverride(end.musicId()));
            case AudioCommand.RestoreMusic ignored ->
                    enqueue(new RestoreMusicOverride());
            case AudioCommand.SetSpeedShoes speed ->
                    enqueue(new SetSpeedShoes(speed.enabled()));
            case AudioCommand.SetSpeedMultiplier speed ->
                    enqueue(new SetSpeedMultiplier(speed.multiplier()));
            case AudioCommand.ChangeMusicTempo tempo ->
                    enqueue(new ChangeMusicTempo(tempo.dividingTiming()));
            case AudioCommand.ResetRingAlternation ring ->
                    enqueue(new ResetRingAlternation(ring.ringLeft()));
        }
    }

    /**
     * Submits an SMPS SFX against the source tuple captured by its caller.
     *
     * <p>Public manager entry points classify and register before publishing
     * their logical command. Retaining that exact source here prevents a
     * reentrant ROM/profile/donor replacement during the first loader call
     * from resolving the published command against another generation.
     * Direct resolver callers continue to use {@link #submit(AudioCommand)}
     * and retain lookup-before-load miss handling.</p>
     */
    public void submitRegisteredSmpsSfx(
            AudioCommand.PlaySfx command, SourceAccess source) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(source, "source");
        if (command.route() == AudioCommand.SfxRoute.FALLBACK_NAME
                || command.route() == AudioCommand.SfxRoute.RING_RESOLVED) {
            throw new IllegalArgumentException(
                    "registered SMPS submission requires an SMPS route");
        }
        submitSfx(command, source);
    }

    public void submitRawPcm(byte[] pcm, int sourceRate) {
        byte[] source = Objects.requireNonNull(pcm, "pcm").clone();
        String assetId = "sega-pcm:" + sourceRate + ":"
                + source.length + ":" + HexFormat.of().formatHex(source);
        DecodedPcm registered = factory.registerUnsigned8Mono(
                assetId, source, sourceRate);
        enqueue(AudioPresentationCommand.ReplaceRawPcm.fromVoice(
                factory.segaPcm(allocateVoiceId(), registered), source));
    }

    public void stopRawPcm() {
        enqueue(new StopRawPcm());
    }

    private void submitMusic(AudioCommand.PlayMusic command) {
        if (command.route() == AudioCommand.MusicRoute.SYSTEM_COMMAND) {
            warn("Rejected system music command " + command.musicId()
                    + ": presentation system-command semantics are unsupported");
            return;
        }
        AudioPresentationCommand resolved;
        try {
            AudioPresentationCommand.MusicVoiceEntry voice =
                    switch (command.route()) {
                        case BASE_SMPS -> {
                            SourceAccess source = sources.sourceFor(
                                    SmpsAssetKey.Route.BASE_MUSIC, null);
                            yield resolveSmpsMusic(
                                    SmpsAssetKey.Route.BASE_MUSIC,
                                    source, command.musicId(),
                                    AudioSourceDescriptor.baseMusic(
                                            command.musicId()));
                        }
                        case DONOR_SMPS -> {
                            String gameId = requireGameId(
                                    command.donorGameId());
                            SourceAccess source = sources.sourceFor(
                                    SmpsAssetKey.Route.DONOR_MUSIC, gameId);
                            yield resolveSmpsMusic(
                                    SmpsAssetKey.Route.DONOR_MUSIC,
                                    source, command.musicId(),
                                    AudioSourceDescriptor.donorMusic(
                                            command.donorGameId(),
                                            command.musicId()));
                        }
                        case FALLBACK_WAV -> factory.fallbackMusic(
                                allocateVoiceId(),
                                command.musicId(),
                                AudioSourceDescriptor.fallbackMusic(
                                        command.musicId()));
                        case SYSTEM_COMMAND ->
                                throw new AssertionError("handled above");
                    };
            resolved = command.override()
                    ? new PushMusicOverride(voice)
                    : new ReplaceMusic(voice);
        } catch (IOException | RuntimeException failure) {
            AudioDiagnosticObserverException.rethrowIfPresent(failure);
            warn("Rejected music " + command.musicId()
                    + " via " + command.route() + ": "
                    + failure.getMessage());
            return;
        }
        enqueue(resolved);
    }

    private AudioPresentationCommand.MusicVoiceEntry resolveSmpsMusic(
            SmpsAssetKey.Route route,
            SourceAccess source,
            int musicId,
            AudioSourceDescriptor descriptor) {
        SmpsAssetKey key = new SmpsAssetKey(
                source.gameId(), route, musicId, null);
        SmpsAssetCatalog.ProgramEntry entry =
                factory.findRegisteredSmpsMusicAsset(
                        key, source.dependencyGeneration());
        if (entry == null) {
            LoadedSmpsMusic loaded = Objects.requireNonNull(
                    loadMusic(route, source, musicId),
                    "loader returned no SMPS data");
            entry = factory.registerSmpsMusicAsset(
                    key, source.dependencyGeneration(), loaded,
                    requireDac(route, source),
                    requireConfig(route, source));
        }
        return factory.musicSmpsFromRegistered(
                source.gameId(),
                musicId,
                allocateVoiceId(),
                descriptor,
                sources.maxStereoFrames(), entry);
    }

    private void submitSfx(AudioCommand.PlaySfx command) {
        submitSfx(command, null);
    }

    private void submitSfx(
            AudioCommand.PlaySfx command, SourceAccess capturedSource) {
        if (command.route() == AudioCommand.SfxRoute.RING_RESOLVED) {
            enqueue(new ResetRingAlternation(
                    !GameSound.RING_LEFT.name().equals(command.sfxName())));
        }
        AudioPresentationCommand resolved;
        try {
            resolved = switch (command.route()) {
                case BASE_SMPS_ID -> {
                    SourceAccess source = capturedSource != null
                            ? capturedSource
                            : sources.sourceFor(
                            SmpsAssetKey.Route.BASE_ID, null);
                    SmpsAssetKey key = new SmpsAssetKey(
                            source.gameId(), SmpsAssetKey.Route.BASE_ID,
                            command.sfxId(), null);
                    yield resolveSmpsSfxCommand(
                            source, key, command.sfxId(),
                            () -> loadSfx(source, command.sfxId()),
                            command.pitch());
                }
                case BASE_SMPS_NAME -> {
                    SourceAccess source = capturedSource != null
                            ? capturedSource
                            : sources.sourceFor(
                            SmpsAssetKey.Route.BASE_NAME, null);
                    SmpsAssetKey key = new SmpsAssetKey(
                            source.gameId(), SmpsAssetKey.Route.BASE_NAME,
                            -1, command.sfxName());
                    yield resolveSmpsSfxCommand(
                            source, key, -1,
                            () -> loadSfx(source, command.sfxName()),
                            command.pitch());
                }
                case DONOR_SMPS -> {
                    String gameId = requireGameId(command.donorGameId());
                    SourceAccess source = capturedSource != null
                            ? capturedSource
                            : sources.sourceFor(
                            SmpsAssetKey.Route.DONOR_ID, gameId);
                    if (!gameId.equals(source.gameId())) {
                        throw new IllegalArgumentException(
                                "captured donor source does not match "
                                        + gameId);
                    }
                    SmpsAssetKey key = new SmpsAssetKey(
                            source.gameId(), SmpsAssetKey.Route.DONOR_ID,
                            command.sfxId(), null);
                    yield resolveSmpsSfxCommand(
                            source, key, command.sfxId(),
                            () -> loadSfx(source, command.sfxId()),
                            command.pitch());
                }
                case FALLBACK_NAME, RING_RESOLVED ->
                        StartSampleSfx.fromVoice(
                                factory.fallbackSfx(
                                        allocateVoiceId(),
                                        command.sfxName(),
                                        0,
                                        command.pitch()));
            };
        } catch (IOException | RuntimeException failure) {
            AudioDiagnosticObserverException.rethrowIfPresent(failure);
            warn("Rejected SFX " + command.sfxName()
                    + "/" + command.sfxId() + " via "
                    + command.route() + ": " + failure.getMessage());
            return;
        }
        enqueue(resolved);
    }

    private AudioPresentationCommand resolveSmpsSfxCommand(
            SourceAccess source,
            SmpsAssetKey key,
            int requestedSfxId,
            SmpsSfxLoader loader,
            float pitch) {
        long generation = source.dependencyGeneration();
        SmpsAssetCatalog.ProgramEntry entry =
                factory.findRegisteredSmpsSfxAsset(key, generation);
        if (entry == null) {
            AbstractSmpsData data = Objects.requireNonNull(
                    loader.load(), "loader returned no SMPS data");
            int resolvedSfxId = requestedSfxId >= 0
                    ? requestedSfxId : data.getId();
            entry = factory.registerSmpsSfxAsset(
                    key, generation, data,
                    requireDac(key.route(), source),
                    requireConfig(key.route(), source),
                    new SmpsSfxPlaybackPolicy(
                            source.sfxPolicy().priority(resolvedSfxId),
                            source.sfxPolicy().special(resolvedSfxId),
                            source.sfxPolicy().continuous(resolvedSfxId)));
        }
        int sfxId = entry.assetId();
        SmpsSfxPlaybackPolicy policy = entry.sfxPolicy();
        int priority = policy.priority();
        int continuousId = policy.continuous() ? sfxId : 0;
        return new AddSmpsSfx(factory.resolveSmpsSfx(
                allocateVoiceId(),
                key,
                generation,
                pitchQ16(pitch),
                priority,
                continuousId,
                entry.trackCount(),
                sources.maxStereoFrames()));
    }

    @FunctionalInterface
    private interface SmpsSfxLoader {
        AbstractSmpsData load();
    }

    private static LoadedSmpsMusic loadMusic(
            SmpsAssetKey.Route route, SourceAccess source, int musicId) {
        if (source.loader() == null) {
            return null;
        }
        return route == SmpsAssetKey.Route.BASE_MUSIC
                ? source.loader().loadMusicWithReadiness(musicId)
                : LoadedSmpsMusic.immediate(source.loader().loadMusic(musicId));
    }

    private static AbstractSmpsData loadSfx(
            SourceAccess source, int sfxId) {
        return source.loader() != null
                ? source.loader().loadSfx(sfxId) : null;
    }

    private static AbstractSmpsData loadSfx(
            SourceAccess source, String name) {
        return source.loader() != null
                ? source.loader().loadSfx(name) : null;
    }

    private static DacData requireDac(
            SmpsAssetKey.Route route, SourceAccess source) {
        return Objects.requireNonNull(
                source.dac(),
                "no DAC data for " + source.gameId()
                        + " via " + route);
    }

    private static SmpsSequencerConfig requireConfig(
            SmpsAssetKey.Route route, SourceAccess source) {
        return Objects.requireNonNull(
                source.config(),
                "no sequencer config for " + source.gameId()
                        + " via " + route);
    }

    private void enqueue(AudioPresentationCommand command) {
        if (activeResolutionBatch != null) {
            activeResolutionBatch.enqueuePrivate(command);
            return;
        }
        if (synchronousDrain != null) {
            queue.submit(command, ownerThreadBoundary, synchronousDrain);
        } else {
            queue.submit(command, ownerThreadBoundary, synchronousApply);
        }
    }

    private long allocateVoiceId() {
        return nextVoiceId++;
    }

    /**
     * Advances this resolver's allocation cursor when reconstructing a
     * detached logical timeline from an existing registry snapshot.
     */
    public void reserveVoiceIdsThrough(long nextAvailableVoiceId) {
        if (nextAvailableVoiceId < 0) {
            throw new IllegalArgumentException(
                    "nextAvailableVoiceId must be non-negative");
        }
        nextVoiceId = Math.max(nextVoiceId, nextAvailableVoiceId);
    }

    private void warn(String warning) {
        if (activeResolutionBatch != null) {
            activeResolutionBatch.privateWarnings.add(warning);
        } else {
            warningConsumer.accept(warning);
        }
    }

    private static int pitchQ16(float pitch) {
        if (!Float.isFinite(pitch) || pitch <= 0.0f) {
            throw new IllegalArgumentException(
                    "pitch must be finite and positive");
        }
        long value = Math.round(pitch * 65_536.0);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("pitch is outside Q16 range");
        }
        return (int) value;
    }

    private static String requireGameId(String gameId) {
        String value = Objects.requireNonNull(gameId, "gameId");
        if (value.isBlank()) {
            throw new IllegalArgumentException(
                    "gameId must not be blank");
        }
        return value;
    }

    private static final class EmptySources implements Sources {
        @Override
        public SourceAccess sourceFor(
                SmpsAssetKey.Route route, String donorGameId) {
            String gameId = donorGameId != null
                    ? requireGameId(donorGameId) : "unconfigured";
            return new SourceAccess(
                    gameId, 0, null, null, null,
                    new SfxPolicy() {
                        @Override public int priority(int sfxId) { return 0; }
                        @Override public boolean special(int sfxId) {
                            return false;
                        }
                        @Override public boolean continuous(int sfxId) {
                            return false;
                        }
                    });
        }

        @Override
        public int maxStereoFrames() {
            return 0;
        }
    }
}
