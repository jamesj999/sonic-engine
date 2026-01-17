package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Caches and renders ROM-backed object art (springs, spikes, monitors).
 */
public class ObjectRenderManager {
    private static final Logger LOGGER = Logger.getLogger(ObjectRenderManager.class.getName());

    public enum SpringVariant {
        VERTICAL,
        HORIZONTAL,
        DIAGONAL
    }

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
    private final SpriteAnimationSet monitorAnimations;
    private final SpriteAnimationSet springAnimations;
    private final SpriteAnimationSet checkpointAnimations;

    // Badnik sheets
    private final ObjectSpriteSheet masherSheet;
    private final ObjectSpriteSheet buzzerSheet;
    private final ObjectSpriteSheet coconutsSheet;
    private final ObjectSpriteSheet animalSheet;
    private final ObjectSpriteSheet pointsSheet;
    private final int animalTypeA;
    private final int animalTypeB;

    // Signpost
    private final ObjectSpriteSheet signpostSheet;
    private final SpriteAnimationSet signpostAnimations;

    // CNZ Bumpers
    private final ObjectSpriteSheet bumperSheet;
    private final ObjectSpriteSheet hexBumperSheet;
    private final ObjectSpriteSheet bonusBlockSheet;
    private final ObjectSpriteSheet flipperSheet;
    private final SpriteAnimationSet flipperAnimations;

    // Results screen
    private final ObjectSpriteSheet resultsSheet;
    private final Pattern[] resultsHudDigitPatterns;

    private final PatternSpriteRenderer monitorRenderer;
    private final PatternSpriteRenderer spikeRenderer;
    private final PatternSpriteRenderer spikeSideRenderer;
    private final PatternSpriteRenderer springVerticalRenderer;
    private final PatternSpriteRenderer springHorizontalRenderer;
    private final PatternSpriteRenderer springDiagonalRenderer;
    private final PatternSpriteRenderer springVerticalRedRenderer;
    private final PatternSpriteRenderer springHorizontalRedRenderer;
    private final PatternSpriteRenderer springDiagonalRedRenderer;
    private final PatternSpriteRenderer explosionRenderer;
    private final PatternSpriteRenderer shieldRenderer;
    private final PatternSpriteRenderer invincibilityStarsRenderer;
    private final PatternSpriteRenderer bridgeRenderer;
    private final PatternSpriteRenderer waterfallRenderer;
    private final PatternSpriteRenderer checkpointRenderer;
    private final PatternSpriteRenderer checkpointStarRenderer;

    // Badnik renderers
    private final PatternSpriteRenderer masherRenderer;
    private final PatternSpriteRenderer buzzerRenderer;
    private final PatternSpriteRenderer coconutsRenderer;
    private final PatternSpriteRenderer animalRenderer;
    private final PatternSpriteRenderer pointsRenderer;

    // Signpost renderer
    private final PatternSpriteRenderer signpostRenderer;

    // CNZ Bumper renderers
    private final PatternSpriteRenderer bumperRenderer;
    private final PatternSpriteRenderer hexBumperRenderer;
    private final PatternSpriteRenderer bonusBlockRenderer;
    private final PatternSpriteRenderer flipperRenderer;

    // Results screen renderer
    private final PatternSpriteRenderer resultsRenderer;

    private final List<ObjectSpriteSheet> sheetOrder = new ArrayList<>();
    private final List<PatternSpriteRenderer> rendererOrder = new ArrayList<>();

    public ObjectRenderManager(ObjectArtData artData) {
        this.monitorSheet = artData.monitorSheet();
        this.spikeSheet = artData.spikeSheet();
        this.spikeSideSheet = artData.spikeSideSheet();
        this.springVerticalSheet = artData.springVerticalSheet();
        this.springHorizontalSheet = artData.springHorizontalSheet();
        this.springDiagonalSheet = artData.springDiagonalSheet();
        this.springVerticalRedSheet = artData.springVerticalRedSheet();
        this.springHorizontalRedSheet = artData.springHorizontalRedSheet();
        this.springDiagonalRedSheet = artData.springDiagonalRedSheet();
        this.explosionSheet = artData.explosionSheet();
        this.shieldSheet = artData.shieldSheet();
        this.invincibilityStarsSheet = artData.invincibilityStarsSheet();
        this.bridgeSheet = artData.bridgeSheet();
        this.waterfallSheet = artData.waterfallSheet();
        this.checkpointSheet = artData.checkpointSheet();
        this.checkpointStarSheet = artData.checkpointStarSheet();
        this.monitorAnimations = artData.monitorAnimations();
        this.springAnimations = artData.springAnimations();
        this.checkpointAnimations = artData.checkpointAnimations();

        // Badnik sheets
        this.masherSheet = artData.masherSheet();
        this.buzzerSheet = artData.buzzerSheet();
        this.coconutsSheet = artData.coconutsSheet();
        this.animalSheet = artData.animalSheet();
        this.pointsSheet = artData.pointsSheet();
        this.animalTypeA = artData.getAnimalTypeA();
        this.animalTypeB = artData.getAnimalTypeB();

        // Signpost
        this.signpostSheet = artData.signpostSheet();
        this.signpostAnimations = artData.signpostAnimations();

        // CNZ Bumpers
        this.bumperSheet = artData.bumperSheet();
        this.hexBumperSheet = artData.hexBumperSheet();
        this.bonusBlockSheet = artData.bonusBlockSheet();
        this.flipperSheet = artData.flipperSheet();
        this.flipperAnimations = artData.flipperAnimations();

        // Results screen
        this.resultsSheet = artData.resultsSheet();
        this.resultsHudDigitPatterns = artData.getHudDigitPatterns();

        this.monitorRenderer = new PatternSpriteRenderer(monitorSheet);
        this.spikeRenderer = new PatternSpriteRenderer(spikeSheet);
        this.spikeSideRenderer = new PatternSpriteRenderer(spikeSideSheet);
        this.springVerticalRenderer = new PatternSpriteRenderer(springVerticalSheet);
        this.springHorizontalRenderer = new PatternSpriteRenderer(springHorizontalSheet);
        this.springDiagonalRenderer = new PatternSpriteRenderer(springDiagonalSheet);
        this.springVerticalRedRenderer = new PatternSpriteRenderer(springVerticalRedSheet);
        this.springHorizontalRedRenderer = new PatternSpriteRenderer(springHorizontalRedSheet);
        this.springDiagonalRedRenderer = new PatternSpriteRenderer(springDiagonalRedSheet);
        this.explosionRenderer = new PatternSpriteRenderer(explosionSheet);

        register(monitorSheet, monitorRenderer);
        register(spikeSheet, spikeRenderer);
        register(spikeSideSheet, spikeSideRenderer);
        register(springVerticalSheet, springVerticalRenderer);
        register(springHorizontalSheet, springHorizontalRenderer);
        register(springDiagonalSheet, springDiagonalRenderer);
        register(springVerticalRedSheet, springVerticalRedRenderer);
        register(springHorizontalRedSheet, springHorizontalRedRenderer);
        register(springDiagonalRedSheet, springDiagonalRedRenderer);
        register(explosionSheet, explosionRenderer);
        this.shieldRenderer = new PatternSpriteRenderer(shieldSheet);
        register(shieldSheet, shieldRenderer);
        this.invincibilityStarsRenderer = new PatternSpriteRenderer(invincibilityStarsSheet);
        register(invincibilityStarsSheet, invincibilityStarsRenderer);
        this.bridgeRenderer = new PatternSpriteRenderer(bridgeSheet);
        register(bridgeSheet, bridgeRenderer);
        this.waterfallRenderer = new PatternSpriteRenderer(waterfallSheet);
        register(waterfallSheet, waterfallRenderer);
        this.checkpointRenderer = new PatternSpriteRenderer(checkpointSheet);
        register(checkpointSheet, checkpointRenderer);
        this.checkpointStarRenderer = new PatternSpriteRenderer(checkpointStarSheet);
        register(checkpointStarSheet, checkpointStarRenderer);

        // Badnik renderers
        this.masherRenderer = new PatternSpriteRenderer(masherSheet);
        register(masherSheet, masherRenderer);
        this.buzzerRenderer = new PatternSpriteRenderer(buzzerSheet);
        register(buzzerSheet, buzzerRenderer);
        this.coconutsRenderer = new PatternSpriteRenderer(coconutsSheet);
        register(coconutsSheet, coconutsRenderer);
        this.animalRenderer = new PatternSpriteRenderer(animalSheet);
        register(animalSheet, animalRenderer);
        this.pointsRenderer = new PatternSpriteRenderer(pointsSheet);
        register(pointsSheet, pointsRenderer);

        // Signpost renderer
        this.signpostRenderer = new PatternSpriteRenderer(signpostSheet);
        register(signpostSheet, signpostRenderer);

        // CNZ Bumper renderers
        this.bumperRenderer = new PatternSpriteRenderer(bumperSheet);
        register(bumperSheet, bumperRenderer);
        this.hexBumperRenderer = new PatternSpriteRenderer(hexBumperSheet);
        register(hexBumperSheet, hexBumperRenderer);
        this.bonusBlockRenderer = new PatternSpriteRenderer(bonusBlockSheet);
        register(bonusBlockSheet, bonusBlockRenderer);
        this.flipperRenderer = new PatternSpriteRenderer(flipperSheet);
        register(flipperSheet, flipperRenderer);

        // Results screen - NOT registered in sheetOrder, uses separate caching
        this.resultsRenderer = new PatternSpriteRenderer(resultsSheet);
        // Don't register - Results gets its own pattern namespace in
        // ensurePatternsCached
    }

    private void register(ObjectSpriteSheet sheet, PatternSpriteRenderer renderer) {
        sheetOrder.add(sheet);
        rendererOrder.add(renderer);
    }

    public int ensurePatternsCached(GraphicsManager graphicsManager, int basePatternIndex) {
        int next = basePatternIndex;
        for (int i = 0; i < rendererOrder.size(); i++) {
            ObjectSpriteSheet sheet = sheetOrder.get(i);
            PatternSpriteRenderer renderer = rendererOrder.get(i);
            int count = sheet.getPatterns().length;
            renderer.ensurePatternsCached(graphicsManager, next);
            next += count;
        }

        // Results screen uses a dedicated pattern namespace starting at 0.
        // This ensures its tile indices (0-465) map directly to texture IDs,
        // avoiding issues with high basePatternIndex values.
        // We use a high offset (0x10000) to avoid collision with level/object patterns.
        resultsRenderer.ensurePatternsCached(graphicsManager, 0x10000);

        return next;
    }

    public boolean isReady() {
        return monitorRenderer.isReady()
                || spikeRenderer.isReady()
                || springVerticalRenderer.isReady();
    }

    public PatternSpriteRenderer getMonitorRenderer() {
        return monitorRenderer;
    }

    public ObjectSpriteSheet getMonitorSheet() {
        return monitorSheet;
    }

    public SpriteAnimationSet getMonitorAnimations() {
        return monitorAnimations;
    }

    public PatternSpriteRenderer getSpikeRenderer(boolean sideways) {
        return sideways ? spikeSideRenderer : spikeRenderer;
    }

    public ObjectSpriteSheet getSpikeSheet(boolean sideways) {
        return sideways ? spikeSideSheet : spikeSheet;
    }

    public PatternSpriteRenderer getSpringRenderer(SpringVariant variant, boolean red) {
        return switch (variant) {
            case HORIZONTAL -> red ? springHorizontalRedRenderer : springHorizontalRenderer;
            case DIAGONAL -> red ? springDiagonalRedRenderer : springDiagonalRenderer;
            case VERTICAL -> red ? springVerticalRedRenderer : springVerticalRenderer;
        };
    }

    public ObjectSpriteSheet getSpringSheet(SpringVariant variant, boolean red) {
        return switch (variant) {
            case HORIZONTAL -> red ? springHorizontalRedSheet : springHorizontalSheet;
            case DIAGONAL -> red ? springDiagonalRedSheet : springDiagonalSheet;
            case VERTICAL -> red ? springVerticalRedSheet : springVerticalSheet;
        };
    }

    public SpriteAnimationSet getSpringAnimations() {
        return springAnimations;
    }

    public PatternSpriteRenderer getExplosionRenderer() {
        return explosionRenderer;
    }

    public PatternSpriteRenderer getShieldRenderer() {
        return shieldRenderer;
    }

    public PatternSpriteRenderer getInvincibilityStarsRenderer() {
        return invincibilityStarsRenderer;
    }

    public PatternSpriteRenderer getBridgeRenderer() {
        return bridgeRenderer;
    }

    public ObjectSpriteSheet getBridgeSheet() {
        return bridgeSheet;
    }

    public PatternSpriteRenderer getWaterfallRenderer() {
        return waterfallRenderer;
    }

    public ObjectSpriteSheet getWaterfallSheet() {
        return waterfallSheet;
    }

    public PatternSpriteRenderer getCheckpointRenderer() {
        return checkpointRenderer;
    }

    public PatternSpriteRenderer getCheckpointStarRenderer() {
        return checkpointStarRenderer;
    }

    public ObjectSpriteSheet getCheckpointSheet() {
        return checkpointSheet;
    }

    public ObjectSpriteSheet getCheckpointStarSheet() {
        return checkpointStarSheet;
    }

    public SpriteAnimationSet getCheckpointAnimations() {
        return checkpointAnimations;
    }

    public PatternSpriteRenderer getMasherRenderer() {
        return masherRenderer;
    }

    public PatternSpriteRenderer getBuzzerRenderer() {
        return buzzerRenderer;
    }

    public PatternSpriteRenderer getCoconutsRenderer() {
        return coconutsRenderer;
    }

    public PatternSpriteRenderer getAnimalRenderer() {
        return animalRenderer;
    }

    public PatternSpriteRenderer getPointsRenderer() {
        return pointsRenderer;
    }

    public int getAnimalTypeA() {
        return animalTypeA;
    }

    public int getAnimalTypeB() {
        return animalTypeB;
    }

    public PatternSpriteRenderer getSignpostRenderer() {
        return signpostRenderer;
    }

    public ObjectSpriteSheet getSignpostSheet() {
        return signpostSheet;
    }

    public SpriteAnimationSet getSignpostAnimations() {
        return signpostAnimations;
    }

    public PatternSpriteRenderer getBumperRenderer() {
        return bumperRenderer;
    }

    public ObjectSpriteSheet getBumperSheet() {
        return bumperSheet;
    }

    public PatternSpriteRenderer getHexBumperRenderer() {
        return hexBumperRenderer;
    }

    public ObjectSpriteSheet getHexBumperSheet() {
        return hexBumperSheet;
    }

    public PatternSpriteRenderer getBonusBlockRenderer() {
        return bonusBlockRenderer;
    }

    public ObjectSpriteSheet getBonusBlockSheet() {
        return bonusBlockSheet;
    }

    public PatternSpriteRenderer getFlipperRenderer() {
        return flipperRenderer;
    }

    public ObjectSpriteSheet getFlipperSheet() {
        return flipperSheet;
    }

    public SpriteAnimationSet getFlipperAnimations() {
        return flipperAnimations;
    }

    public PatternSpriteRenderer getResultsRenderer() {
        return resultsRenderer;
    }

    public ObjectSpriteSheet getResultsSheet() {
        return resultsSheet;
    }

    public Pattern[] getResultsHudDigitPatterns() {
        return resultsHudDigitPatterns;
    }
}
