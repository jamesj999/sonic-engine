package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * Object 0x4C - CNZ Spiral Tube ({@code Obj_CNZSpiralTube}).
 *
 * <p>Verified S&K-side ROM anchors in {@code sonic3k.asm}:
 * <ul>
 *   <li>{@code Obj_CNZSpiralTube} - top-level controller for Player 1 and Player 2</li>
 *   <li>{@code loc_33102} - capture window and {@code object_control=$81} handoff</li>
 *   <li>{@code loc_3318E} - pre-route sway phase using {@code Ring_count} parity</li>
 *   <li>{@code loc_331CA} - timed descent phase before route selection</li>
 *   <li>{@code sub_33272} - dominant-axis {@code $0C00} route velocity calculation</li>
 *   <li>{@code loc_33260} - immediate last-point release with a same-frame final move</li>
 * </ul>
 *
 * <p>The object is controller-only. The verified note disproves the older plan's
 * rolling-radius assumption: the ROM forces {@code anim=2} but does not write
 * {@code x_radius}/{@code y_radius}. This implementation therefore locks the
 * player and drives the traversal without calling any helper that rewrites
 * collision radii.
 */
public final class CnzSpiralTubeInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int PHASE_DETECT = 0x00;
    private static final int PHASE_SWAY = 0x02;
    private static final int PHASE_DESCEND = 0x04;
    private static final int PHASE_ROUTE = 0x06;

    private static final int CAPTURE_HALF_WIDTH = 0x40;
    private static final int CAPTURE_HALF_HEIGHT = 0x10;
    private static final int CAPTURE_REPOSITION_X = 0x30;
    private static final int CAPTURE_GROUND_SPEED = 0x0800;
    private static final int SWAY_STEP = 0x08;
    private static final int DESCENT_STEP = 0x0C;
    private static final int DESCENT_DURATION_FRAMES = 0xC0;
    private static final int DESCENT_Y_STEP = 2;
    private static final int ROUTE_SPEED = 0x0C00;

    private final PlayerState p1State = new PlayerState();
    private final PlayerState p2State = new PlayerState();

    private int expectedTravelFramesForTest;
    private CnzTubePathTables.RoutePoint expectedExitPointForTest = new CnzTubePathTables.RoutePoint(0, 0);

    public CnzSpiralTubeInstance(ObjectSpawn spawn) {
        super(spawn, "CNZSpiralTube");
    }

    @Override
    public CnzSpiralTubeInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzSpiralTubeInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        PlayableEntity queriedMainPlayer = services().playerQuery().mainPlayerOrNull();
        AbstractPlayableSprite mainPlayer = queriedMainPlayer instanceof AbstractPlayableSprite sprite ? sprite : null;
        if (mainPlayer == null && playerEntity instanceof AbstractPlayableSprite sprite) {
            mainPlayer = sprite;
        }
        if (mainPlayer != null) {
            processPlayer(mainPlayer, p1State);
        }

        PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
        if (nativeP2 instanceof AbstractPlayableSprite sidekick && sidekick != mainPlayer) {
            processPlayer(sidekick, p2State);
        }
    }

    @Override
    public boolean isPersistent() {
        return p1State.isActive() || p2State.isActive();
    }

    /**
     * Mirrors {@code sub_330EE}'s per-player state-block dispatch. The ROM uses
     * exactly two blocks ({@code $30(a0)} for Player 1, {@code $3A(a0)} for
     * Player 2); the engine preserves that pattern instead of collapsing the
     * controller into one shared traversal state.
     */
    private void processPlayer(AbstractPlayableSprite player, PlayerState state) {
        switch (state.phase) {
            case PHASE_DETECT -> detectCapture(player, state);
            case PHASE_SWAY -> updateSwayPhase(player, state);
            case PHASE_DESCEND -> updateDescentPhase(player, state);
            case PHASE_ROUTE -> updateRoutePhase(player, state);
            default -> state.reset();
        }
    }

    /**
     * ROM: {@code loc_33102}. Capture only checks a tight 0x80x0x20 centre-based
     * window and whether {@code object_control} is already set.
     */
    private void detectCapture(AbstractPlayableSprite player, PlayerState state) {
        int dx = player.getCentreX() - spawn.x();
        if (dx < -CAPTURE_HALF_WIDTH || dx >= CAPTURE_HALF_WIDTH) {
            return;
        }

        int dy = player.getCentreY() - spawn.y();
        if (dy < -CAPTURE_HALF_HEIGHT || dy >= CAPTURE_HALF_HEIGHT) {
            return;
        }

        if (player.isObjectControlled()) {
            return;
        }

        beginCapture(player, state);
    }

    /**
     * ROM: {@code loc_33102}. The controller writes {@code object_control=$81},
     * {@code anim=2}, {@code ground_vel=$0800}, clears push status, forces the
     * player airborne, and repositions to {@code objectX +/- $30, objectY}.
     *
     * <p>Notably absent in the disassembly: there is no {@code x_radius/y_radius}
     * write. The implementation keeps the player's current radii intact.
     */
    private void beginCapture(AbstractPlayableSprite player, PlayerState state) {
        state.reset();
        state.phase = PHASE_SWAY;

        boolean capturedFromRight = player.getCentreX() >= spawn.x();
        state.phaseAngle = capturedFromRight ? 0x00 : 0x80;

        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setControlLocked(true);
        player.setAnimationId(Sonic3kAnimationIds.ROLL.id());
        player.setGSpeed((short) CAPTURE_GROUND_SPEED);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setPushing(false);
        player.setAir(true);
        writePositionWordX(player, spawn.x() + (capturedFromRight ? CAPTURE_REPOSITION_X : -CAPTURE_REPOSITION_X));
        writePositionWordY(player, spawn.y());

        updateTestExpectation(player, state);
        playRollSfx();
    }

    /**
     * ROM: {@code loc_3318E}. The sway phase uses the pre-increment angle for
     * the X-position sample, then advances by {@code +8}. Transition occurs when
     * the updated angle reaches either {@code 0x00} or {@code 0x80} depending on
     * {@code Ring_count}'s parity.
     */
    private void updateSwayPhase(AbstractPlayableSprite player, PlayerState state) {
        int sampledAngle = state.phaseAngle;
        state.phaseAngle = (state.phaseAngle + SWAY_STEP) & 0xFF;
        writePositionWordX(player, spawn.x() + horizontalOffset(sampledAngle));

        if (state.phaseAngle == targetAngleForRingParity(player)) {
            state.phase = PHASE_DESCEND;
            state.timer = DESCENT_DURATION_FRAMES;
        }
    }

    /**
     * ROM: {@code loc_331CA}. The player keeps the same cosine-based X motion,
     * descends by {@code 2 px/frame}, and after {@code $C0} frames chooses one of
     * the verified {@code off_33320} route payloads by evaluating
     * {@code playerX > objectX}.
     */
    private void updateDescentPhase(AbstractPlayableSprite player, PlayerState state) {
        int sampledAngle = state.phaseAngle;
        state.phaseAngle = (state.phaseAngle + DESCENT_STEP) & 0xFF;
        writePositionWordX(player, spawn.x() + horizontalOffset(sampledAngle));
        writePositionWordY(player, player.getCentreY() + DESCENT_Y_STEP);

        state.timer--;
        if (state.timer == 0) {
            beginRoute(player, state,
                    CnzTubePathTables.spiralPathForEntry(spawn.subtype(), spawn.x(), player.getCentreX()));
        }
    }

    /**
     * ROM: {@code sub_33272}. The route helper snaps the player to the first
     * point immediately, stores the remaining byte length, and computes a
     * dominant-axis velocity plan toward the next point using {@code $0C00}.
     */
    private void beginRoute(AbstractPlayableSprite player, PlayerState state, CnzTubePathTables.SpiralPath path) {
        state.phase = PHASE_ROUTE;
        state.activePath = path;
        state.nextPointIndex = 1;
        state.remainingBytes = CnzTubePathTables.spiralPayloadLengthBytes() - 4;

        CnzTubePathTables.RoutePoint first = path.point(0);
        writePositionWordX(player, first.centerX());
        writePositionWordY(player, first.centerY());

        applyVelocityPlan(player, state, velocityPlan(first, path.point(1)));
    }

    /**
     * ROM: {@code loc_3320E} / {@code loc_3323A}. The route timer is a byte-sized
     * countdown stored in the high byte of the duration word. While it remains
     * non-negative, the player moves by the current fixed-point velocity. When it
     * underflows, the controller snaps to the next waypoint.
     */
    private void updateRoutePhase(AbstractPlayableSprite player, PlayerState state) {
        state.timer--;
        if (state.timer >= 0) {
            movePlayer(player);
            return;
        }

        CnzTubePathTables.RoutePoint point = state.activePath.point(state.nextPointIndex);
        writePositionWordX(player, point.centerX());
        writePositionWordY(player, point.centerY());
        state.remainingBytes -= 4;

        if (state.remainingBytes == 0) {
            releaseAtLastPoint(player, state);
            movePlayer(player);
            return;
        }

        state.nextPointIndex++;
        applyVelocityPlan(player, state,
                velocityPlan(point, state.activePath.point(state.nextPointIndex)));
    }

    /**
     * ROM: {@code loc_33260}. Release happens immediately after snapping to the
     * last point: clear the phase byte, clear {@code object_control}, clear
     * {@code jumping}, mask Y to 12 bits, then continue into the same-frame move
     * with the current route velocity still intact.
     */
    private void releaseAtLastPoint(AbstractPlayableSprite player, PlayerState state) {
        writePositionWordY(player, player.getCentreY() & 0x0FFF);
        ObjectControlState.none().applyTo(player);
        player.setControlLocked(false);
        player.setJumping(false);
        state.reset();
    }

    private void applyVelocityPlan(AbstractPlayableSprite player, PlayerState state, VelocityPlan plan) {
        player.setXSpeed(plan.xVel());
        player.setYSpeed(plan.yVel());
        state.timer = plan.timer();
    }

    private static void writePositionWordX(AbstractPlayableSprite player, int centerX) {
        player.setCentreXPreserveSubpixel((short) centerX);
    }

    private static void writePositionWordY(AbstractPlayableSprite player, int centerY) {
        player.setCentreYPreserveSubpixel((short) centerY);
    }

    /**
     * Matches the dominant-axis velocity calculation shape in {@code sub_33272}.
     * The larger delta gets the fixed speed {@code $0C00}; the cross-axis uses
     * proportional division, and the timer is the high byte of the absolute
     * 16.8 quotient stored into {@code 2(a4)}.
     */
    private static VelocityPlan velocityPlan(CnzTubePathTables.RoutePoint start,
                                             CnzTubePathTables.RoutePoint target) {
        int dx = target.centerX() - start.centerX();
        int dy = target.centerY() - start.centerY();
        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);

        if (absDy >= absDx) {
            short yVel = (short) (dy >= 0 ? ROUTE_SPEED : -ROUTE_SPEED);
            int durationWord = dy == 0 ? 0 : (int) (((long) dy << 16) / yVel);
            short xVel = (short) (durationWord == 0 ? 0 : (int) (((long) dx << 16) / durationWord));
            return new VelocityPlan(xVel, yVel, Math.abs(durationWord) >> 8);
        }

        short xVel = (short) (dx >= 0 ? ROUTE_SPEED : -ROUTE_SPEED);
        int durationWord = dx == 0 ? 0 : (int) (((long) dx << 16) / xVel);
        short yVel = (short) (durationWord == 0 ? 0 : (int) (((long) dy << 16) / durationWord));
        return new VelocityPlan(xVel, yVel, Math.abs(durationWord) >> 8);
    }

    private void movePlayer(AbstractPlayableSprite player) {
        player.move(player.getXSpeed(), player.getYSpeed());
    }

    /**
     * ROM uses {@code d1} from {@code GetSineCosine}, then computes
     * {@code (d1 >> 3) + ((d1 >> 3) >> 1)}. With cosine scaled to 256, this is a
     * 48-pixel horizontal sway around the controller center.
     */
    private static int horizontalOffset(int phaseAngle) {
        int cosine = com.openggf.physics.TrigLookupTable.cosHex(phaseAngle);
        int scaled = cosine >> 3;
        return scaled + (scaled >> 1);
    }

    private static int targetAngleForRingParity(AbstractPlayableSprite player) {
        return (player.getRingCount() & 1) == 0 ? 0x00 : 0x80;
    }

    private void updateTestExpectation(AbstractPlayableSprite player, PlayerState state) {
        int targetAngle = targetAngleForRingParity(player);
        int diff = (targetAngle - state.phaseAngle) & 0xFF;
        int swayFrames = diff == 0 ? (0x100 / SWAY_STEP) : (diff / SWAY_STEP);

        int finalDescentSampleAngle = (targetAngle - DESCENT_STEP) & 0xFF;
        int predictedPlayerX = spawn.x() + horizontalOffset(finalDescentSampleAngle);
        CnzTubePathTables.SpiralPath predictedPath =
                CnzTubePathTables.spiralPathForEntry(spawn.subtype(), spawn.x(), predictedPlayerX);

        VelocityPlan firstLeg = velocityPlan(predictedPath.point(0), predictedPath.point(1));
        VelocityPlan secondLeg = velocityPlan(predictedPath.point(1), predictedPath.point(2));

        expectedTravelFramesForTest = swayFrames
                + DESCENT_DURATION_FRAMES
                + firstLeg.timer() + 1
                + secondLeg.timer() + 1;
        expectedExitPointForTest = movePoint(predictedPath.point(2), secondLeg);
    }

    private static CnzTubePathTables.RoutePoint movePoint(CnzTubePathTables.RoutePoint point, VelocityPlan plan) {
        int xPos = point.centerX() << 16;
        int yPos = point.centerY() << 16;
        xPos += plan.xVel() << 8;
        yPos += plan.yVel() << 8;
        return new CnzTubePathTables.RoutePoint(xPos >> 16, yPos >> 16);
    }

    private void playRollSfx() {
        try {
            services().playSfx(Sonic3kSfx.ROLL.id);
        } catch (Exception ignored) {
            // Headless tests may not wire audio, but the traversal logic must continue.
        }
    }

    int getExpectedTravelFramesForTest() {
        return expectedTravelFramesForTest;
    }

    CnzTubePathTables.RoutePoint getExpectedExitPointForTest() {
        return expectedExitPointForTest;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Verified controller-only object: the S&K route family has no mappings or art owner.
    }

    private static final class PlayerState {
        private int phase;
        private int phaseAngle;
        private int timer;
        private int nextPointIndex;
        private int remainingBytes;
        private CnzTubePathTables.SpiralPath activePath;

        private void reset() {
            phase = PHASE_DETECT;
            phaseAngle = 0;
            timer = 0;
            nextPointIndex = 0;
            remainingBytes = 0;
            activePath = null;
        }

        private boolean isActive() {
            return phase != PHASE_DETECT;
        }
    }

    private record VelocityPlan(short xVel, short yVel, int timer) {
    }
}
