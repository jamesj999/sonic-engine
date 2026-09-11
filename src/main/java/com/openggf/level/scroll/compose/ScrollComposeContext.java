package com.openggf.level.scroll.compose;

/**
 * Frame-local inputs for scroll composition helpers.
 *
 * <p>The composer helpers deliberately depend on a tiny value object instead of the full runtime
 * so parallax math stays deterministic, testable, and reusable across zones.
 */
public record ScrollComposeContext(int cameraX,
                                   int cameraY,
                                   int frameCounter,
                                   int actId) {
}
