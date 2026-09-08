package com.openggf.audio.presentation;

import com.openggf.audio.LiveCaptureAudioHandle;
import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.runtime.AudioFrameClock;
import com.openggf.audio.runtime.PcmHistoryRing;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.session.SmpsServiceOutcome;
import com.openggf.audio.session.SmpsSessionCommand;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioCommandTimeline;

/**
 * Sole outer-frame owner of final PCM cadence, history, rewind, and taps.
 */
public final class AudioPresentationProducer {
    private static final int CHANNELS = 2;
    private static final int MAX_CAPTURE_HANDLES = 32;

    /**
     * Ceiling on {@link #setForwardRate}. Each whole multiple costs another
     * full mixer pass inside the one outer frame, so this bounds the worst-case
     * cost of a runaway rate rather than expressing a musical limit.
     */
    private static final double MAX_FORWARD_RATE = 8.0;

    private final Thread ownerThread;
    private final int sampleRate;
    private final int maxStereoFrames;
    private final int crossfadeFrames;
    private final AudioVoiceRegistry registry;
    private final AudioPresentationCommandQueue commands;
    private final AudioPresentationMixer mixer;
    private final AudioFrameClock clock;
    private final PcmHistoryRing history;
    private final short[] silence;
    private final short[] reversePcm;
    private final short[] forwardPcm;
    private final short[] smpsSourcePcm;
    private final AudioPresentationFrameView frameView;
    private final CaptureHandle[] captures =
            new CaptureHandle[MAX_CAPTURE_HANDLES];
    private final Consumer<AudioPresentationCommand> commandApplier;
    private final IdentityTokenRegistry diagnosticIdentityTokens =
            new IdentityTokenRegistry();
    private final SmpsDriverSession smpsSession;
    private final AudioPresentationForwardService forwardService;
    private final Consumer<AudioCommand> forwardCommandSink;
    private final AudioPresentationCommandResolver forwardResolver;
    private final AudioCommandTimeline forwardTimeline;
    private final AudioPresentationParityProbe forwardParity;

    private AudioPresentationSink sink;
    private PcmHistoryRing.ReverseCursor reverseCursor;
    private AudioPresentationSnapshot selectedRestore;
    private AudioPresentationDependencyResolver selectedRestoreResolver;
    private PreparedPresentationRestore preparedSelectedRestore;
    private double forwardRate = 1.0;
    private int captureCount;
    private int releaseCrossfadeRemaining;
    private short lastReverseLeft;
    private short lastReverseRight;
    private boolean historyArmed;
    private boolean presenting;
    private boolean typedRequestRejected;
    /**
     * Stable applier identity handed to the forward resolver. Keeping the
     * reference here means the resolver publishes resolved commands through a
     * function this producer owns rather than through a producer reference,
     * which keeps AudioManager the only entry point into the producer type.
     */
    private final AudioPresentationCommandResolver.ResolvedCommandApplier
            forwardCommandApplier = this::applyResolvedForwardCommand;
    private boolean reverseActive;
    private boolean reverseFrameOutput;
    private boolean hasLastReverseFrame;
    private boolean closed;

    private record PreparedPresentationRestore(
            AudioVoiceRegistry.PreparedSnapshotRestore registry,
            SmpsDriverSession.PreparedRestore session) {
        private PreparedPresentationRestore {
            Objects.requireNonNull(registry, "registry");
        }
    }

    public AudioPresentationProducer(
            int sampleRate,
            int frameRate,
            int historyFrames,
            int crossfadeFrames,
            AudioVoiceRegistry registry,
            AudioPresentationCommandQueue commands,
            AudioPresentationMixer mixer,
            AudioPresentationSink sink) {
        this(sampleRate, frameRate, historyFrames, crossfadeFrames, registry,
                commands, mixer, sink, null, null, null, null,
                null, null, null);
    }

    public AudioPresentationProducer(
            int sampleRate,
            int frameRate,
            int historyFrames,
            int crossfadeFrames,
            AudioVoiceRegistry registry,
            AudioPresentationCommandQueue commands,
            AudioPresentationMixer mixer,
            AudioPresentationSink sink,
            SmpsDriverSession smpsSession) {
        this(sampleRate, frameRate, historyFrames, crossfadeFrames, registry,
                commands, mixer, sink, smpsSession, null, null, null,
                null, null, null);
    }

    public AudioPresentationProducer(
            int sampleRate,
            int frameRate,
            int historyFrames,
            int crossfadeFrames,
            AudioVoiceRegistry registry,
            AudioPresentationCommandQueue commands,
            AudioPresentationMixer mixer,
            AudioPresentationSink sink,
            SmpsDriverSession smpsSession,
            AudioPresentationForwardService forwardService,
            Consumer<AudioCommand> forwardCommandSink) {
        this(sampleRate, frameRate, historyFrames, crossfadeFrames, registry,
                commands, mixer, sink, smpsSession, null, forwardService,
                forwardCommandSink, null, null, null);
    }

    public AudioPresentationProducer(
            int sampleRate, int frameRate, int historyFrames,
            int crossfadeFrames, AudioVoiceRegistry registry,
            AudioPresentationCommandQueue commands,
            AudioPresentationMixer mixer, AudioPresentationSink sink,
            SmpsDriverSession smpsSession,
            AudioPresentationForwardService forwardService,
            AudioPresentationCommandResolver forwardResolver,
            AudioCommandTimeline forwardTimeline,
            AudioPresentationParityProbe forwardParity) {
        this(sampleRate, frameRate, historyFrames, crossfadeFrames, registry,
                commands, mixer, sink, smpsSession, null, forwardService,
                null, forwardResolver, forwardTimeline, forwardParity);
    }

    AudioPresentationProducer(
            int sampleRate,
            int frameRate,
            int historyFrames,
            int crossfadeFrames,
            AudioVoiceRegistry registry,
            AudioPresentationCommandQueue commands,
            AudioPresentationMixer mixer,
            AudioPresentationSink sink,
            Consumer<AudioPresentationCommand> commandApplier) {
        this(sampleRate, frameRate, historyFrames, crossfadeFrames, registry,
                commands, mixer, sink, null, commandApplier, null, null,
                null, null, null);
    }

    private AudioPresentationProducer(
            int sampleRate,
            int frameRate,
            int historyFrames,
            int crossfadeFrames,
            AudioVoiceRegistry registry,
            AudioPresentationCommandQueue commands,
            AudioPresentationMixer mixer,
            AudioPresentationSink sink,
            SmpsDriverSession smpsSession,
            Consumer<AudioPresentationCommand> commandApplier,
            AudioPresentationForwardService forwardService,
            Consumer<AudioCommand> forwardCommandSink,
            AudioPresentationCommandResolver forwardResolver,
            AudioCommandTimeline forwardTimeline,
            AudioPresentationParityProbe forwardParity) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        if (frameRate <= 0) {
            throw new IllegalArgumentException("frameRate must be positive");
        }
        if (historyFrames <= 0) {
            throw new IllegalArgumentException("historyFrames must be positive");
        }
        if (crossfadeFrames < 0) {
            throw new IllegalArgumentException(
                    "crossfadeFrames must be non-negative");
        }
        ownerThread = Thread.currentThread();
        this.sampleRate = sampleRate;
        maxStereoFrames = (sampleRate + frameRate - 1) / frameRate;
        this.crossfadeFrames = crossfadeFrames;
        this.registry = Objects.requireNonNull(registry, "registry");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.mixer = Objects.requireNonNull(mixer, "mixer");
        if (mixer.maxStereoFrames() < maxStereoFrames) {
            throw new IllegalArgumentException(
                    "mixer capacity is smaller than the producer packet");
        }
        this.sink = requireCompatibleSink(sink);
        clock = new AudioFrameClock(sampleRate, frameRate);
        history = new PcmHistoryRing(historyFrames);
        silence = new short[maxStereoFrames * CHANNELS];
        reversePcm = new short[maxStereoFrames * CHANNELS];
        forwardPcm = new short[maxStereoFrames * CHANNELS];
        smpsSourcePcm = new short[Math.multiplyExact(
                Math.multiplyExact(maxStereoFrames, (int) MAX_FORWARD_RATE),
                CHANNELS)];
        frameView = new AudioPresentationFrameView(silence);
        this.smpsSession = smpsSession;
        this.forwardService = forwardService;
        this.forwardCommandSink = forwardService == null ? null
                : forwardResolver == null
                ? Objects.requireNonNull(forwardCommandSink,
                "forwardCommandSink") : null;
        this.forwardResolver = forwardResolver;
        this.forwardTimeline = forwardTimeline;
        this.forwardParity = forwardParity;
        if (forwardResolver != null) {
            Objects.requireNonNull(forwardService, "forwardService");
            Objects.requireNonNull(forwardTimeline, "forwardTimeline");
            Objects.requireNonNull(forwardParity, "forwardParity");
            forwardResolver.bindForwardExecutor(forwardCommandApplier);
        }
        if (smpsSession != null && !smpsSession.installed()) {
            smpsSession.install();
        }
        this.commandApplier =
                commandApplier != null ? commandApplier : registry::apply;
    }

    public enum PresentationResult {
        COMMITTED,
        REQUEST_REJECTED
    }

    public PresentationResult present(long commandFrame, PresentationMode mode) {
        assertOwnerThread();
        assertOpen();
        Objects.requireNonNull(mode, "mode");
        if (presenting) {
            throw new IllegalStateException(
                    "audio presentation cannot be re-entered");
        }
        presenting = true;
        try {
            AudioFrameClock.Snapshot clockBefore =
                    mode == PresentationMode.FORWARD
                            && forwardResolver != null
                    ? clock.captureSnapshot() : null;
            typedRequestRejected = false;
            int stereoFrames = clock.samplesForNextFrame();
            short[] pcm;
            if (mode == PresentationMode.FORWARD) {
                pcm = smpsSession != null
                        ? presentSessionForward(stereoFrames)
                        : presentLegacyForward(stereoFrames);
                if (typedRequestRejected) {
                    clock.restoreSnapshot(clockBefore);
                    return PresentationResult.REQUEST_REJECTED;
                }
                applyReleaseCrossfade(pcm, stereoFrames);
                if (historyArmed) {
                    history.write(pcm, stereoFrames);
                }
            } else if (mode == PresentationMode.SILENT) {
                if (smpsSession != null) {
                    applyPendingSessionCommandsTransactionally();
                } else {
                    commands.applyPending(commandApplier);
                }
                if (smpsSession != null
                        && smpsSession.segaPcmTransportActive()) {
                    // The Z80's PCM loop is not silence-aware: it runs to its
                    // end or its stop request either way (Sound/Z80 Sound
                    // Driver.asm:4372-4424). Advancing it here keeps a muted
                    // frame from parking the driver inside the loop; the
                    // rendered bytes are discarded with the frame.
                    smpsSession.renderFrames(
                            smpsSourcePcm, 0, stereoFrames);
                }
                Arrays.fill(silence, 0, stereoFrames * CHANNELS, (short) 0);
                pcm = silence;
            } else {
                Arrays.fill(reversePcm, 0, stereoFrames * CHANNELS, (short) 0);
                if (reverseCursor != null) {
                    reverseCursor.readPrevious(reversePcm, stereoFrames);
                }
                if (reverseActive && stereoFrames > 0) {
                    rememberLastReverseFrame(reversePcm, stereoFrames);
                }
                pcm = reversePcm;
            }

            frameView.update(pcm, stereoFrames, commandFrame, mode);
            sink.accept(frameView);
            for (int index = 0; index < captureCount; index++) {
                CaptureHandle capture = captures[index];
                if (capture != null) {
                    capture.onPresentationFrame(frameView);
                }
            }
            return PresentationResult.COMMITTED;
        } finally {
            presenting = false;
        }
    }

    public LiveCaptureAudioHandle attachCapture(int frameRate) {
        return attachCapture(frameRate, null);
    }

    /**
     * @param phase clock phase to resume from, or null to start from this
     *        producer's current phase. Used when a recording outlives the
     *        producer it was attached to: a rebuilt producer starts its clock
     *        at zero, and reseeding the lease from zero would jump the recorded
     *        audio relative to the video.
     */
    public LiveCaptureAudioHandle attachCapture(
            int frameRate, AudioFrameClock.Snapshot phase) {
        assertOwnerBoundary();
        if (frameRate <= 0) {
            throw new IllegalArgumentException("frameRate must be positive");
        }
        if (captureCount == captures.length) {
            throw new IllegalStateException(
                    "audio presentation capture capacity exhausted");
        }
        CaptureHandle capture = new CaptureHandle(frameRate,
                phase != null ? phase : clock.captureSnapshot());
        captures[captureCount++] = capture;
        return capture;
    }

    public void beginReverse(double rate) {
        assertOwnerBoundary();
        discardPreparedRestore(preparedSelectedRestore);
        preparedSelectedRestore = null;
        selectedRestore = null;
        selectedRestoreResolver = null;
        cancelReleaseCrossfade();
        reverseCursor = history.createReverseCursor();
        reverseCursor.setRate(rate);
        reverseActive = true;
        sink.onReverseBoundary();
    }

    public void setReverseRate(double rate) {
        assertOwnerBoundary();
        if (reverseCursor != null) {
            reverseCursor.setRate(rate);
        }
    }

    /**
     * Forward playback rate, 1.0 being real time. A higher rate renders that
     * many frames of source audio into the one outer-frame packet, the mirror
     * of {@code PcmHistoryRing.ReverseCursor}'s rate in reverse, so a caller
     * running the simulation faster than real time hears it speed up and pitch
     * up together instead of drifting out of sync with the picture. NaN and
     * non-positive rates fall back to real time.
     */
    public void setForwardRate(double rate) {
        assertOwnerBoundary();
        forwardRate = Double.isNaN(rate) || rate <= 0.0
                ? 1.0
                : Math.min(MAX_FORWARD_RATE, rate);
    }

    public void endReverse() {
        endReverse(true);
    }

    /**
     * Commits the selected reverse target. Fresh level-boundary targets may
     * retain their newly initialized SFX because they were created after the
     * rewindable segment ended, rather than being transient voices restored
     * from the selected pre-boundary frame.
     */
    public void endReverse(boolean stopTransientVoices) {
        assertOwnerBoundary();
        if (!reverseActive) {
            return;
        }
        if (selectedRestore != null) {
            if (preparedSelectedRestore == null) {
                preparedSelectedRestore = preparePresentationRestore(
                        selectedRestore, selectedRestoreResolver);
            }
            commitPreparedRestore(preparedSelectedRestore);
        }
        if (stopTransientVoices) {
            stopTransientVoicesAtomically();
        }
        history.commitReverseCursor(reverseCursor);
        reverseCursor = null;
        reverseActive = false;
        selectedRestore = null;
        selectedRestoreResolver = null;
        preparedSelectedRestore = null;
        if (hasLastReverseFrame && reverseFrameOutput
                && crossfadeFrames > 0) {
            releaseCrossfadeRemaining = crossfadeFrames;
        }
        reverseFrameOutput = false;
        sink.onReverseBoundary();
    }

    public void clearHistory() {
        assertOwnerBoundary();
        history.clear();
        selectedRestore = null;
        selectedRestoreResolver = null;
        discardPreparedRestore(preparedSelectedRestore);
        preparedSelectedRestore = null;
        cancelReleaseCrossfade();
    }

    public void setHistoryArmed(boolean armed) {
        assertOwnerBoundary();
        historyArmed = armed;
    }

    public AudioPresentationSnapshot snapshot() {
        assertOwnerBoundary();
        return registry.snapshot();
    }

    public boolean areSfxRequestsBlocked() {
        assertOwnerBoundary();
        return registry.areSfxRequestsBlocked();
    }

    /**
     * Read-only identity/state fingerprint used by transactional-release
     * diagnostics. Mutable runtime objects are represented by opaque,
     * collision-free reference-identity tokens so taking the fingerprint
     * cannot perturb presentation state or expose those objects to callers.
     */
    public TransactionFingerprint transactionFingerprint() {
        assertOwnerBoundary();
        IdentityFingerprint[] voiceIdentities =
                new IdentityFingerprint[registry.orderedVoiceCount()];
        for (int index = 0; index < registry.orderedVoiceCount(); index++) {
            voiceIdentities[index] = identityFingerprint(
                    registry.orderedVoiceAt(index));
        }
        return new TransactionFingerprint(
                clock.captureSnapshot(),
                history.diagnosticSnapshot(),
                identityFingerprint(smpsSession),
                List.of(voiceIdentities),
                reverseCursor != null ? reverseCursor.state() : null,
                identityFingerprint(selectedRestore),
                identityFingerprint(selectedRestoreResolver),
                identityFingerprint(preparedSelectedRestore),
                releaseCrossfadeRemaining,
                lastReverseLeft,
                lastReverseRight,
                historyArmed,
                reverseActive,
                reverseFrameOutput,
                hasLastReverseFrame,
                captureCount);
    }

    /**
     * Immutable diagnostic fingerprint for atomic rewind-release verification.
     * Runtime voices, resolvers, and prepared tokens are represented by
     * opaque reference-identity fingerprints; no live mutable object is
     * directly accessible through this snapshot.
     */
    public record TransactionFingerprint(
            AudioFrameClock.Snapshot clock,
            PcmHistoryRing.DiagnosticSnapshot history,
            IdentityFingerprint smpsSessionIdentity,
            List<IdentityFingerprint> voiceIdentities,
            PcmHistoryRing.CursorState reverseCursor,
            IdentityFingerprint selectedRestoreIdentity,
            IdentityFingerprint selectedRestoreResolverIdentity,
            IdentityFingerprint preparedSelectedRestoreIdentity,
            int releaseCrossfadeRemaining,
            short lastReverseLeft,
            short lastReverseRight,
            boolean historyArmed,
            boolean reverseActive,
            boolean reverseFrameOutput,
            boolean hasLastReverseFrame,
            int captureCount) {
        public TransactionFingerprint {
            voiceIdentities = List.copyOf(voiceIdentities);
        }
    }

    /**
     * Opaque, collision-free reference identity used only in diagnostics.
     */
    public record IdentityFingerprint(long token) {
    }

    private IdentityFingerprint identityFingerprint(Object reference) {
        return new IdentityFingerprint(
                diagnosticIdentityTokens.tokenFor(reference));
    }

    /**
     * Assigns stable, collision-free diagnostic ids without keeping runtime
     * voices, snapshots, or resolvers alive.
     */
    private static final class IdentityTokenRegistry {
        private final List<TokenEntry> entries = new ArrayList<>();
        private long nextToken = 1;

        private long tokenFor(Object reference) {
            if (reference == null) {
                return 0;
            }
            for (int index = entries.size() - 1; index >= 0; index--) {
                TokenEntry entry = entries.get(index);
                Object existing = entry.reference().get();
                if (existing == null) {
                    entries.remove(index);
                } else if (existing == reference) {
                    return entry.token();
                }
            }
            long token = nextToken++;
            if (token == 0) {
                throw new IllegalStateException(
                        "diagnostic identity token space exhausted");
            }
            entries.add(new TokenEntry(new WeakReference<>(reference), token));
            return token;
        }

        private record TokenEntry(
                WeakReference<Object> reference, long token) {
        }
    }

    public void restore(
            AudioPresentationSnapshot snapshot,
            AudioPresentationDependencyResolver resolver,
            boolean preservePresentation) {
        assertOwnerBoundary();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(resolver, "resolver");
        if (preservePresentation && reverseActive) {
            discardPreparedRestore(preparedSelectedRestore);
            preparedSelectedRestore = null;
            selectedRestore = snapshot;
            selectedRestoreResolver = resolver;
            return;
        }
        commitPreparedRestore(preparePresentationRestore(snapshot, resolver));
        if (!preservePresentation) {
            history.clear();
            reverseCursor = null;
            reverseActive = false;
            selectedRestore = null;
            selectedRestoreResolver = null;
            discardPreparedRestore(preparedSelectedRestore);
            preparedSelectedRestore = null;
            cancelReleaseCrossfade();
        }
    }

    public void prepareSelectedRestore() {
        assertOwnerBoundary();
        if (selectedRestore == null || preparedSelectedRestore != null) {
            return;
        }
        preparedSelectedRestore = preparePresentationRestore(
                selectedRestore, selectedRestoreResolver);
    }

    /**
     * Prepares and publishes a reverse-release selection atomically. Failed
     * dependency resolution leaves the prior selection, prepared token,
     * registry, history, and reverse cursor untouched.
     */
    public void prepareRestoreSelection(
            AudioPresentationSnapshot snapshot,
            AudioPresentationDependencyResolver resolver) {
        assertOwnerBoundary();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(resolver, "resolver");
        PreparedPresentationRestore prepared =
                preparePresentationRestore(snapshot, resolver);
        discardPreparedRestore(preparedSelectedRestore);
        selectedRestore = snapshot;
        selectedRestoreResolver = resolver;
        preparedSelectedRestore = prepared;
    }

    /**
     * Discards only the reverse-release selection. This is used when a
     * manager-owned backend commit fails after this attempt prepared the
     * producer, restoring the exact pre-attempt transaction fingerprint.
     */
    public void discardPreparedRestoreSelection() {
        assertOwnerBoundary();
        discardPreparedRestore(preparedSelectedRestore);
        selectedRestore = null;
        selectedRestoreResolver = null;
        preparedSelectedRestore = null;
    }

    private PreparedPresentationRestore preparePresentationRestore(
            AudioPresentationSnapshot snapshot,
            AudioPresentationDependencyResolver resolver) {
        SmpsDriverSession.PreparedRestore preparedSession = null;
        if (smpsSession != null) {
            if (snapshot.smpsSession() == null
                    || snapshot.smpsLogical() == null) {
                throw new IllegalArgumentException(
                        "session presentation restore requires one SMPS "
                                + "session/logical snapshot pair");
            }
            preparedSession = smpsSession.prepareRestore(
                    snapshot.smpsSession(), snapshot.smpsLogical(),
                    new SmpsDriverSession.DacDependencyResolver() {
                        @Override
                        public com.openggf.audio.smps.DacData resolve(
                                com.openggf.audio.rewind.SmpsSourceDescriptor source) {
                            return resolver.resolveDac(source);
                        }

                        @Override
                        public com.openggf.audio.smps.SmpsLoadReadiness
                                resolveReadiness(
                                com.openggf.audio.rewind.SmpsSourceDescriptor source) {
                            return resolver.resolveSmpsLoadReadiness(source);
                        }
                    });
        }
        AudioVoiceRegistry.PreparedSnapshotRestore preparedRegistry =
                registry.prepareSnapshotRestore(snapshot, resolver);
        return new PreparedPresentationRestore(
                preparedRegistry, preparedSession);
    }

    private void commitPreparedRestore(
            PreparedPresentationRestore restore) {
        Objects.requireNonNull(restore, "restore");
        if (smpsSession == null) {
            registry.commitPreparedRestore(restore.registry());
            return;
        }
        if (restore.session() == null) {
            throw new IllegalArgumentException(
                    "prepared restore has no session memento");
        }
        SmpsDriverSession.LiveMutationToken sessionMutation =
                smpsSession.captureLiveMutation();
        AudioVoiceRegistry.LiveMutationToken registryMutation = null;
        boolean registryCommitted = false;
        boolean sessionCommitted = false;
        try {
            registryMutation = registry.captureLiveMutation();
            smpsSession.commitRestore(restore.session());
            registry.commitPreparedRestoreState(restore.registry());
            registry.reapplySessionControls();
            registry.prepareLiveMutationCommit(registryMutation);
            smpsSession.prepareLiveMutationCommit(sessionMutation);
            registry.commitLiveMutation(registryMutation);
            registryCommitted = true;
            smpsSession.commitLiveMutation(sessionMutation);
            sessionCommitted = true;
        } catch (RuntimeException failure) {
            if (registryMutation != null && !registryCommitted) {
                try {
                    registry.rollbackLiveMutation(registryMutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (!sessionCommitted) {
                try {
                    smpsSession.rollbackLiveMutation(sessionMutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
        try {
            registry.publishPreparedRestoreDiagnostics(restore.registry());
        } catch (RuntimeException ignored) {
            // Observer failures cannot roll back or reject committed audio.
        }
        publishSessionDiagnosticsQuarantined(registryMutation);
    }

    private void discardPreparedRestore(
            PreparedPresentationRestore restore) {
        if (restore != null) {
            registry.discardPreparedRestore(restore.registry());
        }
    }

    private void stopTransientVoicesAtomically() {
        if (smpsSession == null) {
            registry.stopTransientVoices();
            return;
        }
        SmpsDriverSession.LiveMutationToken sessionMutation =
                smpsSession.captureLiveMutation();
        AudioVoiceRegistry.LiveMutationToken registryMutation = null;
        boolean registryCommitted = false;
        boolean sessionCommitted = false;
        try {
            registryMutation = registry.captureLiveMutation();
            smpsSession.applyCommand(new SmpsSessionCommand.StopAllSfx());
            registry.stopTransientVoices();
            registry.prepareLiveMutationCommit(registryMutation);
            smpsSession.prepareLiveMutationCommit(sessionMutation);
            registry.commitLiveMutation(registryMutation);
            registryCommitted = true;
            smpsSession.commitLiveMutation(sessionMutation);
            sessionCommitted = true;
            publishSessionDiagnosticsQuarantined(registryMutation);
        } catch (RuntimeException failure) {
            if (registryMutation != null && !registryCommitted) {
                try {
                    registry.rollbackLiveMutation(registryMutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (!sessionCommitted) {
                try {
                    smpsSession.rollbackLiveMutation(sessionMutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    /** Applies rewind resynchronization as one session/registry mutation. */
    public void stopTransientVoicesThenApplyPendingCommandsAtOwnerBoundary() {
        assertOwnerBoundary();
        if (smpsSession == null) {
            registry.stopTransientVoices();
            commands.applyPending(commandApplier);
            return;
        }
        SmpsDriverSession.LiveMutationToken sessionMutation =
                smpsSession.captureLiveMutation();
        AudioVoiceRegistry.LiveMutationToken registryMutation = null;
        AudioPresentationCommandQueue.PendingBatch commandBatch = null;
        boolean registryCommitted = false;
        boolean sessionCommitted = false;
        boolean commandsCommitted = false;
        try {
            registryMutation = registry.captureLiveMutation();
            commandBatch = commands.capturePendingBatch();
            smpsSession.applyCommand(new SmpsSessionCommand.StopAllSfx());
            registry.stopTransientVoices();
            commands.applyPendingBatch(commandBatch,
                    this::applyResolvedSessionCommand);
            prepareSessionCommandCommit(
                    commandBatch, registryMutation, sessionMutation);
            registry.commitLiveMutation(registryMutation);
            registryCommitted = true;
            smpsSession.commitLiveMutation(sessionMutation);
            sessionCommitted = true;
            commands.commitPendingBatch(commandBatch);
            commandsCommitted = true;
            publishSessionDiagnosticsQuarantined(registryMutation);
        } catch (RuntimeException failure) {
            if (registryMutation != null && !registryCommitted) {
                try {
                    registry.rollbackLiveMutation(registryMutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (!sessionCommitted) {
                try {
                    smpsSession.rollbackLiveMutation(sessionMutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (commandBatch != null && !commandsCommitted) {
                try {
                    commands.rollbackPendingBatch(commandBatch);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    public void stopTransientVoicesAtOwnerBoundary() {
        assertOwnerBoundary();
        stopTransientVoicesAtomically();
    }

    /**
     * Fully drains commands queued while reverse output owned the frame
     * boundary. Session-backed presentation retains the complete captured
     * prefix until the registry/session composite commit, so any failure
     * leaves the original order exactly retryable. The standalone Task-6
     * compatibility path retains its legacy per-command consumption. Callers
     * must capture boundary state only after this method returns normally.
     */
    public void applyPendingCommandsAtOwnerBoundary() {
        assertOwnerBoundary();
        if (smpsSession != null) {
            applyPendingSessionCommandsTransactionally();
        } else {
            commands.applyPending(commandApplier);
        }
    }

    public void replaceSink(AudioPresentationSink sink) {
        assertOwnerBoundary();
        AudioPresentationSink replacement = requireCompatibleSink(sink);
        if (replacement == this.sink) {
            return;
        }
        AudioPresentationSink previous = this.sink;
        this.sink = replacement;
        previous.close();
    }

    /**
     * Whether this producer has released its capture leases. {@link #close()}
     * marks every lease closed and detaches it before any teardown step that
     * can throw, so an owner may use this to decide whether a failed
     * {@code close()} still detached the leases it held.
     */
    public boolean isClosed() {
        return closed;
    }

    public void close() {
        assertOwnerThread();
        if (closed) {
            return;
        }
        closed = true;
        for (int index = 0; index < captureCount; index++) {
            CaptureHandle capture = captures[index];
            if (capture != null) {
                capture.closed = true;
                captures[index] = null;
            }
        }
        captureCount = 0;
        PreparedPresentationRestore prepared =
                preparedSelectedRestore;
        preparedSelectedRestore = null;
        selectedRestore = null;
        selectedRestoreResolver = null;
        try {
            discardPreparedRestore(prepared);
        } finally {
            try {
                registry.clear();
            } finally {
                try {
                    history.clear();
                } finally {
                    try {
                        sink.close();
                    } finally {
                        if (smpsSession != null) {
                            smpsSession.close();
                        }
                    }
                }
            }
        }
    }

    private short[] presentLegacyForward(int stereoFrames) {
        commands.applyPending(commandApplier);
        registry.beginRendering();
        try {
            registry.serviceOuterFrame();
            return forwardRate > 1.0
                    ? mixForwardResampled(stereoFrames)
                    : mixer.mix(registry, stereoFrames);
        } finally {
            registry.endRendering();
        }
    }

    private short[] presentSessionForward(int stereoFrames) {
        SmpsDriverSession.LiveMutationToken sessionMutation = null;
        AudioVoiceRegistry.LiveMutationToken registryMutation = null;
        AudioPresentationCommandQueue.PendingBatch commandBatch = null;
        boolean registryCommitted = false;
        boolean sessionCommitted = false;
        boolean commandsCommitted = false;
        AudioPresentationForwardService.ForwardBoundary forwardBoundary = null;
        boolean forwardCommitted = false;
        AudioPresentationCommandResolver.ResolutionBatch requestBatch = null;
        AudioPresentationCommandResolver.AppliedOutcome requestOutcome = null;
        AudioCommandTimeline.PreparedAppend timelineAppend = null;
        Runnable parityCommit = null;
        AudioPresentationForwardService.CommittedReceipt requestReceipt = null;
        boolean loadBlocked = smpsSession.blocksForwardRequestConsumption();
        try {
            if (!loadBlocked && forwardService != null) {
                forwardBoundary = forwardService.beginForwardBoundary();
                if (forwardResolver == null) {
                    forwardBoundary.service(forwardCommandSink);
                } else {
                    AudioPresentationForwardService.ForwardBoundary
                            activeBoundary = forwardBoundary;
                    AudioPresentationCommandResolver.ResolutionBatch[] holder =
                            new AudioPresentationCommandResolver.ResolutionBatch[1];
                    AudioPresentationCommandResolver.ResolutionResult[] result =
                            new AudioPresentationCommandResolver.ResolutionResult[1];
                    forwardBoundary.service(command -> {
                        if (holder[0] != null) {
                            throw new IllegalStateException(
                                    "one request boundary produced multiple consequences");
                        }
                        AudioPresentationCommandResolver.ResolutionBatch candidate =
                                forwardResolver.beginResolutionBatch();
                        holder[0] = candidate;
                        try {
                            result[0] = candidate.resolve(command);
                            if (result[0] instanceof AudioPresentationCommandResolver
                                    .CompleteSuccess) {
                                activeBoundary.reserveOutcome(
                                        candidate.reservation());
                            }
                        } catch (RuntimeException failure) {
                            try {
                                candidate.rollback();
                            } catch (RuntimeException rollbackFailure) {
                                failure.addSuppressed(rollbackFailure);
                            }
                            holder[0] = null;
                            result[0] = null;
                            throw failure;
                        }
                    });
                    requestBatch = holder[0];
                    if (result[0] instanceof AudioPresentationCommandResolver
                            .Failure) {
                        requestBatch.rollback();
                        forwardBoundary.rollback();
                        Arrays.fill(silence, 0,
                                stereoFrames * CHANNELS, (short) 0);
                        typedRequestRejected = true;
                        return silence;
                    }
                }
            }
            sessionMutation = smpsSession.captureLiveMutation();
            registryMutation = registry.captureLiveMutation();
            if (!loadBlocked) {
                commandBatch = commands.capturePendingBatch();
                commands.applyPendingBatch(commandBatch,
                        this::applyResolvedSessionCommand);
            }
            if (requestBatch != null) {
                requestOutcome = requestBatch.apply();
                forwardBoundary.applyOutcome(requestOutcome);
            }
            registry.beginRendering();
            SmpsServiceOutcome outcome = smpsSession.serviceForward();
            if (outcome == SmpsServiceOutcome.GLOBAL_STOP_CONSUMED) {
                registry.clearForGlobalStopWithoutWrites();
            }
            short[] pcm = forwardRate > 1.0
                    ? mixSessionForwardResampled(stereoFrames)
                    : mixSessionForward(stereoFrames);
            registry.endRendering();
            if (commandBatch != null) {
                prepareSessionCommandCommit(
                        commandBatch, registryMutation, sessionMutation);
            } else {
                registry.prepareLiveMutationCommit(registryMutation);
                smpsSession.prepareLiveMutationCommit(sessionMutation);
            }
            if (requestBatch != null) {
                requestBatch.prepareCommit();
                forwardBoundary.prepareCommit();
                List<AudioCommand> durableRequests =
                        List.of(requestOutcome.request());
                timelineAppend = forwardTimeline.prepareAppend(durableRequests);
                parityCommit = forwardParity.prepareCommandSubmissions(
                        durableRequests.size());
            } else if (forwardBoundary != null && forwardResolver != null) {
                forwardBoundary.prepareCommit();
            }
            registry.commitLiveMutation(registryMutation);
            registryCommitted = true;
            smpsSession.commitLiveMutation(sessionMutation);
            sessionCommitted = true;
            if (commandBatch != null) {
                commands.commitPendingBatch(commandBatch);
                commandsCommitted = true;
            }
            if (requestBatch != null) {
                requestBatch.commit();
                timelineAppend.commit();
                parityCommit.run();
            }
            if (forwardBoundary != null) {
                requestReceipt = forwardBoundary.commit();
                forwardCommitted = true;
            }
            if (requestBatch != null) {
                requestBatch.publishDiagnostics(requestReceipt);
            }
            publishSessionDiagnosticsQuarantined(registryMutation);
            if (requestReceipt != null) {
                forwardBoundary.publishDiagnostics(requestReceipt);
            }
            return pcm;
        } catch (RuntimeException failure) {
            if (registryMutation != null && !registryCommitted) {
                try {
                    registry.rollbackLiveMutation(registryMutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (sessionMutation != null && !sessionCommitted) {
                try {
                    smpsSession.rollbackLiveMutation(sessionMutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (commandBatch != null && !commandsCommitted) {
                try {
                    commands.rollbackPendingBatch(commandBatch);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (forwardBoundary != null && !forwardCommitted) {
                try {
                    forwardBoundary.rollback();
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (requestBatch != null) {
                try {
                    requestBatch.rollback();
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    void applyResolvedForwardCommand(
            AudioCommand request,
            AudioPresentationCommand resolved) {
        if (smpsSession == null) {
            commandApplier.accept(resolved);
        } else {
            applyResolvedSessionCommand(resolved);
        }
    }

    private void applyPendingSessionCommandsTransactionally() {
        SmpsDriverSession.LiveMutationToken sessionMutation =
                smpsSession.captureLiveMutation();
        AudioVoiceRegistry.LiveMutationToken registryMutation = null;
        AudioPresentationCommandQueue.PendingBatch commandBatch = null;
        boolean registryCommitted = false;
        boolean sessionCommitted = false;
        boolean commandsCommitted = false;
        try {
            registryMutation = registry.captureLiveMutation();
            commandBatch = commands.capturePendingBatch();
            commands.applyPendingBatch(commandBatch,
                    this::applyResolvedSessionCommand);
            prepareSessionCommandCommit(
                    commandBatch, registryMutation, sessionMutation);
            registry.commitLiveMutation(registryMutation);
            registryCommitted = true;
            smpsSession.commitLiveMutation(sessionMutation);
            sessionCommitted = true;
            commands.commitPendingBatch(commandBatch);
            commandsCommitted = true;
            publishSessionDiagnosticsQuarantined(registryMutation);
        } catch (RuntimeException failure) {
            if (registryMutation != null && !registryCommitted) {
                try {
                    registry.rollbackLiveMutation(registryMutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (!sessionCommitted) {
                try {
                    smpsSession.rollbackLiveMutation(sessionMutation);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            if (commandBatch != null && !commandsCommitted) {
                try {
                    commands.rollbackPendingBatch(commandBatch);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private void prepareSessionCommandCommit(
            AudioPresentationCommandQueue.PendingBatch commandBatch,
            AudioVoiceRegistry.LiveMutationToken registryMutation,
            SmpsDriverSession.LiveMutationToken sessionMutation) {
        commands.preparePendingBatchCommit(commandBatch);
        registry.prepareLiveMutationCommit(registryMutation);
        smpsSession.prepareLiveMutationCommit(sessionMutation);
    }

    private void publishSessionDiagnosticsQuarantined(
            AudioVoiceRegistry.LiveMutationToken registryMutation) {
        try {
            registry.publishCommittedDiagnostics(registryMutation);
        } catch (RuntimeException ignored) {
            // Observer failures cannot reject or replay committed audio.
        }
        smpsSession.publishCommittedDiagnostics();
    }

    private void applyResolvedSessionCommand(
            AudioPresentationCommand command) {
        AudioPresentationSessionCommandApplier.apply(
                smpsSession, registry, command);
    }

    private short[] mixSessionForward(int stereoFrames) {
        smpsSession.renderFrames(
                smpsSourcePcm, 0, stereoFrames);
        return mixer.mixPcmVoices(
                registry, stereoFrames, smpsSourcePcm, 0);
    }

    private short[] mixSessionForwardResampled(int stereoFrames) {
        if (stereoFrames <= 0) {
            return forwardPcm;
        }
        int sourceFramesNeeded = Math.toIntExact(
                (long) Math.floor(
                        (stereoFrames - 1) * forwardRate + 0.5) + 1);
        smpsSession.renderFrames(
                smpsSourcePcm, 0, sourceFramesNeeded);
        int consumedSourceFrames = 0;
        int outputFrame = 0;
        while (consumedSourceFrames < sourceFramesNeeded) {
            int chunkFrames = Math.min(maxStereoFrames,
                    sourceFramesNeeded - consumedSourceFrames);
            short[] chunk = mixer.mixPcmVoices(
                    registry, chunkFrames, smpsSourcePcm,
                    consumedSourceFrames * CHANNELS);
            while (outputFrame < stereoFrames) {
                long picked = (long) Math.floor(
                        outputFrame * forwardRate + 0.5);
                if (picked >= consumedSourceFrames + chunkFrames) {
                    break;
                }
                int sourceIndex =
                        (int) (picked - consumedSourceFrames) * CHANNELS;
                int targetIndex = outputFrame * CHANNELS;
                forwardPcm[targetIndex] = chunk[sourceIndex];
                forwardPcm[targetIndex + 1] = chunk[sourceIndex + 1];
                outputFrame++;
            }
            consumedSourceFrames += chunkFrames;
        }
        return forwardPcm;
    }

    private AudioPresentationSink requireCompatibleSink(
            AudioPresentationSink sink) {
        Objects.requireNonNull(sink, "sink");
        if (sink.sampleRate() != sampleRate) {
            throw new IllegalArgumentException(
                    "sink sample rate does not match producer sample rate");
        }
        return sink;
    }

    /**
     * Renders {@code forwardRate x} an outer frame of source audio and
     * decimates it down to the one packet the frame is allowed to emit. The
     * source is pulled in chunks no larger than the mixer's declared capacity,
     * so no buffer has to be sized for the fastest rate — the mixer hands back
     * its own internal array, hence the copy out of each chunk before the next
     * pass overwrites it.
     */
    private short[] mixForwardResampled(int stereoFrames) {
        if (stereoFrames <= 0) {
            return forwardPcm;
        }
        long sourceFramesNeeded =
                (long) Math.floor((stereoFrames - 1) * forwardRate + 0.5) + 1;
        long consumedSourceFrames = 0;
        int outputFrame = 0;
        while (consumedSourceFrames < sourceFramesNeeded) {
            int chunkFrames = (int) Math.min(
                    maxStereoFrames, sourceFramesNeeded - consumedSourceFrames);
            short[] chunk = mixer.mix(registry, chunkFrames);
            while (outputFrame < stereoFrames) {
                long picked = (long) Math.floor(outputFrame * forwardRate + 0.5);
                if (picked >= consumedSourceFrames + chunkFrames) {
                    break;
                }
                int sourceIndex = (int) (picked - consumedSourceFrames) * CHANNELS;
                int targetIndex = outputFrame * CHANNELS;
                forwardPcm[targetIndex] = chunk[sourceIndex];
                forwardPcm[targetIndex + 1] = chunk[sourceIndex + 1];
                outputFrame++;
            }
            consumedSourceFrames += chunkFrames;
        }
        return forwardPcm;
    }

    private void rememberLastReverseFrame(short[] pcm, int readFrames) {
        if (readFrames <= 0) {
            return;
        }
        int sample = (readFrames - 1) * CHANNELS;
        lastReverseLeft = pcm[sample];
        lastReverseRight = pcm[sample + 1];
        hasLastReverseFrame = true;
        reverseFrameOutput = true;
    }

    private void applyReleaseCrossfade(short[] pcm, int stereoFrames) {
        if (releaseCrossfadeRemaining <= 0) {
            return;
        }
        for (int frame = 0;
                frame < stereoFrames && releaseCrossfadeRemaining > 0;
                frame++) {
            int elapsed =
                    crossfadeFrames - releaseCrossfadeRemaining + 1;
            int sample = frame * CHANNELS;
            pcm[sample] = crossfade(
                    lastReverseLeft, pcm[sample], elapsed, crossfadeFrames);
            pcm[sample + 1] = crossfade(
                    lastReverseRight, pcm[sample + 1],
                    elapsed, crossfadeFrames);
            releaseCrossfadeRemaining--;
        }
        if (releaseCrossfadeRemaining == 0) {
            hasLastReverseFrame = false;
        }
    }

    private void cancelReleaseCrossfade() {
        hasLastReverseFrame = false;
        reverseFrameOutput = false;
        releaseCrossfadeRemaining = 0;
    }

    private static short crossfade(
            short from, short to, int elapsed, int total) {
        long mixed = (long) from * (total - elapsed)
                + (long) to * elapsed;
        return (short) (mixed / total);
    }

    private void detach(CaptureHandle capture) {
        assertOwnerThread();
        for (int index = 0; index < captureCount; index++) {
            if (captures[index] != capture) {
                continue;
            }
            int remaining = captureCount - index - 1;
            if (remaining > 0) {
                System.arraycopy(
                        captures, index + 1, captures, index, remaining);
            }
            captures[--captureCount] = null;
            return;
        }
    }

    private void assertOwnerBoundary() {
        assertOwnerThread();
        assertOpen();
        if (presenting) {
            throw new IllegalStateException(
                    "audio presentation boundary is active");
        }
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "audio presentation producer accessed off owner thread");
        }
    }

    private void assertOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "audio presentation producer is closed");
        }
    }

    private final class CaptureHandle
            implements LiveCaptureAudioHandle, AudioPresentationListener {
        private final int captureFrameRate;
        private final int captureMaxStereoFrames;
        private final AudioFrameClock captureClock;
        private final short[] pending;
        private int pendingStereoFrames;
        private boolean fresh;
        private boolean closed;

        private CaptureHandle(
                int captureFrameRate,
                AudioFrameClock.Snapshot producerClockSnapshot) {
            this.captureFrameRate = captureFrameRate;
            captureMaxStereoFrames =
                    (sampleRate + captureFrameRate - 1) / captureFrameRate;
            captureClock = new AudioFrameClock(
                    sampleRate, captureFrameRate);
            captureClock.restoreSnapshot(new AudioFrameClock.Snapshot(
                    producerClockSnapshot.sampleRate(),
                    captureFrameRate,
                    0,
                    producerClockSnapshot.frameRate() == captureFrameRate
                            ? producerClockSnapshot.remainder() : 0));
            pending = new short[
                    Math.max(maxStereoFrames, captureMaxStereoFrames)
                            * CHANNELS];
        }

        @Override
        public void onPresentationFrame(AudioPresentationFrameView frame) {
            if (closed) {
                return;
            }
            frame.copyTo(pending, 0);
            pendingStereoFrames = frame.stereoFrames();
            fresh = true;
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public int frameRate() {
            return captureFrameRate;
        }

        @Override
        public int maxStereoFramesPerPacket() {
            return captureMaxStereoFrames;
        }

        @Override
        public int drainPresentationFrame(short[] target) {
            Objects.requireNonNull(target, "target");
            if (closed) {
                throw new IllegalStateException(
                        "audio presentation capture is closed");
            }
            int requiredCapacity =
                    Math.multiplyExact(captureMaxStereoFrames, CHANNELS);
            if (target.length < requiredCapacity) {
                throw new IllegalArgumentException(
                        "target is too small for the maximum presentation packet");
            }
            int requestedFrames = captureClock.samplesForNextFrame();
            int copiedFrames = fresh
                    ? Math.min(requestedFrames, pendingStereoFrames)
                    : 0;
            if (copiedFrames > 0) {
                System.arraycopy(
                        pending, 0, target, 0, copiedFrames * CHANNELS);
            }
            Arrays.fill(
                    target,
                    copiedFrames * CHANNELS,
                    requestedFrames * CHANNELS,
                    (short) 0);
            fresh = false;
            pendingStereoFrames = 0;
            return requestedFrames;
        }

        @Override
        public long totalStereoFrames() {
            return captureClock.totalSamplesProduced();
        }

        @Override
        public AudioFrameClock.Snapshot clockSnapshot() {
            return captureClock.captureSnapshot();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            assertOwnerThread();
            closed = true;
            fresh = false;
            pendingStereoFrames = 0;
            detach(this);
        }
    }
}
