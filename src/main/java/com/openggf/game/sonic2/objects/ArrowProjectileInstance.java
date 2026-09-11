package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnAndCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Arrow projectile fired by ArrowShooter (Object 22) in Aquatic Ruin Zone.
 * <p>
 * Behavior from disassembly (s2.asm lines 51590-51626):
 * <ul>
 *   <li>Travels horizontally at $400 velocity (4 pixels/frame)</li>
 *   <li>Direction based on shooter's x_flip render flag</li>
 *   <li>collision_flags = 0x9B (hurts player)</li>
 *   <li>Deletes on wall collision (ObjCheckLeftWallDist/ObjCheckRightWallDist)</li>
 *   <li>Plays SndID_ArrowFiring (0xAE) when initialized</li>
 * </ul>
 */
public class ArrowProjectileInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnAndCoordinateZeroScalarArgsRewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(ArrowProjectileInstance.class.getName());

    private static final int ARROW_VELOCITY = 0x400; // Fixed-point 8.8 = 4 pixels/frame
    private static final int COLLISION_FLAGS = 0x9B; // HURT (0x80) + size index 0x1B
    private static final int COLLISION_SIZE_INDEX = 0x1B; // 27
    private static final int X_RADIUS = 0x10; // 16 pixels
    private static final int Y_RADIUS = 0x08; // 8 pixels
    private static final int PRIORITY = 4;
    private static final int MAPPING_FRAME = 0; // Arrow frame

    private int currentX;
    private int currentY;
    private int xVelocity; // Fixed-point 8.8
    private int xSubpixel; // Fractional part
    private boolean facingLeft;
    private boolean initialized;

    private ArrowProjectileInstance() {
        this(new ObjectSpawn(0, 0, Sonic2ObjectIds.ARROW_SHOOTER, 0, 0, false, 0), 0, 0, false);
    }

    public ArrowProjectileInstance(ObjectSpawn parentSpawn, int startX, int startY, boolean facingLeft) {
        super(createArrowSpawn(parentSpawn, startX, startY), "Arrow");
        this.currentX = startX;
        this.currentY = startY;
        this.facingLeft = facingLeft;
        this.xSubpixel = 0;
        this.initialized = false;

        // Set velocity based on direction
        if (facingLeft) {
            this.xVelocity = -ARROW_VELOCITY;
        } else {
            this.xVelocity = ARROW_VELOCITY;
        }
    }

    private static ObjectSpawn createArrowSpawn(ObjectSpawn parent, int x, int y) {
        return new ObjectSpawn(
                x, y,
                parent.objectId(),
                parent.subtype(),
                parent.renderFlags(),
                false, // Don't track respawn for projectiles
                parent.rawYWord());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!initialized) {
            // Play arrow firing sound on first update
            services().playSfx(Sonic2Sfx.ARROW_FIRING.id);
            initialized = true;
        }

        // Update position using fixed-point math
        xSubpixel += xVelocity;
        currentX += (xSubpixel >> 8);
        xSubpixel &= 0xFF;

        // Check for wall collision
        if (checkWallCollision()) {
            setDestroyed(true);
            return;
        }

        // Off-screen cleanup - ROM uses MarkObjGone with 480 pixel tolerance:
        //   cmpi.w  #$80+320+$40+$80,d0  ; 480 pixels
        //   bhi.w   DeleteObject
        if (!isOnScreen(480)) {
            setDestroyed(true);
        }
    }

    private boolean checkWallCollision() {
        // ROM Obj22_Arrow probes the opposite-side wall helper after ObjectMove:
        // right-moving arrows call ObjCheckLeftWallDist with d3=-8, and left-moving
        // arrows call ObjCheckRightWallDist with d3=8 (docs/s2disasm/s2.asm:51607-51623).
        if (facingLeft) {
            TerrainCheckResult result = ObjectTerrainUtils.checkRightWallDist(currentX + 8, currentY);
            return result.hasCollision() && result.distance() < 0;
        } else {
            TerrainCheckResult result = ObjectTerrainUtils.checkLeftWallDist(currentX - 8, currentY);
            return result.hasCollision() && result.distance() < 0;
        }
    }

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.standardEnemy();
    }

    @Override
    public ObjectSpawn getSpawn() {
        // Return dynamic spawn with current position for collision detection
        return buildSpawnAt(currentX, currentY);
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
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.ARROW_SHOOTER);
        if (renderer == null) return;

        // Arrow uses mapping frame 0
        renderer.drawFrameIndex(MAPPING_FRAME, currentX, currentY, facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int halfWidth = X_RADIUS;
        int halfHeight = Y_RADIUS;
        int left = currentX - halfWidth;
        int right = currentX + halfWidth;
        int top = currentY - halfHeight;
        int bottom = currentY + halfHeight;

        ctx.drawLine(left, top, right, top, 0.8f, 0.2f, 0.2f);
        ctx.drawLine(right, top, right, bottom, 0.8f, 0.2f, 0.2f);
        ctx.drawLine(right, bottom, left, bottom, 0.8f, 0.2f, 0.2f);
        ctx.drawLine(left, bottom, left, top, 0.8f, 0.2f, 0.2f);
    }

}
