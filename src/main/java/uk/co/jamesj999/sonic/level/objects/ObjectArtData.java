package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;

/**
 * Bundles object art and animations loaded from ROM.
 */
public record ObjectArtData(
        ObjectSpriteSheet monitorSheet,
        ObjectSpriteSheet spikeSheet,
        ObjectSpriteSheet spikeSideSheet,
        ObjectSpriteSheet springVerticalSheet,
        ObjectSpriteSheet springHorizontalSheet,
        ObjectSpriteSheet springDiagonalSheet,
        ObjectSpriteSheet springVerticalRedSheet,
        ObjectSpriteSheet springHorizontalRedSheet,
        ObjectSpriteSheet springDiagonalRedSheet,
        ObjectSpriteSheet explosionSheet,
        ObjectSpriteSheet shieldSheet,
        ObjectSpriteSheet invincibilityStarsSheet,
        ObjectSpriteSheet bridgeSheet,
        SpriteAnimationSet monitorAnimations,
        SpriteAnimationSet springAnimations) {
}
