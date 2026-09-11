package com.openggf.tests;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.Sonic1ObjectPlacement;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1BridgeObjectInstance;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for occasional missed jump input on GHZ2's first bridge.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Ghz2BridgeJumpRegression {
    private static final int ZONE_GHZ = 0;
    private static final int ACT_2 = 1;
    private static final int JUMP_TRIALS = 180;
    private static final int SETTLE_TIMEOUT_FRAMES = 180;

    private static SharedLevel sharedLevel;
    private static ObjectSpawn firstBridgeSpawn;

    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_GHZ, ACT_2);

        RomByteReader reader = RomByteReader.fromRom(com.openggf.tests.TestEnvironment.currentRom());
        Sonic1ObjectPlacement placement = new Sonic1ObjectPlacement(reader);
        List<ObjectSpawn> spawns = placement.load(Sonic1Constants.ZONE_GHZ, ACT_2);
        firstBridgeSpawn = spawns.stream()
                .filter(s -> s.objectId() == Sonic1ObjectIds.BRIDGE)
                .min(Comparator.comparingInt(ObjectSpawn::x))
                .orElse(null);
        assertNotNull(firstBridgeSpawn, "GHZ2 should contain at least one bridge object");
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();

        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager != null) {
            objectManager.reset(fixture.camera().getX());
        }
    }

    @Test
    public void firstBridgeJumpPressAlwaysLaunches() {
        Sonic1BridgeObjectInstance bridge = requireFirstBridgeActive();

        for (int trial = 0; trial < JUMP_TRIALS; trial++) {
            assertTrue(settleOnBridge(bridge), "Failed to settle on GHZ2 first bridge before trial " + trial);

            fixture.stepFrame(false, false, false, false, false);
            fixture.stepFrame(false, false, false, false, true);

            assertTrue(fixture.sprite().getAir(), "Jump press did not enter airborne state on trial " + trial);
            assertTrue(fixture.sprite().getYSpeed() < 0, "Jump press did not apply upward velocity on trial " + trial);

            fixture.stepIdleFrames(4);
        }
    }

    private Sonic1BridgeObjectInstance requireFirstBridgeActive() {
        fixture.sprite().setCentreX((short) (firstBridgeSpawn.x() - 80));
        fixture.sprite().setCentreY((short) (firstBridgeSpawn.y() - 48));
        fixture.camera().updatePosition(true);

        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
            Sonic1BridgeObjectInstance bridge = findFirstBridgeInstance();
            if (bridge != null) {
                return bridge;
            }
        }
        throw new AssertionError("First GHZ2 bridge did not become active near x=" + firstBridgeSpawn.x());
    }

    private boolean settleOnBridge(Sonic1BridgeObjectInstance bridge) {
        fixture.sprite().setCentreX((short) firstBridgeSpawn.x());
        fixture.sprite().setCentreY((short) (firstBridgeSpawn.y() - 56));
        fixture.sprite().setAir(true);
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.camera().updatePosition(true);

        ObjectManager objectManager = GameServices.level().getObjectManager();
        for (int frame = 0; frame < SETTLE_TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            ObjectInstance riding = objectManager != null ? objectManager.getRidingObject(fixture.sprite()) : null;
            if (!fixture.sprite().getAir() && fixture.sprite().isOnObject() && riding == bridge) {
                return true;
            }
        }
        return false;
    }

    private Sonic1BridgeObjectInstance findFirstBridgeInstance() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager == null) {
            return null;
        }
        return objectManager.getActiveObjects().stream()
                .filter(obj -> obj instanceof Sonic1BridgeObjectInstance)
                .map(obj -> (Sonic1BridgeObjectInstance) obj)
                .filter(obj -> obj.getSpawn().x() == firstBridgeSpawn.x())
                .findFirst()
                .orElse(null);
    }
}


