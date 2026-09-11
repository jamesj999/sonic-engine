package com.openggf.game.sonic3k.objects;

import com.openggf.audio.GameSound;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.camera.Camera;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x4A in zone 0x14 - Pachinko round bumper.
 *
 * <p>This shares the same core bumper physics as the CNZ bumper path in the ROM (both
 * branches funnel through the shared {@code sub_32F34}/{@code sub_32F56} bounce routine),
 * but uses the Pachinko-specific mappings and vertical off-screen despawn behavior. Mirrors
 * {@link CnzBumperObjectInstance}'s stationary-bumper touch handling.
 */
public class PachinkoBumperObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, RewindRecreatable {

    private static final int COLLISION_FLAGS = 0x40 | 0x17;
    private static final int BOUNCE_VELOCITY = 0x700;
    private static final int ANIM_DURATION = 8;

    private int animFrame;
    private int animTimer;
    private AbstractPlayableSprite pendingPrimaryTouch;
    private AbstractPlayableSprite pendingSidekickTouch;

    public PachinkoBumperObjectInstance(ObjectSpawn spawn) {
        super(spawn, "PachinkoBumper");
    }

    @Override
    public PachinkoBumperObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new PachinkoBumperObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (animTimer > 0) {
            animTimer--;
            if (animTimer == 0) {
                animFrame = 0;
            }
        }

        // ROM loc_32EF0 (sonic3k.asm:68905-68919 -- the Pachinko-zone vertical
        // variant of Obj_Bumper's on-screen test, reached via the
        // `cmpi.b #$14,(Current_zone).w / bne.s ... / bra.w loc_32EF0` dispatch
        // at sonic3k.asm:68836-68841):
        // `move.w y_pos(a0),d0 / andi.w #$FF80,d0 / sub.w
        // (Camera_Y_pos_coarse_back).w,d0 / cmpi.w #$200,d0 / bhi.s loc_32F22`.
        // The subtraction is unsigned 16-bit; when the object's Y chunk sits more
        // than 0x200 past the trailing camera band, loc_32F22 clears the SST's
        // respawn_addr bit 7 (so the level's placement table can spawn it again)
        // and deletes the sprite. Camera_Y_pos_coarse_back = (Camera_Y_pos - 0x80)
        // & $FF80 (sonic3k.asm:37551-37554), which for chunk-aligned arithmetic is
        // interchangeable with (Camera_Y_pos & $FF80) - 0x80.
        //
        // Without this, the engine's generic S3K windowing (ObjectWindowingStrategy
        // .LEGACY, X-only) never unloads a Pachinko bumper as the board scrolls
        // vertically far past it -- the board's X range stays essentially camera-
        // relative the whole descent, so the X-based unload distance never trips.
        // Round bumpers spawned near the top of the board then occupy dynamic SST
        // slots for the rest of the run, eventually exhausting the 89-slot pool and
        // silently dropping a later bumper's one-shot vertical placement window
        // (pachinko1 trace f2704: engine never constructs the bumper at
        // (0x1C4,0x148) because tryLoadPlacementSpawnForTwoAxisYPass's allocateSlot()
        // fails with all 89 dynamic slots occupied at the exact frame its vertical
        // band is crossed, while the ROM trace shows that same bumper legitimately
        // spawning and despawning multiple times over the run via this check).
        if (isOffScreenVertically()) {
            ObjectLifetimeOps.destroyRespawnableOffscreen(this);
            return;
        }

        // ROM loc_32EF0/loc_32EAA (sonic3k.asm:68877-68880, 68906-68908):
        // `tst.b collision_property(a0) / beq.s ... / bsr.w sub_32F34`. The bounce
        // is gated on the ROM's own Touch_Loop-populated collision_property bit,
        // not a hand-rolled distance check against the player's terrain
        // x_radius/y_radius. The engine's prior ad-hoc `checkCollision(player)`
        // (dx < 16 && dy < 8+player.getYRadius()) used the player's full rolling
        // y_radius (14, matching Sonic_Roll) as the box half-height, which is
        // WIDER than the real Touch_Loop overlap test the ROM actually gates on
        // -- it kept reporting an overlap for a second frame after the player's
        // post-bounce position had already cleared the true touch box, firing a
        // second, unwanted sub_32F56 bounce and corrupting x_speed/y_speed off a
        // position the player was already leaving (pachinko1 trace f2705:
        // expected x_speed=-0x0206/y_speed=-0x0674 constant ballistic flight,
        // engine produced x_speed=-0x01DC/y_speed=-0x06BA from a spurious re-bounce).
        // Driving this through the shared TouchResponseProvider/onTouchResponse
        // path (mirroring CnzBumperObjectInstance) uses the same ROM-accurate
        // touch-box overlap test the rest of the engine's enemy/object touch
        // system already gets right, and defers the resolved touch to this
        // update() call exactly as sub_32F34's own per-routine bounce does.
        processPendingTouches(vIntRunCount);
    }

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                true,
                true,
                multiRegionSource,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)
                || player.isHurt() || player.getDead()) {
            return;
        }
        if (player.isCpuControlled()) {
            pendingSidekickTouch = player;
        } else {
            pendingPrimaryTouch = player;
        }
    }

    private void processPendingTouches(int vIntRunCount) {
        int romFrameCounter = resolveRomFrameCounter(vIntRunCount);
        AbstractPlayableSprite primary = pendingPrimaryTouch;
        AbstractPlayableSprite sidekick = pendingSidekickTouch;
        pendingPrimaryTouch = null;
        pendingSidekickTouch = null;

        if (primary != null) {
            applyBounce(primary, romFrameCounter);
        }
        if (sidekick != null) {
            applyBounce(sidekick, romFrameCounter);
        }
    }

    /**
     * ROM loc_32EF0's on-screen band test (sonic3k.asm:68913-68917): the
     * bumper's y_pos, chunk-aligned to $FF80, minus the trailing camera band
     * (Camera_Y_pos_coarse_back), is compared unsigned against $200. This
     * round bumper never moves for the Pachinko variant (only the CNZ/
     * competition branches of Obj_Bumper write an orbiting x_pos/y_pos), so
     * {@code spawn.y()} is the same value ROM's y_pos(a0) holds every frame.
     */
    private boolean isOffScreenVertically() {
        ObjectServices svc = tryServices();
        Camera camera = svc != null ? svc.camera() : null;
        if (camera == null) {
            return false;
        }
        // ROM Camera_Y_pos_coarse_back = (Camera_Y_pos - 0x80) & $FF80
        // (sonic3k.asm:37551-37554); chunk-aligned arithmetic makes that
        // interchangeable with (Camera_Y_pos & $FF80) - 0x80.
        int cameraYCoarseBack = (camera.getY() & 0xFF80) - 0x80;
        int objChunkY = spawn.y() & 0xFF80;
        int delta = (objChunkY - cameraYCoarseBack) & 0xFFFF;
        return delta > 0x200;
    }

    private void applyBounce(AbstractPlayableSprite player, int romFrameCounter) {
        int dx = spawn.x() - player.getCentreX();
        int dy = spawn.y() - player.getCentreY();
        int angle = TrigLookupTable.calcAngle((short) dx, (short) dy);

        // ROM sub_32F34/sub_32F56 (sonic3k.asm:68953-68968): `move.b
        // (Level_frame_counter).w,d1` reads the HIGH byte of the big-endian
        // Level_frame_counter word (no +1 offset, unlike the many call sites that
        // read `(Level_frame_counter+1).w` for the low/fast-changing byte), then
        // `andi.w #3,d1` keeps only its bottom 2 bits -- i.e. bits 8-9 of the full
        // word counter, not the distinct per-object V-int run count
        // (which can de-phase from Level_frame_counter -- see
        // PachinkoItemOrbObjectInstance.resolveRomFrameCounter). Mirrors the S2
        // CNZ Obj_Bumper port (BumperObjectInstance.applyBounce, s2.asm:44675-44677)
        // which reads the same ROM-aligned counter for the identical bias term.
        angle = (angle + ((romFrameCounter >> 8) & 3)) & 0xFF;

        int cosVal = TrigLookupTable.cosHex(angle);
        int sinVal = TrigLookupTable.sinHex(angle);
        player.setXSpeed((short) (cosVal * -BOUNCE_VELOCITY >> 8));
        player.setYSpeed((short) (sinVal * -BOUNCE_VELOCITY >> 8));
        player.setAir(true);
        // ROM sub_32F56 (sonic3k.asm:68976-68977): `bclr #Status_RollJump,status(a1)`
        // alongside the Status_InAir set and Status_Push clear already ported below.
        player.setRollingJump(false);
        player.setPushing(false);
        player.setJumping(false);
        // ROM sub_32F56 never writes ground_vel(a1) -- the pre-bounce inertia is
        // left untouched (observed: engine cleared g_speed to 0 while the ROM
        // trace kept g_speed=0x0800 constant across the bounce, Pachinko trace
        // frame 1318 onward).

        animFrame = 1;
        animTimer = ANIM_DURATION;

        try {
            services().playSfx(GameSound.BUMPER);
        } catch (Exception e) {
            // Keep gameplay logic independent from audio state.
        }
        services().gameState().addScore(10);
    }

    /**
     * Resolves the ROM-aligned {@code Level_frame_counter} equivalent for the bounce-angle
     * bias. Falls back to the raw {@code update()} parameter only when the object manager is
     * unavailable (e.g. bare unit-test construction without full services wiring). See
     * {@link PachinkoItemOrbObjectInstance#resolveRomFrameCounter} for why the per-object
     * object-visible V-int run count is not the ROM level-frame counter used here.
     */
    private int resolveRomFrameCounter(int vIntRunCount) {
        try {
            return services().objectManager().getFrameCounter();
        } catch (Exception e) {
            return vIntRunCount;
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(1);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.PACHINKO_BUMPER);
        if (renderer == null) {
            return;
        }
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(animFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }
}
