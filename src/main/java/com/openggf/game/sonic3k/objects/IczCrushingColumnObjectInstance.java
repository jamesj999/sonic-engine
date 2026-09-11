package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.Knuckles;

import java.util.List;

/**
 * Object 0xAF - ICZ crushing column.
 *
 * <p>ROM reference: {@code Obj_ICZCrushingColumn}
 * (sonic3k.asm:187924-188150). The object runs its movement routine before the
 * per-frame {@code SolidObjectFull} call, so contact triggers are latched from
 * the previous frame and consumed by the next update.
 */
public class IczCrushingColumnObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_ICZCrushingColumn} is installed from the S3K object pointer table at
     * {@code $0008A44A} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:187929).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0008}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0008;
    }


    private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_WALL_AND_COLUMN;

    // ObjDat_ICZCrushingColumn: priority $280, width $20, height $70, frame 2.
    private static final int PRIORITY_BUCKET = 5;
    private static final int DEFAULT_MAPPING_FRAME = 2;
    private static final int CEILING_MAPPING_FRAME = 0x0C;
    private static final int BOTTOM_DECORATION_FRAME = 0x0D;
    private static final int BOTTOM_DECORATION_Y_OFFSET = 0xB0;
    private static final int ON_SCREEN_HALF_WIDTH = 0x20;
    private static final int ON_SCREEN_HALF_HEIGHT = 0x70;
    private static final int SOLID_HALF_WIDTH = 0x2B;
    private static final int SOLID_HALF_HEIGHT = 0x70;
    private static final int TERRAIN_Y_RADIUS = 0x70;

    // loc_8A488 / Obj_Wait timers.
    private static final int INITIAL_TIMER = 0x1F;
    private static final int RESET_TIMER = 0x5F;

    // loc_8A51C / loc_8A562 velocity ramps.
    private static final int CRUSH_ACCEL = 0x20;
    private static final int MAX_UP_VELOCITY = -0x400;
    private static final int MAX_DOWN_VELOCITY = 0x400;

    // loc_8A586 / loc_8A5AC / loc_8A5C8 fixed pixel steps.
    private static final int FAST_DOWN_STEP = 8;
    private static final int RETURN_STEP = 1;

    private static final int ROUTINE_WAIT_STANDING = 0x02;
    private static final int ROUTINE_TIMER_TO_UP = 0x04;
    private static final int ROUTINE_WAIT_PLAYER_SIDE = 0x06;
    private static final int ROUTINE_TIMER_TO_FAST_DOWN = 0x08;
    private static final int ROUTINE_KNUCKLES_ONLY = 0x0A;
    private static final int ROUTINE_CRUSH_UP = 0x0C;
    private static final int ROUTINE_CRUSH_DOWN = 0x0E;
    private static final int ROUTINE_FAST_DOWN = 0x10;
    private static final int ROUTINE_WAIT_AFTER_IMPACT = 0x12;
    private static final int ROUTINE_RETURN_UP = 0x14;
    private static final int ROUTINE_RETURN_DOWN = 0x16;
    private static final int ROUTINE_WAIT_CLEAR_TO_RETURN_UP = 0x18;

    private int spawnY;
    private int subtype;
    private boolean xFlip;
    private boolean yFlip;
    private boolean hasBottomDecoration;

    private int x;
    private int y;
    private int ySub;
    private int yVel;
    private int routine;
    private int timer = INITIAL_TIMER;
    private int mappingFrame = DEFAULT_MAPPING_FRAME;
    private boolean standingLatched;
    private boolean decorationChildSpawned;
    private boolean renderDecorationInParent = true;
    private boolean initialDispatchPending = true;

    public IczCrushingColumnObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZCrushingColumn");
        this.spawnY = spawn.y();
        this.x = spawn.x();
        this.y = spawn.y();
        this.subtype = spawn.subtype() & 0xFF;
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.yFlip = (spawn.renderFlags() & 0x02) != 0;
        this.hasBottomDecoration = subtype < 3;
        if (hasBottomDecoration) {
            this.mappingFrame = CEILING_MAPPING_FRAME;
        }
        resetToSubtypeRoutine();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        ensureDecorationChild();
        // Native routine 0 only installs attributes/child state and selects
        // subtype*2; it returns before running that selected movement routine.
        if (initialDispatchPending) {
            initialDispatchPending = false;
            updateDynamicSpawn(x, y);
            return;
        }
        switch (routine) {
            case ROUTINE_WAIT_STANDING -> updateWaitStanding();
            case ROUTINE_TIMER_TO_UP -> updateTimer(ROUTINE_CRUSH_UP);
            case ROUTINE_WAIT_PLAYER_SIDE -> updateWaitPlayerSide(playerEntity);
            case ROUTINE_TIMER_TO_FAST_DOWN -> updateTimer(ROUTINE_FAST_DOWN);
            case ROUTINE_KNUCKLES_ONLY -> updateKnucklesOnly(playerEntity);
            case ROUTINE_CRUSH_UP -> updateCrushUp();
            case ROUTINE_CRUSH_DOWN -> updateCrushDown();
            case ROUTINE_FAST_DOWN -> updateFastDown();
            case ROUTINE_WAIT_AFTER_IMPACT -> updateWaitAfterImpact();
            case ROUTINE_RETURN_UP -> updateReturnUp();
            case ROUTINE_RETURN_DOWN -> updateReturnDown();
            case ROUTINE_WAIT_CLEAR_TO_RETURN_UP -> updateWaitClearToReturnUp(playerEntity);
            default -> {
            }
        }
        updateDynamicSpawn(x, y);
    }

    private void updateWaitStanding() {
        if (standingLatched) {
            routine = ROUTINE_CRUSH_UP;
        }
        standingLatched = false;
    }

    private void updateTimer(int nextRoutine) {
        timer--;
        if (timer < 0) {
            routine = nextRoutine;
        }
    }

    private void updateWaitPlayerSide(PlayableEntity playerEntity) {
        if (playerEntity == null) {
            return;
        }
        int side = otherObjectHorizontalSide(playerEntity);
        if (horizontalDistance(playerEntity) < 0x28) {
            return;
        }
        if (xFlip) {
            side -= 2;
        }
        if (side != 0) {
            routine = ROUTINE_FAST_DOWN;
        }
    }

    private void updateKnucklesOnly(PlayableEntity playerEntity) {
        // ROM tests Player_1 character_id against 2 before keeping subtype $05.
        if (!(playerEntity instanceof Knuckles)) {
            setDestroyed(true);
        }
    }

    private void updateCrushUp() {
        int next = yVel - CRUSH_ACCEL;
        if (next > MAX_UP_VELOCITY) {
            yVel = next;
        }
        moveYByVelocity();
        TerrainCheckResult ceiling = checkCeilingDistance();
        if (ceiling.distance() < 0) {
            impact(ceiling.distance());
        }
    }

    private void updateCrushDown() {
        int next = yVel + CRUSH_ACCEL;
        if (next <= MAX_DOWN_VELOCITY) {
            yVel = next;
        }
        moveYByVelocity();
        TerrainCheckResult floor = checkFloorDistance();
        if (floor.distance() < 0) {
            impact(floor.distance());
        }
    }

    private void updateFastDown() {
        y += FAST_DOWN_STEP;
        TerrainCheckResult floor = checkFloorDistance();
        if (floor.distance() < 0) {
            impact(floor.distance());
        }
    }

    private void impact(int terrainDistance) {
        // loc_8A540 adds the signed terrain correction to the integer y_pos
        // word only. The low subpixel word survives the impact and therefore
        // sets the phase of every later return/crush cycle.
        y += terrainDistance;
        routine = ROUTINE_WAIT_AFTER_IMPACT;
        timer = INITIAL_TIMER;
        playSfx(Sonic3kSfx.MECHA_LAND.id);
    }

    private void updateWaitAfterImpact() {
        timer--;
        if (timer >= 0) {
            return;
        }
        routine = routineAfterImpact();
    }

    private int routineAfterImpact() {
        return switch (subtype) {
            case 1, 2 -> ROUTINE_RETURN_DOWN;
            case 3 -> ROUTINE_WAIT_CLEAR_TO_RETURN_UP;
            case 4 -> ROUTINE_RETURN_UP;
            default -> routine;
        };
    }

    private void updateReturnUp() {
        int nextY = y - RETURN_STEP;
        if (nextY <= spawnY) {
            timer = RESET_TIMER;
            resetToSubtypeRoutine();
            return;
        }
        y = nextY;
    }

    private void updateReturnDown() {
        int nextY = y + RETURN_STEP;
        if (nextY >= spawnY) {
            timer = RESET_TIMER;
            resetToSubtypeRoutine();
            return;
        }
        y = nextY;
    }

    private void updateWaitClearToReturnUp(PlayableEntity playerEntity) {
        if (playerEntity == null) {
            return;
        }
        int side = otherObjectHorizontalSide(playerEntity);
        if (xFlip) {
            side -= 2;
        }
        if (side == 0) {
            routine = ROUTINE_RETURN_UP;
        }
    }

    private void resetToSubtypeRoutine() {
        routine = subtype * 2;
    }

    private void moveYByVelocity() {
        // ROM MoveSprite2 adds y_vel<<8 to the full y_pos:y_sub longword; keep
        // the subpixel carry rather than doing integer-only y += y_vel >> 8.
        int combined = (y << 16) + (ySub & 0xFFFF) + (yVel << 8);
        y = combined >> 16;
        ySub = combined & 0xFFFF;
    }

    private int horizontalDistance(PlayableEntity playerEntity) {
        return Math.abs(sign16(x - playerEntity.getCentreX()));
    }

    private int otherObjectHorizontalSide(PlayableEntity playerEntity) {
        return sign16(x - playerEntity.getCentreX()) < 0 ? 2 : 0;
    }

    private static int sign16(int value) {
        value &= 0xFFFF;
        return value >= 0x8000 ? value - 0x10000 : value;
    }

    protected TerrainCheckResult checkCeilingDistance() {
        // ObjCheckCeilingDist enters S3K FindFloor with the upward EOR-$F
        // coordinate transform and the X-indexed height map.
        return ObjectTerrainUtils.checkNativeUpwardCeilingDist(x, y, TERRAIN_Y_RADIUS);
    }

    protected TerrainCheckResult checkFloorDistance() {
        return ObjectTerrainUtils.checkFloorDist(x, y, TERRAIN_Y_RADIUS);
    }

    private void playSfx(int sfxId) {
        var services = tryServices();
        if (services != null) {
            services.playSfx(sfxId);
        }
    }

    private void ensureDecorationChild() {
        if (!hasBottomDecoration || decorationChildSpawned) {
            return;
        }
        var services = tryServices();
        if (services == null || services.objectManager() == null) {
            return;
        }
        spawnChild(() -> new BottomDecoration(this));
        decorationChildSpawned = true;
        renderDecorationInParent = false;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        // SolidObjectFull is called after every routine; solidity follows
        // physical existence, not the internal crushing/return state.
        return !isDestroyed();
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // The moving column rewrites its engine spawn coordinates as it travels,
        // but ROM standing/pushing bits live in this one SST's status byte.
        return true;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // S3K SolidObjectFull's broad X gate rejects only with unsigned bhi;
        // relX == d1*2 remains a valid zero-distance side contact.
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (contact != null && contact.standing()) {
            standingLatched = true;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, x, y, xFlip, yFlip, 2);
        if (hasBottomDecoration && renderDecorationInParent) {
            renderer.drawFrameIndex(BOTTOM_DECORATION_FRAME, x, y + BOTTOM_DECORATION_Y_OFFSET,
                    xFlip, yFlip, 2);
        }
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public int getOnScreenHalfWidth() {
        return ON_SCREEN_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return ON_SCREEN_HALF_HEIGHT;
    }

    public int getRoutineByteForTesting() {
        return routine;
    }

    public int getTimerForTesting() {
        return timer;
    }

    public int getYVelocityForTesting() {
        return yVel;
    }

    public int getYSubpixelForTesting() {
        return ySub;
    }

    public int getMappingFrameForTesting() {
        return mappingFrame;
    }

    public String getArtKeyForTesting() {
        return ART_KEY;
    }

    public boolean hasBottomDecorationForTesting() {
        return hasBottomDecoration;
    }

    private static final class BottomDecoration extends AbstractObjectInstance implements RewindRecreatable {
        private final IczCrushingColumnObjectInstance parent;

        private BottomDecoration(IczCrushingColumnObjectInstance parent) {
            super(new ObjectSpawn(parent.x, parent.y + BOTTOM_DECORATION_Y_OFFSET,
                    parent.spawn.objectId(), parent.subtype, parent.spawn.renderFlags(), false,
                    parent.y + BOTTOM_DECORATION_Y_OFFSET), "ICZCrushingColumnDecoration");
            this.parent = parent;
        }

        @Override
        public BottomDecoration recreateForRewind(RewindRecreateContext ctx) {
            IczCrushingColumnObjectInstance liveParent =
                    RewindRecreateObjectLinks.nearestLiveObject(ctx, IczCrushingColumnObjectInstance.class);
            if (liveParent == null) {
                return null;
            }
            return new BottomDecoration(liveParent);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer != null) {
                renderer.drawFrameIndex(BOTTOM_DECORATION_FRAME, getX(), getY(),
                        parent.xFlip, parent.yFlip, 2);
            }
        }

        @Override
        public int getX() {
            return parent.x;
        }

        @Override
        public int getY() {
            return parent.y + BOTTOM_DECORATION_Y_OFFSET;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY_BUCKET);
        }
    }
}
