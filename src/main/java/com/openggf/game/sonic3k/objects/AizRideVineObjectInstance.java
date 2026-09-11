package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
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
 * S3K Obj 0x06 - AIZ Ride Vine.
 *
 * <p>Primary disassembly references:
 * Obj_AIZRideVine / Obj_AIZRideVineHandle (sonic3k.asm:46098-46748).
 */
public class AizRideVineObjectInstance extends AbstractObjectInstance
        implements PostPlayerUpdateHook, SpawnRewindRecreatable {
    private static final int ROOT_FRAME = 0x21;
    private static final int HANDLE_FRAME = 0x20;
    private static final int PRIORITY_BUCKET = 4; // priority $200
    private static final int SEGMENT_GAP = 0x10;
    private static final int MOVE_STEP_X = 8; // addi.l #$80000
    private static final int MOVE_STEP_Y = 2; // addi.l #$20000
    private static final int STILL_ANIM_STEP_FRAMES = 4;
    private static final int STILL_ANIM_FIRST_LOOP_FRAME = 5;
    private static final int STILL_ANIM_LAST_LOOP_FRAME = 8;

    private enum State {
        WAIT_FOR_GRAB,
        ZIP_TO_TARGET,
        SWING_DAMPED,
        PENDULUM_DECAY,
        SIN_SWING,
        STILL_SPRITE
    }

    private static final class Segment {
        int x;
        int y;
        int angle;
        int value3A;
        int mappingFrame;
    }

    private int subtype;
    private int targetX;

    private int currentX;
    private int currentY;
    private final Segment first = new Segment();
    private final Segment[] chain = {
            new Segment(),
            new Segment(),
            new Segment()
    };
    private final AizVineHandleLogic.State handle = new AizVineHandleLogic.State();
    private boolean childSlotsReserved;

    private State state = State.WAIT_FOR_GRAB;
    private int rootAngle;
    private int root3A;
    private int root2E;
    private int root38;
    private int root42;
    private int root44;

    private boolean firstCopiesParent;
    private int first2E;
    private int first38;
    private int first3A;

    private int rootFrame = ROOT_FRAME;
    private int stillXVel = 0x800;
    private int stillYVel = 0x200;
    private int stillFrame;
    private int stillAnimTimer;

    public AizRideVineObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AIZRideVine");
        this.subtype = spawn.subtype();
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.targetX = currentX + ((subtype & 0x7F) << 4);

        first.x = currentX;
        first.y = currentY;
        for (int i = 0; i < chain.length; i++) {
            chain[i].x = currentX;
            chain[i].y = currentY + ((i + 1) * SEGMENT_GAP);
        }
        handle.x = currentX;
        handle.y = currentY + (4 * SEGMENT_GAP);
        handle.prevX = handle.x;
        handle.prevY = handle.y;
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
        // Obj_AIZRideVine allocates the first link, three more chain links,
        // then rewrites the last child as the handle (sonic3k.asm:46115-46142).
        return 5;
    }

    @Override
    public boolean isPersistent() {
        // loc_21F38 always applies the root's coarse-X cull, then deletes the
        // complete child chain before Delete_Current_Sprite. The handle grab
        // bytes do not participate in the root lifetime decision.
        return false;
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        // loc_21F38 uses the fixed native $280 threshold, not the engine's
        // viewport-scaled legacy window.
        int coarseBack = (cameraX - 0x80) & 0xFF80;
        int distance = ((currentX & 0xFF80) - coarseBack) & 0xFFFF;
        return distance > 0x280;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        reserveRomChildSlots();
        updateRootState();
        updateSegments();
        updateHandle(player);
        updateDynamicSpawn(currentX, currentY);
        // Off-screen lifecycle is handled by the Placement system (see comment in
        // AizGiantRideVineObjectInstance).
    }

    private void reserveRomChildSlots() {
        if (childSlotsReserved || getSlotIndex() < 0) {
            return;
        }
        childSlotsReserved = true;
        ObjectServices svc = tryServices();
        if (svc != null && svc.objectManager() != null) {
            svc.objectManager().allocateChildSlotsAfter(
                    spawn, getReservedChildSlotCount(), getSlotIndex());
        }
    }

    @Override
    public void onUnload() {
        clearGrabbedPlayers();
    }

    @Override
    public void updatePostPlayer(int frameCounter, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        AbstractPlayableSprite sidekick = firstTrackedSidekick();
        AizVineHandleLogic.updatePostPlayer(handle, player, sidekick);
    }

    private void updateRootState() {
        switch (state) {
            case WAIT_FOR_GRAB -> {
                if (AizVineHandleLogic.anyGrabbed(handle)) {
                    state = State.ZIP_TO_TARGET;
                    first2E = 1;
                    first38 = 0;
                }
            }
            case ZIP_TO_TARGET -> {
                currentX += MOVE_STEP_X;
                currentY += MOVE_STEP_Y;
                if (currentX >= targetX) {
                    if ((subtype & 0x80) != 0) {
                        state = State.STILL_SPRITE;
                        rootFrame = 0;
                        stillFrame = 0;
                        stillAnimTimer = 0;
                        AizVineHandleLogic.markGrabbedAsFastEject(handle);
                    } else {
                        state = State.SWING_DAMPED;
                        firstCopiesParent = true;
                        first3A = 0;
                        handle.mode = 1;
                        rootAngle = 0;
                        root3A = 0x400;
                    }
                }
            }
            case SWING_DAMPED -> updateSwingDamped();
            case PENDULUM_DECAY -> updatePendulumDecay();
            case SIN_SWING -> updateSinSwing();
            case STILL_SPRITE -> updateStillSprite();
        }
    }

    private void updateSwingDamped() {
        int d0 = root3A;
        int d1 = Math.abs(signedAngleByte(rootAngle));
        d0 -= d1 + d1;
        rootAngle = asSigned16(rootAngle - d0);

        if (!AizVineHandleLogic.anyGrabbed(handle)) {
            int angleByte = (angleByte(rootAngle) + 8) & 0xFF;
            if (angleByte < 0x10) {
                state = State.PENDULUM_DECAY;
                root42 = 0;
                root44 = -0x300;
                root38 = 0x1000;
                root2E = 0;
                handle.mode = 2;
            }
        }
    }

    private void updatePendulumDecay() {
        int d2 = angleByte(root38);
        int d0 = root44;
        if (root2E == 0) {
            d0 += d2;
            root44 = asSigned16(d0);
            root42 = asSigned16(root42 + root44);
            if (signedAngleByte(root42) >= 0) {
                int damp = root44 >> 4;
                root44 = asSigned16(root44 - damp);
                root2E = 1;
                if (root38 == 0x0C00) {
                    state = State.SIN_SWING;
                    root38 = 0;
                    handle.mode = 0;
                } else {
                    root38 = asSigned16(root38 - 0x40);
                }
            }
        } else {
            d0 -= d2;
            root44 = asSigned16(d0);
            root42 = asSigned16(root42 + root44);
            if (signedAngleByte(root42) < 0) {
                int damp = root44 >> 4;
                root44 = asSigned16(root44 - damp);
                root2E = 0;
                if (root38 == 0x0C00) {
                    state = State.SIN_SWING;
                    root38 = 0;
                    handle.mode = 0;
                } else {
                    root38 = asSigned16(root38 - 0x40);
                }
            }
        }

        rootAngle = asSigned16(root42);
        root3A = rootAngle >> 3;
        first3A = root3A;
    }

    private void updateSinSwing() {
        int angle = angleByte(root38);
        root38 = asSigned16(root38 + 0x200);
        int sin = TrigLookupTable.sinHex(angle) << 2;
        if (sin == 0x400) {
            sin = 0x3FF;
        }
        rootAngle = asSigned16(sin);
        root3A = rootAngle;
        first3A = root3A;
    }

    private void updateSegments() {
        updateFirstSegment();

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

    private void updateFirstSegment() {
        if (firstCopiesParent) {
            first.angle = rootAngle;
        } else {
            if (first2E == 0) {
                int angle = angleByte(first38);
                first38 = asSigned16(first38 + 0x200);
                int sin = TrigLookupTable.sinHex(angle) << 2;
                if (sin == 0x400) {
                    sin = 0x3FF;
                }
                first3A = asSigned16(sin);
            } else {
                int angle = angleByte(first38);
                first38 = asSigned16(first38 + 0x100);
                first3A = asSigned16(TrigLookupTable.sinHex(angle) << 3);
            }
            first.angle = first3A;
        }

        first.value3A = first3A;
        first.mappingFrame = ((angleByte(first.angle) + 4) & 0xFF) >> 3;
        first.x = currentX;
        first.y = currentY;
    }

    private void updateHandle(AbstractPlayableSprite player) {
        Segment lastSegment = chain[chain.length - 1];
        AizVineHandleLogic.positionFromParent(handle, lastSegment.x, lastSegment.y, lastSegment.angle);
        AbstractPlayableSprite sidekick = firstTrackedSidekick();
        AizVineHandleLogic.updatePlayers(handle, services(), player, sidekick, lastSegment.angle);
        if (services().levelManager() != null && services().levelManager().objectsExecuteAfterPlayerPhysics()) {
            AizVineHandleLogic.updatePostPlayer(handle, player, sidekick);
        }
    }

    private void updateStillSprite() {
        currentX += stillXVel >> 8;
        currentY += stillYVel >> 8;
        // loc_21DF2 tests the render_flags bit produced by the preceding
        // Render_Sprites pass after MoveSprite. Once the 8x12 still-sprite
        // bounds were wholly off-screen, it writes x_pos=$7FF0 so loc_21F38
        // deletes the root and its child chain in this same object pass.
        if (!wasStillSpriteRenderedOnScreen()) {
            currentX = 0x7FF0;
        }

        stillAnimTimer++;
        if (stillAnimTimer < STILL_ANIM_STEP_FRAMES) {
            return;
        }
        stillAnimTimer = 0;

        if (stillFrame == 0) {
            stillFrame = STILL_ANIM_FIRST_LOOP_FRAME;
            return;
        }

        stillFrame++;
        if (stillFrame > STILL_ANIM_LAST_LOOP_FRAME) {
            stillFrame = STILL_ANIM_FIRST_LOOP_FRAME;
        }
    }

    private boolean wasStillSpriteRenderedOnScreen() {
        ObjectServices svc = tryServices();
        if (svc == null || svc.camera() == null) {
            return true;
        }
        // Object execution sees the camera position completed by the preceding
        // frame, the same position whose copy Render_Sprites used to produce
        // this frame's incoming render_flags bit. Keep the ROM's fixed 320x224
        // viewport here; the $7FF0 self-delete gate is not widescreen-scaled.
        int relativeX = currentX - svc.camera().getX();
        int relativeY = currentY - svc.camera().getY();
        return relativeX + 8 >= 0 && relativeX - 8 < 320
                && relativeY + 12 >= 0 && relativeY - 12 < 224;
    }

    private void clearGrabbedPlayers() {
        AbstractPlayableSprite player = resolveMainPlayer();
        AbstractPlayableSprite sidekick = firstTrackedSidekick();
        clearControlFor(player, handle.p1.grabFlag != 0);
        clearControlFor(sidekick, handle.p2.grabFlag != 0);
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

        if (state == State.STILL_SPRITE) {
            PatternSpriteRenderer stillRenderer = renderManager.getRenderer(Sonic3kObjectArtKeys.ANIMATED_STILL_SPRITES);
            if (stillRenderer != null && stillRenderer.isReady()) {
                stillRenderer.drawFrameIndex(stillFrame, currentX, currentY, false, false);
            } else {
                renderer.drawFrameIndex(rootFrame, currentX, currentY, false, false);
            }
        } else {
            renderer.drawFrameIndex(rootFrame, currentX, currentY, false, false);
        }
        renderer.drawFrameIndex(first.mappingFrame, first.x, first.y, false, false);
        for (Segment segment : chain) {
            renderer.drawFrameIndex(segment.mappingFrame, segment.x, segment.y, false, false);
        }
        if (AizVineHandleLogic.shouldRender(handle)) {
            renderer.drawFrameIndex(HANDLE_FRAME, handle.x, handle.y, false, false);
        }
    }

    private static int asSigned16(int value) {
        return (short) value;
    }

    private static int angleByte(int angleWord) {
        return (angleWord >> 8) & 0xFF;
    }

    private static int signedAngleByte(int angleWord) {
        return (byte) angleByte(angleWord);
    }

    private static int[] offsetFromAngle(int angle) {
        int byteAngle = (angleByte(angle) + 4) & 0xF8;
        int sin = TrigLookupTable.sinHex(byteAngle);
        int cos = TrigLookupTable.cosHex(byteAngle);
        return new int[]{(-sin + 8) >> 4, (cos + 8) >> 4};
    }
}
