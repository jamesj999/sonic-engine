package uk.co.jamesj999.sonic.graphics;

import javax.media.opengl.GL2;

public class GraphicsManager {
	private static GL2 graphics;

	public static GL2 getGraphics() {
		return graphics;
	}

	public static void setGraphics(GL2 graphicsgl) {
		graphics = graphicsgl;
	}
}
