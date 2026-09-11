package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x59 - MGZ Dash Trigger.
 *
 * <p>ROM: Obj_MGZDashTrigger (sonic3k.asm:51473-51608).
 * Small spring-bumper-shaped pad. When a player stands on it while in the
 * spindash animation (anim 9), the trigger arms for {@value #TIMER_DURATION}
 * frames. While armed it sets the matching {@code Level_trigger_array} slot
 * (consumed by {@link MGZTriggerPlatformObjectInstance}) and on each frame
 * launches every standing player away from a 16-pixel offset point using the
 * ROM's GetArcTan / GetSineCosine launch (speed magnitude {@code 0x700}).
 *
 * <p>Subtype low nibble selects the {@code Level_trigger_array} index
 * (matching the trigger platform's low nibble). High nibble is unused on the
 * dash trigger itself.
 *
 * <p>Status bit 0 (mirrored from {@code spawn.renderFlags()}) selects which
 * side of the trigger the launch target sits on: clear = target {@code -16}
 * pixels (player launched right), set = target {@code +16} (launched left).
 */
public class MGZDashTriggerObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider,
        RomObjectCodePointerProvider, SpawnRewindRecreatable {

    private static final String ART_KEY = Sonic3kObjectArtKeys.MGZ_DASH_TRIGGER;

    // ROM: priority(a0) = $280
    private static final int PRIORITY_BUCKET = 5;

    // ROM: move.w #$3C,$30(a0) -- arm timer (60 frames)
    private static final int TIMER_DURATION = 0x3C;

    // ROM: muls.w #-$700,d1; asr.l #8,d1 -- launch speed in 16:8 fixed point.
    private static final int LAUNCH_SPEED_FIXED = 0x700;

    // ROM: subi.w #$10,d1 / addi.w #2*$10,d1 -- target X offset from trigger
    private static final int LAUNCH_TARGET_X_OFFSET = 0x10;
    // ROM: addi.w #$10,d2 -- target Y offset from trigger
    private static final int LAUNCH_TARGET_Y_OFFSET = 0x10;

    // ROM: cmpi.b #9,anim(a1) -- player must be in the spindash animation
    private static final int SPINDASH_ANIM_ID = Sonic3kAnimationIds.SPINDASH.id();

    // ROM: move.w #$1B,d1 / move.w #$10,d2 / sub_1DD0E (sloped solid 27 px wide, 16 tall)
    private static final int SOLID_HALF_WIDTH = 0x1B;
    private static final int SOLID_HALF_HEIGHT = 0x10;
    // ROM: byte_25F0E, sampled by sub_1DD0E/SolidObjSloped2 for standing riders.
    // Sonic 3K disassembly: sonic3k.asm:51489-51493,51611-51639,41727-41753.
    private static final byte[] SLOPE_DATA = {
            0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10,
            0x0F, 0x0F, 0x0E, 0x0E, 0x0D, 0x0C, 0x0A, 0x08, 0x06, 0x04,
            0x00, -0x04, -0x08, -0x0A, -0x0A, -0x0A, -0x0A, -0x0A, -0x0A
    };

    // ROM: mapping_frame ping-pongs between 0 (rest) and 4 (extended) each active frame.
    private static final int FRAME_REST = 0;
    private static final int FRAME_EXTENDED = 4;
    private static final int CHILD_FRAME_MASK = 0x03;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS;

    private int triggerIndex;
    /** Trigger facing direction: false = bit 0 clear (right), true = bit 0 set (left). */
    private boolean facingLeft;

    // ROM: $30(a0) -- arm timer countdown (0 = idle, 60 = freshly armed)
    private int armTimer;
    // ROM: mapping_frame(a0)
    private int mappingFrame = FRAME_REST;
    // ROM: sub2_mapframe(a0) -- child sprite frame at the same position.
    private int childMappingFrame;
    // ROM: $32(a0) -- signed step for the child sprite cycle.
    private int childFrameStep = 1;
    // ROM: anim_frame_timer(a0) -- child sprite delay counter.
    private int childAnimFrameTimer;

    public MGZDashTriggerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MGZDashTrigger");
        this.triggerIndex = spawn.subtype() & 0x0F;
        this.facingLeft = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // ROM loc_25D9C resolves sub_1DD0E before inspecting its standing-bit
        // result and before the armed launch loop (sonic3k.asm:51489-51579).
        checkpointAll();

        // ROM: each frame sub_1DD0E performs a proximity probe (independent of
        // SolidObject collision). The post-call mask is #$33 -- both standing
        // bit AND pushing bit for both players. We approximate by checking
        // bounding-box adjacency every frame regardless of movement, so that a
        // player charging a spindash while pressed against the side still arms
        // the trigger even though no SolidContact event fires.
        // ROM loc_25D9C runs the arm test EVERY frame, ahead of the loc_25E22
        // countdown and regardless of the current value of $30(a0): a player
        // still in the spindash animation against the trigger re-loads
        // move.w #$3C,$30(a0) on each such frame (sonic3k.asm:51502-51545).
        // It also evaluates P1 (andi.b #$11,d6) and P2 (andi.b #$22,d6)
        // independently -- neither arm short-circuits the other.
        for (PlayableEntity participant : playerQuery(playerEntity).playersFor(PLAYER_PARTICIPATION)) {
            if (participant instanceof AbstractPlayableSprite sprite) {
                tryArmFromPlayer(sprite);
            }
        }

        if (armTimer == 0) {
            mappingFrame = FRAME_REST;
            return;
        }

        // ROM: subq.w #1,$30(a0); bne.s loc_25E4A
        armTimer--;
        if (armTimer == 0) {
            // ROM: move.b #0,(a3); move.b #0,mapping_frame(a0)
            Sonic3kLevelTriggerManager.clearAll(triggerIndex);
            mappingFrame = FRAME_REST;
            return;
        }

        // ROM: move.b #1,(a3) -- byte-write 1 (overwrites all bits, not bit-set)
        Sonic3kLevelTriggerManager.clearAll(triggerIndex);
        Sonic3kLevelTriggerManager.setBit(triggerIndex, 0);

        // ROM: per standing player, sub_25EA6 launches them and plays sfx_SmallBumpers.
        for (PlayableEntity participant : playerQuery(playerEntity).playersFor(PLAYER_PARTICIPATION)) {
            if (participant instanceof AbstractPlayableSprite sprite) {
                launchIfRiding(sprite);
            }
        }

        // ROM: subq.b #1,anim_frame_timer(a0) / move.b #1,anim_frame_timer(a0)
        // / add.b $32(a0),sub2_mapframe(a0) / andi.b #3,sub2_mapframe(a0)
        childAnimFrameTimer--;
        if (childAnimFrameTimer < 0) {
            childAnimFrameTimer = 1;
            childMappingFrame = (childMappingFrame + childFrameStep) & CHILD_FRAME_MASK;
        }

        // ROM: mapping_frame ping-pong between 0 and 4 every frame.
        mappingFrame = (mappingFrame == FRAME_REST) ? FRAME_EXTENDED : FRAME_REST;
    }

    /**
     * ROM: lines 51499-51521 / 51527-51545 -- standing player check followed by
     * {@code cmpi.b #9,anim(a1)} and the {@code move.w #$3C,$30(a0)} arm.
     */
    private void tryArmFromPlayer(AbstractPlayableSprite player) {
        if (player == null) return;
        boolean animationMatches = player.getAnimationId() == SPINDASH_ANIM_ID;
        boolean spindashFlag = player.getSpindash();
        if (!animationMatches && !spindashFlag) return;
        if (!isAdjacent(player)) return;

        armTimer = TIMER_DURATION;
        boolean playerFacingLeft = player.getDirection() == Direction.LEFT;
        childFrameStep = (playerFacingLeft == facingLeft) ? -1 : 1;
    }

    /**
     * AABB adjacency probe matching the ROM's sub_1DD0E coverage area. The
     * dash trigger's solid box is 27x16 half-extents; the player's hitbox is
     * inflated by one pixel so a player pressed flush against the trigger is
     * still detected (SolidObjectFull pushes the player just outside).
     */
    private boolean isAdjacent(AbstractPlayableSprite player) {
        int dx = Math.abs(player.getCentreX() - spawn.x());
        int dy = Math.abs(player.getCentreY() - spawn.y());
        int maxDx = SOLID_HALF_WIDTH + player.getXRadius() + 1;
        int maxDy = SOLID_HALF_HEIGHT + player.getYRadius() + 1;
        return dx <= maxDx && dy <= maxDy;
    }

    /** ROM: sub_25EA6 (lines 51580-51608) -- only standing players are launched. */
    private void launchIfRiding(AbstractPlayableSprite player) {
        if (player == null || !isRiding(player)) {
            return;
        }

        // ROM:
        //   move.w x_pos(a0),d1; subi.w #$10,d1
        //   btst #0,status(a0); beq.s loc_25EBA; addi.w #2*$10,d1
        int targetX = spawn.x() + (facingLeft ? +LAUNCH_TARGET_X_OFFSET : -LAUNCH_TARGET_X_OFFSET);
        int targetY = spawn.y() + LAUNCH_TARGET_Y_OFFSET;

        // ROM: sub.w x_pos(a1),d1; sub.w y_pos(a1),d2
        short dx = (short) (targetX - player.getCentreX());
        short dy = (short) (targetY - player.getCentreY());

        // ROM: GetArcTan -> d0 = angle; GetSineCosine -> d0 = sin, d1 = cos
        int angle = TrigLookupTable.calcAngle(dx, dy);
        int sin = TrigLookupTable.sinHex(angle);
        int cos = TrigLookupTable.cosHex(angle);

        // ROM: muls.w #-$700,d{0,1}; asr.l #8,d{0,1}
        short xVel = (short) ((-cos * LAUNCH_SPEED_FIXED) >> 8);
        short yVel = (short) ((-sin * LAUNCH_SPEED_FIXED) >> 8);

        player.setXSpeed(xVel);
        player.setYSpeed(yVel);
        // ROM: bset #Status_InAir,status(a1)
        player.setAir(true);
        // ROM: bclr #Status_RollJump,status(a1)
        player.setRollingJump(false);
        // ROM: bclr #Status_Push,status(a1)
        player.setPushing(false);
        // ROM: clr.b jumping(a1)
        player.setJumping(false);
        // ROM: clr.b spin_dash_flag(a1) -- exit pinball/spindash gating
        player.setPinballMode(false);
        player.setSpindash(false);

        // ROM: moveq #signextendB(sfx_SmallBumpers),d0; jsr (Play_SFX).l
        // Called every frame the trigger is armed and a player is standing;
        // the SMPS engine handles per-channel dedupe of a still-playing SFX.
        services().playSfx(Sonic3kSfx.SMALL_BUMPERS.id);
    }

    private boolean isRiding(AbstractPlayableSprite player) {
        ObjectManager om = services().objectManager();
        return om != null && om.isRidingObject(player, this);
    }

    private ObjectPlayerQuery playerQuery(PlayableEntity primary) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> primary, query::sidekicks);
    }

    int triggerIndex() {
        return triggerIndex;
    }

    // ===== SolidObjectProvider (sloped solid bumper -- pushes from sides too) =====

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public byte[] getSlopeData() {
        return SLOPE_DATA;
    }

    @Override
    public boolean isSlopeFlipped() {
        return facingLeft;
    }

    @Override
    public boolean addsSlopeCatchRangeToVerticalOverlap() {
        // ROM loc_1DECE adds the caller's d2 ($10) to y_radius before
        // classifying the sampled slope contact (sonic3k.asm:41275-41281).
        return true;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // ROM loc_1DECE rejects only d0 > d1*2 (bhi), so a player whose
        // centre is exactly halfWidth pixels to the right is still sampled.
        // sonic3k.asm:41263-41272.
        return true;
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // sub_1DD0E enters loc_1DECE directly when the standing bit is clear;
        // unlike SolidObjectFull, it never tests render_flags bit 7 at
        // loc_1DF88 before resolving a new sloped contact.
        return true;
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // Obj_MGZDashTrigger lives at $00025D9C in the locked-on ROM.
        return 0x0002;
    }

    @Override
    public boolean keepsOnObjWhenAirborneAfterSameFrameStandingContact(PlayableEntity player) {
        // Obj_MGZDashTrigger runs sub_1DD0E first, then sub_25EA6 sets
        // Status_InAir without clearing Status_OnObj. The stale-standing
        // cleanup cannot run until this object's next execution.
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Activation logic lives in update() via isAdjacent(), which fires every
        // frame regardless of whether the player is moving.
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }
        // ROM Render_Sprites skips the main sprite when mapping_frame == 0, then
        // always draws the child sprite from sub2_mapframe at the same position.
        if (mappingFrame != FRAME_REST) {
            renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), facingLeft, false);
        }
        renderer.drawFrameIndex(childMappingFrame, spawn.x(), spawn.y(), facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        float r = armTimer > 0 ? 1.0f : 0.4f;
        float g = armTimer > 0 ? 0.6f : 0.6f;
        float b = armTimer > 0 ? 0.0f : 0.9f;
        ctx.drawRect(spawn.x(), spawn.y(), SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, r, g, b);

        int targetX = spawn.x() + (facingLeft ? +LAUNCH_TARGET_X_OFFSET : -LAUNCH_TARGET_X_OFFSET);
        int targetY = spawn.y() + LAUNCH_TARGET_Y_OFFSET;
        ctx.drawCross(targetX, targetY, 4, 1.0f, 0.2f, 0.2f);
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

}
