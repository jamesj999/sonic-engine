package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * S3K SKL object $03 - MHZ twisted vine.
 *
 * <p>ROM reference: {@code Obj_MHZTwistedVine}. This ports the route-critical
 * lower/upper vine entry windows and RideObject_SetRide side effects from
 * {@code sub_3DCD0/sub_3DE80}.
 */
public final class MhzTwistedVineObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int LOWER_RIGHT_ENTRY_MIN_DX = -0x40;
    private static final int LOWER_RIGHT_ENTRY_MAX_DX = -0x30;
    private static final int LOWER_RIGHT_ENTRY_Y_BIAS = 0x30;
    private static final int LOWER_LEFT_ENTRY_MIN_DX = 0x30;
    private static final int LOWER_LEFT_ENTRY_MAX_DX = 0x40;
    private static final int LOWER_LEFT_ENTRY_Y_BIAS = -0x10;
    private static final int UPPER_LEFT_ENTRY_MIN_DX = 0x30;
    private static final int UPPER_LEFT_ENTRY_MAX_DX = 0x40;
    private static final int UPPER_LEFT_ENTRY_Y_BIAS = 0x30;
    private static final int UPPER_RIGHT_ENTRY_MIN_DX = -0x40;
    private static final int UPPER_RIGHT_ENTRY_MAX_DX = -0x30;
    private static final int UPPER_RIGHT_ENTRY_Y_BIAS = -0x10;
    private static final int ENTRY_Y_RANGE = 0x20;
    private static final int MIN_ENTRY_GROUND_SPEED = 0x0600;
    private static final int MIN_ACTIVE_GROUND_SPEED = 0x0500;
    private static final int MOVE_LOCK_FRAMES = 20;
    private static final int RELEASE_FLIP_SPEED = 4;

    private boolean upperVariant;
    private final Set<AbstractPlayableSprite> activePlayers =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private String lastDecision = "init";

    public MhzTwistedVineObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MHZTwistedVine");
        this.upperVariant = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public MhzTwistedVineObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MhzTwistedVineObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (playerEntity instanceof AbstractPlayableSprite player) {
            updatePlayer(player);
        }
        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        for (PlayableEntity participant : services.playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (participant != playerEntity && participant instanceof AbstractPlayableSprite sprite) {
                updatePlayer(sprite);
            }
        }
    }

    private void updatePlayer(AbstractPlayableSprite player) {
        if (activePlayers.contains(player)) {
            updateActivePlayer(player);
            return;
        }
        tryEntry(player);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
    }

    @Override
    public int getOnScreenHalfWidth() {
        return 0xD0;
    }

    @Override
    public String traceDebugDetails() {
        return super.traceDebugDetails()
                + " upper=" + upperVariant
                + " active=" + activePlayers.size()
                + " decision=" + lastDecision;
    }

    private void tryEntry(AbstractPlayableSprite player) {
        if (player.getAir()) {
            lastDecision = "entry-air";
            return;
        }
        if (player.isObjectControlled()) {
            lastDecision = "entry-control";
            return;
        }

        int xSpeed = player.getXSpeed();
        if (xSpeed == 0) {
            lastDecision = "entry-still";
            return;
        }

        if (upperVariant) {
            tryUpperEntry(player, xSpeed);
        } else {
            tryLowerEntry(player, xSpeed);
        }
    }

    private void tryLowerEntry(AbstractPlayableSprite player, int xSpeed) {
        if (xSpeed > 0 && isEntryWindow(player, LOWER_RIGHT_ENTRY_MIN_DX, LOWER_RIGHT_ENTRY_MAX_DX,
                LOWER_RIGHT_ENTRY_Y_BIAS)) {
            enterRide(player, Direction.RIGHT, 0, 0x80, MIN_ENTRY_GROUND_SPEED);
            lastDecision = "lower-right-entry";
            return;
        }
        if (xSpeed < 0 && isEntryWindow(player, LOWER_LEFT_ENTRY_MIN_DX, LOWER_LEFT_ENTRY_MAX_DX,
                LOWER_LEFT_ENTRY_Y_BIAS)) {
            enterRide(player, Direction.LEFT, 0x80, 0x80, -MIN_ENTRY_GROUND_SPEED);
            lastDecision = "lower-left-entry";
            return;
        }
        lastDecision = "lower-no-entry";
    }

    private void tryUpperEntry(AbstractPlayableSprite player, int xSpeed) {
        if (xSpeed < 0 && isEntryWindow(player, UPPER_LEFT_ENTRY_MIN_DX, UPPER_LEFT_ENTRY_MAX_DX,
                UPPER_LEFT_ENTRY_Y_BIAS)) {
            enterRide(player, Direction.LEFT, 0, 0x84, -MIN_ENTRY_GROUND_SPEED);
            lastDecision = "upper-left-entry";
            return;
        }
        if (xSpeed > 0 && isEntryWindow(player, UPPER_RIGHT_ENTRY_MIN_DX, UPPER_RIGHT_ENTRY_MAX_DX,
                UPPER_RIGHT_ENTRY_Y_BIAS)) {
            enterRide(player, Direction.RIGHT, 0x80, 0x84, MIN_ENTRY_GROUND_SPEED);
            lastDecision = "upper-right-entry";
            return;
        }
        lastDecision = "upper-no-entry";
    }

    private boolean isEntryWindow(AbstractPlayableSprite player, int minDx, int maxDx, int yBias) {
        int dx = signedWordDelta(player.getCentreX(), spawn.x());
        if (dx < minDx || dx > maxDx) {
            return false;
        }
        int dy = wordDelta(player.getCentreY(), spawn.y()) + yBias;
        return (dy & 0xFFFF) < ENTRY_Y_RANGE;
    }

    private void enterRide(AbstractPlayableSprite player, Direction direction, int flipAngle, int flipType,
                           int minimumGroundSpeed) {
        activePlayers.add(player);
        player.setOnObject(true);
        player.setLatchedSolidObject(Sonic3kObjectIds.MHZ_TWISTED_VINE, this);
        player.setDirection(direction);
        player.setFlipAngle(flipAngle);
        player.setFlipType(flipType);
        player.setMoveLockTimer(MOVE_LOCK_FRAMES);
        clampGroundSpeed(player, minimumGroundSpeed);
    }

    private static void clampGroundSpeed(AbstractPlayableSprite player, int minimumGroundSpeed) {
        int groundSpeed = player.getGSpeed();
        if (minimumGroundSpeed > 0) {
            if (groundSpeed < minimumGroundSpeed) {
                player.setGSpeed((short) minimumGroundSpeed);
            }
            return;
        }
        if (groundSpeed > minimumGroundSpeed) {
            player.setGSpeed((short) minimumGroundSpeed);
        }
    }

    private void updateActivePlayer(AbstractPlayableSprite player) {
        if (player.getAir()) {
            release(player, "release-air");
            return;
        }
        if (Math.abs(player.getGSpeed()) < MIN_ACTIVE_GROUND_SPEED) {
            release(player, "release-slow");
            return;
        }
        if (upperVariant) {
            updateUpperRideArc(player);
        } else {
            updateLowerRideArc(player);
        }
    }

    private void updateLowerRideArc(AbstractPlayableSprite player) {
        int offset = (wordDelta(player.getCentreX(), spawn.x()) + 0x40) & 0xFFFF;
        if ((short) offset < 0) {
            exitLowerLeft(player);
            return;
        }
        if (offset >= 0x80) {
            exitLowerRight(player);
            return;
        }
        applyRideArc(player, (offset + 0x80) & 0xFF, offset & 0xFF);
        lastDecision = "lower-ride-active";
    }

    private void updateUpperRideArc(AbstractPlayableSprite player) {
        int offset = (wordDelta(player.getCentreX(), spawn.x()) + 0x40) & 0xFFFF;
        if ((short) offset < 0) {
            exitUpperLeft(player);
            return;
        }
        if (offset >= 0x80) {
            exitUpperRight(player);
            return;
        }
        applyRideArc(player, offset & 0xFF, (0x80 - offset) & 0xFF);
        lastDecision = "upper-ride-active";
    }

    private void applyRideArc(AbstractPlayableSprite player, int sineAngle, int flipAngle) {
        int cos = TrigLookupTable.cosHex(sineAngle);
        int y = spawn.y() + (cos >> 4) + ((player.getYRadius() * cos) >> 8);
        NativePositionOps.writeYPosPreserveSubpixel(player, y);
        player.setFlipAngle(flipAngle);
        player.setOnObject(true);
        player.setLatchedSolidObject(Sonic3kObjectIds.MHZ_TWISTED_VINE, this);
    }

    private static int wordDelta(int value, int origin) {
        return ((value & 0xFFFF) - (origin & 0xFFFF)) & 0xFFFF;
    }

    private static int signedWordDelta(int value, int origin) {
        return (short) wordDelta(value, origin);
    }

    private void exitLowerLeft(AbstractPlayableSprite player) {
        clearActiveRide(player);
        player.setAngle((byte) 0);
        lastDecision = "lower-left-exit";
    }

    private void exitLowerRight(AbstractPlayableSprite player) {
        clearActiveRide(player);
        player.setAngle((byte) 0x80);
        player.setGSpeed((short) -player.getGSpeed());
        player.setXSpeed((short) -player.getXSpeed());
        player.setDirection(Direction.LEFT);
        lastDecision = "lower-right-exit";
    }

    private void exitUpperLeft(AbstractPlayableSprite player) {
        clearActiveRide(player);
        player.setAngle((byte) 0x80);
        player.setGSpeed((short) -player.getGSpeed());
        player.setXSpeed((short) -player.getXSpeed());
        player.setDirection(Direction.RIGHT);
        lastDecision = "upper-left-exit";
    }

    private void exitUpperRight(AbstractPlayableSprite player) {
        clearActiveRide(player);
        player.setAngle((byte) 0);
        lastDecision = "upper-right-exit";
    }

    private void release(AbstractPlayableSprite player, String decision) {
        activePlayers.remove(player);
        player.setOnObject(false);
        clearRideSupport(player);
        player.setFlipAngle(0);
        player.setFlipType(0);
        player.setFlipsRemaining(0);
        player.setFlipSpeed(RELEASE_FLIP_SPEED);
        lastDecision = decision;
    }

    private void clearActiveRide(AbstractPlayableSprite player) {
        activePlayers.remove(player);
        player.setOnObject(false);
        clearRideSupport(player);
        player.setFlipAngle(0);
        player.setFlipType(0);
    }

    private void clearRideSupport(AbstractPlayableSprite player) {
        if (player.getLatchedSolidObjectInstance() == this
                || (player.getLatchedSolidObjectInstance() == null
                && player.getLatchedSolidObjectId() == Sonic3kObjectIds.MHZ_TWISTED_VINE)) {
            player.setLatchedSolidObjectId(0);
        }
    }
}
