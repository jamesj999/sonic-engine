package com.openggf.game.sonic3k.objects.bosses;

/** Shared ROM {@code Obj_FlickerMove} fixed-point and native culling primitives. */
final class S3kBossFlickerMove {
    private static final int COARSE_MASK = 0xFF80;

    private S3kBossFlickerMove() {
    }

    static int integrate(int fixedPosition, int velocity) {
        return fixedPosition + velocity;
    }

    static boolean isVisible(int flickerCounter) {
        return (flickerCounter & 1) == 0;
    }

    static boolean isOutsideNativeBounds(int centreX, int centreY, int cameraX, int cameraY) {
        int coarseBack = (cameraX - 0x80) & COARSE_MASK;
        int coarseDeltaX = (centreX & COARSE_MASK) - coarseBack;
        int deltaY = centreY - cameraY + 0x80;
        return Integer.compareUnsigned(coarseDeltaX & 0xFFFF, 0x280) > 0
                || Integer.compareUnsigned(deltaY & 0xFFFF, 0x200) > 0;
    }
}
