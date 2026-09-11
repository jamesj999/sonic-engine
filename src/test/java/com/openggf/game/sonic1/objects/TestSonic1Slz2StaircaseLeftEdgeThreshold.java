package com.openggf.game.sonic1.objects;

import com.openggf.game.GameServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression characterization for a bug report describing "SLZ2 at X5611 Y58
 * has a staircase with a single blocking pixel — Sonic running across it
 * gets stopped by a 1px obstruction that shouldn't block him."
 *
 * <p>The reported staircase is Object 0x5B (Staircase) at SLZ act 2,
 * placement index 104 (x=5648, y=112, subtype=0x00, flags=0x01), found via
 * {@code ObjectManager.getAllSpawns()} — the closest real placement to the
 * reported (approximate, likely top-left-corner) X5611/Y58 coordinate.
 *
 * <p><b>ROM verdict: rom-confirmed-intentional, not an engine defect.</b>
 * The recorded S1 REV01 complete-run trace for SLZ act 2
 * ({@code src/test/resources/traces/s1/slz2_completerun/physics.csv.gz})
 * shows the SAME 1px oscillation trap in the real ROM: from frame 0x05D4
 * through 0x063E (~106 frames) with only the right d-pad bit held
 * (input=0x0008), Sonic's x oscillates in the narrow window
 * [0x15F5,0x15F8] (5621-5624) and y toggles between 77/78/82 without ever
 * advancing past the staircase's left edge. The recorded route only clears
 * it once a jump is added to the held input (frame 0x063F, input=0x0018,
 * y_speed goes negative and air=1) — i.e. the ROM staircase piece's left
 * edge genuinely cannot be walked onto at low approach speed; it must be
 * jumped onto. This is the classic ROM "SolidObject_SideAir barely-poking"
 * boundary (docs/s1disasm/_incObj/sub SolidObject.asm:181-184,211-214):
 * the piece's top surface sits close enough above the approach ground that
 * the vertical penetration teeters exactly on the &lt;=4px "walk over it"
 * threshold, and the ground's own natural 1px height quantization on
 * approach flips the classification frame to frame, trapping a
 * jump-less run in place — in both ROM and the engine.
 *
 * <p>This test locks in that ROM-matching engine behavior (bounded-window
 * trap while only holding right; a jump clears it) so a future change to
 * {@code ObjectSolidContactController}'s SideAir/LeftRight threshold does
 * not silently regress it back to either "always blocks" or "never blocks".
 */
@RequiresRom(SonicGame.SONIC_1)
class TestSonic1Slz2StaircaseLeftEdgeThreshold {
    private static final int ZONE_SLZ = 4;
    private static final int ACT_2 = 1;
    private static final int STAIRCASE_PIECE0_X = 5648;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_SLZ, ACT_2);
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
    }

    @Test
    void holdingOnlyRightOscillatesInPlaceAtTheStaircaseLeftEdgeMatchingRom() {
        placeApproachingFromLeft();
        Sonic1StaircaseObjectInstance staircase = locateReportedStaircase();
        assertNotNull(staircase, "Expected the reported SLZ2 staircase near x=5648");

        int maxXReached = fixture.sprite().getCentreX();
        for (int frame = 0; frame < 150; frame++) {
            fixture.stepFrame(false, false, false, true, false); // hold right only
            maxXReached = Math.max(maxXReached, fixture.sprite().getCentreX());
        }

        // ROM (trace frames 0x05D4-0x063E) never advances past x=5624 while
        // only holding right; the piece's left edge (pieceX-halfWidth=5621)
        // acts as a genuine, ROM-matching wall at this approach height.
        assertTrue(maxXReached < STAIRCASE_PIECE0_X - 16,
                "Holding only right should NOT be able to walk onto the staircase's "
                        + "left edge without jumping (ROM traps at the same spot) — "
                        + "reached x=" + maxXReached);
    }

    @Test
    void addingAJumpClearsTheSameEdgeMatchingRomFrame0x063F() {
        placeApproachingFromLeft();
        Sonic1StaircaseObjectInstance staircase = locateReportedStaircase();
        assertNotNull(staircase, "Expected the reported SLZ2 staircase near x=5648");

        // Let the trap establish first (mirrors ROM holding right-only through
        // frame 0x063E before a jump is added at 0x063F).
        for (int frame = 0; frame < 40; frame++) {
            fixture.stepFrame(false, false, false, true, false);
        }

        boolean clearedEdge = false;
        for (int frame = 0; frame < 120; frame++) {
            fixture.stepFrame(false, false, false, true, true); // right + jump
            if (fixture.sprite().getCentreX() > STAIRCASE_PIECE0_X + 8) {
                clearedEdge = true;
                break;
            }
        }

        assertTrue(clearedEdge,
                "Jumping should clear the staircase left-edge trap, matching the ROM "
                        + "route's escape at trace frame 0x063F");
    }

    private void placeApproachingFromLeft() {
        fixture.sprite().setCentreX((short) 5560);
        fixture.sprite().setCentreY((short) 93); // staircase baseY(112) - yRadius(~19)
        fixture.sprite().setAir(false);
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.camera().updatePosition(true);
        GameServices.level().getObjectManager().reset(fixture.camera().getX());
    }

    private Sonic1StaircaseObjectInstance locateReportedStaircase() {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        for (int frame = 0; frame < 30; frame++) {
            for (ObjectInstance object : objectManager.getActiveObjects()) {
                if (object instanceof Sonic1StaircaseObjectInstance staircase
                        && staircase.getPieceX(0) == STAIRCASE_PIECE0_X) {
                    return staircase;
                }
            }
            fixture.stepFrame(false, false, false, false, false);
        }
        return null;
    }
}
