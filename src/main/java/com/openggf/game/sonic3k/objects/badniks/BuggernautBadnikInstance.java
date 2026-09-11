package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;
import java.util.List;

/**
 * S3K Obj $95 — Buggernaut (HCZ Acts 1 &amp; 2).
 *
 * <p>A dragonfly-like badnik that hovers above water. Alternates between a
 * stationary hover phase and an active chase phase where it pursues the
 * nearest player. On init it spawns a single baby child
 * ({@link BuggernautBabyInstance}) that follows it. If the parent is
 * destroyed, orphaned babies attempt to adopt a new parent (max 4 per
 * parent), or flee in a straight line away from the player.
 *
 * <p>ROM reference: {@code Obj_Buggernaut} / {@code Obj_Buggernaut_2}
 * in {@code sonic3k.asm} (lines 183677–183858).
 *
 * <h3>State machine:</h3>
 * <pre>
 * [Init] → [Hover, 63f] → [Chase, 127f] → [Hover, 63f] → ...
 * </pre>
 *
 * <h3>Chase behaviour:</h3>
 * <ul>
 *   <li>Find nearest player via {@code Find_SonicTails}</li>
 *   <li>Accelerate toward player: max speed $200, accel $10</li>
 *   <li>Bounce off water surface at {@code Water_level - 8}</li>
 * </ul>
 *
 * <h3>Collision:</h3>
 * {@code collision_flags = $17}: type 0 (enemy), size index 23.
 * No shield reaction. No character-specific behaviour. No subtype usage.
 *
 * <h3>Animation:</h3>
 * Frames 0→1→2 loop (speed 0 = every frame). Wing flapping cycle.
 */
public final class BuggernautBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {

    // --- ObjDat_Buggernaut constants ---

    // collision_flags = $17: type 0, size index $17 (23)
    private static final int COLLISION_SIZE_INDEX = 0x17;

    // dc.w $280 → priority bucket 5
    private static final int PRIORITY_BUCKET = 5;

    // --- Timers (Obj_Wait) ---

    // loc_87A5E: move.w #$3F,$2E(a0) — hover duration
    private static final int HOVER_TIMER = 0x3F;

    // loc_87A7C: move.w #$7F,$2E(a0) — chase duration
    private static final int CHASE_TIMER = 0x7F;

    // --- Chase physics ---

    // loc_87A92: move.w #$200,d0 — max speed
    private static final int CHASE_MAX_SPEED = 0x200;

    // loc_87A92: moveq #$10,d1 — acceleration (X and Y)
    private static final int CHASE_ACCEL = 0x10;

    // --- Water surface bounce (sub_87B88) ---

    // sub_87B88: subi.w #8,d1 — bounce threshold offset from water level
    private static final int WATER_BOUNCE_MARGIN = 8;

    // sub_87B88: move.w #-$200,y_vel(a0) — upward bounce velocity
    private static final int WATER_BOUNCE_Y_VEL = -0x200;

    // --- Baby spawn offset (ChildObjDat_Buggernaught_Baby) ---

    // dc.b $20,0 — baby spawned at parent + (32, 0) pixels
    private static final int BABY_SPAWN_X_OFFSET = 0x20;

    // --- Animation (AniRaw_Buggernaut) ---
    // dc.b 0, 0, 1, 2, $FC — speed 0, frames [0, 1, 2], loop
    private static final int[] ANIM_FRAMES = {0, 1, 2};

    // --- Child tracking ---

    // sub_87B72: cmpi.b #4,d1 — maximum children per parent
    static final int MAX_CHILDREN = 4;

    private static final ObjectPlayerParticipationPolicy TARGET_PARTICIPATION =
            ObjectPlayerParticipationPolicy.NATIVE_P1_P2;

    // --- State ---

    private enum State { HOVER, CHASE }

    private State state;
    private int waitTimer;
    private int animIndex;

    /** Number of baby children attached to this parent ($39 in ROM). */
    int childCount;

    public BuggernautBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Buggernaut",
                Sonic3kObjectArtKeys.HCZ_BUGGERNAUT, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        this.mappingFrame = 0;

        // Routine 0 → spawn baby and set up hover state
        this.childCount = 0;
        this.state = State.HOVER;
        this.waitTimer = HOVER_TIMER;
        this.animIndex = 0;
    }

    /**
     * Late init: spawn the baby child once services are available.
     * Called from the first update tick. ROM does this in routine 0 via
     * {@code CreateChild1_Normal}. ROM init routine returns after spawning;
     * hover logic starts on the NEXT frame (routine 2).
     */
    private boolean babySpawned;

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) return;

        // Obj_WaitOffscreen parity: suppress logic until on-screen
        if (!isOnScreenX()) return;

        // Spawn baby on first visible frame (ROM: routine 0 init)
        // ROM: routine 0 returns after spawning; hover runs next frame.
        if (!babySpawned) {
            spawnBaby();
            babySpawned = true;
            return;
        }

        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;

        switch (state) {
            case HOVER -> updateHover();
            case CHASE -> updateChase(player);
        }
    }

    // ── Routine 2: Hover ─────────────────────────────────────────────────

    /**
     * Hover in place, animating wings, until timer expires.
     * <pre>
     * loc_87A74:
     *     jsr    Animate_Raw(pc)
     *     jmp    Obj_Wait(pc)
     * </pre>
     */
    private void updateHover() {
        animateWings();

        // Obj_Wait: decrement timer, transition when expired
        waitTimer--;
        if (waitTimer < 0) {
            // loc_87A7C: transition to Chase
            state = State.CHASE;
            waitTimer = CHASE_TIMER;
        }
    }

    // ── Routine 4: Chase ─────────────────────────────────────────────────

    /**
     * Chase the nearest player with acceleration-capped velocity.
     * <pre>
     * loc_87A92:
     *     tst.b  render_flags(a0)
     *     bpl.s  locret_87A90       ; skip if off-screen
     *     jsr    Find_SonicTails(pc)
     *     jsr    Change_FlipX(pc)
     *     move.w #$200,d0           ; max speed
     *     moveq  #$10,d1            ; acceleration
     *     jsr    Chase_Object(pc)
     *     bsr.w  sub_87B88          ; water surface bounce
     *     jsr    (MoveSprite2).l
     *     jsr    Animate_Raw(pc)
     *     jmp    Obj_Wait(pc)
     * </pre>
     */
    private void updateChase(AbstractPlayableSprite player) {
        // tst.b render_flags(a0) / bpl.s locret_87A90
        // ROM: when off-screen during chase, immediate rts — no animation,
        // no timer decrement, no movement. Everything freezes.
        if (!isOnScreenX()) return;

        // Find_SonicTails + Change_FlipX
        AbstractPlayableSprite target = findNearestPlayer(player);
        if (target != null) {
            facingLeft = target.getCentreX() < currentX;
        }

        // Chase_Object: accelerate toward target in both axes
        chaseTarget(target);

        // sub_87B88: water surface bounce
        applyWaterSurfaceBounce();

        // MoveSprite2: apply velocity (no gravity)
        moveWithVelocity();

        animateWings();

        // Obj_Wait timer
        waitTimer--;
        if (waitTimer < 0) {
            // loc_87A5E: transition back to Hover
            state = State.HOVER;
            waitTimer = HOVER_TIMER;
        }
    }

    // ── Chase_Object ─────────────────────────────────────────────────────

    /**
     * ROM {@code Chase_Object} (sonic3k.asm lines 179340–179386).
     *
     * <p>ROM-accurate semantics:
     * <ul>
     *   <li>When one axis position matches the target, that axis's velocity
     *       is NOT modified (preserved as-is). No zeroing per-axis.</li>
     *   <li>Only when BOTH X and Y positions match are both velocities zeroed
     *       (d5 flag / loc_85480).</li>
     *   <li>Velocity updates use refuse-to-store: if {@code old_vel + accel}
     *       falls outside [-max, +max], the old velocity is preserved
     *       unchanged (no clamping to max).</li>
     * </ul>
     */
    private void chaseTarget(AbstractPlayableSprite target) {
        if (target == null) return;

        int targetX = target.getCentreX();
        int targetY = target.getCentreY();
        boolean xEqual = (currentX == targetX);

        // X axis: skip entirely if positions match (seq d5 / beq.s loc_8545E)
        if (!xEqual) {
            int accelX = (currentX > targetX) ? -CHASE_ACCEL : CHASE_ACCEL;
            int newXVel = xVelocity + accelX;
            // Refuse-to-store if out of range (blt.s/bgt.s skip move.w)
            if (newXVel >= -CHASE_MAX_SPEED && newXVel <= CHASE_MAX_SPEED) {
                xVelocity = newXVel;
            }
        }

        // Y axis: skip acceleration if positions match
        boolean yEqual = (currentY == targetY);
        if (yEqual) {
            // loc_85480: both match → zero both velocities
            if (xEqual) {
                xVelocity = 0;
                yVelocity = 0;
            }
            // If only Y matches but X doesn't, return without modifying y_vel
            return;
        }

        int accelY = (currentY > targetY) ? -CHASE_ACCEL : CHASE_ACCEL;
        int newYVel = yVelocity + accelY;
        if (newYVel >= -CHASE_MAX_SPEED && newYVel <= CHASE_MAX_SPEED) {
            yVelocity = newYVel;
        }
    }

    // ── Water surface bounce (sub_87B88) ─────────────────────────────────

    /**
     * Bounce off the water surface. Keeps the Buggernaut above water.
     * <pre>
     * sub_87B88:
     *     move.w y_pos(a0),d0
     *     move.w (Water_level).w,d1
     *     subi.w #8,d1
     *     cmp.w  d1,d0
     *     blo.s  locret_87B9E     ; if above threshold, skip
     *     move.w #-$200,y_vel(a0) ; bounce upward
     * </pre>
     */
    private void applyWaterSurfaceBounce() {
        int waterLevel = resolveWaterLevel();
        if (waterLevel == 0) return; // No water configured
        int threshold = waterLevel - WATER_BOUNCE_MARGIN;
        if (currentY >= threshold) {
            yVelocity = WATER_BOUNCE_Y_VEL;
        }
    }

    // ── Animation ────────────────────────────────────────────────────────

    /**
     * AniRaw_Buggernaut: speed 0, frames [0, 1, 2], $FC loop.
     * Speed 0 = advance every frame.
     */
    private void animateWings() {
        mappingFrame = ANIM_FRAMES[animIndex];
        animIndex++;
        if (animIndex >= ANIM_FRAMES.length) {
            animIndex = 0;
        }
    }

    // ── Baby spawning ────────────────────────────────────────────────────

    /**
     * ROM: {@code CreateChild1_Normal} — spawn one baby at parent + ($20, 0).
     * Increments child count ($39).
     */
    private void spawnBaby() {
        int babyX = currentX + BABY_SPAWN_X_OFFSET;
        int babyY = currentY;
        BuggernautBabyInstance baby = spawnChild(
                () -> new BuggernautBabyInstance(spawn, babyX, babyY, this));
        if (!baby.isDestroyed() && baby.getSlotIndex() >= 0) {
            childCount++;
        }
    }

    /**
     * Rewind recreate factory for {@link BuggernautBabyInstance}. The baby's
     * constructor and the baby class are package-private, so the registry (in the
     * parent package) reaches them through this public static bridge — mirroring
     * {@code S3kBadnikProjectileInstance.forRewindRecreate}. The {@code parent} is
     * relinked by object-reference restore; all other baby state is reapplied by the generic
     * field capturer after recreate.
     */
    public static BuggernautBabyInstance recreateBabyForRewind(
            ObjectSpawn ownerSpawn, int x, int y, BuggernautBadnikInstance parent) {
        return new BuggernautBabyInstance(ownerSpawn, x, y, parent);
    }

    // ── Player proximity (Find_SonicTails) ───────────────────────────────

    /**
     * Find nearest native player (Player_1 or Player_2) by horizontal distance.
     */
    private AbstractPlayableSprite findNearestPlayer(AbstractPlayableSprite mainPlayer) {
        ObjectServices svc = tryServices();
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> mainPlayer,
                () -> svc != null ? svc.playerQuery().sidekicks() : List.of());
        PlayableEntity nearest = query.nearestByRomX(
                TARGET_PARTICIPATION,
                currentX,
                BuggernautBadnikInstance::isLivePlayable).player();
        return nearest instanceof AbstractPlayableSprite sprite ? sprite : null;
    }

    private static boolean isLivePlayable(PlayableEntity player) {
        return player instanceof AbstractPlayableSprite sprite && !sprite.getDead();
    }

    // ── Water level resolution ───────────────────────────────────────────

    private int resolveWaterLevel() {
        ObjectServices svc = tryServices();
        if (svc == null) return 0;
        WaterSystem waterSystem = svc.waterSystem();
        if (waterSystem == null) return 0;
        return waterSystem.getWaterLevelY(svc.featureZoneId(), svc.featureActId());
    }

    // ── Parent adoption for orphaned babies ──────────────────────────────

    /**
     * ROM: {@code sub_87B56} — scan dynamic objects for a living Buggernaut
     * parent that can accept another child (child count &lt; 4).
     *
     * @return an adoptable parent, or null if none found
     */
    static BuggernautBadnikInstance findAdoptiveParent(ObjectManager objectManager) {
        if (objectManager == null) return null;
        Collection<ObjectInstance> objects = objectManager.getActiveObjects();
        for (ObjectInstance obj : objects) {
            if (obj instanceof BuggernautBadnikInstance parent
                    && !parent.isDestroyed()
                    && parent.childCount < MAX_CHILDREN) {
                return parent;
            }
        }
        return null;
    }
}
