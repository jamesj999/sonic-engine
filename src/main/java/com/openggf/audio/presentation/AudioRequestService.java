package com.openggf.audio.presentation;

import com.openggf.audio.rewind.AudioCommand;

/** Typed optional ingress plus forward lifecycle for a game-owned request front end. */
public interface AudioRequestService extends AudioPresentationForwardService {
    void submitMusic(int nativeRequestId, AudioCommand command);

    void submitSound(int nativeRequestId, AudioCommand command);

    /** Uses a game's secondary sound-request mailbox when it has one. */
    default void submitSecondarySound(int nativeRequestId, AudioCommand command) {
        submitSound(nativeRequestId, command);
    }

    /**
     * The service-owned ring left/right alternation, when this game's mailbox
     * dispatch selects it itself (e.g. Sonic 2's {@code zPlaySound_CheckRing},
     * {@code s2.sounddriver.asm:2127-2135}) rather than a caller advancing it
     * synchronously on submit. {@code null} when this service does not own
     * ring alternation, so the caller should fall back to its own bookkeeping.
     */
    default Boolean ringLeft() {
        return null;
    }
}
