package uk.co.jamesj999.sonic.configuration;

import org.lwjgl.glfw.GLFW;

/**
 * All configurable properties are put here. Eventually, these will be loaded
 * from a file. Use {@link SonicConfigurationService} to retrieve the values for
 * these properties. This way, the service can eventually populate the options
 * from the file.
 *
 * @author james
 */
public enum SonicConfiguration {
	/**
	 * Current Version number.
	 */
	VERSION("Alpha With ROM Loading Logic - V0.1"),
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
	UP(GLFW.GLFW_KEY_UP),
	/**
	 * Key to crouch/roll.
	 */
	DOWN(GLFW.GLFW_KEY_DOWN),
	/**
	 * Key to move Sonic left.
	 */
	LEFT(GLFW.GLFW_KEY_LEFT),
	/**
	 * Key to move Sonic right.
	 */
	RIGHT(GLFW.GLFW_KEY_RIGHT),
	/**
	 * Key to jump etc.
	 */
	JUMP(GLFW.GLFW_KEY_SPACE),

	/**
	 * Test button only used in debug
	 */
	TEST(GLFW.GLFW_KEY_Z),

	/**
	 * Code of the sprite of the main playable character.
	 */
	MAIN_CHARACTER_CODE("sonic"),
	/**
	 * Whether to display debugging information on screen.
	 */
	DEBUG_VIEW_ENABLED(true),

	/**
	 * Filename of ROM to use (temporary)
	 */
	ROM_FILENAME("Sonic The Hedgehog 2 (W) (REV01) [!].gen"),

	/**
	 * Whether to enable Debug Movement Mode
	 */
	DEBUG_MODE(true);

	private final Object value;

	private SonicConfiguration(final Object value) {
		this.value = value;
	}

	protected Object getValue() {
		return value;
	}

	public int getInt() {
		return (Integer) value;
	}

	public String getString() {
		return (String) value;
	}

	public boolean getBoolean() {
		return (Boolean) value;
	}
}
