package com.openggf.game.sonic3k.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.GameModule;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.sprites.art.SpriteArtSet;

import java.util.List;

/**
 * Lightning shield spark particle (sonic3k.asm:34811-34858).
 * Created by {@link LightningShieldObjectInstance#triggerSparks()} — 4 sparks fly
 * diagonally with gravity, playing animation script 0 (frames [0,1,2] delay=0).
 * Auto-deletes after {@link #MAX_LIFE} frames.
 *
 * <p>Renders tiles directly via {@link GraphicsManager#renderPatternWithId} rather
 * than through PlayerSpriteRenderer/DPLC, matching the ROM where spark art is
 * DMA-loaded once at init (not managed by PLCLoad_Shields).
 */
public class LightningSparkObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    /** Gravity per frame in subpixels (sonic3k.asm:34849: addi.w #$18,y_vel(a0)) */
    private static final int GRAVITY = 0x18;

    /** Lifetime in frames before auto-delete */
    private static final int MAX_LIFE = 21;

    /** Animation script index for sparks */
    private static final int SPARK_ANIM_SCRIPT = 0;

    /** Pattern atlas base ID for spark tiles, isolated from level object art. */
    private static final int SPARK_PATTERN_BASE = PatternAtlasRange.TRANSIENT_EFFECTS.base();

    /** Shared descriptor for spark tiles: palette 0, no flip, no priority. */
    private static final PatternDesc SPARK_DESC = new PatternDesc(0);

    private final SpriteAnimationSet animSet;
    private final Pattern[] sparkTiles;
    private boolean tilesCached;

    private int currentX;
    private int currentY;
    private int xSub;
    private int ySub;
    private int xVel;
    private int yVel;

    private int life;
    private int frameIndex;
    private int delayCounter;
    private int currentMappingFrame;

    private final SubpixelMotion.State motionState = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    LightningSparkObjectInstance() {
        this(0, 0, 0, 0, null, null);
    }

    public LightningSparkObjectInstance(int x, int y, int xVel, int yVel,
            SpriteAnimationSet animSet, Pattern[] sparkTiles) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "LightningSpark");
        this.currentX = x;
        this.currentY = y;
        this.xSub = 0;
        this.ySub = 0;
        this.xVel = xVel;
        this.yVel = yVel;
        this.animSet = animSet;
        this.sparkTiles = sparkTiles;
        this.life = 0;
        this.frameIndex = 0;
        this.currentMappingFrame = 0;
        initAnimation();
    }

    /**
     * Rewind recreate factory. The captured dynamic spawn carries the spark's
     * position; movement/animation scalars are reapplied by the generic field
     * capturer after recreate, so the constructor velocities are placeholders.
     * Art (animation set + spark tiles) is re-fetched from the live S3K object-art
     * provider exactly as {@link LightningShieldObjectInstance#triggerSparks()}
     * does; the object null-guards missing art, so a structurally-valid instance is
     * always returned.
     */
    public static LightningSparkObjectInstance forRewindRecreate(
            ObjectSpawn spawn, ObjectServices services) {
        SpriteAnimationSet animSet = null;
        Pattern[] sparkTiles = null;
        GameModule module = (services != null) ? services.gameModule() : null;
        if (module != null
                && module.getObjectArtProvider() instanceof Sonic3kObjectArtProvider s3k) {
            SpriteArtSet sparkArt = s3k.getShieldArtSet(Sonic3kObjectArtKeys.LIGHTNING_SPARK);
            if (sparkArt != null) {
                animSet = sparkArt.animationSet();
                sparkTiles = sparkArt.artTiles();
            }
        }
        return new LightningSparkObjectInstance(spawn.x(), spawn.y(), 0, 0, animSet, sparkTiles);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return forRewindRecreate(ctx.spawn(), ctx.objectServices());
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public boolean isPersistent() {
        return true; // Don't cull by spawn position — has no spawn
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        life++;
        if (life >= MAX_LIFE) {
            setDestroyed(true);
            return;
        }

        // Apply subpixel movement with gravity
        motionState.x = currentX; motionState.y = currentY;
        motionState.xSub = xSub;  motionState.ySub = ySub;
        motionState.xVel = xVel;  motionState.yVel = yVel;
        SubpixelMotion.moveSprite(motionState, GRAVITY);
        currentX = motionState.x; currentY = motionState.y;
        xSub = motionState.xSub;  ySub = motionState.ySub;
        yVel = motionState.yVel;

        stepAnimation();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || sparkTiles == null) return;
        // Frame 2 = empty (invisible flash frame)
        if (currentMappingFrame >= 2) return;

        GraphicsManager gfx = services().graphicsManager();
        ensureSparkTilesCached(gfx);

        if (currentMappingFrame == 0) {
            // 1×1 tile at (-4, -4) — ROM Map_LightningShield frame $C
            gfx.renderPatternWithId(SPARK_PATTERN_BASE, SPARK_DESC, currentX - 4, currentY - 4);
        } else {
            // 2×2 tiles at (-8, -8) — ROM Map_LightningShield frame $D
            // Column-major: tileOffset = col * heightTiles + row
            gfx.renderPatternWithId(SPARK_PATTERN_BASE + 1, SPARK_DESC, currentX - 8, currentY - 8);
            gfx.renderPatternWithId(SPARK_PATTERN_BASE + 2, SPARK_DESC, currentX - 8, currentY);
            gfx.renderPatternWithId(SPARK_PATTERN_BASE + 3, SPARK_DESC, currentX,     currentY - 8);
            gfx.renderPatternWithId(SPARK_PATTERN_BASE + 4, SPARK_DESC, currentX,     currentY);
        }
    }

    /** Cache spark tiles in the pattern atlas once (static art, never changes). */
    private void ensureSparkTilesCached(GraphicsManager gfx) {
        if (tilesCached) return;
        for (int i = 0; i < sparkTiles.length; i++) {
            gfx.cachePatternTexture(sparkTiles[i], SPARK_PATTERN_BASE + i);
        }
        tilesCached = true;
    }

    private void initAnimation() {
        frameIndex = 0;
        if (animSet != null) {
            SpriteAnimationScript script = animSet.getScript(SPARK_ANIM_SCRIPT);
            if (script != null) {
                delayCounter = script.delay();
                if (!script.frames().isEmpty()) {
                    currentMappingFrame = script.frames().get(0);
                }
            }
        }
    }

    private void stepAnimation() {
        if (animSet == null) return;
        SpriteAnimationScript script = animSet.getScript(SPARK_ANIM_SCRIPT);
        if (script == null || script.frames().isEmpty()) return;

        if (delayCounter > 0) {
            delayCounter--;
            return;
        }
        delayCounter = script.delay();

        frameIndex++;
        if (frameIndex >= script.frames().size()) {
            frameIndex = 0; // Always loop for sparks
        }
        currentMappingFrame = script.frames().get(frameIndex);
    }
}
