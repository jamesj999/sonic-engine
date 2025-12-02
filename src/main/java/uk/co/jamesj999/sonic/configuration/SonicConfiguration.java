package uk.co.jamesj999.sonic.configuration;

import java.awt.event.KeyEvent;

/**
 * All configurable properties are put here. Eventually, these will be loaded
 * from a file. Use SonicConfigurationSerivce to retrieve the values for
 * these properties. This way, the service can eventually populate the options
 * from the file.
 * 
 * 
 * 
 * @author james
 * 
 */
public enum SonicConfiguration {
	/**
	 * Current Version number.
	 */
	VERSION,
	/**
	 * Actual width of the screen (number of available x-coordinates).
	 */
	SCREEN_WIDTH_PIXELS,
	/**
	 * Actual height of the screen (number of available y-coordinates).
	 */
	SCREEN_HEIGHT_PIXELS,
	/**
	 * Current width of the screen.
	 */
	SCREEN_WIDTH,
	/**
	 * Current height of the screen.
	 */
	SCREEN_HEIGHT,
	/**
	 * Scale used with BufferedImage TODO: Work out what this does
	 */
	SCALE,
	/**
	 * Frames per second to render. Will make the game faster/slower!
	 */
	FPS,
	/*
	 * ALWAYS DEFINE BUTTONS IN THE ORDER: UP, DOWN, LEFT, RIGHT. NOT FOR ANY
	 * TECHNICAL REASON, JUST BECAUSE LEVEL SELECT.
	 */
	/**
	 * Key to look up.
	 */
	UP,
	/**
	 * Key to crouch/roll.
	 */
	DOWN,
	/**
	 * Key to move Sonic left.
	 */
	LEFT,
	/**
	 * Key to move Sonic right.
	 */
	RIGHT,
	/**
	 * Key to jump etc.
	 */
	JUMP,

	/**
	 * Test button only used in debug
	 */
	TEST,

	/**
	 * Test button for next act
	 */
	NEXT_ACT,

	/**
	 * Test button for next zone
	 */
	NEXT_ZONE,

	/**
	 * Code of the sprite of the main playable character.
	 */
	MAIN_CHARACTER_CODE,
    /**
     * Whether to display debugging information on screen.
     */
    DEBUG_VIEW_ENABLED,

	/**
	 * Whether to display debugging collision information on screen.
	 */
	DEBUG_COLLISION_VIEW_ENABLED,

	/**
	 * Filename of ROM to use (temporary)
	 */
	ROM_FILENAME,

	/**
	 * Whether to enable Debug Movement Mode
	 */
	DEBUG_MODE,

	/**
	 * Whether to enable Audio (Music/SFX)
	 */
	AUDIO_ENABLED;

}
