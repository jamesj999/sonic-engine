package com.openggf.audio.driver;

import com.openggf.audio.smps.SmpsLogicalWriteTarget;

/**
 * Restricted logical-write boundary supplied by the session composition root.
 * Implementations must resolve every operation against the currently scoped
 * physical capability; implementations may not retain that capability.
 */
public interface SmpsDriverSessionAccess extends SmpsLogicalWriteTarget {
    void forceSilenceFmChannel(int channelId);

    /** Applies host-owned terminal fade semantics after a logical service step. */
    boolean completeFadeOut();

    boolean fadeOutCompletesWithGlobalStop();
}
