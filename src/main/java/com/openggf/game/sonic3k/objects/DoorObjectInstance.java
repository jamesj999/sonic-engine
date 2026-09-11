package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x3C - Door (Sonic 3 &amp; Knuckles).
 * <p>
 * A solid door that slides open when a player enters its trigger zone.
 * Two variants selected by the subtype sign bit:
 * <ul>
 *   <li><b>Vertical door</b> (subtype &ge; 0): Door slides up when the player enters
 *       from the trigger side. Subtype low bits select the HCZ/CNZ/DEZ variant.</li>
 *   <li><b>Horizontal door</b> (subtype &lt; 0): Door slides left/right when player
 *       enters the trigger band above or below it. Used in CNZ.</li>
 * </ul>
 * <p>
 * Art is loaded from the level's pattern buffer (zone-specific tiles). The door does not
 * animate its mappings in the ROM; opening is represented only by moving the solid object.
 * <p>
 * ROM reference: Obj_Door (sonic3k.asm:66036), loc_30FD2 (horizontal variant).
 */
public class DoorObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable,
        RomObjectCodePointerProvider {

    private static final int ROM_CODE_POINTER_HIGH_WORD = 0x0003;

    private static final int VERTICAL_HEIGHT = 0x20;
    private static final int VERTICAL_PRIORITY = 3;

    private static final int HORIZONTAL_WIDTH = 0x20;
    private static final int HORIZONTAL_HEIGHT = 8;
    private static final int HORIZONTAL_PRIORITY = 2;

    private static final int SLIDE_SPEED = 0x08;
    private static final int SLIDE_MAX = 0x40;

    private static final int VERTICAL_TRIGGER_NEAR = 0x18;
    private static final int VERTICAL_TRIGGER_FAR = 0x200;
    private static final int VERTICAL_TRIGGER_HALF_HEIGHT = 0x20;

    private static final int HORIZONTAL_TRIGGER_NEAR = 0x18;
    private static final int HORIZONTAL_TRIGGER_FAR = 0x100;
    private static final int HORIZONTAL_TRIGGER_HALF_WIDTH = 0x20;

    private boolean horizontal;
    private boolean xFlipped;
    private boolean yFlipped;
    private int baseX;
    private int baseY;
    private int halfWidth;
    private int halfHeight;
    private int priority;
    private final String artKey;
    private int triggerMin;
    private int triggerMax;

    private int slideOffset;
    private boolean playerInTriggerPreviousFrame;

    public DoorObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Door");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.horizontal = (spawn.subtype() & 0x80) != 0;
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;
        this.yFlipped = (spawn.renderFlags() & 0x02) != 0;

        int actualSubtype = spawn.subtype() & 0x7F;

        if (horizontal) {
            this.halfWidth = HORIZONTAL_WIDTH;
            this.halfHeight = HORIZONTAL_HEIGHT;
            this.priority = HORIZONTAL_PRIORITY;
            this.artKey = Sonic3kObjectArtKeys.DOOR_HORIZONTAL;
            int top = baseY - HORIZONTAL_TRIGGER_FAR;
            int bottom = baseY + HORIZONTAL_TRIGGER_NEAR;
            if (yFlipped) {
                top += HORIZONTAL_TRIGGER_FAR - HORIZONTAL_TRIGGER_NEAR;
                bottom += HORIZONTAL_TRIGGER_FAR - HORIZONTAL_TRIGGER_NEAR;
            }
            this.triggerMin = top;
            this.triggerMax = bottom;
        } else {
            VerticalDoorVariant variant = resolveVerticalVariant(actualSubtype);
            this.halfWidth = variant.halfWidth();
            this.halfHeight = VERTICAL_HEIGHT;
            this.priority = VERTICAL_PRIORITY;
            this.artKey = variant.artKey();
            this.triggerMin = xFlipped ? baseX - VERTICAL_TRIGGER_NEAR : baseX - VERTICAL_TRIGGER_FAR;
            this.triggerMax = xFlipped ? baseX + VERTICAL_TRIGGER_FAR : baseX + VERTICAL_TRIGGER_NEAR;
        }

        this.slideOffset = 0;
        this.playerInTriggerPreviousFrame = false;
    }

    @Override
    public DoorObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new DoorObjectInstance(ctx.spawn());
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // Both Obj_Door variants install routines in the $00030xxx-$00031xxx
        // range, so word 0 of their SST code pointer is $0003. S3K sub_13EFC
        // retains and later compares this word through Tails_CPU_interact
        // (docs/skdisasm/sonic3k.asm:66036-66167,26816-26843).
        return ROM_CODE_POINTER_HIGH_WORD;
    }

    private VerticalDoorVariant resolveVerticalVariant(int subtype) {
        return switch (subtype) {
            case 1 -> new VerticalDoorVariant(Sonic3kObjectArtKeys.DOOR_VERTICAL_CNZ, 8);
            case 2 -> new VerticalDoorVariant(Sonic3kObjectArtKeys.DOOR_VERTICAL_DEZ, 0x10);
            default -> new VerticalDoorVariant(Sonic3kObjectArtKeys.DOOR_VERTICAL_HCZ, 0x10);
        };
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(halfWidth + 0x0B, halfHeight, halfHeight + 1);
    }

    @Override
    public int getOnScreenHalfWidth() {
        // ROM byte_30FCE (sonic3k.asm:66167) sets width_pixels = $20 for the
        // horizontal CNZ door; byte_30E18 sets width_pixels per vertical-door
        // variant (HCZ/CNZ/DEZ). The horizontal half-width $20 is wider than
        // the engine's default 16-px on-screen margin, so the camera+margin
        // gate must use the ROM rendered half-width to match the ROM
        // SolidObject_OnScreenTest (sonic3k.asm:36336-36370). Vertical
        // variants share the same field; using halfWidth here keeps the
        // engine in sync regardless of variant.
        return halfWidth;
    }

    @Override
    public int getOnScreenHalfHeight() {
        // Render_Sprites uses height_pixels for the same render_flags bit-7
        // gate consumed by SolidObjectFull. The horizontal CNZ door's
        // byte_30FCE height is only $08; the default $10 extent makes its
        // solid path visible one frame before the ROM as the camera rises.
        return halfHeight;
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Both door variants call SolidObjectFull. SolidObject_cont rejects
        // the initial X window with bhi, so relX == d1 * 2 remains a valid
        // zero-distance side contact and retains Status_Push
        // (sonic3k.asm:41394-41403,66136-66137,66249-66258).
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        return true;
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity playerEntity) {
        // Obj_Door calls SolidObjectFull for both horizontal and vertical doors
        // after preparing d1/d2/d3/d4 (docs/skdisasm/sonic3k.asm:66136-66137,
        // 66249-66258). SolidObjectFull_1P consumes a stale standing bit with
        // Status_InAir by clearing support and returning d4=0 before
        // SolidObject_cont can reland the player (sonic3k.asm:41017-41035).
        return true;
    }

    @Override
    public boolean airborneRiderUnseatRequiresOwnCheckpoint(PlayableEntity playerEntity) {
        // SolidObjectFull consumes only this door's own a0.d6 standing bit.
        // A later object's checkpoint cannot clear the door ride on its behalf.
        // This matters when a later controller (the CNZ vacuum tube is the
        // native example) sets Status_InAir after the door has re-seated P2;
        // the final frame legitimately contains both OnObj and InAir, and the
        // door clears its stale standing bit when its own slot runs next frame.
        return true;
    }

    @Override
    public boolean suppressesGroundingRecoveryFromAirborneStaleRide(PlayableEntity playerEntity) {
        // Player movement runs before Obj_Door's SolidObjectFull checkpoint.
        // If a later controller set InAir while leaving this door's standing
        // bit intact, movement must consume the airborne routine first; the
        // door then clears its own stale support in object-slot order.
        return true;
    }

    @Override
    public boolean carriesRiderOnHorizontalMove(PlayableEntity playerEntity) {
        // Obj_Door stores the post-slide x_pos in d4 immediately before
        // SolidObjectFull (docs/skdisasm/sonic3k.asm:66123-66137,
        // 66239-66258). MvSonicOnPtfm subtracts current x_pos from d4, so the
        // horizontal carry delta is zero even when the horizontal CNZ door moves.
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (horizontal) {
            updateHorizontalDoor(playerEntity);
        } else {
            updateVerticalDoor(playerEntity);
        }
    }

    private void updateVerticalDoor(PlayableEntity updatePlayer) {
        int left = xFlipped
                ? (playerInTriggerPreviousFrame ? triggerMin : getX())
                : triggerMin;
        int right = xFlipped
                ? triggerMax
                : (playerInTriggerPreviousFrame ? triggerMax : getX());

        int top = baseY - VERTICAL_TRIGGER_HALF_HEIGHT;
        int bottom = baseY + VERTICAL_TRIGGER_HALF_HEIGHT;

        boolean playerInTrigger = isNativePlayerInTrigger(updatePlayer, left, right, top, bottom);

        playerInTriggerPreviousFrame = playerInTrigger;
        updateSlideOffset(playerInTrigger);
    }

    private void updateHorizontalDoor(PlayableEntity updatePlayer) {
        int top = yFlipped
                ? (playerInTriggerPreviousFrame ? triggerMin : getY())
                : triggerMin;
        int bottom = yFlipped
                ? triggerMax
                : (playerInTriggerPreviousFrame ? triggerMax : getY());

        int left = baseX - HORIZONTAL_TRIGGER_HALF_WIDTH;
        int right = baseX + HORIZONTAL_TRIGGER_HALF_WIDTH;

        boolean playerInTrigger = isNativePlayerInTrigger(updatePlayer, left, right, top, bottom);

        playerInTriggerPreviousFrame = playerInTrigger;
        updateSlideOffset(playerInTrigger);
    }

    private boolean isNativePlayerInTrigger(PlayableEntity updatePlayer, int left, int right, int top, int bottom) {
        boolean sawQueryParticipant = false;
        try {
            for (PlayableEntity candidate : services().playerQuery().playersFor(
                    ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
                sawQueryParticipant = true;
                if (isPlayerInTrigger(candidate, left, right, top, bottom)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // Unit tests may instantiate this object without injected services.
        }
        return !sawQueryParticipant && isPlayerInTrigger(updatePlayer, left, right, top, bottom);
    }

    private boolean isPlayerInTrigger(PlayableEntity player, int left, int right, int top, int bottom) {
        if (!(player instanceof AbstractPlayableSprite sprite) || sprite.isObjectControlled()) {
            return false;
        }

        int px = sprite.getCentreX();
        int py = sprite.getCentreY();
        return px >= left && px < right && py >= top && py < bottom;
    }

    private void updateSlideOffset(boolean playerInZone) {
        if (playerInZone) {
            if (slideOffset < SLIDE_MAX) {
                slideOffset += SLIDE_SPEED;
                if (slideOffset > SLIDE_MAX) {
                    slideOffset = SLIDE_MAX;
                }
                if (slideOffset == SLIDE_MAX) {
                    playDoorSound();
                }
            }
        } else {
            if (slideOffset > 0) {
                slideOffset -= SLIDE_SPEED;
                if (slideOffset < 0) {
                    slideOffset = 0;
                }
                if (slideOffset == 0) {
                    playDoorSound();
                }
            }
        }
    }

    private void playDoorSound() {
        // ROM: horizontal door variant (loc_31034) has no Play_SFX calls;
        // only vertical doors (loc_30E8C) play sfx_FanLatch at endpoints.
        if (horizontal) {
            return;
        }
        try {
            services().playSfx(Sonic3kSfx.FAN_LATCH.id);
        } catch (Exception e) {
            // Ignore audio errors
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(0, getX(), getY(), false, false);
        }
    }

    @Override
    public int getX() {
        if (!horizontal) {
            return baseX;
        }
        return xFlipped ? baseX - slideOffset : baseX + slideOffset;
    }

    @Override
    public int getY() {
        if (horizontal) {
            return baseY;
        }
        return baseY - slideOffset;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(priority);
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    private record VerticalDoorVariant(String artKey, int halfWidth) {
    }
}
