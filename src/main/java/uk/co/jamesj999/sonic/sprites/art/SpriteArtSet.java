package uk.co.jamesj999.sonic.sprites.art;

import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.render.SpriteDplcFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;

import java.util.List;

/**
 * Game-agnostic sprite art bundle (tile art + mappings + DPLCs).
 */
public record SpriteArtSet(
        Pattern[] artTiles,
        List<SpriteMappingFrame> mappingFrames,
        List<SpriteDplcFrame> dplcFrames,
        int paletteIndex,
        int basePatternIndex,
        int frameDelay,
        int bankSize,
        SpriteAnimationProfile animationProfile,
        SpriteAnimationSet animationSet
) {
}
