package com.openggf.sprites.managers;

public interface SpriteMovementManager {

	public void handleMovement(boolean up, boolean down, boolean left, boolean right, boolean space,
			boolean testKey, boolean speedUp, boolean slowDown);

	default void resetTransientState() {
	}

	/**
	 * Clears the manager's copy of the ROM {@code tilt}/{@code next_tilt} SST
	 * bytes. All three level inits zero the whole player SST, so the angle
	 * bytes a fresh act's first balance check reads are 0, never a value
	 * carried over from the previous act:
	 * <ul>
	 * <li>S3K {@code Level:} -&gt; {@code clearRAM Object_RAM,...}
	 * (sonic3k.asm:7504, :7619), {@code Player_1} first slot
	 * (sonic3k.constants.asm:303-304)</li>
	 * <li>S2 {@code Level_ClrRam} {@code clearRAM Object_RAM,...}
	 * (s2.asm:4806-4808), {@code MainCharacter} first slot
	 * (s2.constants.asm:1096-1101)</li>
	 * <li>S1 {@code Level_ClrRam} {@code clearRAM v_objspace}
	 * (s1disasm/sonic.asm:2739-2740), {@code v_player} at offset 0
	 * (s1disasm/_Variables.asm:43, :53)</li>
	 * </ul>
	 */
	default void resetGroundAngleLatches() {
	}

}
