package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Sonic 1 Pinball Bumper (Object 0x47) - Spring Yard Zone.
 * <p>
 * From docs/s1disasm/_incObj/47 Bumper.asm:
 * <ul>
 *   <li>Routine 0 (Bump_Main): Init - set art, mappings, collision</li>
 *   <li>Routine 2 (Bump_Hit): Main loop - check touch, apply bounce, animate</li>
 * </ul>
 * <p>
 * Bounces Sonic radially away from the bumper center when contacted.
 * Awards 10 points per hit, up to 10 hits per bumper (tracked via respawn state).
 * <p>
 * <b>Art:</b> Nem_Bumper at ArtTile_SYZ_Bumper ($380), palette 0.
 * 3 mapping frames: idle, compressed hit, expanded hit.
 * <p>
 * <b>Animation (Ani_Bump):</b>
 * <ul>
 *   <li>Anim 0 (idle): frame 0, duration $F, loop</li>
 *   <li>Anim 1 (hit): frames 1,2,1,2 at duration 3, then change to anim 0</li>
 * </ul>
 * <p>
 * <b>Collision:</b> obColType $D7 — {@code React_Special} increments
 * obColProp, and Bump_Hit consumes that signal.
 * <p>
 * <b>Physics (Bump_Hit):</b>
 * <pre>
 * dx = bumper_x - sonic_x
 * dy = bumper_y - sonic_y
 * angle = CalcAngle(dx, dy)
 * sin, cos = CalcSine(angle)
 * x_vel = -$700 * cos >> 8
 * y_vel = -$700 * sin >> 8
 * </pre>
 */
public class Sonic1BumperObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, SpawnRewindRecreatable {

    // ---- ROM Constants ----

    // From disassembly: muls.w #-$700,d1 / muls.w #-$700,d0
    private static final int BOUNCE_VELOCITY = 0x700;

    // From disassembly: move.b #1,obPriority(a0)
    private static final int PRIORITY = 1;

    // From disassembly: move.b #$10,obActWid(a0) — active width 16 pixels
    private static final int ACTIVE_WIDTH = 0x10;

    // obColType $D7 — React_Special property route + size index $17 = 8x8 half-widths.
    private static final int COLLISION_FLAGS = 0xD7;
    private static final int COLLISION_SIZE_INDEX = 0x17;
    private static final int COLLISION_HALF_WIDTH = 8;
    private static final int COLLISION_HALF_HEIGHT = 8;
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.S1_SPECIAL_PROPERTY,
            true,
            false,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    // From disassembly: cmpi.b #$8A,2(a2,d0.w) — max 10 hits ($80 + 10 = $8A)
    private static final int MAX_HIT_COUNT = 10;

    // From disassembly: moveq #1,d0 / jsr (AddPoints).l — 10 points
    private static final int POINTS_PER_HIT = 10;

    // Points popup frame index 4 = "10" display
    private static final int POINTS_FRAME_INDEX = 4;

    // ---- Animation Constants (from Ani_Bump) ----

    // Anim 0: idle — frame 0, duration $F (15), loop
    private static final int FRAME_IDLE = 0;

    // Anim 1: hit sequence — frames 1,2,1,2 at duration 3, then revert to idle
    private static final int[] HIT_FRAMES = {1, 2, 1, 2};
    private static final int HIT_FRAME_DURATION = 4; // dc.b 3 = 3+1 frames per step

    // ---- Instance State ----

    private int mappingFrame = FRAME_IDLE;
    private int hitAnimIndex = -1;  // -1 = not playing hit anim
    private int hitAnimTimer = 0;
    private int collisionProperty = 0;
    private transient AbstractPlayableSprite pendingTouchedPlayer;
    private int pendingTouchCentreX;
    private int pendingTouchCentreY;
    private int hitCount = 0;

    public Sonic1BumperObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Bumper");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM Bump_Hit consumes obColProp after ReactToItem increments it for
        // $D7: docs/s1disasm/_incObj/47 Bumper.asm:24-40 and
        // docs/s1disasm/_incObj/sub ReactToItem.asm:377-427.
        if (collisionProperty != 0 && pendingTouchedPlayer != null) {
            collisionProperty = 0;
            AbstractPlayableSprite touchedPlayer = pendingTouchedPlayer;
            int touchCentreX = pendingTouchCentreX;
            int touchCentreY = pendingTouchCentreY;
            pendingTouchedPlayer = null;
            if (!touchedPlayer.isHurt() && !touchedPlayer.getDead()) {
                applyBounce(touchedPlayer, touchCentreX, touchCentreY);
            }
        }

        // Update hit animation sequence
        if (hitAnimIndex >= 0) {
            if (hitAnimTimer > 0) {
                hitAnimTimer--;
            } else {
                hitAnimIndex++;
                if (hitAnimIndex >= HIT_FRAMES.length) {
                    // afChange,0 — revert to idle animation
                    hitAnimIndex = -1;
                    mappingFrame = FRAME_IDLE;
                } else {
                    mappingFrame = HIT_FRAMES[hitAnimIndex];
                    hitAnimTimer = HIT_FRAME_DURATION - 1;
                }
            }
        }

        // ROM Bump_Hit .display uses out_of_range.s, then .resetcount clears
        // obRespawnNo's v_objstate bit before DeleteObject:
        // docs/s1disasm/_incObj/47 Bumper.asm:66-79. ObjPosLoad streams
        // bumpers far ahead of the visible screen, so this must use the
        // chunk-aligned out_of_range window rather than the visible-X gate.
        if (!isInRange()) {
            setDestroyedByOffscreen();
        }
    }

    /**
     * Apply radial bounce to player.
     * <p>
     * ROM: Bump_Hit (docs/s1disasm/_incObj/47 Bumper.asm lines 29-43)
     * <pre>
     * sub.w obX(a1),d1     ; dx = bumper_x - sonic_x
     * sub.w obY(a1),d2     ; dy = bumper_y - sonic_y
     * jsr   (CalcAngle).l  ; d0 = angle (0-255)
     * jsr   (CalcSine).l   ; d0 = sin, d1 = cos
     * muls.w #-$700,d1     ; x_vel = -$700 * cos
     * asr.l  #8,d1         ; x_vel >>= 8
     * muls.w #-$700,d0     ; y_vel = -$700 * sin
     * asr.l  #8,d0         ; y_vel >>= 8
     * </pre>
     * Note: CalcAngle takes (dx, dy) and returns angle.
     * CalcSine returns (sin, cos) in (d0, d1).
     * The negation pushes Sonic AWAY from the bumper.
     * <p>
     * ROM's ReactToItem (Sonic's own slot) and Bump_Hit (the bumper's slot) both
     * run within the SAME frame's ExecuteObjects pass — Bump_Hit reads obX/obY
     * of the position Sonic was AT WHEN THE TOUCH WAS DETECTED, docs/s1disasm/
     * _incObj/47 Bumper.asm:29-30 (docs/s1disasm/_incObj/sub ReactToItem.asm:
     * 377-427 sets obColProp that same frame). Our engine defers the bumper's
     * own consuming update() to the FOLLOWING frame (ObjectManager runs every
     * object's update() before the touch-response pass, so a touch armed this
     * frame is only consumed on the object's NEXT update() call). A fast-moving
     * player (e.g. one just launched by a spring) can cross clean through the
     * bumper's position during that single deferred frame, so re-reading the
     * player's LIVE position at consumption time can sample him already on the
     * opposite side of the bumper and compute a bounce in the wrong direction
     * entirely (observed: a player approaching from below gets launched further
     * UP instead of reflected back down). Use the position captured at the
     * moment of the actual touch (onTouchResponse) instead of the live,
     * one-frame-stale player position.
     */
    private void applyBounce(AbstractPlayableSprite player, int touchCentreX, int touchCentreY) {
        // ROM: sub.w obX(a1),d1 / sub.w obY(a1),d2 (d1=bumper.X - sonic.X, d2=bumper.Y - sonic.Y)
        // Then CalcAngle (d1, d2) and CalcSine to get sin/cos as 8-bit fixed-point.
        int dx = spawn.x() - touchCentreX;
        int dy = spawn.y() - touchCentreY;

        // ROM CalcAngle takes raw (dx, dy) — not speed. We reuse the same logic
        // since it's just atan2 over the 256-entry lookup table.
        int hexAngle = com.openggf.physics.TrigLookupTable.calcAngle(
                (short) dx, (short) dy);

        int sinVal = com.openggf.physics.TrigLookupTable.sinHex(hexAngle);
        int cosVal = com.openggf.physics.TrigLookupTable.cosHex(hexAngle);

        // ROM: muls.w #-$700,d1 / asr.l #8,d1 (cos * -0x700 >> 8 -> VelX)
        // ROM: muls.w #-$700,d0 / asr.l #8,d0 (sin * -0x700 >> 8 -> VelY)
        // ROM sin/cos are 8-bit fixed-point (range -0x100..+0x100).
        int xVel = (-BOUNCE_VELOCITY * cosVal) >> 8;
        int yVel = (-BOUNCE_VELOCITY * sinVal) >> 8;

        // ROM: move.w d1,obVelX(a1) / move.w d0,obVelY(a1)
        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);

        // ROM: bset #1,obStatus(a1) — set airborne
        // ROM: bclr #4,obStatus(a1) — clear roll-jumping
        // ROM: bclr #5,obStatus(a1) — clear pushing
        // ROM: clr.b objoff_3C(a1)  — clear the jumping flag (Sonic's $3C byte),
        //      NOT obInertia. Bump_Hit preserves ground speed.
        player.setAir(true);
        player.setPushing(false);
        player.setRollingJump(false);
        player.setJumping(false);

        // ROM: move.b #1,obAnim(a0) — trigger hit animation
        hitAnimIndex = 0;
        mappingFrame = HIT_FRAMES[0];
        hitAnimTimer = HIT_FRAME_DURATION - 1;

        // ROM: move.w #sfx_Bumper,d0 / jsr (QueueSound2).l
        try {
            services().playSfx(Sonic1Sfx.BUMPER.id);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }

        // Score: up to MAX_HIT_COUNT hits award points
        awardPoints();
    }

    /**
     * Award 10 points per hit, up to 10 hits per bumper.
     * <p>
     * ROM tracks hits via v_objstate table:
     * <pre>
     * cmpi.b #$8A,2(a2,d0.w)  ; $80 (spawned flag) + 10 = $8A
     * bhs.s  .display          ; if >= 10 hits, no points
     * addq.b #1,2(a2,d0.w)    ; increment hit counter
     * moveq  #1,d0
     * jsr    (AddPoints).l     ; add 10 to score
     * </pre>
     * The engine doesn't expose the respawn state byte directly,
     * so we use a local hit counter as equivalent behavior.
     */
    private void awardPoints() {
        if (hitCount >= MAX_HIT_COUNT) {
            return;
        }
        hitCount++;

        // ROM: moveq #1,d0 / jsr (AddPoints).l — adds 10 points
        services().gameState().addScore(POINTS_PER_HIT);

        // Spawn points popup (id_Points = 0x29)
        final ObjectServices svc = tryServices();
        ObjectManager objectManager = svc != null ? svc.objectManager() : null;
        if (objectManager != null) {
            spawnFreeChild(() -> {
                ObjectSpawn pointsSpawn = new ObjectSpawn(
                        spawn.x(), spawn.y(), 0x29, 0, 0, false, 0);
                Sonic1PointsObjectInstance pointsObj = new Sonic1PointsObjectInstance(
                        pointsSpawn, svc, POINTS_PER_HIT);
                pointsObj.setScoreFrameIndex(POINTS_FRAME_INDEX);
                return pointsObj;
            });
        }
    }

    @Override
    public int getCollisionFlags() {
        return isDestroyed() ? 0 : COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return collisionProperty;
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
    public boolean usesSonic1TouchSpecialPropertyResponse() {
        return true;
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        return true;
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        if (result.category() != TouchCategory.SPECIAL || result.sizeIndex() != COLLISION_SIZE_INDEX) {
            return;
        }
        if (playerEntity instanceof AbstractPlayableSprite player) {
            // ROM React_Special .D7orE1: addq.b #1,obColProp(a1). Bump_Hit
            // consumes the property from the object's own slot later this frame.
            collisionProperty = (collisionProperty + 1) & 0xFF;
            pendingTouchedPlayer = player;
            // Capture position at the moment of the touch (see applyBounce's
            // Javadoc): our update() defers consumption to the object's NEXT
            // update() call, one frame after ROM's same-frame Bump_Hit would
            // have read it, so the live position must not be re-sampled then.
            pendingTouchCentreX = player.getCentreX();
            pendingTouchCentreY = player.getCentreY();
        }
    }

    // ---- Rendering ----

    @Override
    public int getPriorityBucket() {
        // ROM: move.b #1,obPriority(a0)
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.BUMPER);
        if (renderer == null) return;
        // ROM: move.b #4,obRender(a0) — bit 2 set = use screen coordinates
        // No H-flip or V-flip for bumpers (all subtypes are 0x00)
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw collision box (16x16 from center)
        float r = collisionProperty != 0 ? 1f : 0f;
        float g = collisionProperty != 0 ? 0.5f : 1f;
        float b = 0f;
        ctx.drawRect(spawn.x(), spawn.y(), COLLISION_HALF_WIDTH, COLLISION_HALF_HEIGHT, r, g, b);
    }
}
