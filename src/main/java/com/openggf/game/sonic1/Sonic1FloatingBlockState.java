package com.openggf.game.sonic1;

/**
 * Runtime owner for Sonic 1's {@code f_obj56} byte.
 *
 * <p>REV01 uses this flag to swap the two subtype-$37 SYZ moving-block
 * placements: the real block begins at $1BB8, and the stationary proxy at
 * $1F38 exists only after the real block has completed its $380-pixel trip.</p>
 */
public final class Sonic1FloatingBlockState {
    private boolean tunnelBlockAtDestination;

    public boolean isTunnelBlockAtDestination() {
        return tunnelBlockAtDestination;
    }

    public void markTunnelBlockAtDestination() {
        tunnelBlockAtDestination = true;
    }

    public void reset() {
        tunnelBlockAtDestination = false;
    }

    public Snapshot capture() {
        return new Snapshot(tunnelBlockAtDestination);
    }

    public void restore(Snapshot snapshot) {
        tunnelBlockAtDestination = snapshot != null && snapshot.tunnelBlockAtDestination();
    }

    public record Snapshot(boolean tunnelBlockAtDestination) {
    }
}
