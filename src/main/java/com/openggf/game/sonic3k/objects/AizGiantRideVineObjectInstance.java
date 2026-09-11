package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.sonic3k.AizVineAngleProvider;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PostPlayerUpdateHook;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K Obj 0x0C - AIZ Giant Ride Vine.
 *
 * <p>Primary disassembly references:
 * Obj_AIZGiantRideVine (sonic3k.asm:46749-46963).
 */
public class AizGiantRideVineObjectInstance extends AbstractObjectInstance
        implements PostPlayerUpdateHook, SpawnRewindRecreatable {
    private static final int ROOT_FRAME = 0x21;
    private static final int HANDLE_FRAME = 0x20;
    private static final int PRIORITY_BUCKET = 4; // priority $200
    private static final int SEGMENT_GAP = 0x10;
    private static final int ACTIVATED_SWING_STEP = 0x08;
    private static final int ACTIVATED_SWING_INITIAL_VELOCITY = -0x1B0;

    private static final class Segment {
        int x;
        int y;
        int angle;
        int value3A;
        int mappingFrame;
    }

    private int currentX;
    private int currentY;
    private int segmentCount;
    private int phaseOffset;

    private final Segment first;
    private final Segment[] chain;
    private final AizVineHandleLogic.State handle = new AizVineHandleLogic.State();
    private boolean childSlotsReserved;
    private int handleExecutionSlot = -1;
    private boolean activatedSwingStarted;
    private boolean activatedSwingReturning;
    private int activatedSwingAngle;
    private int activatedSwingVelocity;

    public AizGiantRideVineObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AIZGiantRideVine");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        // ROM reuses the last allocated child as the handle (move.l #loc_2257E,(a1)),
        // so the number of actual vine segments before the handle is the low nibble.
        this.segmentCount = spawn.subtype() & 0x0F;
        this.phaseOffset = spawn.subtype() & 0xF0;

        if (segmentCount > 0) {
            this.first = new Segment();
            this.first.x = currentX;
            this.first.y = currentY;
            this.chain = new Segment[Math.max(0, segmentCount - 1)];
            for (int i = 0; i < chain.length; i++) {
                chain[i] = new Segment();
                chain[i].x = currentX;
                chain[i].y = currentY + ((i + 1) * SEGMENT_GAP);
            }
        } else {
            this.first = null;
            this.chain = new Segment[0];
        }

        int handleYOffset = (segmentCount + 1) * SEGMENT_GAP;
        handle.x = currentX;
        handle.y = currentY + handleYOffset;
        handle.prevX = handle.x;
        handle.prevY = handle.y;
        activatedSwingAngle = asSigned16(phaseOffset << 8);
        activatedSwingVelocity = ACTIVATED_SWING_INITIAL_VELOCITY;
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
        return PRIORITY_BUCKET;
    }

    @Override
    public int getReservedChildSlotCount() {
        return romChildSlotCount();
    }

    @Override
    public int getExecutionSlotIndex() {
        // ROM Obj_AIZGiantRideVine keeps the root in its parent slot, then
        // allocates children after it and rewrites the final child to loc_2257E
        // (docs/skdisasm/sonic3k.asm:46749-46787, 46929-46950). sub_220C2
        // player carry runs from that handle child after earlier slots such as
        // Obj_CollapsingPlatform's loc_205DE solid pass
        // (docs/skdisasm/sonic3k.asm:44841-44851). Execute the consolidated
        // Java object at the handle slot once it has been reserved, while
        // retaining the parent slot for lifecycle and child-slot cleanup.
        return handleExecutionSlot >= 0 ? handleExecutionSlot : super.getExecutionSlotIndex();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        reserveRomChildSlots();
        updateSegmentsFromGlobalAngle(currentAizVineAngleWord());
        updateHandle(player);
        // Off-screen lifecycle is handled by the Placement system: non-persistent
        // objects are unloaded when the spawn leaves the window and respawned on
        // re-entry.  The ROM's Delete_Current_Sprite immediately clears the respawn
        // bit, but our setDestroyed() latches until the spawn leaves the window,
        // which can prevent respawn when the vine's cull range is narrower than
        // the Placement window.
    }

    @Override
    public void onUnload() {
        clearGrabbedPlayers();
        // Obj_AIZGiantRideVine loc_22442 always applies the root's coarse-X
        // cull, even while either handle grab byte ($32/$33) is set, then
        // loc_2245C deletes every child before Delete_Current_Sprite removes
        // the root (docs/skdisasm/sonic3k.asm:46802-46831). This Java object
        // executes from its reserved handle slot for SST-order parity, so the
        // manager's execution-slot cleanup cannot identify the distinct parent
        // slot as the current slot. Release that root ownership explicitly.
        ObjectServices svc = tryServices();
        if (svc != null) {
            ObjectLifetimeOps.releaseParentSlotKeepingChildren(svc.objectManager(), this);
        }
    }

    @Override
    public void updatePostPlayer(int frameCounter, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        AbstractPlayableSprite sidekick = firstTrackedSidekick();
        AizVineHandleLogic.updatePostPlayer(handle, player, sidekick);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_RIDE_VINE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(ROOT_FRAME, currentX, currentY, false, false);

        if (first != null) {
            renderer.drawFrameIndex(first.mappingFrame, first.x, first.y, false, false);
            for (Segment segment : chain) {
                renderer.drawFrameIndex(segment.mappingFrame, segment.x, segment.y, false, false);
            }
        }

        if (AizVineHandleLogic.shouldRender(handle)) {
            renderer.drawFrameIndex(HANDLE_FRAME, handle.x, handle.y, false, false);
        }
    }

    private void updateSegmentsFromGlobalAngle(int aizVineAngleWord) {
        if (first == null) {
            return;
        }

        if (activatedSwingStarted) {
            updateActivatedFirstSegment();
        } else {
            updatePassiveFirstSegment(aizVineAngleWord);
        }

        Segment parent = first;
        for (Segment segment : chain) {
            segment.value3A = parent.value3A;
            segment.angle = asSigned16(parent.angle + segment.value3A);
            segment.mappingFrame = ((angleByte(segment.angle) + 4) & 0xFF) >> 3;
            int[] offset = offsetFromAngle(parent.angle);
            segment.x = parent.x + offset[0];
            segment.y = parent.y + offset[1];
            parent = segment;
        }
    }

    private void updatePassiveFirstSegment(int aizVineAngleWord) {
        // loc_2248A default path: angle = sin(AIZ_vine_angle + subtypePhase) * $2C.
        int angleByte = (((aizVineAngleWord >> 8) & 0xFF) + phaseOffset) & 0xFF;
        int sin = TrigLookupTable.sinHex(angleByte);
        first.angle = asSigned16(sin * 0x2C);
        first.value3A = first.angle >> 3;
        first.mappingFrame = ((angleByte(first.angle) + 4) & 0xFF) >> 3;
        first.x = currentX;
        first.y = currentY;
    }

    private void updateActivatedFirstSegment() {
        int velocity = activatedSwingVelocity;
        if (!activatedSwingReturning) {
            velocity = asSigned16(velocity + ACTIVATED_SWING_STEP);
            activatedSwingVelocity = velocity;
            activatedSwingAngle = asSigned16(activatedSwingAngle + velocity);
            if ((byte) angleByte(activatedSwingAngle) >= 0) {
                activatedSwingReturning = true;
            }
        } else {
            velocity = asSigned16(velocity - ACTIVATED_SWING_STEP);
            activatedSwingVelocity = velocity;
            activatedSwingAngle = asSigned16(activatedSwingAngle + velocity);
            if ((byte) angleByte(activatedSwingAngle) < 0) {
                activatedSwingReturning = false;
            }
        }

        first.angle = activatedSwingAngle;
        first.value3A = first.angle >> 3;
        first.mappingFrame = ((angleByte(first.angle) + 4) & 0xFF) >> 3;
        first.x = currentX;
        first.y = currentY;
    }

    private void updateHandle(AbstractPlayableSprite player) {
        int parentX;
        int parentY;
        int parentAngle;
        if (first == null) {
            parentX = currentX;
            parentY = currentY;
            parentAngle = 0;
        } else if (chain.length == 0) {
            parentX = first.x;
            parentY = first.y;
            parentAngle = first.angle;
        } else {
            Segment parent = chain[chain.length - 1];
            parentX = parent.x;
            parentY = parent.y;
            parentAngle = parent.angle;
        }

        AizVineHandleLogic.positionFromParent(handle, parentX, parentY, parentAngle);
        AbstractPlayableSprite sidekick = firstTrackedSidekick();
        AizVineHandleLogic.updatePlayers(handle, services(), player, sidekick, parentAngle);
        // sub_220C2's giant-vine grab path only writes the handle's per-player
        // grab byte at $32/$33 and player fields (docs/skdisasm/sonic3k.asm:
        // 46731-46743). It does not alter the first child; loc_2248A continues
        // to read AIZ_vine_angle on subsequent frames (sonic3k.asm:46840-46854).
        if (services().levelManager() != null && services().levelManager().objectsExecuteAfterPlayerPhysics()) {
            AizVineHandleLogic.updatePostPlayer(handle, player, sidekick);
        }
    }

    private void reserveRomChildSlots() {
        if (childSlotsReserved || getSlotIndex() < 0) {
            return;
        }
        childSlotsReserved = true;
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return;
        }
        int childCount = romChildSlotCount();
        if (childCount > 0) {
            int[] childSlots = svc.objectManager().allocateChildSlotsAfter(spawn, childCount, getSlotIndex());
            handleExecutionSlot = childSlots[childSlots.length - 1];
        }
    }

    private int romChildSlotCount() {
        // Obj_AIZGiantRideVine allocates one child, then dbf allocates the
        // remaining low-nibble count; the final child is rewritten as the handle.
        return segmentCount + 1;
    }

    private void clearGrabbedPlayers() {
        if (handle.p1.grabFlag != 0) {
            clearControlFor(resolveMainPlayer(), true);
        }
        if (handle.p2.grabFlag != 0) {
            clearControlFor(firstTrackedSidekick(), true);
        }
        handle.p1.grabFlag = 0;
        handle.p2.grabFlag = 0;
    }

    private AbstractPlayableSprite firstTrackedSidekick() {
        return services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick
                ? sidekick
                : null;
    }

    private AbstractPlayableSprite resolveMainPlayer() {
        var sprite = services().spriteManager().getSprite(
                ActiveGameplayTeamResolver.resolveMainCharacterCode(config()));
        return sprite instanceof AbstractPlayableSprite playable ? playable : null;
    }

    private static void clearControlFor(AbstractPlayableSprite player, boolean wasGrabbed) {
        if (player == null || !wasGrabbed) {
            return;
        }
        AizVineHandleLogic.clearPlayerControl(player);
    }

    private int currentAizVineAngleWord() {
        ObjectServices svc = tryServices();
        if (svc == null || svc.levelManager() == null) {
            return 0;
        }
        return svc.levelManager().getAnimatedPatternManager() instanceof AizVineAngleProvider provider
                ? provider.aizVineAngleWord()
                : 0;
    }

    private static int asSigned16(int value) {
        return (short) value;
    }

    private static int angleByte(int angleWord) {
        return (angleWord >> 8) & 0xFF;
    }

    private static int[] offsetFromAngle(int angle) {
        int byteAngle = (angleByte(angle) + 4) & 0xF8;
        int sin = TrigLookupTable.sinHex(byteAngle);
        int cos = TrigLookupTable.cosHex(byteAngle);
        return new int[]{(-sin + 8) >> 4, (cos + 8) >> 4};
    }
}
