package uk.co.jamesj999.sonic.configuration;

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
	VERSION("Alpha V0.01"),
	/**
	 * Current width of the screen.
	 */
	SCREEN_WIDTH(1280),
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
	FPS(60);
	
	private Object value;

	private SonicConfiguration(final Object value) {
		this.value = value;
	}

	protected Object getValue() {
		return value;
	}

}
