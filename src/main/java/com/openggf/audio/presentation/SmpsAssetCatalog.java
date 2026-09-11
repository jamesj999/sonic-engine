package com.openggf.audio.presentation;

import com.openggf.audio.SmpsSfxPlaybackPolicy;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsProgramView;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsSfxData;
import com.openggf.audio.smps.SmpsLoadReadiness;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Session-owned immutable SMPS dependency and program catalog. */
final class SmpsAssetCatalog {
    record Snapshot(
            Map<DependencyKey, DependencyEntry> dependencies,
            Map<ProgramKey, ProgramEntry> programs,
            Map<SmpsSourceDescriptor, ProgramEntry> descriptors) {
        Snapshot {
            dependencies = Map.copyOf(dependencies);
            programs = Map.copyOf(programs);
            descriptors = Map.copyOf(descriptors);
        }
    }
    static final class ProgramIdentityConflict extends IllegalStateException {
        private ProgramIdentityConflict(String message) {
            super(message);
        }
    }

    enum DependencyKind {
        BASE,
        DONOR
    }

    record DependencyKey(
            String gameId, DependencyKind kind, long generation) {
        DependencyKey {
            requireGameId(gameId);
            Objects.requireNonNull(kind, "kind");
            requireGeneration(generation);
        }
    }

    record ProgramKey(SmpsAssetKey assetKey, long generation) {
        ProgramKey {
            Objects.requireNonNull(assetKey, "assetKey");
            requireGeneration(generation);
        }

        DependencyKey dependencyKey() {
            return new DependencyKey(
                    assetKey.gameId(), dependencyKind(assetKey.route()),
                    generation);
        }
    }

    static final class ProgramEntry {
        private final AbstractSmpsData program;
        private final SmpsProgramView programView;
        private final DependencyEntry dependency;
        private final SmpsSourceDescriptor sourceDescriptor;
        private final int assetId;
        private final int trackCount;
        private final SmpsSfxPlaybackPolicy sfxPolicy;
        private final AbstractSmpsData sourceIdentity;
        private final SmpsLoadReadiness readiness;

        private ProgramEntry(
                AbstractSmpsData program,
                SmpsProgramView programView,
                DependencyEntry dependency,
                SmpsSourceDescriptor sourceDescriptor,
                int assetId,
                int trackCount,
                SmpsSfxPlaybackPolicy sfxPolicy,
                AbstractSmpsData sourceIdentity,
                SmpsLoadReadiness readiness) {
            this.program = Objects.requireNonNull(program, "program");
            this.programView = Objects.requireNonNull(
                    programView, "programView");
            this.dependency = Objects.requireNonNull(
                    dependency, "dependency");
            this.sourceDescriptor = Objects.requireNonNull(
                    sourceDescriptor, "sourceDescriptor");
            this.assetId = assetId;
            this.trackCount = trackCount;
            this.sfxPolicy = Objects.requireNonNull(sfxPolicy, "sfxPolicy");
            this.sourceIdentity = Objects.requireNonNull(
                    sourceIdentity, "sourceIdentity");
            this.readiness = Objects.requireNonNull(readiness, "readiness");
        }

        AbstractSmpsData program() {
            return program;
        }

        SmpsProgramView programView() {
            return programView;
        }

        DacData dac() {
            return dependency.dac;
        }

        SmpsSequencerConfig staticConfig() {
            return dependency.config;
        }

        SmpsSourceDescriptor sourceDescriptor() {
            return sourceDescriptor;
        }

        int assetId() {
            return assetId;
        }

        int trackCount() {
            return trackCount;
        }

        boolean specialSfx() {
            return sfxPolicy.special();
        }

        SmpsSfxPlaybackPolicy sfxPolicy() {
            return sfxPolicy;
        }

        SmpsLoadReadiness readiness() { return readiness; }

        private boolean hasSourceIdentity(AbstractSmpsData source) {
            return sourceIdentity == source;
        }

        private DependencyEntry dependency() {
            return dependency;
        }
    }

    private static final class DependencyEntry {
        private final DependencyKey key;
        private final DacData dac;
        private final SmpsSequencerConfig config;
        private final DacData dacSourceIdentity;
        private final SmpsSequencerConfig configSourceIdentity;

        private DependencyEntry(
                DependencyKey key,
                DacData dac,
                SmpsSequencerConfig config,
                DacData dacSourceIdentity,
                SmpsSequencerConfig configSourceIdentity) {
            this.key = key;
            this.dac = dac;
            this.config = config;
            this.dacSourceIdentity = dacSourceIdentity;
            this.configSourceIdentity = configSourceIdentity;
        }

        private void requireProvenance(
                DacData candidateDac,
                SmpsSequencerConfig candidateConfig) {
            if (candidateDac != dacSourceIdentity
                    || candidateConfig != configSourceIdentity) {
                throw new IllegalStateException(
                        "SMPS dependency identity conflict for " + key);
            }
        }
    }

    private final SmpsCoordFlagHandlerOwner coordFlagHandlers;
    private final Map<DependencyKey, DependencyEntry> dependencies =
            new HashMap<>();
    private final Map<ProgramKey, ProgramEntry> programs = new HashMap<>();
    private final Map<SmpsSourceDescriptor, ProgramEntry> descriptors =
            new HashMap<>();

    SmpsAssetCatalog(SmpsCoordFlagHandlerOwner coordFlagHandlers) {
        this.coordFlagHandlers = Objects.requireNonNull(
                coordFlagHandlers, "coordFlagHandlers");
    }

    ProgramEntry register(
            ProgramKey key,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            boolean specialSfx) {
        return register(key, data, dac, config,
                SmpsSfxPlaybackPolicy.defaults(specialSfx),
                SmpsLoadReadiness.immediatePlan());
    }

    ProgramEntry register(
            ProgramKey key,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            SmpsSfxPlaybackPolicy sfxPolicy) {
        return register(key, data, dac, config, sfxPolicy,
                SmpsLoadReadiness.immediatePlan());
    }

    ProgramEntry register(
            ProgramKey key,
            AbstractSmpsData data,
            DacData dac,
            SmpsSequencerConfig config,
            SmpsSfxPlaybackPolicy sfxPolicy,
            SmpsLoadReadiness readiness) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(dac, "dac");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(sfxPolicy, "sfxPolicy");
        Objects.requireNonNull(readiness, "readiness");

        ProgramEntry existing = programs.get(key);
        if (existing != null) {
            existing.dependency().requireProvenance(dac, config);
            if (existing.hasSourceIdentity(data)) {
                if (!existing.sfxPolicy().equals(sfxPolicy)
                        || !existing.readiness().provenance().equals(
                                readiness.provenance())) {
                    throw programConflict(key);
                }
                return existing;
            }
        }

        DependencyEntry dependency = requireDependency(
                key.dependencyKey(), dac, config);
        if (existing != null) {
            if (existing.sfxPolicy().equals(sfxPolicy)
                    && existing.readiness().provenance().equals(
                            readiness.provenance())
                    && sameProgram(existing.program(), data)) {
                return existing;
            }
            throw programConflict(key);
        }

        FrozenSmpsData frozen = freeze(data);
        SmpsSourceDescriptor descriptor = describe(
                key, frozen, frozen.dataHash());
        ProgramEntry entry = new ProgramEntry(
                frozen,
                frozen,
                dependency,
                descriptor,
                resolveAssetId(key.assetKey(), frozen),
                frozen.getChannels() + frozen.getPsgChannels(),
                sfxPolicy,
                data,
                readiness);
        ProgramEntry descriptorOwner = descriptors.get(descriptor);
        if (descriptorOwner != null
                && descriptorOwner.dependency() != dependency) {
            throw new IllegalStateException(
                    "SMPS source descriptor collision for " + descriptor
                            + " between dependencies "
                            + descriptorOwner.dependency().key + " and "
                            + dependency.key);
        }
        if (descriptorOwner != null) {
            throw new IllegalStateException(
                    "SMPS source descriptor collision for " + descriptor);
        }
        descriptors.put(descriptor, entry);
        programs.put(key, entry);
        return entry;
    }

    ProgramEntry find(ProgramKey key) {
        return programs.get(Objects.requireNonNull(key, "key"));
    }

    ProgramEntry require(SmpsSourceDescriptor descriptor) {
        ProgramEntry entry = descriptors.get(
                Objects.requireNonNull(descriptor, "descriptor"));
        if (entry == null) {
            throw new IllegalStateException(
                    "no registered SMPS asset for " + descriptor);
        }
        return entry;
    }

    Snapshot snapshot() {
        return new Snapshot(dependencies, programs, descriptors);
    }

    void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        dependencies.clear();
        dependencies.putAll(snapshot.dependencies());
        programs.clear();
        programs.putAll(snapshot.programs());
        descriptors.clear();
        descriptors.putAll(snapshot.descriptors());
        if (!dependencies.equals(snapshot.dependencies())
                || !programs.equals(snapshot.programs())
                || !descriptors.equals(snapshot.descriptors())) {
            throw new IllegalStateException("SMPS catalog rollback failed");
        }
    }

    static AbstractSmpsData freezeStandalone(AbstractSmpsData source) {
        return source instanceof FrozenSmpsData ? source : freeze(source);
    }

    private DependencyEntry requireDependency(
            DependencyKey key,
            DacData dac,
            SmpsSequencerConfig config) {
        DependencyEntry existing = dependencies.get(key);
        if (existing != null) {
            existing.requireProvenance(dac, config);
            return existing;
        }
        SmpsSequencerConfig boundConfig = bindConfig(
                key.gameId(), config, coordFlagHandlers);
        DependencyEntry created = new DependencyEntry(
                key, dac, boundConfig, dac, config);
        dependencies.put(key, created);
        return created;
    }

    private static SmpsSourceDescriptor describe(
            ProgramKey key,
            FrozenSmpsData data,
            int dataHash) {
        long generation = key.generation();
        SmpsAssetKey assetKey = key.assetKey();
        return switch (assetKey.route()) {
            case BASE_MUSIC -> SmpsSourceDescriptor.baseMusic(
                    assetKey.assetId(), generation, data,
                    data.dataLength(), dataHash);
            case DONOR_MUSIC -> SmpsSourceDescriptor.donorMusic(
                    assetKey.gameId(), assetKey.assetId(), generation, data,
                    data.dataLength(), dataHash);
            case BASE_ID -> SmpsSourceDescriptor.baseSfx(
                    assetKey.assetId(), generation, data,
                    data.dataLength(), dataHash);
            case BASE_NAME -> SmpsSourceDescriptor.baseNamedSfx(
                    assetKey.assetName(), generation, data,
                    data.dataLength(), dataHash);
            case DONOR_ID -> SmpsSourceDescriptor.donorSfx(
                    assetKey.gameId(), assetKey.assetId(), generation, data,
                    data.dataLength(), dataHash);
            case FALLBACK_NAME -> SmpsSourceDescriptor.from(
                    generation, data, data.dataLength(), dataHash);
        };
    }

    private static DependencyKind dependencyKind(SmpsAssetKey.Route route) {
        return switch (route) {
            case DONOR_MUSIC, DONOR_ID -> DependencyKind.DONOR;
            case BASE_MUSIC, BASE_ID, BASE_NAME, FALLBACK_NAME ->
                    DependencyKind.BASE;
        };
    }

    private static int resolveAssetId(
            SmpsAssetKey key, AbstractSmpsData data) {
        return switch (key.route()) {
            case BASE_MUSIC, DONOR_MUSIC ->
                    key.assetId();
            case BASE_ID, BASE_NAME, DONOR_ID, FALLBACK_NAME ->
                    data.getId();
        };
    }

    private static ProgramIdentityConflict programConflict(ProgramKey key) {
        return new ProgramIdentityConflict(
                "SMPS program identity conflict for route="
                        + key.assetKey().route() + ", asset="
                        + key.assetKey() + ", generation="
                        + key.generation());
    }

    private static boolean sameProgram(
            AbstractSmpsData frozen,
            AbstractSmpsData candidate) {
        if (!Arrays.equals(frozen.getData(), candidate.getData())
                || frozen.getVoicePtr() != candidate.getVoicePtr()
                || frozen.getChannels() != candidate.getChannels()
                || frozen.getPsgChannels() != candidate.getPsgChannels()
                || frozen.getDividingTiming()
                != candidate.getDividingTiming()
                || frozen.getTempo() != candidate.getTempo()
                || frozen.getDacPointer() != candidate.getDacPointer()
                || !Arrays.equals(frozen.getFmPointers(),
                candidate.getFmPointers())
                || !Arrays.equals(frozen.getFmKeyOffsets(),
                candidate.getFmKeyOffsets())
                || !Arrays.equals(frozen.getFmVolumeOffsets(),
                candidate.getFmVolumeOffsets())
                || !Arrays.equals(frozen.getPsgPointers(),
                candidate.getPsgPointers())
                || !Arrays.equals(frozen.getPsgKeyOffsets(),
                candidate.getPsgKeyOffsets())
                || !Arrays.equals(frozen.getPsgVolumeOffsets(),
                candidate.getPsgVolumeOffsets())
                || !Arrays.equals(frozen.getPsgModEnvs(),
                candidate.getPsgModEnvs())
                || !Arrays.equals(frozen.getPsgInstruments(),
                candidate.getPsgInstruments())
                || frozen.getZ80StartAddress()
                != candidate.getZ80StartAddress()
                || frozen.getId() != candidate.getId()
                || frozen.isPalSpeedupDisabled()
                != candidate.isPalSpeedupDisabled()
                || frozen.getBaseNoteOffset()
                != candidate.getBaseNoteOffset()
                || frozen.getPsgBaseNoteOffset()
                != candidate.getPsgBaseNoteOffset()) {
            return false;
        }
        for (int id = 0; id < 256; id++) {
            int lookupId = id;
            if (!Arrays.equals(safely(() -> frozen.getVoice(lookupId)),
                    safely(() -> candidate.getVoice(lookupId)))
                    || !Arrays.equals(
                    safely(() -> frozen.getPsgEnvelope(lookupId)),
                    safely(() -> candidate.getPsgEnvelope(lookupId)))
                    || !Arrays.equals(
                    safely(() -> frozen.getModEnvelope(lookupId)),
                    safely(() -> candidate.getModEnvelope(lookupId)))) {
                return false;
            }
        }
        if ((frozen instanceof SmpsSfxData)
                != (candidate instanceof SmpsSfxData)) {
            return false;
        }
        if (frozen instanceof SmpsSfxData frozenSfx) {
            SmpsSfxData candidateSfx = (SmpsSfxData) candidate;
            if (frozenSfx.getTickMultiplier()
                    != candidateSfx.getTickMultiplier()) {
                return false;
            }
            List<? extends SmpsSfxData.SmpsSfxTrack> frozenTracks =
                    frozenSfx.getTrackEntries();
            List<? extends SmpsSfxData.SmpsSfxTrack> candidateTracks =
                    candidateSfx.getTrackEntries();
            if (frozenTracks.size() != candidateTracks.size()) {
                return false;
            }
            for (int index = 0; index < frozenTracks.size(); index++) {
                SmpsSfxData.SmpsSfxTrack left = frozenTracks.get(index);
                SmpsSfxData.SmpsSfxTrack right = candidateTracks.get(index);
                if (left.channelMask() != right.channelMask()
                        || left.pointer() != right.pointer()
                        || left.transpose() != right.transpose()
                        || left.volume() != right.volume()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static SmpsSequencerConfig bindConfig(
            String gameId,
            SmpsSequencerConfig source,
            SmpsCoordFlagHandlerOwner handlers) {
        SmpsSequencerConfig.Builder copy = copyBuilder(source);
        if (source.getCoordFlagHandler() != null) {
            copy.coordFlagHandler(handlers.handlerFor(gameId));
        } else {
            copy.coordFlagHandler(null);
        }
        return copy.build();
    }

    static SmpsSequencerConfig copyConfigWithoutHandler(
            SmpsSequencerConfig source) {
        return copyBuilder(source).coordFlagHandler(null).build();
    }

    static SmpsSequencerConfig bindLegacyConfig(
            String gameId,
            SmpsSequencerConfig source,
            boolean handlerRequired,
            SmpsCoordFlagHandlerOwner handlers) {
        SmpsSequencerConfig.Builder copy = copyBuilder(source);
        copy.coordFlagHandler(handlerRequired
                ? handlers.handlerFor(gameId) : null);
        return copy.build();
    }

    private static SmpsSequencerConfig.Builder copyBuilder(
            SmpsSequencerConfig source) {
        Objects.requireNonNull(source, "source");
        return new SmpsSequencerConfig.Builder()
                .speedUpTempos(source.getSpeedUpTempos())
                .tempoModBase(source.getTempoModBase())
                .fmChannelOrder(source.getFmChannelOrder())
                .psgChannelOrder(source.getPsgChannelOrder())
                .tempoMode(source.getTempoMode())
                .palUpdateMode(source.getPalUpdateMode())
                .coordFlagParamOverrides(
                        source.getCoordFlagParamOverrides())
                .applyModOnNote(source.isApplyModOnNote())
                .halveModSteps(source.isHalveModSteps())
                .extraTrkEndFlags(Set.copyOf(
                        source.getExtraTrkEndFlags()))
                .relativePointers(source.isRelativePointers())
                .tempoOnFirstTick(source.isTempoOnFirstTick())
                .resetTempoOnMusicLoad(source.isResetTempoOnMusicLoad())
                .direct68kDriver(source.isDirect68kDriver())
                .advancePsgEnvelopeOnRest(source.isAdvancePsgEnvelopeOnRest())
                .writeFmPanOnNote(source.isWriteFmPanOnNote())
                .fmSfxTakeoverMode(source.getFmSfxTakeoverMode())
                .psgSfxTakeoverMode(source.getPsgSfxTakeoverMode())
                .psg3SfxAdmissionWriteMode(
                        source.getPsg3SfxAdmissionWriteMode())
                .sfxChannelOwnershipMode(
                        source.getSfxChannelOwnershipMode())
                .fmSfxReleaseMode(source.getFmSfxReleaseMode())
                .psgSfxReleaseMode(source.getPsgSfxReleaseMode())
                .sfxTrackWalkMode(source.getSfxTrackWalkMode())
                .fmVolumeVoiceBankMode(source.getFmVolumeVoiceBankMode())
                .fmVoiceWriteProfile(source.getFmVoiceWriteProfile())
                .volMode(source.getVolMode())
                .psgEnvCmd80(source.getPsgEnvCmd80())
                .noteOnPrevent(source.getNoteOnPrevent())
                .delayFreq(source.getDelayFreq())
                .modAlgo(source.getModAlgo())
                .noteGoingFreqSend(source.getNoteGoingFreqSend())
                .psgNoteGoingOrder(source.getPsgNoteGoingOrder())
                .psgEnvRestCmd(source.getPsgEnvRestCmd())
                .stepModulationAtRest(source.isStepModulationAtRest())
                .noteResetAliasesModulationState(
                        source.isNoteResetAliasesModulationState())
                .fmNoteGoingReturnsAtRest(source.isFmNoteGoingReturnsAtRest())
                .fadeOutHalt(source.getFadeOutHalt())
                .fadeDelayCadence(source.getFadeDelayCadence())
                .tempoWaitPrecedesRequest(source.isTempoWaitPrecedesRequest())
                .psgSilenceShape(source.getPsgSilenceShape())
                .psgVolumeTail(source.getPsgVolumeTail())
                .fadeInRestore(source.getFadeInRestore())
                .driverOwnedFadeDelay(source.isDriverOwnedFadeDelay())
                .dacNoteKeysOffFm6AndRestoresFm3(
                        source.isDacNoteKeysOffFm6AndRestoresFm3())
                .enableDacOnSequencerStart(
                        source.isEnableDacOnSequencerStart())
                .psgFrequencyHighByteNibbleSwap(
                        source.isPsgFrequencyHighByteNibbleSwap())
                .specialSfxPsg3SilenceMode(
                        source.getSpecialSfxPsg3SilenceMode())
                .sfxWalkPrecedesRequest(source.isSfxWalkPrecedesRequest())
                .sfxAdmissionKeyOffAndClearsSsgEg(
                        source.isSfxAdmissionKeyOffAndClearsSsgEg())
                .psgSfxAdmissionSilencesNoise(source.isPsgSfxAdmissionSilencesNoise())
                .trackEndFlagOwnsTheStop(source.isTrackEndFlagOwnsTheStop())
                .noteFillTail(source.getNoteFillTail())
                .fadeOutDelay(source.getFadeOutDelay())
                .fadeOutSteps(source.getFadeOutSteps())
                .fadeInSteps(source.getFadeInSteps())
                .fadeInDelay(source.getFadeInDelay());
    }

    private static FrozenSmpsData freeze(AbstractSmpsData source) {
        if (source instanceof FrozenSmpsData frozen) {
            return frozen;
        }
        return source instanceof SmpsSfxData sfx
                ? new FrozenSfxData(source, sfx)
                : new FrozenSmpsData(source);
    }

    private static class FrozenSmpsData extends AbstractSmpsData {
        private final byte[][] voices = new byte[256][];
        private final byte[][] psgEnvelopes = new byte[256][];
        private final byte[][] modEnvelopes = new byte[256][];
        private final int[] words;
        private final int baseNoteOffset;
        private final int psgBaseNoteOffset;
        private final int dataHash;

        private FrozenSmpsData(AbstractSmpsData source) {
            this(Objects.requireNonNull(source, "source"),
                    new FrozenBytes(source.getData()));
        }

        private FrozenSmpsData(
                AbstractSmpsData source, FrozenBytes frozenBytes) {
            super(frozenBytes.bytes, source.getZ80StartAddress());
            dataHash = frozenBytes.hash;
            voicePtr = source.getVoicePtr();
            channels = source.getChannels();
            psgChannels = source.getPsgChannels();
            dividingTiming = source.getDividingTiming();
            tempo = source.getTempo();
            dacPointer = source.getDacPointer();
            fmPointers = copy(source.getFmPointers());
            fmKeyOffsets = copy(source.getFmKeyOffsets());
            fmVolumeOffsets = copy(source.getFmVolumeOffsets());
            psgPointers = copy(source.getPsgPointers());
            psgKeyOffsets = copy(source.getPsgKeyOffsets());
            psgVolumeOffsets = copy(source.getPsgVolumeOffsets());
            psgModEnvs = copy(source.getPsgModEnvs());
            psgInstruments = copy(source.getPsgInstruments());
            id = source.getId();
            palSpeedupDisabled = source.isPalSpeedupDisabled();
            baseNoteOffset = source.getBaseNoteOffset();
            psgBaseNoteOffset = source.getPsgBaseNoteOffset();
            words = new int[Math.max(0, data.length - 1)];
            for (int index = 0; index < words.length; index++) {
                words[index] = source.read16(index);
            }
            for (int index = 0; index < 256; index++) {
                int lookupId = index;
                voices[index] = copyNullable(
                        safely(() -> source.getVoice(lookupId)));
                psgEnvelopes[index] = copyNullable(
                        safely(() -> source.getPsgEnvelope(lookupId)));
                modEnvelopes[index] = copyNullable(
                        safely(() -> source.getModEnvelope(lookupId)));
            }
        }

        private int dataHash() {
            return dataHash;
        }

        @Override protected void parseHeader() { }
        @Override public byte[] getData() { return data.clone(); }
        @Override public int[] getFmPointers() { return fmPointers.clone(); }
        @Override public int[] getFmKeyOffsets() {
            return fmKeyOffsets.clone();
        }
        @Override public int[] getFmVolumeOffsets() {
            return fmVolumeOffsets.clone();
        }
        @Override public int[] getPsgPointers() {
            return psgPointers.clone();
        }
        @Override public int[] getPsgKeyOffsets() {
            return psgKeyOffsets.clone();
        }
        @Override public int[] getPsgVolumeOffsets() {
            return psgVolumeOffsets.clone();
        }
        @Override public int[] getPsgModEnvs() {
            return psgModEnvs.clone();
        }
        @Override public int[] getPsgInstruments() {
            return psgInstruments.clone();
        }
        @Override public void setId(int ignored) {
            throw new UnsupportedOperationException(
                    "frozen SMPS metadata cannot be changed");
        }
        @Override public void setPalSpeedupDisabled(boolean ignored) {
            throw new UnsupportedOperationException(
                    "frozen SMPS metadata cannot be changed");
        }
        @Override public byte[] getVoice(int voiceId) {
            return copyAt(voices, voiceId);
        }
        @Override public byte[] getPsgEnvelope(int envelopeId) {
            return copyAt(psgEnvelopes, envelopeId);
        }
        @Override public byte[] getModEnvelope(int envelopeId) {
            return copyAt(modEnvelopes, envelopeId);
        }
        @Override public int voiceLength(int voiceId) {
            return lengthAt(voices, voiceId);
        }
        @Override public byte voiceByteAt(int voiceId, int index) {
            return voices[voiceId][index];
        }
        @Override public int psgEnvelopeLength(int envelopeId) {
            return lengthAt(psgEnvelopes, envelopeId);
        }
        @Override public byte psgEnvelopeByteAt(
                int envelopeId, int index) {
            return psgEnvelopes[envelopeId][index];
        }
        @Override public int modEnvelopeLength(int envelopeId) {
            return lengthAt(modEnvelopes, envelopeId);
        }
        @Override public byte modEnvelopeByteAt(
                int envelopeId, int index) {
            return modEnvelopes[envelopeId][index];
        }
        @Override protected byte[] materializeVoiceForSequencer(int id) {
            return copyAt(voices, id);
        }
        @Override protected byte[] materializePsgEnvelopeForSequencer(int id) {
            return copyAt(psgEnvelopes, id);
        }
        @Override protected byte[] materializeModEnvelopeForSequencer(int id) {
            return copyAt(modEnvelopes, id);
        }
        @Override public int read16(int offset) {
            if (offset < 0 || offset >= words.length) {
                throw new IndexOutOfBoundsException(offset);
            }
            return words[offset];
        }
        @Override public int getBaseNoteOffset() {
            return baseNoteOffset;
        }
        @Override public int getPsgBaseNoteOffset() {
            return psgBaseNoteOffset;
        }

        private static byte[] copyAt(byte[][] values, int index) {
            return index < 0 || index >= values.length
                    ? null : copyNullable(values[index]);
        }

        private static int lengthAt(byte[][] values, int index) {
            if (index < 0 || index >= values.length
                    || values[index] == null) {
                return 0;
            }
            return values[index].length;
        }
    }

    private static final class FrozenSfxData extends FrozenSmpsData
            implements SmpsSfxData {
        private final int tickMultiplier;
        private final List<FrozenTrack> tracks;

        private FrozenSfxData(AbstractSmpsData source, SmpsSfxData sfx) {
            super(source);
            tickMultiplier = sfx.getTickMultiplier();
            tracks = sfx.getTrackEntries().stream()
                    .map(track -> new FrozenTrack(
                            track.channelMask(), track.pointer(),
                            track.transpose(), track.volume()))
                    .toList();
        }

        @Override public int getTickMultiplier() {
            return tickMultiplier;
        }
        @Override public List<? extends SmpsSfxTrack> getTrackEntries() {
            return tracks;
        }
    }

    private record FrozenTrack(
            int channelMask, int pointer, int transpose, int volume)
            implements SmpsSfxData.SmpsSfxTrack {
    }

    private static final class FrozenBytes {
        private final byte[] bytes;
        private final int hash;

        private FrozenBytes(byte[] source) {
            Objects.requireNonNull(source, "SMPS data");
            bytes = new byte[source.length];
            int result = 1;
            for (int index = 0; index < source.length; index++) {
                byte value = source[index];
                bytes[index] = value;
                result = 31 * result + value;
            }
            hash = result;
        }
    }

    @FunctionalInterface
    private interface ByteArraySource {
        byte[] get();
    }

    private static byte[] safely(ByteArraySource source) {
        try {
            return source.get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int[] copy(int[] source) {
        return source == null ? new int[0] : source.clone();
    }

    private static byte[] copyNullable(byte[] source) {
        return source == null ? null : source.clone();
    }

    private static String requireGameId(String gameId) {
        String value = Objects.requireNonNull(gameId, "gameId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("gameId must not be blank");
        }
        return value;
    }

    private static long requireGeneration(long generation) {
        if (generation < 0) {
            throw new IllegalArgumentException(
                    "generation must be non-negative");
        }
        return generation;
    }
}
