package com.openggf.audio.presentation;

import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.session.SmpsDriverSessionSnapshot;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;

import java.util.List;
import java.util.Objects;

public record AudioPresentationSnapshot(
        long nextVoiceId,
        List<PresentationVoiceSnapshot> voices,
        MusicSlotSnapshot activeMusic,
        List<MusicSlotSnapshot> overrideStack,
        Long rawPcmVoiceId,
        int fmMuteMask,
        int fmSoloMask,
        int psgMuteMask,
        int psgSoloMask,
        boolean sfxBlocked,
        boolean sfxBlockHeldThroughFadeIn,
        boolean pendingRestore,
        boolean speedShoesEnabled,
        int speedMultiplier,
        boolean ringLeft,
        SmpsCoordFlagRuntimeState.Snapshot coordFlagRuntimeState,
        SmpsDriverSessionSnapshot smpsSession,
        SmpsDriverSnapshot smpsLogical) {

    private static final AudioPresentationSnapshot EMPTY =
            new AudioPresentationSnapshot(0, List.of(), null, List.of(),
                    null, 0, 0, 0, 0, false, false, false, false, 1, true,
                    new SmpsCoordFlagRuntimeState.Snapshot(0), null, null);

    public AudioPresentationSnapshot {
        voices = List.copyOf(Objects.requireNonNull(voices, "voices"));
        overrideStack =
                List.copyOf(Objects.requireNonNull(overrideStack, "overrideStack"));
        Objects.requireNonNull(coordFlagRuntimeState, "coordFlagRuntimeState");
        if ((smpsSession == null) != (smpsLogical == null)) {
            throw new IllegalArgumentException(
                    "session and logical SMPS snapshots must be paired");
        }
    }

    public static AudioPresentationSnapshot empty() {
        return EMPTY;
    }

    public record MusicSlotSnapshot(
            int musicId,
            AudioSourceDescriptor sourceDescriptor,
            long voiceId) {
        public MusicSlotSnapshot {
            Objects.requireNonNull(sourceDescriptor, "sourceDescriptor");
        }
    }
}
