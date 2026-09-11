package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x55 - MGZ Head Trigger (zone-set S3KL only).
 *
 * <p>ROM: Obj_MGZHeadTrigger (sonic3k.asm:70752-70902).
 * Stone face mounted on a 3-piece column. Watches one side of the head for an
 * approaching player; when detected, the eyes blink and the head spits a
 * rock-spike projectile. Takes 3 hits (ROM {@code collision_property = 3}).
 * On the final hit the face shatters into an explosion and the matching
 * {@link Sonic3kLevelTriggerManager} entry is set so {@code MGZTriggerPlatform}
 * objects sharing the low-nibble index can react.
 *
 * <p>Subtype layout:
 * <ul>
 *   <li>Bits [3:0] — {@code Level_trigger_array} index</li>
 *   <li>Upper bits — unused</li>
 * </ul>
 *
 * <p>Render-flags bit 0 (= ROM {@code status} bit 0) selects which side the head
 * watches and which direction its projectile flies:
 * clear = watches and fires LEFT; set = watches and fires RIGHT.
 *
 * <p>When destroyed, the column (child frame 6) keeps rendering as scenery and
 * the face frame becomes an empty mapping. Re-entering the head's spawn window
 * after level respawn sees the trigger-array flag still set, so the head comes
 * back up already shattered (ROM loc_34364 early-out).
 */
public class MGZHeadTriggerObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, SpawnRewindRecreatable {

    private static final String ART_KEY = Sonic3kObjectArtKeys.MGZ_HEAD_TRIGGER;

    // ROM: priority(a0) = $280
    private static final int PRIORITY_BUCKET = 5;

    // ROM: move.b #$17,collision_flags(a0) — ENEMY category (00), size index 0x17.
    private static final int COLLISION_FLAGS_ACTIVE = 0x17;
    // ROM: move.b #3,collision_property(a0) — 3-hit HP counter.
    private static final int INITIAL_HP = 3;

    // ROM: move.w #60,$32(a0) — recovery delay after a hit.
    private static final int RECOVER_FRAMES = 60;
    // ROM: move.w #$C0,$30(a0) — watch-window extent (x and y both $C0).
    private static final int WATCH_X_EXTENT = 0xC0;
    // ROM: addi.w #$80,d0 on Y (cmpi.w #$C0,d0). Player may be up to $80 above
    // and $40 below the head centre.
    private static final int WATCH_Y_BIAS = 0x80;
    private static final int WATCH_Y_EXTENT = 0xC0;

    // ROM: mapping_frame(a0) init value 2 (closed-eye face).
    private static final int FACE_FRAME_IDLE = 2;
    // ROM: sub2_mapframe init value 6 (3-piece column: capital + shaft + base).
    private static final int BODY_FRAME = 6;
    // ROM: move.b #0,mapping_frame(a0) when the trigger is already spent and again
    // on the final hit. This frame shares art with the projectile, but it does not
    // look the same on the head because the head keeps make_art_tile(...,1,1)
    // while the projectile masks its copied art_tile with drawing_mask first.
    private static final int FACE_FRAME_TRIGGERED = 0;
    // Frame 7 is the empty mapping used by anim 2's stone-chip flicker child.
    private static final int FACE_FRAME_EMPTY = 7;

    // ROM: projectile spawn offset from head centre (before flip adjustment).
    // addi.w #$10,x_pos(a1) / addi.w #$20,y_pos(a1).
    private static final int PROJECTILE_X_OFFSET = 0x10;
    private static final int PROJECTILE_Y_OFFSET = 0x20;
    // ROM: subi.w #$20,x_pos(a1) after neg on the flipped branch.
    private static final int PROJECTILE_X_FLIP_SHIFT = 0x20;
    // ROM: move.w #-$400,x_vel(a1). Signed 8:8 velocity.
    private static final int PROJECTILE_SPEED = 0x400;

    // ===== Animation tables (ROM: Ani_MGZHeadTrigger in Anim - Head Trigger.asm) =====
    // Byte layout: [duration, frame|cmd, frame|cmd, ...]
    // Commands: $FF rewind, $FE n back-up, $FD n set anim, $FC routine+=2, $FB off-screen.
    private static final int[] ANIM_IDLE = {
            0x7F, 2, 0xFF
    };
    // ROM byte_34551: blink+spit cycle. Frame 7 ($FC) fires projectile via routine
    // increment; frames 8-10 return the eyes to closed; frame 11 ($FD) jumps back
    // to the idle animation.
    private static final int[] ANIM_BLINK_SPIT = {
            7, 3, 4, 3, 2, 2, 2, 1, 0xFC, 1, 1, 1, 0xFD, 0
    };
    private static final int[][] ANIM_TABLE = { ANIM_IDLE, ANIM_BLINK_SPIT };

    private static final int ANIM_IDLE_ID = 0;
    private static final int ANIM_BLINK_ID = 1;

    // Anim cmd nibbles (negative byte values).
    private static final int CMD_FF_REWIND = 0xFF;
    private static final int CMD_FE_BACK = 0xFE;
    private static final int CMD_FD_SET_ANIM = 0xFD;
    private static final int CMD_FC_INCR_ROUTINE = 0xFC;
    private static final int CMD_FB_OFFSCREEN = 0xFB;

    // ===== Per-instance state =====

    private int triggerIndex;
    /**
     * ROM status(a0) bit 0 (mirrored in ObjectSpawn.renderFlags bit 0).
     * When false, head watches the left side and fires left (ROM: $30 = $C0).
     * When true, head watches the right side and fires right (ROM: $30 = 0).
     */
    private boolean flipped;

    // ROM: $34(a0). Permanent-destruction flag; survives respawn via Level_trigger_array.
    private boolean triggered;

    // ROM: collision_property(a0).
    private int hp;
    // ROM: $32(a0). Recovery cooldown: while > 0, collision_flags stays at 0.
    private int recoverTimer;
    // Shared touch handling clears collision immediately; the object reacts on its next update.
    private boolean hitPending;

    // ROM: mapping_frame(a0).
    private int mappingFrame;

    // ROM: anim / prev_anim / anim_frame / anim_frame_timer.
    private int animId;
    private int prevAnimId = -1;
    private int animFrameIndex;
    private int animFrameTimer;

    // ROM: routine(a0). Only non-zero after the $FC command fires; we clear it
    // the same frame we emit the projectile.
    private int routine;

    public MGZHeadTriggerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MGZHeadTrigger");

        // ROM: move.b subtype(a0),d0 / andi.w #$F,d0 -> Level_trigger_array index.
        this.triggerIndex = spawn.subtype() & 0x0F;

        // ROM: btst #0,status(a0); bne.s loc_34364. Note that our spawn.renderFlags()
        // carries the layout's status byte bits 0-1 (flip).
        this.flipped = (spawn.renderFlags() & 0x01) != 0;

        this.hp = INITIAL_HP;
        this.recoverTimer = 0;
        this.mappingFrame = FACE_FRAME_IDLE;
        this.animId = ANIM_IDLE_ID;
        this.routine = 0;
        this.hitPending = false;

        if (Sonic3kLevelTriggerManager.testAny(triggerIndex)) {
            this.triggered = true;
            this.mappingFrame = FACE_FRAME_TRIGGERED;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        if (triggered) {
            // ROM loc_3438E: tst.b $34(a0); bne.w loc_34512. Head is inert; the
            // column (child sprite) still renders as scenery.
            return;
        }

        // ROM loc_3438E: proximity check that arms the blink animation only when
        // the current anim is the idle loop.
        if (animId == ANIM_IDLE_ID && isPlayerInWatchWindow(playerEntity)) {
            startBlinkSpitCycle();
        }

        if (hitPending) {
            processPendingHit();
        } else {
            // ROM's collision recovery word at $32 is owned by loc_343C6 and
            // keeps counting while Animate_Sprite advances independently.
            if (recoverTimer > 0) {
                recoverTimer--;
            }
        }

        // ROM loc_3447C: animate, then react to the $FC routine bump.
        advanceAnimation();
        if (routine != 0) {
            // ROM: clr.b routine(a0); jsr (AllocateObjectAfterCurrent).l ...
            routine = 0;
            fireProjectile();
        }
    }

    /** ROM: lines 70788-70800. */
    private boolean isPlayerInWatchWindow(PlayableEntity player) {
        if (player == null) {
            return false;
        }
        int px = player.getCentreX();
        int py = player.getCentreY();
        // ROM: (player_x - obj_x + $30) < $C0 unsigned.
        // $30(a0) = $C0 when not flipped, 0 when flipped (ROM loc_34364).
        int watchXBias = flipped ? 0 : WATCH_X_EXTENT;
        int dx = (px - spawn.x() + watchXBias) & 0xFFFF;
        if (dx >= WATCH_X_EXTENT) {
            return false;
        }
        int dy = (py - spawn.y() + WATCH_Y_BIAS) & 0xFFFF;
        return dy < WATCH_Y_EXTENT;
    }

    /**
     * ROM Animate_Sprite (sonic3k.asm:36157) replayed against {@link #ANIM_TABLE}.
     * Handles commands $FF/$FE/$FD/$FC/$FB as the disassembly routine does.
     */
    private void advanceAnimation() {
        if (animId != prevAnimId) {
            prevAnimId = animId;
            animFrameIndex = 0;
            animFrameTimer = 0;
        }

        // ROM: subq.b #1,anim_frame_timer(a0); bcc.s locret
        // The decrement uses bcc (carry-clear) after sub, which on 68k means the
        // timer was non-zero before the sub. We use pre-decrement equivalent.
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return;
        }

        int[] data = ANIM_TABLE[animId];
        // ROM: move.b (a1),anim_frame_timer — reset to duration byte.
        animFrameTimer = data[0];
        int entry = data[1 + animFrameIndex] & 0xFF;

        if (entry < 0x80) {
            // Normal frame byte: write to mapping_frame, advance index.
            mappingFrame = entry;
            animFrameIndex++;
            return;
        }

        // Commands (negative bytes).
        switch (entry) {
            case CMD_FF_REWIND -> {
                // ROM loc_1AC38: reset frame index, play frame at index 0.
                animFrameIndex = 0;
                mappingFrame = data[1] & 0xFF;
                animFrameIndex++;
            }
            case CMD_FE_BACK -> {
                // ROM loc_1AC48: back up by N frames, play that frame.
                int backup = data[2 + animFrameIndex] & 0xFF;
                animFrameIndex -= backup;
                mappingFrame = data[1 + animFrameIndex] & 0xFF;
                animFrameIndex++;
            }
            case CMD_FD_SET_ANIM -> {
                // ROM loc_1AC5C: anim = next byte. Frame index resets on next tick
                // via the anim-change path at top of advanceAnimation().
                animId = data[2 + animFrameIndex] & 0xFF;
            }
            case CMD_FC_INCR_ROUTINE -> {
                // ROM loc_1AC68: routine += 2, timer cleared, index advances past
                // the $FC byte so the next frame advances to the byte after it.
                routine += 2;
                animFrameTimer = 0;
                animFrameIndex++;
            }
            case CMD_FB_OFFSCREEN -> {
                // ROM loc_1AC7A: move.w #$7F00,x_pos(a0). Not used by the head
                // itself but listed for completeness — the stone-fragment child
                // does rely on it. Head has no movement field so this is a no-op.
            }
            default -> {
                // $80..$FA are not valid commands in S3K anim data; leave as a
                // no-op to stay tolerant of malformed tables.
            }
        }
    }

    /**
     * ROM loc_34480 - loc_3450A.
     * <p>Spawns an {@link MGZHeadTriggerProjectileInstance} just past the face
     * in the direction the head is watching.
     */
    private void fireProjectile() {
        ObjectServices svc = tryServices();
        if (svc == null) {
            return;
        }

        // ROM:
        //   addi.w #$10,x_pos(a1)      ; default +$10
        //   addi.w #$20,y_pos(a1)
        //   move.w #-$400,x_vel(a1)    ; default leftward
        //   btst  #0,status(a0)        ; flipped?
        //   beq.s loc_3450A
        //   neg.w x_vel(a1)            ; mirror to rightward
        //   subi.w #$20,x_pos(a1)      ; +$10 - $20 = -$10
        int px = spawn.x() + PROJECTILE_X_OFFSET;
        int py = spawn.y() + PROJECTILE_Y_OFFSET;
        int xVel = -PROJECTILE_SPEED;
        if (flipped) {
            xVel = -xVel;
            px -= PROJECTILE_X_FLIP_SHIFT;
        }
        final int finalPx = px;
        final int finalPy = py;
        final int finalVel = xVel;

        spawnChild(() -> new MGZHeadTriggerProjectileInstance(finalPx, finalPy, finalVel, flipped));
        // ROM: moveq #signextendB(sfx_LevelProjectile),d0; jsr (Play_SFX).l
        svc.playSfx(Sonic3kSfx.LEVEL_PROJECTILE.id);
    }

    private void processPendingHit() {
        hitPending = false;
        recoverTimer = RECOVER_FRAMES;

        ObjectServices svc = tryServices();
        if (svc == null) {
            return;
        }

        if (hp == 0) {
            onFinalHit(svc);
        } else {
            spawnFragment();
        }

        svc.playSfx(Sonic3kSfx.BOSS_HIT.id);
    }

    private void startBlinkSpitCycle() {
        if (animId != ANIM_BLINK_ID) {
            animId = ANIM_BLINK_ID;
        }
        prevAnimId = -1;
        animFrameIndex = 0;
        animFrameTimer = 0;
    }

    // ===== TouchResponseProvider =====

    @Override
    public int getCollisionFlags() {
        // ROM literal behaviour:
        //   - init sets collision_flags = $17 (sonic3k.asm:70760).
        //   - Touch_Enemy clears it to 0 on hit (sonic3k.asm:20919).
        //   - The 60-frame $32 recovery timer restores it to $17
        //     (sonic3k.asm:70809).
        //   - Permanently cleared once the head is triggered/destroyed.
        if (triggered || hitPending || recoverTimer > 0) {
            return 0;
        }
        return COLLISION_FLAGS_ACTIVE;
    }

    @Override
    public int getCollisionProperty() {
        // ROM collision_property(a0). Used by the collision system to decide
        // between the Touch_Enemy_Part2 velocity-negate bounce (HP>0 before hit)
        // and Touch_KillEnemy (HP==0). onPlayerAttack below decrements HP after
        // the collision system has already captured the pre-hit value.
        return hp;
    }

    // ===== TouchResponseAttackable =====

    @Override
    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        if (triggered || hitPending || recoverTimer > 0) {
            return;
        }
        if (hp > 0) {
            hp--;
        }
        hitPending = true;
    }

    /** ROM loc_343FE - loc_34432 (HP > 0 branch). */
    private void spawnFragment() {
        // Transient animated stone-chip that fires anim 2 (frame 5 / empty / ...)
        // then deletes itself via the $FB off-screen command. ROM copies the
        // head's render_flags (excluding bit 6) so the chip inherits h-flip.
        final boolean chipFlip = flipped;
        spawnChild(() -> new HeadTriggerStoneChipChild(spawn.x(), spawn.y(), chipFlip));
    }

    /** ROM loc_343FE with collision_property == 0 branch. */
    private void onFinalHit(ObjectServices svc) {
        triggered = true;
        mappingFrame = FACE_FRAME_TRIGGERED;

        // ROM: move.b #1,(a3,d0.w) — byte-write to Level_trigger_array[idx].
        // Use bit 0 so MGZTriggerPlatform's testAny check passes identically to
        // a ROM byte-write of 1.
        Sonic3kLevelTriggerManager.setBit(triggerIndex, 0);

        // ROM: move.l #Obj_Explosion,(a1) overwrites the fragment's code pointer.
        ObjectManager om = svc.objectManager();
        ObjectRenderManager rm = svc.renderManager();
        if (om != null && rm != null && rm.getExplosionRenderer() != null) {
            spawnFreeChild(() -> new ExplosionObjectInstance(
                    0x27, spawn.x(), spawn.y(), rm));
        }
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }

        // ROM: mainspr_childsprites = 1, sub2_mapframe = 6.
        // The column (frame 6) always renders — it is level scenery that survives
        // the face being shattered off.
        renderer.drawFrameIndex(BODY_FRAME, spawn.x(), spawn.y(), flipped, false);

        // The face overlay keeps rendering after the trigger completes so the
        // broken-gem state remains visible on top of the pillar body.
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), flipped, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        // Face hit-box: ROM collision size index 0x17 corresponds to a 24x40 box
        // per the shared S3K collision-size table; without an authoritative port
        // we visualise a conservative 24x32 bound centred on the face.
        float r = triggered ? 0.3f : (recoverTimer > 0 ? 0.5f : 1.0f);
        float g = triggered ? 0.3f : 0.4f;
        float b = triggered ? 0.3f : 0.2f;
        ctx.drawRect(spawn.x(), spawn.y(), 12, 16, r, g, b);

        // Watch window.
        int watchLeft = flipped ? spawn.x() : (spawn.x() - WATCH_X_EXTENT);
        int watchRight = flipped ? (spawn.x() + WATCH_X_EXTENT) : spawn.x();
        int watchTop = spawn.y() - WATCH_Y_BIAS;
        int watchBottom = spawn.y() + (WATCH_Y_EXTENT - WATCH_Y_BIAS);
        ctx.drawLine(watchLeft, watchTop, watchRight, watchTop, 0.2f, 0.8f, 0.8f);
        ctx.drawLine(watchLeft, watchBottom, watchRight, watchBottom, 0.2f, 0.8f, 0.8f);
        ctx.drawLine(watchLeft, watchTop, watchLeft, watchBottom, 0.2f, 0.8f, 0.8f);
        ctx.drawLine(watchRight, watchTop, watchRight, watchBottom, 0.2f, 0.8f, 0.8f);
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return spawn.y();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    /**
     * Transient child spawned when the face is struck but not yet destroyed.
     * <p>Plays ROM animation 2 from Ani_MGZHeadTrigger: alternates the firing-
     * frame (5) with an empty frame (7) three times, then $FB moves it offscreen
     * which we translate to {@code setDestroyed(true)}.
     */
    private static final class HeadTriggerStoneChipChild extends AbstractObjectInstance
            implements SpawnCoordinateZeroScalarArgsRewindRecreatable {

        private static final int FRAME_STONE_CHIP = 5;
        private static final int[] ANIM_STONE_CHIP = {1, 5, 7, 5, 7, 5, 7, 0xFB};

        // Non-final so the generic rewind field capturer can reapply the captured
        // values after spawn-coordinate recreate rebuilds this chip with placeholder ctor args
        // (the chip's ObjectSpawn carries x/y but not the h-flip).
        private int originX;
        private int originY;
        private boolean hFlipCopied;
        private int currentFrame;
        private int animFrameIndex;
        private int animFrameTimer;

        private HeadTriggerStoneChipChild() {
            this(0, 0, false);
        }

        private HeadTriggerStoneChipChild(int x, int y, boolean hFlip) {
            super(new ObjectSpawn(x, y, 0xFF, 0, 0, false, 0), "MGZHeadTriggerStoneChip");
            this.originX = x;
            this.originY = y;
            this.hFlipCopied = hFlip;
            this.currentFrame = FRAME_STONE_CHIP;
            this.animFrameIndex = 0;
            this.animFrameTimer = 0;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (isDestroyed()) {
                return;
            }

            animFrameTimer--;
            if (animFrameTimer >= 0) {
                return;
            }

            animFrameTimer = ANIM_STONE_CHIP[0];
            int entry = ANIM_STONE_CHIP[1 + animFrameIndex] & 0xFF;
            if (entry == CMD_FB_OFFSCREEN) {
                setDestroyed(true);
                return;
            }

            currentFrame = entry;
            animFrameIndex++;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer == null) {
                return;
            }
            renderer.drawFrameIndex(currentFrame, originX, originY, hFlipCopied, false);
        }

        @Override
        public int getX() { return originX; }
        @Override
        public int getY() { return originY; }
        @Override
        public int getPriorityBucket() {
            // ROM: priority $200 — one bucket in front of the head.
            return RenderPriority.clamp(PRIORITY_BUCKET - 1);
        }
    }
}
