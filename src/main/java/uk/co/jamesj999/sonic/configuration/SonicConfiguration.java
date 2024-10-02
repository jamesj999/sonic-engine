package uk.co.jamesj999.sonic.configuration;

import java.awt.event.KeyEvent;

/**
 * All configurable properties are put here. Eventually, these will be loaded
 * from a file. Use {@link SonicConfigurationSerivce} to retrieve the values for
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
	VERSION("Alpha With ROM Loading Logic - V0.06"),
	/**
	 * Actual width of the screen (number of available x-coordinates).
	 */
	SCREEN_WIDTH_PIXELS(320),
	/**
	 * Actual height of the screen (number of available y-coordinates).
	 */
	SCREEN_HEIGHT_PIXELS(224),
	/**
	 * Current width of the screen.
	 */
	SCREEN_WIDTH(640),
	/**
	 * Current height of the screen.
	 */
	SCREEN_HEIGHT(480),
	/**
	 * Scale used with BufferedImage TODO: Work out what this does
	 */
	SCALE(1),
	/**
	 * Frames per second to render. Will make the game faster/slower!
	 */
	FPS(60),
	/*
	 * ALWAYS DEFINE BUTTONS IN THE ORDER: UP, DOWN, LEFT, RIGHT. NOT FOR ANY
	 * TECHNICAL REASON, JUST BECAUSE LEVEL SELECT.
	 */
	/**
	 * Key to look up.
	 */
	UP(KeyEvent.VK_UP),
	/**
	 * Key to crouch/roll.
	 */
	DOWN(KeyEvent.VK_DOWN),
	/**
	 * Key to move Sonic left.
	 */
	LEFT(KeyEvent.VK_LEFT),
	/**
	 * Key to move Sonic right.
	 */
	RIGHT(KeyEvent.VK_RIGHT),
	/**
	 * Key to jump etc.
	 */
	JUMP(KeyEvent.VK_SPACE),
	/**
	 * Code of the sprite of the main playable character.
	 */
	MAIN_CHARACTER_CODE("sonic"),
    /**
     * Whether to display debugging information on screen.
     */
    DEBUG_ENABLED(true),

	/**
	 * Filename of ROM to use (temporary)
	 */
	ROM_FILENAME("Sonic The Hedgehog 2 (W) (REV01) [!].gen");

	private Object value;

	private SonicConfiguration(final Object value) {
		this.value = value;
	}

	protected Object getValue() {
		return value;
	}

}
