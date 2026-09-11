package com.openggf.sprites.ghost;

import com.openggf.sprites.art.SpriteArtSet;

public final class GhostArtBankAllocator {
    private GhostArtBankAllocator() {
    }

    public static SpriteArtSet shiftToGhostBank(SpriteArtSet source, int basePatternIndex) {
        if (source == null) {
            return SpriteArtSet.EMPTY;
        }
        return new SpriteArtSet(
                source.artTiles(),
                source.mappingFrames(),
                source.dplcFrames(),
                source.paletteIndex(),
                basePatternIndex,
                source.frameDelay(),
                source.bankSize(),
                source.animationProfile(),
                source.animationSet());
    }

    public static int nextBankBase(int currentBase, int bankSize) {
        return currentBase + Math.max(0, bankSize);
    }
}
