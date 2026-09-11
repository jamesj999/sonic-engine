package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * S3K S3KL object $14 - Launch Base trigger bridge.
 *
 * <p>ROM reference: {@code Obj_LBZTriggerBridge} (sonic3k.asm:51653-51804).
 */
public final class LbzTriggerBridgeInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_LBZTriggerBridge} is installed from the S3K object pointer table at
     * {@code $00025F6A} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:51658).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }

    private static final int TABLE_ENTRY_SIZE = 8;
    private static final int TRIGGERED_TABLE_OFFSET = 4;
    private static final int TABLE_INDEX_MASK = 0x38;
    private static final int CHILD_SUBTYPE_OFFSET = 0x40;
    private static final int SUBTYPE_MASK = 0x7F;
    private static final int TRIGGER_INDEX_MASK = 0x0F;
    private static final int SOLID_WIDTH_EXTRA = 0x0B;
    private static final int OFFSCREEN_X = 0x7FFF;
    private static final int PRIORITY_BUCKET = 4; // ROM priority(a0) = $200

    private static final int ROUTINE_WAIT_OPEN = 0;
    private static final int ROUTINE_OPENING_EXPIRE = 2;
    private static final int ROUTINE_CLOSING_TO_WAIT_CLOSE = 4;
    private static final int ROUTINE_WAIT_CLOSE = 6;
    private static final int ROUTINE_CLOSING_EXPIRE = 8;
    private static final int ROUTINE_OPENING_TO_WAIT_OPEN = 0x0A;

    // byte_25F2A: x offset, y offset, width, height, initial routine/frame, child routine/frame.
    private static final int[][] BRIDGE_TABLE = {
            { 0x48, 0x00, 0x08, 0x40, 0x00, 0x00, 0x0A, 0x08 },
            { 0x00, 0x48, 0x40, 0x08, 0x00, 0x08, 0x0A, 0x10 },
            { -0x48, 0x00, 0x08, 0x40, 0x00, 0x00, 0x0A, 0x08 },
            { 0x00, 0x48, 0x40, 0x08, 0x00, 0x10, 0x0A, 0x18 },
            { 0x00, 0x48, 0x40, 0x08, 0x06, 0x08, 0x04, 0x10 },
            { 0x48, 0x00, 0x08, 0x40, 0x06, 0x00, 0x04, 0x08 },
            { 0x00, 0x48, 0x40, 0x08, 0x06, 0x10, 0x04, 0x18 },
            { -0x48, 0x00, 0x08, 0x40, 0x06, 0x00, 0x04, 0x08 }
    };

    private int baseX;
    private int baseY;
    private int triggerIndex;
    private int widthPixels;
    private int heightPixels;

    private int routine;
    private int mappingFrame;
    private int timer;

    public LbzTriggerBridgeInstance(ObjectSpawn spawn) {
        this(spawn, false, 0);
    }

    LbzTriggerBridgeInstance(ObjectSpawn spawn, boolean childInit, int childTimer) {
        super(spawn, "LBZTriggerBridge");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.triggerIndex = spawn.subtype() & TRIGGER_INDEX_MASK;

        int[] attrs = BRIDGE_TABLE[tableIndex(spawn.subtype(), childInit)];
        updateDynamicSpawn(baseX + attrs[0], baseY + attrs[1]);
        this.widthPixels = attrs[2];
        this.heightPixels = attrs[3];
        this.routine = attrs[childInit ? 6 : 4];
        this.mappingFrame = attrs[childInit ? 7 : 5];
        this.timer = childInit ? childTimer : 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        switch (routine) {
            case ROUTINE_WAIT_OPEN -> waitForOpenTrigger();
            case ROUTINE_OPENING_EXPIRE, ROUTINE_CLOSING_EXPIRE -> animateThenMoveOffscreen();
            case ROUTINE_CLOSING_TO_WAIT_CLOSE -> decrementFrameThenAdvanceRoutine();
            case ROUTINE_WAIT_CLOSE -> waitForCloseTrigger();
            case ROUTINE_OPENING_TO_WAIT_OPEN -> decrementFrameThenWaitOpen();
            default -> {
            }
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM loc_25FFE: d1 = width_pixels + $B, d2 = height_pixels, d3 = d2 + 1.
        return SolidObjectParams.of(widthPixels + SOLID_WIDTH_EXTRA, heightPixels, heightPixels + 1);
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return !isDestroyed() && getX() != OFFSCREEN_X;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public int getOnScreenHalfWidth() {
        return widthPixels + SOLID_WIDTH_EXTRA;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return heightPixels + 1;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        // ROM loc_2602E feeds saved $30(a0), not live x_pos, to Sprite_OnScreen_Test2.
        return baseX;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || getX() == OFFSCREEN_X) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_TRIGGER_BRIDGE);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
        }
    }

    int currentRoutineForTest() {
        return routine;
    }

    int currentMappingFrameForTest() {
        return mappingFrame;
    }

    int currentTimerForTest() {
        return timer;
    }

    private static int tableIndex(int subtype, boolean childInit) {
        int selector = subtype & SUBTYPE_MASK;
        if (!childInit && Sonic3kLevelTriggerManager.testAny(subtype & TRIGGER_INDEX_MASK)) {
            selector += TRIGGERED_TABLE_OFFSET;
        }
        return ((selector >> 1) & TABLE_INDEX_MASK) / TABLE_ENTRY_SIZE;
    }

    private void waitForOpenTrigger() {
        if (!Sonic3kLevelTriggerManager.testAny(triggerIndex)) {
            return;
        }
        mappingFrame++;
        timer = 7;
        routine += 2;
        spawnOppositeBridge(8);
    }

    private void animateThenMoveOffscreen() {
        mappingFrame++;
        timer--;
        if (timer == 0) {
            updateDynamicSpawn(OFFSCREEN_X, getY());
        }
    }

    private void decrementFrameThenAdvanceRoutine() {
        mappingFrame--;
        timer--;
        if (timer == 0) {
            routine += 2;
        }
    }

    private void waitForCloseTrigger() {
        if (Sonic3kLevelTriggerManager.testAny(triggerIndex)) {
            return;
        }
        mappingFrame++;
        timer = 7;
        routine += 2;
        spawnOppositeBridge(8);
    }

    private void decrementFrameThenWaitOpen() {
        mappingFrame--;
        timer--;
        if (timer == 0) {
            routine = ROUTINE_WAIT_OPEN;
        }
    }

    private void spawnOppositeBridge(int childTimer) {
        if (tryServices() == null || tryServices().objectManager() == null) {
            return;
        }
        int childSubtype = (spawn.subtype() + CHILD_SUBTYPE_OFFSET) & SUBTYPE_MASK;
        spawnChild(() -> new LbzTriggerBridgeInstance(
                new ObjectSpawn(baseX, baseY, Sonic3kObjectIds.LBZ_TRIGGER_BRIDGE,
                        childSubtype, spawn.renderFlags(), false, spawn.rawYWord()),
                true,
                childTimer));
    }
}
