package com.openggf.game.sonic2.objects;

import com.openggf.game.GameServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
public class TestCpzStaircaseWallCollision {
    private static final int ZONE_CPZ = 1;
    private static final int ACT_1 = 0;
    private static final int STAIRCASE_X = 8336;
    private static final int STAIRCASE_Y = 848;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_CPZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    @Test
    public void topContactTimerDoesNotDecrementOnTriggerFrame() {
        CPZStaircaseObjectInstance staircase = new CPZStaircaseObjectInstance(
                new com.openggf.level.objects.ObjectSpawn(0x2090, 0x0350, 0x78, 0x00, 0, false, 0),
                "CPZStaircase");

        staircase.onPieceContact(0, null,
                new com.openggf.level.objects.SolidContact(true, false, false, false, false), 0);
        staircase.update(0, null);
        for (int frame = 1; frame <= 30; frame++) {
            staircase.update(frame, null);
        }

        assertEquals(0x0350, staircase.getPieceY(0),
                "Obj78 loc_292C8 sets objoff_2C=$1E and returns; movement begins one frame later");
        staircase.update(31, null);
        assertEquals(0x0351, staircase.getPieceY(0));
    }

    @Test
    public void ridingIntoHigherStepPushesLikeWall() {
        CPZStaircaseObjectInstance staircase = activateMovingStaircase();
        Assumptions.assumeTrue(staircase != null, "Expected CPZ staircase to become active near test coordinates");

        StepPair pair = findLargestAdjacentStep(staircase);
        Assumptions.assumeTrue(pair != null && pair.heightDifference() >= 6, "Expected a visible staircase height difference before wall-collision check");

        placeSpriteAbovePiece(staircase, pair.lowerPiece());
        ObjectManager objectManager = GameServices.level().getObjectManager();
        boolean landed = waitForRideOnPiece(objectManager, staircase, pair.lowerPiece(), 60);
        Assumptions.assumeTrue(landed, "Sonic could not land on the lower CPZ staircase piece");

        int startX = fixture.sprite().getCentreX();
        boolean moveRight = staircase.getPieceX(pair.higherPiece()) > staircase.getPieceX(pair.lowerPiece());
        boolean sawPush = false;

        for (int frame = 0; frame < 24; frame++) {
            fixture.stepFrame(false, false, !moveRight, moveRight, false);
            if (fixture.sprite().getPushing()) {
                sawPush = true;
                break;
            }
        }

        int horizontalAdvance = Math.abs(fixture.sprite().getCentreX() - startX);
        assertTrue(sawPush && horizontalAdvance < 32, "Running from the lower CPZ staircase block into the higher adjacent block "
                        + "should produce a wall-style push before Sonic can advance a full block "
                        + "(advance=" + horizontalAdvance + ")");
    }

    private CPZStaircaseObjectInstance activateMovingStaircase() {
        fixture.sprite().setCentreX((short) STAIRCASE_X);
        fixture.sprite().setCentreY((short) (STAIRCASE_Y - 48));
        fixture.sprite().setAir(true);
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.camera().updatePosition(true);
        GameServices.level().getObjectManager().reset(fixture.camera().getX());

        CPZStaircaseObjectInstance staircase = null;
        for (int frame = 0; frame < 140; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (staircase == null) {
                staircase = findStaircase();
            }
            if (staircase != null) {
                StepPair pair = findLargestAdjacentStep(staircase);
                if (pair != null && pair.heightDifference() >= 6) {
                    return staircase;
                }
            }
        }
        return staircase;
    }

    private CPZStaircaseObjectInstance findStaircase() {
        for (ObjectInstance object : GameServices.level().getObjectManager().getActiveObjects()) {
            if (object instanceof CPZStaircaseObjectInstance staircase) {
                return staircase;
            }
        }
        return null;
    }

    private void placeSpriteAbovePiece(CPZStaircaseObjectInstance staircase, int pieceIndex) {
        fixture.sprite().setCentreX((short) staircase.getPieceX(pieceIndex));
        fixture.sprite().setCentreY((short) (staircase.getPieceY(pieceIndex) - 48));
        fixture.sprite().setAir(true);
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.sprite().setPushing(false);
        fixture.camera().updatePosition(true);
    }

    private boolean waitForRideOnPiece(ObjectManager objectManager, CPZStaircaseObjectInstance staircase,
            int pieceIndex, int maxFrames) {
        for (int frame = 0; frame < maxFrames; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (objectManager.getRidingObject(fixture.sprite()) == staircase
                    && objectManager.getRidingPieceIndex(fixture.sprite()) == pieceIndex) {
                return true;
            }
        }
        return false;
    }

    private StepPair findLargestAdjacentStep(CPZStaircaseObjectInstance staircase) {
        StepPair best = null;
        int bestHeightDifference = -1;
        for (int i = 0; i < staircase.getPieceCount() - 1; i++) {
            int firstY = staircase.getPieceY(i);
            int secondY = staircase.getPieceY(i + 1);
            int diff = Math.abs(firstY - secondY);
            if (diff <= bestHeightDifference) {
                continue;
            }
            int lowerPiece = firstY > secondY ? i : (i + 1);
            int higherPiece = lowerPiece == i ? (i + 1) : i;
            best = new StepPair(lowerPiece, higherPiece, diff);
            bestHeightDifference = diff;
        }
        return best;
    }

    private record StepPair(int lowerPiece, int higherPiece, int heightDifference) { }
}
