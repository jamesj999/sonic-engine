package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;

import java.util.List;

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
        private final int animalTypeA;
        private final int animalTypeB;
        private final ObjectSpriteSheet pointsSheet;
        private final ObjectSpriteSheet signpostSheet;
        private final ObjectSpriteSheet bumperSheet;
        private final ObjectSpriteSheet hexBumperSheet;
        private final ObjectSpriteSheet bonusBlockSheet;
        private final ObjectSpriteSheet flipperSheet;
        private final ObjectSpriteSheet speedBoosterSheet;
        private final ObjectSpriteSheet blueBallsSheet;
        private final ObjectSpriteSheet breakableBlockSheet;
        private final ObjectSpriteSheet cpzPlatformSheet;
        private final ObjectSpriteSheet cpzStairBlockSheet;
        private final ObjectSpriteSheet pipeExitSpringSheet;
        private final ObjectSpriteSheet tippingFloorSheet;
        private final ObjectSpriteSheet resultsSheet;
        private final Pattern[] hudDigitPatterns;
        private final Pattern[] hudTextPatterns;
        private final Pattern[] hudLivesPatterns;
        private final Pattern[] hudLivesNumbers;
        private final Pattern[] debugFontPatterns;
        private final List<SpriteMappingFrame> obj26Mappings;
        private final List<SpriteMappingFrame> obj41Mappings;
        private final List<SpriteMappingFrame> obj79Mappings;
        private final SpriteAnimationSet monitorAnimations;
        private final SpriteAnimationSet springAnimations;
        private final SpriteAnimationSet checkpointAnimations;
        private final SpriteAnimationSet signpostAnimations;
        private final SpriteAnimationSet flipperAnimations;
        private final SpriteAnimationSet pipeExitSpringAnimations;
        private final SpriteAnimationSet tippingFloorAnimations;

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
                        int animalTypeA,
                        int animalTypeB,
                        ObjectSpriteSheet pointsSheet,
                        ObjectSpriteSheet signpostSheet,
                        ObjectSpriteSheet bumperSheet,
                        ObjectSpriteSheet hexBumperSheet,
                        ObjectSpriteSheet bonusBlockSheet,
                        ObjectSpriteSheet flipperSheet,
                        ObjectSpriteSheet speedBoosterSheet,
                        ObjectSpriteSheet blueBallsSheet,
                        ObjectSpriteSheet breakableBlockSheet,
                        ObjectSpriteSheet cpzPlatformSheet,
                        ObjectSpriteSheet cpzStairBlockSheet,
                        ObjectSpriteSheet pipeExitSpringSheet,
                        ObjectSpriteSheet tippingFloorSheet,
                        ObjectSpriteSheet resultsSheet,
                        Pattern[] hudDigitPatterns,
                        Pattern[] hudTextPatterns,
                        Pattern[] hudLivesPatterns,
                        Pattern[] hudLivesNumbers,
                        Pattern[] debugFontPatterns,
                        List<SpriteMappingFrame> obj26Mappings,
                        List<SpriteMappingFrame> obj41Mappings,
                        List<SpriteMappingFrame> obj79Mappings,
                        SpriteAnimationSet monitorAnimations,
                        SpriteAnimationSet springAnimations,
                        SpriteAnimationSet checkpointAnimations,
                        SpriteAnimationSet signpostAnimations,
                        SpriteAnimationSet flipperAnimations,
                        SpriteAnimationSet pipeExitSpringAnimations,
                        SpriteAnimationSet tippingFloorAnimations) {
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
                this.animalTypeA = animalTypeA;
                this.animalTypeB = animalTypeB;
                this.pointsSheet = pointsSheet;
                this.signpostSheet = signpostSheet;
                this.bumperSheet = bumperSheet;
                this.hexBumperSheet = hexBumperSheet;
                this.bonusBlockSheet = bonusBlockSheet;
                this.flipperSheet = flipperSheet;
                this.speedBoosterSheet = speedBoosterSheet;
                this.blueBallsSheet = blueBallsSheet;
                this.breakableBlockSheet = breakableBlockSheet;
                this.cpzPlatformSheet = cpzPlatformSheet;
                this.cpzStairBlockSheet = cpzStairBlockSheet;
                this.pipeExitSpringSheet = pipeExitSpringSheet;
                this.tippingFloorSheet = tippingFloorSheet;
                this.resultsSheet = resultsSheet;
                this.hudDigitPatterns = hudDigitPatterns;
                this.hudTextPatterns = hudTextPatterns;
                this.hudLivesPatterns = hudLivesPatterns;
                this.hudLivesNumbers = hudLivesNumbers;
                this.debugFontPatterns = debugFontPatterns;
                this.obj26Mappings = obj26Mappings;
                this.obj41Mappings = obj41Mappings;
                this.obj79Mappings = obj79Mappings;
                this.monitorAnimations = monitorAnimations;
                this.springAnimations = springAnimations;
                this.checkpointAnimations = checkpointAnimations;
                this.signpostAnimations = signpostAnimations;
                this.flipperAnimations = flipperAnimations;
                this.pipeExitSpringAnimations = pipeExitSpringAnimations;
                this.tippingFloorAnimations = tippingFloorAnimations;
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

        public int getAnimalTypeA() {
                return animalTypeA;
        }

        public int getAnimalTypeB() {
                return animalTypeB;
        }

        public ObjectSpriteSheet pointsSheet() {
                return pointsSheet;
        }

        public ObjectSpriteSheet signpostSheet() {
                return signpostSheet;
        }

        public ObjectSpriteSheet bumperSheet() {
                return bumperSheet;
        }

        public ObjectSpriteSheet hexBumperSheet() {
                return hexBumperSheet;
        }

        public ObjectSpriteSheet bonusBlockSheet() {
                return bonusBlockSheet;
        }

        public ObjectSpriteSheet flipperSheet() {
                return flipperSheet;
        }

        public ObjectSpriteSheet speedBoosterSheet() {
                return speedBoosterSheet;
        }

        public ObjectSpriteSheet blueBallsSheet() {
                return blueBallsSheet;
        }

        public ObjectSpriteSheet breakableBlockSheet() {
                return breakableBlockSheet;
        }

        public ObjectSpriteSheet cpzPlatformSheet() {
                return cpzPlatformSheet;
        }

        public ObjectSpriteSheet cpzStairBlockSheet() {
                return cpzStairBlockSheet;
        }

        public ObjectSpriteSheet pipeExitSpringSheet() {
                return pipeExitSpringSheet;
        }

        public ObjectSpriteSheet tippingFloorSheet() {
                return tippingFloorSheet;
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

        public Pattern[] getHudLivesPatterns() {
                return hudLivesPatterns;
        }

        public Pattern[] getHudLivesNumbers() {
                return hudLivesNumbers;
        }

        public Pattern[] getDebugFontPatterns() {
                return debugFontPatterns;
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

        public SpriteAnimationSet flipperAnimations() {
                return flipperAnimations;
        }

        public SpriteAnimationSet pipeExitSpringAnimations() {
                return pipeExitSpringAnimations;
        }

        public SpriteAnimationSet tippingFloorAnimations() {
                return tippingFloorAnimations;
        }
}
