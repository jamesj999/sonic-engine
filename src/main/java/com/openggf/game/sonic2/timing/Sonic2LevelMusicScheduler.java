package com.openggf.game.sonic2.timing;

import com.openggf.game.rewind.RewindSnapshottable;

import java.util.OptionalInt;

/** Session-owned, rewind-stable gate for Sonic 2 {@code Level_PlayBgm}. */
public final class Sonic2LevelMusicScheduler
        implements RewindSnapshottable<Sonic2LevelMusicScheduler.Snapshot> {
    public static final String REWIND_KEY = "sonic2.level-music-scheduler";

    private int pendingMusicId = -1;
    private int remainingVBlanks;

    public void arm(int musicId, int vblanks) {
        if (musicId < 0) {
            throw new IllegalArgumentException("music id must be non-negative");
        }
        if (vblanks <= 0) {
            throw new IllegalArgumentException("VBlank countdown must be positive");
        }
        pendingMusicId = musicId;
        remainingVBlanks = vblanks;
    }

    public OptionalInt serviceVBlank() {
        if (!pending()) {
            return OptionalInt.empty();
        }
        if (--remainingVBlanks > 0) {
            return OptionalInt.empty();
        }
        int musicId = pendingMusicId;
        cancel();
        return OptionalInt.of(musicId);
    }

    public boolean pending() {
        return pendingMusicId >= 0;
    }

    public boolean pending(int musicId) {
        return pendingMusicId == musicId;
    }

    public void cancel() {
        pendingMusicId = -1;
        remainingVBlanks = 0;
    }

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public Snapshot capture() {
        return new Snapshot(pendingMusicId, remainingVBlanks);
    }

    @Override
    public void restore(Snapshot snapshot) {
        if (snapshot == null) {
            cancel();
            return;
        }
        pendingMusicId = snapshot.pendingMusicId();
        remainingVBlanks = snapshot.remainingVBlanks();
    }

    @Override
    public void resetForMissingSnapshot() {
        cancel();
    }

    public record Snapshot(int pendingMusicId, int remainingVBlanks) {
        public Snapshot {
            if ((pendingMusicId < 0) != (remainingVBlanks == 0)
                    || remainingVBlanks < 0) {
                throw new IllegalArgumentException("inconsistent scheduler snapshot");
            }
        }
    }
}
