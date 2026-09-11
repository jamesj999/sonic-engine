package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;
import java.util.logging.Logger;

/**
 * AIZ end boss bomb projectile (ROM: loc_698D2).
 *
 * <p>Spawned by the flame child. Falls with gravity, bouncing along the floor.
 * Spawns smoke trails every 4 frames. Lifetime = $9F (159) frames.
 *
 * <p>ROM attributes: word_69CF2 — priority $100, size $14x$18, frame $F,
 * collision $9A. Shield reaction: fire shield immune (bit 4).
 *
 * <p>Velocity per angle (ROM: word_69BD2):
 * - Angle 0: xVel=$300, yVel=$300
 * - Angle 4: xVel=0, yVel=$400
 * - Angle 8: xVel=0, yVel=$400
 * - Angle $C: xVel=-$300, yVel=$300
 */
public class AizEndBossBombChild extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AizEndBossBombChild.class.getName());
    private static final int SHIELD_REACTION_FIRE = 1 << 4;
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            false,
            true,
            false,
            TouchShieldDeflectCapability.NONE,
            SHIELD_REACTION_FIRE,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    private static final int COLLISION_FLAGS = 0x9A; // Hurts player, size index $1A
    private static final int LIFETIME = 0x9F;        // 159 frames
    private static final int Y_RADIUS = 0x0C;        // ROM: move.b #$C,y_radius(a0)

    // Velocity per angle index (ROM: word_69BD2)
    private static final int[][] BOMB_VELOCITY = {
            {0x300, 0x300},   // angle 0
            {0, 0x400},       // angle 4
            {0, 0x400},       // angle 8
            {-0x300, 0x300},  // angle $C
    };
    private static final int[][] BOMB_OFFSETS = {
            {0x14, 0x14},
            {0x00, 0x18},
            {0x00, 0x18},
            {-0x14, 0x14}
    };
    private final AizEndBossInstance boss;
    private int currentX;
    private int currentY;
    private int xSub;
    private int ySub;
    private int xVel;
    private int yVel;
    private int lifetime;
    private int mappingFrame;
    private int frameCounter;
    private boolean hitFloor;
    private boolean faceRight;

    public AizEndBossBombChild(AizEndBossInstance boss, int startX, int startY, int angle) {
        super(buildSpawnAt(startX, startY, boss), "AIZEndBossBomb");
        this.boss = boss;
        this.currentX = startX;
        this.currentY = startY;
        this.lifetime = LIFETIME;
        this.frameCounter = 0;
        this.hitFloor = false;

        // Apply position offset and velocity from angle (ROM: loc_69B7A).
        int angleIndex = angle / 4;
        this.currentX += BOMB_OFFSETS[angleIndex][0];
        this.currentY += BOMB_OFFSETS[angleIndex][1];
        this.xVel = BOMB_VELOCITY[angleIndex][0];
        this.yVel = BOMB_VELOCITY[angleIndex][1];
        this.faceRight = (xVel >= 0);

        // Select animation by angle
        boolean vertical = (angleIndex == 1 || angleIndex == 2);
        this.mappingFrame = vertical ? 0x16 : 0x26;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AizEndBossInstance restoredBoss = AizEndBossRewindLinks.nearestBoss(ctx);
        if (restoredBoss == null) {
            return null;
        }
        AizEndBossBombChild restored = new AizEndBossBombChild(restoredBoss, 0, 0, 0);
        AizEndBossRewindLinks.seedCapturedScalars(restored, ctx);
        return restored;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) return;

        // Play SFX on first frame (deferred from constructor since services aren't injected yet)
        this.frameCounter++;
        if (this.frameCounter == 1) {
            services().playSfx(Sonic3kSfx.PROJECTILE.id);
            // ROM AIZEndBossBomb_Init (docs/skdisasm/sonic3k.asm:138624-138634)
            // plays this same sfx_Projectile, installs AIZEndBossBomb_Main as the
            // object's code pointer and then leaves via
            // `bra.w AIZEndBossBomb_SetAngleData`, which applies the angle's
            // position offset and velocities and ends in `rts` (:138876-138893).
            // It does NOT fall through into AIZEndBossBomb_Main, so the bomb's
            // MoveSprite2 (:138643) first runs on the pass AFTER the one that
            // created it. The recording shows exactly that: the object already
            // reads AIZEndBossBomb_Main on its creation frame -- init ran -- while
            // its y is unchanged until the following frame.
            //
            // Moving here made the bomb one step further down at every frame-start
            // touch snapshot, which landed its HURT on the sidekick a row early;
            // the knockback sign then inverted as a consequence, because the ROM's
            // `cmp.w x_pos(a2),d0 / blo` (:21102-21106) negates on the equal x a
            // row-early hit produces.
            return;
        }
        lifetime--;
        if (lifetime <= 0) {
            setDestroyed(true);
            return;
        }

        if (!hitFloor && xVel != 0) {
            TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
            if (floor.hasCollision()) {
                hitFloor = true;
                currentY += floor.distance();
                yVel = 0;
            }
        }

        // Apply movement (ROM: MoveSprite2)
        int xPos24 = (currentX << 8) | (xSub & 0xFF);
        xPos24 += xVel;
        currentX = xPos24 >> 8;
        xSub = xPos24 & 0xFF;

        int yPos24 = (currentY << 8) | (ySub & 0xFF);
        yPos24 += yVel;
        currentY = yPos24 >> 8;
        ySub = yPos24 & 0xFF;
        updateDynamicSpawn(currentX, currentY);

        // Apply gravity if not on floor
        if (!hitFloor) {
            yVel += 0x38; // ROM: standard gravity
        }

        // Animate bomb (cycling frames)
        if (hitFloor) {
            // Rolling: frames $10, $11, $2D, $2E
            int phase = (this.frameCounter / 2) % 4;
            int[] rollingFrames = {0x10, 0x11, 0x2D, 0x2E};
            mappingFrame = rollingFrames[phase];
        } else {
            // Falling: cycle through 4 frames
            int phase = (this.frameCounter / 2) % 4;
            boolean vertical = (xVel == 0);
            if (vertical) {
                int[] frames = {0x16, 0x17, 0x2F, 0x30};
                mappingFrame = frames[phase];
            } else {
                int[] frames = {0x26, 0x27, 0x28, 0x29};
                mappingFrame = frames[phase];
            }
        }

        // Spawn smoke trail every 4 frames (ROM: V_int_run_count & 3 == 0)
        if ((this.frameCounter & 3) == 0) {
            spawnSmoke();
        }
    }

    private void spawnSmoke() {
        if (services().objectManager() == null) return;

        spawnFreeChild(() -> new AizEndBossSmokeChild(
                boss, currentX, currentY, xVel != 0));
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
    public int getShieldReactionFlags() {
        return SHIELD_REACTION_FIRE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public int getX() { return currentX; }

    @Override
    public int getY() { return currentY; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) return;

        renderer.drawFrameIndex(mappingFrame, currentX, currentY, faceRight, false);
    }

    @Override
    public boolean isHighPriority() { return true; }

    @Override
    public int getPriorityBucket() { return 2; }

    private static ObjectSpawn buildSpawnAt(int x, int y, AizEndBossInstance boss) {
        int objectId = boss != null && boss.getSpawn() != null ? boss.getSpawn().objectId() : 0x92;
        return new ObjectSpawn(x, y, objectId, 0, 0, false, 0);
    }
}
