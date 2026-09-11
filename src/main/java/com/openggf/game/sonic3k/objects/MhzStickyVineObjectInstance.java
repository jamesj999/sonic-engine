package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K SKL object $0A - MHZ sticky vine.
 *
 * <p>ROM reference: {@code Obj_MHZStickyVine}. This ports the route-critical
 * overlap capture, child-chain deformation state, player pull, and spindash
 * release handoff into the retraction routine.
 */
public final class MhzStickyVineObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int PRIORITY_BUCKET = 4;
    private static final int DISPLAY_HALF_WIDTH = 0x80;
    private static final int DISPLAY_HALF_HEIGHT = 0x80;
    private static final int GRAB_X_BIAS = 0x0C;
    private static final int GRAB_X_RANGE = 0x18;
    private static final int GRAB_Y_BIAS = 0x18;
    private static final int GRAB_Y_RANGE = 0x30;
    private static final int SEGMENT_COUNT = 8;
    private static final int SPINDASH_RELEASE_DELAY = 0x10;

    private final int[] segmentX = new int[SEGMENT_COUNT];
    private final int[] segmentY = new int[SEGMENT_COUNT];

    private AbstractPlayableSprite capturedPlayer;
    private boolean active;
    private boolean spindashReleaseArmed;
    private boolean retracting;
    private int spindashReleaseTimer;
    private int retractX;
    private int retractY;
    private int retractYSpeed;

    public MhzStickyVineObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MHZStickyVine");
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segmentX[i] = spawn.x();
            segmentY[i] = spawn.y();
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (retracting) {
            updateRetraction();
            updateOffscreenLifecycle();
            return;
        }
        if (!active) {
            scanNativePlayers(playerEntity);
        }
        if (capturedPlayer != null) {
            updateActivePlayer(capturedPlayer);
        }
        updateOffscreenLifecycle();
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return DISPLAY_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return DISPLAY_HALF_HEIGHT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_STICKY_VINE);
        if (renderer == null) {
            return;
        }
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            renderer.drawFrameIndex(0, segmentX[i], segmentY[i], false, false);
        }
    }

    @Override
    public String traceDebugDetails() {
        return super.traceDebugDetails()
                + " active=" + active
                + " retracting=" + retracting
                + " armed=" + spindashReleaseArmed
                + " timer=" + spindashReleaseTimer;
    }

    private void updateActivePlayer(AbstractPlayableSprite player) {
        if (player.getSpindash()) {
            spindashReleaseArmed = true;
            spindashReleaseTimer = SPINDASH_RELEASE_DELAY;
        }

        int playerX = player.getCentreX();
        int playerY = player.getCentreY();
        updateSegmentsToward(playerX, playerY);
        applyStickyPull(player);

        if (spindashReleaseArmed && !player.getSpindash()) {
            spindashReleaseTimer--;
            if (spindashReleaseTimer <= 0) {
                beginRetraction(player);
            }
        }
    }

    private void updateRetraction() {
        if (retractX != spawn.x()) {
            retractX += retractX < spawn.x() ? 2 : -2;
            if (Math.abs(retractX - spawn.x()) < 2) {
                retractX = spawn.x();
            }
        }
        if (retractY != spawn.y()) {
            if (retractY < spawn.y() || retractYSpeed < 0) {
                retractY += retractYSpeed >> 8;
                retractYSpeed += 0x38;
                if (retractY >= spawn.y() && retractYSpeed > 0) {
                    retractY = spawn.y();
                    retractYSpeed = 0;
                }
            } else {
                retractY = Math.max(spawn.y(), retractY - 2);
            }
        }
        updateSegmentsToward(retractX, retractY);
        if (retractX == spawn.x() && retractY == spawn.y()) {
            retracting = false;
            active = false;
            capturedPlayer = null;
        }
    }

    private void beginRetraction(AbstractPlayableSprite player) {
        retractX = player.getCentreX();
        retractY = player.getCentreY();
        retractYSpeed = -0x0600;
        spindashReleaseArmed = false;
        active = false;
        capturedPlayer = null;
        retracting = true;
    }

    private void scanNativePlayers(PlayableEntity playerEntity) {
        if (playerEntity instanceof AbstractPlayableSprite player) {
            tryCapture(player);
        }
        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        for (PlayableEntity participant : services.playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (participant != playerEntity && participant instanceof AbstractPlayableSprite sprite) {
                tryCapture(sprite);
            }
        }
    }

    private void tryCapture(AbstractPlayableSprite player) {
        if (isInStickyWindow(player)
                && !player.isObjectControlled()
                && !player.isHurt()
                && !player.getDead()
                && !player.isDebugMode()) {
            active = true;
            capturedPlayer = player;
        }
    }

    private void updateOffscreenLifecycle() {
        if (!isInRange()) {
            setDestroyedByOffscreen();
        }
    }

    private void updateSegmentsToward(int targetX, int targetY) {
        int dx = targetX - spawn.x();
        int dy = targetY - spawn.y();
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segmentX[i] = spawn.x() + ((dx * i) / SEGMENT_COUNT);
            segmentY[i] = spawn.y() + ((dy * i) / SEGMENT_COUNT);
        }
    }

    /**
     * ROM: {@code sub_3EC66} (sonic3k.asm ~83210-83258). Computes the pull vector via
     * {@code GetArcTan}/{@code GetSineCosine} over the full ROM-precision (x_pos:x_sub)
     * delta to the vine anchor and subtracts it from the player's position, honouring
     * the distinct air vs ground branches.
     */
    private void applyStickyPull(AbstractPlayableSprite player) {
        // sub_3EC2A: full 32-bit (pixel:subpixel) delta between the player and the anchor,
        // in the same Q16.16 representation as the ROM's x_pos(a1)/y_pos(a1) longword.
        int vineXQ = spawn.x() << 16;
        int vineYQ = spawn.y() << 16;
        int playerXQ = (player.getCentreX() << 16) | (player.getXSubpixelRaw() & 0xFFFF);
        int playerYQ = (player.getCentreY() << 16) | (player.getYSubpixelRaw() & 0xFFFF);

        // sub_3EC66: "swap d1/d2" keeps only the integer pixel delta for the arctan input.
        short dxPixel = (short) ((playerXQ - vineXQ) >> 16);
        short dyPixel = (short) ((playerYQ - vineYQ) >> 16);
        short d3 = (short) ((Math.abs(dxPixel) + Math.abs(dyPixel)) * 2);

        int angle = TrigLookupTable.calcAngle(dxPixel, dyPixel);
        int sin = TrigLookupTable.sinHex(angle);
        int cos = TrigLookupTable.cosHex(angle);

        // muls.w d3,d1 / asl.l #2,d1 -- x_pos -= cos(angle)*d3*4 (full sub-pixel precision).
        int xPullQ = (cos * d3) << 2;
        // muls.w d3,d0 / asl.l #1,d0 -- y_pos -= sin(angle)*d3*2, applied only while airborne.
        int yPullQ = (sin * d3) << 1;

        int newXQ = playerXQ - xPullQ;
        player.setSubpixelRaw(newXQ & 0xFFFF, player.getYSubpixelRaw());
        NativePositionOps.writeXPosPreserveSubpixel(player, newXQ >> 16);

        if (player.getAir()) {
            int newYQ = playerYQ - yPullQ;
            player.setSubpixelRaw(player.getXSubpixelRaw(), newYQ & 0xFFFF);
            NativePositionOps.writeYPosPreserveSubpixel(player, newYQ >> 16);
            if (player.getYSpeed() >= 0) {
                player.setXSpeed((short) (player.getXSpeed() >> 1));
            }
        } else {
            // asr.l #8,d1 then tst.w/neg.w -- pullStrength is the low word of the x-pull
            // delta, scaled from Q16.16 down to the Q8.8 units ground_vel is stored in.
            int pullStrength = Math.abs((short) (xPullQ >> 8));
            int groundSpeedMagnitude = Math.abs(player.getGSpeed());
            if (groundSpeedMagnitude >= 0x200 && groundSpeedMagnitude - 0x10 < pullStrength) {
                player.setGSpeed((short) (player.getGSpeed() >> 1));
            }
        }
        player.setPushing(false);
    }

    private boolean isInStickyWindow(AbstractPlayableSprite player) {
        int relX = player.getCentreX() - spawn.x() + GRAB_X_BIAS;
        if (relX < 0 || relX >= GRAB_X_RANGE) {
            return false;
        }
        int relY = player.getCentreY() - spawn.y() + GRAB_Y_BIAS;
        return relY >= 0 && relY < GRAB_Y_RANGE;
    }
}
