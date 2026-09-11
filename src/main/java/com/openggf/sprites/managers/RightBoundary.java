package com.openggf.sprites.managers;

/**
 * Pure computation of the player's right level-boundary clamp:
 * {@code maxX + designWidth - spriteWidth} (plus {@code rightExtra} when not strict).
 *
 * <p>Callers pass the level's <em>design</em> width (the native 320), NOT the render
 * viewport: the boundary tracks the level's right wall ({@code Camera_Max_X_pos + 320}),
 * which is fixed by level design and independent of {@code DISPLAY_ASPECT}. Widening
 * it by a wider viewport would let the player walk past the wall into the void beyond a
 * camera lock and fall to their death. At {@code designWidth == 320} this reproduces the
 * ROM values exactly: strict -&gt; {@code maxX + 0x128}, normal -&gt; {@code maxX + 0x128 + 0x40}
 * ({@code 0x128 = 320 - 24}).
 */
public final class RightBoundary {

    private RightBoundary() {
    }

    public static int compute(int maxX, int viewportWidth, int spriteWidth,
            int rightExtra, boolean strict) {
        int boundary = maxX + viewportWidth - spriteWidth;
        if (!strict) {
            boundary += rightExtra;
        }
        return boundary;
    }
}
