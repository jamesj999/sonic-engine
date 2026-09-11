package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K Buggernaut baby — a smaller dragonfly child that follows the parent
 * {@link BuggernautBadnikInstance}.
 *
 * <p>ROM reference: {@code Obj_Buggernaught_Baby} in {@code sonic3k.asm}
 * (lines 183804–183858).
 *
 * <h3>State machine:</h3>
 * <pre>
 * [Init] → [Follow parent]
 *               ├── parent alive: chase parent, mirror flip, water bounce
 *               └── parent dead: scan for new parent
 *                     ├── found: re-attach
 *                     └── not found: → [Free-fly away from player]
 * </pre>
 *
 * <h3>Follow physics:</h3>
 * Max speed $200, acceleration $20 (faster than parent's $10).
 * Bounces off water surface at {@code Water_level - 8}.
 *
 * <h3>Collision:</h3>
 * {@code collision_flags = $00}: no collision — baby is not hittable.
 *
 * <h3>Animation:</h3>
 * Frames 3→4→5 loop (speed 0). Uses parent's art/mappings.
 *
 * <h3>Deletion:</h3>
 * Uses {@code Sprite_CheckDeleteTouchXY} (both X and Y bounds check).
 */
public final class BuggernautBabyInstance extends AbstractObjectInstance implements RewindRecreatable {

    // --- Chase physics ---

    // loc_87B10: move.w #$200,d0 — max speed (same as parent)
    private static final int CHASE_MAX_SPEED = 0x200;

    // loc_87B10: moveq #$20,d1 — acceleration (faster than parent's $10)
    private static final int CHASE_ACCEL = 0x20;

    // --- Water surface bounce (sub_87B88) ---

    // sub_87B88: subi.w #8,d1
    private static final int WATER_BOUNCE_MARGIN = 8;

    // sub_87B88: move.w #-$200,y_vel(a0)
    private static final int WATER_BOUNCE_Y_VEL = -0x200;

    // --- Free-fly velocity ---

    // loc_87B24: move.w #$200,d1 — flee speed
    private static final int FLEE_SPEED = 0x200;

    // --- Off-screen deletion margin (Sprite_CheckDeleteTouchXY) ---
    // Uses generous bounds: ~$280 horizontal, ~$200 vertical
    private static final int OFF_SCREEN_MARGIN = 160;

    // --- Animation (AniRaw_Buggernaut_Baby) ---
    // dc.b 0, 3, 4, 5, $FC — speed 0, frames [3, 4, 5], loop
    private static final int[] ANIM_FRAMES = {3, 4, 5};

    // --- Priority ---
    // ObjDat3_Buggernaught_Baby: dc.w $280
    private static final int PRIORITY_BUCKET = 5;

    // --- State ---

    private enum State { INIT, FOLLOW, FREE_FLY }

    private State state = State.INIT;
    private int currentX;
    private int currentY;
    private int xVelocity;
    private int yVelocity;
    private int xSubpixel;
    private int ySubpixel;
    private int mappingFrame;
    private boolean facingLeft;
    private int animIndex;

    /** Reference to the current parent (ROM: parent3 at $42). */
    @RewindTransient(reason = "Structural parent link; relinked to the nearest live "
            + "Buggernaut on rewind recreate. State/velocity/facing are reapplied by "
            + "the generic field capturer.")
    private BuggernautBadnikInstance parent;

    BuggernautBabyInstance(ObjectSpawn ownerSpawn, int x, int y,
                           BuggernautBadnikInstance parent) {
        super(ownerSpawn, "BuggernautBaby");
        this.currentX = x;
        this.currentY = y;
        this.parent = parent;
        // ObjDat3_Buggernaught_Baby: mapping_frame = 3
        this.mappingFrame = 3;
        this.animIndex = 0;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        ObjectManager objectManager = ctx.objectServices().objectManager();
        BuggernautBadnikInstance liveParent =
                findNearestLiveParentForRewind(objectManager, ctx.spawn());
        if (liveParent == null) {
            return null;
        }
        ObjectSpawn capturedSpawn = ctx.spawn();
        return BuggernautBadnikInstance.recreateBabyForRewind(
                capturedSpawn, capturedSpawn.x(), capturedSpawn.y(), liveParent);
    }

    private static BuggernautBadnikInstance findNearestLiveParentForRewind(
            ObjectManager objectManager,
            ObjectSpawn spawn) {
        if (objectManager == null || spawn == null) {
            return null;
        }
        BuggernautBadnikInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (!(instance instanceof BuggernautBadnikInstance parent) || parent.isDestroyed()) {
                continue;
            }
            long dx = parent.getX() - spawn.x();
            long dy = parent.getY() - spawn.y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = parent;
            }
        }
        return best;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        switch (state) {
            case INIT -> updateInit();
            case FOLLOW -> updateFollow(playerEntity);
            case FREE_FLY -> updateFreeFly();
        }

        // Sprite_CheckDeleteTouchXY: generous off-screen deletion
        if (!isOnScreen(OFF_SCREEN_MARGIN)) {
            setDestroyed(true);
        }
    }

    // ── Routine 0: Init ──────────────────────────────────────────────────

    /**
     * ROM parity: the baby's first execution only performs init/setup and
     * leaves movement/animation for the next frame.
     */
    private void updateInit() {
        state = State.FOLLOW;
    }

    // ── Routine 2: Follow Parent ─────────────────────────────────────────

    /**
     * Follow the parent Buggernaut. If parent is dead, try to find a new one.
     * <pre>
     * loc_87AE6:
     *     jsr    Animate_Raw(pc)
     *     movea.w parent3(a0),a1
     *     cmpi.l #Obj_Buggernaut_2,(a1)   ; parent still alive?
     *     beq.s  loc_87AFC                 ; yes → follow normally
     *     bsr.w  sub_87B56                 ; no → try to find new parent
     *     beq.s  loc_87B24                 ; none found → free-fly
     * </pre>
     */
    private void updateFollow(PlayableEntity playerEntity) {
        animateWings();

        // Check if parent is still alive
        if (parent == null || parent.isDestroyed()) {
            // Try to adopt a new parent
            ObjectServices svc = tryServices();
            ObjectManager objectManager = svc != null ? svc.objectManager() : null;
            BuggernautBadnikInstance newParent =
                    BuggernautBadnikInstance.findAdoptiveParent(objectManager);

            if (newParent != null) {
                // sub_87B56: adoption successful
                newParent.childCount++;
                parent = newParent;
            } else {
                // loc_87B24: no parent found → enter free-fly
                enterFreeFly(playerEntity);
                return;
            }
        }

        // loc_87AFC: mirror parent's horizontal flip
        // bclr #0,render_flags(a0)
        // btst #0,render_flags(a1)
        // beq.s +
        // bset #0,render_flags(a0)
        facingLeft = parent.badnikFacingLeft();

        // Chase_Object toward parent (not player)
        chaseParent();

        // sub_87B88: water surface bounce
        applyWaterSurfaceBounce();

        // MoveSprite2: apply velocity
        moveWithVelocity();
    }

    // ── Routine 4: Free-fly ──────────────────────────────────────────────

    /**
     * Fly away in a straight line. No chasing, no water bounce.
     * <pre>
     * loc_87B4C:
     *     jsr    Animate_Raw(pc)
     *     jmp    (MoveSprite2).l
     * </pre>
     */
    private void updateFreeFly() {
        animateWings();
        moveWithVelocity();
    }

    // ── Free-fly entry ───────────────────────────────────────────────────

    /**
     * Enter free-fly mode: fly away from Player_1.
     * <pre>
     * loc_87B24:
     *     move.b #4,routine(a0)
     *     move.w x_pos(a0),d0
     *     move.w #$200,d1
     *     bset   #0,render_flags(a0)       ; default: face right
     *     cmp.w  (Player_1+x_pos).w,d0
     *     bhs.s  loc_87B46                 ; if baby >= player, fly LEFT
     *     neg.w  d1
     *     bclr   #0,render_flags(a0)       ; face left
     * loc_87B46:
     *     move.w d1,x_vel(a0)
     * </pre>
     */
    private void enterFreeFly(PlayableEntity playerEntity) {
        state = State.FREE_FLY;
        parent = null;

        // Determine direction: fly AWAY from Player_1
        int playerX = Integer.MAX_VALUE;
        if (playerEntity instanceof AbstractPlayableSprite player && !player.getDead()) {
            playerX = player.getCentreX();
        }

        if (currentX >= playerX) {
            // Baby is to the right of or at player → fly right (positive X)
            xVelocity = FLEE_SPEED;
            facingLeft = false; // bset #0 → face right in ROM render_flags bit 0
        } else {
            // Baby is to the left of player → fly left (negative X)
            xVelocity = -FLEE_SPEED;
            facingLeft = true; // bclr #0 → face left
        }
        // Y velocity stays at whatever it was
    }

    // ── Chase parent (Chase_Object) ──────────────────────────────────────

    /**
     * ROM {@code Chase_Object} toward parent (not player). Uses higher
     * acceleration ($20 vs parent's $10) so baby catches up faster.
     *
     * <p>ROM-accurate: refuse-to-store when velocity exceeds range, axis
     * velocity untouched when position matches, both zeroed only when both
     * axes match. See {@code sonic3k.asm} lines 179340–179386.
     */
    private void chaseParent() {
        int targetX = parent.getX();
        int targetY = parent.getY();
        boolean xEqual = (currentX == targetX);

        // X axis: skip if positions match (preserve old x velocity)
        if (!xEqual) {
            int accelX = (currentX > targetX) ? -CHASE_ACCEL : CHASE_ACCEL;
            int newXVel = xVelocity + accelX;
            if (newXVel >= -CHASE_MAX_SPEED && newXVel <= CHASE_MAX_SPEED) {
                xVelocity = newXVel;
            }
        }

        // Y axis
        boolean yEqual = (currentY == targetY);
        if (yEqual) {
            if (xEqual) {
                xVelocity = 0;
                yVelocity = 0;
            }
            return;
        }

        int accelY = (currentY > targetY) ? -CHASE_ACCEL : CHASE_ACCEL;
        int newYVel = yVelocity + accelY;
        if (newYVel >= -CHASE_MAX_SPEED && newYVel <= CHASE_MAX_SPEED) {
            yVelocity = newYVel;
        }
    }

    // ── Water surface bounce (sub_87B88) ─────────────────────────────────

    private void applyWaterSurfaceBounce() {
        int waterLevel = resolveWaterLevel();
        if (waterLevel == 0) return;
        int threshold = waterLevel - WATER_BOUNCE_MARGIN;
        if (currentY >= threshold) {
            yVelocity = WATER_BOUNCE_Y_VEL;
        }
    }

    // ── Animation ────────────────────────────────────────────────────────

    /**
     * AniRaw_Buggernaut_Baby: speed 0, frames [3, 4, 5], $FC loop.
     */
    private void animateWings() {
        mappingFrame = ANIM_FRAMES[animIndex];
        animIndex++;
        if (animIndex >= ANIM_FRAMES.length) {
            animIndex = 0;
        }
    }

    // ── Movement ─────────────────────────────────────────────────────────

    /** MoveSprite2: 16:8 fixed-point position update, no gravity. */
    private void moveWithVelocity() {
        int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
        int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
        xPos24 += xVelocity;
        yPos24 += yVelocity;
        currentX = xPos24 >> 8;
        currentY = yPos24 >> 8;
        xSubpixel = xPos24 & 0xFF;
        ySubpixel = yPos24 & 0xFF;
    }

    // ── Utility ──────────────────────────────────────────────────────────

    private int resolveWaterLevel() {
        ObjectServices svc = tryServices();
        if (svc == null) return 0;
        WaterSystem waterSystem = svc.waterSystem();
        if (waterSystem == null) return 0;
        return waterSystem.getWaterLevelY(svc.featureZoneId(), svc.featureActId());
    }

    // ── Rendering ────────────────────────────────────────────────────────

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;
        // Baby uses same art as parent (ROM: CreateChild1_Normal copies mappings/art_tile)
        PatternSpriteRenderer renderer = renderManager.getRenderer(
                Sonic3kObjectArtKeys.HCZ_BUGGERNAUT);
        if (renderer == null || !renderer.isReady()) return;
        // S3K render_flags bit 0: 0=face left, 1=face right
        // PatternSpriteRenderer hFlip: true=flip. facingLeft=true → hFlip=true
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, !facingLeft, false);
    }

    @Override
    public ObjectSpawn getSpawn() {
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
        return PRIORITY_BUCKET;
    }

    @Override
    public void onUnload() {
        parent = null;
    }
}
