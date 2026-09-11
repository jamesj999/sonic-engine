package com.openggf.game.sonic1.objects;

import com.openggf.audio.AudioManager;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Sonic 1 Spikes - Object ID 0x36.
 * <p>
 * Subtype high nybble selects visual variant (Spik_Var table):
 * <ul>
 *   <li>0: 3 upward spikes (frame 0, actWidth=$14)</li>
 *   <li>1: 3 sideways spikes (frame 1, actWidth=$10)</li>
 *   <li>2: 1 upward spike (frame 2, actWidth=4)</li>
 *   <li>3: 3 widely spaced upward spikes (frame 3, actWidth=$1C)</li>
 *   <li>4: 6 upward spikes (frame 4, actWidth=$40)</li>
 *   <li>5: 1 sideways spike (frame 5, actWidth=$10)</li>
 * </ul>
 * <p>
 * Subtype low nybble selects movement behavior:
 * <ul>
 *   <li>0: Static</li>
 *   <li>1: Vertical oscillation (32px displacement, 60-frame wait)</li>
 *   <li>2: Horizontal oscillation (32px displacement, 60-frame wait)</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/36 Spikes.asm
 */
public class Sonic1SpikeObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // Spik_Var table: frame index and actWidth per visual type (high nybble)
    // From disassembly: dc.b frame, width pairs
    private static final int[] FRAME_TABLE = {0, 1, 2, 3, 4, 5};
    private static final int[] ACT_WIDTH_TABLE = {0x14, 0x10, 4, 0x1C, 0x40, 0x10};

    // Movement constants from disassembly
    private static final int RETRACT_STEP = 0x800;    // addi.w #$800,objoff_34(a0)
    private static final int RETRACT_MAX = 0x2000;     // cmpi.w #$2000,objoff_34(a0)
    private static final int RETRACT_DELAY = 60;       // move.w #60,objoff_38(a0)
    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS;

    private int baseX;
    private int baseY;
    private int frameIndex;
    private int actWidth;
    private int movementType;

    private int currentX;
    private int currentY;
    private int displacement;    // objoff_34: current movement offset (8.8 fixed point)
    private int direction;       // objoff_36: 0=extending, 1=retracting
    private int delayTimer;      // objoff_38: frame delay counter

    public Sonic1SpikeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Spikes");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.currentX = baseX;
        this.currentY = baseY;

        // From disassembly: high nybble selects Spik_Var entry
        int visualType = (spawn.subtype() >> 4) & 0x0F;
        if (visualType >= FRAME_TABLE.length) {
            visualType = 0;
        }
        this.frameIndex = FRAME_TABLE[visualType];
        this.actWidth = ACT_WIDTH_TABLE[visualType];

        // From disassembly: andi.b #$F,obSubtype(a0) — low nybble is movement type
        this.movementType = spawn.subtype() & 0x0F;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        // ROM Spikes_Display checks out_of_range against spikes_origX (objoff_30),
        // the spawn-origin X, NOT the current (moved) obX:
        //   out_of_range.w DeleteObject,spikes_origX(a0)
        // (docs/s1disasm/_incObj/36 Spikes.asm:163,167; spikes_origX set at :47).
        // Horizontal-moving spikes (subtype $x2) extend their obX away from the
        // origin each frame, so anchoring the unload window on the moved getX()
        // (the default) culls them up to a chunk early when the extended tip
        // crosses the despawn threshold while the origin is still in range. MZ3:
        // the sideways spike at origin (0xDEC,0x710) spawned at f6527 then the
        // moved-getX() out_of_range deleted it at f6528, ~285 frames before the
        // player rolling-jumps into its solid underside at f6813. Anchoring on
        // baseX (= spikes_origX) keeps it loaded exactly as long as ROM does.
        return baseX;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        updateMovement();
        updateDynamicSpawn(currentX, currentY);
        SolidCheckpointBatch batch = checkpointAll();
        List<PlayableEntity> players = services().playerQuery().playersFor(PLAYER_PARTICIPATION);
        if (players.isEmpty() && playerEntity != null) {
            players = List.of(playerEntity);
        }
        for (PlayableEntity candidate : players) {
            if (candidate instanceof AbstractPlayableSprite player) {
                applyCheckpointContact(player, batch.perPlayer().get(candidate), vIntRunCount);
            }
        }
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        handleSolidContact(player, contact, frameCounter);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    private void applyCheckpointContact(AbstractPlayableSprite player,
                                        PlayerSolidContactResult result,
                                        int vIntRunCount) {
        if (player == null || result == null || result.kind() == ContactKind.NONE) {
            return;
        }
        handleSolidContact(player, contactFrom(result), vIntRunCount);
    }

    private void handleSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null) {
            return;
        }
        if (!shouldHurt(player, contact)) {
            return;
        }
        // From disassembly: tst.b (v_invinc).w / bne.s Spik_Display
        if (player.getInvincibleFrames() > 0) {
            return;
        }
        // From disassembly: cmpi.b #4,obRoutine(a0) / bhs.s loc_CF20
        // Skip if Sonic is already in the hurt routine or dead — prevents
        // spikes from double-hitting on consecutive frames.
        if (player.isHurt() || player.getDead()) {
            return;
        }

        // ROM parity (Spik_Hurt): REWIND Sonic's Y position before applying hurt.
        // The ROM undoes SpeedToPos by subtracting the current Y velocity from the
        // position: obY_full -= (obVelY << 8). This returns Sonic to his pre-movement
        // Y, preventing the spike from pushing Sonic downward into the spike surface.
        // Without this, the spike hurt fires at the post-SpeedToPos position, which
        // is deeper inside the spike collision box, causing a 7px Y divergence.
        //   move.l obY(a0),d3
        //   move.w obVelY(a0),d0
        //   ext.l d0
        //   asl.l #8,d0
        //   sub.l d0,d3
        //   move.l d3,obY(a0)
        // ROM rewind: sub.l d0,d3 where d3=obY_full, d0=obVelY<<8
        // This operates on the top-left Y (obY), not centre Y.
        // The engine stores yPixel (top-left) + sub-pixel accumulator separately.
        // Combine, subtract, split back. Use move() with negative velocity
        // to replicate sub.l: move(-velX, -velY) reverses SpeedToPos.
        if (player instanceof AbstractPlayableSprite aps) {
            // Rewind Y: subtract the current velocity (which includes gravity
            // added during ObjectMoveAndFall within the same frame).
            short negYSpeed = (short) -aps.getYSpeed();
            // Only rewind Y, not X — ROM only rewinds Y in Spik_Hurt.
            aps.move((short) 0, negYSpeed);
        }

        // S1 spike exception: unlike most hazards, spikes still hurt during post-hit
        // invulnerability frames (while still respecting invincibility power-up).
        if (player.isCpuControlled()) {
            player.applyHurtIgnoringIFrames(currentX, true);
            return;
        }
        boolean hadRings = player.getRingCount() > 0;
        if (hadRings && !player.hasShield()) {
            services().spawnLostRings(player, frameCounter);
        }
        // spikeHit=true triggers DamageCause.SPIKE → GameSound.HURT_SPIKE (SFX 0xA6)
        player.applyHurtOrDeathIgnoringIFrames(currentX, true, hadRings);
    }

    @Override
    public boolean usesStickyContactBuffer() {
        // Spikes should not hold contact via the generic riding sticky buffer.
        // This keeps collision/hurt timing aligned with ROM spike behavior.
        return false;
    }

    @Override
    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        // ROM Spikes do NOT drag a standing rider horizontally. The Spikes
        // standing branch reaches MvSonicOnPtfm via the shared SolidObject
        // routine (sub SolidObject.asm:46-55, .stand), which carries the rider
        // by the X-delta between d4 (the carry-reference X) and the object's
        // current obX. The Spikes caller passes the object's *post-move* obX as
        // the carry reference:
        //   bsr.w Spikes_Move        ; updates obX to the new position FIRST
        //   ...
        //   move.w obX(a0),d4        ; d4 = already-moved obX (36 Spikes.asm:52,96)
        // so MvSonicOnPtfm's "sub.w obX(a0),d2" computes a ZERO delta
        // (sub MvSonicOnPtfm.asm:38-39) and the standing rider is not carried.
        // A horizontally-moving spike (subtype $x2) therefore slides out from
        // under a standing player, who stays put and walks off the edge — the
        // MZ1 trace at f4230 (player x stays 0x0B35 while the spike moves
        // 0x0B34->0x0B3C, then the player drops off and falls at f4234).
        // This matches ALL spikes (every Spikes caller passes the post-move
        // obX), so it is an object-wide property, not a zone carve-out.
        return false;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        if (isSideways()) {
            // ROM: d2 = 4 (frame 5) or $14 (frame 1). d3 = d2 + 1 (addq.w #1,d3).
            int d2 = (frameIndex == 5) ? 4 : 0x14;
            return SolidObjectParams.of(0x1B, d2, d2 + 1);
        }
        // Spik_Upright: d1=obActWid+$B, d2=$10, d3=$11. ROM-exact.
        return SolidObjectParams.of(actWidth + 0x0B, 0x10, 0x11);
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        // Spikes call the shared S1 SolidObject routine, whose horizontal
        // bounds reject only values above 2*d1 (`bhi`). A player exactly flush
        // with the right face therefore remains a side contact.
        return SolidRoutineProfile.fullSolid(usesStickyContactBuffer(), true, false);
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // SolidObject stores its standing/pushing bits in the live Obj36 SST.
        // Moving spike variants rebuild their engine spawn as displacement
        // changes, but that transient placement is not a new native object.
        return true;
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
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SPIKE);
        if (renderer == null) return;
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(frameIndex, currentX, currentY, hFlip, vFlip);
    }

    private boolean isSideways() {
        return frameIndex == 1 || frameIndex == 5;
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    private SolidContact contactFrom(PlayerSolidContactResult result) {
        return switch (result.kind()) {
            case TOP -> new SolidContact(true, false, false, true, false);
            case SIDE -> new SolidContact(false, true, false, false, result.pushingNow());
            case BOTTOM -> new SolidContact(false, false, true, false, false);
            case NONE, CRUSH -> null;
        };
    }

    /**
     * Determines whether the given contact should hurt the player.
     * <p>
     * From disassembly:
     * - Sideways spikes (Spik_SideWays): hurt on side contact (d4==1)
     * - Upright spikes (Spik_Upright): hurt on standing (bit 3) or bottom contact (d4 < 0)
     */
    private boolean shouldHurt(AbstractPlayableSprite player, SolidContact contact) {
        if (isSideways()) {
            return contact.touchSide();
        }
        // From disassembly: btst #3,obStatus(a0) / bne.s Spik_Hurt (standing)
        // tst.w d4 / bpl.s Spik_Display (d4 < 0 = bottom contact also hurts)
        return (contact.standing() && isRomAccurateStandingHit(player)) || contact.touchBottom();
    }

    /**
     * ROM parity for Spik_Upright standing damage:
     * SolidObject must have reached Solid_Landed, which requires:
     * - d3 < $10 (top-contact window)
     * - X within obActWid window
     * - y_vel >= 0
     */
    private boolean isRomAccurateStandingHit(AbstractPlayableSprite player) {
        if (player.getYSpeed() < 0) {
            return false;
        }

        int maxTop = player.getYRadius() + 0x10; // d2 in Spik_Upright
        int relY = player.getCentreY() - currentY + 4 + maxTop;
        if (relY < 0 || relY >= 0x10) {
            return false;
        }

        int relX = player.getCentreX() - currentX + actWidth;
        int xWindow = actWidth * 2;
        return relX >= 0 && relX < xWindow;
    }

    /**
     * Updates spike movement based on movement type (Spik_Type0x).
     */
    private void updateMovement() {
        switch (movementType) {
            case 1 -> {
                // Spik_Type01: vertical movement
                updateDelay();
                int offsetPixels = displacement >> 8;
                currentX = baseX;
                currentY = baseY + offsetPixels;
            }
            case 2 -> {
                // Spik_Type02: horizontal movement
                updateDelay();
                int offsetPixels = displacement >> 8;
                currentX = baseX + offsetPixels;
                currentY = baseY;
            }
            default -> {
                // Spik_Type00: static
                currentX = baseX;
                currentY = baseY;
            }
        }
    }

    /**
     * Oscillation state machine (Spik_Wait).
     * Extends to RETRACT_MAX, waits RETRACT_DELAY frames, retracts to 0, waits again.
     * Plays sfx_SpikesMove when delay expires and object is on screen.
     */
    private void updateDelay() {
        if (delayTimer > 0) {
            delayTimer--;
            if (delayTimer == 0 && isOnScreen()) {
                // From disassembly: move.w #sfx_SpikesMove,d0 / jsr (QueueSound2).l
                try {
                    services().playSfx(Sonic1Sfx.SPIKES_MOVE.id);
                } catch (Exception e) {
                    // Prevent audio failure from breaking game logic
                }
            }
            return;
        }

        if (direction != 0) {
            // Retracting: subi.w #$800,objoff_34(a0) / bcc.s locret_CFE6
            displacement -= RETRACT_STEP;
            if (displacement < 0) {
                displacement = 0;
                direction = 0;
                delayTimer = RETRACT_DELAY;
            }
            return;
        }

        // Extending: addi.w #$800,objoff_34(a0)
        displacement += RETRACT_STEP;
        if (displacement >= RETRACT_MAX) {
            displacement = RETRACT_MAX;
            direction = 1;
            delayTimer = RETRACT_DELAY;
        }
    }
}
