package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.audio.Sonic1SmpsConstants;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TODO #7 -- LZ Rumbling SFX.
 *
 * <p>The Labyrinth Zone water routines play sfx_Rumbling ($B7) when wall blocks
 * collapse in the water areas. The Final Zone boss also plays this sound when
 * the boss triggers the rumbling phase.
 *
 * <p>Disassembly references:
 * <ul>
 *   <li>{@code docs/s1disasm/Constants.asm:227} - {@code sfx_Rumbling equ ... sfx__First}
 *       (resolves to $B7)</li>
 *   <li>{@code docs/s1disasm/_inc/LZWaterFeatures.asm:174} - {@code move.w #sfx_Rumbling,d0}</li>
 *   <li>{@code docs/s1disasm/_inc/DynamicLevelEvents.asm:202} - rumbling in LZ3 event</li>
 *   <li>{@code docs/s1disasm/_incObj/85 Boss - Final.asm:158} - FZ boss rumble</li>
 * </ul>
 *
 * <p>The SFX ID $B7 is the 24th SFX (0xB7 - 0xA0 = 0x17 = 23, zero-indexed).
 * Its sound data is defined at {@code SoundB7} in s1.sounddriver.asm:2831.
 */
public class TestTodo7_LZRumblingSfx {

    /**
     * Expected SFX ID for sfx_Rumbling, from s1disasm Constants.asm:227.
     * sfx__First = $A0, sfx_Rumbling is the 24th entry = $B7.
     */
    private static final int SFX_RUMBLING = 0xB7;

    @Test
    public void testRumblingSfxHasValidPriority() {
        // The sound priority table should have an entry for sfx_Rumbling.
        // From s1disasm: SFX $B0-$BF priorities are in the 0x60-0x70 range.
        // sfx_Rumbling ($B7) index = $B7 - $A0 = $17 = 23 in the SFX block,
        // which is priority row $B0-$BF (second SFX block).
        int priority = Sonic1SmpsConstants.getSfxPriority(SFX_RUMBLING);
        assertTrue(priority > 0, "sfx_Rumbling priority should be > 0");
        // From the SOUND_PRIORITIES table: $B7 is at index $B7-$81 = 0x36 = 54
        // In the $B0-$BF block: 0x70, 0x60, 0x70, 0x60, 0x70, 0x70, 0x70, 0x70,
        // Index within block: $B7-$B0 = 7 -> priority = 0x70
        assertEquals(0x70, priority, "sfx_Rumbling priority should be 0x70");
    }

}


