package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.MgzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x57 - MGZ Trigger Platform.
 *
 * <p>ROM: Obj_MGZTriggerPlatform (sonic3k.asm:70910-71029).
 * The high subtype nibble selects one of three table-driven platform shapes:
 * a horizontal escape platform (nibble $0) or vertical trigger platforms
 * (nibbles $1 and $2) that move 1px/frame or 2px/frame once their trigger fires.
 *
 * <p>Subtype bits:
 * <ul>
 *   <li>Bits [7:4]: config index into byte_34568</li>
 *   <li>Bits [3:0]: Level_trigger_array index to monitor</li>
 * </ul>
 *
 * <p>Render/status bit 0 reverses the movement direction for both horizontal
 * and vertical variants.
 */
public class MGZTriggerPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider,
        SpawnRewindRecreatable {

    private static final String ART_KEY = Sonic3kObjectArtKeys.MGZ_TRIGGER_PLATFORM;
    private static final int PRIORITY_BUCKET = 5; // ROM: priority = $280


    private enum Mode {
        HORIZONTAL_DELETE,
        VERTICAL_MOVE
    }

    private int triggerIndex;
    private int frameIndex;
    private int widthPixels;
    private int heightPixels;
    private int totalFrames;
    private int stepPerFrame;
    private int direction;
    private Mode mode;

    private int currentX;
    private int currentY;
    private int remainingFrames;
    private boolean activated;
    private boolean completed;

    public MGZTriggerPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MGZTriggerPlatform");

        int highNibble = spawn.subtype() & 0xF0;
        int configIndex = highNibble >> 4;
        if (configIndex > 2) {
            configIndex = 2;
        }

        this.widthPixels = switch (configIndex) {
            case 0 -> 0x40;
            case 1, 2 -> 0x20;
            default -> 0x20;
        };
        this.heightPixels = switch (configIndex) {
            case 0 -> 0x1E;
            case 1, 2 -> 0x40;
            default -> 0x40;
        };
        this.frameIndex = (configIndex == 0) ? 0 : 1;
        this.totalFrames = 0x40;
        this.stepPerFrame = configIndex;
        this.mode = (configIndex == 0) ? Mode.HORIZONTAL_DELETE : Mode.VERTICAL_MOVE;

        this.triggerIndex = spawn.subtype() & 0x0F;
        this.direction = ((spawn.renderFlags() & 0x01) != 0) ? -1 : 1;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.remainingFrames = totalFrames;

        // ROM: vertical variants spawned after the trigger has already fired are
        // immediately fast-forwarded to their final Y and marked complete.
        if (mode == Mode.VERTICAL_MOVE && Sonic3kLevelTriggerManager.testAny(triggerIndex)) {
            currentY += direction * totalFrames * stepPerFrame;
            remainingFrames = 0;
            completed = true;
        }

        updateDynamicSpawn(currentX, currentY);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        boolean wasActivated = activated;
        if (!completed && Sonic3kLevelTriggerManager.testAny(triggerIndex)) {
            activated = true;
        }

        // Horizontal triggers are published before this native SST slot and
        // loc_34600 moves on the same pass. A vertical dash trigger can sit on
        // either side of the platform in the live SST table. If it is later, a
        // nonzero byte observed by this earlier platform necessarily survived
        // from the preceding pass and is actionable immediately. The fast $2x
        // variant also consumes an earlier source on the same pass. A slow $1x
        // platform allocated after its source retains the one-pass bridge: that
        // engine ordering can be the reverse of the native SST order.
        boolean activationVisibleThisPass = wasActivated
                || (mode == Mode.HORIZONTAL_DELETE && activated)
                || (activated && hasDashTriggerVisibleThisPass());
        if (!completed && activationVisibleThisPass) {
            advanceActiveMotion();
            if (!completed) {
                applyScreenShake(vIntRunCount);
            }
        }

        updateDynamicSpawn(currentX, currentY);
    }

    private boolean hasDashTriggerVisibleThisPass() {
        var svc = tryServices();
        if (svc == null || svc.objectManager() == null || getSlotIndex() < 0) {
            return false;
        }
        for (MGZDashTriggerObjectInstance trigger :
                svc.objectManager().activeObjectsOfType(MGZDashTriggerObjectInstance.class)) {
            if (trigger.triggerIndex() == triggerIndex
                    && trigger.getSlotIndex() >= 0
                    && (trigger.getSlotIndex() > getSlotIndex()
                    || (stepPerFrame == 2 && trigger.getSlotIndex() < getSlotIndex()))) {
                return true;
            }
        }
        return false;
    }

    private void advanceActiveMotion() {
        if (mode == Mode.HORIZONTAL_DELETE) {
            currentX += direction * 2;
        } else {
            currentY += direction * stepPerFrame;
        }

        remainingFrames--;
        if (remainingFrames > 0) {
            return;
        }

        completed = true;

        if (mode == Mode.HORIZONTAL_DELETE) {
            markRemembered();
            setDestroyed(true);
        }
    }

    private void markRemembered() {
        var svc = tryServices();
        if (svc == null) {
            return;
        }
        ObjectManager objectManager = svc.objectManager();
        ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);
    }

    private void applyScreenShake(int vIntRunCount) {
        MgzZoneRuntimeState mgzState = resolveMgzRuntimeState();
        if (mgzState == null) {
            return;
        }
        // ROM: the object only raises Screen_shake_flag; ShakeScreen_Setup
        // (docs/skdisasm/sonic3k.asm:104188-104210) samples
        // ScreenShakeArray2[Level_frame_counter & $3F] once per frame from the
        // zone's background event, and MGZ's screen event consumes the previous
        // frame's sample into Camera_Y_pos_copy (sonic3k.asm:106257-106260,
        // :106308). Both the clock and the one-frame publication now live in
        // that owner, so no object-clock offset is needed here.
        mgzState.requestContinuousScreenShake();
    }

    private MgzZoneRuntimeState resolveMgzRuntimeState() {
        var svc = tryServices();
        if (svc == null || svc.zoneRuntimeRegistry() == null) {
            return null;
        }
        return S3kRuntimeStates.currentMgz(svc.zoneRuntimeRegistry()).orElse(null);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(widthPixels + 0x0B, heightPixels, heightPixels + 1);
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // ROM SolidObjectFull's horizontal entry check rejects only values
        // above d1*2 (bhi), retaining the exact right edge.
        // sonic3k.asm:41390-41401.
        return true;
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // SolidObjectFull2_1P sees this object's retained standing bit before
        // SolidObject_cont. An airborne rider clears the bit and returns without
        // resolving another contact (sonic3k.asm:41066-41084).
        return true;
    }

    @Override
    public boolean suppressesGroundingRecoveryFromAirborneStaleRide(PlayableEntity player) {
        // Player slots run before this later object slot. Preserve the airborne
        // movement pass until SolidObjectFull consumes the stale standing bit.
        return true;
    }

    @Override
    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        // Obj_MGZTriggerPlatform loads d4 from the already-updated x_pos before
        // calling SolidObjectFull, so MvSonicOnPtfm sees no horizontal delta.
        // This is observable while the horizontal variant retracts beneath a
        // rider: the platform moves, but the rider keeps their world X.
        // sonic3k.asm:70991-71005.
        return false;
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // Obj_MGZTriggerPlatform lives at $000345D4 in the locked-on ROM.
        return 0x0003;
    }

    @Override
    public int getOnScreenHalfWidth() {
        // ROM Render_Sprites consumes byte_34568's width_pixels value.
        return widthPixels;
    }

    @Override
    public int getOnScreenHalfHeight() {
        // render_flags bit 2 selects the custom height_pixels visibility path.
        return heightPixels;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (playerEntity == null || contact == null || !contact.standing()
                || (getSpawn().subtype() & 0xF0) != 0x10) {
            return;
        }

        // The two vertical variants are adjacent placements, but a backwards
        // Load_Sprites pass can allocate the right-hand subtype-$1x landing
        // platform before its left-hand subtype-$2x sibling even when the
        // engine's placement slots are reversed. The later native
        // SolidObjectFull then sees the just-grounded player and may publish
        // Status_Push. A right-hand sibling was loaded in the ordinary forward
        // order and has already executed in both engines, so it must not be
        // replayed after the landing.
        // sonic3k.asm:70910-71029,41370-41534.
        int landingSlot = getSlotIndex();
        ObjectManager objectManager = services().objectManager();
        for (MGZTriggerPlatformObjectInstance sibling :
                objectManager.activeObjectsOfType(MGZTriggerPlatformObjectInstance.class)) {
            if (sibling.getSlotIndex() >= landingSlot) {
                break;
            }
            if ((sibling.getSpawn().subtype() & 0xF0) == 0x20
                    && sibling.getSpawn().x() < getSpawn().x()) {
                objectManager.processImmediateInlineSolidCheckpoint(sibling, playerEntity, List.of());
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(frameIndex, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.drawRect(currentX, currentY, widthPixels + 0x0B, heightPixels, 0.2f, 0.9f, 0.2f);
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
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }
}
