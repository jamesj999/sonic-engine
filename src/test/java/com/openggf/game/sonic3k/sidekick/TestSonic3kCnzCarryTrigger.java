package com.openggf.game.sonic3k.sidekick;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kCnzCarryTrigger {

    private final Sonic3kCnzCarryTrigger trigger = new Sonic3kCnzCarryTrigger();

    private static final int ZONE_AIZ = 0;
    private static final int ZONE_HCZ = 1;
    private static final int ZONE_MGZ = 2;
    private static final int ZONE_CNZ = 3;
    private static final int ZONE_MHZ = 7;

    @Test
    void cnzAct1SonicAndTailsFires() {
        assertTrue(trigger.shouldEnterCarry(ZONE_CNZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void cnzAct1SonicAloneFires() {
        // ROM SpawnLevelMainSprites loc_68D8 spawns a throwaway carry-in Tails for
        // solo Sonic (Player_mode==1) at CNZ1; the trigger fires for it too.
        assertTrue(trigger.shouldEnterCarry(ZONE_CNZ, 0, PlayerCharacter.SONIC_ALONE));
    }

    @Test
    void mhzAct1SonicAndTailsFires() {
        // ROM Tails CPU loc_13A8E sends MHZ1 Sonic+Tails into the same carry
        // routine as CNZ1, but with the MHZ pickup coordinates.
        assertTrue(trigger.shouldEnterCarry(ZONE_MHZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void mhzAct1SonicAloneFires() {
        assertTrue(trigger.shouldEnterCarry(ZONE_MHZ, 0, PlayerCharacter.SONIC_ALONE));
    }

    @Test
    void cnzAct1TailsAloneDoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_CNZ, 0, PlayerCharacter.TAILS_ALONE));
    }

    @Test
    void cnzAct1KnucklesDoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_CNZ, 0, PlayerCharacter.KNUCKLES));
    }

    @Test
    void cnzAct2DoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_CNZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void mhzAct2DoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_MHZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void aizAct1SonicAndTailsDoesNotFire() {
        // CRITICAL: protects AIZ from spurious carry-mode triggering
        assertFalse(trigger.shouldEnterCarry(ZONE_AIZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void hczAct1SonicAndTailsDoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_HCZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void mgzAct1SonicAndTailsDoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_MGZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void romConstantsMatch() {
        assertEquals(Sonic3kConstants.CARRY_DESCEND_OFFSET_Y, trigger.carryDescendOffsetY());
        assertEquals(Sonic3kConstants.CARRY_INIT_TAILS_X_VEL, trigger.carryInitXVel());
        assertEquals(Sonic3kConstants.CARRY_INPUT_INJECT_MASK, trigger.carryInputInjectMask());
        assertEquals(Sonic3kConstants.CARRY_COOLDOWN_JUMP_RELEASE,
                trigger.carryJumpReleaseCooldownFrames());
        assertEquals(Sonic3kConstants.CARRY_COOLDOWN_LATCH_RELEASE,
                trigger.carryLatchReleaseCooldownFrames());
        assertEquals(Sonic3kConstants.CARRY_RELEASE_JUMP_Y_VEL, trigger.carryReleaseJumpYVel());
        assertEquals(Sonic3kConstants.CARRY_RELEASE_JUMP_X_VEL, trigger.carryReleaseJumpXVel());
    }
}
