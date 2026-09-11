package com.openggf.tests;

import com.openggf.game.PlayerCharacter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verify PlayerCharacter enum ordinals match S3K ROM Player_mode values.
 * ROM reference: docs/skdisasm/sonic3k.asm lines 8090-8101
 */
public class TestTodo14_PlayerModeValues {

    @Test
    public void testPlayerCharacterEnumOrdinalsMatchRom() {
        assertEquals(0, PlayerCharacter.SONIC_AND_TAILS.ordinal(), "SONIC_AND_TAILS should be Player_mode 0");
        assertEquals(1, PlayerCharacter.SONIC_ALONE.ordinal(), "SONIC_ALONE should be Player_mode 1");
        assertEquals(2, PlayerCharacter.TAILS_ALONE.ordinal(), "TAILS_ALONE should be Player_mode 2");
        assertEquals(3, PlayerCharacter.KNUCKLES.ordinal(), "KNUCKLES should be Player_mode 3");
    }

    @Test
    public void testPlayerCharacterCount() {
        assertEquals(4, PlayerCharacter.values().length, "Should have exactly 4 player modes");
    }

}


