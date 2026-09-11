package com.openggf.game.sonic1.objects;

import com.openggf.game.GameStateManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No S1 bumper test existed before this file (Task 5, S1 bug-triage plan).
 * See docs/s1disasm/_incObj/47 Bumper.asm for the ROM reference cited throughout.
 */
class TestSonic1BumperObjectInstance {

    // ROM: obColType $D7, React_Special size index $17 = 8x8 half-widths
    // (docs/s1disasm/_incObj/47 Bumper.asm; Sonic1BumperObjectInstance.COLLISION_SIZE_INDEX).
    private static final int BUMPER_SIZE_INDEX = 0x17;
    private static final int BUMPER_RADIUS = 8;

    // AbstractPlayableSprite.getEffectiveGravity() normal value (0x38, 56 subpixels).
    private static final short GRAVITY = 0x38;

    @Test
    void radialBounceLaunchesPlayerAirborneAwayFromBumperAtRomVelocityMagnitude() {
        Sonic1BumperObjectInstance bumper = new Sonic1BumperObjectInstance(
                new ObjectSpawn(0x200, 0x200, 0x47, 0, 0, false, 0));
        bumper.setServices(new BumperStubServices());
        bumper.update(0, null);

        // Player directly below the bumper (touching it from underneath), grounded.
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1f0, (short) 0x210);
        player.setAirForTest(false);
        player.setPushing(true);
        player.setJumping(true);
        player.setRollingJump(true);

        bumper.onTouchResponse(player,
                new TouchResponseResult(BUMPER_SIZE_INDEX, BUMPER_RADIUS, BUMPER_RADIUS, TouchCategory.SPECIAL), 0);
        // ROM Bump_Hit consumes obColProp the frame AFTER React_Special sets it
        // (docs/s1disasm/_incObj/sub ReactToItem.asm:377-427) -- one-frame latency.
        bumper.update(1, player);

        assertTrue(player.getAir(), "Bump_Hit sets Status_InAir (bset #1,obStatus)");
        assertFalse(player.getPushing(), "Bump_Hit clears Status_Push (bclr #5,obStatus)");
        assertFalse(player.getRollingJump(), "Bump_Hit clears roll-jumping (bclr #4,obStatus)");
        assertFalse(player.isJumping(), "Bump_Hit clears the jumping flag (clr.b objoff_3C)");

        double magnitude = Math.hypot(player.getXSpeed(), player.getYSpeed());
        // ROM: muls.w #-$700,sin/cos then asr.l #8 -- magnitude ~=0x700 (8-bit trig table rounding).
        assertTrue(Math.abs(magnitude - 0x700) <= 0x20,
                "bounce velocity magnitude should match ROM's -$700 radial strength, was " + magnitude);
        assertTrue(player.getYSpeed() > 0,
                "player below the bumper must bounce further down/away, not back up into it");
    }

    /**
     * Reconstructs the reported "SYZ spring feeds directly into a bumper" pinball
     * loop: an up-spring sits below a bumper. Sonic bounces off the spring, flies
     * into the bumper, gets knocked back down, and per the bug report can
     * eventually end up visually clipped INTO the spring's solid box instead of
     * relaunching cleanly.
     * <p>
     * This fixture drives the REAL {@code onSolidContact}/{@code onTouchResponse}
     * methods on both objects repeatedly. Per-frame standing/overlap
     * classification is a deliberately simplified geometric model of what
     * {@code ObjectSolidContactController.resolveContactInternal} actually does
     * for a FULL_SOLID top landing: that method snaps the player's Y to the
     * clean surface position ({@code newCenterY = playerCenterY - distY + 3 -
     * adjustment}, then {@code player.setY(...)}) BEFORE ever invoking the
     * listener, for any accepted landing depth in its [0,16) window
     * (ObjectSolidContactController.java:3871-3916, citing s2.asm:35298's
     * {@code cmpi.w #$10,d3 / blo Solid_Landed}). This fixture reproduces that
     * snap-then-callback contract directly rather than re-deriving the
     * classifier, so the test isolates exactly what Sonic1SpringObjectInstance's
     * own onSolidContact can and cannot do to Sonic's position across repeated
     * cycles.
     * <p>
     * Player X is pinned to the spring/bumper's shared X so the fixture
     * isolates the vertical bounce loop (the reported symptom is about vertical
     * clipping into the spring, not horizontal drift).
     */
    @Test
    void springBumperPinballLoopNeverGrowsPlayersEmbedDepthIntoSpringSolid() {
        final int sharedX = 0x200;
        final int springY = 0x400;
        final int bumperY = 0x300; // 256px above the spring -- within the up-spring's launch arc

        Sonic1SpringObjectInstance spring = new Sonic1SpringObjectInstance(
                new ObjectSpawn(sharedX, springY, 0x41, 0x00, 0, false, 0));
        spring.setServices(new StubObjectServices());

        Sonic1BumperObjectInstance bumper = new Sonic1BumperObjectInstance(
                new ObjectSpawn(sharedX, bumperY, 0x47, 0, 0, false, 0));
        bumper.setServices(new BumperStubServices());

        SolidObjectParams params = spring.getSolidParams();
        int restingCentreY = springY - params.groundHalfHeight() - 10; // approx standing on the spring's top

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) sharedX, (short) 0);
        player.setCentreX((short) sharedX);
        player.setCentreY((short) restingCentreY);
        player.setAirForTest(false);
        player.setYSpeed((short) 0);

        SolidContact standing = new SolidContact(true, false, false, true, false);
        TouchResponseResult bumperTouch =
                new TouchResponseResult(BUMPER_SIZE_INDEX, BUMPER_RADIUS, BUMPER_RADIUS, TouchCategory.SPECIAL);

        int landingWindow = 16; // matches the ROM's own d3=0x10 accepted-landing depth
        int maxObservedDepthPastRest = Integer.MIN_VALUE;
        int springTriggerCount = 0;
        int bumperTouchCount = 0;

        for (int frame = 1; frame <= 800 && springTriggerCount < 6; frame++) {
            if (player.getAir()) {
                player.setYSpeed((short) (player.getYSpeed() + GRAVITY));
            }
            player.move(player.getXSpeed(), player.getYSpeed());
            player.setCentreX((short) sharedX); // isolate the vertical loop

            spring.update(frame, player);
            bumper.update(frame, player); // consumes any bounce armed by a prior frame's touch

            boolean overlapsBumper = Math.abs(player.getCentreY() - bumperY) <= BUMPER_RADIUS + 12;
            if (overlapsBumper) {
                bumper.onTouchResponse(player, bumperTouch, frame);
                bumperTouchCount++;
            }

            boolean withinLandingWindow = Math.abs(player.getCentreY() - restingCentreY) <= landingWindow
                    && player.getYSpeed() >= 0;
            if (withinLandingWindow && spring.isSolidFor(player)) {
                // Mirror ObjectSolidContactController's pre-callback surface snap
                // (see method Javadoc) instead of re-deriving its distY math here.
                player.setCentreY((short) restingCentreY);
                spring.onSolidContact(player, standing, frame);
                int depthPastRest = player.getCentreY() - restingCentreY;
                maxObservedDepthPastRest = Math.max(maxObservedDepthPastRest, depthPastRest);
                springTriggerCount++;
            }
        }

        assertTrue(springTriggerCount >= 2,
                "fixture must exercise the spring -> bumper -> spring loop at least twice; only "
                        + springTriggerCount + " spring triggers (bumper touches: " + bumperTouchCount + ")");
        // ROM only ever pushes Sonic 8px INTO the spring on a genuine landing
        // (addq.w #8,obY -- s1disasm/_incObj/41 Springs.asm:91) before immediately
        // launching him back out. That fixed offset must never grow across
        // repeated pinball cycles -- it is object-local state, not accumulated.
        assertEquals(8, maxObservedDepthPastRest,
                "spring must not let Sonic sink deeper than its own ROM-intentional 8px push-in "
                        + "across repeated bounce cycles");
    }

    /** Bumper's awardPoints() calls services().gameState().addScore(...) unconditionally. */
    private static final class BumperStubServices extends StubObjectServices {
        private final GameStateManager gameState = new GameStateManager();

        @Override
        public GameStateManager gameState() {
            return gameState;
        }
    }
}
