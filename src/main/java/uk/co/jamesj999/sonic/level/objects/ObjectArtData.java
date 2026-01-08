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
                ObjectSpriteSheet waterfallSheet,
                ObjectSpriteSheet checkpointSheet,
                ObjectSpriteSheet checkpointStarSheet,
                ObjectSpriteSheet masherSheet,
                ObjectSpriteSheet buzzerSheet,
                ObjectSpriteSheet coconutsSheet,
                ObjectSpriteSheet animalSheet,
                ObjectSpriteSheet pointsSheet,
                ObjectSpriteSheet signpostSheet,
                SpriteAnimationSet monitorAnimations,
                SpriteAnimationSet springAnimations,
                SpriteAnimationSet checkpointAnimations,
                SpriteAnimationSet signpostAnimations) {
}
