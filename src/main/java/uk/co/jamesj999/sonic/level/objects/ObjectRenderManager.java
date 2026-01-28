package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.game.ObjectArtProvider;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationSet;

import java.util.List;
import java.util.logging.Logger;

/**
 * Manages object art rendering by delegating to a game-specific ObjectArtProvider.
 * Provides both key-based lookups and typed convenience methods for backward compatibility.
 */
public class ObjectRenderManager {
    private static final Logger LOGGER = Logger.getLogger(ObjectRenderManager.class.getName());

    public enum SpringVariant {
        VERTICAL,
        HORIZONTAL,
        DIAGONAL
    }

    private final ObjectArtProvider provider;

    /**
     * Creates an ObjectRenderManager using the given provider.
     *
     * @param provider the game-specific object art provider
     */
    public ObjectRenderManager(ObjectArtProvider provider) {
        this.provider = provider;
    }

    public int ensurePatternsCached(GraphicsManager graphicsManager, int basePatternIndex) {
        return provider.ensurePatternsCached(graphicsManager, basePatternIndex);
    }

    public boolean isReady() {
        return provider.isReady();
    }

    // Key-based lookups

    /**
     * Gets a renderer by its key.
     *
     * @param key the renderer key
     * @return the renderer, or null if not found
     */
    public PatternSpriteRenderer getRenderer(String key) {
        return provider.getRenderer(key);
    }

    /**
     * Gets a sprite sheet by its key.
     *
     * @param key the sheet key
     * @return the sprite sheet, or null if not found
     */
    public ObjectSpriteSheet getSheet(String key) {
        return provider.getSheet(key);
    }

    /**
     * Gets an animation set by its key.
     *
     * @param key the animation key
     * @return the animation set, or null if not found
     */
    public SpriteAnimationSet getAnimations(String key) {
        return provider.getAnimations(key);
    }

    /**
     * Gets all available renderer keys.
     *
     * @return list of renderer keys
     */
    public List<String> getRendererKeys() {
        return provider.getRendererKeys();
    }

    // Convenience methods for backward compatibility

    public PatternSpriteRenderer getMonitorRenderer() {
        return provider.getRenderer(ObjectArtKeys.MONITOR);
    }

    public ObjectSpriteSheet getMonitorSheet() {
        return provider.getSheet(ObjectArtKeys.MONITOR);
    }

    public SpriteAnimationSet getMonitorAnimations() {
        return provider.getAnimations(ObjectArtKeys.ANIM_MONITOR);
    }

    public PatternSpriteRenderer getSpikeRenderer(boolean sideways) {
        return sideways
                ? provider.getRenderer(ObjectArtKeys.SPIKE_SIDE)
                : provider.getRenderer(ObjectArtKeys.SPIKE);
    }

    public ObjectSpriteSheet getSpikeSheet(boolean sideways) {
        return sideways
                ? provider.getSheet(ObjectArtKeys.SPIKE_SIDE)
                : provider.getSheet(ObjectArtKeys.SPIKE);
    }

    public PatternSpriteRenderer getSpringRenderer(SpringVariant variant, boolean red) {
        String key = switch (variant) {
            case HORIZONTAL -> red ? ObjectArtKeys.SPRING_HORIZONTAL_RED : ObjectArtKeys.SPRING_HORIZONTAL;
            case DIAGONAL -> red ? ObjectArtKeys.SPRING_DIAGONAL_RED : ObjectArtKeys.SPRING_DIAGONAL;
            case VERTICAL -> red ? ObjectArtKeys.SPRING_VERTICAL_RED : ObjectArtKeys.SPRING_VERTICAL;
        };
        return provider.getRenderer(key);
    }

    public ObjectSpriteSheet getSpringSheet(SpringVariant variant, boolean red) {
        String key = switch (variant) {
            case HORIZONTAL -> red ? ObjectArtKeys.SPRING_HORIZONTAL_RED : ObjectArtKeys.SPRING_HORIZONTAL;
            case DIAGONAL -> red ? ObjectArtKeys.SPRING_DIAGONAL_RED : ObjectArtKeys.SPRING_DIAGONAL;
            case VERTICAL -> red ? ObjectArtKeys.SPRING_VERTICAL_RED : ObjectArtKeys.SPRING_VERTICAL;
        };
        return provider.getSheet(key);
    }

    public SpriteAnimationSet getSpringAnimations() {
        return provider.getAnimations(ObjectArtKeys.ANIM_SPRING);
    }

    public PatternSpriteRenderer getExplosionRenderer() {
        return provider.getRenderer(ObjectArtKeys.EXPLOSION);
    }

    public PatternSpriteRenderer getShieldRenderer() {
        return provider.getRenderer(ObjectArtKeys.SHIELD);
    }

    public PatternSpriteRenderer getInvincibilityStarsRenderer() {
        return provider.getRenderer(ObjectArtKeys.INVINCIBILITY_STARS);
    }

    public PatternSpriteRenderer getBridgeRenderer() {
        return provider.getRenderer(ObjectArtKeys.BRIDGE);
    }

    public ObjectSpriteSheet getBridgeSheet() {
        return provider.getSheet(ObjectArtKeys.BRIDGE);
    }

    public PatternSpriteRenderer getCheckpointRenderer() {
        return provider.getRenderer(ObjectArtKeys.CHECKPOINT);
    }

    public PatternSpriteRenderer getCheckpointStarRenderer() {
        return provider.getRenderer(ObjectArtKeys.CHECKPOINT_STAR);
    }

    public ObjectSpriteSheet getCheckpointSheet() {
        return provider.getSheet(ObjectArtKeys.CHECKPOINT);
    }

    public ObjectSpriteSheet getCheckpointStarSheet() {
        return provider.getSheet(ObjectArtKeys.CHECKPOINT_STAR);
    }

    public SpriteAnimationSet getCheckpointAnimations() {
        return provider.getAnimations(ObjectArtKeys.ANIM_CHECKPOINT);
    }

    public PatternSpriteRenderer getAnimalRenderer() {
        return provider.getRenderer(ObjectArtKeys.ANIMAL);
    }

    public PatternSpriteRenderer getPointsRenderer() {
        return provider.getRenderer(ObjectArtKeys.POINTS);
    }

    public int getAnimalTypeA() {
        return provider.getZoneData(ObjectArtKeys.ANIMAL_TYPE_A, -1);
    }

    public int getAnimalTypeB() {
        return provider.getZoneData(ObjectArtKeys.ANIMAL_TYPE_B, -1);
    }

    public PatternSpriteRenderer getSignpostRenderer() {
        return provider.getRenderer(ObjectArtKeys.SIGNPOST);
    }

    public ObjectSpriteSheet getSignpostSheet() {
        return provider.getSheet(ObjectArtKeys.SIGNPOST);
    }

    public SpriteAnimationSet getSignpostAnimations() {
        return provider.getAnimations(ObjectArtKeys.ANIM_SIGNPOST);
    }

    public PatternSpriteRenderer getEggPrisonRenderer() {
        return provider.getRenderer(ObjectArtKeys.EGG_PRISON);
    }

    public ObjectSpriteSheet getEggPrisonSheet() {
        return provider.getSheet(ObjectArtKeys.EGG_PRISON);
    }

    public PatternSpriteRenderer getResultsRenderer() {
        return provider.getRenderer(ObjectArtKeys.RESULTS);
    }

    public ObjectSpriteSheet getResultsSheet() {
        return provider.getSheet(ObjectArtKeys.RESULTS);
    }

    public Pattern[] getResultsHudDigitPatterns() {
        return provider.getHudDigitPatterns();
    }
}
