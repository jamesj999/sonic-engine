package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x5E -- Seesaws (SLZ).
 * <p>
 * A tilting platform that responds to player position. When subtype is 0x00,
 * a spikeball child is spawned that sits on one end. The ball launches when
 * the seesaw tilts, and landing ball launches any standing player.
 * <p>
 * Subtype 0xFF: Seesaw without ball (ball not spawned).
 * Subtype 0x00: Seesaw with spikeball.
 * <p>
 * Disassembly reference: docs/s1disasm/_incObj/5E Seesaw.asm
 */
public class Sonic1SeesawObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider, RewindRecreatable {

    // From disassembly: move.b #$30,obActWid(a0)
    private static final int COLLISION_HALF_WIDTH = 0x30;

    // Slope data surface height used for collision
    private static final int COLLISION_HEIGHT = 8;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    /**
     * Slope data for tilted state (frame 0 or 2).
     * From See_DataSlope = binclude "misc/slzssaw1.bin" (48 bytes).
     */
    private static final byte[] SLOPE_TILTED = {
            0x24, 0x24, 0x26, 0x28, 0x2A, 0x2C, 0x2A, 0x28,
            0x26, 0x24, 0x23, 0x22, 0x21, 0x20, 0x1F, 0x1E,
            0x1D, 0x1C, 0x1B, 0x1A, 0x19, 0x18, 0x17, 0x16,
            0x15, 0x14, 0x13, 0x12, 0x11, 0x10, 0x0F, 0x0E,
            0x0D, 0x0C, 0x0B, 0x0A, 0x09, 0x08, 0x07, 0x06,
            0x05, 0x04, 0x03, 0x02, 0x02, 0x02, 0x02, 0x02
    };

    /**
     * Slope data for flat state (frame 1).
     * From See_DataFlat = binclude "misc/slzssaw2.bin" (48 bytes, all 0x15).
     */
    private static final byte[] SLOPE_FLAT = {
            0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15,
            0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15,
            0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15,
            0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15,
            0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15,
            0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15, 0x15
    };

    // Saved original X position (see_origX = objoff_30)
    private int origX;

    // Current target frame (see_frame = objoff_3A)
    // 0 = tilted left (left side up), 1 = flat, 2 = tilted right (right side up)
    private int targetFrame;

    // Current mapping frame (obFrame)
    // Transitions gradually toward targetFrame
    private int mappingFrame;

    // Stored player Y velocity when landing (see_speed = objoff_38)
    private int storedPlayerYVel;

    // Standing player tracking (obStatus bit 3 = player standing)
    private boolean playerStanding;

    // Child spikeball reference
    private Sonic1SeesawBallObjectInstance ball;
    private boolean ballSpawned;

    public Sonic1SeesawObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Seesaw");

        this.origX = spawn.x();

        // From disassembly See_Main:
        // btst #0,obStatus(a0) — is seesaw flipped?
        // beq.s .noflip
        // move.b #2,obFrame(a0) — use different frame
        boolean flipped = (spawn.renderFlags() & 0x01) != 0;
        if (flipped) {
            mappingFrame = 2;
        } else {
            mappingFrame = 0;
        }
        // move.b obFrame(a0),see_frame(a0)
        targetFrame = mappingFrame;
    }

    @Override
    public Sonic1SeesawObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic1SeesawObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        ensureBallSpawned();

        // Validate standing player
        validateStandingPlayer(player);

        // See_Slope (routine 2): Store player Y velocity when approaching
        // move.w obVelY(a1),see_speed(a0)
        if (player != null && !playerStanding) {
            storedPlayerYVel = player.getYSpeed();
        }

        // See_Slope2 (routine 4): when a player is standing, ROM runs See_ChkSide
        // (which sets see_frame from the player's CURRENT x, then falls into
        // See_ChgFrame) -- all inside ExecuteObjects, AFTER the player slot has
        // moved (docs/s1disasm/_incObj/5E SLZ Seesaw.asm:71-118). The engine runs
        // S1 objects after player physics (objectsExecuteAfterPlayerPhysics=true),
        // so this update() already observes Sonic's post-move x. Computing the
        // tilt target HERE -- immediately before See_ChgFrame -- keeps the
        // ChkSide->ChgFrame order atomic and post-move, matching ROM. Previously
        // the target was latched in onSolidContact (which runs during the player's
        // solid pass, BEFORE this update), so See_ChgFrame advanced obFrame using
        // the PREVIOUS frame's target -> the tilt flip lagged ROM by a frame
        // (SLZ3 f745: ROM flips obFrame 2->1 when the rocking player crosses
        // within 8px of centre; the engine flipped a frame late, re-seating the
        // rider on the wrong slope).
        //
        // ROM See_ChkSide runs in routine 4 (See_Slope2), which the seesaw enters
        // when SlopeObject lands a player on it (addq.b #2,obRoutine -- docs/
        // s1disasm/_incObj/sub PlatformObject.asm:66) and leaves when ExitPlatform
        // unseats them; the resulting see_frame then LATCHES while routine 2
        // (See_Slope) keeps animating obFrame toward it (5E SLZ Seesaw.asm:55-68).
        // The faithful engine proxy for "seesaw is in routine 4 (a player is
        // standing on it)" is the SolidContacts riding state -- NOT the narrower
        // playerStanding flag, which validateStandingPlayer clears the moment the
        // rider's centre drifts 1px past the strict slope x-range (matching ROM
        // SlopeObject's own bmi.s Plat_Exit, sub PlatformObject.asm:136). At SLZ3
        // f9862 the rider sits at the seesaw's left edge (centre 1px off the range)
        // while still riding: playerStanding was already cleared, so the engine
        // never set see_frame=2 and the seesaw stayed at frame 0 (raised left end).
        // Three frames later (f9866) that raised end caught the player who had
        // correctly fallen off at f9863, re-landing him -- while ROM, whose seesaw
        // had latched to frame 2 (dropped left end) by f9863, let him free-fall
        // past it. Drive the tilt target from the riding state so it tracks the
        // rider's side and latches after they leave, matching ROM obFrame 0->1->2.
        boolean tiltTracking = player != null && !player.getAir()
                && (playerStanding
                        || (services().objectManager() != null
                                && services().objectManager().isRidingObject(player, this)));
        if (tiltTracking) {
            targetFrame = calculateTargetAngle(player);
        }

        // Animate mapping frame toward target (See_ChgFrame)
        updateMappingFrame(targetFrame);
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }

        if (!contact.standing()) {
            // Player left the seesaw
            playerStanding = false;
            return;
        }

        // Player is standing on seesaw - track it. The See_ChkSide tilt-target
        // computation is deferred to update() (which runs after player physics)
        // so it observes Sonic's post-move x and stays atomic with See_ChgFrame,
        // matching ROM See_Slope2 ordering. onSolidContact only maintains the
        // standing bit here (it fires during the player's solid pass, before the
        // post-physics object update).
        playerStanding = true;
    }

    /**
     * Calculates target frame based on player position relative to seesaw center.
     * From See_ChkSide:
     * <pre>
     *   move.w obX(a0),d0
     *   sub.w  obX(a1),d0   ; d0 = seesaw_x - player_x
     *   bcc.s  .leftside    ; if >= 0, player is on left side -> d1 = 2
     *   neg.w  d0           ; make positive
     *   moveq  #0,d1        ; player is on right side -> d1 = 0
     *   .leftside:
     *   cmpi.w #8,d0
     *   bhs.s  See_ChgFrame ; if distance >= 8, use d1
     *   moveq  #1,d1        ; within dead zone -> flat
     * </pre>
     */
    private int calculateTargetAngle(AbstractPlayableSprite player) {
        // moveq #2,d1 (default: player on left = tilted right)
        int d1 = 2;
        int d0 = spawn.x() - player.getCentreX();
        if (d0 < 0) {
            // Player is on the right side
            d0 = -d0;
            d1 = 0;
        }
        // cmpi.w #8,d0
        if (d0 < 8) {
            d1 = 1; // Dead zone -> flat
        }
        return d1;
    }

    /**
     * Gradual visual transition of mapping frame toward target.
     * From See_ChgFrame:
     * <pre>
     *   move.b obFrame(a0),d0
     *   cmp.b  d1,d0        ; does frame need to change?
     *   beq.s  .noflip      ; if equal, done
     *   bcc.s  .loc_11772   ; if frame > target, go to subtract
     *   addq.b #2,d0        ; frame < target: add 2 first
     *   .loc_11772:
     *   subq.b #1,d0        ; then subtract 1
     *   move.b d0,obFrame(a0)
     *   move.b d1,see_frame(a0)
     *   bclr   #0,obRender(a0)
     *   btst   #1,obFrame(a0)
     *   beq.s  .noflip
     *   bset   #0,obRender(a0)
     * </pre>
     */
    private void updateMappingFrame(int target) {
        if (mappingFrame == target) {
            return;
        }

        int d0 = mappingFrame;
        if (d0 < target) {
            d0 += 2;
        }
        d0 -= 1;
        mappingFrame = d0;
        targetFrame = target;
    }

    /**
     * Gets the current target frame (see_frame / objoff_3A) for the ball to query.
     */
    public int getTargetFrame() {
        return targetFrame;
    }

    /**
     * Gets the current mapping frame (obFrame) for the ball to query.
     * Used for ball Y offset calculation and player launch comparison.
     */
    public int getMappingFrame() {
        return mappingFrame;
    }

    /**
     * Gets the stored player Y velocity for ball launch power (see_speed / objoff_38).
     */
    public int getStoredPlayerYVel() {
        return storedPlayerYVel;
    }

    /**
     * Sets the target frame from the ball when it lands.
     * Also clears the player standing bit and triggers potential player launch.
     */
    public void setTargetFrame(int frame) {
        this.targetFrame = frame;
    }

    /**
     * Checks if a player is standing on this seesaw (obStatus bit 3).
     */
    public boolean isPlayerStanding() {
        return playerStanding;
    }

    /**
     * Clears the player standing tracking after launch.
     */
    public void clearPlayerStanding() {
        this.playerStanding = false;
    }

    /**
     * Gets the standing player reference for the ball to launch.
     */
    public AbstractPlayableSprite getStandingPlayer() {
        if (!playerStanding) {
            return null;
        }
        // Get from SpriteManager - the standing player is always the main character
        var sprites = services().spriteManager().getAllSprites();
        for (var sprite : sprites) {
            if (sprite instanceof AbstractPlayableSprite aps) {
                return aps;
            }
        }
        return null;
    }

    private void validateStandingPlayer(AbstractPlayableSprite player) {
        if (playerStanding && player != null) {
            if (player.getAir() || !isPlayerInXRange(player)) {
                playerStanding = false;
            }
        }
    }

    private boolean isPlayerInXRange(AbstractPlayableSprite player) {
        int relX = player.getCentreX() - spawn.x() + COLLISION_HALF_WIDTH;
        return relX >= 0 && relX < COLLISION_HALF_WIDTH * 2;
    }

    private void ensureBallSpawned() {
        if (ballSpawned) {
            return;
        }
        ballSpawned = true;

        // From See_Main: tst.b obSubtype(a0) / bne.s .noball
        // Only spawn ball when subtype is 0
        if (spawn.subtype() != 0) {
            return;
        }

        final boolean flipped = (spawn.renderFlags() & 0x01) != 0;

        if (services().objectManager() != null) {
            // ROM See_Main uses FindNextFreeObj (docs/s1disasm/_incObj/5E SLZ
            // Seesaw.asm:38), which allocates the spikeball a slot AFTER the
            // seesaw. ExecuteObjects then runs the seesaw first, so the ball's
            // See_MoveSpike reads the seesaw's see_frame (objoff_3A) that the
            // seesaw already updated this frame via See_Slope2/See_ChgFrame.
            // Using spawnFreeChild (FindFreeObj, lowest free slot) put the ball
            // at a LOWER slot than the seesaw, so it launched off the previous
            // frame's target -> the spring that launches the standing player
            // fired one frame late (SLZ3 f814).
            ball = spawnChild(() -> new Sonic1SeesawBallObjectInstance(
                    this, spawn.x(), spawn.y(), flipped));
        } else {
            ball = new Sonic1SeesawBallObjectInstance(this, spawn.x(), spawn.y(), flipped);
        }
    }

    boolean hasLiveBallForRewind() {
        return ball != null && !ball.isDestroyed();
    }

    void adoptBallForRewind(Sonic1SeesawBallObjectInstance restoredBall) {
        ball = restoredBall;
        ballSpawned = true;
    }

    // ---- SolidObjectProvider ----

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(COLLISION_HALF_WIDTH, COLLISION_HEIGHT, COLLISION_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // ROM See_Slope (routine 2) lands the falling player via SlopeObject,
        // which falls into Plat_NoXCheck_AltY (docs/s1disasm/_incObj/sub
        // PlatformObject.asm:128-152,52-66). That landing band is gated by the
        // UNSIGNED `cmpi.w #-16,d0 / blo` test, so the exact-touch case d0=0
        // (player bottom flush with the slope surface) is REJECTED — the standable
        // band is d0 in [-16,-1] (strict penetration), the same gate Obj 18 and the
        // SLZ circling platform use. Without this, a player falling onto the seesaw
        // was caught one frame early on the flush-contact frame (SLZ3 f1416: a
        // rolling-jump Sonic falls onto the seesaw — ROM keeps him airborne at
        // f1416 and lands him at f1417 when he penetrates, the engine seated him at
        // f1416). The seesaw surface comes from the heightmap (obY - heightByte) in
        // both the landing (SlopeObject) and continued-ride (SlopeObject_AssumeStoodOn)
        // paths, so no obY-8 vs obY-9 detect/ride split is needed (unlike Obj 18).
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // ROM See_Slope / See_Slope2 pass #96/2 (= 0x30) directly as SlopeObject's
        // / SlopeObject_AssumeStoodOn's d1 (docs/s1disasm/_incObj/5E SLZ
        // Seesaw.asm:66-67,79-83), and obActWid is itself #96/2 (line 33). SlopeObject
        // does its X-range check on that d1 with no narrowing (docs/s1disasm/_incObj/
        // sub PlatformObject.asm:133-139), then bra's to Plat_NoXCheck_AltY which skips
        // any further X check. COLLISION_HALF_WIDTH (0x30) is therefore already the
        // standable top-landing width and must NOT receive the generic SolidObjectFull
        // +$B narrowing (which would shrink it to 0x25). Without this, a player falling
        // onto the raised end of the seesaw near its edge is rejected as out-of-width and
        // never lands (SLZ3 f6364: a rolling-jump Sonic arcs back down onto the seesaw at
        // relX=90 -- 42px right of centre, inside the full +/-0x30 range but outside the
        // narrowed +/-0x25 -- so ROM re-lands him while the engine kept him falling).
        // Matches the sibling SlopeObject users Sonic1CollapsingLedgeObjectInstance and
        // Sonic1CollapsingFloorObjectInstance, which opt in for the same reason.
        return true;
    }

    // ---- SlopedSolidProvider ----

    @Override
    public byte[] getSlopeData() {
        // See_Slope / See_Slope2: btst #0,obFrame(a0) / beq.s .notflat
        // Frame 1 or 3 (bit 0 set) = flat, frame 0 or 2 = tilted
        return ((mappingFrame & 1) != 0) ? SLOPE_FLAT : SLOPE_TILTED;
    }

    @Override
    public int getSlopeBaseline() {
        // ROM See_Slope (docs/s1disasm/_incObj/5E SLZ Seesaw.asm:67) lands the
        // player via SlopeObject, which uses ABSOLUTE slope values:
        // SlopeObject (sub PlatformObject.asm:150-152) computes the surface as
        // d0 = obY(a0) - heightmapByte with no baseline subtraction. Returning a
        // non-zero baseline pushes the sampled surface down by that amount,
        // delaying the landing by ~1 frame (SLZ3 trace f718). Match the sibling
        // SlopeObject user (Sonic1CollapsingLedgeObjectInstance) and return 0.
        return 0;
    }

    @Override
    public boolean isSlopeFlipped() {
        // Slope is flipped when bit 1 of obFrame is set (frame 2)
        // From See_ChgFrame: btst #1,obFrame(a0) / beq.s .noflip / bset #0,obRender(a0)
        return (mappingFrame & 2) != 0;
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SLZ_SEESAW);
        if (renderer == null) return;

        // From See_ChgFrame:
        // bclr #0,obRender(a0) — clear x-flip
        // btst #1,obFrame(a0) — if bit 1 set (frame 2)
        // bset #0,obRender(a0) — set x-flip
        boolean useHFlip = (mappingFrame & 2) != 0;

        // Mapping table has entries: 0=sloping, 1=flat, 2=sloping, 3=flat
        // Frame 2 uses the same .sloping data as frame 0, rendered with x-flip
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), useHFlip, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ---- Lifecycle ----

    @Override
    public void onUnload() {
        // Destroy ball child so it doesn't persist after the seesaw is unloaded.
        // Without this, the ball survives and a duplicate is created on respawn.
        if (ball != null) {
            ball.setDestroyed(true);
        }
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // From main object loop: uses see_origX for range check
        return !isDestroyed() && isInRangeAt(origX);
    }

    // ---- Debug rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int x = spawn.x();
        int y = spawn.y();

        // Draw collision box (green)
        int left = x - COLLISION_HALF_WIDTH;
        int right = x + COLLISION_HALF_WIDTH;
        int top = y - COLLISION_HEIGHT;
        int bottom = y + COLLISION_HEIGHT;
        ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(right, top, right, bottom, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(right, bottom, left, bottom, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(left, bottom, left, top, 0.0f, 1.0f, 0.0f);

        // Draw center (red cross)
        ctx.drawLine(x - 4, y, x + 4, y, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(x, y - 4, x, y + 4, 1.0f, 0.0f, 0.0f);

        // Draw frame state (yellow text indicator)
        String state = "F" + mappingFrame + "/T" + targetFrame;
        // (state shown as cross color: cyan = flat, yellow = tilted)
        float cr = (mappingFrame == 1) ? 0f : 1f;
        float cg = 1f;
        float cb = (mappingFrame == 1) ? 1f : 0f;
        ctx.drawLine(x - 2, y - 10, x + 2, y - 10, cr, cg, cb);
    }


}
