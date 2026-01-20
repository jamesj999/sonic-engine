package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.game.ObjectArtProvider;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
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

    public PatternSpriteRenderer getWaterfallRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.WATERFALL);
    }

    public ObjectSpriteSheet getWaterfallSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.WATERFALL);
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

    public PatternSpriteRenderer getMasherRenderer() {
        return provider.getRenderer(ObjectArtKeys.MASHER);
    }

    public PatternSpriteRenderer getBuzzerRenderer() {
        return provider.getRenderer(ObjectArtKeys.BUZZER);
    }

    public PatternSpriteRenderer getCoconutsRenderer() {
        return provider.getRenderer(ObjectArtKeys.COCONUTS);
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

    // --- Sonic 2-specific convenience methods (for backward compatibility) ---
    // For new code, prefer using getRenderer(Sonic2ObjectArtKeys.KEY) directly

    public PatternSpriteRenderer getBumperRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.BUMPER);
    }

    public ObjectSpriteSheet getBumperSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.BUMPER);
    }

    public PatternSpriteRenderer getHexBumperRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.HEX_BUMPER);
    }

    public ObjectSpriteSheet getHexBumperSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.HEX_BUMPER);
    }

    public PatternSpriteRenderer getBonusBlockRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.BONUS_BLOCK);
    }

    public ObjectSpriteSheet getBonusBlockSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.BONUS_BLOCK);
    }

    public PatternSpriteRenderer getFlipperRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.FLIPPER);
    }

    public ObjectSpriteSheet getFlipperSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.FLIPPER);
    }

    public SpriteAnimationSet getFlipperAnimations() {
        return provider.getAnimations(Sonic2ObjectArtKeys.ANIM_FLIPPER);
    }

    public PatternSpriteRenderer getSpeedBoosterRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.SPEED_BOOSTER);
    }

    public ObjectSpriteSheet getSpeedBoosterSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.SPEED_BOOSTER);
    }

    public PatternSpriteRenderer getBlueBallsRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.BLUE_BALLS);
    }

    public ObjectSpriteSheet getBlueBallsSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.BLUE_BALLS);
    }

    public PatternSpriteRenderer getBreakableBlockRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.BREAKABLE_BLOCK);
    }

    public ObjectSpriteSheet getBreakableBlockSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.BREAKABLE_BLOCK);
    }

    public PatternSpriteRenderer getCpzPlatformRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.CPZ_PLATFORM);
    }

    public ObjectSpriteSheet getCpzPlatformSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.CPZ_PLATFORM);
    }

    public PatternSpriteRenderer getCpzStairBlockRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.CPZ_STAIR_BLOCK);
    }

    public ObjectSpriteSheet getCpzStairBlockSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.CPZ_STAIR_BLOCK);
    }

    public PatternSpriteRenderer getSidewaysPformRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.SIDEWAYS_PFORM);
    }

    public ObjectSpriteSheet getSidewaysPformSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.SIDEWAYS_PFORM);
    }

    public PatternSpriteRenderer getCpzPylonRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.CPZ_PYLON);
    }

    public ObjectSpriteSheet getCpzPylonSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.CPZ_PYLON);
    }

    public PatternSpriteRenderer getPipeExitSpringRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.PIPE_EXIT_SPRING);
    }

    public ObjectSpriteSheet getPipeExitSpringSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.PIPE_EXIT_SPRING);
    }

    public SpriteAnimationSet getPipeExitSpringAnimations() {
        return provider.getAnimations(Sonic2ObjectArtKeys.ANIM_PIPE_EXIT_SPRING);
    }

    public PatternSpriteRenderer getTippingFloorRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.TIPPING_FLOOR);
    }

    public ObjectSpriteSheet getTippingFloorSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.TIPPING_FLOOR);
    }

    public SpriteAnimationSet getTippingFloorAnimations() {
        return provider.getAnimations(Sonic2ObjectArtKeys.ANIM_TIPPING_FLOOR);
    }

    public PatternSpriteRenderer getBarrierRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.BARRIER);
    }

    public ObjectSpriteSheet getBarrierSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.BARRIER);
    }

    public PatternSpriteRenderer getSpringboardRenderer() {
        return provider.getRenderer(Sonic2ObjectArtKeys.SPRINGBOARD);
    }

    public ObjectSpriteSheet getSpringboardSheet() {
        return provider.getSheet(Sonic2ObjectArtKeys.SPRINGBOARD);
    }

    public SpriteAnimationSet getSpringboardAnimations() {
        return provider.getAnimations(Sonic2ObjectArtKeys.ANIM_SPRINGBOARD);
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
