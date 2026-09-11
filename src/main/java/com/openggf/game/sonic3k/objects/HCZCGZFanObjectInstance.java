package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugColor;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0x38 — HCZ/CGZ Fan (Sonic 3 &amp; Knuckles, Hydrocity Zone / Chrome Gadget Zone).
 * <p>
 * A vertical fan that blows the player upward with distance-dependent force.
 * Supports several modes controlled by the subtype byte:
 * <ul>
 *   <li>Timer-toggle mode (default): alternates between 3s blowing / 2s idle</li>
 *   <li>Always-on mode (bit 4): continuously blowing</li>
 *   <li>Trigger-controlled mode (bit 5): waits for Level_trigger_array[0], then becomes always-on</li>
 *   <li>Underwater mode (bit 6): spawns bubble children, uses special player animation</li>
 *   <li>Platform mode (bit 7): spawns a sliding solid block child</li>
 * </ul>
 * <p>
 * ROM references: Obj_HCZCGZFan (sonic3k.asm:65309-65520).
 * <p>
 * Subtype encoding:
 * <pre>
 * Bits 0-3: Fan strength (lift range = (value + 8) * 16 pixels)
 * Bit  4:   Always-on fan (continuous blowing)
 * Bit  5:   Trigger-controlled (waits for Level_trigger_array[0])
 * Bit  6:   Underwater fan (spawns bubbles, uses anim $F for player)
 * Bit  7:   Has sliding platform child
 * Bits 4-5: (When bit 7 set) Platform slide distance: ($00/$40/$80/$C0)
 * </pre>
 */
public class HCZCGZFanObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    // ===== Subtype bit masks =====
    private static final int MASK_STRENGTH = 0x0F;
    private static final int BIT_ALWAYS_ON = 0x10;     // bit 4
    private static final int BIT_TRIGGER = 0x20;        // bit 5
    private static final int BIT_UNDERWATER = 0x40;     // bit 6
    private static final int BIT_PLATFORM = 0x80;       // bit 7
    private static final int MASK_SLIDE_DIST = 0x30;    // bits 4-5 for platform slide distance

    // ===== Priorities (from disassembly) =====
    // ROM: move.w #$200,priority(a0) → fan
    private static final int FAN_PRIORITY = 4;
    // ROM: move.w #$280,priority(a0) → platform
    private static final int PLATFORM_PRIORITY = 5;

    // ===== Dimensions =====
    // ROM: move.b #$10,width_pixels(a0) / move.b #$C,height_pixels(a0)
    private static final int FAN_HALF_WIDTH = 0x10;
    private static final int FAN_HALF_HEIGHT = 0x0C;

    // ===== Timer durations (in frames) =====
    // ROM: move.w #2*60,$30(a0) — 120 frames when turning off
    private static final int IDLE_DURATION = 2 * 60;    // 120 frames
    // The ROM stores #120 after the first negative countdown tick; because the
    // engine's Java update observes the post-setup state in the same call, keep
    // the terminal zero tick idle before the fan resumes player interaction.
    private static final int ENGINE_IDLE_COUNTDOWN_RESET = IDLE_DURATION + 1;
    // ROM: move.w #3*60,$30(a0) — 180 frames when turning on
    private static final int ACTIVE_DURATION = 3 * 60;  // 180 frames

    // ===== Fan animation constants =====
    private static final int FAN_FRAME_COUNT = 5;  // frames 0-4
    // ROM: addi.w #$2A,$34(a0) — speed ramp increment
    private static final int RAMP_INCREMENT = 0x2A;
    // ROM: cmpi.w #$400,$34(a0) — speed ramp max
    private static final int RAMP_MAX = 0x400;

    // ===== Player interaction constants =====
    // ROM: addi.w #$18,d0 / cmpi.w #$30,d0 — X range 48px wide
    private static final int FAN_X_OFFSET = 0x18;
    private static final int FAN_X_RANGE = 0x30;

    // ===== Bubble spawn constants =====
    // ROM: andi.b #3,d0 — spawn every 4 frames
    private static final int BUBBLE_SPAWN_INTERVAL_MASK = 0x03;
    // ROM: move.w #-$800,y_vel(a1)
    private static final int BUBBLE_Y_VELOCITY = -0x800;

    // ===== Sound effect interval =====
    // ROM: andi.b #$F,d0 — every 16 frames
    private static final int SFX_INTERVAL_MASK = 0x0F;

    // ===== Flip animation constants (applied to player) =====
    // ROM: move.b #1,flip_angle(a1)
    private static final int PLAYER_FLIP_ANGLE = 1;
    // ROM: move.b #$7F,flips_remaining(a1)
    private static final int PLAYER_FLIPS_REMAINING = 0x7F;
    // ROM: move.b #8,flip_speed(a1)
    private static final int PLAYER_FLIP_SPEED = 8;

    // ===== Configuration (from subtype) =====
    private int innerRange;            // $36(a0): inner detection range
    private int outerRange;            // $38(a0): outer detection range
    private boolean isUnderwater;      // subtype bit 6
    private int subtype;               // mutable: trigger mode clears bit 5, sets bit 4

    // ===== Instance state =====
    private int x;                     // current X position (may be updated by platform)
    private int y;                     // Y position (fixed)
    private int originalX;             // $40(a0): stored for on-screen test

    // Timer-toggle state
    private int timer;                 // $30(a0): countdown timer
    private int toggleFlag;            // $32(a0): 0 = fan active, 1 = fan idle
    private int speedRamp;             // $34(a0): animation speed ramp (0..0x400)
    private int animFrameTimer;        // anim_frame_timer(a0)
    private int mappingFrame;          // mapping_frame(a0)
    private boolean latchedOn;         // $42(a0): set by platform child when player is above

    // Platform child reference
    private FanPlatformChild platformChild;

    public HCZCGZFanObjectInstance(ObjectSpawn spawn) {
        this(spawn, true);
    }

    private HCZCGZFanObjectInstance(ObjectSpawn spawn, boolean spawnPlatform) {
        super(spawn, "HCZCGZFan");
        this.subtype = spawn.subtype();
        this.originalX = spawn.x();
        this.x = spawn.x();
        this.y = spawn.y();

        // ROM: andi.w #$F,d0 / addq.w #8,d0 / lsl.w #4,d0
        int strength = (subtype & MASK_STRENGTH);
        int base = (strength + 8) << 4;  // (value + 8) * 16
        this.innerRange = base;
        // ROM: addi.w #$30,d0
        this.outerRange = base + 0x30;

        this.isUnderwater = (subtype & BIT_UNDERWATER) != 0;

        // Initialize timer state
        this.timer = 0;
        this.toggleFlag = 0;  // 0 = fan active initially
        this.speedRamp = 0;
        this.animFrameTimer = 0;
        this.mappingFrame = 0;
        this.latchedOn = false;

        // ROM: btst #7,d0 / beq.s loc_30602
        if (spawnPlatform && (subtype & BIT_PLATFORM) != 0) {
            spawnPlatformChild();
        }
    }

    @Override
    public HCZCGZFanObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HCZCGZFanObjectInstance(ctx.spawn(), false);
    }

    /**
     * Spawns the sliding platform child when subtype bit 7 is set.
     * <p>
     * ROM: AllocateObjectAfterCurrent (sonic3k.asm:65315-65336).
     * The original object (a0) becomes the platform, the child (a1) becomes the fan.
     * In our implementation, this object IS the fan, and we spawn the platform as a child.
     */
    private void spawnPlatformChild() {
        // ROM: andi.w #$30,d0 / add.w d0,d0 — slide distance from bits 4-5
        int slideDist = (subtype & MASK_SLIDE_DIST) * 2;
        boolean facingLeft = (spawn.renderFlags() & 0x01) != 0;

        // ROM: addi.w #$1C,y_pos(a0) — platform is 28 pixels below fan spawn Y
        int platformY = y + 0x1C;

        platformChild = spawnChild(() -> new FanPlatformChild(
                new ObjectSpawn(originalX, platformY, Sonic3kObjectIds.HCZ_CGZ_FAN, 0,
                        spawn.renderFlags(), false, 0),
                this, slideDist, facingLeft));

        // ROM: bclr #5,subtype(a1) / bset #4,subtype(a1)
        // When platform is present, fan child is forced to always-on mode
        subtype &= ~BIT_TRIGGER;
        subtype |= BIT_ALWAYS_ON;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (playerEntity instanceof AbstractPlayableSprite)
                ? (AbstractPlayableSprite) playerEntity : null;

        // ROM: btst #5,d0 — trigger-controlled fan (sonic3k.asm:65358-65366)
        if ((subtype & BIT_TRIGGER) != 0) {
            if (!Sonic3kLevelTriggerManager.testAny(0)) {
                // Trigger not active — skip to on-screen test only
                return;
            }
            // ROM: bclr #5,subtype(a0) / bset #4,subtype(a0)
            subtype &= ~BIT_TRIGGER;
            subtype |= BIT_ALWAYS_ON;
        }

        // ROM platform mode makes the original slot the sliding platform and
        // updates the fan child after the platform has written the fan x_pos.
        if (platformChild != null) {
            return;
        }

        updateFanRoutine(vIntRunCount, player);
    }

    private void updateFanRoutine(int vIntRunCount, AbstractPlayableSprite player) {
        // ROM: tst.b $42(a0) — latched-on flag (sonic3k.asm:65368-65370)
        if (latchedOn) {
            updateRampUp(vIntRunCount, player);
            return;
        }

        // ROM: btst #4,subtype(a0) — always-on fan (sonic3k.asm:65372-65374)
        if ((subtype & BIT_ALWAYS_ON) != 0) {
            updatePlayerInteraction(vIntRunCount, player);
            return;
        }

        // Timer-based toggle mode (sonic3k.asm:65376-65392)
        timer--;
        if (timer < 0) {
            speedRamp = 0;
            timer = ENGINE_IDLE_COUNTDOWN_RESET;
            toggleFlag ^= 1;  // ROM: bchg #0,$32(a0)
            if (toggleFlag == 0) {
                // Fan just turned on
                timer = ACTIVE_DURATION;
            }
        }

        // ROM: tst.b $32(a0) / beq.w loc_306C2
        if (toggleFlag == 0) {
            // Fan is active — do player interaction
            updatePlayerInteraction(vIntRunCount, player);
        } else {
            // Fan is ramping down/idle
            updateRampUp(vIntRunCount, player);
        }
    }

    /**
     * Ramp-up/ramp-down animation when fan is decelerating or latched.
     * <p>
     * ROM: loc_306A2 (sonic3k.asm:65384-65392).
     * Gradually increases frame delay as speedRamp increases, creating a slowing effect.
     */
    private void updateRampUp(int vIntRunCount, AbstractPlayableSprite player) {
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return;
        }
        // ROM: cmpi.w #$400,$34(a0) / bhs.w loc_30774
        if (speedRamp >= RAMP_MAX) {
            return;
        }
        // ROM: addi.w #$2A,$34(a0)
        speedRamp += RAMP_INCREMENT;
        // ROM: move.b $34(a0),anim_frame_timer(a0) — high byte as frame delay
        animFrameTimer = (speedRamp >> 8) & 0xFF;

        advanceFanFrame();
    }

    /**
     * Active fan: processes player interaction and fast animation.
     * <p>
     * ROM: loc_306C2 (sonic3k.asm:65394-65447).
     */
    private void updatePlayerInteraction(int vIntRunCount, AbstractPlayableSprite player) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        if (player != null && !participants.contains(player)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(player);
            withUpdatePlayer.addAll(participants);
            participants = withUpdatePlayer;
        }
        for (PlayableEntity participant : participants) {
            if (participant instanceof AbstractPlayableSprite sprite) {
                applyFanPush(sprite, vIntRunCount);
            }
        }

        // Fast animation (sonic3k.asm:65398-65407)
        animFrameTimer--;
        if (animFrameTimer < 0) {
            // ROM: move.b #0,anim_frame_timer(a0)
            animFrameTimer = 0;
            advanceFanFrame();
        }

        // Sound effect (sonic3k.asm:65409-65417)
        // ROM: tst.b render_flags(a0) / bpl.s — only if on-screen
        if (isOnScreen()) {
            // ROM: move.b (Level_frame_counter+1).w,d0 / addq.b #1,d0 / andi.b #$F,d0
            int fc = (vIntRunCount & 0xFF) + 1;
            if ((fc & SFX_INTERVAL_MASK) == 0) {
                try {
                    services().playSfx(Sonic3kSfx.FAN_SMALL.id);
                } catch (Exception e) {
                    // Audio not available
                }
            }
        }

        // Underwater bubble spawning (sonic3k.asm:65420-65447)
        if (isUnderwater) {
            spawnBubbles(vIntRunCount);
        }
    }

    /**
     * Fan push physics subroutine applied to a single player.
     * <p>
     * ROM: loc_3077E (sonic3k.asm:65453-65520).
     * Distance-dependent upward force with oscillation wobble.
     */
    private void applyFanPush(AbstractPlayableSprite player, int vIntRunCount) {
        // ROM: cmpi.b #4,routine(a1) — player dead/hurt?
        if (player.isHurt()) {
            return;
        }

        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // X range check (sonic3k.asm:65459-65466)
        // ROM: move.w x_pos(a1),d0 / sub.w x_pos(a0),d0 / addi.w #$18,d0 / cmpi.w #$30,d0
        int dx = playerX - x + FAN_X_OFFSET;
        if (dx < 0 || dx >= FAN_X_RANGE) {
            return;
        }

        // Y range check with oscillation (sonic3k.asm:65468-65480)
        // ROM: move.b (Oscillating_table+$16).w,d1 — offset $14 in engine (minus 2 for control word)
        int oscillation = OscillationManager.getByte(0x14) & 0xFF;
        int dy = playerY + oscillation + innerRange - y;
        if (dy < 0) {
            return;  // below fan
        }
        if (dy >= outerRange) {
            return;  // too far above
        }

        // Object control check (sonic3k.asm:65482-65484)
        if (player.isObjectControlled()) {
            // ROM: move.w #1,ground_vel(a1)
            player.setGSpeed((short) 1);
            return;
        }

        // Fan lift force calculation (sonic3k.asm:65486-65498)
        // ROM: sub.w $36(a0),d1 / bcs.s loc_307C6
        int force = dy - innerRange;
        if (force >= 0) {
            // ROM: not.w d1 / add.w d1,d1 — invert and double
            force = (~force & 0xFFFF) << 1;
            force &= 0xFFFF;
        }
        // ROM: add.w $36(a0),d1 / neg.w d1 / asr.w #6,d1
        force = (force + innerRange) & 0xFFFF;
        force = (-force) & 0xFFFF;
        int push = ((short) force) >> 6;  // asr.w #6

        // ROM: add.w d1,y_pos(a1) — directly adjust the native Y word.
        NativePositionOps.addYPosPreserveSubpixel(player, push);

        // Player state changes (sonic3k.asm:65500-65510)
        // ROM: bset #Status_InAir,status(a1)
        player.setAir(true);
        // ROM: bclr #Status_RollJump,status(a1)
        player.setRollingJump(false);
        // ROM: move.w #0,y_vel(a1)
        player.setYSpeed((short) 0);
        // ROM: move.b #0,double_jump_flag(a1) — cancel shield abilities
        player.setDoubleJumpFlag(0);
        // ROM: move.b #0,jumping(a1)
        player.setJumping(false);

        // ROM: btst #6,subtype(a0) — underwater fan special behavior
        if (isUnderwater) {
            // ROM: move.w #1,ground_vel(a1)
            player.setGSpeed((short) 1);
            // ROM: move.b #$F,anim(a1) — special underwater animation
            player.setAnimationId(0x0F);
            return;
        }

        // Normal fan — flip animation (sonic3k.asm:65512-65520)
        // ROM: move.w #1,ground_vel(a1)
        player.setGSpeed((short) 1);
        // ROM: tst.b flip_angle(a1) / bne.s locret_3081C
        if (player.getFlipAngle() == 0) {
            // ROM: move.b #1,flip_angle(a1)
            player.setFlipAngle(PLAYER_FLIP_ANGLE);
            // ROM: move.b #0,anim(a1)
            player.setAnimationId(0);
            // ROM: move.b #$7F,flips_remaining(a1)
            player.setFlipsRemaining(PLAYER_FLIPS_REMAINING);
            // ROM: move.b #8,flip_speed(a1)
            player.setFlipSpeed(PLAYER_FLIP_SPEED);
        }
    }

    /**
     * Spawns bubble children when underwater (subtype bit 6 set).
     * <p>
     * ROM: loc_3070C (sonic3k.asm:65420-65447).
     * Spawns a bubble every 4 frames that rises until it reaches the water surface.
     */
    private void spawnBubbles(int vIntRunCount) {
        // ROM: andi.b #3,d0 / bne.s — every 4 frames
        if ((vIntRunCount & BUBBLE_SPAWN_INTERVAL_MASK) != 0) {
            return;
        }
        // ROM: jsr (AllocateObject).l
        try {
            int bubbleX = x + services().rng().nextInt(16) - 8;  // ROM: random X offset -8..+7
            // ROM: Obj_HCZCGZFan uses AllocateObject here, not
            // AllocateObjectAfterCurrent. Bubbles therefore take the lowest
            // free dynamic SST slot, which can be below their fan parent.
            spawnFreeChild(() -> new FanBubbleChild(
                    new ObjectSpawn(bubbleX, y, Sonic3kObjectIds.HCZ_CGZ_FAN, 0, 0, false, 0)));
        } catch (Exception e) {
            // Object allocation failed
        }
    }

    /**
     * Advances the fan animation frame (cycles 0-4).
     * <p>
     * ROM: loc_306E0 (sonic3k.asm:65403-65407).
     */
    private void advanceFanFrame() {
        mappingFrame++;
        if (mappingFrame >= FAN_FRAME_COUNT) {
            mappingFrame = 0;
        }
    }

    // Called by FanPlatformChild to update this fan's X position
    void setFanX(int newX) {
        this.x = newX;
    }

    // Called by FanPlatformChild to set/clear latch state
    void setLatchedOn(boolean latched) {
        if (latched && !this.latchedOn) {
            // ROM: move.w #0,$34(a1) — reset speed ramp on latch
            this.speedRamp = 0;
        }
        if (!latched && this.latchedOn) {
            // ROM: move.b #0,anim_frame_timer(a1) — reset frame timer on unlatch
            this.animFrameTimer = 0;
        }
        this.latchedOn = latched;
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer r = getRenderer(Sonic3kObjectArtKeys.HCZ_FAN);
        if (r == null) return;
        r.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    @Override
    public int getX() { return x; }

    @Override
    public int getY() { return y; }

    @Override
    public int getPriorityBucket() { return RenderPriority.clamp(FAN_PRIORITY); }

    @Override
    public ObjectSpawn getSpawn() {
        // Use originalX for on-screen test (ROM: move.w $40(a0),d0)
        return buildSpawnAt(originalX, y);
    }

    @Override
    public int getOutOfRangeReferenceX() {
        // ROM: loc_30774 feeds $40(a0), not the current sliding fan x_pos, to
        // Sprite_OnScreen_Test2.
        return originalX;
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) return;
        ctx.drawCross(x, y, 4, 0.2f, 0.8f, 1f);
        // Draw fan detection range
        ctx.drawRect(x, y - innerRange, FAN_X_OFFSET, innerRange, 0.2f, 0.8f, 1f);
        String state = latchedOn ? "LATCH" :
                (subtype & BIT_ALWAYS_ON) != 0 ? "ON" :
                        toggleFlag == 0 ? "BLOW" : "IDLE";
        ctx.drawWorldLabel(x, y + 12, 0, state, DebugColor.CYAN);
    }

    // ===== Inner class: Sliding Platform Child =====

    /**
     * Solid sliding platform spawned when subtype bit 7 is set.
     * Extends/retracts based on player proximity to the fan.
     * <p>
     * ROM: loc_30850 (sonic3k.asm:65523-65580).
     * Uses Map_HCZWaterRushBlock mappings, ArtTile_HCZMisc+$A.
     */
    static class FanPlatformChild extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

        /**
         * Word 0 of this object's S3K SST holds its live ROM code pointer.
         * ROM {@code Obj_HCZCGZFan} is installed from the S3K object pointer table at
         * {@code $00030580} (table read from the user-supplied ROM; the
         * label is defined at docs/skdisasm/sonic3k.asm:65314).
         * Its whole code block lies in one bank, so the HIGH word that
         * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
         * on the next off-screen on-object frame is {@code $0003}
         * (docs/skdisasm/sonic3k.asm:26816-26843).
         */
        @Override
        public int romObjectCodePointerHighWord() {
            return 0x0003;
        }


        // ROM: move.b #$10,width_pixels(a0) / move.b #$10,height_pixels(a0)
        private static final int HALF_WIDTH = 0x10;
        private static final int HALF_HEIGHT = 0x10;

        // ROM: addq.w #8,$30(a0) — slide rate (8 pixels per frame)
        private static final int SLIDE_RATE = 8;

        // Player detection thresholds
        // ROM: cmpi.w #$20,d0 — close-below threshold
        private static final int BELOW_THRESHOLD = 0x20;
        // ROM: cmpi.w #-$30,d0 — above threshold
        private static final int ABOVE_THRESHOLD = -0x30;
        private final HCZCGZFanObjectInstance fanParent;
        private int maxSlideDistance;         // $3A(a0): max slide offset
        private boolean facingLeft;
        private int originalX;                // $40(a0): base X position
        private int y;                        // Y position (fixed, platform doesn't move vertically)

        private int x;
        private int slideOffset;             // $30(a0): current slide offset

        FanPlatformChild(ObjectSpawn spawn, HCZCGZFanObjectInstance fanParent,
                         int maxSlideDistance, boolean facingLeft) {
            super(spawn, "HCZCGZFanPlatform");
            this.fanParent = fanParent;
            this.maxSlideDistance = maxSlideDistance;
            this.facingLeft = facingLeft;
            this.originalX = spawn.x();
            this.x = spawn.x();
            this.y = spawn.y();
            this.slideOffset = 0;
        }

        @Override
        public FanPlatformChild recreateForRewind(RewindRecreateContext ctx) {
            HCZCGZFanObjectInstance restoredParent =
                    RewindRecreateObjectLinks.nearestLiveObject(ctx, HCZCGZFanObjectInstance.class);
            return new FanPlatformChild(ctx.spawn(), restoredParent, 0, false);
        }

        @Override
        public SolidObjectParams getSolidParams() {
            // ROM: move.b width_pixels(a0),d1 / addi.w #$B,d1
            //      move.b height_pixels(a0),d2 / move.w d2,d3 / addq.w #1,d3
            int d1 = HALF_WIDTH + 0xB;
            int d2 = HALF_HEIGHT;
            int d3 = HALF_HEIGHT + 1;
            return SolidObjectParams.of(d1, d2, d3);
        }

        @Override
        public void onSolidContact(PlayableEntity player, SolidContact contact, int fc) {
            // No special behavior on contact
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (playerEntity instanceof AbstractPlayableSprite)
                    ? (AbstractPlayableSprite) playerEntity : null;

            if (player != null) {
                // ROM: move.w (Player_1+y_pos).w,d0 / sub.w y_pos(a0),d0
                int playerRelY = player.getCentreY() - y;

                if (playerRelY < 0) {
                    // Player above platform
                    if (playerRelY < ABOVE_THRESHOLD) {
                        // Far above — unlatch fan (sonic3k.asm:65549-65556)
                        if (fanParent.latchedOn) {
                            fanParent.setLatchedOn(false);
                            try {
                                services().playSfx(Sonic3kSfx.FAN_LATCH.id);
                            } catch (Exception e) {
                                // Audio not available
                            }
                        }
                        retractPlatform();
                    }
                } else if (playerRelY >= BELOW_THRESHOLD) {
                    // Player below platform — latch fan ON (sonic3k.asm:65530-65540)
                    if (!fanParent.latchedOn) {
                        fanParent.setLatchedOn(true);
                        try {
                            services().playSfx(Sonic3kSfx.FAN_LATCH.id);
                        } catch (Exception e) {
                            // Audio not available
                        }
                    }
                    extendPlatform();
                } else {
                    // Player close but within normal range — no slide change
                }
            }

            // Update platform position (sonic3k.asm:65558-65568)
            int offset = slideOffset;
            // ROM: btst #0,status(a0) — facing direction
            if (facingLeft) {
                offset = -offset;
            }
            // ROM: add.w $40(a0),d0
            x = originalX + offset;
            // ROM: move.w d0,x_pos(a1) — set fan X too
            fanParent.setFanX(x);

            checkpointAll();
            fanParent.updateFanRoutine(vIntRunCount, player);
        }

        private void extendPlatform() {
            // ROM: cmp.w $3A(a0),d1 / beq.s — already at max
            if (slideOffset >= maxSlideDistance) return;
            // ROM: addq.w #8,$30(a0)
            slideOffset = Math.min(slideOffset + SLIDE_RATE, maxSlideDistance);
        }

        private void retractPlatform() {
            // ROM: tst.w $30(a0) / beq.s — already retracted
            if (slideOffset <= 0) return;
            // ROM: subq.w #8,$30(a0)
            slideOffset = Math.max(slideOffset - SLIDE_RATE, 0);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer r = getRenderer(Sonic3kObjectArtKeys.HCZ_WATER_RUSH_BLOCK);
            if (r == null) return;
            // ROM: mapping_frame = 0 for platform
            r.drawFrameIndex(0, x, y, facingLeft, false);
        }

        @Override
        public int getX() { return x; }

        @Override
        public int getY() { return y; }

        @Override
        public int getPriorityBucket() { return RenderPriority.clamp(PLATFORM_PRIORITY); }

        @Override
        public SolidExecutionMode solidExecutionMode() {
            return SolidExecutionMode.MANUAL_CHECKPOINT;
        }

        @Override
        public int getOutOfRangeReferenceX() {
            // ROM: loc_308B8 feeds $40(a0), not the current platform x_pos, to
            // Sprite_OnScreen_Test2.
            return originalX;
        }

        @Override
        public void appendDebugRenderCommands(DebugRenderContext ctx) {
            if (ctx == null) return;
            ctx.drawRect(x, y, HALF_WIDTH, HALF_HEIGHT, 0f, 1f, 0.5f);
            ctx.drawWorldLabel(x, y - HALF_HEIGHT - 8, 0,
                    "slide=" + slideOffset + "/" + maxSlideDistance, DebugColor.GREEN);
        }
    }

    // ===== Inner class: Bubble Child =====

    /**
     * Small bubble spawned by underwater fan (subtype bit 6).
     * Rises upward until it reaches the water surface.
     * <p>
     * ROM: loc_30834 (sonic3k.asm:65511-65520).
     * Uses Map_Bubbler mappings, ArtTile_Bubbles ($045C), palette 0.
     */
    static class FanBubbleChild extends AbstractObjectInstance implements RewindRecreatable {

        // ROM: move.w #$300,priority(a1)
        private static final int PRIORITY = 6;

        // Safety: max lifetime in frames before forced cleanup (prevents leaks).
        // At -8 pixels/frame, a bubble traverses ~480px in 60 frames — more than
        // enough to reach any water surface in HCZ.
        private static final int MAX_LIFETIME = 120;

        private int x;
        private int y;
        private int yVelocity;          // y_vel = -$800
        private int lifetime;

        FanBubbleChild(ObjectSpawn spawn) {
            super(spawn, "HCZFanBubble");
            this.x = spawn.x();
            this.y = spawn.y();
            this.yVelocity = BUBBLE_Y_VELOCITY;
            this.lifetime = 0;
        }

        @Override
        public FanBubbleChild recreateForRewind(RewindRecreateContext ctx) {
            return new FanBubbleChild(ctx.spawn());
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            // Safety: force-destroy after max lifetime to prevent object leaks
            lifetime++;
            if (lifetime >= MAX_LIFETIME) {
                setDestroyed(true);
                return;
            }

            // ROM checks water level BEFORE moving (sonic3k.asm:65511-65515)
            // ROM: cmp.w (Water_level).w,d0 / bhs.s — delete when above water level
            try {
                WaterSystem water = services().waterSystem();
                if (water != null) {
                    int zoneId = services().romZoneId();
                    int actId = services().currentAct();
                    int waterLevel = water.getWaterLevelY(zoneId, actId);
                    if (waterLevel > 0 && y <= waterLevel) {
                        // Above water — destroy (ROM: jmp Delete_Current_Sprite)
                        setDestroyed(true);
                        return;
                    }
                }
            } catch (Exception e) {
                // Water system not available
            }

            // Off-screen cleanup fallback
            if (!isOnScreen(64)) {
                setDestroyed(true);
                return;
            }

            // ROM: Obj_HCZCGZFan's bubble routine calls MoveSprite2 twice
            // after the water check. $800 is 8 pixels per call, so the native
            // bubble advances 16 pixels upward per frame.
            y += 2 * (yVelocity >> 8);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // ROM: Map_Bubbler frame 0 = tiny 8×8 bubble, ArtTile_Bubbles, palette 0
            PatternSpriteRenderer r = getRenderer(Sonic3kObjectArtKeys.HCZ_FAN_BUBBLE);
            if (r == null) return;
            r.drawFrameIndex(0, x, y, false, false);
        }

        @Override
        public int getX() { return x; }

        @Override
        public int getY() { return y; }

        @Override
        public int getPriorityBucket() { return RenderPriority.clamp(PRIORITY); }
    }
}
