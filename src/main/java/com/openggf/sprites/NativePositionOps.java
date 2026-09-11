package com.openggf.sprites;

import com.openggf.game.PlayableEntity;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Central helpers for ROM native x_pos/y_pos writes on playable sprites.
 */
public final class NativePositionOps {
    private NativePositionOps() {
    }

    public static void writeXPosPreserveSubpixel(AbstractPlayableSprite player, int x) {
        player.setCentreXPreserveSubpixel((short) x);
    }

    public static void writeYPosPreserveSubpixel(AbstractPlayableSprite player, int y) {
        player.setCentreYPreserveSubpixel((short) y);
    }

    public static void writeYPosPreserveSubpixel(PlayableEntity player, int y) {
        player.setCentreYPreserveSubpixel((short) y);
    }

    public static void addXPosPreserveSubpixel(AbstractPlayableSprite player, int dx) {
        player.shiftX(dx);
    }

    /**
     * Adds a native 16:16 fixed-point delta to {@code x_pos}.
     *
     * <p>This mirrors a 68000 {@code add.l} against the pixel/subpixel pair and
     * is used by object routines whose motion table contains sub-pixel forces.
     */
    public static void addXPos16_16(AbstractPlayableSprite player, int delta16_16) {
        int nativePosition = (player.getCentreX() << 16) | player.getXSubpixelRaw();
        nativePosition += delta16_16;
        player.setCentreXPreserveSubpixel((short) (nativePosition >> 16));
        player.setSubpixelRaw(nativePosition & 0xFFFF, player.getYSubpixelRaw());
    }

    public static void addYPosPreserveSubpixel(AbstractPlayableSprite player, int dy) {
        player.shiftY(dy);
    }

    public static void writeXPosResetSubpixel(AbstractPlayableSprite player, int x) {
        player.setCentreX((short) x);
    }

    public static void writeYPosResetSubpixel(AbstractPlayableSprite player, int y) {
        player.setCentreY((short) y);
    }
}
