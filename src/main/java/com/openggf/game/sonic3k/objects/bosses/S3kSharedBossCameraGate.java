package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.RewindStateful;

/**
 * Shared S3K boss-camera gate used by bosses that call
 * {@code Check_CameraInRange}, {@code sub_85D6A}, and {@code loc_85CA4}.
 */
public final class S3kSharedBossCameraGate
        implements RewindStateful<S3kSharedBossCameraGate.RewindState> {
    private static final int APPROACH_FROM_BELOW_Y_TOLERANCE = 0x60;

    public record LockBounds(int minY, int maxY, int minX, int maxX) {
    }

    public record RewindState(int minY, int maxY, int minX, int maxX,
                              boolean hasBounds, boolean approachFromBelow,
                              boolean approachFromRight, boolean yLocked,
                              boolean xLocked, boolean musicStarted,
                              boolean complete, int musicWaitTimer) {
    }

    private LockBounds lockBounds;
    private boolean approachFromBelow;
    private boolean approachFromRight;
    private boolean yLocked;
    private boolean xLocked;
    private boolean musicStarted;
    private boolean complete;
    private int musicWaitTimer;

    public void reset() {
        lockBounds = null;
        approachFromBelow = false;
        approachFromRight = false;
        yLocked = false;
        xLocked = false;
        musicStarted = false;
        complete = false;
        musicWaitTimer = -1;
    }

    public void begin(Camera camera, LockBounds lockBounds, int musicWaitFrames) {
        this.lockBounds = lockBounds;
        approachFromBelow = camera != null && unsigned(camera.getY()) > lockBounds.minY();
        approachFromRight = camera != null && unsigned(camera.getX()) > lockBounds.minX();
        yLocked = false;
        xLocked = false;
        musicStarted = false;
        complete = false;
        musicWaitTimer = musicWaitFrames;
    }

    public boolean update(Camera camera, Runnable onMusicStart) {
        if (complete) {
            return true;
        }
        if (lockBounds == null || camera == null) {
            complete = true;
            return true;
        }

        if (!musicStarted && musicWaitTimer-- <= 0) {
            musicStarted = true;
            if (onMusicStart != null) {
                onMusicStart.run();
            }
        }

        updateY(camera);
        updateX(camera);
        complete = musicStarted && yLocked && xLocked;
        return complete;
    }

    public boolean isComplete() {
        return complete;
    }

    private void updateY(Camera camera) {
        if (yLocked) {
            return;
        }
        int cameraY = unsigned(camera.getY());
        if (!approachFromBelow) {
            if (cameraY >= lockBounds.minY()) {
                lockY(camera);
            } else {
                camera.setMinY((short) cameraY);
            }
            return;
        }

        if (cameraY <= lockBounds.maxY() + APPROACH_FROM_BELOW_Y_TOLERANCE) {
            lockY(camera);
        }
    }

    private void lockY(Camera camera) {
        yLocked = true;
        camera.setMinY((short) lockBounds.minY());
        camera.setMaxYTarget((short) lockBounds.maxY());
    }

    private void updateX(Camera camera) {
        if (xLocked) {
            return;
        }
        int cameraX = unsigned(camera.getX());
        if (!approachFromRight) {
            if (cameraX >= lockBounds.minX()) {
                lockX(camera);
            } else {
                camera.setMinX((short) cameraX);
            }
            return;
        }

        if (cameraX <= lockBounds.maxX()) {
            lockX(camera);
        } else {
            camera.setMaxX((short) cameraX);
        }
    }

    private void lockX(Camera camera) {
        xLocked = true;
        camera.setMinX((short) lockBounds.minX());
        camera.setMaxX((short) lockBounds.maxX());
    }

    private static int unsigned(short value) {
        return value & 0xFFFF;
    }

    @Override
    public RewindState captureRewindStateValue() {
        LockBounds bounds = lockBounds;
        return new RewindState(
                bounds != null ? bounds.minY() : 0,
                bounds != null ? bounds.maxY() : 0,
                bounds != null ? bounds.minX() : 0,
                bounds != null ? bounds.maxX() : 0,
                bounds != null,
                approachFromBelow, approachFromRight, yLocked, xLocked,
                musicStarted, complete, musicWaitTimer);
    }

    @Override
    public void restoreRewindStateValue(RewindState state) {
        lockBounds = state.hasBounds()
                ? new LockBounds(state.minY(), state.maxY(), state.minX(), state.maxX())
                : null;
        approachFromBelow = state.approachFromBelow();
        approachFromRight = state.approachFromRight();
        yLocked = state.yLocked();
        xLocked = state.xLocked();
        musicStarted = state.musicStarted();
        complete = state.complete();
        musicWaitTimer = state.musicWaitTimer();
    }
}
