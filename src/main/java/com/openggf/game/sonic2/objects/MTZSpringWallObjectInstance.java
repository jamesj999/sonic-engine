package com.openggf.game.sonic2.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.SpringHelper;

import com.openggf.audio.GameSound;
import com.openggf.camera.Camera;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpringBounceHelper;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 66 - Yellow spring walls from MTZ (Metropolis Zone).
 * <p>
 * An invisible solid wall that bounces the player when they push against it
 * from the correct side. The visual spring graphics are part of the level tiles;
 * this object only provides the collision and bounce behavior.
 * <p>
 * Based on Sonic 2 disassembly (s2.asm lines 52760-52926).
 * <p>
 * Subtypes:
 * <ul>
 *   <li>0x01 (upper nibble 0) - Small wall, y_radius = 0x40 (64px), mapping frame 0</li>
 *   <li>0x11 (upper nibble 1) - Large wall, y_radius = 0x80 (128px), mapping frame 1</li>
 * </ul>
 * <p>
 * Subtype lower nibble bits (applied during bounce):
 * <ul>
 *   <li>Bit 7 - If set, y_vel = 0 (horizontal-only bounce)</li>
 *   <li>Bit 0 - If set, applies flip/twirl effect</li>
 *   <li>Bit 1 - Modifies flip count (0=3 flips, 1=1 flip)</li>
 *   <li>Bits 2-3 - Collision layer switching (same as springs)</li>
 * </ul>
 */
public class MTZSpringWallObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    // From disassembly: move.w #$13,d1
    private static final int SOLID_HALF_WIDTH = 0x13;

    // From disassembly: move.b #$40,y_radius(a0)
    private static final int Y_RADIUS_SMALL = 0x40;

    // From disassembly: move.b #$80,y_radius(a0)
    private static final int Y_RADIUS_LARGE = 0x80;

    // From disassembly: move.w #-$800,x_vel(a1) / move.w #-$800,y_vel(a1)
    private static final int BOUNCE_SPEED = 0x800;

    // From disassembly: move.w #$F,move_lock(a1)
    // Same lock duration as standard springs
    private static final int MOVE_LOCK_FRAMES = SpringBounceHelper.CONTROL_LOCK_FRAMES;

    private int yRadius;
    private boolean xFlip;

    public MTZSpringWallObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // From disassembly: lsr.b #4,d0 / andi.b #7,d0 / move.b d0,mapping_frame(a0)
        int frame = (spawn.subtype() >> 4) & 7;

        // From disassembly: beq.s Obj66_Main / move.b #$80,y_radius(a0)
        this.yRadius = (frame != 0) ? Y_RADIUS_LARGE : Y_RADIUS_SMALL;

        // status(a0) bit 0 = x_flip from level placement
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public MTZSpringWallObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MTZSpringWallObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly: d1=$13, d2=y_radius, d3=y_radius+1
        return SolidObjectParams.of(SOLID_HALF_WIDTH, yRadius, yRadius + 1);
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // Obj66 calls the solid routines before its explicit coarse-X DeleteObject
        // tail, so it must not be skipped by the engine's shared offscreen gate.
        return true;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj66 calls SolidObject_Always_SingleCharacter, which falls into
        // SolidObject_cont's X gate. S2 rejects the right edge with `bhi`,
        // not `bhs`, so relX == width*2 still reaches the side-bounce path
        // (s2.asm:34898-34913, 35153-35157).
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // ROM Obj66 (loc_2702C, s2.asm:52839-52844) performs an explicit coarse-X
        // DeleteObject after the per-player solid/bounce passes:
        //   move.w x_pos(a0),d0
        //   andi.w #$FF80,d0              ; chunk-align object X
        //   sub.w  (Camera_X_pos_coarse).w,d0
        //   cmpi.w #$280,d0              ; 640px coarse cutoff
        //   bhi.w  JmpTo33_DeleteObject
        // Camera_X_pos_coarse = (Camera_X_pos - 128) chunk-aligned
        // (s2.constants.asm:1619), modeled as ((camera.getX() - 128) & 0xFF80) to
        // match the established port in TornadoObjectInstance / ConveyorObjectInstance.
        // This is the same explicit delete the twin stompers (Obj64) use; the
        // spring wall otherwise has no despawn path of its own. ROM uses
        // DeleteObject (not MarkObjGone), so we use setDestroyed(true), which also
        // clears the re-spawnable flag (DeleteObject does not leave a respawn slot).
        Camera camera = services().camera();
        if (camera != null) {
            int objChunk = spawn.x() & 0xFF80;
            int camChunk = (camera.getX() - 128) & 0xFF80;
            int d0 = (objChunk - camChunk) & 0xFFFF;
            if (d0 > 0x280) {
                setDestroyed(true);
            }
        }
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }

        // From disassembly: cmpi.b #1,d4 / bne.s loc_26FF6
        // d4=1 means player is pushing against the side of the object
        if (!contact.touchSide()) {
            return;
        }

        // From disassembly: btst #status.player.in_air,status(a1) / beq.s loc_26FF6
        // Player must be in the air for the spring wall to trigger
        if (!player.getAir()) {
            return;
        }

        // Check which side the player is pushing from
        // From disassembly:
        //   move.b status(a0),d1          ; get wall's x_flip
        //   move.w x_pos(a0),d0
        //   sub.w  x_pos(a1),d0           ; d0 = wall_x - player_x
        //   bcs.s  +                       ; if negative (player RIGHT of wall), skip
        //   eori.b #1,d1                   ; player LEFT of wall: flip the check
        //   andi.b #1,d1
        //   bne.s  loc_26FF6               ; if set, wrong side - skip bounce
        int wallX = spawn.x();
        int playerX = player.getCentreX();
        int d1 = xFlip ? 1 : 0;

        if (wallX >= playerX) {
            // Player is LEFT of (or at) wall - flip the check
            d1 ^= 1;
        }
        // If d1 bit 0 is set, player is on the wrong side
        if ((d1 & 1) != 0) {
            return;
        }

        applyBounce(player);
    }

    /**
     * Applies the spring wall bounce to the player.
     * From disassembly: loc_27042 / loc_2704C (s2.asm lines 52855-52921)
     */
    private void applyBounce(AbstractPlayableSprite player) {
        // REV01 check: player routine < 4 (not hurt/dead)
        // From disassembly: cmpi.b #4,routine(a1) / blo.s loc_2704C / rts
        if (player.isHurt() || player.getDead()) {
            return;
        }

        // From disassembly:
        //   move.w #-$800,x_vel(a1)
        //   move.w #-$800,y_vel(a1)
        //   bset #status.player.x_flip,status(a1)   ; face LEFT (default)
        //   btst #status.npc.x_flip,status(a0)      ; check wall's x_flip
        //   bne.s +                                   ; if flipped, keep facing LEFT + negative x_vel
        //   bclr #status.player.x_flip,status(a1)   ; face RIGHT
        //   neg.w x_vel(a1)                          ; x_vel = +$800 (rightward)
        int xVel;
        Direction dir;
        if (xFlip) {
            // Wall is flipped - bounce player LEFT (x_vel = -$800, face LEFT)
            xVel = -BOUNCE_SPEED;
            dir = Direction.LEFT;
        } else {
            // Wall is not flipped - bounce player RIGHT (x_vel = +$800, face RIGHT)
            xVel = BOUNCE_SPEED;
            dir = Direction.RIGHT;
        }

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) -BOUNCE_SPEED);
        player.setDirection(dir);

        // From disassembly: move.w #$F,move_lock(a1)
        player.setMoveLockTimer(MOVE_LOCK_FRAMES);

        // From disassembly: move.w x_vel(a1),inertia(a1)
        player.setGSpeed((short) xVel);

        // From disassembly: btst #status.player.rolling,status(a1) / bne.s +
        //                    move.b #AniIDSonAni_Walk,anim(a1)
        if (!player.getRolling()) {
            player.setAnimationId(Sonic2AnimationIds.WALK);
        }

        int subtype = spawn.subtype();

        // From disassembly: move.b subtype(a0),d0 / bpl.s + / move.w #0,y_vel(a1)
        // Bit 7: if set, clear Y velocity (horizontal-only bounce)
        if ((subtype & 0x80) != 0) {
            player.setYSpeed((short) 0);
        }

        // From disassembly: btst #0,d0 / beq.s loc_270DC
        // Bit 0: flip/twirl effect
        if ((subtype & 0x01) != 0) {
            // From disassembly:
            //   move.w #1,inertia(a1)
            //   move.b #1,flip_angle(a1)
            //   move.b #AniIDSonAni_Walk,anim(a1)
            //   move.b #1,flips_remaining(a1)
            //   move.b #8,flip_speed(a1)
            player.setGSpeed((short) 1);
            player.setFlipAngle(1);
            player.setAnimationId(Sonic2AnimationIds.WALK);
            player.setFlipsRemaining(1);
            player.setFlipSpeed(8);

            // From disassembly: btst #1,d0 / bne.s +
            //                    move.b #3,flips_remaining(a1)
            if ((subtype & 0x02) == 0) {
                player.setFlipsRemaining(3);
            }

            // From disassembly: btst #status.player.x_flip,status(a1) / beq.s loc_270DC
            //                    neg.b flip_angle(a1) / neg.w inertia(a1)
            // x_flip set = facing left -> negate
            if (player.getDirection() == Direction.LEFT) {
                player.setFlipAngle(-player.getFlipAngle());
                player.setGSpeed((short) -player.getGSpeed());
            }
        }

        // From disassembly: andi.b #$C,d0 - collision layer switching
        SpringHelper.applyCollisionLayerBits(player, subtype);

        // From disassembly: bclr #p1_pushing_bit,status(a0) / bclr #p2_pushing_bit,status(a0)
        //   if fixBugs: bclr #status.player.rolljumping,status(a1)
        //   bclr #status.player.pushing,status(a1)
        // (docs/s2disasm/s2.asm:53393-53400).
        //
        // fixBugs = 0 in the shipped ROM (docs/s2disasm/s2.asm:27), so the
        // roll-jumping clear between the two is NOT executed and the engine
        // must not perform it either. The fixed branch would clear
        // status.player.rolljumping so the character's controls unlock and they
        // cannot get stuck after the spring-wall bounce; on the un-fixed path
        // the flag survives the bounce, which is the behaviour every recorded
        // trace carries.
        // The two object-side bclrs are unconditional and cover BOTH characters,
        // so they release the spring wall's own pushing bits whoever bounced
        // (docs/s2disasm/s2.asm:53393-53394). That is what stops the following
        // frame's SolidObject_TestClearPush `btst d4,status(a0)` (:35462-35466)
        // from passing and restarting the walk animation.
        services().objectManager().solidContacts()
                .releaseObjectPushLatchForAllPlayers(this);
        player.setPushing(false);

        // From disassembly: move.w #SndID_Spring,d0 / jmp (PlaySound).l
        try {
            services().playSfx(GameSound.SPRING);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // This object is invisible in REV01 (no DisplaySprite call).
        // The visual spring graphics are part of the level tiles.
        // In REV00, it was only visible in debug placement mode.
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw solid collision box
        int x = spawn.x();
        int y = spawn.y();
        float r = 0.9f;
        float g = 0.9f;
        float b = 0.0f;
        ctx.drawRect(x, y, SOLID_HALF_WIDTH, yRadius, r, g, b);

        // Draw arrow indicating bounce direction
        int arrowX = xFlip ? (x - 8) : (x + 8);
        ctx.drawRect(arrowX, y, 2, 4, 1.0f, 0.5f, 0.0f);
    }

    @Override
    public int getPriorityBucket() {
        // From disassembly: move.b #4,priority(a0)
        return 4;
    }
}
