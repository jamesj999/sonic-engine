package com.openggf.game.sonic3k.events;

import com.openggf.game.LevelEventProvider;

/**
 * Session-owned boundary for AIZ art that the ROM leaves resident across the
 * act 1-to-act 2 level-data reload.
 */
public interface AizPreparedTransitionArtBridge {

    void retainAizFireOverlay(byte[] tiles8x8);

    byte[] aizFireOverlayCopy();

    int aizFireOverlayTileCount();
    static AizPreparedTransitionArtBridge current(LevelEventProvider provider) {
        if (provider instanceof AizPreparedTransitionArtBridge bridge) {
            return bridge;
        }
        throw new IllegalStateException(
                "S3K AIZ prepared transition art has no session owner");
    }
}
