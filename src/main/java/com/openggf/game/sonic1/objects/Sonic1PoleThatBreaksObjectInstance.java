package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.Sonic1ZoneFeatureProvider;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x0B - Pole that breaks (LZ).
 * <p>
 * Disassembly reference: docs/s1disasm/_incObj/0B Pole that Breaks.asm
 */
public class Sonic1PoleThatBreaksObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, SpawnRewindRecreatable {

    // move.w #make_art_tile(ArtTile_LZ_Pole,2,0),obGfx(a0)
    private static final int DISPLAY_PRIORITY = 4;

    // move.b #$E1,obColType(a0), but SPECIAL category is used in-engine to
    // emulate Touch_Special/obColProp polling without automatic hurt response.
    private static final int COLLISION_FLAGS = 0x40 | 0x21;

    // move.w obX(a0),d0 / addi.w #$14,d0
    private static final int GRAB_X_OFFSET = 0x14;

    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            true,
            true,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    // subi.w #$18,d0
    private static final int CLIMB_MIN_Y_OFFSET = 0x18;
    // addi.w #$24,d0 (after -$18), so max is obY + $0C
    private static final int CLIMB_RANGE = 0x24;

    // move.b #1,obFrame(a0) when pole breaks.
    private static final int FRAME_NORMAL = 0;
    private static final int FRAME_BROKEN = 1;

    private enum Routine {
        ACTION,   // routine 2
        DISPLAY   // routine 4
    }

    private Routine routine = Routine.ACTION;
    private int collisionFlags = COLLISION_FLAGS;
    private int mappingFrame = FRAME_NORMAL;

    // objoff_30 / objoff_32
    private int poleTime;
    private boolean poleGrabbed;

    // obColProp emulation signal from touch callback.
    private boolean touchSignal;

    public Sonic1PoleThatBreaksObjectInstance(ObjectSpawn spawn) {
        super(spawn, "PoleThatBreaks");
        int subtype = spawn.subtype() & 0xFF;
        this.poleTime = subtype * 60;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (routine != Routine.ACTION) {
            return;
        }

        if (poleGrabbed) {
            updateGrabbedPlayer(player);
            return;
        }

        tryGrabPlayer(player);
    }

    private void tryGrabPlayer(AbstractPlayableSprite player) {
        if (!touchSignal || player == null || player.isCpuControlled()) {
            return;
        }

        int grabX = getX() + GRAB_X_OFFSET;
        if (player.getCentreX() <= grabX) {
            return;
        }

        // ROM clears obColProp after passing the X-side check.
        touchSignal = false;

        // cmpi.b #4,obRoutine(a1) / bhs.s Pole_Display
        if (player.isHurt() || player.getDead()) {
            return;
        }

        // clr.w obVelX(a1) / clr.w obVelY(a1)
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);

        // ROM Obj0B writes only obX(a1)'s pixel word; x_sub is preserved.
        // docs/s1disasm/_incObj/0B LZ Pole that Breaks.asm: .grab move.w d0,obX(a1)
        player.setCentreXPreserveSubpixel((short) grabX);

        // bclr #0,obStatus(a1)
        player.setDirection(Direction.RIGHT);

        // ROM: move.b #id_Hang,obAnim(a1)
        // Clear any forcedAnimationId left by wind tunnels (FLOAT2) since the ROM
        // has no separate forced field — obAnim is simply overwritten by the pole.
        player.setForcedAnimationId(-1);
        player.setAnimationId(Sonic1AnimationIds.HANG);

        // move.b #1,(f_playerctrl).w -- bit 0 only, sign bit CLEAR
        // (docs/s1disasm/_incObj/0B LZ Pole that Breaks.asm:102). Bit 0 makes
        // Sonic_Control skip Sonic_Modes; the object-interaction gate is the sign
        // bit (tst.b f_playerctrl / bmi.s .ignoreobjcoll,
        // docs/s1disasm/_incObj/01 Sonic.asm:94-97), so ReactToItem keeps running
        // while Sonic hangs on the pole.
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);

        // move.b #1,(f_wtunnelallow).w
        setWindTunnelDisabled(true);

        // move.b #1,pole_grabbed(a0)
        poleGrabbed = true;
    }

    private void updateGrabbedPlayer(AbstractPlayableSprite player) {
        if (player == null) {
            releasePlayer(null);
            return;
        }

        if (poleTime != 0) {
            poleTime--;
            if (poleTime == 0) {
                mappingFrame = FRAME_BROKEN;
                releasePlayer(player);
                return;
            }
        }

        int minY = getY() - CLIMB_MIN_Y_OFFSET;
        if (player.isUpPressed()) {
            int newY = player.getCentreY() - 1;
            if (newY < minY) {
                newY = minY;
            }
            player.setCentreYPreserveSubpixel((short) newY);
        }

        int maxY = minY + CLIMB_RANGE;
        if (player.isDownPressed()) {
            int newY = player.getCentreY() + 1;
            if (newY > maxY) {
                newY = maxY;
            }
            player.setCentreYPreserveSubpixel((short) newY);
        }

        if (player.isJumpJustPressed()) {
            mappingFrame = FRAME_BROKEN;
            releasePlayer(player);
            return;
        }
    }

    private void releasePlayer(AbstractPlayableSprite player) {
        // clr.b obColType(a0)
        collisionFlags = 0;

        // addq.b #2,obRoutine(a0)
        routine = Routine.DISPLAY;

        // clr.b (f_playerctrl).w / clr.b (f_wtunnelallow).w
        if (player != null) {
            ObjectControlState.none().applyTo(player);
        }
        setWindTunnelDisabled(false);

        // clr.b pole_grabbed(a0)
        poleGrabbed = false;
        touchSignal = false;
    }

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return touchSignal ? 1 : 0;
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
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (routine != Routine.ACTION || poleGrabbed || player == null || player.isCpuControlled()) {
            return;
        }
        touchSignal = true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.LZ_BREAKABLE_POLE);
        if (renderer == null) return;
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(DISPLAY_PRIORITY);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (!services().configuration().getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED)) {
            return;
        }

        int x = getX();
        int y = getY();
        ctx.drawCross(x, y, 4, 0.9f, 0.8f, 0.2f);
        ctx.drawRect(x, y, 8, 0x20, 0.2f, 0.8f, 1.0f);

        int minY = y - CLIMB_MIN_Y_OFFSET;
        int maxY = minY + CLIMB_RANGE;
        ctx.drawLine(x + GRAB_X_OFFSET, minY, x + GRAB_X_OFFSET, maxY, 0.4f, 1.0f, 0.4f);
        ctx.drawWorldLabel(x, y, -2, "Pole t=" + poleTime + " f=" + mappingFrame
                + (poleGrabbed ? " GRAB" : ""), DebugColor.CYAN);
    }

    private void setWindTunnelDisabled(boolean disabled) {
        ZoneFeatureProvider provider = services().zoneFeatureProvider();
        if (provider instanceof Sonic1ZoneFeatureProvider sonic1Provider) {
            sonic1Provider.setWindTunnelDisabled(disabled);
        }
    }
}
