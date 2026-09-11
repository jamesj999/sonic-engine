package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SpawnAndCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.sprites.playable.Tails;

import java.util.List;

/**
 * S3K Obj $94 — Blastoid (HCZ Act 1).
 *
 * <p>A stationary turret badnik embedded in walls. Detects the nearest player
 * within 128 pixels horizontally ({@code Find_SonicTails}), then fires 3
 * projectiles in a burst before returning to idle. On defeat, sets a
 * {@link Sonic3kLevelTriggerManager} flag that can trigger
 * {@code CollapsingBridge} collapse.
 *
 * <p>Based on {@code Obj_Blastoid} (sonic3k.asm, lines 183566–183674).
 *
 * <h3>Subtype:</h3>
 * Bits 0-3: trigger array index — set to $FF on defeat
 * (ROM: {@code st (a3,d0.w)}). Paired with HCZ CollapsingBridge instances
 * whose TRIGGER mode uses the same index.
 *
 * <h3>State machine:</h3>
 * <ul>
 *   <li>Routine 2 (DETECT): polls nearest player X distance, activates at &lt; $80</li>
 *   <li>Routine 4 (ATTACK): runs Animate_RawMultiDelay, fires on frame 1
 *       transitions while on-screen, resets to DETECT on $F4 end command</li>
 * </ul>
 *
 * <h3>Animation (byte_87A10):</h3>
 * Frame 0 (128f idle) → Frame 1 (5f, FIRE) → Frame 0 (10f) →
 * Frame 1 (5f, FIRE) → Frame 0 (10f) → Frame 1 (5f, FIRE) →
 * Frame 0 (64f idle) → callback: reset to DETECT.
 *
 * <h3>Collision:</h3>
 * Body {@code $D7}: custom category with size 23. Implements both
 * {@code TouchResponseAttackable} (defeatable by attacking player) and
 * standard hurt (damages non-attacking player). On defeat, sets the
 * trigger array entry for the paired CollapsingBridge.
 *
 * <h3>Projectile:</h3>
 * Fires from mouth offset (-20, -7) with velocity (-$200, -$100), no gravity.
 * X offset/velocity negated when parent faces right. Alternates mapping frames
 * 2 and 3 every tick. Shield bounce deflectable (bit 3).
 */
public final class BlastoidBadnikInstance extends AbstractS3kBadnikInstance
        implements SpawnRewindRecreatable, TouchResponseListener {

    // --- Constants from ObjDat_Blastoid ---

    // collision_flags = $D7: size = $D7 & $3F = $17 (23)
    private static final int COLLISION_SIZE_INDEX = 0x17;
    private static final int COLLISION_FLAGS = 0xD7;

    // dc.w $280 → priority bucket 5
    private static final int PRIORITY_BUCKET = 5;

    // --- Detection ---

    // loc_87952: cmpi.w #$80,d2
    private static final int DETECT_RANGE = 0x80;

    // Obj_WaitOffscreen installs a $20-by-$20 placeholder and only restores
    // Obj_Blastoid after Render_Sprites sets render_flags bit 7.
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;

    // --- Projectile constants (ChildObjDat_879F8) ---

    // dc.b -$14,-7 — spawn offset relative to parent
    private static final int PROJECTILE_X_OFFSET = -0x14;
    private static final int PROJECTILE_Y_OFFSET = -7;

    // dc.w -$200,-$100 — initial velocity
    private static final int PROJECTILE_X_VEL = -0x200;
    private static final int PROJECTILE_Y_VEL = -0x100;

    // ObjDat3_879EC: collision_flags = $98 → size = $18 (24)
    private static final int PROJECTILE_COLLISION_SIZE = 0x18;

    // ObjDat3_879EC: dc.w $280 → priority bucket 5
    private static final int PROJECTILE_PRIORITY = 5;

    // --- Animation data (byte_87A10) ---
    // Animate_RawMultiDelay format: (frame, delay) pairs, terminated by $F4.
    // Delay N means the frame displays for N+1 ticks before advancing.

    // dc.b 0,$7F / 1,4 / 0,9 / 1,4 / 0,9 / 1,4 / 0,$3F / $F4
    private static final int[] ANIM_FRAMES = {0, 1, 0, 1, 0, 1, 0};
    private static final int[] ANIM_DELAYS = {0x7F, 4, 9, 4, 9, 4, 0x3F};

    // Frame 1 = mouth open (firing frame)
    private static final int FIRE_FRAME = 1;

    // --- State ---

    private enum State { DETECT, ATTACK }

    private State state = State.DETECT;
    private int triggerIndex; // subtype & 0x0F

    // Animate_RawMultiDelay state — unsigned byte timer (0-255).
    // The $F4 command handler clears anim_frame_timer to 0 (ROM: clr.b anim_frame_timer),
    // so re-entry to ATTACK always starts with timer=0 → immediate advance.
    private int animTimer;
    private int animIndex;
    private boolean waitingForOnscreen = true;
    private boolean placeholderRenderedOnscreen;
    /**
     * routine 0. Obj_WaitOffscreen's release pass (loc_85B02) restores the saved
     * operation pointer and returns without dispatching, leaving routine at 0, so
     * the dispatch AFTER the gate releases is the one that runs Blastoid_Init.
     * Both dispatches are consumed; neither runs Blastoid_DetectPlayer.
     */
    private boolean initPending = true;
    private boolean publishedTouchResponseListEntryThisFrame;
    private int collisionProperty;

    public BlastoidBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Blastoid",
                Sonic3kObjectArtKeys.HCZ_BLASTOID, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        // move.b subtype(a0),d0 / andi.w #$F,d0
        this.triggerIndex = spawn.subtype() & 0x0F;
        this.mappingFrame = 0;
        // animTimer starts at 0 (matches zeroed object RAM on spawn)
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        publishedTouchResponseListEntryThisFrame = false;
        if (isDestroyed()) return;

        // Obj_WaitOffscreen parity: the ROM entry point begins with a
        // retained placeholder routine. The dispatch that observes the
        // placeholder rendered on-screen only restores Obj_Blastoid and
        // returns; Blastoid's normal routine resumes on the next pass.
        if (waitingForOnscreen) {
            if (!placeholderRenderedOnscreen) {
                return;
            }
            placeholderRenderedOnscreen = false;
            waitingForOnscreen = false;
            return;
        }

        if (initPending) {
            // Blastoid_Init (sonic3k.asm:183586-183588) is
            // `lea ObjDat_Blastoid,a1 / jmp SetUp_ObjAttributes`, whose tail is
            // `addq.b #2,routine(a0)` then `rts`
            // (sonic3k.asm:176901-176919). Init RETURNS rather than falling
            // through to Blastoid_DetectPlayer, so this dispatch runs no player
            // detection and starts no attack; the routine-2 detect begins on the
            // next dispatch.
            initPending = false;
            // Obj_Blastoid (sonic3k.asm:183570-183578) still runs
            // Blastoid_CheckPlayerTouch and Sprite_CheckDeleteTouch after the
            // routine returns, so the tail work happens on the Init dispatch too.
            processPendingTouch();
            publishedTouchResponseListEntryThisFrame = true;
            return;
        }

        switch (state) {
            case DETECT -> updateDetect((AbstractPlayableSprite) playerEntity);
            case ATTACK -> updateAttack();
        }
        // Obj_Blastoid calls Check_PlayerCollision after its state routine.
        // Touch_Special has already latched the player selector in the
        // collision property during the player pass.
        processPendingTouch();
        // Obj_Blastoid's normal tail is Sprite_CheckDeleteTouch, which adds
        // the object to Collision_response_list after the wait routine has
        // returned. The list is built from this per-pass publication state.
        publishedTouchResponseListEntryThisFrame = true;
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (waitingForOnscreen) {
            placeholderRenderedOnscreen = isWithinRenderSpriteBounds(
                    WAIT_OFFSCREEN_MARGIN, WAIT_OFFSCREEN_MARGIN);
        }
    }

    @Override
    public int getCollisionFlags() {
        // Obj_WaitOffscreen returns before SetUp_ObjAttributes has been
        // reached and before Sprite_CheckDeleteTouch can publish this object.
        // collision_flags stays zero through the Init dispatch as well: the
        // frame's touch scan runs at the player slot before this object's
        // routine writes it.
        return (waitingForOnscreen || initPending) ? 0 : COLLISION_FLAGS;
    }

    @Override
    public boolean publishesTouchResponseListEntryThisFrame() {
        return publishedTouchResponseListEntryThisFrame;
    }

    @Override
    public int getCollisionProperty() {
        return collisionProperty;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.fromProvider(this);
    }

    @Override
    public boolean usesS3kTouchSpecialPropertyResponse() {
        // ObjDat_Blastoid uses collision_flags $D7. S3K Touch_Special handles
        // size index $17 by incrementing collision_property rather than
        // dispatching the generic boss response.
        return true;
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        // Touch_Special is polled every frame while the overlap remains; the
        // object consumes the resulting property on its next routine pass.
        return true;
    }

    @Override
    public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
        if (result.sizeIndex() != COLLISION_SIZE_INDEX || player == null) {
            return;
        }
        PlayableEntity main = services().playerQuery().mainPlayerOrNull();
        PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
        if (player == main) {
            collisionProperty = (collisionProperty + 1) & 0xFF;
        } else if (player == nativeP2) {
            collisionProperty = (collisionProperty + 2) & 0xFF;
        }
    }

    /**
     * ROM: {@code Check_PlayerCollision} selects the player from the latched
     * property and {@code sub_879A8} immediately tests attack state before
     * either defeating the Blastoid or calling {@code HurtCharacter_Directly}.
     */
    private void processPendingTouch() {
        int property = collisionProperty & 0xFF;
        if (property == 0) {
            return;
        }
        collisionProperty = 0;

        PlayableEntity target = property == 1
                ? services().playerQuery().mainPlayerOrNull()
                : services().playerQuery().nativeP2OrNull();
        if (!(target instanceof AbstractPlayableSprite player)) {
            return;
        }

        if (isPlayerAttacking(player)) {
            int enemyY = currentY;
            onPlayerAttack(player, null);
            applyEnemyDefeatedBounce(player, enemyY);
            return;
        }
        if (player.getInvulnerable()) {
            return;
        }

        boolean hadRings = player.getRingCount() > 0;
        player.applyHurtOrDeath(currentX, com.openggf.game.DamageCause.NORMAL, hadRings);
    }

    private boolean isPlayerAttacking(AbstractPlayableSprite player) {
        // Check_PlayerAttack first accepts the invincible status and the raw
        // spindash/roll animation IDs before character-specific abilities.
        if (player.isSuperSonic()
                || player.getInvincibleFrames() > 0
                || player.getAnimationId() == 9
                || player.getAnimationId() == 2) {
            return true;
        }
        if (player instanceof Knuckles) {
            int ability = player.getDoubleJumpFlag();
            return ability == 1 || ability == 3;
        }
        if (player instanceof Tails tails) {
            if (player.getDoubleJumpFlag() == 0 || tails.isInWater()) {
                return false;
            }
            int dx = (short) (player.getCentreX() - currentX);
            int dy = (short) (player.getCentreY() - currentY);
            int angle = (int) Math.round(Math.atan2(dy, dx) * 128.0 / Math.PI) & 0xFF;
            return ((angle - 0x20) & 0xFF) < 0x40;
        }
        return false;
    }

    private void applyEnemyDefeatedBounce(AbstractPlayableSprite player, int enemyY) {
        // EnemyDefeated adjusts only y_vel; it does not set the air flag.
        int ySpeed = player.getYSpeed();
        if (ySpeed < 0) {
            player.setYSpeed((short) (ySpeed + 0x100));
        } else if (player.getCentreY() >= enemyY) {
            player.setYSpeed((short) (ySpeed - 0x100));
        } else {
            player.setYSpeed((short) -ySpeed);
        }
    }

    // ── Routine 2: Detect ────────────────────────────────────────────────

    /**
     * Wait for nearest player within detection range.
     * <pre>
     * loc_87952:
     *     jsr    Find_SonicTails(pc)     ; d2 = abs X distance to nearest player
     *     cmpi.w #$80,d2
     *     blo.s  loc_8795E               ; if less, activate
     *     rts
     * </pre>
     */
    private void updateDetect(AbstractPlayableSprite player) {
        int distance = findNearestPlayerXDistance(player);
        if (distance >= DETECT_RANGE) return;

        // loc_8795E: transition to ATTACK (routine 4)
        // move.b #4,routine(a0)
        // move.l #byte_87A10,$30(a0)  — reset animation script pointer
        // move.l #loc_879A0,$34(a0)   — set end-of-animation callback
        state = State.ATTACK;
        // The ROM anim_frame is a byte offset into the pair table. It starts
        // at zero, and Animate_RawMultiDelay advances it by two before
        // loading the first entry, so the first attack pass loads frame 1
        // and fires immediately.
        animIndex = 0;
        // animTimer is NOT reset — ROM preserves anim_frame_timer across transitions
    }

    // ── Routine 4: Attack animation ──────────────────────────────────────

    /**
     * Run firing animation and spawn projectiles on frame 1 transitions.
     * <pre>
     * loc_87976:
     *     jsr    Animate_RawMultiDelay(pc)
     *     tst.w  d2                       ; 0=timer running, -1=anim ended
     *     beq.s  locret_87974
     *     bmi.s  locret_87974             ; skip fire on end callback
     *     cmpi.b #1,mapping_frame(a0)     ; mouth open?
     *     bne.s  locret_87974
     *     tst.b  render_flags(a0)         ; on screen?
     *     bpl.w  locret_87974
     *     ; fire projectile
     * </pre>
     */
    private void updateAttack() {
        // Animate_RawMultiDelay: subq.b #1,anim_frame_timer(a0) / bpl.s return
        // 68k bpl branches when N flag clear (result 0x00-0x7F); falls through
        // when result is negative in signed byte (0x80-0xFF).
        animTimer = (animTimer - 1) & 0xFF;
        if (animTimer < 0x80) return; // bpl: result positive → timer still running (d2=0)

        // Timer expired (result negative) → advance to next animation entry
        animIndex++;
        if (animIndex >= ANIM_FRAMES.length) {
            // $F4 terminator: loc_84600 → clr.b anim_frame_timer / callback loc_879A0
            animTimer = 0; // ROM: clr.b anim_frame_timer(a0)
            state = State.DETECT;
            mappingFrame = 0;
            return; // d2=-1: skip fire check
        }

        // Load new frame and delay
        int newFrame = ANIM_FRAMES[animIndex];
        mappingFrame = newFrame;
        animTimer = ANIM_DELAYS[animIndex];

        // d2=1: frame changed — check if we should fire
        // cmpi.b #1,mapping_frame(a0) / bne.s skip
        // tst.b render_flags(a0)      / bpl.w skip  (bit 7 = X-on-screen flag)
        if (newFrame == FIRE_FRAME && isOnScreenX()) {
            fireProjectile();
        }
    }

    // ── Projectile spawning ──────────────────────────────────────────────

    /**
     * Spawn a projectile from the Blastoid's mouth.
     * <pre>
     * ChildObjDat_879F8:
     *     dc.b -$14,-7               ; X/Y offset from parent
     *     dc.w -$200,-$100           ; X/Y velocity
     * CreateChild5_ComplexAdjusted negates X offset and X velocity
     * when render_flags bit 0 (H-flip) is set.
     * </pre>
     */
    private void fireProjectile() {
        // moveq #signextendB(sfx_Projectile),d0 / jsr (Play_SFX).l
        services().playSfx(Sonic3kSfx.PROJECTILE.id);

        int xOff = PROJECTILE_X_OFFSET;
        int xVel = PROJECTILE_X_VEL;

        // CreateChild5_ComplexAdjusted: negate X when parent faces right
        if (!facingLeft) {
            xOff = -xOff;
            xVel = -xVel;
        }

        // CreateChild5_ComplexAdjusted does NOT copy render_flags bit 0 to child;
        // SetUp_ObjAttributes only sets bit 2 (world coords). Projectile never H-flips.
        int finalXOff = xOff;
        int finalXVel = xVel;
        ObjectServices svc = tryServices();
        if (svc != null && svc.objectManager() != null) {
            // ROM CreateChild5_ComplexAdjusted uses AllocateObjectAfterCurrent,
            // so the projectile burst occupies the slots immediately after
            // this Blastoid in SST order.
            spawnChild(() -> new BlastoidProjectile(
                    spawn,
                    currentX + finalXOff,
                    currentY + PROJECTILE_Y_OFFSET,
                    finalXVel,
                    PROJECTILE_Y_VEL));
        }
    }

    // ── Player proximity ─────────────────────────────────────────────────

    /**
     * Find nearest player (Sonic or Tails) by horizontal distance.
     * ROM: {@code Find_SonicTails} — compares Player_1 and Player_2 X distances,
     * returns d2 = absolute X distance to the closer one.
     */
    private int findNearestPlayerXDistance(AbstractPlayableSprite mainPlayer) {
        ObjectServices svc = tryServices();
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> mainPlayer,
                () -> svc != null ? svc.playerQuery().sidekicks() : List.of());
        return query.nearestByRomX(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS,
                currentX,
                BlastoidBadnikInstance::isLivePlayable).distance();
    }

    private static boolean isLivePlayable(PlayableEntity player) {
        return player instanceof AbstractPlayableSprite sprite && !sprite.getDead();
    }

    // ── Defeat + trigger ─────────────────────────────────────────────────

    /**
     * Override defeat to set level trigger array before destruction.
     * <pre>
     * loc_879C4:                          ; enemy defeated path
     *     move.b subtype(a0),d0
     *     andi.w #$F,d0
     *     lea    (Level_trigger_array).w,a3
     *     st     (a3,d0.w)               ; set byte to $FF
     *     jsr    EnemyDefeated(pc)
     * </pre>
     */
    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        // Set trigger flag for paired CollapsingBridge
        Sonic3kLevelTriggerManager.setAll(triggerIndex);
        // Standard defeat: explosion + animal + points
        super.onPlayerAttack(playerEntity, result);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Projectile inner class
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Blastoid projectile — linear velocity with no gravity, animated between
     * mapping frames 2 and 3 every tick. Shield bounce deflectable.
     *
     * <p>ROM: {@code loc_86D4A} child object with {@code Move_AnimateRaw}
     * (MoveSprite2 + Animate_Raw) and {@code byte_87A1F} animation script
     * (speed 0, frames [2, 3], $FC loop).
     *
     * <p>Touch collision: HURT category ({@code $98 = $80 | $18}).
     * Shield reaction: bounce (bit 3) via {@code bset #3,shield_reaction(a0)}.
     */
    private static final class BlastoidProjectile extends AbstractObjectInstance
            implements TouchResponseProvider, SpawnAndCoordinateZeroScalarArgsRewindRecreatable {

        // loc_86D4A: bset #3,shield_reaction(a0)
        private static final int SHIELD_REACTION_BOUNCE = 1 << 3;
        private static final int DEFLECT_SPEED = 0x800;
        private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = TouchResponseProfile.fromCanonical(
                new com.openggf.game.profiles.touchresponse.TouchResponseProfile(
                        com.openggf.game.profiles.touchresponse.TouchCategoryDecodeMode.NORMAL,
                        false,
                        true,
                        false,
                        com.openggf.game.profiles.touchresponse.TouchShieldDeflectCapability.SHIELD_DEFLECT,
                        SHIELD_REACTION_BOUNCE,
                        com.openggf.game.profiles.touchresponse.TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                        com.openggf.game.profiles.touchresponse.TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                        com.openggf.game.profiles.touchresponse.TouchOverlapStopPolicy
                                .STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS));

        // byte_87A1F: Animate_Raw speed 0, frames [2, 3], $FC loop
        private static final int FRAME_A = 2;
        private static final int FRAME_B = 3;

        // Sprite_CheckDeleteTouchXY uses generous bounds: ~$280 horizontal, ~$200 vertical.
        // With a 320×224 viewport, this gives ~160px margin on each side.
        private static final int OFF_SCREEN_MARGIN = 160;

        private int currentX;
        private int currentY;
        private int xVelocity;
        private int yVelocity;
        private int xSubpixel;
        private int ySubpixel;
        private int animFrame;
        private boolean collisionEnabled = true;

        private BlastoidProjectile() {
            this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), 0, 0, 0, 0);
        }

        BlastoidProjectile(ObjectSpawn ownerSpawn, int x, int y,
                           int xVel, int yVel) {
            super(ownerSpawn, "BlastoidProjectile");
            this.currentX = x;
            this.currentY = y;
            this.xVelocity = xVel;
            this.yVelocity = yVel;
            this.animFrame = FRAME_A;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            // Move_AnimateRaw → MoveSprite2: velocity to position, no gravity
            int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
            int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
            xPos24 += xVelocity;
            yPos24 += yVelocity;
            currentX = xPos24 >> 8;
            currentY = yPos24 >> 8;
            xSubpixel = xPos24 & 0xFF;
            ySubpixel = yPos24 & 0xFF;

            // Animate_Raw speed 0: toggle frame every tick
            // byte_87A1F: dc.b 0, 2, 3, $FC
            animFrame = (animFrame == FRAME_A) ? FRAME_B : FRAME_A;

            // Sprite_CheckDeleteTouchXY: generous off-screen deletion
            if (!isOnScreen(OFF_SCREEN_MARGIN)) {
                setDestroyed(true);
            }
        }

        @Override
        public int getCollisionFlags() {
            if (!collisionEnabled) return 0;
            // ObjDat3_879EC: collision_flags = $98 (HURT | size $18)
            return 0x80 | PROJECTILE_COLLISION_SIZE;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
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
        public int getShieldReactionFlags() {
            return SHIELD_REACTION_BOUNCE;
        }

        @Override
        public boolean onShieldDeflect(PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (player == null) return false;

            int dx = player.getCentreX() - currentX;
            int dy = player.getCentreY() - currentY;
            int angle = TrigLookupTable.calcAngle(
                    saturateToShort(dx), saturateToShort(dy));

            // ROM: Touch_ChkHurt_Bounce_Projectile / ShieldTouchResponse
            xVelocity = -((TrigLookupTable.cosHex(angle) * DEFLECT_SPEED) >> 8);
            yVelocity = -((TrigLookupTable.sinHex(angle) * DEFLECT_SPEED) >> 8);
            collisionEnabled = false;
            return true;
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
            return PROJECTILE_PRIORITY;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager rm = services().renderManager();
            if (rm == null) return;
            PatternSpriteRenderer renderer = rm.getRenderer(Sonic3kObjectArtKeys.HCZ_BLASTOID);
            if (renderer == null || !renderer.isReady()) return;
            // ROM: render_flags bit 0 never set on child — no H-flip
            renderer.drawFrameIndex(animFrame, currentX, currentY, false, false);
        }

        private static short saturateToShort(int v) {
            return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }
    }
}
