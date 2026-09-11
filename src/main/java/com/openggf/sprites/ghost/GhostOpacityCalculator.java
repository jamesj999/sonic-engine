package com.openggf.sprites.ghost;

public final class GhostOpacityCalculator {
    private GhostOpacityCalculator() {
    }

    public static float alphaForDistance(int dx, int dy, int fullOpacityDistance) {
        if (fullOpacityDistance <= 0) {
            return dx == 0 && dy == 0 ? 0.0f : 1.0f;
        }
        double distance = Math.hypot(dx, dy);
        double alpha = distance / fullOpacityDistance;
        if (alpha <= 0.0) {
            return 0.0f;
        }
        if (alpha >= 1.0) {
            return 1.0f;
        }
        return (float) alpha;
    }
}
