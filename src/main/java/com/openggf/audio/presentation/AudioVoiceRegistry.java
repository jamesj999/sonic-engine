package com.openggf.audio.presentation;

import com.openggf.audio.AudioDiagnosticObserverException;
import com.openggf.audio.ChannelType;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy;
import com.openggf.audio.presentation.AudioPresentationCommand.AddSmpsSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.ChangeMusicTempo;
import com.openggf.audio.presentation.AudioPresentationCommand.EndMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.FadeMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.HardReset;
import com.openggf.audio.presentation.AudioPresentationCommand.MusicVoiceEntry;
import com.openggf.audio.presentation.AudioPresentationCommand.PushMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceRawPcm;
import com.openggf.audio.presentation.AudioPresentationCommand.ResetRingAlternation;
import com.openggf.audio.presentation.AudioPresentationCommand.RestoreMusicOverride;
import com.openggf.audio.presentation.AudioPresentationCommand.RewindBoundary;
import com.openggf.audio.presentation.AudioPresentationCommand.SampleVoiceDescriptor;
import com.openggf.audio.presentation.AudioPresentationCommand.SetSpeedMultiplier;
import com.openggf.audio.presentation.AudioPresentationCommand.SetSpeedShoes;
import com.openggf.audio.presentation.AudioPresentationCommand.SetVoiceGain;
import com.openggf.audio.presentation.AudioPresentationCommand.SetVoicePitch;
import com.openggf.audio.presentation.AudioPresentationCommand.StartSampleSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.StopAllSfx;
import com.openggf.audio.presentation.AudioPresentationCommand.StopMusic;
import com.openggf.audio.presentation.AudioPresentationCommand.StopRawPcm;
import com.openggf.audio.presentation.AudioPresentationCommand.SmpsVoiceDescriptor;
import com.openggf.audio.presentation.AudioPresentationCommand.ToggleMute;
import com.openggf.audio.presentation.AudioPresentationCommand.ToggleSolo;
import com.openggf.audio.presentation.AudioPresentationCommand.VoiceDescriptor;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.session.SmpsDriverSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Deterministic fixed-slot owner for software presentation voices.
 */
public final class AudioVoiceRegistry implements PresentationVoiceSource {
    public static final int MAX_SAMPLE_SFX_VOICES = 32;
    public static final int MAX_DEFERRED_MUTATIONS = 64;
    private static final int MAX_MUSIC_OVERRIDES =
            AudioPresentationCommandQueue.CAPACITY;
    private static final int ORDERED_VOICE_CAPACITY =
            MAX_SAMPLE_SFX_VOICES + 3;
    private static final int WARNED_REJECTION_CAPACITY =
            AudioPresentationCommandQueue.CAPACITY;

    private record MusicSlot(
            int musicId,
            com.openggf.audio.rewind.AudioSourceDescriptor sourceDescriptor,
            PresentationVoice voice) {
    }

    /** Driverless metadata handle for one session-owned music command. */
    private static final class SmpsMusicHandle
            implements PresentationVoice {
        private final long voiceId;

        private SmpsMusicHandle(long voiceId) {
            this.voiceId = voiceId;
        }

        @Override public long voiceId() { return voiceId; }
        @Override public int priority() { return 0; }
        @Override public void mixInto(
                long[] accumulation, int stereoFrames) { }
        @Override public boolean isComplete() { return false; }
        @Override public void stop() { }
        @Override public PresentationVoiceSnapshot snapshot() {
            throw new IllegalStateException(
                    "session SMPS handles are snapshotted by the session");
        }
    }

    private record PreparedRestore(
            MusicSlot activeMusic,
            MusicSlot[] overrides,
            SampleBackedVoice rawPcm,
            SampleBackedVoice[] sampleSfx) {
    }

    public interface LiveMutationToken {
    }

    /** Only one registry mutation is open; token flags and diagnostics remain independent. */
    private final class MutationBuffers {
        private final MusicSlot[] overrides = new MusicSlot[overrideStack.length];
        private final SampleBackedVoice[] samples = new SampleBackedVoice[sampleSfx.length];
        private final PresentationVoice[] voices =
                new PresentationVoice[overrideStack.length + sampleSfx.length + 2];
        private final Object[] voiceStates = new Object[voices.length];
        private final long[] removals = new long[deferredRemovals.length];
        private final long[] warnings = new long[warnedRejectionVoiceIds.length];

        private void clearReferences() {
            java.util.Arrays.fill(overrides, null);
            java.util.Arrays.fill(samples, null);
            java.util.Arrays.fill(voices, null);
            java.util.Arrays.fill(voiceStates, null);
        }
    }

    private MutationBuffers mutationBuffers;

    private final class RegistryLiveMutation implements LiveMutationToken {
        private final AudioVoiceRegistry owner = AudioVoiceRegistry.this;
        private final MusicSlot activeMusic =
                AudioVoiceRegistry.this.activeMusic;
        private final MusicSlot[] overrides = mutationBuffers.overrides;
        private final SampleBackedVoice rawPcm =
                AudioVoiceRegistry.this.rawPcm;
        private final SampleBackedVoice[] sampleSfx = mutationBuffers.samples;
        private final PresentationVoice[] voices = mutationBuffers.voices;
        private final Object[] voiceStates = mutationBuffers.voiceStates;
        private final int voiceCount;
        private final int overrideCount =
                AudioVoiceRegistry.this.overrideCount;
        private final int sampleSfxCount =
                AudioVoiceRegistry.this.sampleSfxCount;
        private final int deferredRemovalCount =
                AudioVoiceRegistry.this.deferredRemovalCount;
        private final long[] deferredRemovals = mutationBuffers.removals;
        private final int completionSweepCount =
                AudioVoiceRegistry.this.completionSweepCount;
        private final boolean completionSweepRequired =
                AudioVoiceRegistry.this.completionSweepRequired;
        private final boolean rendering =
                AudioVoiceRegistry.this.rendering;
        private final boolean sfxBlocked =
                AudioVoiceRegistry.this.sfxBlocked;
        private final boolean sfxBlockHeldThroughFadeIn =
                AudioVoiceRegistry.this.sfxBlockHeldThroughFadeIn;
        private final boolean pendingRestore =
                AudioVoiceRegistry.this.pendingRestore;
        private final boolean speedShoesEnabled =
                AudioVoiceRegistry.this.speedShoesEnabled;
        private final int speedMultiplier =
                AudioVoiceRegistry.this.speedMultiplier;
        private final int fmMuteMask =
                AudioVoiceRegistry.this.fmMuteMask;
        private final int fmSoloMask =
                AudioVoiceRegistry.this.fmSoloMask;
        private final int psgMuteMask =
                AudioVoiceRegistry.this.psgMuteMask;
        private final int psgSoloMask =
                AudioVoiceRegistry.this.psgSoloMask;
        private final boolean ringLeft = AudioVoiceRegistry.this.ringLeft;
        private final long nextVoiceId =
                AudioVoiceRegistry.this.nextVoiceId;
        private final int warnedRejectionCount =
                AudioVoiceRegistry.this.warnedRejectionCount;
        private final int warnedRejectionCursor =
                AudioVoiceRegistry.this.warnedRejectionCursor;
        private final long[] warnedRejectionVoiceIds =
                mutationBuffers.warnings;
        private final SmpsCoordFlagRuntimeState.Snapshot coordState =
                coordFlagHandlers.state().snapshot();
        private final AudioPresentationDependencyResolver
                .DiagnosticTransaction diagnostics;
        private boolean diagnosticsPublished;
        private boolean commitPrepared;
        private boolean consumed;

        private RegistryLiveMutation() {
            diagnostics = dependencyResolver.beginDiagnosticTransaction();
            try {
                System.arraycopy(overrideStack, 0, overrides, 0, overrideCount);
                System.arraycopy(AudioVoiceRegistry.this.sampleSfx, 0, sampleSfx, 0, sampleSfxCount);
                System.arraycopy(AudioVoiceRegistry.this.deferredRemovals, 0,
                        deferredRemovals, 0, deferredRemovalCount);
                System.arraycopy(AudioVoiceRegistry.this.warnedRejectionVoiceIds, 0,
                        warnedRejectionVoiceIds, 0, warnedRejectionVoiceIds.length);
                voiceCount = copyOwnedVoices(voices);
                for (int index = 0; index < voiceCount; index++) {
                    voiceStates[index] = captureVoiceMutationState(
                            voices[index]);
                }
            } catch (RuntimeException failure) {
                try {
                    diagnostics.discard();
                } catch (RuntimeException discardFailure) {
                    failure.addSuppressed(discardFailure);
                }
                throw failure;
            }
        }
    }

    public static final class PreparedSnapshotRestore {
        private final PreparedRestore voices;
        private final AudioPresentationSnapshot snapshot;
        private final AudioPresentationDependencyResolver.DiagnosticTransaction
                diagnostics;
        private boolean consumed;
        private boolean diagnosticsPublished;

        private PreparedSnapshotRestore(
                PreparedRestore voices,
                AudioPresentationSnapshot snapshot,
                AudioPresentationDependencyResolver.DiagnosticTransaction
                        diagnostics) {
            this.voices = voices;
            this.snapshot = snapshot;
            this.diagnostics = diagnostics;
        }
    }

    private final Thread ownerThread;
    private final SmpsSfxInstantiation sfxInstantiation;
    private final AudioPresentationDependencyResolver dependencyResolver;
    private final SmpsCoordFlagHandlerOwner coordFlagHandlers;
    private final Consumer<String> warningConsumer;
    private final SmpsDriverSession smpsSession;
    private final MusicSlot[] overrideStack =
            new MusicSlot[MAX_MUSIC_OVERRIDES];
    private final SampleBackedVoice[] sampleSfx =
            new SampleBackedVoice[MAX_SAMPLE_SFX_VOICES];
    private final PresentationVoice[] orderedVoices =
            new PresentationVoice[ORDERED_VOICE_CAPACITY];
    private final long[] deferredRemovals =
            new long[MAX_DEFERRED_MUTATIONS];
    private final long[] warnedRejectionVoiceIds =
            new long[WARNED_REJECTION_CAPACITY];

    private MusicSlot activeMusic;
    private SampleBackedVoice rawPcm;
    private int overrideCount;
    private int sampleSfxCount;
    private int orderedVoiceCount;
    private int deferredRemovalCount;
    private int completionSweepCount;
    private int warnedRejectionCount;
    private int warnedRejectionCursor;
    private boolean rendering;
    private boolean completionSweepRequired;
    private boolean sfxBlocked;
    /**
     * The S1/S2 {@code f_fadein_flag} half of the SFX block: the 1-up has
     * handed back and the block now lasts only until the restored song's fade
     * in completes. False while the jingle itself plays.
     */
    private boolean sfxBlockHeldThroughFadeIn;
    private boolean pendingRestore;
    private boolean speedShoesEnabled;
    private int speedMultiplier = 1;
    private int fmMuteMask;
    private int fmSoloMask;
    private int psgMuteMask;
    private int psgSoloMask;
    private boolean ringLeft = true;
    private long nextVoiceId;
    private boolean liveMutationOpen;

    public AudioVoiceRegistry() {
        this(new SmpsSfxInstantiation() {
            @Override
            public SmpsSequencer instantiateCached(
                    ResolvedSmpsSfxSource source, SmpsDriver currentOwner) {
                return null;
            }

        }, rejectingDependencyResolver(),
                new SmpsCoordFlagHandlerOwner(new SmpsCoordFlagRuntimeState()),
                ignored -> {
                }, null);
    }

    public AudioVoiceRegistry(
            SmpsSfxInstantiation sfxInstantiation,
            SmpsCoordFlagHandlerOwner coordFlagHandlers,
            Consumer<String> warningConsumer) {
        this(sfxInstantiation, rejectingDependencyResolver(),
                coordFlagHandlers, warningConsumer, null);
    }

    public AudioVoiceRegistry(
            SmpsSfxInstantiation sfxInstantiation,
            AudioPresentationDependencyResolver dependencyResolver,
            SmpsCoordFlagHandlerOwner coordFlagHandlers,
            Consumer<String> warningConsumer) {
        this(sfxInstantiation, dependencyResolver, coordFlagHandlers,
                warningConsumer, null);
    }

    public AudioVoiceRegistry(
            SmpsSfxInstantiation sfxInstantiation,
            AudioPresentationDependencyResolver dependencyResolver,
            SmpsCoordFlagHandlerOwner coordFlagHandlers,
            Consumer<String> warningConsumer,
            SmpsDriverSession smpsSession) {
        ownerThread = Thread.currentThread();
        this.sfxInstantiation =
                Objects.requireNonNull(sfxInstantiation, "sfxInstantiation");
        this.dependencyResolver =
                Objects.requireNonNull(dependencyResolver, "dependencyResolver");
        this.coordFlagHandlers =
                Objects.requireNonNull(coordFlagHandlers, "coordFlagHandlers");
        this.warningConsumer =
                Objects.requireNonNull(warningConsumer, "warningConsumer");
        this.smpsSession = smpsSession;
    }

    public void apply(AudioPresentationCommand command) {
        assertOwnerBoundary();
        Objects.requireNonNull(command, "command");

        if (hasUnownedSmps(command)) {
            throw new IllegalArgumentException(
                    "SMPS presentation requires a session-owned prepared command");
        }
        if (smpsSession != null && applySessionMetadata(command)) {
            rebuildOrderedVoices();
            return;
        }

        if (command instanceof ReplaceMusic replace) {
            replaceMusic(replace.music());
        } else if (command instanceof PushMusicOverride push) {
            if (smpsSession != null && smpsSession.stopsSfxWhenOverrideStarts()) {
                stopAllSfx();
            }
            pushMusicOverride(push.music());
            blockOverrideSfx();
            sfxInstantiation.observeLifecycle(
                    SmpsDriverServiceObserver.LifecycleEvent.registry(
                            SmpsDriverServiceObserver.LifecycleKind.SAVE,
                            SmpsDriverServiceObserver.LifecycleSource.MUSIC_OVERRIDE));
        } else if (command instanceof RestoreMusicOverride) {
            if (restoreMusicOverride()) {
                sfxInstantiation.observeLifecycle(
                        SmpsDriverServiceObserver.LifecycleEvent.registry(
                                SmpsDriverServiceObserver.LifecycleKind.RESTORE,
                                SmpsDriverServiceObserver.LifecycleSource.MUSIC_OVERRIDE));
            }
        } else if (command instanceof EndMusicOverride end) {
            endMusicOverride(end.musicId());
        } else if (command instanceof StartSampleSfx start) {
            admitSampleSfx(start.voice());
        } else if (command instanceof ReplaceRawPcm replace) {
            // A driver whose policy owns zPlaySEGAPCM streams the chant
            // itself (Sound/Z80 Sound Driver.asm:4372-4424); the prepared
            // presentation voice is then not the mechanism, and would double
            // the chant against the chip's own DAC output.
            if (smpsSession != null && smpsSession.ownsSegaPcmTransport()) {
                stopRawPcm();
                smpsSession.beginSegaPcmTransport(replace.pcm());
            } else {
                replaceRawPcm(replace.voice());
            }
            sfxInstantiation.observeLifecycle(
                    SmpsDriverServiceObserver.LifecycleEvent.pcm(
                            SmpsDriverServiceObserver.LifecycleKind.SEGA_PCM_ENTER));
        } else if (command instanceof AudioPresentationCommand
                .StopRawPcmAndRetainGlobalStop) {
            if (stopRawPcmOrTransport()) {
                sfxInstantiation.observeLifecycle(
                        SmpsDriverServiceObserver.LifecycleEvent.pcm(
                                SmpsDriverServiceObserver.LifecycleKind
                                        .SEGA_PCM_LEAVE));
            }
        } else if (command instanceof StopRawPcm) {
            if (stopRawPcmOrTransport()) {
                sfxInstantiation.observeLifecycle(
                        SmpsDriverServiceObserver.LifecycleEvent.pcm(
                                SmpsDriverServiceObserver.LifecycleKind.SEGA_PCM_LEAVE));
            }
        } else if (command instanceof StopMusic) {
            stopMusic();
        } else if (command instanceof StopAllSfx) {
            stopAllSfx();
        } else if (command instanceof FadeMusic fade) {
            fadeMusic(fade.steps(), fade.delay());
        } else if (command instanceof SetVoiceGain gain) {
            setVoiceGain(gain.voiceId(), gain.gainQ16());
        } else if (command instanceof SetVoicePitch pitch) {
            setVoicePitch(pitch.voiceId(), pitch.sourceStepQ32());
        } else if (command instanceof SetSpeedShoes speedShoes) {
            setSpeedShoes(speedShoes.enabled());
        } else if (command instanceof SetSpeedMultiplier speed) {
            setSpeedMultiplier(speed.multiplier());
        } else if (command instanceof ChangeMusicTempo tempo) {
            changeMusicTempo(tempo.dividingTiming());
        } else if (command instanceof ResetRingAlternation reset) {
            ringLeft = reset.ringLeft();
        } else if (command instanceof ToggleMute mute) {
            toggleMute(mute.type(), mute.channel());
        } else if (command instanceof ToggleSolo solo) {
            toggleSolo(solo.type(), solo.channel());
        } else if (command instanceof HardReset) {
            clear();
            sfxInstantiation.observeLifecycle(
                    SmpsDriverServiceObserver.LifecycleEvent.registry(
                            SmpsDriverServiceObserver.LifecycleKind.RESET,
                            SmpsDriverServiceObserver.LifecycleSource.COMMAND));
            return;
        } else if (!(command instanceof RewindBoundary)) {
            throw new IllegalArgumentException(
                    "unsupported presentation command " + command.getClass());
        }
        rebuildOrderedVoices();
    }

    /** Resolves a cached SFX program after the producer captured rollback. */
    public com.openggf.audio.session.PreparedSmpsSfxProgram
            prepareSessionSfx(AddSmpsSfx command) {
        assertOwnerBoundary();
        if (smpsSession == null) {
            throw new IllegalStateException(
                    "registry has no SMPS session");
        }
        ResolvedSmpsSfxSource source = Objects.requireNonNull(
                command, "command").source();
        SmpsSfxInstantiation.Admission admission =
                sfxInstantiation.evaluateAdmission(source, null);
        if (!admission.result().accepted()) {
            observeAdmissionQuarantined(admission);
            warnRejected(source.standaloneVoiceId(),
                    "SMPS SFX policy rejected " + source.assetKey());
            return null;
        }
        releaseSfxBlockWhenFadeInEnds();
        if (sfxBlocked) {
            observeAdmissionQuarantined(
                    sfxInstantiation.rejectedAdmission(admission,
                            SmpsRequestAdmissionPolicy.RejectionReason
                                    .BLOCKED));
            warnRejected(source.standaloneVoiceId(),
                    "SMPS SFX blocked at presentation boundary");
            return null;
        }
        com.openggf.audio.session.PreparedSmpsSfxProgram program;
        try {
            program = command.program() != null
                    ? command.program()
                    : sfxInstantiation.prepareCached(source);
        } catch (SmpsSfxInstantiation.CacheMissException cacheMiss) {
            observeAdmissionQuarantined(
                    sfxInstantiation.rejectedAdmission(admission,
                            SmpsRequestAdmissionPolicy.RejectionReason
                                    .CACHE_MISS));
            warnRejected(source.standaloneVoiceId(),
                    "SMPS SFX cache rejected " + source.assetKey());
            return null;
        }
        if (program == null) {
            observeAdmissionQuarantined(
                    sfxInstantiation.rejectedAdmission(admission,
                            SmpsRequestAdmissionPolicy.RejectionReason
                                    .CACHE_MISS));
            warnRejected(source.standaloneVoiceId(),
                    "SMPS SFX cache miss for " + source.assetKey());
            return null;
        }
        observeAdmissionQuarantined(admission);
        nextVoiceId = Math.max(nextVoiceId,
                source.standaloneVoiceId() + 1);
        return program;
    }

    private boolean applySessionMetadata(
            AudioPresentationCommand command) {
        if (command instanceof ReplaceMusic replace
                && isPreparedSmps(replace.music())) {
            replaceSessionMusic(replace.music());
            return true;
        }
        if (command instanceof PushMusicOverride push
                && isPreparedSmps(push.music())) {
            pushSessionMusic(push.music());
            return true;
        }
        if (command instanceof AddSmpsSfx) {
            return true;
        }
        if (command instanceof AudioPresentationCommand.RetainGlobalStop
                || command instanceof AudioPresentationCommand.StopSmpsSfx
                || command instanceof AudioPresentationCommand.SilencePsg
                || command instanceof AudioPresentationCommand
                .ReferenceLimitation) {
            return true;
        }
        if (command instanceof StopMusic) {
            stopMusic();
            return true;
        }
        if (command instanceof StopAllSfx) {
            stopAllSfx();
            return true;
        }
        if (command instanceof FadeMusic fade) {
            fadeMusic(fade.steps(), fade.delay());
            // The host fade policy may clear speed shoes. Retain the session's
            // result so later metadata snapshots/restores cannot resurrect them.
            speedShoesEnabled = smpsSession.speedShoesEnabled();
            return true;
        }
        if (command instanceof ChangeMusicTempo) {
            return true;
        }
        if (command instanceof SetSpeedShoes speed) {
            setSpeedShoes(speed.enabled());
            return true;
        }
        if (command instanceof SetSpeedMultiplier speed) {
            setSpeedMultiplier(speed.multiplier());
            return true;
        }
        if (command instanceof ResetRingAlternation reset) {
            ringLeft = reset.ringLeft();
            return true;
        }
        if (command instanceof HardReset) {
            clear();
            return true;
        }
        return false;
    }

    boolean hasActiveMusicMetadata() {
        return activeMusic != null;
    }

    int activeMusicMetadataId() {
        return activeMusic == null ? -1 : activeMusic.musicId();
    }

    boolean activeMusicMetadataUsesSession() {
        return activeMusic != null
                && activeMusic.voice() instanceof SmpsMusicHandle;
    }

    private static boolean isPreparedSmps(MusicVoiceEntry music) {
        return music.voiceDescriptor() instanceof SmpsVoiceDescriptor smps
                && smps.activation() != null;
    }

    private void replaceSessionMusic(MusicVoiceEntry entry) {
        stopMusic();
        SmpsVoiceDescriptor descriptor = (SmpsVoiceDescriptor) entry.voiceDescriptor();
        if (descriptor.activation().logicalPolicy().resetsTempoOnMusicStart()) {
            speedShoesEnabled = false;
            speedMultiplier = 1;
        }
        activeMusic = sessionMusicSlot(entry);
        noteVoiceId(activeMusic.voice());
    }

    private void pushSessionMusic(MusicVoiceEntry entry) {
        if (activeMusic != null
                && activeMusic.musicId() != entry.musicId()
                && overrideCount == overrideStack.length) {
            throw new IllegalStateException(
                    "music override stack exceeds fixed capacity");
        }
        MusicSlot replacement = sessionMusicSlot(entry);
        if (activeMusic != null
                && activeMusic.musicId() == entry.musicId()) {
            activeMusic = replacement;
        } else {
            if (activeMusic != null) {
                overrideStack[overrideCount++] = activeMusic;
            }
            activeMusic = replacement;
        }
        noteVoiceId(replacement.voice());
        blockOverrideSfx();
    }

    private void restoreSessionMusic() {
        if (overrideCount == 0) {
            pendingRestore = false;
            return;
        }
        activeMusic = overrideStack[--overrideCount];
        overrideStack[overrideCount] = null;
        pendingRestore = false;
    }

    private void endSessionMusic(int musicId) {
        if (activeMusic != null && activeMusic.musicId() == musicId) {
            restoreSessionMusic();
            return;
        }
        for (int index = overrideCount - 1; index >= 0; index--) {
            if (overrideStack[index].musicId() != musicId) {
                continue;
            }
            int remaining = overrideCount - index - 1;
            if (remaining > 0) {
                System.arraycopy(overrideStack, index + 1,
                        overrideStack, index, remaining);
            }
            overrideStack[--overrideCount] = null;
            return;
        }
    }

    private static MusicSlot sessionMusicSlot(MusicVoiceEntry entry) {
        return new MusicSlot(entry.musicId(), entry.sourceDescriptor(),
                new SmpsMusicHandle(entry.voiceDescriptor().voiceId()));
    }

    private void observeAdmissionQuarantined(
            SmpsSfxInstantiation.Admission admission) {
        try {
            sfxInstantiation.observeAdmission(admission);
        } catch (RuntimeException ignored) {
            // Session diagnostics cannot reject or replay committed audio.
        }
    }

    public LiveMutationToken captureLiveMutation() {
        assertOwnerBoundary();
        if (liveMutationOpen) {
            throw new IllegalStateException(
                    "an audio registry mutation is already active");
        }
        if (mutationBuffers == null) mutationBuffers = new MutationBuffers();
        liveMutationOpen = true;
        try {
            return new RegistryLiveMutation();
        } catch (RuntimeException failure) {
            liveMutationOpen = false;
            mutationBuffers.clearReferences();
            throw failure;
        }
    }

    public void commitLiveMutation(LiveMutationToken token) {
        assertOwnerBoundary();
        RegistryLiveMutation state = requireLiveMutation(token);
        requireUnconsumed(state);
        if (!liveMutationOpen) {
            throw new IllegalStateException(
                    "audio registry mutation is not active");
        }
        if (!state.commitPrepared) {
            prepareLiveMutationCommit(token);
        }
        state.consumed = true;
        liveMutationOpen = false;
        mutationBuffers.clearReferences();
    }

    /** Runs the only fallible commit work while rollback is still possible. */
    public void prepareLiveMutationCommit(LiveMutationToken token) {
        assertOwnerBoundary();
        RegistryLiveMutation state = requireLiveMutation(token);
        requireUnconsumed(state);
        if (!liveMutationOpen) {
            throw new IllegalStateException(
                    "audio registry mutation is not active");
        }
        if (state.commitPrepared) {
            throw new IllegalStateException(
                    "audio registry mutation commit is already prepared");
        }
        state.diagnostics.endPreparation();
        state.commitPrepared = true;
    }

    /** Publishes diagnostics only after the composite owner committed. */
    public void publishCommittedDiagnostics(LiveMutationToken token) {
        assertOwnerBoundary();
        RegistryLiveMutation state = requireLiveMutation(token);
        if (!state.consumed) {
            throw new IllegalStateException(
                    "registry mutation is not committed");
        }
        if (state.diagnosticsPublished) {
            throw new IllegalStateException(
                    "registry diagnostics are already published");
        }
        state.diagnosticsPublished = true;
        state.diagnostics.commit();
    }

    public void rollbackLiveMutation(LiveMutationToken token) {
        assertOwnerThread();
        RegistryLiveMutation state = requireLiveMutation(token);
        requireUnconsumed(state);
        if (!liveMutationOpen) {
            throw new IllegalStateException(
                    "audio registry mutation is not active");
        }
        RuntimeException primary = null;
        try {
            state.diagnostics.discard();
        } catch (RuntimeException failure) {
            primary = failure;
        }
        for (int index = state.voiceCount - 1; index >= 0; index--) {
            try {
                rollbackVoiceMutation(
                        state.voices[index], state.voiceStates[index]);
            } catch (RuntimeException failure) {
                if (primary == null) {
                    primary = failure;
                } else {
                    primary.addSuppressed(failure);
                }
            }
        }
        activeMusic = state.activeMusic;
        java.util.Arrays.fill(overrideStack, null);
        System.arraycopy(state.overrides, 0,
                overrideStack, 0, state.overrideCount);
        overrideCount = state.overrideCount;
        rawPcm = state.rawPcm;
        java.util.Arrays.fill(sampleSfx, null);
        System.arraycopy(state.sampleSfx, 0,
                sampleSfx, 0, state.sampleSfxCount);
        sampleSfxCount = state.sampleSfxCount;
        deferredRemovalCount = state.deferredRemovalCount;
        System.arraycopy(state.deferredRemovals, 0,
                deferredRemovals, 0, state.deferredRemovalCount);
        completionSweepCount = state.completionSweepCount;
        completionSweepRequired = state.completionSweepRequired;
        rendering = state.rendering;
        sfxBlocked = state.sfxBlocked;
        sfxBlockHeldThroughFadeIn = state.sfxBlockHeldThroughFadeIn;
        pendingRestore = state.pendingRestore;
        speedShoesEnabled = state.speedShoesEnabled;
        speedMultiplier = state.speedMultiplier;
        fmMuteMask = state.fmMuteMask;
        fmSoloMask = state.fmSoloMask;
        psgMuteMask = state.psgMuteMask;
        psgSoloMask = state.psgSoloMask;
        ringLeft = state.ringLeft;
        nextVoiceId = state.nextVoiceId;
        warnedRejectionCount = state.warnedRejectionCount;
        warnedRejectionCursor = state.warnedRejectionCursor;
        System.arraycopy(state.warnedRejectionVoiceIds, 0,
                warnedRejectionVoiceIds, 0,
                warnedRejectionVoiceIds.length);
        coordFlagHandlers.state().restore(state.coordState);
        rebuildOrderedVoices();
        state.consumed = true;
        liveMutationOpen = false;
        mutationBuffers.clearReferences();
        if (primary != null) {
            throw primary;
        }
    }

    /** Clears non-SMPS presentation state after a committed retained stop. */
    public void clearForGlobalStopWithoutWrites() {
        assertOwnerThread();
        activeMusic = null;
        java.util.Arrays.fill(overrideStack, null);
        overrideCount = 0;
        rawPcm = null;
        java.util.Arrays.fill(sampleSfx, null);
        sampleSfxCount = 0;
        pendingRestore = false;
        releaseOverrideSfx();
        speedShoesEnabled = false;
        speedMultiplier = 1;
        ringLeft = true;
        coordFlagHandlers.reset();
        rebuildOrderedVoices();
    }

    @Override
    public int orderedVoiceCount() {
        return orderedVoiceCount;
    }

    @Override
    public PresentationVoice orderedVoiceAt(int index) {
        if (index < 0 || index >= orderedVoiceCount) {
            throw new IndexOutOfBoundsException(index);
        }
        return orderedVoices[index];
    }

    public long allocateVoiceId() {
        assertOwnerBoundary();
        return nextVoiceId++;
    }

    public boolean isMuted(ChannelType type, int channel) {
        int bit = channelBit(type, channel);
        return type == ChannelType.PSG
                ? (psgMuteMask & bit) != 0
                : (fmMuteMask & bit) != 0;
    }

    public boolean isSoloed(ChannelType type, int channel) {
        int bit = channelBit(type, channel);
        return type == ChannelType.PSG
                ? (psgSoloMask & bit) != 0
                : (fmSoloMask & bit) != 0;
    }

    public void beginRendering() {
        releaseSfxBlockWhenFadeInEnds();
        assertOwnerThread();
        if (rendering) {
            throw new IllegalStateException("audio voice traversal already active");
        }
        rendering = true;
        deferredRemovalCount = 0;
        completionSweepRequired = false;
        releaseSfxBlockWhenFadeInEnds();
    }

    /** Services every live SMPS driver once at the outer-frame boundary. */
    public void serviceOuterFrame() {
        assertOwnerThread();
        if (!rendering) {
            throw new IllegalStateException(
                    "outer-frame service requires active voice traversal");
        }
    }

    public void endRendering() {
        assertOwnerThread();
        if (!rendering) {
            throw new IllegalStateException("audio voice traversal is not active");
        }
        for (int index = 0; index < orderedVoiceCount; index++) {
            PresentationVoice voice = orderedVoices[index];
            if (voice.isComplete()) {
                deferRemovalInternal(voice.voiceId());
            }
        }
        rendering = false;
        if (overrideCount == 0 && sfxBlocked && activeMusic != null
                && activeMusic.voice() instanceof StreamedMusicVoice streamed
                && streamed.restoreFadeComplete()) {
            sfxBlocked = false;
        }

        if (completionSweepRequired) {
            completionSweepCount++;
            sweepCompletedAndDeferredVoices();
        } else {
            for (int index = 0; index < deferredRemovalCount; index++) {
                removeVoiceById(deferredRemovals[index]);
            }
        }
        deferredRemovalCount = 0;
        completionSweepRequired = false;
        rebuildOrderedVoices();
    }

    public boolean isRendering() {
        return rendering;
    }

    public void deferRemoval(long voiceId) {
        assertOwnerThread();
        if (!rendering) {
            throw new IllegalStateException(
                    "voice removal may be deferred only during traversal");
        }
        deferRemovalInternal(voiceId);
    }

    public void onVoiceFailure(PresentationVoice voice) {
        assertOwnerThread();
        Objects.requireNonNull(voice, "voice");
        warningConsumer.accept(
                "Audio presentation voice " + voice.voiceId()
                        + " failed and was removed");
        if (rendering) {
            deferRemovalInternal(voice.voiceId());
        } else {
            removeVoiceById(voice.voiceId());
            rebuildOrderedVoices();
        }
    }

    public boolean completionSweepRequired() {
        return completionSweepRequired;
    }

    public int completionSweepCount() {
        return completionSweepCount;
    }

    public AudioPresentationSnapshot snapshot() {
        assertOwnerBoundary();
        List<PresentationVoiceSnapshot> voices = new ArrayList<>();
        addMusicSnapshot(voices, activeMusic);
        for (int index = 0; index < overrideCount; index++) {
            addMusicSnapshot(voices, overrideStack[index]);
        }
        addVoiceSnapshot(voices, rawPcm, null);
        for (int index = 0; index < sampleSfxCount; index++) {
            addVoiceSnapshot(voices, sampleSfx[index], null);
        }

        List<AudioPresentationSnapshot.MusicSlotSnapshot> overrides =
                new ArrayList<>(overrideCount);
        for (int index = 0; index < overrideCount; index++) {
            overrides.add(slotSnapshot(overrideStack[index]));
        }
        return new AudioPresentationSnapshot(
                nextVoiceId,
                voices,
                activeMusic == null ? null : slotSnapshot(activeMusic),
                overrides,
                rawPcm == null ? null : rawPcm.voiceId(),
                fmMuteMask,
                fmSoloMask,
                psgMuteMask,
                psgSoloMask,
                sfxBlocked,
                sfxBlockHeldThroughFadeIn,
                pendingRestore,
                speedShoesEnabled,
                speedMultiplier,
                ringLeft,
                coordFlagHandlers.state().snapshot(),
                smpsSession == null ? null : smpsSession.captureSnapshot(),
                smpsSession == null
                        ? null : smpsSession.captureLogicalSnapshot());
    }

    public void restore(
            AudioPresentationSnapshot snapshot,
            AudioPresentationDependencyResolver resolver) {
        commitPreparedRestore(prepareSnapshotRestore(snapshot, resolver));
    }

    public PreparedSnapshotRestore prepareSnapshotRestore(
            AudioPresentationSnapshot snapshot,
            AudioPresentationDependencyResolver resolver) {
        assertOwnerBoundary();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(resolver, "resolver");
        validateAuthoritativeSnapshot(snapshot);
        SmpsCoordFlagRuntimeState.Snapshot previousCoordState =
                coordFlagHandlers.state().snapshot();
        PresentationVoice[] recreated =
                new PresentationVoice[snapshot.voices().size()];
        AudioPresentationDependencyResolver.DiagnosticTransaction diagnostics =
                resolver.beginDiagnosticTransaction();
        PreparedRestore prepared;
        try {
            coordFlagHandlers.state().restore(snapshot.coordFlagRuntimeState());
            for (int index = 0; index < recreated.length; index++) {
                PresentationVoiceSnapshot voiceSnapshot =
                        snapshot.voices().get(index);
                recreated[index] = recreate(voiceSnapshot, resolver);
            }
            prepared = prepareRestore(snapshot, recreated);
            applyPreparedControls(prepared, snapshot);
        } catch (RuntimeException failure) {
            for (PresentationVoice voice : recreated) {
                if (voice != null) {
                    try {
                        discardDetachedVoice(voice);
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            try {
                diagnostics.endPreparation();
            } catch (RuntimeException endFailure) {
                failure.addSuppressed(endFailure);
            }
            try {
                diagnostics.discard();
            } catch (RuntimeException discardFailure) {
                failure.addSuppressed(discardFailure);
            }
            AudioDiagnosticObserverException.rethrowIfPresent(failure);
            throw failure;
        } finally {
            coordFlagHandlers.state().restore(previousCoordState);
        }
        diagnostics.endPreparation();
        return new PreparedSnapshotRestore(prepared, snapshot, diagnostics);
    }

    public void commitPreparedRestore(PreparedSnapshotRestore restore) {
        commitPreparedRestoreState(restore);
        publishPreparedRestoreDiagnostics(restore);
    }

    void commitPreparedRestoreState(PreparedSnapshotRestore restore) {
        assertOwnerBoundary();
        Objects.requireNonNull(restore, "restore");
        if (restore.consumed) {
            throw new IllegalStateException(
                    "Prepared presentation restore already consumed");
        }
        restore.consumed = true;
        PreparedRestore prepared = restore.voices;
        AudioPresentationSnapshot snapshot = restore.snapshot;
        try {
            coordFlagHandlers.state().restore(
                    snapshot.coordFlagRuntimeState());
            stopAndRemoveAllVoices();
            activeMusic = prepared.activeMusic();
            overrideCount = prepared.overrides().length;
            System.arraycopy(prepared.overrides(), 0, overrideStack, 0,
                    overrideCount);
            rawPcm = prepared.rawPcm();
            for (SampleBackedVoice sample : prepared.sampleSfx()) {
                insertSampleSorted(sample);
            }

            nextVoiceId = snapshot.nextVoiceId();
            fmMuteMask = snapshot.fmMuteMask();
            fmSoloMask = snapshot.fmSoloMask();
            psgMuteMask = snapshot.psgMuteMask();
            psgSoloMask = snapshot.psgSoloMask();
            sfxBlocked = snapshot.sfxBlocked();
            sfxBlockHeldThroughFadeIn = snapshot.sfxBlockHeldThroughFadeIn();
            pendingRestore = snapshot.pendingRestore();
            speedShoesEnabled = snapshot.speedShoesEnabled();
            speedMultiplier = snapshot.speedMultiplier();
            ringLeft = snapshot.ringLeft();
            rebuildOrderedVoices();
        } catch (RuntimeException failure) {
            try {
                restore.diagnostics.discard();
            } catch (RuntimeException discardFailure) {
                failure.addSuppressed(discardFailure);
            }
            AudioDiagnosticObserverException.rethrowIfPresent(failure);
            throw failure;
        }
    }

    void publishPreparedRestoreDiagnostics(
            PreparedSnapshotRestore restore) {
        assertOwnerBoundary();
        Objects.requireNonNull(restore, "restore");
        if (!restore.consumed) {
            throw new IllegalStateException(
                    "presentation restore state is not committed");
        }
        if (restore.diagnosticsPublished) {
            throw new IllegalStateException(
                    "presentation restore diagnostics already published");
        }
        restore.diagnosticsPublished = true;
        restore.diagnostics.commit();
    }

    public void discardPreparedRestore(PreparedSnapshotRestore restore) {
        assertOwnerBoundary();
        if (restore == null || restore.consumed) {
            return;
        }
        restore.consumed = true;
        try {
            PreparedRestore prepared = restore.voices;
            if (prepared.activeMusic() != null) {
                discardDetachedVoice(prepared.activeMusic().voice());
            }
            for (MusicSlot slot : prepared.overrides()) {
                discardDetachedVoice(slot.voice());
            }
            if (prepared.rawPcm() != null) {
                discardDetachedVoice(prepared.rawPcm());
            }
            for (SampleBackedVoice sample : prepared.sampleSfx()) {
                if (sample != null) {
                    discardDetachedVoice(sample);
                }
            }
        } finally {
            restore.diagnostics.discard();
        }
    }

    private static void discardDetachedVoice(PresentationVoice voice) {
        if (voice instanceof SampleBackedVoice sample) {
            sample.stop();
        }
    }

    private void applyPreparedControls(
            PreparedRestore prepared,
            AudioPresentationSnapshot snapshot) {
        // SMPS controls are restored by the session snapshot; only sample
        // voices are materialized by this registry.
    }

    private PreparedRestore prepareRestore(
            AudioPresentationSnapshot snapshot,
            PresentationVoice[] recreated) {
        boolean[] claimed = new boolean[recreated.length];
        MusicSlot preparedActive = restoreMusicSlot(
                snapshot.activeMusic(), recreated, claimed);
        if (snapshot.overrideStack().size() > overrideStack.length) {
            throw new IllegalArgumentException(
                    "snapshot override stack exceeds fixed capacity");
        }
        MusicSlot[] preparedOverrides =
                new MusicSlot[snapshot.overrideStack().size()];
        int preparedOverrideCount = 0;
        for (AudioPresentationSnapshot.MusicSlotSnapshot slot
                : snapshot.overrideStack()) {
            preparedOverrides[preparedOverrideCount++] =
                    restoreMusicSlot(slot, recreated, claimed);
        }

        SampleBackedVoice preparedRawPcm = null;
        if (snapshot.rawPcmVoiceId() != null) {
            PresentationVoice voice = claimVoice(
                    snapshot.rawPcmVoiceId(), recreated, claimed);
            if (!(voice instanceof SampleBackedVoice sample)) {
                throw new IllegalArgumentException(
                        "raw PCM slot does not reference a sample voice");
            }
            preparedRawPcm = sample;
        }

        SampleBackedVoice[] preparedSamples =
                new SampleBackedVoice[recreated.length];
        int preparedSampleCount = 0;
        for (int index = 0; index < recreated.length; index++) {
            if (claimed[index]) {
                continue;
            }
            if (!(recreated[index] instanceof SampleBackedVoice sample)) {
                throw new IllegalArgumentException(
                        "unclaimed composite voice in snapshot");
            }
            if (preparedSampleCount == sampleSfx.length) {
                throw new IllegalArgumentException(
                        "snapshot sample SFX exceeds fixed capacity");
            }
            preparedSamples[preparedSampleCount++] = sample;
            claimed[index] = true;
        }
        return new PreparedRestore(
                preparedActive, preparedOverrides, preparedRawPcm,
                java.util.Arrays.copyOf(preparedSamples, preparedSampleCount));
    }

    public void stopTransientVoices() {
        assertOwnerBoundary();
        stopAllSfx();
        rebuildOrderedVoices();
    }

    void reapplySessionControls() {
        assertOwnerBoundary();
        if (smpsSession == null) {
            throw new IllegalStateException(
                    "registry has no SMPS session controls");
        }
        applySessionMasks(fmMuteMask, fmSoloMask, psgMuteMask, psgSoloMask);
    }

    public void clear() {
        assertOwnerBoundary();
        stopAndRemoveAllVoices();
        fmMuteMask = 0;
        fmSoloMask = 0;
        psgMuteMask = 0;
        psgSoloMask = 0;
        sfxBlocked = false;
        sfxBlockHeldThroughFadeIn = false;
        pendingRestore = false;
        speedShoesEnabled = false;
        speedMultiplier = 1;
        ringLeft = true;
        nextVoiceId = 0;
        deferredRemovalCount = 0;
        completionSweepCount = 0;
        completionSweepRequired = false;
        warnedRejectionCount = 0;
        warnedRejectionCursor = 0;
        coordFlagHandlers.reset();
        rebuildOrderedVoices();
    }

    public void setSfxBlocked(boolean blocked) {
        assertOwnerBoundary();
        sfxBlocked = blocked;
        if (!blocked) {
            sfxBlockHeldThroughFadeIn = false;
        }
    }

    boolean areSfxRequestsBlocked() {
        assertOwnerBoundary();
        releaseSfxBlockWhenFadeInEnds();
        return sfxBlocked;
    }

    private void blockOverrideSfx() {
        if (smpsSession != null && smpsSession.suppressesSfxDuringOverride()) {
            sfxBlocked = true;
            sfxBlockHeldThroughFadeIn = false;
        }
    }

    private void releaseOverrideSfx() {
        if (smpsSession != null && smpsSession.suppressesSfxDuringOverride()) {
            sfxBlocked = false;
            sfxBlockHeldThroughFadeIn = false;
        }
    }

    /**
     * The restore has brought the saved song back. S3K lifts the block here;
     * S1 and S2 keep it until that song's fade in completes
     * (see {@code SmpsStatefulCommandPolicy.releasesSfxSuppressionAtRestore}).
     */
    private void releaseOrHoldOverrideSfxAtRestore() {
        if (smpsSession == null || !smpsSession.suppressesSfxDuringOverride()) {
            return;
        }
        if (smpsSession.releasesSfxSuppressionAtRestore()) {
            releaseOverrideSfx();
        } else if (sfxBlocked) {
            sfxBlockHeldThroughFadeIn = true;
        }
    }

    /**
     * Models S1's {@code DoFadeIn} and S2's {@code zUpdateFadeIn} clearing the
     * fade-in flag once the counter has run down (s1.sounddriver.asm:1650,
     * s2.sounddriver.asm:2740). The ROM clears it at the top of the service
     * that follows the last step, ahead of that service's request cycle, so
     * the engine evaluates it at each frame boundary and again at every SFX
     * admission: a request that arrives after the fade has completed is
     * admitted, whatever the flag said a frame earlier.
     */
    private void releaseSfxBlockWhenFadeInEnds() {
        if (!sfxBlocked || !sfxBlockHeldThroughFadeIn || smpsSession == null) {
            return;
        }
        if (!smpsSession.isMusicFadingIn()) {
            sfxBlocked = false;
            sfxBlockHeldThroughFadeIn = false;
        }
    }

    public void setPendingRestore(boolean pending) {
        assertOwnerBoundary();
        pendingRestore = pending;
    }

    private MusicSlot materializeMusic(MusicVoiceEntry music) {
        PresentationVoice voice = materializeVoice(music.voiceDescriptor());
        return new MusicSlot(
                music.musicId(), music.sourceDescriptor(), voice);
    }

    private SampleBackedVoice materializeSample(
            SampleVoiceDescriptor descriptor) {
        return (SampleBackedVoice) materializeVoice(descriptor);
    }

    private PresentationVoice materializeVoice(VoiceDescriptor descriptor) {
        PresentationVoice voice = null;
        try {
            voice = Objects.requireNonNull(
                    dependencyResolver.recreateVoice(descriptor),
                    "dependency resolver returned no voice");
            if (voice.voiceId() != descriptor.voiceId()
                    || voice.priority() != descriptor.priority()) {
                throw new IllegalStateException(
                        "dependency resolver changed voice identity");
            }
            if (descriptor instanceof SampleVoiceDescriptor
                    && !(voice instanceof SampleBackedVoice)) {
                throw new IllegalStateException(
                        "sample descriptor recreated as "
                                + voice.getClass().getName());
            }
            return voice;
        } catch (RuntimeException failure) {
            disposeUnpublishedVoice(voice, failure);
            throw failure;
        }
    }

    private void replaceMusic(MusicVoiceEntry entry) {
        MusicSlot music = materializeMusic(entry);
        boolean published = false;
        RuntimeException primaryFailure = null;
        try {
            applyMusicControls(music, speedShoesEnabled, speedMultiplier,
                    fmMuteMask, fmSoloMask, psgMuteMask, psgSoloMask);
            stopMusic();
            activeMusic = music;
            noteVoiceId(music.voice());
            published = true;
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!published) {
                disposeUnpublishedVoice(music.voice(), primaryFailure);
            }
        }
    }

    private void pushMusicOverride(MusicVoiceEntry entry) {
        if (activeMusic != null
                && activeMusic.musicId() != entry.musicId()
                && overrideCount == overrideStack.length) {
            throw new IllegalStateException(
                    "music override stack exceeds fixed capacity");
        }

        MusicSlot music = materializeMusic(entry);
        boolean published = false;
        RuntimeException primaryFailure = null;
        try {
            applyMusicControls(music, speedShoesEnabled, speedMultiplier,
                    fmMuteMask, fmSoloMask, psgMuteMask, psgSoloMask);
            if (activeMusic != null
                    && activeMusic.musicId() == music.musicId()) {
                stopVoicesAtomically(activeMusic.voice());
                activeMusic = music;
                noteVoiceId(music.voice());
                published = true;
                return;
            }
            if (activeMusic != null) {
                overrideStack[overrideCount++] = activeMusic;
            }
            activeMusic = music;
            if (music.voice() instanceof StreamedMusicVoice && overrideCount > 0) {
                sfxBlocked = true;
            }
            noteVoiceId(music.voice());
            published = true;
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!published) {
                disposeUnpublishedVoice(music.voice(), primaryFailure);
            }
        }
    }

    private boolean restoreMusicOverride() {
        if (overrideCount == 0) {
            pendingRestore = false;
            releaseOverrideSfx();
            return false;
        }
        MusicSlot restored = overrideStack[overrideCount - 1];
        PresentationVoice current =
                activeMusic == null ? null : activeMusic.voice();
        mutateVoicesAtomically(() -> {
            applyMusicControls(restored, speedShoesEnabled, speedMultiplier,
                    fmMuteMask, fmSoloMask, psgMuteMask, psgSoloMask);
            if (restored.voice() instanceof StreamedMusicVoice streamed) {
                streamed.beginOverrideRestore();
            }
            if (current != null) {
                current.stop();
            }
        }, restored.voice(), current);
        activeMusic = restored;
        overrideStack[--overrideCount] = null;
        pendingRestore = false;
        if (restored.voice() instanceof StreamedMusicVoice streamed) {
            sfxBlocked = overrideCount > 0 || !streamed.releasesSfxOnRestore();
            // Streamed restoration is paced by its own cursor fade, even
            // when a SMPS session is present to own SFX and host commands.
            sfxBlockHeldThroughFadeIn = false;
        }
        // The ROM does the whole resume inside the driver: zFadeInToPrevious
        // restores the saved tracks, attenuates and re-voices them and arms
        // the fade in (Sound/Z80 Sound Driver.asm:2725-2789). Reactivating the
        // slot here only brings the song back; the driver still owes it that
        // body, or it returns at full volume with stale voices.
        if (smpsSession != null && restored.voice() instanceof SmpsMusicHandle) {
            smpsSession.fadeInRestoredMusic();
            releaseOrHoldOverrideSfxAtRestore();
        }
        return true;
    }

    private void endMusicOverride(int musicId) {
        if (activeMusic != null && activeMusic.musicId() == musicId) {
            restoreMusicOverride();
            return;
        }
        for (int index = overrideCount - 1; index >= 0; index--) {
            if (overrideStack[index].musicId() != musicId) {
                continue;
            }
            stopVoicesAtomically(overrideStack[index].voice());
            int remaining = overrideCount - index - 1;
            if (remaining > 0) {
                System.arraycopy(overrideStack, index + 1, overrideStack,
                        index, remaining);
            }
            overrideStack[--overrideCount] = null;
            return;
        }
    }

    private void applyMusicControls(
            MusicSlot music,
            boolean targetSpeedShoes,
            int targetSpeedMultiplier,
            int targetFmMuteMask,
            int targetFmSoloMask,
            int targetPsgMuteMask,
            int targetPsgSoloMask) {
        if (music.voice() instanceof StreamedMusicVoice streamed) {
            streamed.setSpeedMultiplier(
                    targetSpeedShoes ? targetSpeedMultiplier : 1);
            return;
        }
    }

    private void disposeUnpublishedVoice(
            PresentationVoice voice, RuntimeException primaryFailure) {
        if (voice == null) {
            return;
        }
        try {
            voice.stop();
        } catch (RuntimeException disposalFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(disposalFailure);
            } else {
                throw disposalFailure;
            }
        }
    }

    /*
     * Registry structure is published only after every throwable preparation
     * step completes. The helpers below capture only live voices touched by a
     * command. SMPS voices use their dedicated identity-bearing command token;
     * ordinary rewind snapshots keep their callback-free recreation semantics.
     */
    private void mutateVoicesAtomically(
            Runnable mutation, PresentationVoice... candidates) {
        PresentationVoice[] voices =
                new PresentationVoice[candidates.length];
        Object[] rollbackStates = new Object[candidates.length];
        int count = 0;
        for (PresentationVoice candidate : candidates) {
            if (candidate == null || containsIdentity(voices, count, candidate)) {
                continue;
            }
            voices[count] = candidate;
            rollbackStates[count] =
                    captureVoiceMutationState(candidate);
            count++;
        }
        try {
            mutation.run();
        } catch (RuntimeException failure) {
            for (int index = count - 1; index >= 0; index--) {
                try {
                    rollbackVoiceMutation(
                            voices[index], rollbackStates[index]);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private void stopVoicesAtomically(PresentationVoice... voices) {
        mutateVoicesAtomically(() -> {
            for (PresentationVoice voice : voices) {
                if (voice != null) {
                    voice.stop();
                }
            }
        }, voices);
    }

    private static boolean containsIdentity(
            PresentationVoice[] voices, int count, PresentationVoice candidate) {
        for (int index = 0; index < count; index++) {
            if (voices[index] == candidate) {
                return true;
            }
        }
        return false;
    }

    private static Object captureVoiceMutationState(
            PresentationVoice voice) {
        if (voice instanceof SmpsMusicHandle) {
            return null;
        }
        return voice.snapshot();
    }

    private static void rollbackVoiceMutation(
            PresentationVoice voice, Object rollbackState) {
        if (voice instanceof SmpsMusicHandle && rollbackState == null) {
            return;
        }
        if (voice instanceof SampleBackedVoice sample
                && rollbackState
                instanceof PresentationVoiceSnapshot.Sample state) {
            sample.restore(state);
            return;
        }
        if (voice instanceof StreamedMusicVoice streamed
                && rollbackState instanceof PresentationVoiceSnapshot.Streamed state) {
            streamed.restoreMutation(state);
            return;
        }
        throw new IllegalStateException(
                "unsupported presentation voice rollback "
                        + voice.getClass().getName());
    }

    private void admitSampleSfx(SampleVoiceDescriptor descriptor) {
        releaseSfxBlockWhenFadeInEnds();
        if (sfxBlocked) {
            warnRejected(descriptor.voiceId(),
                    "sample SFX blocked at presentation boundary");
            return;
        }
        int replacement = -1;
        if (sampleSfxCount == sampleSfx.length) {
            int replacementPriority = Integer.MAX_VALUE;
            for (int index = 0; index < sampleSfxCount; index++) {
                int existingPriority = sampleSfx[index].priority();
                if (existingPriority < descriptor.priority()
                        && existingPriority < replacementPriority) {
                    replacement = index;
                    replacementPriority = existingPriority;
                }
            }
            if (replacement < 0) {
                warnRejected(descriptor.voiceId(),
                        "sample SFX capacity rejected voice "
                                + descriptor.voiceId());
                return;
            }
        }

        SampleBackedVoice voice = materializeSample(descriptor);
        boolean published = false;
        RuntimeException primaryFailure = null;
        try {
            if (replacement >= 0) {
                stopVoicesAtomically(sampleSfx[replacement]);
                int remaining = sampleSfxCount - replacement - 1;
                if (remaining > 0) {
                    System.arraycopy(sampleSfx, replacement + 1, sampleSfx,
                            replacement, remaining);
                }
                sampleSfx[--sampleSfxCount] = null;
            }
            insertSampleSorted(voice);
            noteVoiceId(voice);
            published = true;
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!published) {
                disposeUnpublishedVoice(voice, primaryFailure);
            }
        }
    }

    private void insertSampleSorted(SampleBackedVoice voice) {
        int insertion = sampleSfxCount;
        while (insertion > 0
                && sampleSfx[insertion - 1].voiceId() > voice.voiceId()) {
            sampleSfx[insertion] = sampleSfx[insertion - 1];
            insertion--;
        }
        sampleSfx[insertion] = voice;
        sampleSfxCount++;
    }

    private void replaceRawPcm(SampleVoiceDescriptor descriptor) {
        SampleBackedVoice voice = materializeSample(descriptor);
        boolean published = false;
        RuntimeException primaryFailure = null;
        try {
            if (rawPcm != null && rawPcm != voice) {
                stopVoicesAtomically(rawPcm);
            }
            rawPcm = voice;
            noteVoiceId(voice);
            published = true;
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!published) {
                disposeUnpublishedVoice(voice, primaryFailure);
            }
        }
    }

    /**
     * Presents {@code cmd_StopSEGA} to a driver-owned transport, or stops the
     * presentation voice, whichever is playing the chant.
     */
    private boolean stopRawPcmOrTransport() {
        if (smpsSession != null && smpsSession.segaPcmTransportActive()) {
            smpsSession.requestSegaPcmTransportStop();
            return true;
        }
        return stopRawPcm();
    }

    private boolean stopRawPcm() {
        if (rawPcm != null) {
            stopVoicesAtomically(rawPcm);
            rawPcm = null;
            return true;
        }
        return false;
    }

    private void stopMusic() {
        PresentationVoice[] voices =
                new PresentationVoice[overrideCount + 1];
        int voiceCount = 0;
        if (activeMusic != null) {
            voices[voiceCount++] = activeMusic.voice();
        }
        for (int index = 0; index < overrideCount; index++) {
            voices[voiceCount++] = overrideStack[index].voice();
        }
        int capturedVoiceCount = voiceCount;
        mutateVoicesAtomically(() -> {
            for (int index = 0; index < capturedVoiceCount; index++) {
                voices[index].stop();
            }
        }, voices);
        activeMusic = null;
        for (int index = 0; index < overrideCount; index++) {
            overrideStack[index] = null;
        }
        overrideCount = 0;
        pendingRestore = false;
        releaseOverrideSfx();
    }

    private void stopAllSfx() {
        PresentationVoice[] voices = allOwnedVoices();
        boolean stoppedRawPcm = rawPcm != null;
        mutateVoicesAtomically(() -> {
            if (rawPcm != null) {
                rawPcm.stop();
            }
            for (int index = 0; index < sampleSfxCount; index++) {
                sampleSfx[index].stop();
            }
        }, voices);
        rawPcm = null;
        for (int index = 0; index < sampleSfxCount; index++) {
            sampleSfx[index] = null;
        }
        sampleSfxCount = 0;
        if (stoppedRawPcm) {
            sfxInstantiation.observeLifecycle(
                    SmpsDriverServiceObserver.LifecycleEvent.pcm(
                            SmpsDriverServiceObserver.LifecycleKind.SEGA_PCM_LEAVE));
        }
    }

    private PresentationVoice[] allOwnedVoices() {
        PresentationVoice[] voices =
                new PresentationVoice[overrideCount + sampleSfxCount + 4];
        int count = copyOwnedVoices(voices);
        return java.util.Arrays.copyOf(voices, count);
    }

    private int copyOwnedVoices(PresentationVoice[] voices) {
        int count = 0;
        if (activeMusic != null) {
            voices[count++] = activeMusic.voice();
        }
        for (int index = 0; index < overrideCount; index++) {
            voices[count++] = overrideStack[index].voice();
        }
        if (rawPcm != null) {
            voices[count++] = rawPcm;
        }
        for (int index = 0; index < sampleSfxCount; index++) {
            voices[count++] = sampleSfx[index];
        }
        return count;
    }

    private void fadeMusic(int steps, int delay) {
        if (activeMusic != null && activeMusic.voice() instanceof StreamedMusicVoice streamed) {
            mutateVoicesAtomically(() -> streamed.fadeOut(steps, delay), streamed);
        }
    }

    private void setVoiceGain(long voiceId, int gainQ16) {
        PresentationVoice voice = voiceById(voiceId);
        if (voice instanceof SampleBackedVoice sample) {
            PresentationVoiceSnapshot.Sample snapshot =
                    (PresentationVoiceSnapshot.Sample) sample.snapshot();
            mutateVoicesAtomically(() -> sample.restore(
                    new PresentationVoiceSnapshot.Sample(
                            snapshot.voiceId(), snapshot.priority(),
                            snapshot.assetId(), snapshot.musicId(),
                            snapshot.sourceDescriptor(),
                            snapshot.sourcePositionQ32(),
                            snapshot.sourceStepQ32(), gainQ16,
                            snapshot.looping(), snapshot.stopped())), sample);
        }
    }

    private void setVoicePitch(long voiceId, long sourceStepQ32) {
        PresentationVoice voice = voiceById(voiceId);
        if (voice instanceof SampleBackedVoice sample) {
            PresentationVoiceSnapshot.Sample snapshot =
                    (PresentationVoiceSnapshot.Sample) sample.snapshot();
            mutateVoicesAtomically(() -> sample.restore(
                    new PresentationVoiceSnapshot.Sample(
                            snapshot.voiceId(), snapshot.priority(),
                            snapshot.assetId(), snapshot.musicId(),
                            snapshot.sourceDescriptor(),
                            snapshot.sourcePositionQ32(), sourceStepQ32,
                            snapshot.gainQ16(), snapshot.looping(),
                            snapshot.stopped())), sample);
        }
    }

    private void setSpeedShoes(boolean enabled) {
        updateActiveMusicSpeed(enabled, speedMultiplier);
        speedShoesEnabled = enabled;
    }

    private void setSpeedMultiplier(int multiplier) {
        updateActiveMusicSpeed(speedShoesEnabled, multiplier);
        speedMultiplier = multiplier;
    }

    private void updateActiveMusicSpeed(
            boolean targetSpeedShoes, int targetSpeedMultiplier) {
        if (activeMusic != null
                && activeMusic.voice() instanceof StreamedMusicVoice streamed) {
            mutateVoicesAtomically(() -> streamed.setSpeedMultiplier(
                    targetSpeedShoes ? targetSpeedMultiplier : 1), streamed);
        }
        // Session-owned SMPS speed controls are applied before metadata.
    }

    private void changeMusicTempo(int dividingTiming) {
        // Session-owned SMPS tempo controls are applied before metadata.
    }

    private void toggleMute(ChannelType type, int channel) {
        int bit = channelBit(type, channel);
        int targetFmMuteMask = fmMuteMask;
        int targetPsgMuteMask = psgMuteMask;
        if (type == ChannelType.PSG) {
            targetPsgMuteMask ^= bit;
        } else {
            targetFmMuteMask ^= bit;
        }
        applyDriverControlsAtomically(targetFmMuteMask, fmSoloMask,
                targetPsgMuteMask, psgSoloMask);
        fmMuteMask = targetFmMuteMask;
        psgMuteMask = targetPsgMuteMask;
    }

    private void toggleSolo(ChannelType type, int channel) {
        int bit = channelBit(type, channel);
        int targetFmSoloMask = fmSoloMask;
        int targetPsgSoloMask = psgSoloMask;
        if (type == ChannelType.PSG) {
            targetPsgSoloMask ^= bit;
        } else {
            targetFmSoloMask ^= bit;
        }
        applyDriverControlsAtomically(fmMuteMask, targetFmSoloMask,
                psgMuteMask, targetPsgSoloMask);
        fmSoloMask = targetFmSoloMask;
        psgSoloMask = targetPsgSoloMask;
    }

    private static int channelBit(ChannelType type, int channel) {
        int limit = type == ChannelType.PSG ? 4 : 6;
        if (channel < 0 || channel >= limit) {
            throw new IllegalArgumentException("channel outside " + type + " range");
        }
        return 1 << channel;
    }

    private void applyDriverControlsAtomically(
            int targetFmMuteMask,
            int targetFmSoloMask,
            int targetPsgMuteMask,
            int targetPsgSoloMask) {
        if (smpsSession != null) {
            applySessionMasks(targetFmMuteMask, targetFmSoloMask,
                    targetPsgMuteMask, targetPsgSoloMask);
            return;
        }
        // A registry without a session has no SMPS physical controls.
    }

    private void applySessionMasks(
            int fmMute,
            int fmSolo,
            int psgMute,
            int psgSolo) {
        boolean anySolo = fmSolo != 0 || psgSolo != 0;
        int effectiveFm = fmMute;
        int effectivePsg = psgMute;
        if (anySolo) {
            effectiveFm |= (~fmSolo) & 0x3F;
            effectivePsg |= (~psgSolo) & 0x0F;
        }
        smpsSession.applyChannelMasks(effectiveFm, effectivePsg);
    }

    private void deferRemovalInternal(long voiceId) {
        if (completionSweepRequired) {
            return;
        }
        for (int index = 0; index < deferredRemovalCount; index++) {
            if (deferredRemovals[index] == voiceId) {
                return;
            }
        }
        if (deferredRemovalCount < deferredRemovals.length) {
            deferredRemovals[deferredRemovalCount++] = voiceId;
        } else {
            completionSweepRequired = true;
        }
    }

    private void sweepCompletedAndDeferredVoices() {
        for (int index = orderedVoiceCount - 1; index >= 0; index--) {
            PresentationVoice voice = orderedVoices[index];
            if (voice.isComplete() || isDeferred(voice.voiceId())) {
                removeVoiceById(voice.voiceId());
            }
        }
    }

    private boolean isDeferred(long voiceId) {
        for (int index = 0; index < deferredRemovalCount; index++) {
            if (deferredRemovals[index] == voiceId) {
                return true;
            }
        }
        return false;
    }

    private void removeVoiceById(long voiceId) {
        if (activeMusic != null && activeMusic.voice().voiceId() == voiceId) {
            if (activeMusic.voice() instanceof StreamedMusicVoice streamed) {
                streamed.stop();
            }
            activeMusic = null;
            return;
        }
        if (rawPcm != null && rawPcm.voiceId() == voiceId) {
            rawPcm = null;
            return;
        }
        for (int index = 0; index < sampleSfxCount; index++) {
            if (sampleSfx[index].voiceId() != voiceId) {
                continue;
            }
            int remaining = sampleSfxCount - index - 1;
            if (remaining > 0) {
                System.arraycopy(sampleSfx, index + 1, sampleSfx,
                        index, remaining);
            }
            sampleSfx[--sampleSfxCount] = null;
            return;
        }
    }

    private void rebuildOrderedVoices() {
        orderedVoiceCount = 0;
        if (activeMusic != null
                && (smpsSession == null
                        || activeMusic.voice()
                                instanceof PcmPresentationVoice)) {
            orderedVoices[orderedVoiceCount++] = activeMusic.voice();
        }
        if (rawPcm != null) {
            orderedVoices[orderedVoiceCount++] = rawPcm;
        }
        for (int index = 0; index < sampleSfxCount; index++) {
            orderedVoices[orderedVoiceCount++] = sampleSfx[index];
        }
        for (int index = orderedVoiceCount; index < orderedVoices.length; index++) {
            orderedVoices[index] = null;
        }
    }

    private boolean hasUnownedSmps(
            AudioPresentationCommand command) {
        if (command instanceof AddSmpsSfx) {
            return smpsSession == null;
        }
        MusicVoiceEntry music = switch (command) {
            case ReplaceMusic replace -> replace.music();
            case PushMusicOverride push -> push.music();
            default -> null;
        };
        return music != null
                && music.voiceDescriptor() instanceof SmpsVoiceDescriptor smps
                && (smpsSession == null || smps.activation() == null);
    }

    private void validateAuthoritativeSnapshot(
            AudioPresentationSnapshot snapshot) {
        if (smpsSession == null) {
            if (snapshot.smpsSession() != null) {
                throw new IllegalArgumentException(
                        "registry without a session cannot restore SMPS state");
            }
            return;
        }
        if (snapshot.smpsSession() == null
                || snapshot.smpsLogical() == null) {
            throw new IllegalArgumentException(
                    "session-backed restore requires the SMPS snapshot pair");
        }
    }

    private void stopAndRemoveAllVoices() {
        PresentationVoice[] voices = allOwnedVoices();
        stopVoicesAtomically(voices);
        activeMusic = null;
        for (int index = 0; index < overrideCount; index++) {
            overrideStack[index] = null;
        }
        overrideCount = 0;
        rawPcm = null;
        for (int index = 0; index < sampleSfxCount; index++) {
            sampleSfx[index] = null;
        }
        sampleSfxCount = 0;
        rebuildOrderedVoices();
    }

    private void addMusicSnapshot(
            List<PresentationVoiceSnapshot> voices, MusicSlot music) {
        if (music != null) {
            addVoiceSnapshot(voices, music.voice(), music);
        }
    }

    private void addVoiceSnapshot(
            List<PresentationVoiceSnapshot> voices,
            PresentationVoice voice,
            MusicSlot music) {
        if (!(voice instanceof SampleBackedVoice || voice instanceof StreamedMusicVoice)
                || containsVoiceId(voices, voice.voiceId())) {
            return;
        }
        PresentationVoiceSnapshot snapshot = voice.snapshot();
        if (music != null
                && snapshot instanceof PresentationVoiceSnapshot.Sample sample) {
            snapshot = new PresentationVoiceSnapshot.Sample(
                    sample.voiceId(), sample.priority(), sample.assetId(),
                    music.musicId(), music.sourceDescriptor(),
                    sample.sourcePositionQ32(), sample.sourceStepQ32(),
                    sample.gainQ16(), sample.looping(), sample.stopped());
        }
        voices.add(snapshot);
    }

    private static boolean containsVoiceId(
            List<PresentationVoiceSnapshot> voices, long voiceId) {
        for (PresentationVoiceSnapshot voice : voices) {
            if (snapshotVoiceId(voice) == voiceId) {
                return true;
            }
        }
        return false;
    }

    private static long snapshotVoiceId(PresentationVoiceSnapshot snapshot) {
        return switch (snapshot) {
            case PresentationVoiceSnapshot.Sample sample -> sample.voiceId();
            case PresentationVoiceSnapshot.Streamed streamed -> streamed.voiceId();
        };
    }

    private static AudioPresentationSnapshot.MusicSlotSnapshot slotSnapshot(
            MusicSlot music) {
        return new AudioPresentationSnapshot.MusicSlotSnapshot(
                music.musicId(), music.sourceDescriptor(),
                music.voice().voiceId());
    }

    private PresentationVoice recreate(
            PresentationVoiceSnapshot snapshot,
            AudioPresentationDependencyResolver resolver) {
        if (snapshot instanceof PresentationVoiceSnapshot.Streamed streamed) {
            return Objects.requireNonNull(resolver.recreateStreamed(streamed),
                    "resolver returned no streamed voice");
        }
        PresentationVoiceSnapshot.Sample sample =
                (PresentationVoiceSnapshot.Sample) snapshot;
        DecodedPcm pcm = Objects.requireNonNull(
                resolver.resolvePcm(sample.assetId()),
                "resolver returned no PCM for " + sample.assetId());
        return SampleBackedVoice.restore(sample, pcm);
    }

    private MusicSlot restoreMusicSlot(
            AudioPresentationSnapshot.MusicSlotSnapshot slot,
            PresentationVoice[] recreated,
            boolean[] claimed) {
        if (slot == null) {
            return null;
        }
        boolean hasPresentationVoice = java.util.Arrays.stream(recreated)
                .anyMatch(voice -> voice.voiceId() == slot.voiceId());
        if (!hasPresentationVoice && smpsSession != null && isSmpsMusicRoute(
                slot.sourceDescriptor().route())) {
            return new MusicSlot(
                    slot.musicId(), slot.sourceDescriptor(),
                    new SmpsMusicHandle(slot.voiceId()));
        }
        PresentationVoice voice =
                claimVoice(slot.voiceId(), recreated, claimed);
        return new MusicSlot(
                slot.musicId(), slot.sourceDescriptor(), voice);
    }

    private static boolean isSmpsMusicRoute(
            com.openggf.audio.rewind.AudioSourceDescriptor.Route route) {
        return route == com.openggf.audio.rewind.AudioSourceDescriptor.Route
                        .BASE_MUSIC_ID
                || route == com.openggf.audio.rewind.AudioSourceDescriptor.Route
                        .DONOR_MUSIC_ID;
    }

    private static PresentationVoice claimVoice(
            long voiceId,
            PresentationVoice[] recreated,
            boolean[] claimed) {
        for (int index = 0; index < recreated.length; index++) {
            if (recreated[index].voiceId() == voiceId) {
                if (claimed[index]) {
                    throw new IllegalArgumentException(
                            "voice referenced by more than one ledger slot: "
                                    + voiceId);
                }
                claimed[index] = true;
                return recreated[index];
            }
        }
        throw new IllegalArgumentException(
                "ledger slot references missing voice " + voiceId);
    }

    private PresentationVoice voiceById(long voiceId) {
        if (activeMusic != null && activeMusic.voice().voiceId() == voiceId) {
            return activeMusic.voice();
        }
        for (int index = 0; index < overrideCount; index++) {
            if (overrideStack[index].voice().voiceId() == voiceId) {
                return overrideStack[index].voice();
            }
        }
        if (rawPcm != null && rawPcm.voiceId() == voiceId) {
            return rawPcm;
        }
        for (int index = 0; index < sampleSfxCount; index++) {
            if (sampleSfx[index].voiceId() == voiceId) {
                return sampleSfx[index];
            }
        }
        return null;
    }

    private void noteVoiceId(PresentationVoice voice) {
        nextVoiceId = Math.max(nextVoiceId, voice.voiceId() + 1);
    }

    private void warnRejected(long voiceId, String warning) {
        for (int index = 0; index < warnedRejectionCount; index++) {
            if (warnedRejectionVoiceIds[index] == voiceId) {
                return;
            }
        }
        if (warnedRejectionCount < warnedRejectionVoiceIds.length) {
            warnedRejectionVoiceIds[warnedRejectionCount++] = voiceId;
        } else {
            warnedRejectionVoiceIds[warnedRejectionCursor++] = voiceId;
            if (warnedRejectionCursor == warnedRejectionVoiceIds.length) {
                warnedRejectionCursor = 0;
            }
        }
        try {
            warningConsumer.accept(warning);
        } catch (RuntimeException ignored) {
            // Diagnostics cannot turn an accepted/rejected command into a
            // retained command whose warning-side effects are not reversible.
        }
    }

    private void assertOwnerBoundary() {
        assertOwnerThread();
        if (rendering) {
            throw new IllegalStateException(
                    "audio registry mutation is forbidden during rendering");
        }
    }

    private RegistryLiveMutation requireLiveMutation(
            LiveMutationToken token) {
        if (!(token instanceof AudioVoiceRegistry.RegistryLiveMutation state)
                || state.owner != this) {
            throw new IllegalArgumentException(
                    "registry mutation token belongs to another registry");
        }
        return state;
    }

    private static void requireUnconsumed(
            RegistryLiveMutation state) {
        if (state.consumed) {
            throw new IllegalStateException(
                    "registry mutation token has already been consumed");
        }
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "audio registry accessed outside its owner thread");
        }
    }

    private static AudioPresentationDependencyResolver
            rejectingDependencyResolver() {
        return new AudioPresentationDependencyResolver() {
            @Override
            public DecodedPcm resolvePcm(String assetId) {
                throw new IllegalStateException(
                        "no presentation PCM resolver for " + assetId);
            }

        };
    }
    public void requestMusicOverrideRestore() {
        assertOwnerThread();
        pendingRestore = true;
    }





}
