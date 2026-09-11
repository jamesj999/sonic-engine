package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x62 - Gargoyle Head (LZ).
 * <p>
 * A stationary gargoyle head trap that periodically spits fireballs horizontally.
 * The head itself is non-interactive (no collision). Spawned fireballs travel
 * horizontally until they hit a wall, damaging Sonic on contact.
 * <p>
 * This class handles both the gargoyle head (spawner) and the fireball projectile.
 * The head creates fireball instances dynamically via {@link Fireball} inner class.
 * <p>
 * <b>Subtype encoding (head):</b>
 * Lower nybble (bits 0-3): Index into Gar_SpitRate table, selecting spit intervals
 * of 30, 60, 90, 120, 150, 180, 210, or 240 frames.
 * <p>
 * <b>Facing direction:</b>
 * obStatus bit 0 (from spawn renderFlags bit 0): 0 = facing left, 1 = facing right.
 * The ROM checks "btst #0,obStatus(a0) / bne.s .noflip" after setting velocity to +$200.
 * So bit 0 SET keeps positive X velocity (fireball moves right), and bit 0 CLEAR negates
 * velocity (fireball moves left).
 * <p>
 * Reference: docs/s1disasm/_incObj/62 Gargoyle.asm
 */
public class Sonic1GargoyleObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /**
     * Gar_SpitRate: Fireball spit rate table indexed by lower nybble of subtype.
     * From disassembly: dc.b 30, 60, 90, 120, 150, 180, 210, 240
     */
    private static final int[] SPIT_RATES = {30, 60, 90, 120, 150, 180, 210, 240};

    /** obPriority = 3 for head (from Gar_Main). */
    private static final int HEAD_PRIORITY = 3;

    /** obActWid = $10 for head (from Gar_Main). */
    private static final int HEAD_ACT_WIDTH = 0x10;

    /** Head mapping frame index (frames 0 and 1 are identical in Map_Gar). */
    private static final int HEAD_FRAME = 0;

    /** Debug color for gargoyle head (dark stone gray). */
    private static final DebugColor DEBUG_COLOR = new DebugColor(128, 128, 96);

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Spit delay in frames, from Gar_SpitRate. Stored in obDelayAni. */
    private int spitDelay;

    /** Countdown timer. Stored in obTimeFrame. */
    private int timer;

    /** Whether gargoyle faces right (obStatus bit 0: 0=left, 1=right). */
    private boolean facingRight;

    public Sonic1GargoyleObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Gargoyle");

        // ROM: Gar_Main
        // move.b obSubtype(a0),d0
        // andi.w #$F,d0
        // move.b Gar_SpitRate(pc,d0.w),obDelayAni(a0)
        int subtype = spawn.subtype();
        int rateIndex = subtype & 0x0F;
        rateIndex = Math.min(rateIndex, SPIT_RATES.length - 1);
        this.spitDelay = SPIT_RATES[rateIndex];

        // move.b obDelayAni(a0),obTimeFrame(a0)
        this.timer = spitDelay;

        // obStatus bit 0 from spawn renderFlags bit 0.
        // S1 convention: bit 0 clear = facing left, bit 0 set = facing right.
        this.facingRight = (spawn.renderFlags() & 1) != 0;
    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: Gar_MakeFire (Routine 2)
        // subq.b #1,obTimeFrame(a0)
        timer--;
        // bne.s .nofire
        if (timer > 0) {
            return;
        }

        // move.b obDelayAni(a0),obTimeFrame(a0) ; reset timer
        timer = spitDelay;

        // docs/s1disasm/s1disasm/_incObj/62 LZ Gargoyle.asm:38 calls
        // ChkObjectVisible, which uses exact screen bounds with no act-width margin.
        if (!isChkObjectVisible()) {
            return;
        }

        // bsr.w FindFreeObj / bne.s .nofire
        if (services().objectManager() == null) {
            return;
        }

        // Create fireball child object at gargoyle's position
        // ROM: _move.b #id_Gargoyle,obID(a1)
        //      addq.b #4,obRoutine(a1) ; use Gar_FireBall routine
        //      move.w obX(a0),obX(a1)
        //      move.w obY(a0),obY(a1)
        //      move.b obRender(a0),obRender(a1)
        //      move.b obStatus(a0),obStatus(a1)
        spawnFreeChild(() -> new Fireball(spawn.x(), spawn.y(), facingRight));
        // Play fireball sound (ROM: move.w #sfx_Fireball,d0 / jsr (QueueSound2).l)
        services().playSfx(Fireball.SFX_FIREBALL);
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.LZ_GARGOYLE);
        if (renderer == null) return;

        // Head uses frame 0. Gargoyle art is authored facing left by default, so apply
        // H-flip when obStatus bit 0 is set (facing right).
        // ori.b #4,obRender(a0) -> bit 2 set = uses screen-space coords
        // The render flag bit 0 in ROM controls X-flip for display.
        renderer.drawFrameIndex(HEAD_FRAME, spawn.x(), spawn.y(), facingRight, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(HEAD_PRIORITY);
    }

    // ========================================================================
    // Debug Rendering
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawRect(spawn.x(), spawn.y(), HEAD_ACT_WIDTH, HEAD_ACT_WIDTH,
                0.5f, 0.5f, 0.4f);
        ctx.drawWorldLabel(spawn.x(), spawn.y(), -1,
                String.format("Gargoyle rate=%d %s",
                        spitDelay, facingRight ? "R" : "L"),
                DEBUG_COLOR);
    }

    // ========================================================================
    // Fireball Inner Class
    // ========================================================================

    /**
     * Gargoyle fireball projectile (Gar_FireBall / Gar_AniFire routines).
     * <p>
     * Moves horizontally at speed $200 (or -$200 if facing left).
     * Animates by toggling between mapping frames 2 and 3 every 8 game frames.
     * Damages Sonic on contact (obColType = $98: HURT $80 | size $18).
     * Deleted when hitting a wall (ObjHitWallLeft / ObjHitWallRight returns negative).
     * <p>
     * Reference: docs/s1disasm/_incObj/62 Gargoyle.asm, Gar_FireBall / Gar_AniFire
     */
    public static class Fireball extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {

        // ====================================================================
        // ROM Constants
        // ====================================================================

        /** obVelX = $200 for rightward fireball (from Gar_FireBall). */
        private static final int FIREBALL_SPEED = 0x200;

        /** obColType = $98: HURT category ($80) | size index $18 (from Gar_FireBall). */
        private static final int COLLISION_FLAGS = 0x98;

        /** obPriority = 4 for fireball (from Gar_FireBall). */
        private static final int FIREBALL_PRIORITY = 4;

        /** obActWid = 8 for fireball (from Gar_FireBall). */
        private static final int FIREBALL_ACT_WIDTH = 8;

        /** obHeight = 8, obWidth = 8 for fireball (from Gar_FireBall). */
        private static final int FIREBALL_RADIUS = 8;

        /** Y offset applied to fireball spawn position: addq.w #8,obY(a0). */
        private static final int FIREBALL_Y_OFFSET = 8;

        /** Fireball animation frame 1 (mapping frame 2). */
        private static final int FIREBALL_FRAME_1 = 2;

        /** Fireball animation frame 2 (mapping frame 3). */
        private static final int FIREBALL_FRAME_2 = 3;

        /**
         * Animation toggle interval: every 8 frames.
         * ROM: move.b (v_framebyte).w,d0 / andi.b #7,d0 / bne.s .nochg
         */
        private static final int ANIM_TOGGLE_MASK = 7;

        /**
         * Wall sensor offset for wall collision check.
         * ROM: moveq #-8,d3 (left) or moveq #8,d3 (right)
         * d3 is the horizontal offset from object center for wall check.
         */
        private static final int WALL_SENSOR_OFFSET = 8;

        /**
         * SFX ID for fireball: sfx_Fireball = $AE.
         * From Constants.asm: sfx_Fireball equ ((ptr_sndAE-SoundIndex)/4)+sfx__First
         */
        static final int SFX_FIREBALL = Sonic1Sfx.AE_UNUSED.id;

        /** Debug color for fireball (bright orange). */
        private static final DebugColor DEBUG_COLOR = new DebugColor(255, 140, 0);

        // ====================================================================
        // Instance State
        // ====================================================================

        /** Current X position. */
        private int currentX;

        /** Current Y position (offset by +8 from gargoyle center). */
        private int currentY;

        /** X velocity ($200 or -$200). */
        private int velX;

        /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
        private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

        /** Whether fireball is moving right. */
        private boolean movingRight;

        /** Current animation frame (toggles between FIREBALL_FRAME_1 and FIREBALL_FRAME_2). */
        private int currentFrame;

        public Fireball(int gargoyleX, int gargoyleY, boolean facingRight) {
            // Create spawn with gargoyle's object ID and fireball routine
            super(new ObjectSpawn(gargoyleX, gargoyleY, 0x62, 0, 0, false, 0), "GargoyleFireball");

            // ROM: move.w obX(a0),obX(a1) / move.w obY(a0),obY(a1)
            this.currentX = gargoyleX;
            // addq.w #8,obY(a0)
            this.currentY = gargoyleY + FIREBALL_Y_OFFSET;

            // move.w #$200,obVelX(a0)
            // btst #0,obStatus(a0) ; is gargoyle facing left?
            // bne.s .noflip ; if not, branch
            // neg.w obVelX(a0)
            // Bit 0 SET = facing right = no neg -> velX = +$200 (rightward).
            // Bit 0 CLEAR = facing left -> velX = -$200 (leftward).
            this.movingRight = facingRight;
            this.velX = facingRight ? FIREBALL_SPEED : -FIREBALL_SPEED;

            // move.b #2,obFrame(a0) -> starts at mapping frame 2
            this.currentFrame = FIREBALL_FRAME_1;

            // Sound is played by the parent Gargoyle after construction
        }

        private Fireball() {
            this(0, 0, false);
        }

        @Override
        public Fireball recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn spawn = ctx.spawn();
            return new Fireball(spawn.x(), spawn.y() - FIREBALL_Y_OFFSET, false);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            // ROM: Gar_AniFire (Routine 6)

            // Animation: toggle frame every 8 game frames
            // move.b (v_framebyte).w,d0 / andi.b #7,d0 / bne.s .nochg
            // bchg #0,obFrame(a0) ; change every 8 frames
            if ((vIntRunCount & ANIM_TOGGLE_MASK) == 0) {
                // Toggle between frames 2 and 3
                currentFrame = (currentFrame == FIREBALL_FRAME_1)
                        ? FIREBALL_FRAME_2 : FIREBALL_FRAME_1;
            }

            // bsr.w SpeedToPos - apply X velocity using shared 16:8 helper.
            // Note: the previous inline form (xSubpixel += velX; currentX += xSubpixel >> 8;
            // xSubpixel &= 0xFF) is mathematically equivalent to SubpixelMotion.moveX for the
            // velocities used here (FIREBALL_SPEED = ±$200, low byte 0), but the helper keeps
            // the integration semantics consistent across the codebase.
            motion.x = currentX;
            motion.xVel = velX;
            SubpixelMotion.moveX(motion);
            currentX = motion.x;

            // Wall collision check
            // btst #0,obStatus(a0) / bne.s .isright
            if (!movingRight) {
                // Moving left: ObjHitWallLeft
                // moveq #-8,d3
                TerrainCheckResult result = ObjectTerrainUtils.checkLeftWallDist(
                        currentX - WALL_SENSOR_OFFSET, currentY);
                // tst.w d1 / bmi.w DeleteObject
                if (result.foundSurface() && result.distance() < 0) {
                    setDestroyed(true);
                    return;
                }
            } else {
                // Moving right: ObjHitWallRight
                // moveq #8,d3
                TerrainCheckResult result = ObjectTerrainUtils.checkRightWallDist(
                        currentX + WALL_SENSOR_OFFSET, currentY);
                // tst.w d1 / bmi.w DeleteObject
                if (result.foundSurface() && result.distance() < 0) {
                    setDestroyed(true);
                    return;
                }
            }
        }

        // ================================================================
        // Rendering
        // ================================================================

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.LZ_GARGOYLE);
            if (renderer == null || !renderer.isReady()) {
                return;
            }

            // Fireball has no directional flip in its rendering; it uses the same frame
            // regardless of direction.
            // The ROM copies obRender from the head, but only bit 2 (screen coords)
            // is relevant. H-flip for the fireball sprite is not applied.
            //
            // ROM parity: Gar_FireBall sets obGfx to make_art_tile(..., 0, 0), so
            // fireballs must render with palette line 0 (not the head's palette line 2).
            renderer.drawFrameIndex(currentFrame, currentX, currentY, false, false, 0);
        }

        @Override
        public ObjectSpawn getSpawn() {
            return new ObjectSpawn(currentX, currentY, 0x62, 0, 0, false, 0);
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
            return RenderPriority.clamp(FIREBALL_PRIORITY);
        }

        @Override
        public boolean isPersistent() {
            if (isDestroyed()) {
                return false;
            }
            // Fireball is dynamically spawned; persist while on screen
            return isOnScreen(FIREBALL_ACT_WIDTH * 4);
        }

        // ================================================================
        // Touch Response (collision with player)
        // ================================================================

        @Override
        public int getCollisionFlags() {
            // $98 = HURT ($80) | size index $18
            return COLLISION_FLAGS;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        // ================================================================
        // Debug Rendering
        // ================================================================

        @Override
        public void appendDebugRenderCommands(DebugRenderContext ctx) {
            ctx.drawRect(currentX, currentY, FIREBALL_RADIUS, FIREBALL_RADIUS,
                    1.0f, 0.55f, 0.0f);
            ctx.drawWorldLabel(currentX, currentY, -1,
                    String.format("GarFire vx=%d", velX),
                    DEBUG_COLOR);
        }
    }
}
