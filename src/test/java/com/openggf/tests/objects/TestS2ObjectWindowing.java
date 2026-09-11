package com.openggf.tests.objects;

import static org.junit.jupiter.api.Assertions.*;
import com.openggf.game.sonic2.objects.S2ObjectWindowing;
import com.openggf.level.objects.ObjectManager;
import org.junit.jupiter.api.Test;

class TestS2ObjectWindowing {

    @Test
    void loadBaseIsCameraMaskedWithoutSubtract() {
        // camRounded = Camera_X_pos & $FF80 (NO -$80 on the load base) — s2.asm:33026
        assertEquals(0x1500, S2ObjectWindowing.loadCoarse(0x1540));
        assertEquals(0x1500, S2ObjectWindowing.loadCoarse(0x157F));
    }

    @Test
    void unloadCoarseSubtracts80FirstThenMasks() {
        // Camera_X_pos_coarse = (Camera_X_pos - $80) & $FF80 — s2.asm MarkObjGone base
        assertEquals(0x1480, S2ObjectWindowing.unloadCoarse(0x1540)); // (0x1540-0x80)&0xFF80 = 0x14C0&0xFF80=0x1480
        assertEquals(0x1500, S2ObjectWindowing.unloadCoarse(0x1580)); // (0x1580-0x80)&0xFF80 = 0x1500
    }

    @Test
    void forwardLoadEdgeIsCoarsePlus280_trimEdgeIsCoarseMinus80() {
        int cam = 0x1500; // already chunk-aligned
        assertEquals(0x1500 + 0x280, S2ObjectWindowing.forwardLoadEdge(cam));
        assertEquals(0x1500 - 0x80,  S2ObjectWindowing.leftTrimEdge(cam));
        // NOT camRounded - 0x300:
        assertNotEquals(0x1500 - 0x300, S2ObjectWindowing.leftTrimEdge(cam));
    }

    @Test
    void backwardLoadEdgeIsCoarseMinus80_rightTrimEdgeIsCoarsePlus280() {
        int cam = 0x1500;
        assertEquals(0x1500 - 0x80,  S2ObjectWindowing.backwardLoadEdge(cam));
        assertEquals(0x1500 + 0x280, S2ObjectWindowing.rightTrimEdge(cam));
        assertNotEquals(0x1500 + 0x300, S2ObjectWindowing.rightTrimEdge(cam));
    }

    @Test
    void markObjGone_firstDeleteBucketIs300_native() {
        // (x & $FF80) - Camera_X_pos_coarse > $280  ⇒ first deleting bucket = $300 (value is $80-coarse)
        int cam = 0x1500;            // unloadCoarse(0x1500) = (0x1500-0x80)&0xFF80 = 0x1480
        int base = S2ObjectWindowing.unloadCoarse(cam); // 0x1480
        // object exactly at base + $280 → compare == $280 → NOT > $280 → stays
        assertFalse(S2ObjectWindowing.markObjGone(base + 0x280, cam));
        // object at base + $300 (next $80 bucket) → > $280 → delete
        assertTrue(S2ObjectWindowing.markObjGone(base + 0x300, cam));
    }

    // ---- Task 1.4b: directional load cursor consumes S2ObjectWindowing final boundaries ----

    @Test
    void s2ForwardScanLoadBoundaryIsExclusive() {
        // ROM ObjectsManager_GoingForward (s2.asm:33099-33104): addi.w #$280,d6;
        // cmp.w (a0),d6; bls.s .done -> loads while d6 > objX, i.e. objX < forwardLoadEdge.
        // A spawn exactly AT forwardLoadEdge does NOT load; forwardLoadEdge-1 loads.
        int cam = 0x1500;
        int edge = S2ObjectWindowing.forwardLoadEdge(cam); // 0x1780
        int leftTrim = S2ObjectWindowing.leftTrimEdge(cam); // 0x1480
        assertEquals(0x1780, edge);
        assertEquals(0x1480, leftTrim);

        // Spawns probing the forward edge plus a left-of-trim spawn.
        int[] spawnXs = {0x1480, 0x1500, edge - 1, edge, edge + 1};
        // Establish a window one chunk back, then scroll forward into cam (forward scan).
        int[] cameraSeq = {0x1400, cam};
        int[] active = ObjectManager.runWindowingScanForTest(spawnXs, cameraSeq, S2ObjectWindowing.INSTANCE);

        // edge-1 loads (strictly below edge); edge and edge+1 do NOT (exclusive).
        // 0x1480 == leftTrim survives (trim is x < leftTrim); 0x1500 in window.
        assertArrayEquals(new int[] {0x1480, 0x1500, edge - 1}, active,
                "forward load edge must be exclusive: spawn AT forwardLoadEdge must not load");
    }

    @Test
    void s2ReverseScanRightTrimRemovesEdgeSpawn() {
        // ROM ObjectsManager_GoingBackward (s2.asm:33076-33081): right trim edge =
        // loadCoarse + $280; cmp.w -6(a0),d6; bgt.s .done -> trims while prevX >= rightTrimEdge.
        int forwardCam = 0x1500;
        int reverseCam = 0x1400;
        int fwdEdge = S2ObjectWindowing.forwardLoadEdge(forwardCam); // 0x1780
        int rightTrim = S2ObjectWindowing.rightTrimEdge(reverseCam); // 0x1680
        int backLoad = S2ObjectWindowing.backwardLoadEdge(reverseCam); // 0x1380
        assertEquals(0x1680, rightTrim);
        assertEquals(0x1380, backLoad);

        int[] spawnXs = {0x1480, 0x1500, fwdEdge - 1};
        // Forward into 0x1500 (loads fwdEdge-1 = 0x177F), then reverse back to 0x1400.
        int[] cameraSeq = {0x1400, forwardCam, reverseCam};
        int[] active = ObjectManager.runWindowingScanForTest(spawnXs, cameraSeq, S2ObjectWindowing.INSTANCE);

        // On reverse, the right cursor trims spawns at/beyond rightTrimEdge (0x1680):
        // 0x177F >= 0x1680 -> trimmed. 0x1480, 0x1500 stay (both < 0x1680, > backLoad).
        assertArrayEquals(new int[] {0x1480, 0x1500}, active,
                "reverse right-trim must drop the spawn at/beyond rightTrimEdge");
    }

    @Test
    void chkLoadObj_alreadyLoadedContinuesScan_onlyFullStops() {
        // respawn bit already set (object already loaded) → CONTINUE (advance list ptr by 6), success
        assertEquals(S2ObjectWindowing.LoadOutcome.ALREADY_LOADED_CONTINUE,
                S2ObjectWindowing.chkLoadObj(/*respawnBitAlreadySet*/ true, /*slotAllocatable*/ false));
        // not yet loaded + a free slot → LOADED
        assertEquals(S2ObjectWindowing.LoadOutcome.LOADED,
                S2ObjectWindowing.chkLoadObj(false, true));
        // not loaded + SST full → STOP (only allocation failure halts the scan)
        assertEquals(S2ObjectWindowing.LoadOutcome.SST_FULL_STOP,
                S2ObjectWindowing.chkLoadObj(false, false));
    }
}
