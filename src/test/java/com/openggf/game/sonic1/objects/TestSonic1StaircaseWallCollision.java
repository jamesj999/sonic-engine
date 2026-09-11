package com.openggf.game.sonic1.objects;

import com.openggf.game.GameServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_1)
public class TestSonic1StaircaseWallCollision {
    private static final int ZONE_SLZ = 4;
    private static final int ACT_1 = 0;
    private static final int STAIRCASE_X = 1070;
    private static final int STAIRCASE_Y = 441;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_SLZ, ACT_1);
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
    public void ridingIntoHigherStepPushesLikeWall() {
        Sonic1StaircaseObjectInstance staircase = activateMovingStaircase();
        assertNotNull(staircase, "Expected SLZ staircase to become active near test coordinates");

        StepPair pair = findLargestAdjacentStep(staircase);
        assertTrue(pair != null && pair.heightDifference() >= 6,
                "Expected a visible SLZ staircase height difference before wall-collision check");

        placeSpriteAbovePiece(staircase, pair.lowerPiece());
        ObjectManager objectManager = GameServices.level().getObjectManager();
        boolean landed = waitForRideOnPiece(objectManager, staircase, pair.lowerPiece(), 60);
        assertTrue(landed, "Sonic could not land on the lower SLZ staircase piece");

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
        assertTrue(sawPush && horizontalAdvance < 32, "Running from the lower SLZ staircase block into the higher adjacent block "
                        + "should produce a wall-style push before Sonic can advance a full block "
                        + "(advance=" + horizontalAdvance + ")");
    }

    private Sonic1StaircaseObjectInstance activateMovingStaircase() {
        fixture.sprite().setCentreX((short) STAIRCASE_X);
        fixture.sprite().setCentreY((short) STAIRCASE_Y);
        fixture.sprite().setAir(false);
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.camera().updatePosition(true);
        GameServices.level().getObjectManager().reset(fixture.camera().getX());

        Sonic1StaircaseObjectInstance staircase = null;
        for (int frame = 0; frame < 200; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (staircase == null) {
                staircase = findStaircase();
            }
            if (staircase == null) {
                continue;
            }
            StepPair pair = findLargestAdjacentStep(staircase);
            if (pair != null && pair.heightDifference() >= 6) {
                return staircase;
            }
        }
        return staircase;
    }

    private Sonic1StaircaseObjectInstance findStaircase() {
        for (ObjectInstance object : GameServices.level().getObjectManager().getActiveObjects()) {
            if (object instanceof Sonic1StaircaseObjectInstance staircase) {
                return staircase;
            }
        }
        return null;
    }

    private void placeSpriteAbovePiece(Sonic1StaircaseObjectInstance staircase, int pieceIndex) {
        fixture.sprite().setCentreX((short) staircase.getPieceX(pieceIndex));
        fixture.sprite().setCentreY((short) (staircase.getPieceY(pieceIndex) - 48));
        fixture.sprite().setAir(true);
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.sprite().setPushing(false);
        fixture.camera().updatePosition(true);
    }

    private boolean waitForRideOnPiece(ObjectManager objectManager, Sonic1StaircaseObjectInstance staircase,
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

    private StepPair findLargestAdjacentStep(Sonic1StaircaseObjectInstance staircase) {
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


