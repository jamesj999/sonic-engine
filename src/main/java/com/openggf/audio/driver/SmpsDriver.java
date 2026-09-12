package com.openggf.audio.driver;

import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsLogicalWriteTarget;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.audio.smps.SmpsSequencerHost;
import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class SmpsDriver implements SmpsLogicalWriteTarget, SmpsSequencerHost {
    @FunctionalInterface
    public interface DirectPcmRenderer {
        void render(short[] target, int frameOffset, int stereoFrames);
    }
    public enum ReadMode {
        SAMPLE_ACCURATE,
        HYBRID
    }

    private static final int MIN_BATCH_SAMPLES = 32;

    private enum DetachedLogicalWriteTarget implements SmpsLogicalWriteTarget {
        INSTANCE;

        @Override public void writeFm(Object source, int port, int register, int value) { }
        @Override public void writePsg(Object source, int value) { }
        @Override public void setInstrument(Object source, int channel,
                byte[] voice) { }
        @Override public void playDac(Object source, int note) { }
        @Override public void stopDac(Object source) { }
        @Override public void setDacData(DacData data) { }
        @Override public void setFmMute(int channel, boolean mute) { }
        @Override public void setPsgMute(int channel, boolean mute) { }
        @Override public void setDacInterpolate(boolean interpolate) { }
        @Override public void silenceAll() { }
        @Override public void selectDac(SmpsSourceDescriptor source, DacData data) { }
    }

    private final SmpsLogicalWriteTarget synthesizer;

    private final Object sequencersLock = new Object();
    private final List<SmpsSequencer> sequencers = new ArrayList<>();
    private final Set<SmpsSequencer> sfxSequencers = new HashSet<>();
    private final Map<Integer, SmpsSequencer> sfxSequencersById =
            new HashMap<>();
    private final SmpsSequencer[] fmSfxClaims = new SmpsSequencer[6];
    private final SmpsSequencer[] dacSfxClaims = new SmpsSequencer[6];
    private final SmpsSequencer[] psgSfxClaims = new SmpsSequencer[4];
    /** Diagnostic-only state; deliberately absent from rewind snapshots. */
    private final IdentityHashMap<SmpsSequencer, Long> sfxAdmissionOrdinals = new IdentityHashMap<>();
    private final Map<ConflictKey, SfxContentionObserver.Source> pendingConflictOwners = new HashMap<>();
    /**
     * {@code zFadeDelay} (1C0Eh) and {@code zFadeDelayTimeout} (1C0Fh), the
     * driver's own fade delay pair. {@code zFadeOutMusic} sets both to 6 and
     * {@code zFadeInToPrevious} sets both to 2, neither caring whether a song
     * is loaded (skdisasm Sound/Z80 Sound Driver.asm:2306-2312, :2784-2789).
     * The fade steppers decrement the first and reload it from the second
     * (:2337-2346, :2405-2414).
     */
    private int fadeDelay;
    private int fadeDelayTimeout;
    /** zFadeOutTimeout (1C0Dh) and zFadeInTimeout (1C29h). */
    private int fadeOutTimeout;
    private int fadeInTimeout;
    /**
     * Set once a request arms a fade through the driver-owned shape. With no
     * song loaded there is no config to consult, and the ROM's stepper runs
     * from zUpdateMusic regardless of whether one is (D:2331-2346).
     */
    private boolean driverOwnedFade;

    /** zMusicNumber, zSFXNumber0 and zSFXNumber1 (D:698-701). */
    private static final int SOUND_QUEUE_SLOTS = 3;

    /** Requests handed to the running service, consumed at the ROM's point. */
    private final List<Runnable> pendingServiceRequests = new ArrayList<>();

    private final SmpsSequencer[] fmLocks = new SmpsSequencer[6];
    private final SmpsSequencer[] psgLocks = new SmpsSequencer[4];
    private final Map<Object, Integer> psgLatches = new HashMap<>();
    private SmpsSequencer.Region region = SmpsSequencer.Region.NTSC;

    private final List<SmpsSequencer> pendingRemovals = new ArrayList<>();

    // Reusable buffer for stopAllSfx() to avoid per-call ArrayList allocation
    private final List<SmpsSequencer> sfxRemovalBuffer = new ArrayList<>();

    private ReadMode readMode = ReadMode.HYBRID;
    private int hybridChunkCountForTesting;

    // --- Continuous SFX state (Z80: zContinuousSFX, zContinuousSFXFlag, zContSFXLoopCnt) ---
    // S3K continuous SFX (0xBC+) loop via the 0xFC coord flag (cfLoopContinuousSFX).
    // When the same continuous SFX is re-triggered, the flag is set to extend playback
    // instead of restarting. The loop counter tracks how many tracks still need to hit
    // their loop point before the flag is consumed.
    private int continuousSfxId;
    private boolean continuousSfxFlag;
    private int contSfxLoopCnt;

    /**
     * S1 {@code v_special_voice_ptr}: the voice bank of the most recently
     * dispatched special SFX. {@code Sound_PlaySpecial} is its only writer
     * (docs/s1disasm/s1.sounddriver.asm:1132), so it outlives the special SFX
     * that installed it and is cleared only when the driver RAM globals are
     * wiped -- {@code InitMusicPlayback} (:1498-1502) and {@code StopAllSound}
     * (:1468-1478). The shipped {@code FixBugs = 0} {@code SendVoiceTL} reads
     * this pointer for every normal SFX track (:2391-2398), so it must persist
     * exactly as the ROM's global does rather than track a live sequencer.
     */
    private AbstractSmpsData s1SpecialVoicePointer;
    /** Shared Z80 PAL cadence byte: zPALUpdTick / zPalDblUpdCounter. */
    private int palUpdateCounter = 5;
    /**
     * Whether a DAC sample has been queued since the idle loop last looked.
     *
     * <p>Stands for the ROM's {@code zDACIndex} going non-zero
     * (skdisasm Sound/Z80 Sound Driver.asm:2903). The DAC idle loop reads the
     * index on its next pass and enables the DAC (:4269-4276), so the caller
     * that owns the physical write partition consumes this at the service
     * boundary rather than inside the update that set it.</p>
     */
    private boolean dacQueuedSinceIdleLoopPass;
    private long nextSfxAdmissionOrdinal;
    private SfxContentionObserver sfxContentionObserver = SfxContentionObserver.NONE;
    /** Diagnostic-only state; deliberately absent from rewind snapshots. */
    private long nextServiceOrdinal;
    /** Diagnostic-only sequencer identities; deliberately absent from snapshots. */
    private long nextServiceSequencerOrdinal;
    private IdentityHashMap<SmpsSequencer, Long> serviceSequencerOrdinals;
    private SmpsDriverServiceObserver serviceObserver =
            SmpsDriverServiceObserver.NONE;
    private SmpsDriverServiceObserver.DriverIdentity diagnosticIdentity =
            SmpsDriverServiceObserver.DriverIdentity.unspecified();

    /**
     * Exact, identity-bearing rollback state for one live presentation command.
     * This is intentionally separate from {@link SmpsDriverSnapshot}, whose
     * rewind contract recreates sequencers and omits process-local callbacks.
     */
    public static final class LiveCommandMutationToken {
        private final SmpsDriver owner;
        private final SmpsSequencer[] sequencers;
        private final SmpsSequencer.LiveCommandMutationToken[] sequencerStates;
        private final SmpsSequencer[] sfxSequencers;
        private final SmpsSequencer[] admissionSequencers;
        private final long[] admissionOrdinals;
        private final SmpsSequencer[] fmLocks;
        private final SmpsSequencer[] psgLocks;
        private final Object[] psgLatchSources;
        private final int[] psgLatchChannels;
        private final SmpsSequencer[] pendingRemovals;
        private final SmpsSequencer[] sfxRemovalBuffer;
        private final SmpsSequencer.Region region;
        private final ReadMode readMode;
        private final int hybridChunkCountForTesting;
        private final int continuousSfxId;
        private final boolean continuousSfxFlag;
        private final int contSfxLoopCnt;
        private final int palUpdateCounter;
        private final AbstractSmpsData s1SpecialVoicePointer;

        private LiveCommandMutationToken(
                SmpsDriver owner,
                SmpsSequencer[] sequencers,
                SmpsSequencer.LiveCommandMutationToken[] sequencerStates,
                SmpsSequencer[] sfxSequencers,
                SmpsSequencer[] admissionSequencers,
                long[] admissionOrdinals,
                SmpsSequencer[] fmLocks,
                SmpsSequencer[] psgLocks,
                Object[] psgLatchSources,
                int[] psgLatchChannels,
                SmpsSequencer[] pendingRemovals,
                SmpsSequencer[] sfxRemovalBuffer,
                SmpsSequencer.Region region,
                ReadMode readMode,
                int hybridChunkCountForTesting,
                int continuousSfxId,
                boolean continuousSfxFlag,
                int contSfxLoopCnt,
                int palUpdateCounter,
                AbstractSmpsData s1SpecialVoicePointer) {
            this.owner = owner;
            this.sequencers = sequencers;
            this.sequencerStates = sequencerStates;
            this.sfxSequencers = sfxSequencers;
            this.admissionSequencers = admissionSequencers;
            this.admissionOrdinals = admissionOrdinals;
            this.fmLocks = fmLocks;
            this.psgLocks = psgLocks;
            this.psgLatchSources = psgLatchSources;
            this.psgLatchChannels = psgLatchChannels;
            this.pendingRemovals = pendingRemovals;
            this.sfxRemovalBuffer = sfxRemovalBuffer;
            this.region = region;
            this.readMode = readMode;
            this.hybridChunkCountForTesting = hybridChunkCountForTesting;
            this.continuousSfxId = continuousSfxId;
            this.continuousSfxFlag = continuousSfxFlag;
            this.contSfxLoopCnt = contSfxLoopCnt;
            this.palUpdateCounter = palUpdateCounter;
            this.s1SpecialVoicePointer = s1SpecialVoicePointer;
        }
    }

    static final class SfxAdmissionMutationState {
        private final boolean continuousOnly;
        private final SmpsSequencer[] affected;
        private final SmpsSequencer.LiveCommandMutationToken[] sequencerStates;
        private final int[] positions;
        private final boolean[] sfxMembers;
        private final boolean[] pendingRemovalMembers;
        private final boolean[] removalBufferMembers;
        private final boolean[] admissionOrdinalPresent;
        private final long[] admissionOrdinals;
        private final boolean[] serviceOrdinalPresent;
        private final long[] serviceOrdinals;
        private final SmpsSequencer[] fmLocks;
        private final SmpsSequencer[] psgLocks;
        private final boolean[] psgLatchPresent;
        private final int[] psgLatchChannels;
        private final SmpsSequencer.Track[] musicOverrideTracks;
        private final boolean[] musicOverrides;
        private final ConflictKey[] conflictKeys;
        private final SfxContentionObserver.Source[] conflictSources;
        private final long nextAdmissionOrdinal;
        private final long nextServiceOrdinal;
        private final long nextServiceSequencerOrdinal;
        private final int continuousSfxId;
        private final boolean continuousSfxFlag;
        private final int continuousLoopCount;

        private SfxAdmissionMutationState(
                SmpsSequencer[] affected,
                SmpsSequencer.LiveCommandMutationToken[] sequencerStates,
                int[] positions, boolean[] sfxMembers,
                boolean[] pendingRemovalMembers,
                boolean[] removalBufferMembers,
                boolean[] admissionOrdinalPresent, long[] admissionOrdinals,
                boolean[] serviceOrdinalPresent, long[] serviceOrdinals,
                SmpsSequencer[] fmLocks, SmpsSequencer[] psgLocks,
                boolean[] psgLatchPresent, int[] psgLatchChannels,
                SmpsSequencer.Track[] musicOverrideTracks,
                boolean[] musicOverrides,
                ConflictKey[] conflictKeys,
                SfxContentionObserver.Source[] conflictSources,
                long nextAdmissionOrdinal, long nextServiceOrdinal,
                long nextServiceSequencerOrdinal,
                int continuousSfxId, boolean continuousSfxFlag,
                int continuousLoopCount) {
            this.continuousOnly = false;
            this.affected = affected;
            this.sequencerStates = sequencerStates;
            this.positions = positions;
            this.sfxMembers = sfxMembers;
            this.pendingRemovalMembers = pendingRemovalMembers;
            this.removalBufferMembers = removalBufferMembers;
            this.admissionOrdinalPresent = admissionOrdinalPresent;
            this.admissionOrdinals = admissionOrdinals;
            this.serviceOrdinalPresent = serviceOrdinalPresent;
            this.serviceOrdinals = serviceOrdinals;
            this.fmLocks = fmLocks;
            this.psgLocks = psgLocks;
            this.psgLatchPresent = psgLatchPresent;
            this.psgLatchChannels = psgLatchChannels;
            this.musicOverrideTracks = musicOverrideTracks;
            this.musicOverrides = musicOverrides;
            this.conflictKeys = conflictKeys;
            this.conflictSources = conflictSources;
            this.nextAdmissionOrdinal = nextAdmissionOrdinal;
            this.nextServiceOrdinal = nextServiceOrdinal;
            this.nextServiceSequencerOrdinal = nextServiceSequencerOrdinal;
            this.continuousSfxId = continuousSfxId;
            this.continuousSfxFlag = continuousSfxFlag;
            this.continuousLoopCount = continuousLoopCount;
        }

        private SfxAdmissionMutationState(
                long nextAdmissionOrdinal,
                long nextServiceOrdinal,
                long nextServiceSequencerOrdinal,
                int continuousSfxId,
                boolean continuousSfxFlag,
                int continuousLoopCount) {
            this.continuousOnly = true;
            this.affected = null;
            this.sequencerStates = null;
            this.positions = null;
            this.sfxMembers = null;
            this.pendingRemovalMembers = null;
            this.removalBufferMembers = null;
            this.admissionOrdinalPresent = null;
            this.admissionOrdinals = null;
            this.serviceOrdinalPresent = null;
            this.serviceOrdinals = null;
            this.fmLocks = null;
            this.psgLocks = null;
            this.psgLatchPresent = null;
            this.psgLatchChannels = null;
            this.musicOverrideTracks = null;
            this.musicOverrides = null;
            this.conflictKeys = null;
            this.conflictSources = null;
            this.nextAdmissionOrdinal = nextAdmissionOrdinal;
            this.nextServiceOrdinal = nextServiceOrdinal;
            this.nextServiceSequencerOrdinal = nextServiceSequencerOrdinal;
            this.continuousSfxId = continuousSfxId;
            this.continuousSfxFlag = continuousSfxFlag;
            this.continuousLoopCount = continuousLoopCount;
        }
    }

    public SmpsDriver() {
        this(DetachedLogicalWriteTarget.INSTANCE);
    }

    public SmpsDriver(double outputSampleRate) {
        this();
        if (!Double.isFinite(outputSampleRate)
                || outputSampleRate <= 0.0) {
            throw new IllegalArgumentException(
                    "outputSampleRate must be positive and finite");
        }
    }

    private SmpsDriver(SmpsLogicalWriteTarget synthesizer) {
        this.synthesizer = Objects.requireNonNull(
                synthesizer, "synthesizer");
    }

    /**
     * Composition-root seam for the one session-owned logical driver. This is
     * the sole construction path which does not allocate a private chip pair.
     */
    public static SmpsDriver createSessionDriver(
            SmpsDriverSessionAccess sessionAccess) {
        return new SmpsDriver(
                Objects.requireNonNull(sessionAccess, "sessionAccess"));
    }

    @Override
    public void setDacData(DacData data) {
        synthesizer.setDacData(data);
    }

    @Override
    public void selectDac(SmpsSourceDescriptor source, DacData data) {
        synthesizer.selectDac(Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(data, "data"));
    }

    public void setFmMute(int channel, boolean mute) {
        synthesizer.setFmMute(channel, mute);
    }

    public void setPsgMute(int channel, boolean mute) {
        synthesizer.setPsgMute(channel, mute);
    }

    public void setDacInterpolate(boolean interpolate) {
        synthesizer.setDacInterpolate(interpolate);
    }

    @Override
    public void silenceAll() {
        synthesizer.silenceAll();
    }

    public void forceSilenceChannel(int channelId) {
        if (synthesizer instanceof SmpsDriverSessionAccess access) {
            access.forceSilenceFmChannel(channelId);
        }
    }

    @Override
    public boolean fadeOutCompletesWithGlobalStop() {
        return synthesizer instanceof SmpsDriverSessionAccess access
                && access.fadeOutCompletesWithGlobalStop();
    }

    /** Installs the disabled-by-default complete-service diagnostic observer. */
    public void setServiceObserver(SmpsDriverServiceObserver observer) {
        serviceObserver = Objects.requireNonNull(observer, "observer");
        if (observer == SmpsDriverServiceObserver.NONE) {
            serviceSequencerOrdinals = null;
        }
    }

    /** Returns the installed diagnostic observer. */
    public SmpsDriverServiceObserver serviceObserver() {
        return serviceObserver;
    }

    public void setDiagnosticIdentity(
            SmpsDriverServiceObserver.DriverIdentity identity) {
        diagnosticIdentity = Objects.requireNonNull(identity, "identity");
    }

    public SmpsDriverServiceObserver.DriverIdentity diagnosticIdentity() {
        return diagnosticIdentity;
    }

    /** Begins one actual semantic SMPS sequencer update. */
    public SmpsDriverServiceObserver.ServiceEvent beginSequencerService(
            SmpsSequencer sequencer,
            SmpsDriverServiceObserver.ServiceKind kind) {
        if (serviceObserver == SmpsDriverServiceObserver.NONE) {
            return null;
        }
        if (serviceSequencerOrdinals == null) {
            serviceSequencerOrdinals = new IdentityHashMap<>();
        }
        long sequencerOrdinal = serviceSequencerOrdinals.computeIfAbsent(
                Objects.requireNonNull(sequencer, "sequencer"),
                ignored -> nextServiceSequencerOrdinal++);
        SmpsDriverServiceObserver.ServiceEvent event =
                new SmpsDriverServiceObserver.ServiceEvent(
                        nextServiceOrdinal++, diagnosticIdentity,
                        new SmpsDriverServiceObserver.SequencerIdentity(
                                sequencerOrdinal,
                                sequencer.getSourceDescriptor(),
                                sequencer.isSfx()),
                        Objects.requireNonNull(kind, "kind"));
        serviceObserver.onServiceBegin(event);
        return event;
    }

    /** Completes one actual semantic SMPS sequencer update. */
    public void endSequencerService(
            SmpsDriverServiceObserver.ServiceEvent event) {
        if (event != null) {
            serviceObserver.onServiceEnd(event, captureSnapshot());
        }
    }

    private void forgetSequencerServiceIdentity(SmpsSequencer sequencer) {
        if (serviceSequencerOrdinals != null) {
            serviceSequencerOrdinals.remove(sequencer);
        }
    }

    int trackedServiceSequencerCountForTesting() {
        return serviceSequencerOrdinals == null
                ? 0 : serviceSequencerOrdinals.size();
    }

    long nextServiceSequencerOrdinalForTesting() {
        return nextServiceSequencerOrdinal;
    }

    /** Reports a completed out-of-service lifecycle mutation. */
    public void observeLifecycle(
            SmpsDriverServiceObserver.LifecycleKind kind) {
        SmpsDriverServiceObserver.LifecycleSource source =
                kind == SmpsDriverServiceObserver.LifecycleKind.DRIVER_CREATED
                        ? SmpsDriverServiceObserver.LifecycleSource.DRIVER_CONSTRUCTION
                        : kind == SmpsDriverServiceObserver.LifecycleKind.RESTORE
                        ? SmpsDriverServiceObserver.LifecycleSource.SNAPSHOT_RESTORE
                        : SmpsDriverServiceObserver.LifecycleSource.DRIVER_MUTATION;
        serviceObserver.onLifecycle(
                SmpsDriverServiceObserver.LifecycleEvent.driver(
                        Objects.requireNonNull(kind, "kind"), source,
                        diagnosticIdentity));
    }

    // --- Continuous SFX API ---

    /**
     * Prepares the continuous-extension fast path without mutating live state.
     * Returns {@code null} when the current SFX cannot be extended.
     */
    public PreparedSfxAdmission prepareContinuousSfxExtension(
            int sfxId, int trackCount) {
        validateContinuousMetadata(sfxId, trackCount);
        if (sfxId == 0) {
            return null;
        }
        synchronized (sequencersLock) {
            if (continuousSfxId != sfxId) {
                return null;
            }
            for (SmpsSequencer sequencer : sfxSequencers) {
                if (sequencer.getSmpsData().getId() == sfxId) {
                    return new PreparedSfxAdmission(
                            this, null, true, 0, 0, 0, 0,
                            sfxId, trackCount,
                            null, null, null);
                }
            }
            return null;
        }
    }

    /**
     * Validates a new SFX and records only same-id/channel-bounded conflicts.
     */
    public PreparedSfxAdmission prepareNewSfxAdmission(
            SmpsSequencer sequencer, int sfxId, int trackCount) {
        Objects.requireNonNull(sequencer, "sequencer");
        validateContinuousMetadata(sfxId, trackCount);
        if (!sequencer.isBoundTo(this)) {
            throw new IllegalArgumentException(
                    "SFX sequencer belongs to another SMPS driver");
        }
        if (sequencer.getSfxPriority() < 0
                || sequencer.getSfxPriority() > 0xFF) {
            throw new IllegalArgumentException(
                    "SFX priority must fit one unsigned byte");
        }
        sequencer.validateSfxAdmissionMetadata();

        synchronized (sequencersLock) {
            if (sfxSequencers.contains(sequencer)) {
                throw new IllegalArgumentException(
                        "SFX sequencer is already attached");
            }

            int newId = sequencer.getSmpsData().getId();
            SmpsSequencer replaced = sfxSequencersById.get(newId);

            SmpsSequencer[] displacedOwners =
                    new SmpsSequencer[sequencer.trackCount()];
            SmpsSequencer.Track[] displacedTracks =
                    new SmpsSequencer.Track[sequencer.trackCount()];
            int fmMask = 0;
            int psgMask = 0;

            for (int newIndex = 0;
                    newIndex < sequencer.trackCount(); newIndex++) {
                SmpsSequencer.Track newTrack = sequencer.trackAt(newIndex);
                if (newTrack.type == SmpsSequencer.TrackType.FM
                        || newTrack.type == SmpsSequencer.TrackType.DAC) {
                    if (newTrack.channelId < 0
                            || newTrack.channelId >= fmLocks.length) {
                        throw new IllegalArgumentException(
                                "SFX FM channel is outside hardware bounds");
                    }
                    fmMask |= 1 << newTrack.channelId;
                } else if (newTrack.type == SmpsSequencer.TrackType.PSG) {
                    if (newTrack.channelId < 0
                            || newTrack.channelId >= psgLocks.length) {
                        throw new IllegalArgumentException(
                                "SFX PSG channel is outside hardware bounds");
                    }
                    psgMask |= 1 << newTrack.channelId;
                } else {
                    throw new IllegalArgumentException(
                            "SFX track has an unsupported channel type");
                }
            }
            int claimedFmMask = fmMask;
            int claimedPsgMask = psgMask;

            if (replaced != null) {
                for (int channel = 0; channel < fmLocks.length; channel++) {
                    if (fmLocks[channel] == replaced) {
                        fmMask |= 1 << channel;
                    }
                }
                for (int channel = 0; channel < psgLocks.length; channel++) {
                    if (psgLocks[channel] == replaced) {
                        psgMask |= 1 << channel;
                    }
                }
            }

            for (int newIndex = 0;
                    newIndex < sequencer.trackCount(); newIndex++) {
                SmpsSequencer.Track newTrack = sequencer.trackAt(newIndex);
                SmpsSequencer existing = sfxClaimOwner(newTrack);
                if (existing != null && existing != replaced
                        && !specialSfxCoexists(sequencer, existing)) {
                    for (int trackIndex = 0;
                            trackIndex < existing.trackCount(); trackIndex++) {
                        SmpsSequencer.Track track = existing.trackAt(trackIndex);
                        if (!track.active
                                || track.type != newTrack.type
                                || track.channelId != newTrack.channelId
                                || isAlreadyDisplaced(displacedTracks,
                                        newIndex, track)) {
                            continue;
                        }
                        if (displacedOwners[newIndex] != null) {
                            throw new IllegalStateException(
                                    "multiple active SFX tracks own one exact channel type");
                        }
                        displacedOwners[newIndex] = existing;
                        displacedTracks[newIndex] = track;
                    }
                }
            }

            return new PreparedSfxAdmission(
                    this, sequencer, false,
                    claimedFmMask, claimedPsgMask, fmMask, psgMask,
                    sfxId, trackCount, replaced,
                    displacedOwners, displacedTracks);
        }
    }

    /** Applies one already-validated admission in deterministic native order. */
    public void commitSfxAdmission(PreparedSfxAdmission admission) {
        commitSfxAdmission(admission, true);
    }

    /**
     * Commits while the registry retains the journal through its final
     * admission callback.
     */
    public void commitSfxAdmissionUnderJournal(
            PreparedSfxAdmission admission) {
        commitSfxAdmission(admission, false);
    }

    private void commitSfxAdmission(
            PreparedSfxAdmission admission, boolean captureLocalJournal) {
        Objects.requireNonNull(admission, "admission");
        if (admission.owner() != this) {
            throw new IllegalArgumentException(
                    "prepared SFX admission belongs to another driver");
        }
        synchronized (sequencersLock) {
            if (admission.continuousExtension()) {
                admission.claimCommit();
                continuousSfxFlag = true;
                contSfxLoopCnt = admission.trackCount();
                return;
            }

            admission.claimCommit();
            SfxAdmissionMutationState rollbackState = null;
            try {
                if (captureLocalJournal
                        && hasPotentiallyThrowingAdmissionObserver()) {
                    rollbackState = captureSfxAdmissionMutation(admission);
                }
            } catch (RuntimeException failure) {
                admission.releaseCommit();
                throw failure;
            }
            try {
                commitNewSfxAdmission(admission);
            } catch (RuntimeException failure) {
                if (rollbackState != null) {
                    try {
                        restoreSfxAdmissionMutation(rollbackState);
                    } catch (RuntimeException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    } finally {
                        admission.releaseCommit();
                    }
                }
                throw failure;
            }
            if (rollbackState != null) {
                releaseSfxAdmissionMutation(rollbackState);
            }
        }
    }

    private boolean hasPotentiallyThrowingAdmissionObserver() {
        return sfxContentionObserver != SfxContentionObserver.NONE
                || serviceObserver != SmpsDriverServiceObserver.NONE;
    }

    private void commitNewSfxAdmission(PreparedSfxAdmission admission) {
        SmpsSequencer sequencer = admission.sequencer();
        boolean ownsAtAdmission = ownsChannelsAtAdmission(sequencer);
        if (sfxContentionObserver != SfxContentionObserver.NONE) {
            trackSfxAdmission(sequencer);
        }
        sequencer.commitSfxAdmissionInitialization();
        sequencer.setRegion(region);
        sequencer.setIsSfx(true);

        SmpsSequencer replaced = admission.replacedSequencer;
        if (replaced != null) {
            rememberReplacementConflicts(replaced, sequencer);
            sequencers.remove(replaced);
            if (ownsAtAdmission) {
                transferPreparedLocks(replaced, sequencer, admission);
            } else {
                releaseLocks(replaced);
            }
            sfxSequencers.remove(replaced);
            sfxSequencersById.remove(replaced.getSmpsData().getId(), replaced);
            forgetSfxClaims(replaced);
            forgetSequencerServiceIdentity(replaced);
        }

        boolean killedPsg3Track = false;
        for (int action = 0;
                action < admission.displacedTracks.length; action++) {
            SmpsSequencer.Track track = admission.displacedTracks[action];
            if (track == null) {
                continue;
            }
            SmpsSequencer owner = admission.displacedOwners[action];
            track.active = false;
            forgetSfxClaim(owner, track);
            if (!ownsAtAdmission
                    && !isReplacedByExplicitPsg3AdmissionPair(
                            sequencer, track)) {
                owner.stopNote(track);
            }
            int channel = track.channelId;
            if (track.type == SmpsSequencer.TrackType.PSG
                    && psgLocks[channel] == owner) {
                rememberConflict(SfxContentionObserver.Bus.PSG,
                        channel, owner, sequencer);
                if (ownsAtAdmission) {
                    psgLocks[channel] = sequencer;
                } else {
                    psgLocks[channel] = null;
                    updateOverrides(SmpsSequencer.TrackType.PSG,
                            channel, false);
                }
            } else if (track.type != SmpsSequencer.TrackType.PSG
                    && fmLocks[channel] == owner) {
                rememberConflict(SfxContentionObserver.Bus.FM,
                        channel, owner, sequencer);
                if (ownsAtAdmission) {
                    fmLocks[channel] = sequencer;
                } else {
                    fmLocks[channel] = null;
                    updateOverrides(SmpsSequencer.TrackType.FM,
                            channel, false);
                }
            }
            killedPsg3Track |= track.type == SmpsSequencer.TrackType.PSG
                    && channel == 2;
        }

        if (sequencer.trackCount() > 0) {
            removeInactiveSfxSequencers(admission);
        }
        if (killedPsg3Track && !hasExplicitPsg3AdmissionPair(sequencer)) {
            writeRawPsg(0xDF);
            writeRawPsg(0xFF);
        }
        writeConfiguredPsg3AdmissionPair(sequencer);
        writeS1Psg3AdmissionPair(sequencer);

        sequencers.add(sequencer);
        sfxSequencers.add(sequencer);
        sfxSequencersById.put(sequencer.getSmpsData().getId(), sequencer);
        if (sequencer.isSpecialSfx()) {
            // Sound_PlaySpecial stores the sound's voice pointer into the
            // driver global (s1.sounddriver.asm:1128-1132).
            s1SpecialVoicePointer = sequencer.getSmpsData();
        }
        recordSfxClaims(sequencer);
        installPreparedSfxChannelOwnership(admission, sequencer);
        writeSpecialSfxPsg3SilencePair(sequencer);
        restoreMusicDacData();
        reportSfxAdmission(sequencer);
        if (admission.continuousSfxId() != 0) {
            continuousSfxFlag = false;
            continuousSfxId = admission.continuousSfxId();
            contSfxLoopCnt = admission.trackCount();
        }
    }

    /**
     * ROM {@code zSFXTrackInitLoop}: while an SFX is still being loaded, each
     * of its tracks is keyed off and has its SSG-EG operators cleared, in
     * track order (skdisasm Sound/Z80 Sound Driver.asm:2092-2103). The clear
     * is {@code zFMClearSSGEGOps}, which walks 90h and the three operator
     * registers above it with zero (:2528-2536).
     *
     * <p>{@code fix_sndbugs = 0} is the branch the shipped ROM takes and the
     * one modelled here (skdisasm/sonic3k.asm:38). On that branch
     * {@code zSFXTrackInitLoop} calls {@code zFMClearSSGEGOps} for every
     * track including PSG ones, which the listing flags with its own
     * "(even on PSG tracks!!!)" note at :2099. Nothing reaches the chip for
     * those, because every write goes through {@code zWriteFMIorII}, which
     * returns at once on bit 7 of {@code VoiceControl} (:2549-2551). The
     * fixed branch would test that bit at the call site and skip the call
     * instead; the observable SSG-EG clear stream is identical either way.
     *
     * <p>The same routine's second guard is modelled too: {@code
     * zWriteFMIorII} also returns on bit 2 of {@code PlaybackControl}, the
     * SFX-overriding bit (:2552-2553), and {@code zKeyOffIfActive} returns on
     * either that bit or the do-not-attack bit (:3338-3341).
     *
     * <p>Those two track bits are the driver's whole arbitration here: the
     * writes themselves go out through {@code zWriteFMI} / {@code
     * zWriteFMIorII} straight to the chip, with no test of which track last
     * claimed the channel. They must therefore bypass this class's FM lock
     * table, which would otherwise drop them whenever the incoming SFX is
     * taking the channel from an SFX that still holds it - exactly the case
     * where the ROM's key off is audible, because it releases the outgoing
     * SFX's note before the new one attacks.
     */
    private void emitSfxTrackInitWrites(SmpsSequencer sequencer) {
        boolean initializeFm = sequencer.getConfig().isSfxAdmissionKeyOffAndClearsSsgEg();
        boolean silenceNoise = sequencer.getConfig().isPsgSfxAdmissionSilencesNoise();
        if (!initializeFm && !silenceNoise) {
            return;
        }
        SmpsSequencer.Track previousInitializedHeader = null;
        for (SmpsSequencer.Track track : sequencer.getSfxHeaderOrderTracks()) {
            if (track.type == SmpsSequencer.TrackType.PSG && silenceNoise) {
                // The first header's incoming IX is pre-existing slot RAM,
                // which this bounded admission model does not project.
                // At the next header, IX still identifies the track initialized
                // by the preceding loop pass. All shipped S3K SFX headers seed
                // PlaybackControl with 80h, so zSilencePSGChannel's bit-0 gate
                // is clear (TestSonic3kSmpsMetaCommandReachability).
                if (previousInitializedHeader != null
                        && previousInitializedHeader.type
                        == SmpsSequencer.TrackType.PSG) {
                    writeRawPsg(0x9F
                            | (previousInitializedHeader.channelId << 5));
                }
                // Retail fix_sndbugs=0: zGetSFXChannelPointers.is_psg writes
                // FF unconditionally, even for PSG1/2, before initializing
                // the incoming track (skdisasm Sound/Z80 Sound Driver.asm:2131-2136).
                // The fixed branch relies on corrected channel silence instead.
                writeRawPsg(0xFF);
            }
            previousInitializedHeader = track;
            // zWriteFMIorII returns on bit 7 of VoiceControl, so a PSG track's
            // clear writes nothing at all.
            if (track.type != SmpsSequencer.TrackType.FM || !initializeFm) {
                continue;
            }
            if (track.overridden) {
                continue;
            }
            int channel = track.channelId;
            int port = channel < 3 ? 0 : 1;
            int channelInPort = channel % 3;
            if (!track.tieNext) {
                int keyOffSelect = port == 0 ? channelInPort : channelInPort + 4;
                writeRawFm(0, 0x28, keyOffSelect);
            }
            for (int operator = 0; operator < 4; operator++) {
                writeRawFm(port, 0x90 + operator * 4 + channelInPort, 0x00);
            }
        }
    }

    private void installPreparedSfxChannelOwnership(
            PreparedSfxAdmission admission, SmpsSequencer sequencer) {
        emitSfxTrackInitWrites(sequencer);
        if (!ownsChannelsAtAdmission(sequencer)) {
            return;
        }
        // S2 zPlaySound_CheckRing resolves B5 to CE before zPlaySFX installs
        // PlaybackControl bit 2 on the music slot (sd:2116-2135, 2243-2246).
        // With FixDriverBugs=0 the following zUpdateEverything services music
        // first (sd:420-452), so ownership must exist at admission even though
        // constructing the SFX has emitted no chip write.
        // S1's Sound_PlaySpecial takes music ownership at admission the same
        // way, setting bit 2 on the music slot (s1.sounddriver.asm:1146 for
        // FM4, :1153 for PSG3). It does not take the channel from a normal
        // SFX: see yieldsToIncumbentSfx.
        int claimedFmMask = admission.claimedFmMask();
        for (int channel = 0; channel < fmLocks.length; channel++) {
            if ((claimedFmMask & (1 << channel)) == 0) {
                continue;
            }
            updateOverrides(SmpsSequencer.TrackType.FM, channel, true);
            if (yieldsToIncumbentSfx(sequencer, fmLocks[channel])) {
                sequencer.setChannelOverriddenWithoutRestore(
                        SmpsSequencer.TrackType.FM, channel, true);
                continue;
            }
            overrideIncumbentSpecialSfx(
                    SmpsSequencer.TrackType.FM, channel, sequencer);
            fmLocks[channel] = sequencer;
        }
        int claimedPsgMask = admission.claimedPsgMask();
        for (int channel = 0; channel < psgLocks.length; channel++) {
            if ((claimedPsgMask & (1 << channel)) == 0) {
                continue;
            }
            updateOverrides(SmpsSequencer.TrackType.PSG, channel, true);
            if (yieldsToIncumbentSfx(sequencer, psgLocks[channel])) {
                sequencer.setChannelOverriddenWithoutRestore(
                        SmpsSequencer.TrackType.PSG, channel, true);
                continue;
            }
            overrideIncumbentSpecialSfx(
                    SmpsSequencer.TrackType.PSG, channel, sequencer);
            psgLocks[channel] = sequencer;
        }
    }

    /**
     * True when an admitting SFX must leave an incumbent SFX track playing on
     * a shared channel instead of displacing it.
     *
     * <p>S1's special SFX ({@code Sound_PlaySpecial},
     * docs/s1disasm/s1.sounddriver.asm:1117) is the case this exists for. It
     * initialises only its own {@code v_spcsfx_*} track slots and never writes
     * the normal {@code v_sfx_*} slots. When the normal SFX track on the shared
     * channel is already playing it sets bit 2 ('SFX is overriding') on its
     * own special track instead (:1180-1182 for FM4, :1185-1187 for PSG3), so
     * the special SFX advances its timing silently until the normal SFX
     * releases the channel through {@code cfStopTrack}'s special-track branch
     * (:2514-2518). Displacing the incumbent would make the special SFX audible
     * immediately, which the ROM never does.
     *
     * <p>This is the admission-time counterpart of the same precedence
     * {@link #shouldStealLock} already applies to per-write arbitration: a
     * normal SFX outranks a special one, never the reverse.
     */
    private boolean yieldsToIncumbentSfx(
            SmpsSequencer challenger, SmpsSequencer incumbent) {
        return incumbent != null && isSfx(incumbent)
                && challenger.isSpecialSfx() && !incumbent.isSpecialSfx();
    }

    /**
     * True when an admitting SFX and an incumbent SFX on a shared channel are
     * one special and one normal, the pair S1's driver keeps side by side in
     * separate track RAM instead of one replacing the other.
     */
    private boolean specialSfxCoexists(
            SmpsSequencer challenger, SmpsSequencer incumbent) {
        return incumbent != null && isSfx(incumbent)
                && challenger.isSpecialSfx() != incumbent.isSpecialSfx();
    }

    /**
     * Silences, without stopping, a special SFX whose channel a normal SFX is
     * taking at admission.
     *
     * <p>{@code Sound_PlaySFX} loads its own tracks and then tests the SFX
     * track it just wrote: with FM4 in use it sets bit 2, 'SFX is overriding',
     * on {@code v_spcsfx_fm4_track} (s1.sounddriver.asm:1072-1074), and PSG3
     * the same way (:1077-1079). It never clears the special track's playing
     * bit, so the special SFX keeps advancing silently and gets the channel
     * back through {@code cfStopTrack}; see {@link #waitingSpecialSfx}. This is
     * the mirror of {@link #yieldsToIncumbentSfx}.
     */
    private void overrideIncumbentSpecialSfx(
            SmpsSequencer.TrackType type, int channel, SmpsSequencer challenger) {
        SmpsSequencer incumbent = type == SmpsSequencer.TrackType.PSG
                ? psgLocks[channel] : fmLocks[channel];
        if (incumbent == null || incumbent == challenger
                || !incumbent.isSpecialSfx() || challenger.isSpecialSfx()) {
            return;
        }
        incumbent.setChannelOverriddenWithoutRestore(type, channel, true);
    }

    private static boolean ownsChannelsAtAdmission(
            SmpsSequencer sequencer) {
        return sequencer.getConfig().getSfxChannelOwnershipMode()
                == SmpsSequencerConfig.SfxChannelOwnershipMode.ADMISSION;
    }

    private void transferPreparedLocks(
            SmpsSequencer displaced,
            SmpsSequencer challenger,
            PreparedSfxAdmission admission) {
        for (int channel = 0; channel < fmLocks.length; channel++) {
            if (fmLocks[channel] != displaced) {
                continue;
            }
            if ((admission.claimedFmMask() & (1 << channel)) == 0) {
                fmLocks[channel] = null;
                pendingConflictOwners.remove(new ConflictKey(
                        SfxContentionObserver.Bus.FM, channel, challenger));
                updateOverridesWithoutRestore(SmpsSequencer.TrackType.FM,
                        channel, false);
                continue;
            }
            fmLocks[channel] = challenger;
        }
        for (int channel = 0; channel < psgLocks.length; channel++) {
            if (psgLocks[channel] != displaced) {
                continue;
            }
            if ((admission.claimedPsgMask() & (1 << channel)) == 0) {
                psgLocks[channel] = null;
                pendingConflictOwners.remove(new ConflictKey(
                        SfxContentionObserver.Bus.PSG, channel, challenger));
                updateOverridesWithoutRestore(SmpsSequencer.TrackType.PSG,
                        channel, false);
                continue;
            }
            psgLocks[channel] = challenger;
        }
        displaced.setPsgLatchChannel(-1);
        psgLatches.remove(displaced);
        sfxAdmissionOrdinals.remove(displaced);
        pendingConflictOwners.keySet().removeIf(
                key -> key.challenger() == displaced);
    }

    private void writeS1Psg3AdmissionPair(SmpsSequencer sequencer) {
        if (sequencer.getConfig().getPsgSfxTakeoverMode()
                != SmpsSequencerConfig.PsgSfxTakeoverMode.S1_PSG3_SILENCE_PAIR) {
            return;
        }
        // S1 Sound_PlaySFX writes these while loading cPSG3, before the
        // track's first UpdateMusic service (SD:1038-1044). PSG1/2 have
        // no corresponding admission write.
        writePsg3AdmissionPairIfDeclared(sequencer);
    }

    /**
     * Emits the PSG pair S1's {@code Sound_PlaySpecial} writes at its
     * {@code .doneoverride} tail (docs/s1disasm/s1.sounddriver.asm:1183-1191).
     *
     * <p>The routine loads the special SFX's own {@code v_spcsfx_*} tracks,
     * then tests the NORMAL SFX PSG3 slot: {@code tst.b
     * SMPS_RAM.v_sfx_psg3_track.PlaybackControl(a6) / bpl.s .locret} (:1183).
     * When that slot is playing it sets the 'SFX is overriding' bit on its own
     * special PSG3 track and writes {@code ori.b #$1F,d4 / move.b d4,(psg_input)
     * / bchg #5,d4 / move.b d4,(psg_input)} (:1186-1191).
     *
     * <p>{@code d4} is NOT the PSG3 channel byte the "command to silence
     * channel" comment implies. Its last assignment is {@code move.b 1(a1),d4}
     * at the top of {@code .sfxloadloop} (:1141), so it holds the voice control
     * bits of the last track the loop read. {@code SndD0 - Waterfall} declares
     * one track, {@code cFM4} = {@code $04}, so the pair emitted is {@code $1F,
     * $3F}: two SN76489 data bytes rather than the intended {@code $DF, $FF}
     * latch pair. This is the shipped {@code FixBugs = 0} path
     * (docs/s1disasm/sonic.asm:20); the fixed branch does not exist for this
     * site, so there is no alternative behaviour to select. The engine emits
     * what the ROM emits.
     */
    private void writeSpecialSfxPsg3SilencePair(SmpsSequencer sequencer) {
        if (!sequencer.isSpecialSfx()
                || sequencer.getConfig().getSpecialSfxPsg3SilenceMode()
                        != SmpsSequencerConfig.SpecialSfxPsg3SilenceMode
                                .S1_STALE_VOICE_CONTROL_PAIR) {
            return;
        }
        if (!normalSfxPsg3TrackPlaying()) {
            return;
        }
        int staleVoiceControl = lastLoadedSfxTrackVoiceControl(sequencer);
        if (staleVoiceControl < 0) {
            return;
        }
        int silence = (staleVoiceControl | 0x1F) & 0xFF;
        writeRawPsg(silence);
        writeRawPsg(silence ^ 0x20);
    }

    /**
     * The engine's reading of {@code v_sfx_psg3_track.PlaybackControl} bit 7:
     * a normal (non-special) SFX holding an active PSG3 track. S1 keeps exactly
     * one such slot, which {@code Sound_PlaySpecial} never writes.
     */
    private boolean normalSfxPsg3TrackPlaying() {
        for (SmpsSequencer candidate : sfxSequencers) {
            if (candidate.isSpecialSfx()) {
                continue;
            }
            for (int track = 0; track < candidate.trackCount(); track++) {
                SmpsSequencer.Track entry = candidate.trackAt(track);
                if (entry.active
                        && entry.type == SmpsSequencer.TrackType.PSG
                        && entry.channelId == 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The voice control byte {@code Sound_PlaySpecial}'s {@code .sfxloadloop}
     * leaves in {@code d4}: the channel byte of the last header entry it read
     * (s1.sounddriver.asm:1141).
     */
    private static int lastLoadedSfxTrackVoiceControl(SmpsSequencer sequencer) {
        if (!(sequencer.getSmpsData() instanceof SmpsSfxData sfxData)) {
            return -1;
        }
        java.util.List<? extends SmpsSfxData.SmpsSfxTrack> entries =
                sfxData.getTrackEntries();
        if (entries.isEmpty()) {
            return -1;
        }
        return entries.get(entries.size() - 1).channelMask() & 0xFF;
    }

    private void writeConfiguredPsg3AdmissionPair(
            SmpsSequencer sequencer) {
        if (sequencer.getConfig().getPsg3SfxAdmissionWriteMode()
                != SmpsSequencerConfig.Psg3SfxAdmissionWriteMode
                        .SILENCE_TONE_AND_NOISE) {
            return;
        }
        writePsg3AdmissionPairIfDeclared(sequencer);
    }

    private boolean hasExplicitPsg3AdmissionPair(
            SmpsSequencer sequencer) {
        return sequencer.getConfig().getPsg3SfxAdmissionWriteMode()
                        == SmpsSequencerConfig.Psg3SfxAdmissionWriteMode
                                .SILENCE_TONE_AND_NOISE
                || sequencer.getConfig().getPsgSfxTakeoverMode()
                        == SmpsSequencerConfig.PsgSfxTakeoverMode
                                .S1_PSG3_SILENCE_PAIR;
    }

    private boolean isReplacedByExplicitPsg3AdmissionPair(
            SmpsSequencer sequencer, SmpsSequencer.Track displacedTrack) {
        return displacedTrack.type == SmpsSequencer.TrackType.PSG
                && displacedTrack.channelId == 2
                && hasExplicitPsg3AdmissionPair(sequencer);
    }

    private void writePsg3AdmissionPairIfDeclared(
            SmpsSequencer sequencer) {
        for (int index = 0; index < sequencer.trackCount(); index++) {
            SmpsSequencer.Track track = sequencer.trackAt(index);
            if (track.type != SmpsSequencer.TrackType.PSG
                    || track.channelId != 2) {
                continue;
            }
            writeRawPsg(0xDF);
            writeRawPsg(0xFF);
            return;
        }
    }

    private static void validateContinuousMetadata(
            int sfxId, int trackCount) {
        if (sfxId < 0 || sfxId > 0xFF) {
            throw new IllegalArgumentException(
                    "continuous SFX id must fit one unsigned byte");
        }
        if (trackCount < 0) {
            throw new IllegalArgumentException(
                    "continuous SFX track count must not be negative");
        }
    }

    private static boolean isAlreadyDisplaced(
            SmpsSequencer.Track[] tracks,
            int limit,
            SmpsSequencer.Track track) {
        for (int index = 0; index < limit; index++) {
            if (tracks[index] == track) {
                return true;
            }
        }
        return false;
    }

    private SmpsSequencer sfxClaimOwner(SmpsSequencer.Track track) {
        return switch (track.type) {
            case FM -> fmSfxClaims[track.channelId];
            case DAC -> dacSfxClaims[track.channelId];
            case PSG -> psgSfxClaims[track.channelId];
        };
    }

    private void recordSfxClaims(SmpsSequencer sequencer) {
        for (int index = 0; index < sequencer.trackCount(); index++) {
            SmpsSequencer.Track track = sequencer.trackAt(index);
            if (!track.active) {
                continue;
            }
            switch (track.type) {
                case FM -> fmSfxClaims[track.channelId] = sequencer;
                case DAC -> dacSfxClaims[track.channelId] = sequencer;
                case PSG -> psgSfxClaims[track.channelId] = sequencer;
            }
        }
    }

    private void forgetSfxClaim(
            SmpsSequencer sequencer, SmpsSequencer.Track track) {
        SmpsSequencer[] claims = switch (track.type) {
            case FM -> fmSfxClaims;
            case DAC -> dacSfxClaims;
            case PSG -> psgSfxClaims;
        };
        if (claims[track.channelId] == sequencer) {
            claims[track.channelId] = null;
        }
    }

    private void forgetSfxClaims(SmpsSequencer sequencer) {
        clearClaimsOwnedBy(fmSfxClaims, sequencer);
        clearClaimsOwnedBy(dacSfxClaims, sequencer);
        clearClaimsOwnedBy(psgSfxClaims, sequencer);
    }

    private static void clearClaimsOwnedBy(
            SmpsSequencer[] claims, SmpsSequencer sequencer) {
        for (int channel = 0; channel < claims.length; channel++) {
            if (claims[channel] == sequencer) {
                claims[channel] = null;
            }
        }
    }

    private void removeInactiveSfxSequencers(
            PreparedSfxAdmission admission) {
        for (int action = 0;
                action < admission.displacedOwners.length; action++) {
            Iterator<SmpsSequencer> iterator = sfxSequencers.iterator();
            while (iterator.hasNext()) {
                SmpsSequencer sequencer = iterator.next();
                if (!allTracksInactive(sequencer)) {
                    continue;
                }
                if (legacyDeathAction(admission, sequencer) != action) {
                    continue;
                }
                sequencers.remove(sequencer);
                releaseLocks(sequencer);
                iterator.remove();
                sfxSequencersById.remove(
                        sequencer.getSmpsData().getId(), sequencer);
                forgetSfxClaims(sequencer);
                forgetSequencerServiceIdentity(sequencer);
            }
        }
    }

    private static int legacyDeathAction(
            PreparedSfxAdmission admission, SmpsSequencer sequencer) {
        int lastAction = 0;
        for (int action = 0;
                action < admission.displacedOwners.length; action++) {
            if (admission.displacedOwners[action] == sequencer) {
                lastAction = action;
            }
        }
        return lastAction;
    }

    private static boolean allTracksInactive(SmpsSequencer sequencer) {
        for (int index = 0; index < sequencer.trackCount(); index++) {
            if (sequencer.trackAt(index).active) {
                return false;
            }
        }
        return true;
    }

    /**
     * Attempt to extend a currently-playing continuous SFX instead of restarting it.
     * ROM: when the same continuous SFX ID is re-triggered, set zContinuousSFXFlag = 0x80
     * and refresh zContSFXLoopCnt, but do NOT restart the SFX.
     *
     * @param sfxId the SFX ID being triggered
     * @param trackCount total FM+PSG track count for the SFX (from header byte 3)
     * @return true if the SFX was extended (caller should skip creating a new sequencer)
     */
    public boolean extendContinuousSfx(int sfxId, int trackCount) {
        PreparedSfxAdmission admission =
                prepareContinuousSfxExtension(sfxId, trackCount);
        if (admission == null) {
            return false;
        }
        commitSfxAdmission(admission);
        return true;
    }

    /**
     * Mark a new continuous SFX as starting playback.
     * ROM: store SFX index in zContinuousSFX, clear zContinuousSFXFlag, set loop count.
     */
    public void startContinuousSfx(int sfxId, int trackCount) {
        synchronized (sequencersLock) {
            continuousSfxFlag = false;
            continuousSfxId = sfxId;
            contSfxLoopCnt = trackCount;
        }
    }

    /** ROM: read zContinuousSFXFlag (0x80 when set). */
    public boolean isContinuousSfxFlagSet() {
        return continuousSfxFlag;
    }

    /** ROM: clear zContinuousSFX. */
    public void clearContinuousSfxId() {
        continuousSfxId = 0;
    }

    /** ROM: clear zContinuousSFXFlag. */
    public void clearContinuousSfxFlag() {
        continuousSfxFlag = false;
    }

    /**
     * ROM: decrement zContSFXLoopCnt and return whether it reached zero.
     * When zero, all tracks have passed their loop point for this extension cycle.
     */
    public boolean decrementContSfxLoopCnt() {
        return --contSfxLoopCnt <= 0;
    }

    /** Reads the current clock region without capturing sequencer or track state. */
    public SmpsSequencer.Region getRegion() {
        synchronized (sequencersLock) {
            return region;
        }
    }

    public void setRegion(SmpsSequencer.Region region) {
        this.region = region;
        synchronized (sequencersLock) {
            for (SmpsSequencer seq : sequencers) {
                seq.setRegion(region);
            }
        }
    }

    public void setReadModeForTesting(ReadMode readMode) {
        this.readMode = readMode;
    }

    public SmpsSequencer firstMusicSequencer() {
        synchronized (sequencersLock) {
            for (SmpsSequencer sequencer : sequencers) {
                if (!isSfx(sequencer)) {
                    return sequencer;
                }
            }
            return null;
        }
    }

    public void bindMusicFadeCompleteCallback(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        synchronized (sequencersLock) {
            for (SmpsSequencer sequencer : sequencers) {
                if (!isSfx(sequencer)) {
                    sequencer.setOnFadeComplete(callback);
                }
            }
        }
    }

    public int getHybridChunkCountForTesting() {
        return hybridChunkCountForTesting;
    }

    public List<SmpsSequencer> sequencersForTesting() {
        synchronized (sequencersLock) {
            return List.copyOf(sequencers);
        }
    }

    SfxAdmissionMutationState captureSfxAdmissionMutation(
            PreparedSfxAdmission admission) {
        synchronized (sequencersLock) {
            if (admission.continuousExtension()) {
                return new SfxAdmissionMutationState(
                        nextSfxAdmissionOrdinal, nextServiceOrdinal,
                        nextServiceSequencerOrdinal, continuousSfxId,
                        continuousSfxFlag, contSfxLoopCnt);
            }
            SmpsSequencer[] affected = affectedSequencers(admission);
            int count = affected.length;
            SmpsSequencer.LiveCommandMutationToken[] sequencerStates =
                    new SmpsSequencer.LiveCommandMutationToken[count];
            int[] positions = new int[count];
            boolean[] sfxMembers = new boolean[count];
            boolean[] pendingRemovalMembers = new boolean[count];
            boolean[] removalBufferMembers = new boolean[count];
            boolean[] admissionOrdinalPresent = new boolean[count];
            long[] admissionOrdinals = new long[count];
            boolean[] serviceOrdinalPresent = new boolean[count];
            long[] serviceOrdinals = new long[count];
            boolean[] psgLatchPresent = new boolean[count];
            int[] psgLatchChannels = new int[count];
            for (int index = 0; index < count; index++) {
                SmpsSequencer sequencer = affected[index];
                sequencerStates[index] =
                        sequencer.captureLiveCommandMutation();
                positions[index] = sequencers.indexOf(sequencer);
                sfxMembers[index] = sfxSequencers.contains(sequencer);
                pendingRemovalMembers[index] =
                        pendingRemovals.contains(sequencer);
                removalBufferMembers[index] =
                        sfxRemovalBuffer.contains(sequencer);
                Long admissionOrdinal = sfxAdmissionOrdinals.get(sequencer);
                admissionOrdinalPresent[index] = admissionOrdinal != null;
                admissionOrdinals[index] = admissionOrdinal == null
                        ? 0 : admissionOrdinal;
                Long serviceOrdinal = serviceSequencerOrdinals == null
                        ? null : serviceSequencerOrdinals.get(sequencer);
                serviceOrdinalPresent[index] = serviceOrdinal != null;
                serviceOrdinals[index] = serviceOrdinal == null
                        ? 0 : serviceOrdinal;
                Integer latch = psgLatches.get(sequencer);
                psgLatchPresent[index] = latch != null;
                psgLatchChannels[index] = latch == null ? 0 : latch;
            }
            SmpsSequencer.Track[] musicOverrideTracks =
                    new SmpsSequencer.Track[fmLocks.length + psgLocks.length];
            boolean[] musicOverrides = new boolean[musicOverrideTracks.length];
            int musicOverrideCount = 0;
            for (SmpsSequencer live : sequencers) {
                if (isSfx(live)) {
                    continue;
                }
                for (int trackIndex = 0;
                        trackIndex < live.trackCount(); trackIndex++) {
                    SmpsSequencer.Track track = live.trackAt(trackIndex);
                    boolean channelAffected = track.type == SmpsSequencer.TrackType.PSG
                            ? (admission.affectedPsgMask()
                            & (1 << track.channelId)) != 0
                            : (admission.affectedFmMask()
                            & (1 << track.channelId)) != 0;
                    if (channelAffected) {
                        musicOverrideTracks[musicOverrideCount] = track;
                        musicOverrides[musicOverrideCount] = track.overridden;
                        musicOverrideCount++;
                    }
                }
            }
            musicOverrideTracks = java.util.Arrays.copyOf(
                    musicOverrideTracks, musicOverrideCount);
            musicOverrides = java.util.Arrays.copyOf(
                    musicOverrides, musicOverrideCount);
            ConflictKey[] conflictKeys = new ConflictKey[
                    affected.length * (fmLocks.length + psgLocks.length)];
            SfxContentionObserver.Source[] conflictSources =
                    new SfxContentionObserver.Source[conflictKeys.length];
            int conflictCount = 0;
            for (Map.Entry<ConflictKey, SfxContentionObserver.Source> entry
                    : pendingConflictOwners.entrySet()) {
                if (containsIdentity(affected, affected.length,
                        entry.getKey().challenger())) {
                    conflictKeys[conflictCount] = entry.getKey();
                    conflictSources[conflictCount] = entry.getValue();
                    conflictCount++;
                }
            }
            conflictKeys = java.util.Arrays.copyOf(
                    conflictKeys, conflictCount);
            conflictSources = java.util.Arrays.copyOf(
                    conflictSources, conflictCount);
            return new SfxAdmissionMutationState(
                    affected, sequencerStates, positions, sfxMembers,
                    pendingRemovalMembers, removalBufferMembers,
                    admissionOrdinalPresent, admissionOrdinals,
                    serviceOrdinalPresent, serviceOrdinals,
                    fmLocks.clone(), psgLocks.clone(), psgLatchPresent,
                    psgLatchChannels, musicOverrideTracks, musicOverrides,
                    conflictKeys, conflictSources,
                    nextSfxAdmissionOrdinal,
                    nextServiceOrdinal, nextServiceSequencerOrdinal,
                    continuousSfxId,
                    continuousSfxFlag, contSfxLoopCnt);
        }
    }

    /** Returns a committed admission's per-sequencer backups to their pools. */
    void releaseSfxAdmissionMutation(SfxAdmissionMutationState state) {
        if (state.continuousOnly) {
            return;
        }
        synchronized (sequencersLock) {
            for (int index = 0; index < state.affected.length; index++) {
                state.affected[index].releaseLiveCommandMutation(
                        state.sequencerStates[index]);
            }
        }
    }

    void restoreSfxAdmissionMutation(SfxAdmissionMutationState state) {
        synchronized (sequencersLock) {
            if (state.continuousOnly) {
                nextSfxAdmissionOrdinal = state.nextAdmissionOrdinal;
                nextServiceOrdinal = state.nextServiceOrdinal;
                nextServiceSequencerOrdinal =
                        state.nextServiceSequencerOrdinal;
                continuousSfxId = state.continuousSfxId;
                continuousSfxFlag = state.continuousSfxFlag;
                contSfxLoopCnt = state.continuousLoopCount;
                return;
            }
            for (int index = 0; index < state.affected.length; index++) {
                state.affected[index].rollbackLiveCommandMutation(
                        state.sequencerStates[index]);
            }
            for (SmpsSequencer sequencer : state.affected) {
                sequencers.remove(sequencer);
            }
            for (int position = 0; position <= sequencers.size()
                    + state.affected.length; position++) {
                for (int index = 0; index < state.affected.length; index++) {
                    if (state.positions[index] == position) {
                        sequencers.add(Math.min(position, sequencers.size()),
                                state.affected[index]);
                    }
                }
            }
            for (int index = 0;
                    index < state.musicOverrideTracks.length; index++) {
                state.musicOverrideTracks[index].overridden =
                        state.musicOverrides[index];
            }
            for (SmpsSequencer sequencer : state.affected) {
                sfxSequencersById.remove(
                        sequencer.getSmpsData().getId(), sequencer);
                forgetSfxClaims(sequencer);
            }
            for (int index = 0; index < state.affected.length; index++) {
                SmpsSequencer sequencer = state.affected[index];
                restoreMembership(sfxSequencers, sequencer,
                        state.sfxMembers[index]);
                if (state.sfxMembers[index]) {
                    sfxSequencersById.put(
                            sequencer.getSmpsData().getId(), sequencer);
                    recordSfxClaims(sequencer);
                }
                restoreMembership(pendingRemovals, sequencer,
                        state.pendingRemovalMembers[index]);
                restoreMembership(sfxRemovalBuffer, sequencer,
                        state.removalBufferMembers[index]);
                restoreIdentityEntry(sfxAdmissionOrdinals, sequencer,
                        state.admissionOrdinalPresent[index],
                        state.admissionOrdinals[index]);
                if (serviceSequencerOrdinals != null) {
                    restoreIdentityEntry(serviceSequencerOrdinals, sequencer,
                            state.serviceOrdinalPresent[index],
                            state.serviceOrdinals[index]);
                }
                if (state.psgLatchPresent[index]) {
                    psgLatches.put(sequencer, state.psgLatchChannels[index]);
                } else {
                    psgLatches.remove(sequencer);
                }
            }
            pendingConflictOwners.keySet().removeIf(key ->
                    containsIdentity(state.affected, state.affected.length,
                            key.challenger()));
            for (int index = 0; index < state.conflictKeys.length; index++) {
                pendingConflictOwners.put(
                        state.conflictKeys[index], state.conflictSources[index]);
            }
            System.arraycopy(state.fmLocks, 0, fmLocks, 0, fmLocks.length);
            System.arraycopy(state.psgLocks, 0, psgLocks, 0, psgLocks.length);
            nextSfxAdmissionOrdinal = state.nextAdmissionOrdinal;
            nextServiceOrdinal = state.nextServiceOrdinal;
            nextServiceSequencerOrdinal = state.nextServiceSequencerOrdinal;
            continuousSfxId = state.continuousSfxId;
            continuousSfxFlag = state.continuousSfxFlag;
            contSfxLoopCnt = state.continuousLoopCount;
        }
    }

    private static SmpsSequencer[] affectedSequencers(
            PreparedSfxAdmission admission) {
        if (admission.continuousExtension()) {
            return new SmpsSequencer[0];
        }
        SmpsSequencer[] scratch = new SmpsSequencer[
                2 + admission.displacedOwners.length];
        int count = 0;
        scratch[count++] = admission.sequencer();
        if (admission.replacedSequencer != null
                && admission.replacedSequencer != admission.sequencer()) {
            scratch[count++] = admission.replacedSequencer;
        }
        for (SmpsSequencer owner : admission.displacedOwners) {
            if (owner == null || containsIdentity(scratch, count, owner)) {
                continue;
            }
            scratch[count++] = owner;
        }
        return java.util.Arrays.copyOf(scratch, count);
    }

    private static boolean containsIdentity(
            SmpsSequencer[] values, int count, SmpsSequencer candidate) {
        for (int index = 0; index < count; index++) {
            if (values[index] == candidate) {
                return true;
            }
        }
        return false;
    }

    private static void restoreMembership(
            java.util.Collection<SmpsSequencer> members,
            SmpsSequencer sequencer, boolean present) {
        members.remove(sequencer);
        if (present) {
            members.add(sequencer);
        }
    }

    private static void restoreIdentityEntry(
            IdentityHashMap<SmpsSequencer, Long> entries,
            SmpsSequencer sequencer, boolean present, long value) {
        if (present) {
            entries.put(sequencer, value);
        } else {
            entries.remove(sequencer);
        }
    }

    public LiveCommandMutationToken captureLiveCommandMutation() {
        synchronized (sequencersLock) {
            SmpsSequencer[] capturedSequencers =
                    sequencers.toArray(SmpsSequencer[]::new);
            SmpsSequencer.LiveCommandMutationToken[] sequencerStates =
                    new SmpsSequencer.LiveCommandMutationToken[
                            capturedSequencers.length];
            for (int index = 0;
                 index < capturedSequencers.length;
                 index++) {
                sequencerStates[index] =
                        capturedSequencers[index]
                                .captureLiveCommandMutation();
            }
            Object[] latchSources = new Object[psgLatches.size()];
            int[] latchChannels = new int[psgLatches.size()];
            int latchIndex = 0;
            for (Map.Entry<Object, Integer> entry
                    : psgLatches.entrySet()) {
                latchSources[latchIndex] = entry.getKey();
                latchChannels[latchIndex] = entry.getValue();
                latchIndex++;
            }
            List<SmpsSequencer> admittedSequencers = new ArrayList<>();
            List<Long> admissionOrdinals = new ArrayList<>();
            for (SmpsSequencer sequencer : capturedSequencers) {
                Long ordinal = sfxAdmissionOrdinals.get(sequencer);
                if (ordinal != null) {
                    admittedSequencers.add(sequencer);
                    admissionOrdinals.add(ordinal);
                }
            }
            return new LiveCommandMutationToken(
                    this,
                    capturedSequencers,
                    sequencerStates,
                    sfxSequencers.toArray(SmpsSequencer[]::new),
                    admittedSequencers.toArray(SmpsSequencer[]::new),
                    admissionOrdinals.stream().mapToLong(Long::longValue).toArray(),
                    fmLocks.clone(),
                    psgLocks.clone(),
                    latchSources,
                    latchChannels,
                    pendingRemovals.toArray(SmpsSequencer[]::new),
                    sfxRemovalBuffer.toArray(SmpsSequencer[]::new),
                    region,
                    readMode,
                    hybridChunkCountForTesting,
                    continuousSfxId,
                    continuousSfxFlag,
                    contSfxLoopCnt,
                    palUpdateCounter,
                    s1SpecialVoicePointer);
        }
    }

    /** Returns a committed token's per-sequencer backups to their pools. */
    public void releaseLiveCommandMutation(LiveCommandMutationToken token) {
        Objects.requireNonNull(token, "token");
        if (token.owner != this) {
            throw new IllegalArgumentException(
                    "live command token belongs to another SMPS driver");
        }
        synchronized (sequencersLock) {
            for (int index = 0; index < token.sequencers.length; index++) {
                token.sequencers[index].releaseLiveCommandMutation(
                        token.sequencerStates[index]);
            }
        }
    }

    public void rollbackLiveCommandMutation(
            LiveCommandMutationToken token) {
        Objects.requireNonNull(token, "token");
        if (token.owner != this) {
            throw new IllegalArgumentException(
                    "live command token belongs to another SMPS driver");
        }
        synchronized (sequencersLock) {
            for (int index = 0;
                 index < token.sequencers.length;
                 index++) {
                token.sequencers[index].rollbackLiveCommandMutation(
                        token.sequencerStates[index]);
            }

            sequencers.clear();
            for (SmpsSequencer sequencer : token.sequencers) {
                sequencers.add(sequencer);
            }
            sfxSequencers.clear();
            sfxSequencersById.clear();
            Arrays.fill(fmSfxClaims, null);
            Arrays.fill(dacSfxClaims, null);
            Arrays.fill(psgSfxClaims, null);
            for (SmpsSequencer sequencer : token.sfxSequencers) {
                sfxSequencers.add(sequencer);
                sfxSequencersById.put(
                        sequencer.getSmpsData().getId(), sequencer);
                recordSfxClaims(sequencer);
            }
            pendingConflictOwners.clear();
            System.arraycopy(token.fmLocks, 0, fmLocks, 0,
                    fmLocks.length);
            System.arraycopy(token.psgLocks, 0, psgLocks, 0,
                    psgLocks.length);
            psgLatches.clear();
            for (int index = 0;
                 index < token.psgLatchSources.length;
                 index++) {
                psgLatches.put(token.psgLatchSources[index],
                        token.psgLatchChannels[index]);
            }
            pendingRemovals.clear();
            sfxAdmissionOrdinals.clear();
            for (int index = 0; index < token.admissionSequencers.length; index++) {
                sfxAdmissionOrdinals.put(token.admissionSequencers[index],
                        token.admissionOrdinals[index]);
            }
            for (SmpsSequencer sequencer : token.pendingRemovals) {
                pendingRemovals.add(sequencer);
            }
            sfxRemovalBuffer.clear();
            for (SmpsSequencer sequencer : token.sfxRemovalBuffer) {
                sfxRemovalBuffer.add(sequencer);
            }
            region = token.region;
            readMode = token.readMode;
            hybridChunkCountForTesting =
                    token.hybridChunkCountForTesting;
            continuousSfxId = token.continuousSfxId;
            continuousSfxFlag = token.continuousSfxFlag;
            contSfxLoopCnt = token.contSfxLoopCnt;
            palUpdateCounter = token.palUpdateCounter;
            s1SpecialVoicePointer = token.s1SpecialVoicePointer;
            if (serviceSequencerOrdinals != null) {
                serviceSequencerOrdinals.keySet().retainAll(sequencers);
            }
        }
    }

    public SmpsDriverSnapshot captureSnapshot() {
        return captureLogicalState();
    }

    /**
     * Captures immutable logical channel ownership without consulting routing
     * locks or touching the chip.  SFX occupancy is derived from active track
     * declarations, which is the state a ROM-shaped command can actually
     * inspect before a track has produced its first hardware write.
     */
    public SmpsChannelOwnershipProjection captureOwnershipProjection() {
        synchronized (sequencersLock) {
            Map<SmpsChannelOwnershipProjection.PhysicalChannel,
                    List<SmpsChannelOwnershipProjection.TrackProjection>>
                    sfx = new LinkedHashMap<>();
            Map<SmpsChannelOwnershipProjection.PhysicalChannel,
                    List<SmpsChannelOwnershipProjection.TrackProjection>>
                    music = new LinkedHashMap<>();
            for (int sequencerIndex = 0;
                    sequencerIndex < sequencers.size(); sequencerIndex++) {
                SmpsSequencer sequencer = sequencers.get(sequencerIndex);
                boolean sfxSequencer = isSfx(sequencer);
                SmpsSequencerSnapshot snapshot = sequencer.captureSnapshot();
                for (int trackIndex = 0;
                        trackIndex < snapshot.tracks().size(); trackIndex++) {
                    SmpsTrackSnapshot track = snapshot.tracks().get(trackIndex);
                    if (sfxSequencer && !track.active()) {
                        continue;
                    }
                    SmpsChannelOwnershipProjection.PhysicalChannel channel =
                            normalizedChannel(track);
                    SmpsChannelOwnershipProjection.TrackProjection projection =
                            new SmpsChannelOwnershipProjection.TrackProjection(
                                    new SmpsChannelOwnershipProjection.TrackCoordinate(
                                            sequencerIndex, trackIndex,
                                            sfxSequencer,
                                            sequencer.getSourceDescriptor()),
                                    track);
                    (sfxSequencer ? sfx : music).computeIfAbsent(channel,
                            ignored -> new ArrayList<>()).add(projection);
                }
            }
            Map<SmpsChannelOwnershipProjection.PhysicalChannel,
                    SmpsChannelOwnershipProjection.RoleOwnership> roles =
                    new LinkedHashMap<>();
            for (SmpsChannelOwnershipProjection.PhysicalChannel channel
                    : unionChannels(sfx, music)) {
                roles.put(channel,
                        new SmpsChannelOwnershipProjection.RoleOwnership(
                                channel,
                                sfx.getOrDefault(channel, List.of()),
                                music.getOrDefault(channel, List.of())));
            }
            return new SmpsChannelOwnershipProjection(roles);
        }
    }

    private static List<SmpsChannelOwnershipProjection.PhysicalChannel>
            unionChannels(
                    Map<SmpsChannelOwnershipProjection.PhysicalChannel,
                            List<SmpsChannelOwnershipProjection.TrackProjection>> first,
                    Map<SmpsChannelOwnershipProjection.PhysicalChannel,
                            List<SmpsChannelOwnershipProjection.TrackProjection>> second) {
        LinkedHashSet<SmpsChannelOwnershipProjection.PhysicalChannel> channels =
                new LinkedHashSet<>(first.keySet());
        channels.addAll(second.keySet());
        return List.copyOf(channels);
    }

    private static SmpsChannelOwnershipProjection.PhysicalChannel
            normalizedChannel(SmpsTrackSnapshot track) {
        SmpsChannelOwnershipProjection.Bus bus = switch (track.type()) {
            case FM -> SmpsChannelOwnershipProjection.Bus.FM;
            case PSG -> SmpsChannelOwnershipProjection.Bus.PSG;
            // DAC occupies its own normalized role.  Its sequencer-local FM6
            // storage coordinate is retained in TrackProjection instead.
            case DAC -> SmpsChannelOwnershipProjection.Bus.DAC;
        };
        int channel = track.type() == SmpsSequencer.TrackType.DAC
                ? 0 : track.channelId();
        return new SmpsChannelOwnershipProjection.PhysicalChannel(bus,
                channel);
    }

    private SmpsDriverSnapshot captureLogicalState() {
        synchronized (sequencersLock) {
            IdentityHashMap<SmpsSequencer, Integer> sequencerIds = new IdentityHashMap<>();
            IdentityHashMap<AbstractSmpsData, SmpsSourceDescriptor> sourceDescriptors = new IdentityHashMap<>();
            for (SmpsSequencer sequencer : sequencers) {
                sourceDescriptors.put(sequencer.getSmpsData(), sequencer.getSourceDescriptor());
            }
            List<SmpsDriverSnapshot.SequencerEntry> entries = new ArrayList<>(sequencers.size());
            for (int i = 0; i < sequencers.size(); i++) {
                SmpsSequencer sequencer = sequencers.get(i);
                sequencerIds.put(sequencer, i);
                AbstractSmpsData fallbackVoiceData = sequencer.getFallbackVoiceData();
                SmpsSourceDescriptor fallbackVoiceSource = null;
                if (fallbackVoiceData != null) {
                    fallbackVoiceSource = sourceDescriptors.get(fallbackVoiceData);
                    if (fallbackVoiceSource == null) {
                        fallbackVoiceSource = SmpsSourceDescriptor.from(fallbackVoiceData);
                        sourceDescriptors.put(fallbackVoiceData, fallbackVoiceSource);
                    }
                }
                entries.add(new SmpsDriverSnapshot.SequencerEntry(
                        isSfx(sequencer),
                        sequencer.getSourceDescriptor(),
                        sequencer.getSourceDescriptorTrust(),
                        fallbackVoiceSource,
                        sequencer.getSmpsData(),
                        sequencer.getDacData(),
                        sequencer.getAudioManager(),
                        sequencer.getConfig(),
                        sequencer.captureSnapshot()));
            }

            return new SmpsDriverSnapshot(
                    region,
                    readMode,
                    continuousSfxId,
                    continuousSfxFlag,
                    contSfxLoopCnt,
                    palUpdateCounter,
                    entries,
                    captureLockIds(fmLocks, sequencerIds),
                    captureLockIds(psgLocks, sequencerIds),
                    List.of(),
                    null,
                    fadeDelay,
                    fadeDelayTimeout,
                    fadeOutTimeout,
                    fadeInTimeout,
                    driverOwnedFade);
        }
    }

    /** Restores logical SMPS driver state without touching the physical device. */
    public void restoreSnapshot(SmpsDriverSnapshot snapshot) {
        restoreSnapshot(snapshot, SmpsDriverSnapshot.liveReferences());
    }

    /**
     * Restores logical SMPS driver state using a caller-provided dependency resolver.
     * This is the descriptor-backed boundary used by rewind restore paths that should
     * not depend on object identity captured inside the snapshot.
     */
    public void restoreSnapshot(
            SmpsDriverSnapshot snapshot,
            SmpsDriverSnapshot.DependencyResolver resolver) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(resolver, "resolver");
        List<SmpsDriverSnapshot.SequencerEntry> entries = snapshot.sequencers();
        List<ResolvedSequencerDependencies> resolved = resolveSequencerDependencies(entries, resolver);
        restoreLogicalState(snapshot, entries, resolved);
        observeLifecycle(SmpsDriverServiceObserver.LifecycleKind.RESTORE);
    }

    private void restoreLogicalState(
            SmpsDriverSnapshot snapshot,
            List<SmpsDriverSnapshot.SequencerEntry> entries,
            List<ResolvedSequencerDependencies> resolved) {
        synchronized (sequencersLock) {
            sequencers.clear();
            sfxSequencers.clear();
            sfxSequencersById.clear();
            Arrays.fill(fmSfxClaims, null);
            Arrays.fill(dacSfxClaims, null);
            Arrays.fill(psgSfxClaims, null);
            psgLatches.clear();
            pendingRemovals.clear();
            sfxAdmissionOrdinals.clear();
            pendingConflictOwners.clear();
            if (serviceSequencerOrdinals != null) {
                serviceSequencerOrdinals.clear();
            }
            Arrays.fill(fmLocks, null);
            Arrays.fill(psgLocks, null);

            region = snapshot.region();
            readMode = snapshot.readMode();
            continuousSfxId = snapshot.continuousSfxId();
            continuousSfxFlag = snapshot.continuousSfxFlag();
            contSfxLoopCnt = snapshot.contSfxLoopCnt();
            palUpdateCounter = snapshot.palUpdateCounter();
            fadeDelay = snapshot.fadeDelay();
            fadeDelayTimeout = snapshot.fadeDelayTimeout();
            fadeOutTimeout = snapshot.fadeOutTimeout();
            fadeInTimeout = snapshot.fadeInTimeout();
            driverOwnedFade = snapshot.driverOwnedFade();

            for (int i = 0; i < entries.size(); i++) {
                SmpsDriverSnapshot.SequencerEntry entry = entries.get(i);
                ResolvedSequencerDependencies dependency = resolved.get(i);
                SmpsSequencer sequencer = new SmpsSequencer(
                        dependency.smpsData(),
                        dependency.dacData(),
                        this, this,
                        dependency.audioManager(),
                        dependency.config(),
                        entry.source(),
                        dependency.sourceDescriptorTrust());
                sequencer.setRegion(region);
                sequencer.restoreSnapshot(entry.snapshot());
                sequencer.setIsSfx(entry.sfx());
                sequencers.add(sequencer);
                if (entry.sfx()) {
                    sfxSequencers.add(sequencer);
                    sfxSequencersById.put(
                            sequencer.getSmpsData().getId(), sequencer);
                    recordSfxClaims(sequencer);
                    trackRestoredSfxAdmission(sequencer);
                }
            }

            for (int i = 0; i < entries.size(); i++) {
                SmpsSourceDescriptor fallbackVoiceSource = entries.get(i).fallbackVoiceSource();
                if (fallbackVoiceSource != null) {
                    AbstractSmpsData fallbackData = findRestoredDataBySource(
                            entries,
                            resolved,
                            fallbackVoiceSource);
                    if (fallbackData != null) {
                        sequencers.get(i).setFallbackVoiceData(fallbackData);
                    }
                }
            }

            restoreLocks(snapshot.fmLockSequencerIds(), fmLocks);
            restoreLocks(snapshot.psgLockSequencerIds(), psgLocks);
            for (SmpsSequencer restored : sequencers) {
                if (isSfx(restored)) {
                    reportSfxAdmission(restored);
                }
            }
        }
    }

    private static List<ResolvedSequencerDependencies> resolveSequencerDependencies(
            List<SmpsDriverSnapshot.SequencerEntry> entries,
            SmpsDriverSnapshot.DependencyResolver resolver) {
        List<ResolvedSequencerDependencies> resolved = new ArrayList<>(entries.size());
        for (SmpsDriverSnapshot.SequencerEntry entry : entries) {
            AbstractSmpsData smpsData = Objects.requireNonNull(
                    resolver.resolveSmpsData(entry), "resolved SMPS data");
            boolean trustedIdentity = entry.sourceDescriptorTrust()
                    == SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE
                    && smpsData == entry.smpsData();
            SmpsSequencer.SourceDescriptorTrust effectiveTrust = trustedIdentity
                    ? SmpsSequencer.SourceDescriptorTrust.PRECOMPUTED_IMMUTABLE
                    : SmpsSequencer.SourceDescriptorTrust.LEGACY_RECOMPUTE;
            SmpsSourceDescriptor resolvedSource = trustedIdentity
                    ? entry.source() : SmpsSourceDescriptor.from(smpsData);
            if (!entry.source().matches(resolvedSource)) {
                throw new IllegalStateException(
                        "resolved SMPS source does not match snapshot source: expected "
                                + entry.source() + ", got " + resolvedSource);
            }
            resolved.add(new ResolvedSequencerDependencies(
                    smpsData,
                    Objects.requireNonNull(resolver.resolveDacData(entry), "resolved DAC data"),
                    Objects.requireNonNull(resolver.resolveAudioManager(entry), "resolved audio manager"),
                    Objects.requireNonNull(resolver.resolveConfig(entry), "resolved config"),
                    effectiveTrust));
        }
        return resolved;
    }

    private static AbstractSmpsData findRestoredDataBySource(
            List<SmpsDriverSnapshot.SequencerEntry> entries,
            List<ResolvedSequencerDependencies> restoredData,
            SmpsSourceDescriptor source) {
        for (int i = 0; i < entries.size(); i++) {
            SmpsDriverSnapshot.SequencerEntry entry = entries.get(i);
            if (entry.source().equals(source)) {
                return restoredData.get(i).smpsData();
            }
        }
        return null;
    }

    private record ResolvedSequencerDependencies(
            AbstractSmpsData smpsData,
            DacData dacData,
            MusicRestoreSink audioManager,
            SmpsSequencerConfig config,
            SmpsSequencer.SourceDescriptorTrust sourceDescriptorTrust) {
    }

    private static int[] captureLockIds(
            SmpsSequencer[] locks,
            IdentityHashMap<SmpsSequencer, Integer> sequencerIds) {
        int[] ids = new int[locks.length];
        Arrays.fill(ids, -1);
        for (int i = 0; i < locks.length; i++) {
            Integer id = sequencerIds.get(locks[i]);
            if (id != null) {
                ids[i] = id;
            }
        }
        return ids;
    }

    private void restoreLocks(int[] ids, SmpsSequencer[] target) {
        for (int i = 0; i < target.length && i < ids.length; i++) {
            int id = ids[i];
            if (id >= 0 && id < sequencers.size()) {
                target[i] = sequencers.get(id);
            }
        }
    }

    public void addSequencer(SmpsSequencer seq, boolean isSfx) {
        if (isSfx) {
            PreparedSfxAdmission admission =
                    prepareNewSfxAdmission(seq, 0, 0);
            seq.beginSfxAdmission();
            commitSfxAdmission(admission);
            return;
        }
        seq.setRegion(region);
        seq.setIsSfx(false); // Cache isSfx flag on the sequencer for O(1) lookup
        synchronized (sequencersLock) {
            // InitMusicPlayback clears the driver globals, including
            // v_special_voice_ptr, before loading a song
            // (s1.sounddriver.asm:1498-1502).
            s1SpecialVoicePointer = null;
            // ROM behavior: re-triggering the same SFX replaces the old one.
            // Without this, two sequencers for the same sound compete for the same
            // FM/PSG channels, causing lock ping-pong when priority bit 7 is set
            // (S1/S2 jump SFX priority 0x80 allows any SFX to steal the lock,
            // so the old sequencer steals back from the new one every sample).
            if (isSfx) {
                if (sfxContentionObserver != SfxContentionObserver.NONE) {
                    trackSfxAdmission(seq);
                }
                int newId = seq.getSmpsData().getId();
                SmpsSequencer existing = null;
                for (SmpsSequencer s : sfxSequencers) {
                    if (s.getSmpsData().getId() == newId) {
                        existing = s;
                        break;
                    }
                }
                if (existing != null) {
                    rememberReplacementConflicts(existing, seq);
                    sequencers.remove(existing);
                    releaseLocks(existing);
                    sfxSequencers.remove(existing);
                    sfxSequencersById.remove(
                            existing.getSmpsData().getId(), existing);
                    forgetSfxClaims(existing);
                    forgetSequencerServiceIdentity(existing);
                }

                // Channel-based SFX conflict resolution (ROM: s2.sounddriver.asm lines 2203-2266)
                // When a new SFX uses a channel already in use by another SFX, kill the old
                // SFX's track on that channel. This prevents the old SFX from resuming after
                // the new one finishes and stops noise mode from leaking through shared PSG
                // channels (e.g., DrawbridgeMove noise leaking into BLIP on PSG3).
                List<SmpsSequencer.Track> newTracks = seq.getTracks();
                Set<SmpsSequencer> deadSequencers = null;
                boolean killedPsg3Track = false;
                for (int i = 0; i < newTracks.size(); i++) {
                    SmpsSequencer.Track newTrack = newTracks.get(i);
                    for (SmpsSequencer existingSfx : sfxSequencers) {
                        List<SmpsSequencer.Track> existingTracks = existingSfx.getTracks();
                        for (int j = 0; j < existingTracks.size(); j++) {
                            SmpsSequencer.Track existingTrack = existingTracks.get(j);
                            if (existingTrack.active
                                    && existingTrack.type == newTrack.type
                                    && existingTrack.channelId == newTrack.channelId) {
                                existingTrack.active = false;
                                existingSfx.stopNote(existingTrack);
                                // Release the lock for this channel
                                if (existingTrack.type == SmpsSequencer.TrackType.FM
                                        || existingTrack.type == SmpsSequencer.TrackType.DAC) {
                                    if (fmLocks[existingTrack.channelId] == existingSfx) {
                                        rememberConflict(SfxContentionObserver.Bus.FM,
                                                existingTrack.channelId, existingSfx, seq);
                                        fmLocks[existingTrack.channelId] = null;
                                        updateOverrides(SmpsSequencer.TrackType.FM,
                                                existingTrack.channelId, false);
                                    }
                                } else if (existingTrack.type == SmpsSequencer.TrackType.PSG) {
                                    if (psgLocks[existingTrack.channelId] == existingSfx) {
                                        rememberConflict(SfxContentionObserver.Bus.PSG,
                                                existingTrack.channelId, existingSfx, seq);
                                        psgLocks[existingTrack.channelId] = null;
                                        updateOverrides(SmpsSequencer.TrackType.PSG,
                                                existingTrack.channelId, false);
                                    }
                                    if (existingTrack.channelId == 2) {
                                        killedPsg3Track = true;
                                    }
                                }
                            }
                        }
                        // If all tracks in existing SFX are now inactive, mark for removal
                        boolean allInactive = true;
                        for (int j = 0; j < existingTracks.size(); j++) {
                            if (existingTracks.get(j).active) {
                                allInactive = false;
                                break;
                            }
                        }
                        if (allInactive) {
                            if (deadSequencers == null) deadSequencers = new LinkedHashSet<>();
                            deadSequencers.add(existingSfx);
                        }
                    }
                }
                if (deadSequencers != null) {
                    for (SmpsSequencer dead : deadSequencers) {
                        sequencers.remove(dead);
                        releaseLocks(dead);
                        sfxSequencers.remove(dead);
                        sfxSequencersById.remove(
                                dead.getSmpsData().getId(), dead);
                        forgetSfxClaims(dead);
                        forgetSequencerServiceIdentity(dead);
                    }
                }

                // ROM lines 2221-2228: when PSG3 SFX replaces another, silence both
                // tone2 and noise. stopNote() only silences one (tone or noise depending
                // on noiseMode), so this ensures both are cleaned up to prevent noise
                // mode leaking from the old SFX.
                if (killedPsg3Track) {
                    writeRawPsg(0xDF); // silence PSG3 (tone2): 0x80|(2<<5)|(1<<4)|0x0F
                    writeRawPsg(0xFF); // silence noise channel: 0x80|(3<<5)|(1<<4)|0x0F
                }
            }
            selectDac(seq.getSourceDescriptor(), seq.getDacData());
            if (seq.getConfig().isEnableDacOnSequencerStart()) {
                // The S3K Z80 driver never enables the DAC when a song loads:
                // zPlayDigitalAudio disables it on entry and only writes
                // 2Bh = 80h from its idle loop, after zDACIndex goes non-zero
                // and outside the V-int service that queued the sample
                // (skdisasm Sound/Z80 Sound Driver.asm:4256-4275).
                writeFm(seq, 0, 0x2B, 0x80);
            }
            sequencers.add(seq);
            if (isSfx) {
                sfxSequencers.add(seq);
                sfxSequencersById.put(seq.getSmpsData().getId(), seq);
                recordSfxClaims(seq);
                reportSfxAdmission(seq);
            }
        }
    }

    /** Installs an opt-in listener which receives completed lock decisions only. */
    public void setSfxContentionObserver(SfxContentionObserver observer) {
        Objects.requireNonNull(observer, "observer");
        synchronized (sequencersLock) {
            sfxContentionObserver = observer;
            if (observer == SfxContentionObserver.NONE) {
                sfxAdmissionOrdinals.clear();
                pendingConflictOwners.clear();
                return;
            }
            for (SmpsSequencer sequencer : sequencers) {
                if (isSfx(sequencer) && !sfxAdmissionOrdinals.containsKey(sequencer)) {
                    admitSfx(sequencer);
                }
            }
        }
    }

    /** Returns the installed diagnostic observer, normally {@link SfxContentionObserver#NONE}. */
    public SfxContentionObserver sfxContentionObserver() {
        return sfxContentionObserver;
    }

    int trackedSfxAdmissionCountForTesting() {
        return sfxAdmissionOrdinals.size();
    }

    /**
     * Restores the music (non-SFX) sequencer's DAC data on the shared synthesizer.
     * Called after committing an SFX sequencer whose initialization selected its bank.
     */
    private void restoreMusicDacData() {
        for (int i = 0; i < sequencers.size(); i++) {
            SmpsSequencer s = sequencers.get(i);
            if (!isSfx(s) && s.getDacData() != null) {
                selectDac(s.getSourceDescriptor(), s.getDacData());
                return;
            }
        }
    }

    public void stopAll() {
        synchronized (sequencersLock) {
            sequencers.clear();
            sfxSequencers.clear();
            sfxSequencersById.clear();
            Arrays.fill(fmSfxClaims, null);
            Arrays.fill(dacSfxClaims, null);
            Arrays.fill(psgSfxClaims, null);
            sfxAdmissionOrdinals.clear();
            pendingConflictOwners.clear();
            if (serviceSequencerOrdinals != null) {
                serviceSequencerOrdinals.clear();
            }
            for (int i = 0; i < 6; i++)
                fmLocks[i] = null;
            for (int i = 0; i < 4; i++)
                psgLocks[i] = null;
            psgLatches.clear();
            continuousSfxId = 0;
            continuousSfxFlag = false;
            contSfxLoopCnt = 0;
            // StopAllSound clears the driver RAM globals, v_special_voice_ptr
            // included (s1.sounddriver.asm:1468-1478). S3K's zStopAllSound
            // zeroes zTempVariablesStart..zTempVariablesEnd, which contains
            // zDACIndex (skdisasm Sound/Z80 Sound Driver.asm:134,163,214,
            // :2461-2470), so a sample queued but not yet seen by the idle
            // loop is discarded and the DAC stays disabled by the same
            // routine's own 2Bh = 0 (:2508-2511).
            s1SpecialVoicePointer = null;
            dacQueuedSinceIdleLoopPass = false;
        }
        // Silence hardware (ROM: zFMSilenceAll + zPSGSilenceAll)
        silenceAll();
        observeLifecycle(SmpsDriverServiceObserver.LifecycleKind.STOP_ALL);
    }

    /**
     * Stop all SFX sequencers, releasing their channel locks and silencing them.
     * Used when starting override music to prevent partial SFX playback on restore.
     */
    @Override
    public int fadeDelay() {
        return fadeDelay;
    }

    @Override
    public void setFadeDelay(int value) {
        fadeDelay = value & 0xFF;
    }

    @Override
    public int fadeDelayTimeout() {
        return fadeDelayTimeout;
    }

    @Override
    public void setFadeDelayTimeout(int value) {
        fadeDelayTimeout = value & 0xFF;
    }

    /**
     * Arms the driver's fade delay pair, as {@code zFadeOutMusic} and
     * {@code zFadeInToPrevious} both do before anything else
     * (skdisasm Sound/Z80 Sound Driver.asm:2306-2312, :2784-2789). Neither
     * routine looks for a song first, so this records the value even when no
     * music is installed, which is the case the song-owned copy could not
     * represent at all.
     */
    public void armFadeDelay(int value) {
        setFadeDelay(value);
        setFadeDelayTimeout(value);
        driverOwnedFade = true;
    }

    /**
     * Arms a fade out on the driver, as {@code zFadeOutMusic} does: the step
     * counter first, then both halves of the delay pair, none of it
     * conditional on a song being loaded (skdisasm Sound/Z80 Sound
     * Driver.asm:2306-2312).
     */
    public void armFadeOut(int steps, int delay) {
        setFadeStepCounter(true, steps);
        armFadeDelay(delay);
    }

    @Override
    public int fadeStepCounter(boolean fadeOut) {
        return fadeOut ? fadeOutTimeout : fadeInTimeout;
    }

    @Override
    public void setFadeStepCounter(boolean fadeOut, int value) {
        if (fadeOut) {
            fadeOutTimeout = value & 0xFF;
        } else {
            fadeInTimeout = value & 0xFF;
        }
        driverOwnedFade = true;
    }

    /**
     * Runs {@code zDoMusicFadeOut}'s delay step for a driver with no song
     * loaded (skdisasm Sound/Z80 Sound Driver.asm:2331-2346). The routine is
     * called from {@code zUpdateMusic} every service and tests only
     * {@code zFadeOutTimeout}, so the counters keep moving with nothing to
     * apply the volume change to. A song of its own drives this through
     * {@code SmpsSequencer.processFade} instead.
     */
    /**
     * {@code zUpdateMusic} runs {@code TempoWait} and both fade handlers
     * before it reaches {@code zFillSoundQueue} (skdisasm Sound/Z80 Sound
     * Driver.asm:659-701), so a fade armed by this service's own request is
     * not stepped until the next one. A song drives its own fade through
     * {@code SmpsSequencer.processFade}; this covers the case where there is
     * none to drive it.
     */
    private void stepSonglessFadeIfNoSong() {
        if (firstMusicSequencerLocked() == null) {
            stepSonglessFade();
        }
    }

    private void stepSonglessFade() {
        if (!driverOwnedFade || fadeOutTimeout == 0) {
            return;
        }
        fadeDelayTimeout = (fadeDelayTimeout - 1) & 0xFF;
        if (fadeDelayTimeout != 0) {
            return;
        }
        fadeDelayTimeout = fadeDelay;
        int previousFadeSteps = fadeOutTimeout;
        fadeOutTimeout = (fadeOutTimeout - 1) & 0xFF;
        completeHostFadeIfTerminal(previousFadeSteps);
    }

    private boolean completeHostFadeIfTerminal(int previousFadeSteps) {
        return previousFadeSteps != 0 && driverOwnedFade && fadeOutTimeout == 0
                && synthesizer instanceof SmpsDriverSessionAccess access
                && access.completeFadeOut();
    }

    public void stopAllSfx() {
        synchronized (sequencersLock) {
            sfxRemovalBuffer.clear();
            sfxRemovalBuffer.addAll(sfxSequencers);
            for (int i = 0; i < sfxRemovalBuffer.size(); i++) {
                SmpsSequencer sfx = sfxRemovalBuffer.get(i);
                sequencers.remove(sfx);
                releaseLocks(sfx);
                sfxSequencers.remove(sfx);
                sfxSequencersById.remove(sfx.getSmpsData().getId(), sfx);
                forgetSfxClaims(sfx);
                sfxAdmissionOrdinals.remove(sfx);
                forgetSequencerServiceIdentity(sfx);
            }
            pendingConflictOwners.clear();
            continuousSfxId = 0;
            continuousSfxFlag = false;
            contSfxLoopCnt = 0;
        }
        observeLifecycle(
                SmpsDriverServiceObserver.LifecycleKind.STOP_ALL_SFX);
    }

    /**
     * Releases the logical SFX slots without emitting channel restoration
     * writes or clearing the continuous-SFX bookkeeping bytes. S3K
     * {@code zStopSFX} owns a distinct seven-slot physical-write program; the
     * session uses this boundary until that program is ported in full.
     */
    public void stopAllSfxWithoutRestoreWrites() {
        synchronized (sequencersLock) {
            sfxRemovalBuffer.clear();
            sfxRemovalBuffer.addAll(sfxSequencers);
            for (int i = 0; i < sfxRemovalBuffer.size(); i++) {
                SmpsSequencer sfx = sfxRemovalBuffer.get(i);
                sequencers.remove(sfx);
                releaseLocksWithoutRestoreWrites(sfx);
                sfxSequencers.remove(sfx);
                sfxSequencersById.remove(sfx.getSmpsData().getId(), sfx);
                forgetSfxClaims(sfx);
                sfxAdmissionOrdinals.remove(sfx);
                forgetSequencerServiceIdentity(sfx);
            }
            pendingConflictOwners.clear();
        }
        observeLifecycle(
                SmpsDriverServiceObserver.LifecycleKind.STOP_ALL_SFX);
    }

    /**
     * Advances direct-read logical cadence while a separate owner renders the
     * physical frames. Presentation never calls this compatibility boundary.
     */
    public int readDirect(
            short[] buffer,
            int length,
            DirectPcmRenderer renderer) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(renderer, "renderer");
        if (length < 0 || length > buffer.length || (length & 1) != 0) {
            throw new IllegalArgumentException(
                    "length must be an even count within the target buffer");
        }
        return readMode == ReadMode.HYBRID
                ? readDirectHybrid(buffer, length, renderer)
                : readDirectSampleAccurate(buffer, length, renderer);
    }

    private int readDirectSampleAccurate(
            short[] buffer, int length, DirectPcmRenderer renderer) {
        int frames = length / 2;
        synchronized (sequencersLock) {
            for (int frame = 0; frame < frames; frame++) {
                advanceSequencersBatch(1);
                removeCompletedSequencers();
                renderer.render(buffer, frame, 1);
            }
        }
        return length;
    }

    private int readDirectHybrid(
            short[] buffer, int length, DirectPcmRenderer renderer) {
        int frames = length / 2;
        hybridChunkCountForTesting = 0;
        synchronized (sequencersLock) {
            int frameIndex = 0;
            while (frameIndex < frames) {
                if (requiresSampleAccurateFallback()) {
                    renderDirectSample(buffer, frameIndex++, renderer);
                    continue;
                }
                int safeChunk = computeSafeChunkSamples(
                        frames - frameIndex);
                if (safeChunk < MIN_BATCH_SAMPLES) {
                    renderDirectSample(buffer, frameIndex++, renderer);
                    continue;
                }
                advanceSequencersBatch(safeChunk);
                removeCompletedSequencers();
                renderer.render(buffer, frameIndex, safeChunk);
                hybridChunkCountForTesting++;
                frameIndex += safeChunk;
            }
        }
        return length;
    }

    private void renderDirectSample(
            short[] buffer,
            int frameIndex,
            DirectPcmRenderer renderer) {
        advanceSequencersBatch(1);
        removeCompletedSequencers();
        renderer.render(buffer, frameIndex, 1);
    }

    // Ephemeral service receipt, cleared before each presentation; not rewind state.
    private boolean sfxTrackStoppedDuringService;

    public void clearTrackStopReceipt() {
        sfxTrackStoppedDuringService = false;
    }

    public boolean sfxTrackStoppedDuringService() {
        return sfxTrackStoppedDuringService;
    }

    @Override
    public void onSfxTrackStop(SmpsSequencer sequencer) {
        if (isSfx(sequencer)) {
            sfxTrackStoppedDuringService = true;
        }
    }

    /** Runs one V-blank-owned service for every sequencer in this driver. */
    public void serviceOuterFrame() {
        synchronized (sequencersLock) {
            SmpsSequencer music = firstMusicSequencerLocked();
            SmpsSequencerConfig.PalUpdateMode palMode = music == null
                    ? SmpsSequencerConfig.PalUpdateMode.NONE
                    : music.getConfig().getPalUpdateMode();
            if (palMode == SmpsSequencerConfig.PalUpdateMode.EXTRA_FULL) {
                serviceSfxThenMusic();
                if (region == SmpsSequencer.Region.PAL) {
                    // S&K fix_sndbugs=0: test before decrement; zero reloads 5,
                    // repeats zUpdateEverything, then the re-check stores 4.
                    if (palUpdateCounter == 0) {
                        palUpdateCounter = 5;
                        serviceSfxThenMusic();
                    }
                    palUpdateCounter--;
                }
            } else {
                if (palMode == SmpsSequencerConfig.PalUpdateMode.EXTRA_MUSIC
                        && region == SmpsSequencer.Region.PAL
                        && music != null
                        && !music.getSmpsData().isPalSpeedupDisabled()) {
                    // S2 FixDriverBugs=0: decrement first; zero reloads 5 and
                    // calls zUpdateMusic once before the normal music pass.
                    palUpdateCounter--;
                    if (palUpdateCounter == 0) {
                        palUpdateCounter = 5;
                        serviceSequencers(false);
                    }
                }
                // The queue is consumed before the music walk here too. This
                // branch is reached whenever no music sequencer sets the S3K
                // service order, which includes every service before the first
                // song exists, and a song loaded by this service is walked by
                // it: zCycleSoundQueue runs at :698-701 and .update_music
                // follows at :702. SmpsSequencer.primeFirstService owns what
                // that first walk does.
                stepSonglessFadeIfNoSong();
                runPendingServiceRequest();
                serviceSequencers(false);
                serviceSequencers(true);
            }
            // Safety net only: both branches above consume the queue at their
            // own point. A request must never survive its service, because the
            // next one would consume it instead.
            runPendingServiceRequest();
            removeCompletedSequencers();
        }
    }

    private void serviceSfxThenMusic() {
        SmpsSequencer music = firstMusicSequencerLocked();
        serviceSequencers(true);
        if (music != null) {
            int previousFadeSteps = fadeOutTimeout;
            music.serviceS3kSpeedupTail();
            if (completeHostFadeIfTerminal(previousFadeSteps)) {
                music = null;
            }
        }
        if (music != null) {
            int previousFadeSteps = fadeOutTimeout;
            music.serviceFadeStepAheadOfRequest();
            completeHostFadeIfTerminal(previousFadeSteps);
        }
        stepSonglessFadeIfNoSong();
        runPendingServiceRequest();
        music = firstMusicSequencerLocked();
        serviceSequencers(false);
        if (music != null) {
            int previousFadeSteps = fadeOutTimeout;
            music.serviceS3kSpeedupTail();
            completeHostFadeIfTerminal(previousFadeSteps);
        }
    }

    /**
     * Runs the request the host handed to this service, at the point
     * {@code zUpdateEverything} reaches it.
     *
     * <p>The ROM's V-blank service is an order, not a set. It runs
     * {@code zPauseUnpause} and {@code zUpdateSFXTracks}, then
     * {@code zUpdateMusic}'s {@code TempoWait} and both fade handlers, and only
     * then loads {@code zMusicNumber} and hands it to {@code zFillSoundQueue}
     * and {@code zCycleSoundQueue} (skdisasm Sound/Z80 Sound
     * Driver.asm:653-701). So the tracks playing when a request arrives are
     * walked by the very service that consumes it, before it is consumed, and
     * the music walk that follows sees whatever the request left behind.
     *
     * <p>A request applied before the service instead loses that walk
     * entirely: a music change tears the tracks down first, and the frequency
     * their last update owed the chip is never sent. The music slot is re-read
     * after the request because a music change replaces it.
     */
    private void runPendingServiceRequest() {
        if (pendingServiceRequests.isEmpty()) {
            return;
        }
        // zFillSoundQueue transfers the three 68k bytes and zCycleSoundQueue is
        // then called three times, playing the first, second and third entries
        // in that order (skdisasm Sound/Z80 Sound Driver.asm:698-701). A
        // service can therefore consume a song and two sound effects at once,
        // so this runs them in submission order rather than taking only one.
        List<Runnable> requests = new ArrayList<>(pendingServiceRequests);
        pendingServiceRequests.clear();
        Set<SmpsSequencer> before = new HashSet<>(sfxSequencers);
        for (Runnable request : requests) {
            request.run();
        }
        for (SmpsSequencer sequencer : sfxSequencers) {
            if (!before.contains(sequencer)) {
                sequencer.markAdmittingServiceWalkMissed();
            }
        }
    }

    /**
     * Hands this service a request to consume at the ROM's own consume point.
     * Only the S3K service order routes through {@code serviceSfxThenMusic},
     * so a host that submits one to a driver servicing in any other order
     * would see it dropped; the assertion below keeps that from being silent.
     */
    public void submitServiceRequest(Runnable request) {
        Objects.requireNonNull(request, "request");
        synchronized (sequencersLock) {
            if (pendingServiceRequests.size() >= SOUND_QUEUE_SLOTS) {
                throw new IllegalStateException(
                        "the sound queue holds only " + SOUND_QUEUE_SLOTS
                                + " entries per service");
            }
            pendingServiceRequests.add(request);
        }
    }

    private void serviceSequencers(boolean sfx) {
        if (sfx && usesChannelRamOrderSfxWalk()) {
            serviceSfxByChannelRamOrder();
            return;
        }
        int size = sequencers.size();
        for (int index = 0; index < size; index++) {
            SmpsSequencer sequencer = sequencers.get(index);
            if (isSfx(sequencer) != sfx) {
                continue;
            }
            sequencer.serviceOuterFrame();
            if (sequencer.isComplete() && !retainsFinishedMusic(sequencer, sfx)) {
                pendingRemovals.add(sequencer);
            }
        }
    }

    /**
     * Whether a music sequencer whose tracks have all ended must keep being
     * serviced. S1's {@code UpdateMusic} decrements
     * {@code v_main_tempo_timeout} and reloads it through {@code TempoWait}
     * before it looks at any track, and unconditionally
     * (s1.sounddriver.asm:174-176, :1549-1560), so the driver's tempo keeps
     * running after a song's last track stops; the RAM only stops changing
     * when a new song loads or {@code StopAllSound} clears it. The engine
     * treated an all-tracks-inactive music sequencer as finished and stopped
     * servicing it, which froze the tempo state a song end onward.
     *
     * <p>SFX sequencers are unaffected: those genuinely are released when they
     * end, and the ROM frees their slots.
     */
    private static boolean retainsFinishedMusic(SmpsSequencer sequencer, boolean sfx) {
        return !sfx && sequencer.getConfig().isDirect68kDriver();
    }

    private boolean usesChannelRamOrderSfxWalk() {
        for (int index = 0; index < sequencers.size(); index++) {
            SmpsSequencer sequencer = sequencers.get(index);
            if (!isSfx(sequencer)) {
                continue;
            }
            if (sequencer.getConfig().getSfxTrackWalkMode()
                    != SmpsSequencerConfig.SfxTrackWalkMode.CHANNEL_RAM_ORDER) {
                return false;
            }
        }
        return true;
    }

    /**
     * Services every live SFX program by walking the fixed SFX track slots.
     *
     * <p>S1 {@code UpdateMusic} owns one array of SFX track slots and walks it
     * in RAM order -- SFX FM3..FM5 (s1.sounddriver.asm:222-231) then SFX
     * PSG1..PSG3 (:233-241) -- with no notion of which sound owns a slot. So
     * when a second sound is admitted while the first is still playing, their
     * tracks interleave by channel rather than by admission order: a ring on
     * FM4 is serviced before a still-playing jump on PSG1, even though the jump
     * started fourteen invocations earlier. The two special-SFX slots come
     * after the whole SFX block (:258-268); see {@link
     * SmpsSequencer#sfxSlotWalkOrder}.
     */
    private void serviceSfxByChannelRamOrder() {
        List<SmpsSequencer> pass = null;
        List<SlotWalkEntry> walk = null;
        int size = sequencers.size();
        for (int index = 0; index < size; index++) {
            SmpsSequencer sequencer = sequencers.get(index);
            if (!isSfx(sequencer)) {
                continue;
            }
            if (pass == null) {
                pass = new ArrayList<>();
            }
            pass.add(sequencer);
            for (SmpsSequencer.Track track : sequencer.beginSfxSlotWalkPass()) {
                if (walk == null) {
                    walk = new ArrayList<>();
                }
                walk.add(new SlotWalkEntry(
                        SmpsSequencer.sfxSlotWalkOrder(
                                track, sequencer.isSpecialSfx()),
                        walk.size(),
                        sequencer, track));
            }
        }
        if (pass == null) {
            return;
        }
        if (walk != null) {
            walk.sort(SLOT_WALK_ORDER);
            for (int index = 0; index < walk.size(); index++) {
                SlotWalkEntry entry = walk.get(index);
                entry.sequencer().tickSfxSlotWalkTrack(entry.track());
            }
        }
        for (int index = 0; index < pass.size(); index++) {
            SmpsSequencer sequencer = pass.get(index);
            sequencer.finishSfxSlotWalkPass();
            if (sequencer.isComplete()) {
                pendingRemovals.add(sequencer);
            }
        }
    }

    private record SlotWalkEntry(
            int slot, int arrival, SmpsSequencer sequencer,
            SmpsSequencer.Track track) {
    }

    private static final Comparator<SlotWalkEntry> SLOT_WALK_ORDER =
            Comparator.comparingInt(SlotWalkEntry::slot)
                    .thenComparingInt(SlotWalkEntry::arrival);

    private SmpsSequencer firstMusicSequencerLocked() {
        for (int index = 0; index < sequencers.size(); index++) {
            SmpsSequencer sequencer = sequencers.get(index);
            if (!isSfx(sequencer)) {
                return sequencer;
            }
        }
        return null;
    }

    private boolean requiresSampleAccurateFallback() {
        for (int i = 0; i < sequencers.size(); i++) {
            if (sequencers.get(i).requiresSampleAccurateFallback()) {
                return true;
            }
        }
        return false;
    }

    // The tempo cap is nextTempoFrame - 1 because sample-accurate mode advances one
    // sample before rendering it; the boundary sample must stay on that path so the
    // pre-boundary samples do not render with post-tick state.
    private int computeSafeChunkSamples(int maxFrames) {
        int safe = maxFrames;
        for (int i = 0; i < sequencers.size(); i++) {
            SmpsSequencer seq = sequencers.get(i);
            int preTempoSafe = Math.max(0, seq.getSamplesUntilNextTempoFrame() - 1);
            safe = Math.min(safe, preTempoSafe);
            int preEventSafe = Math.max(0, seq.getSamplesUntilNextObservableEvent() - 1);
            safe = Math.min(safe, preEventSafe);
        }
        return safe;
    }

    private void advanceSequencersBatch(int frames) {
        int size = sequencers.size();
        for (int i = 0; i < size; i++) {
            SmpsSequencer seq = sequencers.get(i);
            int previousFadeSteps = fadeOutTimeout;
            seq.advanceBatch(frames);
            if (completeHostFadeIfTerminal(previousFadeSteps)) {
                // Global stop has replaced the sequencer list and save area.
                return;
            }
            if (seq.isComplete()) {
                pendingRemovals.add(seq);
            }
        }
    }

    private void removeCompletedSequencers() {
        while (!pendingRemovals.isEmpty()) {
            SmpsSequencer seq = pendingRemovals.getFirst();
            SmpsDriverServiceObserver.ServiceEvent service =
                    beginSequencerService(seq,
                            SmpsDriverServiceObserver.ServiceKind
                                    .COMPLETION_CLEANUP);
            try {
                pendingRemovals.removeFirst();
                sequencers.remove(seq);
                releaseLocks(seq);
                sfxSequencers.remove(seq);
                sfxSequencersById.remove(seq.getSmpsData().getId(), seq);
                forgetSfxClaims(seq);
                endSequencerService(service);
            } finally {
                forgetSequencerServiceIdentity(seq);
            }
        }
    }

    /**
     * Tool-facing: detaches every sequencer that has completed, releasing its
     * channel locks exactly as the render loop's completion cleanup would.
     * Parity capture hosts advance sequencers directly and never call
     * direct-read cadence, so they invoke this once per driver tick.
     */
    public void reapCompletedSequencers() {
        synchronized (sequencersLock) {
            for (int i = 0; i < sequencers.size(); i++) {
                SmpsSequencer seq = sequencers.get(i);
                if (seq.isComplete() && !pendingRemovals.contains(seq)
                        && !retainsFinishedMusic(seq, isSfx(seq))) {
                    pendingRemovals.add(seq);
                }
            }
            removeCompletedSequencers();
        }
    }

    public boolean isComplete() {
        return sequencers.isEmpty();
    }

    /**
     * Check if a source is an SFX sequencer.
     * Uses cached isSfx field on SmpsSequencer for O(1) lookup instead of HashSet.contains().
     */
    private boolean isSfx(Object source) {
        if (source instanceof SmpsSequencer seq) {
            return seq.isSfx();
        }
        // Fallback to HashSet for non-SmpsSequencer sources (shouldn't happen normally)
        return sfxSequencers.contains(source);
    }

    /**
     * S3K's {@code cfStopTrack} hands the channel back inside the flag: it
     * clears the overridden music track's bit and sends that track's FM
     * instrument before the music update of the same service runs
     * (skdisasm Sound/Z80 Sound Driver.asm:3059-3086). Only an SFX track's
     * end does this; the ROM gates the whole tail on {@code zUpdatingSFX}
     * (:3050-3054).
     */
    @Override
    public void releaseChannelToMusic(SmpsSequencer sequencer,
            SmpsSequencer.Track endingTrack) {
        if (!isSfx(sequencer)) {
            return;
        }
        SmpsSequencer.TrackType type = endingTrack.type;
        int channelId = endingTrack.channelId;
        SmpsSequencer[] locks = type == SmpsSequencer.TrackType.PSG ? psgLocks : fmLocks;
        if (channelId < 0 || channelId >= locks.length
                || locks[channelId] != sequencer
                || hasOtherActiveTrack(sequencer, endingTrack)) {
            return;
        }
        locks[channelId] = null;
        if (type == SmpsSequencer.TrackType.PSG) {
            if (psgSfxClaims[channelId] == sequencer) {
                psgSfxClaims[channelId] = null;
            }
            if (channelId == PSG_TONE3_CHANNEL
                    && endingTrack.noiseMode
                    && psgLocks[PSG_NOISE_CHANNEL] == sequencer) {
                psgLocks[PSG_NOISE_CHANNEL] = null;
            }
        }
        updateOverridesAfterSfxTrackStop(type, channelId);
    }

    private static boolean hasOtherActiveTrack(
            SmpsSequencer sequencer, SmpsSequencer.Track endingTrack) {
        for (SmpsSequencer.Track track : sequencer.getTracks()) {
            if (track != endingTrack && track.active
                    && track.type == endingTrack.type
                    && track.channelId == endingTrack.channelId) {
                return true;
            }
        }
        return false;
    }

    private void releaseLocks(SmpsSequencer seq) {
        boolean isSfx = isSfx(seq);
        for (int i = 0; i < 6; i++) {
            if (fmLocks[i] == seq) {
                // If this was an SFX, ensure the channel is silenced before handing it back.
                if (isSfx && seq.getConfig().getFmSfxReleaseMode()
                        == SmpsSequencerConfig.FmSfxReleaseMode.LEGACY_FULL_RESTORE) {
                    seq.forceSilence(SmpsSequencer.TrackType.FM, i);
                } else if (isSfx) {
                    keyOffTornDownSfxTrack(seq, i);
                }
                fmLocks[i] = null;
                updateOverrides(SmpsSequencer.TrackType.FM, i, false);
            }
        }
        for (int i = 0; i < 4; i++) {
            if (psgLocks[i] == seq) {
                if (isSfx && seq.getConfig().getPsgSfxReleaseMode()
                        != SmpsSequencerConfig.PsgSfxReleaseMode.ROM_REST_RESTORE) {
                    // Preserve the established wholesale-teardown silence for
                    // profiles whose ROM-specific policy is scoped to F2.
                    seq.forceSilence(SmpsSequencer.TrackType.PSG, i);
                }
                psgLocks[i] = null;
                updateOverrides(SmpsSequencer.TrackType.PSG, i, false);
            }
        }
        // Clear cached PSG latch channel and remove from fallback HashMap
        seq.setPsgLatchChannel(-1);
        psgLatches.remove(seq);
        sfxAdmissionOrdinals.remove(seq);
        pendingConflictOwners.keySet().removeIf(key -> key.challenger() == seq);
    }

    /**
     * A track leaving an SFX runs {@code cfStopTrack}, which clears the
     * playing flag and then sends {@code zKeyOffIfActive} for the channel
     * before any voice restore (skdisasm Sound/Z80 Sound Driver.asm:3443-3462).
     * A sequencer torn down wholesale never executes that flag, so the key off
     * is issued here on its behalf; without it the outgoing SFX's note keeps
     * sounding on the channel it just gave back.
     *
     * <p>{@code zKeyOffIfActive} returns without writing when the track's
     * SFX-overriding or do-not-attack bit is set (:3338-3341), which is the
     * {@code overridden} test below. The write bypasses the lock table because
     * the ROM's own key off goes straight out through {@code zWriteFMI}.
     */
    private void keyOffTornDownSfxTrack(SmpsSequencer seq, int channel) {
        for (SmpsSequencer.Track track : seq.getTracks()) {
            if (track.type != SmpsSequencer.TrackType.FM
                    || track.channelId != channel) {
                continue;
            }
            if (!track.active || track.overridden) {
                return;
            }
            int keyOffSelect = channel < 3 ? channel : (channel % 3) + 4;
            writeRawFm(0, 0x28, keyOffSelect);
            return;
        }
    }

    private void releaseLocksWithoutRestoreWrites(SmpsSequencer seq) {
        for (int i = 0; i < fmLocks.length; i++) {
            if (fmLocks[i] == seq) {
                fmLocks[i] = null;
                updateOverridesWithoutRestore(
                        SmpsSequencer.TrackType.FM, i, false);
            }
        }
        for (int i = 0; i < psgLocks.length; i++) {
            if (psgLocks[i] == seq) {
                psgLocks[i] = null;
                updateOverridesWithoutRestore(
                        SmpsSequencer.TrackType.PSG, i, false);
            }
        }
        seq.setPsgLatchChannel(-1);
        psgLatches.remove(seq);
        sfxAdmissionOrdinals.remove(seq);
        pendingConflictOwners.keySet().removeIf(
                key -> key.challenger() == seq);
    }

    /** Releases stopped tracks while their sibling SFX tracks remain active. */
    public void reconcileInactiveSfxTracks(SmpsSequencer sequencer) {
        reconcileInactiveSfxTracks(sequencer, false);
    }

    @Override
    public void reconcileFinishedSfxSlot(SmpsSequencer sequencer) {
        reconcileInactiveSfxTracks(sequencer, true);
    }

    private void reconcileInactiveSfxTracks(
            SmpsSequencer sequencer, boolean includeCompleted) {
        if (!isSfx(sequencer)
                || (!includeCompleted && sequencer.isComplete())) {
            return;
        }
        for (int index = 0; index < sequencer.trackCount(); index++) {
            SmpsSequencer.Track track = sequencer.trackAt(index);
            if (!track.active) {
                forgetSfxClaim(sequencer, track);
            }
        }
        for (int channel = 0; channel < fmLocks.length; channel++) {
            if (fmLocks[channel] != sequencer
                    || hasActiveTrack(sequencer, channel, false)) {
                continue;
            }
            if (sequencer.getConfig().getFmSfxReleaseMode()
                    == SmpsSequencerConfig.FmSfxReleaseMode.LEGACY_FULL_RESTORE) {
                sequencer.forceSilence(SmpsSequencer.TrackType.FM, channel);
            }
            SmpsSequencer waiting = waitingSpecialSfx(
                    SmpsSequencer.TrackType.FM, channel, sequencer);
            if (waiting != null) {
                fmLocks[channel] = waiting;
                waiting.setChannelOverridden(
                        SmpsSequencer.TrackType.FM, channel, false);
                continue;
            }
            fmLocks[channel] = null;
            updateOverrides(SmpsSequencer.TrackType.FM, channel, false);
        }
        // Two passes, and the split is a ROM requirement rather than tidiness.
        // A released music PSG track writes during its override update: a PSG3
        // noise track re-latches its stored PSGNoise byte
        // (zStopPSGSFXTrack, s2.sounddriver.asm:3581-3587). That byte's
        // ownership channel is the noise channel, not the track's own, so
        // releasing channel by channel would run the write while a later
        // channel this same sequencer still holds is suppressing it. The ROM
        // has no per-channel hardware ownership to be half-released: it clears
        // the override bit on the music track and the write goes out.
        boolean[] releasedPsg = new boolean[psgLocks.length];
        for (int channel = 0; channel < psgLocks.length; channel++) {
            if (psgLocks[channel] != sequencer
                    || hasActiveTrack(sequencer, channel, true)) {
                continue;
            }
            if (sequencer.getConfig().getPsgSfxReleaseMode()
                    != SmpsSequencerConfig.PsgSfxReleaseMode.ROM_REST_RESTORE) {
                sequencer.forceSilence(SmpsSequencer.TrackType.PSG, channel);
            }
            SmpsSequencer waitingPsg = waitingSpecialSfx(
                    SmpsSequencer.TrackType.PSG, channel, sequencer);
            if (waitingPsg != null) {
                psgLocks[channel] = waitingPsg;
                waitingPsg.setChannelOverridden(
                        SmpsSequencer.TrackType.PSG, channel, false);
                continue;
            }
            psgLocks[channel] = null;
            releasedPsg[channel] = true;
        }
        for (int channel = 0; channel < releasedPsg.length; channel++) {
            if (releasedPsg[channel]) {
                updateOverrides(SmpsSequencer.TrackType.PSG, channel, false);
            }
        }
    }

    /**
     * The special SFX waiting on a channel a normal SFX is about to release,
     * or {@code null} when nothing is waiting.
     *
     * <p>{@code cfStopTrack}'s FM4 case tests {@code v_spcsfx_fm4_track}'s
     * PlaybackControl before anything else (s1.sounddriver.asm:2512-2518). When
     * a special SFX is playing it restores into the *special* track using
     * {@code v_special_voice_ptr}, the special SFX's own voice table, and never
     * reaches the music track, so the music override bit survives the release.
     * PSG3 takes the same shape through {@code .getpsgptr} (:2540-2547). The
     * restore itself is {@code .gotpointer} (:2529-2533, PSG at :2554-2556):
     * clear the track's
     * 'SFX overriding' bit, set 'track at rest', and reload its current voice.
     */
    private SmpsSequencer waitingSpecialSfx(
            SmpsSequencer.TrackType type, int channel, SmpsSequencer releasing) {
        for (SmpsSequencer candidate : sfxSequencers) {
            if (candidate == releasing || !candidate.isSpecialSfx()) {
                continue;
            }
            if (hasActiveTrack(candidate, channel,
                    type == SmpsSequencer.TrackType.PSG)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Whether {@code sequencer} still drives {@code channel}.
     *
     * <p>A PSG track in noise mode owns two hardware channels, not one. The
     * ROM keeps the noise channel on the PSG3 track slot itself: {@code
     * zStopPSGTrack} decides whether to re-send a stored noise byte by testing
     * {@code PlaybackControl} bit 0 -- "Is this a noise channel?" -- on that
     * track, and reads {@code zTrack.PSGNoise} out of the same slot
     * (Sound/Z80 Sound Driver.asm:3520-3527). There is no separate noise track
     * to hold the ownership, so a track whose {@code channelId} is PSG3 keeps
     * the noise channel alive for as long as it is active.
     *
     * <p>Matching only on {@code channelId} released the noise channel on the
     * first reconcile of every noise-form effect, and the release force-silences
     * it. That silenced the whole tail of {@code sfx_Collapse} ($59) and
     * {@code sfx_Dash} ($B6), whose PSG3 noise tracks outlive their short FM
     * tracks by design: Collapse's six-iteration volume ramp
     * (Sound/SFX/59 - Collapse.asm:31-36) and Dash's $4F-tick noise sweep
     * (Sound/SFX/B6 - Dash.asm:19-23) never reached the bus.
     *
     * <p>Drivers that fold noise ownership onto PSG3 are unaffected: {@link
     * #psgOwnershipChannel} already remaps the noise channel to 2 under
     * {@code S1_PSG3_SILENCE_PAIR}, so those never take a channel-3 lock.
     */
    private static boolean hasActiveTrack(
            SmpsSequencer sequencer, int channel, boolean psg) {
        for (int index = 0; index < sequencer.trackCount(); index++) {
            SmpsSequencer.Track track = sequencer.trackAt(index);
            if (!track.active
                    || (track.type == SmpsSequencer.TrackType.PSG) != psg) {
                continue;
            }
            if (track.channelId == channel) {
                return true;
            }
            if (psg && channel == PSG_NOISE_CHANNEL && track.noiseMode
                    && track.channelId == PSG_TONE3_CHANNEL) {
                return true;
            }
        }
        return false;
    }

    private void updateOverrides(SmpsSequencer.TrackType type, int ch, boolean overridden) {
        synchronized (sequencersLock) {
            for (SmpsSequencer s : sequencers) {
                if (!isSfx(s)) {
                    s.setChannelOverridden(type, ch, overridden);
                }
            }
        }
    }

    private void updateOverridesAfterSfxTrackStop(
            SmpsSequencer.TrackType type, int channel) {
        synchronized (sequencersLock) {
            for (SmpsSequencer sequencer : sequencers) {
                if (!isSfx(sequencer)) {
                    sequencer.setChannelOverriddenAfterSfxTrackStop(
                            type, channel);
                }
            }
        }
    }

    private void updateOverridesWithoutRestore(
            SmpsSequencer.TrackType type, int ch, boolean overridden) {
        synchronized (sequencersLock) {
            for (SmpsSequencer sequencer : sequencers) {
                if (!isSfx(sequencer)) {
                    sequencer.setChannelOverriddenWithoutRestore(
                            type, ch, overridden);
                }
            }
        }
    }

    @Override
    public void writeFm(Object source, int port, int reg, int val) {
        int ch = -1;
        int rawReg = reg & 0xFF;

        // Map Register to Channel
        if (rawReg >= 0x30 && rawReg <= 0x9E) {
            ch = (rawReg & 0x03) + (port * 3);
        } else if (rawReg >= 0xA0 && rawReg <= 0xA2) {
            ch = (rawReg - 0xA0) + (port * 3);
        } else if (rawReg >= 0xA4 && rawReg <= 0xA6) {
            ch = (rawReg - 0xA4) + (port * 3);
        } else if (rawReg >= 0xB0 && rawReg <= 0xB2) {
            ch = (rawReg - 0xB0) + (port * 3);
        } else if (rawReg >= 0xB4 && rawReg <= 0xB6) {
            ch = (rawReg - 0xB4) + (port * 3);
        } else if (rawReg == 0x28) {
            // Key On/Off: 0x28 is Port 0 only.
            // Val: d7-d4 (slot mask), d2-d0 (channel). d2 (bit 4 of ch?) No.
            // Channel is 0-2 (0,1,2) or 4-6 (4,5,6).
            // Ym2612Chip: "if (chIdx >= 4) chIdx -= 1;" -> Maps 4,5,6 to 3,4,5.
            // So Ch 0,1,2 -> 0,1,2. Ch 4,5,6 -> 3,4,5.
            // We need linear channel 0-5.
            int c = val & 0x07;
            if (c >= 4)
                c -= 1;
            ch = c;
        }

        if (ch >= 0 && ch < 6) {
            if (isSfx(source)) {
                LockDecision decision = decideLock(SfxContentionObserver.Bus.FM, ch,
                        fmLocks[ch], (SmpsSequencer) source);
                if (decision.acquired()) {
                    // Silence channel if stealing from music (not from another SFX or self)
                    if (fmLocks[ch] != source && !isSfx(fmLocks[ch])
                            && usesForcedFmTakeover(source)) {
                        silenceFmChannel(ch);
                    }
                    fmLocks[ch] = (SmpsSequencer) source;
                    updateOverrides(SmpsSequencer.TrackType.FM, ch, true);
                }
                reportLockDecision(decision);

                if (fmLocks[ch] == source) {
                    synthesizer.writeFm(source, port, reg, val);
                }
            } else {
                if (fmLocks[ch] == null) {
                    synthesizer.writeFm(source, port, reg, val);
                }
            }
        } else {
            // Global or unmapped
            synthesizer.writeFm(source, port, reg, val);
        }
    }

    @Override
    public void writePsg(Object source, int val) {
        // Use cached psgLatchChannel on SmpsSequencer for O(1) lookup instead of HashMap
        SmpsSequencer seq = (source instanceof SmpsSequencer) ? (SmpsSequencer) source : null;

        if ((val & 0x80) != 0) {
            // Latch
            int ch = (val >> 5) & 0x03;
            int ownershipChannel = psgOwnershipChannel(ch, source);

            // Cache latch channel on sequencer (fast path) and in HashMap (fallback)
            if (seq != null) {
                seq.setPsgLatchChannel(ch);
            } else {
                psgLatches.put(source, ch);
            }

            if (isSfx(source)) {
                LockDecision decision = decideLock(SfxContentionObserver.Bus.PSG,
                        ownershipChannel, psgLocks[ownershipChannel],
                        (SmpsSequencer) source);
                if (decision.acquired()) {
                    // Silence channel if stealing from music (not from another SFX or self)
                    if (psgLocks[ownershipChannel] != source
                            && !isSfx(psgLocks[ownershipChannel])
                            && usesForcedPsgTakeover(source)) {
                        silencePsgChannel(ownershipChannel);
                    }
                    psgLocks[ownershipChannel] = (SmpsSequencer) source;
                    updateOverrides(SmpsSequencer.TrackType.PSG,
                            ownershipChannel, true);
                }
                reportLockDecision(decision);

                if (psgLocks[ownershipChannel] == source) {
                    synthesizer.writePsg(source, val);
                }
            } else {
                if (psgLocks[ownershipChannel] == null) {
                    synthesizer.writePsg(source, val);
                }
            }
        } else {
            // Data - get cached latch channel
            int ch = (seq != null) ? seq.getPsgLatchChannel() : -1;
            if (ch < 0) {
                // Fallback to HashMap for non-SmpsSequencer sources
                Integer chObj = psgLatches.get(source);
                ch = (chObj != null) ? chObj : -1;
            }

            if (ch >= 0) {
                int ownershipChannel = psgOwnershipChannel(ch, source);
                if (isSfx(source)) {
                    // Update lock just in case? Already locked by Latch.
                    LockDecision decision = decideLock(SfxContentionObserver.Bus.PSG,
                            ownershipChannel, psgLocks[ownershipChannel],
                            (SmpsSequencer) source);
                    if (decision.acquired()) {
                        // Silence channel if stealing from music (not from another SFX or self)
                        if (psgLocks[ownershipChannel] != source
                                && !isSfx(psgLocks[ownershipChannel])
                                && usesForcedPsgTakeover(source)) {
                            silencePsgChannel(ownershipChannel);
                        }
                        psgLocks[ownershipChannel] = (SmpsSequencer) source;
                        updateOverrides(SmpsSequencer.TrackType.PSG,
                                ownershipChannel, true);
                    }
                    reportLockDecision(decision);

                    if (psgLocks[ownershipChannel] == (SmpsSequencer) source) {
                        synthesizer.writePsg(source, val);
                    }
                } else {
                    if (psgLocks[ownershipChannel] == null) {
                        synthesizer.writePsg(source, val);
                    }
                }
            } else {
                // Unknown channel (no previous latch from this source), drop or pass?
                // Pass for safety/compatibility
                synthesizer.writePsg(source, val);
            }
        }
    }

    @Override
    public void writePsgFrequencyPair(
            Object source, int latchByte, int followingByte) {
        int first = latchByte & 0xFF;
        int second = followingByte & 0xFF;
        int hardwareChannel = (first >> 5) & 0x03;
        if ((first & 0x90) != 0x80 || hardwareChannel >= 3) {
            throw new IllegalArgumentException(
                    "PSG frequency transaction must start with a tone latch");
        }

        int ownershipChannel = psgOwnershipChannel(hardwareChannel, source);
        boolean admitted;
        if (isSfx(source)) {
            LockDecision decision = decideLock(SfxContentionObserver.Bus.PSG,
                    ownershipChannel, psgLocks[ownershipChannel],
                    (SmpsSequencer) source);
            if (decision.acquired()) {
                if (psgLocks[ownershipChannel] != source
                        && !isSfx(psgLocks[ownershipChannel])
                        && usesForcedPsgTakeover(source)) {
                    silencePsgChannel(ownershipChannel);
                }
                psgLocks[ownershipChannel] = (SmpsSequencer) source;
                updateOverrides(SmpsSequencer.TrackType.PSG,
                        ownershipChannel, true);
            }
            reportLockDecision(decision);
            admitted = psgLocks[ownershipChannel] == source;
        } else {
            admitted = psgLocks[ownershipChannel] == null;
        }
        if (!admitted) {
            return;
        }

        // zUpdatePSGTrack gates the source track once, then writes both bytes
        // without another ownership check (:4085-4095). Keep both physical
        // bytes verbatim: S3K's unmasked second byte can itself be a latch.
        synthesizer.writePsg(source, first);
        synthesizer.writePsg(source, second);
        int physicalLatchChannel = (second & 0x80) != 0
                ? (second >> 5) & 0x03 : hardwareChannel;
        if (source instanceof SmpsSequencer sequencer) {
            sequencer.setPsgLatchChannel(physicalLatchChannel);
        } else {
            psgLatches.put(source, physicalLatchChannel);
        }
    }

    @Override
    public void writePsgDriverSilence(
            Object source, int toneChannel, boolean noiseMode) {
        if (toneChannel < 0 || toneChannel >= 3) {
            throw new IllegalArgumentException(
                    "PSG driver silence requires a tone channel");
        }
        // This is one driver-owned physical transaction, reached after the
        // ROM stream has already selected cfStopTrack. It neither competes
        // for nor releases the logical channel lock.
        synthesizer.writePsg(source, 0x80 | (toneChannel << 5) | 0x1F);
        if (noiseMode) {
            synthesizer.writePsg(source, 0xFF);
        }
        synthesizer.writePsg(source, 0xFF);
        if (source instanceof SmpsSequencer sequencer) {
            sequencer.setPsgLatchChannel(PSG_NOISE_CHANNEL);
        } else {
            psgLatches.put(source, PSG_NOISE_CHANNEL);
        }
    }

    /** PSG3, the tone channel a noise track's own slot names. */
    private static final int PSG_TONE3_CHANNEL = 2;

    /** The PSG noise channel, which a PSG3 noise track owns alongside PSG3. */
    private static final int PSG_NOISE_CHANNEL = 3;

    private static int psgOwnershipChannel(
            int hardwareChannel, Object source) {
        // S1 stores tone 3 ($C0) and noise ($E0) in one logical track slot.
        // Keep the hardware latch intact, but arbitrate both register classes
        // through the PSG3 owner for its source-authentic profile.
        if (hardwareChannel == 3
                && source instanceof SmpsSequencer sequencer
                && sequencer.getConfig().getPsgSfxTakeoverMode()
                == SmpsSequencerConfig.PsgSfxTakeoverMode.S1_PSG3_SILENCE_PAIR) {
            return 2;
        }
        return hardwareChannel;
    }

    // Override other methods if needed (setInstrument calls writeFm, so it's
    // covered)
    @Override
    public void setInstrument(Object source, int channelId, byte[] voice) {
        // Channel ID is passed explicitly.
        if (channelId >= 0 && channelId < 6) {
            if (isSfx(source)) {
                LockDecision decision = decideLock(SfxContentionObserver.Bus.FM, channelId,
                        fmLocks[channelId], (SmpsSequencer) source);
                if (decision.acquired()) {
                    // Silence channel if stealing from music (not from another SFX or self)
                    if (fmLocks[channelId] != source && !isSfx(fmLocks[channelId])
                            && usesForcedFmTakeover(source)) {
                        silenceFmChannel(channelId);
                    }
                    fmLocks[channelId] = (SmpsSequencer) source;
                    updateOverrides(SmpsSequencer.TrackType.FM, channelId, true);
                }
                reportLockDecision(decision);

                if (fmLocks[channelId] == source) {
                    synthesizer.setInstrument(source, channelId, voice);
                }
            } else {
                if (fmLocks[channelId] == null) {
                    synthesizer.setInstrument(source, channelId, voice);
                }
            }
        }
    }

    @Override
    public void playDac(Object source, int note) {
        // DAC is on Channel 5 (FM6)
        int ch = 5;
        if (isSfx(source)) {
            LockDecision decision = decideLock(SfxContentionObserver.Bus.FM, ch,
                    fmLocks[ch], (SmpsSequencer) source);
            if (decision.acquired()) {
                // Silence channel if stealing from music (not from another SFX or self)
                if (fmLocks[ch] != source && !isSfx(fmLocks[ch])
                        && usesForcedFmTakeover(source)) {
                    silenceFmChannel(5);
                    synthesizer.stopDac(null);
                }
                fmLocks[ch] = (SmpsSequencer) source;
                updateOverrides(SmpsSequencer.TrackType.FM, ch, true);
            }
            reportLockDecision(decision);

            if (fmLocks[ch] == source) {
                synthesizer.playDac(source, note);
                dacQueuedSinceIdleLoopPass = true;
            }
        } else {
            if (fmLocks[ch] == null) {
                synthesizer.playDac(source, note);
                dacQueuedSinceIdleLoopPass = true;
            }
        }
    }

    /**
     * Reports and clears whether a DAC sample was queued since the last pass.
     *
     * <p>One {@code true} answers one {@code zDACIndex} store, matching the
     * ROM's one 2Bh = 80h per queued sample: a sample queued while another is
     * playing clears bit 7, so {@code jp p, .dac_idle_loop} sends the loop
     * back through the enable (skdisasm Sound/Z80 Sound
     * Driver.asm:2896-2903, :4343-4345).</p>
     */
    public boolean consumeDacIdleLoopPass() {
        synchronized (sequencersLock) {
            boolean queued = dacQueuedSinceIdleLoopPass;
            dacQueuedSinceIdleLoopPass = false;
            return queued;
        }
    }

    private boolean shouldStealLock(SmpsSequencer currentLock, SmpsSequencer challenger) {
        if (currentLock == null)
            return true;
        if (currentLock == challenger)
            return true;
        if (!isSfx(currentLock))
            return true; // Challenger is SFX, current is Music -> Steal

        // Both are SFX.
        // Sonic 1 has a dedicated "special SFX" class (e.g. GHZ waterfall) that can be
        // overridden by normal SFX on shared channels, but not vice versa.
        boolean currentSpecial = currentLock.isSpecialSfx();
        boolean challengerSpecial = challenger.isSpecialSfx();
        if (currentSpecial && !challengerSpecial) {
            return true;
        }
        if (!currentSpecial && challengerSpecial) {
            return false;
        }

        // Priority arbitration:
        // Higher priority steals. If current priority has bit 7 set, treat it as
        // non-storing/transient (ROM-style), so any subsequent SFX can steal.
        int currentPriority = currentLock.getSfxPriority();
        int challengerPriority = challenger.getSfxPriority();
        if ((currentPriority & 0x80) != 0) {
            return true;
        }

        if (challengerPriority > currentPriority) {
            return true; // Higher priority always steals
        } else if (challengerPriority == currentPriority) {
            // Equal priority: newer SFX wins (prevents old SFX from stealing back)
            int currentIdx = sequencers.indexOf(currentLock);
            int challengerIdx = sequencers.indexOf(challenger);
            return challengerIdx > currentIdx;
        }
        return false; // Lower priority cannot steal
    }

    private static boolean usesForcedFmTakeover(Object source) {
        return ((SmpsSequencer) source).getConfig().getFmSfxTakeoverMode()
                == SmpsSequencerConfig.FmSfxTakeoverMode.FORCE_RESET;
    }

    private static boolean usesForcedPsgTakeover(Object source) {
        return ((SmpsSequencer) source).getConfig().getPsgSfxTakeoverMode()
                == SmpsSequencerConfig.PsgSfxTakeoverMode.FORCE_SILENCE;
    }

    /**
     * Returns the currently installed S1 special-SFX voice for the shipped
     * SendVoiceTL(a6) pointer bug, or {@code null} while that pointer is zero.
     */
    public byte[] s1SpecialSfxVoiceForBug(int voiceId) {
        synchronized (sequencersLock) {
            AbstractSmpsData bank = s1SpecialVoicePointer;
            return bank == null ? null : bank.getVoice(voiceId);
        }
    }

    private LockDecision decideLock(SfxContentionObserver.Bus bus,
                                    int channel,
                                    SmpsSequencer currentLock,
                                    SmpsSequencer challenger) {
        boolean acquired = shouldStealLock(currentLock, challenger);
        SfxContentionObserver.Source previous = null;
        if (currentLock == null || currentLock == challenger) {
            previous = pendingConflictOwners.remove(
                    new ConflictKey(bus, channel, challenger));
        }
        if (previous == null && currentLock != null) {
            previous = sourceFor(currentLock);
        }
        return new LockDecision(acquired, new SfxContentionObserver.Arbitration(
                bus, channel, sourceFor(challenger), previous, acquired));
    }

    private void reportLockDecision(LockDecision decision) {
        sfxContentionObserver.onRoleArbitrated(decision.arbitration());
    }

    private SfxContentionObserver.Source sourceFor(SmpsSequencer sequencer) {
        return new SfxContentionObserver.Source(sequencer.getSourceDescriptor(),
                sfxAdmissionOrdinals.getOrDefault(sequencer, -1L), isSfx(sequencer),
                sequencer.isSpecialSfx());
    }

    private List<SfxContentionObserver.Role> declaredRoles(SmpsSequencer seq) {
        return seq.getTracks().stream()
                .filter(track -> track.type == SmpsSequencer.TrackType.FM || track.type == SmpsSequencer.TrackType.PSG)
                .map(track -> new SfxContentionObserver.Role(track.type == SmpsSequencer.TrackType.FM
                        ? SfxContentionObserver.Bus.FM : SfxContentionObserver.Bus.PSG, track.channelId)).toList();
    }

    private void rememberConflict(SfxContentionObserver.Bus bus, int channel,
                                 SmpsSequencer displaced, SmpsSequencer challenger) {
        if (sfxContentionObserver != SfxContentionObserver.NONE) {
            pendingConflictOwners.put(new ConflictKey(bus, channel, challenger), sourceFor(displaced));
        }
    }

    private void rememberReplacementConflicts(SmpsSequencer displaced, SmpsSequencer challenger) {
        if (sfxContentionObserver == SfxContentionObserver.NONE) {
            return;
        }
        for (int channel = 0; channel < fmLocks.length; channel++) {
            if (fmLocks[channel] == displaced) {
                rememberConflict(SfxContentionObserver.Bus.FM, channel, displaced, challenger);
            }
        }
        for (int channel = 0; channel < psgLocks.length; channel++) {
            if (psgLocks[channel] == displaced) {
                rememberConflict(SfxContentionObserver.Bus.PSG, channel, displaced, challenger);
            }
        }
    }

    private record ConflictKey(SfxContentionObserver.Bus bus, int channel,
                               SmpsSequencer challenger) { }

    private record LockDecision(boolean acquired, SfxContentionObserver.Arbitration arbitration) { }

    private void trackRestoredSfxAdmission(SmpsSequencer sequencer) {
        if (sfxContentionObserver != SfxContentionObserver.NONE) {
            trackSfxAdmission(sequencer);
        }
    }

    private void admitSfx(SmpsSequencer sequencer) {
        trackSfxAdmission(sequencer);
        reportSfxAdmission(sequencer);
    }

    private void trackSfxAdmission(SmpsSequencer sequencer) {
        sfxAdmissionOrdinals.put(sequencer, nextSfxAdmissionOrdinal++);
    }

    private void reportSfxAdmission(SmpsSequencer sequencer) {
        if (sfxContentionObserver == SfxContentionObserver.NONE) {
            return;
        }
        sfxContentionObserver.onSfxAdmitted(new SfxContentionObserver.Admission(
                sourceFor(sequencer), declaredRoles(sequencer)));
    }

    /**
     * Silence an FM channel before SFX takes it over from music.
     * This directly resets envelope state to prevent the "chirp" artifact
     * that occurs when SFX first samples inherit envelope state from the
     * previous music note.
     *
     * Unlike register writes (which would be overwritten by the subsequent
     * voice load), this directly resets the envelope counters to fully
     * silent state, ensuring the next Key On starts from a clean slate.
     */
    private void silenceFmChannel(int ch) {
        // Directly reset envelope state - this takes effect immediately
        // without needing audio samples to be rendered
        forceSilenceChannel(ch);

        // Also send Key Off via registers for completeness
        int port = (ch < 3) ? 0 : 1;
        int hwCh = ch % 3;
        int chVal = (port == 0) ? hwCh : (hwCh + 4);
        synthesizer.writeFm(null, 0, 0x28, 0x00 | chVal);
    }

    /**
     * Write directly to PSG hardware, bypassing SFX lock checks.
     * Used for unconditional channel silencing during SFX load (ROM: zPlaySound).
     * Protected to allow test spy access.
     */
    protected void writeRawPsg(int val) {
        synthesizer.writePsg(null, val);
    }

    /**
     * Driver-owned FM write that skips the channel lock table, modelling a ROM
     * write issued through {@code zWriteFMI} / {@code zWriteFMIorII} outside
     * any track's playback (skdisasm Sound/Z80 Sound Driver.asm:2545-2556).
     */
    protected void writeRawFm(int port, int register, int value) {
        synthesizer.writeFm(null, port, register, value);
    }

    /**
     * Silence a PSG channel before SFX takes it over from music.
     * Sets volume to 0xF (silence).
     */
    private void silencePsgChannel(int ch) {
        if (ch >= 0 && ch <= 3) {
            synthesizer.writePsg(null, 0x80 | (ch << 5) | (1 << 4) | 0x0F);
        }
    }

    @Override
    public void stopDac(Object source) {
        int ch = 5;
        if (isSfx(source)) {
            // Don't release lock here, just stop sound.
            // Lock is released when track ends or channel unused?
            // Actually, stopDac is just stopping sound.
            synthesizer.stopDac(source);
        } else {
            if (fmLocks[ch] == null) {
                synthesizer.stopDac(source);
            }
        }
        // Stopping the DAC is zDACIndex returning to zero, so any sample the
        // idle loop has not yet seen is gone: zStopAllSound zeroes the
        // variable block that holds it (skdisasm Sound/Z80 Sound
        // Driver.asm:134,163,214,:2461-2470) and the playback loop's own exit
        // clears it before re-entering zPlayDigitalAudio (:4352-4355). Either
        // way the next idle-loop pass finds zero and writes no enable.
        synchronized (sequencersLock) {
            dacQueuedSinceIdleLoopPass = false;
        }
    }
}
