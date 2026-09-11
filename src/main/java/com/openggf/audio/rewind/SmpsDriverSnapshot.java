package com.openggf.audio.rewind;

import com.openggf.audio.MusicRestoreSink;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.smps.SmpsLoadReadiness;
import com.openggf.audio.session.SmpsMusicActivation;
import com.openggf.audio.session.SmpsWriteProgram;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record SmpsDriverSnapshot(
        SmpsSequencer.Region region,
        SmpsDriver.ReadMode readMode,
        int continuousSfxId,
        boolean continuousSfxFlag,
        int contSfxLoopCnt,
        int palUpdateCounter,
        List<SequencerEntry> sequencers,
        int[] fmLockSequencerIds,
        int[] psgLockSequencerIds,
        List<SavedOverride> savedOverrides,
        PendingService pendingService,
        int fadeDelay,
        int fadeDelayTimeout,
        int fadeOutTimeout,
        int fadeInTimeout,
        boolean driverOwnedFade) {

    public SmpsDriverSnapshot {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(readMode, "readMode");
        sequencers = List.copyOf(sequencers);
        fmLockSequencerIds = Arrays.copyOf(fmLockSequencerIds, fmLockSequencerIds.length);
        psgLockSequencerIds = Arrays.copyOf(psgLockSequencerIds, psgLockSequencerIds.length);
        savedOverrides = List.copyOf(Objects.requireNonNull(
                savedOverrides, "savedOverrides"));
    }

    public SmpsDriverSnapshot(
            SmpsSequencer.Region region,
            SmpsDriver.ReadMode readMode,
            int continuousSfxId,
            boolean continuousSfxFlag,
            int contSfxLoopCnt,
            int palUpdateCounter,
            List<SequencerEntry> sequencers,
            int[] fmLockSequencerIds,
            int[] psgLockSequencerIds,
            List<SavedOverride> savedOverrides) {
        this(region, readMode, continuousSfxId, continuousSfxFlag,
                contSfxLoopCnt, palUpdateCounter, sequencers,
                fmLockSequencerIds, psgLockSequencerIds, savedOverrides,
                null, 0, 0, 0, 0, false);
    }

    /** Retains the pre-fade-pair arity for callers that carry no fade state. */
    public SmpsDriverSnapshot(
            SmpsSequencer.Region region,
            SmpsDriver.ReadMode readMode,
            int continuousSfxId,
            boolean continuousSfxFlag,
            int contSfxLoopCnt,
            int palUpdateCounter,
            List<SequencerEntry> sequencers,
            int[] fmLockSequencerIds,
            int[] psgLockSequencerIds,
            List<SavedOverride> savedOverrides,
            PendingService pendingService) {
        this(region, readMode, continuousSfxId, continuousSfxFlag,
                contSfxLoopCnt, palUpdateCounter, sequencers,
                fmLockSequencerIds, psgLockSequencerIds, savedOverrides,
                pendingService, 0, 0, 0, 0, false);
    }

    public SmpsDriverSnapshot(
            SmpsSequencer.Region region,
            SmpsDriver.ReadMode readMode,
            int continuousSfxId,
            boolean continuousSfxFlag,
            int contSfxLoopCnt,
            int palUpdateCounter,
            List<SequencerEntry> sequencers,
            int[] fmLockSequencerIds,
            int[] psgLockSequencerIds) {
        this(region, readMode, continuousSfxId, continuousSfxFlag,
                contSfxLoopCnt, palUpdateCounter, sequencers,
                fmLockSequencerIds, psgLockSequencerIds, List.of());
    }

    public SmpsDriverSnapshot(
            SmpsSequencer.Region region,
            SmpsDriver.ReadMode readMode,
            int continuousSfxId,
            boolean continuousSfxFlag,
            int contSfxLoopCnt,
            List<SequencerEntry> sequencers,
            int[] fmLockSequencerIds,
            int[] psgLockSequencerIds) {
        this(
                region,
                readMode,
                continuousSfxId,
                continuousSfxFlag,
                contSfxLoopCnt,
                5,
                sequencers,
                fmLockSequencerIds,
                psgLockSequencerIds,
                List.of());
    }

    @Override
    public int[] fmLockSequencerIds() {
        return Arrays.copyOf(fmLockSequencerIds, fmLockSequencerIds.length);
    }

    @Override
    public int[] psgLockSequencerIds() {
        return Arrays.copyOf(psgLockSequencerIds, psgLockSequencerIds.length);
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof SmpsDriverSnapshot other
                && region == other.region
                && readMode == other.readMode
                && continuousSfxId == other.continuousSfxId
                && continuousSfxFlag == other.continuousSfxFlag
                && contSfxLoopCnt == other.contSfxLoopCnt
                && palUpdateCounter == other.palUpdateCounter
                && sequencers.equals(other.sequencers)
                && Arrays.equals(fmLockSequencerIds,
                        other.fmLockSequencerIds)
                && Arrays.equals(psgLockSequencerIds,
                        other.psgLockSequencerIds)
                && savedOverrides.equals(other.savedOverrides)
                && Objects.equals(pendingService, other.pendingService);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(region, readMode, continuousSfxId,
                continuousSfxFlag, contSfxLoopCnt, palUpdateCounter,
                sequencers, savedOverrides, pendingService);
        result = 31 * result + Arrays.hashCode(fmLockSequencerIds);
        return 31 * result + Arrays.hashCode(psgLockSequencerIds);
    }

    public interface DependencyResolver {
        AbstractSmpsData resolveSmpsData(SequencerEntry entry);

        DacData resolveDacData(SequencerEntry entry);

        MusicRestoreSink resolveAudioManager(SequencerEntry entry);

        SmpsSequencerConfig resolveConfig(SequencerEntry entry);
    }

    public static DependencyResolver liveReferences() {
        return new DependencyResolver() {
            @Override
            public AbstractSmpsData resolveSmpsData(SequencerEntry entry) {
                return entry.smpsData();
            }

            @Override
            public DacData resolveDacData(SequencerEntry entry) {
                return entry.dacData();
            }

            @Override
            public MusicRestoreSink resolveAudioManager(SequencerEntry entry) {
                return entry.audioManager();
            }

            @Override
            public SmpsSequencerConfig resolveConfig(SequencerEntry entry) {
                return entry.config();
            }
        };
    }

    /** Logical-only RAM save area retained by a temporary music override. */
    public record SavedOverride(SmpsDriverSnapshot logical) {
        public SavedOverride {
            Objects.requireNonNull(logical, "logical");
        }
    }

    /** Deferred physical activation/reassertion for the next real service. */
    public record PendingService(
            SmpsMusicActivation activation,
            SmpsDriverSnapshot readyLogical,
            SmpsWriteProgram firstServiceWrites,
            SmpsSourceDescriptor selectedDacSource,
            String readinessProvenance,
            long remainingTStates,
            SmpsLoadReadiness.Context readinessContext) {
        public PendingService(
                SmpsMusicActivation activation,
                SmpsWriteProgram firstServiceWrites,
                SmpsSourceDescriptor selectedDacSource) {
            this(activation, null, firstServiceWrites, selectedDacSource,
                    SmpsLoadReadiness.immediatePlan().provenance(
                            new SmpsLoadReadiness.Context(
                                    SmpsSequencer.Region.NTSC, false)),
                    0, new SmpsLoadReadiness.Context(
                            SmpsSequencer.Region.NTSC, false));
        }
        public PendingService {
            Objects.requireNonNull(firstServiceWrites,
                    "firstServiceWrites");
            Objects.requireNonNull(readinessProvenance,
                    "readinessProvenance");
            Objects.requireNonNull(readinessContext, "readinessContext");
        }
    }

    public record SequencerEntry(
            boolean sfx,
            SmpsSourceDescriptor source,
            SmpsSequencer.SourceDescriptorTrust sourceDescriptorTrust,
            SmpsSourceDescriptor fallbackVoiceSource,
            AbstractSmpsData smpsData,
            DacData dacData,
            MusicRestoreSink audioManager,
            SmpsSequencerConfig config,
            SmpsSequencerSnapshot snapshot) {

        public SequencerEntry {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(
                    sourceDescriptorTrust, "sourceDescriptorTrust");
            Objects.requireNonNull(smpsData, "smpsData");
            Objects.requireNonNull(audioManager, "audioManager");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(snapshot, "snapshot");
        }

        public SequencerEntry(
                boolean sfx,
                SmpsSourceDescriptor source,
                SmpsSourceDescriptor fallbackVoiceSource,
                AbstractSmpsData smpsData,
                DacData dacData,
                MusicRestoreSink audioManager,
                SmpsSequencerConfig config,
                SmpsSequencerSnapshot snapshot) {
            this(sfx, source,
                    SmpsSequencer.SourceDescriptorTrust.LEGACY_RECOMPUTE,
                    fallbackVoiceSource, smpsData, dacData, audioManager,
                    config, snapshot);
        }
    }
}
