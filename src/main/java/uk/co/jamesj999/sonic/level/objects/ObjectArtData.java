package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;

/**
 * Bundles object art and animations loaded from ROM.
 */
public class ObjectArtData {
        private final ObjectSpriteSheet monitorSheet;
        private final ObjectSpriteSheet spikeSheet;
        private final ObjectSpriteSheet spikeSideSheet;
        private final ObjectSpriteSheet springVerticalSheet;
        private final ObjectSpriteSheet springHorizontalSheet;
        private final ObjectSpriteSheet springDiagonalSheet;
        private final ObjectSpriteSheet springVerticalRedSheet;
        private final ObjectSpriteSheet springHorizontalRedSheet;
        private final ObjectSpriteSheet springDiagonalRedSheet;
        private final ObjectSpriteSheet explosionSheet;
        private final ObjectSpriteSheet shieldSheet;
        private final ObjectSpriteSheet invincibilityStarsSheet;
        private final ObjectSpriteSheet bridgeSheet;
        private final ObjectSpriteSheet waterfallSheet;
        private final ObjectSpriteSheet checkpointSheet;
        private final ObjectSpriteSheet checkpointStarSheet;
        private final ObjectSpriteSheet masherSheet;
        private final ObjectSpriteSheet buzzerSheet;
        private final ObjectSpriteSheet coconutsSheet;
        private final ObjectSpriteSheet animalSheet;
        private final ObjectSpriteSheet pointsSheet;
        private final ObjectSpriteSheet signpostSheet;
        private final ObjectSpriteSheet resultsSheet;
        private final Pattern[] hudDigitPatterns;
        private final Pattern[] hudTextPatterns;
        private final SpriteAnimationSet monitorAnimations;
        private final SpriteAnimationSet springAnimations;
        private final SpriteAnimationSet checkpointAnimations;
        private final SpriteAnimationSet signpostAnimations;

        public ObjectArtData(
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
                        ObjectSpriteSheet resultsSheet,
                        Pattern[] hudDigitPatterns,
                        Pattern[] hudTextPatterns,
                        SpriteAnimationSet monitorAnimations,
                        SpriteAnimationSet springAnimations,
                        SpriteAnimationSet checkpointAnimations,
                        SpriteAnimationSet signpostAnimations) {
                this.monitorSheet = monitorSheet;
                this.spikeSheet = spikeSheet;
                this.spikeSideSheet = spikeSideSheet;
                this.springVerticalSheet = springVerticalSheet;
                this.springHorizontalSheet = springHorizontalSheet;
                this.springDiagonalSheet = springDiagonalSheet;
                this.springVerticalRedSheet = springVerticalRedSheet;
                this.springHorizontalRedSheet = springHorizontalRedSheet;
                this.springDiagonalRedSheet = springDiagonalRedSheet;
                this.explosionSheet = explosionSheet;
                this.shieldSheet = shieldSheet;
                this.invincibilityStarsSheet = invincibilityStarsSheet;
                this.bridgeSheet = bridgeSheet;
                this.waterfallSheet = waterfallSheet;
                this.checkpointSheet = checkpointSheet;
                this.checkpointStarSheet = checkpointStarSheet;
                this.masherSheet = masherSheet;
                this.buzzerSheet = buzzerSheet;
                this.coconutsSheet = coconutsSheet;
                this.animalSheet = animalSheet;
                this.pointsSheet = pointsSheet;
                this.signpostSheet = signpostSheet;
                this.resultsSheet = resultsSheet;
                this.hudDigitPatterns = hudDigitPatterns;
                this.hudTextPatterns = hudTextPatterns;
                this.monitorAnimations = monitorAnimations;
                this.springAnimations = springAnimations;
                this.checkpointAnimations = checkpointAnimations;
                this.signpostAnimations = signpostAnimations;
        }

        public ObjectSpriteSheet monitorSheet() {
                return monitorSheet;
        }

        public ObjectSpriteSheet spikeSheet() {
                return spikeSheet;
        }

        public ObjectSpriteSheet spikeSideSheet() {
                return spikeSideSheet;
        }

        public ObjectSpriteSheet springVerticalSheet() {
                return springVerticalSheet;
        }

        public ObjectSpriteSheet springHorizontalSheet() {
                return springHorizontalSheet;
        }

        public ObjectSpriteSheet springDiagonalSheet() {
                return springDiagonalSheet;
        }

        public ObjectSpriteSheet springVerticalRedSheet() {
                return springVerticalRedSheet;
        }

        public ObjectSpriteSheet springHorizontalRedSheet() {
                return springHorizontalRedSheet;
        }

        public ObjectSpriteSheet springDiagonalRedSheet() {
                return springDiagonalRedSheet;
        }

        public ObjectSpriteSheet explosionSheet() {
                return explosionSheet;
        }

        public ObjectSpriteSheet shieldSheet() {
                return shieldSheet;
        }

        public ObjectSpriteSheet invincibilityStarsSheet() {
                return invincibilityStarsSheet;
        }

        public ObjectSpriteSheet bridgeSheet() {
                return bridgeSheet;
        }

        public ObjectSpriteSheet waterfallSheet() {
                return waterfallSheet;
        }

        public ObjectSpriteSheet checkpointSheet() {
                return checkpointSheet;
        }

        public ObjectSpriteSheet checkpointStarSheet() {
                return checkpointStarSheet;
        }

        public ObjectSpriteSheet masherSheet() {
                return masherSheet;
        }

        public ObjectSpriteSheet buzzerSheet() {
                return buzzerSheet;
        }

        public ObjectSpriteSheet coconutsSheet() {
                return coconutsSheet;
        }

        public ObjectSpriteSheet animalSheet() {
                return animalSheet;
        }

        public ObjectSpriteSheet pointsSheet() {
                return pointsSheet;
        }

        public ObjectSpriteSheet signpostSheet() {
                return signpostSheet;
        }

        public ObjectSpriteSheet resultsSheet() {
                return resultsSheet;
        }

        public Pattern[] getHudDigitPatterns() {
                return hudDigitPatterns;
        }

        public Pattern[] getHudTextPatterns() {
                return hudTextPatterns;
        }

        public SpriteAnimationSet monitorAnimations() {
                return monitorAnimations;
        }

        public SpriteAnimationSet springAnimations() {
                return springAnimations;
        }

        public SpriteAnimationSet checkpointAnimations() {
                return checkpointAnimations;
        }

        public SpriteAnimationSet signpostAnimations() {
                return signpostAnimations;
        }
}
