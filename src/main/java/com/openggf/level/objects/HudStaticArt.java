package com.openggf.level.objects;

import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;

/**
 * Shared static HUD art bundle for mapping-driven HUD rendering.
 * <p>
 * {@code patterns} contains the decoded HUD tile graphics in the same order used by
 * the supplied base pattern index. The mapping frames reference those tiles by
 * relative tile index, so a caller can cache the patterns once and then render each
 * frame by adding the base index to the frame's tile references.
 */
public record HudStaticArt(
        Pattern[] patterns,
        SpriteMappingFrame scoreFrame,
        SpriteMappingFrame debugScoreFrame,
        SpriteMappingFrame timeFrame,
        SpriteMappingFrame timeFlashFrame,
        SpriteMappingFrame ringsFrame,
        SpriteMappingFrame ringsFlashFrame,
        SpriteMappingFrame livesFrame
) {
}
