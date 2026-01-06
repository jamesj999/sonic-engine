package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Caches and renders ROM-backed object art (springs, spikes, monitors).
 */
public class ObjectRenderManager {
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
    private final ObjectSpriteSheet bridgeSheet;
    private final SpriteAnimationSet monitorAnimations;
    private final SpriteAnimationSet springAnimations;

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
    private final PatternSpriteRenderer bridgeRenderer;

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
        this.bridgeSheet = artData.bridgeSheet();
        this.monitorAnimations = artData.monitorAnimations();
        this.springAnimations = artData.springAnimations();

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
        this.bridgeRenderer = new PatternSpriteRenderer(bridgeSheet);
        register(bridgeSheet, bridgeRenderer);
    }

    private void register(ObjectSpriteSheet sheet, PatternSpriteRenderer renderer) {
        sheetOrder.add(sheet);
        rendererOrder.add(renderer);
    }

    public void ensurePatternsCached(GraphicsManager graphicsManager, int basePatternIndex) {
        int next = basePatternIndex;
        for (int i = 0; i < rendererOrder.size(); i++) {
            ObjectSpriteSheet sheet = sheetOrder.get(i);
            PatternSpriteRenderer renderer = rendererOrder.get(i);
            int count = sheet.getPatterns().length;
            renderer.ensurePatternsCached(graphicsManager, next);
            next += count;
        }
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

    public PatternSpriteRenderer getBridgeRenderer() {
        return bridgeRenderer;
    }

    public ObjectSpriteSheet getBridgeSheet() {
        return bridgeSheet;
    }
}
