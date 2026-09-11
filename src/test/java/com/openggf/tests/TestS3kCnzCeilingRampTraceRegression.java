package com.openggf.tests;

import com.openggf.game.GroundMode;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kCnzCeilingRampTraceRegression {
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private Sonic sonic;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 3, 0);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sonic = (Sonic) fixture.sprite();
    }

    @Test
    void airborneCeilingSnapMatchesCnzTraceBeforeSpiralTubeRampLanding() {
        hydrateFrame24fc();

        fixture.stepFrame(false, false, false, true, false);
        assertFrame24fd();

        fixture.stepFrame(false, false, false, true, false);
        fixture.stepFrame(false, false, false, true, false);
        fixture.stepFrame(false, false, false, true, false);
        assertFrame2500();
    }

    private void hydrateFrame24fc() {
        sonic.setGroundMode(GroundMode.GROUND);
        sonic.setRolling(true);
        sonic.setRollingJump(true);
        sonic.setAir(true);
        sonic.setAngle((byte) 0);
        sonic.setCentreX((short) 0x1387);
        sonic.setCentreY((short) 0x0113);
        sonic.setSubpixelRaw(0xF400, 0x5100);
        sonic.setXSpeed((short) 0x0776);
        sonic.setYSpeed((short) 0xFA98);
        sonic.setGSpeed((short) 0x0776);
    }

    private void assertFrame24fd() {
        assertEquals(0x138F, sonic.getCentreX() & 0xFFFF);
        assertEquals(0x0113, sonic.getCentreY() & 0xFFFF);
        assertEquals(0x0776, sonic.getXSpeed() & 0xFFFF);
        assertEquals(0x0000, sonic.getYSpeed() & 0xFFFF);
        assertEquals(0x0776, sonic.getGSpeed() & 0xFFFF);
        assertEquals(0x00, sonic.getAngle() & 0xFF);
        assertTrue(sonic.getAir());
        assertTrue(sonic.getRolling());
    }

    private void assertFrame2500() {
        assertEquals(0x13A5, sonic.getCentreX() & 0xFFFF);
        assertEquals(0x010D, sonic.getCentreY() & 0xFFFF);
        assertEquals(0x0776, sonic.getXSpeed() & 0xFFFF);
        assertEquals(0x0000, sonic.getYSpeed() & 0xFFFF);
        assertEquals(0x0776, sonic.getGSpeed() & 0xFFFF);
        assertEquals(0xE2, sonic.getAngle() & 0xFF);
        assertFalse(sonic.getAir());
        assertFalse(sonic.getRolling());
    }
}
