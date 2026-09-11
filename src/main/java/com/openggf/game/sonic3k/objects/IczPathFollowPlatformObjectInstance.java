package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0xB0 - ICZ path-follow platform.
 *
 * <p>ROM reference: {@code Obj_ICZPathFollowPlatform}
 * (sonic3k.asm:187354-187940). The subtype pair selects the starting
 * routine from {@code byte_89FB2}: stand-triggered falling platform, pushed
 * floor follower, inert platform, or a stand-triggered sink/rebound platform.
 */
public class IczPathFollowPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_ICZPathFollowPlatform} is installed from the S3K object pointer table at
     * {@code $00089F32} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:187359).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0008}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0008;
    }


    private enum Phase {
        WAIT_STANDING(0x02),
        JITTER_WAIT(0x04),
        WAIT_PUSH(0x06),
        FOLLOW_FLOOR(0x08),
        FALLING(0x0A),
        STOPPED(0x0C),
        WAIT_STANDING_SINK(0x0E),
        SINKING(0x10),
        REBOUNDING(0x12);

        private final int routineByte;

        Phase(int routineByte) {
            this.routineByte = routineByte;
        }
    }

    private static final String ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS;
    private static final int PRIORITY_BUCKET = 5; // ObjDat priority $280.
    private static final int MAPPING_FRAME = 0;
    private static final int PALETTE_LINE = 2;

    // ObjDat_ICZPathFollowPlatform: width=$20, height=$14, then explicit x/y radii $20/$12.
    // SolidObjectFull call at loc_89F64 uses d1=$2B, d2=$14, d3=$14.
    private static final int SOLID_HALF_WIDTH = 0x2B;
    private static final int SOLID_HALF_HEIGHT = 0x14;
    // width_pixels(a0) from ObjDat_ICZPathFollowPlatform (sonic3k.asm:187886, set by
    // SetUp_ObjAttributes at sonic3k.asm:176908). This is the byte the player on-object
    // balance routine reads (Sonic_Move move.b width_pixels(a1),d1 at sonic3k.asm:22455),
    // which differs from the $2B SolidObjectFull X-collision half-width above.
    private static final int BALANCE_WIDTH_PIXELS = 0x20;
    private static final SolidObjectParams SOLID_PARAMS =
            SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);

    private static final int JITTER_TIMER = 0x0F; // loc_89FC0: move.w #$F,$2E(a0).
    private static final int PUSH_DELAY = 0x10; // loc_8A00A: cmpi.b #$10,$39(a0).
    private static final int PUSH_SPEED = 0x80; // loc_8A020-loc_8A030.
    private static final int MAX_SPEED = 0x0C00; // sub_8A35A/sub_8A36C.
    private static final int FALL_ACCEL = 0x38; // sub_8A36C.
    private static final int FLOOR_Y_RADIUS = 0x12;
    private static final int FLOOR_LOST_THRESHOLD = 8; // loc_8A04C: cmpi.w #8,d1.
    private static final int WALL_RIGHT_SENSOR_X = 0x1C;
    private static final int WALL_LEFT_SENSOR_X = -0x1C;
    private static final int FALLING_WALL_RIGHT_SENSOR_X = 0x20;
    private static final int FALLING_WALL_LEFT_SENSOR_X = -0x20;
    private static final int REVEALED_SPRING_X = 0x5D5A;
    private static final int REVEALED_SPRING_Y = 0x027A;

    private int spawnX;
    private int spawnY;
    private boolean hFlip;

    private Phase phase;
    private int x;
    private int y;
    private int xSub;
    private int ySub;
    private int xVel;
    private int yVel;
    private int angle;
    private int waitTimer;
    private int pushCounter;
    private boolean standingThisFrame;
    private boolean pushingThisFrame;
    private int pushingPlayerX;

    public IczPathFollowPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ICZPathFollowPlatform");
        this.spawnX = spawn.x();
        this.spawnY = spawn.y();
        this.x = spawn.x();
        this.y = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.phase = initialPhase(spawn.subtype());
        updateDynamicSpawn(x, y);
    }

    @Override
    public IczPathFollowPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new IczPathFollowPlatformObjectInstance(ctx.spawn());
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // SolidObjectFull's unsigned broad-X gate branches on BHI, so +d1 is contact.
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // Dynamic spawn coordinates follow the moving platform, but native
        // standing/pushing bits remain owned by this one SST instance.
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        boolean standing = standingThisFrame;
        boolean pushing = pushingThisFrame;
        int pusherX = pushingPlayerX;
        standingThisFrame = false;
        pushingThisFrame = false;

        switch (phase) {
            case WAIT_STANDING -> {
                if (standing) {
                    phase = Phase.JITTER_WAIT;
                    waitTimer = JITTER_TIMER;
                }
            }
            case JITTER_WAIT -> updateJitterWait(vIntRunCount);
            case WAIT_PUSH -> updateWaitPush(pushing, pusherX);
            case FOLLOW_FLOOR -> updateFollowFloor(playerEntity);
            case FALLING -> updateFalling();
            case WAIT_STANDING_SINK -> {
                if (standing) {
                    phase = Phase.SINKING;
                }
            }
            case SINKING -> {
                if (standing) {
                    y++;
                } else {
                    phase = Phase.REBOUNDING;
                    yVel = 0;
                }
            }
            case REBOUNDING -> updateRebound();
            case STOPPED -> {
            }
        }

        updateDynamicSpawn(x, y);
    }

    private static Phase initialPhase(int subtype) {
        return switch ((subtype & 0xFF) >> 1) {
            case 1 -> Phase.WAIT_PUSH;
            case 2 -> Phase.STOPPED;
            case 3 -> Phase.WAIT_STANDING_SINK;
            default -> Phase.WAIT_STANDING;
        };
    }

    private void updateJitterWait(int vIntRunCount) {
        // loc_89FD6 reads V_int_run_count+3, not Level_frame_counter.
        // ObjectManager supplies its VBlank clock; the service resolves the
        // independent low-bit phase retained for legacy S3K replay starts.
        ObjectServices svc = tryServices();
        int vIntLowByte = svc != null ? svc.resolveVIntRunCount(vIntRunCount) : vIntRunCount;
        x += (vIntLowByte & 1) == 0 ? 1 : -1;
        if (waitTimer-- <= 0) {
            phase = Phase.FALLING;
        }
    }

    private void updateWaitPush(boolean pushing, int pusherX) {
        if (!pushing) {
            pushCounter = 0;
            return;
        }

        pushCounter++;
        if (pushCounter < PUSH_DELAY) {
            return;
        }

        phase = Phase.FOLLOW_FLOOR;
        xVel = pusherX < x ? PUSH_SPEED : -PUSH_SPEED;
    }

    private void updateFollowFloor(PlayableEntity playerEntity) {
        applyVerticalWrapMaskIfNeeded();
        moveSprite2();

        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDistWithFlipAwareAngle(x, y, FLOOR_Y_RADIUS);
        if (!floor.foundSurface() || floor.distance() >= FLOOR_LOST_THRESHOLD) {
            phase = Phase.FALLING;
            seedFallVelocityFromSlope();
            return;
        }

        angle = romObjectFloorAngle(floor.angle());
        y += floor.distance();

        int absAngle = angle;
        if ((absAngle & 0x80) != 0) {
            absAngle = (-((byte) absAngle)) & 0xFF;
        }
        if ((absAngle & 0xF8) != 0) {
            int adjusted = xVel + romSlopeAccelerationDelta(angle);
            clampXVelocity(adjusted);
        }

        if (xVel > 0) {
            TerrainCheckResult wall = ObjectTerrainUtils.checkRightWallDist(x + WALL_RIGHT_SENSOR_X, y);
            if (wall.distance() < 0) {
                stopFollowFloorAgainstWall(true, playerEntity);
            }
        } else if (xVel < 0) {
            TerrainCheckResult wall = ObjectTerrainUtils.checkLeftWallDist(x + WALL_LEFT_SENSOR_X, y);
            if (wall.distance() < 0) {
                stopFollowFloorAgainstWall(false, playerEntity);
            }
        }
    }

    private void updateFalling() {
        clampYVelocity(yVel + FALL_ACCEL);
        moveSprite2();

        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDistWithFlipAwareAngle(x, y, FLOOR_Y_RADIUS);
        if (floor.distance() < 0) {
            angle = romObjectFloorAngle(floor.angle());
            phase = Phase.FOLLOW_FLOOR;
            seedXVelocityFromFall();
            return;
        }

        if (xVel > 0) {
            TerrainCheckResult wall = ObjectTerrainUtils.checkRightWallDist(x + FALLING_WALL_RIGHT_SENSOR_X, y);
            if (wall.distance() < 0) {
                stopFallingAgainstWall(wall.distance());
            }
        } else if (xVel < 0) {
            TerrainCheckResult wall = ObjectTerrainUtils.checkLeftWallDist(x + FALLING_WALL_LEFT_SENSOR_X, y);
            if (wall.distance() < 0) {
                stopFallingAgainstWall(wall.distance());
            }
        }
    }

    private void updateRebound() {
        yVel = sign16(yVel - 0x20); // loc_8A1AE: addi.w #-$20,y_vel(a0).
        moveSprite2();
        if (y <= spawnY) {
            phase = Phase.WAIT_STANDING_SINK;
            y = spawnY;
            yVel = 0;
            ySub = 0;
        }
    }

    private void seedFallVelocityFromSlope() {
        int speed = Math.abs(xVel);
        int selector = ((angle & 0xFF) >> 3) & 0x0E;
        if (selector == 0 || selector == 8) {
            yVel = 0;
        } else if (selector == 4 || selector == 12) {
            yVel = speed;
        } else {
            yVel = speed >> 1;
        }
    }

    private void seedXVelocityFromFall() {
        int velocity = yVel;
        if ((angle & 0x40) != 0) {
            velocity = -velocity;
        }
        xVel = sign16(velocity);
    }

    private void stopFollowFloorAgainstWall(boolean rightWall, PlayableEntity playerEntity) {
        phase = Phase.STOPPED;
        xVel = 0;
        yVel = 0;
        xSub = 0;
        ySub = 0;
        if (rightWall && (spawn.subtype() & 0xFF) != 0) {
            spawnBreakDebris();
            spawnRevealedSpring();
            displacePlayerOffObject(playerEntity);
            // loc_8A0AA jumps to loc_85088 after allocating the spring.
            setDestroyedByOffscreen();
        }
    }

    /**
     * loc_8A0AA: {@code CreateChild6_Simple(ChildObjDat_8A42A)} shatters the
     * platform into 6 debris pieces (loc_8A1D4) at the platform's position.
     */
    private void spawnBreakDebris() {
        for (IczPlatformBreakDebris.Spec spec : IczPlatformBreakDebris.buildSpecs(x, y)) {
            spawnChild(() -> new IczPlatformBreakDebris(spec));
        }
    }

    private void stopFallingAgainstWall(int wallDistance) {
        x += wallDistance;
        xVel = 0;
    }

    private void spawnRevealedSpring() {
        spawnChild(() -> new Sonic3kSpringObjectInstance(new ObjectSpawn(
                REVEALED_SPRING_X, REVEALED_SPRING_Y,
                Sonic3kObjectIds.SPRING, 0, 0, false, 0)));
    }

    private void displacePlayerOffObject(PlayableEntity playerEntity) {
        if (playerEntity == null) {
            return;
        }
        ObjectServices services = tryServices();
        if (services != null && services.objectManager() != null) {
            services.objectManager().clearRidingObject(playerEntity);
        }
        playerEntity.setOnObject(false);
        playerEntity.setAir(true);
    }

    private void requestFastVerticalScrollIfMoving() {
        if (xVel == 0 && yVel == 0) {
            return;
        }
        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        Camera camera = services.camera();
        if (camera != null) {
            camera.requestFastVerticalScroll();
        }
    }

    private void applyVerticalWrapMaskIfNeeded() {
        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        Camera camera = services.camera();
        if (camera != null && camera.isVerticalWrapEnabled()) {
            int range = camera.getVerticalWrapRange();
            if (range > 0 && (range & (range - 1)) == 0) {
                y &= range - 1;
            }
        }
    }

    private void moveSprite2() {
        SubpixelMotion.State motion = new SubpixelMotion.State(x, y, xSub, ySub, xVel, yVel);
        SubpixelMotion.moveSprite2(motion);
        x = motion.x;
        y = motion.y;
        xSub = motion.xSub;
        ySub = motion.ySub;
        xVel = sign16(motion.xVel);
        yVel = sign16(motion.yVel);
    }

    private void clampXVelocity(int value) {
        if (value >= -MAX_SPEED && value <= MAX_SPEED) {
            xVel = sign16(value);
        }
    }

    private void clampYVelocity(int value) {
        if (value >= -MAX_SPEED && value <= MAX_SPEED) {
            yVel = sign16(value);
        }
    }

    private static int romSlopeAccelerationDelta(int angle) {
        // loc_8A066: add.w d3,d3; ext.w d3; asr.w #1,d3. This is not angle / 2.
        return ((byte) ((angle << 1) & 0xFF)) >> 1;
    }

    private static int romObjectFloorAngle(byte rawAngle) {
        int result = rawAngle & 0xFF;
        // ObjCheckFloorDist clears odd Primary_Angle values before returning d3.
        return (result & 0x01) != 0 ? 0 : result;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    /**
     * ROM Sonic_Move / Tails_Move on-object balance reads {@code width_pixels(a1)}
     * ($20 here, sonic3k.asm:22455/27825), NOT the $2B SolidObjectFull X-collision
     * half-width. The shared default of 16 px shifts the {@code d1 = player_x +
     * width - object_x} balance window inward by 16 px, which spuriously flipped a
     * rider's facing to LEFT (status bit0) on this wide platform when the ROM kept
     * it RIGHT (ICZ1 trace f3116).
     */
    @Override
    public int getBalanceWidthPixels() {
        return BALANCE_WIDTH_PIXELS;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (contact == null) {
            return;
        }
        if (contact.standing()) {
            standingThisFrame = true;
            // sub_8A3C4 runs after SolidObjectFull and sees a newly established
            // p1_standing_bit in this same object pass.
            requestFastVerticalScrollIfMoving();
        }
        if (contact.pushing() && player != null) {
            pushingThisFrame = true;
            pushingPlayerX = player.getCentreX();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(MAPPING_FRAME, x, y, hFlip, false, PALETTE_LINE);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx != null) {
            ctx.drawRect(x, y, SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, 0.2f, 0.8f, 1.0f);
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
    public int getOutOfRangeReferenceX() {
        // ROM Sprite_OnScreen_Test loads x_pos(a0), not the original placement X.
        return x;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public String traceDebugDetails() {
        return String.format(
                "phase=%02X vel=(%04X,%04X) sub=(%04X,%04X) angle=%02X wait=%02X push=%02X stand=%s pushFlag=%s",
                phase.routineByte & 0xFF,
                xVel & 0xFFFF,
                yVel & 0xFFFF,
                xSub & 0xFFFF,
                ySub & 0xFFFF,
                angle & 0xFF,
                waitTimer & 0xFF,
                pushCounter & 0xFF,
                standingThisFrame,
                pushingThisFrame);
    }

    public int getRoutineByteForTesting() {
        return phase.routineByte;
    }

    public int getWaitTimerForTesting() {
        return waitTimer;
    }

    public int getXVelocityForTesting() {
        return xVel;
    }

    public int getYVelocityForTesting() {
        return yVel;
    }

    public int getXSubpixelForTesting() {
        return xSub;
    }

    public int getMappingFrameForTesting() {
        return MAPPING_FRAME;
    }

    public String getArtKeyForTesting() {
        return ART_KEY;
    }

    /** Break-debris specs as {subtype, x, y, xVel, yVel} rows (ChildObjDat_8A42A). */
    public static int[][] breakDebrisSpecsForTesting(int x, int y) {
        List<IczPlatformBreakDebris.Spec> specs = IczPlatformBreakDebris.buildSpecs(x, y);
        int[][] rows = new int[specs.size()][];
        for (int i = 0; i < specs.size(); i++) {
            IczPlatformBreakDebris.Spec spec = specs.get(i);
            rows[i] = new int[]{spec.subtype(), spec.x(), spec.y(), spec.xVel(), spec.yVel()};
        }
        return rows;
    }

    private static int sign16(int value) {
        return (short) value;
    }

    /**
     * loc_8A1D4: one of the 6 platform-break debris pieces. Renders via
     * {@code ObjDat3_8A412} (Map_ICZPlatforms + ArtTile_ICZMisc1) with the raw
     * animation {@code byte_8A43E} and falls with gravity, deleting once
     * offscreen ({@code AnimateRaw_MoveChkDel} -> {@code Sprite_CheckDeleteXY}).
     */
    public static final class IczPlatformBreakDebris extends GravityDebrisChild
            implements SpawnTrailingZeroIntsRewindRecreatable {
        private static final String DEBRIS_ART_KEY = Sonic3kObjectArtKeys.ICZ_PLATFORMS;
        private static final int GRAVITY = 0x70; // MoveSprite gravity.
        private static final int INITIAL_MAPPING_FRAME = 0x23; // ObjDat3_8A412.
        // byte_8A43E: timer, then frames $23,$13,$24,$14, restart ($FC).
        private static final int[] RAW_ANIMATION = {2, 0x23, 0x13, 0x24, 0x14, 0xFC};

        // byte_8A200: child_dx/child_dy per subtype (0,2,...,10).
        private static final int[][] OFFSETS = {
                {-0x14, -0x09},
                { 0x14, -0x0C},
                { 0x14,  0x00},
                { 0x0C,  0x0C},
                {-0x08,  0x09},
                {-0x04, -0x06}
        };
        // Obj_VelocityIndex entries 2..7, selected by Set_IndexedVelocity(d0=8).
        // Child render_flags start cleared, so no per-piece x_vel flip applies.
        private static final int[][] VELOCITY_INDEX = {
                {-0x200, -0x200},
                { 0x200, -0x200},
                {-0x300, -0x200},
                { 0x300, -0x200},
                {-0x200, -0x200},
                {      0, -0x200}
        };

        private int mappingFrame = INITIAL_MAPPING_FRAME;
        private int animFrame;
        private int animFrameTimer;

        private IczPlatformBreakDebris(Spec spec) {
            super(new ObjectSpawn(spec.x(), spec.y(), Sonic3kObjectIds.ICZ_PATH_FOLLOW_PLATFORM,
                    spec.subtype(), 0, false, spec.y()),
                    "ICZPlatformBreakDebris", spec.xVel(), spec.yVel(), GRAVITY);
        }

        private IczPlatformBreakDebris(ObjectSpawn spawn, int ignored) {
            super(spawn, "ICZPlatformBreakDebris", 0, 0, GRAVITY);
        }

        private static List<Spec> buildSpecs(int x, int y) {
            List<Spec> specs = new ArrayList<>(OFFSETS.length);
            for (int i = 0; i < OFFSETS.length; i++) {
                int[] offset = OFFSETS[i];
                int[] velocity = VELOCITY_INDEX[i];
                specs.add(new Spec(i * 2, x + offset[0], y + offset[1], velocity[0], velocity[1]));
            }
            return specs;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            animateRaw();
            super.update(vIntRunCount, player);
        }

        private void animateRaw() {
            animFrameTimer--;
            if (animFrameTimer >= 0) {
                return;
            }
            animFrame = (animFrame + 1) & 0xFF;
            int scriptIndex = animFrame + 1;
            int value = scriptIndex < RAW_ANIMATION.length ? RAW_ANIMATION[scriptIndex] : 0xFC;
            if ((value & 0x80) != 0) {
                mappingFrame = RAW_ANIMATION[1];
                animFrameTimer = RAW_ANIMATION[0];
                animFrame = 0;
                return;
            }
            animFrameTimer = RAW_ANIMATION[0];
            mappingFrame = value;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(1); // ObjDat3_8A412 priority $80.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(DEBRIS_ART_KEY);
            if (renderer != null) {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false, PALETTE_LINE);
            }
        }

        private record Spec(int subtype, int x, int y, int xVel, int yVel) {
        }
    }
}
