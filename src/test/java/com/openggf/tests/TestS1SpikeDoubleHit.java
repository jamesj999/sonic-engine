package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for S1 spike double-hit bug on ceiling spikes.
 *
 * In LZ3 near (5560, 496) there are downward-pointing spikes (subtype 0x30,
 * V-flipped upright type 3). When Sonic jumps upward into these ceiling spikes
 * with 10 rings, he should take damage and lose rings, NOT die.
 *
 * The ROM prevents re-damage because Sonic is in the hurt routine (obRoutine >= 4)
 * until he lands, and by the time he lands he has moved away from the spike.
 *
 * Reference: docs/s1disasm/_incObj/36 Spikes.asm lines 87-88
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1SpikeDoubleHit {

    private static final int LZ_ZONE = 3;
    private static final int LZ_ACT_3 = 2;
    private static final int FRAMES_AFTER_DAMAGE = 10;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, LZ_ZONE, LZ_ACT_3);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    @Test
    public void ceilingSpikesShouldNotKillSonicWithRings() {
        Sonic sprite = (Sonic) fixture.sprite();

        // Let level settle and objects spawn
        for (int i = 0; i < 10; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        // Place Sonic below the ceiling spike and give him rings.
        // Position him on the ground directly beneath the spike at (5560, 496).
        // Try several starting Y positions to find the floor level.
        sprite.setCentreX((short) 5560);
        sprite.setCentreY((short) 550);
        sprite.setAir(false);
        sprite.setYSpeed((short) 0);
        sprite.setRingCount(10);

        // Let Sonic settle onto the ground for a few frames
        for (int i = 0; i < 3; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        int groundY = sprite.getCentreY();
        System.out.println("Ground Y after settling: " + groundY);
        System.out.println("Spike at Y=496, solid box bottom = 496 + 16 + " + sprite.getYRadius() + " = " + (496 + 16 + sprite.getYRadius()));

        assertFalse(sprite.getDead(), "Sonic should not be dead before jump");
        assertFalse(sprite.isHurt(), "Sonic should not be hurt before jump");
        assertEquals(10, sprite.getRingCount(), "Sonic should have 10 rings before jump");

        // Now set Sonic airborne with an upward velocity to simulate a jump
        // into the ceiling spike. Use a speed that reaches the spike's Y range.
        // The spike is at Y=496. Sonic needs to reach ~Y=496+16+yRadius = ~531 from below.
        // Distance: groundY - 531. Speed needed: varies by distance.
        // Use a moderate speed so Sonic reaches the spike near apex.
        // Re-align X to spike column â€” settling drifts Sonic sideways on the slope,
        // and different X positions have different ceiling geometry above.
        // Also zero horizontal speed so Sonic goes straight up into the spike.
        sprite.setCentreX((short) 5560);
        sprite.setXSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAir(true);
        sprite.setYSpeed((short) -1440);

        fixture.camera().updatePosition(true);

        int damageFrame = -1;
        int damageCount = 0;
        boolean wasHurt = false;

        // Step up to 60 frames â€” enough to hit spike, bounce, land, and survive
        for (int frame = 0; frame < 60; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            boolean isHurtNow = sprite.isHurt();
            boolean tookDamage = !wasHurt && isHurtNow;
            if (tookDamage) {
                damageCount++;
            }
            wasHurt = isHurtNow;

            System.out.printf("Frame %2d: X=%d Y=%d ySpeed=%d air=%b hurt=%b dead=%b rings=%d invuln=%d%s%n",
                    frame, sprite.getCentreX(), sprite.getCentreY(), sprite.getYSpeed(),
                    sprite.getAir(), sprite.isHurt(), sprite.getDead(),
                    sprite.getRingCount(), sprite.getInvulnerableFrames(),
                    tookDamage ? " *** DAMAGE ***" : "");

            // Detect the frame damage first occurs
            if (damageFrame < 0 && (isHurtNow || sprite.getRingCount() < 10)) {
                damageFrame = frame;
            }

            if (sprite.getDead()) {
                int framesAfterDamage = (damageFrame >= 0) ? (frame - damageFrame) : -1;
                fail("Sonic died on frame " + (frame + 1)
                        + " (" + framesAfterDamage + " frames after first damage)"
                        + ", damage count: " + damageCount
                        + ". Rings: " + sprite.getRingCount()
                        + ", hurt: " + sprite.isHurt()
                        + ", ySpeed: " + sprite.getYSpeed()
                        + ", centreX: " + sprite.getCentreX()
                        + ", centreY: " + sprite.getCentreY());
            }

            // Once we've survived FRAMES_AFTER_DAMAGE frames past the hit, we're good
            if (damageFrame >= 0 && (frame - damageFrame) >= FRAMES_AFTER_DAMAGE) {
                break;
            }
        }

        assertTrue(damageFrame >= 0, "Sonic should have been damaged by spikes");
        assertEquals(0, sprite.getRingCount(), "Sonic should have 0 rings after spike hit");
        assertFalse(sprite.getDead(), "Sonic should not be dead after surviving " + FRAMES_AFTER_DAMAGE
                + " frames post-damage");
    }
}


