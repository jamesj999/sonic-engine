package com.openggf.audio.driver;

import com.openggf.audio.smps.SmpsSequencer;

/**
 * A validated, channel-bounded SMPS SFX admission plan.
 *
 * <p>The referenced sequencers and tracks are live identities, but preparation
 * does not mutate them. Conflict storage is bounded by the new program's
 * track count rather than by the number of unrelated live sequencers.</p>
 */
public final class PreparedSfxAdmission {
    private final SmpsDriver owner;
    private final SmpsSequencer sequencer;
    private final boolean continuousExtension;
    private final int claimedFmMask;
    private final int claimedPsgMask;
    private final int affectedFmMask;
    private final int affectedPsgMask;
    private final int continuousSfxId;
    private final int trackCount;
    final SmpsSequencer replacedSequencer;
    final SmpsSequencer[] displacedOwners;
    final SmpsSequencer.Track[] displacedTracks;
    private boolean committed;

    PreparedSfxAdmission(
            SmpsDriver owner,
            SmpsSequencer sequencer,
            boolean continuousExtension,
            int claimedFmMask,
            int claimedPsgMask,
            int affectedFmMask,
            int affectedPsgMask,
            int continuousSfxId,
            int trackCount,
            SmpsSequencer replacedSequencer,
            SmpsSequencer[] displacedOwners,
            SmpsSequencer.Track[] displacedTracks) {
        this.owner = owner;
        this.sequencer = sequencer;
        this.continuousExtension = continuousExtension;
        this.claimedFmMask = claimedFmMask;
        this.claimedPsgMask = claimedPsgMask;
        this.affectedFmMask = affectedFmMask;
        this.affectedPsgMask = affectedPsgMask;
        this.continuousSfxId = continuousSfxId;
        this.trackCount = trackCount;
        this.replacedSequencer = replacedSequencer;
        this.displacedOwners = displacedOwners;
        this.displacedTracks = displacedTracks;
    }

    public SmpsDriver owner() {
        return owner;
    }

    public SmpsSequencer sequencer() {
        return sequencer;
    }

    public boolean continuousExtension() {
        return continuousExtension;
    }

    public int claimedFmMask() {
        return claimedFmMask;
    }

    public int claimedPsgMask() {
        return claimedPsgMask;
    }

    public int affectedFmMask() {
        return affectedFmMask;
    }

    public int affectedPsgMask() {
        return affectedPsgMask;
    }

    public int continuousSfxId() {
        return continuousSfxId;
    }

    public int trackCount() {
        return trackCount;
    }

    void claimCommit() {
        if (committed) {
            throw new IllegalStateException(
                    "prepared SFX admission was already committed");
        }
        committed = true;
    }

    void releaseCommit() {
        committed = false;
    }
}
